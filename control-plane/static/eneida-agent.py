import json
import os
import sqlite3
import subprocess
import time
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

import httpx

CONTROL_URL = os.getenv("ENEIDA_CONTROL_URL", "").strip().rstrip("/")
SERVER_ID = int(os.getenv("ENEIDA_SERVER_ID", "0"))
AGENT_TOKEN = os.getenv("ENEIDA_AGENT_TOKEN", "").strip()
INTERFACE = os.getenv("ENEIDA_AGENT_INTERFACE", "wg0").strip()
POLL_SECONDS = int(os.getenv("ENEIDA_AGENT_POLL_SECONDS", "5"))
DB_PATH = Path(os.getenv("ENEIDA_AGENT_DB", "/var/lib/eneida-agent/agent.db"))

HEADERS = {"Authorization": f"Bearer {AGENT_TOKEN}"}


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
            CREATE TABLE IF NOT EXISTS managed_peers (
                device_id TEXT PRIMARY KEY,
                public_key TEXT NOT NULL,
                address TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """)
        db.commit()


def run(*args: str) -> str:
    result = subprocess.run(args, check=True, capture_output=True, text=True)
    return result.stdout.strip()


def wg(*args: str) -> str:
    return run("wg", *args)


def server_public_key() -> str:
    return wg("show", INTERFACE, "public-key")


def cpu_percent() -> int:
    try:
        load1 = float(Path("/proc/loadavg").read_text().split()[0])
        cpus = max(os.cpu_count() or 1, 1)
        return max(0, min(100, round(load1 / cpus * 100)))
    except Exception:
        return 0


def ram_percent() -> int:
    try:
        data = {}
        for line in Path("/proc/meminfo").read_text().splitlines():
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            data[key] = int(value.strip().split()[0])
        total = data.get("MemTotal", 0)
        available = data.get("MemAvailable", 0)
        if total <= 0:
            return 0
        return max(0, min(100, round((total - available) / total * 100)))
    except Exception:
        return 0


def active_clients() -> int:
    try:
        output = wg("show", INTERFACE, "latest-handshakes")
        now = int(time.time())
        count = 0
        for line in output.splitlines():
            parts = line.split()
            if len(parts) != 2:
                continue
            ts = int(parts[1])
            if ts > 0 and now - ts <= 180:
                count += 1
        return count
    except Exception:
        return 0


def managed_rows() -> dict:
    with closing(connect_db()) as db:
        rows = db.execute("SELECT * FROM managed_peers").fetchall()
    return {row["device_id"]: dict(row) for row in rows}


def apply_peer(public_key: str, address: str) -> None:
    wg("set", INTERFACE, "peer", public_key, "allowed-ips", address)


def remove_peer(public_key: str) -> None:
    try:
        wg("set", INTERFACE, "peer", public_key, "remove")
    except Exception:
        pass


def reconcile(desired: list[dict]) -> None:
    current = managed_rows()
    desired_by_device = {item["device_id"]: item for item in desired}

    for device_id, old in current.items():
        item = desired_by_device.get(device_id)
        if item is None:
            remove_peer(old["public_key"])
            with closing(connect_db()) as db:
                db.execute("DELETE FROM managed_peers WHERE device_id = ?", (device_id,))
                db.commit()
            continue

        if old["public_key"] != item["public_key"]:
            remove_peer(old["public_key"])

    for item in desired:
        apply_peer(item["public_key"], item["address"])
        with closing(connect_db()) as db:
            db.execute("""
                INSERT INTO managed_peers (device_id, public_key, address, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    public_key = excluded.public_key,
                    address = excluded.address,
                    updated_at = excluded.updated_at
            """, (
                item["device_id"],
                item["public_key"],
                item["address"],
                now_iso(),
            ))
            db.commit()


def post_heartbeat(client: httpx.Client, error: str | None = None) -> None:
    payload = {
        "public_key": server_public_key(),
        "active_clients": active_clients(),
        "cpu_pct": cpu_percent(),
        "ram_pct": ram_percent(),
        "error": error,
    }
    response = client.post(
        f"{CONTROL_URL}/api/v1/agent/{SERVER_ID}/heartbeat",
        headers=HEADERS,
        json=payload,
    )
    response.raise_for_status()


def fetch_desired(client: httpx.Client) -> list[dict]:
    response = client.get(
        f"{CONTROL_URL}/api/v1/agent/{SERVER_ID}/sync",
        headers=HEADERS,
    )
    response.raise_for_status()
    return response.json().get("peers", [])


def main() -> None:
    if not CONTROL_URL.startswith("https://"):
        raise SystemExit("ENEIDA_CONTROL_URL must use HTTPS")
    if SERVER_ID <= 0:
        raise SystemExit("ENEIDA_SERVER_ID is required")
    if not AGENT_TOKEN:
        raise SystemExit("ENEIDA_AGENT_TOKEN is required")

    init_db()
    wg("show", INTERFACE)

    with httpx.Client(timeout=15.0) as client:
        last_error = None
        while True:
            try:
                desired = fetch_desired(client)
                reconcile(desired)
                post_heartbeat(client)
                last_error = None
            except Exception as error:
                message = str(error)[:500]
                if message != last_error:
                    try:
                        post_heartbeat(client, message)
                    except Exception:
                        pass
                    last_error = message
            time.sleep(max(POLL_SECONDS, 2))


if __name__ == "__main__":
    main()
