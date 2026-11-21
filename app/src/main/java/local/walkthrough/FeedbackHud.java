package local.walkthrough;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;

/** A noninteractive, live-only window. Disk commits supply its count. */
final class FeedbackHud {
    private final AccessibilityService service;
    private final FeedbackOptions.Settings settings;
    private final WindowManager windows;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable expire=this::clearForCapture;
    private final Runnable timeout=this::clearanceFailed;
    private Layer layer;
    private final Runnable detachFallback=()->detachAndWait(layer);
    private Runnable ready, unsafe;
    private boolean clearing, failed, closed;
    private int count;
    private long savedAt;

    FeedbackHud(AccessibilityService service) {
        this.service=service;settings=FeedbackOptions.Settings.read(service);windows=service.getSystemService(WindowManager.class);
    }
    void saved(int committed) {
        if(closed || settings.style==FeedbackOptions.Style.OFF || committed<=count)return;
        count=committed;savedAt=SystemClock.uptimeMillis();show();
        if(layer!=null && settings.haptic) {
            Layer visible=layer;
            visible.post(()->{
                if(!closed && visible==layer && !visible.blank && committed==count)
                    visible.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            });
        }
        main.removeCallbacks(expire);
        if(settings.style!=FeedbackOptions.Style.COUNTER)main.postDelayed(expire,settings.style==FeedbackOptions.Style.EDGE?560:710);
    }
    void betweenSamples() {
        if(count>0 && settings.style==FeedbackOptions.Style.COUNTER && !closed)show();
    }
    private void show() {
        if(clearing || failed || closed)return;
        if(layer!=null){layer.invalidate();return;}
        Layer view=new Layer();
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0);p.gravity=Gravity.TOP|Gravity.START;p.setTitle("Walkthrough screenshot feedback");
        try {windows.addView(view,p);layer=view;view.invalidate();}
        catch(RuntimeException unavailable) { /* Capture can proceed when no window was attached. */ }
    }
    void beforeSample(Runnable capture,Runnable stop) {
        if(closed)return;
        if(failed){stop.run();return;}
        if(layer==null && !clearing){capture.run();return;}
        ready=capture;unsafe=stop;
        long remaining=400-(SystemClock.uptimeMillis()-savedAt);
        if(!clearing && settings.style!=FeedbackOptions.Style.COUNTER && remaining>0) {
            main.removeCallbacks(expire);main.postDelayed(expire,remaining);
        } else clearForCapture();
    }
    private void clearForCapture() {
        if(closed || clearing || layer==null)return;
        main.removeCallbacks(expire);clearing=true;
        Layer old=layer;
        old.blank=true;
        if(old.isAttachedToWindow() && old.isHardwareAccelerated() && old.getViewTreeObserver().isAlive()) {
            // Prefer presenting a blank overlay frame first. A renderer may skip committing
            // this frame, including when the display has stopped producing damage.
            old.getViewTreeObserver().registerFrameCommitCallback(()->detachAndWait(old));
            old.postInvalidateOnAnimation();
            main.postDelayed(detachFallback,450);
        } else detachAndWait(old);
    }
    private void detachAndWait(Layer old) {
        if(closed || !clearing || old==null || old!=layer)return;
        main.removeCallbacks(detachFallback);
        try { windows.removeViewImmediate(old); }
        catch(IllegalArgumentException absent) { /* It may already have been detached. */ }
        if(old.isAttachedToWindow() || old.getWindowToken()!=null || old.getParent()!=null) {
            clearanceFailed();return;
        }
        layer=null;
        // Keep capture blocked through two subsequent display frames to allow the
        // detached surface to leave display composition, even after a missed commit.
        main.postDelayed(timeout,1600);
        Choreographer clock=Choreographer.getInstance();
        clock.postFrameCallback(one->clock.postFrameCallback(two->{
            if(closed || !clearing || failed || layer!=null)return;
            main.removeCallbacks(timeout);
            clearing=false;
            Runnable next=ready;ready=null;unsafe=null;
            if(next!=null)next.run();
        }));
    }
    private void clearanceFailed() {
        if(!clearing || closed)return;
        main.removeCallbacks(detachFallback);main.removeCallbacks(timeout);
        failed=true;clearing=false;removeLayer();Runnable error=unsafe;ready=null;unsafe=null;
        if(error!=null)error.run();
    }
    private void removeLayer() {
        Layer current=layer;layer=null;
        if(current!=null)try {windows.removeViewImmediate(current);}catch(IllegalArgumentException detached) { }
    }
    void close() {
        closed=true;main.removeCallbacks(expire);main.removeCallbacks(detachFallback);main.removeCallbacks(timeout);
        ready=null;unsafe=null;removeLayer();
    }
    private final class Layer extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick=new Path();
        private final float dp=getResources().getDisplayMetrics().density;
        boolean blank;
        Layer() {
            super(service);setBackgroundColor(Color.TRANSPARENT);setWillNotDraw(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);setFocusable(false);setClickable(false);
        }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
            if(blank)return;
            long age=SystemClock.uptimeMillis()-savedAt;
            drawFeedback(c,paint,tick,settings.style,count,age,getWidth(),getHeight(),dp,dp);
            if(settings.style==FeedbackOptions.Style.EDGE && age<535)postInvalidateOnAnimation();
        }
    }

    /** Used at native size by the overlay and at a smaller scale inside the settings preview. */
    static void drawFeedback(Canvas c,Paint paint,Path tick,FeedbackOptions.Style style,int count,
                             long age,float w,float h,float dp,float marginScale) {
        if(style==FeedbackOptions.Style.OFF)return;
        boolean edge=style==FeedbackOptions.Style.EDGE;
        boolean live=style==FeedbackOptions.Style.COUNTER;
        if(edge) {
            float fade=Math.max(0,Math.min(1,(535-age)/260f));
            float inset=5*dp;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3.5f*dp);
            paint.setColor(0xff2cbda3);paint.setAlpha(Math.round(255*fade));
            c.drawRoundRect(inset,inset,w-inset,h-inset,15*dp,15*dp,paint);
        }
        String label=live?count+" saved":"Saved "+count;
        paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextSize(14*dp);
        float pill=paint.measureText(label)+49*dp;float left=live?w-pill-15*dp:(w-pill)/2;
        float height=36*dp;
        float top=edge?h-height-46*marginScale:47*marginScale;
        paint.setColor(edge?0xff126c5c:0xff173b32);c.drawRoundRect(left,top,left+pill,top+height,18*dp,18*dp,paint);
        paint.setColor(0xffa1edd2);c.drawCircle(left+17*dp,top+18*dp,7.5f*dp,paint);
        tick.reset();tick.moveTo(left+13*dp,top+18*dp);tick.lineTo(left+16*dp,top+21*dp);tick.lineTo(left+21*dp,top+15*dp);
        paint.setColor(0xff173b32);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.7f*dp);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);c.drawPath(tick,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);float baseline=top+height/2-(paint.ascent()+paint.descent())/2;
        c.drawText(label,left+31*dp,baseline,paint);
    }
}
