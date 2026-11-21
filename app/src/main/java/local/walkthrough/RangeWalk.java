package local.walkthrough;

import android.view.accessibility.AccessibilityNodeInfo;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** On-device index of readable row identities. No accessibility handles survive a screen. */
public final class RangeWalk {
    public static final class Entry {
        public final String key, label, summary;
        public final long timeMs;
        Entry(long time, String title, String index) { this(time,title,index,""); }
        Entry(long time, String title, String index, String summary) {
            this.summary = summary;
            timeMs = time;
            label = title.isEmpty() ? "Trajectory" : title;
            key = time + "|" + index + "|" + title.length() + "|" + title + "|" + summary;
        }
    }

    static final class Visible {
        final Entry entry;
        final WalkTree.Item anchor;
        Visible(Entry entry, WalkTree.Item anchor) { this.entry = entry; this.anchor = anchor; }
    }

    static final class Config {
        final String pkg, avatar, groups, steps, stamp, title;
        final boolean card;
        final long from, to;
        final ZoneId zone = ZoneId.systemDefault();
        final Locale locale = Locale.getDefault();
        final LocalDate today = LocalDate.now(zone);
        final Set<String> anchorRules;
        Config(String pkg, String avatar, String groups, String steps, String stamp, String title,
               String level, long from, long to) {
            this.pkg = pkg.trim(); this.avatar = avatar.trim(); this.groups = groups.trim(); this.steps = steps.trim();
            this.stamp = stamp.trim(); this.title = title.trim(); this.card = level.equals("card");
            this.from = from; this.to = to;
            anchorRules = card ? WalkTree.selectors(groups) : Collections.singleton(this.avatar);
            if (this.pkg.isEmpty() || this.groups.isEmpty() || this.steps.isEmpty() || (!card && this.avatar.isEmpty())
                    || (!card && !level.equals("avatar")) || from >= to)
                throw new IllegalArgumentException("Set the target, required row controls and a valid time range.");
        }
    }

    final Config config;
    final boolean discovering;
    private final MuseDays museDays;
    // AccessibilityNodeInfo equality includes the provider's node identity and window.
    // Keep only the immediately preceding Activity viewport, and recycle on every exit.
    private Map<AccessibilityNodeInfo,LocalDate> previousSections=new HashMap<>();
    final Map<String,Integer> positions = new LinkedHashMap<>();
    final List<Entry> catalog = new ArrayList<>(), selected = new ArrayList<>();
    // Only consecutive scan windows may share a row whose bare clock requires a previous header.
    // Playback can additionally recover an unambiguous row date from discovery after Android Back.
    final Map<String,LocalDate> knownHeaders = new HashMap<>();
    final Set<String> repeatedAcrossDays = new HashSet<>();
    private Map<String,LocalDate> previousHints = new HashMap<>();
    private LocalDate lastDay;
    private Set<String> priorPage = Collections.emptySet();
    private int furthest = -1;
    int done;
    boolean verifiedTop, directList, openedShared;

    RangeWalk(Config config) { this.config = config; discovering = true; museDays=new MuseDays(); }
    RangeWalk(RangeWalk scan, Set<String> wanted) {
        this.config = scan.config; discovering = false; museDays=new MuseDays(scan.museDays);
        positions.putAll(scan.positions); catalog.addAll(scan.catalog);
        knownHeaders.putAll(scan.knownHeaders);
        repeatedAcrossDays.addAll(scan.repeatedAcrossDays);
        if (!config.zone.equals(ZoneId.systemDefault()) || !config.today.equals(LocalDate.now(config.zone)))
            throw new IllegalStateException("The phone date or time zone changed. Scan the range again.");
        for (Entry e : catalog) if (inRange(e) && wanted.contains(e.key)) selected.add(e);
        if (selected.isEmpty() || selected.size() != wanted.size())
            throw new IllegalStateException("Select at least one reviewed trajectory from this scan.");
    }

    private boolean inRange(Entry e) { return e.timeMs >= config.from && e.timeMs < config.to; }
    List<Entry> review() {
        List<Entry> result = new ArrayList<>();
        for (Entry e : catalog) if (inRange(e)) result.add(e);
        return Collections.unmodifiableList(result);
    }

    private boolean anchor(WalkTree.Item n) {
        for (String rule : config.anchorRules) if (n.matches(rule)) return true;
        return false;
    }

