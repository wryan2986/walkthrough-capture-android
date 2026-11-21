package local.walkthrough;

import java.util.Random;

public final class ManualGuideMatchTest {
    private static final int W = 140, H = 200;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        for (boolean horizontal : new boolean[]{false, true}) {
            int length = horizontal ? W : H;
            int distance = Math.round(ManualGuideMatch.FAR * length) - Math.round(ManualGuideMatch.NEAR * length);
            byte[] old = new byte[W * H];
            new Random(9).nextBytes(old);
            ManualGuideMatch first = new ManualGuideMatch(W, H, old);
            for (int delta : new int[]{distance - 2, distance, distance + 2, 0, 30, -3}) {
                byte[] next = new byte[W * H];
                new Random(41).nextBytes(next);
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                    int sx = horizontal ? x + delta : x, sy = horizontal ? y : y + delta;
                    if (sx >= 0 && sx < W && sy >= 0 && sy < H) next[y*W+x] = old[sy*W+sx];
                }
                boolean aligned = Math.abs(delta - distance) <= 2;
                check(first.foundNearGuide(new ManualGuideMatch(W, H, next), horizontal) == aligned,
                        "Incorrect match at " + delta + ", horizontal=" + horizontal);
            }
            byte[] repeat = new byte[W * H];
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
                repeat[y*W+x] = (byte) ((((horizontal ? x : y) / 2) % 2) * 255);
            ManualGuideMatch repeated = new ManualGuideMatch(W, H, repeat);
            check(!repeated.foundNearGuide(repeated, horizontal), "Repeated decoration confirmed overlap");
            ManualGuideMatch blank = new ManualGuideMatch(W, H, new byte[W * H]);
            check(!blank.foundNearGuide(blank, horizontal), "Blank screen confirmed overlap");
            check(!first.foundNearGuide(new ManualGuideMatch(H, W, old), horizontal), "Rotation confirmed overlap");
        }
        byte[] leftOnly = new byte[W * H];
        Random pattern = new Random(19);
        for (int y=0; y<H; y++) for (int x=0; x<W/4; x++) leftOnly[y*W+x]=(byte)pattern.nextInt(256);
        check(new ManualGuideMatch(W,H,leftOnly).verticalAnchor < 0.3f, "Empty center chosen for left-aligned content");
        // A one-native-pixel offset at 4x downsampling mixes adjacent samples at a sharp edge.
        byte[] shifted = new byte[W * H];
        int delta = Math.round(ManualGuideMatch.FAR * H) - Math.round(ManualGuideMatch.NEAR * H);
        for (int y=0; y<H-delta-1; y++) for (int x=0; x<W; x++) {
            int i=(y+delta)*W+x;
            shifted[y*W+x]=(byte)((3*(leftOnly[i]&255)+(leftOnly[i+W]&255))/4);
        }
        check(new ManualGuideMatch(W,H,leftOnly).foundNearGuide(new ManualGuideMatch(W,H,shifted),false),
                "A subpixel shift in left-aligned detail lost the guide match");
        System.out.println("Manual save-to-save alignment passed on both axes, including ambiguous and rotated views.");
    }
}
