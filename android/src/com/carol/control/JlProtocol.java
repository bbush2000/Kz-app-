package com.carol.control;

/**
 * JieLi RCSP as used by KZ Audio 1.0.8.
 *
 * Frame: FE DC BA | flags | opcode | len_be | sn [+ status] | param | EF
 * Volume / ANC go through CMD_SET_SYS_INFO (0x08) public function 0xFF.
 * Keys / game mode go through CMD_ADV_SETTINGS (0xC0).
 *
 * Official key table (ADV type 2) is n concatenated triples:
 *   keyID(1=left, 2=right) + action(1=single, 2=double, 3=triple, 4=hold) + function
 * Function codes: 0 none, 3 prev, 4 next, 5 play/pause, 6 answer, 7 hangup,
 * 8 callback, 9 vol+, 10 vol-, 11 photo, 12 assistant, 13 game, 255 ANC.
 * configKeySettings writes the FULL list, not a single triple.
 */
final class JlProtocol {
    static final String SVC = "0000ae00-0000-1000-8000-00805f9b34fb";
    static final String WR = "0000ae01-0000-1000-8000-00805f9b34fb";
    static final String NT = "0000ae02-0000-1000-8000-00805f9b34fb";
    static final String SVC_ALT = "0000ae80-0000-1000-8000-00805f9b34fb";
    static final String WR_ALT = "0000ae81-0000-1000-8000-00805f9b34fb";
    static final String NT_ALT = "0000ae82-0000-1000-8000-00805f9b34fb";

    static final byte PRE0 = (byte) 0xFE;
    static final byte PRE1 = (byte) 0xDC;
    static final byte PRE2 = (byte) 0xBA;
    static final byte END = (byte) 0xEF;

    static final int CMD_GET_TARGET_INFO = 0x03;
    static final int CMD_GET_SYS_INFO = 0x07;
    static final int CMD_SET_SYS_INFO = 0x08;
    static final int CMD_SEARCH_DEVICE = 0x19;
    static final int CMD_ADV_SETTINGS = 0xC0;
    static final int CMD_ADV_GET_INFO = 0xC1;
    static final int CMD_CUSTOM = 0xF0;
    /** KZ Audio CustomCmd — opcode 255 (CMD_EXTRA_CUSTOM). */
    static final int CMD_EXTRA_CUSTOM = 0xFF;
    /** Official JieLi RebootDeviceCmd. Param 0 = reboot. */
    static final int CMD_REBOOT_DEVICE = 231;

    static final int FUNC_PUBLIC = 0xFF;
    static final int FUNC_BT = 0x00;

    static final int ATTR_BATTERY = 0;
    static final int ATTR_VOLUME = 1;
    static final int ATTR_EQ = 4;
    static final int ATTR_NOISE = 13; // live mode. Buttons do not read this.
    static final int ATTR_NOISE_ALL = 14; // saved mode table the hold button loads
    static final int ATTR_ADAPTIVE = 21;
    static final int ATTR_SCENE = 23; // Scene denoise: 0 smart, 1 mild, 2 balance, 3 depth
    static final int ATTR_LIGHT = 7;

    /** TargetInfo (CMD 0x03) — same numbers as KZ Audio AttrAndFunCode. */
    static final int TI_PROTOCOL = 0;
    static final int TI_FIRMWARE = 5;
    static final int TI_UBOOT = 7;
    static final int TI_MANDATORY = 9;
    static final int TI_VIDPID = 10;
    static final int TI_AUTH_KEY = 11;
    static final int TI_PROJECT = 12;

    static final int ADV_KEY = 2;
    static final int ADV_LED = 3;
    static final int ADV_WORK = 5;
    static final int ADV_INEAR = 8;
    static final int ADV_ANC_LIST = 10;

    static final int ATTR_PROMPT = 0x15;
    static final int ATTR_DUAL = 0x16;
    static final int ATTR_LDAC = 0x17;
    static final int ATTR_LEAUDIO = 0x18;
    static final int ATTR_INEAR = 0x19;

    /** Firmware keyID / action (1-based). WebView UI is 0-based. */
    static final int KEY_LEFT = 1;
    static final int KEY_RIGHT = 2;
    static final int KEY_ACT_CLICK = 1;
    static final int KEY_ACT_DOUBLE = 2;
    static final int KEY_ACT_TRIPLE = 3;
    static final int KEY_ACT_HOLD = 4;

