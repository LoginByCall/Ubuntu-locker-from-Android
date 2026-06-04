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
PORT="${RFID_PORT:-5390}"

mkdir -p "$BIN_DIR" "$CONF_DIR" "$UNIT_DIR"

# 1. Скрипт сервера
install -m 0755 "$SRC_DIR/rfid-server.py" "$BIN_DIR/rfid-server.py"
echo "Установлен сервер: $BIN_DIR/rfid-server.py"

# 2. Токен
TOKEN_FILE="$CONF_DIR/token"
if [[ ! -s "$TOKEN_FILE" ]]; then
    head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 32 >"$TOKEN_FILE"
    chmod 0600 "$TOKEN_FILE"
    echo "Сгенерирован новый токен."
fi
TOKEN="$(cat "$TOKEN_FILE")"

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

# 5. Подсказка по подключению
IP="$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)"
echo "============================================================"
echo " RFID TCP-сервер запущен."
echo "   IP ПК:  ${IP:-<определите вручную>}"
echo "   Порт:   $PORT"
echo "   Токен:  $TOKEN"
echo
echo " Введите эти данные в Android-приложении (экран Настройки)."
echo " Журнал: ~/.local/state/rfid-agent/rfid-server.log"
echo " Статус: systemctl --user status rfid-server.service"
echo "============================================================"
