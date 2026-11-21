package local.walkthrough;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Chronological, offline capture of the rendered Muse message list and its exposed read-only controls. */
final class ConversationHistory {
    interface Device {
        void save(File candidate) throws IOException;
        void pause(String status,long delay);
        void swipe(WalkTree.Item pane,Rect area,int direction,float fraction);
        void back();
        void trace(String event);
        boolean releasePointer();
        boolean nativeScroll(WalkTree.Item pane,int direction);
        void finish(boolean ok,String reason);
    }
    private enum Phase { START, REWIND, READ, OPENING, RETURNING }
    private enum Job { OLDER, DOWN, INITIAL_LEFT, RIGHT, RESTORE_LEFT }
    private static final class Page {
        boolean saved; int initial,lane,rightSteps,restoreSteps; boolean returningLeft;
        ConversationPixels canonical;
        final List<String> paths=new ArrayList<>();
        void nextLane(){lane++;returningLeft=false;rightSteps=0;restoreSteps=0;}
        void reset(){saved=false;canonical=null;initial=0;lane=0;rightSteps=0;restoreSteps=0;returningLeft=false;paths.clear();}
    }
    private static final class CoveredLane {
        final String content;
        final Rect bounds;
        final int documentY;
        final ConversationPixels image;
        CoveredLane(String content,Rect bounds,int documentY,ConversationPixels image) {
            this.content=content;this.bounds=new Rect(bounds);this.documentY=documentY;this.image=image;
        }
    }
    private final ArrayDeque<CoveredLane> fullLanes=new ArrayDeque<>();
    private int reusedLanes;
    private boolean covered(ConversationScene scene,WalkTree.Item n,Rect area,ConversationPixels raw) {
        if(panel || ConversationScene.direction(n,WalkTree.LEFT)!=0)return false;
        int doc=verticalAt+n.bounds.centerY()-area.top;
        String content=ConversationScene.contentMark(scene.tree,n);
        Rect fresh=intersection(n.bounds,area);
        for(CoveredLane c:fullLanes) {
            if(!content.equals(c.content) || Math.abs(doc-c.documentY)>42 || n.bounds.width()!=c.bounds.width()
                    || n.bounds.height()!=c.bounds.height())continue;
            Rect previous=new Rect(fresh);previous.offset(c.bounds.left-n.bounds.left,c.bounds.top-n.bounds.top);
            if(c.image.sameCrop(raw,previous,fresh))return true;
        }
        return false;
    }
    private void remember(ConversationScene scene,WalkTree.Item n,Rect area,ConversationPixels restored) {
        if(panel || page.canonical==null || !area.contains(n.bounds) || !page.canonical.sameCrop(restored,n.bounds,n.bounds))return;
        if(fullLanes.size()>=12)fullLanes.removeLast();
        fullLanes.addFirst(new CoveredLane(ConversationScene.contentMark(scene.tree,n),n.bounds,
                verticalAt+n.bounds.centerY()-area.top,page.canonical));
    }
    private static final class Motion {
        final ConversationPixels before;
        final Rect area;
        final Job job;
        final int direction;
        final String lane;
        final Integer oldPosition;
        int repairs,stationary;
        Motion(ConversationPixels before,Rect area,Job job,int direction,WalkTree.Item pane,WalkScroll scroll) {
            this.before=before;this.area=new Rect(area);this.job=job;this.direction=direction;
            lane=pane.path;
            Integer base=scroll.position(pane,direction);
            if(base==null && direction==WalkTree.RIGHT && ConversationScene.direction(pane,WalkTree.LEFT)==0)base=0;
            oldPosition=base;
        }
    }
    private static final class PanelParent {
        final ConversationPixels pixels;
        final Rect area;
        final int window,id,position;
        final Set<String> collapses;
        PanelParent(ConversationPixels pixels,Rect area,int window,int id,int position,Set<String> collapses) {
            this.pixels=pixels;this.area=new Rect(area);this.window=window;this.id=id;this.position=position;this.collapses=collapses;
        }
    }
    private static final class Seen {
        final String token; final int coordinate; final String role;
        Seen(String token,String role,int coordinate){this.token=token;this.role=role;this.coordinate=coordinate;}
    }
    private final Device device;
    private final WalkScroll scroll;
    private Phase phase=Phase.START;
    private final Page page=new Page();
    private Motion motion;
    private Motion recovering;
    private ConversationPixels recoveryLast;
    private int recoveryStalls,recoveryAttempts,unverifiedLanes;
    private int edgeWait,verticalAt,viewportCount,horizontalCount,opened,disabled,menuChoices,disabledChoices,declined;
    private int menuPages,expanded,rewound,knownRows=-1,firstWindow=-1,waits;
    private int verticalMoves,horizontalMoves,minVerticalOverlap=100,minHorizontalOverlap=100;
    private boolean cameFromActivity,panel,saveColumn;
    private final Set<String> menuOptionVisits=new HashSet<>();
    private final ArrayDeque<PanelParent> panelParents=new ArrayDeque<>();
    private PanelParent pendingPanel;
    private int panelId,panelPosition;
    private final List<Seen> controlsSeen=new ArrayList<>();
    private ConversationPixels origin,edgeFrame;
    private Rect parentArea;
    private String originRole="";
    private int originalTreeHash;
    private String originMessagePath,originalWords;
    private int originMessageTop;
    private int originWindow=-1;
    private boolean physicalRewind;
    boolean readingBack(){return !panel && (phase==Phase.START || phase==Phase.REWIND);}

