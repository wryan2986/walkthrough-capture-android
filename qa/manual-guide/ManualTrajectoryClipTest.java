package local.walkthrough;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

/** Exercises the production planner's click, destination, and return transitions. */
public final class ManualTrajectoryClipTest {
    private static final Rect SCREEN=new Rect(0,0,720,1280), BODY=new Rect(0,193,720,1224);
    private static final Rect FIRST=new Rect(28,280,692,380), CLIPPED=new Rect(28,1167,692,1224),
            REVEALED=new Rect(28,580,692,680), NEXT=new Rect(28,695,692,795);
    private static final String SAME="Duplicate title", SECONDARY="Duplicate summary";

    private static void click(AccessibilityNodeInfo n) {
        n.clickable=true;
        n.actions.add(new AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
    private static AccessibilityNodeInfo window() {
        AccessibilityNodeInfo root=new AccessibilityNodeInfo("android.view.View",SCREEN);
        AccessibilityNodeInfo handle=root.add("android.widget.Button",new Rect(319,116,403,193));
        handle.description="Dismiss"; click(handle);
        return root;
    }
    private static void row(AccessibilityNodeInfo body,Rect b,String title,String description) {
        AccessibilityNodeInfo step=body.add("android.view.View",b);
        click(step);
        step.add("android.view.View",b).description="Completed";
        step.add("android.widget.TextView",b).text=title;
        if (description!=null) step.add("android.widget.TextView",b).text=description;
    }
    private static WalkTree summary(boolean returned,boolean identified) {
        AccessibilityNodeInfo root=window(), body=root.add("android.widget.ScrollView",BODY);
        if (!returned) { body.scrollable=true; body.actions.add(AccessibilityAction.ACTION_SCROLL_DOWN); }
        row(body,FIRST,SAME,SECONDARY);
        row(body,returned?REVEALED:CLIPPED,SAME,returned || identified ? SECONDARY : null);
        if (returned) row(body,NEXT,"Last step","Last summary");
        return new WalkTree(root,true);
    }
    private static WalkTree detail() {
        AccessibilityNodeInfo root=window();
        AccessibilityNodeInfo back=root.add("android.widget.Button",new Rect(25,204,109,288));
        back.description="Go back"; click(back);
        AccessibilityNodeInfo body=root.add("android.widget.ScrollView",BODY);
        body.add("android.widget.TextView",FIRST).text=SAME;
        body.add("android.widget.TextView",REVEALED).text=SECONDARY;
        return new WalkTree(root,true);
    }
    private static Rect next(ManualTrajectoryGuide guide,WalkTree tree) {
        try (WalkTree owned=tree) { return guide.update(owned,SCREEN).bounds; }
    }
    private static void expect(Rect expected,Rect actual,String why) {
        if (!expected.equals(actual)) throw new AssertionError(why);
    }
    private static void exercise(boolean identified) {
        ManualTrajectoryGuide guide=new ManualTrajectoryGuide();
        expect(FIRST,next(guide,summary(false,identified)),"The clipped row must not be highlighted");
        guide.clicked(CLIPPED);
        next(guide,detail());
        expect(FIRST,next(guide,summary(true,true)),"Tapping the clipped duplicate must not debit the first row");
        guide.clicked(FIRST);
        next(guide,detail());
        expect(identified?NEXT:REVEALED,next(guide,summary(true,true)),
                "Only a supported, observed clipped occurrence is marked as opened");
    }
    private static void checkTargetIsolation() {
        for(String target:new String[]{MuseProfile.PKG,MuseProfile.LAB}) {
            String other=target.equals(MuseProfile.LAB) ? MuseProfile.PKG : MuseProfile.LAB;
            if(!MuseProfile.matchesSession(target,target) || MuseProfile.matchesSession(target,other)
                    || MuseProfile.matchesSession(target,null) || MuseProfile.matchesSession(target,"")) {
                throw new AssertionError("A guided session must read only its selected package");
            }
            AccessibilityNodeInfo root=new AccessibilityNodeInfo("android.view.View",SCREEN); root.pkg=target;
            root.add("android.widget.TextView",FIRST).text="Selected app";
            AccessibilityNodeInfo foreign=root.add("android.view.View",CLIPPED); foreign.pkg=other;
            foreign.add("android.widget.TextView",CLIPPED).text="Foreign content";
            try(WalkTree tree=new WalkTree(root)) {
                if(tree.items.size()!=2 || !MuseProfile.muse(tree.items.get(1)) || tree.signature.contains("Foreign content")) {
                    throw new AssertionError("The opposite package leaked into the selected window tree");
                }
            }
        }
    }

    private static void checkInsetRow() {
        AccessibilityNodeInfo root=new AccessibilityNodeInfo("android.view.View",SCREEN);
        AccessibilityNodeInfo list=root.add("android.widget.ScrollView",new Rect(0,709,720,1280));
        list.scrollable=true; list.actions.add(AccessibilityAction.ACTION_SCROLL_DOWN);
        list.add("android.view.View",new Rect(0,1247,720,1280));
        try(WalkTree tree=new WalkTree(root)) {
            PreviewRow.Position p=new PreviewRow.Position(tree,tree.items.get(2),new WalkScroll(true),7,
                    new Rect(0,42,720,1238));
            if(!p.visible.isEmpty() || p.direction!=WalkTree.DOWN || p.pane!=tree.items.get(1))
                throw new AssertionError("Bring a card hidden by the navigation inset into view first");
        }
    }
    public static void main(String[] args) {
        checkInsetRow();
        checkTargetIsolation();
        exercise(true);
        exercise(false);
        System.out.println("Manual guide clipped alternate taps and identical step labels passed.");
    }
}

// The guided path uses these declarations only to link the broader WalkTree adapter.
final class WalkScroll {
    private final boolean automatic;
    WalkScroll() { this(false); }
    WalkScroll(boolean automatic) { this.automatic=automatic; }
    boolean atEdge(WalkTree.Item item,int direction) {
        if(!automatic)throw new AssertionError("Manual guide invoked automatic scrolling");
        return false;
    }
}
final class ConversationScene {
    static boolean isMessage(WalkTree.Item item) { return item.id.startsWith("hatch-message-"); }
}

// The position check only needs the preview settings type, not Android window drawing.
final class TapHighlight { static final class Settings {} }
