package com.carol.control;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Optional last-seen pin. Off until the user taps Find → Allow GPS.
 * Never reverse-geocodes (that would send coordinates off-device).
 */
final class LastLost {
    static void capture(final Context ctx) {
        if (!enabled(ctx) || !hasLoc(ctx)) return;
        new Thread(() -> {
            try {
                write(ctx, readFix(ctx));
            } catch (Exception ignored) {
            }
        }, "carol-lost").start();
    }

    static void radioDrop(Context ctx, BluetoothDevice d) {
        try {
            SharedPreferences.Editor ed = ctx.getSharedPreferences("carol_ble", 0).edit();
            ed.putLong("radio_at", System.currentTimeMillis());
            String n = "";
            if (d != null) {
                try { n = d.getName() == null ? "" : d.getName(); } catch (Exception ignored) {}
            }
            if (n.length() > 0) ed.putString("radio_name", n);
            ed.apply();
        } catch (Exception ignored) {
        }
    }

    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean("on", false);
    }

    static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("on", on).apply();
        if (!on) clear(ctx);
    }

    static void clear(Context ctx) {
        prefs(ctx).edit().putString("lost", "[]").apply();
    }

    static String json(Context ctx) {
        return prefs(ctx).getString("lost", "[]");
    }

    static String status(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("on", enabled(ctx));
            o.put("pins", new JSONArray(json(ctx)));
            SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
            o.put("radioAt", p.getLong("radio_at", 0));
            o.put("radioName", p.getString("radio_name", ""));
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    static void openLast(Context ctx) {
        try {
            JSONArray a = new JSONArray(json(ctx));
            if (a.length() == 0) return;
            JSONObject o = a.getJSONObject(0);
            openAt(ctx, o.optDouble("lat", 0), o.optDouble("lng", 0));
        } catch (Exception ignored) {
        }
    }

    static void openAt(Context ctx, double lat, double lng) {
        if (lat == 0 && lng == 0) return;
        try {
            Uri u = Uri.parse("geo:" + lat + "," + lng + "?q=" + lat + "," + lng + "(KZ last seen)");
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private static JSONObject readFix(Context ctx) throws Exception {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        if (lm != null) {
            String[] ps = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
            for (String p : ps) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l == null) continue;
                    if (best == null || l.getTime() > best.getTime()) best = l;
                } catch (SecurityException ignored) {
                }
            }
        }
        JSONObject o = new JSONObject();
        o.put("time", System.currentTimeMillis());
        if (best != null) {
            o.put("lat", best.getLatitude());
            o.put("lng", best.getLongitude());
            o.put("acc", best.hasAccuracy() ? best.getAccuracy() : 0);
        } else {
            o.put("lat", 0);
            o.put("lng", 0);
        }
        return o;
    }

    private static void write(Context ctx, JSONObject o) throws Exception {
        JSONArray cur = new JSONArray(json(ctx));
        JSONArray next = new JSONArray();
        next.put(o);
        for (int i = 0; i < cur.length() && next.length() < 8; i++) next.put(cur.get(i));
        prefs(ctx).edit().putString("lost", next.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("carol_lost", 0);
    }

    static boolean hasLoc(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
