"""Режимы питания: status отдаёт список, команды подписаны, чужое отклоняется.

Настоящий systemctl не зовём — ПК усыплять незачем; вместо него заглушка.
"""
import hashlib, hmac, importlib.util, json, os, socket, threading, time, uuid
from pathlib import Path

PORT = "55396"
os.environ.update(RFID_TOKEN="t0ken", RFID_PORT=PORT)
spec = importlib.util.spec_from_file_location("srv", "rfid-server.py")
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)

# Что «умеет» этот ПК в тесте, и куда записывается выполненное.
srv.POWER_CAPABILITIES = "suspend,poweroff"   # гибернацию машина не умеет
done: list[str] = []
srv.subprocess.run = lambda *a, **k: done.append(a[0]) or type("R", (), {"stdout": ""})()
# Сессию подменяем: настоящий loginctl мы только что отключили заглушкой.
srv.current_session_id = lambda: "test-session"

threading.Thread(target=srv.main, daemon=True).start()
time.sleep(0.5)


def call(cmd: str) -> dict:
    rid, ts = str(uuid.uuid4()), int(time.time())
    sig = hmac.new(b"t0ken", f"{cmd}|{rid}|{ts}".encode(), hashlib.sha256).hexdigest()
    with socket.create_connection(("127.0.0.1", int(PORT)), 3) as s:
        s.sendall((json.dumps({"cmd": cmd, "reqId": rid, "ts": ts, "sig": sig}) + "\n").encode())
        return json.loads(s.makefile().readline()) | {"_reqId": rid}


# 1. status перечисляет только поддерживаемое, и это входит в подпись ответа.
resp = call("status")
assert resp.get("power") == "suspend,poweroff", resp
expected = hmac.new(b"t0ken", "|".join([resp["_reqId"], resp["status"], resp["detail"],
                                        resp.get("lan", ""), resp["power"]]).encode(),
                    hashlib.sha256).hexdigest()
assert resp["sig"] == expected, "поле power должно быть под подписью"

# 2. Поддерживаемый режим выполняется.
done.clear()
resp = call("suspend")
assert resp["status"] == "ok" and resp["detail"] == "suspend", resp
assert done == [["systemctl", "suspend"]], done

# 3. Неподдерживаемый — отказ, и systemctl не вызывается.
done.clear()
resp = call("hibernate")
assert resp["status"] == "error" and resp["detail"] == "unsupported:hibernate", resp
assert done == [], done

# 4. Без подписи не проходит даже поддерживаемый режим.
rid, ts = str(uuid.uuid4()), int(time.time())
with socket.create_connection(("127.0.0.1", int(PORT)), 3) as s:
    s.sendall((json.dumps({"cmd": "poweroff", "reqId": rid, "ts": ts,
                           "sig": "00" * 32}) + "\n").encode())
    resp = json.loads(s.makefile().readline())
assert resp["detail"] == "unauthorized", resp
assert done == [], done

print("OK: status отдаёт поддерживаемые режимы питания и подписывает их")
print("OK: неподдерживаемый режим и неподписанная команда отклоняются")
