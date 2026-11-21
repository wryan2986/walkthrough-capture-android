package local.walkthrough;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The phone stores one configuration. Each capture works from an independent snapshot. */
final class PrivacyMasks {
    static final class Settings {
        final List<RectF> regions;
        final List<String> terms;
        final boolean emails, phones;

        Settings(List<RectF> regions,boolean emails,boolean phones,List<String> terms) {
            ArrayList<RectF> copy=new ArrayList<>();
            for(RectF region:regions) {
                if(region==null || !Float.isFinite(region.left) || !Float.isFinite(region.top)
                        || !Float.isFinite(region.right) || !Float.isFinite(region.bottom)
                        || region.left<0 || region.top<0 || region.right>1 || region.bottom>1 || region.isEmpty())
                    throw new IllegalArgumentException("Choose a privacy rectangle inside the display.");
                copy.add(new RectF(region));
            }
            this.regions=Collections.unmodifiableList(copy);
            this.terms=Collections.unmodifiableList(new ArrayList<>(terms));
            this.phones=phones; this.emails=emails;
        }
        static Settings read(Context c) {
            SharedPreferences p=c.getSharedPreferences("privacy",Context.MODE_PRIVATE);
            List<RectF> regions=new ArrayList<>(); List<String> terms=new ArrayList<>();
            try {
                JSONArray r=new JSONArray(p.getString("regions","[]"));
                for(int i=0;i<r.length();i++) {
                    JSONArray b=r.getJSONArray(i);
                    regions.add(new RectF((float)b.getDouble(0),(float)b.getDouble(1),
                            (float)b.getDouble(2),(float)b.getDouble(3)));
                }
                JSONArray t=new JSONArray(p.getString("terms","[]"));
                for(int i=0;i<t.length();i++)terms.add(t.getString(i));
                return new Settings(regions,p.getBoolean("emails",false),p.getBoolean("phones",false),terms);
            } catch(JSONException | IllegalArgumentException error) {
                throw new IllegalStateException("The saved privacy settings could not be read. Review and save the masks again.");
            }
        }
        void save(Context c) {
            try {
                JSONArray r=new JSONArray();
                for(RectF b:regions)r.put(new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom));
                c.getSharedPreferences("privacy",Context.MODE_PRIVATE).edit().putString("regions",r.toString())
                        .putString("terms",new JSONArray(terms).toString()).putBoolean("emails",emails)
                        .putBoolean("phones",phones).apply();
            } catch(JSONException e) { throw new IllegalArgumentException("Choose valid privacy coordinates."); }
        }
    }
    final Settings settings;
    private final PrivacyTextRules text;
    private PrivacyMasks(Settings s) {
        settings=s; text=new PrivacyTextRules(s.emails,s.phones,s.terms);
    }
    static PrivacyMasks read(Context c) { return new PrivacyMasks(Settings.read(c)); }
    boolean hasRegions() { return !settings.regions.isEmpty(); }
    static List<Rect> fixed(List<RectF> normalized,Rect display) {
        ArrayList<Rect> result=new ArrayList<>(normalized.size());
        for(RectF b:normalized)result.add(new Rect(display.left+(int)Math.floor(b.left*display.width()),
                display.top+(int)Math.floor(b.top*display.height()),display.left+(int)Math.ceil(b.right*display.width()),
                display.top+(int)Math.ceil(b.bottom*display.height())));
        return result;
    }
    List<Rect> regionsFor(WalkTree tree,Rect display,boolean includeText) {
        List<Rect> result=fixed(settings.regions,display);
        if(includeText && text.enabled())for(WalkTree.Item n:tree.items) {
            if(!n.node.isPassword() && !text.matches(n.label) && !text.matches(n.description))continue;
            Rect r=new Rect(n.bounds);
            r.inset(-4,-4);
            if(r.intersect(display))result.add(r);
        }
        return result;
    }
    static void paint(Bitmap image,List<Rect> regions) {
        if(regions.isEmpty())return;
        Canvas canvas=new Canvas(image); Paint ink=new Paint(); ink.setColor(Color.BLACK);
        for(Rect region:regions)canvas.drawRect(region,ink);
    }
}
