package local.walkthrough;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads a single foreground snapshot. The returned box is advisory and has no touch behavior. */
final class ManualTrajectoryGuide {
    static final class Hint {
        final Rect bounds;
        final String text;
        Hint(Rect bounds, String text) {
            this.bounds=bounds==null ? null : new Rect(bounds);
            this.text=text;
        }
    }

    private enum Page { NONE, CHAT, FEED, SUMMARY, DETAIL }
    private static final class Row {
        final Rect bounds;
        final String title, summary, token;
        boolean fullyVisible;
        ManualStepOrder.Entry occurrence;
        Row(WalkTree.Item item,String title,String summary,String token) {
            bounds=new Rect(item.bounds);
            this.title=title; this.summary=summary; this.token=token;
        }
    }

    private Page page=Page.NONE;
    private ManualStepOrder order=new ManualStepOrder();
    private final Set<String> openedCards=new HashSet<>();
    private List<Row> cards=Collections.emptyList(), steps=Collections.emptyList();
    private Row clicked;
    private Page clickPage=Page.NONE;
    private long clickAt;
    private static final long CLICK_WINDOW_NS=1_800_000_000L;
    private boolean detailChecked;

    void clearPendingClick() {
        clicked=null;
        clickPage=Page.NONE;
    }

    void reset() {
        page=Page.NONE;
        order=new ManualStepOrder();
        openedCards.clear();
        cards=Collections.emptyList();
        steps=Collections.emptyList();
        clearPendingClick();
        detailChecked=false;
    }

    /** Optional evidence from the user's actual click. Opening a detail must still be observed. */
    void clicked(Rect sourceBounds) {
        clearPendingClick();
        if (sourceBounds==null || sourceBounds.isEmpty() || (page!=Page.FEED && page!=Page.SUMMARY)) return;
        clickPage=page;
        clickAt=System.nanoTime();
        List<Row> rows=page==Page.FEED ? cards : steps;
        Row result=null;
        for (Row row : rows) {
            if (!row.bounds.contains(sourceBounds) && !row.bounds.equals(sourceBounds)) continue;
            if (result!=null) { clicked=null; return; }
            result=row;
        }
        clicked=result;
    }

    private boolean recentClick(Page source) {
        return clickPage==source && System.nanoTime()-clickAt<=CLICK_WINDOW_NS;
    }

    Hint update(WalkTree tree, Rect display) {
        if (tree==null || tree.all.isEmpty() || display==null || display.isEmpty()
                || !MuseProfile.muse(tree.all.get(0))) {
            clearPendingClick();
            return note("Open Muse to see the next control.");
        }
        WalkTree.Item chat=MuseProfile.id(tree,"hatch-chat-view"), feed=MuseProfile.id(tree,MuseProfile.ACTIVITY);
        WalkTree.Item handle=MuseProfile.control(tree,"Dismiss"), back=MuseProfile.control(tree,"Go back");
        if (chat!=null && feed==null && handle==null) {
            if (page!=Page.CHAT) { reset(); page=Page.CHAT; }
            List<WalkTree.Item> avatar=tree.matches(MuseProfile.AVATAR);
            return avatar.size()==1 ? cue(avatar.get(0),display,"Tap the avatar to open Activity.")
                    : note("Open the activity list in Muse.");
        }
        if (feed!=null && chat==null && handle==null) return feed(tree,display,feed);
        if (chat==null && feed==null && handle!=null) {
            WalkTree.Item body=body(tree);
            if (body!=null) return back==null ? summary(tree,display,body,handle) : detail(tree,display,body,handle,back);
        }
        clearPendingClick();
        return note("Waiting for a recognizable Muse screen.");
    }

    private Hint feed(WalkTree tree,Rect display,WalkTree.Item feed) {
        // Leaving the sheet means a new entry, including reopening the same card,
        // starts a fresh step list. The next card always remains the user's choice.
        if (page!=Page.FEED) {
            order=new ManualStepOrder(); steps=Collections.emptyList(); clearPendingClick();
            detailChecked=false; page=Page.FEED;
        }
        ArrayList<Row> current=new ArrayList<>();
        for (WalkTree.Item item:tree.items) {
            if (!MuseProfile.CARD.equals(item.id) || !item.inside(feed) || !MuseProfile.muse(item)
                    || item.clickable()==null || !fullyVisible(item,display)) continue;
            List<String> fields=MuseProfile.directText(tree,item);
            if (fields.size()!=3) continue;
            String day="";
            for (WalkTree.Item p=item.parent;p!=null;p=p.parent) if (MuseProfile.DAY.equals(p.id)) {
                for (WalkTree.Item child:tree.all) if (child.parent==p && !MuseProfile.CARD.equals(child.id) && child.node.isClickable()) {
                    List<String> labels=MuseProfile.directText(tree,child);
                    if (labels.size()==1) day=labels.get(0);
                    break;
                }
                break;
            }
            String token=part(day)+part(fields.get(0))+part(fields.get(1))+part(fields.get(2));
            current.add(new Row(item,fields.get(0),fields.get(1),token));
        }
        current.sort(Comparator.comparingInt(r->r.bounds.top));
        cards=current;
        for (Row row:cards) if (!openedCards.contains(row.token))
            return new Hint(row.bounds,"Tap an activity. You can choose any card.");
        WalkTree.Item day=MuseProfile.closedDay(tree,new HashSet<>());
        if (day!=null && fullyVisible(day,display)) return cue(day,display,"Tap the date to show its activities.");
        if (!cards.isEmpty()) return note("Choose another activity, scroll, or finish recording.");
        return note("Scroll to bring an activity card into view.");
    }

