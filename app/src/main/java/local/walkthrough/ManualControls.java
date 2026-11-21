package local.walkthrough;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** User-operated windows on the default display. This class never inspects the app underneath. */
final class ManualControls {
    interface Listener {
        void onCapture();
        void onStop();
        default void onMarkerChanged(RectF fullDisplayFraction) {}
        default void onEditingChanged(boolean editing) {}
    }

    private enum Guides { VERTICAL, HORIZONTAL, OFF }
    private final Context context;
    private final WindowManager windows;
    private final boolean video, guided;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FeedbackOptions.Settings feedback;
    private final float density;
    private final int slop;
    private final SharedPreferences prefs;
    private final int markerHoldMs;
    private boolean shortcutEnabled, timedMarker;
    private Button shortcut;
    private final Runnable expireMarker=this::clearMarker;
    private LinearLayout toolbar;
    private Button primary, guidesButton, referenceButton;
    private LinearLayout referenceBox;
    private ImageView referenceImage;
    private TextView referenceHelp, alignmentStatus;
    private String alignment="";
    private String problem;
    private ManualLandmarks landmarks;
    private boolean referenceOpen;
    private TextView status;
    private PassiveLayer layer;
    private FrameLayout editor;
    private RectF marker;
    private Rect guideTarget;
    private String guideHint="";
    private int guideCorner;
    private boolean recording, visible, pending, suspended, closed, bottom, editingControls;
    private int count, surfaceGeneration, editGeneration;
    private long savedAt;
    private Guides guides = Guides.VERTICAL;
    private final Runnable expireFeedback = this::refreshLayer;

    ManualControls(Context owner, boolean video, boolean guided, Listener listener) {
        DisplayManager manager = owner.getSystemService(DisplayManager.class);
        Display display = manager.getDisplay(Display.DEFAULT_DISPLAY);
        context = owner.createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        windows = context.getSystemService(WindowManager.class);
        this.video = video;
        this.guided = video && guided;
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        feedback = FeedbackOptions.Settings.read(context);
        prefs = context.getSharedPreferences("profile",Context.MODE_PRIVATE);
        shortcutEnabled = prefs.getBoolean("manualMarkNext",false);
        timedMarker = prefs.getBoolean("manualTimedMarker",false);
        markerHoldMs = TapHighlight.Settings.read(context).dwellMs;
    }

    /** Returns false if the system has withheld the user's overlay permission. */
    boolean show() {
        if (closed || !Settings.canDrawOverlays(context)) return false;
        visible = true;
        removeShortcut();
        if (suspended || editor != null) return true;
        setEditingControls(recording);
        refreshLayer();
        if (toolbar != null) return true;
        toolbar = makeToolbar();
        WindowManager.LayoutParams p = barParams();
        if (attach(toolbar, p)) return true;
        toolbar = null;
        hide();
        return false;
    }

    /** Hiding the controls preserves a video marker placed by the user. */
    void hide() {
        visible = false;
        removeToolbar();
        detachEditor();
        refreshLayer();
        refreshShortcut();
        setEditingControls(false);
    }

    void close() {
        closed = true;
        ++surfaceGeneration;
        ++editGeneration;
        main.removeCallbacksAndMessages(null);
        visible = false;
        removeShortcut();
        marker = null;
        guideTarget = null;
        guideHint = "";
        landmarks = null;
        removeToolbar();
        detachEditor();
        detach(layer);
        layer = null;
    }

    void setRecording(boolean value) {
        recording = value;
        if (value) {
            suspended = false;
            ++surfaceGeneration;
            hide();
            scheduleMarkerExpiry();
        } else {
            main.removeCallbacks(expireMarker);
            removeShortcut();
            if (visible) rebuildToolbar();
        }
    }

    void setGuidance(Rect bounds,String hint) {
        if(closed || !guided)return;
        Rect next=bounds==null || bounds.isEmpty()?null:new Rect(bounds);
        String words=hint==null?"":hint;
        if(java.util.Objects.equals(guideTarget,next) && guideHint.equals(words))return;
        guideTarget=next;
        guideHint=words;
        if(toolbar!=null && toolbar.isAttachedToWindow())windows.updateViewLayout(toolbar,barParams());
        refreshLayer();
    }

