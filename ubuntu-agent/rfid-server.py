#!/usr/bin/env python3
"""rfid-server.py — TCP-сервер Ubuntu-агента проекта Ubuntu-RFID-Android.

Слушает TCP-порт в локальной сети и принимает команды LOCK/UNLOCK/STATUS от
смартфона. Выполняет блокировку/разблокировку GNOME-сессии (X11) через
`loginctl` и отвечает подтверждением по тому же соединению.

Протокол: построчный JSON (одна команда — одна строка, разделитель '\n').
  Запрос:  {"cmd": "lock"|"unlock"|"status", "reqId": "<id>", "token": "<secret>"}
  Ответ:   {"reqId": "<id>", "status": "ok"|"error", "detail": "<text>"}

Аутентификация (MVP, этап 1): общий предварительный токен (pre-shared token).
Команды без верного токена отклоняются. Прикладное шифрование/HMAC — этап 2.
"""

from __future__ import annotations

import getpass
import json
import logging
import os
import socket
import socketserver
import subprocess
import sys
from datetime import datetime
from pathlib import Path

HOST = os.environ.get("RFID_BIND_HOST", "::")  # "::" = dual-stack: LAN IPv4 + Yggdrasil IPv6
PORT = int(os.environ.get("RFID_PORT", "5390"))
TOKEN = os.environ.get("RFID_TOKEN", "")  # пустой = проверка отключена (не рекомендуется)

STATE_DIR = Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local/state"))) / "rfid-agent"
LOG_FILE = STATE_DIR / "rfid-server.log"
STATE_DIR.mkdir(parents=True, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.FileHandler(LOG_FILE), logging.StreamHandler(sys.stderr)],
)
log = logging.getLogger("rfid-server")


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


def handle_command(payload: dict) -> dict:
    """Обработать одну команду и сформировать ответ."""
    req_id = str(payload.get("reqId", ""))
    cmd = str(payload.get("cmd", "")).lower()
    token = str(payload.get("token", ""))

    if TOKEN and token != TOKEN:
        log.warning("Отклонена команда с неверным токеном (cmd=%s reqId=%s)", cmd, req_id)
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
            return {"reqId": req_id, "status": "ok", "detail": f"locked={locked}"}
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
