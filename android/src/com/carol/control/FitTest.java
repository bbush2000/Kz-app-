package com.carol.control;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Sequential ear-tip fit test over A2DP stereo (not SCO).
 * Left finishes and is graded before the right tone starts.
 */
final class FitTest {
    interface Listener {
        void onFit(String json);
    }

    private final Context ctx;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile boolean running;
    private Thread worker;
    private int savedVol = -1;
    private static final double TONE_HZ = 500;

    FitTest(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
    }

    void start() {
        stop();
        running = true;
        worker = new Thread(this::run, "carol-fit");
        worker.start();
    }

    void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.interrupt();
                worker.join(1200);
            } catch (Exception ignored) {
            }
            worker = null;
        }
        restoreAudio();
        SpatialFx.get(ctx).fitHold(false);
    }

    private void emit(String json) {
        main.post(() -> listener.onFit(json));
    }

    private void run() {
        AudioRecord rec = null;
        int sr = 44100;
        restoreAudio();
        try {
            SpatialFx.get(ctx).fitHold(true);
            boostMedia();
            emit("{\"type\":\"fit\",\"phase\":\"quiet\",\"msg\":\"Keep still — measuring the room (~1 s)\"}");
            int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, sr * 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("mic");
            }
            rec.startRecording();
            double noise = capture(rec, 900);
            if (!running) return;

            emit("{\"type\":\"fit\",\"phase\":\"left\",\"msg\":\"Left bud — two long beeps. Keep still.\"}");
            drain(rec);
            double[] left = probe(rec, sr, true);
            if (!running) return;
            String lg = grade(left[0], left[1], noise);
            emit("{\"type\":\"fit\",\"phase\":\"leftwait\",\"left\":\"" + lg
                    + "\",\"msg\":\"Left: " + gradeLabel(lg) + ". Pause, then right bud…\"}");
            sleep(1800);
            if (!running) return;

            emit("{\"type\":\"fit\",\"phase\":\"right\",\"msg\":\"Right bud — three long beeps. Keep still.\"}");
            drain(rec);
            sleep(400);
            if (!running) return;
            double[] right = probe(rec, sr, false);
            if (!running) return;
            String rg = grade(right[0], right[1], noise);
            emit("{\"type\":\"fit\",\"phase\":\"done\",\"left\":\"" + lg + "\",\"right\":\"" + rg
                    + "\",\"msg\":\"Left " + gradeLabel(lg) + " · Right " + gradeLabel(rg)
                    + ". Swap the tip on any Loose side.\"}");
        } catch (SecurityException se) {
            emit("{\"type\":\"fit\",\"phase\":\"error\",\"msg\":\"Microphone permission is required for the fit test\"}");
        } catch (Throwable t) {
            emit("{\"type\":\"fit\",\"phase\":\"error\",\"msg\":\"Fit test failed — keep media volume around half and retry\"}");
        } finally {
            try {
                if (rec != null) {
                    rec.stop();
                    rec.release();
                }
            } catch (Exception ignored) {
            }
            restoreAudio();
            SpatialFx.get(ctx).fitHold(false);
            running = false;
        }
    }

    private void restoreAudio() {
        if (am == null) return;
        try {
            am.setBluetoothScoOn(false);
            am.stopBluetoothSco();
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {
        }
        if (savedVol >= 0) {
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0); } catch (Exception ignored) {}
            savedVol = -1;
        }
    }

    private void boostMedia() {
        if (am == null) return;
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            savedVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int want = Math.max(savedVol, (int) Math.round(max * 0.92));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(max, want), 0);
        } catch (Exception ignored) {
        }
    }

    /**
     * Left = 2 beeps, right = 3. Long tones + gaps so A2DP has time to
     * start, and the mic records the whole burst plus a tail.
     */
    private double[] probe(AudioRecord rec, int sr, boolean leftBud) {
        int beeps = leftBud ? 2 : 3;
        int beep = (int) (sr * 0.70);
        int gap = (int) (sr * 0.38);
        int tail = (int) (sr * 0.40);
        int n = beeps * beep + (beeps - 1) * gap + tail;
        short[] stereo = new short[n * 2];
        double a = leftBud ? 0.94 : 0.99;
        int t = 0;
        for (int b = 0; b < beeps; b++) {
            for (int i = 0; i < beep; i++, t++) {
                double fade = 1;
                int fadeN = sr / 50;
                if (i < fadeN) fade = i / (double) fadeN;
                if (i > beep - fadeN) fade = Math.max(0, (beep - i) / (double) fadeN);
                double s = fade * a * Math.sin(2 * Math.PI * TONE_HZ * t / sr);
                short v = (short) Math.max(-32767, Math.min(32767, s * 32767));
                if (leftBud) {
                    stereo[t * 2] = v;
                    stereo[t * 2 + 1] = 0;
                } else {
                    stereo[t * 2] = 0;
                    stereo[t * 2 + 1] = v;
                }
            }
            t += gap;
        }
        AudioTrack track = null;
        try {
            AudioAttributes attr = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            int bytes = Math.max(stereo.length * 2, AudioTrack.getMinBufferSize(sr,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT));
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attr)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            UsbDac.get(ctx).prefer(track);
            track.write(stereo, 0, stereo.length);
            try { track.setVolume(1f); } catch (Exception ignored) {}

            int recN = rec.getSampleRate() * (n + sr / 5) / sr;
            if (recN < rec.getSampleRate()) recN = rec.getSampleRate();
            short[] buf = new short[recN];
            track.play();

            int got = 0;
            long end = System.currentTimeMillis() + (n * 1000L / sr) + 600;
            while (got < recN && running && System.currentTimeMillis() < end) {
                int r = rec.read(buf, got, recN - got);
                if (r > 0) got += r;
                else if (r < 0) break;
            }
            waitPlay(track, stereo.length / 2);
            return new double[]{
                    goertzel(buf, got, TONE_HZ, rec.getSampleRate()),
                    rms(buf, got)
            };
        } finally {
            if (track != null) {
                try {
                    track.stop();
                    track.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void waitPlay(AudioTrack track, int frames) {
        try {
            long until = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < until && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                int head = track.getPlaybackHeadPosition();
                if (frames > 0 && head >= frames - 48) break;
                Thread.sleep(50);
            }
        } catch (Exception ignored) {
        }
    }

    private void drain(AudioRecord rec) {
        try {
            short[] tmp = new short[2048];
            if (Build.VERSION.SDK_INT >= 23) {
                for (int i = 0; i < 8; i++) {
                    int r = rec.read(tmp, 0, tmp.length, AudioRecord.READ_NON_BLOCKING);
                    if (r <= 0) break;
                }
            } else {
                rec.read(tmp, 0, Math.min(tmp.length, rec.getSampleRate() / 20));
            }
        } catch (Exception ignored) {
        }
    }

    private double capture(AudioRecord rec, int ms) {
        int n = rec.getSampleRate() * Math.max(ms, 280) / 1000;
        if (n < 256) n = 256;
        short[] buf = new short[n];
        int got = 0;
        long end = System.currentTimeMillis() + ms + 200;
        while (got < n && running && System.currentTimeMillis() < end) {
            int r = rec.read(buf, got, n - got);
            if (r > 0) got += r;
            else if (r < 0) break;
        }
        return rms(buf, got);
    }

    private static double rms(short[] x, int n) {
        if (n <= 0) return 1;
        double s = 0;
        for (int i = 0; i < n; i++) s += (double) x[i] * x[i];
        return Math.sqrt(s / n) + 1;
    }

    private static double goertzel(short[] x, int n, double freq, double sr) {
        if (n < 16) return 1;
        double k = (int) (0.5 + n * freq / sr);
        double w = 2 * Math.PI * k / n;
        double c = 2 * Math.cos(w);
        double s0 = 0, s1 = 0, s2 = 0;
        for (int i = 0; i < n; i++) {
            s0 = x[i] + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double mag = Math.sqrt(s1 * s1 + s2 * s2 - c * s1 * s2);
        return mag / n + 1;
    }

    private static String grade(double bass, double ref, double noise) {
        double leak = (bass - noise) / Math.max(ref, noise);
        if (ref < noise * 1.15 && bass < noise * 1.15) return "quiet";
        if (leak < 0.55) return "good";
        if (leak < 1.15) return "ok";
        return "loose";
    }

    private static String gradeLabel(String g) {
        if ("good".equals(g)) return "good seal";
        if ("ok".equals(g)) return "OK";
        if ("loose".equals(g)) return "loose";
        if ("quiet".equals(g)) return "no tone heard";
        return g;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
