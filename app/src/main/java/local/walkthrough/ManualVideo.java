package local.walkthrough;

import android.content.Context;
import android.graphics.Rect;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** One user-started local encoder. No audio source or intermediate unmasked file. */
final class ManualVideo {
    final int width, height;
    private MediaRecorder recorder;
    private Surface encoder;
    private MaskedVideoSurface masked;
    private final File staging, destination;
    private boolean started;

    @SuppressWarnings("deprecation")
    ManualVideo(Context context, File directory, int sourceWidth, int sourceHeight,
                PrivacyMasks privacy, Runnable failed) throws IOException {
        double scale = Math.min(1, Math.min(1920.0 / Math.max(sourceWidth, sourceHeight),
                1080.0 / Math.min(sourceWidth, sourceHeight)));
        width = Math.max(2, (int) (sourceWidth * scale / 2) * 2);
        height = Math.max(2, (int) (sourceHeight * scale / 2) * 2);
        destination = RunFiles.video(directory);
        staging = new File(directory, "manual-video.part");
        if (staging.exists() || destination.exists()) throw new IOException("The video already exists.");
        try {
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(width, height);
            recorder.setVideoFrameRate(24);
            recorder.setVideoEncodingBitRate(5_000_000);
            recorder.setOutputFile(staging);
            recorder.setOnErrorListener((ignored, what, extra) -> failed.run());
            recorder.prepare();
            encoder = recorder.getSurface();
            if (privacy.hasRegions()) {
                masked = new MaskedVideoSurface(encoder, width, height,
                        PrivacyMasks.fixed(privacy.settings.regions, new Rect(0, 0, width, height)), failed);
            }
        } catch (RuntimeException | IOException e) {
            release();
            staging.delete();
            throw e;
        }
    }

    Surface input() { return masked == null ? encoder : masked.input(); }

    void begin() {
        recorder.start();
        started = true;
    }

    void finish() throws IOException {
        boolean encoderStopped = true;
        try {
            if (masked != null) { masked.close(); masked = null; }
            if (started) recorder.stop();
        } catch (RuntimeException e) {
            encoderStopped = false;
        } finally {
            release();
        }
        try {
            if (!started || !playable(staging)) throw new IOException("No playable video was recorded.");
            Files.move(staging.toPath(), destination.toPath());
            if (!encoderStopped) throw new IOException("Saved the playable part of the interrupted video.");
        } finally { staging.delete(); }
    }

    private void release() {
        if (masked != null) try { masked.close(); } catch (RuntimeException ignored) {}
        if (recorder != null) try { recorder.release(); } catch (RuntimeException ignored) {}
        if (encoder != null) try { encoder.release(); } catch (RuntimeException ignored) {}
        masked = null; recorder = null; encoder = null;
    }

    private static boolean playable(File file) {
        if (!file.isFile() || file.length() == 0) return false;
        MediaExtractor e = new MediaExtractor();
        try {
            e.setDataSource(file.getAbsolutePath());
            if (e.getTrackCount() != 1 || !"video/avc".equals(
                    e.getTrackFormat(0).getString(MediaFormat.KEY_MIME))) return false;
            e.selectTrack(0);
            return e.getSampleTime() >= 0 && e.getSampleSize() > 0;
        } catch (RuntimeException | IOException ignored) { return false; }
        finally { e.release(); }
    }
}
