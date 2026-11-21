package local.walkthrough;

import android.graphics.Bitmap;
import android.graphics.Rect;

/** Temporary in-memory pixels used to register overlapping rendered viewports. */
final class ConversationPixels {
    private final int width,height,originalWidth,originalHeight;
    private final ConversationOverlap samples;
    ConversationPixels(Bitmap source) {
        originalWidth=source.getWidth(); originalHeight=source.getHeight();
        float scale=Math.min(1f,420f/Math.max(originalWidth,originalHeight));
        width=Math.max(1,Math.round(originalWidth*scale)); height=Math.max(1,Math.round(originalHeight*scale));
        byte[] gray = PixelThumbnail.luminance(source, width, height);
        samples=new ConversationOverlap(width,height,gray);
    }
    private ConversationOverlap.Box scaled(Rect physical) {
        int x=Math.round(physical.left*(float)width/originalWidth), y=Math.round(physical.top*(float)height/originalHeight);
        Rect r=new Rect(x,y,Math.round(physical.right*(float)width/originalWidth),Math.round(physical.bottom*(float)height/originalHeight));
        if(!r.intersect(1,1,width-1,height-1))r.setEmpty();
        return new ConversationOverlap.Box(r.left,r.top,r.right,r.bottom);
    }
    boolean sameCrop(ConversationPixels newer,Rect old,Rect fresh) {
        return width==newer.width && height==newer.height && samples.sameCrop(newer.samples,scaled(old),newer.scaled(fresh));
    }
    boolean hasVisibleContent(Rect area){return samples.hasVisibleContent(scaled(area));}
    boolean same(ConversationPixels next,Rect physical) { return samples.same(next.samples,scaled(physical)); }
    boolean equalTranslatedOverlap(ConversationPixels next,Rect physical,int offset) {
        return samples.equalTranslatedOverlap(next.samples,scaled(physical),Math.round(offset*(float)width/originalWidth));
    }
    int displacement(ConversationPixels next,Rect physical,int direction,Integer expected) {
        boolean horizontal=WalkScroll.horizontal(direction);
        float scale=horizontal?(float)width/originalWidth:(float)height/originalHeight;
        int sign=(direction==WalkTree.DOWN || direction==WalkTree.RIGHT)?1:-1;
        return samples.displacement(next.samples,scaled(physical),horizontal,sign,scale,expected);
    }
}
