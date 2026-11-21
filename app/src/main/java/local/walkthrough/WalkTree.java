package local.walkthrough;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One short-lived view of the foreground window. Node handles never outlive the decision. */
final class WalkTree implements AutoCloseable {
    static final int CLICK = AccessibilityNodeInfo.ACTION_CLICK;
    static final int EXPAND = AccessibilityNodeInfo.ACTION_EXPAND;
    static final int COLLAPSE = AccessibilityNodeInfo.ACTION_COLLAPSE;
    static final int UP = 0, LEFT = 1, RIGHT = 2, DOWN = 3;
    private static final int[] SCROLL = {
            AccessibilityAction.ACTION_SCROLL_UP.getId(), AccessibilityAction.ACTION_SCROLL_LEFT.getId(),
            AccessibilityAction.ACTION_SCROLL_RIGHT.getId(), AccessibilityAction.ACTION_SCROLL_DOWN.getId()
    };
    private static final int[] PAGE = {
            AccessibilityAction.ACTION_PAGE_UP.getId(), AccessibilityAction.ACTION_PAGE_LEFT.getId(),
            AccessibilityAction.ACTION_PAGE_RIGHT.getId(), AccessibilityAction.ACTION_PAGE_DOWN.getId()
    };

    static final class Item {
        final AccessibilityNodeInfo node;
        final WalkTree tree;
        final Item parent;
        final int depth;
        final String id, label, kind, description, path;
        final Rect bounds = new Rect();
        final List<AccessibilityAction> actions;

        Item(WalkTree tree, AccessibilityNodeInfo n, Item p, String path) {
            this.tree = tree;
            this.node = n;
            this.parent = p;
            this.path = path;
            depth = p == null ? 0 : p.depth + 1;
            id = string(n.getViewIdResourceName());
            description = string(n.getContentDescription());
            String text = string(n.getText());
            label = text.isEmpty() ? description : text;
            kind = string(n.getClassName());
            n.getBoundsInScreen(bounds);
            actions = n.getActionList();
        }

        boolean has(int action) {
            for (AccessibilityAction a : actions) if (a.getId() == action) return true;
            return false;
        }

        boolean matches(String selector) {
            if (MuseProfile.AVATAR.equals(selector)) return MuseProfile.muse(this) && MuseProfile.avatarLabel(description)
                    && has(CLICK) && MuseProfile.id(tree,"hatch-chat-view") != null;
            if (MuseProfile.STEP.equals(selector)) return MuseProfile.isStep(tree,this);
            return !selector.isEmpty() && Selector.matches(node, selector);
        }

        Item clickable() {
            for (Item n = this; n != null; n = n.parent) {
                if (n.node.isEnabled() && n.has(CLICK)) return n;
                if (n.node.isScrollable()) break;
            }
            return null;
        }

        boolean inside(Item ancestor) {
            for (Item n = this; n != null; n = n.parent) if (n == ancestor) return true;
            return false;
        }

        void checkScrollAxis() {
            if (!node.isEnabled() || !node.isScrollable()
                    || (!has(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) && !has(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))) return;
            for (int dir = UP; dir <= DOWN; dir++) if (scrollAction(dir) != 0) return;
            throw new IllegalStateException("A visible scrollable control does not expose a recognizable scroll direction.");
        }

        int scrollAction(int direction) {
            if (!node.isEnabled()) return 0;
            if (has(SCROLL[direction])) return SCROLL[direction];
            if (has(PAGE[direction])) return PAGE[direction];
            // Generic forward/backward have no axis. Infer only for an identifiable control.
            if (!node.isScrollable()) return 0;
            boolean horizontal = kind.contains("Horizontal") || kind.contains("ViewPager");
            boolean vertical = kind.equals("android.widget.ScrollView") || kind.contains("ListView") || MuseProfile.vertical(tree,this);
            AccessibilityNodeInfo.CollectionInfo collection = node.getCollectionInfo();
            if (collection != null) {
                horizontal |= collection.getRowCount() == 1 && collection.getColumnCount() > 1;
                vertical |= collection.getColumnCount() == 1 && collection.getRowCount() > 1;
            }
            boolean x = direction == LEFT || direction == RIGHT;
            if ((x && !horizontal) || (!x && !vertical)) return 0;
            // A provider advertising the opposite direction explicitly uses the presence
            // of each directional action to signal its boundary (also in RTL layouts).
            if (has(SCROLL[x ? (direction == LEFT ? RIGHT : LEFT) : (direction == UP ? DOWN : UP)])
                    || has(PAGE[x ? (direction == LEFT ? RIGHT : LEFT) : (direction == UP ? DOWN : UP)])) return 0;
            int generic = (direction == LEFT || direction == UP)
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            return has(generic) ? generic : 0;
        }
    }

    final List<Item> items = new ArrayList<>();
    final List<Item> all = new ArrayList<>();
    final String signature;
    private final List<AccessibilityNodeInfo> handles = new ArrayList<>();
    private final StringBuilder state = new StringBuilder();

