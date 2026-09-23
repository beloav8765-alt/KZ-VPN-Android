import asyncio
import os
import sqlite3
import uuid
from contextlib import closing
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_UP
from pathlib import Path
from typing import Optional

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

BASE_DIR = Path(__file__).resolve().parent.parent
DB_PATH = Path(os.getenv("ENEIDA_DB") or os.getenv("NIVORA_DB") or (BASE_DIR / "data" / "eneida.db"))
ADMIN_TOKEN = (os.getenv("ENEIDA_ADMIN_TOKEN") or os.getenv("NIVORA_ADMIN_TOKEN") or "").strip()

PLAN_PRICE_RUB = int(os.getenv("ENEIDA_PLAN_PRICE_RUB", "399"))
PLAN_DAYS = int(os.getenv("ENEIDA_PLAN_DAYS", "30"))
PAYMENT_TTL_MINUTES = int(os.getenv("ENEIDA_PAYMENT_TTL_MINUTES", "30"))
MONERO_CONFIRMATIONS_REQUIRED = int(os.getenv("ENEIDA_MONERO_CONFIRMATIONS", "1"))
VPN_DNS = os.getenv("ENEIDA_VPN_DNS", "8.8.8.8").strip()
VPN_MTU = int(os.getenv("ENEIDA_VPN_MTU", "1280"))
MONERO_RPC_URL = os.getenv("ENEIDA_MONERO_RPC_URL", "").strip()
MONERO_RPC_USER = os.getenv("ENEIDA_MONERO_RPC_USER", "").strip()
MONERO_RPC_PASSWORD = os.getenv("ENEIDA_MONERO_RPC_PASSWORD", "").strip()
XMR_RUB_RATE = os.getenv("ENEIDA_XMR_RUB_RATE", "").strip()
XMR_RATE_URL = os.getenv(
    "ENEIDA_XMR_RATE_URL",
    "https://api.coingecko.com/api/v3/simple/price?ids=monero&vs_currencies=rub",
).strip()

app = FastAPI(title="Eneida Control", version="0.3.0")
payment_task: Optional[asyncio.Task] = None


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def now_iso() -> str:
    return utc_now().isoformat()


def parse_dt(value: Optional[str]) -> Optional[datetime]:
    if not value:
        return None
    return datetime.fromisoformat(value)


def connect_db() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH)
    db.row_factory = sqlite3.Row
    return db


def ensure_column(db: sqlite3.Connection, table: str, name: str, definition: str) -> None:
    columns = {row["name"] for row in db.execute(f"PRAGMA table_info({table})").fetchall()}
    if name not in columns:
        db.execute(f"ALTER TABLE {table} ADD COLUMN {name} {definition}")


