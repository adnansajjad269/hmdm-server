# Adding the Analytics tab by hand

Use these steps when `inject-analytics-tab.sh` could not auto-detect the
deployed web panel markup (it prints a WARN and points here). Every Headwind
CE build differs slightly, so all paths below are examples — adapt them.

## 1. Find the deployed webapp

```bash
ls /var/lib/tomcat*/webapps/
# the Headwind panel is the webapp whose index.html contains ng-app="..."
grep -l 'ng-app' /var/lib/tomcat*/webapps/*/index.html
```

Call that directory `$WEBAPP` below. If you find it this way, the simplest fix
is to re-run the injector with the explicit path — only do the rest of this
file if that also fails:

```bash
WEBAPP_DIR=$WEBAPP GRAFANA_URL=http://10.10.10.12:3000 \
    /opt/hmdm-stats/webpanel/inject-analytics-tab.sh
```

## 2. Install the route script

```bash
# render the placeholders by hand; NG_APP is the value of ng-app= in index.html
sed -e 's|__NG_APP__|headwind-kiosk|g' \
    -e 's|__GRAFANA_URL__|http://10.10.10.12:3000|g' \
    /opt/hmdm-stats/webpanel/analytics-tab.js > $WEBAPP/hmdm-stats-analytics-tab.js
```

Then edit `$WEBAPP/index.html` and add, just before `</body>`:

```html
<script src="hmdm-stats-analytics-tab.js"></script>
```

## 3. Add the nav item

Find the template containing the main navigation (search for the Applications
link):

```bash
grep -rl 'href="#/applications"' $WEBAPP --include='*.html'
```

In that file, immediately **before** the `<li>` element that contains the
Applications link, add:

```html
<!-- hmdm-stats:analytics:begin --><li><a href="#/analytics">Analytics</a></li><!-- hmdm-stats:analytics:end -->
```

Keep the marker comments — they let the injector recognize and refresh the
block on later runs. If the nav `<li>` elements carry CSS classes, copy the
same classes onto the new `<li>` so it matches visually.

## 4. Verify

Hard-refresh the panel (Ctrl+Shift+R). An **Analytics** tab should appear
between Devices and Applications and render the Grafana dashboard. If the
iframe is blank, confirm Grafana has `allow_embedding = true` and anonymous
Viewer access (both set by install.sh) and that the browser can reach
`http://10.10.10.12:3000` directly.

## Notes

- Headwind upgrades replace the webapp — redo these steps (or re-run the
  injector) after every upgrade.
- Backups of every touched file are kept next to it as `*.hmdm-stats.orig`.
