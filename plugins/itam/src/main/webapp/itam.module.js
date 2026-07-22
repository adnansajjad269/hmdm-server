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
    .controller('PluginItamTabController', function ($scope, $rootScope, $uibModal, $window, $stateParams,
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

        // Pre-search when opened via a device-number link from the Devices grid
        if ($stateParams && $stateParams.deviceNumber) {
            $scope.paging.deviceFilter = $stateParams.deviceNumber;
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
            var modalInstance = $uibModal.open({
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
    .controller('PluginItamAddEntryController', function ($scope, $uibModalInstance, $http, $q, $timeout,
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
        // The device.number of the currently selected valid device; kept in sync so we can reject
        // free-typed values that don't correspond to a real device from the list.
        var selectedDeviceNumber = null;

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

        function focusDeviceInput() {
            var el = document.getElementById('itamDeviceInput');
            if (el) {
                el.focus();
            }
        }

        // Applies a device chosen from the list (or resolved from a scanned barcode): marks it as the
        // valid selection and loads its live telemetry + latest-entry pre-population.
        function applySelectedDevice(device) {
            $scope.entry.deviceId = device.id;
            selectedDeviceNumber = device.number;
            $scope.selectedDeviceLabel = device.number;

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
                        // Pre-populate condition fields from the latest entry; they stay editable.
                        $scope.entry.deviceCondition = latest.deviceCondition || 'GOOD';
                        $scope.entry.batteryCondition = latest.batteryCondition || 'GOOD';
                    } else {
                        // State 3: no history for this device
                        latestOwnerAtSelection = null;
                        $scope.entry.ownerName = '';
                        $scope.entry.ownershipDate = new Date();
                        $scope.dateLocked = false;
                        $scope.entry.deviceCondition = 'GOOD';
                        $scope.entry.batteryCondition = 'GOOD';
                    }
                });
        }

        $scope.onDeviceSelected = function () {
            var device = ($scope.deviceCandidates || []).filter(function (d) {
                return d.number === $scope.selectedDeviceLabel;
            })[0];
            if (!device) {
                return;
            }
            applySelectedDevice(device);
        };

        // Resolves a raw device number (typically from a scanned barcode) to a real device.
        function resolveDeviceByNumber(num) {
            pluginItamService.searchDevices({query: num}).$promise.then(function (response) {
                var device = null;
                if (response.status === 'OK') {
                    device = (response.data || []).filter(function (d) {
                        return d.number === num;
                    })[0];
                }
                if (device) {
                    applySelectedDevice(device);
                } else {
                    $scope.entry.deviceId = null;
                    $scope.errorMessage = localization.localize('plugin.itam.error.device.not.found');
                    $timeout(focusDeviceInput);
                }
            });
        }

        // ------------------------------------------------------------------ barcode scanning
        // Live camera scan using the browser-native BarcodeDetector (Chrome/Edge/Android). Requires an
        // HTTPS origin for camera access; on plain HTTP getUserMedia is rejected and we surface an error.
        var scanStream = null;
        var scanDetector = null;
        var scanRAF = null;
        var scanActive = false;

        $scope.scanning = false;

        $scope.startScan = function () {
            $scope.errorMessage = undefined;
            if (typeof window.BarcodeDetector === 'undefined') {
                $scope.errorMessage = localization.localize('plugin.itam.error.scan.unsupported');
                return;
            }
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                $scope.errorMessage = localization.localize('plugin.itam.error.scan.camera');
                return;
            }
            $scope.scanning = true;
            // Let the <video> element render before wiring the stream to it.
            $timeout(function () {
                navigator.mediaDevices.getUserMedia({video: {facingMode: 'environment'}})
                    .then(function (stream) {
                        scanStream = stream;
                        var video = document.getElementById('itamScanVideo');
                        video.srcObject = stream;
                        video.setAttribute('playsinline', 'true');
                        video.play();
                        try {
                            scanDetector = new window.BarcodeDetector({
                                formats: ['code_128', 'code_39', 'ean_13', 'ean_8', 'upc_a', 'upc_e',
                                    'itf', 'codabar', 'qr_code', 'data_matrix']
                            });
                        } catch (e) {
                            scanDetector = new window.BarcodeDetector();
                        }
                        scanActive = true;
                        detectLoop(video);
                    })
                    .catch(function () {
                        $scope.$applyAsync(function () {
                            $scope.scanning = false;
                            $scope.errorMessage = localization.localize('plugin.itam.error.scan.camera');
                        });
                    });
            });
        };

        function detectLoop(video) {
            if (!scanActive) {
                return;
            }
            scanDetector.detect(video).then(function (codes) {
                if (!scanActive) {
                    return;
                }
                if (codes && codes.length > 0 && codes[0].rawValue) {
                    var value = ('' + codes[0].rawValue).trim();
                    $scope.$applyAsync(function () {
                        $scope.stopScan();
                        $scope.selectedDeviceLabel = value;
                        resolveDeviceByNumber(value);
                    });
                } else {
                    scanRAF = requestAnimationFrame(function () { detectLoop(video); });
                }
            }).catch(function () {
                // Ignore transient decode errors and keep scanning.
                if (scanActive) {
                    scanRAF = requestAnimationFrame(function () { detectLoop(video); });
                }
            });
        }

        $scope.stopScan = function () {
            scanActive = false;
            if (scanRAF) {
                cancelAnimationFrame(scanRAF);
                scanRAF = null;
            }
            if (scanStream) {
                scanStream.getTracks().forEach(function (t) { t.stop(); });
                scanStream = null;
            }
            $scope.scanning = false;
        };

        // ------------------------------------------------------------------ camera photo capture
        $scope.triggerCapture = function () {
            var el = document.getElementById('itamCaptureInput');
            if (el) {
                el.click();
            }
        };

        $scope.$on('$destroy', function () {
            $scope.stopScan();
        });

        // Invalidate the selected device as soon as the text is edited away from a real device number,
        // so only a value chosen from the list (or typed to exactly match one) counts as valid.
        $scope.$watch('selectedDeviceLabel', function (newVal) {
            if (newVal !== selectedDeviceNumber) {
                $scope.entry.deviceId = null;
                $scope.telemetry = null;
            }
        });

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
            $scope.stopScan();
            $uibModalInstance.dismiss();
        };

        $scope.save = function () {
            $scope.stopScan();
            $scope.errorMessage = undefined;
            // Only a device chosen from the list (deviceId set, label still matching it) is accepted.
            if (!$scope.entry.deviceId || $scope.selectedDeviceLabel !== selectedDeviceNumber) {
                $scope.errorMessage = localization.localize('plugin.itam.error.invalid.device');
                $timeout(function () {
                    var el = document.getElementById('itamDeviceInput');
                    if (el) {
                        el.focus();
                    }
                });
                return;
            }
            if (!$scope.pictures || $scope.pictures.length < 1) {
                $scope.errorMessage = localization.localize('plugin.itam.error.no.pictures');
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
                    $uibModalInstance.close(true);
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
