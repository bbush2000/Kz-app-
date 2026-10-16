package com.carol.control;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;

/**
 * KZ USB-C IEM cables (Type-C Cable, M2) and dongles (AM01 / AM02).
 * Exclusive pins a silent media track on the USB DAC so spatial/EQ
 * stay there instead of jumping back to the speaker or A2DP.
 */
final class UsbDac {
    static final String PERM = "com.carol.control.USB_PERM";
    private final Context ctx;
    private final AudioManager am;
    private final UsbManager um;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean exclusive;
    private int sampleRate = 48000;
    private int bitDepth = 16;
    private AudioTrack hold;
    private Thread holdThread;
    private volatile boolean holdRun;
    private Runnable listener;
    private static UsbDac inst;

    static synchronized UsbDac get(Context ctx) {
        if (inst == null) inst = new UsbDac(ctx.getApplicationContext());
        return inst;
    }

    private UsbDac(Context ctx) {
        this.ctx = ctx;
        this.am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        this.um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        exclusive = false;
        try {
            ctx.getSharedPreferences("carol_ble", 0).edit().putBoolean("usb_ex", false).apply();
        } catch (Throwable ignored) {
        }
        sampleRate = ctx.getSharedPreferences("carol_ble", 0).getInt("usb_sr", 48000);
        bitDepth = ctx.getSharedPreferences("carol_ble", 0).getInt("usb_bits", 16);
        try {
            IntentFilter f = new IntentFilter(PERM);
            f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            f.addAction(Intent.ACTION_HEADSET_PLUG);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(usbRx, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(usbRx, f);
            }
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 23 && am != null) {
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    h.post(() -> {
                        if (exclusive) applyRoute();
                        fire();
                    });
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    h.post(() -> {
                        if (exclusive) applyRoute();
                        fire();
                    });
                }
            }, h);
        }
        if (exclusive) applyRoute();
    }

    void setListener(Runnable r) {
        listener = r;
    }

    private void fire() {
        try {
            org.json.JSONObject o = new org.json.JSONObject(status());
            if (o.optBoolean("plugged")) {
                String model = o.optString("model", "typec");
                DeviceRoster.upsert(ctx, "usb:" + model, o.optString("label", "USB-C"), model, "usb");
            } else {
                DeviceRoster.removeKind(ctx, "usb");
            }
            CarolService.usb(ctx, o.optBoolean("plugged") || exclusive);
        } catch (Exception ignored) {
        }
        Runnable r = listener;
        if (r != null) h.post(r);
    }

    String status() {
        JSONObject o = new JSONObject();
        try {
            AudioDeviceInfo d = findUsb();
            String name = d != null ? label(d) : usbProductName();
            KzCatalog.M m = KzCatalog.usbOf(name + " " + usbProductName());
            o.put("plugged", d != null);
            o.put("name", name == null ? "" : name);
            o.put("exclusive", exclusive);
            o.put("model", m == null ? "typec" : m.id);
            o.put("label", m == null ? "Type-C Cable" : m.label);
            o.put("cable", m != null && ("typec".equals(m.id) || "m2".equals(m.id) || "gale".equals(m.id)));
            o.put("rate", sampleRate);
            o.put("bits", bitDepth);
            JSONArray rates = new JSONArray();
            if (d != null && Build.VERSION.SDK_INT >= 23) {
                try {
                    int[] sr = d.getSampleRates();
                    if (sr != null) for (int x : sr) if (x >= 44100) rates.put(x);
                } catch (Exception ignored) {
                }
            }
            o.put("hwRates", rates);
            JSONArray a = new JSONArray();
            if (am != null && Build.VERSION.SDK_INT >= 23) {
                for (AudioDeviceInfo x : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (isUsb(x)) {
                        JSONObject n = new JSONObject();
                        n.put("name", label(x));
                        n.put("id", x.getId());
                        n.put("type", x.getType());
                        a.put(n);
                    }
                }
            }
            o.put("devices", a);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    String setExclusive(boolean on) {
        exclusive = false;
        ctx.getSharedPreferences("carol_ble", 0).edit().putBoolean("usb_ex", false).apply();
        stopHold();
        clearComm();
        try { SpatialFx.get(ctx).reattach(); } catch (Throwable ignored) {}
        fire();
        return status();
    }

    boolean exclusive() {
        return exclusive;
    }

    void restoreIfNeeded() {
        if (exclusive) {
            h.postDelayed(() -> {
                applyRoute();
                SpatialFx.get(ctx).reattach();
            }, 600);
        } else {
            SpatialFx.get(ctx).reattach();
        }
    }

    int sampleRate() {
        return sampleRate;
    }

    int bitDepth() {
        return bitDepth;
    }

    void setFormat(int rate, int bits) {
        if (rate >= 44100) sampleRate = rate;
        if (bits == 16 || bits == 24 || bits == 32) bitDepth = bits;
        ctx.getSharedPreferences("carol_ble", 0).edit()
                .putInt("usb_sr", sampleRate)
                .putInt("usb_bits", bitDepth)
                .apply();
        UserState.markDirty();
        applyRoute();
    }

    void prefer(AudioTrack t) {
        if (t == null || !exclusive || Build.VERSION.SDK_INT < 23) return;
        AudioDeviceInfo d = findUsb();
        if (d != null) {
            try {
                t.setPreferredDevice(d);
            } catch (Exception ignored) {
            }
        }
    }

    void onAttached() {
        requestPerms();
        if (exclusive) applyRoute();
        fire();
    }

    void requestPerms() {
        if (um == null) return;
        try {
            HashMap<String, UsbDevice> map = um.getDeviceList();
            if (map == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(PERM),
                    Build.VERSION.SDK_INT >= 31
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT);
            for (UsbDevice d : map.values()) {
                if (d == null || um.hasPermission(d)) continue;
                if (isAudioUsb(d)) um.requestPermission(d, pi);
            }
        } catch (Exception ignored) {
        }
    }

    private void applyRoute() {
        AudioDeviceInfo d = findUsb();
        if (d == null || am == null) {
            stopHold();
            clearComm();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT < 26) am.setSpeakerphoneOn(false);
            am.setBluetoothScoOn(false);
            am.stopBluetoothSco();
        } catch (Exception ignored) {
        }
        setComm(d);
        if (exclusive) startHold(d);
        SpatialFx.get(ctx).reattach();
        fire();
    }

    private void setComm(AudioDeviceInfo d) {
        try {
            am.getClass().getMethod("setCommunicationDevice", AudioDeviceInfo.class).invoke(am, d);
        } catch (Throwable ignored) {
        }
    }

    private void clearComm() {
        try {
            am.getClass().getMethod("clearCommunicationDevice").invoke(am);
        } catch (Throwable ignored) {
        }
    }

    private synchronized void startHold(AudioDeviceInfo d) {
        stopHold();
        if (d == null || Build.VERSION.SDK_INT < 23) return;
        try {
            int sr = sampleRate >= 44100 ? sampleRate : 48000;
            int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            hold = Compat.mediaStream(sr, AudioFormat.CHANNEL_OUT_STEREO, Math.max(min, sr / 5 * 4));
            try {
                hold.setPreferredDevice(d);
            } catch (Exception ignored) {
            }
            try {
                hold.setVolume(0f);
            } catch (Exception ignored) {
            }
            hold.play();
            holdRun = true;
            final AudioTrack t = hold;
            final int n = Math.max(256, sr / 10 * 2);
            holdThread = new Thread(() -> {
                short[] z = new short[n];
                while (holdRun && t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        int w = t.write(z, 0, z.length);
                        if (w < 0) break;
                    } catch (Exception e) {
                        break;
                    }
                }
            }, "kz-usb-hold");
            holdThread.start();
        } catch (Exception ignored) {
        }
    }

    private synchronized void stopHold() {
        holdRun = false;
        if (holdThread != null) {
            try {
                holdThread.interrupt();
                holdThread.join(200);
            } catch (Exception ignored) {
            }
            holdThread = null;
        }
        if (hold != null) {
            try {
                hold.pause();
                hold.flush();
                hold.release();
            } catch (Exception ignored) {
            }
            hold = null;
        }
    }

    AudioDeviceInfo findUsb() {
        if (am == null || Build.VERSION.SDK_INT < 23) return null;
        AudioDeviceInfo best = null;
        int bestScore = 0;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (!isUsb(d)) continue;
                int s = score(d);
                if (s > bestScore) {
                    bestScore = s;
                    best = d;
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private int score(AudioDeviceInfo d) {
        String n = label(d).toLowerCase();
        int s = 1;
        if (n.contains("kz") || n.contains("am01") || n.contains("am02") || n.contains("m2")
                || n.contains("gale") || n.contains("starbit") || n.contains("type-c") || n.contains("typec")) s += 12;
        int t = d.getType();
        if (t == AudioDeviceInfo.TYPE_USB_HEADSET) s += 6;
        else if (t == AudioDeviceInfo.TYPE_USB_DEVICE) s += 4;
        else if (t == AudioDeviceInfo.TYPE_USB_ACCESSORY) s += 2;
        return s;
    }

    static boolean isUsb(AudioDeviceInfo d) {
        if (d == null) return false;
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_USB_DEVICE
                || t == AudioDeviceInfo.TYPE_USB_HEADSET
                || t == AudioDeviceInfo.TYPE_USB_ACCESSORY
                || (Build.VERSION.SDK_INT >= 26 && t == AudioDeviceInfo.TYPE_BUS);
    }

    private String usbProductName() {
        if (um == null) return "";
        try {
            HashMap<String, UsbDevice> map = um.getDeviceList();
            if (map == null) return "";
            StringBuilder b = new StringBuilder();
            for (UsbDevice d : map.values()) {
                if (d == null || !isAudioUsb(d)) continue;
                if (Build.VERSION.SDK_INT >= 21) {
                    if (d.getManufacturerName() != null) b.append(d.getManufacturerName()).append(' ');
                    if (d.getProductName() != null) b.append(d.getProductName()).append(' ');
                }
            }
            return b.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isAudioUsb(UsbDevice d) {
        if (d.getDeviceClass() == UsbConstants.USB_CLASS_AUDIO) return true;
        try {
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface inf = d.getInterface(i);
                if (inf != null && inf.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) return true;
            }
        } catch (Exception ignored) {
        }
        return d.getInterfaceCount() > 0;
    }

    private static String label(AudioDeviceInfo d) {
        String n = null;
        if (Build.VERSION.SDK_INT >= 28) n = d.getProductName() == null ? null : d.getProductName().toString();
        if (n == null || n.length() == 0) n = "USB-C IEM";
        return n;
    }

    private final BroadcastReceiver usbRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i == null || i.getAction() == null) return;
            if (PERM.equals(i.getAction()) || UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
                if (exclusive) applyRoute();
                else requestPerms();
                fire();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                stopHold();
                clearComm();
                fire();
            } else if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())
                    || Intent.ACTION_HEADSET_PLUG.equals(i.getAction())) {
                restoreIfNeeded();
            }
        }
    };
}
