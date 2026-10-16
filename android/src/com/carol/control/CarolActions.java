package com.carol.control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Notification / Quick Settings actions. EQ bypass is phone-side only.
 * ANC cycle never writes the chip table.
 */
public class CarolActions extends BroadcastReceiver {
    static final String BYPASS = "com.carol.control.BYPASS";
    static final String BYPASS_OFF = "com.carol.control.BYPASS_OFF";
    static final String STEREO = "com.carol.control.STEREO";
    static final String CYCLE_SPATIAL = "com.carol.control.CYCLE_SPATIAL";
    static final String CYCLE_ANC = "com.carol.control.CYCLE_ANC";
    static final String FIND_PING = "com.carol.control.FIND_PING";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if (StatusNote.DISMISS.equals(a)) {
            StatusNote.dismiss(ctx);
            return;
        }
        SpatialFx fx = SpatialFx.get(ctx);
        if (BYPASS.equals(a)) fx.setBypass(!fx.bypass());
        else if (BYPASS_OFF.equals(a)) fx.setBypass(false);
        else if (STEREO.equals(a)) fx.apply("off");
        else if (CYCLE_SPATIAL.equals(a)) fx.cycleLayout();
        else if (CYCLE_ANC.equals(a)) MainActivity.cycleAnc(ctx);
        else if (FIND_PING.equals(a)) MainActivity.pingFind(ctx);
        MixTile.refresh(ctx);
        if (MainActivity.uiResumed && CarolService.session(ctx)) CarolService.start(ctx);
        else if (!CarolService.session(ctx)) CarolService.stop(ctx);
    }
}