    void guideDisplayChanged() {
        if(!guided || closed)return;
        guideTarget=null;
        guideHint="";
        if(toolbar!=null && toolbar.isAttachedToWindow())windows.updateViewLayout(toolbar,barParams());
        refreshLayer();
    }

    void setLandmarks(ManualLandmarks value) {
        if (closed || video) return;
        if (landmarks!=value) {
            alignment=value!=null && guides!=Guides.OFF && landmarks!=null
                    && landmarks.width==value.width && landmarks.height==value.height
                    ? value.alignmentFrom(landmarks,guides==Guides.HORIZONTAL) : "";
            landmarks=value;
        }
        refreshText();
    }

    void setCount(int value) {
        count = Math.max(0, value);
        refreshText();
    }

    void setCapturePending(boolean busy) {
        pending = busy;
        if (busy) problem = null;
        refreshText();
    }

    void showProblem(String message) {
        problem = message;
        refreshText();
        if (status != null) status.announceForAccessibility(message);
    }

    /** Called only after the PNG is committed locally. */
    void feedbackOnSave(int value) {
        if (closed || video) return;
        setCount(value);
        savedAt = SystemClock.uptimeMillis();
        if (feedback.haptic && toolbar != null)
            toolbar.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        refreshLayer();
        main.removeCallbacks(expireFeedback);
        if (feedback.style != FeedbackOptions.Style.COUNTER)
            main.postDelayed(expireFeedback, 740);
    }

    void pauseForScreenshot(Runnable ready) {
        if (closed) return;
        int generation = ++surfaceGeneration;
        suspended = true;
        removeShortcut();
        removeToolbar();
        detachEditor();
        detach(layer);
        layer = null;
        afterComposition(() -> {
            if (!closed && suspended && generation == surfaceGeneration) ready.run();
        });
    }

    void resumeAfterScreenshot() {
        if (closed) return;
        suspended = false;
        ++surfaceGeneration;
        if (visible) show();
        else { refreshLayer(); refreshShortcut(); }
    }

    RectF currentMarker() { return marker == null ? null : new RectF(marker); }

    void clearMarker() {
        main.removeCallbacks(expireMarker);
        marker = null;
        listener.onMarkerChanged(null);
        refreshLayer();
        if (toolbar != null) rebuildToolbar();
    }

    /** Only this explicit placement gesture is consumed. Returning to the app requires a new touch. */
    void placeMarker() {
        if (closed || !video || guided || suspended || editor != null || !Settings.canDrawOverlays(context)) return;
        setEditingControls(recording);
        removeShortcut();
        removeToolbar();
        detach(layer);
        layer = null;
        FrameLayout root = new FrameLayout(context);
        editor = root;
        MarkerPicker picker = new MarkerPicker();
        root.addView(picker, new FrameLayout.LayoutParams(-1,-1));
        LinearLayout hint = Ui.row(context);
        Ui.pad(hint, 12, 5, 5, 5);
        hint.setBackground(Ui.shape(context, 0xfa123c33, 0xff99cbb6, 14));
        TextView copy = Ui.text(context, "Tap a row or drag a box. Then tap the app yourself.", 14, Color.WHITE, true);
        hint.addView(copy, new LinearLayout.LayoutParams(0,-2,1));
        Button moveHint = control("Move", false, () -> {
            FrameLayout.LayoutParams pos=(FrameLayout.LayoutParams)hint.getLayoutParams();
            boolean nowTop=(pos.gravity & Gravity.VERTICAL_GRAVITY_MASK)==Gravity.TOP;
            pos.gravity=(nowTop ? Gravity.BOTTOM : Gravity.TOP)|Gravity.CENTER_HORIZONTAL;
            pos.topMargin=nowTop ? 0 : safeTop()+dp(6);
            pos.bottomMargin=nowTop ? windows.getCurrentWindowMetrics().getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom+dp(6) : 0;
            hint.setLayoutParams(pos);
        });
        moveHint.setContentDescription("Move placement instructions to the opposite edge");
        hint.addView(moveHint,new LinearLayout.LayoutParams(-2,-2));
        Button cancel = control("Cancel", false, () -> finishPlacement(null));
        hint.addView(cancel, new LinearLayout.LayoutParams(-2,-2));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                Math.min(dp(340), Math.max(dp(150), screen().width()-dp(20))), -2, Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        hp.topMargin = safeTop() + dp(6);
        root.addView(hint, hp);
        if (!attach(root, fullParams(true))) finishPlacement(null);
    }

