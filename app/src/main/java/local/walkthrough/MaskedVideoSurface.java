package local.walkthrough;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** The encoder only receives bitmaps containing the user's opaque privacy rectangles. */
final class MaskedVideoSurface {
    private final HandlerThread thread = new HandlerThread("Protected video frames");
    private final ImageReader reader;
    private final Surface encoder;
    private final List<Rect> masks = new ArrayList<>();
    private final Rect output;
    private final Runnable failed;
    private final Bitmap frame;
    private final ByteBuffer packed;
    private final byte[] blackLine;
    private boolean closed;
    private boolean interrupted;

    MaskedVideoSurface(Surface encoder, int width, int height, List<Rect> masks, Runnable failed) {
        this.encoder = encoder;
        this.failed = failed;
        output = new Rect(0, 0, width, height);
        for (Rect mask : masks) {
            Rect clipped = new Rect(mask);
            if (clipped.intersect(output)) this.masks.add(clipped);
        }
        if (this.masks.isEmpty()) throw new IllegalArgumentException("No privacy region intersects the display.");
        packed = ByteBuffer.allocateDirect(width * height * 4);
        blackLine = new byte[width * 4];
        for (int alpha = 3; alpha < blackLine.length; alpha += 4) blackLine[alpha] = (byte) 255;
        frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        thread.start();
        reader.setOnImageAvailableListener(this::render, new Handler(thread.getLooper()));
    }

    Surface input() {
        return reader.getSurface();
    }

    private synchronized void render(ImageReader source) {
        if (closed || interrupted) return;
        try (Image image = source.acquireLatestImage()) {
            if (image == null) return;
            if (image.getWidth() != output.width() || image.getHeight() != output.height()
                    || !image.getCropRect().equals(output)) {
                throw new IllegalStateException("The projected frame size changed.");
            }
            copyRows(image.getPlanes()[0]);
            // Keep unmasked input out of the bitmap even while the graphics driver is
            // consuming a previous frame. Only the packed CPU buffer sees raw pixels.
            for (Rect mask : masks) {
                int bytes = mask.width() * 4;
                for (int y = mask.top; y < mask.bottom; y++) {
                    packed.position((y * output.width() + mask.left) * 4);
                    packed.put(blackLine, 0, bytes);
                }
            }
            packed.rewind();
            frame.copyPixelsFromBuffer(packed);
            Canvas canvas = encoder.lockHardwareCanvas();
            try {
                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(frame, null, output, null);
            } finally {
                encoder.unlockCanvasAndPost(canvas);
            }
        } catch (RuntimeException error) {
            interrupted = true;
            reader.setOnImageAvailableListener(null, null);
            failed.run();
        }
    }

    private void copyRows(Image.Plane plane) {
        int stride = plane.getRowStride();
        int rowBytes = output.width() * 4;
        if (plane.getPixelStride() != 4 || stride < rowBytes) {
            throw new IllegalStateException("The projected pixel layout is unsupported.");
        }
        ByteBuffer pixels = plane.getBuffer().duplicate();
        int first = pixels.position();
        int end = pixels.limit();
        packed.clear();
        if (stride == rowBytes) {
            pixels.limit(first + packed.capacity());
            packed.put(pixels);
        } else {
            for (int y = 0; y < output.height(); y++) {
                int start = first + y * stride;
                pixels.limit(end).position(start).limit(start + rowBytes);
                packed.put(pixels);
            }
        }
        packed.flip();
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        reader.setOnImageAvailableListener(null, null);
        reader.close();
        thread.quitSafely();
    }
}
