package local.walkthrough;

import android.view.accessibility.AccessibilityNodeInfo;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Observed Muse accessibility semantics. The offline test app exposes the same controls. */
final class MuseProfile {
    static final String PKG = "com.facebook.aura", LAB = "local.muse.lab";
    static final String AVATAR = "@muse/profile", CARD = "hatch-activity-action-row", STEP = "@muse/step";
    static final String DAY = "hatch-activity-day-card", ACTIVITY = "hatch-activity-view";

    static boolean supports(String pkg) { return PKG.equals(pkg) || LAB.equals(pkg); }

    static boolean matchesSession(String selected,CharSequence actual) {
        return supports(selected) && actual!=null && selected.contentEquals(actual);
    }

    static boolean muse(WalkTree.Item n) { return supports(WalkTree.string(n.node.getPackageName())); }

    static WalkTree.Item id(WalkTree tree, String id) {
        for (WalkTree.Item n : tree.items) if (id.equals(n.id) && muse(n)) return n;
        return null;
    }

    static WalkTree.Item control(WalkTree tree, String label) {
        WalkTree.Item result = null;
        for (WalkTree.Item n : tree.items) {
            if (!label.equals(n.description) || !muse(n)) continue;
            WalkTree.Item c = n.clickable();
            if (c == null) continue;
            if (result != null && result != c) return null;
            result = c;
        }
        return result;
    }

    static boolean sheet(WalkTree tree) {
        return control(tree,"Dismiss") != null && id(tree,"hatch-chat-view") == null && id(tree,ACTIVITY) == null;
    }
    static boolean summary(WalkTree tree) { return sheet(tree) && control(tree,"Go back") == null; }

    /** Short native sheets stop at their content height. Their handle can remain
     * halfway down the display even though every element is readable. */
    static boolean contentFits(WalkTree tree) {
        if (!sheet(tree)) return false;
        boolean detail = control(tree,"Go back")!=null;
        for (WalkTree.Item body : tree.items) {
            if (!body.kind.endsWith(".ScrollView")) continue;
            if (body.scrollAction(WalkTree.UP)!=0 || body.scrollAction(WalkTree.DOWN)!=0) return false;
            int last = body.bounds.top;
            boolean hasContents = false;
            for (WalkTree.Item n : tree.items) {
                if (!n.inside(body) || n==body) continue;
                if (n.kind.endsWith("TextView") && !n.label.isEmpty()) {
                    last = Math.max(last,n.bounds.bottom);
                    if (detail) hasContents=true;
                }
                if (!detail && isStep(tree,n)) {
                    last = Math.max(last,n.bounds.bottom);
                    hasContents=true;
                }
            }
            if (hasContents && last < body.bounds.bottom-3) return true;
        }
        return false;
    }

    static boolean avatarLabel(String s) {
        return s.startsWith("Open ") && (s.endsWith("'s profile") || s.endsWith("’s profile"));
    }

    static boolean vertical(WalkTree tree, WalkTree.Item n) {
        if (!muse(n)) return false;
        if (n.parent != null && ACTIVITY.equals(n.parent.id)) return true;
        for (WalkTree.Item child : tree.items) if (child.parent == n && child.id.startsWith("hatch-message-")) return true;
        return false;
    }

    static List<String> directText(WalkTree tree, WalkTree.Item parent) {
        List<String> labels = new ArrayList<>();
        for (WalkTree.Item n : tree.all) {
            if (n.parent == parent && n.kind.endsWith("TextView") && !n.label.isEmpty()) labels.add(n.label);
        }
        return labels;
    }

    static WalkTree.Item scrollParent(WalkTree.Item n) {
        for (WalkTree.Item p = n.parent; p != null; p = p.parent)
            if (p.kind.endsWith("ScrollView") || p.node.isScrollable() || ACTIVITY.equals(p.id)) return p;
        return null;
    }

    static boolean clipped(WalkTree.Item row) {
        WalkTree.Item parent = scrollParent(row);
        return parent != null && (row.bounds.top <= parent.bounds.top + 2 || row.bounds.bottom >= parent.bounds.bottom - 2);
    }

    static boolean isStep(WalkTree tree, WalkTree.Item n) {
        if (!muse(n) || !n.node.isClickable() || n.parent == null || !n.parent.kind.endsWith("ScrollView")
                || !n.id.isEmpty() || !n.kind.endsWith(".View")) return false;
        List<String> texts = directText(tree,n);
        if (texts.isEmpty() || (texts.size() < 2 && clipped(n))) return false;
        // A status icon precedes the two text children. This excludes links, buttons and
        // any unrelated clickable surfaces in the prose of a detail sheet.
        boolean statusIcon = false;
        for (WalkTree.Item child : tree.all) if (child.parent == n && !child.description.isEmpty()
                && child.kind.endsWith(".View")) { statusIcon = true; break; }
        return statusIcon && summary(tree);
    }

    static String stepKey(WalkTree tree, WalkTree.Item n) {
        List<String> texts = directText(tree,n);
        String title = texts.get(0), summary = texts.size() > 1 ? texts.get(1) : "";
        return "Muse step|" + title.length() + "|" + title + "|" + summary;
    }

    /** Returns null for partially clipped action cards so a summary is never mistaken for the title. */
    static List<String> cardFields(WalkTree tree, WalkTree.Item n) {
        List<String> labels = directText(tree,n);
        if (labels.size() < 3 && clipped(n)) return null;
        if (labels.size() != 3) throw new IllegalStateException("Muse changed the layout of an activity card; its title, summary and clock could not be verified.");
        return labels;
    }

    static WalkTree.Item closedDay(WalkTree tree, java.util.Set<String> expanded) {
        if (id(tree, ACTIVITY) == null) return null;
        for (WalkTree.Item day : tree.items) {
            if (!DAY.equals(day.id)) continue;
            WalkTree.Item header = null;
            boolean hasRows = false;
            for (WalkTree.Item child : tree.all) {
                if (child.parent != day) continue;
                if (CARD.equals(child.id)) { hasRows = true; break; }
                if (child.node.isClickable()) header = child;
            }
            if (hasRows || header == null) continue;
            List<String> title = directText(tree,header);
            if (title.size()!=1 || expanded.contains(title.get(0))) continue;
            WalkTree.Item viewport = scrollParent(day);
            if (viewport != null && day.bounds.bottom >= viewport.bounds.bottom-2
                    && viewport.scrollAction(WalkTree.DOWN) != 0) continue;
            // A collapsed day is exactly its header; an expanded offscreen section may
            // expose its header before its rows and must be allowed to scroll into view.
            if (Math.abs(day.bounds.height() - header.bounds.height()) <= 6) {
                expanded.add(title.get(0));
                return header;
            }
        }
        return null;
    }

    static boolean rawStep(AccessibilityNodeInfo n) {
        if (n == null || !supports(WalkTree.string(n.getPackageName())) || !n.isClickable()) return false;
        AccessibilityNodeInfo p = n.getParent();
        try {
            if (p == null || !WalkTree.string(p.getClassName()).endsWith("ScrollView")) return false;
            int texts = 0;
            boolean icon = false;
            for (int i=0;i<n.getChildCount();i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c == null) continue;
                try {
                    String kind = WalkTree.string(c.getClassName());
                    if (kind.endsWith("TextView") && c.getText()!=null) texts++;
                    if (kind.endsWith(".View") && c.getContentDescription()!=null) icon=true;
                } finally { c.recycle(); }
            }
            return texts>=2 && icon;
        } finally { if (p != null) p.recycle(); }
    }

    private MuseProfile() {}
}
