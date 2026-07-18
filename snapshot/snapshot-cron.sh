#!/usr/bin/env bash
# hmdm-stats cron wrapper: overlap guard + logging. Invoked from /etc/cron.d/hmdm-stats.
set -u

LOG_DIR=/var/log/hmdm-stats
LOG="$LOG_DIR/snapshot.log"
LOCK=/run/lock/hmdm-stats-snapshot.lock
SNAPSHOT=/opt/hmdm-stats/snapshot/snapshot.py
CONF=/etc/hmdm-stats/hmdm-stats.conf

mkdir -p "$LOG_DIR" 2>/dev/null || true

exec 9>"$LOCK" || { logger -t hmdm-stats -p user.err "cannot open lock $LOCK"; exit 1; }
if ! flock -n 9; then
    echo "$(date -Is) SKIP: previous snapshot still running" >>"$LOG"
    exit 0
fi

python3 "$SNAPSHOT" "$CONF" >>"$LOG" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
    echo "$(date -Is) FAILED rc=$rc (see lines above)" >>"$LOG"
    logger -t hmdm-stats -p user.err "snapshot failed rc=$rc, see $LOG"
fi
exit "$rc"
