#!/usr/bin/env bash
# hmdm-stats preflight: verify the live Headwind schema and environment before
# anything is installed. Read-only. Writes the detected column mapping to
# ./preflight.out (INI fragment merged into /etc/hmdm-stats/hmdm-stats.conf by
# install.sh) and prints a human-readable report.
#
# Usage: sudo ./preflight.sh [dbname]     (default dbname: $HMDM_DB_NAME or hmdm)
# Exit: 0 ok, 1 hard failure (report explains what to fix / set in .env).
set -euo pipefail

DB="${1:-${HMDM_DB_NAME:-hmdm}}"
BATTERY_KEY="${BATTERY_JSON_KEY:-batteryLevel}"
CHARGING_KEY="${CHARGING_JSON_KEY:-batteryCharging}"
OUT="$(dirname "$0")/preflight.out"
FAIL=0

say()  { printf '%s\n' "$*"; }
ok()   { say "  [ok]   $*"; }
warn() { say "  [warn] $*"; }
fail() { say "  [FAIL] $*"; FAIL=1; }

psq() { sudo -u postgres psql -d "$DB" -AtX -v ON_ERROR_STOP=1 -c "$1"; }

say "== hmdm-stats preflight (db=$DB) =="

# --- 1. database + devices table ---------------------------------------------
if ! sudo -u postgres psql -AtX -c "SELECT 1 FROM pg_database WHERE datname='$DB'" | grep -q 1; then
    fail "database '$DB' not found; set HMDM_DB_NAME in .env"
    say "   databases present:"; sudo -u postgres psql -AtX -c "SELECT datname FROM pg_database WHERE NOT datistemplate" | sed 's/^/     /'
    exit 1
fi
ok "database '$DB' exists"

DEVICES_TABLE=devices
if ! psq "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='devices'" | grep -q 1; then
    say "  table 'devices' not found; candidates with a lastupdate-like column:"
    psq "SELECT table_name FROM information_schema.columns WHERE table_schema='public' AND column_name ILIKE '%lastupdate%'" | sed 's/^/     /'
    fail "cannot locate the devices table — inspect the schema and adjust preflight.sh"
    exit 1
fi
ok "table 'devices' exists"

cols() { psq "SELECT column_name || '|' || data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='$DEVICES_TABLE'"; }
COLS="$(cols)"

pick_col() { # $1: preferred names (space-separated); echoes "name|type" or empty
    local want
    for want in $1; do
        local hit
        hit=$(printf '%s\n' "$COLS" | awk -F'|' -v w="$want" '$1==w {print; exit}')
        [ -n "$hit" ] && { printf '%s' "$hit"; return 0; }
    done
    return 1
}

# --- 2. column resolution ------------------------------------------------------
ID_HIT=$(pick_col "id") || { fail "no 'id' column on devices"; exit 1; }
NUM_HIT=$(pick_col "number deviceid device_id") || { fail "no device-number column (tried: number, deviceid, device_id). Columns present: $(printf '%s ' $COLS)"; exit 1; }
INFO_HIT=$(pick_col "info") || { fail "no 'info' column on devices — CE version may store device info elsewhere. Columns present: $(printf '%s ' $COLS)"; exit 1; }
LU_HIT=$(pick_col "lastupdate last_update") || { fail "no lastupdate column (tried: lastupdate, last_update). Columns present: $(printf '%s ' $COLS)"; exit 1; }

NUMBER_COL=${NUM_HIT%%|*}
INFO_COL=${INFO_HIT%%|*};  INFO_TYPE=${INFO_HIT#*|}
LU_COL=${LU_HIT%%|*};      LU_TYPE=${LU_HIT#*|}
ok "columns: number=$NUMBER_COL info=$INFO_COL($INFO_TYPE) lastupdate=$LU_COL($LU_TYPE)"

case "$INFO_TYPE" in
    jsonb|json) INFO_KIND=jsonb ;;
    text|"character varying") INFO_KIND=text-json ;;
    *) fail "unexpected type for $INFO_COL: $INFO_TYPE"; exit 1 ;;
esac

