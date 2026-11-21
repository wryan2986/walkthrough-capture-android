package local.walkthrough;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Displays a scaled, local frame from the saved capture. Offscreen rows release their image. */
final class CaptureThumbnail extends FrameLayout {
    private static final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };

    private final File session;
    private final boolean video;
    private final String entry;
    private final int maxPixels;
    private final long videoMillis;
    private final View placeholder;
    private final ImageView image;
    private final Rect visibleBounds = new Rect();
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::update;
    private Future<?> pending;
    private boolean requested;
    private int generation;

    CaptureThumbnail(Context context, File session, boolean video, int maxPixels) {
        this(context, session, video, null, maxPixels, -1);
    }

    CaptureThumbnail(Context context, File session, String screenshotEntry, int maxPixels) {
        this(context, session, false, screenshotEntry, maxPixels, -1);
    }

    CaptureThumbnail(Context context, File session, long videoMillis, int maxPixels) {
        this(context, session, true, null, maxPixels, videoMillis);
    }

    private CaptureThumbnail(Context context, File session, boolean video, String entry, int maxPixels, long videoMillis) {
        super(context);
        this.session = session;
        this.video = video;
        this.entry = entry;
        this.videoMillis = videoMillis;
        this.maxPixels = Math.max(1, Math.min(maxPixels, 1280));
        setBackground(Ui.shape(context, Ui.PALE, Ui.LINE, 10));
        setClipToOutline(true);
        setContentDescription(video ? "Video preview" : "Screenshot preview");
        int inset = Ui.dp(context, 1);
        setPadding(inset, inset, inset, inset);
        placeholder = new Ui.Glyph(context, video ? "video" : "photos", Ui.TEAL);
        int side = Ui.dp(context, 25);
        addView(placeholder, new LayoutParams(side, side, Gravity.CENTER));
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        post(this::update);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        discard();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        post(this::update);
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (image == null) return;
        if (visible) post(this::update);
        else discard();
    }

    private void discard() {
        if (!requested) return;
        requested = false;
        generation++;
        if (pending != null) pending.cancel(false);
        pending = null;
        image.setImageDrawable(null);
        placeholder.setVisibility(VISIBLE);
    }

    private void update() {
        if (!isAttachedToWindow() || !isShown() || !getGlobalVisibleRect(visibleBounds)) {
            discard();
            return;
        }
        if (requested) return;
        requested = true;
        final int ticket = ++generation;
        pending = decoder.submit(() -> {
            Bitmap result = null;
            try {
                File source = video ? RunFiles.video(session) : RunFiles.archive(session);
                if (!source.isFile()) source = new File(session, "screenshots");
                String key = source.getAbsolutePath() + ":" + source.lastModified() + ":" + source.length()
                        + ":" + maxPixels + ":" + entry + ":" + videoMillis;
                result = cache.get(key);
                if (result == null) {
                    result = video ? videoFrame(source, maxPixels, videoMillis) : screenshot(session, entry, maxPixels);
                    if (result != null) cache.put(key, result);
                }
            } catch (IOException | RuntimeException ignored) { /* Partial or unavailable capture. */ }
            final Bitmap loaded = result;
            main.post(() -> {
                if (ticket != generation) return;
                pending = null;
                if (loaded != null) {
                    image.setImageBitmap(loaded);
                    placeholder.setVisibility(GONE);
                }
            });
        });
    }

    private static Bitmap videoFrame(File source, int edge, long position) throws IOException {
        try (MediaMetadataRetriever reader = new MediaMetadataRetriever()) {
            reader.setDataSource(source.getAbsolutePath());
            String value = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long milliseconds = value == null ? 0 : Long.parseLong(value);
            long timeUs = position < 0 ? Math.min(1_000, milliseconds / 2) * 1_000 : displayedSample(source,position*1000);
            return reader.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, edge, edge);
        }
    }

    // A screen recording holds its previous frame until the next UI change, sometimes for seconds.
    private static long displayedSample(File video, long timeUs) throws IOException {
        MediaExtractor samples = new MediaExtractor();
        try {
            samples.setDataSource(video.getAbsolutePath());
            for (int i=0; i<samples.getTrackCount(); i++) {
                String mime=samples.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime==null || !mime.startsWith("video/")) continue;
                samples.selectTrack(i);
                samples.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long preceding=0;
                for (long pts=samples.getSampleTime(); pts>=0 && pts<=timeUs; pts=samples.getSampleTime()) {
                    preceding=Math.max(preceding,pts);
                    if (!samples.advance()) break;
                }
                return preceding;
            }
            return 0;
        } finally { samples.release(); }
    }

    /** Members are returned in capture order. Older sessions can still have loose PNGs only. */
    static List<String> screenshotEntries(File session) {
        File archive = RunFiles.archive(session);
        if (archive.isFile()) {
            try (ZipFile zip = new ZipFile(archive)) {
                return namesInZip(zip);
            } catch (IOException ignored) { /* A partial session may still have its PNGs. */ }
        }
        List<String> names = new ArrayList<>();
        addLooseNames(names, session, "");
        addLooseNames(names, new File(session, "screenshots"), "screenshots/");
        Collections.sort(names);
        return names;
    }

    private static List<String> namesInZip(ZipFile zip) {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && screenshotName(entry.getName())) names.add(entry.getName());
        }
        Collections.sort(names);
        return names;
    }

    private static void addLooseNames(List<String> result, File dir, String prefix) {
        String[] names = dir.list((parent, name) -> screenshotName(prefix + name));
        if (names != null) for (String name : names) result.add(prefix + name);
    }

    private static boolean screenshotName(String name) {
        String leaf = name.startsWith("screenshots/") ? name.substring("screenshots/".length()) : name;
        return !leaf.startsWith(".") && !leaf.contains("/") && !leaf.contains("\\") && leaf.endsWith(".png");
    }

    private static Bitmap screenshot(File session, String requested, int edge) throws IOException {
        String name = requested;
        if (name == null) {
            List<String> names = screenshotEntries(session);
            if (names.isEmpty()) return null;
            name = names.get(0);
        }
        if (!screenshotName(name)) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        File archive = RunFiles.archive(session);
        if (archive.isFile()) {
            try (ZipFile zip = new ZipFile(archive)) {
                ZipEntry member = zip.getEntry(name);
                if (member != null) {
                    try (InputStream pixels = zip.getInputStream(member)) {
                        BitmapFactory.decodeStream(pixels, null, options);
                    }
                    sample(options, edge);
                    try (InputStream pixels = zip.getInputStream(member)) {
                        return BitmapFactory.decodeStream(pixels, null, options);
                    }
                }
            } catch (IOException ignored) { /* Try the original session image. */ }
        }
        String path = new File(session, name).getAbsolutePath();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        sample(options, edge);
        return BitmapFactory.decodeFile(path, options);
    }

    private static void sample(BitmapFactory.Options options, int edge) {
        options.inJustDecodeBounds = false;
        int longSide = Math.max(options.outWidth, options.outHeight);
        int scale = 1;
        while (longSide / scale > edge * 1.5) scale *= 2;
        options.inSampleSize = scale;
    }
}
