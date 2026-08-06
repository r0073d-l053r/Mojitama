package com.mojitama.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The one place native code and the web app meet.
 *
 * The game's real state lives in the WebView's localStorage; native cannot read
 * that. So the page hands us a compact snapshot whenever it saves, and we keep
 * it here for the widget and the alarm receiver to read. Nothing here simulates
 * the game — it only stores what the page computed.
 *
 * Written from the WebView's JS thread and read from BroadcastReceivers in
 * (potentially) other processes, so writes use commit() rather than apply():
 * a receiver that reads immediately after must not race an unflushed write.
 */
final class Store {

    private static final String PREFS = "mojitama_bridge";
    private static final String K_SNAPSHOT = "snapshot";
    private static final String K_ALERTS = "alerts";
    private static final String K_LAST_POST = "last_post_";
    private static final String K_DAY_START = "day_start";
    private static final String K_DAY_COUNT = "day_count";

    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* ---- snapshot: what the widget draws ---- */

    static void putSnapshot(Context c, String json) {
        prefs(c).edit().putString(K_SNAPSHOT, json).commit();
    }

    static JSONObject snapshot(Context c) {
        String s = prefs(c).getString(K_SNAPSHOT, null);
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    /* ---- alerts: the schedule we re-arm after a reboot ---- */

    static void putAlerts(Context c, String jsonArray) {
        prefs(c).edit().putString(K_ALERTS, jsonArray).commit();
    }

    static JSONArray alerts(Context c) {
        String s = prefs(c).getString(K_ALERTS, null);
        if (s == null) return new JSONArray();
        try {
            return new JSONArray(s);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /* ---- notification rate limiting ---- */

    static long lastPostAt(Context c, String kind) {
        return prefs(c).getLong(K_LAST_POST + kind, 0);
    }

    /** How many reminders have been posted in the current rolling day. */
    static int postsToday(Context c, long now) {
        SharedPreferences p = prefs(c);
        long dayStart = p.getLong(K_DAY_START, 0);
        if (now - dayStart > 24L * 60 * 60 * 1000) return 0;   // window rolled over
        return p.getInt(K_DAY_COUNT, 0);
    }

    static void recordPost(Context c, String kind, long now) {
        SharedPreferences p = prefs(c);
        long dayStart = p.getLong(K_DAY_START, 0);
        int count = p.getInt(K_DAY_COUNT, 0);
        if (now - dayStart > 24L * 60 * 60 * 1000) { dayStart = now; count = 0; }
        p.edit()
         .putLong(K_LAST_POST + kind, now)
         .putLong(K_DAY_START, dayStart)
         .putInt(K_DAY_COUNT, count + 1)
         .commit();
    }
}
