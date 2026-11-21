package android.view.accessibility;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;

/** Small tree-backed host adapter for the Android node methods used by WalkTree. */
public final class AccessibilityNodeInfo {
    public static final int ACTION_CLICK=16, ACTION_EXPAND=262144, ACTION_COLLAPSE=524288,
            ACTION_SCROLL_FORWARD=4096, ACTION_SCROLL_BACKWARD=8192;
    public static final class AccessibilityAction {
        public static final AccessibilityAction ACTION_SCROLL_UP=new AccessibilityAction(1),
                ACTION_SCROLL_LEFT=new AccessibilityAction(2), ACTION_SCROLL_RIGHT=new AccessibilityAction(3),
                ACTION_SCROLL_DOWN=new AccessibilityAction(4), ACTION_PAGE_UP=new AccessibilityAction(5),
                ACTION_PAGE_LEFT=new AccessibilityAction(6), ACTION_PAGE_RIGHT=new AccessibilityAction(7),
                ACTION_PAGE_DOWN=new AccessibilityAction(8);
        private final int id;
        public AccessibilityAction(int id) { this.id=id; }
        public int getId() { return id; }
    }
    public static final class CollectionItemInfo {
        public int getRowIndex() { return -1; }
        public int getColumnIndex() { return -1; }
    }
    public static final class CollectionInfo {
        public int getRowCount() { return -1; }
        public int getColumnCount() { return -1; }
    }
    public final String kind;
    public final Rect bounds;
    public final List<AccessibilityAction> actions=new ArrayList<>();
    public final List<AccessibilityNodeInfo> children=new ArrayList<>();
    public String text="", description="", pkg="com.facebook.aura";
    public boolean clickable, scrollable;
    public AccessibilityNodeInfo parent;
    public AccessibilityNodeInfo(String kind,Rect bounds) { this.kind=kind; this.bounds=bounds; }
    public AccessibilityNodeInfo add(String kind,Rect bounds) {
        AccessibilityNodeInfo child=new AccessibilityNodeInfo(kind,bounds);
        child.parent=this; child.pkg=pkg; children.add(child); return child;
    }
    public void getBoundsInScreen(Rect r) { r.set(bounds.left,bounds.top,bounds.right,bounds.bottom); }
    public boolean isVisibleToUser() { return !bounds.isEmpty(); }
    public boolean isClickable() { return clickable; }
    public boolean isScrollable() { return scrollable; }
    public boolean isEnabled() { return true; }
    public boolean isChecked() { return false; }
    public boolean isSelected() { return false; }
    public List<AccessibilityAction> getActionList() { return actions; }
    public String getStateDescription() { return ""; }
    public String getViewIdResourceName() { return ""; }
    public CharSequence getPackageName() { return pkg; }
    public CharSequence getText() { return text; }
    public CharSequence getContentDescription() { return description; }
    public CharSequence getClassName() { return kind; }
    public int getChildCount() { return children.size(); }
    public AccessibilityNodeInfo getChild(int index) { return children.get(index); }
    public AccessibilityNodeInfo getParent() { return parent; }
    public CollectionInfo getCollectionInfo() { return null; }
    public CollectionItemInfo getCollectionItemInfo() { return null; }
    public void recycle() {}
}
