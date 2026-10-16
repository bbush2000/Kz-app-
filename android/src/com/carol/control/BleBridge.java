package com.carol.control;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.location.LocationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.SparseArray;

import com.jieli.bluetooth.impl.RcspAuth;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Classic SPP RCSP to KZ Carol (A2DP address). Auth, then firmware SYS_INFO / ADV.
 * Volume never touches AudioManager.
 */
@SuppressLint("MissingPermission")
final class BleBridge {
    interface Listener {
        void onEvent(String json);
    }

    private static final String PREF = "carol_ble";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_CUSTOM = UUID.fromString("00001107-d102-11e1-9b23-00025b00a5a5");
    private static final UUID UUID_JL = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_AE80 = UUID.fromString("0000ae80-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_FE = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Context host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Listener listener;
    private final Set<String> seenScan = new HashSet<>();
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothSocket spp;
    private OutputStream sppOut;
    private InputStream sppIn;
    private Thread reader;
    private BluetoothDevice pending;
    private BluetoothDevice gattTarget;
    private final ArrayList<String> derivedMacs = new ArrayList<>();
    private UUID sppUuid;
    private boolean sppJl;
    private int seq = 1;
    private boolean live;
    private boolean scanningWanted;
    private int connectTries;
    private BluetoothA2dp a2dp;
    private BluetoothHeadset hfp;
    private volatile boolean mediaPaused;
    private volatile int pauseTries;
    private byte[] lastAncVm;
    private boolean ancHeard;
    private final byte[][] ancVm = new byte[3][];
    /** First copy of the chip's mode list. Never replaced by a live write. */
    private final byte[][] ancFactory = new byte[3][];
    private String fwVersion = "";
    private String fwAuth = "";
    private String fwProj = "";
    private int fwVid;
    private int fwPid;
    private boolean rcspSeen;
    private boolean emittedConnected;
    private boolean chipTold;
    private int rxBytes;
    private RcspAuth auth;
    private boolean authFailed;
    private byte[] authRandom;
    private boolean authDone;
    private boolean authProgress;
    private final ByteArrayOutputStream rx = new ByteArrayOutputStream();
    private final Object writeLock = new Object();
    private final Object gattIo = new Object();
    private volatile boolean gattBusy;
    private volatile int gattMtu = 23;
    private volatile boolean cccdPending;
    private volatile boolean notifyArmed;
    private volatile boolean sppWanted;
    private volatile boolean sppBusy;
    private volatile boolean classicCtrl;
    private volatile boolean gattUp;
    private long gattAttemptAt;
    private long lastWake;
    private boolean userOff;
    private int scanLogLeft;
    /** Firmware key table: ((sideFw << 8) | actionFw) → functionFw. */
    private final LinkedHashMap<Integer, Integer> keyMap = new LinkedHashMap<>();
    private int batL = Integer.MIN_VALUE, batR = Integer.MIN_VALUE, batC = Integer.MIN_VALUE;
    private final Runnable sysBattRun = () -> emitSysBatt();
    private final Runnable huntScan = () -> {
        if (pending == null || writeChar != null || rcspSeen || !expectsJl()) return;
        if (gatt != null && System.currentTimeMillis() - gattAttemptAt < 2500) return;
        startScanQuiet();
    };
    private final Runnable huntKick = () -> {
        if (pending == null || writeChar != null || rcspSeen || !expectsJl()) return;
        kickRadio();
    };
    private final Runnable sppFirst = this::maybeOpenSpp;
    private final Runnable sppAll = this::maybeOpenSppAll;
    private final Runnable hangWatch = this::gattHangWatch;
    private final Runnable silentRetry = this::retryGattIfSilent;
    private final Runnable shake = this::watchHandshake;
    private final Runnable enterApp = () -> {
        if (pending == null || emittedConnected) return;
        if (!a2dpConnected(pending)) return;
        dbg("a2dp live — enter app, keep hunting chip");
        markConnected(false);
    };
    private final Runnable quietStop = this::stopScanQuiet;
    private String lastAncMode = "";
    private boolean lastAncAgree = true;
    private long ancAt;
    private long ancWriteUntil;
    private String ancWant = "";
    private String ancHealed = "";
    private int ancHealTries;
    private boolean ancFlagRepaired;
    private boolean ancButtonSaving;
    private int ancButtonTries;
    private long ancSaveUntil;
    private boolean ancFullSet;
    private boolean ancToldSupport;
    private int ancFullL;
    private int ancFullR;
    private int ancPct = 100;
    private boolean transFullSet;
    private boolean transToldSupport;
    private int transFullL;
    private int transFullR;
    private int transPct = 100;
    private boolean ancRestoring;
    private boolean ancPersistArmed;
    private int ancPersistMode = -1;
    private final Runnable ancPersistRun = () -> {
        final int m = ancPersistMode;
        if (m < 0 || m > 2) return;
        if (pending == null && writeChar == null && sppOut == null) return;
        io.execute(() -> {
            if (writeChar == null && sppOut == null) return;
            byte[] vm = liveBoth(m);
            writePkt(JlProtocol.setCurrentVm(vm, nextSn()));
            sleepQuiet(320);
            boolean ok = writePkt(JlProtocol.setCurrentVm(vm, nextSn()));
            dbg("anc persist " + (ok ? "ok" : "FAIL") + " mode=" + m
                    + " L=" + u16(vm, 3) + " R=" + u16(vm, 7));
        });
    };
    private boolean ancRestored = true;
    private boolean ancCycleSent;
    private boolean ancListRepair;
    private String workMode = "normal";
    private final Runnable workPersistRun = () -> {
        if (pending == null && writeChar == null && sppOut == null) return;
        final String w = workMode;
        io.execute(() -> {
            if (writeChar == null && sppOut == null) return;
            boolean ok = writePkt(JlProtocol.work(w, nextSn()));
            dbg("work persist " + (ok ? "ok" : "FAIL") + " " + w);
        });
    };
    private boolean inEarOn;
    private boolean inEarKnown;
    private long inEarWriteUntil;
    /** Second ADV in-ear write only. Never work-mode, never the hold mask. */
    private final Runnable inEarPersistRun = () -> {
        if (pending == null && writeChar == null && sppOut == null) return;
        final boolean on = inEarOn;
        io.execute(() -> {
            if (writeChar == null && sppOut == null) return;
            boolean ok = writePkt(JlProtocol.inEar(on, nextSn()));
            dbg("inear persist " + (ok ? "ok" : "FAIL") + " " + (on ? "on" : "off"));
        });
    };
    private String findSide = "";
    private int eqPreset;
    private int[] eqBands;
    private final Runnable eqRun = () -> {
        int[] b = eqBands;
        if (b != null) burst(JlProtocol.eq(eqPreset, b, nextSn()));
    };
    private int pendSide, pendAct, pendFn;
    private final Runnable keyRun = () -> writeKey(pendSide, pendAct, pendFn);

    BleBridge(Context ctx) {
        this.host = ctx;
        this.ctx = ctx.getApplicationContext();
        // JieLi JNI is 16KB-page sensitive on Android 15+ flagships. Load on first auth, never at launch.
        try {
            fwVersion = prefs().getString("fw_ver", "");
            fwAuth = prefs().getString("fw_auth", "");
            fwProj = prefs().getString("fw_proj", "");
        } catch (Exception ignored) {
        }
        try {
            BluetoothAdapter ad = adapter();
            if (ad != null) {
                ad.getProfileProxy(this.ctx, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile == BluetoothProfile.A2DP) a2dp = (BluetoothA2dp) proxy;
                        if (profile == BluetoothProfile.HEADSET) hfp = (BluetoothHeadset) proxy;
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        if (profile == BluetoothProfile.A2DP) a2dp = null;
                        if (profile == BluetoothProfile.HEADSET) hfp = null;
                    }
                }, BluetoothProfile.A2DP);
                ad.getProfileProxy(this.ctx, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile == BluetoothProfile.HEADSET) hfp = (BluetoothHeadset) proxy;
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        if (profile == BluetoothProfile.HEADSET) hfp = null;
                    }
                }, BluetoothProfile.HEADSET);
            }
        } catch (Exception ignored) {
        }
    }

    private void ensureAuth() {
        if (auth != null || authFailed) return;
        try {
            RcspAuth a = new RcspAuth();
            a.nativeInit();
            auth = a;
        } catch (Throwable ignored) {
            auth = null;
            authFailed = true;
        }
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isLive() {
        return live;
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private void emit(String json) {
        ui.post(() -> {
            if (listener != null) listener.onEvent(json);
        });
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private boolean locationOn() {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return true;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return true;
        }
    }

    void startScan() {
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) {
            emit("{\"type\":\"error\",\"message\":\"Bluetooth is off.\"}");
            return;
        }
        scanningWanted = true;
        seenScan.clear();
        emit("{\"type\":\"scan\",\"state\":\"start\"}");
        emitBonded(ad);
        emitRemembered();
        if (Build.VERSION.SDK_INT < 31 && !locationOn()) {
            emit("{\"type\":\"error\",\"message\":\"Turn Location on. Android 7–11 hides nearby Bluetooth until it is on. Paired buds still work from the list.\"}");
        }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) {
            emit("{\"type\":\"scan\",\"state\":\"stop\"}");
            return;
        }
        try {
            ScanSettings.Builder b = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
            if (Build.VERSION.SDK_INT >= 23) {
                b.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
            }
            scanner.startScan(java.util.Collections.<ScanFilter>emptyList(), b.build(), scanCb);
        } catch (Exception e) {
            emit("{\"type\":\"error\",\"message\":\"Scan failed. Tap Connect paired Carol — A2DP is enough.\"}");
        }
        ui.postDelayed(this::stopScan, 12000);
    }

    private void emitRemembered() {
        String addr = prefs().getString("addr", null);
        String name = prefs().getString("name", "Carol");
        if (addr != null) {
            emit("{\"type\":\"device\",\"id\":\"" + esc(addr) + "\",\"name\":\"" + esc(name)
                    + "\",\"rssi\":-50,\"bonded\":true,\"likely\":true,\"remembered\":true,\"model\":\""
                    + modelOf(name) + "\"}");
        }
    }

    private void remember(String addr, String name) {
        remember(addr, name, true);
    }

    private void remember(String addr, String name, boolean active) {
        if (addr == null) return;
        if (name == null || name.length() == 0) name = "KZ";
        if (active) {
            prefs().edit().putString("addr", addr).putString("name", name).apply();
            try { UserState.cacheLink(ctx, addr, name, modelOf(name), false); } catch (Throwable ignored) {}
        }
        DeviceRoster.upsert(ctx, addr, name, modelOf(name), "bt");
        if (active) DeviceRoster.setActive(ctx, addr);
        UserState.markDirty();
        emit(DeviceRoster.eventJson(ctx));
    }

    private void linkA2dp(BluetoothDevice d) {
        if (a2dp == null || d == null) return;
        try {
            java.lang.reflect.Method m = BluetoothA2dp.class.getMethod("connect", BluetoothDevice.class);
            m.invoke(a2dp, d);
        } catch (Exception ignored) {
        }
    }

    private void emitBonded(BluetoothAdapter ad) {
        try {
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded == null) return;
            for (BluetoothDevice d : bonded) {
                String n = d.getName();
                if (n == null || n.length() == 0) n = "Paired device";
                boolean hit = likely(n);
                if (hit) {
                    remember(d.getAddress(), n, false);
                    linkA2dp(d);
                }
                emit("{\"type\":\"device\",\"id\":\"" + esc(d.getAddress()) + "\",\"name\":\"" + esc(n)
                        + "\",\"rssi\":-30,\"bonded\":true,\"likely\":" + hit
                        + ",\"remembered\":false,\"model\":\""
                        + modelOf(n) + "\"}");
            }
        } catch (Exception ignored) {
        }
    }

    void stopScan() {
        scanningWanted = false;
        try {
            if (scanner != null) scanner.stopScan(scanCb);
        } catch (Exception ignored) {
        }
        emit("{\"type\":\"scan\",\"state\":\"stop\"}");
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = d.getName();
            ScanRecord rec = result.getScanRecord();
            if ((name == null || name.length() == 0) && rec != null) name = rec.getDeviceName();
            if ((name == null || name.length() == 0) && rec != null) name = nameInRecord(rec);
            boolean jl = hasJlRecord(rec) || nearMac(d.getAddress());
            boolean named = likely(name);
            if (scanLogLeft > 0) {
                scanLogLeft--;
                dbg("scan " + d.getAddress() + " n=" + (name == null ? "-" : name)
                        + " rssi=" + result.getRssi() + " jl=" + jl + " named=" + named);
            }
            if (!named && !jl) return;
            if (!seenScan.add(d.getAddress())) {
                if (pending != null && writeChar == null && jl) gattTo(d, true);
                return;
            }
            if (name == null || name.length() == 0) name = named ? name : "Carol BLE";
            if (pending == null) remember(d.getAddress(), name);
            emit("{\"type\":\"device\",\"id\":\"" + esc(d.getAddress()) + "\",\"name\":\"" + esc(name)
                    + "\",\"rssi\":" + result.getRssi()
                    + ",\"bonded\":false,\"likely\":true,\"remembered\":false,\"model\":\"" + modelOf(name) + "\"}");
            emit("{\"type\":\"probe\",\"scan\":\"" + esc(d.getAddress()) + "\",\"name\":\"" + esc(name) + "\"}");
            if (pending != null && writeChar == null) {
                dbg("scan hit " + d.getAddress() + " jl=" + jl + " named=" + named
                        + " classic=" + pending.getAddress());
                gattTo(d, true);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            dbg("scan fail " + errorCode);
        }
    };

    private static String nameInRecord(ScanRecord rec) {
        try {
            byte[] raw = rec.getBytes();
            if (raw == null) return null;
            String s = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.US);
            if (s.contains("carol")) return "Carol";
            if (s.contains("krio")) return "Krio";
            if (s.contains("xtra")) return "Xtra";
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean hasJlRecord(ScanRecord rec) {
        if (rec == null) return false;
        try {
            List<ParcelUuid> uuids = rec.getServiceUuids();
            if (uuids != null) {
                for (ParcelUuid u : uuids) {
                    if (u == null) continue;
                    String s = u.toString().toLowerCase(Locale.US);
                    if (s.contains("ae00") || s.contains("ae80") || s.contains("ae01") || s.contains("fe01")) return true;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            SparseArray<byte[]> md = rec.getManufacturerSpecificData();
            if (md != null) {
                if (md.get(0x05D6) != null || md.get(0x02B0) != null || md.get(0x05D7) != null
                        || md.get(0xD605) != null) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean nearMac(String addr) {
        if (addr == null) return false;
        String want = pending != null ? pending.getAddress() : prefs().getString("addr", null);
        if (want == null) return false;
        String a = addr.replace(":", "").toUpperCase(Locale.US);
        String b = want.replace(":", "").toUpperCase(Locale.US);
        if (a.length() != 12 || b.length() != 12) return false;
        if (a.equals(b)) return true;
        // U/L-bit flip on first octet, rest same (classic vs BLE public).
        if (a.substring(2).equals(b.substring(2))) return true;
        // Last 4 bytes same.
        if (a.substring(4).equals(b.substring(4))) return true;
        // First 5 bytes same (last-byte ± for left/right).
        if (a.substring(0, 10).equals(b.substring(0, 10))) return true;
        // Same OUI, last 3 bytes close — JieLi TWS family around the A2DP MAC.
        if (a.regionMatches(0, b, 0, 6)) {
            int dist = 0;
            for (int i = 6; i < 12; i += 2) {
                int x = Integer.parseInt(a.substring(i, i + 2), 16);
                int y = Integer.parseInt(b.substring(i, i + 2), 16);
                dist += Math.abs(x - y);
            }
            if (dist <= 4) return true;
        }
        return false;
    }

    private void gattTo(BluetoothDevice d, boolean le) {
        ui.post(() -> {
            if (d == null || writeChar != null || rcspSeen) return;
            boolean same = gattTarget != null && d.getAddress().equalsIgnoreCase(gattTarget.getAddress());
            if (same && gatt != null && System.currentTimeMillis() - gattAttemptAt < 1800) return;
            try {
                dbg("gattTo " + d.getAddress() + " le=" + le
                        + " classic=" + (pending != null ? pending.getAddress() : "-"));
                gattTarget = d;
                stopScanQuiet();
                closeGattQuiet();
                int t = BluetoothDevice.TRANSPORT_LE;
                if (!le && Build.VERSION.SDK_INT >= 23) t = BluetoothDevice.TRANSPORT_AUTO;
                gattAttemptAt = System.currentTimeMillis();
                gatt = connectGattNow(d, t, false);
            } catch (Exception e) {
                dbg("gattTo FAIL " + e.getClass().getSimpleName());
            }
        });
    }

    /** JieLi dual-mode: BLE public address is usually classic ± last byte, or U/L bit. */
    private void fillDerived(String mac) {
        derivedMacs.clear();
        byte[] b = parseMac(mac);
        if (b == null) return;
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(fmtMac(b));
        for (int d : new int[] { 1, -1 }) {
            byte[] c = b.clone();
            c[5] = (byte) ((c[5] + d) & 0xFF);
            set.add(fmtMac(c));
        }
        byte[] ul = b.clone();
        ul[0] = (byte) (ul[0] ^ 0x02);
        set.add(fmtMac(ul));
        derivedMacs.addAll(set);
    }

    private static byte[] parseMac(String mac) {
        if (mac == null) return null;
        String h = mac.replace(":", "").replace("-", "");
        if (h.length() != 12) return null;
        try {
            byte[] b = new byte[6];
            for (int i = 0; i < 6; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmtMac(byte[] b) {
        return String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
                b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF, b[4] & 0xFF, b[5] & 0xFF);
    }

    static String modelOf(String n) {
        return KzCatalog.modelId(n);
    }

    static boolean likely(String n) {
        return KzCatalog.likely(n);
    }

    /** Classic audio, no JieLi GATT. Do not scan for a chip that will not advertise. */
    private static boolean classicOnly(String id) {
        if (id == null) return false;
        switch (id) {
            case "carol":
            case "an01":
            case "ae01":
            case "az09":
            case "az09_pro":
            case "az10":
            case "btmod":
            case "ae01_pro":
            case "az15":
            case "az20":
            case "xz10":
            case "z3":
            case "vxs":
            case "bte":
                return true;
            default:
                return false;
        }
    }

    private boolean expectsJl() {
        if (pending == null) return true;
        KzCatalog.M m = KzCatalog.of(pending.getName());
        if (m == null) return true;
        if (classicOnly(m.id)) return false;
        return m.jl;
    }

    void connectPaired() {
        BluetoothAdapter ad = adapter();
        if (ad == null) {
            emit("{\"type\":\"error\",\"message\":\"Bluetooth adapter missing\"}");
            return;
        }
        BluetoothDevice kz = null;
        BluetoothDevice other = null;
        String want = DeviceRoster.activeId(ctx);
        try {
            if (a2dp != null) {
                for (BluetoothDevice d : a2dp.getConnectedDevices()) {
                    if (d == null) continue;
                    String n = d.getName();
                    if (likely(n)) {
                        remember(d.getAddress(), n == null ? "KZ" : n, false);
                        if (kz == null || d.getAddress().equalsIgnoreCase(want)) kz = d;
                    } else if (other == null) other = d;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (d == null || !likely(d.getName())) continue;
                    String n = d.getName();
                    remember(d.getAddress(), n, false);
                    if (kz == null || d.getAddress().equalsIgnoreCase(want)) kz = d;
                }
            }
        } catch (Exception ignored) {
        }
        BluetoothDevice pick = kz != null ? kz : other;
        if (pick != null) {
            connect(pick.getAddress());
            return;
        }
        String addr = prefs().getString("addr", null);
        if (addr != null) {
            connect(addr);
            return;
        }
        emit("{\"type\":\"error\",\"message\":\"Carol is not the active A2DP device. Connect it for media, then List paired devices.\"}");
    }

    /** App came back. If audio is up and the chip never answered, hunt again. A disconnect stays disconnected. */
    void wake() {
        long now = System.currentTimeMillis();
        if (now - lastWake < 4000) return;
        lastWake = now;
        if (userOff || chipReady()) return;
        if (pending != null && expectsJl()) {
            dbg("wake — chip quiet, hunt again");
            ui.post(huntScan);
            ui.postDelayed(huntKick, 700);
            ui.postDelayed(silentRetry, 2200);
            return;
        }
        if (pending != null) return;
        boolean up = false;
        try {
            if (a2dp != null) {
                for (BluetoothDevice d : a2dp.getConnectedDevices()) {
                    if (d != null && likely(d.getName())) { up = true; break; }
                }
            }
        } catch (Exception ignored) {
        }
        if (up) {
            dbg("wake — media is up, start the handshake");
            connectPaired();
        }
    }

    void retryLast() {
        connectPaired();
    }

    void connect(String address) {
        userOff = false;
        stopScan();
        BluetoothAdapter ad = adapter();
        if (ad == null) {
            emit("{\"type\":\"error\",\"message\":\"Bluetooth adapter missing\"}");
            return;
        }
        pending = bondedOf(address);
        gattTarget = null;
        fillDerived(address);
        String nm = pending.getName();
        remember(address, nm == null || nm.length() == 0 ? "Carol" : nm);
        SpatialFx.get(ctx).bindDevice(address);
        emittedConnected = false;
        chipTold = false;
        ancRestored = false;
        ui.removeCallbacks(sysBattRun);
        ancCycleSent = false;
        ancSlotsSaved = false;
        ancPersistArmed = false;
        ui.removeCallbacks(ancPersistRun);
        ui.removeCallbacks(workPersistRun);
        ui.removeCallbacks(inEarPersistRun);
        inEarKnown = false;
        inEarWriteUntil = 0;
        workMode = "normal";
        rcspSeen = false;
        rxBytes = 0;
        authDone = false;
        authProgress = false;
        sppWanted = false;
        classicCtrl = false;
        sppJl = false;
        sppUuid = null;
        gattUp = false;
        mediaPaused = false;
        pauseTries = 0;
        connectTries = 0;
        gattMtu = 23;
        seenScan.clear();
        scanLogLeft = 16;
        clearHunt();
        emit("{\"type\":\"connecting\",\"id\":\"" + esc(address) + "\"}");
        try { CarolService.hold(ctx); } catch (Throwable ignored) {}
        dbg("connect " + address + " oem=" + Build.MANUFACTURER + " " + Build.MODEL
                + " xiaomi=" + oemStarvesLe() + " derived=" + derivedMacs.size());
        if (!derivedMacs.isEmpty()) dbg("ble candidates " + derivedMacs.toString());
        stopScanQuiet();
        if (!expectsJl()) {
            dbg("no BLE hunt on " + (nm == null ? "this model" : nm) + " — Classic audio, phone EQ");
            ui.postDelayed(() -> {
                if (!emittedConnected) markConnected(false);
            }, 400);
            return;
        }
        String cached = null;
        try { cached = prefs().getString(bleKey(address), null); } catch (Exception ignored) {}
        if (cached != null && cached.length() > 0) {
            try {
                gattTarget = ad.getRemoteDevice(cached);
                dbg("cached ble " + cached + " for " + address);
                ui.post(() -> openGatt(0));
            } catch (Exception ignored) {
                gattTarget = null;
            }
        }
        if (gattTarget == null) ui.post(huntScan);
        else ui.postDelayed(huntScan, 1200);
        ui.postDelayed(huntKick, 1800);
        ui.postDelayed(enterApp, 350);
        ui.postDelayed(hangWatch, 4500);
        ui.postDelayed(sppFirst, 5200);
        ui.postDelayed(silentRetry, 6200);
        ui.postDelayed(sppAll, 7400);
        ui.postDelayed(shake, 8000);
        ui.postDelayed(shake, 14000);
    }

    private BluetoothDevice bondedOf(String address) {
        BluetoothAdapter ad = adapter();
        if (ad == null || address == null) return null;
        try {
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (d != null && address.equalsIgnoreCase(d.getAddress())) return d;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            if (a2dp != null) {
                for (BluetoothDevice d : a2dp.getConnectedDevices()) {
                    if (d != null && address.equalsIgnoreCase(d.getAddress())) return d;
                }
            }
        } catch (Exception ignored) {
        }
        return ad.getRemoteDevice(address);
    }

    private static boolean oemStarvesLe() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.US);
        String b = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(Locale.US);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
                || b.contains("xiaomi") || b.contains("redmi") || b.contains("poco")
                || m.contains("blackshark");
    }

    private boolean a2dpConnected(BluetoothDevice d) {
        if (d == null || a2dp == null) return false;
        try {
            List<BluetoothDevice> list = a2dp.getConnectedDevices();
            if (list == null) return false;
            for (BluetoothDevice x : list) {
                if (x != null && d.getAddress().equalsIgnoreCase(x.getAddress())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void maybeOpenSpp() {
        if (writeChar != null || rcspSeen || gattUp) return;
        openSpp(false);
    }

    private void maybeOpenSppAll() {
        if (writeChar != null || rcspSeen || gattUp || sppJl) return;
        openSpp(true);
    }

    private void openSpp(boolean includeStd) {
        if (writeChar != null || rcspSeen) {
            dbg("skip spp — gatt live mtu=" + gattMtu);
            return;
        }
        BluetoothAdapter ad = adapter();
        if (ad == null || pending == null) return;
        sppWanted = true;
        sppBusy = true;
        final String addr = pending.getAddress();
        dbg("spp " + (includeStd ? "classic" : "jieli-uuids") + " after gatt wait");
        io.execute(() -> {
            try {
            if (includeStd && writeChar == null && oemStarvesLe()) {
                dbg("classic spp — close gatt so Xiaomi does not stub RFCOMM");
                closeGattQuiet();
                classicCtrl = true;
                sleepQuiet(450);
                try { pending.fetchUuidsWithSdp(); } catch (Exception ignored) {}
                sleepQuiet(800);
            }
            connectBlocking(ad, addr, includeStd);
            } finally {
                sppBusy = false;
            }
        });
    }

    private boolean dummySpp() {
        return sppOut != null && !sppJl && rxBytes == 0 && !rcspSeen;
    }

    private void retryGattIfSilent() {
        if (pending == null || writeChar != null || rcspSeen || gattUp) return;
        dbg("retry gatt silent rx=" + rxBytes + " spp=" + (sppOut != null) + " try=" + connectTries);
        startScanQuiet();
        ui.post(() -> openGatt(connectTries + 1));
    }

    private void gattHangWatch() {
        if (pending == null || writeChar != null || rcspSeen || gattUp) return;
        if (gatt == null) {
            startScanQuiet();
            return;
        }
        if (System.currentTimeMillis() - gattAttemptAt < 5500) return;
        dbg("gatt hang mac=" + (gatt.getDevice() != null ? gatt.getDevice().getAddress() : "?")
                + " — close, scan, do not guess MACs");
        closeGattQuiet();
        startScanQuiet();
        ui.postDelayed(() -> {
            if (writeChar == null && gattTarget != null) openGatt(0);
            else if (writeChar == null) openGatt(connectTries + 1);
        }, 400);
    }

    private boolean hfpConnected(BluetoothDevice d) {
        if (d == null || hfp == null) return false;
        try {
            List<BluetoothDevice> list = hfp.getConnectedDevices();
            if (list == null) return false;
            for (BluetoothDevice x : list) {
                if (x != null && d.getAddress().equalsIgnoreCase(x.getAddress())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void pauseMediaForRadio() {
        pauseMediaForRadio(false);
    }

    private void pauseMediaForRadio(boolean force) {
        if (pending == null) return;
        if (writeChar != null || rcspSeen) return;
        dbg("hunt ble — keep a2dp force=" + force);
        startScanQuiet();
    }

    private void maybePauseA2dp() {
        pauseMediaForRadio();
    }

    private void resumeA2dp() {
        if (pending == null) return;
        if (!mediaPaused && !a2dpConnected(pending)) {
            dbg("a2dp resume");
            linkA2dp(pending);
            return;
        }
        if (!mediaPaused) return;
        mediaPaused = false;
        dbg("a2dp resume");
        linkA2dp(pending);
        try {
            if (hfp != null) {
                Method m = BluetoothHeadset.class.getMethod("connect", BluetoothDevice.class);
                m.invoke(hfp, pending);
            }
        } catch (Exception ignored) {
        }
    }

    private void unlinkA2dp(BluetoothDevice d) {
        if (a2dp == null || d == null) return;
        try {
            Method m = BluetoothA2dp.class.getMethod("disconnect", BluetoothDevice.class);
            m.invoke(a2dp, d);
        } catch (Exception ignored) {
        }
    }

    private void unlinkHfp(BluetoothDevice d) {
        if (hfp == null || d == null) return;
        try {
            Method m = BluetoothHeadset.class.getMethod("disconnect", BluetoothDevice.class);
            m.invoke(hfp, d);
        } catch (Exception ignored) {
        }
    }

    private void kickRadio() {
        if (pending == null || writeChar != null || rcspSeen || gattUp) return;
        if (gatt != null && System.currentTimeMillis() - gattAttemptAt < 8000) return;
        startScanQuiet();
        ui.post(() -> openGatt(connectTries + 1));
    }

    private void clearHunt() {
        ui.removeCallbacks(huntScan);
        ui.removeCallbacks(huntKick);
        ui.removeCallbacks(sppFirst);
        ui.removeCallbacks(sppAll);
        ui.removeCallbacks(hangWatch);
        ui.removeCallbacks(silentRetry);
        ui.removeCallbacks(shake);
        ui.removeCallbacks(enterApp);
        ui.removeCallbacks(quietStop);
    }

    private static String bleKey(String classic) {
        String h = classic == null ? "" : classic.replace(":", "").replace("-", "");
        return "ble_" + h;
    }

    private void startScanQuiet() {
        if (pending == null || writeChar != null || rcspSeen) return;
        try {
            BluetoothAdapter ad = adapter();
            if (ad == null) return;
            scanner = ad.getBluetoothLeScanner();
            if (scanner == null) return;
            ScanSettings.Builder b = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
            if (Build.VERSION.SDK_INT >= 23) {
                b.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);
            }
            scanner.startScan(java.util.Collections.<ScanFilter>emptyList(), b.build(), scanCb);
            dbg("scan start");
            ui.removeCallbacks(quietStop);
            ui.postDelayed(quietStop, 12000);
        } catch (Exception ignored) {
        }
    }

    private void stopScanQuiet() {
        try {
            if (scanner != null) scanner.stopScan(scanCb);
        } catch (Exception ignored) {
        }
    }

    private void watchHandshake() {
        if (pending == null) return;
        if (emittedConnected) {
            emit("{\"type\":\"chip\",\"ok\":" + rcspSeen + ",\"rx\":" + rxBytes + "}");
            if (!rcspSeen) {
                dbg("handshake still silent rx=" + rxBytes + " via=" + via());
                kickRadio();
            }
            return;
        }
        // Media can be up while JieLi BLE is still coming. Do not kick the user home.
        if (writeChar == null && !sppLive()) {
            dbg("handshake: no chip yet — stay on device, keep hunting via=" + via());
            markConnected(false);
            if (sppOut == null) kickRadio();
            return;
        }
        markConnected(rcspSeen);
    }

    private void markConnected(boolean chip) {
        if (emittedConnected) {
            if (chip) noteChip();
            return;
        }
        emittedConnected = true;
        live = true;
        if (chip) chipTold = true;
        if (chip || !expectsJl()) stopScan();
        else {
            scanningWanted = false;
            emit("{\"type\":\"scan\",\"state\":\"stop\"}");
        }
        String name = "Carol";
        String id = "";
        if (pending != null) {
            id = pending.getAddress();
            String n = pending.getName();
            if (n != null && n.length() > 0) name = n;
        }
        emit("{\"type\":\"connected\",\"id\":\"" + esc(id) + "\",\"name\":\"" + esc(name)
                + "\",\"chip\":" + chip + ",\"model\":\"" + esc(modelFor(name, chip)) + "\"}");
        try {
            UserState.cacheLink(ctx, id, name, modelFor(name, chip), chip);
            CarolService.buds(ctx, true);
            StatusNote.connected(ctx);
            MixTile.refresh(ctx);
        } catch (Throwable ignored) {}
        emit(DeviceRoster.eventJson(ctx));
        emitAbsVol();
        emit("{\"type\":\"chip\",\"ok\":" + chip + ",\"rx\":" + rxBytes + "}");
        emitSysBatt();
        ui.postDelayed(sysBattRun, 1600);
        ui.postDelayed(sysBattRun, 4200);
    }

    /** The app already entered on A2DP. Tell the UI when the JieLi radio actually answers. */
    private void noteChip() {
        if (chipTold) return;
        chipTold = true;
        clearHunt();
        stopScan();
        String name = "Carol";
        String id = "";
        if (pending != null) {
            id = pending.getAddress();
            String n = pending.getName();
            if (n != null && n.length() > 0) name = n;
        }
        try {
            UserState.cacheLink(ctx, id, name, modelFor(name, true), true);
            MixTile.refresh(ctx);
        } catch (Throwable ignored) {}
        emit("{\"type\":\"chip\",\"ok\":true,\"rx\":" + rxBytes + "}");
        try { CarolService.chipUp(ctx); } catch (Throwable ignored) {}
    }

    private String modelFor(String name, boolean chip) {
        String model = modelOf(name);
        return model;
    }

    private void connectBlocking(BluetoothAdapter ad, String address, boolean includeStd) {
        if (writeChar != null || rcspSeen) {
            dbg("spp abort — gatt already live");
            return;
        }
        closeSppQuiet();
        authDone = false;
        authProgress = false;
        authRandom = null;
        try {
            ad.cancelDiscovery();
        } catch (Exception ignored) {
        }
        BluetoothDevice device = pending != null ? pending : ad.getRemoteDevice(address);

        List<UUID> uuids = sppCandidates(includeStd);
        try {
            ParcelUuid[] found = device.getUuids();
            if (found != null) {
                StringBuilder sb = new StringBuilder();
                for (ParcelUuid p : found) {
                    if (p == null) continue;
                    UUID u = p.getUuid();
                    String s = u.toString().toLowerCase(Locale.US);
                    if (sb.length() > 0) sb.append(',');
                    sb.append(s.substring(0, 8));
                    if (s.startsWith("0000110b") || s.startsWith("0000110e") || s.startsWith("0000111e")
                            || s.startsWith("00001108") || s.startsWith("0000110c") || s.startsWith("0000110f")) {
                        continue;
                    }
                    if (!includeStd && !isJlSpp(u)) continue;
                    uuids.add(u);
                }
                emit("{\"type\":\"probe\",\"sdp\":\"" + esc(sb.toString()) + "\"}");
            }
        } catch (Exception ignored) {
        }
        for (UUID u : uuids) {
            if (writeChar != null || rcspSeen) return;
            dbg("spp try " + u.toString().substring(0, 8));
            if (trySpp(device, u, false)) return;
            if (trySpp(device, u, true)) return;
        }
        if (includeStd) {
            for (int ch = 1; ch <= 16; ch++) {
                if (writeChar != null || rcspSeen) return;
                if (tryChannel(device, ch)) return;
            }
        }
        emit("{\"type\":\"link\",\"state\":\"spp-timeout\"}");
        dbg("spp timeout includeStd=" + includeStd);
    }

    private List<UUID> sppCandidates(boolean includeStd) {
        LinkedHashSet<UUID> out = new LinkedHashSet<>();
        out.add(UUID_JL);
        out.add(UUID_AE80);
        out.add(UUID_FE);
        out.add(UUID_CUSTOM);
        if (includeStd) out.add(UUID_SPP);
        return new ArrayList<>(out);
    }

    private static boolean isJlSpp(UUID u) {
        if (u == null) return false;
        String s = u.toString().toLowerCase(Locale.US);
        return s.contains("ae00") || s.contains("ae80") || s.contains("ae01")
                || s.contains("fe01") || s.contains("1107");
    }

    private boolean trySpp(BluetoothDevice device, UUID uuid, boolean insecure) {
        BluetoothSocket sock = null;
        Thread killer = null;
        try {
            sock = insecure
                    ? device.createInsecureRfcommSocketToServiceRecord(uuid)
                    : device.createRfcommSocketToServiceRecord(uuid);
            final BluetoothSocket target = sock;
            killer = new Thread(() -> {
                try {
                    Thread.sleep(2500);
                    try {
                        if (target != null && !target.isConnected()) target.close();
                    } catch (Exception ignored) {
                    }
                } catch (InterruptedException ignored) {
                }
            }, "spp-timeout");
            killer.setDaemon(true);
            killer.start();
            sock.connect();
            killer.interrupt();
            if (!sock.isConnected()) {
                closeQuiet(sock);
                return false;
            }
            return takeSocket(sock, device, uuid);
        } catch (Exception e) {
            closeQuiet(sock);
            return false;
        }
    }

    private boolean tryChannel(BluetoothDevice device, int ch) {
        BluetoothSocket sock = null;
        Thread killer = null;
        try {
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            sock = (BluetoothSocket) m.invoke(device, ch);
            if (sock == null) return false;
            final BluetoothSocket target = sock;
            killer = new Thread(() -> {
                try {
                    Thread.sleep(1200);
                    try {
                        if (target != null && !target.isConnected()) target.close();
                    } catch (Exception ignored) {
                    }
                } catch (InterruptedException ignored) {
                }
            }, "ch-timeout");
            killer.setDaemon(true);
            killer.start();
            sock.connect();
            killer.interrupt();
            if (!sock.isConnected()) {
                closeQuiet(sock);
                return false;
            }
            return takeSocket(sock, device, null);
        } catch (Exception e) {
            closeQuiet(sock);
            return false;
        }
    }

    private boolean takeSocket(BluetoothSocket sock, BluetoothDevice device, UUID uuid) throws IOException {
        if (writeChar != null || rcspSeen) {
            closeQuiet(sock);
            dbg("spp discarded — gatt already live");
            return true;
        }
        boolean jl = isJlSpp(uuid);
        spp = sock;
        sppOut = sock.getOutputStream();
        sppIn = sock.getInputStream();
        sppUuid = uuid;
        sppJl = jl;
        live = true;
        String name = device.getName();
        if (name == null || name.length() == 0) name = "Carol";
        remember(device.getAddress(), name);
        emit("{\"type\":\"link\",\"state\":\"spp\"}");
        dbg("link spp uuid=" + (uuid != null ? uuid.toString().substring(0, 8) : "ch") + " jl=" + jl);
        startReader();
        if (jl) {
            startAuthThenSync();
            return true;
        }
        io.execute(() -> {
            sleepQuiet(900);
            if (rcspSeen || rxBytes > 0) {
                startAuthThenSync();
                return;
            }
            if (writeChar != null) {
                dbg("spp held — gatt is still opening, socket stays until the chip answers");
                return;
            }
            dbg("spp classic keep uuid=" + (sppUuid != null ? sppUuid.toString().substring(0, 8) : "-"));
            classicCtrl = true;
            emit("{\"type\":\"link\",\"state\":\"classic\"}");
            startAuthThenSync();
        });
        return true;
    }

    private void startReader() {
        reader = new Thread(this::readLoop, "carol-spp");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        byte[] buf = new byte[512];
        while (live && sppIn != null) {
            try {
                int n = sppIn.read(buf);
                if (n <= 0) break;
                rxBytes += n;
                synchronized (rx) {
                    rx.write(buf, 0, n);
                    drainRx();
                }
            } catch (IOException e) {
                break;
            }
        }
    }

    private void drainRx() {
        byte[] d = rx.toByteArray();
        int i = 0;
        while (i < d.length) {
            if (d[i] == JlProtocol.PRE0) {
                if (i + 3 >= d.length) break;
                if (d[i + 1] != JlProtocol.PRE1 || d[i + 2] != JlProtocol.PRE2) {
                    i++;
                    continue;
                }
                int end = -1;
                for (int k = i + 3; k < d.length; k++) {
                    if (d[k] == JlProtocol.END) {
                        end = k;
                        break;
                    }
                }
                if (end < 0) break;
                onRcsp(Arrays.copyOfRange(d, i, end + 1));
                i = end + 1;
                continue;
            }
            int left = d.length - i;
            if (left >= 5 && d[i] == 2) {
                byte[] a = Arrays.copyOfRange(d, i, i + 5);
                if (Arrays.equals(a, JlProtocol.AUTH_OK) || a[0] == 2) {
                    onAuthBytes(a);
                    i += 5;
                    continue;
                }
            }
            if (left >= 17 && (d[i] == 0 || d[i] == 1)) {
                onAuthBytes(Arrays.copyOfRange(d, i, i + 17));
                i += 17;
                continue;
            }
            if (left < 17) break;
            i++;
        }
        rx.reset();
        if (i < d.length) rx.write(d, i, d.length - i);
    }

    private void onAuthBytes(byte[] b) {
        if (authDone) return;
        try {
            if (Arrays.equals(b, JlProtocol.AUTH_OK)) {
                authDone = true;
                afterAuth();
                markConnected(true);
                return;
            }
            if (b.length == 17 && b[0] == 1 && auth != null && authRandom != null) {
                byte[] expect = auth.getEncryptedAuthData(authRandom);
                if (expect != null && Arrays.equals(expect, b)) {
                    authProgress = writeRaw(JlProtocol.AUTH_OK);
                }
                return;
            }
            if (b.length == 17 && b[0] == 0 && auth != null) {
                byte[] enc = auth.getEncryptedAuthData(b);
                if (enc != null) writeRaw(enc);
            }
        } catch (Throwable ignored) {
        }
    }

    private void onRcsp(byte[] pkt) {
        if (pkt.length < 8) return;
        rcspSeen = true;
        if (sppOut != null && !sppJl && writeChar != null) {
            ui.post(() -> {
                if (sppOut == null || sppJl || writeChar == null || !rcspSeen) return;
                dbg("gatt answered — drop dummy spp");
                closeSppQuiet();
                emit("{\"type\":\"link\",\"state\":\"spp-dead\"}");
            });
        }
        try {
            if (!prefs().getBoolean("cache_chip", false)) {
                prefs().edit().putBoolean("cache_chip", true).apply();
                MixTile.refresh(ctx);
            }
        } catch (Throwable ignored) {
        }
        repairAncCycle();
        rxBytes += pkt.length;
        markConnected(true);
        int flags = pkt[3] & 0xFF;
        int opcode = pkt[4] & 0xFF;
        boolean isCmd = (flags & 0x80) != 0;
        int off = 8;
        if (!isCmd) off = 9;
        if (off >= pkt.length - 1) return;
        byte[] param = Arrays.copyOfRange(pkt, off, pkt.length - 1);
        if (opcode == JlProtocol.CMD_GET_TARGET_INFO) {
            parseTarget(param);
        } else if (opcode == JlProtocol.CMD_GET_SYS_INFO || opcode == JlProtocol.CMD_SET_SYS_INFO) {
            parseSysInfo(param);
        } else if (opcode == JlProtocol.CMD_ADV_GET_INFO) {
            parseAdv(param);
        } else if (opcode == JlProtocol.CMD_ADV_SETTINGS) {
            if (ancListRepair) {
                ancListRepair = false;
                dbg("anc cycle restored");
            }
        }
    }

    private void parseSysInfo(byte[] param) {
        if (param == null || param.length < 2) return;
        int b0 = param[0] & 0xFF;
        boolean skip = b0 == 0xFF || b0 == 0x00;
        ancHeard = false;
        parseSys(param, skip);
        if (!ancHeard && skip) parseSys(param, false);
    }

    private void parseSys(byte[] param, boolean skipFunc) {
        if (param == null || param.length < 2) return;
        int i = (skipFunc && param.length >= 3) ? 1 : 0;
        while (i + 2 <= param.length) {
            int size = param[i] & 0xFF;
            int type = param[i + 1] & 0xFF;
            if (size < 1 || i + 1 + size > param.length) break;
            byte[] val = Arrays.copyOfRange(param, i + 2, i + 1 + size);
            if (type == JlProtocol.ATTR_VOLUME && val.length >= 1) {
                emit("{\"type\":\"volume\",\"value\":" + (val[0] & 0xFF) + "}");
            } else if (type == JlProtocol.ATTR_BATTERY && val.length >= 1) {
                int l = val[0] & 0xFF;
                int r = val.length > 1 ? (val[1] & 0xFF) : l;
                int c = val.length > 2 ? (val[2] & 0xFF) : 0;
                emitBattery(l, r, c);
            } else if (type == JlProtocol.ATTR_NOISE_ALL && val.length >= 1) {
                int n = val[0] & 0xFF;
                boolean prefixed = n <= 8 && val.length >= 1 + n * 9;
                int start = prefixed ? 1 : 0;
                int count = prefixed ? n : (val.length / 9);
                int seen = 0;
                boolean ancBefore = ancFullSet;
                boolean transBefore = transFullSet;
                for (int o = start; o + 9 <= val.length; o += 9) {
                    int mode = val[o] & 0xFF;
                    if (mode >= 0 && mode <= 2) {
                        byte[] one = Arrays.copyOfRange(val, o, o + 9);
                        ancVm[mode] = one;
                        if (ancFactory[mode] == null && flagMax(u16(one, 1)) && flagMax(u16(one, 5))) {
                            ancFactory[mode] = Arrays.copyOf(one, 9);
                        }
                        if (mode == 1) noteAncDepth(one);
                        if (mode == 2) noteTransDepth(one);
                    }
                    seen++;
                    if (prefixed && seen >= count) break;
                }
                if (((ancFullSet && !ancBefore) || (transFullSet && !transBefore)) && lastAncMode.length() > 0) {
                    emitAnc(lastAncMode, lastAncAgree);
                }
            } else if (type == JlProtocol.ATTR_NOISE && val.length >= 1) {
                int mode = val[0] & 0xFF;
                boolean agree = earsAgree(val, mode);
                if (val.length >= 9) {
                    lastAncVm = Arrays.copyOf(val, 9);
                    if (mode >= 0 && mode <= 2) {
                        byte[] prev = ancVm[mode];
                        boolean keep = prev != null && presetSane(prev) && !presetSane(lastAncVm);
                        if (!keep) ancVm[mode] = Arrays.copyOf(lastAncVm, 9);
                    }
                }
                ancHeard = true;
                String m = mode == 1 ? "anc" : mode == 2 ? "transparency" : "off";
                if (mode == 1 && agree) noteAncDepth(val);
                if (mode == 2 && agree) noteTransDepth(val);
                dbg("buds anc=" + m + " bytes=" + val.length + " agree=" + agree
                        + (val.length >= 9 ? (" L=" + u16(val, 3) + " R=" + u16(val, 7)
                        + " flag1=" + u16(val, 1) + " flag5=" + u16(val, 5)) : ""));
                if (val.length >= 9 && !ancFlagRepaired && !ancRestoring && (mode == 1 || mode == 2)
                        && flagMax(u16(val, 1)) && flagMax(u16(val, 3)) && saneDepth(u16(val, 5))) {
                    ancFlagRepaired = true;
                    final int modeN = mode;
                    io.execute(() -> {
                        byte[] vm = ancTemplate(modeN);
                        if (vm == null || (writeChar == null && sppOut == null)) return;
                        dbg("restore anc bytes L=" + u16(vm, 3) + " R=" + u16(vm, 5));
                        writePkt(JlProtocol.setCurrentVm(vm, nextSn()));
                        sleepQuiet(200);
                        writePkt(JlProtocol.getAnc(nextSn()));
                    });
                }
                emitAnc(m, agree);
            }
            i += 1 + size;
        }
    }

    /**
     * ADV GET_INFO TLVs. Type 1 is the BLE name — never feed ADV into parseSys
     * (SYS type 1 is volume). Type 2 is the key table.
     */
    private void parseAdv(byte[] param) {
        if (param == null || param.length < 2) return;
        if (!walkAdv(param, 0)) {
            if (param.length > 1 && !walkAdv(param, 1) && param.length > 4) walkAdv(param, 4);
        }
    }

    private boolean walkAdv(byte[] param, int start) {
        boolean any = false;
        int i = start;
        while (i + 2 <= param.length) {
            int size = param[i] & 0xFF;
            int type = param[i + 1] & 0xFF;
            if (size < 1 || i + 1 + size > param.length) break;
            byte[] val = Arrays.copyOfRange(param, i + 2, i + 1 + size);
            if (type == JlProtocol.ADV_KEY && val.length >= 3) {
                ingestKeyTriples(val);
            } else if (type == JlProtocol.ADV_LED && val.length >= 2) {
                StringBuilder sb = new StringBuilder();
                for (int o = 0; o + 1 < val.length; o += 2) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(val[o] & 0xFF).append(':').append(val[o + 1] & 0xFF);
                }
                dbg("logo buds " + sb);
            } else if (type == JlProtocol.ADV_WORK && val.length >= 1) {
                int v = val[0] & 0xFF;
                workMode = v == 2 ? "game" : "normal";
                try { prefs().edit().putString("cache_work", workMode).apply(); } catch (Throwable ignored) {}
                emit("{\"type\":\"work\",\"mode\":\"" + workMode + "\"}");
            } else if (type == JlProtocol.ADV_INEAR && val.length >= 1) {
                int v = val[0] & 0xFF;
                if (v == 0) {
                    emit("{\"type\":\"inear\",\"absent\":true,\"on\":false}");
                } else {
                    boolean on = v == 2;
                    boolean stale = System.currentTimeMillis() < inEarWriteUntil && on != inEarOn;
                    if (!stale) {
                        inEarOn = on;
                        inEarKnown = true;
                        emit("{\"type\":\"inear\",\"on\":" + on + "}");
                    }
                }
            } else if (type == 0 && val.length >= 1) {
                int l = val[0] & 0xFF;
                int r = val.length > 1 ? (val[1] & 0xFF) : l;
                int c = val.length > 2 ? (val[2] & 0xFF) : 0;
                emitBattery(l, r, c);
            }
            any = true;
            i += 1 + size;
        }
        return any;
    }

    private void ingestKeyTriples(byte[] val) {
        int start = (val.length % 3 == 1) ? 1 : 0;
        if ((val.length - start) < 3 || (val.length - start) % 3 != 0) return;
        LinkedHashMap<Integer, Integer> next = new LinkedHashMap<>();
        for (int o = start; o + 2 < val.length; o += 3) {
            int side = val[o] & 0xFF;
            int act = val[o + 1] & 0xFF;
            int fn = val[o + 2] & 0xFF;
            if (side >= 1 && side <= 2 && act >= 1 && act <= 4) {
                next.put((side << 8) | act, fn);
            }
        }
        if (next.isEmpty()) return;
        keyMap.clear();
        keyMap.putAll(next);
        emitKeys();
    }

    private void seedStockKeys() {
        keyMap.clear();
        byte[] t = JlProtocol.STOCK_KEY_TRIPLES;
        for (int o = 0; o + 2 < t.length; o += 3) {
            int side = t[o] & 0xFF;
            int act = t[o + 1] & 0xFF;
            int fn = t[o + 2] & 0xFF;
            keyMap.put((side << 8) | act, fn);
        }
    }

    private byte[] packKeyMap() {
        if (keyMap.isEmpty()) seedStockKeys();
        byte[] triples = new byte[keyMap.size() * 3];
        int i = 0;
        for (Map.Entry<Integer, Integer> e : keyMap.entrySet()) {
            triples[i++] = (byte) ((e.getKey() >> 8) & 0xFF);
            triples[i++] = (byte) (e.getKey() & 0xFF);
            triples[i++] = (byte) (e.getValue() & 0xFF);
        }
        return triples;
    }

    private void emitKeys() {
        JSONObject o = new JSONObject();
        JSONObject map = new JSONObject();
        try {
            o.put("type", "keys");
            for (Map.Entry<Integer, Integer> e : keyMap.entrySet()) {
                int side = JlProtocol.sideFwToUi((e.getKey() >> 8) & 0xFF);
                int act = JlProtocol.actFwToUi(e.getKey() & 0xFF);
                int ui = JlProtocol.fnFwToUi(e.getValue());
                if (ui >= 0) map.put(side + "-" + act, ui);
            }
            o.put("map", map);
            emit(o.toString());
        } catch (Exception ignored) {
        }
    }

    /** Official TargetInfo TLVs (KZ Audio ParseHelper.parseTargetInfo). No function prefix. */
    private void parseTarget(byte[] param) {
        if (param == null || param.length < 2) return;
        walkTarget(param, 0);
        if (fwVersion.length() == 0 && fwAuth.length() == 0) walkTarget(param, 1);
        if (fwVersion.length() > 0 || fwAuth.length() > 0 || fwProj.length() > 0) {
            try {
                SharedPreferences.Editor ed = prefs().edit();
                if (fwVersion.length() > 0) ed.putString("fw_ver", fwVersion);
                if (fwAuth.length() > 0) ed.putString("fw_auth", fwAuth);
                if (fwProj.length() > 0) ed.putString("fw_proj", fwProj);
                ed.apply();
            } catch (Exception ignored) {
            }
            emitFirmware();
        }
    }

    private void walkTarget(byte[] param, int start) {
        int i = start;
        while (i + 2 <= param.length) {
            int size = param[i] & 0xFF;
            int type = param[i + 1] & 0xFF;
            if (size < 1 || i + 1 + size > param.length) break;
            byte[] val = Arrays.copyOfRange(param, i + 2, i + 1 + size);
            if (type == JlProtocol.TI_FIRMWARE && val.length >= 4) {
                int v = ((val[0] & 0xFF) << 24) | ((val[1] & 0xFF) << 16)
                        | ((val[2] & 0xFF) << 8) | (val[3] & 0xFF);
                String name = ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF)
                        + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
                if (val.length > 4) {
                    String ascii = ascii(Arrays.copyOfRange(val, 4, val.length));
                    if (ascii.length() >= 3) name = ascii;
                }
                fwVersion = name;
            } else if (type == JlProtocol.TI_AUTH_KEY) {
                String s = ascii(val);
                if (s.length() > 0) fwAuth = s;
            } else if (type == JlProtocol.TI_PROJECT) {
                String s = ascii(val);
                if (s.length() > 0) fwProj = s;
            } else if (type == JlProtocol.TI_VIDPID && val.length >= 4) {
                fwVid = ((val[0] & 0xFF) << 8) | (val[1] & 0xFF);
                fwPid = ((val[2] & 0xFF) << 8) | (val[3] & 0xFF);
            } else if (type == JlProtocol.TI_UBOOT && val.length >= 4 && fwVersion.length() == 0) {
                int v = ((val[0] & 0xFF) << 24) | ((val[1] & 0xFF) << 16)
                        | ((val[2] & 0xFF) << 8) | (val[3] & 0xFF);
                if (v != 0) {
                    fwVersion = ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF)
                            + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
                }
            }
            i += 1 + size;
        }
    }

    private static String ascii(byte[] b) {
        if (b == null || b.length == 0) return "";
        int n = b.length;
        while (n > 0 && (b[n - 1] == 0 || b[n - 1] == 0x20)) n--;
        if (n == 0) return "";
        try {
            return new String(b, 0, n, "UTF-8").trim();
        } catch (Exception e) {
            return new String(b, 0, n).trim();
        }
    }

    private void emitFirmware() {
        emit("{\"type\":\"firmware\",\"version\":\"" + esc(fwVersion)
                + "\",\"vid\":" + fwVid + ",\"pid\":" + fwPid
                + ",\"hasKeys\":" + (fwAuth.length() > 0 && fwProj.length() > 0) + "}");
    }

    String firmwareJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", fwVersion.length() > 0 ? fwVersion : prefs().getString("fw_ver", ""));
            o.put("hasKeys", (fwAuth.length() > 0 && fwProj.length() > 0)
                    || (prefs().getString("fw_auth", "").length() > 0
                    && prefs().getString("fw_proj", "").length() > 0));
            o.put("vid", fwVid);
            o.put("pid", fwPid);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    void checkFirmware() {
        io.execute(() -> {
            writePkt(JlProtocol.getTarget(nextSn()));
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            String auth = fwAuth.length() > 0 ? fwAuth : prefs().getString("fw_auth", "");
            String proj = fwProj.length() > 0 ? fwProj : prefs().getString("fw_proj", "");
            String ver = fwVersion.length() > 0 ? fwVersion : prefs().getString("fw_ver", "");
            emit(FwOta.check(auth, proj, ver));
        });
    }

    private void startAuthThenSync() {
        if (authDone) {
            afterAuth();
            return;
        }
        if (authProgress) return;
        authProgress = true;
        io.execute(() -> {
            boolean sent = false;
            try {
                ensureAuth();
                if (auth != null) {
                    authRandom = auth.getRandomAuthData();
                    if (authRandom != null && authRandom.length > 0) {
                        sent = writeRaw(authRandom);
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(sent ? 1600 : 250);
            } catch (InterruptedException ignored) {
            }
            if (!authDone) {
                if (rxBytes == 0 && writeChar == null && sppOut == null) {
                    authProgress = false;
                    dbg("auth timeout no rx — not marking done");
                    return;
                }
                dbg("auth timeout — continue without reply via=" + via());
                afterAuth();
            }
        });
    }

    private void afterAuth() {
        authDone = true;
        VolumeUnlock.tryDisable(ctx);
        VolumeUnlock.maxPcm(ctx);
        emitAbsVol();
        io.execute(() -> {
            writePkt(JlProtocol.getTarget(nextSn()));
            writePkt(JlProtocol.getInfo(nextSn()));
            writePkt(JlProtocol.getVolume(nextSn()));
            writePkt(JlProtocol.getAncNow(nextSn()));
            writePkt(JlProtocol.getAdvAll(nextSn()));
            sleepQuiet(350);
            writePkt(JlProtocol.getAdvAll(nextSn()));
        });
    }

    void emitAbsVol() {
        boolean off = VolumeUnlock.isDisabled(ctx);
        emit("{\"type\":\"absvol\",\"off\":" + off + "}");
    }

    private int nextSn() {
        seq = (seq + 1) & 0xFF;
        if (seq == 0) seq = 1;
        return seq;
    }

    private boolean sppLive() {
        return sppOut != null && (rxBytes > 0 || rcspSeen || sppJl || classicCtrl);
    }

    private String via() {
        boolean g = writeChar != null;
        if (g && sppOut != null) return "gatt+spp";
        if (g) return "gatt";
        if (sppOut != null && (rxBytes > 0 || rcspSeen)) return "spp";
        if (sppOut != null) return "classic";
        return "NONE";
    }

    private boolean writeRaw(byte[] pkt) {
        if (pkt == null) return false;
        boolean ok = false;
        boolean gattLive = writeChar != null && gatt != null;
        if (sppOut != null) {
            synchronized (writeLock) {
                try {
                    sppOut.write(pkt);
                    sppOut.flush();
                    ok = true;
                } catch (IOException ignored) {
                }
            }
        }
        if (gattLive) {
            ok |= gattWrite(pkt);
        }
        return ok;
    }

    private boolean gattWrite(byte[] pkt) {
        final BluetoothGatt g = gatt;
        final BluetoothGattCharacteristic ch = writeChar;
        if (g == null || ch == null || pkt == null) return false;
        int room = Math.max(20, gattMtu - 3);
        if (pkt.length > room) {
            dbg("gatt write " + pkt.length + " bytes > mtu " + gattMtu + " — wait, do not split the frame");
            try { g.requestMtu(517); } catch (Exception ignored) {}
            ui.postDelayed(() -> {
                if (gattMtu - 3 >= pkt.length) gattWrite(pkt);
                else dbg("gatt write still larger than mtu, left unsent");
            }, 220);
            return false;
        }
        final int withRsp = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        final int noRsp = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        int props = 0;
        try { props = ch.getProperties(); } catch (Exception ignored) {}
        // HyperOS 15/16 drops WRITE_NO_RESPONSE. Prefer a response whenever the char allows it.
        final int type;
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) type = withRsp;
        else if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) type = noRsp;
        else type = withRsp;
        final byte[] data = pkt;
        synchronized (gattIo) {
            long until = System.currentTimeMillis() + 700;
            while (gattBusy && System.currentTimeMillis() < until) {
                try { gattIo.wait(40); } catch (InterruptedException ignored) { break; }
            }
            gattBusy = true;
            final boolean[] sent = { false };
            Runnable run = () -> {
                boolean ok = false;
                try {
                    BluetoothGatt gg = gatt;
                    BluetoothGattCharacteristic cc = writeChar;
                    if (gg != null && cc != null) {
                        try { cc.setWriteType(type); } catch (Exception ignored) {}
                        try { cc.setValue(data); } catch (Exception ignored) {}
                        if (Build.VERSION.SDK_INT >= 33) {
                            int r = gg.writeCharacteristic(cc, data, type);
                            ok = r == BluetoothGatt.GATT_SUCCESS;
                            if (!ok && type != withRsp) {
                                try { cc.setWriteType(withRsp); } catch (Exception ignored) {}
                                r = gg.writeCharacteristic(cc, data, withRsp);
                                ok = r == BluetoothGatt.GATT_SUCCESS;
                            }
                        } else {
                            ok = gg.writeCharacteristic(cc);
                            if (!ok && type != withRsp) {
                                try { cc.setWriteType(withRsp); } catch (Exception ignored) {}
                                ok = gg.writeCharacteristic(cc);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    ok = false;
                }
                sent[0] = ok;
                if (!ok) {
                    synchronized (gattIo) {
                        gattBusy = false;
                        gattIo.notifyAll();
                    }
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) run.run();
            else ui.post(run);
            try { gattIo.wait(type == noRsp ? 160 : 550); } catch (InterruptedException ignored) {}
            boolean ok = sent[0] || !gattBusy;
            gattBusy = false;
            gattIo.notifyAll();
            return ok;
        }
    }

    private boolean writePkt(byte[] pkt) {
        return writeRaw(pkt);
    }

    private Context gattCtx() {
        try {
            if (host instanceof Activity) {
                Activity a = (Activity) host;
                if (!a.isFinishing() && (Build.VERSION.SDK_INT < 17 || !a.isDestroyed())) return a;
            } else if (host != null) {
                return host;
            }
        } catch (Exception ignored) {
        }
        return ctx;
    }

    private BluetoothGatt connectGattNow(BluetoothDevice d, int transport, boolean auto) {
        if (d == null) return null;
        Context c = gattCtx();
        // PHY_LE_1M + AUTO/BR/EDR on HyperOS is a 133. Handler+PHY only for explicit LE.
        if (Build.VERSION.SDK_INT >= 26 && transport == BluetoothDevice.TRANSPORT_LE && !auto) {
            return d.connectGatt(c, false, gattCb, transport, BluetoothDevice.PHY_LE_1M_MASK, ui);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return d.connectGatt(c, auto, gattCb, transport);
        }
        return d.connectGatt(c, auto, gattCb);
    }

    private BluetoothDevice gattDeviceForTry(int tryNo) {
        return gattTarget;
    }

    private int gattTransportForTry(int tryNo) {
        int n = tryNo % 3;
        if (n == 0) return BluetoothDevice.TRANSPORT_AUTO;
        if (n == 1) return BluetoothDevice.TRANSPORT_LE;
        return BluetoothDevice.TRANSPORT_BREDR;
    }

    private boolean gattAutoForTry(int tryNo) {
        return false;
    }

    private static long gattBackoff(int nextTry, boolean hard) {
        int shift = Math.max(0, Math.min(nextTry - 1, 4));
        long base = hard ? 1000L : 500L;
        long delay = base << shift;
        return delay > 8000L ? 8000L : delay;
    }

    /** System Bluetooth went off. Drop the radio objects before the stack does. */
    void radioOff() {
        dbg("bluetooth off — drop gatt");
        live = false;
        writeChar = null;
        rcspSeen = false;
        gattUp = false;
        clearHunt();
        stopScan();
        stopScanQuiet();
        closeSppQuiet();
        closeGattQuiet();
        pending = null;
        gattTarget = null;
        try { CarolService.chipUp(ctx); } catch (Throwable ignored) {}
        try { CarolService.buds(ctx, false); } catch (Throwable ignored) {}
        try { StatusNote.clear(ctx); } catch (Throwable ignored) {}
        emit("{\"type\":\"radio\",\"state\":\"off\"}");
        emit("{\"type\":\"disconnected\",\"radio\":\"off\"}");
    }

    private int gattTryLimit() {
        return 6;
    }

    private void openGatt(int tryNo) {
        if (!expectsJl() && gattTarget == null) {
            dbg("gatt skip — this model has no BLE radio");
            return;
        }
        if (pending == null && gattTarget == null) return;
        if (sppBusy) {
            dbg("gatt wait — spp socket is opening");
            final int again = tryNo;
            ui.postDelayed(() -> openGatt(again), 400);
            return;
        }
        if (gattTarget == null) {
            dbg("gatt wait for BLE ad — keep a2dp");
            startScanQuiet();
            return;
        }
        if (writeChar != null) return;
        if (tryNo >= gattTryLimit()) tryNo = 0;
        if (gatt != null && System.currentTimeMillis() - gattAttemptAt < 1500 && tryNo == connectTries) {
            dbg("gatt in-flight skip try=" + tryNo);
            return;
        }
        connectTries = tryNo;
        if (gattTarget != null) stopScanQuiet();
        closeGattQuiet();
        int transport = BluetoothDevice.TRANSPORT_LE;
        if (Build.VERSION.SDK_INT >= 23) transport = gattTransportForTry(tryNo);
        BluetoothDevice target = gattDeviceForTry(tryNo);
        if (target == null) return;
        boolean auto = gattAutoForTry(tryNo);
        String tname = transport == BluetoothDevice.TRANSPORT_LE ? "LE"
                : transport == BluetoothDevice.TRANSPORT_AUTO ? "AUTO" : "BR/EDR";
        dbg("gatt connect try=" + tryNo + " transport=" + tname + (auto ? " auto" : "")
                + " mac=" + target.getAddress()
                + " classic=" + (pending != null ? pending.getAddress() : "-")
                + (gattTarget != null ? " scan-hit" : " bonded"));
        gattAttemptAt = System.currentTimeMillis();
        try {
            gatt = connectGattNow(target, transport, auto);
        } catch (Exception e) {
            dbg("gatt connect FAIL " + e.getClass().getSimpleName());
        }
    }

    void disconnect() {
        userOff = true;
        stopFind();
        ui.removeCallbacks(findRightRun);
        ui.removeCallbacks(findStopRun);
        ui.removeCallbacks(ancPersistRun);
        ui.removeCallbacks(workPersistRun);
        ui.removeCallbacks(inEarPersistRun);
        ui.removeCallbacks(sysBattRun);
        ancPersistArmed = false;
        live = false;
        pending = null;
        clearHunt();
        stopScan();
        stopScanQuiet();
        writeChar = null;
        if (mediaPaused) resumeA2dp();
        gattTarget = null;
        derivedMacs.clear();
        keyMap.clear();
        closeSppQuiet();
        closeGattQuiet();
        try { CarolService.buds(ctx, false); } catch (Throwable ignored) {}
        try { CarolService.chipUp(ctx); } catch (Throwable ignored) {}
        try { prefs().edit().putBoolean("cache_chip", false).apply(); } catch (Throwable ignored) {}
        try { StatusNote.clear(ctx); MixTile.refresh(ctx); } catch (Throwable ignored) {}
        if (DeviceRoster.size(ctx) > 1) {
            emit("{\"type\":\"disconnected\",\"keep\":true}");
            emit(DeviceRoster.eventJson(ctx));
        } else {
            emit("{\"type\":\"disconnected\"}");
        }
    }

    void switchTo(String id) {
        if (id == null || id.length() == 0) return;
        DeviceRoster.setActive(ctx, id);
        SpatialFx.get(ctx).bindDevice(id);
        if (id.startsWith("usb:")) {
            try {
                UsbDac.get(ctx).setExclusive(true);
            } catch (Exception ignored) {
            }
            JSONObject row = DeviceRoster.item(ctx, id);
            String name = row != null ? row.optString("name", "USB-C") : "USB-C";
            String model = row != null ? row.optString("model", "typec") : "typec";
            emit("{\"type\":\"connected\",\"id\":\"" + esc(id) + "\",\"name\":\"" + esc(name)
                    + "\",\"chip\":false,\"model\":\"" + esc(model) + "\",\"usb\":true}");
            emit(DeviceRoster.eventJson(ctx));
            return;
        }
        try {
            UsbDac.get(ctx).setExclusive(false);
        } catch (Exception ignored) {
        }
        connect(id);
    }

    void forget(String id) {
        DeviceRoster.remove(ctx, id);
        emit(DeviceRoster.eventJson(ctx));
        String next = DeviceRoster.activeId(ctx);
        if (next.length() > 0) switchTo(next);
        else disconnect();
    }

    private void closeSppQuiet() {
        synchronized (writeLock) {
            try {
                if (sppIn != null) sppIn.close();
            } catch (Exception ignored) {
            }
            sppIn = null;
            try {
                if (sppOut != null) sppOut.close();
            } catch (Exception ignored) {
            }
            sppOut = null;
            try {
                if (spp != null) spp.close();
            } catch (Exception ignored) {
            }
            spp = null;
        }
        if (reader != null) {
            try {
                reader.interrupt();
            } catch (Exception ignored) {
            }
            reader = null;
        }
    }

    private void closeGattQuiet() {
        try {
            if (gatt != null) {
                try {
                    Method refresh = gatt.getClass().getMethod("refresh");
                    refresh.invoke(gatt);
                } catch (Exception ignored) {
                }
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {
        }
        gatt = null;
        writeChar = null;
        gattUp = false;
    }

    private static void closeQuiet(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    void send(byte[] pkt) {
        writePkt(pkt);
    }

    void setVolume(int step) {
        VolumeUnlock.tryDisable(ctx);
        VolumeUnlock.maxPcm(ctx);
        send(JlProtocol.volume(step, nextSn()));
    }

    /** The Carol logo ignores ADV LED and the light attribute. Do not reboot for it. */
    void setLogo(int pct) {
        dbg("logo ignored by firmware pct=" + pct);
    }

    void commitLogo(int pct) {
        dbg("logo ignored by firmware pct=" + pct);
    }
    void setAnc(String mode) {
        final int m = "anc".equals(mode) ? 1 : "transparency".equals(mode) ? 2 : 0;
        ancWant = m == 1 ? "anc" : m == 2 ? "transparency" : "off";
        ancPersistArmed = false;
        ancPersistMode = m;
        ui.removeCallbacks(ancPersistRun);
        ui.removeCallbacks(workPersistRun);
        ancWriteUntil = System.currentTimeMillis() + 8000;
        ancHealed = "";
        ancHealTries = 0;
        ancButtonTries = 0;
        try { UserState.cacheAnc(ctx, ancWant); } catch (Throwable ignored) {}
        try {
            StatusNote.anc(ctx, ancWant);
            MixTile.refresh(ctx);
        } catch (Throwable ignored) {}
        io.execute(() -> {
            String v = via();
            dbg("anc tap " + mode + " -> " + m + " via=" + v + " mtu=" + gattMtu
                    + " chip=" + rcspSeen + " auth=" + authDone + " live=" + live
                    + " dummy=" + dummySpp());
            if (!expectsJl() && writeChar == null) {
                dbg("anc skip — stock firmware has no BLE radio (nRF sees Carol Pro, not Carol)");
                return;
            }
            if (writeChar == null && sppOut == null && pending != null) {
                dbg("anc: open 1101 now");
                if (!trySpp(pending, UUID_SPP, false)) trySpp(pending, UUID_SPP, true);
            }
            if (dummySpp() && writeChar == null) {
                dbg("anc dummy — hunt ble, keep a2dp");
                startScanQuiet();
                ui.post(() -> openGatt(0));
                sleepQuiet(800);
            }
            if (writeChar == null && sppOut == null) {
                dbg("anc no radio — hunt gatt");
                ui.post(() -> openGatt(connectTries));
                sleepQuiet(500);
            }
            if (writeChar == null && sppOut == null) {
                dbg("anc FAIL still no radio via=" + via());
                return;
            }
            dbg("anc write via=" + via() + " spp=" + (sppOut != null) + " gatt=" + (writeChar != null));
            writePkt(JlProtocol.setAncMode(m, nextSn()));
            boolean g = writePkt(JlProtocol.getAnc(nextSn()));
            dbg("anc get " + (g ? "ok" : "FAIL") + " waiting for buds…");
        });
    }

    boolean chipReady() {
        return live && (rcspSeen || writeChar != null || (sppOut != null && !dummySpp()));
    }

    /**
     * Resend the current noise mode and, when the live report has a flag-sized
     * max (16384), equalize current bytes only. Max bytes are copied unchanged.
     * Never ADV type 10 and never work-mode.
     */
    void matchEars(String mode) {
        int m;
        if ("anc".equals(mode)) m = 1;
        else if ("transparency".equals(mode)) m = 2;
        else if ("off".equals(mode) || "normal".equals(mode)) m = 0;
        else if (ancPersistMode >= 0 && ancPersistMode <= 2 && ancWant.length() > 0) m = ancPersistMode;
        else m = "anc".equals(lastAncMode) ? 1 : "transparency".equals(lastAncMode) ? 2 : 0;
        final int modeN = m;
        ancWant = m == 1 ? "anc" : m == 2 ? "transparency" : "off";
        ancPersistArmed = true;
        ancPersistMode = m;
        ui.removeCallbacks(ancPersistRun);
        ui.removeCallbacks(workPersistRun);
        ancWriteUntil = System.currentTimeMillis() + 8000;
        ancHealed = "";
        ancHealTries = 0;
        ancButtonTries = 0;
        try { UserState.cacheAnc(ctx, ancWant); } catch (Throwable ignored) {}
        try {
            StatusNote.anc(ctx, ancWant);
            MixTile.refresh(ctx);
        } catch (Throwable ignored) {}
        io.execute(() -> {
            dbg("match ears mode=" + modeN + " via=" + via());
            if (writeChar == null && sppOut == null) {
                dbg("match ears FAIL no radio");
                return;
            }
            writeEqual(modeN);
            writePkt(JlProtocol.getAnc(nextSn()));
        });
    }

    String linkDebug() {
        String v = via();
        return "{\"type\":\"debug\",\"spp\":" + sppLive()
                + ",\"gatt\":" + (writeChar != null)
                + ",\"mtu\":" + gattMtu
                + ",\"chip\":" + rcspSeen
                + ",\"live\":" + live
                + ",\"auth\":" + authDone
                + ",\"rx\":" + rxBytes
                + ",\"via\":\"" + v + "\""
                + ",\"msg\":\"link via=" + v
                + " mtu=" + gattMtu + " chip=" + rcspSeen + " rx=" + rxBytes + "\"}";
    }

    private void dbg(String msg) {
        emit("{\"type\":\"debug\",\"msg\":\"" + esc(msg) + "\"}");
    }

    /**
     * JieLi order: mode, left max, left current, right max, right current.
     * 2.23.92 parked the gain in the right-max slot and the flag in the left current.
     * This puts both flags back and both gains in the current slots.
     */
    /** The left bud only applies a mode change, so step through the other mode first. */
    private void writeEqual(int modeN) {
        byte[] vm = liveBoth(modeN);
        if (modeN == 1 || modeN == 2) {
            byte[] kick = liveBoth(modeN == 1 ? 2 : 1);
            int depth = u16(vm, 3);
            putU16(kick, 3, depth);
            putU16(kick, 5, depth);
            putU16(kick, 7, depth);
            dbg("wake left via mode " + (kick[0] & 0xFF));
            writePkt(JlProtocol.setCurrentVm(kick, nextSn()));
            sleepQuiet(280);
        }
        dbg("both ears mode=" + modeN + " L=" + u16(vm, 3) + " R=" + u16(vm, 7));
        for (int i = 0; i < 3; i++) {
            writePkt(JlProtocol.setCurrentVm(vm, nextSn()));
            sleepQuiet(320);
        }
    }

    private byte[] liveBoth(int mode) {
        byte[] base = vmSave(mode);
        int depth = mode == 0 ? 0 : u16(base, 3);
        byte[] vm = new byte[9];
        vm[0] = (byte) mode;
        putU16(vm, 1, u16(base, 1));
        putU16(vm, 3, depth);
        putU16(vm, 5, depth);
        putU16(vm, 7, depth);
        return vm;
    }

    /** Flags at 1 and 5, same gain at 3 and 7. Jieli VoiceMode, the slot the hold button loads. */
    private byte[] vmSave(int mode) {
        byte[] fac = (mode >= 0 && mode <= 2) ? ancFactory[mode] : null;
        int lmax = fac != null && flagMax(u16(fac, 1)) ? u16(fac, 1) : 0x4000;
        int rmax = fac != null && flagMax(u16(fac, 5)) ? u16(fac, 5) : lmax;
        int depth = 0;
        if (mode != 0) depth = earDepth();
        byte[] vm = new byte[9];
        vm[0] = (byte) mode;
        putU16(vm, 1, lmax);
        putU16(vm, 3, mode == 0 ? 0 : depth);
        putU16(vm, 5, rmax);
        putU16(vm, 7, mode == 0 ? 0 : depth);
        return vm;
    }

    /** Loudest real gain we have seen. A dead transparency slot must not silence the left ear. */
    private int earDepth() {
        int depth = 0;
        for (int m = 1; m <= 2; m++) {
            byte[] fac = ancFactory[m];
            if (fac != null && fac.length >= 9) depth = Math.max(depth, sharedDepth(u16(fac, 3), u16(fac, 7)));
        }
        if (lastAncVm != null && lastAncVm.length >= 9) {
            depth = Math.max(depth, sharedDepth(u16(lastAncVm, 3), u16(lastAncVm, 7)));
        }
        depth = Math.max(depth, borrowedDepth());
        if (depth < 64) depth = 0x012C;
        return depth;
    }

    /** Left current is byte 3, right current is byte 7. A flag in byte 5 is the max, not a mismatch. */
    private static boolean currentsMatch(byte[] val, int mode) {
        if (val == null || val.length < 9) return false;
        int l = u16(val, 3);
        int r = u16(val, 7);
        if (flagMax(l) || flagMax(r)) return false;
        if (mode == 0) return l == 0 && r == 0;
        return l > 0 && l == r;
    }

    private byte[] ancTemplate(int mode) {
        byte[] base = (mode >= 0 && mode <= 2) ? ancFactory[mode] : null;
        if (base == null && mode >= 0 && mode <= 2) base = ancVm[mode];
        if (base == null) base = lastAncVm;
        return safeVm(base, mode);
    }

    private byte[] matchTemplate(int mode) {
        byte[] base = (mode >= 0 && mode <= 2) ? ancFactory[mode] : null;
        if (base == null) base = lastAncVm;
        if (base == null && mode >= 0 && mode <= 2) base = ancVm[mode];
        return safeVm(base, mode);
    }

    private byte[] safeVm(byte[] raw, int mode) {
        int f1 = raw != null && raw.length >= 9 ? u16(raw, 1) : 0;
        int f3 = raw != null && raw.length >= 9 ? u16(raw, 3) : 0;
        int f5 = raw != null && raw.length >= 9 ? u16(raw, 5) : 0;
        int f7 = raw != null && raw.length >= 9 ? u16(raw, 7) : 0;
        int lmax = flagMax(f1) ? f1 : 0x4000;
        int rmax = flagMax(f7) ? f7 : lmax;
        int lcur = saneDepth(f3) ? f3 : 0;
        int rcur = saneDepth(f5) ? f5 : 0;
        if (lcur == 0 && saneDepth(f7)) lcur = f7;
        if (rcur == 0 && saneDepth(f7)) rcur = f7;
        if (!saneDepth(lcur) && saneDepth(rcur)) lcur = rcur;
        if (!saneDepth(rcur) && saneDepth(lcur)) rcur = lcur;
        int depth = sharedDepth(lcur, rcur);
        if (mode != 0 && depth <= 0) depth = borrowedDepth();
        if (mode != 0 && depth <= 0) depth = 0x012C;
        byte[] vm = new byte[9];
        vm[0] = (byte) mode;
        putU16(vm, 1, lmax);
        putU16(vm, 5, rmax);
        if (mode == 0) {
            putU16(vm, 3, 0);
            putU16(vm, 7, 0);
        } else {
            putU16(vm, 3, depth);
            putU16(vm, 7, depth);
        }
        return vm;
    }

    /** Official VoiceMode: max flags at 1 and 5, currents at 3 and 7. */
    private static boolean presetSane(byte[] vm) {
        if (vm == null || vm.length < 9) return false;
        if (!flagMax(u16(vm, 1)) || !flagMax(u16(vm, 5))) return false;
        return !flagMax(u16(vm, 3)) && !flagMax(u16(vm, 7));
    }

    private static boolean flagMax(int v) {
        return v >= 1024;
    }

    private static int sharedDepth(int l, int r) {
        int saneL = saneDepth(l) ? l : 0;
        int saneR = saneDepth(r) ? r : 0;
        if (saneL > 0 && saneR > 0) return Math.max(saneL, saneR);
        return Math.max(saneL, saneR);
    }

    /** A real gain. 16384 is the flag in the max field, not a depth to copy. */
    private static boolean saneDepth(int v) {
        return v > 0 && v <= 512;
    }

    private int borrowedDepth() {
        byte[][] sources = new byte[][] { ancFactory[1], ancFactory[2], ancVm[1], ancVm[2], lastAncVm };
        for (byte[] vm : sources) {
            if (vm == null || vm.length < 9) continue;
            for (int off : new int[] { 3, 7, 5 }) {
                int v = u16(vm, off);
                if (saneDepth(v)) return v;
            }
        }
        return 0;
    }

    private static void putU16(byte[] d, int o, int v) {
        d[o] = (byte) ((v >> 8) & 0xFF);
        d[o + 1] = (byte) (v & 0xFF);
    }

    /** Put standard, ANC, and transparency back on the bud hold cycle. One write per connect. */
    private void repairAncCycle() {
        if (ancCycleSent) return;
        ancCycleSent = true;
        io.execute(() -> {
            sleepQuiet(600);
            if (writeChar == null && sppOut == null) return;
            ancListRepair = true;
            writePkt(JlProtocol.ancCycle(nextSn()));
        });
    }

    void refreshAnc() {
        long now = System.currentTimeMillis();
        if (ancPersistArmed && now < ancWriteUntil) return;
        if (now >= ancWriteUntil) ancPersistArmed = false;
        if (now - ancAt < 2500) return;
        ancAt = now;
        io.execute(() -> writePkt(JlProtocol.getAncNow(nextSn())));
    }

    private void emitAnc(String m, boolean agree) {
        ancAt = System.currentTimeMillis();
        String prev = lastAncMode;
        boolean same = m.equals(lastAncMode) && agree == lastAncAgree;
        lastAncMode = m;
        lastAncAgree = agree;
        if (!ancRestored) ancRestored = true;
        boolean inFlight = System.currentTimeMillis() < ancWriteUntil && ancWant.length() > 0;
        boolean holdSave = System.currentTimeMillis() < ancSaveUntil && ancWant.length() > 0 && !m.equals(ancWant);
        boolean stale = holdSave || (inFlight && !m.equals(ancWant) && !prev.equals(ancWant));
        if (!stale) {
            try { UserState.cacheAnc(ctx, m); } catch (Throwable ignored) {}
            try {
                StatusNote.anc(ctx, m);
                MixTile.refresh(ctx);
            } catch (Throwable ignored) {}
        }
        if (ancPersistArmed && agree && ancWant.length() > 0 && m.equals(ancWant)) {
            ancPersistArmed = false;
            ui.removeCallbacks(ancPersistRun);
            ui.postDelayed(ancPersistRun, 500);
        }
        if (agree && m.equals(ancWant)) ancButtonTries = 0;
        if (agree) ancHealTries = 0;
        boolean tell = !same || (ancFullSet && !ancToldSupport) || (transFullSet && !transToldSupport);
        if (ancFullSet) ancToldSupport = true;
        if (transFullSet) transToldSupport = true;
        if ("anc".equals(m) && !ancRestoring) ancPct = reportedPct();
        if ("transparency".equals(m) && !ancRestoring) transPct = reportedTransPct();
        if (!tell) return;
        emit("{\"type\":\"anc\",\"mode\":\"" + m + "\",\"agree\":" + agree
                + ",\"supported\":" + ancFullSet + ",\"pct\":" + ancPct
                + ",\"transSupported\":" + transFullSet + ",\"transPct\":" + transPct + "}");
    }

    /**
     * Store the mode the user is hearing into the table the hold button loads.
     * Only this mode is replaced. The other two slots are left as last saved.
     */
    void saveAncMode(String mode) {
        final int m = "anc".equals(mode) ? 1 : "transparency".equals(mode) ? 2 : 0;
        io.execute(() -> {
            if (writeChar == null && sppOut == null) {
                dbg("save mode FAIL no radio");
                return;
            }
            ui.removeCallbacks(ancPersistRun);
            ancPersistArmed = false;
            writeEqual(m);
            dbg("save mode " + m);
            emit("{\"type\":\"debug\",\"msg\":\"saved mode " + m + " on the buds\"}");
        });
    }

    private int ancAutoMode = -1;
    private boolean ancSlotsSaved;

    /**
     * The hold button loads the slot saved for that mode, not the live packet.
     * Write Off, Noise reduction, and Transparency once, then leave the buds
     * on the mode they were already using.
     */
    private void persistAncSlots(int stay) {
        if (ancSlotsSaved || ancRestoring) return;
        if (stay < 0 || stay > 2) stay = 1;
        ancSlotsSaved = true;
        ancButtonSaving = true;
        final int land = stay;
        io.execute(() -> {
            try {
                if (writeChar == null && sppOut == null) return;
                dbg("save anc table");
                byte[] vm0 = matchTemplate(0);
                byte[] vm1 = matchTemplate(1);
                byte[] vm2 = matchTemplate(2);
                ancFactory[0] = Arrays.copyOf(vm0, 9);
                ancFactory[1] = Arrays.copyOf(vm1, 9);
                ancFactory[2] = Arrays.copyOf(vm2, 9);
                boolean table = writePkt(JlProtocol.setAncTable(vm0, vm1, vm2, nextSn()));
                dbg("anc table " + (table ? "ok" : "FAIL"));
                sleepQuiet(200);
                for (byte[] vm : new byte[][] { vm0, vm1, vm2 }) {
                    writePkt(JlProtocol.setCurrentVm(vm, nextSn()));
                    sleepQuiet(180);
                }
                writePkt(JlProtocol.setCurrentVm(land == 2 ? vm2 : land == 0 ? vm0 : vm1, nextSn()));
                writePkt(JlProtocol.ancCycle(nextSn()));
                sleepQuiet(80);
                writePkt(JlProtocol.getAnc(nextSn()));
            } finally {
                ancButtonSaving = false;
            }
        });
    }

    /** Same writes as Match ears: mode command, then both ears at the same gain. */
    private void saveMode(int modeN) {
        if (ancButtonSaving || modeN < 0 || modeN > 2) return;
        if (modeN != ancAutoMode) ancButtonTries = 0;
        if (ancButtonTries >= 4) return;
        ancButtonTries++;
        ancAutoMode = modeN;
        ancButtonSaving = true;
        ancWant = modeN == 1 ? "anc" : modeN == 2 ? "transparency" : "off";
        ancPersistMode = modeN;
        ancPersistArmed = false;
        ancSaveUntil = System.currentTimeMillis() + 2500;
        ui.removeCallbacks(ancPersistRun);
        io.execute(() -> {
            try {
                if (writeChar == null && sppOut == null) return;
                dbg("auto match mode=" + modeN);
                writeEqual(modeN);
                writePkt(JlProtocol.getAnc(nextSn()));
            } finally {
                ancButtonSaving = false;
            }
        });
    }

    private void noteAncDepth(byte[] val) {
        if (ancFullSet || ancRestoring || val == null || val.length < 9 || ancPct < 100) return;
        if ((val[0] & 0xFF) != 1) return;
        byte[] fixed = safeVm(val, 1);
        if (fixed == null) return;
        int d = sharedDepth(u16(fixed, 3), u16(fixed, 5));
        if (d <= 0) return;
        ancFullL = d;
        ancFullR = d;
        ancFullSet = true;
    }

    private void noteTransDepth(byte[] val) {
        if (transFullSet || ancRestoring || val == null || val.length < 9 || transPct < 100) return;
        if ((val[0] & 0xFF) != 2) return;
        byte[] fixed = safeVm(val, 2);
        if (fixed == null) return;
        int d = sharedDepth(u16(fixed, 3), u16(fixed, 5));
        if (d <= 0) return;
        transFullL = d;
        transFullR = d;
        transFullSet = true;
    }

    private int reportedTransPct() {
        if (!transFullSet || lastAncVm == null || lastAncVm.length < 9) return transPct;
        if ((lastAncVm[0] & 0xFF) != 2) return transPct;
        byte[] fixed = safeVm(lastAncVm, 2);
        if (fixed == null) return transPct;
        int depth = sharedDepth(u16(fixed, 3), u16(fixed, 5));
        int full = Math.min(transFullL, transFullR);
        if (full <= 0 || !saneDepth(depth)) return transPct;
        return Math.max(0, Math.min(100, depth * 100 / full));
    }

    private int reportedPct() {
        if (!ancFullSet || lastAncVm == null || lastAncVm.length < 9) return ancPct;
        byte[] fixed = safeVm(lastAncVm, 1);
        if (fixed == null) return ancPct;
        int depth = sharedDepth(u16(fixed, 3), u16(fixed, 5));
        int full = Math.min(ancFullL, ancFullR);
        if (full <= 0 || !saneDepth(depth)) return ancPct;
        return Math.max(0, Math.min(100, depth * 100 / full));
    }

    /**
     * Depth only. Refuses unless the buds are already in ANC.
     * 100 writes the captured default currents. Max bytes are never touched.
     */
    void setStrength(int pct) {
        dbg("strength slider removed");
    }

    void setTransStrength(int pct) {
        dbg("transparency slider removed");
    }

    /** Strength 100 if ANC is already on, then a mode cycle with no strength attached. */
    void restoreAnc() {
        if (ancRestoring) return;
        ancRestoring = true;
        final String desired = ancWant.length() > 0 ? ancWant
                : (lastAncMode.length() > 0 ? lastAncMode : "transparency");
        ancPersistArmed = false;
        ui.removeCallbacks(ancPersistRun);
        io.execute(() -> {
            try {
                dbg("restore anc start want=" + desired);
                if ("anc".equals(lastAncMode)) writeStrengthNow(100);
                for (String step : new String[]{"off", "transparency", "anc", "off"}) {
                    writeModeOnly(step);
                    sleepQuiet(420);
                    if ("anc".equals(step)) {
                        lastAncMode = "anc";
                        writeStrengthNow(100);
                        sleepQuiet(80);
                    }
                }
                writeModeOnly(desired);
                sleepQuiet(200);
                if ("anc".equals(desired)) {
                    lastAncMode = "anc";
                    writeStrengthNow(100);
                }
                ancPct = 100;
                writePkt(JlProtocol.getAnc(nextSn()));
                String mode = "anc".equals(desired) ? "anc" : "transparency".equals(desired) ? "transparency" : "off";
                emit("{\"type\":\"anc\",\"mode\":\"" + mode + "\",\"agree\":true,\"supported\":" + ancFullSet
                        + ",\"pct\":100,\"restored\":true}");
            } finally {
                ancRestoring = false;
            }
        });
    }

    private void writeModeOnly(String mode) {
        int m = "anc".equals(mode) ? 1 : "transparency".equals(mode) ? 2 : 0;
        writePkt(JlProtocol.setAncMode(m, nextSn()));
    }

    private boolean writeStrengthNow(int pct) {
        if (!ancFullSet) {
            if (ancVm[1] != null) noteAncDepth(ancVm[1]);
            if (!ancFullSet && lastAncVm != null) noteAncDepth(lastAncVm);
        }
        if (!ancFullSet) return false;
        int p = Math.max(10, Math.min(100, pct));
        p = ((p + 5) / 10) * 10;
        byte[] base = ancVm[1];
        if (base == null || base.length != 9 || (base[0] & 0xFF) != 1) base = lastAncVm;
        if (base == null || base.length != 9 || (base[0] & 0xFF) != 1) return false;
        byte[] vm = safeVm(base, 1);
        if (vm == null) return false;
        if (!ancFullSet) {
            ancFullL = u16(vm, 3);
            ancFullR = u16(vm, 5);
            ancFullSet = true;
        }
        int l = p >= 100 ? ancFullL : Math.max(1, (ancFullL * p + 50) / 100);
        int r = p >= 100 ? ancFullR : Math.max(1, (ancFullR * p + 50) / 100);
        putU16(vm, 3, l);
        putU16(vm, 5, l);
        putU16(vm, 7, l);
        if (!presetSane(vm)) return false;
        ancPct = p;
        boolean ok = writePkt(JlProtocol.setCurrentVm(vm, nextSn()));
        dbg("strength step " + (p / 10) + " " + (ok ? "ok" : "FAIL") + " L=" + l + " R=" + r);
        return ok;
    }

    private boolean writeTransNow(int pct) {
        if (!transFullSet) {
            if (ancVm[2] != null) noteTransDepth(ancVm[2]);
            if (!transFullSet && lastAncVm != null) noteTransDepth(lastAncVm);
        }
        if (!transFullSet) return false;
        int p = Math.max(0, Math.min(100, pct));
        byte[] base = ancVm[2];
        if (base == null || base.length != 9 || (base[0] & 0xFF) != 2) base = lastAncVm;
        if (base == null || base.length != 9 || (base[0] & 0xFF) != 2) return false;
        byte[] vm = safeVm(base, 2);
        if (vm == null) return false;
        if (!transFullSet) {
            transFullL = u16(vm, 3);
            transFullR = u16(vm, 5);
            transFullSet = true;
        }
        int l = p >= 100 ? transFullL : Math.max(1, transFullL * p / 100);
        int r = p >= 100 ? transFullR : Math.max(1, transFullR * p / 100);
        if (p == 0) { l = 0; r = 0; }
        putU16(vm, 3, l);
        putU16(vm, 5, l);
        putU16(vm, 7, l);
        if (p > 0 && !presetSane(vm)) return false;
        transPct = p;
        boolean ok = writePkt(JlProtocol.setCurrentVm(vm, nextSn()));
        dbg("trans strength " + p + " " + (ok ? "ok" : "FAIL") + " L=" + l + " R=" + r);
        return ok;
    }

    /** Left current is byte 3, right current is byte 7. A flag at byte 5 is normal. */
    private static boolean earsAgree(byte[] val, int mode) {
        return currentsMatch(val, mode) || val == null || val.length < 9;
    }

    /** Phone Bluetooth battery, 0–100. -1 if the system has no level. Not a bud byte. */
    private static int deviceBattery(BluetoothDevice d) {
        if (d == null) return -1;
        try {
            Method m = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object o = m.invoke(d);
            if (o instanceof Integer) {
                int n = (Integer) o;
                if (n >= 0 && n <= 100) return n;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private void emitSysBatt() {
        int n = deviceBattery(pending);
        if (n < 0 || n > 100) return;
        emit("{\"type\":\"sysbatt\",\"value\":" + n + "}");
    }

    /** -1 = hide. Bit 7 is charging (100+128=228). A level of 0–10 is bars. */
    static int battPct(int raw) {
        if (raw < 0) return -1;
        int n = raw & 0xFF;
        if (n > 100) {
            int stripped = n & 0x7F;
            if (stripped <= 100) n = stripped;
            else return -1;
        }
        if (n <= 10) n = n * 10;
        if (n > 100) return -1;
        return n;
    }

    private void emitBattery(int l, int r, int c) {
        boolean rawOk = l >= 0 && l <= 100 && r >= 0 && r <= 100;
        if (!rcspSeen && !rawOk) return;
        int sl = battPct(l);
        int sr = battPct(r);
        int sc = battPct(c);
        if (sc < 1) sc = -1;
        if (sl == batL && sr == batR && sc == batC) return;
        batL = sl;
        batR = sr;
        batC = sc;
        try { UserState.cacheBattery(ctx, sl, sr, sc); } catch (Throwable ignored) {}
        try { StatusNote.batt(ctx, sl, sr, sc); } catch (Throwable ignored) {}
        emit("{\"type\":\"battery\",\"left\":" + sl + ",\"right\":" + sr + ",\"case\":" + sc
                + ",\"live\":" + rcspSeen + "}");
    }

    void factoryReset() {
        io.execute(() -> {
            seedStockKeys();
            writePkt(JlProtocol.work("normal", nextSn()));
            sleepQuiet(120);
            writePkt(JlProtocol.keys(packKeyMap(), nextSn()));
            sleepQuiet(160);
            writePkt(JlProtocol.eq(0, new int[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, nextSn()));
            sleepQuiet(120);
            writePkt(JlProtocol.flag(JlProtocol.ATTR_DUAL, false, nextSn()));
            sleepQuiet(220);
            writePkt(JlProtocol.reboot(nextSn()));
        });
    }

    private static int u16(byte[] d, int o) {
        if (d == null || o + 1 >= d.length) return 0;
        return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
    }

    private static void sleepQuiet(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
    void setWork(String mode) {
        ui.removeCallbacks(workPersistRun);
        workMode = "game".equals(mode) ? "game" : "normal";
        try { prefs().edit().putString("cache_work", workMode).apply(); } catch (Throwable ignored) {}
        burst(JlProtocol.work(workMode, nextSn()));
        ui.postDelayed(workPersistRun, 700);
    }
    void setEq(int preset, int[] bands) {
        eqPreset = preset;
        eqBands = bands;
        ui.removeCallbacks(eqRun);
        ui.postDelayed(eqRun, 120);
    }
    void setFind(String side) {
        if (side == null || "stop".equals(side)) findSide = "";
        else findSide = side;
        send(JlProtocol.find(side == null ? "stop" : side, nextSn()));
    }

    private final Runnable findRightRun = () -> {
        if (findSide.length() == 0) return;
        setFind("right");
    };
    private final Runnable findStopRun = () -> setFind("stop");

    /** Left, then right, then a hard stop at 5 s. Never an ANC or work-mode write. */
    void pingFind() {
        if (!chipReady()) return;
        ui.removeCallbacks(findRightRun);
        ui.removeCallbacks(findStopRun);
        setFind("left");
        ui.postDelayed(findRightRun, 2500);
        ui.postDelayed(findStopRun, 5000);
    }

    private void stopFind() {
        if (findSide.length() == 0 && writeChar == null && sppOut == null) return;
        if (findSide.length() == 0) return;
        findSide = "";
        try { send(JlProtocol.find("stop", nextSn())); } catch (Throwable ignored) {}
    }

    /**
     * UI 0-based side/action and 0..8 function. Merges one slot into the full
     * JieLi table and writes every triple — firmware ignores a lone 3-byte SET.
     * ADV_SETTINGS fails in game mode, so we leave game first.
     */
    void setKey(int side, int action, int fn) {
        pendSide = side;
        pendAct = action;
        pendFn = fn;
        ui.removeCallbacks(keyRun);
        ui.postDelayed(keyRun, 120);
    }

    private void writeKey(int side, int action, int fn) {
        final int fwSide = JlProtocol.sideUiToFw(side);
        final int fwAct = JlProtocol.actUiToFw(action);
        final int fwFn = JlProtocol.fnUiToFw(fn);
        if (keyMap.isEmpty()) seedStockKeys();
        keyMap.put((fwSide << 8) | fwAct, fwFn);
        emitKeys();
        final byte[] triples = packKeyMap();
        io.execute(() -> {
            writePkt(JlProtocol.work("normal", nextSn()));
            sleepQuiet(160);
            byte[] pkt = JlProtocol.keys(triples, nextSn());
            for (int i = 0; i < 3; i++) {
                writePkt(pkt);
                sleepQuiet(120);
            }
            sleepQuiet(180);
            writePkt(JlProtocol.getAdvAll(nextSn()));
        });
    }

    void setKeys(byte[] triples) {
        if (triples == null || triples.length < 3) return;
        ingestKeyTriples(triples);
        if (keyMap.isEmpty()) seedStockKeys();
        final byte[] all = packKeyMap();
        io.execute(() -> {
            writePkt(JlProtocol.work("normal", nextSn()));
            sleepQuiet(160);
            byte[] pkt = JlProtocol.keys(all, nextSn());
            for (int i = 0; i < 3; i++) {
                writePkt(pkt);
                sleepQuiet(120);
            }
            sleepQuiet(180);
            writePkt(JlProtocol.getAdvAll(nextSn()));
        });
    }
    void setFlag(int attr, boolean on) { burst(JlProtocol.flag(attr, on, nextSn())); }

    /** In-ear pause. ADV type 8 only (2=on, 1=off). A second write 700ms later is the only persist. */
    void setInEar(boolean on) {
        inEarOn = on;
        inEarKnown = true;
        inEarWriteUntil = System.currentTimeMillis() + 2500;
        ui.removeCallbacks(inEarPersistRun);
        burst(JlProtocol.inEar(on, nextSn()));
        ui.postDelayed(inEarPersistRun, 700);
        emit("{\"type\":\"inear\",\"on\":" + on + "}");
    }

    private void burst(byte[] pkt) {
        io.execute(() -> {
            for (int i = 0; i < 3; i++) {
                writePkt(pkt);
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ignored) {
                }
            }
        });
    }
    void setMic(String ch) { send(JlProtocol.mic(ch, nextSn())); }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gattUp = true;
                emit("{\"type\":\"link\",\"state\":\"gatt\"}");
                dbg("gatt connected status=" + status + " mac=" + (g.getDevice() != null ? g.getDevice().getAddress() : "?"));
                gattMtu = 23;
                notifyArmed = false;
                cccdPending = false;
                try {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                } catch (Exception ignored) {
                }
                ui.postDelayed(() -> {
                    try { g.requestMtu(517); } catch (Exception ignored) {}
                }, 200);
                ui.postDelayed(() -> {
                    try { g.discoverServices(); } catch (Exception ignored) {}
                }, 1100);
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gattUp = false;
                writeChar = null;
                notifyArmed = false;
                cccdPending = false;
                gattBusy = false;
                gattMtu = 23;
                synchronized (gattIo) { gattIo.notifyAll(); }
                dbg("gatt disconnect status=" + status + " try=" + connectTries);
                emit("{\"type\":\"link\",\"state\":\"gatt-off\"}");
                if (status == 133 && pending != null && !rcspSeen) {
                    dbg("gatt 133 — scan, keep a2dp");
                    ui.post(() -> startScanQuiet());
                }
                if (connectTries + 1 < gattTryLimit() && pending != null && !rcspSeen) {
                    final int next = connectTries + 1;
                    ui.postDelayed(() -> openGatt(next), gattBackoff(next, status == 133));
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 0) gattMtu = mtu;
            dbg("gatt mtu=" + gattMtu + " status=" + status);
            try { g.discoverServices(); } catch (Exception ignored) {}
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            writeChar = findWrite(g);
            if (writeChar == null) {
                StringBuilder sb = new StringBuilder();
                try {
                    for (BluetoothGattService s : g.getServices()) {
                        if (sb.length() > 0) sb.append(',');
                        String u = s.getUuid().toString();
                        sb.append(u.length() >= 8 ? u.substring(0, 8) : u);
                    }
                } catch (Exception ignored) {
                }
                emit("{\"type\":\"probe\",\"gatt\":\"no-ae00\",\"try\":" + connectTries + "}");
                dbg("gatt no-ae00 try=" + connectTries + " services=" + sb);
                try {
                    g.disconnect();
                } catch (Exception ignored) {
                }
                if (connectTries + 1 < gattTryLimit()) {
                    final int next = connectTries + 1;
                    ui.postDelayed(() -> openGatt(next), gattBackoff(next, false));
                }
                return;
            }
            emit("{\"type\":\"probe\",\"gatt\":\"ae00\",\"mtu\":" + gattMtu + "}");
            dbg("gatt ae00 mtu=" + gattMtu);
            try {
                if (pending != null && g.getDevice() != null) {
                    prefs().edit().putString(bleKey(pending.getAddress()), g.getDevice().getAddress()).apply();
                }
            } catch (Exception ignored) {}
            if (!notifyArmed) {
                notifyArmed = true;
                enableNotify(g);
            }
            live = true;
            markConnected(rcspSeen);
            ui.postDelayed(() -> {
                if (!cccdPending) startAuthThenSync();
            }, 550);
            ui.postDelayed(() -> startAuthThenSync(), 1800);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            cccdPending = false;
            startAuthThenSync();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (gattIo) {
                gattBusy = false;
                gattIo.notifyAll();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = null;
            try { v = c.getValue(); } catch (Exception ignored) {}
            if (v != null && v.length > 0) onNotify(v);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (value != null && value.length > 0) onNotify(value);
            else onCharacteristicChanged(g, c);
        }

        private void onNotify(byte[] v) {
            rxBytes += v.length;
            synchronized (rx) {
                rx.write(v, 0, v.length);
                drainRx();
            }
        }
    };

    private void enableNotify(BluetoothGatt g) {
        UUID[] nts = {
                UUID.fromString(JlProtocol.NT),
                UUID.fromString(JlProtocol.NT_ALT)
        };
        for (UUID u : nts) {
            for (BluetoothGattService s : g.getServices()) {
                BluetoothGattCharacteristic c = s.getCharacteristic(u);
                if (c == null) continue;
                try {
                    g.setCharacteristicNotification(c, true);
                    BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                    if (d != null) {
                        cccdPending = true;
                        boolean wrote = false;
                        try {
                            if (Build.VERSION.SDK_INT >= 33) {
                                wrote = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                        == BluetoothGatt.GATT_SUCCESS;
                                if (!wrote) {
                                    wrote = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                                            == BluetoothGatt.GATT_SUCCESS;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                        if (!wrote) {
                            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            wrote = g.writeDescriptor(d);
                            if (!wrote) {
                                d.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                                wrote = g.writeDescriptor(d);
                            }
                        }
                        if (!wrote) cccdPending = false;
                    }
                } catch (Exception ignored) {
                }
                return;
            }
        }
        for (BluetoothGattService s : g.getServices()) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    try {
                        g.setCharacteristicNotification(c, true);
                        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                        if (d != null) {
                            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            g.writeDescriptor(d);
                        }
                    } catch (Exception ignored) {
                    }
                    return;
                }
            }
        }
    }

    private static BluetoothGattCharacteristic findWrite(BluetoothGatt g) {
        UUID[] prefer = {
                UUID.fromString(JlProtocol.WR),
                UUID.fromString(JlProtocol.WR_ALT)
        };
        for (UUID u : prefer) {
            for (BluetoothGattService s : g.getServices()) {
                BluetoothGattCharacteristic c = s.getCharacteristic(u);
                if (c != null) return c;
            }
        }
        int mask = BluetoothGattCharacteristic.PROPERTY_WRITE
                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        for (BluetoothGattService s : g.getServices()) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if ((c.getProperties() & mask) != 0) return c;
            }
        }
        return null;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
