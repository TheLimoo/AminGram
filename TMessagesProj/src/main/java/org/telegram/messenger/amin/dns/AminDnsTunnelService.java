package org.telegram.messenger.amin.dns;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.telegram.ui.LaunchActivity;

/**
 * Foreground-service shell for the DNS Tunnel (declared in the
 * manifest by patch 0021 as a {@code specialUse} FGS with subtype
 * {@code proxy_tunnel} — the exact shape dnstt-xyz ships, required
 * under targetSdk 36's FGS-type enforcement).
 *
 * The service owns NO engine logic: AminDnsController orchestrates
 * the engines (Soar in-process via the gomobile .aar, Slipstream
 * as a subprocess) on its worker thread. This service exists to
 * keep the process alive at foreground priority while the tunnel
 * is up and to show the persistent notification with a Disconnect
 * action. It is only ever started from a user-visible UI action
 * (the DNS Tunnel page or the hub toggle), which keeps it inside
 * the FGS-start exemption path.
 *
 * Deliberately NO wakelock (AminGram's power rule: official-app
 * level only). The honest cost, also stated in the UI: with the
 * screen off for long stretches Android may batch DNS traffic and
 * the tunnel can feel stall-y until the app is foregrounded;
 * Telegram's own connection recovery covers re-entry.
 */
public class AminDnsTunnelService extends Service implements AminDnsController.Listener {

    private static final String CHANNEL_ID = "amingram_dns_tunnel";
    private static final int NOTIFICATION_ID = 4207;
    private static final String ACTION_STOP = "org.telegram.messenger.amin.dns.STOP";

    private AminDnsController controller;
    private boolean foregroundStarted;

    @Override
    public void onCreate() {
        super.onCreate();
        controller = AminDnsController.get(this);
        controller.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (controller != null) {
                controller.stop();
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        // startForeground must land immediately (FGS contract);
        // engine boot happens on the controller's worker thread.
        startForeground(NOTIFICATION_ID, buildNotification());
        foregroundStarted = true;
        // Not sticky: a system restart of this service must not
        // resurrect the tunnel on its own — if the process died,
        // AminDnsController.recoverIfStale restores the user's
        // pre-tunnel proxy instead (plan §4.1).
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (controller != null) {
            controller.removeListener(this);
            // Killed without a user stop (and the process lived):
            // funnels into the same restore path as a normal stop.
            controller.onServiceDied();
        }
        foregroundStarted = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDnsStateChanged() {
        if (!foregroundStarted) {
            return;
        }
        if (controller.state() == AminDnsController.State.OFF
                || controller.state() == AminDnsController.State.ERROR) {
            stopForeground(true);
            foregroundStarted = false;
            stopSelf();
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        ensureChannel();
        AminDnsController.State state = controller != null ? controller.state() : AminDnsController.State.STARTING;
        String engine = controller != null ? controller.engineLabel() : "DNS";
        String text;
        switch (state) {
            case CONNECTED:
                text = engine + " · Connected — Telegram is routed through the DNS tunnel";
                break;
            case ERROR:
                text = engine + " · Failed — tap to open";
                break;
            case OFF:
                text = engine + " · Off";
                break;
            case STARTING:
            default:
                text = engine + " · Connecting…";
                break;
        }
        Intent openIntent = new Intent(this, LaunchActivity.class);
        openIntent.setAction(Intent.ACTION_MAIN);
        openIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | pendingIntentImmutableFlag());
        Intent stopIntent = new Intent(this, AminDnsTunnelService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | pendingIntentImmutableFlag());

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle("DNS Tunnel")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPending);
        return builder.build();
    }

    private static int pendingIntentImmutableFlag() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "DNS Tunnel", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while the AminGram DNS tunnel is running");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
