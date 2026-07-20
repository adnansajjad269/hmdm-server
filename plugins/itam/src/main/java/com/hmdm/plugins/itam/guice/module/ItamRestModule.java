package com.hmdm.plugins.itam.guice.module;

import com.google.inject.servlet.ServletModule;
import com.hmdm.plugin.rest.PluginAccessFilter;
import com.hmdm.plugins.itam.rest.ItamResource;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.rest.filter.PrivateIPFilter;
import com.hmdm.security.jwt.JWTFilter;

public class ItamRestModule extends ServletModule {

    protected void configureServlets() {
        this.filter("/rest/plugins/itam/private/*").through(JWTFilter.class);
        this.filter("/rest/plugins/itam/private/*").through(AuthFilter.class);
        this.filter("/rest/plugins/itam/private/*").through(PluginAccessFilter.class);
        this.filter("/rest/plugins/itam/private/*").through(PrivateIPFilter.class);
        this.bind(ItamResource.class);
    }
}
