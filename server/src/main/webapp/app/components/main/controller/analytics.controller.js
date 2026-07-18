// Localization completed
angular.module('headwind-kiosk')
    .controller('AnalyticsTabController', function ($scope, $window, $sce) {
        // Grafana (hmdm-stats) is deployed on the same host, LAN-only, port 3000.
        // Computed from the current page location rather than a build-time
        // constant so the panel keeps working regardless of hostname/IP.
        var grafanaBaseUrl = $window.location.protocol + '//' + $window.location.hostname + ':3000';

        $scope.grafanaAvailable = $window.location.protocol !== 'https:';
        $scope.grafanaUrl = $sce.trustAsResourceUrl(
            grafanaBaseUrl + '/d/hmdm-fleet/hmdm-fleet-status?kiosk&orgId=1'
        );
    });
