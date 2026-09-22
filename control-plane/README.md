# Nivora Control

Control plane for Nivora VPN.

## First version
- private admin API protected by a bearer token;
- web admin panel at /admin;
- add, enable/disable, maintenance mode, delete VPN servers;
- SQLite server registry;
- client endpoint with only enabled/non-maintenance servers;
- heartbeat endpoint for load and active client count.

This foundation does not yet SSH into VPN servers or modify WireGuard peers remotely.

## Security
Never commit root passwords, WireGuard private keys, client private keys, or the real admin token.
VPN server private keys stay on each VPN server. Future Nivora clients generate their private key locally and send only the public key to the control plane.

## Run
    export NIVORA_ADMIN_TOKEN='replace-with-a-long-random-secret'
    python -m venv .venv
    . .venv/bin/activate
    pip install -r requirements.txt
    uvicorn app.main:app --host 0.0.0.0 --port 8080

Admin: http://SERVER:8080/admin
Health: GET /health
Client registry: GET /api/v1/public/servers

## Next
1. WireGuard server agent.
2. Device registration and public-key provisioning.
3. Automatic server selection and failover.
4. Admin audit log and proper login/session.
5. Connect Android Nivora so users never import .conf files manually.