    static final int KEY_FN_NONE = 0;
    static final int KEY_FN_PREV = 3;
    static final int KEY_FN_NEXT = 4;
    static final int KEY_FN_PP = 5;
    static final int KEY_FN_ANSWER = 6;
    static final int KEY_FN_HANGUP = 7;
    static final int KEY_FN_CALLBACK = 8;
    static final int KEY_FN_VOL_UP = 9;
    static final int KEY_FN_VOL_DOWN = 10;
    static final int KEY_FN_PHOTO = 11;
    static final int KEY_FN_ASSIST = 12;
    static final int KEY_FN_GAME = 13;
    static final int KEY_FN_ANC = 255;

    /**
     * Carol Pro / AN01 stock (manuals): L/R single = play, L double = prev,
     * R double = next, triple = game, L hold = ANC, R hold = assistant.
     */
    static final byte[] STOCK_KEY_TRIPLES = new byte[] {
            KEY_LEFT, KEY_ACT_CLICK, KEY_FN_PP,
            KEY_LEFT, KEY_ACT_DOUBLE, KEY_FN_PREV,
            KEY_LEFT, KEY_ACT_TRIPLE, KEY_FN_GAME,
            KEY_LEFT, KEY_ACT_HOLD, (byte) KEY_FN_ANC,
            KEY_RIGHT, KEY_ACT_CLICK, KEY_FN_PP,
            KEY_RIGHT, KEY_ACT_DOUBLE, KEY_FN_NEXT,
            KEY_RIGHT, KEY_ACT_TRIPLE, KEY_FN_GAME,
            KEY_RIGHT, KEY_ACT_HOLD, KEY_FN_ASSIST
    };

    static final byte[] AUTH_OK = new byte[] { 2, 'p', 'a', 's', 's' };

    private JlProtocol() {}

    static byte[] pack(int opcode, byte[] param, int sn, boolean needResp) {
        byte[] p = param == null ? new byte[0] : param;
        int paramLen = 1 + p.length;
        byte flags = (byte) 0x80;
        if (needResp) flags |= 0x40;
        byte[] inner = new byte[4 + paramLen];
        inner[0] = flags;
        inner[1] = (byte) (opcode & 0xFF);
        inner[2] = (byte) ((paramLen >> 8) & 0xFF);
        inner[3] = (byte) (paramLen & 0xFF);
        inner[4] = (byte) (sn & 0xFF);
        if (p.length > 0) System.arraycopy(p, 0, inner, 5, p.length);
        byte[] out = new byte[inner.length + 4];
        out[0] = PRE0;
        out[1] = PRE1;
        out[2] = PRE2;
        System.arraycopy(inner, 0, out, 3, inner.length);
        out[out.length - 1] = END;
        return out;
    }

    static byte[] attr(int type, byte[] data) {
        byte[] d = data == null ? new byte[0] : data;
        byte[] a = new byte[d.length + 2];
        a[0] = (byte) (d.length + 1);
        a[1] = (byte) (type & 0xFF);
        if (d.length > 0) System.arraycopy(d, 0, a, 2, d.length);
        return a;
    }

    static byte[] setSys(int function, byte[] attrBytes, int sn) {
        byte[] p = new byte[1 + attrBytes.length];
        p[0] = (byte) (function & 0xFF);
        System.arraycopy(attrBytes, 0, p, 1, attrBytes.length);
        return pack(CMD_SET_SYS_INFO, p, sn, true);
    }

    static byte[] getSys(int function, int mask, int sn) {
        byte[] p = new byte[] {
                (byte) (function & 0xFF),
                (byte) ((mask >> 24) & 0xFF),
                (byte) ((mask >> 16) & 0xFF),
                (byte) ((mask >> 8) & 0xFF),
                (byte) (mask & 0xFF)
        };
        return pack(CMD_GET_SYS_INFO, p, sn, true);
    }

