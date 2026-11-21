package local.walkthrough;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** Full-display, noninteractive highlight for a row verified by the capture service. */
final class TapHighlight {
    enum Style {
        OUTLINE("Focus outline","A teal border frames the row before it opens."),
        SPOTLIGHT("Spotlight","The row stays bright while the rest of the screen dims."),
        POINTER("Pointer","A gold arrow points to the edge of the next row."),
        OFF("Off","The row opens without a visual cue.");
        final String label;
        final String description;
        Style(String label,String description) { this.label=label; this.description=description; }
    }
    static final class Settings {
        private static final String STYLE="tapHighlightStyle", SECONDS="tapHighlightSeconds";
        final Style style;
        final int dwellMs;
        Settings(Style style,int seconds) {
            this.style=style; dwellMs=Math.max(1,Math.min(10,seconds))*1000;
        }
        boolean enabled() { return style!=Style.OFF; }
        static Settings read(Context context) {
            SharedPreferences p=context.getSharedPreferences("profile",Context.MODE_PRIVATE);
            Style style;
            try { style=Style.valueOf(p.getString(STYLE,Style.OUTLINE.name())); }
            catch(IllegalArgumentException ex) { style=Style.OUTLINE; }
            return new Settings(style,p.getInt(SECONDS,2));
        }
        void save(Context c) {
            c.getSharedPreferences("profile",Context.MODE_PRIVATE).edit()
                    .putString(STYLE,style.name()).putInt(SECONDS,dwellMs/1000).apply();
        }
    }

    private final AccessibilityService service;
    private View surface;
    private WindowManager windows;
    private long paintedAt;
    long paintedAt() { return paintedAt; }

    TapHighlight(AccessibilityService service) { this.service=service; }

    boolean show(Rect bounds,Settings settings) {
        clear();
        if(!settings.enabled() || bounds==null || bounds.isEmpty())return false;
        final Rect screenRow=new Rect(bounds);
        View layer=new View(service) {
            private final int[] origin=new int[2];
            @Override protected void onDraw(Canvas canvas) {
                if(surface!=this)return;
                getLocationOnScreen(origin); RectF local=new RectF(screenRow);
                local.offset(-origin[0],-origin[1]);
                drawMark(canvas,new RectF(0,0,getWidth(),getHeight()),local,
                        getResources().getDisplayMetrics().density,settings.style);
                if(paintedAt==0)paintedAt=android.os.SystemClock.elapsedRealtime();
            }
        };
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        layer.setFocusable(false); layer.setClickable(false); layer.setBackgroundColor(Color.TRANSPARENT);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;
        p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("Walkthrough tap preview");
        windows=service.getSystemService(WindowManager.class);
        try {
            windows.addView(layer,p); surface=layer; return true;
        } catch(RuntimeException unavailable) {
            clear(); return false;
        }
    }
    void clear() {
        View old=surface; surface=null; paintedAt=0;
        if(old!=null && windows!=null) {
            try { windows.removeViewImmediate(old); } catch(IllegalArgumentException alreadyRemoved) { }
        }
        windows=null;
    }

    /** Paint a marked full-display PNG from the unobstructed native screenshot. Caller owns
     * the mutable bitmap. No views, window handles or other UI state are touched here. */
    void drawOn(Bitmap bitmap,Rect rowOnDisplay,Settings settings) {
        if(!settings.enabled())return;
        Canvas canvas=new Canvas(bitmap);
        drawMark(canvas,new RectF(0,0,bitmap.getWidth(),bitmap.getHeight()),new RectF(rowOnDisplay),
                service.getResources().getDisplayMetrics().density,settings.style);
    }

    static final int ACCENT=0xffffcb56, DARK=0xff162522, FOCUS=0xff008d7b;
    /** Draw using the same physical scale for the on-screen overlay and native style samples. */
    static void drawMark(Canvas canvas,RectF viewport,RectF target,float density,Style style) {
        if(style==Style.OFF || viewport.isEmpty() || target.isEmpty())return;
        RectF r=new RectF(target);
        if(!r.intersect(viewport))return;
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setStrokeJoin(Paint.Join.ROUND);
        float thickness=Math.min((style==Style.OUTLINE?6.5f:5f)*density,Math.min(r.width(),r.height())/4f);
        float radius=Math.min(10*density,Math.min(r.width(),r.height())/3f);
        canvas.save(); canvas.clipRect(viewport);
        if(style==Style.OUTLINE || style==Style.SPOTLIGHT) {
            // Protect every original row pixel, including text flush against an edge.
            // At a display edge the outside stroke is naturally clipped; the remaining
            // horizontal or vertical edges still indicate the row without hiding text.
            canvas.clipOutRect(r);
            float stroke=style==Style.OUTLINE?thickness:1.5f*density;
            float outside=1.5f*density+stroke/2;
            r.inset(-outside,-outside);
        }
        if(style==Style.SPOTLIGHT) {
            Path shade=new Path(); shade.setFillType(Path.FillType.EVEN_ODD);
            shade.addRect(viewport,Path.Direction.CW); shade.addRoundRect(r,radius,radius,Path.Direction.CW);
            p.setColor(0xa8000000); canvas.drawPath(shade,p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f*density); p.setColor(ACCENT);
            canvas.drawRoundRect(r,radius,radius,p);
        } else if(style==Style.OUTLINE) {
            p.setStyle(Paint.Style.STROKE); p.setColor(0xff164e46); p.setStrokeWidth(thickness);
            canvas.drawRoundRect(r,radius,radius,p);
            p.setColor(0xffecfff9); p.setStrokeWidth(thickness*.82f); canvas.drawRoundRect(r,radius,radius,p);
            p.setColor(FOCUS); p.setStrokeWidth(thickness*.49f); canvas.drawRoundRect(r,radius,radius,p);
        } else {
            // Keep the point on the trailing edge and the pointer body outside the row when space allows.
            float unit=Math.min(density,Math.min(viewport.width()/70f,viewport.height()/62f));
            boolean above=r.top-viewport.top>=28*unit;
            boolean below=viewport.bottom-r.bottom>=28*unit;
            float tipX=Math.max(viewport.left+20*unit,Math.min(r.right-16*unit,viewport.right-23*unit));
            float tipY=above?r.top:below?r.bottom:Math.min(r.bottom,r.top+5*unit);
            float sign=above?-1f:1f;
            canvas.translate(tipX,tipY); canvas.scale(unit,unit*sign);
            Path cursor=new Path(); cursor.moveTo(0,0); cursor.lineTo(2,22); cursor.lineTo(7,17);
            cursor.lineTo(13,26); cursor.lineTo(18,23); cursor.lineTo(12,14); cursor.lineTo(19,12); cursor.close();
            p.setStyle(Paint.Style.STROKE); p.setColor(Color.WHITE); p.setStrokeWidth(5); canvas.drawPath(cursor,p);
            p.setColor(DARK); p.setStrokeWidth(2.5f); canvas.drawPath(cursor,p);
            p.setStyle(Paint.Style.FILL); p.setColor(ACCENT); canvas.drawPath(cursor,p);
        }
        canvas.restore();
    }
}
