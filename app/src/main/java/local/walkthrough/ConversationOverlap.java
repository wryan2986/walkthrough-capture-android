package local.walkthrough;

/** Registers only the overlapping, edge-rich pixels of two local luminance thumbnails. */
final class ConversationOverlap {
    static final class Box {
        final int left,top,right,bottom;
        Box(int left,int top,int right,int bottom){this.left=left;this.top=top;this.right=right;this.bottom=bottom;}
        boolean empty(){return left>=right || top>=bottom;}
        int width(){return right-left;} int height(){return bottom-top;}
    }
    final int width,height;
    private final byte[] gray;
    ConversationOverlap(int width,int height,byte[] gray){this.width=width;this.height=height;this.gray=gray;}
    boolean hasVisibleContent(Box area) {
        int lo=255,hi=0,edges=0;
        for(int y=area.top;y<area.bottom-1;y+=2)for(int x=area.left;x<area.right-1;x+=2) {
            int i=y*width+x,a=gray[i]&255;
            lo=Math.min(lo,a);hi=Math.max(hi,a);
            if(Math.abs(a-(gray[i+1]&255))+Math.abs(a-(gray[i+width]&255))>=18)edges++;
        }
        return hi-lo>=24 && edges>=4;
    }
    boolean same(ConversationOverlap next,Box r) {
        if(width!=next.width || height!=next.height)return false;
        if(r.empty())return false;
        int n=0,changed=0;long difference=0;
        for(int y=r.top;y<r.bottom;y++) for(int x=r.left;x<r.right;x+=2) {
            int index=y*width+x,d=Math.abs((gray[index]&255)-(next.gray[index]&255));n++;difference+=d;
            if(d>22)changed++;
        }
        return n>0 && difference<(long)n*1.4 && changed<=Math.max(2,n/250);
    }
    boolean sameCrop(ConversationOverlap next,Box old,Box fresh) {
        if(old.empty() || fresh.empty() || Math.abs(old.width()-fresh.width())>1 || Math.abs(old.height()-fresh.height())>1)return false;
        int w=Math.min(old.width(),fresh.width()),h=Math.min(old.height(),fresh.height());
        if(w<10 || h<6 || !hasVisibleContent(old))return false;
        long diff=0;int n=0,mismatches=0;
        for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x+=2) {
            int d=Math.abs((gray[(old.top+y)*width+old.left+x]&255)-(next.gray[(fresh.top+y)*next.width+fresh.left+x]&255));
            diff+=d;n++;if(d>38)mismatches++;
        }
        return n>0 && diff<n*10L && mismatches*9<n;
    }
    /** Returns physical displacement only when older and newer image regions are in register. */
    int displacement(ConversationOverlap next,Box box,boolean horizontal,int sign,float scale,Integer expected) {
        if(width!=next.width || height!=next.height)return -1;
        if(box.empty())return -1;
        int length=horizontal?box.width():box.height();
        int max=(int)(length*.64); if(max<2)return -1;
        float[] scores=new float[max+1];int best=0;
        for(int shift=0;shift<=max;shift++) {
            scores[shift]=error(next,box,horizontal?shift*sign:0,horizontal?0:shift*sign);
            if(scores[shift]<scores[best])best=shift;
        }
        if(expected!=null && expected>=2 && expected*scale<=max+1) {
            int guess=Math.min(max,Math.max(1,Math.round(expected*scale)));
            int pos=guess;
            // Compose pager pixel displacement can be slightly smaller than its
            // native logical offset. Confirm the nearest rendered translation.
            int tolerance=Math.max(2,Math.round(guess*.13f));
            for(int i=Math.max(1,guess-tolerance);i<=Math.min(max,guess+tolerance);i++)if(scores[i]<scores[pos])pos=i;
            if(scores[pos]<34)return Math.max(1,Math.round(pos/scale));
        }
        if(best==0 || scores[best]>28)return -1;
        float other=Float.POSITIVE_INFINITY;
        for(int i=0;i<=max;i++)if(Math.abs(i-best)>3)other=Math.min(other,scores[i]);
        if(other<scores[best]+2.0f)return -1;
        return Math.max(1,Math.round(best/scale));
    }
    private float error(ConversationOverlap next,Box box,int dx,int dy) {
        int left=Math.max(box.left,box.left-dx)+1,right=Math.min(box.right,box.right-dx)-1;
        int top=Math.max(box.top,box.top-dy)+1,bottom=Math.min(box.bottom,box.bottom-dy)-1;
        int count=0,sum=0;
        for(int y=top;y<bottom;y+=2)for(int x=left;x<right;x+=2) {
            int j=y*width+x,i=j+dy*width+dx,a=gray[i]&255,b=next.gray[j]&255;
            int edge=Math.abs(a-(gray[i+1]&255))+Math.abs(a-(gray[i+width]&255))
                    +Math.abs(b-(next.gray[j+1]&255))+Math.abs(b-(next.gray[j+width]&255));
            if(edge>=22) { count++;sum+=Math.min(120,Math.abs(a-b)); }
        }
        return count>=Math.max(8,(right-left)*(bottom-top)/1200)?(float)sum/count:Float.POSITIVE_INFINITY;
    }
}
