"""Проверка потока подтверждения: ask ждёт вердикт, confirm его приносит.

Push подменяется заглушкой — Firebase для проверки логики не нужен.
Главное, что проверяется: подпись покрывает тело (askId/verdict), «нет» и
таймаут не превращаются в «да», чужой askId отвергается.
"""
import hashlib, hmac, importlib.util, json, os, socket, threading, time, uuid

os.environ["RFID_TOKEN"] = "t0ken"; os.environ["RFID_PORT"] = "55391"
spec = importlib.util.spec_from_file_location("srv", "rfid-server.py")
srv = importlib.util.module_from_spec(spec); spec.loader.exec_module(srv)

pushed: list[str] = []


def fake_push(ask_id, prompt, timeout_s):
    pushed.append(ask_id)
    return ""


srv.send_push = fake_push
threading.Thread(target=srv.main, daemon=True).start()
time.sleep(0.5)


def send(cmd, extra=None, wait=10.0, token=b"t0ken"):
    extra = extra or {}
    rid, ts = str(uuid.uuid4()), int(time.time())
    parts = [cmd, rid, str(ts)] + [str(extra.get(f, "")) for f in srv.SIGNED_FIELDS.get(cmd, ())]
    msg = {"cmd": cmd, "reqId": rid, "ts": ts,
           "sig": hmac.new(token, "|".join(parts).encode(), hashlib.sha256).hexdigest(), **extra}
    with socket.create_connection(("127.0.0.1", 55391), 3) as s:
        s.settimeout(wait)
        s.sendall((json.dumps(msg) + "\n").encode())
        return json.loads(s.makefile().readline())


def start_ask(timeout=5):
    """Запустить ask в фоне; вернуть (askId, функция ожидания ответа)."""
    pushed.clear()
    box = {}
    t = threading.Thread(
        target=lambda: box.update(r=send("ask", {"prompt": "sudo apt", "timeout": timeout},
                                         wait=timeout + 10)))
    t.start()
    while not pushed:
        time.sleep(0.05)
    return pushed[-1], lambda: (t.join(), box["r"])[1]


# 1. Подтверждение проходит.
ask_id, result = start_ask()
assert send("confirm", {"askId": ask_id, "verdict": "approve"})["detail"] == "accepted"
assert result()["status"] == "ok", result()

# 2. Отказ — именно отказ, а не «ок».
ask_id, result = start_ask()
send("confirm", {"askId": ask_id, "verdict": "deny"})
resp = result()
assert resp["status"] == "error" and resp["detail"] == "deny", resp

# 3. Молчание телефона = отказ по таймауту (fail-closed).
_, result = start_ask(timeout=5)
resp = result()
assert resp["status"] == "error" and resp["detail"] == "timeout", resp

# 4. Подменённый вердикт не проходит: подпись покрывает тело.
ask_id, result = start_ask()
forged = send("confirm", {"askId": ask_id, "verdict": "approve"}, token=b"wrong")
assert forged["detail"] == "unauthorized", forged
# ...и подпись «deny», переписанная в «approve» по дороге, тоже отвергается
rid, ts = str(uuid.uuid4()), int(time.time())
sig = hmac.new(b"t0ken", f"confirm|{rid}|{ts}|{ask_id}|deny".encode(), hashlib.sha256).hexdigest()
with socket.create_connection(("127.0.0.1", 55391), 3) as s:
    s.sendall((json.dumps({"cmd": "confirm", "reqId": rid, "ts": ts, "sig": sig,
                           "askId": ask_id, "verdict": "approve"}) + "\n").encode())
    assert json.loads(s.makefile().readline())["detail"] == "unauthorized"
send("confirm", {"askId": ask_id, "verdict": "deny"})
assert result()["detail"] == "deny"

# 5. Чужой/протухший askId ничего не открывает.
assert send("confirm", {"askId": "нет-такого", "verdict": "approve"})["detail"] == "unknown-ask"

print("OK: ask/confirm — подтверждение, отказ, таймаут, подмена вердикта")
