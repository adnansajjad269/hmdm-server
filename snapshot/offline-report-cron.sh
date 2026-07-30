#!/usr/bin/env bash
# hmdm-stats offline-devices report cron wrapper: overlap guard + logging.
# Invoked from /etc/cron.d/hmdm-stats-offline-report.
set -u

LOG_DIR=/var/log/hmdm-stats
LOG="$LOG_DIR/offline-report.log"
LOCK=/run/lock/hmdm-stats-offline-report.lock
SCRIPT=/opt/hmdm-stats/snapshot/offline_report.py
CONF=/etc/hmdm-stats/hmdm-stats.conf

mkdir -p "$LOG_DIR" 2>/dev/null || true

exec 9>"$LOCK" || { logger -t hmdm-stats -p user.err "cannot open lock $LOCK"; exit 1; }
if ! flock -n 9; then
    echo "$(date -Is) SKIP: previous offline-report still running" >>"$LOG"
    exit 0
fi

python3 "$SCRIPT" "$CONF" >>"$LOG" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
    echo "$(date -Is) FAILED rc=$rc (see lines above)" >>"$LOG"
    logger -t hmdm-stats -p user.err "offline-report failed rc=$rc, see $LOG"
fi
exit "$rc"
