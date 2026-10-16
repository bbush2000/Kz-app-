package com.carol.control;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

/** Stereo find beep for every Classic radio. The chip never answers, so the phone plays one side. */
final class FindBeep {
    private final Context ctx;
    private Thread worker;
    private volatile boolean run;
    private AudioTrack track;
    private static FindBeep inst;

    static synchronized FindBeep get(Context ctx) {
        if (inst == null) inst = new FindBeep(ctx.getApplicationContext());
        return inst;
    }

    private FindBeep(Context ctx) {
        this.ctx = ctx;
    }

    void play(String side) {
        stop();
        if (side == null || side.length() == 0 || "stop".equalsIgnoreCase(side)) return;
        final boolean right = "right".equalsIgnoreCase(side);
        run = true;
        worker = new Thread(() -> tone(right), "carol-find");
        worker.start();
    }

    void stop() {
        run = false;
        Thread t = worker;
        worker = null;
        AudioTrack a = track;
        if (a != null) {
            try { a.pause(); } catch (Throwable ignored) {}
            try { a.flush(); } catch (Throwable ignored) {}
            try { a.release(); } catch (Throwable ignored) {}
            if (track == a) track = null;
        }
        if (t != null) t.interrupt();
    }

    private void tone(boolean right) {
        int sr = 44100;
        AudioTrack t = null;
        try {
            int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            t = Compat.mediaStream(sr, AudioFormat.CHANNEL_OUT_STEREO, Math.max(min, 4096));
            track = t;
            preferMusic(t);
            t.play();
            int frame = sr / 50;
            short[] buf = new short[frame * 2];
            long end = System.currentTimeMillis() + 15000;
            double freq = right ? 988 : 784;
            int n = 0;
            while (run && System.currentTimeMillis() < end) {
                boolean on = ((n / 8) % 2) == 0;
                for (int i = 0; i < frame; i++) {
                    short s = 0;
                    if (on) {
                        double env = Math.min(1.0, Math.min(i, frame - 1 - i) / 30.0);
                        double phase = 2 * Math.PI * freq * (n * (double) frame + i) / sr;
                        s = (short) (Math.sin(phase) * 12000 * env);
                    }
                    buf[i * 2] = right ? 0 : s;
                    buf[i * 2 + 1] = right ? s : 0;
                }
                t.write(buf, 0, buf.length);
                n++;
            }
        } catch (Throwable ignored) {
        } finally {
            run = false;
            if (t != null) {
                try { t.stop(); } catch (Throwable ignored) {}
                try { t.release(); } catch (Throwable ignored) {}
                if (track == t) track = null;
            }
        }
    }

    private void preferMusic(AudioTrack t) {
        if (Build.VERSION.SDK_INT < 28 || t == null) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            AudioDeviceInfo pick = null;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d == null) continue;
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) { pick = d; break; }
                if (pick == null && (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || type == AudioDeviceInfo.TYPE_USB_HEADSET
                        || type == AudioDeviceInfo.TYPE_USB_DEVICE)) {
                    pick = d;
                }
            }
            if (pick != null) t.setPreferredDevice(pick);
        } catch (Throwable ignored) {
        }
    }
}
