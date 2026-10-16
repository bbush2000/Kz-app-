package com.carol.control;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Map;

/**
 * Keeps EQ, spatial profiles, last device, USB, codec, and mic EQ across
 * version upgrades. Internal snapshot always; public Downloads/KZControl
 * copy so a sideload reinstall can restore (no GPS in the public file).
 */
final class UserState {
    private static final String META = "carol_meta";
    private static final String INTERNAL = "kz_user_state.json";
    private static final String PUBLIC_NAME = "kz-user-state.json";
    private static final String[] FILES = {"carol_ble", "carol_mic", "carol_lost"};
    private static Context app;
    private static Handler h;
    private static final Runnable SNAP = new Runnable() {
        @Override
        public void run() {
            if (app != null) snapshot(app);
        }
    };

    static void boot(Context ctx) {
        app = ctx.getApplicationContext();
        h = new Handler(Looper.getMainLooper());
        restoreIfEmpty(app);
        SharedPreferences meta = app.getSharedPreferences(META, 0);
        int ver = ver(app);
        int old = meta.getInt("ver", 0);
        if (old != ver) {
            meta.edit().putInt("prev", old).putInt("ver", ver).apply();
            snapshot(app);
        }
    }

    static void markDirty() {
        if (h == null) return;
        h.removeCallbacks(SNAP);
        h.postDelayed(SNAP, 900);
    }

