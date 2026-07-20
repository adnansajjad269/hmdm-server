package com.hmdm.plugins.itam.persistence.mapper;

import com.hmdm.plugins.itam.persistence.domain.DeviceLookupItem;
import com.hmdm.plugins.itam.persistence.domain.ItamLog;
import com.hmdm.plugins.itam.rest.json.ItamLogFilter;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * MyBatis mapper for {@link ItamLog} records and the device lookup used by the "Add Entry" combobox.
 */
public interface ItamMapper {

    List<ItamLog> findAll(ItamLogFilter filter);

    long countAll(ItamLogFilter filter);

    ItamLog findLatestByDevice(@Param("deviceId") int deviceId, @Param("customerId") int customerId);

    void insert(ItamLog log);

    int softDelete(@Param("id") String id, @Param("userId") int userId, @Param("customerId") int customerId);

    List<ItamLog> findPurgeable(@Param("cutoff") Date cutoff);

    void hardDeletePurged(@Param("cutoff") Date cutoff);

    List<DeviceLookupItem> searchDevices(@Param("customerId") int customerId, @Param("query") String query);

    DeviceLookupItem getDeviceTelemetry(@Param("deviceId") int deviceId, @Param("customerId") int customerId);
}