    List<Visible> visible(WalkTree tree) {
        List<WalkTree.Item> anchors = new ArrayList<>(), onscreen = new ArrayList<>();
        Set<WalkTree.Item> visible = new HashSet<>(tree.items);
        for (WalkTree.Item n : tree.all) if (anchor(n)) {
            anchors.add(n);
            if (visible.contains(n)) onscreen.add(n);
        }
        onscreen.sort(Comparator.comparingInt((WalkTree.Item n) -> n.bounds.top).thenComparingInt(n -> n.bounds.left));
        Map<WalkTree.Item,LocalDate> headers = new HashMap<>();
        LocalDate day = null;
        // Native Muse cards get dates from their own section. Only the generic
        // profile needs to inspect unrelated text for headings.
        if (onscreen.isEmpty() || onscreen.stream().anyMatch(row -> !nativeCard(row))) {
            List<WalkTree.Item> ordered = new ArrayList<>(tree.items);
            ordered.sort(Comparator.comparingInt((WalkTree.Item n) -> n.bounds.top).thenComparingInt(n -> n.bounds.left));
            for (WalkTree.Item n : ordered) {
                LocalDate header = asDate(n.label);
                if (header != null) day = header;
                if (onscreen.contains(n) && day != null) headers.put(n, day);
            }
        }

        Map<WalkTree.Item,Entry> datedCards=readMuseCards(tree,onscreen);
        List<Visible> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Map<String,LocalDate> nextHints = new HashMap<>();
        for (WalkTree.Item n : onscreen) {
            Entry e = nativeCard(n) ? datedCards.get(n) : readRow(tree,n,anchors,headers.get(n),nextHints);
            if (e == null) continue;
            if (!keys.add(e.key)) throw new IllegalStateException("Two visible trajectories have the same timestamp and identity. Set a distinct title control.");
            result.add(new Visible(e, n));
        }
        if (day != null) lastDay = day;
        if (!result.isEmpty()) previousHints = nextHints;
        return result;
    }

    private LocalDate asDate(String label) {
        return label.isEmpty() || label.length() > 100 ? null : TimeLabels.date(label, config.today, config.locale);
    }

    private boolean nativeCard(WalkTree.Item row) {
        return MuseProfile.supports(config.pkg) && MuseProfile.CARD.equals(row.id)
                && config.stamp.isEmpty() && config.title.isEmpty();
    }

    @SuppressWarnings("deprecation")
    void releaseWindow() {
        for(AccessibilityNodeInfo section:previousSections.keySet()) section.recycle();
        previousSections.clear();
    }

    @SuppressWarnings("deprecation")
    private Map<WalkTree.Item,Entry> readMuseCards(WalkTree tree,List<WalkTree.Item> anchors) {
        Map<WalkTree.Item,Entry> result=new HashMap<>();
        if(!MuseProfile.supports(config.pkg) || !config.stamp.isEmpty() || !config.title.isEmpty()
                || MuseProfile.id(tree,MuseProfile.ACTIVITY)==null) {
            releaseWindow();
            return result;
        }
        Map<WalkTree.Item,List<WalkTree.Item>> sections=new LinkedHashMap<>();
        for(WalkTree.Item n:tree.items) if(MuseProfile.DAY.equals(n.id))sections.put(n,new ArrayList<>());
        for(WalkTree.Item n:anchors) if(nativeCard(n))
            sections.computeIfAbsent(MuseDays.group(n),unused->new ArrayList<>()).add(n);
        Map<AccessibilityNodeInfo,LocalDate> nextSections=new HashMap<>();
        museDays.beginPage();
        try {
            for(Map.Entry<WalkTree.Item,List<WalkTree.Item>> section:sections.entrySet()) {
                WalkTree.Item group=section.getKey();
                List<String> identities=new ArrayList<>(), partial=new ArrayList<>();
                Map<WalkTree.Item,List<String>> complete=new LinkedHashMap<>();
                for(WalkTree.Item row:section.getValue()) {
                    List<String> fields=MuseProfile.cardFields(tree,row);
                    if(fields!=null) {
                        complete.put(row,fields);
                        identities.add(MuseDays.identity(fields));
                        MuseDays.partialKeys(partial,fields,true);
                    } else {
                        List<String> labels=MuseProfile.directText(tree,row);
                        if(labels.size()==2) {
                            boolean clock=TimeLabels.parse(labels.get(1),config.today,config.zone,config.locale,config.today)!=null;
                            MuseDays.partialKeys(partial,labels,clock);
                        }
                    }
                }
                LocalDate day=museDays.resolve(MuseDays.heading(tree,group),identities,partial,
                        previousSections.get(group.node),!complete.isEmpty(),config.today,config.locale);
                if(day==null)continue;
                nextSections.put(AccessibilityNodeInfo.obtain(group.node),day);
                for(Map.Entry<WalkTree.Item,List<String>> card:complete.entrySet()) {
                    List<String> labels=card.getValue();
                    Long time=TimeLabels.parse(labels.get(2),day,config.zone,config.locale,config.today);
                    if(time==null)throw new IllegalStateException("The Muse activity clock could not be read.");
                    result.put(card.getKey(),new Entry(time,labels.get(0),collectionIndex(card.getKey()),labels.get(1)));
                }
            }
            museDays.finishPage();
        } finally {
            releaseWindow();
            previousSections=nextSections;
        }
        return result;
    }

