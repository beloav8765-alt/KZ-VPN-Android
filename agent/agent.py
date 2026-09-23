import ipaddress
import json
import os
import sqlite3
import subprocess
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

INTERFACE = os.getenv("ENEIDA_AGENT_INTERFACE", "wg0").strip()
AGENT_TOKEN = os.getenv("ENEIDA_AGENT_TOKEN", "").strip()
ADDRESS_POOL = os.getenv("ENEIDA_AGENT_ADDRESS_POOL", "10.66.66.0/24").strip()
SERVER_ADDRESS = os.getenv("ENEIDA_AGENT_SERVER_ADDRESS", "10.66.66.1").strip()
DB_PATH = Path(os.getenv("ENEIDA_AGENT_DB", "/var/lib/eneida-agent/agent.db"))

app = FastAPI(title="Eneida Agent", version="0.1.0")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def connect_db() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH)
    db.row_factory = sqlite3.Row
    return db


def init_db() -> None:
    with closing(connect_db()) as db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS peers (
                device_id TEXT PRIMARY KEY,
                public_key TEXT NOT NULL UNIQUE,
                address TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """)
        db.commit()


def run_wg(*args: str) -> str:
    cmd = ["wg", *args]
    result = subprocess.run(cmd, check=True, capture_output=True, text=True)
    return result.stdout.strip()


def require_token(authorization: Optional[str] = Header(default=None)) -> None:
    if authorization != f"Bearer {AGENT_TOKEN}":
        raise HTTPException(status_code=401, detail="Unauthorized")


def ensure_interface() -> None:
    try:
        run_wg("show", INTERFACE)
    except Exception as error:
        raise RuntimeError(f"WireGuard interface {INTERFACE} is not available") from error


def allocate_address(db: sqlite3.Connection) -> str:
    network = ipaddress.ip_network(ADDRESS_POOL, strict=False)
    used = {row["address"].split("/")[0] for row in db.execute("SELECT address FROM peers").fetchall()}
    reserved = {str(network.network_address), str(network.broadcast_address), SERVER_ADDRESS}

    for host in network.hosts():
        value = str(host)
        if value in reserved or value in used:
            continue
        return f"{value}/{network.prefixlen}"

    raise HTTPException(status_code=503, detail="Address pool is exhausted")


def apply_peer(public_key: str, address: str) -> None:
    run_wg("set", INTERFACE, "peer", public_key, "allowed-ips", address)


def remove_peer(public_key: str) -> None:
    run_wg("set", INTERFACE, "peer", public_key, "remove")


def restore_peers() -> None:
    with closing(connect_db()) as db:
        rows = db.execute("SELECT public_key, address FROM peers").fetchall()
    for row in rows:
        try:
            apply_peer(row["public_key"], row["address"])
        except Exception:
            pass


def read_interface_public_key() -> str:
    return run_wg("show", INTERFACE, "public-key")


def active_clients() -> int:
    output = run_wg("show", INTERFACE, "latest-handshakes")
    if not output:
        return 0
    now = int(datetime.now(timezone.utc).timestamp())
    count = 0
    for line in output.splitlines():
        parts = line.split()
        if len(parts) != 2:
            continue
        try:
            ts = int(parts[1])
        except ValueError:
            continue
        if ts > 0 and now - ts <= 180:
            count += 1
    return count


@app.on_event("startup")
def on_startup() -> None:
    if not AGENT_TOKEN:
        raise RuntimeError("ENEIDA_AGENT_TOKEN is required")
    init_db()
    ensure_interface()
    restore_peers()


class PeerCreate(BaseModel):
    device_id: str = Field(min_length=8, max_length=120)
    public_key: str = Field(min_length=40, max_length=60)


@app.get("/health", dependencies=[Depends(require_token)])
def health() -> dict:
    return {
        "ok": True,
        "interface": INTERFACE,
        "server_public_key": read_interface_public_key(),
        "active_clients": active_clients(),
    }


@app.get("/v1/peers", dependencies=[Depends(require_token)])
def list_peers() -> dict:
    with closing(connect_db()) as db:
        rows = db.execute("SELECT * FROM peers ORDER BY created_at ASC").fetchall()
    return {
        "peers": [
            {
                "device_id": row["device_id"],
                "public_key": row["public_key"],
                "address": row["address"],
                "created_at": row["created_at"],
                "updated_at": row["updated_at"],
            }
            for row in rows
        ]
    }


@app.post("/v1/peers", dependencies=[Depends(require_token)])
def create_or_update_peer(payload: PeerCreate) -> dict:
    now = now_iso()
    with closing(connect_db()) as db:
        existing = db.execute(
            "SELECT * FROM peers WHERE device_id = ?",
            (payload.device_id,),
        ).fetchone()

        if existing:
            if existing["public_key"] != payload.public_key:
                try:
                    remove_peer(existing["public_key"])
                except Exception:
                    pass
                db.execute(
                    "UPDATE peers SET public_key = ?, updated_at = ? WHERE device_id = ?",
                    (payload.public_key, now, payload.device_id),
                )
                db.commit()
                existing = db.execute(
                    "SELECT * FROM peers WHERE device_id = ?",
                    (payload.device_id,),
                ).fetchone()

            apply_peer(existing["public_key"], existing["address"])
            return {
                "device_id": existing["device_id"],
                "public_key": existing["public_key"],
                "address": existing["address"],
                "server_public_key": read_interface_public_key(),
            }

        address = allocate_address(db)
        db.execute(
            """
            INSERT INTO peers (device_id, public_key, address, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (payload.device_id, payload.public_key, address, now, now),
        )
        db.commit()

    try:
        apply_peer(payload.public_key, address)
    except Exception as error:
        with closing(connect_db()) as db:
            db.execute("DELETE FROM peers WHERE device_id = ?", (payload.device_id,))
            db.commit()
        raise HTTPException(status_code=500, detail=f"Failed to apply WireGuard peer: {error}")

    return {
        "device_id": payload.device_id,
        "public_key": payload.public_key,
        "address": address,
        "server_public_key": read_interface_public_key(),
    }


@app.delete("/v1/peers/{device_id}", dependencies=[Depends(require_token)])
def delete_peer(device_id: str) -> dict:
    with closing(connect_db()) as db:
        row = db.execute(
            "SELECT * FROM peers WHERE device_id = ?",
            (device_id,),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Peer not found")
        db.execute("DELETE FROM peers WHERE device_id = ?", (device_id,))
        db.commit()

    try:
        remove_peer(row["public_key"])
    except Exception:
        pass
    return {"ok": True}
