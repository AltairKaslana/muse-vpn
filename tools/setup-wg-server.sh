#!/usr/bin/env bash
# setup-wg-server.sh - pasang WireGuard server di VPS Linux ber-IP publik.
# Pakai:  bash setup-wg-server.sh [PORT] [NAMA_CLIENT]
#         contoh: bash setup-wg-server.sh 51820 muse-id
# Jalankan sebagai root. Setelah selesai, file .conf client ada di /root/<NAMA_CLIENT>.conf
set -euo pipefail

PORT="${1:-51820}"
CLIENT="${2:-muse-id}"
WG_DIR="/etc/wireguard"
SUBNET="10.8.0"

if [ "$(id -u)" -ne 0 ]; then
    echo "Jalankan sebagai root (sudo bash $0 ...)" >&2
    exit 1
fi

if ! command -v wg >/dev/null 2>&1; then
    echo ">> memasang wireguard-tools ..."
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq wireguard
fi

mkdir -p "$WG_DIR"
chmod 700 "$WG_DIR"
umask 077

# Kunci: sekali buat, lalu dipakai ulang supaya client tidak perlu dibuat baru.
[ -f "$WG_DIR/server.key" ] || wg genkey > "$WG_DIR/server.key"
[ -f "$WG_DIR/server.pub" ] || wg pubkey < "$WG_DIR/server.key" > "$WG_DIR/server.pub"
[ -f "$WG_DIR/client.key" ] || wg genkey > "$WG_DIR/client.key"
[ -f "$WG_DIR/client.pub" ] || wg pubkey < "$WG_DIR/client.key" > "$WG_DIR/client.pub"

SRV_PRIV="$(cat "$WG_DIR/server.key")"
SRV_PUB="$(cat "$WG_DIR/server.pub")"
CLI_PRIV="$(cat "$WG_DIR/client.key")"
CLI_PUB="$(cat "$WG_DIR/client.pub")"

EXT_IF="$(ip route get 1.1.1.1 2>/dev/null | grep -oP 'dev \K\S+' | head -1)"
if [ -z "$EXT_IF" ]; then
    echo "Tidak bisa menentukan interface keluar." >&2
    exit 1
fi

PUB_IP="$(curl -s --max-time 10 https://api.ipify.org || true)"
if [ -z "$PUB_IP" ]; then
    PUB_IP="$(hostname -I | awk '{print $1}')"
fi

cat > "$WG_DIR/wg0.conf" <<EOF
[Interface]
Address = ${SUBNET}.1/24
ListenPort = ${PORT}
PrivateKey = ${SRV_PRIV}
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o ${EXT_IF} -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o ${EXT_IF} -j MASQUERADE

[Peer]
PublicKey = ${CLI_PUB}
AllowedIPs = ${SUBNET}.2/32
EOF
chmod 600 "$WG_DIR/wg0.conf"

sysctl -w net.ipv4.ip_forward=1 >/dev/null
grep -q '^net.ipv4.ip_forward=1' /etc/sysctl.conf 2>/dev/null || echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf

if command -v ufw >/dev/null 2>&1; then
    ufw allow "${PORT}/udp" >/dev/null 2>&1 || true
fi
iptables -I INPUT -p udp --dport "$PORT" -j ACCEPT || true

systemctl enable "wg-quick@wg0" >/dev/null 2>&1 || true
systemctl restart "wg-quick@wg0"

cat > "/root/${CLIENT}.conf" <<EOF
[Interface]
PrivateKey = ${CLI_PRIV}
Address = ${SUBNET}.2/32
DNS = 1.1.1.1

[Peer]
PublicKey = ${SRV_PUB}
Endpoint = ${PUB_IP}:${PORT}
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
EOF
chmod 600 "/root/${CLIENT}.conf"

echo "=================================================="
echo "SERVER publik : ${PUB_IP}:${PORT} (UDP, interface ${EXT_IF})"
echo "Config client : /root/${CLIENT}.conf"
echo "Status tunnel :"
wg show wg0 2>/dev/null | head -4 || echo "(wg show gagal, cek: systemctl status wg-quick@wg0)"
echo "=================================================="
echo "Ambil file .conf lalu impor di aplikasi Muse VPN."
echo "PENTING: file itu memuat private key - pindahkan lewat scp, jangan lewat chat."
