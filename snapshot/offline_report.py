#!/usr/bin/env python3
"""hmdm-stats offline devices report: a point-in-time snapshot, sent on its own cron
schedule (independent of Grafana alerting).

This used to be a Grafana alert rule (interval: 4h) fanned out through a webhook
contact point, with tiering/formatting done in a Go notification template. That
was retired because it fundamentally couldn't do what was wanted: a strict "exactly
every N hours, reflecting current status at send time" heartbeat. Grafana's rule
`interval` controls how often the query re-runs, but Alertmanager's `repeat_interval`
is a *separate* clock that just re-sends the last notification for an alert that
hasn't resolved yet -- it does not re-query anything. With both set to the same 4h,
the two clocks drift out of phase, and a repeat-interval resend can fire using
stale (pre-recovery) data from the previous evaluation, moments before the next
real evaluation would have caught the device coming back online. Since this report
only ever needs "query now, format now, send now" and nothing resembling
alert-state tracking, it doesn't need Grafana's alerting engine at all -- a plain
cron job that queries fresh data on every run sidesteps the mismatch entirely.

Reads the same /etc/hmdm-stats/hmdm-stats.conf as snapshot.py (for the DB DSN) plus
an [offline_report] section for the webhook URL. Sends nothing (exit 0) when no
device qualifies -- silent when the fleet is fully online/unassigned, matching the
original alert's `disableResolveMessage` behavior.

Exit codes: 0 ok (including nothing-to-report), 1 config error, 2 database error,
3 webhook delivery error.
"""

import configparser
import contextlib
import json
import logging
import sys
import urllib.error
import urllib.request

import psycopg2

DEFAULT_CONF = "/etc/hmdm-stats/hmdm-stats.conf"

log = logging.getLogger("hmdm-stats")

# Same query as the retired Grafana rule (rules.yaml.tmpl), minus the sentinel row
# hack that worked around Grafana's format:table + reduce/threshold failing on a
# genuinely empty result -- plain SQL has no such restriction. Table/column names
# here are hardcoded (device_number, devices.number, devices.id, plugin_itam_log's
# columns) rather than driven by hmdm-stats.conf's [mapping] section, matching how
# the original Grafana rule was written: independent of snapshot.py's configurable
# `devices` table mapping.
REPORT_SQL = """
WITH latest AS (
  SELECT DISTINCT ON (dsh.device_number) dsh.device_number, dsh.online, dsh.last_update
  FROM device_status_history dsh
  ORDER BY dsh.device_number, dsh.sampled_at DESC
),
offline_devices AS (
  SELECT device_number,
         GREATEST(0, ROUND((extract(epoch FROM (now() - COALESCE(last_update, timestamptz 'epoch'))) / 3600.0)::numeric, 1)) AS hours_offline
  FROM latest
  WHERE NOT online
),
latest_owner AS (
  SELECT DISTINCT ON (d.number) d.number AS device_number, l.ownername
  FROM devices d
  JOIN plugin_itam_log l ON l.deviceid = d.id AND l.deletedat IS NULL
  ORDER BY d.number, l.createdat DESC
),
owners AS (
  SELECT device_number, ownername
  FROM latest_owner
  WHERE ownername IS NOT NULL
    AND trim(ownername) <> ''
    AND upper(trim(ownername)) <> 'N/A'
)
SELECT o.device_number, ow.ownername AS owner_name, o.hours_offline
FROM offline_devices o
JOIN owners ow ON ow.device_number = o.device_number
ORDER BY o.hours_offline DESC
"""


def load_conf(path):
    cp = configparser.ConfigParser(interpolation=None)
    if not cp.read(path):
        raise SystemExit(f"config not readable: {path}")
    webhook_url = cp.get("offline_report", "webhook_url", fallback="")
    if not webhook_url:
        raise SystemExit("[offline_report] webhook_url is empty in config")
    return {
        "dsn": cp.get("db", "dsn"),
        "webhook_url": webhook_url,
    }


def format_message(rows):
    """rows: list of (device_number, owner_name, hours_offline), already DESC by hours.
    Reproduces the tiering/format the retired Grafana template produced."""
    gt12h, lt12h, lt1h = [], [], []
    for device_number, owner_name, hours in rows:
        line = f"{device_number} -- {owner_name} -- {float(hours):.1f} hours"
        if hours >= 12.0:
            gt12h.append(line)
        elif hours >= 1.0:
            lt12h.append(line)
        else:
            lt1h.append(line)

    msg = f"Offline Devices Report -- {len(rows)} devices offline\n"
    for heading, tier in (
        ("Offline > 12 hours", gt12h),
        ("Offline 1-12 hours", lt12h),
        ("Offline < 1 hour", lt1h),
    ):
        if not tier:
            continue
        msg += f"\n------------------------------------\n{heading} ({len(tier)} devices)\n------------------------------------\n"
        for line in tier:
            msg += f"{line}\n"
    return msg


def send_webhook(url, message):
    body = json.dumps({"text": message}).encode("utf-8")
    req = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        resp.read()


def main(argv):
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S%z",
    )
    conf_path = argv[1] if len(argv) > 1 else DEFAULT_CONF
    conf = load_conf(conf_path)

    try:
        with contextlib.closing(psycopg2.connect(conf["dsn"])) as db:
            with db.cursor() as cur:
                cur.execute(REPORT_SQL)
                rows = cur.fetchall()
    except psycopg2.Error as exc:
        log.error("database error: %s", str(exc).strip())
        return 2

    if not rows:
        log.info("0 devices offline (with a known owner) — nothing to report")
        return 0

    message = format_message(rows)
    try:
        send_webhook(conf["webhook_url"], message)
    except (urllib.error.URLError, TimeoutError) as exc:
        log.error("webhook delivery failed: %s", exc)
        return 3

    log.info("sent report: %d devices offline", len(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
