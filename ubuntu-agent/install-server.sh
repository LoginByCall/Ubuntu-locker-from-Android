#!/usr/bin/env bash
#
# install-server.sh — установка TCP-сервера Ubuntu-агента как systemd user-сервиса.
#
# Что делает:
#   - копирует rfid-server.py в ~/.local/bin/rfid-server.py;
#   - генерирует общий токен (если ещё не задан) в ~/.config/rfid-agent/token;
#   - создаёт и включает user-сервис rfid-server.service (автозапуск при входе);
#   - выводит IP, порт и токен для ввода в Android-приложении.

set -euo pipefail

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="$HOME/.local/bin"
CONF_DIR="$HOME/.config/rfid-agent"
UNIT_DIR="$HOME/.config/systemd/user"
AUTOSTART_DIR="$HOME/.config/autostart"
PORT="${RFID_PORT:-5390}"

mkdir -p "$BIN_DIR" "$CONF_DIR" "$UNIT_DIR" "$AUTOSTART_DIR"

# 1. Скрипт сервера
install -m 0755 "$SRC_DIR/rfid-server.py" "$BIN_DIR/rfid-server.py"
echo "Установлен сервер: $BIN_DIR/rfid-server.py"

# 1b. Tray-приложение для показа QR-кода профиля ПК
install -m 0755 "$SRC_DIR/rfid-tray.py" "$BIN_DIR/rfid-tray.py"
echo "Установлен tray: $BIN_DIR/rfid-tray.py"

# 2. Токен
TOKEN_FILE="$CONF_DIR/token"
if [[ ! -s "$TOKEN_FILE" ]]; then
    head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 32 >"$TOKEN_FILE"
    chmod 0600 "$TOKEN_FILE"
    echo "Сгенерирован новый токен."
fi
TOKEN="$(cat "$TOKEN_FILE")"

# 2b. Параметры ZeroTier для QR (встроенный узел libzt в приложении).
# Без zt-network QR отдаёт ZT-адрес ПК, но приложение идёт «напрямую» → «недоступен».
# Берём из системного ZeroTier, если файлы ещё не созданы вручную (см. README).
for ZT_HOME in /var/lib/zerotier-one /var/snap/zerotier/common; do
    [[ -d "$ZT_HOME/networks.d" ]] || continue
    if [[ ! -s "$CONF_DIR/zt-network" ]]; then
        # ponytail: первая сеть; несколько сетей — задать zt-network вручную
        NW="$(basename -a "$ZT_HOME"/networks.d/*.conf 2>/dev/null | cut -d. -f1 | head -1)"
        [[ -n "$NW" ]] && printf '%s' "$NW" >"$CONF_DIR/zt-network" && echo "zt-network: $NW"
    fi
    if [[ ! -s "$CONF_DIR/zt-moon" ]]; then
        MOON="$(ls "$ZT_HOME"/moons.d/*.moon 2>/dev/null | head -1)"
        if [[ -n "$MOON" ]]; then
            basename "$MOON" .moon | sed 's/^0*//' | tr -d '\n' >"$CONF_DIR/zt-moon"
            { printf '\x01'; tail -c +2 "$MOON"; } >"$CONF_DIR/zt-roots"
            echo "zt-moon/zt-roots: $(cat "$CONF_DIR/zt-moon")"
        fi
    fi
    break
done

# 3. systemd user unit
UNIT_FILE="$UNIT_DIR/rfid-server.service"
cat >"$UNIT_FILE" <<EOF
[Unit]
Description=RFID Unlock TCP server (Ubuntu-RFID-Android)
After=graphical-session.target

[Service]
Type=simple
Environment=RFID_PORT=$PORT
Environment=RFID_TOKEN=$TOKEN
ExecStart=/usr/bin/env python3 $BIN_DIR/rfid-server.py
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF
echo "Создан unit: $UNIT_FILE"

# 4. Запуск
systemctl --user daemon-reload
systemctl --user enable --now rfid-server.service
echo

# 4b. Зависимости для tray-приложения (QR-код) и автозапуск.
# Используем изолированный venv (на современных Ubuntu pip --user заблокирован, PEP 668).
# --system-site-packages — чтобы был доступен системный PyGObject (gi) для
# appindicator-бэкенда трея на GNOME/Ubuntu.
VENV_DIR="$HOME/.local/share/rfid-agent/venv"
echo "Создание venv и установка зависимостей tray (pystray qrcode pillow) ..."
if python3 -m venv --system-site-packages "$VENV_DIR" 2>/dev/null && \
   "$VENV_DIR/bin/pip" install --quiet --upgrade pip >/dev/null 2>&1 && \
   "$VENV_DIR/bin/pip" install --quiet pystray "qrcode[pil]" pillow; then
    echo "Зависимости tray установлены в $VENV_DIR."
    DESKTOP_FILE="$AUTOSTART_DIR/rfid-tray.desktop"
    cat >"$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Name=RFID Unlock — профиль ПК
Comment=Иконка в трее для показа QR-кода профиля ПК
Exec=$VENV_DIR/bin/python $BIN_DIR/rfid-tray.py
Icon=changes-prevent-symbolic
Terminal=false
X-GNOME-Autostart-enabled=true
EOF
    echo "Создан автозапуск: $DESKTOP_FILE"
    # запустить tray сразу (если есть графическая сессия)
    if [[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]]; then
        nohup "$VENV_DIR/bin/python" "$BIN_DIR/rfid-tray.py" >/dev/null 2>&1 &
        echo "Tray запущен."
    fi
else
    echo "ВНИМАНИЕ: не удалось установить зависимости tray. QR-код будет недоступен."
    echo "Установите вручную:"
    echo "  python3 -m venv $VENV_DIR"
    echo "  $VENV_DIR/bin/pip install pystray 'qrcode[pil]' pillow"
fi
echo

# 5. Подсказка по подключению
IP="$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)"
echo "============================================================"
echo " RFID TCP-сервер запущен."
echo "   IP ПК:  ${IP:-<определите вручную>}"
echo "   Порт:   $PORT"
echo "   Токен:  $TOKEN"
echo
echo " Подключение в Android-приложении:"
echo "   - проще всего: иконка в трее → «Показать QR-код» → сканировать;"
echo "   - вручную: ввести IP/порт/токен на экране настроек."
echo
echo " На GNOME для иконки в трее нужно расширение AppIndicator"
echo " (sudo apt install gnome-shell-extension-appindicator,"
echo "  затем включить «Ubuntu AppIndicators» и перелогиниться)."
echo " Журнал: ~/.local/state/rfid-agent/rfid-server.log"
echo " Статус: systemctl --user status rfid-server.service"
echo "============================================================"
