#!/usr/bin/env bash
#
# install.sh — установка Ubuntu-агента проекта Ubuntu-RFID-Android.
#
# Действия:
#   1. Копирует rfid-agent.sh в ~/.local/bin/rfid-agent (стабильный путь без спецсимволов).
#   2. Регистрирует в плагине GSConnect "Run Command" две команды:
#        - "RFID Unlock" -> rfid-agent unlock
#        - "RFID Lock"   -> rfid-agent lock
#      Существующие команды пользователя сохраняются.
#
# Требования: установленный и сопряжённый GSConnect, python3, dconf.

set -euo pipefail

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="${HOME}/.local/bin"
TARGET="${BIN_DIR}/rfid-agent"

echo "==> Установка бинарника агента в ${TARGET}"
mkdir -p "${BIN_DIR}"
install -m 0755 "${SRC_DIR}/rfid-agent.sh" "${TARGET}"

# --- Регистрация команд в GSConnect Run Command ----------------------------

echo "==> Регистрация команд в GSConnect (Run Command)"

# Найти id устройства GSConnect (первое в списке).
DEVICE_ID="$(dconf read /org/gnome/shell/extensions/gsconnect/devices 2>/dev/null \
    | tr -d "[]' " | cut -d, -f1)"

if [[ -z "${DEVICE_ID}" ]]; then
    echo "!! Устройство GSConnect не найдено. Сопрягите смартфон и запустите снова." >&2
    exit 1
fi
echo "    Устройство: ${DEVICE_ID}"

DCONF_PATH="/org/gnome/shell/extensions/gsconnect/device/${DEVICE_ID}/plugin/runcommand/command-list"

# Слить существующие команды с нашими (стабильные ключи rfid-lock / rfid-unlock).
CURRENT="$(dconf read "${DCONF_PATH}" 2>/dev/null || true)"

NEW_VALUE="$(python3 - "$TARGET" "$CURRENT" <<'PY'
import sys, ast
target = sys.argv[1]
current_raw = sys.argv[2] if len(sys.argv) > 2 else ""

# GVariant a{sv}; в строковом виде GSConnect это python-совместимый dict
# вида {'uuid': {'name': '...', 'command': '...'}} без типовых аннотаций <>.
# dconf отдаёт строку с <...> для variant — очистим аннотации для парсинга.
def parse(raw):
    raw = raw.strip()
    if not raw:
        return {}
    raw = raw.replace('<', '').replace('>', '')
    try:
        return ast.literal_eval(raw)
    except Exception:
        return {}

data = parse(current_raw)

data['rfid-unlock'] = {'name': 'RFID Unlock', 'command': f'{target} unlock'}
data['rfid-lock']   = {'name': 'RFID Lock',   'command': f'{target} lock'}

# Сериализовать обратно в GVariant a{sv} с аннотацией <...> у значений.
parts = []
for key, val in data.items():
    name = val.get('name', '').replace("'", "\\'")
    cmd  = val.get('command', '').replace("'", "\\'")
    parts.append(f"'{key}': <{{'name': '{name}', 'command': '{cmd}'}}>")
print('{' + ', '.join(parts) + '}')
PY
)"

dconf write "${DCONF_PATH}" "${NEW_VALUE}"
echo "    Команды зарегистрированы: RFID Unlock, RFID Lock"

echo
echo "==> Готово."
echo "    Бинарник: ${TARGET}"
echo "    Проверка: ${TARGET} status"
echo "    На смартфоне команды появятся в виджете/плагине Run Command GSConnect."
