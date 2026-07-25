// Localization completed
angular.module('headwind-kiosk')
    .controller('AnalyticsTabController', function ($scope, $window, $sce, $http, authService) {
        // Grafana no longer allows anonymous access (see grafana-overrides.ini.tmpl) now that
        // it's reachable through the public reverse proxy rather than being LAN-only, so the
        // iframe below only ever gets a Grafana session if the browser already has one. Gating
        // on authService.isLoggedIn() here is a deliberate extra check requested on top of this
        // tab already sitting behind Headwind's own authenticated routes: an unauthenticated
        // visitor is never even given the iframe src, rather than relying solely on Grafana's
        // own (now-required) login prompt inside the frame.
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
        var dashboardPath = '/d/hmdm-fleet/hmdm-fleet-status?kiosk&orgId=1';

        $scope.grafanaAvailable = true;

        // Try to fetch a short-lived Grafana SSO token (see GrafanaSsoResource /
        // GrafanaJwtService) so the iframe logs straight into Grafana via its auth.jwt
        // url_login feature, without a separate Grafana login prompt. This endpoint is gated
        // by the same "analytics" permission already required to see this tab at all, so it
        // never grants any access beyond what the user could already reach. If SSO isn't set
        // up server-side (no RSA key pair yet) or the request fails for any reason, we fall
        // back to the plain dashboard URL -- Grafana's own login prompt still works fine
        // inside the iframe, this is purely a convenience on top of that.
        $http.get('rest/private/plugins/grafana/token').then(function (response) {
            var token = response.data && response.data.data && response.data.data.token;
            var url = token ? (dashboardPath + '&auth_token=' + encodeURIComponent(token)) : dashboardPath;
            $scope.grafanaUrl = $sce.trustAsResourceUrl(grafanaBaseUrl + url);
        }, function () {
            $scope.grafanaUrl = $sce.trustAsResourceUrl(grafanaBaseUrl + dashboardPath);
        });
    });
