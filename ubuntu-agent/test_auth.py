#!/usr/bin/env python3
"""Регрессионный тест HMAC-аутентификации rfid-server.py (ТЗ 7.2, этап 2).

Запускает сервер на локальном порту с тестовым токеном и проверяет:
валидную подпись, replay, неверный токен, битую подпись, устаревший ts,
старый формат (plain token), подмену команды под чужой подписью,
подпись ответа сервера и отказ стартовать без токена.

Запуск: python3 test_auth.py
"""

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

PORT = 5399
TOKEN = "test-token"
SERVER = Path(__file__).with_name("rfid-server.py")


def call(payload: dict) -> dict:
    s = socket.create_connection(("127.0.0.1", PORT), timeout=3)
    s.sendall((json.dumps(payload) + "\n").encode())
    line = s.makefile().readline().strip()
    s.close()
    return json.loads(line)


def signed(cmd: str, req_id: str | None = None, ts: int | None = None,
           token: str = TOKEN) -> dict:
    req_id = req_id or str(uuid.uuid4())
    ts = ts if ts is not None else int(time.time())
    sig = hmac.new(token.encode(), f"{cmd}|{req_id}|{ts}".encode(),
                   hashlib.sha256).hexdigest()
    return {"cmd": cmd, "reqId": req_id, "ts": ts, "sig": sig}


def main() -> int:
    env = dict(os.environ, RFID_PORT=str(PORT), RFID_TOKEN=TOKEN)
    server = subprocess.Popen([sys.executable, str(SERVER)], env=env,
                              stderr=subprocess.DEVNULL)
    try:
        time.sleep(1)
        ok = signed("status")
        assert call(ok)["status"] == "ok", "валидная подпись должна приниматься"
        assert call(ok)["detail"] == "unauthorized", "replay должен отклоняться"
        assert call(signed("status", token="wrong"))["detail"] == "unauthorized"
        bad = signed("status")
        bad["sig"] = "00" * 32
        assert call(bad)["detail"] == "unauthorized", "битая подпись"
        stale = signed("status", ts=int(time.time()) - 400)
        assert call(stale)["detail"] == "unauthorized", "устаревший ts"
        legacy = {"cmd": "status", "reqId": "z1", "token": TOKEN}
        assert call(legacy)["detail"] == "unauthorized", "plain token отклоняется"
        tamper = signed("unlock")
        tamper["cmd"] = "status"
        assert call(tamper)["detail"] == "unauthorized", "подмена cmd"

        # Ответ подписан: телефон должен уметь отличить его от подделки
        # посредника (подмена "lan" или ложное "locked=yes").
        req = signed("status")
        resp = call(req)
        expected = hmac.new(
            TOKEN.encode(),
            "|".join([req["reqId"], resp.get("status", ""), resp.get("detail", ""),
                      resp.get("lan", ""), resp.get("power", "")]).encode(),
            hashlib.sha256).hexdigest()
        assert resp.get("sig") == expected, "ответ сервера должен быть подписан"
        assert hmac.new(TOKEN.encode(),
                        "|".join(["чужой-reqId", resp.get("status", ""),
                                  resp.get("detail", ""), resp.get("lan", ""),
                                  resp.get("power", "")]).encode(),
                        hashlib.sha256).hexdigest() != resp["sig"], \
            "подпись должна быть привязана к reqId запроса"
        print("OK: все проверки HMAC-аутентификации пройдены")
        return 0
    finally:
        server.terminate()
        server.wait()


def no_token_refused() -> None:
    """Без токена сервер обязан не подниматься (fail-closed)."""
    env = dict(os.environ, RFID_PORT=str(PORT + 1), RFID_TOKEN="")
    done = subprocess.run([sys.executable, str(SERVER)], env=env,
                          capture_output=True, text=True, timeout=30)
    assert done.returncode == 2, f"ожидался отказ старта, код {done.returncode}"
    assert "Токен не найден" in done.stderr, done.stderr[:200]
    print("OK: без токена сервер не стартует")


if __name__ == "__main__":
    no_token_refused()
    raise SystemExit(main())
