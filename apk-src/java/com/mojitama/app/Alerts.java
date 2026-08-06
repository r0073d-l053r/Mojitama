package com.mojitama.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pet-care reminders.
 *
 * The page predicts WHEN each need will run low and hands us the list; we fire
 * at those times. No game rules live here — one source of truth means the
 * reminder always agrees with what the player sees when they open the app.
 *
 * Two deliberate choices, both forced by how Android throttles background work:
 *
 *  - setAndAllowWhileIdle() is the only setter that pierces Doze without a
 *    permission. SCHEDULE_EXACT_ALARM is denied by default on Android 14+ and
 *    USE_EXACT_ALARM is reserved for alarm clocks; a pet nudge needs neither.
 *  - Only ONE alarm is ever armed — the next one — and the receiver arms the
 *    following one after it fires. App Standby drops a neglected app to one
 *    alarm per hour (or per day when "restricted"), which is exactly the state
 *    an app is in when the pet most needs attention, so a batch of pending
 *    alarms would be silently eaten.
 */
final class Alerts {

    static final String ACTION_FIRE = "com.mojitama.app.ALERT";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_KIND = "kind";

    private static final String CHANNEL_ID = "pet_care_v1";
    private static final int REQ_NEXT = 1001;
    private static final Uri SLOT = Uri.parse("mojitama://alert/next");

    private static final long HORIZON_MS = 48L * 60 * 60 * 1000;   // don't plan further ahead
    private static final long PER_KIND_GAP_MS = 3L * 60 * 60 * 1000; // don't nag about the same need
    private static final int DAILY_CAP = 6;

    private Alerts() {}

    /* ---- scheduling ---- */

