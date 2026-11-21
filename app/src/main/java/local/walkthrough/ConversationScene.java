package local.walkthrough;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class ConversationScene {
    enum Kind { DISCLOSURE, MENU }
    static final class Control {
        final WalkTree.Item node, message;
        final Kind kind;
        final int action;
        final String token;
        Control(WalkTree.Item node,WalkTree.Item message,Kind kind,int action,String token) {
            this.node=node;this.message=message;this.kind=kind;this.action=action;this.token=token;
        }
    }
    private static final Pattern THINKING=Pattern.compile("^(?:thought|thinking|reasoned|reasoning)(?: for)? (?:[0-9]+ ?(?:s|m|h|secs?|mins?|seconds?|minutes?|hours?) ?){1,3}$");
    final WalkTree tree;
    final WalkTree.Item chat,list;
    final Rect screen,viewport;
    final List<WalkTree.Item> messages=new ArrayList<>();
    final int densityMargin;
    ConversationScene(WalkTree tree,Rect safe,int margin) {
        this.tree=tree;densityMargin=margin;screen=new Rect(safe);
        chat=byId("hatch-chat-view");
        WalkTree.Item shared=null;
        if(chat!=null) for(WalkTree.Item n:tree.all) if(isMessage(n) && n.inside(chat)) {
            if(shared!=null && n.parent!=shared)continue;
            shared=n.parent;messages.add(n);
        }
        WalkTree.Item scrollPane=shared;
        for(WalkTree.Item p=shared;p!=null && p!=chat;p=p.parent) {
            if(!p.kind.contains("Horizontal") && (p.node.isScrollable() || p.kind.endsWith(".ScrollView"))) {scrollPane=p;break;}
        }
        list=scrollPane;
        viewport=new Rect(safe);
        if(list==null || !viewport.intersect(list.bounds)) { viewport.setEmpty();return; }
        int header=viewport.top,footer=viewport.bottom;
        for(WalkTree.Item n:tree.items) {
            if(n.inside(list))continue;
            String label=norm(n.description);
            boolean upper=MuseProfile.avatarLabel(n.description) || n.id.equals("hatch-voice-call-button") || label.equals("open side panel");
            boolean lower=n.id.equals("hatch-message-input") || label.equals("voice input") || label.equals("add attachment");
            if(upper || lower) {
                for(WalkTree.Item p=n;p!=null;p=p.parent) {
                    if(p.bounds.height()>screen.height()*.38f)break;
                    if(upper && p.bounds.centerY()<screen.exactCenterY())header=Math.max(header,p.bounds.bottom);
                    if(lower && p.bounds.centerY()>screen.exactCenterY())footer=Math.min(footer,p.bounds.top);
                }
            } else if(label.equals("scroll to latest message") && n.bounds.centerY()>screen.centerY())
                footer=Math.min(footer,n.bounds.top);
        }
        viewport.top=Math.min(viewport.bottom,header+margin);
        viewport.bottom=Math.max(viewport.top,footer-margin);
    }
    static boolean isMessage(WalkTree.Item n) {
        return n.id.equals("hatch-message-user") || n.id.equals("hatch-message-assistant") || n.id.equals("hatch-message-assistant-latest");
    }
    WalkTree.Item byId(String id) {
        for(WalkTree.Item n:tree.items)if(n.id.equals(id))return n;
        return null;
    }
    boolean loading() {
        for(WalkTree.Item n:tree.items)if(n.kind.endsWith("ProgressBar") || norm(n.label).matches("loading (?:older |earlier |more )?messages[.…]*"))return true;
        return false;
    }
    private static String norm(String s) { return s.trim().toLowerCase(Locale.ROOT).replace('’','\''); }
    private static boolean menuLabel(String s) {
        return s.matches("(?:more (?:message |response )?(?:options|actions)|(?:message|response) (?:menu|options|actions)|open (?:message|response) menu)");
    }
    static boolean disclosureLabel(String s) {
        return s.matches("(?:show|view|see|expand) (?:thinking|thoughts|reasoning|reasoning summary|response details|thinking details|details|more)"
                +"|read more|about this response|thinking|thoughts|reasoning") || THINKING.matcher(s).matches();
    }
    private WalkTree.Item messageOf(WalkTree.Item n) {
        for(WalkTree.Item p=n;p!=null;p=p.parent)if(isMessage(p))return p;
        return null;
    }
    List<Control> controls() {
        Map<String,Control> result=new LinkedHashMap<>();
        for(WalkTree.Item peer:tree.items) {
            WalkTree.Item msg=messageOf(peer);
            if(msg==null || !Rect.intersects(peer.bounds,viewport))continue;
            String caption=norm(peer.description.isEmpty()?peer.label:peer.description);
            Kind kind=menuLabel(caption)?Kind.MENU:disclosureLabel(caption)?Kind.DISCLOSURE:null;
            if(kind==null || (kind==Kind.DISCLOSURE && msg.id.equals("hatch-message-user")))continue;
            WalkTree.Item button=peer;
            while(button!=null && button!=msg && !button.has(WalkTree.CLICK) && !button.has(WalkTree.EXPAND)
                    && !button.kind.endsWith("Button"))button=button.parent;
            if(button==null || button==msg || !button.inside(msg) || navigationLink(peer,msg))continue;
            result.putIfAbsent(button.path,new Control(button,msg,kind,button.has(WalkTree.EXPAND)?WalkTree.EXPAND:WalkTree.CLICK,caption));
        }
        for(WalkTree.Item m:messages)if(m.node.isVisibleToUser() && m.has(AccessibilityNodeInfo.ACTION_LONG_CLICK) && Rect.intersects(viewport,m.bounds)) {
            result.putIfAbsent(m.path,new Control(m,m,Kind.MENU,AccessibilityNodeInfo.ACTION_LONG_CLICK,"long press message"));
        }
        return new ArrayList<>(result.values());
    }
    static boolean navigationLink(WalkTree.Item leaf,WalkTree.Item message) {
        for(WalkTree.Item p=leaf;p!=null && p!=message;p=p.parent) {
            CharSequence text=p.node.getText();
            if(text instanceof android.text.Spanned) {
                android.text.Spanned span=(android.text.Spanned)text;
                if(span.getSpans(0,span.length(),android.text.style.ClickableSpan.class).length!=0)return true;
            }
            if(p.kind.contains("WebView") || p.id.contains("work-preview") || p.id.contains("activity-action")
                    || norm(p.node.getExtras().getString("AccessibilityNodeInfo.roleDescription","")).contains("link"))return true;
        }
        return false;
    }
    static String role(WalkTree.Item m) { return m.id.equals("hatch-message-user")?"user":"assistant"; }
    static String readableText(WalkTree tree,WalkTree.Item message) {
        StringBuilder text=new StringBuilder();
        for(WalkTree.Item n:tree.all)if(n.inside(message) && n.node.getChildCount()==0 && !n.has(WalkTree.CLICK)) {
            CharSequence words=n.node.getText();
            if(words!=null && !disclosureLabel(norm(words.toString())))text.append(words).append(' ');
            if(text.length()>64000)break;
        }
        return text.toString().trim().replaceAll("\\s+"," ");
    }
    static String contentMark(WalkTree tree,WalkTree.Item msg) {
        // Native IDs are shared across chat bubbles. Keep private text out of files and status.
        StringBuilder b=new StringBuilder(role(msg));
        for(WalkTree.Item n:tree.all)if(n.inside(msg) && !n.label.isEmpty())b.append(n.label.length()).append(':').append(n.label);
        try {
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(hash,android.util.Base64.NO_WRAP);
        } catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 unavailable",ex);}
    }
    static boolean paged(WalkTree.Item n) {
        AccessibilityNodeInfo.CollectionInfo grid=n.node.getCollectionInfo();
        return grid!=null && grid.getRowCount()==1 && grid.getColumnCount()>1
                && (n.has(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.getId())
                    || n.has(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.getId()));
    }
    static int direction(WalkTree.Item n,int d) {
        if(WalkScroll.horizontal(d) && paged(n)) {
            int left=AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();
            int right=AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
            if(n.has(left) || n.has(right)) {
                int action=d==WalkTree.LEFT?left:right;
                return n.has(action)?action:0;
            }
        }
        return n.scrollAction(d);
    }
    List<WalkTree.Item> lanes(WalkTree.Item scope,Rect window) {
        List<WalkTree.Item> out=new ArrayList<>();
        for(WalkTree.Item n:tree.items) if(n!=scope && n.inside(scope) && (n.node.isScrollable() || n.scrollAction(WalkTree.RIGHT)!=0)
                && (n.kind.contains("Horizontal") || n.scrollAction(WalkTree.RIGHT)!=0 || n.scrollAction(WalkTree.LEFT)!=0)
                && Rect.intersects(window,n.bounds))out.add(n);
        return out;
    }
    WalkTree.Item modalRoot() {
        for(WalkTree.Item n:tree.items)if(n.id.equals("android:id/parentPanel") || n.kind.contains("Dialog") || n.kind.contains("PopupWindow")
                || n.kind.contains("ListPopupWindow") || n.kind.contains("BottomSheet"))return n;
        for(WalkTree.Item n:tree.items) {
            String s=norm(n.description);
            if((s.equals("close sheet") || s.equals("dismiss") || s.equals("close menu") || s.equals("close details"))
                    && (n.has(WalkTree.CLICK) || n.clickable()!=null))return tree.all.get(0);
        }
        // Some native dialogs expose the title and actions but omit the decorative
        // parentPanel from the accessibility service's visible node hierarchy.
        if(chat==null) {
            boolean close=false, detail=false, editable=false;
            for(WalkTree.Item n:tree.items) {
                close|=controlLabel(n).equals("close") && n.has(WalkTree.CLICK);
                detail|=isDetailHeading(n);
                editable|=n.node.isEditable();
            }
            if(close && detail && !editable)return tree.all.get(0);
        }
        int commands=0;
        for(WalkTree.Item n:tree.items)if(messageOf(n)==null && norm(n.label).matches("copy(?: text| message| response)?|report(?: a concern)?|share(?: message| response)?|edit message|regenerate response"))
            commands++;
        return commands>=2?tree.all.get(0):null;
    }
    boolean panelNode(WalkTree.Item n,WalkTree.Item modal) {
        // An in-window sheet can share the window root with the covered editable chat.
        // Only independently identified sheet descendants may bypass that exclusion.
        return n.inside(modal) && (modal!=tree.all.get(0) || chat==null || !n.inside(chat));
    }
    WalkTree.Item modalPane(WalkTree.Item modal) {
        WalkTree.Item best=null;
        for(WalkTree.Item n:tree.items)if(panelNode(n,modal)
                && !n.kind.contains("Horizontal") && n.node.isScrollable() && n.bounds.height()>densityMargin*10
                && (best==null || n.bounds.height()>best.bounds.height()))best=n;
        return best;
    }
    static boolean isDetailHeading(WalkTree.Item n) {
        return !n.has(WalkTree.CLICK) && controlLabel(n).matches(
                "thinking|thoughts|reasoning|reasoning summary|thinking details|response details|details|about this response");
    }
    static String controlLabel(WalkTree.Item n) { return norm(n.description.isEmpty()?n.label:n.description); }
    static boolean chromeOnly(String s) { return s.matches("copy(?: text| message| response)?|share(?: message| response)?|edit(?: message)?|delete(?: message)?|regenerate(?: response)?|report(?: a concern)?|thumbs (up|down)|more options"); }
}
