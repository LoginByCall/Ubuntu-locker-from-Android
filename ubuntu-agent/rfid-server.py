#!/usr/bin/env python3
"""rfid-server.py — TCP-сервер Ubuntu-агента проекта Ubuntu-RFID-Android.

Слушает TCP-порт в локальной сети и принимает команды LOCK/UNLOCK/STATUS от
смартфона. Выполняет блокировку/разблокировку GNOME-сессии (X11) через
`loginctl` и отвечает подтверждением по тому же соединению.

Протокол: построчный JSON (одна команда — одна строка, разделитель '\n').
  Запрос:  {"cmd": "lock"|"unlock"|"status"|"ask"|"confirm"|"register",
            "reqId": "<nonce>", "ts": <unix-сек>,
            "sig": "<hex HMAC-SHA256(token, "cmd|reqId|ts")>"}
  Ответ:   {"reqId": "<id>", "status": "ok"|"error", "detail": "<text>"}
  Ответ на status дополнительно несёт "lan": "<ip>" — текущий адрес ПК в
  локальной сети; телефон обновляет им профиль (адрес меняется по DHCP).

Подтверждение действий на телефоне (ask/confirm/register):
  ask      — локальный запрос с ПК (sudo и т. п.): «спроси у телефона». Сервер
             будит приложение push-уведомлением (FCM) и блокируется до вердикта.
  confirm  — вердикт: {"askId": "<id ask>", "verdict": "approve"|"deny"|"cancel"};
             cancel шлёт сам ПК, когда пароль успели ввести в терминале.
  register — телефон сообщает свой FCM-токен для push.
Режимы питания (suspend/hibernate/poweroff) выполняются через systemctl; список
поддерживаемых этим ПК приходит в ответе на status полем "power" (спрашиваем
logind, а не гадаем), телефон показывает в меню плитки только их.
Тело этих команд входит в подпись (см. SIGNED_FIELDS), иначе вердикт можно было
бы подменить в пути.

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

AUTH_WINDOW_S = 300  # допустимый разбег часов телефона и ПК
MAX_CONNECTIONS = 32  # одновременных соединений; сверх — отказ (защита от исчерпания потоков)

CONFIG_DIR = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config"))) / "rfid-agent"
FCM_SA_FILE = CONFIG_DIR / "fcm-sa.json"      # ключ сервис-аккаунта Firebase (600)
FCM_TOKEN_FILE = CONFIG_DIR / "fcm-token"     # push-токен телефона (команда register)
PROFILE_ID_FILE = CONFIG_DIR / "profile-id"   # id профиля ПК (создаёт rfid-tray.py)
TOKEN_FILE = CONFIG_DIR / "token"             # общий секрет (600), см. install-server.sh
VERSION_FILE = CONFIG_DIR / "version"         # версия сборки, кладёт install-server.sh
ASK_TIMEOUT_MAX_S = 120


def version() -> str:
    """Версия сборки: VERSION из репозитория, иначе копия, снятая установщиком."""
    for path in (Path(__file__).resolve().parent.parent / "VERSION", VERSION_FILE):
        try:
            return path.read_text(encoding="utf-8").strip()
        except OSError:
            continue
    return "dev"


def load_token() -> str:
    """Токен: из файла 600 в конфиге; RFID_TOKEN — только для тестов.

    В юните systemd токена нет намеренно: файл юнита создаётся с правами 644 и
    читался бы любым локальным пользователем.
    """
    env = os.environ.get("RFID_TOKEN")
    if env is not None:
        return env
    try:
        return TOKEN_FILE.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


TOKEN = load_token()
VERSION = version()

# Поля тела, попадающие в подпись помимо "cmd|reqId|ts" (порядок важен).
SIGNED_FIELDS: dict[str, tuple[str, ...]] = {
    "confirm": ("askId", "verdict"),
    "register": ("fcm",),
}

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


# Режимы питания: имя команды -> (действие systemctl, метод logind «умеем ли»).
POWER_ACTIONS = {
    "suspend": ("suspend", "CanSuspend"),
    "hibernate": ("hibernate", "CanHibernate"),
    "poweroff": ("poweroff", "CanPowerOff"),
}


def power_capabilities() -> str:
    """Что этот ПК умеет из режимов питания: «suspend,hibernate,poweroff».

    Спрашиваем сам logind: он проверяет и ядро, и наличие подкачки под
    гибернацию, и права — гадать по /sys/power/state было бы хуже.
    Ответ не меняется при работе, поэтому считаем один раз.
    """
    able = []
    for name, (_, method) in POWER_ACTIONS.items():
        try:
            out = subprocess.run(
                ["busctl", "--system", "call", "org.freedesktop.login1",
                 "/org/freedesktop/login1", "org.freedesktop.login1.Manager", method],
                capture_output=True, text=True, timeout=5, check=True).stdout
        except (OSError, subprocess.SubprocessError):
            continue
        # Ответ вида: s "yes" | "no" | "na" | "challenge".
        # challenge — можно, но polkit спросит подтверждение у сидящего за ПК.
        if '"yes"' in out or '"challenge"' in out:
            able.append(name)
    return ",".join(able)


POWER_CAPABILITIES = power_capabilities()  # один раз при старте: список не меняется


def run_power(action: str) -> tuple[bool, str]:
    """Выполнить режим питания. Возвращает (успех, описание)."""
    if action not in POWER_CAPABILITIES.split(","):
        return False, f"unsupported:{action}"
    command = POWER_ACTIONS[action][0]
    try:
        # Ответ телефону успеет уйти: systemctl возвращается сразу, а сон
        # наступает уже после. Для poweroff это тоже верно.
        subprocess.run(["systemctl", command], check=True, capture_output=True, text=True)
        return True, action
    except (OSError, subprocess.CalledProcessError) as exc:
        detail = getattr(exc, "stderr", "") or str(exc)
        return False, detail.strip()[:200]


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
    parts = [cmd, req_id, str(ts)]
    parts += [str(payload.get(f, "")) for f in SIGNED_FIELDS.get(cmd, ())]
    expected = hmac.new(
        TOKEN.encode("utf-8"), "|".join(parts).encode("utf-8"), hashlib.sha256
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


def sign_reply(resp: dict) -> dict:
    """Подписать ответ тем же токеном: reqId|status|detail|lan.

    Без этого посредник в общей сети мог бы подсунуть телефону чужой ответ —
    например, поле "lan" с адресом атакующего или ложное "locked=yes".
    reqId в подписи привязывает ответ к конкретному запросу.
    """
    if not TOKEN:
        return resp
    parts = [str(resp.get(f, "")) for f in ("reqId", "status", "detail", "lan", "power")]
    resp["sig"] = hmac.new(
        TOKEN.encode("utf-8"), "|".join(parts).encode("utf-8"), hashlib.sha256
    ).hexdigest()
    return resp


# --- Подтверждение действий на телефоне ------------------------------------

_pending_lock = threading.Lock()
_pending: dict[str, dict] = {}  # askId -> {"event": Event, "verdict": str}


def send_push(ask_id: str, prompt: str, timeout_s: int) -> str:
    """Разбудить телефон push-уведомлением. Возвращает "" (ок) или причину.

    В push уходит только идентификатор запроса и текст приглашения — вердикт
    возвращается по этому же каналу с HMAC-подписью, поэтому подделать его,
    имея доступ к push-сервису, нельзя.
    """
    try:
        device_token = FCM_TOKEN_FILE.read_text(encoding="utf-8").strip()
    except OSError:
        return "телефон не зарегистрирован (нет команды register)"
    if not device_token:
        return "телефон не зарегистрирован"
    if not FCM_SA_FILE.is_file():
        return f"нет ключа Firebase: {FCM_SA_FILE}"
    try:
        import requests
        from google.oauth2 import service_account
        from google.auth.transport.requests import Request as GoogleRequest
    except ImportError:
        return "нет google-auth/requests (см. install-server.sh)"

    creds = service_account.Credentials.from_service_account_file(
        str(FCM_SA_FILE), scopes=["https://www.googleapis.com/auth/firebase.messaging"]
    )
    creds.refresh(GoogleRequest())
    project = json.loads(FCM_SA_FILE.read_text(encoding="utf-8"))["project_id"]
    body = {"message": {
        "token": device_token,
        # data-only + high: приложение само рисует уведомление с кнопками,
        # high пробивает doze (иначе телефон в кармане ответит через полчаса).
        "android": {"priority": "high"},
        "data": {
            "type": "confirm",
            "askId": ask_id,
            "prompt": prompt,
            "host": socket.gethostname(),
            # id профиля: телефон по нему находит, какому ПК слать вердикт.
            "pcId": PROFILE_ID_FILE.read_text(encoding="utf-8").strip()
                    if PROFILE_ID_FILE.is_file() else "",
            "expires": str(int(time.time()) + timeout_s),
        },
    }}
    resp = requests.post(
        f"https://fcm.googleapis.com/v1/projects/{project}/messages:send",
        json=body, headers={"Authorization": f"Bearer {creds.token}"}, timeout=10,
    )
    if resp.status_code != 200:
        return f"FCM {resp.status_code}: {resp.text[:200]}"
    return ""


def ask_phone(ask_id: str, prompt: str, timeout_s: int) -> tuple[bool, str]:
    """Спросить подтверждение у телефона и дождаться вердикта (блокирующе)."""
    event = threading.Event()
    with _pending_lock:
        _pending[ask_id] = {"event": event, "verdict": ""}
    try:
        error = send_push(ask_id, prompt, timeout_s)
        if error:
            log.warning("ASK %s: push не ушёл: %s", ask_id, error)
            return False, f"push-error: {error}"
        log.info("ASK %s: жду вердикт (%s, %d c)", ask_id, prompt, timeout_s)
        if not event.wait(timeout_s):
            return False, "timeout"
        with _pending_lock:
            verdict = _pending[ask_id]["verdict"]
        return verdict == "approve", verdict
    finally:
        with _pending_lock:
            _pending.pop(ask_id, None)


def resolve_ask(ask_id: str, verdict: str) -> tuple[bool, str]:
    """Принять вердикт от телефона и разбудить ожидающий ask."""
    with _pending_lock:
        entry = _pending.get(ask_id)
        if entry is None:
            return False, "unknown-ask"  # истёк по таймауту или чужой id
        # cancel — пароль уже введён в терминале, запрос снят самим ПК.
        entry["verdict"] = verdict if verdict in ("approve", "deny", "cancel") else "deny"
        entry["event"].set()
    return True, "accepted"


def handle_command(payload: dict, peer: str = "") -> dict:
    """Обработать одну команду и сформировать ответ."""
    req_id = str(payload.get("reqId", ""))
    cmd = str(payload.get("cmd", "")).lower()

    reason = verify_signature(payload)
    if reason:
        # Причина — только в лог, клиенту единый ответ (не подсказывать атакующему).
        log.warning("Отклонена команда: %s (cmd=%s reqId=%s)", reason, cmd, req_id)
        return {"reqId": req_id, "status": "error", "detail": "unauthorized"}

    sessionless = ("ask", "confirm", "register", *POWER_ACTIONS)
    sid = "" if cmd in sessionless else current_session_id()
    if not sid and cmd not in sessionless:
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
                    "lan": lan_ip(), "power": POWER_CAPABILITIES}
        except subprocess.CalledProcessError as exc:
            return {"reqId": req_id, "status": "error", "detail": str(exc)}
    elif cmd == "ask":
        # Инициатор — процесс на самом ПК (sudo askpass, polkit и т. п.).
        # Снаружи такой запрос принимать незачем: он только шлёт push.
        if peer not in ("127.0.0.1", "::1", "::ffff:127.0.0.1"):
            return {"reqId": req_id, "status": "error", "detail": "ask-local-only"}
        timeout_s = max(5, min(int(payload.get("timeout", 60) or 60), ASK_TIMEOUT_MAX_S))
        ok, detail = ask_phone(req_id, str(payload.get("prompt", "")), timeout_s)
    elif cmd == "confirm":
        ok, detail = resolve_ask(str(payload.get("askId", "")), str(payload.get("verdict", "")))
    elif cmd in POWER_ACTIONS:
        ok, detail = run_power(cmd)
    elif cmd == "register":
        fcm = str(payload.get("fcm", "")).strip()
        if not fcm:
            return {"reqId": req_id, "status": "error", "detail": "no-fcm-token"}
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        FCM_TOKEN_FILE.write_text(fcm + "\n", encoding="utf-8")
        FCM_TOKEN_FILE.chmod(0o600)
        ok, detail = True, "registered"
    else:
        return {"reqId": req_id, "status": "error", "detail": f"unknown-cmd:{cmd}"}

    log.info("%s reqId=%s -> %s (%s)", cmd.upper(), req_id or "none",
             "ok" if ok else "error", detail)
    return {"reqId": req_id, "status": "ok" if ok else "error", "detail": detail}


_connections = threading.BoundedSemaphore(MAX_CONNECTIONS)


class Handler(socketserver.StreamRequestHandler):
    # Таймаут чтения: во время ask сервер не читает сокет, поэтому длинное
    # ожидание вердикта ему не мешает — а простаивающие соединения не копятся.
    timeout = 30

    def handle(self) -> None:
        peer = self.client_address[0]
        if not _connections.acquire(blocking=False):
            log.warning("Отказ %s: занято %d соединений", peer, MAX_CONNECTIONS)
            self._send({"status": "error", "detail": "busy"})
            return
        try:
            self._serve(peer)
        finally:
            _connections.release()

    def _serve(self, peer: str) -> None:
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
                response = handle_command(payload, peer)
                self._send(response)
        except (ConnectionError, socket.timeout) as exc:
            log.info("Соединение с %s завершено: %s", peer, exc)

    def _send(self, obj: dict) -> None:
        data = (json.dumps(sign_reply(obj), ensure_ascii=False) + "\n").encode("utf-8")
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
    if "--version" in sys.argv:
        print(VERSION)
        return 0
    if not TOKEN:
        # Fail-closed: без токена подпись не проверить, а слушаем мы всю LAN.
        log.error("Токен не найден (%s). Сервер не запущен: без подписи "
                  "команды принимать нельзя. Выполните install-server.sh.", TOKEN_FILE)
        return 2
    log.info("Старт RFID TCP-сервера %s на %s:%d", VERSION, HOST, PORT)
    with Server((HOST, PORT), Handler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            log.info("Остановка по сигналу")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
