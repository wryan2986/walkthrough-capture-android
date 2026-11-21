package local.walkthrough;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Records only video. The projection grant and all state live in the app process. */
public final class CaptureRecorder extends Service {
    private static final String STOP = "local.walkthrough.STOP_RECORDING";
    private static final String CHANNEL = "capture_recording";
    private static final int NOTIFICATION_ID = 4701;
    private static final Object LOCK = new Object();

    private static long generation;
    private static boolean starting;
    private static CaptureRecorder active;
    private static String error = "";

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private Surface surface;
    private MaskedVideoSurface protectedSurface;
    private PrivacyMasks privacy;
    private File tempFile;
    private File outputFile;
    private boolean recording;
    private int commandId;
    private long session;

    public static void start(Context context, int resultCode, Intent data, File dir) {
        if (ManualCaptureService.isActive()) return;
        synchronized (LOCK) {
            if (starting || active != null) return;
            error = "";
            if (resultCode != Activity.RESULT_OK || data == null) {
                error = "Screen recording permission was not granted.";
                return;
            }
            Intent intent = new Intent(context, CaptureRecorder.class)
                    .putExtra("generation", ++generation)
                    .putExtra("code", resultCode)
                    .putExtra("grant", data)
                    .putExtra("dir", dir.getAbsolutePath());
            starting = true;
            try {
                context.startForegroundService(intent);
            } catch (RuntimeException e) {
                starting = false;
                error = "Could not start the recording service. " + detail(e);
            }
        }
    }

    /** Finalizes the file before returning, including when called on the UI thread. */
    public static void finish(Context context) {
        synchronized (LOCK) {
            ++generation;
            starting = false;
            if (active != null) active.closeSession();
        }
    }

    public static boolean isReady() {
        synchronized (LOCK) { return active != null && active.recording; }
    }

    /** The walker calls this only after checking the foreground package on its main looper. */
    static boolean beginOnTarget() {
        synchronized (LOCK) {
            if (active == null) return false;
            if (active.recording) return true;
            CaptureRecorder service = active;
            try {
                service.startVideo();
                return true;
            } catch (RuntimeException | IOException e) {
                error = "Could not record the target display. " + detail(e);
                service.closeSession();
                return false;
            }
        }
    }

    public static boolean isStarting() {
        synchronized (LOCK) { return starting; }
    }

    static boolean isBusy() {
        synchronized (LOCK) { return starting || active != null; }
    }

    public static String lastError() {
        synchronized (LOCK) { return error; }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            synchronized (LOCK) {
                // stopSelf(startId) needs the newest command, even when its token is stale.
                commandId = startId;
                if (active != this || intent.getLongExtra("generation", -1) != session) {
                    if (active == null && !starting) stopSelf(startId);
                    return START_NOT_STICKY;
                }
            }
            if (!WalkService.stopFromNotification()) {
                synchronized (LOCK) { error = "Screen recording was stopped."; }
                finish(this);
            }
            return START_NOT_STICKY;
        }

