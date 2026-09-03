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
import array
import fcntl
import hashlib
import hmac
import json
import os
import queue
import select
import shutil
import signal
import socket
import subprocess
import sys
import termios
import threading
import time
import uuid
import pathlib
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


class TtyChannel:
    """Ввод пароля в терминале с выключенным эхом."""

    def __init__(self, prompt: str) -> None:
        self.fd = os.open("/dev/tty", os.O_RDWR | os.O_NOCTTY)
        self.saved = termios.tcgetattr(self.fd)
        quiet = termios.tcgetattr(self.fd)
        quiet[3] &= ~termios.ECHO  # lflag
        termios.tcsetattr(self.fd, termios.TCSADRAIN, quiet)
        os.write(self.fd, f"{prompt}\nПароль (или подтвердите на телефоне): "
                 .encode("utf-8"))
        self.typed = b""

    def poll(self) -> str | None:
        """Готовый пароль или None, пока Enter не нажат."""
        ready, _, _ = select.select([self.fd], [], [], 0.1)
        if not ready:
            return None
        char = os.read(self.fd, 1)
        if char in (b"\r", b"\n"):
            os.write(self.fd, b"\n")
            return self.typed.decode("utf-8", "replace")
        if char in (b"\x7f", b"\b"):
            self.typed = self.typed[:-1]
        elif char == b"\x03":
            raise KeyboardInterrupt
        elif char:
            self.typed += char
        return None

    def note(self, text: str) -> None:
        os.write(self.fd, f"\n{text}\n".encode("utf-8"))

    def close(self) -> None:
        termios.tcsetattr(self.fd, termios.TCSADRAIN, self.saved)
        os.close(self.fd)


class GuiChannel:
    """Окно ввода пароля (zenity/kdialog) — когда терминала нет."""

    def __init__(self, prompt: str) -> None:
        if shutil.which("zenity"):
            argv = ["zenity", "--password", "--title", prompt[:60]]
        elif shutil.which("kdialog"):
            argv = ["kdialog", "--password", prompt]
        else:
            raise OSError("нет zenity/kdialog")
        self.proc = subprocess.Popen(argv, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, text=True)

    def poll(self) -> str | None:
        """Пароль из окна; None — окно ещё открыто или его закрыли крестиком."""
        if self.proc.poll() is None:
            time.sleep(0.1)
            return None
        if self.proc.returncode != 0:  # отменил окно — ждём дальше телефон
            raise EOFError
        return (self.proc.stdout.read() or "").rstrip("\n")

    def note(self, text: str) -> None:
        pass  # окно уже закрыто; сообщать некуда

    def close(self) -> None:
        if self.proc.poll() is None:
            self.proc.terminate()  # телефон успел раньше — окно убираем


def open_local_channel(prompt: str):
    """Канал ввода на самом ПК: терминал, иначе окно, иначе ничего."""
    try:
        return TtyChannel(prompt)
    except OSError:
        pass
    if os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"):
        try:
            return GuiChannel(prompt)
        except OSError:
            pass
    return None


def race_local_and_phone(prompt: str, timeout_s: int) -> tuple[str, str]:
    """Спросить одновременно ПК (терминал или окно) и телефон; кто быстрее.

    Возвращает ("local", пароль) — ввели на ПК; ("phone", "") — подтвердили
    на телефоне (пароль берётся из хранилища); ("", причина) — отказ/таймаут.
    """
    ask_id = str(uuid.uuid4())
    answers: queue.Queue = queue.Queue()
    threading.Thread(
        target=lambda: answers.put(ask(prompt, timeout_s, ask_id)),
        daemon=True,
    ).start()

    local = open_local_channel(prompt)
    try:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            if not answers.empty():
                ok, detail = answers.get()
                if local is not None:
                    local.note("Подтверждено с телефона." if ok else f"Отказано: {detail}")
                return ("phone", "") if ok else ("", detail)
            if local is None:
                time.sleep(0.1)
                continue
            try:
                typed = local.poll()
            except EOFError:  # окно закрыли — остаётся только телефон
                local.close()
                local = None
                continue
            if typed is not None:
                cancel_ask(ask_id)  # телефон больше не нужен
                return "local", typed
        cancel_ask(ask_id)
        return "", "timeout"
    finally:
        if local is not None:
            local.close()


def log_pam(message: str) -> None:
    """Строка в журнал разбора: почему гонка пошла именно так.

    Под PAM ни stdout, ни stderr никуда не ведут, поэтому без такого журнала
    любой сбой выглядит как «просто ждём телефон» — на этом уже дважды
    потерялось время.
    """
    try:
        state = Path(os.environ.get("XDG_STATE_HOME",
                                    str(Path.home() / ".local/state"))) / "rfid-agent"
        state.mkdir(parents=True, exist_ok=True)
        with (state / "pam.log").open("a", encoding="utf-8") as log:
            log.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} uid={os.getuid()} "
                      f"euid={os.geteuid()} PAM_TTY={os.environ.get('PAM_TTY', '-')} "
                      f"{message}\n")
    except OSError:
        pass  # журнал — не повод ломать аутентификацию