    private Hint summary(WalkTree tree,Rect display,WalkTree.Item body,WalkTree.Item handle) {
        ArrayList<WalkTree.Item> candidates=new ArrayList<>();
        WalkTree.Item stepList=null;
        for (WalkTree.Item item:tree.items) {
            if (!item.inside(body) || !item.node.isEnabled() || !MuseProfile.isStep(tree,item)) continue;
            if (stepList!=null && stepList!=item.parent)
                return note("More than one step list is visible. Choose the activity you want.");
            stepList=item.parent;
            candidates.add(item);
        }
        if (page==Page.FEED) {
            Row selected=identify(tree,body,cards,Page.FEED,!candidates.isEmpty());
            if (selected!=null) openedCards.add(selected.token);
            if (selected==null && candidates.isEmpty()) return note("Waiting for the activity steps.");
        } else if (page!=Page.SUMMARY && page!=Page.DETAIL && candidates.isEmpty()) {
            return note("Open an activity to begin guiding its steps.");
        }
        if (page!=Page.SUMMARY) { clearPendingClick(); detailChecked=false; page=Page.SUMMARY; }
        if (stepList!=null && stepList.node.isScrollable()) body=stepList;
        candidates.sort(Comparator.comparingInt(n->n.bounds.top));
        boolean atTop=body.scrollAction(WalkTree.UP)==0, atBottom=body.scrollAction(WalkTree.DOWN)==0;
        ArrayList<Row> current=new ArrayList<>();
        ArrayList<String> tokens=new ArrayList<>();
        Set<String> indexed=new HashSet<>();
        boolean duplicateIndex=false;
        Row firstFullyVisible=null;
        for (WalkTree.Item item:candidates) {
            boolean visible=fullyVisible(item,display);
            List<String> fields=MuseProfile.directText(tree,item);
            // A partially visible row can receive a real tap. Keep it in occurrence
            // order only while both native identity fields and part of the row are exposed.
            if (!visible && (fields.size()!=2 || !Rect.intersects(item.bounds,display)
                    || !Rect.intersects(item.bounds,item.parent.bounds))) continue;
            String title=fields.get(0), description=fields.size()>1 ? fields.get(1) : "";
            AccessibilityNodeInfo.CollectionItemInfo info=item.node.getCollectionItemInfo();
            String token=part(title)+part(description);
            if (info!=null && info.getRowIndex()>=0) {
                String index="i"+info.getRowIndex()+"/"+info.getColumnIndex();
                if (!indexed.add(index)) duplicateIndex=true;
                token=index+token;
            }
            Row row=new Row(item,title,description,token);
            row.fullyVisible=visible;
            if (visible && firstFullyVisible==null) firstFullyVisible=row;
            current.add(row);
            tokens.add(token);
        }
        List<ManualStepOrder.Entry> occurrences=duplicateIndex ? Collections.emptyList() : order.observe(tokens,atTop,atBottom);
        if (occurrences.size()==current.size())
            for (int i=0;i<current.size();i++) current.get(i).occurrence=occurrences.get(i);
        steps=current;
        if (shouldExpand(tree,handle,display))
            return cue(handle,display,"Drag the handle up to reveal more steps.");
        if (!current.isEmpty() && occurrences.isEmpty())
            return note("Repeated steps are unclear here. Scroll for a different view or choose a step.");
        for (Row row:current) if (row.fullyVisible && row.occurrence!=null && !row.occurrence.opened)
            return new Hint(row.bounds,"Tap this step to open its details.");
        if (!atBottom) return note("Scroll down to bring the next step fully into view.");
        if (!atTop && (firstFullyVisible==null || order.unseenAbove(firstFullyVisible.occurrence)))
            return note("Scroll up to check the earlier steps.");
        if (firstFullyVisible==null) return note("Bring a complete step into view, or return to Activity.");
        WalkTree.Item close=MuseProfile.control(tree,"Close sheet");
        if (close!=null && close!=handle && close.node.isEnabled() && display.contains(close.bounds)) {
            Rect target=new Rect(close.bounds);
            if (target.width()>display.width()*.8f && target.top<=display.top+2) {
                // Native Muse exposes the backdrop as a full-width button. Point to a
                // fingertip-sized area below system chrome and above the draggable handle.
                int bottom=Math.min(target.bottom,handle.bounds.top);
                int top=Math.max(target.top,display.top+display.height()/18);
                if (bottom>top+8) {
                    int cx=target.centerX(), cy=(bottom+top)/2;
                    int half=Math.min((bottom-top)/2,display.width()/18);
                    target.set(cx-half,cy-half,cx+half,cy+half);
                    return new Hint(target,"Tap above the sheet to choose another activity.");
                }
            } else return new Hint(target,"Close the step list to choose another activity.");
        }
        return cue(handle,display,"Close the step list to choose another activity.");
    }

