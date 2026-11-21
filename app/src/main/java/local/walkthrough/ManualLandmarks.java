package local.walkthrough;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/** Small reference patches taken solely from the screenshot the user just saved. */
final class ManualLandmarks {
    static final float NEAR = ManualGuideMatch.NEAR;
    static final float FAR = ManualGuideMatch.FAR;
    final int width, height;
    final Bitmap vertical, horizontal;
    private final ManualGuideMatch comparison;

    private ManualLandmarks(Bitmap saved) {
        width = saved.getWidth();
        height = saved.getHeight();
        comparison = comparison(saved);
        vertical = patch(saved, comparison.verticalAnchor, FAR);
        horizontal = patch(saved, FAR, comparison.horizontalAnchor);
    }

    static ManualLandmarks from(Bitmap maskedScreen) {
        return new ManualLandmarks(maskedScreen);
    }

    String alignmentFrom(ManualLandmarks previous, boolean sideways) {
        if (previous == null || width != previous.width || height != previous.height) return "";
        if (!previous.comparison.foundNearGuide(comparison, sideways)) return "";
        return sideways ? "Reference matches left guide" : "Reference matches upper guide";
    }

    private static ManualGuideMatch comparison(Bitmap saved) {
        float scale = Math.min(1f, 320f / Math.max(saved.getWidth(), saved.getHeight()));
        int w = Math.max(1, Math.round(saved.getWidth() * scale));
        int h = Math.max(1, Math.round(saved.getHeight() * scale));
        return new ManualGuideMatch(w, h, PixelThumbnail.luminance(saved, w, h));
    }

    private static Bitmap patch(Bitmap saved, float x, float y) {
        int width = saved.getWidth(), height = saved.getHeight();
        int cropWidth = Math.max(1, Math.min(Math.round(width * 0.36f), height));
        int cropHeight = Math.max(1, cropWidth / 4);
        int left = Math.max(0, Math.min(width - cropWidth, Math.round(width * x - cropWidth / 2f)));
        int top = Math.max(0, Math.min(height - cropHeight, Math.round(height * y - cropHeight / 2f)));
        Rect source = new Rect(left, top, left + cropWidth, top + cropHeight);
        int thumbWidth = Math.min(cropWidth, 384);
        int thumbHeight = Math.max(1, Math.round(cropHeight * (thumbWidth / (float) cropWidth)));
        Bitmap result = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888);
        new Canvas(result).drawBitmap(saved, source, new Rect(0, 0, thumbWidth, thumbHeight),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
    }
}
