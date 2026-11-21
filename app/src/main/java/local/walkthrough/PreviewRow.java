package local.walkthrough;

import android.graphics.Rect;

/** Resolves a preview against each new accessibility snapshot; never retains a native node. */
final class PreviewRow {
    interface Finder { WalkTree.Item find(WalkTree tree); }
    final Finder finder;
    final TapHighlight.Settings settings;
    final String label, action;
    final int window;
    final long expires;
    Rect displayed;
    long waitUntil, missingSince;
    int adjustments;
    boolean waitingForImage, imageSaved;

    PreviewRow(Finder finder, TapHighlight.Settings settings, String label, String action, int window, long expires) {
        this.finder=finder; this.settings=settings; this.label=label; this.action=action;
        this.window=window; this.expires=expires;
    }

    /** Reviewed buttons may live beside, rather than contain, the visible row title. */
    WalkTree.Item displayItem(WalkTree tree, WalkTree.Item tap) {
        if (label.isEmpty()) return tap;
        for (WalkTree.Item parent=tap; parent!=null && !parent.node.isScrollable(); parent=parent.parent) {
            if (parent!=tap && parent.bounds.height()>tree.all.get(0).bounds.height()*.82f) break;
            for (WalkTree.Item child:tree.all)
                if (label.equals(child.label) && child.inside(parent)) return parent;
        }
        return tap;
    }

    static final class Position {
        final Rect visible, viewport;
        final WalkTree.Item pane;
        final int direction;
        Position(WalkTree tree,WalkTree.Item row, WalkScroll tracking, int margin,Rect safeDisplay) {
            Rect clipped=new Rect(tree.all.get(0).bounds);
            if(!clipped.intersect(safeDisplay))clipped.setEmpty();
            WalkTree.Item vertical=null, horizontal=null;
            for(WalkTree.Item p=row.parent;p!=null;p=p.parent) {
                if(!p.node.isScrollable())continue;
                if(!clipped.intersect(p.bounds))clipped.setEmpty();
                if(vertical==null && (p.scrollAction(WalkTree.UP)!=0 || p.scrollAction(WalkTree.DOWN)!=0))vertical=p;
                if(horizontal==null && (p.scrollAction(WalkTree.LEFT)!=0 || p.scrollAction(WalkTree.RIGHT)!=0))horizontal=p;
            }
            viewport=clipped;
            visible=new Rect(row.bounds);
            if(!visible.intersect(viewport))visible.setEmpty();
            int needed=-1;
            // Android can expose the top of a row entirely behind the navigation bar.
            // Move it into the safe viewport before starting its outline dwell.
            if (!viewport.isEmpty() && visible.isEmpty()) {
                if (vertical!=null && row.bounds.top>=viewport.bottom && canMove(vertical,WalkTree.DOWN,tracking)) needed=WalkTree.DOWN;
                else if (vertical!=null && row.bounds.bottom<=viewport.top && canMove(vertical,WalkTree.UP,tracking)) needed=WalkTree.UP;
                else if (horizontal!=null && row.bounds.left>=viewport.right && canMove(horizontal,WalkTree.RIGHT,tracking)) needed=WalkTree.RIGHT;
                else if (horizontal!=null && row.bounds.right<=viewport.left && canMove(horizontal,WalkTree.LEFT,tracking)) needed=WalkTree.LEFT;
            }
            if (vertical!=null && !visible.isEmpty() && visible.height()<viewport.height()-2*margin) {
                if(row.bounds.top<=viewport.top+margin && canMove(vertical,WalkTree.UP,tracking)) needed=WalkTree.UP;
                else if(row.bounds.bottom>=viewport.bottom-margin && canMove(vertical,WalkTree.DOWN,tracking)) needed=WalkTree.DOWN;
            }
            if (needed==-1 && horizontal!=null && !visible.isEmpty() && visible.width()<viewport.width()-2*margin) {
                if(row.bounds.left<=viewport.left+margin && canMove(horizontal,WalkTree.LEFT,tracking)) needed=WalkTree.LEFT;
                else if(row.bounds.right>=viewport.right-margin && canMove(horizontal,WalkTree.RIGHT,tracking)) needed=WalkTree.RIGHT;
            }
            direction=needed;
            pane=needed==WalkTree.LEFT || needed==WalkTree.RIGHT ? horizontal : vertical;
        }
        private static boolean canMove(WalkTree.Item pane,int direction,WalkScroll tracker) {
            return pane.scrollAction(direction)!=0 && !tracker.atEdge(pane,direction);
        }
    }

    void resetHold() {
        displayed=null; waitingForImage=false; imageSaved=false;
    }
}
