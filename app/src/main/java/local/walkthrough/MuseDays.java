package local.walkthrough;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Associates a clock with its own Activity day. Virtualized day headers can
 * disappear on later viewports, while neighboring cards still overlap. */
final class MuseDays {
    private final boolean replay;
    private final Map<String,LocalDate> catalog=new HashMap<>();
    private Map<String,LocalDate> previous=Collections.emptyMap(), current;

    MuseDays() { replay=false; }
    MuseDays(MuseDays scan) { replay=true; catalog.putAll(scan.catalog); }

    static WalkTree.Item group(WalkTree.Item row) {
        for(WalkTree.Item p=row.parent;p!=null;p=p.parent) if(MuseProfile.DAY.equals(p.id))return p;
        throw new IllegalStateException("A Muse activity row has no identifiable day section.");
    }

    /** Only the direct day-toggle control owns the heading. A card can itself
     * be titled or described as “Today” or “Yesterday”. */
    static String heading(WalkTree tree,WalkTree.Item group) {
        String result=null;
        for(WalkTree.Item header:tree.all) {
            if(header.parent!=group || MuseProfile.CARD.equals(header.id) || !header.node.isClickable())continue;
            for(WalkTree.Item text:tree.all) {
                if(text.parent!=header || !text.kind.endsWith("TextView") || text.label.isEmpty())continue;
                if(result!=null && !result.equals(text.label))
                    throw new IllegalStateException("Multiple labels identify one Muse day heading.");
                result=text.label;
            }
        }
        return result;
    }

    static String identity(List<String> fields) {
        String title=fields.get(0), summary=fields.get(1);
        return title.length()+"|"+title+summary.length()+"|"+summary+fields.get(2);
    }
    void beginPage() { current=new HashMap<>(); }
    void finishPage() { if(!current.isEmpty()) previous=current; current=null; }

    LocalDate resolve(String heading,List<String> identities,LocalDate today,Locale locale) {
        return resolve(heading,identities,Collections.emptyList(),null,true,today,locale);
    }

    /** Short-lived text pairs can bridge a clipped row but never enter the discovery
     * catalog. sameSection is supplied only for an equal Android node in the previous view. */
    LocalDate resolve(String heading,List<String> identities,List<String> partial,LocalDate sameSection,
                      boolean hasCompleteRows,LocalDate today,Locale locale) {
        LocalDate day;
        if(heading!=null) {
            day=TimeLabels.activityDay(heading,today,locale);
            if(day==null)throw new IllegalStateException("The date shown in a Muse day heading could not be read.");
        } else {
            day=uniqueDay(previous,identities);
            if(day==null && replay)day=uniqueDay(catalog,identities);
            LocalDate pairDay=uniqueDay(previous,partial);
            if(day==null)day=pairDay;
            if(day!=null && sameSection!=null && !day.equals(sameSection))
                throw new IllegalStateException("The Muse day section and its known activity rows disagree on the date.");
            if(day==null)day=sameSection;
            if(day==null) {
                if(!hasCompleteRows)return null;
                throw new IllegalStateException("A Muse day heading is offscreen and neither its section nor its rows match a verified day.");
            }
        }
        for(String key:identities) {
            remember(current,key,day);
            if(!replay)remember(catalog,key,day);
        }
        for(String key:partial)remember(current,key,day);
        return day;
    }

    static void partialKeys(List<String> keys,List<String> labels,boolean endsWithClock) {
        if(labels.size()==3) {
            keys.add(pair("first",labels.get(0),labels.get(1)));
            keys.add(pair("last",labels.get(1),labels.get(2)));
        } else if(labels.size()==2) {
            keys.add(pair(endsWithClock?"last":"first",labels.get(0),labels.get(1)));
        }
    }
    private static String pair(String role,String a,String b) { return role+"|"+a.length()+"|"+a+b; }

    private static LocalDate uniqueDay(Map<String,LocalDate> known,List<String> keys) {
        LocalDate result=null;
        for(String key:keys) {
            LocalDate day=known.get(key); // null also marks an identity repeated on several days.
            if(day==null)continue;
            if(result!=null && !result.equals(day))throw new IllegalStateException("The clipped Muse day section matches different dates.");
            result=day;
        }
        return result;
    }
    private static void remember(Map<String,LocalDate> dates,String key,LocalDate day) {
        if(!dates.containsKey(key))dates.put(key,day);
        else if(!day.equals(dates.get(key)))dates.put(key,null);
    }
}
