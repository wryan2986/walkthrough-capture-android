package local.walkthrough;

import android.app.*;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

final class RangeControls {
    private final Activity activity;
    private final Endpoint from, through;
    private final Spinner source;
    private final TextView zoneLabel;
    private final Locale locale;
    private final LinearLayout box;
    private long startAt, endAt;
    private final DateTimeFormatter format, dateFormat;
    private final java.text.DateFormat clockFormat;
    private static final int INK = 0xff182e2b, MUTED = 0xff506762, TEAL = 0xff116553;
    private static final int LINE = 0xffcedcd5;
    private static final String[] LEVELS = {"card","avatar"};

    RangeControls(Activity activity, LinearLayout parent, SharedPreferences prefs, Runnable changed) {
        this.activity = activity;
        locale = activity.getResources().getConfiguration().getLocales().get(0);
        format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale);
        dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        clockFormat = android.text.format.DateFormat.getTimeFormat(activity);
        box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        parent.addView(box);
        TextView heading = label("Time range", 19, INK, true);
        heading.setAccessibilityHeading(true);
        box.addView(heading);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        startAt = prefs.getLong("startAt", today.atStartOfDay(zone).toInstant().toEpochMilli());
        endAt = prefs.getLong("endAt", today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()-60000L);
        LinearLayout endpoints = new LinearLayout(activity);
        endpoints.setBaselineAligned(false);
        LinearLayout.LayoutParams pair = new LinearLayout.LayoutParams(-1,-2);
        pair.topMargin = dp(10);
        box.addView(endpoints, pair);
        from = new Endpoint("From");
        through = new Endpoint("Through");
        endpoints.addView(from, new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0,-1,1);
        right.setMarginStart(dp(9));
        endpoints.addView(through, right);
        from.setOnClickListener(v -> pick(true, changed));
        through.setOnClickListener(v -> pick(false, changed));
        zoneLabel = label("",12,MUTED,false);
        LinearLayout.LayoutParams note = new LinearLayout.LayoutParams(-1,-2);
        note.topMargin = dp(7);
        box.addView(zoneLabel, note);
        source = new Spinner(activity);
        ArrayAdapter<String> options = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item,
                new String[]{"Activity cards in the avatar profile", "Conversation entries with their own avatars"});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        source.setAdapter(options);
        source.setSelection(Math.max(0,Arrays.asList(LEVELS).indexOf(prefs.getString("rangeLevel","card"))));
        source.setContentDescription("Where to read the activity time");
        update();
    }

    void attachSource(LinearLayout parent) {
        TextView heading = label("Where to read the activity time",14,INK,true);
        parent.addView(heading);
        source.setMinimumHeight(dp(48));
        parent.addView(source);
    }

    private int dp(int n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }

    private TextView label(String text, int sp, int color, boolean medium) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private final class Endpoint extends LinearLayout {
        private final String caption;
        private final TextView date, clock;

        Endpoint(String caption) {
            super(activity);
            this.caption = caption;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(13),dp(10),dp(10),dp(11));
            setClickable(true);
            setFocusable(true);
            setMinimumHeight(dp(86));
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(0xffffffff);
            shape.setCornerRadius(dp(14));
            shape.setStroke(dp(1), LINE);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x20116553),shape,null));
            TextView name = label(caption,12,TEAL,true);
            date = label("",16,INK,true);
            clock = label("",14,MUTED,false);
            date.setPadding(0,dp(3),0,0);
            for (TextView text : new TextView[]{name,date,clock}) {
                text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                addView(text);
            }
        }

        void displayTime(long millis, ZoneId zone) {
            String d = dateFormat.format(Instant.ofEpochMilli(millis).atZone(zone));
            clockFormat.setTimeZone(TimeZone.getTimeZone(zone.getId()));
            String t = clockFormat.format(new Date(millis));
            date.setText(d);
            clock.setText(t);
            setContentDescription(caption + ", " + d + ", " + t);
        }

        @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
    }

    private void pick(boolean start, Runnable changed) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime value = Instant.ofEpochMilli(start ? startAt : endAt).atZone(zone);
        new DatePickerDialog(activity, (v, y, m, d) -> {
            LocalDate day = LocalDate.of(y,m+1,d);
            new TimePickerDialog(activity, (clock,h,min) -> {
                LocalDateTime local = day.atTime(h,min);
                List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
                if (offsets.isEmpty()) {
                    Toast.makeText(activity,"That local time is skipped by a clock change. Choose another minute.",Toast.LENGTH_LONG).show();
                } else if (offsets.size()==1) {
                    set(start,local.toInstant(offsets.get(0)).toEpochMilli(),changed);
                } else {
                    String[] choices = offsets.stream().map(ZoneOffset::toString).toArray(String[]::new);
                    new AlertDialog.Builder(activity).setTitle("Choose the UTC offset for this repeated hour")
                            .setItems(choices,(dialog,i) -> set(start,local.toInstant(offsets.get(i)).toEpochMilli(),changed)).show();
                }
            },value.getHour(),value.getMinute(),android.text.format.DateFormat.is24HourFormat(activity)).show();
        },value.getYear(),value.getMonthValue()-1,value.getDayOfMonth()).show();
    }
    private void set(boolean start, long time, Runnable changed) {
        if (start) startAt=time; else endAt=time;
        update(); changed.run();
    }
    void update() {
        ZoneId zone = ZoneId.systemDefault();
        zoneLabel.setText(zone.getDisplayName(TextStyle.FULL, locale) + " · Includes both minutes");
        from.displayTime(startAt, zone);
        through.displayTime(endAt, zone);
    }
    String display(long millis) { return format.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())); }
    String level() { return LEVELS[source.getSelectedItemPosition()]; }
    void useCardTimes() { source.setSelection(0); }
    long start() { return startAt; }
    long endExclusive() { return endAt + 60_000L; }
    boolean valid() { return endAt >= startAt; }
    void remember(SharedPreferences.Editor prefs) {
        prefs.putLong("startAt",startAt).putLong("endAt",endAt).putString("rangeLevel",level());
    }
    void show(boolean visible) { box.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE); }
}
