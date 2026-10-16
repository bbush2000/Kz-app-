package com.carol.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.EnvironmentalReverb;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Virtualizer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Virtual surround on the stereo mix. Effects stay attached; parameters
 * update in place so playback does not need to restart.
 */
final class SpatialFx {
    private final Context ctx;
    private final AudioManager am;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private String mode = "off";
    private String deviceId = "";
    private JSONObject tune = defaults();
    private final Map<Integer, Fx> live = new HashMap<>();
    private boolean fitHold;
    private boolean forceCommit;
    private final Runnable applyRun = this::engineApply;
    private final Runnable settleRun = this::commitAgain;
    private final Runnable settleLate = this::commitAgain;
    private final Runnable settleTune = this::commitAgain;

    private static SpatialFx inst;

    static synchronized SpatialFx get(Context ctx) {
        if (inst == null) inst = new SpatialFx(ctx.getApplicationContext());
        return inst;
    }

    private boolean routeKnown;
    private boolean routeSpeaker;

    void reattach() {
        followRoute();
    }

    void fitHold(boolean on) {
        fitHold = on;
        if (on) {
            h.removeCallbacks(applyRun);
            h.removeCallbacks(settleRun);
            synchronized (lock) { releaseAll(); }
        } else {
            scheduleApply();
        }
    }

