#!/usr/bin/env bash
#
# setup-pc.sh — приватная Yggdrasil-mesh «ПК + телефон» на стороне ПК.
# (устаревший слой, оставлен для истории)
#
# Что делает:
#   1. Ставит yggdrasil (apt) на ПК.
#   2. Генерирует конфиг ПК (/etc/yggdrasil/yggdrasil.conf) и конфиг телефона
#      (phone.conf.local — приватный ключ телефона, в git не попадает).
#   3. Связывает их взаимно через AllowedPublicKeys (изоляция mesh);
#      multicast оставлен — автопиринг в общей LAN без настройки.
#   4. Слушает tls://[::]:$YGG_PORT (сценарий проброса порта на роутере).
#   5. Запускает сервис и печатает Ygg-адреса обоих узлов.
#
# Телефон: установить приложение Yggdrasil (Google Play / F-Droid),
# импортировать phone.conf.local, включить VPN.
# CGNAT-fallback через VPS: setup-vps.sh (после заполнения peer.local).

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
YGG_PORT="${YGG_PORT:-7642}"
PHONE_CONF="$DIR/phone.conf.local"

command -v yggdrasil >/dev/null || sudo apt-get install -y yggdrasil

# Конфиги (существующие не трогаем — ключи должны быть стабильны)
[[ -s "$PHONE_CONF" ]] || { yggdrasil -genconf -json > "$PHONE_CONF"; chmod 600 "$PHONE_CONF"; }
sudo test -s /etc/yggdrasil/yggdrasil.conf || {
    sudo mkdir -p /etc/yggdrasil
    sudo sh -c 'yggdrasil -genconf -json > /etc/yggdrasil/yggdrasil.conf'
}

PC_PUB="$(sudo yggdrasil -useconffile /etc/yggdrasil/yggdrasil.conf -publickey)"
PH_PUB="$(yggdrasil -useconffile "$PHONE_CONF" -publickey)"

sudo python3 - "$YGG_PORT" "$PH_PUB" <<'PY'
import json, sys
p = "/etc/yggdrasil/yggdrasil.conf"
c = json.load(open(p))
c["Listen"] = [f"tls://[::]:{sys.argv[1]}"]
c["AllowedPublicKeys"] = [sys.argv[2]]
c.setdefault("Peers", [])
json.dump(c, open(p, "w"), indent=2)
PY
python3 - "$PC_PUB" "$PHONE_CONF" <<'PY'
import json, sys
c = json.load(open(sys.argv[2]))
c["AllowedPublicKeys"] = [sys.argv[1]]
c.setdefault("Peers", [])
json.dump(c, open(sys.argv[2], "w"), indent=2)
PY

# Права: сервис работает от пользователя yggdrasil
sudo chown -R yggdrasil:yggdrasil /etc/yggdrasil
sudo chmod 750 /etc/yggdrasil
sudo chmod 640 /etc/yggdrasil/yggdrasil.conf
sudo systemctl enable --now yggdrasil
sudo systemctl restart yggdrasil
sleep 2

echo "============================================================"
echo " Yggdrasil: $(systemctl is-active yggdrasil)"
echo " Ygg-адрес ПК:       $(sudo yggdrasil -useconffile /etc/yggdrasil/yggdrasil.conf -address)"
echo " Ygg-адрес телефона: $(yggdrasil -useconffile "$PHONE_CONF" -address)"
echo " Конфиг телефона:    $PHONE_CONF (импортировать в приложение Yggdrasil)"
echo " После подключения телефона перескануйте QR из трея —"
echo " профиль ПК обновится на стабильный Ygg-адрес."
echo "============================================================"