    static byte[] getTarget(int sn) {
        byte[] p = new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0 };
        return pack(CMD_GET_TARGET_INFO, p, sn, true);
    }

    static byte[] getAdvAll(int sn) {
        byte[] p = new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        return pack(CMD_ADV_GET_INFO, p, sn, true);
    }

    /** Firmware DAC 0–31. Public SYS_INFO attr 1. Never phone STREAM_MUSIC. */
    static byte[] volume(int step, int sn) {
        int v = Math.max(0, Math.min(31, step));
        return setSys(FUNC_PUBLIC, attr(ATTR_VOLUME, new byte[] { (byte) v }), sn);
    }

    static byte[] getVolume(int sn) {
        return getSys(FUNC_PUBLIC, 1 << ATTR_VOLUME, sn);
    }

    static byte[] u16(int v) {
        return new byte[] { (byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF) };
    }

    static byte[] getAnc(int sn) {
        return getSys(FUNC_PUBLIC, (1 << ATTR_NOISE) | (1 << ATTR_NOISE_ALL), sn);
    }

    static byte[] getAncNow(int sn) {
        return getAnc(sn);
    }

    static byte[] setCurrentVm(byte[] vm, int sn) {
        if (vm == null || vm.length != 9) return getAnc(sn);
        return setSys(FUNC_PUBLIC, attr(ATTR_NOISE, vm), sn);
    }

    /** Saved table: count, then one 9-byte VoiceMode per mode. The hold button loads these. */
    static byte[] setAncTable(byte[] vm0, byte[] vm1, byte[] vm2, int sn) {
        byte[] data = new byte[1 + 27];
        data[0] = 3;
        System.arraycopy(vm0, 0, data, 1, 9);
        System.arraycopy(vm1, 0, data, 10, 9);
        System.arraycopy(vm2, 0, data, 19, 9);
        return setSys(FUNC_PUBLIC, attr(ATTR_NOISE_ALL, data), sn);
    }

    /** 1-byte VoiceMode. Live switch only. Does not update the saved slot. */
    static byte[] setAncMode(int mode, int sn) {
        int m = mode < 0 ? 0 : Math.min(2, mode);
        return setSys(FUNC_PUBLIC, attr(ATTR_NOISE, new byte[] { (byte) m }), sn);
    }

    /** Hold-button cycle mask, 4-byte big-endian. bit0 standard, bit1 ANC, bit2 transparency. */
    static byte[] ancCycle(int sn) {
        return pack(CMD_ADV_SETTINGS, attr(ADV_ANC_LIST, new byte[] { 0, 0, 0, 0x07 }), sn, true);
    }

    /** Some KZ units store the current mode in ADV type 10 instead of SYS_INFO 13. */
    static byte[] setAncAdv(int mode, int sn) {
        int m = mode < 0 ? 0 : Math.min(2, mode);
        return pack(CMD_ADV_SETTINGS, attr(ADV_ANC_LIST, new byte[] { (byte) m }), sn, true);
    }

    static byte[] reboot(int sn) {
        return pack(CMD_REBOOT_DEVICE, new byte[] { 0 }, sn, true);
    }

    static byte[] work(String mode, int sn) {
        int v = "game".equals(mode) ? 2 : 1;
        return pack(CMD_ADV_SETTINGS, attr(ADV_WORK, new byte[] { (byte) v }), sn, true);
    }

    static byte[] eq(int preset, int[] bands, int sn) {
        byte[] data = new byte[11];
        data[0] = (byte) (preset & 0xFF);
        int n = bands == null ? 0 : Math.min(10, bands.length);
        for (int i = 0; i < n; i++) {
            int g = Math.max(0, Math.min(24, bands[i] + 12));
            data[1 + i] = (byte) g;
        }
        return setSys(FUNC_PUBLIC, attr(ATTR_EQ, data), sn);
    }

    static byte[] find(String side, int sn) {
        int type = "left".equals(side) ? 1 : "right".equals(side) ? 2 : 0;
        int op = side == null || "stop".equals(side) ? 0 : 1;
        byte[] p = new byte[] { (byte) type, (byte) op, 0, 10, 0, 0 };
        return pack(CMD_SEARCH_DEVICE, p, sn, true);
    }

    /** Firmware-coded triple. Prefer {@link #keys} with the full table. */
    static byte[] key(int side, int action, int fn, int sn) {
        return keys(new byte[] { (byte) side, (byte) action, (byte) fn }, sn);
    }

    static byte[] keys(byte[] triples, int sn) {
        return pack(CMD_ADV_SETTINGS, attr(ADV_KEY, triples), sn, true);
    }

    /**
     * TWS logo table. Scenes 1, 2 and 3 share one effect so the music scene
     * cannot stay on a pulse. 0 off, 2 blue solid, 4 blue breath.
     */
    static byte[] logo(int effect, int sn) {
        int e = effect & 0xFF;
        byte[] data = new byte[] { 1, (byte) e, 2, (byte) e, 3, (byte) e };
        return pack(CMD_ADV_SETTINGS, attr(ADV_LED, data), sn, true);
    }

    /** One scene, the way the JieLi iOS stack writes it. */
    static byte[] logoScene(int scene, int effect, int sn) {
        byte[] data = new byte[] { (byte) scene, (byte) (effect & 0xFF) };
        return pack(CMD_ADV_SETTINGS, attr(ADV_LED, data), sn, true);
    }

    /** Speaker-style light block: blue, luminance 0–100. TWS may ignore it. */
    static byte[] lightLevel(int pct, int sn) {
        int lum = Math.max(0, Math.min(100, pct));
        byte[] data = new byte[13];
        data[0] = (byte) (lum == 0 ? 0 : 2);
        data[1] = 0;
        data[5] = (byte) 0xFF;
        data[10] = (byte) 240;
        data[11] = 100;
        data[12] = (byte) lum;
        return setSys(FUNC_PUBLIC, attr(ATTR_LIGHT, data), sn);
    }

    /** ADV in-ear: 2 = detection on (pause when out), 1 = detection off. 0 means the feature is absent. */
    static byte[] inEar(boolean on, int sn) {
        return pack(CMD_ADV_SETTINGS, attr(ADV_INEAR, new byte[] { (byte) (on ? 2 : 1) }), sn, true);
    }

    static byte[] flag(int attr, boolean on, int sn) {
        if (attr == ATTR_INEAR) return inEar(on, sn);
        return setSys(FUNC_PUBLIC, attr(attr, new byte[] { (byte) (on ? 1 : 0) }), sn);
    }

    static byte[] mic(String ch, int sn) {
        int v = "left".equals(ch) ? 2 : "right".equals(ch) ? 3 : "off".equals(ch) || "mute".equals(ch) ? 0 : 1;
        return pack(CMD_ADV_SETTINGS, attr(4, new byte[] { (byte) v }), sn, true);
    }

    static byte[] getInfo(int sn) {
        return getSys(FUNC_PUBLIC, (1 << ATTR_BATTERY) | (1 << ATTR_VOLUME) | (1 << ATTR_EQ) | (1 << ATTR_NOISE), sn);
    }

    /** UI 0=left/1=right → firmware 1/2. Values already 1/2 pass through. */
    static int sideUiToFw(int side) {
        if (side == 0 || side == 1) return side + 1;
        return side;
    }

    /** UI 0..3 (single/double/triple/hold) → firmware 1..4. */
    static int actUiToFw(int action) {
        if (action >= 0 && action <= 3) return action + 1;
        return action;
    }

    static int sideFwToUi(int side) {
        return side >= 1 ? side - 1 : side;
    }

    static int actFwToUi(int action) {
        return action >= 1 ? action - 1 : action;
    }

    /**
     * WebView function ids 0..8 → firmware codes.
     * Unknown values that already look like firmware codes pass through.
     */
    static int fnUiToFw(int ui) {
        switch (ui) {
            case 0: return KEY_FN_NONE;
            case 1: return KEY_FN_PP;
            case 2: return KEY_FN_PREV;
            case 3: return KEY_FN_NEXT;
            case 4: return KEY_FN_VOL_UP;
            case 5: return KEY_FN_VOL_DOWN;
            case 6: return KEY_FN_ASSIST;
            case 7: return KEY_FN_ANC;
            case 8: return KEY_FN_GAME;
            default:
                int v = ui & 0xFF;
                if (v == KEY_FN_ANC || (v >= 0 && v <= KEY_FN_GAME)) return v;
                return KEY_FN_NONE;
        }
    }

    /** Firmware function → UI 0..8. Returns -1 if the UI has no row for it. */
    static int fnFwToUi(int fw) {
        switch (fw & 0xFF) {
            case KEY_FN_NONE: return 0;
            case KEY_FN_PP: return 1;
            case KEY_FN_PREV: return 2;
            case KEY_FN_NEXT: return 3;
            case KEY_FN_VOL_UP: return 4;
            case KEY_FN_VOL_DOWN: return 5;
            case KEY_FN_ASSIST:
            case 14: return 6;
            case KEY_FN_ANC: return 7;
            case KEY_FN_GAME: return 8;
            default: return -1;
        }
    }
}