def open_user_tty() -> int | None:
    """Найти терминал пользователя и открыть его на чтение-запись.

    Порядок не случаен:
    * `PAM_TTY` — то, что PAM специально сообщает модулю (для sudo это
      /dev/pts/N). Открывается от имени пользователя, а pam_exec запускает
      хелпер именно так, не от root.
    * `/dev/tty` — под pam_exec не работает: управляющего терминала нет
      (проверено на живом sudo — приглашение не появлялось вовсе).
    * потоки родителя (сам sudo) — последняя попытка; годится, только если
      хелпер всё-таки от root, потому что sudo setuid и его /proc/<pid>/fd
      закрыт для пользователя.
    """
    pam_tty = os.environ.get("PAM_TTY", "").strip()
    candidates = []
    if pam_tty and (pam_tty.startswith("/") or pam_tty.startswith("pts")):
        candidates.append(pam_tty if pam_tty.startswith("/") else f"/dev/{pam_tty}")
    parent = os.getppid()
    candidates += ["/dev/tty", f"/proc/{parent}/fd/0",
                   f"/proc/{parent}/fd/1", f"/proc/{parent}/fd/2"]
    for path in candidates:
        try:
            fd = os.open(path, os.O_RDWR | os.O_NOCTTY)
        except OSError:
            continue
        if os.isatty(fd):
            log_pam(f"терминал найден: {path}")
            return fd
        os.close(fd)
    log_pam("терминал НЕ найден, пробовали: " + ", ".join(candidates))
    return None


def pending_input(fd: int) -> bool:
    """Есть ли в очереди терминала готовый ввод — без его вычитывания."""
    counter = array.array("i", [0])
    try:
        fcntl.ioctl(fd, termios.FIONREAD, counter, True)
    except OSError:
        return False
    return counter[0] > 0


def race_phone_and_typing(prompt: str, timeout_s: int) -> bool:
    """Гонка для PAM: телефон против ввода пароля в терминале.

    Пароль здесь не читается и нигде не хранится — им займётся сам PAM.
    Хитрость в том, что набранное мы НЕ забираем из очереди терминала, а лишь
    замечаем через FIONREAD: строка остаётся в буфере и достаётся следующему
    приглашению («[sudo] password for ...»). Поэтому пароль можно набирать
    сразу, не дожидаясь ничего и не теряя ни одного символа.

    Подтвердили на телефоне → True (аутентификация пройдена; всё набранное
    вычищаем, чтобы не утекло в командную строку). Начали вводить пароль,
    отказали или вышло время → False, и PAM идёт дальше по стеку.
    """
    ask_id = str(uuid.uuid4())
    answers: queue.Queue = queue.Queue()
    threading.Thread(target=lambda: answers.put(ask(prompt, timeout_s, ask_id)),
                     daemon=True).start()
    saved = None
    # Терминал не наш (мы в чужой сессии), поэтому смена его настроек шлёт
    # SIGTTOU и остановила бы процесс. Сигнал глушим — иначе гонка зависнет.
    signal.signal(signal.SIGTTOU, signal.SIG_IGN)
    tty = open_user_tty()
    if tty is None:
        pass  # без терминала (GUI, cron) остаётся только телефон
    else:
        # ICANON оставляем: в каноническом режиме строка копится в буфере
        # терминала и после Enter достаётся тому, кто будет читать следующим,
        # то есть pam_unix. ECHO гасим — пароль на экране не нужен.
        saved = termios.tcgetattr(tty)
        quiet = termios.tcgetattr(tty)
        quiet[3] &= ~termios.ECHO  # lflag
        termios.tcsetattr(tty, termios.TCSADRAIN, quiet)
        os.write(tty, f"{prompt}\nПодтвердите на телефоне "
                      "или введите пароль: ".encode("utf-8"))
    try:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            if not answers.empty():
                ok, detail = answers.get()
                if tty is not None:
                    # Недонабранное вычищаем: иначе после успеха оно попадёт
                    # в командную строку — то есть пароль окажется на экране.
                    termios.tcflush(tty, termios.TCIFLUSH)
                    os.write(tty, ("\nПодтверждено с телефона.\n" if ok
                                   else f"\nТелефон: {detail}\n").encode("utf-8"))
                return ok
            if tty is None:
                time.sleep(0.1)
                continue
            if pending_input(tty):
                # Пароль уже набран и ждёт в буфере — забирать его не наше
                # дело: пусть его прочитает pam_unix своим приглашением.
                cancel_ask(ask_id)  # телефон больше не нужен
                return False
            time.sleep(0.05)
        cancel_ask(ask_id)
        return False
    finally:
        if tty is not None:
            if saved is not None:
                termios.tcsetattr(tty, termios.TCSADRAIN, saved)
            os.close(tty)


def sudo_prompt() -> str:
    """Что именно подтверждаем: команду берём у родителя — это сам sudo.

    Своей команды sudo помощнику askpass не сообщает (в окружении её нет),
    зато она видна в аргументах родительского процесса.
    """
    try:
        argv = pathlib.Path(f"/proc/{os.getppid()}/cmdline").read_bytes()
    except OSError:
        return ""
    parts = [a for a in argv.decode("utf-8", "replace").split("\0") if a]
    if not parts or not parts[0].rstrip("0123456789").endswith("sudo"):
        return ""
    # выбрасываем сам sudo и его флаги; с первого не-флага начинается
    # команда пользователя — её показываем целиком, вместе с аргументами
    rest = parts[1:]
    while rest and rest[0].startswith("-"):
        rest = rest[1:]
    command = " ".join(rest)
    return f"sudo: {command}" if command else "sudo (без команды)"


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
    parser.add_argument("--pam", action="store_true",
                        help="режим PAM: гонка «телефон против клавиши», без пароля")
    parser.add_argument("--password", action="store_true",
                        help="при подтверждении напечатать пароль sudo (режим SUDO_ASKPASS)")
    args = parser.parse_args()

    prompt = args.prompt or (sudo_prompt() if args.password or args.pam else "") \
        or "Подтвердить действие?"

    if args.pam:
        # Пароля не касаемся: подтвердил телефон — код 0, иначе PAM спросит сам.
        return 0 if race_phone_and_typing(prompt, args.timeout) else 1

    if args.password:
        # Гонка: что быстрее — руки на клавиатуре или кнопка на телефоне.
        source, value = race_local_and_phone(prompt, args.timeout)
        if source == "local":
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
