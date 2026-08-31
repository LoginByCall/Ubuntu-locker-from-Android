#!/usr/bin/env python3
"""rfid-confirm.py — подтверждение действия на ПК со смартфона.

Спрашивает у телефона «да/нет» через агента (команда ask) и отдаёт ответ
кодом возврата: 0 — подтверждено, 1 — отказано/таймаут, 2 — ошибка канала.

Применение:

  # универсально: любой скрипт, polkit-обёртка, деплой и т. п.
  rfid-confirm.py "Перезагрузить сервер?" && systemctl reboot

  # sudo: печатает пароль на stdout только после подтверждения
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
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path

CONF_DIR = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config"))) / "rfid-agent"
TOKEN_FILE = CONF_DIR / "token"
PASS_FILE = CONF_DIR / "sudo.pass"
PORT = int(os.environ.get("RFID_PORT", "5390"))


def ask(prompt: str, timeout_s: int) -> tuple[bool, str]:
    """Спросить телефон через агента. Возвращает (подтверждено, детали)."""
    token = TOKEN_FILE.read_text(encoding="utf-8").strip() if TOKEN_FILE.is_file() else ""
    req_id, ts = str(uuid.uuid4()), int(time.time())
    sig = hmac.new(token.encode(), f"ask|{req_id}|{ts}".encode(), hashlib.sha256).hexdigest()
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

    ok, detail = ask(prompt, args.timeout)
    if not ok:
        # fail-closed: без явного «да» ничего не выдаём, sudo спросит пароль сам.
        print(f"rfid-confirm: отказано ({detail})", file=sys.stderr)
        return 1 if detail in ("deny", "timeout") else 2
    if args.password:
        password = sudo_password()
        if not password:
            print("rfid-confirm: пароль не найден (secret-tool / sudo.pass)", file=sys.stderr)
            return 2
        print(password)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
