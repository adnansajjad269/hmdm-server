# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Headwind MDM: a Mobile Device Management (MDM) platform for Android devices —
a Java/Tomcat web panel (`server/`) plus a corporate Android launcher (not in
this repo). This repo also carries `hmdm-stats`, an independent battery/
online-status history + Grafana dashboard bundle layered on top without
modifying Headwind's own schema (see `HMDM-STATS-README.md`).

## Build & run

Multi-module Maven reactor (root `pom.xml`), Java 8, packaged as a WAR
deployed to Tomcat with a PostgreSQL backend.

```bash
mvn clean install              # builds all modules, including the Angular
                                # frontend via frontend-maven-plugin (npm/bower
                                # run inside server/webtarget, NOT server/target
                                # -- survives `mvn clean`, makes repeat builds fast)
mvn clean install -DskipTests  # same, skip the (few) unit tests
```

Module order matters (root `pom.xml`): `common` → `jwt` → `notification` →
`plugins/*` → `swagger/ui` → `server`. `server` produces
`server/target/launcher.war` (see `server/pom.xml`'s `<finalName>`).

Before building for local/IDE use, create `server/build.properties` from
`server/build.properties.example` (DB connection, `base.directory`,
`base.url`, secrets) — this file is not tracked in git.

Full install path for a fresh Ubuntu box: `BUILD.txt` / `INSTALL.txt`
(installs via `hmdm_install.sh`, which templates
`install/context_template.xml` into Tomcat's `Catalina/localhost/ROOT.xml`
and prompts before touching an existing database).

Tests: only two exist today
(`common/src/test/java/com/hmdm/util/{IPFilterTests,ApplicationUtilTests}.java`).
Run with `mvn test` from `common/`, or `mvn test -pl common` from the root.

## Architecture

**Modules**: `common` (shared domain/utils) → `jwt` (auth) → `notification`
(MQTT/ActiveMQ push) → `plugins/*` (see below) → `server` (the actual webapp:
REST API + Angular 1.x frontend in one WAR).

**Backend wiring**: Guice, not Spring. Look in
`server/src/main/java/com/hmdm/guice/module/` — `PersistenceModule`,
`MainRestModule`/`PublicRestModule`/`PrivateRestModule` (JAX-RS resources
split by auth requirement), `LiquibaseModule` (runs migrations on startup),
`StartupTaskModule`, `EventListenerModule`. REST endpoints live under
`server/src/main/java/com/hmdm/rest/resource/`.

**Database migrations**: Liquibase, single changelog file
`server/src/main/resources/liquibase/db.changelog.xml` — one long,
chronologically-ordered list of `<changeSet>` entries (do not renumber or
edit already-applied ones; append a new changeset instead, even for a
follow-up fix to a recent change — editing an applied changeset breaks
Liquibase's checksum validation on existing installs).

**Permissions**: DB-driven, not hardcoded in Java or Angular.
`permissions` + `userRolePermissions` tables (seeded/extended via Liquibase
changesets); `UserRoleResource.getPermissions()` returns the full list
generically. Frontend gates UI elements with `ng-if="hasPermission('name')"`
(see `content.html`) and the Roles admin screen
(`roles.controller.js`/`roles.html`) renders one checkbox per DB row,
labelled via the `permission.<name>` localization key — so adding a new
permission is: one Liquibase changeset (insert into `permissions`, grant to
relevant `userRoles` — prefer a dynamic `SELECT id FROM userRoles` grant
over hardcoding role IDs, since role IDs differ across installs) + a
`hasPermission('name')` guard where needed + a `permission.<name>` label in
`server/src/main/webapp/localization/en_US.js` (and ideally other locales).

**Frontend**: AngularJS 1.x (ui-router, Bootstrap `<tabset>`), no separate
frontend build step to invoke manually — `frontend-maven-plugin` handles
npm/bower/grunt as part of `mvn install`. Tabs are registered in three
places that must stay in sync: `app/app.js` (ui-router `.state(...)`),
`app/components/main/controller/tabs.controller.js` (tab name → state map),
and `app/components/main/view/content.html` (the `<tab>` markup, optionally
`ng-if="hasPermission(...)"`-gated).

**Plugins** (`plugins/*`): self-contained modules (`audit`, `deviceinfo`,
`devicelog`, `messaging`, `platform`, `push`, `xtra`), each typically with
its own Liquibase changelog under `plugins/<name>/.../resources/liquibase/`
and Guice bindings, loaded into the same reactor build.

**`hmdm-stats` bundle** (repo root: `install.sh`, `preflight.sh`,
`migrations/`, `snapshot/`, `grafana/`, `sql/`, `config/`): an operationally
separate system, not a Maven module. Installed independently via
`sudo ./install.sh` (idempotent, flags: `--dry-run`,
`--skip-grafana-install`, `--skip-webpanel`). Reads Headwind's `devices`
table read-only, writes its own `device_status_history` table via a
restricted `hmdm_stats` Postgres role, and provisions a Grafana instance
(`grafana_ro` role, LAN-only binding — **never** port-forward Grafana's
port publicly; see the Security notes in `HMDM-STATS-README.md`) whose
fleet dashboard is defined in
`grafana/provisioning/dashboards/json/fleet-dashboard.json` and must be
hand-edited (then re-provisioned via `install.sh`) rather than changed
through Grafana's UI, which doesn't persist edits here. The Analytics tab
in the web panel (`analytics.controller.js`/`analytics.html`) iframes this
dashboard; per `HMDM-STATS-README.md`, this native tab supersedes an older
runtime-patching approach kept only under `webpanel/` as a fallback for
deployments that don't rebuild from source.
