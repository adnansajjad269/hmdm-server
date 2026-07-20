package com.hmdm.plugins.itam.guice.module;

import com.google.inject.Inject;
import com.hmdm.plugin.PluginTaskModule;
import com.hmdm.plugins.itam.persistence.ItamDAO;
import com.hmdm.plugins.itam.util.ItamPictureStorage;
import com.hmdm.util.BackgroundTaskRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Daily job: hard-deletes ITAM log rows soft-deleted more than 7 days ago and removes their
 * picture files from disk.
 */
public class ItamTaskModule implements PluginTaskModule {

    private static final Logger logger = LoggerFactory.getLogger(ItamTaskModule.class);
    private static final int RETENTION_DAYS = 7;

    private final ItamDAO itamDAO;
    private final ItamPictureStorage pictureStorage;
    private final BackgroundTaskRunnerService taskRunner;

    @Inject
    public ItamTaskModule(ItamDAO itamDAO, ItamPictureStorage pictureStorage, BackgroundTaskRunnerService taskRunner) {
        this.itamDAO = itamDAO;
        this.pictureStorage = pictureStorage;
        this.taskRunner = taskRunner;
    }

    @Override
    public void init() {
        taskRunner.submitRepeatableTask(this::purge, 1, 24, TimeUnit.HOURS);
    }

    private void purge() {
        try {
            List<String> picturePaths = itamDAO.purge(RETENTION_DAYS);
            for (String path : picturePaths) {
                pictureStorage.delete(path);
            }
            if (!picturePaths.isEmpty()) {
                logger.info("ITAM purge: removed {} picture file(s) for hard-deleted log entries", picturePaths.size());
            }
        } catch (Exception e) {
            logger.error("ITAM purge job failed", e);
        }
    }
}
