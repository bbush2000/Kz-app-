package com.carol.control;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

/** One short sine for the hearing check. Quiet at step 0, never a full-scale blast. */
final class HearTone {
    private final Context ctx;
    private Thread worker;
    private volatile boolean run;
    private AudioTrack track;
    private static HearTone inst;

    static synchronized HearTone get(Context ctx) {
        if (inst == null) inst = new HearTone(ctx.getApplicationContext());
        return inst;
    }

    private HearTone(Context ctx) {
        this.ctx = ctx;
    }

    void play(int hz, int step, boolean right) {
        stop();
        int f = hz < 80 ? 1000 : Math.min(16000, hz);
        int s = Math.max(0, Math.min(12, step));
        run = true;
        worker = new Thread(() -> burst(f, s, right), "carol-hear");
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

    private void burst(int hz, int step, boolean right) {
        int sr = 44100;
        AudioTrack t = null;
        try {
            int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            t = Compat.mediaStream(sr, AudioFormat.CHANNEL_OUT_STEREO, Math.max(min, 4096));
            track = t;
            preferOut(t);
            t.play();
            // Step 0 is about −42 dBFS. Each step is +3 dB. Cap before it gets loud.
            double amp = Math.min(0.22, 0.008 * Math.pow(Math.pow(10, 3 / 20.0), step));
            int frames = (int) (sr * 0.7);
            int chunk = sr / 40;
            short[] buf = new short[chunk * 2];
            int done = 0;
            while (run && done < frames) {
                int n = Math.min(chunk, frames - done);
                for (int i = 0; i < n; i++) {
                    int at = done + i;
                    double env = 1;
                    if (at < sr / 40) env = at / (double) (sr / 40);
                    else if (frames - at < sr / 40) env = (frames - at) / (double) (sr / 40);
                    double phase = 2 * Math.PI * hz * at / sr;
                    short s = (short) (Math.sin(phase) * amp * 32767 * env);
                    buf[i * 2] = right ? 0 : s;
                    buf[i * 2 + 1] = right ? s : 0;
                }
                t.write(buf, 0, n * 2);
                done += n;
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

    private void preferOut(AudioTrack t) {
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
