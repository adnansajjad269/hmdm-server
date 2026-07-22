// Localization completed
angular.module('headwind-kiosk')
    .controller('AnalyticsTabController', function ($scope, $window, $sce) {
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
