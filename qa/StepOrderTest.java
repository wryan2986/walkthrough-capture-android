package local.walkthrough;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the same ordinal index used for the user's live Muse walkthrough. */
public final class StepOrderTest {
    private static final int TOTAL = 40;
    private static final int VIEW_HEIGHT = 560;
    private static final int BOTTOM = 70 + 80 * TOTAL - VIEW_HEIGHT;
    private static final Set<Integer> REPEATED = Set.of(1, 7, 9, 10, 16, 22, 23, 32, 34);
    private static int viewports, checkedPositions;

    private static WalkTree viewport(int offset, List<String> expected, boolean changed) {
        WalkTree tree = new WalkTree();
        WalkTree.Item list = new WalkTree.Item();
        list.bounds.bottom = VIEW_HEIGHT;
        list.atTop = offset == 0;
        list.atBottom = offset == BOTTOM;
        tree.items.add(list);
        tree.all.add(list);
        for (int ordinal = 0; ordinal < TOTAL; ordinal++) {
            int top = 70 + 80 * ordinal - offset;
            if (top < 0 || top + 55 > VIEW_HEIGHT) continue;
            WalkTree.Item row = new WalkTree.Item();
            row.parent = list;
            row.isStep = true;
            row.label = REPEATED.contains(ordinal) ? "Identical step" : "Step " + ordinal;
            if (changed) row.label += " revised";
            row.bounds.top = top;
            row.bounds.bottom = top + 55;
            tree.items.add(row);
            tree.all.add(row);
            WalkTree.Item title = new WalkTree.Item();
            title.kind = "TextView";
            title.label = row.label;
            title.parent = row;
            title.bounds.top = top;
            title.bounds.bottom = top + 24;
            tree.all.add(title);
            expected.add(MuseSteps.key(ordinal));
        }
        return tree;
    }

    private static void run(boolean withScrollOffsets) {
        MuseSteps index = new MuseSteps();
        WalkScroll scroll = new WalkScroll();
        List<Integer> positions = new ArrayList<>();
        for (int offset = 0; offset < BOTTOM; offset += 240) positions.add(offset);
        positions.add(BOTTOM);
        for (int pass = 0; pass < 3; pass++) {
            if (pass > 0) index.beginSweep();
            for (int offset : positions) {
                // The third pass simulates Muse rebuilding its overview after every detail.
                if (pass == 2) index.beginSweep();
                List<String> expected = new ArrayList<>();
                WalkTree tree = viewport(offset, expected, false);
                scroll.offset = withScrollOffsets ? offset : null;
                Map<String,WalkTree.Item> rows = index.rows(tree, scroll, pass == 0, pass > 0, false);
                if (!new ArrayList<>(rows.keySet()).equals(expected)) {
                    throw new AssertionError("Lost or duplicated row at " + offset + ": " + rows.keySet());
                }
                viewports++;
                checkedPositions += expected.size();
            }
            if (index.size() != TOTAL) throw new AssertionError("Incomplete list: " + index.size());
        }
        index.beginSweep();
        scroll.offset = withScrollOffsets ? 0 : null;
        boolean rejected = false;
        try {
            index.rows(viewport(0, new ArrayList<>(), true), scroll, false, true, false);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        if (!rejected) throw new AssertionError("A changed overview passed as the reviewed list");
    }

    public static void main(String[] args) {
        run(true);
        run(false);
        System.out.println("Passed " + viewports + " ordered viewport checks, " + checkedPositions
                + " position checks, and changed-list rejection with and without scroll offsets.");
    }
}
