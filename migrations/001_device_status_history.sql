-- hmdm-stats migration 001: history table for device battery/online snapshots.
-- Safe to re-run (pure IF NOT EXISTS). Applied by install.sh with ON_ERROR_STOP.
--
-- Design notes:
--  * No FK to devices: history must survive device deletion/re-enrollment.
--  * sampled_at is bucket-aligned (now() floored to the cron interval) so the
--    composite PK + ON CONFLICT DO NOTHING makes overlapping runs no-ops.
--  * ~200 devices x 144 samples/day x 30 days ~= 864k rows: plain table +
--    these two indexes is plenty, no partitioning.

CREATE TABLE IF NOT EXISTS device_status_history (
    device_number   text        NOT NULL,   -- devices.number (deviceId the launcher reports)
    device_pk       integer,                -- devices.id at snapshot time (informational)
    sampled_at      timestamptz NOT NULL,   -- bucket-aligned snapshot instant
    battery_level   smallint,               -- 0..100; NULL if info missing/unparseable
    charging        text,                   -- 'usb' | 'ac' | NULL (not charging / unknown)
    last_update     timestamptz,            -- devices.lastupdate at snapshot time
    online          boolean     NOT NULL,   -- now() - last_update < online threshold
    CONSTRAINT device_status_history_pk PRIMARY KEY (device_number, sampled_at)
);

CREATE INDEX IF NOT EXISTS dsh_sampled_at_idx
    ON device_status_history (sampled_at);
CREATE INDEX IF NOT EXISTS dsh_device_time_idx
    ON device_status_history (device_number, sampled_at DESC);

COMMENT ON TABLE device_status_history IS
    'hmdm-stats: periodic snapshots of Headwind MDM device battery/online state';
