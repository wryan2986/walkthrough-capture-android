package local.walkthrough;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import java.util.EnumMap;
import android.widget.ScrollView;
import android.widget.TextView;

final class FeedbackOptions {
    enum Style {
        EDGE("Edge pulse","A teal border flashes. The saved count appears near the bottom."),
        CHIP("Save chip","A checkmark and count briefly appear at the top center."),
        COUNTER("Live counter","The count stays at the top right between screenshots."),
        OFF("Off","Screenshots save with the display clear.");
        final String label;
        final String description;
        Style(String label,String description) { this.label=label; this.description=description; }
    }
    static final class Settings {
        final Style style;
        final boolean haptic;
        Settings(Style style,boolean haptic) { this.style=style; this.haptic=haptic; }
        static Settings read(Context context) {
            SharedPreferences p=context.getSharedPreferences("profile",Context.MODE_PRIVATE);
            Style style;
            try { style=Style.valueOf(p.getString("screenshotFeedback",Style.EDGE.name())); }
            catch(IllegalArgumentException | ClassCastException error) { style=Style.EDGE; }
            return new Settings(style,p.getBoolean("screenshotFeedbackHaptic",false));
        }
        void save(Context c) {
            c.getSharedPreferences("profile",Context.MODE_PRIVATE).edit()
                    .putString("screenshotFeedback",style.name()).putBoolean("screenshotFeedbackHaptic",haptic).apply();
        }
    }
    private final Activity activity;
    private final TextView value;
    private final Ui.Choice control;
    private Settings saved;

    FeedbackOptions(Activity a,LinearLayout parent) {
        activity=a; saved=Settings.read(a);
        control=new Ui.Choice(a,"android.widget.Button"); control.setGravity(Gravity.CENTER_VERTICAL);
        control.setId(R.id.screenshot_feedback);control.setMinimumHeight(Ui.dp(a,50));Ui.pad(control,12,8,12,8);
        control.setBackground(Ui.ripple(a,Ui.SURFACE,Ui.LINE,12));
        control.addView(new Ui.Glyph(a,"check",Ui.TEAL),Ui.lp(a,21,21));Ui.gap(control,10);
        control.addView(Ui.text(a,"Screenshot feedback",15,Ui.INK,true),Ui.weight());
        value=Ui.text(a,"",13,Ui.MUTED,false);control.addView(value);Ui.gap(control,6);
        Ui.Glyph arrow=new Ui.Glyph(a,"down",Ui.TEAL);arrow.setRotation(-90);control.addView(arrow,Ui.lp(a,16,16));
        control.setOnClickListener(v->edit());parent.addView(control,Ui.lp(a,-1,-2));refresh();
    }
    private void refresh() {
        value.setText(saved.style.label);control.setContentDescription("Screenshot feedback, "+saved.style.label);
    }
    private void edit() {
        LinearLayout panel=Ui.column(activity);
        Ui.pad(panel,14,8,14,6);
        ScrollView scroll=new ScrollView(activity);
        scroll.addView(panel);
        panel.addView(Ui.text(activity,"Each PNG saves before the count changes. Indicators appear only on the live screen.",15,Ui.MUTED,false));
        Ui.gap(panel,10);
        StylePreview preview=new StylePreview(activity,false);
        preview.setId(R.id.screenshot_feedback_sample);
        panel.addView(preview,Ui.lp(activity,-1,-2));
        Ui.gap(panel,10);
        CheckBox haptic=new CheckBox(activity);
        haptic.setId(R.id.screenshot_feedback_haptic);
        haptic.setText("Haptic on save");
        haptic.setMinHeight(Ui.dp(activity,48));
        haptic.setChecked(saved.haptic);

        Style[] selected={saved.style};
        EnumMap<Style,StylePreview.Card> cards=new EnumMap<>(Style.class);
        Runnable render=()->{
            for(Style style:cards.keySet()) cards.get(style).select(selected[0]==style);
            haptic.setEnabled(selected[0]!=Style.OFF);
            preview.select(selected[0]);
        };
        for(Style style:Style.values()) {
            StylePreview.Card card=new StylePreview.Card(activity,style.label,style.description,null,style);
            cards.put(style,card);
            panel.addView(card,Ui.lp(activity,-1,-2));
            Ui.gap(panel,5);
            card.setOnClickListener(v->{selected[0]=style;render.run();});
        }
        panel.addView(haptic);
        render.run();
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Screenshot feedback").setView(scroll)
                .setNegativeButton("Cancel",null).setPositiveButton("Apply",(dlg,which)->{
                    saved=new Settings(selected[0],haptic.isChecked());saved.save(activity);refresh();
                }).show();
        StylePreview.fitDialog(dialog);
    }
}
