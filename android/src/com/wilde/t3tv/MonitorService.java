package com.wilde.t3tv;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

public class MonitorService extends Service {
    static volatile JSONObject snapshot;
    static volatile int feedState = DashboardState.CONNECTING;
    static volatile long lastSuccess;
    static volatile String feedDetail; // Diagnostic only; the dashboard never parses it for behavior.
    static final String UPDATED = "com.wilde.t3tv.UPDATED";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService worker;
    private View overlay;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("monitor", "Connection status", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel("tasks", "Tasks need attention", NotificationManager.IMPORTANCE_HIGH));
        startForeground(1, notification("monitor", "Watching your T3 environments", "Task alerts are on. Open the dashboard to pause.", 1));
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(this::poll, 0, 5, TimeUnit.SECONDS);
    }

    Notification notification(String channel, String title, String body, int id) {
        PendingIntent open = PendingIntent.getActivity(this, id, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, channel).setSmallIcon(R.drawable.icon).setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(!channel.equals("monitor"))
                .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build();
    }

    void poll() {
        try {
            JSONObject data = FeedClient.fetch(this);
            if (System.currentTimeMillis()/1000 - data.getLong("generatedAt") > 45) throw new IllegalStateException("Stale bridge feed");
            SharedPreferences prefs = getSharedPreferences("dashboard", 0);
            String stream = data.getString("streamId");
            long cursor = stream.equals(prefs.getString("streamId", "")) ? prefs.getLong("eventCursor", -1) : -1;
            long newest = cursor;
            JSONArray events = data.getJSONArray("events");
            int count = 0;
            JSONObject latest = null;
            for (int i=0; i<events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                long id = event.getLong("id");
                newest = Math.max(newest, id);
                if (cursor >= 0 && id > cursor) { latest = event; count++; }
            }
            // Persist before displaying: a service restart cannot replay the same alert.
            prefs.edit().putLong("eventCursor", Math.max(newest, 0)).putString("streamId", stream).commit();
            if (latest != null && prefs.getBoolean("alerts", true)) {
                String title = count == 1 ? DashboardState.statusLabel(latest.getString("status")) : count + " tasks need attention";
                String body = latest.getString("environment") + " · " + latest.getString("title");
                handler.post(() -> alert(title, body));
            }
            snapshot = data;
            lastSuccess = System.currentTimeMillis();
            feedState = DashboardState.LIVE;
            feedDetail = null;
        } catch (Exception e) {
            android.util.Log.w("T3Dashboard", "Feed refresh failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            feedState = lastSuccess == 0 ? DashboardState.CONNECTING : DashboardState.STALE;
            String message = e.getMessage();
            feedDetail = message != null && !message.isEmpty() ? message : e.getClass().getSimpleName();
        }
        sendBroadcast(new Intent(UPDATED).setPackage(getPackageName()));
    }

    void alert(String title, String body) {
        getSystemService(NotificationManager.class).notify(2, notification("tasks", title, body, 2));
        if (Settings.canDrawOverlays(this)) showOverlay(title, body);
    }

    void showOverlay(String title, String body) {
        WindowManager manager = getSystemService(WindowManager.class);
        if (overlay != null) { manager.removeView(overlay); overlay = null; }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20); card.setPadding(pad,pad,pad,pad);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(21,30,43)); bg.setCornerRadius(dp(12)); bg.setStroke(dp(2), Color.rgb(183,248,118)); card.setBackground(bg);
        TextView brand = new TextView(this); brand.setText("T3  /  TASK UPDATE"); brand.setTextColor(Color.rgb(183,248,118)); brand.setTextSize(12); card.addView(brand);
        TextView heading = new TextView(this); heading.setText(title); heading.setTextColor(Color.WHITE); heading.setTextSize(23); heading.setTypeface(null,Typeface.BOLD); card.addView(heading);
        TextView detail = new TextView(this); detail.setText(body); detail.setTextColor(Color.rgb(205,215,228)); detail.setTextSize(16); detail.setMaxLines(3); card.addView(detail);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(440), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.RIGHT; params.x=dp(28); params.y=dp(28);
        try {
            manager.addView(card, params); overlay=card;
            handler.postDelayed(() -> { if (overlay == card) { manager.removeView(card); overlay=null; } }, 9000);
        } catch (RuntimeException ignored) { /* System notifications remain available if overlay permission changes. */ }
    }

    int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "TEST".equals(intent.getAction())) handler.post(() -> alert("Test alert · ready to review", "This is a test notification from T3 Dashboard."));
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        worker.shutdownNow(); handler.removeCallbacksAndMessages(null);
        if (overlay != null) getSystemService(WindowManager.class).removeView(overlay);
        super.onDestroy();
    }
}
