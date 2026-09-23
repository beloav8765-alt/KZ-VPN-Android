# Eneida Agent

Eneida Agent runs on each WireGuard node and connects **outbound** to Eneida Control over HTTPS.

It:
- sends CPU/RAM/load and active-client health;
- receives the desired WireGuard peer list;
- applies only peers managed by Eneida;
- never sends or stores the WireGuard server private key in Eneida Control.

The raw agent port is no longer exposed to the internet.

Required environment variables:

    ENEIDA_CONTROL_URL=https://control.example.com
    ENEIDA_SERVER_ID=1
    ENEIDA_AGENT_TOKEN=<unique secret>
    ENEIDA_AGENT_INTERFACE=wg0

The one-click VPS installer in Eneida Admin creates these values automatically.
