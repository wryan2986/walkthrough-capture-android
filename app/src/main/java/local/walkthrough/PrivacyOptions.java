package local.walkthrough;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Local privacy-mask configuration. Changes are kept in a draft until Apply. */
final class PrivacyOptions {
    private final Activity activity;
    private final List<Runnable> summaries=new ArrayList<>();

    PrivacyOptions(Activity activity) { this.activity=activity; }

    void add(LinearLayout parent,boolean conversation) {
        Ui.Choice button=new Ui.Choice(activity,"android.widget.Button");
        button.setId(conversation?R.id.chat_privacy_masks:R.id.video_privacy_masks);
        button.setGravity(Gravity.CENTER_VERTICAL); button.setMinimumHeight(Ui.dp(activity,50));
        Ui.pad(button,12,7,12,7); button.setBackground(Ui.ripple(activity,Ui.SURFACE,Ui.LINE,12));
        button.addView(new Ui.Glyph(activity,"privacy",Ui.TEAL),Ui.lp(activity,21,21)); Ui.gap(button,10);
        button.addView(Ui.text(activity,"Privacy masks",15,Ui.INK,true),Ui.weight());
        TextView state=Ui.text(activity,"",13,Ui.MUTED,false); button.addView(state); Ui.gap(button,6);
        Ui.Glyph arrow=new Ui.Glyph(activity,"down",Ui.TEAL); arrow.setRotation(-90); button.addView(arrow,Ui.lp(activity,16,16));
        Runnable update=()-> {
            String summary;
            try {
                PrivacyMasks.Settings s=PrivacyMasks.Settings.read(activity);
                int n=s.regions.size(); boolean text=conversation && (s.emails || s.phones || !s.terms.isEmpty());
                summary=n==0?(text?"Text checks":"Off"):n+(n==1?" area":" areas")+(text?" + text":"");
            } catch(RuntimeException unreadable) { summary="Review settings"; }
            state.setText(summary); button.setContentDescription("Privacy masks, "+summary);
        };
        summaries.add(update); update.run();
        button.setOnClickListener(v->new Editor(conversation).show()); parent.addView(button,Ui.lp(activity,-1,-2));
    }
    private final class Editor {
        final boolean conversation;
        final PrivacyMasks.Settings saved;
        final boolean repairing;
        final ArrayList<RectF> areas=new ArrayList<>();
        int selected;
        MaskCanvas canvas;
        Spinner list;
        Button edit, remove, clear;
        CheckBox emails, phones;
        EditText terms;
        TextView sizeLabel, screenSize;
        final int shortSide,longSide;
        Ui.Choice portrait, landscape;
        boolean wide, changingList;
        Editor(boolean conversation) {
            this.conversation=conversation;
            PrivacyMasks.Settings existing; boolean invalid=false;
            try { existing=PrivacyMasks.Settings.read(activity); }
            catch(RuntimeException unreadable) {
                invalid=true; existing=new PrivacyMasks.Settings(new ArrayList<>(),false,false,new ArrayList<>());
            }
            saved=existing; repairing=invalid;
            android.graphics.Rect screen=activity.getWindowManager().getMaximumWindowMetrics().getBounds();
            shortSide=Math.max(1,Math.min(screen.width(),screen.height()));longSide=Math.max(shortSide,Math.max(screen.width(),screen.height()));
            for(RectF rect:saved.regions)areas.add(new RectF(rect));
            selected=areas.isEmpty()?-1:0;
            wide=activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        }

