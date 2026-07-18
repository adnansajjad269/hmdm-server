# hmdm-stats — Device Status History & Dashboard for Headwind MDM CE

Headwind MDM CE keeps no history: the `devices` table is overwritten on every
device sync. This bundle adds a small, independent pipeline alongside a
self-hosted Headwind CE server that:

- samples **battery level, charging state, and online/offline** for every
  device on a cron schedule (default every 10 min),
- keeps **30 days** of history in a `device_status_history` table in the same
  Postgres instance (read-only with respect to Headwind's own tables),
- serves a provisioned **Grafana dashboard** (LAN-only) with fleet stat tiles,
  a needs-attention table, a battery-over-time chart colored **green while
  online / red while offline**, and an online/offline state timeline,
- provisions **alert rules**: device offline > 1 h, battery below the warn
  (20 %) and critical (5 %) tiers (aligned with FreeKiosk's battery-protection
  latch), and a pipeline-staleness backstop — routed to email and/or a webhook,
- optionally injects an **"Analytics" tab** into the deployed Headwind web
  panel (between Devices and Applications) that iframes the dashboard.

Nothing in Headwind MDM's source or schema is modified. Scales comfortably to
~200 devices (~864k rows / 30 days — a plain indexed table).

```
devices (Headwind, overwritten each sync)
   │  read-only SELECT every N min (cron -> snapshot.py)
   ▼
device_status_history (30-day rolling window)
   │  read-only role grafana_ro
   ▼
Grafana :3000 (LAN only) ── alert rules ──> email / webhook
   ▲
   └── <iframe> in the injected "Analytics" tab of the Headwind panel
```

## Requirements

- Ubuntu VM running Headwind MDM CE (tested target: CE 5.39) with local
  Postgres, database `hmdm`, accessible via `sudo -u postgres psql`
- root/sudo, outbound HTTPS to `apt.grafana.com` (Grafana install)
- Devices running the Headwind launcher (its device-info push is every 15 min;
  the launcher payload provides `batteryLevel` 0–100 and `batteryCharging`)

## Install

```bash
sudo git clone <this repo> /opt/hmdm-stats
cd /opt/hmdm-stats
cp .env.example .env && $EDITOR .env    # alert routing, SMTP, bind address
sudo ./install.sh
```

`install.sh` is idempotent — re-run it after changing `.env`. Useful flags:
`--dry-run` (preflight only, no changes), `--skip-grafana-install` (Grafana
already present), `--skip-webpanel` (don't touch the Headwind panel).

The installer's **preflight** verifies the live schema first (column names,
`info` text vs jsonb, `lastupdate` epoch-millis vs seconds, JSON keys in real
payloads) and refuses to install on any mismatch, telling you exactly what it
found. The detected mapping lands in `/etc/hmdm-stats/hmdm-stats.conf` — the
single runtime config for the snapshot job.

## What "online" means

`online = now() − devices.lastupdate < ONLINE_THRESHOLD_SECONDS` (default
1800 s = 2× the launcher's fixed 15-minute device-info interval). A device that
never synced counts as offline.

## Dashboard

`http://10.10.10.12:3000/d/hmdm-fleet` — anonymous Viewer access on the LAN.

- **Stat tiles**: online now, offline now, battery below a pickable threshold,
  offline longer than a pickable duration.
- **Needs attention** table: all devices, lowest battery / longest offline
  first — the operational view at 200 devices.
- **Battery over time**: scoped by the multi-select *Device* picker (defaults
  to one device — don't select All with a big fleet). Each device renders as a
  green line while online and red while offline. A one-sample gap at each
  transition is expected (two series per device under the hood).
- **Online/offline timeline**: banded state view, same device picker.
- Default range: last 30 days; zoom freely.

Panels are provisioned read-only from
`grafana/provisioning/dashboards/json/fleet-dashboard.json`; edit that file
(and re-run install.sh) rather than the UI — UI edits are not persisted.

## Alerting

| Rule | Fires when | Severity |
|---|---|---|
| Device offline too long | offline > `OFFLINE_ALERT_SECONDS` (1 h) | warning |
| Battery low (warn tier) | battery < `BATTERY_WARN` (20 %), online, not charging | warning |
| Battery critical (freeze tier) | battery < `BATTERY_CRIT` (5 %), online, not charging | critical |
| Snapshot pipeline stale | no new samples for 30 min | critical |

Routing: set `ALERT_EMAIL_TO` (plus `SMTP_*`) and/or `ALERT_WEBHOOK_URL` in
`.env` — the installer provisions a single contact point with whichever
integrations are configured. Neither set → rules still evaluate but go to
Grafana's default empty receiver (a warning is printed).

## Security notes

- Grafana binds to the **LAN address only** and anonymous access is
  Viewer-only, needed for the iframe tab. **Never** add a pfSense port-forward
  for :3000. For off-LAN access, use a VPN (WireGuard/OpenVPN) into the LAN.
- `grafana_ro` can read only `device_status_history`; `hmdm_stats` can read
  `devices` and write only the history table. Headwind's tables are otherwise
  untouched.
- Secrets live in `.env` (0600, gitignored) and
  `/etc/hmdm-stats/hmdm-stats.conf` (0600).

## The Analytics tab

Now living in this same repo, so the Analytics tab is a **native, source-level
tab** — no runtime file patching. It's built into the panel itself:

- `server/src/main/webapp/app/app.js` — new ui-router state `analytics`,
  registered between `main` (Devices) and `applications`.
- `server/src/main/webapp/app/components/main/controller/tabs.controller.js`
  — `ANALYTICS: 'analytics'` added to the tab→state map, between `DEVICES`
  and `APPS`.
- `server/src/main/webapp/app/components/main/view/content.html` — a new
  `<tab>` between the Devices and Applications tabs.
- `server/src/main/webapp/app/components/main/controller/analytics.controller.js`
  and `.../view/analytics.html` — a small `AnalyticsTabController` that
  builds the Grafana iframe URL from `window.location.hostname` (no
  build-time templating needed — it always points at Grafana on the same
  host, port 3000) and shows a plain-text fallback notice instead of a
  broken iframe if the panel itself is served over HTTPS (browsers block
  a plain-HTTP iframe on an HTTPS page — mixed content).
- `server/src/main/webapp/localization/en_US.js` — `tab.analytics`,
  `breadcrumb.analytics`, `analytics.https.warning` keys.

Building the server from this repo (`mvn package`, or however you normally
build/deploy Headwind) produces a WAR with the tab already in it — nothing
to run after deploy. This replaces the earlier approach, which patched an
*already-deployed* webapp at runtime by pattern-matching its markup; against
the real CE panel source that markup never matched what the script assumed
(the panel uses ui-router + a Bootstrap `<tabset>`, not `ngRoute` links), so
that script would have silently done nothing. It's kept under `webpanel/` as
a documented fallback **only** for deployments that install a pre-built WAR
they don't rebuild themselves — see `webpanel/analytics-manual-steps.md`,
which now points at the native tab first. If you deploy from this repo's
source, pass `--skip-webpanel` to `install.sh`.

## Why not the built-in `deviceinfo` plugin?

Headwind CE already ships an open-source plugin (`plugins/deviceinfo/`) with its
own per-device history tables (`plugin_deviceinfo_deviceParams` +
`..._device`/`..._wifi`/`..._gps`/`..._mobile[2]`), a per-customer settings UI
(`dataPreservePeriod`, `intervalMins`, `sendData` — same 15-min/30-day defaults
we use here), and a daily purge job. It looks like a natural fit, but it's not
usable as-is: the launcher-side code that would actually populate those
tables (`DetailedInfoWorker.schedule()`/`requestConfigUpdate()` in
`hmdm-android`) is a documented Pro-only stub in the open-source build — its
methods are no-ops, `ServerService.sendDetailedInfo`/`getDetailedInfoConfig`
are never called anywhere in the app, and the matching on-device buffer table
(`InfoHistoryTable`) is created but never written to. Turning on `sendData`
for a customer today does nothing; no device will ever call the ingest
endpoint. Getting real data flowing would mean writing and shipping a new
`DetailedInfoWorker` implementation to the whole fleet — a much bigger,
riskier change than this pipeline, which needs **no launcher changes** at all
because it reads `devices.info`/`lastupdate`, already populated by the
launcher's real, unconditional 15-min sync. The plugin's own UI is also
single-device-only raw tables with no chart and no fleet view either way, so
even with data flowing it wouldn't replace the Grafana dashboard here.

If a real `DetailedInfoWorker` (or a Pro launcher) is ever adopted, its
richer fields (wifi RSSI, GPS, mobile signal, memory) would be a legitimate
future enhancement to layer onto this same Grafana dashboard — not a v1 item.

## Changing the sample interval / retention

Edit `SNAPSHOT_INTERVAL_MIN` / `RETENTION_DAYS` in `.env` and re-run
`sudo ./install.sh`. The snapshot dedup bucket is derived from the interval,
so don't edit `/etc/cron.d/hmdm-stats` directly.

## Operations

- Logs: `/var/log/hmdm-stats/snapshot.log` (rotated weekly); failures also go
  to syslog (`logger -t hmdm-stats`). The *pipeline stale* Grafana alert is
  the backstop if cron dies entirely.
- Reboot-safe: cron lives in `/etc/cron.d`, Grafana is `systemctl enable`d.
- Verify anytime: `sudo -u postgres psql -d hmdm -f /opt/hmdm-stats/sql/verify.sql`

## Acceptance checklist

1. `verify.sql` §1: row count grows and `staleness` < interval; re-check after
   25 min — `newest_sample` advanced.
2. §2: `dup_devices` = 0 on every bucket; run
   `sudo /opt/hmdm-stats/snapshot/snapshot-cron.sh` twice back-to-back — the
   second logs `inserted=0`.
3. §3: after 30+ days, `history_span` stays ≈ 30 days (pruning works).
4. §4/§5 match what the Headwind admin console shows at the same moment.
5. Dashboard loads on the LAN at `http://10.10.10.12:3000/d/hmdm-fleet`
   without login; values match §4.
6. Pick a device that went offline — its battery trace is green then red.
7. Grafana → Alerting → Alert rules: 4 rules in folder *HMDM Stats*; use
   *Test* on the contact point; stop cron for 35 min → *Snapshot pipeline
   stale* fires; re-enable.
8. Reboot the VM: `/etc/cron.d/hmdm-stats` persists,
   `systemctl is-enabled grafana-server` → `enabled`, a new sample appears
   within the interval.
9. Analytics tab shows between Devices and Applications; re-running the
   injector doesn't duplicate it.

## Uninstall

See [UNINSTALL.md](UNINSTALL.md).
