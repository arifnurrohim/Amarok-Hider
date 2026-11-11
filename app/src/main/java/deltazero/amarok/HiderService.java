package deltazero.amarok;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;
import androidx.lifecycle.Observer;

import deltazero.amarok.ui.MainActivity;

public class HiderService extends LifecycleService {

    private static final String TAG = "HiderService";
    private static final String CHANNEL_ID = "FILE_OPERATION_CHANNEL";
    private static final int NOTIFICATION_ID = 2;
    private static final long WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final String ACTION_START = "deltazero.amarok.action.START_HIDER_SERVICE";
    private static final String ACTION_CANCEL = "deltazero.amarok.action.CANCEL_HIDER_SERVICE";

    private Observer<Hider.Progress> progressObserver;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.operation_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        PowerManager powerManager = getSystemService(PowerManager.class);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Amarok:HiderOperation");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            Hider.cancel();
            return START_NOT_STICKY;
        }

        startForegroundCompat(buildNotification(Hider.progress.getValue()));

        if (!wakeLock.isHeld())
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);

        if (progressObserver != null)
            Hider.progress.removeObserver(progressObserver);
        progressObserver = this::updateNotification;
        Hider.progress.observe(this, progressObserver);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (progressObserver != null) {
            Hider.progress.removeObserver(progressObserver);
            progressObserver = null;
        }
        if (wakeLock != null && wakeLock.isHeld())
            wakeLock.release();
        super.onDestroy();
    }

    private void updateNotification(@Nullable Hider.Progress progress) {
        if (progress == null)
            return;
        try {
            getSystemService(NotificationManager.class)
                    .notify(NOTIFICATION_ID, buildNotification(progress));
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to update HiderService notification.", e);
        }
    }

    private Notification buildNotification(@Nullable Hider.Progress progress) {
        boolean hiding = progress == null || progress.hiding;
        String title = getString(hiding
                ? R.string.operation_hiding_title
                : R.string.operation_unhiding_title);
        String text;
        int max = 0;
        int current = 0;
        boolean indeterminate = true;

        if (progress == null) {
            text = getString(R.string.operation_phase_starting);
        } else if (progress.stalled) {
            text = getString(R.string.operation_stalled_notification,
                    progress.processed, progress.total);
            max = progress.total;
            current = progress.processed;
            indeterminate = progress.total <= 0;
        } else {
            String phase = Hider.getPhaseLabel(this, progress.phase);
            if (progress.total > 0) {
                text = getString(R.string.operation_progress,
                        progress.processed, progress.total, phase);
                max = progress.total;
                current = progress.processed;
                indeterminate = false;
            } else {
                text = phase;
            }
        }

        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent cancelIntent = new Intent(this, HiderService.class)
                .setAction(ACTION_CANCEL);
        PendingIntent cancelPendingIntent = PendingIntent.getService(
                this, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_paw)
                .setContentIntent(contentIntent)
                .addAction(0, getString(R.string.cancel), cancelPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(max, current, indeterminate)
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, HiderService.class).setAction(ACTION_START);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            Log.w(TAG, "Unable to start HiderService foreground notification.", e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, HiderService.class));
        } catch (Exception e) {
            Log.w(TAG, "Unable to stop HiderService.", e);
        }
    }
}
