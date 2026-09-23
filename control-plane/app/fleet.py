import asyncio
import hashlib
import ipaddress
import os
import secrets
import shlex
import sqlite3
import tempfile
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import asyncssh
from fastapi import APIRouter, BackgroundTasks, Depends, Header, HTTPException
from pydantic import BaseModel, Field

BASE_DIR = Path(__file__).resolve().parent.parent
DB_PATH = Path(os.getenv("ENEIDA_DB") or os.getenv("NIVORA_DB") or (BASE_DIR / "data" / "eneida.db"))
ADMIN_TOKEN = (os.getenv("ENEIDA_ADMIN_TOKEN") or os.getenv("NIVORA_ADMIN_TOKEN") or "").strip()
PUBLIC_BASE_URL = os.getenv("ENEIDA_PUBLIC_BASE_URL", "").strip().rstrip("/")
AGENT_POLL_SECONDS = int(os.getenv("ENEIDA_AGENT_POLL_SECONDS", "5"))
STALE_SECONDS = int(os.getenv("ENEIDA_SERVER_STALE_SECONDS", "90"))

router = APIRouter()


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def now_iso() -> str:
    return utc_now().isoformat()


def connect_db() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH)
    db.row_factory = sqlite3.Row
    return db


def ensure_column(db: sqlite3.Connection, table: str, name: str, definition: str) -> None:
    columns = {row["name"] for row in db.execute(f"PRAGMA table_info({table})").fetchall()}
    if name not in columns:
        db.execute(f"ALTER TABLE {table} ADD COLUMN {name} {definition}")


def init_fleet_schema() -> None:
    with closing(connect_db()) as db:
        ensure_column(db, "servers", "region_code", "TEXT")
        ensure_column(db, "servers", "country", "TEXT")
        ensure_column(db, "servers", "city", "TEXT")
        ensure_column(db, "servers", "flag", "TEXT")
        ensure_column(db, "servers", "status", "TEXT DEFAULT 'unknown'")
        ensure_column(db, "servers", "error_text", "TEXT")
        ensure_column(db, "servers", "agent_last_seen", "TEXT")
        ensure_column(db, "servers", "agent_token_hash", "TEXT")
        ensure_column(db, "servers", "cpu_pct", "INTEGER DEFAULT 0")
        ensure_column(db, "servers", "ram_pct", "INTEGER DEFAULT 0")

        db.execute("""
            CREATE TABLE IF NOT EXISTS device_assignments (
                server_id INTEGER NOT NULL,
                device_id TEXT NOT NULL,
                public_key TEXT NOT NULL,
                address TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (server_id, device_id)
            )
        """)
        db.execute("""
            CREATE TABLE IF NOT EXISTS fleet_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                severity TEXT NOT NULL,
                category TEXT NOT NULL,
                server_id INTEGER,
                message TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
        """)
        db.execute("""
            CREATE TABLE IF NOT EXISTS provision_jobs (
                id TEXT PRIMARY KEY,
                server_id INTEGER,
                status TEXT NOT NULL,
                step TEXT NOT NULL,
                error_text TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """)
        db.execute("CREATE INDEX IF NOT EXISTS idx_assignments_device ON device_assignments(device_id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_fleet_events_created ON fleet_events(created_at)")
        db.commit()


def require_admin(authorization: Optional[str] = Header(default=None)) -> None:
    if authorization != f"Bearer {ADMIN_TOKEN}":
        raise HTTPException(status_code=401, detail="Unauthorized")


