#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${ENEIDA_REPO_URL:-https://github.com/beloav8765-alt/KZ-VPN-Android.git}"
INSTALL_DIR="${ENEIDA_INSTALL_DIR:-/opt/eneida}"
DOMAIN="${1:-}"

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root."
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl git openssl caddy docker.io docker-compose-plugin

systemctl enable --now docker

if [ -z "$DOMAIN" ]; then
  IP="$(hostname -I | awk '{print $1}')"
  if [ -z "$IP" ]; then
    echo "Could not detect public IP. Pass a domain as the first argument."
    exit 1
  fi
  DOMAIN="${IP//./-}.sslip.io"
fi

if [ -d "$INSTALL_DIR/.git" ]; then
  git -C "$INSTALL_DIR" fetch --all --prune
  git -C "$INSTALL_DIR" reset --hard origin/main
else
  rm -rf "$INSTALL_DIR"
  git clone --depth 1 "$REPO_URL" "$INSTALL_DIR"
fi

ADMIN_TOKEN="$(openssl rand -base64 36 | tr -d '\n' | tr '/+' '_-')"

cat > "$INSTALL_DIR/control-plane/.env" <<EOF
ENEIDA_ADMIN_TOKEN=$ADMIN_TOKEN
ENEIDA_PUBLIC_BASE_URL=https://$DOMAIN
ENEIDA_DB=/data/eneida.db
ENEIDA_PLAN_PRICE_RUB=399
ENEIDA_PLAN_DAYS=30
ENEIDA_PAYMENT_TTL_MINUTES=30
ENEIDA_MONERO_CONFIRMATIONS=1
ENEIDA_VPN_DNS=8.8.8.8
ENEIDA_VPN_MTU=1280
ENEIDA_AGENT_POLL_SECONDS=5
ENEIDA_SERVER_STALE_SECONDS=90
ENEIDA_MONERO_RPC_URL=
ENEIDA_MONERO_RPC_USER=
ENEIDA_MONERO_RPC_PASSWORD=
ENEIDA_XMR_RUB_RATE=
EOF
chmod 600 "$INSTALL_DIR/control-plane/.env"

cd "$INSTALL_DIR/control-plane"
docker compose --env-file .env up -d --build

cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
    encode zstd gzip
    reverse_proxy 127.0.0.1:8080
}
EOF
caddy validate --config /etc/caddy/Caddyfile
systemctl enable --now caddy
systemctl restart caddy

if command -v ufw >/dev/null 2>&1; then
  ufw allow 80/tcp >/dev/null || true
  ufw allow 443/tcp >/dev/null || true
fi

echo
echo "========================================"
echo "Eneida Control installed"
echo "Admin: https://$DOMAIN/admin"
echo "API:   https://$DOMAIN"
echo
echo "ADMIN TOKEN (save it now):"
echo "$ADMIN_TOKEN"
echo "========================================"
echo
echo "The token is stored in:"
echo "$INSTALL_DIR/control-plane/.env"
