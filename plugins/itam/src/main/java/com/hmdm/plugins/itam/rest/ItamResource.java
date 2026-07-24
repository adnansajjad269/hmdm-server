package com.hmdm.plugins.itam.rest;

import com.hmdm.plugins.itam.persistence.ItamDAO;
import com.hmdm.plugins.itam.persistence.domain.DeviceLookupItem;
import com.hmdm.plugins.itam.persistence.domain.ItamLog;
import com.hmdm.plugins.itam.rest.json.ItamLogCreateRequest;
import com.hmdm.plugins.itam.rest.json.ItamLogFilter;
import com.hmdm.plugins.itam.util.ItamPictureStorage;
import com.hmdm.rest.json.PaginatedData;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.glassfish.jersey.media.multipart.ContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Singleton
@Path("/plugins/itam")
@Api(tags = {"Plugin - ITAM"})
public class ItamResource {

    private static final Logger logger = LoggerFactory.getLogger(ItamResource.class);
    private static final int MAX_PICTURES = 5;

    private final ItamDAO itamDAO;
    private final ItamPictureStorage pictureStorage;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    public ItamResource(ItamDAO itamDAO, ItamPictureStorage pictureStorage) {
        this.itamDAO = itamDAO;
        this.pictureStorage = pictureStorage;
    }

    // ---------------------------------------------------------------- list / export

    @ApiOperation(value = "Search ITAM logs", response = PaginatedData.class)
    @POST
    @Path("/private/logs/search")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response search(ItamLogFilter filter) {
        if (!SecurityContext.get().hasPermission("plugin_itam_view")) {
            return Response.PERMISSION_DENIED();
        }
        try {
            List<ItamLog> records = itamDAO.findAll(filter);
            long count = itamDAO.countAll(filter);
            return Response.OK(new PaginatedData<>(records, count));
        } catch (Exception e) {
            logger.error("Failed to search ITAM logs. Filter: {}", filter, e);
            return Response.INTERNAL_ERROR();
        }
    }

