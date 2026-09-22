import os
import sqlite3
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

BASE_DIR = Path(__file__).resolve().parent.parent
DB_PATH = Path(os.getenv("NIVORA_DB", BASE_DIR / "data" / "nivora.db"))
ADMIN_TOKEN = os.getenv("NIVORA_ADMIN_TOKEN", "").strip()

app = FastAPI(title="Nivora Control", version="0.1.0")

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
        db.commit()

@app.on_event("startup")
def on_startup() -> None:
    if not ADMIN_TOKEN:
        raise RuntimeError("NIVORA_ADMIN_TOKEN is required")
    init_db()

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

class ServerUpdate(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=80)
    region: Optional[str] = Field(default=None, min_length=1, max_length=80)
    endpoint_host: Optional[str] = Field(default=None, min_length=1, max_length=255)
    endpoint_port: Optional[int] = Field(default=None, ge=1, le=65535)
    public_key: Optional[str] = Field(default=None, min_length=20, max_length=200)
    enabled: Optional[bool] = None
    maintenance: Optional[bool] = None
    priority: Optional[int] = Field(default=None, ge=0, le=10000)

class ServerHeartbeat(BaseModel):
    load_pct: int = Field(ge=0, le=100)
    active_clients: int = Field(ge=0, le=1000000)

def row_to_admin(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"], "name": row["name"], "region": row["region"],
        "endpoint_host": row["endpoint_host"], "endpoint_port": row["endpoint_port"],
        "public_key": row["public_key"], "enabled": bool(row["enabled"]),
        "maintenance": bool(row["maintenance"]), "priority": row["priority"],
        "load_pct": row["load_pct"], "active_clients": row["active_clients"],
        "last_seen": row["last_seen"], "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }

@app.get("/health")
def health() -> dict:
    return {"ok": True, "service": "nivora-control"}

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
    return {"servers": [row_to_admin(row) for row in rows]}

@app.post("/api/v1/admin/servers", dependencies=[Depends(require_admin)])
def create_server(payload: ServerCreate) -> dict:
    now = now_iso()
    with closing(connect_db()) as db:
        cur = db.execute("""
            INSERT INTO servers (
                name, region, endpoint_host, endpoint_port, public_key,
                enabled, maintenance, priority, load_pct, active_clients,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
        """, (
            payload.name.strip(), payload.region.strip(), payload.endpoint_host.strip(),
            payload.endpoint_port, payload.public_key.strip(), int(payload.enabled),
            int(payload.maintenance), payload.priority, now, now
        ))
        server_id = cur.lastrowid
        db.commit()
        row = db.execute("SELECT * FROM servers WHERE id = ?", (server_id,)).fetchone()
    return row_to_admin(row)

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
    return row_to_admin(row)

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
    return row_to_admin(row)

@app.get("/admin")
def admin_page():
    return FileResponse(BASE_DIR / "static" / "admin.html")
