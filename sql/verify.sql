-- hmdm-stats acceptance queries. Run as:
--   sudo -u postgres psql -d hmdm -f /opt/hmdm-stats/sql/verify.sql

\echo '--- 1. rows accumulating? (count should grow, max(sampled_at) should be recent)'
SELECT count(*) AS total_rows,
       count(DISTINCT device_number) AS devices,
       max(sampled_at) AS newest_sample,
       now() - max(sampled_at) AS staleness
FROM device_status_history;

\echo '--- 2. one row per device per bucket (dup_devices must be 0)'
SELECT sampled_at,
       count(*) AS rows,
       count(*) - count(DISTINCT device_number) AS dup_devices
FROM device_status_history
GROUP BY sampled_at
ORDER BY sampled_at DESC
LIMIT 5;

\echo '--- 3. retention window (oldest row must be within retention_days)'
SELECT min(sampled_at) AS oldest_sample,
       now() - min(sampled_at) AS history_span
FROM device_status_history;

\echo '--- 4. current fleet state (compare against the Headwind admin console)'
SELECT count(*) FILTER (WHERE online)      AS online_now,
       count(*) FILTER (WHERE NOT online)  AS offline_now,
       count(*) FILTER (WHERE battery_level < 20) AS below_20_pct
FROM (
    SELECT DISTINCT ON (device_number) online, battery_level
    FROM device_status_history
    ORDER BY device_number, sampled_at DESC
) latest;

\echo '--- 5. spot-check per device (compare battery % with the admin console)'
SELECT device_number, battery_level, charging, online, last_update
FROM (
    SELECT DISTINCT ON (device_number) *
    FROM device_status_history
    ORDER BY device_number, sampled_at DESC
) latest
ORDER BY battery_level ASC NULLS LAST
LIMIT 15;
