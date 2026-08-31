#!/usr/bin/env python3
"""rfid-server.py — TCP-сервер Ubuntu-агента проекта Ubuntu-RFID-Android.

Слушает TCP-порт в локальной сети и принимает команды LOCK/UNLOCK/STATUS от
смартфона. Выполняет блокировку/разблокировку GNOME-сессии (X11) через
`loginctl` и отвечает подтверждением по тому же соединению.

Протокол: построчный JSON (одна команда — одна строка, разделитель '\n').
  Запрос:  {"cmd": "lock"|"unlock"|"status", "reqId": "<nonce>", "ts": <unix-сек>,
            "sig": "<hex HMAC-SHA256(token, "cmd|reqId|ts")>"}
  Ответ:   {"reqId": "<id>", "status": "ok"|"error", "detail": "<text>"}
  Ответ на status дополнительно несёт "lan": "<ip>" — текущий адрес ПК в
  локальной сети; телефон обновляет им профиль (адрес меняется по DHCP).

Аутентификация (этап 2, ТЗ 7.2): HMAC-подпись команды общим токеном.
Токен по сети не передаётся. Защита от повтора: ts в окне ±AUTH_WINDOW_S
и кэш использованных reqId (nonce). При пустом RFID_TOKEN проверка отключена
(только для отладки).

БЕЗОПАСНОСТЬ: криптографическая часть требует ревью человеком (A13).
"""

from __future__ import annotations

import getpass
import hashlib
import hmac
import json
import logging
import os
import socket
import socketserver
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

HOST = os.environ.get("RFID_BIND_HOST", "::")  # "::" = dual-stack: LAN IPv4 + Yggdrasil IPv6
PORT = int(os.environ.get("RFID_PORT", "5390"))
TOKEN = os.environ.get("RFID_TOKEN", "")  # пустой = проверка отключена (не рекомендуется)

AUTH_WINDOW_S = 300  # допустимый разбег часов телефона и ПК

STATE_DIR = Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local/state"))) / "rfid-agent"
LOG_FILE = STATE_DIR / "rfid-server.log"
STATE_DIR.mkdir(parents=True, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.FileHandler(LOG_FILE), logging.StreamHandler(sys.stderr)],
)
log = logging.getLogger("rfid-server")


