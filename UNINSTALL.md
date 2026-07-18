# Uninstalling hmdm-stats

Everything is a bolt-on; Headwind MDM itself is untouched except the optional
web panel patch (step 5).

```bash
# 1. stop sampling
sudo rm -f /etc/cron.d/hmdm-stats /etc/logrotate.d/hmdm-stats

# 2. drop the history table and roles (this deletes all collected history!)
sudo -u postgres psql -d hmdm -c "DROP TABLE IF EXISTS device_status_history;"
sudo -u postgres psql -c "DROP ROLE IF EXISTS hmdm_stats; DROP ROLE IF EXISTS grafana_ro;"

# 3. remove Grafana (skip if you use it for anything else)
sudo systemctl disable --now grafana-server
sudo apt-get remove --purge grafana
sudo rm -rf /var/lib/grafana/dashboards/hmdm-stats \
            /etc/grafana/provisioning/datasources/hmdm-postgres.yaml \
            /etc/grafana/provisioning/dashboards/hmdm-stats.yaml \
            /etc/grafana/provisioning/alerting/hmdm-*.yaml
# if keeping Grafana, restore its pristine config instead:
#   sudo cp /etc/grafana/grafana.ini.hmdm-stats.orig /etc/grafana/grafana.ini
#   sudo systemctl restart grafana-server

# 4. remove files and logs
sudo rm -rf /opt/hmdm-stats /etc/hmdm-stats /var/log/hmdm-stats

# 5. revert the web panel Analytics tab (if it was injected)
# every touched file has a pristine backup next to it:
sudo find /var/lib/tomcat*/webapps -name '*.hmdm-stats.orig' | while read -r f; do
    sudo cp -p "$f" "${f%.hmdm-stats.orig}" && sudo rm "$f"
done
sudo find /var/lib/tomcat*/webapps -name 'hmdm-stats-analytics-tab.js' -delete
```
