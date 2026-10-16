package com.carol.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Per-device 10-band graphic EQ for the headset microphone, plus mute.
 * Mute uses AudioManager (calls / VoIP). EQ is a live biquad chain on the
 * Bluetooth SCO capture; listen-through plays the result in the buds.
 */
final class MicEq {
    static final int[] HZ = {20, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    private final Context ctx;
    private final AudioManager am;
    private String model = "carol";
    private int[] geq = new int[10];
    private boolean mute;
    private volatile boolean listen;
    private volatile boolean running;
    private Thread worker;
    private AudioRecord rec;
    private AudioTrack track;

    private static MicEq inst;

    static synchronized MicEq get(Context ctx) {
        if (inst == null) inst = new MicEq(ctx.getApplicationContext());
        return inst;
    }

    private MicEq(Context ctx) {
        this.ctx = ctx;
        this.am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        load("carol");
        applyMute();
    }

    String json() {
        JSONObject o = new JSONObject();
        try {
            o.put("model", model);
            o.put("mute", mute);
            o.put("listen", listen);
            JSONArray a = new JSONArray();
            for (int g : geq) a.put(g);
            o.put("geq", a);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    String apply(String model, String json) {
        if (model != null && model.length() > 0) this.model = model;
        try {
            if (json != null && json.length() > 2) {
                JSONObject o = new JSONObject(json);
                mute = o.optBoolean("mute", mute);
                JSONArray a = o.optJSONArray("geq");
                if (a != null) {
                    int[] n = new int[10];
                    for (int i = 0; i < 10; i++) n[i] = clamp(a.optInt(i, 0), -12, 12);
                    geq = n;
                }
            }
        } catch (Exception ignored) {
        }
        save();
        applyMute();
        return json();
    }

    String loadModel(String m) {
        if (m == null || m.length() == 0) m = "carol";
        stopListen();
        load(m);
        applyMute();
        return json();
    }

    void setMute(boolean on) {
        mute = on;
        if (mute) stopListen();
        save();
        applyMute();
    }

    boolean muted() {
        return mute;
    }

    void applyMute() {
        if (am == null) return;
        try {
            am.setMicrophoneMute(mute);
        } catch (Exception ignored) {
        }
    }

    void startListen() {
        if (mute) return;
        stopListen();
        listen = true;
        running = true;
        worker = new Thread(this::loop, "carol-mic");
        worker.start();
        save();
    }

    void stopListen() {
        listen = false;
        running = false;
        if (worker != null) {
            try {
                worker.interrupt();
                worker.join(400);
            } catch (Exception ignored) {
            }
            worker = null;
        }
        releaseIo();
        restoreSco();
        save();
    }

    private void loop() {
        int sr = 16000;
        try {
            trySco();
            int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                sr = 8000;
                min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            }
            int src = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            rec = new AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, sr));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, sr));
            }
            int outMin = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = Compat.mediaStream(sr, AudioFormat.CHANNEL_OUT_MONO, Math.max(outMin, sr));
            UsbDac.get(ctx).prefer(track);
            rec.startRecording();
            track.play();
            short[] buf = new short[1024];
            Biquad[] fx = bands(sr);
            int[] snap = geq.clone();
            double trim = headroom(snap);
            while (running) {
                if (!same(snap, geq)) {
                    snap = geq.clone();
                    fx = bands(sr);
                    trim = headroom(snap);
                }
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                if (mute) {
                    for (int i = 0; i < n; i++) buf[i] = 0;
                } else {
                    for (int i = 0; i < n; i++) {
                        double x = buf[i] / 32768.0;
                        for (Biquad b : fx) x = b.process(x);
                        x *= trim;
                        if (x > 0.95) x = 0.95 + 0.04 * Math.tanh((x - 0.95) / 0.04);
                        else if (x < -0.95) x = -0.95 + 0.04 * Math.tanh((x + 0.95) / 0.04);
                        buf[i] = (short) (x * 32767);
                    }
                }
                track.write(buf, 0, n);
            }
        } catch (SecurityException ignored) {
        } catch (Throwable ignored) {
        } finally {
            releaseIo();
            restoreSco();
            running = false;
            listen = false;
        }
    }

    private Biquad[] bands(int sr) {
        double nyq = sr * 0.45;
        Biquad[] out = new Biquad[10];
        for (int i = 0; i < 10; i++) {
            double f = HZ[i];
            int g = f >= nyq ? 0 : geq[i];
            out[i] = Biquad.peak(sr, f, g, 1.0);
        }
        return out;
    }

    private static double headroom(int[] g) {
        double boost = 0;
        for (int v : g) if (v > 0) boost += v;
        boost = Math.min(9, boost * 0.4);
        return Math.pow(10, -boost / 20.0);
    }

    private static boolean same(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private void trySco() {
        if (am == null) return;
        try {
            am.startBluetoothSco();
            am.setBluetoothScoOn(true);
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            for (int i = 0; i < 15 && running; i++) {
                if (am.isBluetoothScoOn()) return;
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void restoreSco() {
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
        } catch (Throwable ignored) {
        }
        try {
            am.setBluetoothScoOn(false);
            am.stopBluetoothSco();
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {
        }
    }

    private void releaseIo() {
        try {
            if (track != null) {
                track.stop();
                track.release();
            }
        } catch (Exception ignored) {
        }
        track = null;
        try {
            if (rec != null) {
                rec.stop();
                rec.release();
            }
        } catch (Exception ignored) {
        }
        rec = null;
    }

    private void load(String m) {
        model = m;
        SharedPreferences p = prefs();
        mute = p.getBoolean(model + "_mute", false);
        String raw = p.getString(model + "_geq", "");
        int[] n = new int[10];
        try {
            if (raw != null && raw.startsWith("[")) {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < 10; i++) n[i] = clamp(a.optInt(i, 0), -12, 12);
            }
        } catch (Exception ignored) {
        }
        geq = n;
    }

    private void save() {
        JSONArray a = new JSONArray();
        for (int g : geq) a.put(g);
        prefs().edit()
                .putBoolean(model + "_mute", mute)
                .putString(model + "_geq", a.toString())
                .apply();
        UserState.markDirty();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("carol_mic", 0);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static final class Biquad {
        double b0, b1, b2, a1, a2, x1, x2, y1, y2;

        static Biquad peak(int sr, double f, double gainDb, double q) {
            Biquad b = new Biquad();
            if (Math.abs(gainDb) < 0.05) {
                b.b0 = 1;
                return b;
            }
            double A = Math.pow(10, gainDb / 40.0);
            double w0 = 2 * Math.PI * f / sr;
            double cos = Math.cos(w0);
            double sin = Math.sin(w0);
            double alpha = sin / (2 * Math.max(0.3, q));
            double b0 = 1 + alpha * A;
            double b1 = -2 * cos;
            double b2 = 1 - alpha * A;
            double a0 = 1 + alpha / A;
            double a1 = -2 * cos;
            double a2 = 1 - alpha / A;
            b.b0 = b0 / a0;
            b.b1 = b1 / a0;
            b.b2 = b2 / a0;
            b.a1 = a1 / a0;
            b.a2 = a2 / a0;
            return b;
        }

        double process(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }
}
