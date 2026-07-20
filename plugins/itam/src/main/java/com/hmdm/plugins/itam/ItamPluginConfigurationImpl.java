package com.hmdm.plugins.itam;

import com.google.inject.Module;
import com.hmdm.plugin.PluginConfiguration;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.itam.guice.module.ItamLiquibaseModule;
import com.hmdm.plugins.itam.guice.module.ItamPersistenceModule;
import com.hmdm.plugins.itam.guice.module.ItamRestModule;
import com.hmdm.plugins.itam.guice.module.ItamTaskModule;

import javax.servlet.ServletContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class ItamPluginConfigurationImpl implements PluginConfiguration {

    public static final String PLUGIN_ID = "itam";

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public String getRootPackage() {
        return "com.hmdm.plugins.itam";
    }

    @Override
    public List<Module> getPluginModules(ServletContext context) {
        List<Module> modules = new ArrayList<>();
        modules.add(new ItamLiquibaseModule(context));
        modules.add(new ItamPersistenceModule(context));
        modules.add(new ItamRestModule());
        return modules;
    }

    @Override
    public Optional<List<Class<? extends PluginTaskModule>>> getTaskModules(ServletContext context) {
        List<Class<? extends PluginTaskModule>> tasks = new ArrayList<>();
        tasks.add(ItamTaskModule.class);
        return Optional.of(tasks);
    }
}
