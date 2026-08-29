#!/usr/bin/env python3
"""rfid-tray.py — иконка в системном трее для Ubuntu-агента Ubuntu-RFID-Android.

Назначение: показать QR-код профиля этого ПК, чтобы Android-приложение могло
добавить профиль одним сканированием (Этап «Профили ПК»).

QR кодирует JSON-профиль:
    {"v": 1, "id": "<uuid>", "name": "<hostname>", "host": "<lan-ip>",
     "port": <port>, "token": "<token>"}

Поля:
  - id    — стабильный UUID профиля (хранится в ~/.config/rfid-agent/profile-id);
  - name  — имя ПК (hostname);
  - host  — IP в локальной сети (определяется на момент показа);
  - port  — TCP-порт агента (RFID_PORT, по умолчанию 5390);
  - token — общий токen (читается из ~/.config/rfid-agent/token).

Меню трея:
  - «Показать QR-код» — генерирует PNG и открывает его в просмотрщике;
  - «Выход».

Зависимости: pystray, qrcode, Pillow (ставятся install-server.sh).
"""

from __future__ import annotations

import ipaddress
import json
import os
import platform
import socket
import subprocess
import tempfile
import uuid
from pathlib import Path

import qrcode
from PIL import Image, ImageDraw

# На GNOME/Ubuntu системный трей реализован через AppIndicator (UTF-8-совместим).
# Форсируем appindicator-backend: иначе pystray падает на xorg-бэкенде, который
# кодирует заголовок в latin-1 и не переносит кириллицу/тире.
os.environ.setdefault("PYSTRAY_BACKEND", "appindicator")

try:
    import pystray
    from pystray import Menu, MenuItem
except Exception as exc:  # pragma: no cover - окружение без трея
    raise SystemExit(
        "Не найден pystray. Установите зависимости: pip install pystray qrcode pillow"
    ) from exc

CONF_DIR = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config"))) / "rfid-agent"
TOKEN_FILE = CONF_DIR / "token"
PROFILE_ID_FILE = CONF_DIR / "profile-id"
PORT = int(os.environ.get("RFID_PORT", "5390"))
QR_VERSION = 1


def read_token() -> str:
    """Прочитать общий токен (или пустую строку, если не задан)."""
    try:
        return TOKEN_FILE.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def profile_id() -> str:
    """Стабильный UUID профиля; создаётся при первом запуске."""
    try:
        existing = PROFILE_ID_FILE.read_text(encoding="utf-8").strip()
        if existing:
            return existing
    except OSError:
        pass
    new_id = str(uuid.uuid4())
    CONF_DIR.mkdir(parents=True, exist_ok=True)
    PROFILE_ID_FILE.write_text(new_id, encoding="utf-8")
    return new_id


def zt_ip() -> str:
    """Адрес в сети ZeroTier (интерфейс zt*): достижим из любой сети, где есть ZT."""
    try:
        out = subprocess.run(
            ["ip", "-j", "-4", "addr"], capture_output=True, text=True, check=True
        ).stdout
        for iface in json.loads(out):
            if iface.get("ifname", "").startswith("zt"):
                for addr in iface.get("addr_info", []):
                    if addr.get("local"):
                        return addr["local"]
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError):
        pass
    return ""


def ygg_ip() -> str:
    """Yggdrasil-адрес узла (200::/7): стабилен, не зависит от физической сети."""
    try:
        out = subprocess.run(
            ["ip", "-j", "-6", "addr"], capture_output=True, text=True, check=True
        ).stdout
        for iface in json.loads(out):
            for addr in iface.get("addr_info", []):
                ip = addr.get("local", "")
                try:
                    if ipaddress.ip_address(ip) in ipaddress.ip_network("200::/7"):
                        return ip
                except ValueError:
                    continue
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError):
        pass
    return ""


def lan_ip() -> str:
    """Определить IP в локальной сети (адрес исходящего интерфейса)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("1.1.1.1", 80))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def os_family() -> str:
    """Семейство ОС для иконки на плитке: ubuntu/linux/windows/macos."""
    system = platform.system().lower()
    if system == "linux":
        try:
            data = Path("/etc/os-release").read_text(encoding="utf-8").lower()
            if "ubuntu" in data:
                return "ubuntu"
        except OSError:
            pass
        return "linux"
    if system == "darwin":
        return "macos"
    if system == "windows":
        return "windows"
    return system or "unknown"


def build_payload() -> str:
    """Сформировать JSON-профиль для QR-кода."""
    return json.dumps(
        {
            "v": QR_VERSION,
            "id": profile_id(),
            "name": socket.gethostname(),
            "host": zt_ip() or ygg_ip() or lan_ip(),
            "port": PORT,
            "token": read_token(),
            "os": os_family(),
        },
        ensure_ascii=False,
    )


def make_qr_image() -> Image.Image:
    """Сгенерировать изображение QR-кода с профилем."""
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=8, border=2)
    qr.add_data(build_payload())
    qr.make(fit=True)
    return qr.make_image(fill_color="black", back_color="white").convert("RGB")


def show_qr(icon=None, item=None) -> None:
    """Сохранить QR во временный PNG и открыть в просмотрщике."""
    img = make_qr_image()
    path = Path(tempfile.gettempdir()) / "rfid-pc-profile-qr.png"
    img.save(path)
    try:
        subprocess.Popen(["xdg-open", str(path)])
    except OSError:
        img.show()


def tray_icon_image() -> Image.Image:
    """Простая иконка трея: замок на сплошном фоне."""
    size = 64
    img = Image.new("RGB", (size, size), "#1565C0")
    d = ImageDraw.Draw(img)
    # тело замка
    d.rectangle([18, 30, 46, 52], fill="white")
    # дужка
    d.arc([20, 12, 44, 40], start=180, end=360, fill="white", width=4)
    return img


def main() -> int:
    icon = pystray.Icon(
        "rfid-agent",
        icon=tray_icon_image(),
        title="RFID Unlock — профиль ПК",
        menu=Menu(
            MenuItem("Показать QR-код", show_qr, default=True),
            MenuItem("Выход", lambda icon, item: icon.stop()),
        ),
    )
    icon.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