        synchronized (LOCK) {
            commandId = startId;
            if (active == this) return START_NOT_STICKY;
            if (intent == null) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            session = intent.getLongExtra("generation", -1);
            try {
                // Even a start canceled before delivery must honor startForegroundService().
                showRecordingNotification();
                if (!starting || session != generation) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf(startId);
                    return START_NOT_STICKY;
                }
                active = this;
                prepareGrant(intent);
            } catch (RuntimeException | IOException e) {
                error = "Could not start screen recording. " + detail(e);
                closeSession();
            } finally {
                if (session == generation) starting = false;
            }
        }
        return START_NOT_STICKY;
    }

    private void showRecordingNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen recording",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, CaptureRecorder.class).setAction(STOP)
                        .setData(Uri.parse("walkthrough:recording/" + session)).putExtra("generation", session),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_capture_mark)
                .setContentTitle("Walkthrough Capture is recording")
                .setContentText("Screen capture is enabled for this walkthrough")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        startForeground(NOTIFICATION_ID, builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    @SuppressWarnings("deprecation")
    private void prepareGrant(Intent intent) throws IOException {
        privacy=PrivacyMasks.read(this);
        File dir = new File(intent.getStringExtra("dir")).getCanonicalFile();
        if (!dir.toPath().startsWith(getDataDir().getCanonicalFile().toPath())
                || (!dir.isDirectory() && !dir.mkdirs())) {
            throw new IOException("The capture folder is unavailable.");
        }
        outputFile = RunFiles.video(dir);
        if (outputFile.exists()) throw new IOException("This run already has a video.");
        Intent grant = intent.getParcelableExtra("grant");
        projection = getSystemService(MediaProjectionManager.class)
                .getMediaProjection(intent.getIntExtra("code", Activity.RESULT_CANCELED), grant);
        if (projection == null) throw new IOException("The screen recording grant expired.");
        MediaProjection current = projection;
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() {
                synchronized (LOCK) {
                    if (current != projection || active != CaptureRecorder.this) return;
                    error = "Android ended screen sharing.";
                    closeSession();
                }
            }
        };
        projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
    }

    @SuppressWarnings("deprecation")
    private void startVideo() throws IOException {
        // Keep incomplete media outside the run directory, including after process death.
        tempFile = File.createTempFile("walkthrough-", ".mp4.part", getCacheDir());
        Display physical = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Context window = createDisplayContext(physical)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null);
        Rect bounds = window.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        // Preserve aspect ratio and use a capture size suitable for realtime encoding.
        double scale = Math.min(1.0, Math.min(1920.0 / Math.max(bounds.width(), bounds.height()),
                1080.0 / Math.min(bounds.width(), bounds.height())));
        int width = Math.max(2, (int)(bounds.width() * scale / 2) * 2);
        int height = Math.max(2, (int)(bounds.height() * scale / 2) * 2);
        int density = window.getResources().getConfiguration().densityDpi;
        recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(width, height);
        recorder.setVideoFrameRate(24);
        recorder.setVideoEncodingBitRate(5_000_000);
        recorder.setOutputFile(tempFile);
        recorder.setOnErrorListener((source, what, extra) -> {
            synchronized (LOCK) {
                if (source != recorder || active != this) return;
                error = "The video encoder stopped (" + what + ", " + extra + ").";
                closeSession();
            }
        });
        recorder.prepare();
        surface = recorder.getSurface();
        Surface projectionSurface=surface;
        if(privacy.hasRegions()) {
            long token=session;
            Handler main=new Handler(Looper.getMainLooper());
            protectedSurface=new MaskedVideoSurface(surface,width,height,
                    PrivacyMasks.fixed(privacy.settings.regions,new Rect(0,0,width,height)),
                    ()->main.post(()->{
                        synchronized(LOCK) {
                            if(active!=this || session!=token)return;
                            error="Android interrupted the protected video output.";
                            closeSession();
                        }
                    }));
            projectionSurface=protectedSurface.input();
        }
        recorder.start();
        recording = true;
        display = projection.createVirtualDisplay("Walkthrough recording", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, projectionSurface, null, null);
        if (display == null) throw new IOException("Android could not create a recording display.");
    }

    // Caller holds LOCK. Disconnect callbacks first so our own stop is not reported as revocation.
    private void closeSession() {
        active = null;
        if (projection != null && projectionCallback != null) {
            release(() -> projection.unregisterCallback(projectionCallback));
        }
        if (display != null) release(display::release);
        display=null;
        if (protectedSurface!=null) release(protectedSurface::close);
        protectedSurface=null;
        if (recorder != null) {
            RuntimeException stopError = null;
            try {
                if (recording) recorder.stop();
            } catch (RuntimeException e) {
                stopError = e;
            } finally {
                release(recorder::release);
                recorder = null;
            }
            if (recording) {
                try {
                    requireVideoSample(tempFile);
                    Files.move(tempFile.toPath(), outputFile.toPath());
                    if (stopError != null) addError("Saved the playable part of the recording. " + detail(stopError));
                } catch (RuntimeException | IOException e) {
                    addError("Video could not be saved. " + detail(e));
                }
            }
        }
        recording = false;
        if (surface != null) release(surface::release);
        if (projection != null) release(projection::stop);
        if (tempFile != null && tempFile.exists()) tempFile.delete();
        surface = null;
        projection = null;
        projectionCallback = null;
        tempFile = null;
        outputFile = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(commandId);
    }

    // A revoked projection or failed media server can invalidate individual handles.
    private static void release(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException e) {
            if (error.isEmpty()) error = "Android interrupted recording cleanup. " + detail(e);
        }
    }

    /** A quick stop or failed encoder can leave a nonempty MP4 without a usable video track. */
    private static void requireVideoSample(File file) throws IOException {
        if (file == null || file.length() == 0) throw new IOException("No video frames were recorded.");
        MediaExtractor media = new MediaExtractor();
        try {
            media.setDataSource(file.getAbsolutePath());
            int videoTrack=-1;
            for (int i = 0; i < media.getTrackCount(); i++) {
                String mime = media.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime!=null && mime.startsWith("audio/"))throw new IOException("The recording contains an unexpected audio track.");
                if (mime!=null && mime.startsWith("video/"))videoTrack=i;
            }
            if(videoTrack>=0) {
                media.selectTrack(videoTrack);
                if(media.getSampleTime()>=0 && media.getSampleSize()>0)return;
            }
            throw new IOException("No video frames were recorded.");
        } finally {
            media.release();
        }
    }

    private static void addError(String description) {
        error = error.isEmpty() ? description : error + " " + description;
    }

    @Override public void onDestroy() {
        synchronized (LOCK) {
            if (active == this) {
                error = "Android stopped the recording service.";
                closeSession();
            }
        }
        super.onDestroy();
    }

    private static String detail(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
