package local.walkthrough;

/** Tests one saved landmark against positions along the selected axis of a second saved image. */
final class ManualGuideMatch {
    static final float NEAR = 0.20f, FAR = 0.80f;
    private final int width, height;
    private final byte[] gray;
    final float verticalAnchor, horizontalAnchor;

    ManualGuideMatch(int width, int height, byte[] gray) {
        this.width = width;
        this.height = height;
        this.gray = gray;
        verticalAnchor = chooseAnchor(false);
        horizontalAnchor = chooseAnchor(true);
    }

    private int halfWidth() { return Math.max(4, Math.min(Math.round(width * 0.17f), height / 2 - 2)); }
    private int halfHeight() { return Math.max(3, Math.min(halfWidth() / 4, height / 12)); }

    // Text can occupy just one edge of a page. Pick a patch along the guide which contains detail.
    private float chooseAnchor(boolean horizontal) {
        if (width < 32 || height < 32) return 0.5f;
        float best = 0.5f;
        int highest = 0, dx = halfWidth(), dy = halfHeight();
        for (float candidate : new float[]{0.5f, 0.19f, 0.81f, 0.34f, 0.66f}) {
            int x0 = Math.round((horizontal ? FAR : candidate) * width);
            int y0 = Math.round((horizontal ? candidate : FAR) * height);
            int edges = 0;
            for (int y = Math.max(1, y0 - dy); y <= Math.min(height - 2, y0 + dy); y++)
                for (int x = Math.max(1, x0 - dx); x <= Math.min(width - 2, x0 + dx); x++) {
                    int i = y * width + x;
                    int contrast = Math.abs((gray[i-1] & 255) - (gray[i+1] & 255))
                            + Math.abs((gray[i-width] & 255) - (gray[i+width] & 255));
                    if (contrast >= 24) edges++;
                }
            if (edges > highest) { highest = edges; best = candidate; }
        }
        return best;
    }

    boolean foundNearGuide(ManualGuideMatch next, boolean horizontal) {
        if (width != next.width || height != next.height || width < 32 || height < 32) return false;
        int length = horizontal ? width : height;
        int original = Math.round(FAR * length);
        int centerX = horizontal ? original : Math.round(verticalAnchor * width);
        int centerY = horizontal ? Math.round(horizontalAnchor * height) : original;
        int halfWidth = halfWidth(), halfHeight = halfHeight();
        int[] offsets = new int[(halfWidth + 1) * (halfHeight + 1)];
        int step = horizontal ? 1 : width;
        int count = 0;
        for (int y = centerY - halfHeight; y <= centerY + halfHeight; y += 2) {
            for (int x = centerX - halfWidth; x <= centerX + halfWidth; x += 2) {
                int index = y * width + x;
                int gradient = Math.abs((gray[index - step] & 255) - (gray[index + step] & 255));
                if (gradient >= 22) offsets[count++] = index;
            }
        }
        if (count < 18) return false;
        int margin = (horizontal ? halfWidth : halfHeight) + 1;
        int target = Math.round(NEAR * length), tolerance = Math.max(2, Math.round(0.035f * length));
        float near = Float.POSITIVE_INFINITY, elsewhere = Float.POSITIVE_INFINITY;
        for (int pos = margin; pos < length - margin; pos++) {
            int shift = (pos - original) * step;
            int error = 0;
            for (int i = 0; i < count; i++) {
                int previous = offsets[i];
                error += Math.abs((gray[previous] & 255) - (next.gray[previous + shift] & 255));
            }
            float mean = error / (float) count;
            if (Math.abs(pos - target) <= tolerance) near = Math.min(near, mean);
            else if (Math.abs(pos - target) > tolerance + 2) elsewhere = Math.min(elsewhere, mean);
        }
        return near <= 24 && elsewhere >= Math.max(near + 10, near * 1.5f);
    }
}
