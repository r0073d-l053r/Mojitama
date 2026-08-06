package com.mojitama.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

/**
 * Home-screen widget: a status readout plus shortcuts into the app.
 *
 * It renders the snapshot the page last wrote, ageing each need forward with the
 * decay rates in that snapshot so the bars keep moving between app opens.
 *
 * The buttons deliberately do NOT change the game. Each one opens the app and
 * asks it to perform that action there, so the player sees the food menu, the
 * game, the broom sweeping — and every rule and guard runs exactly once, in the
 * one place that owns them.
 */
public class MojitamaWidget extends AppWidgetProvider {

    // same thresholds and colours the in-app meters use
    private static final int GOOD = 0xFF3ECF8E;
    private static final int WARN = 0xFFFFB020;
    private static final int BAD  = 0xFFFF5470;
    private static final int TRACK = 0x55FFFFFF;

    /* ---- lifecycle ---- */

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        // never let a render failure escape: an exception here leaves the widget
        // showing initialLayout forever, which has no click handlers at all
        try {
            for (int id : ids) mgr.updateAppWidget(id, build(context));
        } catch (Throwable ignored) {}
    }

    /** Redraw every placed instance. Safe from any thread; no-ops when none exist. */
    static void refresh(Context context) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            if (mgr == null) return;
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, MojitamaWidget.class));
            if (ids == null || ids.length == 0) return;
            mgr.updateAppWidget(ids, build(context));
        } catch (Throwable ignored) {
            // a widget that fails to draw must never take the app down with it
        }
    }

    /* ---- rendering ---- */

    private static RemoteViews build(Context c) {
        // always a fresh RemoteViews: the action list accumulates otherwise
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
        JSONObject s = Store.snapshot(c);

        String face = "🥚";
        String name = "Mojitama";
        String sub = "Tap to play";
        boolean alive = s != null && "alive".equals(s.optString("phase", ""));

        if (s == null) {
            sub = "Open the app to link up";
        } else {
            face = s.optString("face", "🥚");
            name = s.optString("name", "Mojitama");
            String phase = s.optString("phase", "select");

            if (alive) {
                sub = s.optString("stage", "")
                        + (s.optBoolean("sleeping", false) ? " · asleep 💤" : "")
                        + (s.optBoolean("sick", false) ? " · sick 🤒" : "");
            } else if ("egg".equals(phase)) {
                long left = s.optLong("eggRemainMs", 0) - elapsedSince(s);
                sub = left > 0 ? "Hatching in " + Math.max(1, left / 60000) + "m" : "Ready to hatch!";
            } else if ("dead".equals(phase)) {
                face = "🕊️";
                sub = "Tap to start a new egg";
            } else {
                sub = "No pet yet — tap to choose";
            }
        }

        rv.setTextViewText(R.id.w_face, face);
        rv.setTextViewText(R.id.w_name, name);
        rv.setTextViewText(R.id.w_sub, sub);

        // meters: hidden entirely unless there is a living pet to describe
        rv.setViewVisibility(R.id.w_meters, alive ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.w_actions, alive ? View.VISIBLE : View.GONE);

        if (alive) {
            JSONObject needs = s.optJSONObject("needs");
            JSONObject rate = s.optJSONObject("rate");
            double hours = offlineHours(s);
            double scale = s.optDouble("offlineScale", 0.5);

            rv.setImageViewBitmap(R.id.w_bar_hunger,
                    barBitmap(c, value(needs, rate, "hunger", hours, scale)));
            rv.setImageViewBitmap(R.id.w_bar_happy,
                    barBitmap(c, value(needs, rate, "happy", hours, scale)));
            rv.setImageViewBitmap(R.id.w_bar_energy,
                    barBitmap(c, value(needs, rate, "energy", hours, scale)));
            rv.setImageViewBitmap(R.id.w_bar_hygiene,
                    barBitmap(c, value(needs, rate, "hygiene", hours, scale)));
            // health is not a decaying need — it is whatever the app last saved
            rv.setImageViewBitmap(R.id.w_bar_health,
                    barBitmap(c, clamp(s.optInt("health", 100))));
        }

        // tapping anywhere else just opens the app
        PendingIntent open = launch(c, null, 4000);
        rv.setOnClickPendingIntent(R.id.w_root, open);
        rv.setOnClickPendingIntent(R.id.w_face, open);
        rv.setOnClickPendingIntent(R.id.w_name, open);

        rv.setOnClickPendingIntent(R.id.w_feed,  launch(c, "feed",  4001));
        rv.setOnClickPendingIntent(R.id.w_play,  launch(c, "play",  4002));
        rv.setOnClickPendingIntent(R.id.w_clean, launch(c, "clean", 4003));
        rv.setOnClickPendingIntent(R.id.w_meds,  launch(c, "meds",  4004));
        return rv;
    }

    /**
     * Open MainActivity, optionally asking it to run one action once the page is up.
     *
     * PendingIntent identity is requestCode + Intent.filterEquals(), and filterEquals
     * IGNORES extras — so each shortcut needs its own requestCode AND its own data
     * Uri, or they collapse into one and every button does the same thing.
     */
    private static PendingIntent launch(Context c, String action, int req) {
        Intent i = new Intent(c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (action == null) {
            i.setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        } else {
            i.setAction(MainActivity.ACTION_OPEN)
             .setData(Uri.parse("mojitama://open/" + action))
             .putExtra(MainActivity.EXTRA_ACTION, action);
        }
        return PendingIntent.getActivity(c, req, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** One meter bar, drawn rather than themed: RemoteViews cannot retint a
     *  ProgressBar before API 31, and the colour is the whole point. */
    private static Bitmap barBitmap(Context c, int pct) {
        float d = c.getResources().getDisplayMetrics().density;
        int w = Math.max(32, Math.round(52 * d));
        int h = Math.max(4, Math.round(5 * d));
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = h / 2f;

        p.setColor(TRACK);
        cv.drawRoundRect(new RectF(0, 0, w, h), r, r, p);

        if (pct > 0) {
            p.setColor(pct > 55 ? GOOD : pct > 25 ? WARN : BAD);
            float fill = Math.max(w * (pct / 100f), h);   // keep the cap visible when nearly empty
            cv.drawRoundRect(new RectF(0, 0, fill, h), r, r, p);
        }
        return bm;
    }

    /* ---- extrapolation, using only the model the page supplied ---- */

    private static long elapsedSince(JSONObject s) {
        long at = s.optLong("at", 0);
        if (at <= 0) return 0;
        long d = System.currentTimeMillis() - at;
        return d < 0 ? 0 : d;                       // clock moved backwards
    }

    /** The game halves decay while the app is closed and stops accruing it past
     *  the offline budget — mirror both, or the widget shows a starving pet that
     *  is fine the moment you open it. */
    private static double offlineHours(JSONObject s) {
        long capMs = s.optLong("offlineCapMs", 12L * 60 * 60 * 1000);
        return Math.min(elapsedSince(s), capMs) / 3600000.0;
    }

    private static int value(JSONObject n, JSONObject r, String key, double hours, double scale) {
        if (n == null) return 100;
        double v = n.optDouble(key, 100);
        if (r != null) v -= r.optDouble(key, 0) * scale * hours;   // negative rate = recovering
        return clamp((int) Math.round(v));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }
}
