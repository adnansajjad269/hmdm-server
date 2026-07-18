// hmdm-stats: registers an "/analytics" route in the Headwind MDM web panel
// that iframes the Grafana fleet dashboard. Installed and placeholder-rendered
// by inject-analytics-tab.sh (__NG_APP__ and __GRAFANA_URL__ are substituted).
// Loaded via a plain <script> tag before AngularJS auto-bootstraps on
// DOMContentLoaded, so the config block still runs.
(function () {
    'use strict';
    try {
        angular.module('__NG_APP__').config(['$routeProvider', function ($routeProvider) {
            $routeProvider.when('/analytics', {
                template:
                    '<iframe src="__GRAFANA_URL__/d/hmdm-fleet/hmdm-fleet-status?kiosk&orgId=1" ' +
                    'style="display:block;width:100%;height:calc(100vh - 130px);border:0" ' +
                    'title="HMDM Analytics"></iframe>'
            });
        }]);
    } catch (e) {
        // Non-fatal: the rest of the panel keeps working without the tab.
        if (window.console && console.error) {
            console.error('hmdm-stats: failed to register /analytics route', e);
        }
    }
})();