    @ApiOperation(value = "Export filtered ITAM logs as CSV")
    @GET
    @Path("/private/logs/export")
    @Produces("text/csv")
    public javax.ws.rs.core.Response export(@QueryParam("deviceId") Integer deviceId,
                                             @QueryParam("deviceNumber") String deviceNumber,
                                             @QueryParam("ownerName") String ownerName,
                                             @QueryParam("assetStatus") String assetStatus,
                                             @QueryParam("deviceCondition") String deviceCondition,
                                             @QueryParam("batteryCondition") String batteryCondition,
                                             @QueryParam("dateFrom") Long dateFrom,
                                             @QueryParam("dateTo") Long dateTo) {
        if (!SecurityContext.get().hasPermission("plugin_itam_export")) {
            return javax.ws.rs.core.Response.status(403).build();
        }
        ItamLogFilter filter = new ItamLogFilter();
        filter.setDeviceId(deviceId);
        filter.setDeviceNumber(deviceNumber);
        filter.setOwnerName(ownerName);
        filter.setAssetStatus(assetStatus);
        filter.setDeviceCondition(deviceCondition);
        filter.setBatteryCondition(batteryCondition);
        filter.setDateFrom(dateFrom == null ? null : new Date(dateFrom));
        filter.setDateTo(dateTo == null ? null : new Date(dateTo));
        ContentDisposition contentDisposition = ContentDisposition.type("attachment")
                .fileName("itam-logs.csv").creationDate(new Date()).build();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        StreamingOutput stream = (OutputStream output) -> {
            PrintWriter writer = new PrintWriter(output, true, StandardCharsets.UTF_8);
            writer.println("Device,Owner,Asset Status,Ownership Date,Device Condition,Battery Condition,Comments,Logged By,Created At");
            List<ItamLog> records = itamDAO.findAll(filter);
            for (ItamLog log : records) {
                writer.println(String.join(",",
                        csv(log.getDeviceNumber()),
                        csv(log.getOwnerName()),
                        csv(log.getAssetStatus()),
                        csv(log.getOwnershipDate() == null ? "" : dateFormat.format(log.getOwnershipDate())),
                        csv(log.getDeviceCondition()),
                        csv(log.getBatteryCondition()),
                        csv(log.getComments()),
                        csv(log.getLoggedByUserName()),
                        csv(log.getCreatedAt() == null ? "" : dateFormat.format(log.getCreatedAt()))
                ));
            }
            writer.flush();
        };
        return javax.ws.rs.core.Response.ok(stream)
                .header("Content-Disposition", contentDisposition.toString())
                .build();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    // ---------------------------------------------------------------- latest-log (pre-population)

    @ApiOperation(value = "Get the most recent non-deleted log for a device")
    @GET
    @Path("/private/logs/latest/{deviceId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response latest(@PathParam("deviceId") int deviceId) {
        if (!SecurityContext.get().hasPermission("plugin_itam_create")) {
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(itamDAO.findLatestByDevice(deviceId));
    }

    // ---------------------------------------------------------------- device combobox + telemetry

    @ApiOperation(value = "Search devices by number/serial for the Add Entry combobox")
    @GET
    @Path("/private/devices/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchDevices(@QueryParam("query") String query) {
        if (!SecurityContext.get().hasPermission("plugin_itam_create")) {
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(itamDAO.searchDevices(query));
    }

    @ApiOperation(value = "Live telemetry (model/serial/OS/last check-in) for a selected device")
    @GET
    @Path("/private/devices/{deviceId}/telemetry")
    @Produces(MediaType.APPLICATION_JSON)
    public Response telemetry(@PathParam("deviceId") int deviceId) {
        if (!SecurityContext.get().hasPermission("plugin_itam_create")) {
            return Response.PERMISSION_DENIED();
        }
        DeviceLookupItem item = itamDAO.getDeviceTelemetry(deviceId);
        if (item == null) {
            return Response.OBJECT_NOT_FOUND_ERROR();
        }
        return Response.OK(item);
    }

    // ---------------------------------------------------------------- create

    @ApiOperation(value = "Create a new ITAM log entry, with up to 5 attached pictures")
    @POST
    @Path("/private/logs")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@FormDataParam("data") String dataJson,
                            @FormDataParam("pictures") List<FormDataBodyPart> pictureParts) {
        if (!SecurityContext.get().hasPermission("plugin_itam_create")) {
            return Response.PERMISSION_DENIED();
        }
        try {
            ItamLogCreateRequest request = objectMapper.readValue(dataJson, ItamLogCreateRequest.class);
            if (request.getDeviceId() == null || request.getAssetStatus() == null
                    || request.getDeviceCondition() == null || request.getBatteryCondition() == null) {
                return Response.ERROR("Missing required fields");
            }
            if (pictureParts == null || pictureParts.isEmpty()) {
                return Response.ERROR("At least one picture is required");
            }
            if (pictureParts.size() > MAX_PICTURES) {
                return Response.ERROR("A maximum of " + MAX_PICTURES + " pictures is allowed");
            }

            int customerId = SecurityContext.get().getCurrentUser().map(u -> u.getCustomerId()).orElse(0);

            DeviceLookupItem device = itamDAO.getDeviceTelemetry(request.getDeviceId());
            String deviceNumber = (device != null && device.getNumber() != null) ? device.getNumber() : "device" + request.getDeviceId();
            // DDMM_HHmmss of the submission, shared by every picture in this entry; a "_2", "_3"...
            // suffix distinguishes additional pictures (PICTURE_NAME_FORMAT: DeviceNumber_DDMM_HHMMSS).
            String pictureTimestamp = new SimpleDateFormat("ddMM_HHmmss").format(new Date());

            List<String> savedPaths = new ArrayList<>();
            int pictureIndex = 1;
            for (FormDataBodyPart part : pictureParts) {
                try (InputStream is = part.getValueAs(InputStream.class)) {
                    String fileName = part.getContentDisposition().getFileName();
                    String contentType = part.getMediaType() != null ? part.getMediaType().toString() : null;
                    String baseName = deviceNumber + "_" + pictureTimestamp
                            + (pictureIndex > 1 ? "_" + pictureIndex : "");
                    savedPaths.add(pictureStorage.save(customerId, is, fileName, contentType, baseName));
                    pictureIndex++;
                }
            }

            ItamLog log = new ItamLog();
            log.setDeviceId(request.getDeviceId());
            log.setDeviceNumber(deviceNumber);
            log.setOwnerName(request.getOwnerName());
            log.setOwnershipDate(request.getOwnershipDate() != null ? request.getOwnershipDate() : new Date());
            log.setAssetStatus(request.getAssetStatus());
            log.setDeviceCondition(request.getDeviceCondition());
            log.setBatteryCondition(request.getBatteryCondition());
            log.setComments(request.getComments());
            log.setPictures(savedPaths);

            itamDAO.insert(log);
            return Response.OK();
        } catch (Exception e) {
            logger.error("Failed to create ITAM log entry", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // ---------------------------------------------------------------- delete (permanent)

    @ApiOperation(value = "Permanently delete an ITAM log entry and its picture files")
    @DELETE
    @Path("/private/logs/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") String id) {
        if (!SecurityContext.get().hasPermission("plugin_itam_delete")) {
            return Response.PERMISSION_DENIED();
        }
        try {
            List<String> pictures = itamDAO.hardDelete(id);
            if (pictures == null) {
                return Response.OBJECT_NOT_FOUND_ERROR();
            }
            for (String path : pictures) {
                pictureStorage.delete(path);
            }
            return Response.OK();
        } catch (Exception e) {
            logger.error("Failed to delete ITAM log entry {}", id, e);
            return Response.INTERNAL_ERROR();
        }
    }
}
