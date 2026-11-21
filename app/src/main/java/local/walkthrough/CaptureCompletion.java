package local.walkthrough;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/** Delivers a finalized capture to the recorder, including after a cold launch. */
final class CaptureCompletion {
    private static final String PREFERENCES = "capture_completion";
    private static final String PENDING = "pending_run";
    private static final String CHANNEL = "finished_capture";
    private static final int NOTIFICATION = 4703;

    // Called on the main thread after the encoder or ZIP writer closes its output.
    static void present(Context owner, File dir) {
        File artifact = RunFiles.isVideo(dir) ? RunFiles.video(dir) : RunFiles.archive(dir);
        if (!artifact.isFile() || artifact.length() == 0) return;
        Context app = owner.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String name = dir.getName();
        prefs.edit().putString(PENDING, name).apply();
        Intent show = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!WalkService.isActive() && !ManualCaptureService.isActive() && !CaptureRecorder.isBusy()) {
            try { owner.startActivity(show); }
            catch (RuntimeException unavailable) { /* The receipt stays pending for the next launch. */ }
        }
        // Android can suppress a background activity launch without throwing an exception.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!name.equals(prefs.getString(PENDING, null))) return;
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (!manager.areNotificationsEnabled()) return;
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Saved captures",
                    NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(app, NOTIFICATION, show,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            manager.notify(NOTIFICATION, new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_capture_mark)
                    .setContentTitle("Capture saved").setContentText("Tap to view your capture.")
                    .setAutoCancel(true).setContentIntent(open).build());
        }, 1800);
    }

    static File take(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String name = prefs.getString(PENDING, null);
        if (name == null) return null;
        prefs.edit().remove(PENDING).apply();
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        File dir = new File(new File(context.getFilesDir(), "runs"), name);
        return dir.isDirectory() ? dir : null;
    }

    private CaptureCompletion() {}
}
