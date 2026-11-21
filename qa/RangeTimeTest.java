package local.walkthrough;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Examples include clipped chat text and ambiguous civil times. */
public final class RangeTimeTest {
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static int checks;

    private static void check(String label, Locale locale, LocalDate header, String instant) {
        Long expected = instant == null ? null : Instant.parse(instant).toEpochMilli();
        Long actual = TimeLabels.parse(label, header, ZONE, locale, TODAY);
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError("Time '" + label + "' produced " + actual + ", expected " + expected);
        }
        checks++;
    }

    public static void main(String[] args) {
        String previousEvening = "2026-11-20T03:23:00Z";
        for (String label : List.of("10:23 pm", "10:23PM", "10:23 p.m.", "22:23", "٢٢:٢٣")) {
            check(label, Locale.US, TODAY.minusDays(1), previousEvening);
        }
        for (String label : List.of("Yesterday, 10:23 PM", "2026-11-19T22:23:00-05:00",
                "11/19/2026, 10:23 PM", "2026-11-19 22:23", "Nov 19, 2026, 10:23\u202fPM")) {
            check(label, Locale.US, null, previousEvening);
        }
        check("19/11/2026, 22:23", Locale.UK, null, previousEvening);
        check("19.11.2026, 22:23", Locale.GERMANY, null, previousEvening);
        check("22:23:59", Locale.US, TODAY.minusDays(1), "2026-11-20T03:23:59Z");
        check("Today, 12:00 AM", Locale.US, null, "2026-11-20T05:00:00Z");
        check("2026-11-01T01:30-04:00", Locale.US, null, "2026-11-01T05:30:00Z");
        check("2026-11-01T01:30-05:00", Locale.US, null, "2026-11-01T06:30:00Z");
        for (String ambiguous : List.of("10:23 pm", "November 19, 10:23 PM", "2026-03-08T02:30",
                "2026-11-01T01:30", "2026-02-30T00:00", "yesterday 25:00", "today 2:90")) {
            check(ambiguous, Locale.US, null, null);
        }
        for (String prose : List.of("2 of 6 selected", "Sent at 10:23 pm", "Booked 12 tickets",
                "3 results in 28 seconds", "22:23 left to run", "Clock 10:23 PM EST")) {
            check(prose, Locale.US, TODAY, null);
        }
        if (!TODAY.minusDays(1).equals(TimeLabels.date("Yesterday", TODAY, Locale.US))) {
            throw new AssertionError("Activity date headers must follow the local calendar day");
        }
        LocalDate newYear=LocalDate.of(2027,1,2);
        if (!TODAY.minusDays(1).equals(TimeLabels.activityDay("Nov 19", TODAY, Locale.US))
                || !LocalDate.of(2026,12,31).equals(TimeLabels.activityDay("Dec 31",newYear,Locale.US))
                || !LocalDate.of(2024,2,29).equals(TimeLabels.activityDay("29 February",TODAY,Locale.UK))
                || TimeLabels.activityDay("Nov 31",TODAY,Locale.US)!=null
                || TimeLabels.activityDay("11/19",TODAY,Locale.US)!=null
                || TimeLabels.activityDay("Meeting Nov 19",TODAY,Locale.US)!=null
                || TimeLabels.date("Nov 19",TODAY,Locale.US)!=null) {
            throw new AssertionError("Yearless named month/day is supported only for Muse history headings");
        }
        System.out.println("Passed " + checks + " time-label checks and the day-header check.");
    }
}
