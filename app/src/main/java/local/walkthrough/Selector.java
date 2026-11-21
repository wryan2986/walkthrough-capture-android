package local.walkthrough;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Rules learned on the device. Row paths deliberately omit the changing list index. */
final class Selector {
    static boolean matches(AccessibilityNodeInfo n, String raw) {
        if (n == null || raw == null || raw.trim().isEmpty()) return false;
        String spec = raw.trim();
        if (spec.startsWith("@row/")) return spec.equals(rowPath(n));
        String id = n.getViewIdResourceName();
        if (id != null && (spec.equals(id) || (!spec.contains(":") &&
                (id.endsWith(":id/" + spec) || id.endsWith(":" + spec))))) return true;
        return spec.equals(text(n.getText())) || spec.equals(text(n.getContentDescription()));
    }

    static String learn(AccessibilityNodeInfo node, boolean avatar) {
        if (node == null) return "";
        if (MuseProfile.supports(text(node.getPackageName()))) {
            if (avatar && MuseProfile.avatarLabel(text(node.getContentDescription()))) return MuseProfile.AVATAR;
            if (!avatar && MuseProfile.rawStep(node)) return MuseProfile.STEP;
        }
        String id = node.getViewIdResourceName();
        if (id != null && !id.startsWith("android:")) return id;
        if (!avatar) return rowPath(node);
        String label = text(node.getContentDescription());
        return label.isEmpty() ? text(node.getText()) : label;
    }

    private static String rowPath(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo child = node;
        List<String> parts = new ArrayList<>();
        try {
            for (int depth = 0; depth < 14; depth++) {
                AccessibilityNodeInfo parent = child.getParent();
                if (parent == null) break;
                try {
                    String clazz = text(child.getClassName());
                    if (parent.getCollectionInfo() != null) {
                        String anchor = parent.getViewIdResourceName();
                        if (anchor == null) anchor = text(parent.getClassName());
                        parts.add(clazz);
                        Collections.reverse(parts);
                        return "@row/" + anchor + "/" + String.join("/", parts);
                    }
                    int index = childIndex(parent, child);
                    if (index < 0) break;
                    parts.add(clazz + "[" + index + "]");
                } finally {
                    if (child != node) child.recycle();
                    child = parent;
                }
            }
        } finally {
            if (child != node) child.recycle();
        }
        return "";
    }

    private static int childIndex(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo other = parent.getChild(i);
            if (other == null) continue;
            boolean same = child.equals(other);
            other.recycle();
            if (same) return i;
        }
        return -1;
    }
    private static String text(CharSequence value) { return value == null ? "" : value.toString().trim(); }
    private Selector() {}
}
