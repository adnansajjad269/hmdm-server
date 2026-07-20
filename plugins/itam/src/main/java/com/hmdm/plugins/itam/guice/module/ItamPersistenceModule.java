package com.hmdm.plugins.itam.guice.module;

import com.hmdm.guice.module.AbstractPersistenceModule;

import javax.servlet.ServletContext;

public class ItamPersistenceModule extends AbstractPersistenceModule {

    public ItamPersistenceModule(ServletContext context) {
        super(context);
    }

    @Override
    protected String getMapperPackageName() {
        return "com.hmdm.plugins.itam.persistence.mapper";
    }

    @Override
    protected String getDomainObjectsPackageName() {
        return "com.hmdm.plugins.itam.persistence.domain";
    }
}
