#!/usr/bin/env python3
"""hmdm-stats snapshot: sample Headwind MDM device battery/online state.

Reads current rows from the Headwind `devices` table, derives battery level,
charging state and an online boolean per device, and inserts one bucket-aligned
row per device into device_status_history. Prunes rows past the retention
window in a second transaction. Idempotent: sampled_at is floored to the cron
bucket and the insert is ON CONFLICT DO NOTHING, so overlapping or repeated
runs within the same bucket are no-ops.

All live-schema specifics (column names, info text vs jsonb, lastupdate
epoch-millis vs seconds vs timestamptz) come from /etc/hmdm-stats/hmdm-stats.conf,
written by install.sh from preflight detection. Nothing here guesses.

Exit codes: 0 ok, 1 config error, 2 database error.
"""

import configparser
import contextlib
import json
import logging
import re
import sys
from datetime import datetime, timedelta, timezone

import psycopg2
import psycopg2.extras

DEFAULT_CONF = "/etc/hmdm-stats/hmdm-stats.conf"
IDENT_RE = re.compile(r"^[a-z_][a-z0-9_]*$")

log = logging.getLogger("hmdm-stats")


def load_conf(path):
    cp = configparser.ConfigParser(interpolation=None)
    if not cp.read(path):
        raise SystemExit(f"config not readable: {path}")
    conf = {
        "dsn": cp.get("db", "dsn"),
        "devices_table": cp.get("mapping", "devices_table", fallback="devices"),
        "id_col": cp.get("mapping", "id_col", fallback="id"),
        "number_col": cp.get("mapping", "number_col", fallback="number"),
        "info_col": cp.get("mapping", "info_col", fallback="info"),
        "info_kind": cp.get("mapping", "info_kind", fallback="text-json"),
        "lastupdate_col": cp.get("mapping", "lastupdate_col", fallback="lastupdate"),
        "lastupdate_kind": cp.get("mapping", "lastupdate_kind", fallback="epoch_millis"),
        "battery_json_key": cp.get("mapping", "battery_json_key", fallback="batteryLevel"),
        "charging_json_key": cp.get("mapping", "charging_json_key", fallback="batteryCharging"),
        "bucket_seconds": cp.getint("sampling", "bucket_seconds", fallback=600),
        "online_threshold_seconds": cp.getint("sampling", "online_threshold_seconds", fallback=1800),
        "retention_days": cp.getint("sampling", "retention_days", fallback=30),
    }
    for key in ("devices_table", "id_col", "number_col", "info_col", "lastupdate_col"):
        if not IDENT_RE.match(conf[key]):
            raise SystemExit(f"invalid SQL identifier in config: {key}={conf[key]!r}")
    if conf["info_kind"] not in ("text-json", "jsonb"):
        raise SystemExit(f"invalid info_kind: {conf['info_kind']!r}")
    if conf["lastupdate_kind"] not in ("epoch_millis", "epoch_seconds", "timestamptz"):
        raise SystemExit(f"invalid lastupdate_kind: {conf['lastupdate_kind']!r}")
    return conf


def to_timestamptz(value, kind):
    """Convert a raw lastupdate value to an aware datetime, or None."""
    if value is None:
        return None
    if kind == "timestamptz":
        if isinstance(value, datetime):
            return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
        return None
    try:
        num = float(value)
    except (TypeError, ValueError):
        return None
    if num <= 0:  # 0 = device never synced
        return None
    if kind == "epoch_millis":
        num /= 1000.0
    return datetime.fromtimestamp(num, tz=timezone.utc)


def parse_info(raw, info_kind, battery_key, charging_key):
    """Return (battery_level, charging, error) from a devices.info value."""
    if raw is None:
        return None, None, "info is NULL"
    if info_kind == "jsonb" and isinstance(raw, dict):
        payload = raw
    else:
        try:
            payload = json.loads(raw)
        except (TypeError, ValueError) as exc:
            return None, None, f"info not valid JSON: {exc}"
    if not isinstance(payload, dict):
        return None, None, "info JSON is not an object"

    battery = payload.get(battery_key)
    if isinstance(battery, bool) or not isinstance(battery, (int, float)):
        battery = None
    elif not 0 <= battery <= 100:
        battery = None
    else:
        battery = int(battery)

    charging = payload.get(charging_key)
    if not isinstance(charging, str) or charging == "":
        charging = None  # '' means not charging in the launcher payload

    return battery, charging, None


def main(argv):
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S%z",
    )
    conf_path = argv[1] if len(argv) > 1 else DEFAULT_CONF
    conf = load_conf(conf_path)

    now = datetime.now(timezone.utc)
    bucket = conf["bucket_seconds"]
    bucket_ts = datetime.fromtimestamp(
        (int(now.timestamp()) // bucket) * bucket, tz=timezone.utc
    )
    threshold = timedelta(seconds=conf["online_threshold_seconds"])

    select_sql = (
        f'SELECT {conf["id_col"]}, {conf["number_col"]}, '
        f'{conf["info_col"]}, {conf["lastupdate_col"]} '
        f'FROM {conf["devices_table"]}'
    )

    try:
        with contextlib.closing(psycopg2.connect(conf["dsn"])) as raw_db, raw_db as db:
            with db.cursor() as cur:
                cur.execute(select_sql)
                devices = cur.fetchall()

            rows = []
            parse_errors = 0
            seen = set()
            for dev_id, number, info, lastupdate in devices:
                if number is None:
                    continue
                number = str(number)
                if number in seen:  # duplicate device numbers would break the PK
                    log.warning("duplicate device number %r skipped", number)
                    continue
                seen.add(number)
                battery, charging, err = parse_info(
                    info, conf["info_kind"],
                    conf["battery_json_key"], conf["charging_json_key"],
                )
                if err:
                    parse_errors += 1
                    log.warning("device %s: %s (recorded with NULL battery)", number, err)
                last_update = to_timestamptz(lastupdate, conf["lastupdate_kind"])
                online = last_update is not None and (now - last_update) < threshold
                rows.append(
                    (number, dev_id, bucket_ts, battery, charging, last_update, online)
                )

            inserted = 0
            with db.cursor() as cur:
                if rows:
                    psycopg2.extras.execute_values(
                        cur,
                        "INSERT INTO device_status_history "
                        "(device_number, device_pk, sampled_at, battery_level, "
                        " charging, last_update, online) VALUES %s "
                        "ON CONFLICT (device_number, sampled_at) DO NOTHING",
                        rows,
                    )
                    inserted = cur.rowcount
        # insert transaction committed and connection closed here

        with contextlib.closing(psycopg2.connect(conf["dsn"])) as raw_db, raw_db as db:
            with db.cursor() as cur:
                cur.execute(
                    "DELETE FROM device_status_history "
                    "WHERE sampled_at < now() - make_interval(days => %s)",
                    (conf["retention_days"],),
                )
                pruned = cur.rowcount
    except psycopg2.Error as exc:
        log.error("database error: %s", str(exc).strip())
        return 2

    log.info(
        "bucket=%s devices=%d inserted=%d skipped=%d parse_errors=%d pruned=%d",
        bucket_ts.isoformat(), len(devices), inserted,
        len(rows) - inserted, parse_errors, pruned,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