    private ConversationScene.Kind actionKind;

    ConversationHistory(Device device,WalkScroll scroll){this.device=device;this.scroll=scroll;}
    boolean needFile(){return phase!=Phase.START && phase!=Phase.REWIND;}
    private void waitFor(String description){device.pause(description,270);}
    private static void fail(String why){throw new IllegalStateException(why);}
    private static int opposite(int dir){
        if(dir==WalkTree.UP)return WalkTree.DOWN;if(dir==WalkTree.DOWN)return WalkTree.UP;
        return dir==WalkTree.LEFT?WalkTree.RIGHT:WalkTree.LEFT;
    }
    void frame(WalkTree tree,Rect safe,int margin,ConversationPixels raw,File candidate) throws IOException {
        ConversationScene scene=new ConversationScene(tree,safe,margin);
        if(phase==Phase.START) {
            if(scene.chat==null || scene.list==null) {
                if(!cameFromActivity && scene.byId(MuseProfile.ACTIVITY)!=null && MuseProfile.control(tree,"Dismiss")==null) {
                    cameFromActivity=true;device.back();waitFor("Returning from Activity to the conversation.");return;
                }
                if(++waits<5){waitFor("Waiting for the Muse conversation.");return;}
                fail("A Muse conversation with readable message rows could not be verified.");
            }
            firstWindow=scene.list.node.getWindowId();phase=Phase.REWIND;waits=0;
        }
        if(phase==Phase.OPENING){openedState(scene,raw,candidate);return;}
        if(phase==Phase.RETURNING){returned(scene,raw);return;}
        if(!panel && (scene.chat==null || scene.list==null || scene.list.node.getWindowId()!=firstWindow))
            fail("The active conversation changed during capture.");
        if(scene.loading()){
            if(++waits>35)fail("An older-message loading indicator did not finish.");waitFor("Waiting for messages to load.");return;
        }
        waits=0;
        WalkTree.Item scope=panel?scene.modalRoot():scene.list;
        if(scope==null)fail("The open message panel changed unexpectedly.");
        WalkTree.Item vertical=panel?scene.modalPane(scope):scene.list;
        Rect area=panel?new Rect(vertical==null?scope.bounds:vertical.bounds):new Rect(scene.viewport);
        if(!area.intersect(safe) || area.width()<48 || area.height()<48)fail("The conversation reading area is too small.");
        if(vertical!=null && vertical.node.isScrollable() && vertical.scrollAction(WalkTree.UP)==0 && vertical.scrollAction(WalkTree.DOWN)==0)
            fail("The scrollable reading area did not expose either vertical direction.");
        if(!panel && scene.list.node.getCollectionInfo()!=null) {
            int rows=scene.list.node.getCollectionInfo().getRowCount();knownRows=Math.max(knownRows,rows);
            if(rows>scene.messages.size() && scene.list.scrollAction(WalkTree.UP)==0 && scene.list.scrollAction(WalkTree.DOWN)==0)
                fail("The message count exceeded the exposed rows without a direction to reach them.");
        }
        if(recovering!=null && !restoreOrigin(scene,scope,area,raw))return;
        if(motion!=null && !settleMotion(scene,vertical,area,raw))return;
        if(phase==Phase.REWIND) {
            if(vertical!=null && vertical.scrollAction(WalkTree.UP)!=0) {
                move(raw,vertical,area,Job.OLDER,WalkTree.UP,.52f);return;
            }
            if(!edgeSettled(raw,area))return;
            phase=Phase.READ;page.reset();waitFor("Capturing the oldest accessible conversation viewport.");return;
        }
        List<WalkTree.Item> lanes=scene.lanes(scope,area);
        if(panel)lanes.removeIf(n -> !scene.panelNode(n,scope));
        if(!page.saved) {
            if(!raw.hasVisibleContent(area))fail("Android returned a featureless conversation viewport; readable visual coverage could not be verified.");
            if(page.paths.isEmpty()) for(WalkTree.Item lane:lanes) {
                Rect visible=intersection(lane.bounds,area);
                // A pane just entering at the bottom will remain in the next verified
                // overlap. Wait until its content is above the fixed composer and arrow.
                boolean entering=lane.bounds.bottom>area.bottom && lane.bounds.top>area.top+area.height()*.67f
                        && vertical!=null && vertical.scrollAction(WalkTree.DOWN)!=0;
                if(!entering && visible.width()>=64 && visible.height()>=20) {
                    if(covered(scene,lane,area,raw))reusedLanes++;else page.paths.add(lane.path);
                }
            }
            while(page.initial<page.paths.size()) {
                WalkTree.Item lane=lane(lanes,page.paths.get(page.initial));
                if(ConversationScene.direction(lane,WalkTree.LEFT)!=0 && !edgeKnown(lane,WalkTree.LEFT)) {
                    move(raw,lane,intersection(lane.bounds,area),Job.INITIAL_LEFT,WalkTree.LEFT,.37f);return;
                }
                page.initial++;
            }
            device.save(candidate);page.saved=true;page.canonical=raw;
            if(panel)menuPages++;else viewportCount++;
        } else if(saveColumn) {
            device.save(candidate);saveColumn=false;horizontalCount++;
        }
        while(page.lane<page.paths.size()) {
            WalkTree.Item lane=lane(lanes,page.paths.get(page.lane));
            Rect box=intersection(lane.bounds,area);
            if(!page.returningLeft){
                if(ConversationScene.direction(lane,WalkTree.RIGHT)!=0 && !edgeKnown(lane,WalkTree.RIGHT)){
                    move(raw,lane,box,Job.RIGHT,WalkTree.RIGHT,.38f);return;
                }
                if(device.releasePointer()){waitFor("Confirming the settled carousel endpoint.");return;}
                page.returningLeft=true;
            }
            if(ConversationScene.direction(lane,WalkTree.LEFT)!=0 && !edgeKnown(lane,WalkTree.LEFT)){
                Integer offset=scroll.position(lane,WalkTree.LEFT);
                if(offset!=null && offset==0 && page.canonical.same(raw,box) && page.canonical.sameCrop(raw,box,box)) {
                    if(device.releasePointer()){waitFor("Confirming the restored carousel origin.");return;}
                    remember(scene,lane,area,raw);page.nextLane();continue;
                }
                if(++page.restoreSteps>page.rightSteps*2+8) {
                    beginOriginRecovery(new Motion(raw,box,Job.RESTORE_LEFT,WalkTree.LEFT,lane,scroll));return;
                }
                move(raw,lane,box,Job.RESTORE_LEFT,WalkTree.LEFT,.38f);return;
            }
            if(device.releasePointer()){waitFor("Confirming the restored carousel origin.");return;}
            remember(scene,lane,area,raw);page.nextLane();
        }
        if(panel) {if(readPanel(scene,scope,area,raw))return;}
        else for(ConversationScene.Control c:scene.controls()) {
            Rect visible=intersection(c.node.bounds,area);
            if(visible.height()<10 || visible.width()<10)continue;
            String identity=ConversationScene.contentMark(tree,c.message);
            int where=verticalAt+c.node.bounds.centerY()-area.top;
            if(already(c.token,identity,where))continue;
            controlsSeen.add(new Seen(c.token,identity,where));
            if(!c.node.node.isEnabled() || !c.node.has(c.action)) {disabled++;continue;}
            origin=raw;parentArea=new Rect(area);originWindow=c.node.node.getWindowId();originRole=ConversationScene.role(c.message);
            actionKind=c.kind;waits=0;
            if(!c.node.node.performAction(c.action)){declined++;continue;}
            originMessagePath=c.message.path;originMessageTop=c.message.bounds.top;
            originalWords=c.kind==ConversationScene.Kind.DISCLOSURE?ConversationScene.readableText(tree,c.message):"";
            originalTreeHash=tree.signature.hashCode();
            device.trace("message control kind="+c.kind+" action="+c.action+" role="+originRole+" view="+viewportCount);
            phase=Phase.OPENING;waitFor("Verifying the exposed message control.");return;
        }
        if(!panel && vertical!=null) {
            AccessibilityNodeInfo.CollectionInfo info=vertical.node.getCollectionInfo();
            if(info!=null && knownRows>0 && info.getRowCount()>0 && info.getRowCount()<knownRows)
                fail("The conversation row count decreased before the capture finished.");
        }
        if(vertical!=null && vertical.scrollAction(WalkTree.DOWN)!=0 && !edgeKnown(vertical,WalkTree.DOWN)){
            move(raw,vertical,area,Job.DOWN,WalkTree.DOWN,.40f);return;
        }
        if(!edgeSettled(raw,area))return;
        if(panel) {
            phase=Phase.RETURNING;waits=0;
            device.back();waitFor("Returning from the verified message panel.");return;
        }
        boolean complete=declined==0 && unverifiedLanes==0;
        device.finish(complete,complete?"Reached the newest exposed message after a verified rewind and overlapping capture."
                :"Reached the newest message with one or more unresolved disclosures or horizontal panes.");
    }
    private boolean edgeKnown(WalkTree.Item n,int direction){
        // Muse virtualizes vertical offsets and may report a synthetic max-Y on interior pages.
        // A genuinely absent directional action is authoritative on that axis.
        return WalkScroll.horizontal(direction) && scroll.atEdge(n,direction);
    }
    private static Rect intersection(Rect a,Rect b){Rect c=new Rect(a);if(!c.intersect(b))c.setEmpty();return c;}
    private static WalkTree.Item lane(List<WalkTree.Item> lanes,String path) {
        for(WalkTree.Item n:lanes)if(n.path.equals(path))return n;
        throw new IllegalStateException("A visible horizontal pane changed before its complete traversal.");
    }
    private boolean edgeSettled(ConversationPixels raw,Rect area) {
        if(edgeFrame==null || !edgeFrame.same(raw,area))edgeWait=0;
        edgeFrame=raw;
        if(++edgeWait<3){device.pause("Confirming the exposed conversation boundary.",530);return false;}
        edgeFrame=null;edgeWait=0;return true;
    }
    private void move(ConversationPixels raw,WalkTree.Item pane,Rect area,Job job,int dir,float fraction) {
        edgeWait=0;edgeFrame=null;motion=new Motion(raw,area,job,dir,pane,scroll);
        // Some virtual lists acknowledge a native scroll while displaying the same messages.
        // Keep the fast native rewind only while its rendered viewport actually changes.
        if(job==Job.OLDER && !panel && !physicalRewind && device.nativeScroll(pane,dir))return;
        if((job==Job.INITIAL_LEFT || job==Job.RESTORE_LEFT) && device.nativeScroll(pane,WalkTree.LEFT))return;
        if(WalkScroll.horizontal(dir) && !ConversationScene.paged(pane)) {
            Integer remaining=dir==WalkTree.RIGHT?scroll.remaining(pane,dir):scroll.position(pane,dir);
            // A finger lift can leave Android's table at maxX-1 after overscroll.
            // Ask the identified native pane to finish those final pixels.
            if(remaining!=null && remaining>0 && remaining<=12 && device.nativeScroll(pane,dir))return;
        }
        device.swipe(pane,area,dir,fraction);
    }
    private boolean settleMotion(ConversationScene scene,WalkTree.Item vertical,Rect viewport,ConversationPixels raw) {
        Motion m=motion;
        WalkTree.Item pane=vertical;
        if(WalkScroll.horizontal(m.direction)) {
            pane=null;
            for(WalkTree.Item n:scene.tree.items)if(n.path.equals(m.lane)){pane=n;break;}
        }
        if(pane==null)fail("The scrolling pane disappeared before overlap could be verified.");
        Rect area=intersection(m.area,WalkScroll.horizontal(m.direction)?pane.bounds:viewport);
        if(area.isEmpty())fail("The scrolling viewport changed before its overlap could be verified.");
        boolean stationary=m.before.same(raw,area);
        boolean nativeEdge=ConversationScene.direction(pane,m.direction)==0 || edgeKnown(pane,m.direction);
        if(m.job==Job.OLDER && !stationary) {
            rewound++;
            if(rewound%8==0)device.trace("verified rewind moves="+rewound+" rows="+knownRows);
            motion=null;scroll.release();return true;
        }
        Integer after=scroll.position(pane,m.direction);
        if((m.job==Job.RESTORE_LEFT || m.job==Job.INITIAL_LEFT) && !stationary && after!=null
                && (m.oldPosition!=null?after<m.oldPosition:scroll.moved || nativeEdge)) {
            motion=null;scroll.release();return true;
        }
        Integer expected=m.oldPosition!=null && after!=null?Math.abs(m.oldPosition-after):null;
        int distance=stationary?-1:m.before.displacement(raw,area,m.direction,expected);
        // Repeated or blank cells have no unique visual displacement. Require a
        // scroll event from this exact pane, a bounded native delta and translated
        // pixel agreement throughout the overlapping portion before advancing.
        if(distance<=0 && scroll.moved && m.job==Job.RIGHT && m.oldPosition!=null && after!=null
                && after>m.oldPosition && expected<=area.width()*.60 && m.before.equalTranslatedOverlap(raw,area,expected))distance=expected;
        if(distance<=0 && WalkScroll.horizontal(m.direction) && nativeEdge && expected!=null && expected>0 && expected<=4
                && (stationary || m.before.sameCrop(raw,area,area))
                && ((m.direction==WalkTree.RIGHT && after>m.oldPosition) || (m.direction==WalkTree.LEFT && after<m.oldPosition)))
            distance=expected;
        if(WalkScroll.horizontal(m.direction))device.trace("settled "+m.job+" dir="+m.direction+" before="+m.oldPosition+
                " after="+after+" edge="+nativeEdge+" same="+stationary+" registered="+distance+" bounds="+area.flattenToString());
        if(distance>0) {
            motion=null;scroll.release();
            if(m.job==Job.DOWN) {
                page.reset();if(panel)panelPosition+=distance;else verticalAt+=distance;
                verticalMoves++;minVerticalOverlap=Math.min(minVerticalOverlap,100-(int)Math.ceil(distance*100.0/area.height()));
            }
            if(m.job==Job.RIGHT) {
                saveColumn=true;horizontalMoves++;page.rightSteps++;
                minHorizontalOverlap=Math.min(minHorizontalOverlap,100-(int)Math.ceil(distance*100.0/area.width()));
            }
            return true;
        }
        if(stationary && nativeEdge) { motion=null;scroll.release();return true; }
        if(stationary) {
            if(m.job==Job.OLDER)physicalRewind=true;
            if(++m.stationary>2) {
                if(WalkScroll.horizontal(m.direction))return beginOriginRecovery(m);
                fail("The target still advertised vertical scrolling but repeated gestures produced no verified movement.");
            }
            device.swipe(pane,area,m.direction,m.stationary==1?.29f:.19f);return false;
        }
        if(++m.repairs>4) {
            if(WalkScroll.horizontal(m.direction))return beginOriginRecovery(m);
            fail("Could not prove overlap between successive vertical viewports.");
        }
        // An unexpected fling can overshoot. Move toward the last retained frame in
        // shorter increments until part of that exact frame is visible again.
        int reverse=m.before.displacement(raw,area,opposite(m.direction),null);
        device.swipe(pane,intersection(pane.bounds,area),reverse>0?m.direction:opposite(m.direction),.18f);
        return false;
    }
    private boolean beginOriginRecovery(Motion failed) {
        recovering=failed;motion=null;recoveryLast=null;recoveryStalls=0;recoveryAttempts=0;scroll.release();saveColumn=false;
        if(failed.job==Job.RIGHT)unverifiedLanes++;
        device.releasePointer();device.pause("Restoring the horizontal origin before continuing.",660);return false;
    }
    private boolean restoreOrigin(ConversationScene scene,WalkTree.Item scope,Rect area,ConversationPixels raw) {
        Motion failed=recovering;
        WalkTree.Item pane=lane(scene.lanes(scope,area),failed.lane);
        Rect box=intersection(pane.bounds,area);
        boolean left=ConversationScene.direction(pane,WalkTree.LEFT)==0 || edgeKnown(pane,WalkTree.LEFT);
        if(left && (failed.job==Job.INITIAL_LEFT || page.canonical!=null && (page.canonical.same(raw,box) || page.canonical.sameCrop(raw,box,box)))) {
            scroll.release();recovering=null;recoveryLast=null;
            if(failed.job==Job.INITIAL_LEFT)page.initial++;
            else {
                if(failed.job==Job.RESTORE_LEFT)remember(scene,pane,area,raw);
                page.nextLane();
            }
            return true;
        }
        if(recoveryLast!=null && recoveryLast.same(raw,box))recoveryStalls++;else recoveryStalls=0;
        if(++recoveryAttempts>8 || recoveryStalls>2 || left) {
            // A misreporting carousel can keep moving laterally without ever
            // returning to its origin. Continue only if independent message
            // pixels above or below the pane prove the parent is still in place.
            ConversationPixels anchor=page.canonical==null?failed.before:page.canonical;
            Rect above=new Rect(area.left,area.top,area.right,box.top);
            Rect below=new Rect(area.left,box.bottom,area.right,area.bottom);
            if(!(anchor.same(raw,above) && anchor.sameCrop(raw,above,above)) &&
                    !(anchor.same(raw,below) && anchor.sameCrop(raw,below,below)))
                fail("The horizontal origin and the surrounding chat position could not be verified.");
            if(failed.job!=Job.RIGHT)unverifiedLanes++;
            if(failed.job==Job.INITIAL_LEFT)page.paths.remove(page.initial);else page.nextLane();
            scroll.release();recovering=null;recoveryLast=null;
            device.trace("continued beyond unverified horizontal pane after "+recoveryAttempts+" bounded recovery attempts");
            return true;
        }
        recoveryLast=raw;
        if(recoveryAttempts%2==0 || !device.nativeScroll(pane,WalkTree.LEFT))device.swipe(pane,box,WalkTree.LEFT,.33f);
        return false;
    }
    private boolean already(String token,String role,int coordinate) {
        for(Seen s:controlsSeen)if(s.token.equals(token) && s.role.equals(role) && Math.abs(s.coordinate-coordinate)<54)return true;
        return false;
    }
    private void openedState(ConversationScene scene,ConversationPixels raw,File candidate) throws IOException {
        WalkTree.Item dialog=scene.modalRoot();
        if(pendingPanel!=null){verifyNested(scene,dialog,raw);return;}
        if(dialog!=null || scene.chat==null && scene.tree.all.get(0).node.getWindowId()!=originWindow) {
            if(dialog==null || !readOnlyDestination(scene,dialog))
                fail("A message action left the chat without a recognized read-only panel.");
            panel=true;panelId=++opened;panelPosition=0;phase=Phase.REWIND;page.reset();waits=0;waitFor("Capturing the opened message panel.");return;
        }
        if(scene.chat!=null && actionKind==ConversationScene.Kind.DISCLOSURE && !origin.same(raw,parentArea)) {
            boolean inline=false;
            for(WalkTree.Item m:scene.messages)if(m.path.equals(originMessagePath) && ConversationScene.role(m).equals(originRole)) {
                for(WalkTree.Item n:scene.tree.items)if(n.inside(m) && collapseLabel(n)){inline=true;break;}
                if(!inline && Math.abs(m.bounds.top-originMessageTop)<24) {
                    // Some one-way “Read more” controls vanish once their original
                    // text is extended in place. Match the same row and its text.
                    String before=originalWords.replaceFirst("[.…\\s]+$","");
                    String now=ConversationScene.readableText(scene.tree,m);
                    boolean remaining=false;
                    for(ConversationScene.Control c:scene.controls())if(c.message==m && c.kind==actionKind){remaining=true;break;}
                    inline=!remaining && before.length()>=24 && now.length()>=before.length()+16 && now.startsWith(before);
                }
            }
            if(inline) {
                expanded++;phase=Phase.READ;page.reset();origin=null;waits=0;
                waitFor("Capturing the expanded message content.");return;
            }
        }
        if(++waits<4){waitFor("Waiting for the message control to settle.");return;}
        device.trace("unverified control kind="+actionKind+" chat="+(scene.chat!=null)+" list="+(scene.list!=null)
                +" sameWindow="+(scene.list!=null && scene.list.node.getWindowId()==firstWindow)
                +" sameTree="+(scene.tree.signature.hashCode()==originalTreeHash)+" rows="+scene.messages.size()+" nodes="+scene.tree.items.size()
                +" modal="+(dialog!=null)+" samePixels="+origin.same(raw,parentArea));
        if(scene.chat!=null && origin.same(raw,parentArea)) {
            declined++;phase=Phase.READ;origin=null;waits=0;waitFor("Continuing the visible conversation.");return;
        }
        declined++;
        if(scene.chat!=null && scene.list!=null && scene.list.node.getWindowId()==firstWindow && raw.hasVisibleContent(parentArea))
            device.save(candidate);
        fail("The message control opened an unrecognized state; its coverage could not be verified.");
    }
    private void returned(ConversationScene scene,ConversationPixels raw) {
        if(!panelParents.isEmpty()) {
            PanelParent parent=panelParents.peek();WalkTree.Item modal=scene.modalRoot();
            if(modal!=null && modal.node.getWindowId()==parent.window && parent.pixels.same(raw,parent.area)) {
                panelParents.pop();panelId=parent.id;panelPosition=parent.position;phase=Phase.READ;page.reset();page.saved=true;
                waits=0;waitFor("Continuing at the original message menu.");return;
            }
            if(++waits<6){waitFor("Verifying the return to the parent message panel.");return;}
            fail("Closing a nested message panel did not restore the original parent panel.");
        }
        if(scene.chat!=null && scene.list!=null && scene.list.node.getWindowId()==firstWindow
                && scene.modalRoot()==null && origin.same(raw,parentArea)) {
            panel=false;phase=Phase.READ;page.reset();page.saved=true;origin=null;waits=0;waitFor("Continuing at the original message.");return;
        }
        if(++waits<6){waitFor("Verifying the return to the original chat position.");return;}
        fail("Closing a message panel did not restore the original conversation viewport.");
    }
    private static boolean collapseLabel(WalkTree.Item n) {
        return n.has(WalkTree.COLLAPSE) || ConversationScene.controlLabel(n).matches(
                "show less|see less|collapse(?: details| thinking| reasoning)?|hide (?:details|thinking|thoughts|reasoning)");
    }
    private static Set<String> collapses(ConversationScene scene,WalkTree.Item scope) {
        Set<String> result=new HashSet<>();
        for(WalkTree.Item n:scene.tree.items)if(scene.panelNode(n,scope) && collapseLabel(n))result.add(n.path+"|"+ConversationScene.controlLabel(n));
        return result;
    }
    private static boolean readOnlyDestination(ConversationScene scene,WalkTree.Item scope) {
        for(WalkTree.Item n:scene.tree.items)if(scene.panelNode(n,scope) && n.node.isEditable())return false;
        return true;
    }
    private static boolean detailHeading(ConversationScene scene,WalkTree.Item scope) {
        for(WalkTree.Item n:scene.tree.items)if(scene.panelNode(n,scope) && ConversationScene.isDetailHeading(n))return true;
        return false;
    }
    private void verifyNested(ConversationScene scene,WalkTree.Item modal,ConversationPixels raw) {
        PanelParent parent=pendingPanel;
        if(modal!=null && readOnlyDestination(scene,modal)) {
            boolean visuallyChanged=!parent.pixels.same(raw,parent.area);
            Set<String> toggles=collapses(scene,modal);toggles.removeAll(parent.collapses);
            boolean otherWindow=modal.node.getWindowId()!=parent.window;
            if(!otherWindow && visuallyChanged && !toggles.isEmpty()) {
                pendingPanel=null;expanded++;phase=Phase.READ;page.reset();waits=0;
                waitFor("Capturing the expanded explanation in the message panel.");return;
            }
            if(visuallyChanged && detailHeading(scene,modal)) {
                for(PanelParent prior:panelParents)if(prior.pixels.same(raw,prior.area))
                    fail("An exposed message control repeated a parent panel instead of opening new content.");
                panelParents.push(parent);pendingPanel=null;panelId=++opened;panelPosition=0;phase=Phase.REWIND;
                page.reset();waits=0;waitFor("Capturing the nested read-only message panel.");return;
            }
        }
        if(++waits<5){waitFor("Verifying the nested message control.");return;}
        if(modal!=null && modal.node.getWindowId()==parent.window && parent.pixels.same(raw,parent.area)) {
            pendingPanel=null;declined++;phase=Phase.READ;waits=0;waitFor("Continuing in the message panel.");return;
        }
        fail("The exposed nested message control opened an unrecognized state.");
    }
    private boolean readPanel(ConversationScene scene,WalkTree.Item scope,Rect area,ConversationPixels raw) {
        for(WalkTree.Item n:scene.tree.items) if(scene.panelNode(n,scope)) {
            String label=ConversationScene.controlLabel(n);
            if(label.isEmpty() || label.matches("close|close menu|close sheet|dismiss|back|go back"))continue;
            boolean visibleOption=ConversationScene.chromeOnly(label) || (n.kind.endsWith("Button")
                    && !ConversationScene.disclosureLabel(label) && !collapseLabel(n));
            if(!visibleOption)continue;
            String key=panelId+"|"+label;
            if(menuOptionVisits.add(key)){menuChoices++;if(!n.node.isEnabled())disabledChoices++;}
        }
        for(WalkTree.Item n:scene.tree.items) {
            String label=ConversationScene.controlLabel(n);
            if(!scene.panelNode(n,scope) || !Rect.intersects(n.bounds,area) || !ConversationScene.disclosureLabel(label)
                    || ConversationScene.navigationLink(n,scope))continue;
            WalkTree.Item button=n;
            while(button!=null && button!=scope && !button.has(WalkTree.CLICK) && !button.has(WalkTree.EXPAND)
                    && !button.kind.endsWith("Button"))button=button.parent;
            if(button==null || button==scope)continue;
            String key=panelId+"|nested|"+button.path+"|"+label;
            if(!menuOptionVisits.add(key))continue;
            if(!button.node.isEnabled()){disabled++;continue;}
            int action=button.has(WalkTree.EXPAND)?WalkTree.EXPAND:WalkTree.CLICK;
            if(!button.has(action)){declined++;continue;}
            PanelParent parent=new PanelParent(raw,area,scope.node.getWindowId(),panelId,panelPosition,collapses(scene,scope));
            if(!button.node.performAction(action)){declined++;continue;}
            pendingPanel=parent;phase=Phase.OPENING;waits=0;waitFor("Opening the exposed nested explanation.");return true;
        }
        return false;
    }
    String summary() {
        return "\nConversation viewports: "+viewportCount+"\nAdditional horizontal positions: "+horizontalCount+
                "\nAlready fully captured horizontal panes verified again: "+reusedLanes+"\nMinimum verified vertical overlap: "+(verticalMoves==0?"not applicable":minVerticalOverlap+"%")+
                "\nMinimum verified horizontal overlap: "+(horizontalMoves==0?"not applicable":minHorizontalOverlap+"%")+
                "\nEarlier-history moves: "+rewound+"\nLargest exposed collection count: "+(knownRows<0?"unavailable":knownRows)+
                "\nInline disclosures expanded: "+expanded+"\nRead-only panels opened: "+opened+"\nPanel viewports: "+menuPages+
                "\nVisible recognized menu choices: "+menuChoices+" (disabled: "+disabledChoices+")"+
                "\nDisabled disclosure controls: "+disabled+"\nUnconfirmed disclosures: "+declined+"\nUnverified horizontal traversals: "+unverifiedLanes+
                "\nCoverage describes rendered content and controls exposed to Android accessibility.\n";
    }
}