def token_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def require_agent(server_id: int, authorization: Optional[str]) -> sqlite3.Row:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Unauthorized")
    token = authorization[7:]
    with closing(connect_db()) as db:
        row = db.execute("SELECT * FROM servers WHERE id = ?", (server_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="Server not found")

    expected_hash = row["agent_token_hash"] or ""
    legacy = row["agent_token"] or ""
    if expected_hash:
        valid = secrets.compare_digest(expected_hash, token_hash(token))
    else:
        valid = bool(legacy) and secrets.compare_digest(legacy, token)
    if not valid:
        raise HTTPException(status_code=401, detail="Unauthorized")
    return row


def add_event(severity: str, category: str, message: str, server_id: Optional[int] = None) -> None:
    with closing(connect_db()) as db:
        db.execute(
            "INSERT INTO fleet_events (severity, category, server_id, message, created_at) VALUES (?, ?, ?, ?, ?)",
            (severity, category, server_id, message[:1000], now_iso()),
        )
        db.commit()


def server_is_online(row: sqlite3.Row) -> bool:
    if row["status"] == "provisioning":
        return False
    raw = row["agent_last_seen"] or row["last_seen"]
    if not raw:
        return False
    try:
        dt = datetime.fromisoformat(raw)
        return (utc_now() - dt).total_seconds() <= STALE_SECONDS
    except Exception:
        return False


def region_code_for(row: sqlite3.Row) -> str:
    value = (row["region_code"] or "").strip().lower()
    if value:
        return value
    region = (row["region"] or "other").strip().lower().replace(" ", "-")
    return region[:32] or "other"


def display_region(row: sqlite3.Row) -> str:
    country = (row["country"] or row["region"] or "Другой регион").strip()
    city = (row["city"] or "").strip()
    return country if not city else f"{country}, {city}"


def allocate_address(db: sqlite3.Connection, server_id: int) -> str:
    network = ipaddress.ip_network("10.66.66.0/24")
    used = {
        row["address"].split("/")[0]
        for row in db.execute(
            "SELECT address FROM device_assignments WHERE server_id = ? AND enabled = 1",
            (server_id,),
        ).fetchall()
    }
    reserved = {str(network.network_address), str(network.broadcast_address), "10.66.66.1"}
    for host in network.hosts():
        value = str(host)
        if value not in used and value not in reserved:
            return f"{value}/32"
    raise HTTPException(status_code=503, detail="Server address pool is full")


def subscription_active(device_id: str) -> bool:
    with closing(connect_db()) as db:
        row = db.execute("SELECT valid_until FROM subscriptions WHERE device_id = ?", (device_id,)).fetchone()
    if not row:
        return False
    try:
        return datetime.fromisoformat(row["valid_until"]) > utc_now()
    except Exception:
        return False


class DeviceProvisionRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=120)
    public_key: str = Field(min_length=40, max_length=60)
    preferred_region: Optional[str] = Field(default=None, max_length=40)


class AgentHeartbeat(BaseModel):
    public_key: str = Field(min_length=40, max_length=80)
    active_clients: int = Field(default=0, ge=0, le=1000000)
    cpu_pct: int = Field(default=0, ge=0, le=100)
    ram_pct: int = Field(default=0, ge=0, le=100)
    error: Optional[str] = Field(default=None, max_length=1000)


