package local.walkthrough;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** User-started inspection. Retain short control labels and selectors only in this process. */
final class Inspection {
    private static final int MAX_CHOICES = 220, MAX_NODES = 2000, MAX_CLICKS = 160;
    private static String target, failure = "";
    private static long until, lastScan, lastTap;
    private static boolean teaching;
    private static final String[] taught = new String[3];
    private static int taps;

    private static final class Candidate {
        final String spec, display;
        final int priority;
        Candidate(String spec, String display, int priority) {
            this.spec = spec;
            this.display = display;
            this.priority = priority;
        }
    }
    private static final LinkedHashMap<String,Candidate> candidates = new LinkedHashMap<>();
    private static List<Candidate> menu = Collections.emptyList();

    /** A detached click event can carry a class and label even when its source is gone. */
    private static final class Click {
        final String kind, title, description, avatar, row;
        Click(String kind, String title, String description, String avatar, String row) {
            this.kind = kind; this.title = title; this.description = description;
            this.avatar = avatar; this.row = row;
        }
    }
    private static final class Screen {
        final List<Click> clicks;
        final String signature;
        long lastSeen;
        Screen(List<Click> clicks, long time) {
            this.clicks = clicks; this.lastSeen = time;
            StringBuilder builder = new StringBuilder();
            for (Click c : clicks) builder.append(c.kind).append('|').append(c.title).append('|')
                    .append(c.description).append('|').append(c.avatar).append('|').append(c.row).append('\n');
            signature = builder.toString();
        }
    }
    private static final ArrayDeque<Screen> screens = new ArrayDeque<>();

    static void start(String pkg) {
        candidates.clear(); screens.clear(); menu = Collections.emptyList();
        target = pkg; until = now() + 180_000; lastScan = -1000;
        teaching = false; taps = 0; failure = ""; lastTap = -1000;
        Arrays.fill(taught, "");
    }

    static void teach(String pkg) { start(pkg); teaching = true; }
    static void stop() { target = null; screens.clear(); }
    static boolean hasLesson() { return teaching && taps == 3; }
    static String learned(int index) { return taught[index]; }
    static String lessonStatus() {
        if (!teaching) return "";
        if (!failure.isEmpty()) return failure;
        return taps == 3 ? "Learned three controls. Check the rules below."
                : "Learned " + taps + " of 3 taps. Select the missing controls from inspection.";
    }

    static boolean wants(AccessibilityEvent event) {
        return target != null && event != null && target.equals(string(event.getPackageName()))
                && now() < until && (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED
                    || now() - lastScan >= 160);
    }

    @SuppressWarnings("deprecation")
    static void observe(AccessibilityService service, AccessibilityEvent event) {
        boolean clicked = event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED;
        long t = now();
        if (teaching && taps < 3 && clicked && failure.isEmpty() && t - lastTap > 400) {
            lastTap = t;
            String spec = "";
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                try {
                    if (target.equals(string(source.getPackageName())) && !source.isPassword() && !source.isEditable()) {
                        spec = Selector.learn(source, taps == 0);
                    }
                } finally { source.recycle(); }
            }
            if (!valid(spec)) spec = detachedRule(event, taps == 0, t);
            if (!valid(spec)) {
                failure = "Could not identify tap " + (taps + 1) + ". Choose controls from the inspection results.";
            } else taught[taps++] = spec;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;
        if (!target.equals(string(root.getPackageName()))) { root.recycle(); return; }
        lastScan = t;
        read(root, t);
    }

    private static String detachedRule(AccessibilityEvent event, boolean avatar, long time) {
        String kind = string(event.getClassName());
        Set<String> eventLabels = new HashSet<>();
        String description = shortLabel(event.getContentDescription());
        if (!description.isEmpty()) eventLabels.add(description);
        for (CharSequence text : event.getText()) {
            String label = shortLabel(text);
            if (!label.isEmpty()) eventLabels.add(label);
        }
        if (kind.isEmpty() && eventLabels.isEmpty()) return "";
        String unique = "";
        for (Screen screen : screens) {
            // The latest screen can have stayed unchanged while the user was reading it.
            if (screen != screens.peekLast() && time - screen.lastSeen > 1800) continue;
            for (Click c : screen.clicks) {
                if (!kind.isEmpty() && !kind.equals(c.kind)) continue;
                if (!eventLabels.isEmpty() && !eventLabels.contains(c.title) && !eventLabels.contains(c.description)) continue;
                String spec = avatar ? c.avatar : c.row;
                if (!valid(spec) || (!unique.isEmpty() && !unique.equals(spec))) return "";
                unique = spec;
            }
        }
        return unique;
    }

