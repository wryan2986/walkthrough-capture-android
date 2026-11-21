package local.walkthrough;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Indexes row occurrences in an ordered sheet. Labels alone are not identities in Muse. */
final class MuseSteps {
    private static final class Seen {
        final WalkTree.Item node;
        final String contents;
        final int y;
        Seen(WalkTree tree, WalkTree.Item row, WalkTree.Item viewport) {
            node=row;
            contents=MuseProfile.stepKey(tree,row);
            int titleY=row.bounds.top;
            for (WalkTree.Item child : tree.all) if (child.parent==row && child.kind.endsWith("TextView") && !child.label.isEmpty()) {
                titleY=child.bounds.top;
                break;
            }
            y=titleY - viewport.bounds.top;
        }
    }
    private static final class Known {
        final String contents;
        final int y;
        Known(Seen s,int offset) { contents=s.contents; y=s.y+offset; }
    }
    private static final class Match {
        final int base, offset;
        Match(int base,int offset) { this.base=base; this.offset=offset; }
    }
    private final List<Known> known = new ArrayList<>();
    private int lastBase, lastOffset;
    private Integer origin;
    private String lastPage;
    boolean needsTop;
    int size() { return known.size(); }
    static String key(int ordinal) { return "Muse step " + (ordinal+1); }
    void beginSweep() { lastPage=null; lastBase=0; lastOffset=0; needsTop=false; }

    /** Distance to an already inventoried row from the last aligned viewport. */
    float seekFraction(int ordinal,int height) {
        if (ordinal<0 || ordinal>=known.size() || height<=0) return 0;
        return (float)(known.get(ordinal).y-lastOffset)/height - 0.30f;
    }

    Map<String,WalkTree.Item> rows(WalkTree tree, WalkScroll tracker, boolean collecting,
                                   boolean returning, boolean seekingTop) {
        Map<String,WalkTree.Item> rows = new LinkedHashMap<>();
        List<Seen> visible = new ArrayList<>();
        WalkTree.Item viewport=null;
        for (WalkTree.Item item : tree.items) if (MuseProfile.isStep(tree,item)) {
            if (viewport!=null && viewport!=item.parent) throw new IllegalStateException("Multiple Muse step lists are active.");
            viewport=item.parent; visible.add(new Seen(tree,item,viewport));
        }
        if (visible.isEmpty() || seekingTop) return rows;
        visible.sort(Comparator.comparingInt(v->v.y));
        boolean atTop=viewport.scrollAction(WalkTree.UP)==0 || tracker.atEdge(viewport,WalkTree.UP);
        boolean atEnd=viewport.scrollAction(WalkTree.DOWN)==0 || tracker.atEdge(viewport,WalkTree.DOWN);
        Integer physical=tracker.position(viewport,WalkTree.DOWN);
        if (atTop) physical=0;
        StringBuilder fingerprint=new StringBuilder().append(physical).append('|');
        for (Seen v:visible) fingerprint.append(v.y).append('/').append(v.contents.length()).append(':').append(v.contents);
        String page=fingerprint.toString();
        Match chosen;
        if (known.isEmpty()) {
            if (!collecting) throw new IllegalStateException("No Muse step inventory is available.");
            chosen=new Match(0,physical==null?0:physical); if (physical!=null) origin=0;
        } else if (page.equals(lastPage)) chosen=new Match(lastBase,lastOffset);
        else {
            List<Match> candidates=new ArrayList<>();
            int lastStart=collecting?known.size()-1:known.size()-visible.size();
            int firstStart=collecting?Math.max(0,lastBase):0;
            for (int start=firstStart;start<=lastStart;start++) {
                if (atTop && start!=0) continue;
                if (!collecting && atEnd && start+visible.size()!=known.size()) continue;
                List<Integer> offsets=new ArrayList<>();
                int count=Math.min(visible.size(),known.size()-start);
                boolean same=true;
                for (int i=0;i<count;i++) {
                    Seen seen=visible.get(i); Known item=known.get(start+i);
                    if (!item.contents.equals(seen.contents)) { same=false; break; }
                    // A title at the top boundary may be clipped, so its reported y
                    // cannot constrain an absolute position as accurately as an interior row.
                    if (seen.y>2 && seen.y<viewport.bounds.height()-2) offsets.add(item.y-seen.y);
                }
                if (!same) continue;
                if (offsets.isEmpty()) offsets.add(known.get(start).y-visible.get(0).y);
                Collections.sort(offsets);
                int off=offsets.get(offsets.size()/2);
                if (offsets.get(offsets.size()-1)-offsets.get(0)>18) continue;
                if (physical!=null && origin!=null && Math.abs(off-(physical+origin))>20) continue;
                if (collecting && off<lastOffset-12) continue;
                candidates.add(new Match(start,off));
            }
            if (candidates.size()>1 && collecting && lastPage!=null) {
                // A gesture explicitly scrolls ~44% of the pane. This filters unchanged
                // copies when telemetry is absent, without treating text as a unique key.
                int h=viewport.bounds.height();
                candidates.removeIf(c->c.offset-lastOffset < h*0.18 || c.offset-lastOffset > h*0.72);
            }
            if (candidates.size()!=1) {
                if (!collecting && returning && !atTop) { needsTop=true; return rows; }
                throw new IllegalStateException("Could not align the ordered Muse steps across scroll positions. The overview changed or its scroll position was unavailable.");
            }
            chosen=candidates.get(0);
        }
        if (physical!=null && origin==null) origin=chosen.offset-physical;
        for (int i=0;i<visible.size();i++) {
            int ordinal=chosen.base+i;
            Seen seen=visible.get(i);
            if (ordinal==known.size() && collecting) known.add(new Known(seen,chosen.offset));
            if (ordinal>=known.size() || !known.get(ordinal).contents.equals(seen.contents))
                throw new IllegalStateException("Muse changed a step after its overview was collected.");
            rows.put(key(ordinal),seen.node);
        }
        lastBase=chosen.base; lastOffset=chosen.offset; lastPage=page; needsTop=false;
        return rows;
    }
}
