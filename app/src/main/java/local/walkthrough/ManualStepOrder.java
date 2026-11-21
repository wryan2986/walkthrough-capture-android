package local.walkthrough;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Aligns observed step occurrences across free-form, bidirectional manual scrolling. */
final class ManualStepOrder {
    static final class Entry {
        final String token;
        boolean opened;
        Entry(String token) { this.token=token; }
    }
    private static final class Strip {
        final List<Entry> entries = new ArrayList<>();
        boolean startsAtTop, endsAtBottom;
    }
    private final List<Strip> strips = new ArrayList<>();

    /** Unknown jumps begin a separate strip. Equal-strength conflicting matches are left unresolved. */
    List<Entry> observe(List<String> tokens, boolean top, boolean bottom) {
        if (tokens.isEmpty()) return Collections.emptyList();
        Strip chosen=null;
        int best=0, offset=0;
        boolean ambiguous=false;
        for (Strip strip : strips) {
            int previous=strip.entries.size(), current=tokens.size();
            for (int at=1-current; at<previous; at++) {
                if (strip.startsAtTop && (at<0 || (top && at!=0))) continue;
                if (top && at>0) continue;
                if (strip.endsAtBottom && (at+current>previous || (bottom && at+current!=previous))) continue;
                if (bottom && at+current<previous) continue;
                int start=Math.max(0,-at), end=Math.min(current,previous-at);
                int overlap=end-start;
                if (overlap<best) continue;
                boolean matches=true;
                for (int i=start;i<end;i++) {
                    if (!tokens.get(i).equals(strip.entries.get(at+i).token)) { matches=false; break; }
                }
                if (!matches) continue;
                if (overlap==best) { ambiguous=true; continue; }
                chosen=strip; offset=at; best=overlap; ambiguous=false;
            }
        }
        if (ambiguous) return Collections.emptyList();
        if (chosen==null) { chosen=new Strip(); strips.add(chosen); offset=0; }
        ArrayList<Entry> result=new ArrayList<>();
        if (offset<0) {
            for (int i=-offset-1;i>=0;i--) chosen.entries.add(0,new Entry(tokens.get(i)));
            offset=0;
        }
        for (int i=0;i<tokens.size();i++) {
            if (offset+i==chosen.entries.size()) chosen.entries.add(new Entry(tokens.get(i)));
            result.add(chosen.entries.get(offset+i));
        }
        chosen.startsAtTop |= top;
        chosen.endsAtBottom |= bottom;
        return result;
    }

    boolean unseenAbove(Entry firstVisible) {
        for (Strip strip : strips) {
            int at=strip.entries.indexOf(firstVisible);
            if (at<0) continue;
            if (!strip.startsAtTop) return true;
            for (int i=0;i<at;i++) if (!strip.entries.get(i).opened) return true;
            return false;
        }
        return false;
    }
}
