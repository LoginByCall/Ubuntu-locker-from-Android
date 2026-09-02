#!/usr/bin/env bash
#
# install-pam.sh — подтверждение sudo телефоном вместо пароля (через PAM).
#
#   sudo ./install-pam.sh              установить
#   sudo ./install-pam.sh --uninstall  вернуть как было
#
# В /etc/pam.d/sudo добавляется строка
#   auth sufficient pam_exec.so quiet /usr/local/bin/rfid-pam-confirm
# Подтвердил на телефоне — sudo пускает; отказ или таймаут — обычный пароль
# (fail-closed, ничего не ослабляется). Пароль после этого нигде не хранится:
# связку ключей можно очистить, а SUDO_ASKPASS с alias убрать из ~/.zshrc.
#
# СТРАХОВКА: прежний файл сохраняется рядом, и ставится таймер автоотката на
# 10 минут. Если что-то пошло не так — достаточно подождать.
set -euo pipefail

PAM_FILE=/etc/pam.d/sudo
LINE="auth sufficient pam_exec.so quiet /usr/local/bin/rfid-pam-confirm"
LIB_DIR=/usr/local/lib/rfid-agent
HELPER=/usr/local/bin/rfid-pam-confirm
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[[ $EUID -eq 0 ]] || { echo "Запускать через sudo." >&2; exit 1; }

if [[ "${1:-}" == "--uninstall" ]]; then
    latest="$(ls -1t "$PAM_FILE".rfid-backup-* 2>/dev/null | head -1 || true)"
    if [[ -n "$latest" ]]; then
        cp "$latest" "$PAM_FILE"
        echo "Восстановлен $PAM_FILE из $latest"
    else
        grep -vxF "$LINE" "$PAM_FILE" > "$PAM_FILE.tmp"
        mv "$PAM_FILE.tmp" "$PAM_FILE"
        chmod 0644 "$PAM_FILE"
        echo "Строка убрана из $PAM_FILE (бэкапа не нашлось)"
    fi
    rm -f "$HELPER"
    rm -rf "$LIB_DIR"
    echo "Готово. Подтверждение sudo телефоном выключено."
    exit 0
fi

# 1. Код, исполняемый от root, — в root-owned каталоге. Копия в домашнем
# каталоге не годится: право её править означало бы право стать root.
install -d -m 0755 -o root -g root "$LIB_DIR"
install -m 0755 -o root -g root "$SRC_DIR/rfid-confirm.py" "$LIB_DIR/rfid-confirm.py"
install -m 0755 -o root -g root "$SRC_DIR/rfid-pam-confirm" "$HELPER"
echo "Установлено: $HELPER, $LIB_DIR/rfid-confirm.py"

# 2. Бэкап и таймер автоотката — до правки, а не после.
BACKUP="$PAM_FILE.rfid-backup-$(date +%Y%m%d-%H%M%S)"
cp "$PAM_FILE" "$BACKUP"
echo "Бэкап: $BACKUP"
if command -v systemd-run >/dev/null; then
    systemd-run --quiet --unit=rfid-pam-revert --on-active=10min \
        /bin/cp "$BACKUP" "$PAM_FILE"
    echo "Автооткат через 10 минут. Проверьте sudo и отмените таймер:"
    echo "    sudo systemctl stop rfid-pam-revert.timer"
fi

# 3. Правка: строка должна идти до common-auth, иначе перехватывать нечего.
if grep -qxF "$LINE" "$PAM_FILE"; then
    echo "Строка уже есть — файл не менялся."
else
    awk -v line="$LINE" '
        !inserted && /^@include common-auth/ { print line; inserted = 1 }
        { print }
        END { if (!inserted) print line }
    ' "$PAM_FILE" > "$PAM_FILE.tmp"
    mv "$PAM_FILE.tmp" "$PAM_FILE"
    chmod 0644 "$PAM_FILE"
    echo "Добавлено в $PAM_FILE:"
    echo "    $LINE"
fi

cat <<'TXT'

Проверьте в СОСЕДНЕМ терминале, не закрывая этот:
    sudo -k; sudo true
На телефоне появится запрос — подтвердите, sudo должен пройти без пароля.

Работает — отмените автооткат:  sudo systemctl stop rfid-pam-revert.timer
Не работает — ничего не делайте, через 10 минут вернётся как было
(или сразу: sudo ./install-pam.sh --uninstall).

После успешной проверки пароль sudo больше не нужен ни на диске, ни в связке:
    secret-tool clear service rfid-agent user "$USER"
и уберите из ~/.zshrc строки SUDO_ASKPASS и alias sudo="sudo -A".
TXT