    private Entry readRow(WalkTree tree, WalkTree.Item anchor, List<WalkTree.Item> allAnchors,
                          LocalDate visibleHeader, Map<String,LocalDate> nextHints) {
        for (WalkTree.Item scope = anchor; scope != null; scope = scope.parent) {
            if (scope != anchor && (scope.node.isScrollable() || scope.node.getCollectionInfo() != null)) break;
            // Accessibility can flatten the content wrapper, making real row containers
            // direct children of a ScrollView. Reject only traversal past the anchor's
            // immediate row into a sole page wrapper that spans unrelated controls.
            if (scope != anchor && scope != anchor.parent && scope.parent != null
                    && scope.parent.kind.endsWith("ScrollView") && scope.parent.node.getChildCount() == 1
                    && !scope.node.isClickable() && scope.node.getCollectionItemInfo() == null) break;
            boolean shared = false;
            for (WalkTree.Item other : allAnchors) if (other != anchor && other.inside(scope)) { shared = true; break; }
            if (shared) break;
            List<WalkTree.Item> fields = new ArrayList<>();
            for (WalkTree.Item field : tree.all) if (field.inside(scope) && !field.node.isEditable() && !field.node.isPassword())
                fields.add(field);
            Set<String> titles = new LinkedHashSet<>(), rawTimes = new LinkedHashSet<>();
            LocalDate localDay = visibleHeader;
            Set<LocalDate> rowDates = new LinkedHashSet<>();
            for (WalkTree.Item f : fields) {
                if (!config.title.isEmpty() && f.matches(config.title)) addLabel(titles, f.label);
                LocalDate fieldDay = asDate(f.label);
                if (fieldDay != null) rowDates.add(fieldDay);
                if (!config.stamp.isEmpty() && f.matches(config.stamp)) {
                    addTimeLabels(rawTimes, f.label); addTimeLabels(rawTimes, f.description);
                }
            }
            if (rowDates.size() > 1) throw new IllegalStateException("Multiple date headers match one trajectory row.");
            if (!rowDates.isEmpty()) localDay = rowDates.iterator().next();
            if (titles.size() > 1) throw new IllegalStateException("Multiple titles match one trajectory row.");
            if (!config.title.isEmpty() && titles.isEmpty()) continue;
            if (!config.stamp.isEmpty() && rawTimes.isEmpty()) continue;
            String title = titles.isEmpty() ? fallbackTitle(fields, anchor) : titles.iterator().next();
            String index = collectionIndex(anchor);
            if (config.stamp.isEmpty()) for (WalkTree.Item f : fields) {
                addTimeLabels(rawTimes, f.label); addTimeLabels(rawTimes, f.description);
            }
            Set<Long> times = new LinkedHashSet<>();
            for (String raw : rawTimes) {
                String hintKey = index + "|" + title + "|" + raw;
                Long instant = TimeLabels.parse(raw, null, config.zone, config.locale, config.today);
                if (instant == null) {
                    LocalDate ref = localDay;
                    if (ref == null) ref = previousHints.get(hintKey);
                    if (ref == null && !discovering && !repeatedAcrossDays.contains(hintKey)) ref = knownHeaders.get(hintKey);
                    if (ref == null) ref = lastDay;
                    if (ref == null) continue;
                    instant = TimeLabels.parse(raw, ref, config.zone, config.locale, config.today);
                    if (instant == null) continue;
                    nextHints.put(hintKey, ref);
                    if (discovering) {
                        LocalDate known = knownHeaders.put(hintKey, ref);
                        if (known != null && !known.equals(ref)) repeatedAcrossDays.add(hintKey);
                    }
                }
                times.add(instant);
            }
            if (times.size() > 1) throw new IllegalStateException("Multiple timestamps match one trajectory row. Specify the timestamp control.");
            if (!times.isEmpty()) return new Entry(times.iterator().next(), title, index);
            if (scope.node.getCollectionItemInfo() != null) break;
        }
        throw new IllegalStateException("Could not associate a readable timestamp and title with one trajectory. Inspect its row and check the date, time and selectors.");
    }