    static void paintMarker(Canvas canvas, RectF normalized, float density) {
        if (normalized == null || normalized.isEmpty()) return;
        RectF viewport = new RectF(0,0,canvas.getWidth(),canvas.getHeight());
        RectF row = new RectF(normalized.left*viewport.width(), normalized.top*viewport.height(),
                normalized.right*viewport.width(), normalized.bottom*viewport.height());
        TapHighlight.drawMark(canvas, viewport, row, density, TapHighlight.Style.OUTLINE);
    }

    private void finishPlacement(RectF chosen) {
        detachEditor();
        if (chosen != null) {
            marker = new RectF(chosen);
            listener.onMarkerChanged(new RectF(chosen));
            scheduleMarkerExpiry();
        }
        if (recording) hide();
        else show();
    }

    private void scheduleMarkerExpiry() {
        main.removeCallbacks(expireMarker);
        if (recording && timedMarker && marker!=null) main.postDelayed(expireMarker,markerHoldMs);
    }

    private void removeShortcut() { detach(shortcut); shortcut=null; }

    private void refreshShortcut() {
        if (!video || guided || !recording || !shortcutEnabled || closed || suspended || visible || editor!=null) {
            removeShortcut();
            return;
        }
        if (shortcut!=null) return;
        Button chip=control("Mark next",false,this::placeMarker);
        chip.setContentDescription("Mark next. Long press to show controls.");
        chip.setOnLongClickListener(v->{show();return true;});
        WindowManager.LayoutParams p=barParams();
        p.width=WindowManager.LayoutParams.WRAP_CONTENT;
        p.setTitle("Mark next shortcut");
        if (attach(chip,p)) shortcut=chip;
    }

    private void setEditingControls(boolean value) {
        if (editingControls == value) return;
        editingControls = value;
        int ticket = ++editGeneration;
        if (value) listener.onEditingChanged(true);
        else afterComposition(() -> {
            if (!closed && !editingControls && ticket == editGeneration) listener.onEditingChanged(false);
        });
    }

    private void afterComposition(Runnable after) {
        Choreographer clock = Choreographer.getInstance();
        clock.postFrameCallback(first -> clock.postFrameCallback(second ->
                main.postDelayed(after, 100)));
    }

