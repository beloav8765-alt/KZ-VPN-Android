#!/usr/bin/env python3
"""Read Linux VPN host counters; emit an aggregate report without peer identities."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time


def read(path):
    try:
        return Path(path).read_text()
    except OSError:
        return None


def number(path):
    value = read(path)
    try:
        return int(value.strip()) if value is not None else None
    except ValueError:
        return None


def command(name, *args):
    executable = shutil.which(name)
    if not executable:
        return {"status": "command_missing"}, None
    try:
        result = subprocess.run(
            [executable, *args], stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, timeout=3, check=False,
        )
        if result.returncode:
            return {"status": "unavailable", "exit_code": result.returncode}, None
        return {"status": "ok"}, result.stdout
    except (OSError, subprocess.TimeoutExpired):
        return {"status": "unavailable"}, None


def cpu_snapshot():
    rows = {}
    for line in (read("/proc/stat") or "").splitlines():
        fields = line.split()
        if fields and (fields[0] == "cpu" or (fields[0].startswith("cpu") and fields[0][3:].isdigit())):
            # guest/guest_nice are already included in user/nice: use the first 8.
            rows[fields[0]] = [int(value) for value in fields[1:9]]
    return rows


def cpu_usage(before, after):
    usage = {}
    for name in before.keys() & after.keys():
        delta = [right - left for left, right in zip(before[name], after[name])]
        if len(delta) != 8 or any(value < 0 for value in delta) or sum(delta) == 0:
            continue
        total = sum(delta)
        usage[name] = {
            "active_pct": round(sum(delta[index] for index in (0, 1, 2, 5, 6)) * 100 / total, 2),
            "steal_pct": round(delta[7] * 100 / total, 2),
            "iowait_pct": round(max(0, delta[4]) * 100 / total, 2),
        }
    cores = [value["active_pct"] for name, value in usage.items() if name != "cpu"]
    return {
        "logical_cpus": len(after) - int("cpu" in after),
        "aggregate": usage.get("cpu"),
        "busiest_core_active_pct": max(cores) if cores else None,
    }


def links_snapshot():
    rows = {}
    indices = {"rx_bytes": 0, "rx_packets": 1, "rx_errors": 2, "rx_dropped": 3,
               "tx_bytes": 8, "tx_packets": 9, "tx_errors": 10, "tx_dropped": 11}
    for line in (read("/proc/net/dev") or "").splitlines():
        if ":" not in line:
            continue
        name, data = line.rsplit(":", 1)
        values = data.split()
        if len(values) >= 16:
            rows[name.strip()] = {key: int(values[index]) for key, index in indices.items()}
    return rows


def protocol_snapshot():
    text = read("/proc/net/snmp")
    if text is None:
        return None
    lines = text.splitlines()
    counters = {}
    ip_fields = {"InDiscards", "OutDiscards", "ReasmFails", "FragFails", "FragCreates"}
    for header, values in zip(lines[::2], lines[1::2]):
        names, numbers = header.split(), values.split()
        if not names or len(names) != len(numbers) or names[0] != numbers[0]:
            continue
        group = names[0].rstrip(":")
        for name, value in zip(names[1:], numbers[1:]):
            if group == "Udp" or group == "Ip" and name in ip_fields:
                counters[group + name] = int(value)
    for line in (read("/proc/net/snmp6") or "").splitlines():
        fields = line.split()
        if len(fields) == 2 and (fields[0].startswith("Udp6") or fields[0] in {"Ip6ReasmFails", "Ip6FragFails"}):
            counters[fields[0]] = int(fields[1])
    return counters


def counter_delta(before, after):
    if before is None or after is None:
        return None
    # A reset/disappearing counter is unknown, not zero loss.
    return {name: after[name] - value if name in after and after[name] >= value else None
            for name, value in before.items()}


def wireguard_summary():
    status, output = command("wg", "show", "interfaces")
    if output is None:
        return status
    interfaces = []
    now = time.time()
    for name in output.split():
        item = {"interface": name}
        state, handshakes = command("wg", "show", name, "latest-handshakes")
        item["handshakes"] = state
        if handshakes is not None:
            timestamps = [int(parts[1]) for line in handshakes.splitlines()
                          if len(parts := line.split()) == 2]
            ages = [max(0, now - stamp) for stamp in timestamps if stamp > 0]
            item["handshakes"].update({
                "peer_count": len(timestamps),
                "recent_180s": sum(age <= 180 for age in ages),
                "never_completed": sum(stamp == 0 for stamp in timestamps),
                "newest_age_seconds": round(min(ages), 1) if ages else None,
            })
        # Peer keys are discarded; only aggregate byte counters enter the report.
        state, transfers = command("wg", "show", name, "transfer")
        item["transfers"] = state
        if transfers is not None:
            values = [line.split() for line in transfers.splitlines()]
            item["transfers"].update({
                "rx_bytes_total": sum(int(parts[1]) for parts in values if len(parts) == 3),
                "tx_bytes_total": sum(int(parts[2]) for parts in values if len(parts) == 3),
            })
        interfaces.append(item)
    return {"status": "ok", "interfaces": interfaces}


def queues(kind):
    status, output = command("tc", "-j", "-s", kind, "show")
    if output is None:
        return status
    try:
        entries = json.loads(output)
        # Whitelist scheduling parameters. No filters, addresses or arbitrary options.
        fields = {"kind", "dev", "handle", "parent", "root", "bytes", "packets",
                  "drops", "overlimits", "requeues", "backlog", "qlen"}
        options = {"rate", "ceil", "limit", "burst", "latency", "target", "interval",
                   "delay", "loss", "rate64", "quantum", "flow_limit"}
        selected = []
        for entry in entries:
            item = {key: value for key, value in entry.items()
                    if key in fields and isinstance(value, (int, float, str, bool))}
            item["options"] = {key: value for key, value in entry.get("options", {}).items()
                               if key in options and isinstance(value, (int, float, str, bool))}
            selected.append(item)
        return {"status": "ok", "cumulative": True, "entries": selected}
    except (ValueError, AttributeError, TypeError):
        return {"status": "unsupported_output"}


def snapshot():
    return {"cpu": cpu_snapshot(), "links": links_snapshot(),
            "protocol": protocol_snapshot(), "at": time.monotonic()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=int, default=30, help="Sample interval (1..60, default 30)")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 60:
        parser.error("--seconds must be in 1..60")
    if platform.system() != "Linux":
        parser.error("Run this script on the Linux VPN server")

    print("Sampling host counters. Run the phone's VPN speed test now.", file=sys.stderr, flush=True)
    before = snapshot()
    time.sleep(args.seconds)
    after = snapshot()
    elapsed = after["at"] - before["at"]
    links = {}
    for name, counters in after["links"].items():
        delta = counter_delta(before["links"].get(name), counters)
        # Interface names come from the kernel, not caller-controlled file paths.
        mtu = number(Path("/sys/class/net") / name / "mtu")
        links[name] = {"mtu": mtu, "delta": delta}
        for direction in ("rx", "tx"):
            value = delta.get(direction + "_bytes") if delta is not None else None
            links[name][direction + "_mbps"] = round(value * 8 / elapsed / 1_000_000, 3) if value is not None else None

    mem = {}
    for line in (read("/proc/meminfo") or "").splitlines():
        name, _, value = line.partition(":")
        if name in {"MemTotal", "MemAvailable", "SwapTotal", "SwapFree"}:
            mem[name + "_kib"] = int(value.split()[0])
    sysctls = ("net.ipv4.ip_forward", "net.ipv6.conf.all.forwarding",
               "net.core.rmem_max", "net.core.wmem_max", "net.core.netdev_max_backlog",
               "net.ipv4.udp_rmem_min", "net.ipv4.udp_wmem_min")
    report = {
        "schema_version": 1,
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "kernel": platform.release(),
        "sample_seconds": round(elapsed, 3),
        "run_as_root": os.geteuid() == 0,
        "cpu": cpu_usage(before["cpu"], after["cpu"]),
        "memory": mem,
        "links": links,
        "protocol_counter_delta": counter_delta(before["protocol"], after["protocol"]),
        "wireguard": wireguard_summary(),
        "qdiscs": queues("qdisc"),
        "traffic_classes": queues("class"),
        "sysctls": {key: number("/proc/sys/" + key.replace(".", "/")) for key in sysctls},
        "scope": "Host-wide CPU; network counters from the current network namespace, not per-client loss. Rates are from the server's perspective. Queue counters are cumulative.",
    }
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    main()
