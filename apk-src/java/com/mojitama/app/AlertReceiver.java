package com.mojitama.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/**
 * Fires a pet-care reminder, and re-arms the schedule after events that wipe it.
 *
 * Alarms do not survive a reboot or an app update, so the stored schedule is
 * re-armed on BOOT_COMPLETED and MY_PACKAGE_REPLACED. Everything here runs on
 * the main thread and must finish quickly — the process can be killed the
 * moment onReceive returns.
 */
public class AlertReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || context == null) return;
        String action = intent.getAction();
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Alerts.ensureChannel(context);
            Alerts.armNext(context);
            MojitamaWidget.refresh(context);
            return;
        }

        if (Alerts.ACTION_FIRE.equals(action)) {
            String kind = intent.getStringExtra(Alerts.EXTRA_KIND);
            // The pet may have died, been replaced, or already been cared for since
            // this alarm was set. Re-derive from the snapshot rather than nagging blindly.
            if (stillRelevant(context, kind) && Alerts.allowedNow(context, kind)) {
                String title = intent.getStringExtra(Alerts.EXTRA_TITLE);
                String text = intent.getStringExtra(Alerts.EXTRA_TEXT);
                if (title == null) title = "Your pet needs you";
                if (text == null) text = "";
                Alerts.post(context, kind, title, text);
                Alerts.recordPost(context, kind);
            }
            // only one alarm is ever armed; chain the following one now
            Alerts.armNext(context);
            MojitamaWidget.refresh(context);
        }
    }

    /**
     * A reminder is only worth showing if the pet is still alive, and — for the
     * need-based ones — if the player has not already dealt with it since the
     * alarm was scheduled. The snapshot carries the need level and its per-hour
     * decay, so we can extrapolate the same way the widget does.
     */
    private boolean stillRelevant(Context c, String kind) {
        JSONObject s = Store.snapshot(c);
        if (s == null) return true;                        // no snapshot: don't suppress
        if (!"alive".equals(s.optString("phase", "alive"))) return false;
        if (kind == null) return true;

        if ("sick".equals(kind)) return s.optBoolean("sick", false);
        if ("health".equals(kind)) return s.optInt("health", 100) <= 45;
        if ("checkin".equals(kind)) return true;
        if ("presick".equals(kind)) {
            // "might get sick" — only worth saying while it is still true and
            // has not already happened
            if (s.optBoolean("sick", false)) return false;
            JSONObject n = s.optJSONObject("needs");
            return s.optInt("poops", 0) >= 2
                || (n != null && extrapolate(s, n, "hygiene") < 45);
        }

        JSONObject needs = s.optJSONObject("needs");
        if (needs == null || !needs.has(kind)) return true;
        return extrapolate(s, needs, kind) <= 35;           // still genuinely low
    }

    /** Current value of a need, aged forward using the offline model the page
     *  supplied — the same arithmetic the widget uses. */
    private double extrapolate(JSONObject s, JSONObject needs, String key) {
        double value = needs.optDouble(key, 100);
        JSONObject rate = s.optJSONObject("rate");
        if (rate == null || !rate.has(key)) return value;

        double scale = s.optDouble("offlineScale", 0.5);
        long capMs = s.optLong("offlineCapMs", 12L * 60 * 60 * 1000);
        long elapsed = System.currentTimeMillis() - s.optLong("at", 0);
        if (elapsed <= 0) return value;                     // clock moved backwards
        double hours = Math.min(elapsed, capMs) / 3600000.0;

        value -= rate.optDouble(key, 0) * scale * hours;
        return value < 0 ? 0 : (value > 100 ? 100 : value);
    }
}