def lan_ip() -> str:
    """IP ПК в локальной сети (адрес исходящего интерфейса)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("1.1.1.1", 80))
            return s.getsockname()[0]
    except OSError:
        return ""


def current_session_id() -> str:
    """Определить id графической сессии текущего пользователя.

    Колонки list-sessions зависят от версии systemd, а первой строкой может
    идти сессия Class=manager (systemd user manager), которая не умеет
    lock screen. Поэтому свойства каждой сессии запрашиваются явно и
    выбирается сессия Class=user нашего пользователя.
    """
    user = getpass.getuser()
    try:
        out = subprocess.run(
            ["loginctl", "list-sessions", "--no-legend"],
            capture_output=True, text=True, check=True,
        ).stdout
    except subprocess.CalledProcessError:
        return ""
    for line in out.splitlines():
        parts = line.split()
        if not parts:
            continue
        sid = parts[0]
        try:
            props = subprocess.run(
                ["loginctl", "show-session", sid, "-p", "Name", "-p", "Class", "--value"],
                capture_output=True, text=True, check=True,
            ).stdout.split()
        except subprocess.CalledProcessError:
            continue
        if len(props) >= 2 and props[0] == user and props[1] == "user":
            return sid
    return ""


def run_loginctl(action: str, sid: str) -> tuple[bool, str]:
    """Выполнить lock-session/unlock-session. Возвращает (успех, описание)."""
    try:
        subprocess.run(["loginctl", action, sid], check=True, capture_output=True, text=True)
        return True, f"{action} session={sid}"
    except subprocess.CalledProcessError as exc:
        return False, (exc.stderr or str(exc)).strip()


_seen_lock = threading.Lock()
_seen_req_ids: dict[str, float] = {}  # nonce (reqId) -> время приёма, анти-replay


def verify_signature(payload: dict) -> str:
    """Проверить HMAC-подпись команды. Возвращает "" (ок) или причину отказа."""
    if not TOKEN:
        return ""
    req_id = str(payload.get("reqId", ""))
    cmd = str(payload.get("cmd", "")).lower()
    sig = str(payload.get("sig", ""))
    try:
        ts = int(payload.get("ts", 0))
    except (TypeError, ValueError):
        return "bad-ts"
    now = time.time()
    if not req_id or not sig:
        return "missing-auth"
    if abs(now - ts) > AUTH_WINDOW_S:
        return "stale-ts"
    expected = hmac.new(
        TOKEN.encode("utf-8"), f"{cmd}|{req_id}|{ts}".encode("utf-8"), hashlib.sha256
    ).hexdigest()
    if not hmac.compare_digest(expected, sig.lower()):
        return "bad-signature"
    with _seen_lock:
        for stale in [k for k, v in _seen_req_ids.items() if now - v > AUTH_WINDOW_S]:
            del _seen_req_ids[stale]
        if req_id in _seen_req_ids:
            return "replay"
        _seen_req_ids[req_id] = now
    return ""


def handle_command(payload: dict) -> dict:
    """Обработать одну команду и сформировать ответ."""
    req_id = str(payload.get("reqId", ""))
    cmd = str(payload.get("cmd", "")).lower()

    reason = verify_signature(payload)
    if reason:
        # Причина — только в лог, клиенту единый ответ (не подсказывать атакующему).
        log.warning("Отклонена команда: %s (cmd=%s reqId=%s)", reason, cmd, req_id)
        return {"reqId": req_id, "status": "error", "detail": "unauthorized"}

    sid = current_session_id()
    if not sid:
        return {"reqId": req_id, "status": "error", "detail": "no-session"}

    if cmd == "lock":
        ok, detail = run_loginctl("lock-session", sid)
    elif cmd == "unlock":
        ok, detail = run_loginctl("unlock-session", sid)
    elif cmd == "status":
        try:
            locked = subprocess.run(
                ["loginctl", "show-session", sid, "-p", "LockedHint", "--value"],
                capture_output=True, text=True, check=True,
            ).stdout.strip()
            return {"reqId": req_id, "status": "ok", "detail": f"locked={locked}",
                    "lan": lan_ip()}
        except subprocess.CalledProcessError as exc:
            return {"reqId": req_id, "status": "error", "detail": str(exc)}
    else:
        return {"reqId": req_id, "status": "error", "detail": f"unknown-cmd:{cmd}"}

    log.info("%s reqId=%s -> %s (%s)", cmd.upper(), req_id or "none",
             "ok" if ok else "error", detail)
    return {"reqId": req_id, "status": "ok" if ok else "error", "detail": detail}


class Handler(socketserver.StreamRequestHandler):
    timeout = 30

    def handle(self) -> None:
        peer = self.client_address[0]
        log.info("Соединение от %s", peer)
        try:
            for raw in self.rfile:
                line = raw.decode("utf-8", "replace").strip()
                if not line:
                    continue
                try:
                    payload = json.loads(line)
                except json.JSONDecodeError:
                    self._send({"status": "error", "detail": "bad-json"})
                    continue
                response = handle_command(payload)
                self._send(response)
        except (ConnectionError, socket.timeout) as exc:
            log.info("Соединение с %s завершено: %s", peer, exc)

    def _send(self, obj: dict) -> None:
        data = (json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")
        self.wfile.write(data)
        self.wfile.flush()


class Server(socketserver.ThreadingTCPServer):
    address_family = socket.AF_INET6 if ":" in HOST else socket.AF_INET
    allow_reuse_address = True
    daemon_threads = True

    def server_bind(self) -> None:
        if self.address_family == socket.AF_INET6:
            # Принимать и IPv4 (LAN), и IPv6 (Yggdrasil) на одном сокете.
            self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        super().server_bind()


def main() -> int:
    log.info("Старт RFID TCP-сервера на %s:%d (auth=%s)",
             HOST, PORT, "on" if TOKEN else "OFF")
    with Server((HOST, PORT), Handler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            log.info("Остановка по сигналу")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
