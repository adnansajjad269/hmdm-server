#!/usr/bin/env bash
# hmdm-stats: inject an "Analytics" nav tab (iframe to Grafana) into the
# DEPLOYED Headwind MDM web panel. No source rebuild — this patches the static
# files Tomcat serves. Idempotent (marker comments), takes one-time backups,
# and NEVER hard-fails: if the markup can't be found it prints the manual steps
# and exits 0 so the rest of the install continues.
#
# Re-run after every Headwind upgrade (upgrades replace the webapp files).
#
# Env/args:
#   WEBAPP_DIR   explicit path to the deployed webapp (dir with index.html)
#   GRAFANA_URL  Grafana base URL for the iframe (default http://10.10.10.12:3000)
#   $1           optional WEBAPP_DIR override
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
GRAFANA_URL="${GRAFANA_URL:-http://10.10.10.12:3000}"
WEBAPP_DIR="${1:-${WEBAPP_DIR:-}}"
MANUAL="$HERE/analytics-manual-steps.md"

giveup() {
    echo "WARN: $*" >&2
    echo "WARN: Analytics tab NOT injected. Manual instructions: $MANUAL" >&2
    exit 0
}

# --- 1. locate the deployed webapp -------------------------------------------
if [ -z "$WEBAPP_DIR" ]; then
    for d in /var/lib/tomcat*/webapps/* /opt/tomcat*/webapps/* /usr/share/tomcat*/webapps/*; do
        [ -d "$d" ] || continue
        [ -f "$d/index.html" ] || continue
        if grep -qiE 'headwind|hmdm|ng-app' "$d/index.html" 2>/dev/null; then
            WEBAPP_DIR="$d"
            break
        fi
    done
fi
[ -n "$WEBAPP_DIR" ] && [ -d "$WEBAPP_DIR" ] || giveup "could not locate the deployed Headwind webapp (set WEBAPP_DIR in .env)"
INDEX="$WEBAPP_DIR/index.html"
[ -f "$INDEX" ] || giveup "no index.html in $WEBAPP_DIR"
echo "webapp: $WEBAPP_DIR"

# --- 2. detect the AngularJS module name --------------------------------------
NG_APP=$(grep -ohE 'ng-app="[^"]+"' "$INDEX" | head -1 | sed 's/ng-app="//; s/"//')
[ -n "$NG_APP" ] || giveup "no ng-app attribute found in $INDEX — not an AngularJS panel?"
echo "angular module: $NG_APP"

backup_once() { # one-time pristine backup per file
    [ -f "$1.hmdm-stats.orig" ] || cp -p "$1" "$1.hmdm-stats.orig"
}

# --- 3. install/refresh analytics-tab.js ---------------------------------------
sed -e "s|__NG_APP__|$NG_APP|g" -e "s|__GRAFANA_URL__|${GRAFANA_URL%/}|g" \
    "$HERE/analytics-tab.js" > "$WEBAPP_DIR/hmdm-stats-analytics-tab.js"
echo "installed: $WEBAPP_DIR/hmdm-stats-analytics-tab.js"

if ! grep -q 'hmdm-stats-analytics-tab.js' "$INDEX"; then
    backup_once "$INDEX"
    python3 - "$INDEX" <<'PYEOF'
import re, sys
path = sys.argv[1]
html = open(path, encoding="utf-8", errors="surrogateescape").read()
tag = '<script src="hmdm-stats-analytics-tab.js"></script>'
m = re.search(r'</body>', html, re.IGNORECASE)
if not m:
    sys.exit("no </body> tag found")
html = html[:m.start()] + tag + "\n" + html[m.start():]
open(path, "w", encoding="utf-8", errors="surrogateescape").write(html)
PYEOF
    [ $? -eq 0 ] || giveup "could not insert script tag into $INDEX"
    echo "patched: $INDEX (script tag added)"
else
    echo "script tag already present in index.html"
fi

# --- 4. insert the nav item before the Applications link ------------------------
NAV_ITEM='<!-- hmdm-stats:analytics:begin --><li><a href="#/analytics">Analytics</a></li><!-- hmdm-stats:analytics:end -->'

# find candidate files containing the Applications nav link
mapfile -t NAV_FILES < <(grep -rlE 'href="#(!)?/applications"' "$WEBAPP_DIR" \
    --include='*.html' 2>/dev/null | grep -v '\.orig$' | head -5)
[ "${#NAV_FILES[@]}" -gt 0 ] || giveup "no file with an Applications nav link (href=\"#/applications\") found under $WEBAPP_DIR"

PATCHED=0
for f in "${NAV_FILES[@]}"; do
    if grep -q 'hmdm-stats:analytics:begin' "$f"; then
        # refresh the marked block in place (idempotent re-run)
        backup_once "$f"
        NAV_ITEM="$NAV_ITEM" python3 - "$f" <<'PYEOF'
import os, re, sys
path = sys.argv[1]
html = open(path, encoding="utf-8", errors="surrogateescape").read()
new = os.environ["NAV_ITEM"]
html2 = re.sub(
    r'<!-- hmdm-stats:analytics:begin -->.*?<!-- hmdm-stats:analytics:end -->',
    new, html, flags=re.DOTALL)
if html2 != html:
    open(path, "w", encoding="utf-8", errors="surrogateescape").write(html2)
PYEOF
        echo "refreshed existing Analytics nav item in $f"
        PATCHED=1
        continue
    fi
    backup_once "$f"
    if NAV_ITEM="$NAV_ITEM" python3 - "$f" <<'PYEOF'
import os, re, sys
path = sys.argv[1]
html = open(path, encoding="utf-8", errors="surrogateescape").read()
# anchor: the Applications link; insert our <li> before the <li> that contains it
m = re.search(r'href="#!?/applications"', html)
if not m:
    sys.exit(1)
li_open = html.rfind("<li", 0, m.start())
if li_open < 0:
    sys.exit(1)
html = html[:li_open] + os.environ["NAV_ITEM"] + "\n" + html[li_open:]
open(path, "w", encoding="utf-8", errors="surrogateescape").write(html)
PYEOF
    then
        echo "patched: $f (Analytics nav item inserted before Applications)"
        PATCHED=1
    fi
done

[ "$PATCHED" -eq 1 ] || giveup "found Applications link but no surrounding <li> to anchor on"
echo "OK: Analytics tab injected. Hard-refresh the browser (Ctrl+Shift+R) to see it."
echo "NOTE: re-run this script after any Headwind MDM upgrade."
