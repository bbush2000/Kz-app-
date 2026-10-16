package com.carol.control;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Remembered KZ devices for the in-app switcher. Audio may stay on more than one A2DP sink. */
final class DeviceRoster {
    private static final int MAX = 8;

    static String json(Context ctx) {
        return raw(ctx).toString();
    }

    static String eventJson(Context ctx) {
        try {
            JSONObject o = raw(ctx);
            o.put("type", "roster");
            return o.toString();
        } catch (Exception e) {
            return "{\"type\":\"roster\",\"items\":[],\"active\":\"\"}";
        }
    }

    static String activeId(Context ctx) {
        return raw(ctx).optString("active", "");
    }

    static String activeModel(Context ctx) {
        JSONObject o = raw(ctx);
        String aid = o.optString("active", "");
        JSONArray items = o.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null && aid.equals(it.optString("id"))) {
                    String m = it.optString("model", "");
                    if (m.length() > 0) return m;
                }
            }
        }
        return ctx.getSharedPreferences("carol_ble", 0).getString("model", "kz");
    }

    static void setActive(Context ctx, String id) {
        if (id == null || id.length() == 0) return;
        JSONObject o = raw(ctx);
        try {
            o.put("active", id);
            save(ctx, o);
        } catch (Exception ignored) {
        }
    }

    static void upsert(Context ctx, String id, String name, String model, String kind) {
        if (id == null || id.length() == 0) return;
        JSONObject o = raw(ctx);
        JSONArray items = o.optJSONArray("items");
        if (items == null) items = new JSONArray();
        JSONArray next = new JSONArray();
        try {
            JSONObject row = new JSONObject();
            row.put("id", id);
            row.put("name", name == null || name.length() == 0 ? "KZ" : name);
            row.put("model", model == null || model.length() == 0 ? "kz" : model);
            row.put("kind", kind == null ? "bt" : kind);
            next.put(row);
            for (int i = 0; i < items.length() && next.length() < MAX; i++) {
                JSONObject x = items.optJSONObject(i);
                if (x == null) continue;
                if (id.equals(x.optString("id"))) continue;
                next.put(x);
            }
            o.put("items", next);
            if (o.optString("active", "").length() == 0) o.put("active", id);
            save(ctx, o);
        } catch (Exception ignored) {
        }
    }

    static void remove(Context ctx, String id) {
        if (id == null) return;
        JSONObject o = raw(ctx);
        JSONArray items = o.optJSONArray("items");
        JSONArray next = new JSONArray();
        try {
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject x = items.optJSONObject(i);
                    if (x == null || id.equals(x.optString("id"))) continue;
                    next.put(x);
                }
            }
            o.put("items", next);
            if (id.equals(o.optString("active"))) {
                o.put("active", next.length() > 0 ? next.getJSONObject(0).optString("id") : "");
            }
            save(ctx, o);
        } catch (Exception ignored) {
        }
    }

    static void removeKind(Context ctx, String kind) {
        JSONObject o = raw(ctx);
        JSONArray items = o.optJSONArray("items");
        JSONArray next = new JSONArray();
        try {
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject x = items.optJSONObject(i);
                    if (x == null || kind.equals(x.optString("kind"))) continue;
                    next.put(x);
                }
            }
            o.put("items", next);
            boolean gone = true;
            String act = o.optString("active");
            for (int i = 0; i < next.length(); i++) {
                if (act.equals(next.getJSONObject(i).optString("id"))) gone = false;
            }
            if (gone) o.put("active", next.length() > 0 ? next.getJSONObject(0).optString("id") : "");
            save(ctx, o);
        } catch (Exception ignored) {
        }
    }

    static int size(Context ctx) {
        JSONArray a = raw(ctx).optJSONArray("items");
        return a == null ? 0 : a.length();
    }

    static JSONObject item(Context ctx, String id) {
        JSONArray a = raw(ctx).optJSONArray("items");
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && id.equals(x.optString("id"))) return x;
        }
        return null;
    }

    private static JSONObject raw(Context ctx) {
        try {
            String s = prefs(ctx).getString("roster", "");
            if (s != null && s.startsWith("{")) return new JSONObject(s);
        } catch (Exception ignored) {
        }
        JSONObject o = new JSONObject();
        try {
            o.put("items", new JSONArray());
            o.put("active", "");
            String addr = prefs(ctx).getString("addr", "");
            String name = prefs(ctx).getString("name", "");
            if (addr != null && addr.length() > 0) {
                upsertInto(o, addr, name, KzCatalog.modelId(name), "bt");
                o.put("active", addr);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void upsertInto(JSONObject o, String id, String name, String model, String kind) throws Exception {
        JSONArray items = o.optJSONArray("items");
        if (items == null) {
            items = new JSONArray();
            o.put("items", items);
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject x = items.optJSONObject(i);
            if (x != null && id.equals(x.optString("id"))) return;
        }
        JSONObject row = new JSONObject();
        row.put("id", id);
        row.put("name", name == null || name.length() == 0 ? "KZ" : name);
        row.put("model", model == null ? "kz" : model);
        row.put("kind", kind);
        items.put(row);
    }

    private static void save(Context ctx, JSONObject o) {
        prefs(ctx).edit().putString("roster", o.toString()).apply();
        UserState.markDirty();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("carol_ble", 0);
    }
}
