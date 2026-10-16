package com.carol.control;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Official JieLi OTA lookup used by KZ Audio 1.0.8 (JL_HttpApi.requestOTAFile).
 * Check only — this app never writes a .ufw block to the buds.
 */
final class FwOta {
    static final String KZ_PKG = "com.kzhifi.earphone";
    private static final String[] HOSTS = {
            "https://iotdev.jieliapp.com",
            "https://profile.jieliapp.com"
    };

    private FwOta() {}

    static String check(String authKey, String projCode, String currentVer) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "ota");
            if (authKey == null || authKey.length() == 0 || projCode == null || projCode.length() == 0) {
                o.put("state", "needchip");
                o.put("message", "Take both Carol Pro buds out of the case so they advertise, then connect. They stay invisible while docked.");
                o.put("current", nz(currentVer));
                return o.toString();
            }
            String lastErr = "";
            for (String host : HOSTS) {
                for (String kind : new String[]{"firmware", "0"}) {
                    String url = host + "/license/v1/fileupdate/check?auth_key="
                            + enc(authKey) + "&proj_code=" + enc(projCode) + "&type=" + enc(kind);
                    String body = get(url);
                    if (body == null) {
                        lastErr = "No reply from JieLi.";
                        continue;
                    }
                    JSONObject parsed = tryParse(body, currentVer);
                    if (parsed != null) return parsed.toString();
                    lastErr = "JieLi returned no package.";
                }
            }
            o.put("state", "none");
            o.put("current", nz(currentVer));
            o.put("message", lastErr.length() > 0
                    ? lastErr + " Open KZ Audio → Firmware Upgrade to flash the factory image."
                    : "No newer package on JieLi. Open KZ Audio to confirm, or case-reset the buds.");
            return o.toString();
        } catch (Exception e) {
            try {
                o.put("state", "error");
                o.put("current", nz(currentVer));
                o.put("message", "Could not reach JieLi. Use KZ Audio on Wi-Fi.");
            } catch (Exception ignored) {
            }
            return o.toString();
        }
    }

    private static JSONObject tryParse(String body, String currentVer) {
        try {
            JSONObject root = new JSONObject(body);
            int code = root.optInt("code", -1);
            JSONObject data = root.optJSONObject("data");
            if (data == null && root.has("version")) data = root;
            if ((code == 0 || code == 200) && data != null) {
                String ver = data.optString("version", "");
                String explain = data.optString("explain", "");
                String url = data.optString("url", data.optString("urlbase", ""));
                JSONObject o = new JSONObject();
                o.put("type", "ota");
                o.put("current", nz(currentVer));
                if (ver.length() > 0 && !ver.equals(currentVer)) {
                    o.put("state", "update");
                    o.put("version", ver);
                    o.put("explain", explain);
                    o.put("hasUrl", url.length() > 0);
                    o.put("message", "New firmware " + ver + ". Install it with official KZ Audio — this app will not flash the buds.");
                } else {
                    o.put("state", "none");
                    o.put("version", ver);
                    o.put("message", "JieLi has no newer package than " + nz(currentVer) + ". Case-reset if ANC mics are still dead.");
                }
                return o;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            if (code >= 400) return null;
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String nz(String s) {
        return s == null || s.length() == 0 ? "—" : s;
    }
}
