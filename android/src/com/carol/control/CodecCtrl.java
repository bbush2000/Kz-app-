package com.carol.control;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Bluetooth A2DP codec / rate / bit-depth, plus USB PCM format.
 * Prefs are per catalog model so one set never copies onto another.
 */
final class CodecCtrl {
    private final Context ctx;
    private BluetoothA2dp a2dp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean ldacRetryPosted;
    private static CodecCtrl inst;

    static synchronized CodecCtrl get(Context ctx) {
        if (inst == null) inst = new CodecCtrl(ctx.getApplicationContext());
        return inst;
    }

    private CodecCtrl(Context ctx) {
        this.ctx = ctx;
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad != null) {
                ad.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile == BluetoothProfile.A2DP) a2dp = (BluetoothA2dp) proxy;
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        if (profile == BluetoothProfile.A2DP) a2dp = null;
                    }
                }, BluetoothProfile.A2DP);
            }
        } catch (Exception ignored) {
        }
        migrateLdacQuality();
    }

    String status(String model) {
        JSONObject o = new JSONObject();
        KzCatalog.M m = model == null ? null : findModel(model);
        try {
            o.put("model", model == null ? "" : model);
            o.put("usb", m != null && "usb".equals(m.kind));
            putCaps(o, m);
            BluetoothDevice d = liveDevice();
            if (d != null) {
                o.put("device", d.getName() == null ? "" : d.getName());
                fillBt(o, d, m);
            } else if (m != null && "usb".equals(m.kind)) {
                fillUsb(o);
            }
            SharedPreferences p = prefs();
            String key = "codec_" + (model == null ? "kz" : model);
            if (p.contains(key)) {
                o.put("want", new JSONObject(p.getString(key, "{}")));
                o.put("saved", true);
            } else {
                o.put("want", defaultWant(m, o));
                o.put("saved", false);
            }
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    String apply(String model, String codec, int rate, int bits, int quality) {
        JSONObject out = new JSONObject();
        try {
            JSONObject want = new JSONObject();
            want.put("codec", codec);
            want.put("rate", rate);
            want.put("bits", bits);
            want.put("quality", quality);
            prefs().edit().putString("codec_" + (model == null ? "kz" : model), want.toString()).apply();
            UserState.markDirty();
            try { UserState.cacheCodec(ctx, codec); } catch (Throwable ignored) {}
            KzCatalog.M m = findModel(model);
            if (m != null && "usb".equals(m.kind)) {
                UsbDac.get(ctx).setFormat(rate, bits);
                out.put("ok", true);
                out.put("msg", "USB exclusive will use " + rate + " Hz / " + bits + "-bit");
            } else {
                BluetoothDevice d = liveDevice();
                if (d == null) {
                    out.put("ok", false);
                    out.put("msg", "Connect the headset on media audio first.");
                } else {
                    boolean ok = setBt(d, codec, rate, bits, quality);
                    SpatialFx.get(ctx).setBtCodec(codec);
                    out.put("ok", ok);
                    out.put("msg", ok ? "Asked the stack for " + label(codec, rate, bits)
                            : "Phone refused. Try Developer options → Bluetooth audio codec.");
                }
            }
            JSONObject st = new JSONObject(status(model));
            st.put("ok", out.optBoolean("ok"));
            st.put("msg", out.optString("msg"));
            return st.toString();
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", String.valueOf(e));
            } catch (Exception ignored) {
            }
            return out.toString();
        }
    }

    /** Dual-connect + LDAC stalls. Switch this model to AAC/aptX without touching other devices. */
    String dropLdac(String model) {
        JSONObject st;
        try {
            st = new JSONObject(status(model));
        } catch (Exception e) {
            return status(model);
        }
        JSONObject want = st.optJSONObject("want");
        String codec = want != null ? want.optString("codec", "") : st.optString("codec", "");
        if (!"ldac".equalsIgnoreCase(codec)) return st.toString();
        String next = "aac";
        JSONArray sel = st.optJSONArray("selectable");
        if (sel != null) {
            for (int i = 0; i < sel.length(); i++) {
                String c = sel.optString(i);
                if ("aptxhd".equals(c) || "aptx".equals(c) || "aac".equals(c)) {
                    next = c;
                    if (!"aac".equals(c)) break;
                }
            }
        } else {
            KzCatalog.M m = findModel(model);
            if (m != null && m.aptx) next = "aptx";
        }
        int rate = want != null ? want.optInt("rate", 44100) : 44100;
        if (rate > 48000) rate = 48000;
        return apply(model, next, rate, 16, 1000);
    }

    private void putCaps(JSONObject o, KzCatalog.M m) throws Exception {
        o.put("codecs", codecsOf(m));
        JSONArray r = new JSONArray();
        for (int x : ratesOf(m)) r.put(x);
        o.put("rates", r);
        JSONArray b = new JSONArray();
        for (int x : bitsOf(m)) b.put(x);
        o.put("bits", b);
    }

    static String codecsOf(KzCatalog.M m) {
        if (m == null) return "sbc,aac";
        if ("usb".equals(m.kind) || "iem".equals(m.kind)) return "pcm";
        StringBuilder s = new StringBuilder("sbc,aac");
        if (m.ldac) s.append(",ldac");
        if (m.aptx) {
            if ("krio".equals(m.id) || "xz10".equals(m.id) || "az20".equals(m.id)
                    || "az09_pro".equals(m.id) || "sk10_pro".equals(m.id) || "ae01_pro".equals(m.id))
                s.append(",aptx,aptxhd,aptxadaptive");
            else if ("az15".equals(m.id)) s.append(",aptx,aptxhd");
            else s.append(",aptx");
        }
        return s.toString();
    }

    static int[] ratesOf(KzCatalog.M m) {
        if (m != null && "usb".equals(m.kind)) {
            if ("am01".equals(m.id) || "am02".equals(m.id))
                return new int[]{44100, 48000, 88200, 96000, 176400, 192000, 384000};
            if ("am03".equals(m.id))
                return new int[]{44100, 48000, 88200, 96000};
            return new int[]{44100, 48000, 96000, 192000};
        }
        if (m != null && m.ldac) return new int[]{44100, 48000, 88200, 96000};
        if (m != null && "xz10".equals(m.id)) return new int[]{44100, 48000, 96000};
        return new int[]{44100, 48000};
    }

    static int[] bitsOf(KzCatalog.M m) {
        if (m != null && "usb".equals(m.kind)) return new int[]{16, 24, 32};
        if (m != null && m.ldac) return new int[]{16, 24, 32};
        if (m != null && (m.aptx && ("krio".equals(m.id) || "xz10".equals(m.id) || "az20".equals(m.id)
                || "az09_pro".equals(m.id) || "sk10_pro".equals(m.id) || "ae01_pro".equals(m.id)
                || "az15".equals(m.id))))
            return new int[]{16, 24, 32};
        if (m != null && m.aptx) return new int[]{16};
        return new int[]{16};
    }

    static KzCatalog.M findModel(String id) {
        if (id == null) return null;
        for (KzCatalog.M m : KzCatalog.ALL) if (id.equals(m.id)) return m;
        return KzCatalog.of(id);
    }

    private JSONObject defaultWant(KzCatalog.M m, JSONObject st) throws Exception {
        JSONObject w = new JSONObject();
        String cur = st.optString("codec", "");
        JSONArray sel = st.optJSONArray("selectable");
        String codecs = codecsOf(m);
        String pick = null;
        if (allowed(cur, codecs, sel)) pick = cur;
        else if (m != null && m.ldac && allowed("ldac", codecs, sel)) pick = "ldac";
        else if (m != null && m.aptx) {
            if (allowed("aptxadaptive", codecs, sel)) pick = "aptxadaptive";
            else if (allowed("aptxhd", codecs, sel)) pick = "aptxhd";
            else if (allowed("aptx", codecs, sel)) pick = "aptx";
        }
        if (pick == null) {
            if (m != null && "usb".equals(m.kind)) pick = "pcm";
            else if (allowed("aac", codecs, sel)) pick = "aac";
            else pick = "sbc";
        }
        boolean same = pick.equals(cur);
        int rate = same ? st.optInt("rate", 0) : 0;
        int bits = same ? st.optInt("bits", 0) : 0;
        int[] rates = ratesOf(m);
        int[] bitOpts = bitsOf(m);
        JSONArray stackR = st.optJSONArray("stackRates");
        JSONArray stackB = st.optJSONArray("stackBits");
        if (!inArr(rates, rate) || !inStack(stackR, rate)) {
            rate = preferRate(pick, rates, stackR);
        }
        if (!inArr(bitOpts, bits) || !inStack(stackB, bits)) {
            bits = preferBits(pick, bitOpts, stackB);
        }
        w.put("codec", pick);
        w.put("rate", rate);
        w.put("bits", bits);
        w.put("quality", st.optLong("quality", 1000));
        return w;
    }

    private static boolean allowed(String codec, String codecs, JSONArray sel) {
        if (codec == null || codec.length() == 0) return false;
        if (codecs != null && ("," + codecs + ",").contains("," + codec + ",")) return true;
        return sel != null && contains(sel, codec);
    }

    private static boolean inArr(int[] a, int v) {
        if (v <= 0) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static boolean inStack(JSONArray a, int v) {
        if (a == null || a.length() == 0) return true;
        return containsInt(a, v);
    }

    private static int preferRate(String codec, int[] rates, JSONArray stack) {
        int[] pref;
        if ("ldac".equals(codec)) pref = new int[]{96000, 48000, 44100, 88200};
        else if ("pcm".equals(codec)) pref = new int[]{48000, 96000, 44100, 192000};
        else pref = new int[]{48000, 44100};
        for (int p : pref) {
            if (inArr(rates, p) && inStack(stack, p)) return p;
        }
        for (int r : rates) if (inStack(stack, r)) return r;
        return rates.length > 0 ? rates[0] : 44100;
    }

    private static int preferBits(String codec, int[] bits, JSONArray stack) {
        int[] pref;
        if ("ldac".equals(codec) || "aptxhd".equals(codec) || "aptxadaptive".equals(codec) || "pcm".equals(codec))
            pref = new int[]{32, 24, 16};
        else pref = new int[]{16};
        for (int p : pref) {
            if (inArr(bits, p) && inStack(stack, p)) return p;
        }
        for (int b : bits) if (inStack(stack, b)) return b;
        return bits.length > 0 ? bits[0] : 16;
    }

    private BluetoothDevice liveDevice() {
        if (a2dp == null) return null;
        try {
            BluetoothDevice named = null;
            BluetoothDevice any = null;
            for (BluetoothDevice d : a2dp.getConnectedDevices()) {
                if (d == null) continue;
                any = d;
                if (KzCatalog.likely(d.getName())) named = d;
            }
            return named != null ? named : any;
        } catch (Exception e) {
            return null;
        }
    }

    private void fillUsb(JSONObject o) throws Exception {
        o.put("codec", "pcm");
        AudioDeviceInfo usb = UsbDac.get(ctx).findUsb();
        JSONArray sr = new JSONArray();
        JSONArray en = new JSONArray();
        if (usb != null && Build.VERSION.SDK_INT >= 23) {
            int[] rates = usb.getSampleRates();
            if (rates != null) for (int r : rates) if (r > 0) sr.put(r);
            int[] enc = usb.getEncodings();
            if (enc != null) {
                for (int e : enc) {
                    if (e == AudioFormat.ENCODING_PCM_16BIT) en.put(16);
                    if (Build.VERSION.SDK_INT >= 21 && e == AudioFormat.ENCODING_PCM_FLOAT) en.put(32);
                    if (Build.VERSION.SDK_INT >= 31 && e == AudioFormat.ENCODING_PCM_24BIT_PACKED) en.put(24);
                }
            }
            CharSequence n = Build.VERSION.SDK_INT >= 28 ? usb.getProductName() : null;
            o.put("current", (n == null ? "USB DAC" : n.toString()) + " PCM");
        }
        o.put("selectable", new JSONArray().put("pcm"));
        o.put("stackRates", sr);
        o.put("stackBits", en);
        o.put("rate", UsbDac.get(ctx).sampleRate());
        o.put("bits", UsbDac.get(ctx).bitDepth());
    }

    private void fillBt(JSONObject o, BluetoothDevice d, KzCatalog.M m) {
        try {
            Object status = invoke(a2dp, "getCodecStatus", new Class[]{BluetoothDevice.class}, d);
            if (status == null) {
                o.put("msg", "This Android build hides A2DP codec control.");
                return;
            }
            Object cfg = invoke(status, "getCodecConfig", new Class[]{}, (Object[]) null);
            if (cfg != null) {
                int type = ((Number) invoke(cfg, "getCodecType", new Class[]{}, (Object[]) null)).intValue();
                int rate = bitsToRate(intVal(invoke(cfg, "getSampleRate", new Class[]{}, (Object[]) null)));
                int bits = bitsToDepth(intVal(invoke(cfg, "getBitsPerSample", new Class[]{}, (Object[]) null)));
                long spec1 = longVal(invoke(cfg, "getCodecSpecific1", new Class[]{}, (Object[]) null));
                String name = codecName(type);
                if (spec1 >= 1 && spec1 <= 3) spec1 = 1000 + spec1;
                else if (spec1 < 1000) spec1 = 1003;
                o.put("codec", name);
                o.put("rate", rate);
                o.put("bits", bits);
                o.put("quality", spec1);
                o.put("current", label(name, rate, bits));
                SpatialFx.get(ctx).setBtCodec(name);
                try { UserState.cacheCodec(ctx, name); } catch (Throwable ignored) {}
            }
            Object caps = invoke(status, "getCodecsSelectableCapabilities", new Class[]{}, (Object[]) null);
            JSONArray sel = new JSONArray();
            JSONArray stackRates = new JSONArray();
            JSONArray stackBits = new JSONArray();
            if (caps instanceof Object[]) {
                for (Object c : (Object[]) caps) {
                    if (c == null) continue;
                    int type = intVal(invoke(c, "getCodecType", new Class[]{}, (Object[]) null));
                    String name = codecName(type);
                    if (!contains(sel, name)) sel.put(name);
                    int rf = intVal(invoke(c, "getSampleRate", new Class[]{}, (Object[]) null));
                    for (int hz : new int[]{44100, 48000, 88200, 96000, 176400, 192000}) {
                        if ((rf & rateFlag(hz)) != 0 && !containsInt(stackRates, hz)) stackRates.put(hz);
                    }
                    int bf = intVal(invoke(c, "getBitsPerSample", new Class[]{}, (Object[]) null));
                    for (int b : new int[]{16, 24, 32}) {
                        if ((bf & bitsFlag(b)) != 0 && !containsInt(stackBits, b)) stackBits.put(b);
                    }
                }
            }
            o.put("selectable", sel);
            o.put("stackRates", stackRates);
            o.put("stackBits", stackBits);
        } catch (Exception e) {
            try {
                o.put("msg", "Codec API not open on this phone.");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * AOSP Developer options LDAC playback quality:
     * 1000 = Prefer sound quality / Optimized for Audio Quality (990/909)
     * 1001 = Balanced (660/606)
     * 1002 = Prefer connection (330/303)
     * 1003 = Best Effort (ABR)
     * Encoder uses codecSpecific1 % 10 → 0 HIGH, 1 MID, 2 LOW, 3 ABR.
     */
    private boolean setBt(BluetoothDevice d, String codec, int rate, int bits, int quality) {
        return setBt(d, codec, rate, bits, quality, true);
    }

    private boolean setBt(BluetoothDevice d, String codec, int rate, int bits, int quality, boolean scheduleRetry) {
        try {
            int type = codecType(codec);
            quality = ldacSpecific1(codec, quality);
            enableOptional(d, type);
            Object cfg = buildConfig(type, rateFlag(rate), bitsFlag(bits), quality);
            if (cfg == null) return false;
            try {
                invoke(a2dp, "setCodecConfigPreference",
                        new Class[]{BluetoothDevice.class, cfg.getClass()}, d, cfg);
            } catch (Exception e) {
                Class<?> bcc = Class.forName("android.bluetooth.BluetoothCodecConfig");
                invoke(a2dp, "setCodecConfigPreference",
                        new Class[]{BluetoothDevice.class, bcc}, d, cfg);
            }
            if ("ldac".equalsIgnoreCase(codec)) {
                writeLdacDevOptions(quality);
                if (scheduleRetry && !ldacRetryPosted) {
                    ldacRetryPosted = true;
                    final BluetoothDevice dev = d;
                    final String c = codec;
                    final int r = rate, b = bits, q = quality;
                    h.postDelayed(() -> {
                        ldacRetryPosted = false;
                        try {
                            setBt(dev, c, r, b, q, false);
                        } catch (Exception ignored) {
                        }
                    }, 650);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void enableOptional(BluetoothDevice d, int type) {
        if (type == 0) return;
        try {
            invoke(a2dp, "enableOptionalCodecs", new Class[]{BluetoothDevice.class}, d);
        } catch (Exception ignored) {
        }
        try {
            invoke(a2dp, "setOptionalCodecsEnabled",
                    new Class[]{BluetoothDevice.class, int.class}, d, 1);
        } catch (Exception ignored) {
        }
    }

    private static int ldacSpecific1(String codec, int quality) {
        if (codec == null || !"ldac".equalsIgnoreCase(codec)) return quality;
        if (quality == 990 || quality == 909) return 1000;
        if (quality == 660 || quality == 606) return 1001;
        if (quality == 330 || quality == 303) return 1002;
        if (quality == 1000 || quality == 1001 || quality == 1002 || quality == 1003) return quality;
        if (quality >= 0 && quality <= 3) return 1000 + quality;
        return 1000;
    }

    /** Invert 2.23.2 UI values (1003 was labelled 990 but AOSP 1003 is Best Effort). */
    private void migrateLdacQuality() {
        SharedPreferences p = prefs();
        if (p.getBoolean("ldac_qmap_aosp", false)) return;
        SharedPreferences.Editor e = p.edit().putBoolean("ldac_qmap_aosp", true);
        for (String k : p.getAll().keySet()) {
            if (k == null || !k.startsWith("codec_")) continue;
            try {
                JSONObject w = new JSONObject(p.getString(k, "{}"));
                if (!"ldac".equalsIgnoreCase(w.optString("codec"))) continue;
                int q = w.optInt("quality", 1000);
                int nq = q;
                if (q == 1000) nq = 1003;
                else if (q == 1001) nq = 1002;
                else if (q == 1002) nq = 1001;
                else if (q == 1003) nq = 1000;
                if (nq != q) {
                    w.put("quality", nq);
                    e.putString(k, w.toString());
                }
            } catch (Exception ignored) {
            }
        }
        e.apply();
    }

    private void writeLdacDevOptions(int specific1) {
        int index = specific1 - 1000;
        if (index < 0) index = 0;
        if (index > 3) index = 3;
        android.content.ContentResolver cr = ctx.getContentResolver();
        putSetting(cr, "bluetooth_a2dp_ldac_playback_quality", index);
        putSetting(cr, "bluetooth_select_a2dp_ldac_playback_quality", index);
        putSetting(cr, "bluetooth_ldac_playback_quality", index);
        putSetting(cr, "bluetooth_ldac_quality", index);
        putSetting(cr, "bluetooth_a2dp_src_ldac_playback_quality", specific1);
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class)
                    .invoke(null, "persist.bluetooth.a2dp_ldac.default_quality_mode", String.valueOf(index));
        } catch (Throwable ignored) {
        }
    }

    private static void putSetting(android.content.ContentResolver cr, String key, int v) {
        try { Settings.Global.putInt(cr, key, v); } catch (Throwable ignored) {}
        try { Settings.System.putInt(cr, key, v); } catch (Throwable ignored) {}
        try { Settings.Secure.putInt(cr, key, v); } catch (Throwable ignored) {}
    }

    private Object buildConfig(int type, int rateFlag, int bitsFlag, int quality) throws Exception {
        Class<?> bcc = Class.forName("android.bluetooth.BluetoothCodecConfig");
        try {
            Class<?> builderCl = Class.forName("android.bluetooth.BluetoothCodecConfig$Builder");
            Object b = builderCl.getConstructor().newInstance();
            builderCl.getMethod("setCodecType", int.class).invoke(b, type);
            builderCl.getMethod("setCodecPriority", int.class).invoke(b, 1000000);
            builderCl.getMethod("setSampleRate", int.class).invoke(b, rateFlag);
            builderCl.getMethod("setBitsPerSample", int.class).invoke(b, bitsFlag);
            builderCl.getMethod("setChannelMode", int.class).invoke(b, 2);
            try {
                builderCl.getMethod("setCodecSpecific1", long.class).invoke(b, (long) quality);
            } catch (Exception e) {
                try {
                    builderCl.getMethod("setCodecSpecific1", int.class).invoke(b, quality);
                } catch (Exception ignored) {
                }
            }
            try { builderCl.getMethod("setCodecSpecific2", long.class).invoke(b, 0L); } catch (Exception ignored) {}
            try { builderCl.getMethod("setCodecSpecific3", long.class).invoke(b, 0L); } catch (Exception ignored) {}
            try { builderCl.getMethod("setCodecSpecific4", long.class).invoke(b, 0L); } catch (Exception ignored) {}
            return builderCl.getMethod("build").invoke(b);
        } catch (Exception ignored) {
        }
        for (Constructor<?> c : bcc.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length >= 9 && p[0] == int.class) {
                c.setAccessible(true);
                return c.newInstance(type, 1000000, rateFlag, bitsFlag, 2,
                        (long) quality, 0L, 0L, 0L);
            }
        }
        return null;
    }

    private static String codecName(int t) {
        switch (t) {
            case 0:
                return "sbc";
            case 1:
                return "aac";
            case 2:
                return "aptx";
            case 3:
                return "aptxhd";
            case 4:
                return "ldac";
            case 5:
                return "lc3";
            case 6:
                return "aptxadaptive";
            case 7:
                return "opus";
            default:
                return "codec" + t;
        }
    }

    private static int codecType(String n) {
        if (n == null) return 1;
        switch (n.toLowerCase(Locale.US)) {
            case "sbc":
                return 0;
            case "aac":
                return 1;
            case "aptx":
                return 2;
            case "aptxhd":
            case "aptx_hd":
                return 3;
            case "ldac":
                return 4;
            case "lc3":
                return 5;
            case "aptxadaptive":
            case "aptx_adaptive":
                return 6;
            case "opus":
                return 7;
            default:
                return 1;
        }
    }

    static String label(String codec, int rate, int bits) {
        String n = codec == null ? "?" : codec.toUpperCase(Locale.US);
        if ("aptxhd".equalsIgnoreCase(codec)) n = "aptX HD";
        if ("aptxadaptive".equalsIgnoreCase(codec)) n = "aptX Adaptive";
        if ("aptx".equalsIgnoreCase(codec)) n = "aptX";
        if ("pcm".equalsIgnoreCase(codec)) n = "PCM";
        String r = rate >= 1000 ? (rate / 1000.0) + " kHz" : "?";
        if (rate == 44100) r = "44.1 kHz";
        if (rate == 88200) r = "88.2 kHz";
        if (rate == 176400) r = "176.4 kHz";
        return n + " · " + r + " · " + bits + "-bit";
    }

    private static int rateFlag(int hz) {
        switch (hz) {
            case 44100:
                return 1;
            case 48000:
                return 2;
            case 88200:
                return 4;
            case 96000:
                return 8;
            case 176400:
                return 16;
            case 192000:
                return 32;
            default:
                return 2;
        }
    }

    private static int bitsFlag(int b) {
        if (b >= 32) return 4;
        if (b >= 24) return 2;
        return 1;
    }

    private static int bitsToRate(int flag) {
        if ((flag & 32) != 0) return 192000;
        if ((flag & 16) != 0) return 176400;
        if ((flag & 8) != 0) return 96000;
        if ((flag & 4) != 0) return 88200;
        if ((flag & 2) != 0) return 48000;
        if ((flag & 1) != 0) return 44100;
        return 44100;
    }

    private static int bitsToDepth(int flag) {
        if ((flag & 4) != 0) return 32;
        if ((flag & 2) != 0) return 24;
        return 16;
    }

    private static boolean contains(JSONArray a, String s) {
        for (int i = 0; i < a.length(); i++) if (s.equals(a.optString(i))) return true;
        return false;
    }

    private static boolean containsInt(JSONArray a, int v) {
        for (int i = 0; i < a.length(); i++) if (a.optInt(i) == v) return true;
        return false;
    }

    private static int intVal(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static long longVal(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        if (target == null) return null;
        Class<?> cl = target.getClass();
        while (cl != null) {
            try {
                Method m = cl.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException e) {
                cl = cl.getSuperclass();
            }
        }
        Method m = target.getClass().getMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("carol_ble", Context.MODE_PRIVATE);
    }
}
