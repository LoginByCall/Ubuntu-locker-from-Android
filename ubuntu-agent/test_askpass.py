"""Проверка sudo-цепочки: rfid-askpass печатает пароль только после «да».

Поднимает сервер на отдельном порту с заглушкой push (Firebase не нужен) и
прогоняет реальный CLI: подтверждение → пароль на stdout, код 0; отказ →
пусто, код 1. Пароль для теста берётся из временного sudo.pass.
"""
import hashlib, hmac, importlib.util, json, os, socket, subprocess, sys, tempfile
import threading, time, uuid
from pathlib import Path

PORT = "55392"
conf = Path(tempfile.mkdtemp()) / "rfid-agent"
conf.mkdir(parents=True)
(conf / "token").write_text("t0ken")
(conf / "sudo.pass").write_text("пароль-для-теста\n")

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
    proc = subprocess.Popen(["./rfid-askpass"], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True,
                            env={**os.environ, "SUDO_COMMAND": "/usr/bin/apt update"})
    threading.Thread(target=answer, args=(verdict,), daemon=True).start()
    out, err = proc.communicate(timeout=60)
    return subprocess.CompletedProcess(proc.args, proc.returncode, out, err)


ok = run_askpass("approve")
assert ok.returncode == 0, ok
assert ok.stdout.strip() == "пароль-для-теста", repr(ok.stdout)

no = run_askpass("deny")
assert no.returncode == 1, no
assert "пароль-для-теста" not in no.stdout, repr(no.stdout)

print("OK: rfid-askpass отдаёт пароль только после подтверждения")