    static void snapshot(Context ctx) {
        try {
            JSONObject all = dump(ctx, true);
            writeFile(new File(ctx.getFilesDir(), INTERNAL), all.toString());
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) writeFile(new File(ext, INTERNAL), all.toString());
            writePublic(ctx, dump(ctx, false).toString());
        } catch (Exception ignored) {
        }
    }

    static String migrateWeb(Context ctx, String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
            if (!p.getBoolean("web_migrated", false)) {
                SharedPreferences.Editor e = p.edit();
                String spatial = o.optString("spatial", "");
                if (spatial.length() > 0 && !p.contains("spatial")) e.putString("spatial", spatial);
                String tune = o.optString("tune", "");
                if (tune.length() > 2 && !p.contains("spatial_tune")) e.putString("spatial_tune", tune);
                String profiles = o.optString("profiles", "");
                if (profiles.length() > 2 && !p.contains("spatial_profiles"))
                    e.putString("spatial_profiles", profiles);
                String last = o.optString("last", "");
                if (last.length() > 2 && !p.contains("web_last")) e.putString("web_last", last);
                e.putBoolean("web_migrated", true).apply();
                markDirty();
            }
        } catch (Exception ignored) {
        }
        return "ok";
    }

    static String lastDevice(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
        JSONObject o = new JSONObject();
        try {
            String addr = p.getString("addr", "");
            String name = p.getString("name", "");
            String web = p.getString("web_last", "");
            if (addr != null && addr.length() > 0) {
                o.put("id", addr);
                o.put("name", name == null || name.length() == 0 ? "KZ" : name);
            } else if (web != null && web.startsWith("{")) {
                return web;
            }
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    static void cacheAnc(Context ctx, String mode) {
        if (ctx == null || mode == null || mode.length() == 0) return;
        ctx.getSharedPreferences("carol_ble", 0).edit().putString("cache_anc", mode).apply();
    }

    static void cacheCodec(Context ctx, String codec) {
        if (ctx == null || codec == null || codec.length() == 0) return;
        ctx.getSharedPreferences("carol_ble", 0).edit().putString("cache_codec", codec).apply();
    }

    static void cacheBattery(Context ctx, int l, int r, int c) {
        if (ctx == null) return;
        ctx.getSharedPreferences("carol_ble", 0).edit()
                .putInt("cache_bl", l)
                .putInt("cache_br", r)
                .putInt("cache_bc", c)
                .apply();
    }

    static void cacheLink(Context ctx, String id, String name, String model, boolean chip) {
        if (ctx == null) return;
        android.content.SharedPreferences.Editor e = ctx.getSharedPreferences("carol_ble", 0).edit();
        if (id != null) e.putString("cache_id", id);
        if (name != null) e.putString("cache_name", name);
        if (model != null) e.putString("cache_model", model);
        e.putBoolean("cache_chip", chip);
        e.apply();
    }

    /** Last ANC, codec, battery, and link. UI paints this, then the radio refreshes it. */
    static String radioCache(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
        JSONObject o = new JSONObject();
        try {
            String anc = p.getString("cache_anc", "");
            if (anc.length() == 0) anc = "off";
            o.put("anc", anc);
            o.put("codec", p.getString("cache_codec", ""));
            o.put("bl", p.getInt("cache_bl", -1));
            o.put("br", p.getInt("cache_br", -1));
            o.put("bc", p.getInt("cache_bc", -1));
            String id = p.getString("cache_id", "");
            if (id.length() == 0) id = p.getString("addr", "");
            o.put("id", id);
            String name = p.getString("cache_name", "");
            if (name.length() == 0) name = p.getString("name", "");
            o.put("name", name);
            o.put("model", p.getString("cache_model", ""));
            o.put("chip", p.getBoolean("cache_chip", false));
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private static JSONObject dump(Context ctx, boolean includeLost) throws Exception {
        JSONObject root = new JSONObject();
        root.put("v", ver(ctx));
        root.put("when", System.currentTimeMillis());
        JSONObject files = new JSONObject();
        for (String name : FILES) {
            if (!includeLost && "carol_lost".equals(name)) continue;
            files.put(name, prefsToJson(ctx.getSharedPreferences(name, 0)));
        }
        root.put("prefs", files);
        return root;
    }

    private static void restoreIfEmpty(Context ctx) {
        JSONObject snap = readFile(new File(ctx.getFilesDir(), INTERNAL));
        if (snap == null) {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) snap = readFile(new File(ext, INTERNAL));
        }
        if (snap == null) snap = readPublic(ctx);
        if (snap == null) return;
        merge(ctx, snap);
    }

    private static void merge(Context ctx, JSONObject snap) {
        JSONObject files = snap.optJSONObject("prefs");
        if (files == null) return;
        Iterator<String> names = files.keys();
        while (names.hasNext()) {
            String name = names.next();
            JSONObject kv = files.optJSONObject(name);
            if (kv == null) continue;
            SharedPreferences p = ctx.getSharedPreferences(name, 0);
            SharedPreferences.Editor e = p.edit();
            Iterator<String> keys = kv.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (p.contains(k)) continue;
                JSONObject cell = kv.optJSONObject(k);
                if (cell == null) continue;
                String t = cell.optString("t", "s");
                try {
                    if ("b".equals(t)) e.putBoolean(k, cell.optBoolean("v"));
                    else if ("i".equals(t)) e.putInt(k, cell.optInt("v"));
                    else if ("l".equals(t)) e.putLong(k, cell.optLong("v"));
                    else if ("f".equals(t)) e.putFloat(k, (float) cell.optDouble("v"));
                    else e.putString(k, cell.optString("v"));
                } catch (Exception ignored) {
                }
            }
            e.apply();
        }
    }

    private static JSONObject prefsToJson(SharedPreferences p) throws Exception {
        JSONObject o = new JSONObject();
        Map<String, ?> all = p.getAll();
        if (all == null) return o;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            JSONObject cell = new JSONObject();
            if (v instanceof Boolean) {
                cell.put("t", "b");
                cell.put("v", (Boolean) v);
            } else if (v instanceof Integer) {
                cell.put("t", "i");
                cell.put("v", (Integer) v);
            } else if (v instanceof Long) {
                cell.put("t", "l");
                cell.put("v", (Long) v);
            } else if (v instanceof Float) {
                cell.put("t", "f");
                cell.put("v", (Float) v);
            } else {
                cell.put("t", "s");
                cell.put("v", String.valueOf(v));
            }
            o.put(e.getKey(), cell);
        }
        return o;
    }

    private static void writeFile(File f, String s) {
        FileOutputStream os = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            os = new FileOutputStream(f);
            os.write(s.getBytes(Charset.forName("UTF-8")));
        } catch (Exception ignored) {
        } finally {
            try {
                if (os != null) os.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static JSONObject readFile(File f) {
        if (f == null || !f.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            return parse(in);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static JSONObject parse(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line);
        if (b.length() < 2) return null;
        return new JSONObject(b.toString());
    }

    private static void writePublic(Context ctx, String json) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = ctx.getContentResolver();
                Uri uri = findDownload(cr);
                if (uri == null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, PUBLIC_NAME);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/KZControl");
                    uri = cr.insert(MediaStore.Downloads.getContentUri("external"), v);
                }
                if (uri == null) return;
                OutputStream os = cr.openOutputStream(uri, "wt");
                if (os == null) return;
                try {
                    os.write(json.getBytes(Charset.forName("UTF-8")));
                } finally {
                    os.close();
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "KZControl");
                if (!dir.exists()) dir.mkdirs();
                writeFile(new File(dir, PUBLIC_NAME), json);
            }
        } catch (Exception ignored) {
        }
    }

    private static JSONObject readPublic(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = findDownload(ctx.getContentResolver());
                if (uri == null) return null;
                InputStream in = ctx.getContentResolver().openInputStream(uri);
                if (in == null) return null;
                try {
                    return parse(in);
                } finally {
                    in.close();
                }
            }
            File f = new File(new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "KZControl"), PUBLIC_NAME);
            return readFile(f);
        } catch (Exception e) {
            return null;
        }
    }

    private static Uri findDownload(ContentResolver cr) {
        if (Build.VERSION.SDK_INT < 29) return null;
        Cursor c = null;
        try {
            Uri col = MediaStore.Downloads.getContentUri("external");
            c = cr.query(col,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{PUBLIC_NAME}, null);
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                return Uri.withAppendedPath(col, Long.toString(id));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private static int ver(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }
}
