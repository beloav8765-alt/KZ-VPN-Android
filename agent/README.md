# Eneida Agent

Eneida Agent runs on every WireGuard VPN node.

It never receives or stores the server private key. It controls the already-running WireGuard interface through the local "wg" command and stores only client public keys, device IDs and assigned tunnel addresses.

## API

All endpoints require:

    Authorization: Bearer <ENEIDA_AGENT_TOKEN>

Endpoints:
- GET /health
- GET /v1/peers
- POST /v1/peers
- DELETE /v1/peers/{device_id}

POST /v1/peers body:

    {
      "device_id": "stable-device-id",
      "public_key": "wireguard-client-public-key"
    }

The agent allocates an address from ENEIDA_AGENT_ADDRESS_POOL and applies the peer to wg0.

## Install

    sudo mkdir -p /opt/eneida-agent
    sudo cp agent.py requirements.txt /opt/eneida-agent/
    cd /opt/eneida-agent
    python3 -m venv .venv
    .venv/bin/pip install -r requirements.txt

Create /etc/eneida-agent.env with a strong unique token. Do not commit it.

Then install the systemd unit and start it.

By default the agent binds only to 127.0.0.1:8091. If the control plane is on another host, expose the agent only through a private network or an authenticated HTTPS reverse proxy. Do not expose the raw agent port publicly.