        void show() {
            LinearLayout form=Ui.column(activity); Ui.pad(form,17,10,17,8);
            ScrollView scroll=new ScrollView(activity); scroll.addView(form); scroll.setFillViewport(false);
            if(repairing){
                form.addView(Ui.text(activity,"Saved masks could not be read. Capture is paused until you review and apply these replacement settings.",15,Ui.INK,true));
                Ui.gap(form,12);
            }
            heading(form,"Fixed screen areas");
            form.addView(Ui.text(activity,"These areas are covered on every screen. Moving text can leave a mask.",14,Ui.MUTED,false));
            Ui.gap(form,9); LinearLayout orientation=Ui.row(activity); form.addView(orientation);
            portrait=orientation(orientation,"Portrait",false); Ui.gap(orientation,7); landscape=orientation(orientation,"Landscape",true);
            Ui.gap(form,8);
            canvas=new MaskCanvas(activity,this); canvas.setId(R.id.privacy_canvas);
            form.addView(canvas,Ui.lp(activity,-1,wide?184:247));
            Ui.gap(form,4); screenSize=Ui.text(activity,"",12,Ui.MUTED,true); form.addView(screenSize); Ui.gap(form,4);
            form.addView(Ui.text(activity,"Sample display, including system bars. Tap empty space to place a mask, or drag to draw one. Drag an existing area to move it or a corner to resize.",13,Ui.MUTED,false));
            Ui.gap(form,8);
            LinearLayout add=Ui.row(activity); form.addView(add);
            addButton(add,"Add area",()->coordinates(true)); Ui.gap(add,7);
            addButton(add,"Mask top 10%",()-> {
                RectF top=new RectF(0,0,1,.1f); int prior=areas.indexOf(top);
                if(prior>=0){selected=prior;changed();} else insert(top);
            });
            Ui.gap(form,10);
            sizeLabel=Ui.text(activity,"",14,Ui.INK,true); form.addView(sizeLabel);
            list=new Spinner(activity); list.setId(R.id.privacy_region); sizeLabel.setLabelFor(list.getId());
            list.setMinimumHeight(Ui.dp(activity,48)); form.addView(list,Ui.lp(activity,-1,-2));
            list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                public void onNothingSelected(AdapterView<?> p) {}
                public void onItemSelected(AdapterView<?> p,View v,int index,long id) {
                    if(!changingList && index>=0 && index<areas.size() && index==list.getSelectedItemPosition()){ selected=index; canvas.invalidate(); }
                }
            });
            LinearLayout actions=Ui.row(activity); form.addView(actions);
            edit=addButton(actions,"Edit area",()->coordinates(false)); Ui.gap(actions,5);
            remove=addButton(actions,"Remove",()-> {
                if(selected<0 || selected>=areas.size())return;
                areas.remove(selected); selected=Math.min(selected,areas.size()-1); changed();
            }); Ui.gap(actions,5);
            clear=addButton(actions,"Clear all",()-> { areas.clear(); selected=-1; changed(); });
            if(conversation || repairing) {
                Ui.gap(form,17); heading(form,"Text checks for screenshots");
                form.addView(Ui.text(activity,"Cover the entire readable text element when a match is found. Text inside images or missing from Android accessibility can remain visible.",14,Ui.MUTED,false));
                emails=check(form,"Email addresses",saved.emails);
                phones=check(form,"Phone numbers",saved.phones);
                TextView label=Ui.text(activity,"Additional exact terms",14,Ui.INK,true); form.addView(label);
                terms=new EditText(activity); terms.setId(R.id.privacy_terms); label.setLabelFor(terms.getId());
                terms.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                terms.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
                terms.setSaveEnabled(false); terms.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
                terms.setHint("One term per line"); terms.setMinLines(2); terms.setMaxLines(5);
                terms.setGravity(Gravity.TOP|Gravity.START); terms.setText(String.join("\n",saved.terms));
                form.addView(terms,Ui.lp(activity,-1,-2));
                form.addView(Ui.text(activity,"Matches ignore capitalization and may occur within a longer piece of text.",13,Ui.MUTED,false));
                Button clearTerms=Ui.button(activity,"Clear terms",false,()->terms.setText(""));
                form.addView(clearTerms,Ui.lp(activity,-1,-2));
            } else {
                Ui.gap(form,12);
                form.addView(Ui.text(activity,"Video masks stay at these fixed positions throughout the recording. Automatic text checks apply only to conversation screenshots.",14,Ui.MUTED,false));
            }
            form.setFocusableInTouchMode(true); form.requestFocus();
            changed(); markOrientation();
            AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Privacy masks").setView(scroll)
                    .setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                LinkedHashSet<String> words=new LinkedHashSet<>();
                if(conversation || repairing)for(String s:terms.getText().toString().split("\\R")){s=s.trim(); if(!s.isEmpty())words.add(s);}
                else words.addAll(saved.terms);
                try {
                    new PrivacyMasks.Settings(areas,emails!=null?emails.isChecked():saved.emails,
                            phones!=null?phones.isChecked():saved.phones,new ArrayList<>(words)).save(activity);
                } catch(IllegalArgumentException invalid) {
                    Toast.makeText(activity,invalid.getMessage(),Toast.LENGTH_LONG).show(); return;
                }
                for(Runnable update:summaries)update.run();
                dialog.dismiss();
            });
        }

        Ui.Choice orientation(LinearLayout parent,String label,boolean horizontal) {
            Ui.Choice tab=new Ui.Choice(activity,"android.widget.RadioButton"); tab.setMinimumHeight(Ui.dp(activity,44));
            tab.setGravity(Gravity.CENTER); tab.addView(Ui.text(activity,label,14,Ui.TEAL,true)); tab.setContentDescription(label+" sample display");
            tab.setOnClickListener(v->{wide=horizontal; markOrientation();
                canvas.setLayoutParams(Ui.lp(activity,-1,wide?184:247));canvas.invalidate();});
            parent.addView(tab,Ui.weight()); return tab;
        }
        void markOrientation(){
            screenSize.setText("Full display · "+(wide?longSide:shortSide)+" × "+(wide?shortSide:longSide)+" px");
            portrait.mark(!wide); landscape.mark(wide);
            portrait.setBackground(Ui.ripple(activity,wide?Ui.SURFACE:Ui.PALE,wide?Ui.LINE:Ui.TEAL,11));
            landscape.setBackground(Ui.ripple(activity,wide?Ui.PALE:Ui.SURFACE,wide?Ui.TEAL:Ui.LINE,11));
        }
        void insert(RectF region) { areas.add(region);selected=areas.size()-1;changed(); }
        void changed() {
            if(canvas==null || list==null)return;
            changingList=true;
            ArrayList<String> labels=new ArrayList<>();
            for(int i=0;i<areas.size();i++)labels.add("Area "+(i+1)+" · "+percent(areas.get(i).width())+" × "+percent(areas.get(i).height()));
            ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); list.setAdapter(adapter);
            if(selected>=0)list.setSelection(selected);
            changingList=false;sizeLabel.setText(areas.isEmpty()?"No fixed areas":areas.size()+(areas.size()==1?" fixed area":" fixed areas"));
            list.setVisibility(areas.isEmpty()?View.GONE:View.VISIBLE);
            edit.setEnabled(selected>=0); remove.setEnabled(selected>=0); clear.setEnabled(!areas.isEmpty()); canvas.invalidate();
        }
        void coordinates(boolean create) {
            if(!create && (selected<0 || selected>=areas.size()))return;
            RectF initial=create?new RectF(.15f,.4f,.85f,.55f):areas.get(selected);
            LinearLayout form=Ui.column(activity);Ui.pad(form,20,10,20,4);
            form.addView(Ui.text(activity,"Enter percentages of the full display.",14,Ui.MUTED,false)); Ui.gap(form,6);
            String[] names={"Left","Top","Right","Bottom"};
            float[] values={initial.left,initial.top,initial.right,initial.bottom};
            EditText[] fields=new EditText[4];LinearLayout row=null;
            for(int i=0;i<4;i++) {
                if(i%2==0) { row=Ui.row(activity);form.addView(row); }else Ui.gap(row,8);
                LinearLayout group=Ui.column(activity);row.addView(group,Ui.weight());
                TextView label=Ui.text(activity,names[i]+" (%)",13,Ui.INK,true);group.addView(label);
                EditText input=new EditText(activity); input.setSingleLine(true);input.setId(View.generateViewId());
                input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
                input.setText(String.format(Locale.ROOT,"%.2f",100*values[i]));input.setSelectAllOnFocus(true);label.setLabelFor(input.getId());group.addView(input);fields[i]=input;
            }
            ScrollView sc=new ScrollView(activity);sc.addView(form);
            AlertDialog d=new AlertDialog.Builder(activity).setTitle(create?"Add fixed area":"Edit fixed area")
                    .setView(sc).setNegativeButton("Cancel",null).setPositiveButton(create?"Add":"Update",null).create();
            d.show(); d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try {
                    float[] edges=new float[4];
                    for(int i=0;i<4;i++){
                        edges[i]=Float.parseFloat(fields[i].getText().toString().replace(',','.'))/100f;
                        if(!Float.isFinite(edges[i]) || edges[i]<0 || edges[i]>1)throw new NumberFormatException();
                    }
                    RectF r=new RectF(edges[0],edges[1],edges[2],edges[3]);
                    if(r.isEmpty())throw new NumberFormatException();
                    if(create)insert(r);else {areas.set(selected,r);changed();}
                    d.dismiss();
                } catch(NumberFormatException invalid){Toast.makeText(activity,"Use 0–100%. Right must exceed left, and bottom must exceed top.",Toast.LENGTH_LONG).show();}
            });
        }
    }
    private static String percent(float v){return Math.round(v*100)+"%";}
    private void heading(LinearLayout p,String s){TextView t=Ui.text(activity,s,18,Ui.INK,true);t.setAccessibilityHeading(true);p.addView(t);Ui.gap(p,5);}
    private Button addButton(LinearLayout p,String s,Runnable r){Button b=Ui.button(activity,s,false,r);Ui.pad(b,4,7,4,7);b.setMinWidth(0);p.addView(b,Ui.weight());return b;}
    private CheckBox check(LinearLayout p,String s,boolean checked){CheckBox b=new CheckBox(activity);b.setText(s);b.setChecked(checked);b.setMinHeight(Ui.dp(activity,47));b.setButtonTintList(ColorStateList.valueOf(Ui.TEAL));p.addView(b);return b;}

    private static final class MaskCanvas extends View {
        final Editor editor; final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); final RectF display=new RectF();
        float sx,sy; RectF before, draft;
        int corner=-1; boolean adding;
        MaskCanvas(Context c,Editor editor) {
            super(c);this.editor=editor;setFocusable(false);
            setContentDescription("Sample display. Add area and Edit area provide the same controls using percentage coordinates.");
        }
        private float density(){return getResources().getDisplayMetrics().density;}
        @Override protected void onDraw(Canvas c){
            float d=density(), pad=6*d, ratio=editor.wide?(float)editor.longSide/editor.shortSide:(float)editor.shortSide/editor.longSide;
            float w=Math.min(getWidth()-2*pad,(getHeight()-2*pad)*ratio), h=w/ratio;
            float l=(getWidth()-w)/2,t=(getHeight()-h)/2;display.set(l,t,l+w,t+h);
            c.drawColor(0xffe4ece7);p.setColor(Ui.HEADER);c.drawRect(display,p);
            c.save();c.translate(l,t);c.scale(w,h);p.setColor(0xfff6f9f7);c.drawRect(0,.07f,1,.96f,p);
            p.setColor(0xffc1d6c9);c.drawRect(.06f,.105f,.56f,.133f,p);
            p.setColor(0xffd8e3dc);c.drawRoundRect(.06f,.19f,.74f,.31f,.025f,.025f,p);
            p.setColor(0xffb3d8c6);c.drawRoundRect(.28f,.365f,.94f,.50f,.025f,.025f,p);
            p.setColor(0xffd8e3dc);c.drawRoundRect(.06f,.57f,.77f,.72f,.025f,.025f,p);
            p.setColor(0xffbecdc4);c.drawRoundRect(.06f,.83f,.94f,.91f,.025f,.025f,p);
            p.setColor(0xfff4faf6);c.drawRect(.045f,.029f,.155f,.041f,p);c.drawRect(.84f,.029f,.95f,.041f,p);
            c.drawRect(.38f,.978f,.62f,.989f,p);c.restore();
            for(int i=0;i<editor.areas.size();i++)drawMask(c,editor.areas.get(i),i==editor.selected);
            if(draft!=null)drawMask(c,draft,true);
        }
        void drawMask(Canvas c,RectF r,boolean active) {
            RectF b=new RectF(display.left+r.left*display.width(),display.top+r.top*display.height(),
                    display.left+r.right*display.width(),display.top+r.bottom*display.height());
            p.setStyle(Paint.Style.FILL);p.setColor(0xff000000);c.drawRect(b,p);
            if(active) {
                p.setColor(0xffffca59);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.4f*density());c.drawRect(b,p);
                p.setStyle(Paint.Style.FILL);
                for(float x:new float[]{b.left,b.right})for(float y:new float[]{b.top,b.bottom})c.drawCircle(x,y,2.5f*density(),p);
            }
        }
        float bound(float v){return Math.max(0,Math.min(1,v));}
        int handle(RectF r,float x,float y){
            float rx=10*density()/display.width(),ry=10*density()/display.height();
            for(int i=0;i<4;i++)if(Math.abs(x-((i&1)==0?r.left:r.right))<=rx && Math.abs(y-(i<2?r.top:r.bottom))<=ry)return i;
            return -1;
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if(display.isEmpty())return false;
            float x=bound((e.getX()-display.left)/display.width()), y=bound((e.getY()-display.top)/display.height());
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    if(!display.contains(e.getX(),e.getY()))return false;
                    getParent().requestDisallowInterceptTouchEvent(true); sx=x;sy=y;before=null;draft=null;corner=-1;adding=false;
                    if(editor.selected>=0)corner=handle(editor.areas.get(editor.selected),x,y);
                    if(corner<0){
                        editor.selected=-1;
                        for(int i=editor.areas.size()-1;i>=0;i--)if(editor.areas.get(i).contains(x,y)){editor.selected=i;break;}
                    }
                    if(editor.selected>=0)before=new RectF(editor.areas.get(editor.selected));else adding=true;
                    invalidate();return true;
                case MotionEvent.ACTION_MOVE:
                    if(adding)draft=new RectF(Math.min(sx,x),Math.min(sy,y),Math.max(sx,x),Math.max(sy,y));
                    else if(before!=null && editor.selected>=0){
                        RectF result=new RectF(before);
                        if(corner>=0){
                            if((corner&1)==0)result.left=bound(Math.min(x,result.right-.001f));else result.right=bound(Math.max(x,result.left+.001f));
                            if(corner<2)result.top=bound(Math.min(y,result.bottom-.001f));else result.bottom=bound(Math.max(y,result.top+.001f));
                        }else result.offset(Math.max(-before.left,Math.min(1-before.right,x-sx)),Math.max(-before.top,Math.min(1-before.bottom,y-sy)));
                        editor.areas.set(editor.selected,result);
                    }
                    invalidate();return true;
                case MotionEvent.ACTION_UP:
                    if(adding) {
                        if(draft!=null && draft.width()*display.width()>3 && draft.height()*display.height()>3)editor.insert(draft);
                        else {
                            float left=Math.max(0,Math.min(.70f,sx-.15f)),top=Math.max(0,Math.min(.90f,sy-.05f));
                            editor.insert(new RectF(left,top,left+.3f,top+.1f));
                        }
                    } else editor.changed();
                    adding=false;draft=null;before=null;getParent().requestDisallowInterceptTouchEvent(false);performClick();invalidate();return true;
                case MotionEvent.ACTION_CANCEL:
                    if(before!=null && editor.selected>=0)editor.areas.set(editor.selected,before);
                    before=null;draft=null;adding=false;getParent().requestDisallowInterceptTouchEvent(false);editor.changed();return true;
            }return false;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
