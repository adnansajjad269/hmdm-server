package com.hmdm.plugins.itam.persistence.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * Device search-result / live telemetry row for the ITAM "Add Entry" combobox and info banner.
 * Model/serial/OS version are read live from the devices.info payload, not cached.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@ApiModel(description = "A device combobox match with live telemetry")
public class DeviceLookupItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer id;
    private String number;
    private String model;
    private String serial;
    private String androidVersion;
    private Date lastCheckIn;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSerial() {
        return serial;
    }

    public void setSerial(String serial) {
        this.serial = serial;
    }

    public String getAndroidVersion() {
        return androidVersion;
    }

    public void setAndroidVersion(String androidVersion) {
        this.androidVersion = androidVersion;
    }

    public Date getLastCheckIn() {
        return lastCheckIn;
    }

    public void setLastCheckIn(Date lastCheckIn) {
        this.lastCheckIn = lastCheckIn;
    }
}