    private final boolean messageShellsOnly;
    private final String packageScope;
    WalkTree(AccessibilityNodeInfo ownedRoot) { this(ownedRoot,false); }
    WalkTree(AccessibilityNodeInfo ownedRoot,boolean messageShellsOnly) {
        this.messageShellsOnly=messageShellsOnly;
        String rootPackage=string(ownedRoot.getPackageName());
        packageScope=MuseProfile.supports(rootPackage) ? rootPackage : null;
        try {
            read(ownedRoot, null, "0");
            signature = state.toString();
        } catch (RuntimeException ex) {
            close();
            throw ex;
        }
    }

    private void read(AccessibilityNodeInfo node, Item parent, String path) {
        handles.add(node);
        if (handles.size() > 2200 || path.length() > 240) {
            throw new IllegalStateException("The accessibility tree exceeded the traversal limit.");
        }
        // Embedded foreign content never participates in a built-in capture.
        if (packageScope!=null && !packageScope.equals(string(node.getPackageName()))) return;
        Item n = new Item(this, node, parent, path);
        all.add(n);
        if (node.isVisibleToUser() && !n.bounds.isEmpty()) {
            items.add(n);
            state.append(path).append('|').append(n.id).append('|').append(n.kind)
                    .append('|').append(n.label).append('|').append(n.description).append('|').append(n.bounds)
                    .append('|').append(node.isChecked()).append('|').append(node.isSelected())
                    .append('|').append(node.getStateDescription()).append('|');
            for (AccessibilityAction a : n.actions) state.append(a.getId()).append(',');
            state.append('\n');
            if (state.length() > 1_000_000) throw new IllegalStateException("The readable screen is too large.");
        }
        // Unsaved rewind seeks need only the message row bounds/actions and their
        // scrolling parent. Avoid asking Muse to materialize every virtual table cell.
        if(messageShellsOnly && ConversationScene.isMessage(n))return;
        // Some providers expose visible children through a non-visible virtual group.
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) read(child, n, path + "." + i);
        }
    }

    static boolean supportsOverlap(int action) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return true;
        for (int direction : SCROLL) if (direction == action) return true;
        return false;
    }

    List<Item> matches(String selector) {
        List<Item> found = new ArrayList<>();
        for (Item n : items) if (n.matches(selector)) found.add(n);
        return found;
    }

    /** Prefer the innermost panel so its contents are covered before it leaves the screen. */
    Item scroll(int direction, List<Item> withinRows, Item subtree, WalkScroll tracker) {
        Item chosen = null;
        for (Item n : items) {
            if (subtree != null && !n.inside(subtree)) continue;
            if (!withinRows.isEmpty()) {
                boolean containsRow = false;
                for (Item row : withinRows) if (row.inside(n)) { containsRow = true; break; }
                if (!containsRow) continue;
            }
            n.checkScrollAxis();
            if (n.scrollAction(direction) != 0 && !tracker.atEdge(n,direction) && (chosen == null || n.depth > chosen.depth)) chosen = n;
        }
        return chosen;
    }

    /** Use a collection index or readable row title. Recycled resource IDs alone are insufficient. */
    String rowKey(Item item) {
        if (MuseProfile.isStep(this,item)) return MuseProfile.stepKey(this,item);
        String label = item.label;
        int row = -1, col = -1;
        for (Item n = item; n != null; n = n.parent) {
            AccessibilityNodeInfo.CollectionItemInfo info = n.node.getCollectionItemInfo();
            if (info != null) {
                row = info.getRowIndex();
                col = info.getColumnIndex();
                break;
            }
            if (n.node.isScrollable()) break;
        }
        if (label.isEmpty()) {
            for (Item child : items) {
                if (!child.label.isEmpty() && child.inside(item)) { label = child.label; break; }
            }
        }
        if (label.isEmpty() && row < 0 && col < 0) {
            throw new IllegalStateException("A step row has neither a readable identity nor a collection index.");
        }
        return row + "/" + col + "|" + item.id + "|" + label;
    }

    String rowKeyForControl(Item item) {
        for (Item n = item; n != null; n = n.parent) {
            AccessibilityNodeInfo.CollectionItemInfo info = n.node.getCollectionItemInfo();
            if (info != null) return n.id + "|" + info.getRowIndex() + "|" + info.getColumnIndex() + "|" + item.id;
        }
        // IDs for toggle buttons often stay constant while the displayed label changes
        // from "More" to "Less". Keep that state local to the row, not the button label.
        for (Item parent = item.parent; parent != null && !parent.node.isScrollable(); parent = parent.parent) {
            if (!parent.label.isEmpty()) return parent.path + "|" + parent.label + "|" + item.path;
            for (Item sibling : items) {
                if (sibling.parent == parent && sibling != item && !sibling.label.isEmpty()
                        && !sibling.node.isClickable() && !item.inside(sibling)) {
                    return item.path + "|" + sibling.id + "|" + sibling.label;
                }
            }
        }
        return item.path + "|" + item.id;
    }

    static Set<String> selectors(String csv) {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (String item : csv.split(",")) if (!item.trim().isEmpty()) result.add(item.trim());
        return result;
    }

    static String string(CharSequence s) { return s == null ? "" : s.toString().trim(); }

    @SuppressWarnings("deprecation")
    @Override public void close() {
        for (AccessibilityNodeInfo node : handles) node.recycle();
        handles.clear();
    }
}
