package local.walkthrough;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.view.ViewGroup;
import android.view.Window;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A finite, locally drawn rehearsal. Both samples share their mark with the live renderer. */
final class StylePreview extends LinearLayout {
    private final Scene scene;
    private final TextView caption;
    private final Button replay;
    private ValueAnimator animator;
    private String restingCaption;
    private int hold = 2000;

    StylePreview(Context context, boolean trajectory) {
        super(context);
        setOrientation(VERTICAL);
        setBackground(Ui.shape(context, Ui.HEADER, 0, 14));
        Ui.pad(this, 10, 6, 10, 9);
        LinearLayout top = Ui.row(context);
        TextView title = Ui.text(context, "What you’ll see", 14, Ui.SURFACE, true);
        title.setAccessibilityHeading(true);
        top.addView(title, Ui.weight());
        replay = new Button(context, null, android.R.attr.borderlessButtonStyle);
        replay.setAllCaps(false);
        replay.setText("Replay");
        replay.setContentDescription("Replay selected style preview");
        replay.setTextColor(0xffbcf5dc);
        replay.setTextSize(14);
        replay.setMinWidth(Ui.dp(context, 62));
        replay.setMinHeight(Ui.dp(context, 48));
        Ui.pad(replay, 8, 2, 8, 2);
        replay.setOnClickListener(v -> play());
        top.addView(replay, Ui.lp(context, -2, -2));
        addView(top);
        scene = new Scene(context, trajectory);
        addView(scene, Ui.lp(context, -1, 88));
        Ui.gap(this, 8);
        caption = Ui.text(context, "", 13, Ui.HEADER_TEXT, true);
        addView(caption);
    }

    void select(TapHighlight.Style style, int seconds) {
        scene.highlight = style;
        hold = seconds * 1000;
        restingCaption = style == TapHighlight.Style.OFF ? "Before tap · clear screen"
                : "Before tap · " + seconds + (seconds == 1 ? " second" : " seconds");
        demonstrate();
    }

    void select(FeedbackOptions.Style style) {
        scene.feedback = style;
        restingCaption = style == FeedbackOptions.Style.OFF ? "After save · clear screen"
                : style == FeedbackOptions.Style.COUNTER ? "Between screenshots · 12 saved" : "After save · screenshot 12";
        demonstrate();
    }

    private void demonstrate() {
        stop();
        scene.still();
        caption.setText(restingCaption);
        boolean motion = motionAllowed();
        replay.setEnabled(motion);
        replay.setVisibility(motion ? VISIBLE : GONE);
        if (isAttachedToWindow() && replay.isEnabled()) play();
    }

