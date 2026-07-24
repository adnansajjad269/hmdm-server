package com.hmdm.plugins.itam.persistence.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * A single ITAM log entry, one row per ownership/condition/status snapshot for a device.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@ApiModel(description = "An ITAM log entry")
public class ItamLog implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    @ApiModelProperty(hidden = true)
    private Integer customerId;

    @ApiModelProperty("An ID of the device this entry is for")
    private Integer deviceId;

    // Set once at creation time from the device's number; survives even if the device row is later deleted
    @ApiModelProperty(hidden = true)
    private String deviceNumber;

    @ApiModelProperty("Owner name, or null/\"N/A\" for unassigned stock")
    private String ownerName;

    @ApiModelProperty("Date the current owner took possession")
    private Date ownershipDate;

    @ApiModelProperty("IN_USE | IN_STOCK | UNDER_REPAIR | RETIRED")
    private String assetStatus;

    @ApiModelProperty("GOOD | BAD")
    private String deviceCondition;

    @ApiModelProperty("GOOD | BAD")
    private String batteryCondition;

    private String comments;

    @ApiModelProperty("Local file paths/URLs of attached pictures, max 5")
    private List<String> pictures;

    @ApiModelProperty(hidden = true)
    private Integer loggedByUserId;

    @ApiModelProperty("Display name of the admin who created this entry")
    private String loggedByUserName;

    @ApiModelProperty(hidden = true)
    private Integer deletedByUserId;

    private Date createdAt;

    @ApiModelProperty(hidden = true)
    private Date deletedAt;

    public ItamLog() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getCustomerId() {
        return customerId == null ? 0 : customerId;
    }

    public void setCustomerId(int customerId) {
        this.customerId = customerId;
    }

    public Integer getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(Integer deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceNumber() {
        return deviceNumber;
    }

    public void setDeviceNumber(String deviceNumber) {
        this.deviceNumber = deviceNumber;
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

    public List<String> getPictures() {
        return pictures;
    }

    public void setPictures(List<String> pictures) {
        this.pictures = pictures;
    }

    public Integer getLoggedByUserId() {
        return loggedByUserId;
    }

    public void setLoggedByUserId(Integer loggedByUserId) {
        this.loggedByUserId = loggedByUserId;
    }

    public String getLoggedByUserName() {
        return loggedByUserName;
    }

    public void setLoggedByUserName(String loggedByUserName) {
        this.loggedByUserName = loggedByUserName;
    }

    public Integer getDeletedByUserId() {
        return deletedByUserId;
    }

    public void setDeletedByUserId(Integer deletedByUserId) {
        this.deletedByUserId = deletedByUserId;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Date deletedAt) {
        this.deletedAt = deletedAt;
    }
}
