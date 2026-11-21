package local.walkthrough;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local heuristics over text the foreground app has explicitly exposed to Android. */
final class PrivacyTextRules {
    private static final String LOCAL="[\\p{L}\\p{N}._%+'-]";
    private static final Pattern EMAIL=Pattern.compile("(?<!"+LOCAL+")"+LOCAL+"++@(?:[\\p{L}\\p{N}-]++\\.)+[\\p{L}]{2,}");
    private static final Pattern US_PHONE=Pattern.compile("(?<![\\p{L}\\p{Nd}])(?:\\+?1[ .-]?)?(?:\\(\\p{Nd}{3}\\)|\\p{Nd}{3})[ .-]\\p{Nd}{3}[ .-]\\p{Nd}{4}(?![\\p{L}\\p{Nd}])");
    private static final Pattern NUMBER=Pattern.compile("(?<![\\p{L}\\p{Nd}])(?:\\+|\\()?\\p{Nd}[\\p{Nd} .()−–-]{5,35}\\p{Nd}(?![\\p{L}\\p{Nd}])");
    private static final Pattern DATE=Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}-\\d{1,2}-\\d{4}");
    private final boolean emails, phones;
    private final List<String> terms=new ArrayList<>();

    PrivacyTextRules(boolean emails,boolean phones,List<String> exactTerms) {
        this.emails=emails; this.phones=phones;
        for(String term:exactTerms) {
            String normalized=normalize(term).trim();
            if(!normalized.isEmpty() && !terms.contains(normalized))terms.add(normalized);
        }
    }
    boolean enabled() { return emails || phones || !terms.isEmpty(); }
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    boolean matches(String raw) {
        if(raw==null || raw.isEmpty() || !enabled())return false;
        String value=normalize(raw);
        for(String term:terms)if(value.contains(term))return true;
        if(emails && value.indexOf('@')>=0 && EMAIL.matcher(value).find())return true;
        if(phones) {
            if(US_PHONE.matcher(value).find())return true;
            Matcher m=NUMBER.matcher(value);
            while(m.find()) {
                String candidate=m.group();
                long digits=candidate.codePoints().filter(Character::isDigit).count();
                if(digits>=7 && digits<=15 && !DATE.matcher(candidate).matches())return true;
            }
        }
        return false;
    }
}
