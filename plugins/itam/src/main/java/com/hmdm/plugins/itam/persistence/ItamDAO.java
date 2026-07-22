package com.hmdm.plugins.itam.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.plugins.itam.persistence.domain.DeviceLookupItem;
import com.hmdm.plugins.itam.persistence.domain.ItamLog;
import com.hmdm.plugins.itam.persistence.mapper.ItamMapper;
import com.hmdm.plugins.itam.rest.json.ItamLogFilter;
import com.hmdm.security.SecurityContext;
import org.mybatis.guice.transactional.Transactional;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class ItamDAO {

    private final ItamMapper mapper;

    @Inject
    public ItamDAO(ItamMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public List<ItamLog> findAll(ItamLogFilter filter) {
        return SecurityContext.get().getCurrentUser()
                .map(user -> {
                    filter.setCustomerId(user.getCustomerId());
                    return new ArrayList<>(mapper.findAll(filter));
                })
                .orElseGet(ArrayList::new);
    }

    public long countAll(ItamLogFilter filter) {
        return SecurityContext.get().getCurrentUser()
                .map(user -> {
                    filter.setCustomerId(user.getCustomerId());
                    return mapper.countAll(filter);
                })
                .orElse(0L);
    }

    public ItamLog findLatestByDevice(int deviceId) {
        return SecurityContext.get().getCurrentUser()
                .map(user -> mapper.findLatestByDevice(deviceId, user.getCustomerId()))
                .orElse(null);
    }

    @Transactional
    public void insert(ItamLog log) {
        SecurityContext.get().getCurrentUser().ifPresent(user -> {
            log.setCustomerId(user.getCustomerId());
            log.setLoggedByUserId(user.getId());
            mapper.insert(log);
        });
    }

    /**
     * Permanently deletes a matching row (no retention) and returns its picture paths so the caller can
     * remove the files from disk. Returns {@code null} if no matching row was found for the current
     * customer, or an empty list if the row had no pictures.
     */
    @Transactional
    public List<String> hardDelete(String id) {
        return SecurityContext.get().getCurrentUser()
                .map(user -> {
                    ItamLog log = mapper.findById(id, user.getCustomerId());
                    if (log == null) {
                        return null;
                    }
                    List<String> pictures = log.getPictures() != null
                            ? new ArrayList<>(log.getPictures()) : new ArrayList<String>();
                    mapper.hardDelete(id, user.getCustomerId());
                    return pictures;
                })
                .orElse(null);
    }

    public List<DeviceLookupItem> searchDevices(String query) {
        return SecurityContext.get().getCurrentUser()
                .map(user -> new ArrayList<>(mapper.searchDevices(user.getCustomerId(), query == null ? "" : query)))
                .orElseGet(ArrayList::new);
    }

    public DeviceLookupItem getDeviceTelemetry(int deviceId) {
        return SecurityContext.get().getCurrentUser()
                .map(user -> mapper.getDeviceTelemetry(deviceId, user.getCustomerId()))
                .orElse(null);
    }
}
