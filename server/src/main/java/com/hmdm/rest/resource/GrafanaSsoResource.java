package com.hmdm.rest.resource;

import com.hmdm.grafana.GrafanaJwtService;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mints a short-lived Grafana SSO token for the Analytics tab's iframe (see
 * analytics.controller.js / GrafanaJwtService) -- gated by the same "analytics" permission that
 * already controls whether the tab itself is shown (content.html), so this never grants any access
 * beyond what a user could already reach.
 */
@Singleton
@Path("/private/plugins/grafana")
@Api(tags = {"Grafana SSO"})
public class GrafanaSsoResource {

    private static final Logger logger = LoggerFactory.getLogger(GrafanaSsoResource.class);

    private final GrafanaJwtService grafanaJwtService;

    @Inject
    public GrafanaSsoResource(GrafanaJwtService grafanaJwtService) {
        this.grafanaJwtService = grafanaJwtService;
    }

    @ApiOperation(value = "Get a short-lived Grafana SSO token for the current user")
    @GET
    @Path("/token")
    @Produces(MediaType.APPLICATION_JSON)
    public Response token() {
        if (!SecurityContext.get().hasPermission("analytics")) {
            return Response.PERMISSION_DENIED();
        }
        String login = SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse(null);
        if (login == null) {
            return Response.PERMISSION_DENIED();
        }
        Optional<String> token = grafanaJwtService.mintToken(login);
        if (!token.isPresent()) {
            // Grafana SSO isn't set up (key pair unavailable) -- the Analytics tab falls back to
            // Grafana's own login prompt inside the iframe, this isn't a hard error.
            logger.debug("Grafana SSO token requested but unavailable for user {}", login);
            return Response.OBJECT_NOT_FOUND_ERROR();
        }
        Map<String, String> body = new HashMap<>();
        body.put("token", token.get());
        return Response.OK(body);
    }
}
