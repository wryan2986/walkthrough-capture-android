package local.walkthrough;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Replays the observed day-card > clickable header > TextView shape, including
 * the upper/lower clipping seen while moving from Today to Yesterday. */
public final class MuseDaysTest {
    private static final LocalDate TODAY=LocalDate.of(2026,11,20), YESTERDAY=TODAY.minusDays(1);
    private static final Locale LOCALE=Locale.US;

    private static WalkTree.Item node(WalkTree tree,WalkTree.Item parent,String id,String text,boolean clickable) {
        WalkTree.Item n=new WalkTree.Item(); n.parent=parent; n.id=id; n.label=text; n.node.clickable=clickable;
        n.kind=text.isEmpty()?"android.view.View":"android.widget.TextView"; tree.all.add(n); return n;
    }
    private static WalkTree.Item section(WalkTree tree,String day) {
        WalkTree.Item outer=node(tree,null,MuseProfile.DAY,"",false);
        if(day!=null)node(tree,node(tree,outer,"","",true),"",day,false);
        return outer;
    }
    private static String key(String title,String description,String clock) {
        return MuseDays.identity(List.of(title,description,clock));
    }
    private static void require(Object actual,Object expected) {
        if(!java.util.Objects.equals(actual,expected))throw new AssertionError("Expected "+expected+", got "+actual);
    }
    private static void rejected(Runnable work) {
        try { work.run(); } catch(IllegalStateException expected) { return; }
        throw new AssertionError("An unconfirmed date was accepted");
    }
    public static void main(String[] args) {
        WalkTree tree=new WalkTree(); WalkTree.Item today=section(tree,"Today"), yesterday=section(tree,"Yesterday");
        WalkTree.Item morning=node(tree,today,MuseProfile.CARD,"",true);
        node(tree,morning,"","Yesterday",false); // Date-looking title on a card must not become the next section header.
        WalkTree.Item evening=node(tree,yesterday,MuseProfile.CARD,"",true); node(tree,evening,"","Today",false);
        require(MuseDays.group(morning),today); require(MuseDays.group(evening),yesterday);
        require(MuseDays.heading(tree,today),"Today"); require(MuseDays.heading(tree,yesterday),"Yesterday");
        String a=key("Yesterday","An example activity","12:22 am"), b=key("Calculate total","Example","12:20 am");
        String x=key("Yesterday","Yesterday's result","10:35 pm"), y=key("Record ages","Example","10:23 pm");
        String z=key("List supplies","Example","10:23 pm");
        MuseDays scan=new MuseDays();
        scan.beginPage();
        require(scan.resolve(MuseDays.heading(tree,today),List.of(a,b),TODAY,LOCALE),TODAY);
        require(scan.resolve(MuseDays.heading(tree,yesterday),List.of(x,y),TODAY,LOCALE),YESTERDAY); scan.finishPage();
        WalkTree clipped=new WalkTree(); require(MuseDays.heading(clipped,section(clipped,null)),null);
        scan.beginPage(); require(scan.resolve(null,List.of(y,z),TODAY,LOCALE),YESTERDAY); scan.finishPage();
        scan.beginPage(); rejected(()->scan.resolve("unknown date",List.of(z),TODAY,LOCALE));
        rejected(()->scan.resolve(null,List.of(a),TODAY,LOCALE)); // No overlap; never inherit the last date.
        MuseDays playback=new MuseDays(scan); playback.beginPage();
        require(playback.resolve(null,List.of(a,b),TODAY,LOCALE),TODAY); playback.finishPage();
        playback.beginPage(); require(playback.resolve(null,List.of(x,y),TODAY,LOCALE),YESTERDAY);
        rejected(()->new MuseDays(scan).resolve(null,List.of(a,x),TODAY,LOCALE));
        scan.beginPage(); scan.resolve("Today",List.of(z),TODAY,LOCALE); scan.finishPage();
        MuseDays ambiguous=new MuseDays(scan); ambiguous.beginPage();
        rejected(()->ambiguous.resolve(null,List.of(z),TODAY,LOCALE));
        // Real populated feed traversal: top group is moved to a different child path;
        // only one new complete title is visible on the third page. The equal native
        // section is supplied by the live AccessibilityNodeInfo lookup in RangeWalk.
        MuseDays populated=new MuseDays(); populated.beginPage();
        require(populated.resolve("Today",List.of(a,b),List.of(),null,true,TODAY,LOCALE),TODAY);
        populated.finishPage(); populated.beginPage();
        require(populated.resolve(null,List.of(b,x),List.of(),TODAY,true,TODAY,LOCALE),TODAY);
        populated.finishPage(); populated.beginPage();
        require(populated.resolve(null,List.of(y),List.of(),TODAY,true,TODAY,LOCALE),TODAY);
        java.util.ArrayList<String> clip=new java.util.ArrayList<>(), whole=new java.util.ArrayList<>();
        MuseDays.partialKeys(clip,List.of("Next day first card","Example"),false);
        MuseDays.partialKeys(whole,List.of("Next day first card","Example","10:00 pm"),true);
        require(populated.resolve("Yesterday",List.of(),clip,null,false,TODAY,LOCALE),YESTERDAY);
        populated.finishPage(); populated.beginPage();
        require(populated.resolve(null,List.of(z),whole,null,true,TODAY,LOCALE),YESTERDAY);
        rejected(()->populated.resolve(null,List.of(y),List.of(),YESTERDAY,true,TODAY,LOCALE));
        require(populated.resolve(null,List.of(),List.of(),null,false,TODAY,LOCALE),null);
        System.out.println("Passed Muse section ownership, clipped-row and native-section continuity, replay and ambiguous-date checks.");
    }
}
