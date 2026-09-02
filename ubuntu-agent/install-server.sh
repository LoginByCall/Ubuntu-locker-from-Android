#!/usr/bin/env bash
#
# install-server.sh — установка TCP-сервера Ubuntu-агента как systemd user-сервиса.
#
# Что делает:
#   - копирует rfid-server.py, rfid-tray.py, rfid-confirm.py, rfid-askpass
#     в ~/.local/bin/;
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

# 1c. Подтверждение действий на телефоне (sudo и т. п.)
install -m 0755 "$SRC_DIR/rfid-confirm.py" "$BIN_DIR/rfid-confirm.py"
install -m 0755 "$SRC_DIR/rfid-askpass" "$BIN_DIR/rfid-askpass"
echo "Установлено подтверждение со смартфона: $BIN_DIR/rfid-askpass"

# 1d. Версия сборки: установленный агент лежит вне репозитория, поэтому
# номер кладём рядом с его конфигом (rfid-server.py --version читает его).
if [[ -s "$SRC_DIR/../VERSION" ]]; then
    install -m 0644 "$SRC_DIR/../VERSION" "$CONF_DIR/version"
    echo "Версия: $(cat "$CONF_DIR/version")"
fi

# 2. Токен
TOKEN_FILE="$CONF_DIR/token"
if [[ ! -s "$TOKEN_FILE" ]]; then
    head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 32 >"$TOKEN_FILE"
    chmod 0600 "$TOKEN_FILE"
    echo "Сгенерирован новый токен."
fi
chmod 0600 "$TOKEN_FILE"  # на случай токена, созданного старой версией
# В юнит токен НЕ попадает: файл юнита создаётся с правами 644 и читался бы
# любым локальным пользователем. Сервер берёт токен прямо из $TOKEN_FILE.

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

# 2c. Зависимости Python в изолированном venv (PEP 668: pip --user заблокирован).
# --system-site-packages — ради системного PyGObject (gi) для трея на GNOME.
#   pystray/qrcode/pillow — QR-код профиля;
#   google-auth/requests  — push на телефон (подтверждение sudo).
VENV_DIR="$HOME/.local/share/rfid-agent/venv"
PY_BIN="/usr/bin/env python3"
echo "Создание venv и установка зависимостей ..."
if python3 -m venv --system-site-packages "$VENV_DIR" 2>/dev/null && \
   "$VENV_DIR/bin/pip" install --quiet --upgrade pip >/dev/null 2>&1 && \
   "$VENV_DIR/bin/pip" install --quiet pystray "qrcode[pil]" pillow google-auth requests; then
    echo "Зависимости установлены в $VENV_DIR."
    PY_BIN="$VENV_DIR/bin/python"
else
    echo "ВНИМАНИЕ: venv не создан. Сервер запустится системным python3;"
    echo "QR-код и push (подтверждение sudo) работать не будут."
fi
echo

# 3. systemd user unit
UNIT_FILE="$UNIT_DIR/rfid-server.service"
cat >"$UNIT_FILE" <<EOF
[Unit]
Description=RFID Unlock TCP server (Ubuntu-RFID-Android)
After=graphical-session.target

[Service]
Type=simple
Environment=RFID_PORT=$PORT
ExecStart=$PY_BIN $BIN_DIR/rfid-server.py
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF
chmod 0600 "$UNIT_FILE"  # секретов в нём нет, но и читать посторонним незачем
echo "Создан unit: $UNIT_FILE"

# 4. Запуск
systemctl --user daemon-reload
systemctl --user enable rfid-server.service
# Именно restart, а не `enable --now`: последний не перезапускает уже
# работающий юнит, и обновление агента молча не применялось.
systemctl --user restart rfid-server.service
echo

# 4b. Иконка, пункт в списке программ и автозапуск трея
# (зависимости поставлены в шаге 2c).
if [[ -x "$VENV_DIR/bin/python" ]]; then
    # Иконку рисует сам трей — в репозитории картинки нет.
    ICON_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
    ICON_FILE="$ICON_DIR/rfid-agent.png"
    if "$VENV_DIR/bin/python" "$BIN_DIR/rfid-tray.py" --write-icon "$ICON_FILE"; then
        ICON_NAME="rfid-agent"
        echo "Иконка: $ICON_FILE"
    else
        ICON_NAME="changes-prevent-symbolic"  # запасной вариант из темы
    fi

    APPS_DIR="$HOME/.local/share/applications"
    mkdir -p "$APPS_DIR"
    # Пункт в списке программ и автозапуск — один и тот же .desktop с точностью
    # до строки автозапуска, поэтому пишем оба из одного шаблона.
    for target in "$APPS_DIR/rfid-agent.desktop" "$AUTOSTART_DIR/rfid-tray.desktop"; do
        cat >"$target" <<EOF
[Desktop Entry]
Type=Application
Name=RFID Unlock
GenericName=Профиль ПК для разблокировки по NFC
Comment=Иконка в трее: QR-код профиля этого ПК
Exec=$VENV_DIR/bin/python $BIN_DIR/rfid-tray.py
Icon=$ICON_NAME
Terminal=false
Categories=Utility;Security;
Keywords=RFID;NFC;lock;unlock;QR;
StartupNotify=false
X-GNOME-Autostart-enabled=true
EOF
    done
    echo "Создан пункт в списке программ: $APPS_DIR/rfid-agent.desktop"
    echo "Создан автозапуск: $AUTOSTART_DIR/rfid-tray.desktop"
    command -v update-desktop-database >/dev/null && update-desktop-database "$APPS_DIR" 2>/dev/null

    # Перезапуск трея: старый экземпляр остаётся на прежнем коде, а новый
    # сам откажется стартовать вторым (проверка в rfid-tray.py).
    if pkill -u "$USER" -f "$BIN_DIR/rfid-tray.py" 2>/dev/null; then
        echo "Прежний трей остановлен."
        sleep 1
    fi
    if [[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]]; then
        nohup "$VENV_DIR/bin/python" "$BIN_DIR/rfid-tray.py" >/dev/null 2>&1 &
        echo "Tray запущен."
    fi
else
    echo "ВНИМАНИЕ: venv не создан — QR-код и push будут недоступны."
fi
echo

# 5. Подсказка по подключению
IP="$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)"
echo "============================================================"
echo " RFID TCP-сервер запущен."
echo "   IP ПК:  ${IP:-<определите вручную>}"
echo "   Порт:   $PORT"
echo "   Токен:  $TOKEN_FILE (на экран не печатаем)"
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
