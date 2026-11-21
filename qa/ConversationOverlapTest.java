package local.walkthrough;

/** Real matcher on reproducible rendered content with fixed header/composer and independently moving panes. */
public final class ConversationOverlapTest {
    private static final int W=140,H=170;
    private static final ConversationOverlap.Box VIEW=new ConversationOverlap.Box(8,26,130,148);
    private static ConversationOverlap page(int x,int y,boolean blank) {
        byte[] pixels=new byte[W*H];
        for(int row=0;row<H;row++)for(int col=0;col<W;col++) {
            int cx=col,cy=row;
            if(row>=VIEW.top && row<VIEW.bottom && col>=VIEW.left && col<VIEW.right){cx+=x;cy+=y;}
            int z=(cx*173+cy*3229) ^ (cx*cy*193);
            pixels[row*W+col]=(byte)(blank?238:((z^(z>>>11))&255));
        }
        return new ConversationOverlap(W,H,pixels);
    }
    private static void eq(int actual,int expect){if(actual!=expect)throw new AssertionError(actual+" versus "+expect);}
    public static void main(String[] args) {
        ConversationOverlap start=page(0,0,false),down=page(0,47,false),right=page(43,47,false);
        eq(start.displacement(down,VIEW,false,1,1,null),47);
        eq(down.displacement(right,VIEW,true,1,1,null),43);
        eq(right.displacement(down,VIEW,true,-1,1,null),43);
        eq(down.displacement(start,VIEW,false,-1,1,null),47);
        eq(start.displacement(page(0,105,false),VIEW,false,1,1,105),-1);
        // A snapping carousel moves almost one card width on release. Its held
        // mid-drag position is required to connect both real visible extents.
        ConversationOverlap midway=page(45,0,false),snapped=page(105,0,false);
        eq(start.displacement(snapped,VIEW,true,1,1,105),-1);
        eq(start.displacement(midway,VIEW,true,1,1,45),45);
        eq(midway.displacement(snapped,VIEW,true,1,1,60),60);
        eq(start.displacement(page(0,2,true),VIEW,false,1,1,null),-1);
        if(!start.same(page(0,0,false),VIEW) || start.same(down,VIEW))throw new AssertionError("Stall detection");
        ConversationOverlap.Box originalStrip=new ConversationOverlap.Box(12,80,110,118);
        ConversationOverlap.Box translatedStrip=new ConversationOverlap.Box(12,33,110,71);
        if(!start.sameCrop(down,originalStrip,translatedStrip) || start.sameCrop(right,originalStrip,translatedStrip))
            throw new AssertionError("Complete horizontal origin must match after vertical repositioning");
        ConversationOverlap blank=page(0,0,true);
        if(!blank.equalTranslatedOverlap(blank,VIEW,24) || !down.equalTranslatedOverlap(right,VIEW,43)
                || down.equalTranslatedOverlap(down,VIEW,43) || down.equalTranslatedOverlap(right,VIEW,80)
                || down.equalTranslatedOverlap(right,VIEW,-43) || blank.equalTranslatedOverlap(start,VIEW,24))
            throw new AssertionError("Native horizontal delta must agree with all overlapping pixels.");
        if(!start.hasVisibleContent(VIEW) || blank.hasVisibleContent(VIEW))throw new AssertionError("Protected/blank screen");
        eq(blank.displacement(blank,VIEW,false,1,1,10),-1);
        System.out.println("Conversation overlap: forward and reverse for both axes, false jumps, blank and stationary views passed.");
    }
}