class VpsProvisionRequest(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    country: str = Field(min_length=1, max_length=80)
    city: Optional[str] = Field(default=None, max_length=80)
    region_code: str = Field(min_length=2, max_length=32)
    flag: Optional[str] = Field(default=None, max_length=16)
    host: str = Field(min_length=1, max_length=255)
    ssh_port: int = Field(default=22, ge=1, le=65535)
    ssh_user: str = Field(default="root", min_length=1, max_length=64)
    ssh_password: Optional[str] = Field(default=None, max_length=500)
    ssh_private_key: Optional[str] = Field(default=None, max_length=16000)
    endpoint_port: int = Field(default=51820, ge=1, le=65535)
    priority: int = Field(default=100, ge=0, le=10000)
    replace_server_id: Optional[int] = Field(default=None, ge=1)


class FleetServerUpdate(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=80)
    country: Optional[str] = Field(default=None, min_length=1, max_length=80)
    city: Optional[str] = Field(default=None, max_length=80)
    region_code: Optional[str] = Field(default=None, min_length=2, max_length=32)
    flag: Optional[str] = Field(default=None, max_length=16)
    endpoint_host: Optional[str] = Field(default=None, min_length=1, max_length=255)
    endpoint_port: Optional[int] = Field(default=None, ge=1, le=65535)
    priority: Optional[int] = Field(default=None, ge=0, le=10000)
    enabled: Optional[bool] = None
    maintenance: Optional[bool] = None


@router.get("/api/v1/public/regions")
def public_regions() -> dict:
    with closing(connect_db()) as db:
        rows = db.execute("""
            SELECT * FROM servers
            WHERE enabled = 1 AND maintenance = 0
            ORDER BY priority ASC, load_pct ASC, id ASC
        """).fetchall()

    grouped = {}
    for row in rows:
        if not server_is_online(row):
            continue
        code = region_code_for(row)
        item = grouped.setdefault(code, {
            "code": code,
            "name": (row["country"] or row["region"] or code).strip(),
            "flag": (row["flag"] or "").strip(),
            "servers": 0,
            "load_pct": 0,
            "online": True,
        })
        item["servers"] += 1
        item["load_pct"] += int(row["load_pct"] or row["cpu_pct"] or 0)

    regions = []
    for item in grouped.values():
        if item["servers"]:
            item["load_pct"] = round(item["load_pct"] / item["servers"])
        regions.append(item)

    regions.sort(key=lambda x: (x["load_pct"], x["name"]))
    return {"regions": regions}


@router.post("/api/v1/devices/provision")
def provision_device(payload: DeviceProvisionRequest) -> dict:
    if not subscription_active(payload.device_id):
        raise HTTPException(status_code=402, detail="Active subscription required")

    preferred = (payload.preferred_region or "").strip().lower()
    with closing(connect_db()) as db:
        rows = db.execute("""
            SELECT * FROM servers
            WHERE enabled = 1 AND maintenance = 0
            ORDER BY priority ASC, load_pct ASC, active_clients ASC, id ASC
        """).fetchall()

        online = [row for row in rows if server_is_online(row)]
        if preferred and preferred != "auto":
            online = [row for row in online if region_code_for(row) == preferred]

        if not online:
            raise HTTPException(status_code=503, detail="No healthy VPN servers are available")

        server = online[0]
        existing = db.execute("""
            SELECT * FROM device_assignments
            WHERE server_id = ? AND device_id = ?
        """, (server["id"], payload.device_id)).fetchone()

        if existing:
            address = existing["address"]
            db.execute("""
                UPDATE device_assignments
                SET public_key = ?, enabled = 1, updated_at = ?
                WHERE server_id = ? AND device_id = ?
            """, (payload.public_key, now_iso(), server["id"], payload.device_id))
        else:
            address = allocate_address(db, server["id"])
            db.execute("""
                INSERT INTO device_assignments (
                    server_id, device_id, public_key, address, enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 1, ?, ?)
            """, (
                server["id"], payload.device_id, payload.public_key, address,
                now_iso(), now_iso()
            ))
        db.commit()

    return {
        "server_id": server["id"],
        "server_name": server["name"],
        "region_code": region_code_for(server),
        "region_name": display_region(server),
        "endpoint": f'{server["endpoint_host"]}:{server["endpoint_port"]}',
        "server_public_key": server["public_key"],
        "address": address,
        "dns": os.getenv("ENEIDA_VPN_DNS", "8.8.8.8"),
        "mtu": int(os.getenv("ENEIDA_VPN_MTU", "1280")),
        "allowed_ips": "0.0.0.0/0",
        "persistent_keepalive": 25,
    }


@router.post("/api/v1/agent/{server_id}/heartbeat")
def agent_heartbeat(
    server_id: int,
    payload: AgentHeartbeat,
    authorization: Optional[str] = Header(default=None),
) -> dict:
    server = require_agent(server_id, authorization)
    status = "error" if payload.error else "online"
    now = now_iso()
    with closing(connect_db()) as db:
        db.execute("""
            UPDATE servers
            SET agent_last_seen = ?, last_seen = ?, status = ?, error_text = ?,
                active_clients = ?, load_pct = ?, cpu_pct = ?, ram_pct = ?,
                public_key = CASE WHEN public_key = '' OR public_key IS NULL THEN ? ELSE public_key END,
                updated_at = ?
            WHERE id = ?
        """, (
            now, now, status, payload.error,
            payload.active_clients, payload.cpu_pct, payload.cpu_pct, payload.ram_pct,
            payload.public_key, now, server_id
        ))
        db.commit()

    if server["public_key"] and server["public_key"] != payload.public_key:
        add_event("error", "server", "WireGuard public key mismatch", server_id)
        raise HTTPException(status_code=409, detail="WireGuard public key mismatch")

    return {"ok": True, "poll_seconds": AGENT_POLL_SECONDS}


@router.get("/api/v1/agent/{server_id}/sync")
def agent_sync(server_id: int, authorization: Optional[str] = Header(default=None)) -> dict:
    require_agent(server_id, authorization)
    with closing(connect_db()) as db:
        peers = db.execute("""
            SELECT device_id, public_key, address
            FROM device_assignments
            WHERE server_id = ? AND enabled = 1
            ORDER BY created_at ASC
        """, (server_id,)).fetchall()

    return {
        "generation": now_iso(),
        "peers": [
            {
                "device_id": row["device_id"],
                "public_key": row["public_key"],
                "address": row["address"],
            }
            for row in peers
        ],
    }


@router.get("/api/v1/admin/system/backup", dependencies=[Depends(require_admin)])
def download_backup():
    handle = tempfile.NamedTemporaryFile(prefix="eneida-backup-", suffix=".db", delete=False)
    backup_path = Path(handle.name)
    handle.close()

    source = connect_db()
    target = sqlite3.connect(backup_path)
    try:
        source.backup(target)
    finally:
        target.close()
        source.close()

    return FileResponse(
        backup_path,
        media_type="application/octet-stream",
        filename="eneida-control-backup.db",
        background=BackgroundTask(lambda: backup_path.unlink(missing_ok=True)),
    )


@router.get("/api/v1/admin/dashboard", dependencies=[Depends(require_admin)])
def admin_dashboard() -> dict:
    now = utc_now()
    with closing(connect_db()) as db:
        servers = db.execute("SELECT * FROM servers").fetchall()
        active_subscriptions = db.execute(
            "SELECT COUNT(*) AS c FROM subscriptions WHERE valid_until > ?",
            (now.isoformat(),),
        ).fetchone()["c"]
        payments_today = db.execute(
            "SELECT COUNT(*) AS c FROM payment_orders WHERE status = 'paid' AND paid_at >= ?",
            (now.replace(hour=0, minute=0, second=0, microsecond=0).isoformat(),),
        ).fetchone()["c"]
        events = db.execute("""
            SELECT e.*, s.name AS server_name
            FROM fleet_events e
            LEFT JOIN servers s ON s.id = e.server_id
            ORDER BY e.id DESC LIMIT 100
        """).fetchall()

    online = sum(1 for row in servers if server_is_online(row) and row["enabled"] and not row["maintenance"])
    overloaded = sum(1 for row in servers if int(row["load_pct"] or 0) >= 85)
    errors = sum(1 for row in servers if row["error_text"] or (row["enabled"] and not server_is_online(row)))

    return {
        "servers_total": len(servers),
        "servers_online": online,
        "servers_overloaded": overloaded,
        "server_errors": errors,
        "active_subscriptions": active_subscriptions,
        "payments_today": payments_today,
        "active_clients": sum(int(row["active_clients"] or 0) for row in servers),
        "events": [
            {
                "id": row["id"],
                "severity": row["severity"],
                "category": row["category"],
                "server_id": row["server_id"],
                "server_name": row["server_name"],
                "message": row["message"],
                "created_at": row["created_at"],
            }
            for row in events
        ],
    }


@router.get("/api/v1/admin/fleet/servers", dependencies=[Depends(require_admin)])
def admin_fleet_servers() -> dict:
    with closing(connect_db()) as db:
        rows = db.execute("SELECT * FROM servers ORDER BY region_code, priority, id").fetchall()

    return {
        "servers": [
            {
                "id": row["id"],
                "name": row["name"],
                "country": row["country"] or row["region"],
                "city": row["city"],
                "region_code": region_code_for(row),
                "flag": row["flag"],
                "priority": int(row["priority"] or 100),
                "endpoint": f'{row["endpoint_host"]}:{row["endpoint_port"]}',
                "enabled": bool(row["enabled"]),
                "maintenance": bool(row["maintenance"]),
                "status": (
                    "maintenance" if row["maintenance"] else
                    "disabled" if not row["enabled"] else
                    "online" if server_is_online(row) else
                    (row["status"] or "offline")
                ),
                "load_pct": int(row["load_pct"] or 0),
                "cpu_pct": int(row["cpu_pct"] or 0),
                "ram_pct": int(row["ram_pct"] or 0),
                "active_clients": int(row["active_clients"] or 0),
                "last_seen": row["agent_last_seen"] or row["last_seen"],
                "error": row["error_text"],
            }
            for row in rows
        ]
    }


@router.patch("/api/v1/admin/fleet/servers/{server_id}", dependencies=[Depends(require_admin)])
def update_fleet_server(server_id: int, payload: FleetServerUpdate) -> dict:
    values = payload.model_dump(exclude_none=True)
    if not values:
        raise HTTPException(status_code=400, detail="No changes")

    allowed = {
        "name", "country", "city", "region_code", "flag",
        "endpoint_host", "endpoint_port", "priority", "enabled", "maintenance"
    }
    updates = []
    params = []
    for key, value in values.items():
        if key not in allowed:
            continue
        if key == "region_code" and isinstance(value, str):
            value = value.strip().lower()
        if isinstance(value, bool):
            value = int(value)
        elif isinstance(value, str):
            value = value.strip()
        updates.append(f"{key} = ?")
        params.append(value)

    if "country" in values:
        updates.append("region = ?")
        params.append(values["country"].strip())

    updates.append("updated_at = ?")
    params.append(now_iso())
    params.append(server_id)

    with closing(connect_db()) as db:
        cur = db.execute(
            f"UPDATE servers SET {', '.join(updates)} WHERE id = ?",
            params,
        )
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Server not found")
        db.commit()
        row = db.execute("SELECT * FROM servers WHERE id = ?", (server_id,)).fetchone()

    add_event("info", "server", "Параметры сервера изменены", server_id)
    return {
        "id": row["id"],
        "name": row["name"],
        "country": row["country"] or row["region"],
        "city": row["city"],
        "region_code": region_code_for(row),
        "endpoint": f'{row["endpoint_host"]}:{row["endpoint_port"]}',
        "enabled": bool(row["enabled"]),
        "maintenance": bool(row["maintenance"]),
    }


@router.get("/api/v1/admin/provision-jobs/{job_id}", dependencies=[Depends(require_admin)])
def get_provision_job(job_id: str) -> dict:
    with closing(connect_db()) as db:
        row = db.execute("SELECT * FROM provision_jobs WHERE id = ?", (job_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="Provision job not found")
    return dict(row)


def update_job(job_id: str, status: str, step: str, error: Optional[str] = None) -> None:
    with closing(connect_db()) as db:
        db.execute("""
            UPDATE provision_jobs
            SET status = ?, step = ?, error_text = ?, updated_at = ?
            WHERE id = ?
        """, (status, step, error, now_iso(), job_id))
        db.commit()


def bootstrap_script(server_id: int, agent_token: str, endpoint_port: int) -> str:
    if not PUBLIC_BASE_URL.startswith("https://"):
        raise RuntimeError("ENEIDA_PUBLIC_BASE_URL must be configured with HTTPS")

    control = shlex.quote(PUBLIC_BASE_URL)
    token = shlex.quote(agent_token)
    return f"""set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y wireguard python3 python3-venv curl iptables
install -d -m 700 /etc/wireguard
umask 077
if [ ! -s /etc/wireguard/eneida_private.key ]; then
  wg genkey > /etc/wireguard/eneida_private.key
fi
wg pubkey < /etc/wireguard/eneida_private.key > /etc/wireguard/eneida_public.key
WAN_IF="$(ip route show default | awk '{{print $5; exit}}')"
PRIV="$(cat /etc/wireguard/eneida_private.key)"
cat > /etc/wireguard/wg0.conf <<EOF
[Interface]
Address = 10.66.66.1/24
ListenPort = {endpoint_port}
PrivateKey = $PRIV
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o $WAN_IF -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o $WAN_IF -j MASQUERADE
EOF
chmod 600 /etc/wireguard/wg0.conf
cat > /etc/sysctl.d/99-eneida.conf <<EOF
net.ipv4.ip_forward=1
EOF
sysctl --system >/dev/null
systemctl enable --now wg-quick@wg0
install -d /opt/eneida-agent
python3 -m venv /opt/eneida-agent/.venv
/opt/eneida-agent/.venv/bin/pip install --disable-pip-version-check --quiet httpx==0.28.1
curl -fsSL {control}/api/v1/agent/bootstrap.py -o /opt/eneida-agent/agent.py
cat > /etc/eneida-agent.env <<EOF
ENEIDA_CONTROL_URL={PUBLIC_BASE_URL}
ENEIDA_SERVER_ID={server_id}
ENEIDA_AGENT_TOKEN={agent_token}
ENEIDA_AGENT_INTERFACE=wg0
ENEIDA_AGENT_POLL_SECONDS={AGENT_POLL_SECONDS}
ENEIDA_AGENT_DB=/var/lib/eneida-agent/agent.db
EOF
chmod 600 /etc/eneida-agent.env
cat > /etc/systemd/system/eneida-agent.service <<'EOF'
[Unit]
Description=Eneida VPN Agent
After=network-online.target wg-quick@wg0.service
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=/etc/eneida-agent.env
ExecStart=/opt/eneida-agent/.venv/bin/python /opt/eneida-agent/agent.py
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now eneida-agent
sleep 2
cat /etc/wireguard/eneida_public.key
"""


async def run_provision_job(job_id: str, server_id: int, payload: VpsProvisionRequest, agent_token: str) -> None:
    try:
        update_job(job_id, "running", "Подключение к VPS")
        options = {
            "host": payload.host,
            "port": payload.ssh_port,
            "username": payload.ssh_user,
            "known_hosts": None,
            "connect_timeout": 20,
        }

        if payload.ssh_private_key:
            key = asyncssh.import_private_key(payload.ssh_private_key)
            options["client_keys"] = [key]
        elif payload.ssh_password:
            options["password"] = payload.ssh_password
        else:
            raise RuntimeError("Нужен SSH-пароль или приватный ключ")

        async with asyncssh.connect(**options) as conn:
            update_job(job_id, "running", "Установка WireGuard и Eneida Agent")
            result = await conn.run(
                bootstrap_script(server_id, agent_token, payload.endpoint_port),
                check=True,
                timeout=240,
            )
            public_key = result.stdout.strip().splitlines()[-1].strip()
            if len(public_key) < 40:
                raise RuntimeError("Не удалось получить публичный ключ WireGuard")

        with closing(connect_db()) as db:
            db.execute("""
                UPDATE servers
                SET public_key = ?, status = 'waiting_agent', enabled = 1,
                    maintenance = 0, error_text = NULL, updated_at = ?
                WHERE id = ?
            """, (public_key, now_iso(), server_id))
            db.commit()

        if payload.replace_server_id and payload.replace_server_id != server_id:
            with closing(connect_db()) as db:
                db.execute("""
                    UPDATE servers
                    SET enabled = 0, maintenance = 1, status = 'retired',
                        updated_at = ?
                    WHERE id = ?
                """, (now_iso(), payload.replace_server_id))
                db.commit()
            add_event(
                "info",
                "replace",
                f"Сервер заменён новым узлом #{server_id}",
                payload.replace_server_id,
            )

        update_job(job_id, "success", "Сервер установлен. Ожидаем Agent")
        add_event("info", "provision", "VPN-сервер установлен автоматически", server_id)

    except Exception as error:
        message = str(error)[:1000]
        with closing(connect_db()) as db:
            db.execute("""
                UPDATE servers
                SET status = 'error', error_text = ?, enabled = 0, updated_at = ?
                WHERE id = ?
            """, (message, now_iso(), server_id))
            db.commit()
        update_job(job_id, "error", "Ошибка установки", message)
        add_event("error", "provision", message, server_id)


@router.post("/api/v1/admin/fleet/provision", dependencies=[Depends(require_admin)])
async def provision_vps(payload: VpsProvisionRequest, background_tasks: BackgroundTasks) -> dict:
    if not PUBLIC_BASE_URL.startswith("https://"):
        raise HTTPException(
            status_code=503,
            detail="Сначала настройте ENEIDA_PUBLIC_BASE_URL с HTTPS",
        )

    job_id = uuid.uuid4().hex
    agent_token = secrets.token_urlsafe(32)
    now = now_iso()

    with closing(connect_db()) as db:
        cur = db.execute("""
            INSERT INTO servers (
                name, region, country, city, region_code, flag,
                endpoint_host, endpoint_port, public_key,
                enabled, maintenance, priority, load_pct, active_clients,
                status, agent_token_hash, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', 0, 1, ?, 0, 0, 'provisioning', ?, ?, ?)
        """, (
            payload.name.strip(),
            payload.country.strip(),
            payload.country.strip(),
            (payload.city or "").strip() or None,
            payload.region_code.strip().lower(),
            (payload.flag or "").strip() or None,
            payload.host.strip(),
            payload.endpoint_port,
            payload.priority,
            token_hash(agent_token),
            now,
            now,
        ))
        server_id = cur.lastrowid
        db.execute("""
            INSERT INTO provision_jobs (id, server_id, status, step, created_at, updated_at)
            VALUES (?, ?, 'queued', 'Ожидает запуска', ?, ?)
        """, (job_id, server_id, now, now))
        db.commit()

    background_tasks.add_task(run_provision_job, job_id, server_id, payload, agent_token)
    return {"job_id": job_id, "server_id": server_id, "status": "queued"}


@router.get("/api/v1/agent/bootstrap.py")
def agent_bootstrap_file():
    from fastapi.responses import FileResponse
    return FileResponse(BASE_DIR / "static" / "eneida-agent.py", media_type="text/x-python")
