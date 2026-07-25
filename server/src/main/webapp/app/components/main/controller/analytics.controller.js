// Localization completed
angular.module('headwind-kiosk')
    .controller('AnalyticsTabController', function ($scope, $window, $sce, authService) {
        // Grafana no longer allows anonymous access (see grafana-overrides.ini.tmpl) now that
        // it's reachable through the public reverse proxy rather than being LAN-only, so the
        // iframe below only ever gets a Grafana session if the browser already has one. Gating
        // on authService.isLoggedIn() here is a deliberate extra check requested on top of this
        // tab already sitting behind Headwind's own authenticated routes: an unauthenticated
        // visitor is never even given the iframe src, rather than relying solely on Grafana's
        // own (now-required) login prompt inside the frame.
        //
        // A JWT-based SSO was attempted here (auth_token query param via Grafana's auth.jwt
        // url_login feature) so the iframe could log straight in as the Headwind user without a
        // separate Grafana login. It was reverted: Grafana's own url_login feature has open,
        // unresolved upstream bugs (grafana/grafana#90200, #91464) matching exactly what we hit
        // -- a valid JWT is silently rejected with no session cookie set and no error logged,
        // redirecting back to the login page. Users now just log into Grafana once per browser
        // session inside the iframe, same as visiting Grafana directly.
        if (!authService.isLoggedIn()) {
            $scope.grafanaAvailable = false;
            return;
        }

        // Grafana (hmdm-stats) is deployed on the same host, LAN-only, port 3000 -- it is
        // never reachable directly from outside. On an HTTP panel we can iframe it directly
        // at that LAN port. On an HTTPS panel, browsers block that as mixed content, so we
        // instead go through the same-origin /grafana/ path that the reverse proxy (see
        // HMDM-STATS-README.md) forwards to Grafana over HTTPS -- this only works once the
        // proxy route and Grafana's root_url/serve_from_sub_path (GRAFANA_PUBLIC_URL in
        // install.sh) have both been configured for that path.
        var isHttps = $window.location.protocol === 'https:';
        var grafanaBaseUrl = isHttps
            ? $window.location.origin + '/grafana'
            : $window.location.protocol + '//' + $window.location.hostname + ':3000';

        $scope.grafanaAvailable = true;
        $scope.grafanaUrl = $sce.trustAsResourceUrl(
            grafanaBaseUrl + '/d/hmdm-fleet/hmdm-fleet-status?kiosk&orgId=1'
        );
    });
