"""Проверка: сервер отвечает на status полем lan (быстрый путь телефона)."""
import hashlib, hmac, json, os, socket, threading, time, uuid, importlib.util, sys
os.environ["RFID_TOKEN"] = "t0ken"; os.environ["RFID_PORT"] = "55390"
spec = importlib.util.spec_from_file_location("srv", "rfid-server.py")
srv = importlib.util.module_from_spec(spec); spec.loader.exec_module(srv)

assert srv.lan_ip().count(".") == 3, srv.lan_ip()
threading.Thread(target=srv.main, daemon=True).start(); time.sleep(0.5)

rid, ts = str(uuid.uuid4()), int(time.time())
sig = hmac.new(b"t0ken", f"status|{rid}|{ts}".encode(), hashlib.sha256).hexdigest()
with socket.create_connection(("127.0.0.1", 55390), 3) as s:
    s.sendall((json.dumps({"cmd": "status", "reqId": rid, "ts": ts, "sig": sig}) + "\n").encode())
    resp = json.loads(s.makefile().readline())
assert resp["status"] == "ok", resp
assert resp["lan"] == srv.lan_ip(), resp
print("ok:", resp)
