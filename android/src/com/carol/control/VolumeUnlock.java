package com.carol.control;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

/**
 * Sony / AOSP hide bluetooth_disable_absolute_volume from getInt.
 * Read Global via query + system properties, and let the user confirm.
 */
final class VolumeUnlock {
    private static final String TAG = "CarolAbsVol";
    private static final String PREF = "carol_ble";
    private static final String USER = "absvol_user";
    private static final String[] KEYS = {
            "bluetooth_disable_absolute_volume",
            "hw_bluetooth_absolute_volume",
            "bluetooth_abs_volume"
    };
    private static final String[] PROPS = {
            "persist.bluetooth.disableabsvol",
            "persist.sys.bluetooth.disableabsvol",
            "persist.bluetooth.a2dp.disableabsvol"
    };

    private VolumeUnlock() {}

    static boolean isDisabled(Context ctx) {
        if (prefs(ctx).getBoolean(USER, false)) return true;
        if (propOn()) return true;
        if (queryOn(ctx)) return true;
        for (String k : KEYS) {
            try {
                if (Settings.Global.getInt(ctx.getContentResolver(), k, 0) == 1) return true;
            } catch (Exception ignored) {
            }
            try {
                if (Settings.System.getInt(ctx.getContentResolver(), k, 0) == 1) return true;
            } catch (Exception ignored) {
            }
            try {
                if (Settings.Secure.getInt(ctx.getContentResolver(), k, 0) == 1) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    static void markUserEnabled(Context ctx) {
        prefs(ctx).edit().putBoolean(USER, true).apply();
        maxPcm(ctx);
    }

    static boolean tryDisable(Context ctx) {
        if (isDisabled(ctx)) {
            maxPcm(ctx);
            return true;
        }
        for (String k : KEYS) put(ctx, k, 1);
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setParameters("bt_a2dp_abs_vol=false");
                am.setParameters("absolute_volume=false");
            }
        } catch (Exception e) {
            Log.w(TAG, "setParameters", e);
        }
        if (isDisabled(ctx)) {
            maxPcm(ctx);
            return true;
        }
        return false;
    }

    static void maxPcm(Context ctx) {
        if (!isDisabled(ctx)) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
        } catch (Exception ignored) {
        }
    }

    static void openSettings(Context ctx) {
        Intent[] intents = {
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                new Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
                new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        };
        for (Intent i : intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean queryOn(Context ctx) {
        for (String table : new String[] { "global", "system", "secure" }) {
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(
                        Uri.parse("content://settings/" + table),
                        new String[] { "value" },
                        "name=?",
                        new String[] { "bluetooth_disable_absolute_volume" },
                        null);
                if (c != null && c.moveToFirst()) {
                    String v = c.getString(0);
                    if ("1".equals(v) || "true".equalsIgnoreCase(v)) return true;
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) try { c.close(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static boolean propOn() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method gb = sp.getMethod("getBoolean", String.class, boolean.class);
            java.lang.reflect.Method g = sp.getMethod("get", String.class);
            for (String k : PROPS) {
                try {
                    if (Boolean.TRUE.equals(gb.invoke(null, k, false))) return true;
                    String v = (String) g.invoke(null, k);
                    if ("1".equals(v) || "true".equalsIgnoreCase(v)) return true;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean put(Context ctx, String key, int v) {
        try {
            Settings.Global.putInt(ctx.getContentResolver(), key, v);
            return Settings.Global.getInt(ctx.getContentResolver(), key, 0) == v;
        } catch (Exception ignored) {
        }
        return false;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