    private WindowManager.LayoutParams barParams() {
        if(guided)return guidedBarParams();
        int width = Math.min(dp(270), screen().width()-dp(16));
        WindowManager.LayoutParams p = params(width, WindowManager.LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.END | (bottom ? Gravity.BOTTOM : Gravity.TOP);
        p.x = dp(8);
        p.y = dp(8);
        p.setTitle("Walkthrough manual controls");
        return p;
    }

    // The guided toolbar takes a single compact row. Pick a corner with no overlap
    // before attaching it so even the first tap reaches the underlying app.
    private WindowManager.LayoutParams guidedBarParams() {
        Rect display=screen(), available=new Rect(display);
        android.graphics.Insets insets=windows.getCurrentWindowMetrics().getWindowInsets()
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        available.inset(insets.left+dp(8),insets.top+dp(8),insets.right+dp(8),insets.bottom+dp(8));
        int w=Math.max(1,Math.min(dp(206),available.width()));
        toolbar.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int h=toolbar.getMeasuredHeight();
        WindowManager.LayoutParams p=params(w,WindowManager.LayoutParams.WRAP_CONTENT);
        p.flags|=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.gravity=Gravity.TOP|Gravity.LEFT;
        p.setTitle("Walkthrough guided video controls");
        Rect avoid=guideTarget==null?null:new Rect(guideTarget);
        if(avoid!=null)avoid.inset(-dp(8),-dp(8));
        for(int attempt=0;attempt<4;attempt++) {
            int corner=(guideCorner+attempt)%4;
            int x=corner<2?available.right-w:available.left;
            int y=(corner%2==0)?available.top:available.bottom-h;
            Rect candidate=new Rect(x,y,x+w,y+h);
            if(!available.contains(candidate) || (avoid!=null && Rect.intersects(candidate,avoid)))continue;
            guideCorner=corner;
            p.x=x-display.left;
            p.y=y-display.top;
            return p;
        }
        // Even a completely covering target remains tappable. The same Record/Stop
        // controls are available from the session notification in this uncommon case.
        p.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        p.alpha=0f;
        return p;
    }

    private WindowManager.LayoutParams params(int width, int height) {
        return new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
    }

    private WindowManager.LayoutParams fullParams(boolean touches) {
        WindowManager.LayoutParams p = params(-1,-1);
        p.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.setTitle(touches ? "Place the manual focus outline" : "Walkthrough manual guide");
        if (!touches) {
            p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            // A single full-display non-touchable window covers the touch path. Its alpha is
            // kept below the system's per-UID obscuring limit on Android 12 and later.
            p.alpha = Build.VERSION.SDK_INT >= 31
                    ? Math.min(.78f, context.getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch())
                    : .78f;
        }
        return p;
    }

    private int safeTop() {
        return windows.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout()).top;
    }

    private Rect screen() { return windows.getCurrentWindowMetrics().getBounds(); }
    private int dp(int value) { return Math.round(value*density); }