    private void addLabel(Set<String> out, String text) {
        if (!text.isEmpty() && text.length() <= 256) out.add(text);
    }

    // Some accessibility providers merge a title and timestamp into a single multiline
    // description. Accept complete delimited fields, never a time substring in prose.
    private void addTimeLabels(Set<String> out, String text) {
        if (text.isEmpty() || text.length() > 512) return;
        addLabel(out, text);
        for (String field : text.split("[\\r\\n•·]+")) addLabel(out, field.trim());
    }

    private String fallbackTitle(List<WalkTree.Item> fields, WalkTree.Item anchor) {
        for (WalkTree.Item f : fields) {
            if (f == anchor || f.label.isEmpty() || f.label.length() > 160 || f.matches(config.stamp)) continue;
            if (asDate(f.label) == null && TimeLabels.parse(f.label, config.today, config.zone, config.locale, config.today) == null)
                return f.label;
        }
        return anchor.label.length() > 160 ? "" : anchor.label;
    }

    private String collectionIndex(WalkTree.Item n) {
        for (; n != null && !n.node.isScrollable(); n = n.parent) {
            AccessibilityNodeInfo.CollectionItemInfo ci = n.node.getCollectionItemInfo();
            if (ci != null && ci.getRowIndex() >= 0) return ci.getRowIndex() + "/" + ci.getColumnIndex();
        }
        return "";
    }

    /** Validate order when a page overlaps the preceding viewport. Detect a repeated row after a gap. */
    void collect(List<Visible> rows) {
        Set<String> here = new HashSet<>();
        int previous = -1;
        for (Visible v : rows) {
            String key = v.entry.key;
            Integer pos = positions.get(key);
            if (pos == null) {
                if (catalog.size() >= 10_000) throw new IllegalStateException("Range exceeds the 10,000-row discovery limit.");
                pos = catalog.size(); positions.put(key, pos); catalog.add(v.entry);
            } else if (!priorPage.contains(key)) {
                throw new IllegalStateException("A trajectory identity repeated after leaving the viewport. Choose a distinct title and rescan.");
            }
            if (pos <= previous) throw new IllegalStateException("Trajectory order changed during discovery.");
            previous = pos;
            here.add(key);
        }
        if(!here.isEmpty()) priorPage = here;
    }

    /** Navigation may restore the top on Back. Always seek the next selected key in original display order. */
    Visible nextSelected(List<Visible> rows) {
        int desired = positions.get(selected.get(done).key), previous = -1;
        Visible match = null;
        for (Visible v : rows) {
            Integer pos = positions.get(v.entry.key);
            if (pos == null || pos <= previous || (previous >= 0 && pos != previous + 1))
                throw new IllegalStateException("The trajectory list changed since review. Scan the range again.");
            if (pos > furthest + 1) throw new IllegalStateException("A reviewed trajectory disappeared before playback reached it.");
            previous = pos;
            furthest = Math.max(furthest, pos);
            if (pos == desired) match = v;
        }
        if (previous > desired && match == null)
            throw new IllegalStateException("A selected trajectory could not be located at its reviewed position.");
        return match;
    }

    void verifyReturn(List<Visible> rows) {
        for (Visible v : rows) if (positions.containsKey(v.entry.key)) return;
        throw new IllegalStateException("Could not verify return to the reviewed trajectory list.");
    }
}
