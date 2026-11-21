package local.walkthrough;

import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashMap;
import java.util.Map;

/** Movement proof for the specific pending scroll. Text and bounds can stay fixed when
 * a code pane scrolls underneath its viewport. Never use a scroll event to confirm a tap. */
final class WalkScroll {
    private static final class Offset {
        int value = -1, max = -1;
    }
    private final Map<String,Offset> cursors = new HashMap<>();
    private AccessibilityNodeInfo source;
    private int direction;
    private long armed;
    private Offset offset;
    boolean moved;
    int revision;

    static boolean horizontal(int d) { return d == WalkTree.LEFT || d == WalkTree.RIGHT; }
    private static boolean positive(int d) { return d == WalkTree.RIGHT || d == WalkTree.DOWN; }
    private String key(WalkTree.Item n, int dir) {
        return (horizontal(dir) ? "x|" : "y|") + n.node.getWindowId() + "|" + n.node.hashCode() + "|" + n.path + "|" + n.id;
    }

    Integer position(WalkTree.Item n,int dir) {
        Offset c=cursors.get(key(n,dir));
        return c==null || c.value<0 ? null : c.value;
    }

    Integer remaining(WalkTree.Item n,int dir) {
        Offset c=cursors.get(key(n,dir));
        return c==null || c.value<0 || c.max<=0 ? null : Math.max(0,c.max-c.value);
    }

    boolean atEdge(WalkTree.Item n,int dir) {
        Offset c = cursors.get(key(n,dir));
        return c != null && c.value >= 0 && c.max > 0 && (positive(dir) ? c.value >= c.max : c.value == 0);
    }

    @SuppressWarnings("deprecation")
    void arm(WalkTree.Item n, int dir) {
        release();
        if (!horizontal(dir)) cursors.keySet().removeIf(k -> k.startsWith("x|"));
        if (cursors.size() >= 128) cursors.clear();
        offset = cursors.computeIfAbsent(key(n,dir),ignored -> new Offset());
        if (offset.value < 0 && positive(dir) && n.scrollAction(dir==WalkTree.RIGHT ? WalkTree.LEFT : WalkTree.UP)==0)
            offset.value = 0;
        direction = dir;
        source = AccessibilityNodeInfo.obtain(n.node);
        armed = SystemClock.uptimeMillis();
    }

    @SuppressWarnings("deprecation")
    boolean observe(AccessibilityEvent e) {
        if (source == null || e.getEventType()!=AccessibilityEvent.TYPE_VIEW_SCROLLED || e.getEventTime()<armed) return false;
        AccessibilityNodeInfo reported = e.getSource();
        if (reported == null) return false;
        try { if (!source.equals(reported)) return false; }
        finally { reported.recycle(); }
        boolean x = horizontal(direction);
        int value = x ? e.getScrollX() : e.getScrollY();
        int max = x ? e.getMaxScrollX() : e.getMaxScrollY();
        int delta = x ? e.getScrollDeltaX() : e.getScrollDeltaY();
        int sign = positive(direction) ? 1 : -1;
        // Some SDKs expose -1 for an unset delta. Accept a one-pixel movement via its
        // independently reported change in position, not the sentinel alone.
        boolean changed = (offset.value >= 0 && value >= 0 && (long)(value - offset.value)*sign > 0)
                || (delta != -1 && (long)delta*sign > 0)
                || (offset.value < 0 && max > 0 && value >= 0 && (positive(direction) ? value > 0 : value < max));
        if (value >= 0) offset.value = value;
        if (max > 0) offset.max = max;
        if (changed) { moved = true; revision++; }
        return changed;
    }

    @SuppressWarnings("deprecation")
    void release() {
        if (source != null) source.recycle();
        source = null;
        offset = null;
        moved = false;
    }

    void reset() { release(); cursors.clear(); }
}
