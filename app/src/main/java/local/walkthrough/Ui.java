package local.walkthrough;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared presentation for the capture setup, review and library screens. */
final class Ui {
    static final int PAPER=0xfff6f8f6, SURFACE=0xffffffff, INK=0xff182e2b, MUTED=0xff506762,
            TEAL=0xff116553, PALE=0xffe5f2ec, LINE=0xffcedcd5, HEADER=0xff103e35, HEADER_TEXT=0xffd5ede3;
    static final int FULL=-1, WRAP=-2;

    static int dp(Context c, int n) { return Math.round(c.getResources().getDisplayMetrics().density*n); }
    static LinearLayout.LayoutParams lp(Context c,int w,int h) {
        return new LinearLayout.LayoutParams(w>=0?dp(c,w):w,h>=0?dp(c,h):h);
    }
    static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, WRAP,1); }
    static LinearLayout column(Context c) {
        LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    static LinearLayout row(Context c) { LinearLayout l=new LinearLayout(c); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static void pad(View v,int left,int top,int right,int bottom) {
        Context c=v.getContext(); v.setPaddingRelative(dp(c,left),dp(c,top),dp(c,right),dp(c,bottom));
    }
    static void gap(LinearLayout parent,int n) {
        boolean row=parent.getOrientation()==LinearLayout.HORIZONTAL;
        View space=new View(parent.getContext()); space.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(space,lp(parent.getContext(),row?n:1,row?1:n));
    }
    static TextView text(Context c, String label, int size, int color, boolean medium) {
        TextView v=new TextView(c); v.setText(label); v.setTextSize(size); v.setTextColor(color);
        v.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        v.setIncludeFontPadding(false); return v;
    }
    static GradientDrawable shape(Context c,int fill,int stroke,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(c,radius));
        if(stroke!=Color.TRANSPARENT)d.setStroke(dp(c,1),stroke);
        return d;
    }
    static RippleDrawable ripple(Context c,int fill,int stroke,int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x24196851),shape(c,fill,stroke,radius),null);
    }
    static Button button(Context c,String label,boolean primary,Runnable action) {
        Button b=new Button(c); b.setAllCaps(false); b.setText(label); b.setTextSize(15);
        b.setLetterSpacing(0); b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        int[][] states={{-android.R.attr.state_enabled},{}};
        b.setTextColor(new ColorStateList(states,new int[]{primary?0xffe2ebe6:0xff7b8882,primary?SURFACE:TEAL}));
        b.setBackgroundTintList(null); b.setBackground(ripple(c,primary?TEAL:SURFACE,primary?TEAL:LINE,12));
        b.setStateListAnimator(null); b.setMinHeight(dp(c,48)); pad(b,16,11,16,11);
        b.setOnClickListener(v->action.run()); return b;
    }
    static void enable(Button b,boolean enabled) {
        b.setEnabled(enabled); b.setAlpha(enabled?1f:.57f);
    }

    /** One focusable target for a visual row containing several pieces of text. */
    static class Choice extends LinearLayout {
        private final String role;
        boolean marked;
        Choice(Context c,String role) {
            super(c); this.role=role; setClickable(true); setFocusable(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        void mark(boolean b) { marked=b; setSelected(b); }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(role);
            if(role.endsWith("RadioButton") || role.endsWith("CheckBox")){
                info.setCheckable(true); info.setChecked(marked);
            }
        }
    }

    /** Platform-drawn line icons keep controls legible on different display densities. */
    static final class Glyph extends View {
        final String symbol;
        private int color;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(Context c,String symbol,int color) {
            super(c); this.symbol=symbol; this.color=color; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void tint(int color) { this.color=color; invalidate(); }
        private void line(Canvas c,float... points) {
            Path path=new Path(); path.moveTo(points[0],points[1]);
            for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);
            c.drawPath(path,p);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); float size=Math.min(getWidth(),getHeight());
            c.save(); c.translate((getWidth()-size)/2,(getHeight()-size)/2); c.scale(size/24,size/24);
            p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.7f);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            switch(symbol) {
                case "video": c.drawRoundRect(3,5,16,19,3,3,p); line(c,16,10,21,7,21,17,16,14); break;
                case "photos": c.drawRoundRect(6,3,21,17,2,2,p); line(c,3,7,3,19,4,21,16,21); line(c,7,15,11,11,14,14,17,10,20,13); c.drawCircle(10.2f,7.5f,.8f,p); break;
                case "privacy": line(c,12,3,20,6,20,12,18,16,15,19,12,21,9,19,6,16,4,12,4,6,12,3); c.drawRoundRect(8,9,16,14,1.5f,1.5f,p); break;
                case "capture": line(c,9,4,5,4,4,5,4,9); line(c,15,4,19,4,20,5,20,9); line(c,4,15,4,19,5,20,9,20); line(c,15,20,19,20,20,19,20,15); c.drawCircle(12,12,3,p); break;
                case "library": c.drawRoundRect(4,7,20,20,2,2,p); line(c,7,4,17,4); line(c,9,12,15,12); break;
                case "back": line(c,14,5,7,12,14,19); break;
                case "expand": line(c,7,17,18,6); line(c,9,6,18,6,18,15); break;
                case "down": line(c,6,9,12,15,18,9); break;
                case "check": line(c,5,12,10,17,19,7); break;
                case "clock": c.drawCircle(12,12,8.5f,p); line(c,12,7,12,12,15,14); break;
                case "settings": line(c,4,7,10,7); line(c,14,7,20,7); c.drawCircle(12,7,2,p); line(c,4,17,13,17); line(c,17,17,20,17); c.drawCircle(15,17,2,p); break;
            }
            c.restore();
        }
    }
    private Ui() {}
}