    private SpatialFx(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
        this.deviceId = DeviceRoster.activeId(this.ctx);
        loadBound();
        routeSpeaker = speakerOut();
        routeKnown = true;
        h.post(() -> {
            if (needEngine() && !fitHold) rebind();
        });
        try {
            if (Build.VERSION.SDK_INT >= 26 && am != null) {
                am.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                    @Override
                    public void onPlaybackConfigChanged(java.util.List<AudioPlaybackConfiguration> configs) {
                        // Session 0 is already the mix. Rewriting it on every track
                        // stacks bass and limiter on this phone.
                        if (!live.isEmpty()) return;
                        h.removeCallbacks(applyRun);
                        h.postDelayed(applyRun, 80);
                    }
                }, h);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 23 && am != null) {
                am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                    @Override
                    public void onAudioDevicesAdded(AudioDeviceInfo[] added) { followRoute(); }
                    @Override
                    public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { followRoute(); }
                }, h);
            }
        } catch (Throwable ignored) {
        }
    }

    String mode() {
        return mode;
    }

    boolean bypass() {
        return tune.optInt("bypass", 0) != 0;
    }

    void setBypass(boolean on) {
        try {
            tune.put("bypass", on ? 1 : 0);
            persist();
        } catch (Exception ignored) {
        }
        scheduleApply();
    }

    void cycleLayout() {
        String[] order = {"off", "cross", "51", "71", "game", "spatial", "atmos", "dts", "thx", "360"};
        int i = 0;
        for (int n = 0; n < order.length; n++) if (order[n].equals(mode)) { i = n; break; }
        apply(order[(i + 1) % order.length]);
    }

    String tuneJson() {
        return tune.toString();
    }

    void bindDevice(String id) {
        persist();
        deviceId = id == null ? "" : id;
        loadBound();
        routeSpeaker = speakerOut();
        routeKnown = true;
        if (needEngine() && !fitHold) rebind();
        else scheduleApply();
    }

    /** Speaker virtualization is a hall. One rebuild when the route actually flips. */
    private final Runnable routeRun = () -> {
        boolean sp = speakerOut();
        boolean flip = routeKnown && sp != routeSpeaker;
        routeKnown = true;
        routeSpeaker = sp;
        if (flip && needEngine() && !fitHold) rebind();
        else scheduleApply();
    };

    private void followRoute() {
        h.removeCallbacks(routeRun);
        h.postDelayed(routeRun, 160);
    }

    private void loadBound() {
        mode = prefs().getString(dk("spatial"), prefs().getString("spatial", "off"));
        if ("717".equals(mode)) mode = "71";
        try {
            String raw = prefs().getString(dk("tune"), prefs().getString("spatial_tune", ""));
            if (raw != null && raw.length() > 2) tune = merge(defaults(), new JSONObject(raw));
            else tune = defaults();
        } catch (Exception e) {
            tune = defaults();
        }
        openStockStage();
    }

    /**
     * Factory 62 / 50 / 35 used speaker-style virtualization and a small room,
     * so surround read narrower than stereo. One-shot: only rewrite sliders
     * still sitting on those stock numbers.
     */
    private void openStockStage() {
        try {
            boolean changed = false;
            if (!prefs().getBoolean("stage_v2", false)) {
                if (tune.optInt("width", 62) == 62) { tune.put("width", 78); changed = true; }
                if (tune.optInt("surround", 50) == 50) { tune.put("surround", 64); changed = true; }
                if (tune.optInt("height", 35) == 35) { tune.put("height", 48); changed = true; }
                if (tune.optInt("center", 48) == 48) { tune.put("center", 38); changed = true; }
                if (tune.optInt("distance", 48) == 48) { tune.put("distance", 56); changed = true; }
                prefs().edit().putBoolean("stage_v2", true).apply();
            }
            if (!prefs().getBoolean("stage_out", false)) {
                if (tune.optInt("width", 78) == 78) { tune.put("width", 94); changed = true; }
                if (tune.optInt("surround", 64) == 64) { tune.put("surround", 92); changed = true; }
                if (tune.optInt("height", 48) == 48) { tune.put("height", 90); changed = true; }
                prefs().edit().putBoolean("stage_out", true).apply();
            }
            if (changed) persist();
        } catch (Exception ignored) {
        }
    }

    private String dk(String base) {
        if (deviceId == null || deviceId.length() == 0) {
            return "tune".equals(base) ? "spatial_tune" : base;
        }
        String id = deviceId.replace(":", "").replace("/", "").replace(" ", "");
        if (id.length() > 24) id = id.substring(0, 24);
        return base + "_" + id;
    }

    private void persist() {
        prefs().edit()
                .putString(dk("spatial"), mode)
                .putString(dk("tune"), tune.toString())
                .putString("spatial", mode)
                .putString("spatial_tune", tune.toString())
                .apply();
        UserState.markDirty();
    }

    String apply(String next) {
        if (next == null) next = "off";
        if ("717".equals(next)) next = "71";
        mode = next;
        persist();
        rebind();
        return mode;
    }

    /** Drop the live effects and commit the new layout twice. In-place
     *  strength and EQ writes are ignored while an effect stays enabled,
     *  which is why a profile only sounded right after a Stereo round-trip. */
    private void rebind() {
        h.removeCallbacks(applyRun);
        h.removeCallbacks(settleRun);
        h.removeCallbacks(settleLate);
        h.removeCallbacks(settleTune);
        synchronized (lock) { releaseAll(); }
        h.post(applyRun);
        h.postDelayed(settleRun, 360);
    }

    private void commitAgain() {
        forceCommit = true;
        engineApply();
    }

    String applyTune(String json) {
        try {
            if (json != null && json.length() > 2) {
                tune = merge(defaults(), new JSONObject(json));
                persist();
            }
        } catch (Exception ignored) {
        }
        scheduleApply();
        return tune.toString();
    }

    static JSONObject defaults() {
        JSONObject o = new JSONObject();
        try {
            o.put("width", 94);
            o.put("height", 90);
            o.put("surround", 92);
            o.put("center", 38);
            o.put("lfe", 42);
            o.put("head", 50);
            o.put("lx", 50);
            o.put("ly", 42);
            o.put("distance", 56);
            o.put("room", 0);
            o.put("reflect", 20);
            o.put("decay", 20);
            o.put("bassOn", 0);
            o.put("bass", 0);
            o.put("normOn", 0);
            o.put("norm", 0);
            o.put("voiceOn", 0);
            o.put("voice", 0);
            JSONArray geq = new JSONArray();
            for (int i = 0; i < 10; i++) geq.put(0);
            o.put("geq", geq);
            JSONArray peq = new JSONArray();
            int[] freqs = {20, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
            for (int f : freqs) peq.put(peqBand(f, 0, 12));
            o.put("peq", peq);
            o.put("dsee", 0);
            o.put("dseeSbc", 0);
            o.put("dseeLdac", 0);
            o.put("dseeAptx", 0);
            o.put("spk", 8);
            o.put("parent", "off");
            o.put("preamp", 0);
            o.put("bypass", 0);
            o.put("cross", 35);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject merge(JSONObject base, JSONObject over) {
        JSONObject o = new JSONObject();
        String[] keys = {"width", "height", "surround", "center", "lfe", "head", "lx", "ly", "distance", "room", "reflect", "decay", "bassOn", "bass", "normOn", "norm", "voiceOn", "voice", "dsee", "dseeSbc", "dseeLdac", "dseeAptx", "spk", "preamp", "bypass", "cross"};
        for (String k : keys) {
            try {
                o.put(k, over.has(k) ? over.getInt(k) : base.getInt(k));
            } catch (Exception e) {
                try {
                    o.put(k, 50);
                } catch (Exception ignored) {
                }
            }
        }
        try {
            o.put("geq", over.has("geq") ? over.getJSONArray("geq") : base.getJSONArray("geq"));
        } catch (Exception e) {
            try { o.put("geq", base.getJSONArray("geq")); } catch (Exception ignored) {}
        }
        try {
            o.put("peq", over.has("peq") ? over.getJSONArray("peq") : base.getJSONArray("peq"));
        } catch (Exception e) {
            try { o.put("peq", base.getJSONArray("peq")); } catch (Exception ignored) {}
        }
        try {
            o.put("parent", over.has("parent") ? over.getString("parent") : base.optString("parent", "off"));
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject peqBand(int f, int g, int q10) {
        JSONObject b = new JSONObject();
        try {
            b.put("f", f);
            b.put("g", g);
            b.put("q", q10);
            b.put("t", "peak");
        } catch (Exception ignored) {
        }
        return b;
    }

    private int t(String k, int d) {
        return tune.optInt(k, d);
    }

    private Recipe recipe() {
        return Recipe.forMode(mode, tune, t("width", 94), t("height", 90), t("surround", 92),
                t("center", 38), t("lfe", 42), t("head", 50), t("lx", 50), t("ly", 42), t("distance", 56));
    }

    private boolean userEqActive() {
        try {
            JSONArray geq = tune.optJSONArray("geq");
            if (geq != null) {
                for (int i = 0; i < geq.length(); i++) if (geq.optInt(i) != 0) return true;
            }
            JSONArray peq = tune.optJSONArray("peq");
            if (peq != null) {
                for (int i = 0; i < peq.length(); i++) {
                    JSONObject b = peq.optJSONObject(i);
                    if (b != null && (Math.abs(b.optDouble("g", 0)) >= 0.05 || Recipe.isPass(b.optString("t", "peak")))) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String btCodec = "";

    void setBtCodec(String codec) {
        String next = codec == null ? "" : codec.toLowerCase();
        synchronized (lock) {
            if (next.equals(btCodec)) return;
            btCodec = next;
        }
        scheduleApply();
    }

    private boolean restoreOn() {
        return tune.optInt("dsee", 0) != 0
                || tune.optInt("dseeSbc", 0) != 0
                || tune.optInt("dseeLdac", 0) != 0
                || tune.optInt("dseeAptx", 0) != 0;
    }

    private boolean dseeOn() {
        return restoreOn();
    }

    private boolean enhanceOn() {
        if (tune.optInt("bassOn", 0) != 0 && tune.optInt("bass", 0) > 0) return true;
        if (tune.optInt("normOn", 0) != 0 && tune.optInt("norm", 0) > 0) return true;
        if (tune.optInt("voiceOn", 0) != 0 && tune.optInt("voice", 0) > 0) return true;
        return false;
    }

    private boolean needEngine() {
        if (!"off".equals(mode)) return true;
        if (restoreOn()) return true;
        if (enhanceOn()) return true;
        if (tune.optInt("bypass", 0) != 0) return false;
        return userEqActive();
    }

    /** True while phone EQ or a surround profile is actually running. */
    boolean active() {
        return needEngine() && !fitHold;
    }

    private void releaseAll() {
        Integer[] ids = live.keySet().toArray(new Integer[0]);
        for (Integer id : ids) {
            Fx old = live.remove(id);
            if (old != null) old.release();
        }
    }

    /** Coalesce rapid profile taps onto one engine pass. Never release/recreate. */
    private void scheduleApply() {
        h.removeCallbacks(applyRun);
        h.postDelayed(applyRun, 40);
    }

    private void engineApply() {
        synchronized (lock) {
            try {
                boolean force = forceCommit;
                forceCommit = false;
                if (fitHold || !needEngine()) {
                    releaseAll();
                    return;
                }
                attachNewSessions();
                applyLive(force);
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyLive(boolean force) {
        Recipe r = recipe();
        r.surroundSub = carolSub();
        r.codec = btCodec;
        boolean spatialOn = !"off".equals(mode);
        boolean speaker = speakerOut();
        boolean eqOn = userEqActive() || restoreOn() || (spatialOn && !speaker)
                || (tune.optInt("voiceOn", 0) != 0 && tune.optInt("voice", 0) > 0);
        if (tune.optInt("bypass", 0) != 0 && !restoreOn()) eqOn = false;
        if (tune.optInt("normOn", 0) != 0) {
            r.loudness = Math.max(r.loudness, clamp(tune.optInt("norm", 0), 0, 100) * 10);
        }
        if (spatialOn && speaker) r.loudness = Math.max(r.loudness, 2400);
        boolean sub = carolSub();
        for (Fx fx : live.values()) fx.update(r, spatialOn, eqOn, speaker, sub, force);
    }

    private boolean carolSub() {
        if (mode == null || "off".equals(mode) || "cross".equals(mode)) return false;
        String m = DeviceRoster.activeModel(ctx);
        return "carol".equals(m) || "carol_pro".equals(m);
    }

    private boolean speakerOut() {
        if (am == null) return false;
        try {
            if (am.isBluetoothA2dpOn()) return false;
        } catch (Throwable ignored) {
        }
        try {
            if (am.isWiredHeadsetOn()) return false;
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT < 23) {
            try { return am.isSpeakerphoneOn(); } catch (Throwable ignored) { return false; }
        }
        boolean speaker = false;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d == null) continue;
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET
                        || t == AudioDeviceInfo.TYPE_USB_DEVICE
                        || t == AudioDeviceInfo.TYPE_USB_ACCESSORY
                        || t == AudioDeviceInfo.TYPE_HEARING_AID
                        || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                    return false;
                }
                if (t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        || (Build.VERSION.SDK_INT >= 30 && t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)) {
                    speaker = true;
                }
            }
        } catch (Throwable ignored) {
        }
        return speaker;
    }

    private Set<Integer> playbackIds() {
        Set<Integer> ids = new HashSet<>();
        if (Build.VERSION.SDK_INT < 26 || am == null) return ids;
        try {
            for (AudioPlaybackConfiguration c : am.getActivePlaybackConfigurations()) {
                int id = sessionOf(c);
                if (id > 0) ids.add(id);
            }
        } catch (Throwable ignored) {
        }
        return ids;
    }

    private String playKey = "";

    private void attachNewSessions() {
        // Session 0 is the output mix. A second Virtualizer on the app
        // session stacks on top of it: hall, no bass, stage collapses.
        Set<Integer> ids = new HashSet<>();
        ids.add(0);
        String key = ids.toString();
        boolean changed = !key.equals(playKey);
        playKey = key;
        if (changed) {
            releaseAll();
        }
        ensureSession(0);
        if (changed && !"off".equals(mode)) {
            h.removeCallbacks(settleRun);
            h.postDelayed(settleRun, 220);
        }
    }

    private static int sessionOf(AudioPlaybackConfiguration c) {
        try {
            Object v = c.getClass().getMethod("getAudioSessionId").invoke(c);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private void ensureSession(int session) {
        if (session < 0) return;
        if (live.containsKey(session)) return;
        Fx fx = Fx.open(session);
        if (fx != null) live.put(session, fx);
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("carol_ble", 0);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static final class Recipe {
        short virt;
        short bass;
        short rev;
        int loudness;
        int[] eq;
        boolean surroundSub;
        JSONObject tune;
        String codec = "";
        static final double[] PROF_HZ = {60, 250, 1000, 4000, 12000};
        static final double[] GEQ_HZ = {20, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

        static Recipe forMode(String mode, JSONObject tune, int width, int height, int surround, int center,
                              int lfe, int head, int lx, int ly, int distance) {
            Recipe r = new Recipe();
            r.tune = tune;
            int virt0 = 0, bass0 = 0;
            switch (mode) {
                case "51":
                    virt0 = 280;
                    break;
                case "71":
                    virt0 = 320;
                    break;
                case "game":
                    virt0 = 300;
                    break;
                case "spatial":
                    virt0 = 380;
                    break;
                case "cross":
                    virt0 = 80;
                    break;
                case "atmos":
                    virt0 = 400;
                    break;
                case "dts":
                    virt0 = 340;
                    break;
                case "thx":
                    virt0 = 350;
                    break;
                case "360":
                    virt0 = 460;
                    break;
                default:
                    if (mode != null && mode.startsWith("c_")) {
                        String parent = tune != null ? tune.optString("parent", "71") : "71";
                        if ("717".equals(parent)) parent = "71";
                        Recipe base = forMode(parent.startsWith("c_") ? "71" : parent, tune,
                                width, height, surround, center, lfe, head, lx, ly, distance);
                        int extra = tune != null ? Math.max(0, tune.optInt("spk", 8) - 8) : 0;
                        base.virt = (short) clamp(base.virt + extra * 8, 0, 820);
                        base.loudness = clamp(base.loudness + extra * 20, 0, 2500);
                        return base;
                    }
                    virt0 = 0;
            }
            if ("off".equals(mode) || mode == null) {
                r.virt = 0;
                r.bass = 0;
                r.loudness = 0;
                r.rev = PresetReverb.PRESET_NONE;
                r.eq = new int[]{0, 0, 0, 0, 0};
                return r;
            }
            int w = clamp(width, 0, 100);
            int s = clamp(surround, 0, 100);
            int lf = clamp(lfe, 0, 100);
            int h = clamp(height, 0, 100);
            int cr = tune != null ? clamp(tune.optInt("cross", 35), 0, 100) : 35;
            // 1000 is the hall. 820 is the widest headphone image that still stays dry.
            r.virt = (short) clamp(virt0 + (w - 60) * 4 + (s - 40) * 2 + (h - 30) * 2, 200, 820);
            if ("cross".equals(mode)) {
                r.virt = (short) clamp(40 + cr * 2, 0, 240);
            }
            r.bass = (short) clamp(bass0 + Math.max(0, lf - 50) * 4, 0, 400);
            r.rev = PresetReverb.PRESET_NONE;
            r.loudness = 0;
            if ("cross".equals(mode)) {
                r.eq = new int[]{80, 60, 40, -30, -90};
            } else if ("game".equals(mode)) {
                r.eq = new int[]{30, -90, 40, 180, 20};
            } else {
                r.eq = new int[]{120, -50, 0, 110, 80};
            }
            return r;
        }

        double dbAt(double hz) {
            double db = interp(PROF_HZ, eqAsDb(), hz);
            if (tune != null && tune.optInt("bypass", 0) == 0) {
                JSONArray geq = tune.optJSONArray("geq");
                if (geq != null && geq.length() >= 10) {
                    double[] g = new double[10];
                    for (int i = 0; i < 10; i++) g[i] = geq.optInt(i);
                    db += interp(GEQ_HZ, g, hz);
                }
                JSONArray peq = tune.optJSONArray("peq");
                if (peq != null) {
                    for (int i = 0; i < peq.length(); i++) {
                        JSONObject b = peq.optJSONObject(i);
                        if (b == null) continue;
                        db += shaped(hz, b.optString("t", "peak"),
                                b.optInt("f", 1000), b.optDouble("g", 0), b.optInt("q", 10) / 10.0);
                    }
                }
                db += tune.optInt("preamp", 0);
                db += restoreDb(hz);
                if (tune.optInt("voiceOn", 0) != 0) {
                    double g = tune.optInt("voice", 0) / 100.0 * 4.0;
                    db += peaking(hz, 2800, g, 1.1);
                }
            }
            return db;
        }

        /** Neutral restore: codec air only, plus a tiny wide low shelf so highs don't outrun bass. */
        double restoreDb(double hz) {
            if (tune == null) return 0;
            boolean aac = tune.optInt("dsee", 0) != 0;
            boolean sbcOn = tune.optInt("dseeSbc", 0) != 0;
            boolean ldacOn = tune.optInt("dseeLdac", 0) != 0;
            boolean aptxOn = tune.optInt("dseeAptx", 0) != 0;
            if (!aac && !sbcOn && !ldacOn && !aptxOn) return 0;
            String c = codec == null ? "" : codec.toLowerCase();
            if ("pcm".equals(c)) return 0;
            boolean isLdac = c.contains("ldac");
            boolean isAptx = c.contains("aptx");
            boolean isSbc = c.contains("sbc");
            boolean isAac = c.contains("aac");
            if (isLdac && ldacOn) return restoreLdac(hz);
            if (isAptx && aptxOn) return restoreAptx(hz);
            if (isSbc && sbcOn) return restoreSbc(hz);
            if (isAac && aac) return restoreAac(hz);
            int n = (aac ? 1 : 0) + (sbcOn ? 1 : 0) + (ldacOn ? 1 : 0) + (aptxOn ? 1 : 0);
            if (n == 1) {
                if (ldacOn) return restoreLdac(hz);
                if (aptxOn) return restoreAptx(hz);
                if (sbcOn) return restoreSbc(hz);
                return restoreAac(hz);
            }
            return 0;
        }

        /** Keeps bass:air ratio; far below the old +2.6 dB 160 Hz shelf that muddied. */
        static double restoreWeight(double hz) {
            return shelf(hz, 200, 0.45, 0.5, true);
        }

        static double restoreAac(double hz) {
            return restoreWeight(hz)
                    + shelf(hz, 15200, 0.75, 0.7, false)
                    + peaking(hz, 17500, 0.45, 0.8);
        }

        /** SBC joint-stereo bricks earlier than AAC (~14–16 kHz). Slightly more air, still bass-matched. */
        static double restoreSbc(double hz) {
            return restoreWeight(hz)
                    + shelf(hz, 11800, 1.1, 0.62, false)
                    + peaking(hz, 15500, 0.7, 0.68);
        }

        static double restoreLdac(double hz) {
            return restoreWeight(hz)
                    + shelf(hz, 16000, 0.55, 0.72, false)
                    + peaking(hz, 19000, 0.4, 0.75);
        }

        static double restoreAptx(double hz) {
            return restoreWeight(hz)
                    + shelf(hz, 14000, 0.7, 0.7, false)
                    + peaking(hz, 17000, 0.45, 0.8);
        }

        private double[] eqAsDb() {
            double[] d = new double[eq.length];
            for (int i = 0; i < eq.length; i++) d[i] = eq[i] / 100.0;
            return d;
        }

        static double interp(double[] hz, double[] db, double f) {
            if (f <= hz[0]) return db[0];
            if (f >= hz[hz.length - 1]) return db[db.length - 1];
            for (int i = 0; i < hz.length - 1; i++) {
                if (f <= hz[i + 1]) {
                    double a = Math.log(hz[i]);
                    double b = Math.log(hz[i + 1]);
                    double x = Math.log(Math.max(1, f));
                    double t = (x - a) / Math.max(1e-6, b - a);
                    return db[i] + t * (db[i + 1] - db[i]);
                }
            }
            return 0;
        }

        static boolean isPass(String t) {
            return "hp".equals(t) || "lp".equals(t) || "highpass".equals(t) || "lowpass".equals(t);
        }

        static double shaped(double f, String t, double f0, double gainDb, double q) {
            if (t == null) t = "peak";
            if ("ls".equals(t) || "lowshelf".equals(t)) return shelf(f, f0, gainDb, q, true);
            if ("hs".equals(t) || "highshelf".equals(t)) return shelf(f, f0, gainDb, q, false);
            if ("hp".equals(t) || "highpass".equals(t)) return pass(f, f0, q, true);
            if ("lp".equals(t) || "lowpass".equals(t)) return pass(f, f0, q, false);
            return peaking(f, f0, gainDb, q);
        }

        static double shelf(double f, double f0, double gainDb, double q, boolean low) {
            if (gainDb == 0 || f0 <= 0) return 0;
            double oct = Math.log(Math.max(1, f) / f0) / Math.log(2);
            double sigma = Math.max(0.18, 0.7 / Math.max(0.3, q));
            double x = (low ? oct : -oct) / sigma;
            return gainDb / (1.0 + Math.exp(x * 2.2));
        }

        static double pass(double f, double f0, double q, boolean high) {
            if (f0 <= 0) return 0;
            double w = Math.max(1e-6, f / f0);
            double qq = Math.max(0.3, q);
            double den = Math.sqrt(Math.pow(1 - w * w, 2) + Math.pow(w / qq, 2));
            double mag = high ? (w * w) / den : 1.0 / den;
            return 20 * Math.log10(Math.max(1e-4, mag));
        }

        static double peaking(double f, double f0, double gainDb, double q) {
            if (gainDb == 0 || f0 <= 0 || q <= 0) return 0;
            double oct = Math.log(Math.max(1, f) / f0) / Math.log(2);
            double sigma = Math.max(0.08, 0.6 / q);
            return gainDb * Math.exp(-0.5 * (oct * oct) / (sigma * sigma));
        }
    }

    /**
     * AUTO often treats A2DP as a speaker pair (transaural crosstalk) and
     * shrinks the stage vs dry stereo. Binaural is the headphone / TWS path.
     */
    private static void forceBinaural(Virtualizer v) {
        if (v == null || Build.VERSION.SDK_INT < 21) return;
        try {
            v.forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_BINAURAL);
        } catch (Throwable ignored) {
        }
    }

    private static final class Fx {
        Virtualizer virt;
        Equalizer eq;
        PresetReverb rev;
        EnvironmentalReverb env;
        BassBoost bass;
        LoudnessEnhancer loud;
        DynamicsProcessing dyn;
        int session;
        int lastVirt = -1;
        String eqSig = "";
        boolean dynHeld;

        static Fx open(int session) {
            Fx fx = new Fx();
            fx.session = session;
            try {
                fx.virt = new Virtualizer(0, session);
                forceBinaural(fx.virt);
            } catch (Throwable ignored) {
            }
            try {
                fx.eq = new Equalizer(0, session);
            } catch (Throwable ignored) {
            }
            try {
                fx.bass = new BassBoost(0, session);
            } catch (Throwable ignored) {
            }
            try {
                fx.loud = new LoudnessEnhancer(session);
            } catch (Throwable ignored) {
            }
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                            2, true, 1, true, 3, false, 0, true).build();
                    fx.dyn = new DynamicsProcessing(0, session, cfg);
                } catch (Throwable ignored) {
                }
            }
            if (fx.virt == null && fx.eq == null && fx.rev == null && fx.bass == null && fx.loud == null && fx.dyn == null) {
                return null;
            }
            return fx;
        }

        void flat() {
            update(null, false, false, false, false, true);
        }

        void release() {
            try { if (virt != null) virt.setEnabled(false); } catch (Throwable ignored) {}
            try { if (eq != null) eq.setEnabled(false); } catch (Throwable ignored) {}
            try { if (rev != null) rev.setEnabled(false); } catch (Throwable ignored) {}
            try { if (env != null) env.setEnabled(false); } catch (Throwable ignored) {}
            try { if (bass != null) bass.setEnabled(false); } catch (Throwable ignored) {}
            try { if (loud != null) loud.setEnabled(false); } catch (Throwable ignored) {}
            try { if (dyn != null) dyn.setEnabled(false); } catch (Throwable ignored) {}
            try { if (virt != null) virt.release(); } catch (Throwable ignored) {}
            try { if (eq != null) eq.release(); } catch (Throwable ignored) {}
            try { if (rev != null) rev.release(); } catch (Throwable ignored) {}
            try { if (env != null) env.release(); } catch (Throwable ignored) {}
            try { if (bass != null) bass.release(); } catch (Throwable ignored) {}
            try { if (loud != null) loud.release(); } catch (Throwable ignored) {}
            try { if (dyn != null) dyn.release(); } catch (Throwable ignored) {}
            virt = null;
            eq = null;
            rev = null;
            env = null;
            bass = null;
            loud = null;
            dyn = null;
        }

        /** Phone-side room. Zero size stays dry so the other profiles do not go hall-like. */
        void applyRoom(Recipe r, boolean on) {
            int room = r == null || r.tune == null ? 0 : clamp(r.tune.optInt("room", 0), 0, 100);
            if (!on || room <= 0) {
                try { if (env != null && env.getEnabled()) env.setEnabled(false); } catch (Throwable ignored) {}
                return;
            }
            if (env == null) {
                try { env = new EnvironmentalReverb(0, session); } catch (Throwable ignored) { return; }
            }
            int reflect = r.tune == null ? 20 : clamp(r.tune.optInt("reflect", 20), 0, 100);
            int decay = r.tune == null ? 20 : clamp(r.tune.optInt("decay", 20), 0, 100);
            int decayMs = 160 + decay * 14 + room * 8;
            try {
                env.setDecayTime(decayMs);
                env.setDensity((short) (200 + room * 6));
                env.setDiffusion((short) (150 + reflect * 7));
                env.setRoomLevel((short) (-2800 + room * 10));
                env.setRoomHFLevel((short) (-4000 + reflect * 25));
                env.setReflectionsLevel((short) (-5500 + reflect * 30));
                env.setReflectionsDelay(4 + room / 10);
                env.setReverbLevel((short) (-4500 + room * 16));
                env.setReverbDelay(8 + decay / 8);
                if (!env.getEnabled()) env.setEnabled(true);
            } catch (Throwable ignored) {
            }
        }

        void update(Recipe r, boolean spatialOn, boolean eqOn, boolean speaker, boolean sub, boolean force) {
            if (virt != null) {
                try {
                    boolean on = spatialOn && r != null && !speaker;
                    if (on) {
                        boolean commit = force || !virt.getEnabled() || lastVirt != r.virt;
                        if (!commit) {
                            if (!virt.getEnabled()) virt.setEnabled(true);
                        } else {
                            if (virt.getEnabled()) virt.setEnabled(false);
                            forceBinaural(virt);
                            if (virt.getStrengthSupported()) virt.setStrength(r.virt);
                            forceBinaural(virt);
                            virt.setEnabled(true);
                            lastVirt = r.virt;
                        }
                    } else if (virt.getEnabled()) {
                        virt.setEnabled(false);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (eq != null) {
                try {
                    if (eqOn && r != null) {
                        short n = eq.getNumberOfBands();
                        short[] rng = eq.getBandLevelRange();
                        StringBuilder sig = new StringBuilder();
                        short[] levels = new short[n];
                        if (rng != null && rng.length >= 2) {
                            for (short b = 0; b < n; b++) {
                                double hz = 1000;
                                try { hz = eq.getCenterFreq(b) / 1000.0; } catch (Throwable ignored) {}
                                int mb = (int) Math.round(r.dbAt(hz) * 100);
                                levels[b] = (short) clamp(mb, rng[0], rng[1]);
                                sig.append(levels[b]).append(',');
                            }
                        }
                        String next = sig.toString();
                        if (!force && eq.getEnabled() && next.equals(eqSig)) {
                            // Same curve. Toggling the effect off and on is the hitch.
                        } else {
                            if (eq.getEnabled()) eq.setEnabled(false);
                            for (short b = 0; b < n && rng != null; b++) eq.setBandLevel(b, levels[b]);
                            eq.setEnabled(true);
                            eqSig = next;
                        }
                    } else if (eq.getEnabled()) {
                        eq.setEnabled(false);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (rev != null) {
                try {
                    if (rev.getEnabled()) rev.setEnabled(false);
                } catch (Throwable ignored) {
                }
            }
            applyRoom(r, spatialOn && !speaker);
            if (bass != null) {
                try {
                    int user = 0;
                    if (r != null && r.tune != null && r.tune.optInt("bassOn", 0) != 0) {
                        user = clamp(r.tune.optInt("bass", 0), 0, 100) * 10;
                    }
                    boolean on = user > 0 || (spatialOn && speaker && r != null && r.bass > 40);
                    int str = user;
                    if (spatialOn && speaker && r != null) str = Math.max(str, r.bass);
                    if (on) {
                        if (force && bass.getEnabled()) bass.setEnabled(false);
                        if (bass.getStrengthSupported()) bass.setStrength((short) clamp(str, 0, 1000));
                        if (!bass.getEnabled()) bass.setEnabled(true);
                    } else if (bass.getEnabled()) {
                        bass.setEnabled(false);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (loud != null) {
                try {
                    int gain = spatialOn && r != null ? r.loudness : 0;
                    if (spatialOn && gain > 0) {
                        if (force && loud.getEnabled()) loud.setEnabled(false);
                        loud.setTargetGain(gain);
                        if (!loud.getEnabled()) loud.setEnabled(true);
                    } else {
                        loud.setTargetGain(0);
                        if (loud.getEnabled()) loud.setEnabled(false);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (dyn != null && Build.VERSION.SDK_INT >= 28) {
                try {
                    boolean movie = spatialOn && speaker && r != null && r.loudness >= 500;
                    boolean hold = movie || eqOn || (spatialOn && r != null);
                    if (hold && !movie && dynHeld && !force) {
                        // Already the dry passthrough. Don't mute and rebuild it.
                    } else if (hold) {
                        if (dyn.getEnabled()) dyn.setEnabled(false);
                        if (movie) {
                            armMovie(speaker);
                            keepBass();
                            dynHeld = false;
                        } else {
                            passthrough();
                            dynHeld = true;
                        }
                        if (!dyn.getEnabled()) dyn.setEnabled(true);
                    } else {
                        dynHeld = false;
                        dyn.setInputGainAllChannelsTo(0f);
                        if (dyn.getEnabled()) dyn.setEnabled(false);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        /** No compression, no extra gain. Only keeps the mix in the effect path. */
        private void passthrough() {
            dyn.setInputGainAllChannelsTo(0f);
            try {
                DynamicsProcessing.EqBand low = new DynamicsProcessing.EqBand(true, 20f, 0f);
                dyn.setPreEqBandAllChannelsTo(0, low);
            } catch (Throwable ignored) {
            }
            float[] cuts = {250f, 2500f, 20000f};
            for (int i = 0; i < 3; i++) {
                DynamicsProcessing.MbcBand band = new DynamicsProcessing.MbcBand(
                        true, cuts[i], 5f, 80f, 1f, 0f, 0f, -90f, 1f, 0f, 0f);
                dyn.setMbcBandAllChannelsTo(i, band);
            }
            DynamicsProcessing.Limiter lim = new DynamicsProcessing.Limiter(
                    true, true, 0, 1f, 60f, 10f, -2f, 0f);
            dyn.setLimiterAllChannelsTo(lim);
        }

        private void armMovie(boolean speaker) {
            float in = speaker ? 3f : 1.5f;
            dyn.setInputGainAllChannelsTo(in);
            float[] cuts = {250f, 2500f, 20000f};
            float ratio = speaker ? 1.8f : 1.35f;
            float thresh = speaker ? -14f : -16f;
            float post = 0f;
            for (int i = 0; i < 3; i++) {
                DynamicsProcessing.MbcBand band = new DynamicsProcessing.MbcBand(
                        true, cuts[i], 12f, 120f, ratio, thresh, 3f, -90f, 1f, 0f, post);
                dyn.setMbcBandAllChannelsTo(i, band);
            }
            DynamicsProcessing.Limiter lim = new DynamicsProcessing.Limiter(
                    true, true, 0, 5f, 80f, 8f, -1.5f, 0f);
            dyn.setLimiterAllChannelsTo(lim);
            if (!dyn.getEnabled()) dyn.setEnabled(true);
        }

        /** Virtualizer thins the low end. One shelf puts weight back without a boom. */
        private void keepBass() {
            try {
                DynamicsProcessing.EqBand low = new DynamicsProcessing.EqBand(true, 160f, 3.2f);
                dyn.setPreEqBandAllChannelsTo(0, low);
            } catch (Throwable ignored) {
            }
        }
    }
}
