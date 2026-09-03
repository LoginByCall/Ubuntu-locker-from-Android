"""Проверка sudo-цепочки: rfid-askpass печатает пароль только после «да».

ВАЖНО: тест запускает CLI с PATH без secret-tool, чтобы пароль брался из
временного sudo.pass. Настоящая связка ключей не должна участвовать никогда.

Поднимает сервер на отдельном порту с заглушкой push (Firebase не нужен) и
прогоняет реальный CLI: подтверждение → пароль на stdout, код 0; отказ →
пусто, код 1. Пароль для теста берётся из временного sudo.pass.
"""
import hashlib, hmac, importlib.util, json, os, socket, subprocess, sys, tempfile
import fcntl, pty, termios, threading, time, uuid
from pathlib import Path

PORT = "55392"
conf = Path(tempfile.mkdtemp()) / "rfid-agent"
conf.mkdir(parents=True)
(conf / "token").write_text("t0ken")
(conf / "sudo.pass").write_text("пароль-для-теста\n")

# Заглушка secret-tool впереди PATH: пароль обязан прийти из sudo.pass,
# настоящая связка ключей в тесте не участвует.
stub_bin = conf.parent / "bin"
stub_bin.mkdir()
(stub_bin / "secret-tool").write_text("#!/bin/sh\nexit 1\n")
(stub_bin / "secret-tool").chmod(0o755)

os.environ.update(RFID_TOKEN="t0ken", RFID_PORT=PORT,
                  XDG_CONFIG_HOME=str(conf.parent))
spec = importlib.util.spec_from_file_location("srv", "rfid-server.py")
srv = importlib.util.module_from_spec(spec); spec.loader.exec_module(srv)

pushed: list[str] = []
srv.send_push = lambda ask_id, prompt, timeout_s: (pushed.append(ask_id), "")[1]
threading.Thread(target=srv.main, daemon=True).start()
time.sleep(0.5)


def answer(verdict: str) -> None:
    """Ответить как телефон: дождаться push и отправить вердикт."""
    while not pushed:
        time.sleep(0.05)
    ask_id = pushed.pop()
    rid, ts = str(uuid.uuid4()), int(time.time())
    sig = hmac.new(b"t0ken", f"confirm|{rid}|{ts}|{ask_id}|{verdict}".encode(),
                   hashlib.sha256).hexdigest()
    with socket.create_connection(("127.0.0.1", int(PORT)), 3) as s:
        s.sendall((json.dumps({"cmd": "confirm", "reqId": rid, "ts": ts, "sig": sig,
                               "askId": ask_id, "verdict": verdict}) + "\n").encode())
        s.makefile().readline()


def run_askpass(verdict: str) -> subprocess.CompletedProcess:
    # PATH без secret-tool: тест ОБЯЗАН работать с подставным паролем из
    # временного sudo.pass и никогда не трогать настоящую связку ключей —
    # иначе реальный пароль попадёт в вывод теста.
    env = {**os.environ, "SUDO_COMMAND": "/usr/bin/apt update",
           "PATH": f"{stub_bin}:{os.environ['PATH']}", "RFID_PORT": PORT}
    proc = subprocess.Popen(["./rfid-askpass"], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, env=env)
    threading.Thread(target=answer, args=(verdict,), daemon=True).start()
    out, err = proc.communicate(timeout=60)
    return subprocess.CompletedProcess(proc.args, proc.returncode, out, err)


ok = run_askpass("approve")
assert ok.returncode == 0, ok
assert ok.stdout.strip() == "пароль-для-теста", repr(ok.stdout)

no = run_askpass("deny")
assert no.returncode == 1, no
assert "пароль-для-теста" not in no.stdout, repr(no.stdout)


def run_askpass_pty(typed: str | None, verdict: str | None) -> tuple[int, str]:
    """Запустить CLI на псевдотерминале. typed — что «набирают» руками."""
    out = conf.parent / "out.txt"
    out.write_text("")
    env = {**os.environ, "SUDO_COMMAND": "/usr/bin/apt update",
           "PATH": f"{stub_bin}:{os.environ['PATH']}", "RFID_PORT": PORT}
    pid, master = pty.fork()
    if pid == 0:  # ребёнок: stdout — в файл, stdin/stderr — на псевдотерминал
        fd = os.open(str(out), os.O_WRONLY | os.O_CREAT | os.O_TRUNC)
        os.dup2(fd, 1)
        os.execve("./rfid-askpass", ["./rfid-askpass"], env)
    if verdict:
        threading.Thread(target=answer, args=(verdict,), daemon=True).start()
    if typed is not None:
        time.sleep(1.5)  # дать CLI поднять приглашение
        os.write(master, (typed + "\n").encode())
    _, status = os.waitpid(pid, 0)
    os.close(master)
    return os.waitstatus_to_exitcode(status), out.read_text()


# 3. Гонку выигрывает телефон: пароль берётся из хранилища.
code, stdout = run_askpass_pty(typed=None, verdict="approve")
assert code == 0 and stdout.strip() == "пароль-для-теста", (code, stdout)

# 4. Гонку выигрывает терминал: печатается набранное, телефон не спрашивается.
pushed.clear()
code, stdout = run_askpass_pty(typed="набрано-руками", verdict=None)
assert code == 0 and stdout.strip() == "набрано-руками", (code, stdout)


