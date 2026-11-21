package local.walkthrough;

import android.graphics.Bitmap;

/** Area-average downsampling preserves fine strokes when a viewport shifts by a fraction of a sample. */
final class PixelThumbnail {
    private PixelThumbnail() {}

    static byte[] luminance(Bitmap source, int width, int height) {
        int sourceWidth = source.getWidth(), sourceHeight = source.getHeight();
        byte[] gray = new byte[width * height];
        int[] rows = new int[sourceWidth * ((sourceHeight + height - 1) / height + 1)];
        for (int y = 0; y < height; y++) {
            int top = y * sourceHeight / height, bottom = (y + 1) * sourceHeight / height;
            int lines = bottom - top;
            source.getPixels(rows, 0, sourceWidth, 0, top, sourceWidth, lines);
            for (int x = 0; x < width; x++) {
                int left = x * sourceWidth / width, right = (x + 1) * sourceWidth / width;
                long light = 0;
                for (int row = 0; row < lines; row++) {
                    for (int column = left; column < right; column++) {
                        int rgb = rows[row * sourceWidth + column];
                        light += 77 * ((rgb >>> 16) & 255) + 150 * ((rgb >>> 8) & 255) + 29 * (rgb & 255);
                    }
                }
                gray[y * width + x] = (byte) (light / (256L * lines * (right - left)));
            }
        }
        return gray;
    }
}
