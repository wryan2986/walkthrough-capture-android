package local.walkthrough;

import java.util.List;

/** Manual scrolling can reverse, jump, or start partway down an activity. */
public final class ManualStepOrderTest {
    private static void check(boolean result,String message) {
        if (!result) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        ManualStepOrder sequence=new ManualStepOrder();
        List<ManualStepOrder.Entry> first=sequence.observe(List.of("start","repeat","repeat","bridge"),true,false);
        check(first.size()==4 && first.get(1)!=first.get(2),"Repeated rows are separate occurrences");
        first.get(2).opened=true;
        List<ManualStepOrder.Entry> next=sequence.observe(List.of("repeat","bridge","last"),false,true);
        check(next.get(0)==first.get(2) && next.get(0).opened,"Alignment through a repeated title");
        next.get(2).opened=true;
        check(sequence.unseenAbove(next.get(0)),"Unopened steps above the current page");
        List<ManualStepOrder.Entry> reverse=sequence.observe(List.of("start","repeat","repeat","bridge"),true,false);
        check(!reverse.get(1).opened && reverse.get(2).opened,"Independent repeated occurrences survive return");
        reverse.forEach(row->row.opened=true);
        check(!sequence.unseenAbove(next.get(0)),"All earlier anchored steps opened");
        check(sequence.observe(List.of("repeat"),false,false).isEmpty(),"An ambiguous isolated duplicate gets no identity");

        ManualStepOrder middle=new ManualStepOrder();
        List<ManualStepOrder.Entry> inner=middle.observe(List.of("middle","below"),false,false);
        inner.get(0).opened=true;
        check(middle.unseenAbove(inner.get(0)),"Mid-list start cannot imply earlier steps are complete");
        List<ManualStepOrder.Entry> earlier=middle.observe(List.of("top","middle"),true,false);
        check(earlier.get(1)==inner.get(0) && earlier.get(1).opened,"Prepending earlier rows preserves identity");
        List<ManualStepOrder.Entry> disconnected=middle.observe(List.of("new chapter","bottom"),false,true);
        check(disconnected.size()==2 && !disconnected.get(0).opened,"A manual jump gets a fresh local strip");
        check(middle.observe(List.of("top","middle"),true,false).get(1).opened,"Older strip recovered after jump");
        System.out.println("Manual trajectory order passed duplicate, reverse, unanchored start and jump checks.");
    }
}