    private static final class Pending {
        final AccessibilityNodeInfo node;
        final boolean insideClick;
        Pending(AccessibilityNodeInfo node, boolean insideClick) { this.node = node; this.insideClick = insideClick; }
    }

    @SuppressWarnings("deprecation")
    private static void read(AccessibilityNodeInfo root, long time) {
        ArrayDeque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(root, false));
        List<Click> clicks = new ArrayList<>();
        int count = 0;
        try {
            while (!queue.isEmpty() && count++ < MAX_NODES) {
                Pending entry = queue.removeFirst();
                AccessibilityNodeInfo n = entry.node;
                try {
                    if (n.isPassword() || n.isEditable()) continue;
                    boolean button = n.isClickable() || n.getActionList().contains(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                            || n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
                    if (n.isVisibleToUser() && n.isEnabled()) inspect(n, button, entry.insideClick, clicks);
                    for (int i = 0; i < n.getChildCount() && queue.size() + count < MAX_NODES; i++) {
                        AccessibilityNodeInfo child = n.getChild(i);
                        if (child != null) queue.addLast(new Pending(child, button || entry.insideClick));
                    }
                } finally { n.recycle(); }
            }
        } finally {
            while (!queue.isEmpty()) queue.removeFirst().node.recycle();
        }
        if (teaching) {
            Screen next = new Screen(clicks, time);
            Screen old = screens.peekLast();
            if (old != null && old.signature.equals(next.signature)) old.lastSeen = time;
            else {
                if (old != null) old.lastSeen = time;
                screens.addLast(next);
                while (screens.size() > 3) screens.removeFirst();
            }
        }
    }

    private static void inspect(AccessibilityNodeInfo n, boolean button, boolean insideClick, List<Click> clicks) {
        String id = string(n.getViewIdResourceName());
        String title = shortLabel(n.getText()), desc = shortLabel(n.getContentDescription());
        String label = desc.isEmpty() ? title : desc;
        if (button) {
            String avatar = Selector.learn(n, true);
            String row = Selector.learn(n, false);
            if (teaching && clicks.size() < MAX_CLICKS) {
                clicks.add(new Click(string(n.getClassName()), title, desc, valid(avatar) ? avatar : "", valid(row) ? row : ""));
            }
            if (MuseProfile.AVATAR.equals(avatar)) add(avatar, "Muse profile button", 0);
            if (MuseProfile.STEP.equals(row)) add(row, "Muse step summary", 0);
            else if (valid(row) && row.startsWith("@row/")) add(row, label, 0);
            if (valid(label)) add(label, "Exact label", 2);
        }
        if (!id.isEmpty() && (button || insideClick || !id.startsWith("android:"))) {
            add(id, label, button ? 0 : (insideClick ? 1 : 3));
        }
        if (insideClick && valid(label)) add(label, "Exact label inside control", 3);
    }

    private static void add(String spec, String example, int priority) {
        if (!valid(spec)) return;
        Candidate old = candidates.get(spec);
        if (old != null && old.priority <= priority) return;
        if (old == null && candidates.size() >= MAX_CHOICES) {
            Candidate worst = null;
            for (Candidate c : candidates.values()) if (worst == null || c.priority > worst.priority) worst = c;
            if (worst == null || worst.priority <= priority) return;
            candidates.remove(worst.spec);
        }
        String display = example.isEmpty() || spec.equals(example) ? spec : spec + "   ·   " + example;
        candidates.put(spec, new Candidate(spec, display, priority));
    }

    // Freeze the menu so a dialog index keeps pointing to the selector the user saw.
    static String[] choices() {
        menu = new ArrayList<>(candidates.values());
        menu.sort(Comparator.comparingInt(c -> c.priority));
        String[] labels = new String[menu.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = menu.get(i).display;
        return labels;
    }

    static String choice(int index) { return menu.get(index).spec; }

    static String result() {
        if (candidates.isEmpty()) return "No controls collected. Visit the conversation and trajectory while inspection is active.";
        return android.text.TextUtils.join("\n\n", choices());
    }

    private static String shortLabel(CharSequence input) {
        String s = string(input);
        return s.length() > 90 || s.contains("\n") || s.contains("\r") ? "" : s;
    }

    private static boolean valid(String spec) {
        if (spec == null || spec.isEmpty() || spec.contains(",") || spec.contains("\n") || spec.contains("\r")) return false;
        return spec.length() <= (spec.startsWith("@row/") || spec.contains(":id/") ? 1000 : 90);
    }

    private static String string(CharSequence s) { return s == null ? "" : s.toString().trim(); }
    private static long now() { return SystemClock.uptimeMillis(); }
    private Inspection() {}
}
