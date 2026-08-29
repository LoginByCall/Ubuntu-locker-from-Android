#!/usr/bin/env bash
#
# setup-vps.sh — развёртывание публичного Yggdrasil-пира на выделенном VPS
# (fallback для CGNAT, см. Архитектура-сетенезависимая-связь.md, раздел 3).
#
# Что делает:
#   1. По SSH из peer.local ставит yggdrasil на VPS (apt), генерирует конфиг:
#      Listen tls://[::]:$YGG_PORT, AllowedPublicKeys = [ПК, телефон], Peers=[].
#   2. Записывает PEER_URI в peer.local.
#   3. Добавляет PEER_URI в Peers конфига ПК (/etc/yggdrasil) и телефона
#      (phone.conf.local), перезапускает yggdrasil на ПК.
#
# Требования: peer.local с VPS_SSH; yggdrasil настроен на ПК; phone.conf.local.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
YGG_PORT="${YGG_PORT:-7642}"

source "$DIR/peer.local"
[[ -n "${VPS_SSH:-}" ]] || { echo "peer.local: нет VPS_SSH"; exit 1; }
VPS_IP="$(grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' <<<"$VPS_SSH" | head -1)"

PC_PUB="$(sudo yggdrasil -useconffile /etc/yggdrasil/yggdrasil.conf -publickey)"
PH_PUB="$(yggdrasil -useconffile "$DIR/phone.conf.local" -publickey)"
echo "ПК:      $PC_PUB"
echo "Телефон: $PH_PUB"

# 1. VPS: установка и конфиг
${VPS_SSH#ssh } bash -s -- "$YGG_PORT" "$PC_PUB" "$PH_PUB" <<'REMOTE'
set -euo pipefail
PORT="$1"; PC="$2"; PH="$3"
command -v yggdrasil >/dev/null || { apt-get update -qq && apt-get install -y yggdrasil; }
mkdir -p /etc/yggdrasil
[[ -s /etc/yggdrasil/yggdrasil.conf ]] || yggdrasil -genconf -json > /etc/yggdrasil/yggdrasil.conf
python3 - "$PORT" "$PC" "$PH" <<'PY'
import json, sys
p = "/etc/yggdrasil/yggdrasil.conf"
c = json.load(open(p))
c["Listen"] = [f"tls://[::]:{sys.argv[1]}"]
c["AllowedPublicKeys"] = [sys.argv[2], sys.argv[3]]
c["Peers"] = []
json.dump(c, open(p, "w"), indent=2)
PY
chown -R yggdrasil:yggdrasil /etc/yggdrasil 2>/dev/null || true
chmod 600 /etc/yggdrasil/yggdrasil.conf
systemctl enable --now yggdrasil
systemctl restart yggdrasil
sleep 2 && systemctl is-active yggdrasil
yggdrasil -useconffile /etc/yggdrasil/yggdrasil.conf -publickey
REMOTE

PEER_URI="tls://$VPS_IP:$YGG_PORT"
sed -i "s|^PEER_URI=.*|PEER_URI=\"$PEER_URI\"|" "$DIR/peer.local"
echo "PEER_URI=$PEER_URI записан в peer.local"

# 2. ПК и телефон: добавить пира
sudo python3 - "$PEER_URI" <<'PY'
import json, sys
p = "/etc/yggdrasil/yggdrasil.conf"
c = json.load(open(p))
if sys.argv[1] not in c["Peers"]:
    c["Peers"].append(sys.argv[1])
json.dump(c, open(p, "w"), indent=2)
PY
sudo chown yggdrasil:yggdrasil /etc/yggdrasil/yggdrasil.conf
sudo systemctl restart yggdrasil
python3 - "$PEER_URI" "$DIR/phone.conf.local" <<'PY'
import json, sys
c = json.load(open(sys.argv[2]))
if sys.argv[1] not in c["Peers"]:
    c["Peers"].append(sys.argv[1])
json.dump(c, open(sys.argv[2], "w"), indent=2)
PY
echo "Готово. Телефону нужен обновлённый phone.conf.local (реимпорт в Yggdrasil-приложении)."
