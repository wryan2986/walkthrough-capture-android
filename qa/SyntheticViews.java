package local.walkthrough;

import java.util.ArrayList;
import java.util.List;

/** Small input model for running the production row-alignment algorithm without an emulator. */
final class WalkTree {
    static final int UP = 0, DOWN = 3;
    final List<Item> items = new ArrayList<>();
    final List<Item> all = new ArrayList<>();

    static class Bounds {
        int top, bottom;
        int height() { return bottom - top; }
    }

    static class Item {
        String kind = "View", label = "", id = "";
        final FakeNode node=new FakeNode();
        static final class FakeNode {
            boolean clickable;
            boolean isClickable() { return clickable; }
        }
        final Bounds bounds = new Bounds();
        Item parent;
        boolean isStep, atTop, atBottom;

        int scrollAction(int direction) {
            boolean edge = direction == UP ? atTop : atBottom;
            return edge ? 0 : 1;
        }
    }
}

final class WalkScroll {
    Integer offset;

    Integer position(WalkTree.Item item, int direction) { return offset; }
    boolean atEdge(WalkTree.Item item, int direction) { return false; }
}

final class MuseProfile {
    static final String DAY="hatch-activity-day-card", CARD="hatch-activity-action-row";
    static boolean isStep(WalkTree tree, WalkTree.Item item) { return item.isStep; }
    static String stepKey(WalkTree tree, WalkTree.Item item) { return item.label; }
}
