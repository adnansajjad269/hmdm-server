package com.hmdm.plugins.itam.rest.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;
import java.util.Date;

/**
 * The "data" JSON part of the multipart POST /plugins/itam/private/logs request.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ItamLogCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer deviceId;
    private String ownerName;
    private Date ownershipDate;
    private String assetStatus;
    private String deviceCondition;
    private String batteryCondition;
    private String comments;

    public Integer getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(Integer deviceId) {
        this.deviceId = deviceId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public Date getOwnershipDate() {
        return ownershipDate;
    }

    public void setOwnershipDate(Date ownershipDate) {
        this.ownershipDate = ownershipDate;
    }

    public String getAssetStatus() {
        return assetStatus;
    }

    public void setAssetStatus(String assetStatus) {
        this.assetStatus = assetStatus;
    }

    public String getDeviceCondition() {
        return deviceCondition;
    }

    public void setDeviceCondition(String deviceCondition) {
        this.deviceCondition = deviceCondition;
    }

    public String getBatteryCondition() {
        return batteryCondition;
    }

    public void setBatteryCondition(String batteryCondition) {
        this.batteryCondition = batteryCondition;
    }

    public String getComments() {
        return comments;
    }

    public void setComments(String comments) {
        this.comments = comments;
    }
}
