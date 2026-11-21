package local.walkthrough;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

/** Draws one read-only hint in the same passive display window seen by MediaProjection. */
final class ManualTargetCue {
    private final float density;
    private final Paint shape=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);

    ManualTargetCue(float density,float scaledDensity) {
        this.density=density;
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        text.setTextSize(14*scaledDensity);
    }

    private int dp(int d) { return Math.round(d*density); }

    void draw(Canvas canvas,Rect target,String hint,Rect safe,Rect toolbar) {
        Rect viewport=new Rect(0,0,canvas.getWidth(),canvas.getHeight());
        Rect row=target==null?null:new Rect(target);
        if(row!=null) {
            if(!row.intersect(viewport))return;
            TapHighlight.drawMark(canvas,new RectF(viewport),new RectF(row),density,TapHighlight.Style.OUTLINE);
        }
        if(hint.isEmpty())return;
        int gap=dp(10), inset=dp(10), dotSpace=dp(15);
        Rect room=new Rect(safe);
        room.inset(gap,gap);
        int innerLimit=Math.min(dp(255),room.width()-2*inset-dotSpace);
        if(innerLimit<dp(60) || room.isEmpty())return;
        int textWidth=Math.max(1, Math.min(innerLimit,(int)Math.ceil(text.measureText(hint))));
        StaticLayout layout=StaticLayout.Builder.obtain(hint,0,hint.length(),text,textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(3).setIncludePad(false)
                .setEllipsize(TextUtils.TruncateAt.END).build();
        int width=textWidth+2*inset+dotSpace, height=layout.getHeight()+dp(14);
        if(width>room.width() || height>room.height())return;

        int left=row==null?room.left:Math.max(room.left,Math.min(row.left,room.right-width));
        int top=row==null?room.top:row.top-gap-height;
        int bottom=row==null?room.bottom-height:row.bottom+gap;
        // Try immediately beside the target first, then the four safe corners. A large
        // target can leave no room for the hint. Keep the outline and its tap area clear.
        Rect exclusion=row==null?null:new Rect(row);
        if(exclusion!=null)exclusion.inset(-dp(7),-dp(7));
        int[][] candidates={{left,top},{left,bottom},{room.left,room.top},
                {room.right-width,room.top},{room.left,room.bottom-height},{room.right-width,room.bottom-height}};
        for (int[] point:candidates) {
            Rect box=new Rect(point[0],point[1],point[0]+width,point[1]+height);
            if(!room.contains(box) || (exclusion!=null && Rect.intersects(box,exclusion))
                    || (toolbar!=null && Rect.intersects(box,toolbar)))continue;
            float radius=dp(9);
            shape.setStyle(Paint.Style.FILL);
            shape.setColor(0xff10392f);
            canvas.drawRoundRect(new RectF(box),radius,radius,shape);
            shape.setStyle(Paint.Style.STROKE);
            shape.setStrokeWidth(Math.max(1,density));
            shape.setColor(0xff91e7c3);
            canvas.drawRoundRect(new RectF(box),radius,radius,shape);
            shape.setStyle(Paint.Style.FILL);
            shape.setColor(0xff98f1c9);
            canvas.drawCircle(box.left+inset+dp(3),box.exactCenterY(),dp(3),shape);
            canvas.save();
            canvas.translate(box.left+inset+dotSpace,box.top+dp(7));
            layout.draw(canvas);
            canvas.restore();
            return;
        }
    }
}
