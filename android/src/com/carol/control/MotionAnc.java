package com.carol.control;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Opt-in. While the mix service is up, the phone accelerometer picks still / walk / run
 * and sends the existing ANC mode command. Nothing leaves the phone.
 */
final class MotionAnc implements SensorEventListener {
    private static MotionAnc inst;
    private SensorManager sm;
    private boolean listening;
    private String last = "";
    private long lastAt;
    private int n;
    private double sum, sumSq;

    static void setEnabled(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences("carol_ble", 0).edit().putBoolean("motion_anc", on).apply();
        } catch (Throwable ignored) {
        }
        if (on) start(ctx);
        else stop();
    }

    static boolean enabled(Context ctx) {
        try {
            return ctx.getSharedPreferences("carol_ble", 0).getBoolean("motion_anc", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void start(Context ctx) {
        if (!enabled(ctx)) return;
        if (inst == null) inst = new MotionAnc();
        inst.listen(ctx.getApplicationContext());
    }

    static void stop() {
        if (inst != null) inst.unlisten();
    }

    private void listen(Context ctx) {
        if (listening) return;
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) return;
        listening = sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void unlisten() {
        if (sm != null && listening) {
            try { sm.unregisterListener(this); } catch (Throwable ignored) {}
        }
        listening = false;
        n = 0;
        sum = sumSq = 0;
        last = "";
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e == null || e.values == null || e.values.length < 3) return;
        float x = e.values[0], y = e.values[1], z = e.values[2];
        double mag = Math.sqrt(x * x + y * y + z * z);
        n++;
        sum += mag;
        sumSq += mag * mag;
        long now = System.currentTimeMillis();
        if (n < 20 || now - lastAt < 8000) return;
        double mean = sum / n;
        double var = Math.max(0, sumSq / n - mean * mean);
        n = 0;
        sum = sumSq = 0;
        lastAt = now;
        String mode;
        if (var < 0.35 && mean > 8 && mean < 11.5) mode = "anc";
        else if (var < 3.5) mode = "transparency";
        else mode = "off";
        if (mode.equals(last)) return;
        last = mode;
        MainActivity.applyAnc(mode);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
