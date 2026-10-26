package com.walkietalkie.wifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.IBinder;

/** Keeps the radio listening while the app is in the background. Only runs when "Background" is on. */
public final class RadioService extends Service implements RadioEngine.Listener {

    private static final String CHANNEL_ID = "radio";
    private static final String ACTION_OFF = "com.walkietalkie.wifi.action.OFF";
    private static final int NOTE_ID = 7;

    private RadioEngine engine;
    private String shown;
    private boolean held;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, RadioService.class));
        } catch (RuntimeException ignored) {
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, RadioService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        engine = RadioEngine.get(this);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Radio", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shown while Walkie Talkie listens in the background");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_OFF.equals(intent.getAction())) {
            engine.setPowered(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTE_ID, build());
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!held) {
            held = true;
            engine.addListener(this);
            engine.setServiceHeld(true);
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (held) {
            held = false;
            engine.removeListener(this);
            engine.setServiceHeld(false);
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onRadioChanged() {
        if (held && !text().equals(shown)) {
            getSystemService(NotificationManager.class).notify(NOTE_ID, build());
        }
    }

    private String text() {
        if (!engine.isPowered()) return "Radio is off";
        if (engine.net == RadioEngine.NET_NONE) return "Waiting for Wi-Fi";
        return "Listening on channel " + engine.channel;
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent off = PendingIntent.getService(this, 1,
                new Intent(this, RadioService.class).setAction(ACTION_OFF),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        shown = text();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Walkie Talkie")
                .setContentText(shown)
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_power), "Turn off", off).build())
                .build();
    }
}
