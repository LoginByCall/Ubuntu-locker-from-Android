#!/usr/bin/env python3
"""rfid-confirm.py — подтверждение действия на ПК со смартфона.

Спрашивает у телефона «да/нет» через агента (команда ask) и отдаёт ответ
кодом возврата: 0 — подтверждено, 1 — отказано/таймаут, 2 — ошибка канала.

Применение:

  # универсально: любой скрипт, polkit-обёртка, деплой и т. п.
  rfid-confirm.py "Перезагрузить сервер?" && systemctl reboot

  # sudo: спрашивает ОБА канала сразу — терминал и телефон, кто быстрее
  export SUDO_ASKPASS=~/.local/bin/rfid-askpass
  sudo -A apt update

Пароль для --password берётся из связки ключей (secret-tool) или, если её нет,
из файла ~/.config/rfid-agent/sudo.pass (режим 600). Пароль НЕ передаётся по
сети — по сети идёт только вердикт, подписанный общим токеном.

БЕЗОПАСНОСТЬ: хранение пароля sudo на диске/в keyring по стойкости равно
NOPASSWD с внешним фактором в виде телефона. Требует ревью человеком (A13).
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import queue
import select
import socket
import subprocess
import sys
import termios
import threading
import time
import uuid
from pathlib import Path

CONF_DIR = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config"))) / "rfid-agent"
TOKEN_FILE = CONF_DIR / "token"
PASS_FILE = CONF_DIR / "sudo.pass"
PORT = int(os.environ.get("RFID_PORT", "5390"))


def token() -> str:
    return TOKEN_FILE.read_text(encoding="utf-8").strip() if TOKEN_FILE.is_file() else ""


def ask(prompt: str, timeout_s: int, req_id: str = "") -> tuple[bool, str]:
    """Спросить телефон через агента. Возвращает (подтверждено, детали)."""
    token_value = token()
    req_id, ts = req_id or str(uuid.uuid4()), int(time.time())
    sig = hmac.new(token_value.encode(), f"ask|{req_id}|{ts}".encode(),
                   hashlib.sha256).hexdigest()
    request = {"cmd": "ask", "reqId": req_id, "ts": ts, "sig": sig,
               "prompt": prompt, "timeout": timeout_s}
    try:
        with socket.create_connection(("127.0.0.1", PORT), 5) as sock:
            sock.settimeout(timeout_s + 15)
            sock.sendall((json.dumps(request, ensure_ascii=False) + "\n").encode())
            line = sock.makefile("r", encoding="utf-8").readline()
    except OSError as exc:
        return False, f"агент недоступен: {exc}"
    if not line:
        return False, "нет ответа от агента"
    resp = json.loads(line)
    return resp.get("status") == "ok", resp.get("detail", "")


def cancel_ask(ask_id: str) -> None:
    """Снять зависший запрос: пароль уже введён в терминале."""
    req_id, ts = str(uuid.uuid4()), int(time.time())
    sig = hmac.new(token().encode(), f"confirm|{req_id}|{ts}|{ask_id}|cancel".encode(),
                   hashlib.sha256).hexdigest()
    try:
        with socket.create_connection(("127.0.0.1", PORT), 3) as sock:
            sock.settimeout(5)
            sock.sendall((json.dumps({"cmd": "confirm", "reqId": req_id, "ts": ts,
                                      "sig": sig, "askId": ask_id,
                                      "verdict": "cancel"}) + "\n").encode())
            sock.makefile("r").readline()
    except OSError:
        pass  # агент уже отпустил запрос по таймауту — ничего страшного


def race_tty_and_phone(prompt: str, timeout_s: int) -> tuple[str, str]:
    """Спросить одновременно терминал и телефон; вернуть (источник, пароль).

    Источник: "tty" — пароль набран руками (он и возвращается), "phone" —
    подтверждено с телефона (пароль берётся из хранилища), "" — отказ/таймаут.
    Терминала может не быть (запуск из GUI/cron) — тогда только телефон.
    """
    try:
        tty_fd = os.open("/dev/tty", os.O_RDWR | os.O_NOCTTY)
    except OSError:
        ok, detail = ask(prompt, timeout_s)
        return ("phone", "") if ok else ("", detail)

    ask_id = str(uuid.uuid4())
    answers: queue.Queue = queue.Queue()
    threading.Thread(
        target=lambda: answers.put(("phone",) + ask(prompt, timeout_s, ask_id)),
        daemon=True,
    ).start()

    old_mode = termios.tcgetattr(tty_fd)
    quiet = termios.tcgetattr(tty_fd)
    quiet[3] &= ~termios.ECHO  # lflag: не показывать набираемый пароль
    typed = b""
    try:
        termios.tcsetattr(tty_fd, termios.TCSADRAIN, quiet)
        os.write(tty_fd, f"{prompt}\nПароль (или подтвердите на телефоне): "
                 .encode("utf-8"))
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            if not answers.empty():
                _, ok, detail = answers.get()
                os.write(tty_fd, b"\n")
                if ok:
                    os.write(tty_fd, "Подтверждено с телефона.\n".encode("utf-8"))
                    return "phone", ""
                return "", detail
            ready, _, _ = select.select([tty_fd], [], [], 0.1)
            if not ready:
                continue
            char = os.read(tty_fd, 1)
            if char in (b"\r", b"\n"):
                os.write(tty_fd, b"\n")
                cancel_ask(ask_id)  # телефон больше не нужен
                return "tty", typed.decode("utf-8", "replace")
            if char in (b"\x7f", b"\b"):
                typed = typed[:-1]
            elif char == b"\x03":
                raise KeyboardInterrupt
            elif char:
                typed += char
        cancel_ask(ask_id)
        return "", "timeout"
    finally:
        termios.tcsetattr(tty_fd, termios.TCSADRAIN, old_mode)
        os.close(tty_fd)


def sudo_password() -> str:
    """Пароль из связки ключей, иначе из файла."""
    try:
        out = subprocess.run(
            ["secret-tool", "lookup", "service", "rfid-agent", "user", os.environ.get("USER", "")],
            capture_output=True, text=True, check=True,
        ).stdout
        if out.strip():
            return out.rstrip("\n")
    except (OSError, subprocess.CalledProcessError):
        pass
    try:
        return PASS_FILE.read_text(encoding="utf-8").rstrip("\n")
    except OSError:
        return ""


def main() -> int:
    parser = argparse.ArgumentParser(description="Подтверждение действия со смартфона")
    parser.add_argument("prompt", nargs="?", default="", help="текст на экране телефона")
    parser.add_argument("-t", "--timeout", type=int, default=60, help="секунд на ответ")
    parser.add_argument("--password", action="store_true",
                        help="при подтверждении напечатать пароль sudo (режим SUDO_ASKPASS)")
    args = parser.parse_args()

    prompt = args.prompt or os.environ.get("SUDO_COMMAND", "") or "Подтвердить действие?"
    if args.password and not args.prompt:
        prompt = f"sudo: {os.environ.get('SUDO_COMMAND', 'команда')}"

    if args.password:
        # Гонка: что быстрее — руки на клавиатуре или кнопка на телефоне.
        source, value = race_tty_and_phone(prompt, args.timeout)
        if source == "tty":
            print(value)
            return 0
        if source != "phone":
            print(f"rfid-confirm: отказано ({value})", file=sys.stderr)
            return 1 if value in ("deny", "timeout", "cancel") else 2
        password = sudo_password()
        if not password:
            print("rfid-confirm: пароль не найден (secret-tool / sudo.pass)", file=sys.stderr)
            return 2
        print(password)
        return 0

    ok, detail = ask(prompt, args.timeout)
    if not ok:
        # fail-closed: без явного «да» ничего не выдаём, sudo спросит пароль сам.
        print(f"rfid-confirm: отказано ({detail})", file=sys.stderr)
        return 1 if detail in ("deny", "timeout") else 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