    private static PendingIntent slotIntent(Context c, String kind, String title, String text) {
        Intent i = new Intent(c, AlertReceiver.class)
                .setAction(ACTION_FIRE)
                .setData(SLOT)                       // identity ignores extras — keep the Uri stable
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text);
        return PendingIntent.getBroadcast(c, REQ_NEXT, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Replace the schedule. `json` is [{at,kind,title,text}] from the page. */
    static void schedule(Context c, String json) {
        Store.putAlerts(c, json == null ? "[]" : json);
        armNext(c);
    }

    /** Arm the single next alarm from whatever is stored. Safe to call repeatedly. */
    static void armNext(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        // always clear the slot first — the stored list is authoritative
        am.cancel(slotIntent(c, "", "", ""));

        final JSONObject snap = Store.snapshot(c);
        if (snap != null && !"alive".equals(snap.optString("phase", "alive"))) {
            return;                                   // no pet to nag about
        }

        long now = System.currentTimeMillis();
        JSONArray list = Store.alerts(c);
        JSONObject next = null;
        long bestAt = Long.MAX_VALUE;

        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            long at = o.optLong("at", 0);
            // only a true "already passed" test — the page floors its earliest
            // entry a few minutes out, and anything tighter would skip it
            if (at <= now + 5000L) continue;
            if (at > now + HORIZON_MS) continue;      // beyond the planning horizon
            if (at < bestAt) { bestAt = at; next = o; }
        }

        String kind, title, text;
        if (next != null) {
            kind = next.optString("kind", "hunger");
            title = next.optString("title", "Your pet needs you");
            text = next.optString("text", "");
        } else {
            // The page only refreshes this list while it is running. Once the
            // last entry has fired, native must be able to carry on alone or
            // reminders would stop for good a few hours after the app was closed.
            long at = nextFromSnapshot(c, snap, now);
            if (at <= 0) return;
            bestAt = at;
            kind = "checkin";
            String name = snap == null ? "Your pet" : snap.optString("name", "Your pet");
            title = name + " needs you";
            text = "It has been a while — go and check on them.";
        }

        PendingIntent pi = slotIntent(c, kind, title, text);
        // RTC_WAKEUP: wall clock, so it still means the right moment after a reboot
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bestAt, pi);
    }

    /**
     * Extrapolate from the snapshot to the next moment a need crosses the alert
     * threshold. Uses only the model the page handed us — needs, their per-hour
     * rates, and the offline scaling — never a second copy of the game rules.
     */
    private static long nextFromSnapshot(Context c, JSONObject snap, long now) {
        if (snap == null || !"alive".equals(snap.optString("phase", ""))) return 0;
        JSONObject needs = snap.optJSONObject("needs");
        JSONObject rate = snap.optJSONObject("rate");
        if (needs == null || rate == null) return 0;

        double scale = snap.optDouble("offlineScale", 0.5);
        long capMs = snap.optLong("offlineCapMs", 12L * 60 * 60 * 1000);
        long sampledAt = snap.optLong("at", now);
        double spent = Math.max(0, now - sampledAt);          // budget already burned

        long best = 0;
        String[] keys = {"hunger", "happy", "energy", "hygiene"};
        for (String k : keys) {
            double perHour = rate.optDouble(k, 0) * scale;
            if (perHour <= 0) continue;                        // recovering or static
            double value = needs.optDouble(k, 100)
                    - perHour * (Math.min(spent, capMs) / 3600000.0);
            double drop = value - 25;
            if (drop <= 0) continue;                            // already low; page covered it
            double ms = (drop / perHour) * 3600000.0;
            if (spent + ms > capMs) continue;                   // budget runs out first
            long at = now + (long) ms;
            if (at <= now + 60000L) continue;
            if (best == 0 || at < best) best = at;
        }
        // nothing crosses within the offline budget: check back in a day rather
        // than going silent forever
        if (best == 0) best = now + 24L * 60 * 60 * 1000;
        return best;
    }

    static void cancelAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(slotIntent(c, "", "", ""));
        Store.putAlerts(c, "[]");
    }

    /* ---- rate limiting, enforced at fire time rather than schedule time ---- */

    static boolean allowedNow(Context c, String kind) {
        long now = System.currentTimeMillis();
        if (now - Store.lastPostAt(c, kind) < PER_KIND_GAP_MS) return false;
        return Store.postsToday(c, now) < DAILY_CAP;
    }

    static void recordPost(Context c, String kind) {
        Store.recordPost(c, kind, System.currentTimeMillis());
    }

    /* ---- posting ---- */

    /** Idempotent and cheap. Must also run in the receiver's process, which after
     *  a reboot may never have run the Activity. */
    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        createChannelO(c);
    }

    // isolated so API 24/25 never loads the NotificationChannel class
    private static void createChannelO(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                c.getString(R.string.channel_care),
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription(c.getString(R.string.channel_care_desc));
        ch.enableLights(true);
        ch.setLightColor(Color.parseColor("#7C5CFF"));
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    static void post(Context c, String kind, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannel(c);

        // tapping opens the Activity directly: a broadcast that then starts an
        // Activity is a "notification trampoline" and is blocked on API 31+
        Intent open = new Intent(c, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(c, 2001, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CHANNEL_ID);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_DEFAULT);
            // pre-O there is no channel to carry these: lights alone would make
            // the reminder completely silent on Android 7, which defeats it
            b.setDefaults(Notification.DEFAULT_LIGHTS
                    | Notification.DEFAULT_SOUND
                    | Notification.DEFAULT_VIBRATE);
        }
        b.setSmallIcon(R.drawable.ic_notify)          // white silhouette; RGB is discarded
         .setContentTitle(title)
         .setContentText(text)
         .setStyle(new Notification.BigTextStyle().bigText(text))
         .setColor(Color.parseColor("#7C5CFF"))
         .setAutoCancel(true)
         .setContentIntent(content);

        // one stable id per kind, so a repeat replaces rather than stacks
        nm.notify(3000 + Math.abs(kind == null ? 0 : kind.hashCode() % 100), b.build());
    }

    /** True when the system will actually show what we post. */
    static boolean enabled(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    static ComponentName widgetComponent(Context c) {
        return new ComponentName(c, MojitamaWidget.class);
    }
}
