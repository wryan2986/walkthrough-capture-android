package local.walkthrough;

import java.text.DateFormatSymbols;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DecimalStyle;
import java.time.format.FormatStyle;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interprets complete timestamp labels. A clock by itself requires a confirmed date header. */
final class TimeLabels {
    private static final Pattern CLOCK = Pattern.compile(
            "(?<![\\p{L}\\p{Nd}:])(\\p{Nd}{1,2}):(\\p{Nd}{2})(?::(\\p{Nd}{2}))?(?: ?([\\p{L}.]+(?: [\\p{L}.]+)?))?$");
    private static final Pattern YEAR = Pattern.compile("(?<!\\p{Nd})\\p{Nd}{4}(?!\\p{Nd})");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u202f\\u00a0]+");
    private static final Pattern DIRECTION_MARKS = Pattern.compile("[\\u200e\\u200f\\u061c]");
    private static final ConcurrentHashMap<Locale, Formats> FORMATS = new ConcurrentHashMap<>();

    static Long parse(String raw, LocalDate headerDay, ZoneId zone, Locale locale, LocalDate today) {
        // Most accessibility labels are arbitrary text. Reject them before normalization or parsing.
        if (raw == null || raw.length() > 120 || raw.indexOf(':') < 0) return null;
        String s = clean(raw);
        if (isoDatePrefix(s) && s.length() > 10) {
            char separator = s.charAt(10);
            if (separator == 'T' || separator == 't' || separator == ' ') {
                try {
                    TemporalAccessor iso = DateTimeFormatter.ISO_DATE_TIME.parse(
                            separator == ' ' ? s.substring(0, 10) + "T" + s.substring(11) : s);
                    LocalDateTime time = LocalDateTime.from(iso);
                    ZoneOffset offset = iso.query(TemporalQueries.offset());
                    ZoneId labelZone = iso.query(TemporalQueries.zoneId());
                    if (offset == null) return local(time, labelZone == null ? zone : labelZone);
                    if (labelZone != null && !labelZone.getRules().getValidOffsets(time).contains(offset)) return null;
                    return time.toInstant(offset).toEpochMilli();
                } catch (DateTimeException ignored) {
                    if (separator != ' ') return null;
                }
            }
        }
        Matcher match = CLOCK.matcher(s);
        if (!match.find()) return null;
        LocalDate day = headerDay;
        if (match.start() > 0) {
            String prefix = s.substring(0, match.start()).trim();
            if (prefix.toLowerCase(Locale.ROOT).endsWith(" at")) prefix = prefix.substring(0, prefix.length() - 3).trim();
            if (prefix.endsWith(",") || prefix.endsWith("·")) prefix = prefix.substring(0, prefix.length() - 1).trim();
            day = date(prefix, today, locale);
        }
        if (day == null) return null;
        int hour = number(match.group(1));
        int minute = number(match.group(2));
        int second = match.group(3) == null ? 0 : number(match.group(3));
        String suffix = match.group(4);
        if (suffix != null) {
            if (hour < 1 || hour > 12) return null;
            String period = periodKey(suffix);
            Formats formats = FORMATS.computeIfAbsent(locale, Formats::new);
            if ("am".equals(period) || formats.am.equals(period)) hour %= 12;
            else if ("pm".equals(period) || formats.pm.equals(period)) hour = hour % 12 + 12;
            else return null;
        }
        if (hour > 23 || minute > 59 || second > 59) return null;
        return local(day.atTime(LocalTime.of(hour, minute, second)), zone);
    }

    static LocalDate date(String raw, LocalDate today, Locale locale) {
        if (raw == null || raw.length() > 100) return null;
        // A missing year can't identify an absolute day. Short relative headings are the exception.
        Matcher year = YEAR.matcher(raw);
        if (!year.find()) {
            if (raw.length() > 16) return null;
            String relative = raw.trim();
            if (relative.length() != 5 && relative.length() != 9) relative = clean(relative);
            if (relative.equalsIgnoreCase("Today")) return today;
            if (relative.equalsIgnoreCase("Yesterday")) return today.minusDays(1);
            return null;
        }
        boolean hasDayNumber = false;
        for (int i = 0; i < raw.length(); i++) {
            if ((i < year.start() || i >= year.end()) && Character.isDigit(raw.charAt(i))) {
                hasDayNumber = true;
                break;
            }
        }
        if (!hasDayNumber) return null;
        String s = clean(raw);
        if (s.length() == 10 && isoDatePrefix(s)) {
            try { return LocalDate.parse(s); }
            catch (DateTimeException invalid) { return null; }
        }
        for (DateTimeFormatter format : FORMATS.computeIfAbsent(locale, Formats::new).dates) {
            try { return LocalDate.parse(s, format); }
            catch (DateTimeException ignored) { }
        }
        return null;
    }

    static Long local(LocalDateTime time, ZoneId zone) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(time);
        return offsets.size() == 1 ? time.toInstant(offsets.get(0)).toEpochMilli() : null;
    }

    /** Muse's history headings can omit the year. A standalone named month and
     * day on that control denotes its most recent occurrence in the past. */
    static LocalDate activityDay(String raw, LocalDate today, Locale locale) {
        LocalDate complete = date(raw, today, locale);
        if (complete != null || raw == null || raw.length() > 40) return complete;
        String label = clean(raw);
        for (DateTimeFormatter format : FORMATS.computeIfAbsent(locale, Formats::new).monthDays) {
            try {
                MonthDay day = MonthDay.parse(label, format);
                int year = today.getYear();
                if (day.isAfter(MonthDay.from(today))) year--;
                while (!day.isValidYear(year)) year--;
                return day.atYear(year);
            } catch (DateTimeException ignored) { }
        }
        return null;
    }

    private static final class Formats {
        final List<DateTimeFormatter> dates = new ArrayList<>();
        final List<DateTimeFormatter> monthDays = new ArrayList<>();
        final String am, pm;
        Formats(Locale locale) {
            for (String p : new String[]{"MMM d", "MMMM d", "d MMM", "d MMMM"}) {
                monthDays.add(new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p)
                        .toFormatter(locale).withDecimalStyle(DecimalStyle.of(locale)).withResolverStyle(ResolverStyle.STRICT));
            }
            String[] periods = DateFormatSymbols.getInstance(locale).getAmPmStrings();
            am = periodKey(periods[0]);
            pm = periodKey(periods[1]);
            Set<String> patterns = new LinkedHashSet<>();
            for (FormatStyle style : new FormatStyle[]{FormatStyle.SHORT, FormatStyle.MEDIUM,
                    FormatStyle.LONG, FormatStyle.FULL}) {
                String p = DateTimeFormatterBuilder.getLocalizedDateTimePattern(style, null, IsoChronology.INSTANCE, locale);
                // Keep the locale's numeric month/day order while requiring the century.
                patterns.add(clean(p).replaceAll("y+", "uuuu").replace("dd", "d").replaceAll("(?<!M)MM(?!M)", "M"));
            }
            java.util.Collections.addAll(patterns, "MMM d, uuuu", "MMMM d, uuuu", "d MMM uuuu", "d MMMM uuuu",
                    "EEE, MMM d, uuuu", "EEEE, MMMM d, uuuu", "EEEE, d MMMM uuuu");
            for (String pattern : patterns) {
                dates.add(new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                        .toFormatter(locale).withDecimalStyle(DecimalStyle.of(locale)).withResolverStyle(ResolverStyle.STRICT));
            }
        }
    }

    private static boolean isoDatePrefix(String s) {
        return s.length() >= 10 && Character.isDigit(s.charAt(0)) && s.charAt(4) == '-' && s.charAt(7) == '-';
    }

    private static String clean(String s) {
        String unmarked = DIRECTION_MARKS.matcher(s).replaceAll("");
        return SPACES.matcher(unmarked).replaceAll(" ").trim();
    }

    private static int number(String digits) {
        int result = 0;
        for (int i = 0; i < digits.length();) {
            int cp = digits.codePointAt(i);
            result = result * 10 + Character.digit(cp, 10);
            i += Character.charCount(cp);
        }
        return result;
    }

    private static String periodKey(String s) { return s.replace(".", "").replace(" ", "").toLowerCase(Locale.ROOT); }

    private TimeLabels() {}
}
