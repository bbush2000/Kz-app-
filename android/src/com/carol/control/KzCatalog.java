package com.carol.control;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Every Knowledge Zenith Bluetooth product we know: TWS, earhooks, neckbands.
 * Phone-side spatial / EQ / bandwidth restore / mic / fit / last-seen apply to all.
 * JieLi firmware (ANC VoiceMode, remap, find-tone, LDAC flag) only when jl or the chip answers.
 * Never rewrite the ANC table.
 */
final class KzCatalog {
    static final class M {
        final String id, label, kind;
        final String[] tokens;
        final boolean anc, ldac, aptx, game, jl;

        M(String id, String label, String kind, String tokens, boolean anc, boolean ldac,
          boolean aptx, boolean game, boolean jl) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.tokens = tokens.split(",");
            this.anc = anc;
            this.ldac = ldac;
            this.aptx = aptx;
            this.game = game;
            this.jl = jl;
        }
    }

    /** Most specific tokens first. */
    static final M[] ALL = {
            new M("carol_pro", "Carol Pro", "tws", "carol pro,carol-pro,carolpro", true, true, false, true, true),
            new M("krio", "Krio", "tws", "krio,kz krio", true, true, true, true, true),
            new M("sora", "Sora", "tws", "sora,kz sora,kz-sora,kzsora", false, false, false, true, false),
            new M("xtra_pro", "Xtra Pro", "tws", "xtra pro,xtrapro", true, true, false, true, true),
            new M("z1_pro", "Z1 Pro", "tws", "z1 pro,z1pro", false, false, false, true, false),
            new M("sa08_pro", "SA08 Pro", "tws", "sa08 pro,sa08pro", false, false, false, true, false),
            new M("sk10_pro", "SK10 Pro", "tws", "sk10 pro,sk10pro", false, false, true, true, false),
            new M("az09_pro", "AZ09 Pro", "hook", "az09 pro,az09pro", false, false, true, false, false),
            new M("ae01_pro", "AE01 Pro", "hook", "ae01 pro,ae01pro", true, true, true, true, false),
            new M("carol", "Carol", "tws", "carol", true, false, false, false, false),
            new M("xtra", "Xtra", "tws", "xtra", true, false, false, true, true),
            new M("s1d", "S1D", "tws", "s1d", false, false, false, false, false),
            new M("s2", "S2", "tws", "kz s2,s2", false, false, false, true, false),
            new M("s1", "S1", "tws", "kz s1,s1", false, false, false, true, false),
            new M("z3", "Z3", "tws", "kz z3,z3", false, false, true, true, false),
            new M("z1", "Z1", "tws", "kz z1,z1", false, false, false, true, false),
            new M("sk10", "SK10", "tws", "sk10", false, false, false, true, false),
            new M("sa08", "SA08", "tws", "sa08", false, false, false, false, false),
            new M("sks", "SKS", "tws", "kz sks,sks", false, false, false, false, false),
            new M("e10", "E10", "tws", "kz e10,e10", false, false, false, false, false),
            new M("gp20", "GP20", "tws", "gp20", false, false, false, true, false),
            new M("vx10", "VX10", "tws", "vx10", false, false, false, true, false),
            new M("vxs", "VXS", "tws", "kz vxs,vxs", false, false, false, false, false),
            new M("t10", "T10", "tws", "kz t10,t10", false, false, false, false, false),
            new M("t1", "T1", "tws", "kz t1,t1", false, false, false, false, false),
            new M("an01", "AN01", "hook", "an01", true, true, false, true, false),
            new M("ae01", "AE01", "hook", "ae01", true, true, false, true, false),
            new M("az20", "AZ20", "hook", "az20", false, false, true, false, false),
            new M("az15", "AZ15", "hook", "az15", false, false, true, false, false),
            new M("az10", "AZ10", "hook", "az10", false, false, false, false, false),
            new M("az09", "AZ09", "hook", "az09", false, false, false, false, false),
            new M("btmod", "BT 5.0 Cable", "hook", "bluetooth module,bt 5.0,bt5,bt module,5.0 cable", false, false, false, false, false),
            new M("xz10", "XZ10", "hook", "xz10", false, false, true, false, false),
            new M("starbit_card", "Starbit card", "usb", "starbit card,starbit dac,starbit dsp,starbit sound", false, false, false, false, false),
            new M("starbit", "Starbit", "iem", "starbit,kz starbit", false, false, false, false, false),
            new M("am03", "AM03", "usb", "am03,kz am03", false, false, false, false, false),
            new M("am02", "AM02", "usb", "am02,kz am02", false, false, false, false, false),
            new M("am01", "AM01", "usb", "am01,kz am01", false, false, false, false, false),
            new M("gale", "Gale", "usb", "gale,kz gale", false, false, false, false, false),
            new M("m2", "M2 Type-C", "usb", "kz m2,m2 cable,m2 type", false, false, false, false, false),
            new M("typec", "Type-C Cable", "usb", "type-c cable,typec cable,type c cable,usb-c cable,usbc cable,type-c,typec,c-pin,ofc cable,usb headset,usb dac,uac", false, false, false, false, false),
            new M("bte", "BTE", "neck", "kz bte,bte", false, false, true, false, false),
            new M("kz", "KZ", "tws", "kz,knowledge zenith", false, false, false, false, false)
    };

    static M of(String name) {
        if (name == null) return null;
        String raw = name.toLowerCase(Locale.US).trim();
        if (raw.length() == 0) return null;
        String[] words = raw.replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        String compact = raw.replaceAll("[^a-z0-9]", "");
        M generic = null;
        for (M m : ALL) {
            for (String tok : m.tokens) {
                if (seq(words, tok) || (tok.replace(" ", "").length() >= 4
                        && compact.contains(tok.replace(" ", "").replace("-", "")))) {
                    if ("kz".equals(m.id)) {
                        generic = m;
                        continue;
                    }
                    return m;
                }
            }
        }
        return generic;
    }

    static M byId(String id) {
        if (id == null) return null;
        for (M m : ALL) if (id.equals(m.id)) return m;
        return null;
    }

    /** USB dongle / Type-C IEM cable from product name. Defaults to Type-C Cable. */
    static M usbOf(String name) {
        if (name == null) name = "";
        String n = name.toLowerCase(Locale.US);
        if (n.contains("am03")) return byId("am03");
        if (n.contains("am02")) return byId("am02");
        if (n.contains("am01")) return byId("am01");
        if (n.contains("starbit")) return byId("starbit_card");
        if (n.contains("gale")) return byId("gale");
        if (n.contains("m2")) return byId("m2");
        M m = of(name);
        if (m != null && "usb".equals(m.kind)) return m;
        return byId("typec");
    }

    static boolean likely(String name) {
        return of(name) != null;
    }

    static String modelId(String name) {
        M m = of(name);
        return m == null ? "kz" : m.id;
    }

    static String json() {
        JSONArray a = new JSONArray();
        try {
            for (M m : ALL) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("label", m.label);
                o.put("kind", m.kind);
                boolean wired = "usb".equals(m.kind) || "iem".equals(m.kind);
                boolean classicBt = !wired;
                boolean anc = m.anc || classicBt;
                o.put("anc", anc);
                o.put("ldac", m.ldac);
                o.put("aptx", m.aptx);
                o.put("game", m.game);
                o.put("jl", m.jl);
                o.put("ble", classicBt);
                o.put("remap", m.jl);
                o.put("find", m.jl);
                o.put("ancWrite", anc);
                o.put("ota", false);
                o.put("codecs", CodecCtrl.codecsOf(m));
                JSONArray rates = new JSONArray();
                for (int r : CodecCtrl.ratesOf(m)) rates.put(r);
                o.put("rates", rates);
                JSONArray bits = new JSONArray();
                for (int b : CodecCtrl.bitsOf(m)) bits.put(b);
                o.put("bits", bits);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    private static boolean seq(String[] words, String tok) {
        String[] t = tok.trim().split("\\s+");
        if (t.length == 1) {
            for (String w : words) if (w.equals(t[0])) return true;
            return false;
        }
        for (int i = 0; i + t.length <= words.length; i++) {
            boolean ok = true;
            for (int j = 0; j < t.length; j++) if (!words[i + j].equals(t[j])) {
                ok = false;
                break;
            }
            if (ok) return true;
        }
        return false;
    }
}
