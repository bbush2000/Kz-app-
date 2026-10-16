package com.carol.control;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Helicopter fly-around for the current speaker map.
 * Pans with level + a short ear delay so each dot is a place, and live
 * layout updates move the sound while a speaker is dragged.
 */
final class SurroundDemo {
    interface Listener {
        void onDemo(String json);
    }

    private static final class Sp {
        String n = "";
        float x = 50, y = 50;
        boolean height;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Object lock = new Object();
    private volatile boolean running;
    private Thread worker;
    private Sp[] map = new Sp[0];
    private float headX = 50, headY = 42, head = 50;
    private volatile String at = "";

    SurroundDemo(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    void start(String json) {
        stop();
        layout(json);
        running = true;
        SpatialFx.get(ctx).fitHold(true);
        worker = new Thread(this::run, "carol-heli");
        worker.start();
        emit(true, "");
    }

    void layout(String json) {
        if (json == null || json.length() < 2) return;
        try {
            JSONObject o = new JSONObject(json);
            Sp[] next = parse(o.optJSONArray("spk"));
            synchronized (lock) {
                if (next.length > 0) map = next;
                headX = (float) o.optDouble("lx", headX);
                headY = (float) o.optDouble("ly", headY);
                head = (float) o.optDouble("head", head);
            }
        } catch (Exception ignored) {
        }
    }

    void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.interrupt();
                worker.join(800);
            } catch (Exception ignored) {
            }
            worker = null;
        }
        SpatialFx.get(ctx).fitHold(false);
        at = "";
        emit(false, "");
    }

    private void emit(boolean on, String spk) {
        String j = "{\"type\":\"heli\",\"on\":" + on + ",\"spk\":\"" + esc(spk) + "\"}";
        main.post(() -> listener.onDemo(j));
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "").replace("\"", "");
    }

    private static Sp[] parse(JSONArray a) {
        if (a == null) return new Sp[0];
        Sp[] out = new Sp[a.length()];
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Sp s = new Sp();
            s.n = o.optString("n", "S" + (i + 1));
            s.x = (float) o.optDouble("x", 50);
            s.y = (float) o.optDouble("y", 50);
            s.height = o.optInt("h", 0) != 0 || s.n.startsWith("H");
            out[n++] = s;
        }
        if (n == out.length) return out;
        Sp[] t = new Sp[n];
        System.arraycopy(out, 0, t, 0, n);
        return t;
    }

    private void run() {
        final int sr = 44100;
        AudioTrack track = null;
        try {
            int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int bytes = Math.max(min, sr / 5 * 4);
            AudioAttributes attr = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attr)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            UsbDac.get(ctx).prefer(track);
            try { track.setVolume(1f); } catch (Exception ignored) {}
            track.play();

            final int n = 256;
            short[] buf = new short[n * 2];
            float[] ringL = new float[64];
            float[] ringR = new float[64];
            int rp = 0;
            float lp = 0;
            long noise = 0x1234567L;
            double rotor = 0;
            double eng = 0;
            long dwellStart = System.nanoTime();
            int idx = 0;
            final long dwellNs = 1_350_000_000L;
            final long fadeNs = 160_000_000L;

            while (running) {
                Sp[] m;
                float hx, hy, hd;
                synchronized (lock) {
                    m = map;
                    hx = headX;
                    hy = headY;
                    hd = head;
                }
                if (m == null || m.length == 0) {
                    Thread.sleep(40);
                    continue;
                }
                if (idx >= m.length) idx = 0;
                long now = System.nanoTime();
                long age = now - dwellStart;
                if (age >= dwellNs) {
                    idx = (idx + 1) % m.length;
                    dwellStart = now;
                    age = 0;
                }
                float fade = 1f;
                int bIdx = idx;
                if (age > dwellNs - fadeNs && m.length > 1) {
                    fade = (dwellNs - age) / (float) fadeNs;
                    bIdx = (idx + 1) % m.length;
                }
                Sp a = m[idx];
                Sp b = m[bIdx];
                float u = 1f - fade;
                float x = a.x + (b.x - a.x) * u;
                float y = a.y + (b.y - a.y) * u;
                boolean height = u < 0.5f ? a.height : b.height;
                String name = u < 0.35f ? a.n : b.n;
                if (!name.equals(at)) {
                    at = name;
                    emit(true, name);
                }

                float dx = x - hx;
                float dy = hy - y;
                float az = (float) Math.atan2(dx, dy);
                float side = (float) Math.sin(az);
                float front = (float) Math.cos(az);
                float dist = (float) Math.hypot(dx, dy);
                float distG = 1.25f / (0.38f + dist / 48f);
                float gL = (float) Math.sqrt(Math.max(0.02, (1 - side) * 0.5)) * distG;
                float gR = (float) Math.sqrt(Math.max(0.02, (1 + side) * 0.5)) * distG;
                if (front < 0) {
                    float rear = -front;
                    gL *= 1f - 0.18f * rear;
                    gR *= 1f - 0.18f * rear;
                }
                if (height) {
                    gL *= 0.92f;
                    gR *= 0.92f;
                }
                int maxD = (int) (18 + hd / 100f * 14);
                int delay = (int) (side * maxD);
                int dL = delay > 0 ? delay : 0;
                int dR = delay < 0 ? -delay : 0;

                for (int i = 0; i < n; i++) {
                    rotor += 2 * Math.PI * 23.5 / sr;
                    eng += 2 * Math.PI * (height ? 210 : 118) / sr;
                    noise = noise * 1664525L + 1013904223L;
                    float nz = ((noise >> 16) & 0xffff) / 32768f - 1f;
                    float chop = (float) Math.pow(Math.max(0, Math.sin(rotor)), 6);
                    float raw = (nz * 0.55f + (float) Math.sin(eng) * 0.42f) * (0.22f + 0.78f * chop);
                    float alpha = front < 0 ? 0.12f : (height ? 0.72f : 0.48f);
                    lp += alpha * (raw - lp);
                    float mono = front < 0 ? lp : (height ? raw * 0.35f + (raw - lp) * 0.85f : raw);
                    mono *= 0.92f;
                    ringL[rp] = mono * gL;
                    ringR[rp] = mono * gR;
                    int iL = (rp - dL) & 63;
                    int iR = (rp - dR) & 63;
                    rp = (rp + 1) & 63;
                    buf[i * 2] = (short) Math.max(-32767, Math.min(32767, ringL[iL] * 32767));
                    buf[i * 2 + 1] = (short) Math.max(-32767, Math.min(32767, ringR[iR] * 32767));
                }
                int w = track.write(buf, 0, buf.length);
                if (w < 0) break;
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable ignored) {
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
            }
        }
    }
}
