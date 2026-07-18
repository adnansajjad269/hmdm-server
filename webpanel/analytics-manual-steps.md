# Adding the Analytics tab

## Read this first: you probably don't need this file

The Analytics tab is now a **native part of the panel source** in this repo:

- `server/src/main/webapp/app/app.js` (ui-router state `analytics`)
- `server/src/main/webapp/app/components/main/controller/tabs.controller.js`
  (`ANALYTICS` route entry)
- `server/src/main/webapp/app/components/main/view/content.html` (the `<tab>`)
- `server/src/main/webapp/app/components/main/controller/analytics.controller.js`
  and `.../view/analytics.html` (the iframe + HTTPS fallback notice)
- `server/src/main/webapp/localization/en_US.js` (tab/breadcrumb labels)

**If you build the WAR from this repo** (`mvn package`, or your normal
Headwind build), the tab is already there. Nothing to inject, nothing to run
from this directory. `install.sh --skip-webpanel` is the right flag.

The rest of this file is only for the case where you're deploying a
**pre-built WAR you didn't compile from this repo** — e.g. an existing
Headwind install where you only want the Postgres + Grafana pieces of
hmdm-stats, not a rebuild. In that case `inject-analytics-tab.sh` tries to
patch the already-deployed static files, but its detection logic was written
*before* this repo's real panel source was available, targeting a generic
guess (an AngularJS `ngRoute`-style `<li><a href="#/applications">` link).
The actual CE panel uses **ui-router** with a Bootstrap `<tabset>`
(`content.html`) — there is no such link to find. Against a stock,
unmodified CE WAR the injector will not find a match and will cleanly no-op
with a warning; it will not corrupt anything, it also won't add a tab.

So: on a pre-built WAR, there is currently no reliable automatic way to add
the tab without rebuilding. Your options, in order of preference:

1. **Rebuild from this repo.** Even for an otherwise-unmodified stock
   deployment, building the WAR from `server/` here gives you the native
   tab with zero manual steps.
2. **Patch the deployed WAR by hand**, following the pattern above: add
   `analytics.controller.js` + `analytics.html` from
   `server/src/main/webapp/app/components/main/...` into the deployed
   webapp directory, register the script tag in `index.html`, add the
   `analytics` state to the deployed `app.js`, the `ANALYTICS` entry to
   `tabs.controller.js`, and the `<tab>` block to `content.html` — i.e. the
   same five edits this repo makes, applied to the deployed copies instead
   of the source. Diff this repo against your deployed webapp to find the
   exact insertion points if the deployed version differs.
3. Skip the panel tab; use Grafana directly at `http://10.10.10.12:3000`.

## Verify

Hard-refresh the panel (Ctrl+Shift+R). An **Analytics** tab should appear
between Devices and Applications and render the Grafana dashboard. If the
tab shows a warning instead of the dashboard, the panel is being served over
HTTPS and browsers block the plain-HTTP Grafana iframe (mixed content) — open
`http://<server-host>:3000` directly instead, or put Grafana behind TLS too.