def run_askpass_gui(zenity_body: str, verdict: str | None) -> tuple[int, str]:
    """Запуск без терминала: сработать должен GUI-канал (подставной zenity)."""
    (stub_bin / "zenity").write_text(zenity_body)
    (stub_bin / "zenity").chmod(0o755)
    env = {**os.environ, "SUDO_COMMAND": "/usr/bin/apt update", "DISPLAY": ":99",
           "PATH": f"{stub_bin}:{os.environ['PATH']}", "RFID_PORT": PORT}
    if verdict:
        threading.Thread(target=answer, args=(verdict,), daemon=True).start()
    # start_new_session: своя сессия без управляющего терминала → /dev/tty
    # недоступен, и CLI обязан выбрать окно, а не терминал.
    proc = subprocess.run(["./rfid-askpass"], capture_output=True, text=True,
                          env=env, start_new_session=True, stdin=subprocess.DEVNULL,
                          timeout=90)
    return proc.returncode, proc.stdout


# 5. Нет терминала, окно отвечает первым.
pushed.clear()
code, stdout = run_askpass_gui("#!/bin/sh\nsleep 0.5; echo 'из-окна'\n", verdict=None)
assert code == 0 and stdout.strip() == "из-окна", (code, stdout)

# 6. Нет терминала, телефон опережает окно — окно закрывается.
pushed.clear()
code, stdout = run_askpass_gui("#!/bin/sh\nsleep 60; echo поздно\n", verdict="approve")
assert code == 0 and stdout.strip() == "пароль-для-теста", (code, stdout)

def run_pam_pty(typed: str | None, verdict: str | None) -> tuple[int, str]:
    """Режим --pam на псевдотерминале: пароль не участвует вообще."""
    out = conf.parent / "pam-out.txt"
    out.write_text("")
    env = {**os.environ, "PATH": f"{stub_bin}:{os.environ['PATH']}", "RFID_PORT": PORT}
    pid, master = pty.fork()
    if pid == 0:
        fd = os.open(str(out), os.O_WRONLY | os.O_CREAT | os.O_TRUNC)
        os.dup2(fd, 1)
        os.execve(sys.executable, [sys.executable, "rfid-confirm.py", "--pam",
                                   "sudo: apt update", "-t", "20"], env)
    if verdict:
        threading.Thread(target=answer, args=(verdict,), daemon=True).start()
    if typed is not None:
        time.sleep(1.5)  # дать хелперу поднять приглашение
        os.write(master, typed.encode())
    _, status = os.waitpid(pid, 0)
    os.close(master)
    return os.waitstatus_to_exitcode(status), out.read_text()


# 7. PAM: телефон подтвердил — аутентификация пройдена, пароль не печатается.
pushed.clear()
code, stdout = run_pam_pty(typed=None, verdict="approve")
assert code == 0, (code, stdout)
assert "пароль-для-теста" not in stdout, "в режиме --pam пароль печататься не должен"

# 8. PAM: начали вводить пароль — уходим к обычному приглашению (ненулевой
# код), запрос на телефоне снимается. Время проверяем специально: тест уже
# однажды проходил по таймауту, не замечая, что ввод не сработал.
pushed.clear()
started = time.time()
code, stdout = run_pam_pty(typed="пароль-руками\n", verdict=None)
elapsed = time.time() - started
assert code == 1, (code, stdout)
assert elapsed < 10, f"ввод не прервал ожидание: {elapsed:.1f} с"
assert "пароль-для-теста" not in stdout

# 9. PAM: телефон отказал — тоже к паролю.
pushed.clear()
code, stdout = run_pam_pty(typed=None, verdict="deny")
assert code == 1, (code, stdout)

def run_pam_leftover() -> tuple[int, bytes]:
    """Набранный пароль обязан остаться в очереди терминала.

    Иначе его пришлось бы вводить второй раз: следующим читателем терминала
    будет pam_unix со своим «[sudo] password for ...».
    """
    master, slave = pty.openpty()
    env = {**os.environ, "PATH": f"{stub_bin}:{os.environ['PATH']}", "RFID_PORT": PORT}
    pid = os.fork()
    if pid == 0:
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)  # slave становится нашим терминалом
        for fd in (0, 1, 2):
            os.dup2(slave, fd)
        os.execve(sys.executable, [sys.executable, "rfid-confirm.py", "--pam",
                                   "sudo apt update", "-t", "8"], env)
    time.sleep(1.5)                      # дать хелперу поднять приглашение
    os.write(master, "пароль-руками\n".encode())
    _, status = os.waitpid(pid, 0)
    os.set_blocking(slave, False)
    try:
        leftover = os.read(slave, 200)
    except BlockingIOError:
        leftover = b""
    os.close(master); os.close(slave)
    return os.waitstatus_to_exitcode(status), leftover


# 10. Набранное не съедено: строка ждёт в очереди для pam_unix.
pushed.clear()
code, leftover = run_pam_leftover()
assert code == 1, code
assert leftover == "пароль-руками\n".encode(), leftover

print("OK: rfid-askpass отдаёт пароль только после подтверждения")
print("OK: гонка терминал/телефон — выигрывает тот, кто ответил первым")
print("OK: без терминала гонка идёт между окном (zenity) и телефоном")
print("OK: режим --pam: телефон против ввода пароля, пароль не участвует")
print("OK: набранный пароль остаётся в очереди терминала — вводить дважды не нужно")