    private Hint detail(WalkTree tree,Rect display,WalkTree.Item body,WalkTree.Item handle,WalkTree.Item back) {
        if (page!=Page.DETAIL) {
            // A tap outside the identifiable rows cannot credit a lookalike by title.
            detailChecked=page==Page.SUMMARY && recentClick(Page.SUMMARY) && clicked==null;
            if (page!=Page.SUMMARY) { steps=Collections.emptyList(); clearPendingClick(); }
            page=Page.DETAIL;
        }
        if (!detailChecked) {
            Row opened=identify(tree,body,steps,Page.SUMMARY,true);
            if (opened!=null && opened.occurrence!=null) {
                opened.occurrence.opened=true;
                detailChecked=true;
                clearPendingClick();
            }
        }
        if (steps.isEmpty())
            return cue(back,display,"Tap Back to find the step list.");
        if (shouldExpand(tree,handle,display))
            return cue(handle,display,"Drag the handle up to see more of this detail.");
        for (WalkTree.Item pane:tree.items) if (pane.inside(body) && pane!=body && pane.bounds.height()>8
                && pane.scrollAction(WalkTree.RIGHT)!=0 && Rect.intersects(pane.bounds,display))
            return note("Swipe left inside the detail to read across, then continue.");
        if (body.scrollAction(WalkTree.DOWN)!=0) return note("Scroll down to read the rest of this detail.");
        return cue(back,display,"Tap Back when you have finished reading.");
    }

    /** Associate progress with the observed destination content, including an off-suggestion tap. */
    private Row identify(WalkTree tree,WalkTree.Item body,List<Row> prior,Page source,boolean verifiedDestination) {
        if (prior.isEmpty()) return null;
        Set<String> text=new HashSet<>();
        for (WalkTree.Item item:tree.items)
            if (item.inside(body) && item.kind.endsWith("TextView") && !item.label.isEmpty() && item.clickable()==null)
                text.add(item.label);
        if (text.isEmpty()) return null;
        Row physical=null;
        if (recentClick(source)) {
            if (clicked==null) return null;
            for (Row row:prior) {
                if (row.token.equals(clicked.token) && row.bounds.equals(clicked.bounds)) {
                    if (physical!=null) { physical=null; break; }
                    physical=row;
                }
            }
            if (physical==null) return null;
        }
        Row best=null;
        int bestScore=0;
        for (Row row:prior) {
            if (!text.contains(row.title)) continue;
            int score=1;
            if (!row.summary.isEmpty() && text.contains(row.summary)) score+=4;
            if (physical==row) score++;
            if (score>bestScore) { best=row; bestScore=score; }
            else if (score==bestScore) best=null;
        }
        // A verified native navigation can replace the title with a generic heading.
        // Never advance from a click alone, an expired event or an ambiguous matching title.
        return bestScore==0 && verifiedDestination ? physical : best;
    }

    private static String part(String value) { return value.length()+":"+value; }

    /** The large native scroll view is the sheet body. Nested code panels may also scroll. */
    private static WalkTree.Item body(WalkTree tree) {
        WalkTree.Item outer=null;
        for (WalkTree.Item n:tree.items) {
            if (!n.kind.endsWith(".ScrollView") || !MuseProfile.muse(n)) continue;
            if (outer==null) outer=n;
            else if (!n.inside(outer)) return null;
        }
        return outer;
    }

    private static boolean shouldExpand(WalkTree tree,WalkTree.Item handle,Rect display) {
        return handle.bounds.centerY()>display.top+display.height()*.34f && !MuseProfile.contentFits(tree);
    }

    private static boolean fullyVisible(WalkTree.Item row,Rect display) {
        if (!display.contains(row.bounds) || row.bounds.isEmpty()) return false;
        WalkTree.Item pane=MuseProfile.scrollParent(row);
        if (pane==null) return true;
        if (!pane.bounds.contains(row.bounds)) return false;
        return !(row.bounds.top<=pane.bounds.top+2 && pane.scrollAction(WalkTree.UP)!=0)
                && !(row.bounds.bottom>=pane.bounds.bottom-2 && pane.scrollAction(WalkTree.DOWN)!=0);
    }

    private static Hint cue(WalkTree.Item item,Rect display,String instruction) {
        return !item.bounds.isEmpty() && display.contains(item.bounds) && item.node.isEnabled()
                ? new Hint(item.bounds,instruction) : note("Bring the next control fully into view.");
    }
    private static Hint note(String instruction) { return new Hint(null,instruction); }
}
