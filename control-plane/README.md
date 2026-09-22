# Eneida Control

Control plane for Eneida VPN.

## VPN management
- private admin API;
- server registry;
- enable/disable and maintenance mode;
- server load and client count;
- public list of available VPN nodes.

## Payments

The agreed tariff is 399 RUB for 30 days.

Payment flow:
1. Android creates an order.
2. Eneida Control asks a Monero view-only wallet through monero-wallet-rpc for a unique subaddress.
3. The 399 RUB price is converted to XMR for that order.
4. The app shows exact XMR amount, address and QR/payment URI.
5. Eneida watches the incoming transaction.
6. After 1 confirmation the 30-day subscription is activated automatically.

The Cake Wallet wallet remains the main wallet. Do not place its seed or private spend key on the server or in GitHub. The server-side wallet should be view-only, so it can monitor payments without spending funds.

## Android API URL

Android reads ENEIDA_API_BASE_URL at build time. Set the GitHub repository variable ENEIDA_API_BASE_URL to the HTTPS address of Eneida Control.

Until HTTPS is deployed, the payment screen stays disabled instead of sending payment data over cleartext HTTP.

## Secrets

Never commit:
- admin token;
- wallet seed;
- Monero private spend key;
- WireGuard private keys;
- RPC passwords.
