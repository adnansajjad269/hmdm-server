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
import java.util.Date;
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
     * @return true if a matching, not-already-deleted row was found and soft-deleted.
     */
    @Transactional
    public boolean softDelete(String id) {
        return SecurityContext.get().getCurrentUser()
                .map(user -> mapper.softDelete(id, user.getId(), user.getCustomerId()) > 0)
                .orElse(false);
    }

    /**
     * Hard-deletes rows soft-deleted more than {@code retentionDays} ago, returning the picture paths
     * that need removing from disk (caller's responsibility -- this DAO has no filesystem access).
     */
    @Transactional
    public List<String> purge(int retentionDays) {
        Date cutoff = new Date(System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000);
        List<ItamLog> purgeable = mapper.findPurgeable(cutoff);
        List<String> allPictures = new ArrayList<>();
        for (ItamLog log : purgeable) {
            if (log.getPictures() != null) {
                allPictures.addAll(log.getPictures());
            }
        }
        mapper.hardDeletePurged(cutoff);
        return allPictures;
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
