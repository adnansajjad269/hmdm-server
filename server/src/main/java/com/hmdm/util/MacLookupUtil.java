package com.hmdm.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Computes the Devices grid's "WiFi Area" value from a device's WiFi SSID/BSSID.
 *
 * SSIDs not containing "LAAM" (case-insensitive) are simply reported as "EXTERNAL". SSIDs that DO
 * contain "LAAM" are resolved via an admin-maintained lookup file -- {@code maclookup.csv}, a plain
 * two-column CSV (BSSID, area name) uploaded through the existing Files module UI, which lands at
 * {@code <files.directory>/maclookup.csv} and is thus also reachable at {@code /files/maclookup.csv}
 * (see DownloadFilesServlet). A BSSID with no matching row is reported as "LOCATION NOT FOUND".
 *
 * The file is re-read on every call rather than cached: it's a small, infrequently-changing file an
 * admin re-uploads occasionally, and the Devices grid is a low-traffic internal admin view, so the
 * simplicity of always reflecting the latest upload outweighs any caching benefit.
 */
public class MacLookupUtil {

    private static final Logger logger = LoggerFactory.getLogger(MacLookupUtil.class);
    private static final String EXTERNAL = "EXTERNAL";
    private static final String NOT_FOUND = "LOCATION NOT FOUND";

    private MacLookupUtil() {
    }

    public static String resolveWifiArea(String filesDirectory, String wifiSsid, String wifiBssid) {
        if (wifiSsid == null || !wifiSsid.toLowerCase().contains("laam")) {
            return EXTERNAL;
        }
        if (wifiBssid == null || wifiBssid.trim().isEmpty()) {
            return NOT_FOUND;
        }
        Map<String, String> lookup = loadLookup(filesDirectory);
        String area = lookup.get(wifiBssid.trim().toLowerCase());
        return area != null ? area : NOT_FOUND;
    }

    private static Map<String, String> loadLookup(String filesDirectory) {
        Map<String, String> result = new HashMap<>();
        if (filesDirectory == null || filesDirectory.isEmpty()) {
            return result;
        }
        Path csvPath = Paths.get(filesDirectory, "maclookup.csv");
        if (!Files.exists(csvPath)) {
            return result;
        }
        try {
            for (String line : Files.readAllLines(csvPath, StandardCharsets.UTF_8)) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                int comma = line.indexOf(',');
                if (comma < 0) {
                    continue;
                }
                String key = stripQuotes(line.substring(0, comma).trim());
                String value = stripQuotes(line.substring(comma + 1).trim());
                if (!key.isEmpty()) {
                    result.put(key.toLowerCase(), value);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read maclookup.csv at {}", csvPath, e);
        }
        return result;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
