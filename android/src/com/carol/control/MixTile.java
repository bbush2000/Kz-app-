package com.carol.control;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** One tile. Chip up: tap cycles ANC. Classic audio: tap cycles the phone layout. Otherwise opens the app. */
public class MixTile extends TileService {
    @Override
    public void onStartListening() {
        paint();
    }

    @Override
    public void onClick() {
        if (chipUp()) {
            sendBroadcast(new Intent(CarolActions.CYCLE_ANC).setPackage(getPackageName()));
        } else if (budsUp()) {
            sendBroadcast(new Intent(CarolActions.CYCLE_SPATIAL).setPackage(getPackageName()));
        } else {
            openApp();
            return;
        }
        paint();
    }

    private boolean chipUp() {
        try {
            android.content.SharedPreferences p = getSharedPreferences("carol_ble", 0);
            return p.getBoolean("sess_buds", false) && p.getBoolean("cache_chip", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean budsUp() {
        try {
            return getSharedPreferences("carol_ble", 0).getBoolean("sess_buds", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openApp() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("carol_open", "device");
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 21, open, Compat.piFlags());
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(open);
            }
        } catch (Exception ignored) {
            try { startActivity(open); } catch (Exception ignored2) {}
        }
    }

    private void paint() {
        Tile t = getQsTile();
        if (t == null) return;
        String anc = "";
        String spat = "off";
        boolean up = chipUp();
        boolean buds = budsUp();
        try {
            android.content.SharedPreferences p = getSharedPreferences("carol_ble", 0);
            anc = p.getString("cache_anc", "");
            spat = p.getString("spatial", "off");
        } catch (Exception ignored) {
        }
        String sub = "";
        if (up) {
            sub = "anc".equals(anc) ? "ANC" : "transparency".equals(anc) ? "Trans" : "Off";
        } else if (buds) {
            sub = "off".equals(spat) ? "Stereo" : spat;
        }
        t.setLabel("KZ");
        t.setContentDescription(sub.length() > 0 ? sub : "KZ");
        t.setState(up || buds ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            try { t.setSubtitle(sub); } catch (Exception ignored) {}
        }
        t.updateTile();
    }

    static void refresh(Context ctx) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                TileService.requestListeningState(ctx,
                        new ComponentName(ctx, MixTile.class));
            } catch (Exception ignored) {
            }
        }
    }
}
