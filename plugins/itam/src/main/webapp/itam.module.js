// Localization completed
angular.module('plugin-itam', ['ngResource', 'ui.bootstrap', 'ui.router', 'ncy-angular-breadcrumb'])
    .config(function ($stateProvider) {
        try {
            $stateProvider.state('plugin-itam', {
                url: "/" + 'plugin-itam',
                templateUrl: 'app/components/main/view/content.html',
                controller: 'TabController',
                ncyBreadcrumb: {
                    label: '{{"breadcrumb.plugin.itam.main" | localize}}'
                },
                resolve: {
                    openTab: function () {
                        return 'plugin-itam';
                    }
                }
            });
        } catch (e) {
            console.log('An error when adding state plugin-itam', e);
        }
    })
    .factory('pluginItamService', function ($resource) {
        return $resource('', {}, {
            search: {url: 'rest/plugins/itam/private/logs/search', method: 'POST'},
            latest: {url: 'rest/plugins/itam/private/logs/latest/:deviceId', method: 'GET'},
            searchDevices: {url: 'rest/plugins/itam/private/devices/search', method: 'GET', isArray: false},
            telemetry: {url: 'rest/plugins/itam/private/devices/:deviceId/telemetry', method: 'GET'},
            remove: {url: 'rest/plugins/itam/private/logs/:id', method: 'DELETE'}
        });
    })
    .controller('PluginItamTabController', function ($scope, $rootScope, $modal, $window,
                                                      pluginItamService, confirmModal, authService, localization) {
        $scope.hasPermission = authService.hasPermission;

        $rootScope.settingsTabActive = false;
        $rootScope.pluginsTabActive = true;

        $scope.paging = {
            pageNum: 1,
            pageSize: 20,
            totalItems: 0,
            deviceId: null,
            deviceFilter: '',
            ownerName: '',
            assetStatus: '',
            deviceCondition: '',
            batteryCondition: '',
            dateFrom: null,
            dateTo: null
        };

        $scope.errorMessage = undefined;

        $scope.assetStatuses = ['IN_USE', 'IN_STOCK', 'UNDER_REPAIR', 'RETIRED'];
        $scope.conditions = ['GOOD', 'BAD'];

        $scope.$watch('paging.pageNum', loadData);

        $scope.search = function () {
            $scope.paging.pageNum = 1;
            loadData();
        };

        function buildFilter() {
            return {
                pageNum: $scope.paging.pageNum,
                pageSize: $scope.paging.pageSize,
                deviceNumber: $scope.paging.deviceFilter || null,
                ownerName: $scope.paging.ownerName || null,
                assetStatus: $scope.paging.assetStatus || null,
                deviceCondition: $scope.paging.deviceCondition || null,
                batteryCondition: $scope.paging.batteryCondition || null,
                dateFrom: $scope.paging.dateFrom ? $scope.paging.dateFrom.getTime() : null,
                dateTo: $scope.paging.dateTo ? $scope.paging.dateTo.getTime() : null
            };
        }

        function loadData() {
            $scope.errorMessage = undefined;
            pluginItamService.search(buildFilter(), function (response) {
                if (response.status === 'OK') {
                    $scope.logs = response.data.items;
                    $scope.paging.totalItems = response.data.totalItemsCount;
                } else {
                    $scope.errorMessage = localization.localizeServerResponse(response);
                }
            }, function () {
                $scope.errorMessage = localization.localize('error.request.failure');
            });
        }

        loadData();

        $scope.exportCsv = function () {
            var filter = buildFilter();
            var params = Object.keys(filter)
                .filter(function (k) { return filter[k] !== null && filter[k] !== undefined && k !== 'pageNum' && k !== 'pageSize'; })
                .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(filter[k]); })
                .join('&');
            $window.open('rest/plugins/itam/private/logs/export' + (params ? '?' + params : ''), '_blank');
        };

        $scope.addEntry = function () {
            var modalInstance = $modal.open({
                templateUrl: 'app/components/plugins/itam/views/addEntry.modal.html',
                controller: 'PluginItamAddEntryController',
                size: 'lg'
            });
            modalInstance.result.then(function (saved) {
                if (saved) {
                    loadData();
                }
            });
        };

        $scope.removeEntry = function (log) {
            var text = localization.localize('plugin.itam.confirm.delete');
            confirmModal.getUserConfirmation(text, function () {
                pluginItamService.remove({id: log.id}, function (response) {
                    if (response.status === 'OK') {
                        loadData();
                    } else {
                        $scope.errorMessage = localization.localizeServerResponse(response);
                    }
                });
            });
        };
    })
    .controller('PluginItamAddEntryController', function ($scope, $modalInstance, $http, $q,
                                                            pluginItamService, localization) {
        $scope.saving = false;
        $scope.errorMessage = undefined;

        $scope.entry = {
            deviceId: null,
            ownerName: '',
            ownershipDate: new Date(),
            assetStatus: 'IN_USE',
            deviceCondition: 'GOOD',
            batteryCondition: 'GOOD',
            comments: ''
        };

        $scope.assetStatuses = ['IN_USE', 'IN_STOCK', 'UNDER_REPAIR', 'RETIRED'];
        $scope.conditions = ['GOOD', 'BAD'];

        $scope.dateLocked = false;
        $scope.telemetry = null;
        $scope.selectedDeviceLabel = '';
        $scope.pictures = [];

        var telemetryCanceller = null;
        var latestOwnerAtSelection = null;

        $scope.getDevices = function (val) {
            return pluginItamService.searchDevices({query: val}).$promise.then(function (response) {
                if (response.status === 'OK') {
                    $scope.deviceCandidates = response.data;
                    return response.data.map(function (d) {
                        return d.number;
                    });
                }
                return [];
            });
        };

        $scope.onDeviceSelected = function () {
            var device = ($scope.deviceCandidates || []).filter(function (d) {
                return d.number === $scope.selectedDeviceLabel;
            })[0];
            if (!device) {
                return;
            }
            $scope.entry.deviceId = device.id;

            // Cancel any in-flight telemetry/latest-log requests for a previously selected device
            // so a fast switch can't let a stale response land after a newer one.
            if (telemetryCanceller) {
                telemetryCanceller.resolve();
            }
            telemetryCanceller = $q.defer();

            $scope.telemetry = null;
            $http.get('rest/plugins/itam/private/devices/' + device.id + '/telemetry', {timeout: telemetryCanceller.promise})
                .then(function (resp) {
                    if (resp.data.status === 'OK') {
                        $scope.telemetry = resp.data.data;
                    }
                });

            $http.get('rest/plugins/itam/private/logs/latest/' + device.id, {timeout: telemetryCanceller.promise})
                .then(function (resp) {
                    if (resp.data.status === 'OK' && resp.data.data) {
                        var latest = resp.data.data;
                        latestOwnerAtSelection = latest.ownerName || null;
                        $scope.entry.ownerName = latest.ownerName || '';
                        $scope.entry.ownershipDate = latest.ownershipDate ? new Date(latest.ownershipDate) : new Date();
                        $scope.dateLocked = !!latestOwnerAtSelection;
                    } else {
                        // State 3: no history for this device
                        latestOwnerAtSelection = null;
                        $scope.entry.ownerName = '';
                        $scope.entry.ownershipDate = new Date();
                        $scope.dateLocked = false;
                    }
                });
        };

        // State 2: owner edited away from the pre-populated value -> unlock + default to today
        $scope.$watch('entry.ownerName', function (newVal, oldVal) {
            if (newVal === oldVal) {
                return;
            }
            var normalized = (newVal || '').trim().toUpperCase();
            var isUnassigned = normalized === '' || normalized === 'N/A';
            if (isUnassigned && !latestOwnerAtSelection) {
                return;
            }
            if (newVal !== latestOwnerAtSelection) {
                $scope.dateLocked = false;
                $scope.entry.ownershipDate = new Date();
            }
        });

        $scope.onFilesSelected = function (files) {
            $scope.errorMessage = undefined;
            var maxSize = 5 * 1024 * 1024;
            var allowedTypes = ['image/jpeg', 'image/png', 'image/webp'];

            if ($scope.pictures.length + files.length > 5) {
                $scope.errorMessage = localization.localize('plugin.itam.error.too.many.pictures');
                return;
            }
            for (var i = 0; i < files.length; i++) {
                var f = files[i];
                if (allowedTypes.indexOf(f.type) === -1) {
                    $scope.errorMessage = localization.localize('plugin.itam.error.picture.type');
                    continue;
                }
                if (f.size > maxSize) {
                    $scope.errorMessage = localization.localize('plugin.itam.error.picture.size');
                    continue;
                }
                $scope.pictures.push(f);
            }
        };

        $scope.removePicture = function (index) {
            $scope.pictures.splice(index, 1);
        };

        $scope.closeModal = function () {
            $modalInstance.dismiss();
        };

        $scope.save = function () {
            $scope.errorMessage = undefined;
            if (!$scope.entry.deviceId) {
                $scope.errorMessage = localization.localize('plugin.itam.error.no.device');
                return;
            }

            var formData = new FormData();
            formData.append('data', JSON.stringify({
                deviceId: $scope.entry.deviceId,
                ownerName: $scope.entry.ownerName || null,
                ownershipDate: $scope.entry.ownershipDate ? $scope.entry.ownershipDate.getTime() : null,
                assetStatus: $scope.entry.assetStatus,
                deviceCondition: $scope.entry.deviceCondition,
                batteryCondition: $scope.entry.batteryCondition,
                comments: $scope.entry.comments || null
            }));
            $scope.pictures.forEach(function (file) {
                formData.append('pictures', file);
            });

            $scope.saving = true;
            $http.post('rest/plugins/itam/private/logs', formData, {
                transformRequest: angular.identity,
                headers: {'Content-Type': undefined}
            }).then(function (resp) {
                $scope.saving = false;
                if (resp.data.status === 'OK') {
                    $modalInstance.close(true);
                } else {
                    $scope.errorMessage = localization.localizeServerResponse(resp.data);
                }
            }, function () {
                $scope.saving = false;
                $scope.errorMessage = localization.localize('error.request.failure');
            });
        };
    })
    .run(function (localization) {
        localization.loadPluginResourceBundles('itam');
    });