    static void fitDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        Context c = dialog.getContext();
        int width = Math.min(c.getResources().getDisplayMetrics().widthPixels, Ui.dp(c, 590));
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(() -> {
            if (isAttachedToWindow() && restingCaption != null && !isAnimating() && motionAllowed()) play();
        });
    }

    private boolean isAnimating() { return animator != null && animator.isRunning(); }

    private boolean motionAllowed() {
        AccessibilityManager a = getContext().getSystemService(AccessibilityManager.class);
        return ValueAnimator.areAnimatorsEnabled() && (a == null || !a.isTouchExplorationEnabled());
    }

    private void play() {
        stop();
        if (!motionAllowed()) {
            scene.still();
            caption.setText(restingCaption);
            return;
        }
        int length = scene.trajectory ? (scene.highlight == TapHighlight.Style.OFF ? 0 : hold) + 1320 : 2380;
        animator = ValueAnimator.ofInt(0, length);
        animator.setDuration(length);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            int ms = (int) a.getAnimatedValue();
            if (ms >= length) {
                scene.still();
                caption.setText(restingCaption);
            } else {
                String phase = scene.at(ms, hold);
                if (!phase.contentEquals(caption.getText())) caption.setText(phase);
            }
        });
        animator.start();
    }

    private void stop() {
        if (animator == null) return;
        animator.cancel();
        animator = null;
    }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }

    /** A checked radio card with a small, persistent visual fingerprint alongside its description. */
    static final class Card extends Ui.Choice {
        private final Ui.Glyph check;
        Card(Context c, String label, String detail, TapHighlight.Style highlight, FeedbackOptions.Style feedback) {
            super(c, "android.widget.RadioButton");
            setGravity(Gravity.CENTER_VERTICAL);
            setContentDescription(label + ". " + detail);
            Ui.pad(this, 9, 8, 9, 8);
            setMinimumHeight(Ui.dp(c, 55));
            Swatch swatch = new Swatch(c, highlight, feedback);
            addView(swatch, Ui.lp(c, 43, 38));
            Ui.gap(this, 10);
            LinearLayout copy = Ui.column(c);
            copy.addView(Ui.text(c, label, 15, Ui.INK, true));
            Ui.gap(copy, 2);
            copy.addView(Ui.text(c, detail, 13, Ui.MUTED, false));
            copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            addView(copy, Ui.weight());
            Ui.gap(this, 5);
            check = new Ui.Glyph(c, "check", Ui.TEAL);
            addView(check, Ui.lp(c, 17, 17));
        }
        void select(boolean active) {
            mark(active);
            setBackground(Ui.ripple(getContext(), active ? Ui.PALE : Ui.SURFACE, active ? Ui.TEAL : Ui.LINE, 11));
            check.setVisibility(active ? VISIBLE : INVISIBLE);
        }
    }

    private static final class Swatch extends View {
        final TapHighlight.Style highlight;
        final FeedbackOptions.Style feedback;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        Swatch(Context c, TapHighlight.Style h, FeedbackOptions.Style f) {
            super(c);
            highlight = h;
            feedback = f;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), d = w / 100;
            RectF area = new RectF(0, 0, w, h);
            path.reset();
            path.addRoundRect(area, 8*d, 8*d, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(path);
            canvas.drawColor(0xffdbe6df);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 3; i++) {
                float top = (.13f + i*.29f)*h;
                paint.setColor(i == 1 ? 0xfffdfefa : 0xffeaf0e9);
                canvas.drawRoundRect(6*d, top, w-6*d, top+h*.21f, 3*d, 3*d, paint);
                paint.setColor(i == 1 ? 0xff50675f : 0xffa6b7ab);
                canvas.drawRoundRect(11*d, top+h*.08f, w*(i == 1 ? .73f : .57f), top+h*.13f, d, d, paint);
            }
            if (highlight != null)
                TapHighlight.drawMark(canvas, area, new RectF(6*d, .42f*h, w-6*d, .63f*h), d, highlight);
            if (feedback != null)
                FeedbackHud.drawFeedback(canvas, paint, path, feedback, 12, 0, w, h, d*.76f, d*.16f);
            canvas.restore();
        }
    }

    private static final class Scene extends View {
        final boolean trajectory;
        final Bitmap rowImage, detailImage;
        TapHighlight.Style highlight = TapHighlight.Style.OUTLINE;
        FeedbackOptions.Style feedback = FeedbackOptions.Style.EDGE;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        boolean detail, show = true;
        float offset;
        int count = 12;
        long age;
        Scene(Context c, boolean trajectory) {
            super(c);
            this.trajectory = trajectory;
            rowImage = trajectory ? BitmapFactory.decodeResource(c.getResources(), R.drawable.preview_ticket_row) : null;
            detailImage = BitmapFactory.decodeResource(c.getResources(), R.drawable.preview_ticket_detail);
            paint.setFilterBitmap(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void still() {
            detail = false; show = true; offset = 0; count = 12; age = 0;
            invalidate();
        }
        String at(int ms, int hold) {
            String phase;
            if (trajectory) {
                detail = ms >= 490 + (highlight == TapHighlight.Style.OFF ? 0 : hold);
                show = ms >= 490 && !detail;
                offset = 0;
                phase = detail ? "After tap · step opened" : show ? "Before tap · highlight visible" : "Step in view";
            } else {
                detail = false;
                int end = feedback == FeedbackOptions.Style.EDGE ? 560 : 710;
                boolean counter = feedback == FeedbackOptions.Style.COUNTER;
                offset = Math.min(1, ms / 590f);
                if (ms >= 1580) offset += Math.min(1, (ms-1580) / 550f);
                show = ms < 600 ? counter : ms >= 830 && (counter || ms < 830 + end);
                count = ms < 830 ? 11 : 12;
                age = Math.max(0, ms - 830);
                phase = ms < 600 ? "Scrolling · 11 saved" : ms < 830 ? "Taking screenshot · clear screen"
                        : ms < 1580 ? "Screenshot 12 saved" : "Scrolling · 12 saved";
            }
            invalidate();
            return phase;
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            if (w <= 0 || h <= 0) return;
            RectF area = new RectF(0, 0, w, h);
            path.reset();
            path.addRoundRect(area, 7*d, 7*d, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(path);
            canvas.drawColor(0xfff5f5ef);
            if (trajectory && !detail) {
                // Keep the entire sample step row in view at every supported sheet width.
                // Add space outside it so pointers and outlines never cover the step text.
                canvas.drawColor(0xfff7f8f6);
                float rw = Math.min(w-22*d, h*0.53f*rowImage.getWidth()/rowImage.getHeight());
                float rh = rw*rowImage.getHeight()/rowImage.getWidth();
                RectF row = new RectF((w-rw)/2, (h-rh)/2, (w+rw)/2, (h+rh)/2);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xffdce4dc);
                canvas.drawRoundRect(w*.44f, 5*d, w*.56f, 8*d, 2*d, 2*d, paint);
                paint.setColor(0xffcad5cd);
                canvas.drawRoundRect(row.left-1,row.top-1,row.right+1,row.bottom+1,3*d,3*d,paint);
                paint.setColor(0xffffffff);
                canvas.drawBitmap(rowImage,null,row,paint);
                if (show) TapHighlight.drawMark(canvas, area, row,d*.78f,highlight);
            } else {
                // Move the viewport over the detail image at its original aspect ratio.
                int iw=detailImage.getWidth(), ih=detailImage.getHeight();
                float scale=Math.max(w/iw,h/ih);
                float width=w/scale,height=h/scale;
                float start=Math.min(ih-height,offset*height*.47f);
                Rect source=new Rect(0,Math.round(start),Math.min(iw,Math.round(width)),Math.min(ih,Math.round(start+height)));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xffffffff);
                canvas.drawBitmap(detailImage,source,area,paint);
                if (show && !trajectory)
                    FeedbackHud.drawFeedback(canvas,paint,path,feedback,count,age,w,h,d*.74f,d*.17f);
            }
            canvas.restore();
        }
    }
}
