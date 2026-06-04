#!/usr/bin/env bash
#
# rfid-agent.sh — Ubuntu-агент проекта Ubuntu-RFID-Android.
# Выполняет блокировку/разблокировку текущей GNOME-сессии (X11) по команде,
# логирует событие и отправляет подтверждение обратно на смартфон через
# обратный канал GSConnect (KDE Connect ping).
#
# Использование:
#   rfid-agent.sh lock   [reqId]
#   rfid-agent.sh unlock [reqId]
#   rfid-agent.sh status
#
# Запускается плагином GSConnect "Run Command" по запросу со смартфона.
# Этап 1 (MVP): без прикладного шифрования (полагаемся на TLS+сопряжение KDE Connect).

set -euo pipefail

# --- Конфигурация ----------------------------------------------------------

GSCONNECT_DEST="org.gnome.Shell.Extensions.GSConnect"
GSCONNECT_BASE="/org/gnome/Shell/Extensions/GSConnect"
LOG_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/rfid-agent"
LOG_FILE="$LOG_DIR/rfid-agent.log"

mkdir -p "$LOG_DIR"

# --- Утилиты ---------------------------------------------------------------

log() {
    local level="$1"; shift
    local ts
    ts="$(date '+%Y-%m-%d %H:%M:%S')"
    printf '%s [%s] %s\n' "$ts" "$level" "$*" >>"$LOG_FILE"
    printf '%s [%s] %s\n' "$ts" "$level" "$*" >&2
}

# Определить id текущей графической сессии пользователя.
current_session_id() {
    # Сессия, привязанная к текущему процессу, либо первая графическая сессия пользователя.
    local sid
    sid="$(loginctl show-session "$(loginctl list-sessions --no-legend | awk '$3=="'"$USER"'"{print $1; exit}')" -p Id --value 2>/dev/null || true)"
    if [[ -z "${sid}" ]]; then
        sid="$(loginctl list-sessions --no-legend | awk '$3=="'"$USER"'"{print $1; exit}')"
    fi
    printf '%s' "$sid"
}

# Найти object path первого подключённого устройства GSConnect (смартфон).
gsconnect_device_path() {
    gdbus call --session --dest "$GSCONNECT_DEST" \
        --object-path "$GSCONNECT_BASE" \
        --method org.freedesktop.DBus.ObjectManager.GetManagedObjects 2>/dev/null \
        | grep -oE "$GSCONNECT_BASE/Device/[0-9a-f]+" \
        | head -1 || true
}

# Отправить подтверждение на смартфон через ping-плагин GSConnect.
send_confirmation() {
    local message="$1"
    local dev
    dev="$(gsconnect_device_path)"
    if [[ -z "$dev" ]]; then
        log WARN "Не найдено устройство GSConnect — подтверждение не отправлено"
        return 1
    fi
    if gdbus call --session --dest "$GSCONNECT_DEST" \
        --object-path "$dev" \
        --method org.gtk.Actions.Activate \
        "ping" "[<'$message'>]" "{}" >/dev/null 2>&1; then
        log INFO "Подтверждение отправлено на смартфон: $message"
        return 0
    else
        log WARN "Не удалось отправить подтверждение на смартфон"
        return 1
    fi
}

# --- Действия --------------------------------------------------------------

do_lock() {
    local req_id="$1"
    local sid
    sid="$(current_session_id)"
    log INFO "LOCK запрошен (reqId=${req_id:-none}, session=$sid)"
    if loginctl lock-session "$sid"; then
        log INFO "Сессия заблокирована (session=$sid)"
        send_confirmation "RFID OK: LOCK${req_id:+ #$req_id}" || true
    else
        log ERROR "Не удалось заблокировать сессию (session=$sid)"
        send_confirmation "RFID ERR: LOCK${req_id:+ #$req_id}" || true
        return 1
    fi
}

do_unlock() {
    local req_id="$1"
    local sid
    sid="$(current_session_id)"
    log INFO "UNLOCK запрошен (reqId=${req_id:-none}, session=$sid)"
    if loginctl unlock-session "$sid"; then
        log INFO "Сессия разблокирована (session=$sid)"
        send_confirmation "RFID OK: UNLOCK${req_id:+ #$req_id}" || true
    else
        log ERROR "Не удалось разблокировать сессию (session=$sid)"
        send_confirmation "RFID ERR: UNLOCK${req_id:+ #$req_id}" || true
        return 1
    fi
}

do_status() {
    local sid
    sid="$(current_session_id)"
    local locked
    locked="$(loginctl show-session "$sid" -p LockedHint --value 2>/dev/null || echo unknown)"
    log INFO "STATUS: session=$sid LockedHint=$locked"
    printf 'session=%s LockedHint=%s\n' "$sid" "$locked"
}

# --- Точка входа -----------------------------------------------------------

main() {
    local cmd="${1:-}"
    local req_id="${2:-}"
    case "$cmd" in
        lock)   do_lock   "$req_id" ;;
        unlock) do_unlock "$req_id" ;;
        status) do_status ;;
        *)
            echo "Использование: $0 {lock|unlock|status} [reqId]" >&2
            exit 2
            ;;
    esac
}

main "$@"
