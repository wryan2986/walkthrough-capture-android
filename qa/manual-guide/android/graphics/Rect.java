package android.graphics;

/** Host-side rectangle operations used by the production planner in this isolated test. */
public final class Rect {
    public int left, top, right, bottom;
    public Rect() {}
    public Rect(Rect value) { this(value.left,value.top,value.right,value.bottom); }
    public Rect(int left,int top,int right,int bottom) { set(left,top,right,bottom); }
    public void set(int l,int t,int r,int b) { left=l; top=t; right=r; bottom=b; }
    public int width() { return right-left; }
    public int height() { return bottom-top; }
    public int centerX() { return (left+right)/2; }
    public int centerY() { return (top+bottom)/2; }
    public boolean isEmpty() { return left>=right || top>=bottom; }
    public void setEmpty() { set(0,0,0,0); }
    public boolean intersect(Rect b) {
        if(!intersects(this,b))return false;
        set(Math.max(left,b.left),Math.max(top,b.top),Math.min(right,b.right),Math.min(bottom,b.bottom));
        return true;
    }
    public boolean contains(Rect b) { return left<=b.left && top<=b.top && right>=b.right && bottom>=b.bottom; }
    @Override public boolean equals(Object o) {
        if (!(o instanceof Rect)) return false;
        Rect b=(Rect)o;
        return left==b.left && top==b.top && right==b.right && bottom==b.bottom;
    }
    @Override public int hashCode() { return java.util.Objects.hash(left,top,right,bottom); }
    public static boolean intersects(Rect a,Rect b) {
        return a.left<b.right && b.left<a.right && a.top<b.bottom && b.top<a.bottom;
    }
}