    private boolean attach(View view, WindowManager.LayoutParams p) {
        try { windows.addView(view,p); return true; }
        catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException | SecurityException denied) {
            return false;
        }
    }
    private void detach(View view) {
        if (view == null) return;
        try { windows.removeViewImmediate(view); }
        catch (IllegalArgumentException alreadyDetached) { }
    }
    private void removeToolbar() {
        detach(toolbar);
        toolbar=null; primary=null; status=null; guidesButton=null;
        referenceButton=null; referenceBox=null; referenceImage=null; referenceHelp=null; alignmentStatus=null;
    }
    private void detachEditor() { detach(editor); editor=null; }
    private void rebuildToolbar() { removeToolbar(); if (visible && !suspended) show(); }

    private Button control(String name, boolean mainAction, Runnable action) {
        Button b = Ui.button(context, name, mainAction, action);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        Ui.pad(b,7,6,7,6);
        if (!mainAction) {
            b.setBackground(Ui.ripple(context, 0xff245147, 0xff42685d, 10));
            b.setTextColor(Color.WHITE);
        } else {
            b.setBackground(Ui.ripple(context, 0xffb9f0d9, 0xffb9f0d9, 10));
            b.setTextColor(0xff123e32);
        }
        return b;
    }

    private void row(LinearLayout parent, Button first, Button second) {
        LinearLayout line = Ui.row(context);
        line.addView(first,new LinearLayout.LayoutParams(0,-2,1));
        Ui.gap(line,5);
        line.addView(second,new LinearLayout.LayoutParams(0,-2,1));
        parent.addView(line);
    }

    private LinearLayout makeGuidedToolbar() {
        LinearLayout pane=Ui.row(context);
        Ui.pad(pane,5,5,5,5);
        pane.setBackground(Ui.shape(context,0xfe143a32,0xff91d8b8,12));
        primary=control(recording?"Stop":"Record",true,recording?listener::onStop:listener::onCapture);
        Button secondary=recording?control("Hide",false,this::hide):control("Cancel",false,listener::onStop);
        pane.addView(primary,new LinearLayout.LayoutParams(0,-2,1));
        Ui.gap(pane,4);
        pane.addView(secondary,new LinearLayout.LayoutParams(0,-2,1));
        Ui.enable(primary,!pending);
        pane.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(layer!=null)layer.invalidate();
        });
        return pane;
    }

    private LinearLayout makeToolbar() {
        if(guided)return makeGuidedToolbar();
        LinearLayout pane = Ui.column(context);
        Ui.pad(pane,8,4,8,8);
        pane.setBackground(Ui.shape(context, 0xfe143a32, 0xff5a796f, 14));
        LinearLayout top = Ui.row(context);
        status = Ui.text(context, "", 13, 0xffdcf3e9, true);
        Ui.pad(status,4,0,0,0);
        top.addView(status, new LinearLayout.LayoutParams(0,-2,1));
        if (video && marker!=null && !recording) top.addView(control("Hide",false,this::hide));
        Button move = control("Move",false,() -> {
            bottom = !bottom;
            if (toolbar != null) windows.updateViewLayout(toolbar,barParams());
        });
        move.setContentDescription("Move controls to the opposite edge");
        top.addView(move);
        pane.addView(top);
        if (!video) {
            alignmentStatus=Ui.text(context,"",12,0xffb9f0d9,true);
            Ui.pad(alignmentStatus,4,2,4,4);
            pane.addView(alignmentStatus);
        }
        Ui.gap(pane,5);
        if (video) {
            primary = control(recording?"Place marker":"Record",true,
                    recording ? this::placeMarker : listener::onCapture);
            Button other = recording ? control("Hide",false,this::hide) : control("Place marker",false,this::placeMarker);
            row(pane,primary,other);
        } else {
            primary = control("Save screen",true,listener::onCapture);
            guidesButton = control("",false,this::cycleGuides);
            row(pane,primary,guidesButton);
        }
        Ui.gap(pane,5);
        Button left = video && marker != null ? control("Clear marker",false,this::clearMarker)
                : control("Hide",false,this::hide);
        if (recording && marker==null) {
            pane.addView(control("Stop",false,listener::onStop));
        } else {
            row(pane,left,control(video ? (recording?"Stop":"Cancel") : "Finish",false,listener::onStop));
        }
        if (video) {
            Ui.gap(pane,5);
            Button useChip=control(shortcutEnabled?"Mark next · on":"Mark next · off",false,() -> {
                shortcutEnabled=!shortcutEnabled;
                prefs.edit().putBoolean("manualMarkNext",shortcutEnabled).apply();
                rebuildToolbar();
            });
            int seconds=markerHoldMs/1000;
            Button duration=control(timedMarker?"Marker · "+seconds+" s":"Marker · stays",false,() -> {
                timedMarker=!timedMarker;
                prefs.edit().putBoolean("manualTimedMarker",timedMarker).apply();
                scheduleMarkerExpiry();
                rebuildToolbar();
            });
            useChip.setContentDescription("Mark next shortcut, "+(shortcutEnabled?"on":"off")+". Visible in video when enabled.");
            duration.setContentDescription("Marker duration, "+(timedMarker?seconds+" seconds":"until cleared")+". Change duration.");
            row(pane,useChip,duration);
            String note=shortcutEnabled ? "The Mark next button appears in the video. Hold it for controls."
                    : "Turn on Mark next for an on-screen button in the video.";
            TextView chipHelp=Ui.text(context,note,12,0xffdcf3e9,false);
            Ui.pad(chipHelp,4,6,4,2);
            pane.addView(chipHelp);
        }
        if (!video) {
            referenceButton=control("Show reference",false,() -> {
                referenceOpen=!referenceOpen;
                refreshReference();
            });
            pane.addView(referenceButton);
            referenceBox=Ui.column(context);
            Ui.pad(referenceBox,5,4,5,1);
            referenceHelp=Ui.text(context,"",12,0xffdcf3e9,true);
            referenceBox.addView(referenceHelp);
            Ui.gap(referenceBox,3);
            referenceImage=new ImageView(context);
            referenceImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            referenceImage.setBackground(Ui.shape(context,0xff082720,Color.TRANSPARENT,6));
            referenceBox.addView(referenceImage,new LinearLayout.LayoutParams(-1,dp(54)));
            pane.addView(referenceBox);
            pane.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB) -> refreshReference());
        }
        refreshText();
        return pane;
    }

    private void cycleGuides() {
        alignment="";
        switch(guides) {
            case VERTICAL: guides=Guides.HORIZONTAL; break;
            case HORIZONTAL: guides=Guides.OFF; break;
            case OFF: guides=Guides.VERTICAL; break;
        }
        refreshText();
        refreshLayer();
    }

    private void refreshText() {
        if (status!=null) {
            String ready = video ? (recording ? "Video recording" : "Ready to record")
                    : count + (count==1?" screen saved":" screens saved");
            status.setText(problem == null ? ready : problem);
        }
        if (primary!=null) Ui.enable(primary,!pending);
        if (alignmentStatus!=null) {
            alignmentStatus.setText(alignment);
            alignmentStatus.setVisibility(alignment.isEmpty()?View.GONE:View.VISIBLE);
        }
        if (guidesButton!=null) {
            String direction=guides==Guides.VERTICAL?"vertical":guides==Guides.HORIZONTAL?"horizontal":"off";
            guidesButton.setText("Guides · "+direction);
            guidesButton.setContentDescription("Guides, "+direction+". Change direction or turn off.");
            refreshReference();
        }
    }

    private void refreshReference() {
        if (referenceButton==null || referenceImage==null) return;
        Rect bounds=screen();
        boolean usable=landmarks!=null && guides!=Guides.OFF && landmarks.width==bounds.width()
                && landmarks.height==bounds.height();
        referenceButton.setVisibility(usable ? View.VISIBLE : View.GONE);
        referenceBox.setVisibility(usable && referenceOpen ? View.VISIBLE : View.GONE);
        String toggle = referenceOpen ? "Hide reference" : "Show reference";
        if (!toggle.contentEquals(referenceButton.getText())) referenceButton.setText(toggle);
        if (!usable || !referenceOpen) {
            if (referenceImage.getDrawable()!=null) referenceImage.setImageDrawable(null);
            referenceImage.setTag(null);
            return;
        }
        boolean vertical=guides==Guides.VERTICAL;
        android.graphics.Bitmap sample=vertical ? landmarks.vertical : landmarks.horizontal;
        if (referenceImage.getTag()!=sample || referenceImage.getDrawable()==null) {
            referenceImage.setImageBitmap(sample);
            referenceImage.setTag(sample);
        }
        String where=vertical ? "upper" : "left";
        String hint="Match this at the "+where+" guide.";
        if (!hint.contentEquals(referenceHelp.getText())) {
            referenceHelp.setText(hint);
            referenceImage.setContentDescription("Last saved screen landmark for the "+where+" guide");
        }
    }

    private boolean hasFeedback() {
        if (video || count==0 || savedAt==0 || feedback.style==FeedbackOptions.Style.OFF) return false;
        if (feedback.style==FeedbackOptions.Style.COUNTER) return true;
        return SystemClock.uptimeMillis()-savedAt < (feedback.style==FeedbackOptions.Style.EDGE?560:710);
    }
    private void refreshLayer() {
        boolean videoCue=guided ? guideTarget!=null || !guideHint.isEmpty() : marker!=null;
        boolean needed = !closed && !suspended && editor==null && (video ? videoCue
                : visible && (guides!=Guides.OFF || hasFeedback()));
        if (!needed) { detach(layer); layer=null; return; }
        if (layer!=null) { layer.invalidate(); return; }
        PassiveLayer next = new PassiveLayer();
        if (attach(next,fullParams(false))) layer=next;
    }

    private final class PassiveLayer extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick=new Path();
        private final ManualTargetCue targetCue=guided?new ManualTargetCue(density,
                context.getResources().getDisplayMetrics().scaledDensity):null;
        private final int[] origin=new int[2];
        PassiveLayer() {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            setClickable(false);
        }
        @Override protected void onDraw(Canvas c) {
            if (video) {
                if(guided) {
                    getLocationOnScreen(origin);
                    Rect target=guideTarget==null?null:new Rect(guideTarget);
                    if(target!=null)target.offset(-origin[0],-origin[1]);
                    Rect safe=new Rect(0,0,getWidth(),getHeight());
                    WindowInsets insets=getRootWindowInsets();
                    if(insets!=null) {
                        android.graphics.Insets bars=insets.getInsetsIgnoringVisibility(
                                WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                        safe.inset(bars.left,bars.top,bars.right,bars.bottom);
                    }
                    Rect bar=null;
                    if(toolbar!=null && toolbar.isAttachedToWindow()
                            && ((WindowManager.LayoutParams)toolbar.getLayoutParams()).alpha>0f) {
                        int[] pos=new int[2];toolbar.getLocationOnScreen(pos);
                        bar=new Rect(pos[0],pos[1],pos[0]+toolbar.getWidth(),pos[1]+toolbar.getHeight());
                        bar.offset(-origin[0],-origin[1]);
                    }
                    targetCue.draw(c,target,guideHint,safe,bar);
                } else paintMarker(c,marker,density);
                return;
            }
            if (guides!=Guides.OFF) drawGuides(c,paint,guides==Guides.VERTICAL);
            if (hasFeedback()) {
                long age=SystemClock.uptimeMillis()-savedAt;
                FeedbackHud.drawFeedback(c,paint,tick,feedback.style,count,age,getWidth(),getHeight(),density,density);
                if (feedback.style==FeedbackOptions.Style.EDGE) postInvalidateOnAnimation();
            }
        }
    }

    /** Repeated landmarks supply overlap by eye. Only the display dimensions are used. */
    private void drawGuides(Canvas c,Paint p,boolean vertical) {
        float w=c.getWidth(), h=c.getHeight();
        p.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        p.setTextSize(12*density);
        for (int i=0;i<2;i++) {
            float fraction=i==0?ManualLandmarks.NEAR:ManualLandmarks.FAR;
            float x=vertical?0:w*fraction, y=vertical?h*fraction:0;
            float endX=vertical?w:x, endY=vertical?y:h;
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0xff12342c);p.setStrokeWidth(3*density);
            p.setPathEffect(null);c.drawLine(x,y,endX,endY,p);
            p.setPathEffect(new DashPathEffect(new float[]{9*density,7*density},0));
            p.setColor(0xffb9ffdd);p.setStrokeWidth(1.8f*density);
            c.drawLine(x,y,endX,endY,p);p.setPathEffect(null);
            String text = i==0?"To here":"From here";
            float tx=vertical?8*density:x+5*density, ty=vertical?y+4*density:h*.57f;
            float labelWidth=p.measureText(text)+14*density;
            tx=Math.max(0,Math.min(tx,w-labelWidth));
            p.setStyle(Paint.Style.FILL);p.setColor(0xff173c31);
            c.drawRoundRect(tx,ty,tx+labelWidth,ty+23*density,7*density,7*density,p);
            p.setColor(Color.WHITE);c.drawText(text,tx+7*density,ty+16*density,p);
        }
    }

    private final class MarkerPicker extends View {
        private float downX,downY,currX,currY;
        private boolean dragging;
        MarkerPicker() {
            super(context);
            setContentDescription("Place focus outline. Tap to mark a row, or drag a rectangle.");
            setClickable(true);
        }
        private RectF selection() {
            float w=getWidth(),h=getHeight();
            float x1,x2,y1,y2;
            if (!dragging) {
                x1=8*density;x2=w-8*density;y1=currY-23*density;y2=currY+23*density;
            } else {
                x1=Math.min(downX,currX);x2=Math.max(downX,currX);
                y1=Math.min(downY,currY);y2=Math.max(downY,currY);
                if (x2-x1<8*density) x2=x1+8*density;
                if (y2-y1<8*density) y2=y1+8*density;
            }
            return new RectF(Math.max(0,x1)/w,Math.max(0,y1)/h,Math.min(w,x2)/w,Math.min(h,y2)/h);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch(e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX=currX=e.getX();downY=currY=e.getY();dragging=false;setPressed(true);invalidate();return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    currX=e.getX();currY=e.getY();
                    dragging|=Math.hypot(currX-downX,currY-downY)>slop;
                    invalidate();
                    if (e.getActionMasked()==MotionEvent.ACTION_UP) {
                        performClick();finishPlacement(selection());
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL: setPressed(false);invalidate();return true;
                default:return true;
            }
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(0x22203a32);
            if (isPressed()) paintMarker(c,selection(),density);
        }
    }
}