def init_db() -> None:
    with closing(connect_db()) as db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS servers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                region TEXT NOT NULL,
                endpoint_host TEXT NOT NULL,
                endpoint_port INTEGER NOT NULL DEFAULT 51820,
                public_key TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                maintenance INTEGER NOT NULL DEFAULT 0,
                priority INTEGER NOT NULL DEFAULT 100,
                load_pct INTEGER NOT NULL DEFAULT 0,
                active_clients INTEGER NOT NULL DEFAULT 0,
                last_seen TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """)
        ensure_column(db, "servers", "agent_url", "TEXT")
        ensure_column(db, "servers", "agent_token", "TEXT")
        db.execute("""
            CREATE TABLE IF NOT EXISTS payment_orders (
                id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                address TEXT NOT NULL,
                address_index INTEGER NOT NULL,
                rub_amount INTEGER NOT NULL,
                xmr_amount TEXT NOT NULL,
                expected_atomic INTEGER NOT NULL,
                received_atomic INTEGER NOT NULL DEFAULT 0,
                rate_rub_per_xmr TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                confirmations INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                paid_at TEXT
            )
        """)
        db.execute("""
            CREATE TABLE IF NOT EXISTS subscriptions (
                device_id TEXT PRIMARY KEY,
                valid_until TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """)
        db.execute("CREATE INDEX IF NOT EXISTS idx_payment_device ON payment_orders(device_id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_payment_status ON payment_orders(status)")
        db.commit()


@app.on_event("startup")
async def on_startup() -> None:
    global payment_task
    if not ADMIN_TOKEN:
        raise RuntimeError("ENEIDA_ADMIN_TOKEN is required")
    init_db()
    if MONERO_RPC_URL:
        payment_task = asyncio.create_task(payment_sync_loop())


@app.on_event("shutdown")
async def on_shutdown() -> None:
    if payment_task:
        payment_task.cancel()


def require_admin(authorization: Optional[str] = Header(default=None)) -> None:
    if authorization != f"Bearer {ADMIN_TOKEN}":
        raise HTTPException(status_code=401, detail="Unauthorized")


class ServerCreate(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    region: str = Field(min_length=1, max_length=80)
    endpoint_host: str = Field(min_length=1, max_length=255)
    endpoint_port: int = Field(default=51820, ge=1, le=65535)
    public_key: str = Field(min_length=20, max_length=200)
    enabled: bool = True
    maintenance: bool = False
    priority: int = Field(default=100, ge=0, le=10000)
    agent_url: Optional[str] = Field(default=None, max_length=500)
    agent_token: Optional[str] = Field(default=None, max_length=500)


class ServerUpdate(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=80)
    region: Optional[str] = Field(default=None, min_length=1, max_length=80)
    endpoint_host: Optional[str] = Field(default=None, min_length=1, max_length=255)
    endpoint_port: Optional[int] = Field(default=None, ge=1, le=65535)
    public_key: Optional[str] = Field(default=None, min_length=20, max_length=200)
    enabled: Optional[bool] = None
    maintenance: Optional[bool] = None
    priority: Optional[int] = Field(default=None, ge=0, le=10000)
    agent_url: Optional[str] = Field(default=None, max_length=500)
    agent_token: Optional[str] = Field(default=None, max_length=500)


class ServerHeartbeat(BaseModel):
    load_pct: int = Field(ge=0, le=100)
    active_clients: int = Field(ge=0, le=1000000)


class PaymentOrderCreate(BaseModel):
    device_id: str = Field(min_length=8, max_length=120)


class DeviceProvisionRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=120)
    public_key: str = Field(min_length=40, max_length=60)


class SubscriptionGrant(BaseModel):
    days: int = Field(default=30, ge=1, le=3650)


def row_to_server(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"], "name": row["name"], "region": row["region"],
        "endpoint_host": row["endpoint_host"], "endpoint_port": row["endpoint_port"],
        "public_key": row["public_key"], "enabled": bool(row["enabled"]),
        "maintenance": bool(row["maintenance"]), "priority": row["priority"],
        "load_pct": row["load_pct"], "active_clients": row["active_clients"],
        "last_seen": row["last_seen"], "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "agent_url": row["agent_url"],
        "agent_configured": bool(row["agent_url"] and row["agent_token"]),
    }


def row_to_payment(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "device_id": row["device_id"],
        "address": row["address"],
        "rub_amount": row["rub_amount"],
        "xmr_amount": row["xmr_amount"],
        "payment_uri": f'monero:{row["address"]}?tx_amount={row["xmr_amount"]}',
        "status": row["status"],
        "confirmations": row["confirmations"],
        "created_at": row["created_at"],
        "expires_at": row["expires_at"],
        "paid_at": row["paid_at"],
    }


async def monero_rpc(method: str, params: Optional[dict] = None) -> dict:
    if not MONERO_RPC_URL:
        raise HTTPException(status_code=503, detail="Monero payment service is not configured")
    auth = (MONERO_RPC_USER, MONERO_RPC_PASSWORD) if MONERO_RPC_USER else None
    payload = {"jsonrpc": "2.0", "id": "0", "method": method, "params": params or {}}
    async with httpx.AsyncClient(timeout=20.0, auth=auth) as client:
        response = await client.post(MONERO_RPC_URL, json=payload)
        response.raise_for_status()
        data = response.json()
    if "error" in data:
        raise RuntimeError(str(data["error"]))
    return data.get("result", {})


async def get_xmr_rub_rate() -> Decimal:
    if XMR_RUB_RATE:
        rate = Decimal(XMR_RUB_RATE)
        if rate <= 0:
            raise ValueError("ENEIDA_XMR_RUB_RATE must be positive")
        return rate

    async with httpx.AsyncClient(timeout=12.0) as client:
        response = await client.get(XMR_RATE_URL)
        response.raise_for_status()
        data = response.json()

    rate = Decimal(str(data["monero"]["rub"]))
    if rate <= 0:
        raise ValueError("Invalid XMR/RUB rate")
    return rate


def activate_subscription(db: sqlite3.Connection, device_id: str) -> str:
    row = db.execute("SELECT valid_until FROM subscriptions WHERE device_id = ?", (device_id,)).fetchone()
    now = utc_now()
    current = parse_dt(row["valid_until"]) if row else None
    start = current if current and current > now else now
    valid_until = start + timedelta(days=PLAN_DAYS)
    db.execute("""
        INSERT INTO subscriptions (device_id, valid_until, updated_at)
        VALUES (?, ?, ?)
        ON CONFLICT(device_id) DO UPDATE SET
            valid_until = excluded.valid_until,
            updated_at = excluded.updated_at
    """, (device_id, valid_until.isoformat(), now_iso()))
    return valid_until.isoformat()


async def sync_payment_order(order: sqlite3.Row) -> None:
    result = await monero_rpc("get_transfers", {
        "in": True,
        "pending": True,
        "pool": True,
        "failed": False,
        "account_index": 0,
        "subaddr_indices": [order["address_index"]],
    })

    incoming = []
    for bucket in ("in", "pending", "pool"):
        incoming.extend(result.get(bucket, []) or [])

    received = 0
    confirmations = 0
    for transfer in incoming:
        subaddr = transfer.get("subaddr_index") or {}
        minor = subaddr.get("minor")
        if minor is not None and int(minor) != int(order["address_index"]):
            continue
        received += int(transfer.get("amount", 0))
        confirmations = max(confirmations, int(transfer.get("confirmations", 0)))

    expired = utc_now() > parse_dt(order["expires_at"])
    paid = received >= int(order["expected_atomic"]) and confirmations >= MONERO_CONFIRMATIONS_REQUIRED

    with closing(connect_db()) as db:
        if paid:
            db.execute("""
                UPDATE payment_orders
                SET received_atomic = ?, confirmations = ?, status = 'paid', paid_at = ?
                WHERE id = ? AND status != 'paid'
            """, (received, confirmations, now_iso(), order["id"]))
            changed = db.execute("SELECT changes() AS c").fetchone()["c"]
            if changed:
                activate_subscription(db, order["device_id"])
        elif expired:
            db.execute("""
                UPDATE payment_orders
                SET received_atomic = ?, confirmations = ?, status = 'expired'
                WHERE id = ? AND status = 'pending'
            """, (received, confirmations, order["id"]))
        else:
            db.execute("""
                UPDATE payment_orders
                SET received_atomic = ?, confirmations = ?
                WHERE id = ?
            """, (received, confirmations, order["id"]))
        db.commit()


async def sync_pending_payments() -> int:
    with closing(connect_db()) as db:
        rows = db.execute("""
            SELECT * FROM payment_orders
            WHERE status = 'pending'
            ORDER BY created_at ASC
        """).fetchall()

    checked = 0
    for row in rows:
        try:
            await sync_payment_order(row)
            checked += 1
        except Exception:
            continue
    return checked


async def payment_sync_loop() -> None:
    while True:
        try:
            await sync_pending_payments()
        except Exception:
            pass
        await asyncio.sleep(30)


@app.get("/health")
def health() -> dict:
    return {"ok": True, "service": "eneida-control", "payments_configured": bool(MONERO_RPC_URL)}


@app.get("/api/v1/public/servers")
def public_servers() -> dict:
    with closing(connect_db()) as db:
        rows = db.execute("""
            SELECT * FROM servers
            WHERE enabled = 1 AND maintenance = 0
            ORDER BY priority ASC, load_pct ASC, id ASC
        """).fetchall()
    return {"servers": [{
        "id": row["id"], "name": row["name"], "region": row["region"],
        "endpoint": f'{row["endpoint_host"]}:{row["endpoint_port"]}',
        "public_key": row["public_key"], "load_pct": row["load_pct"]
    } for row in rows]}


@app.get("/api/v1/admin/servers", dependencies=[Depends(require_admin)])
def admin_servers() -> dict:
    with closing(connect_db()) as db:
        rows = db.execute("SELECT * FROM servers ORDER BY priority ASC, id ASC").fetchall()
    return {"servers": [row_to_server(row) for row in rows]}


@app.post("/api/v1/admin/servers", dependencies=[Depends(require_admin)])
def create_server(payload: ServerCreate) -> dict:
    now = now_iso()
    with closing(connect_db()) as db:
        cur = db.execute("""
            INSERT INTO servers (
                name, region, endpoint_host, endpoint_port, public_key,
                enabled, maintenance, priority, load_pct, active_clients,
                created_at, updated_at, agent_url, agent_token
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?)
        """, (
            payload.name.strip(), payload.region.strip(), payload.endpoint_host.strip(),
            payload.endpoint_port, payload.public_key.strip(), int(payload.enabled),
            int(payload.maintenance), payload.priority, now, now,
            (payload.agent_url or "").strip() or None,
            (payload.agent_token or "").strip() or None
        ))
        server_id = cur.lastrowid
        db.commit()
        row = db.execute("SELECT * FROM servers WHERE id = ?", (server_id,)).fetchone()
    return row_to_server(row)


@app.patch("/api/v1/admin/servers/{server_id}", dependencies=[Depends(require_admin)])
def update_server(server_id: int, payload: ServerUpdate) -> dict:
    values = payload.model_dump(exclude_none=True)
    if not values:
        raise HTTPException(status_code=400, detail="No changes")
    updates, params = [], []
    for key, value in values.items():
        updates.append(f"{key} = ?")
        params.append(int(value) if isinstance(value, bool) else value.strip() if isinstance(value, str) else value)
    updates.append("updated_at = ?")
    params.extend([now_iso(), server_id])

    with closing(connect_db()) as db:
        cur = db.execute(f"UPDATE servers SET {', '.join(updates)} WHERE id = ?", params)
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Server not found")
        db.commit()
        row = db.execute("SELECT * FROM servers WHERE id = ?", (server_id,)).fetchone()
    return row_to_server(row)


@app.delete("/api/v1/admin/servers/{server_id}", dependencies=[Depends(require_admin)])
def delete_server(server_id: int) -> dict:
    with closing(connect_db()) as db:
        cur = db.execute("DELETE FROM servers WHERE id = ?", (server_id,))
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Server not found")
        db.commit()
    return {"ok": True}


@app.post("/api/v1/admin/servers/{server_id}/heartbeat", dependencies=[Depends(require_admin)])
def heartbeat(server_id: int, payload: ServerHeartbeat) -> dict:
    now = now_iso()
    with closing(connect_db()) as db:
        cur = db.execute("""
            UPDATE servers
            SET load_pct = ?, active_clients = ?, last_seen = ?, updated_at = ?
            WHERE id = ?
        """, (payload.load_pct, payload.active_clients, now, now, server_id))
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Server not found")
        db.commit()
        row = db.execute("SELECT * FROM servers WHERE id = ?", (server_id,)).fetchone()
    return row_to_server(row)



def subscription_is_active(device_id: str) -> bool:
    with closing(connect_db()) as db:
        row = db.execute(
            "SELECT valid_until FROM subscriptions WHERE device_id = ?",
            (device_id,),
        ).fetchone()
    if not row:
        return False
    valid_until = parse_dt(row["valid_until"])
    return bool(valid_until and valid_until > utc_now())


async def provision_on_agent(server: sqlite3.Row, payload: DeviceProvisionRequest) -> dict:
    agent_url = (server["agent_url"] or "").rstrip("/")
    agent_token = (server["agent_token"] or "").strip()
    if not agent_url or not agent_token:
        raise RuntimeError("Agent is not configured")

    headers = {"Authorization": f"Bearer {agent_token}"}
    body = {"device_id": payload.device_id, "public_key": payload.public_key}

    async with httpx.AsyncClient(timeout=12.0) as client:
        response = await client.post(agent_url + "/v1/peers", headers=headers, json=body)
        response.raise_for_status()
        data = response.json()

    returned_key = data.get("server_public_key")
    if returned_key and returned_key != server["public_key"]:
        raise RuntimeError("Agent WireGuard public key does not match server registry")

    return data


@app.post("/api/v1/devices/provision")
async def provision_device(payload: DeviceProvisionRequest) -> dict:
    if not subscription_is_active(payload.device_id):
        raise HTTPException(status_code=402, detail="Active subscription required")

    with closing(connect_db()) as db:
        servers = db.execute("""
            SELECT * FROM servers
            WHERE enabled = 1
              AND maintenance = 0
              AND agent_url IS NOT NULL
              AND agent_url != ''
              AND agent_token IS NOT NULL
              AND agent_token != ''
            ORDER BY priority ASC, load_pct ASC, active_clients ASC, id ASC
        """).fetchall()

    if not servers:
        raise HTTPException(status_code=503, detail="No managed VPN servers are available")

    errors = []
    for server in servers:
        try:
            peer = await provision_on_agent(server, payload)
            return {
                "server_id": server["id"],
                "server_name": server["name"],
                "region": server["region"],
                "endpoint": f'{server["endpoint_host"]}:{server["endpoint_port"]}',
                "server_public_key": server["public_key"],
                "address": peer["address"],
                "dns": VPN_DNS,
                "mtu": VPN_MTU,
                "allowed_ips": "0.0.0.0/0",
                "persistent_keepalive": 25,
            }
        except Exception as error:
            errors.append(f'{server["name"]}: {error}')

    raise HTTPException(
        status_code=503,
        detail="Managed VPN servers did not accept the device",
    )


@app.post("/api/v1/admin/subscriptions/{device_id}/grant", dependencies=[Depends(require_admin)])
def grant_subscription(device_id: str, payload: SubscriptionGrant) -> dict:
    with closing(connect_db()) as db:
        row = db.execute(
            "SELECT valid_until FROM subscriptions WHERE device_id = ?",
            (device_id,),
        ).fetchone()
        now = utc_now()
        current = parse_dt(row["valid_until"]) if row else None
        start = current if current and current > now else now
        valid_until = start + timedelta(days=payload.days)
        db.execute("""
            INSERT INTO subscriptions (device_id, valid_until, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                valid_until = excluded.valid_until,
                updated_at = excluded.updated_at
        """, (device_id, valid_until.isoformat(), now_iso()))
        db.commit()
    return {"device_id": device_id, "active": True, "valid_until": valid_until.isoformat()}


@app.post("/api/v1/payments/orders")
async def create_payment_order(payload: PaymentOrderCreate) -> dict:
    rate = await get_xmr_rub_rate()
    xmr = (Decimal(PLAN_PRICE_RUB) / rate).quantize(Decimal("0.000000000001"), rounding=ROUND_UP)
    expected_atomic = int((xmr * Decimal("1000000000000")).to_integral_value(rounding=ROUND_UP))

    wallet = await monero_rpc("create_address", {
        "account_index": 0,
        "label": "Eneida " + payload.device_id[:18],
    })
    address = wallet.get("address")
    address_index = wallet.get("address_index")
    if not address or address_index is None:
        raise HTTPException(status_code=503, detail="Wallet did not create a payment address")

    order_id = uuid.uuid4().hex
    created = utc_now()
    expires = created + timedelta(minutes=PAYMENT_TTL_MINUTES)

    with closing(connect_db()) as db:
        db.execute("""
            INSERT INTO payment_orders (
                id, device_id, address, address_index, rub_amount, xmr_amount,
                expected_atomic, received_atomic, rate_rub_per_xmr, status,
                confirmations, created_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, 'pending', 0, ?, ?)
        """, (
            order_id, payload.device_id, address, int(address_index), PLAN_PRICE_RUB,
            format(xmr, "f"), expected_atomic, format(rate, "f"),
            created.isoformat(), expires.isoformat()
        ))
        db.commit()
        row = db.execute("SELECT * FROM payment_orders WHERE id = ?", (order_id,)).fetchone()
    return row_to_payment(row)


@app.get("/api/v1/payments/orders/{order_id}")
async def payment_order(order_id: str) -> dict:
    with closing(connect_db()) as db:
        row = db.execute("SELECT * FROM payment_orders WHERE id = ?", (order_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="Payment not found")

    if row["status"] == "pending" and MONERO_RPC_URL:
        try:
            await sync_payment_order(row)
        except Exception:
            pass
        with closing(connect_db()) as db:
            row = db.execute("SELECT * FROM payment_orders WHERE id = ?", (order_id,)).fetchone()

    return row_to_payment(row)


@app.get("/api/v1/subscriptions/{device_id}")
def subscription(device_id: str) -> dict:
    with closing(connect_db()) as db:
        row = db.execute("SELECT * FROM subscriptions WHERE device_id = ?", (device_id,)).fetchone()

    valid_until = row["valid_until"] if row else None
    valid_dt = parse_dt(valid_until)
    active = bool(valid_dt and valid_dt > utc_now())
    return {
        "device_id": device_id,
        "plan": "Eneida 30 дней",
        "active": active,
        "valid_until": valid_until,
        "price_rub": PLAN_PRICE_RUB,
    }


@app.get("/api/v1/admin/payments", dependencies=[Depends(require_admin)])
def admin_payments() -> dict:
    with closing(connect_db()) as db:
        rows = db.execute("""
            SELECT * FROM payment_orders
            ORDER BY created_at DESC
            LIMIT 200
        """).fetchall()
    return {"payments": [row_to_payment(row) for row in rows]}


@app.post("/api/v1/admin/payments/sync", dependencies=[Depends(require_admin)])
async def admin_sync_payments() -> dict:
    checked = await sync_pending_payments()
    return {"ok": True, "checked": checked}


@app.get("/admin")
def admin_page():
    return FileResponse(BASE_DIR / "static" / "admin.html")
