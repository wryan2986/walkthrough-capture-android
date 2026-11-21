package local.walkthrough;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.List;
import java.util.Set;

/** A single review of the discovered rows. Selection belongs to row identities, not recycled views. */
final class CaptureReview {
    final LinearLayout view, footer;
    private final Activity activity;
    private final RangeControls range;
    private final List<RangeWalk.Entry> entries;
    private final Set<String> checked;
    private final Button toggleAll, submit;
    private final TextView count;
    private final ListView list;
    private final BaseAdapter rows;

    CaptureReview(Activity activity,RangeControls range,List<RangeWalk.Entry> entries,Set<String> checked,
                  Runnable start) {
        this.activity=activity; this.range=range; this.entries=entries; this.checked=checked;
        view=Ui.column(activity); view.setBackgroundColor(Ui.PAPER);
        LinearLayout intro=Ui.column(activity); Ui.pad(intro,20,18,20,6); view.addView(intro);
        TextView title=Ui.text(activity,"Choose trajectories",27,Ui.INK,true);
        title.setAccessibilityHeading(true); intro.addView(title);
        Ui.gap(intro,5);
        intro.addView(Ui.text(activity,"Selected trajectories run from top to bottom in this order.",14,Ui.MUTED,false));
        LinearLayout tools=Ui.row(activity); intro.addView(tools);
        count=Ui.text(activity,"",14,Ui.INK,true); tools.addView(count,Ui.weight());
        toggleAll=textButton("Select all",this::toggleAll);
        tools.addView(toggleAll); toggleAll.setId(R.id.review_all); count.setId(R.id.review_count);
        list=new ListView(activity); list.setDivider(null); list.setCacheColorHint(0); list.setFastScrollEnabled(true);
        list.setClipToPadding(false); Ui.pad(list,20,0,20,8);
        list.setVerticalScrollBarEnabled(false); list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        rows=new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Object getItem(int pos) { return entries.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos,View recycled,ViewGroup parent) {
                Holder holder = recycled==null ? new Holder() : (Holder)recycled.getTag();
                holder.bind(entries.get(pos)); return holder.outer;
            }
        };
        list.setAdapter(rows); list.setId(R.id.review_list);
        view.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        footer=Ui.column(activity); footer.setBackgroundColor(Ui.SURFACE);
        View border=new View(activity); border.setBackgroundColor(Ui.LINE); footer.addView(border,Ui.lp(activity,-1,1));
        LinearLayout actions=Ui.column(activity); Ui.pad(actions,20,10,20,10); footer.addView(actions);
        submit=Ui.button(activity,"",true,start); submit.setId(R.id.review_start); actions.addView(submit,Ui.lp(activity,-1,-2));
        view.addView(footer); update();
    }

    private Button textButton(String label,Runnable action) {
        Button b=Ui.button(activity,label,false,action);
        b.setBackground(Ui.ripple(activity,Ui.PAPER,0,12)); b.setMinWidth(0);
        Ui.pad(b,12,8,4,8); return b;
    }
    private void toggleAll() {
        boolean all=checked.size()==entries.size();
        checked.clear();
        if(!all) for(RangeWalk.Entry item:entries)checked.add(item.key);
        rows.notifyDataSetChanged(); update();
    }
    private void update() {
        int n=checked.size();
        count.setText(n+" of "+entries.size()+" selected");
        toggleAll.setText(n==entries.size()?"Clear all":"Select all");
        submit.setText("Record "+n+" selected"); Ui.enable(submit,n>0);
    }
    Parcelable scrollState() { return list.onSaveInstanceState(); }
    void restore(Parcelable state) { if(state!=null)list.onRestoreInstanceState(state); }

    private final class Holder {
        final LinearLayout outer=Ui.column(activity);
        final Ui.Choice row=new Ui.Choice(activity,"android.widget.CheckBox");
        final TextView time=Ui.text(activity,"",12,Ui.TEAL,true), title=Ui.text(activity,"",17,Ui.INK,true),
                description=Ui.text(activity,"",14,Ui.MUTED,false);
        final CheckBox tick=new CheckBox(activity);
        RangeWalk.Entry item;
        Holder() {
            outer.setTag(this); row.setId(R.id.review_row); tick.setId(R.id.review_checkbox); title.setId(R.id.review_title); Ui.pad(outer,0,0,0,8);
            row.setGravity(Gravity.TOP); row.setOrientation(LinearLayout.HORIZONTAL); Ui.pad(row,14,12,10,12);
            row.setMinimumHeight(Ui.dp(activity,66)); outer.addView(row,Ui.lp(activity,-1,-2));
            LinearLayout copy=Ui.column(activity); copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            row.addView(copy,Ui.weight()); copy.addView(time); Ui.gap(copy,5); copy.addView(title);
            description.setPadding(0,Ui.dp(activity,4),0,0); copy.addView(description); Ui.gap(row,8);
            tick.setClickable(false); tick.setFocusable(false); tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tick.setButtonTintList(ColorStateList.valueOf(Ui.TEAL)); tick.setMinWidth(0); tick.setMinHeight(0);
            row.addView(tick,Ui.lp(activity,32,32));
            row.setOnClickListener(v->{
                if(!checked.add(item.key))checked.remove(item.key);
                bind(item); update();
            });
        }
        void bind(RangeWalk.Entry entry) {
            item=entry; boolean selected=checked.contains(entry.key);
            time.setText(range.display(entry.timeMs)); title.setText(entry.label);
            description.setText(entry.summary); description.setVisibility(entry.summary.isEmpty()?View.GONE:View.VISIBLE);
            row.mark(selected); tick.setChecked(selected);
            row.setBackground(Ui.ripple(activity,selected?Ui.PALE:Ui.SURFACE,selected?Ui.TEAL:Ui.LINE,14));
            row.setContentDescription(time.getText()+", "+entry.label+(entry.summary.isEmpty()?"":", "+entry.summary));
        }
    }
}