case "$LU_TYPE" in
    bigint|integer|numeric)
        MAXLU=$(psq "SELECT COALESCE(max($LU_COL),0)::text FROM $DEVICES_TABLE")
        # magnitude classification: epoch millis today ~ 1.7e12, seconds ~ 1.7e9
        if [ "${MAXLU%%.*}" -gt 1000000000000 ] 2>/dev/null; then
            LU_KIND=epoch_millis
        elif [ "${MAXLU%%.*}" -gt 1000000000 ] 2>/dev/null; then
            LU_KIND=epoch_seconds
        else
            warn "max($LU_COL)=$MAXLU too small to classify (no device ever synced?); assuming epoch_millis (Headwind default)"
            LU_KIND=epoch_millis
        fi
        ;;
    "timestamp with time zone"|"timestamp without time zone") LU_KIND=timestamptz ;;
    *) fail "unexpected type for $LU_COL: $LU_TYPE"; exit 1 ;;
esac
ok "lastupdate kind: $LU_KIND"

# --- 3. sample info payloads and probe JSON keys --------------------------------
SAMPLES=$(psq "SELECT $INFO_COL::text FROM $DEVICES_TABLE WHERE $INFO_COL IS NOT NULL LIMIT 5" || true)
if [ -z "$SAMPLES" ]; then
    warn "no non-NULL $INFO_COL rows yet (no device has synced?) — battery will be NULL until devices report"
else
    PROBE=$(printf '%s\n' "$SAMPLES" | python3 -c "
import json, sys
battery_key, charging_key = sys.argv[1], sys.argv[2]
good = bad = missing = 0
sample_keys = None
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        payload = json.loads(line)
    except ValueError:
        bad += 1
        continue
    if not isinstance(payload, dict):
        bad += 1
        continue
    if sample_keys is None:
        sample_keys = sorted(payload.keys())
    if battery_key in payload:
        good += 1
    else:
        missing += 1
print(f'good={good} bad={bad} missing={missing}')
if sample_keys is not None:
    print('keys=' + ','.join(sample_keys))
" "$BATTERY_KEY" "$CHARGING_KEY")
    say "  info probe: $(printf '%s' "$PROBE" | head -1)"
    GOOD=$(printf '%s\n' "$PROBE" | head -1 | sed 's/.*good=\([0-9]*\).*/\1/')
    if [ "${GOOD:-0}" -eq 0 ]; then
        say "  top-level keys seen: $(printf '%s\n' "$PROBE" | sed -n 's/^keys=//p')"
        fail "no sampled info payload contains key '$BATTERY_KEY' — set BATTERY_JSON_KEY in .env to the correct key (see keys above)"
    else
        ok "info payloads parse; '$BATTERY_KEY' present"
    fi
fi

# --- 4. pg_hba auth mode for the snapshot role -----------------------------------
HBA=$(sudo -u postgres psql -AtX -c "SHOW hba_file")
AUTH_MODE=peer
if [ -r "$HBA" ] && grep -E '^\s*host\s+(all|'"$DB"')\s+(all|\S+)\s+(127\.0\.0\.1/32|::1/128|localhost)\s+(md5|scram-sha-256|trust)' "$HBA" >/dev/null; then
    AUTH_MODE=password
    ok "pg_hba permits localhost password auth — snapshot will use a dedicated hmdm_stats role"
else
    warn "pg_hba has no localhost md5/scram line — snapshot cron will run as the postgres user (peer auth)"
fi

# --- 5. environment checks --------------------------------------------------------
python3 -c 'import psycopg2' 2>/dev/null && ok "python3-psycopg2 present" || warn "python3-psycopg2 missing (install.sh will apt-get it)"
if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE '(^|:)3000$'; then
    warn "port 3000 already in use — is Grafana (or something else) already installed?"
else
    ok "port 3000 free"
fi
command -v crontab >/dev/null && [ -d /etc/cron.d ] && ok "cron present" || fail "cron not available"
DF=$(df -Pm /var/lib 2>/dev/null | awk 'NR==2{print $4}')
[ "${DF:-0}" -gt 2048 ] && ok "disk space ok (${DF} MB free on /var/lib)" || warn "low disk space: ${DF:-?} MB free on /var/lib"

# --- 6. write mapping fragment ------------------------------------------------------
cat >"$OUT" <<EOF
# written by preflight.sh $(date -Is)
[mapping]
devices_table = $DEVICES_TABLE
id_col = id
number_col = $NUMBER_COL
info_col = $INFO_COL
info_kind = $INFO_KIND
lastupdate_col = $LU_COL
lastupdate_kind = $LU_KIND
battery_json_key = $BATTERY_KEY
charging_json_key = $CHARGING_KEY

[detected]
auth_mode = $AUTH_MODE
db_name = $DB
EOF
say "== mapping written to $OUT =="

if [ "$FAIL" -ne 0 ]; then
    say "== PREFLIGHT FAILED — fix the [FAIL] items above before installing =="
    exit 1
fi
say "== preflight passed =="
