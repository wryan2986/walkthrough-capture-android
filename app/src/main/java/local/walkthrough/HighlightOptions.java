package local.walkthrough;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.EnumMap;

final class HighlightOptions {
    private final Activity activity;
    private final TextView value;
    private final Ui.Choice control;
    private TapHighlight.Settings stored;

    HighlightOptions(Activity activity,LinearLayout parent) {
        this.activity=activity; stored=TapHighlight.Settings.read(activity);
        control=new Ui.Choice(activity,"android.widget.Button"); control.setGravity(Gravity.CENTER_VERTICAL);
        control.setId(R.id.tap_highlight); Ui.pad(control,12,8,12,8); control.setMinimumHeight(Ui.dp(activity,50));
        control.setBackground(Ui.ripple(activity,Ui.SURFACE,Ui.LINE,12));
        control.addView(new Ui.Glyph(activity,"capture",Ui.TEAL),Ui.lp(activity,21,21)); Ui.gap(control,10);
        control.addView(Ui.text(activity,"Tap highlight",15,Ui.INK,true),Ui.weight());
        value=Ui.text(activity,"",13,Ui.MUTED,false); control.addView(value); Ui.gap(control,6);
        Ui.Glyph arrow=new Ui.Glyph(activity,"down",Ui.TEAL); arrow.setRotation(-90); control.addView(arrow,Ui.lp(activity,16,16));
        control.setOnClickListener(v->edit()); parent.addView(control,Ui.lp(activity,-1,-2)); update();
    }
    private void update() {
        String label=stored.style.label+(stored.enabled()?" · "+stored.dwellMs/1000+" s":"");
        value.setText(label); control.setContentDescription("Tap highlight, "+label);
    }
    private void edit() {
        LinearLayout panel=Ui.column(activity);
        Ui.pad(panel,14,8,14,6);
        ScrollView scroll=new ScrollView(activity);
        scroll.addView(panel);
        panel.addView(Ui.text(activity,"The video shows the selected activity or step just before it opens.",15,Ui.MUTED,false));
        Ui.gap(panel,10);
        StylePreview preview=new StylePreview(activity,true);
        preview.setId(R.id.highlight_sample);
        panel.addView(preview,Ui.lp(activity,-1,-2));
        Ui.gap(panel,10);

        TapHighlight.Style[] choice={stored.style};
        Spinner duration=new Spinner(activity);
        duration.setId(R.id.highlight_duration);
        duration.setMinimumHeight(Ui.dp(activity,48));
        String[] times=new String[10];
        for(int i=0;i<times.length;i++) times[i]=(i+1)+(i==0?" second":" seconds");
        ArrayAdapter<String> options=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,times);
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        duration.setAdapter(options);
        duration.setSelection(stored.dwellMs/1000-1);
        EnumMap<TapHighlight.Style,StylePreview.Card> cards=new EnumMap<>(TapHighlight.Style.class);
        Runnable render=()->{
            for(TapHighlight.Style style:cards.keySet()) cards.get(style).select(choice[0]==style);
            duration.setEnabled(choice[0]!=TapHighlight.Style.OFF);
            preview.select(choice[0],duration.getSelectedItemPosition()+1);
        };
        for(TapHighlight.Style style:TapHighlight.Style.values()) {
            StylePreview.Card card=new StylePreview.Card(activity,style.label,style.description,style,null);
            cards.put(style,card);
            panel.addView(card,Ui.lp(activity,-1,-2));
            Ui.gap(panel,5);
            card.setOnClickListener(v->{choice[0]=style;render.run();});
        }
        TextView label=Ui.text(activity,"Preview before tap",15,Ui.INK,true);
        label.setLabelFor(duration.getId());
        Ui.gap(panel,4);
        panel.addView(label);
        panel.addView(duration,Ui.lp(activity,-1,-2));
        duration.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,View view,int i,long id) {
                preview.select(choice[0],i+1);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        render.run();
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Tap highlight").setView(scroll).setNegativeButton("Cancel",null)
                .setPositiveButton("Apply",(confirmed,which)-> {
                    stored=new TapHighlight.Settings(choice[0],duration.getSelectedItemPosition()+1);
                    stored.save(activity); update();
                }).show();
        StylePreview.fitDialog(dialog);
    }
}
