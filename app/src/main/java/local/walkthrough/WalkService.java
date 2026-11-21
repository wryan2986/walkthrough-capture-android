package local.walkthrough;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Performs one user-configured, package-scoped capture run on the service's main thread. */
public final class WalkService extends AccessibilityService {
    private static WalkService connected;
    private static boolean packaging;
    private static String status = "Ready.";
    private static RangeWalk reviewed;
    private static final long POLL = 230;
    private static final long LAUNCH_TIMEOUT = 90_000;
    private static final long ACTION_TIMEOUT = 12_000;
    private static final long RUN_TIMEOUT = 30 * 60_000;
    private static final int MAX_FRAMES = 400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Runnable poll = () -> guardedPoll();
    private ManualTrajectoryGuide manualGuide;
    private final Runnable updateManualHint = this::readManualHint;
    private final Runnable settleManualHint = this::readManualHint;
    private Run run;
    private TapHighlight marker;
    private android.view.WindowManager previewWindows;

    private enum Phase { BROWSE, CHAT, REWIND, PANEL, GROUPS, GROUP_OPEN, GROUP_RETURN, STEPS, STEP_OPEN, DETAIL, STEP_RETURN, MUSE_REOPEN, MUSE_RESUME, RANGE_TOP, RANGE_FEED, RANGE_LIST_TOP, RANGE_ROWS, RANGE_BACK, RANGE_HOME }

    private static final class Page {
        boolean seekTop = true, seekLeft = true;
        final Set<String> namedClicks = new HashSet<>();
    }

    private static final class Level {
        final Set<String> selectors;
        final Set<String> visited = new HashSet<>();
        final Set<String> catalog = new HashSet<>();
        MuseSteps inventory;
        boolean collecting, rewindCatalog, fullBeforeOpen;
        final Set<String> heldOverviews = new HashSet<>(), savedOverviews = new HashSet<>();
        final String label;
        boolean seekTop = true, seekLeft = true, inline;
        String selected;
        Set<String> previous = Collections.emptySet();

        Level(Set<String> selectors, String label) {
            this.selectors = selectors;
            this.label = label;
        }

        boolean recognized(Map<String,WalkTree.Item> rows) {
            return !Collections.disjoint(rows.keySet(), previous)
                    || !Collections.disjoint(rows.keySet(), visited);
        }
    }

    /** Android may coalesce the scroll event or delay the injection callback. The
     * original container and its visible contents provide independent progress evidence. */
    private static final class ListDrag {
        final int window, attempt;
        final String path, kind, id, before, action;
        final boolean activityFeed;
        final long began = now();
        boolean callbackDone;
        ListDrag(WalkTree tree,WalkTree.Item pane,String action,int attempt) {
            window=pane.node.getWindowId(); path=pane.path; kind=pane.kind; id=pane.id;
            activityFeed=isActivityFeed(pane);
            before=contents(tree,pane); this.action=action; this.attempt=attempt;
        }
        WalkTree.Item find(WalkTree tree) {
            WalkTree.Item feed=null;
            for (WalkTree.Item n:tree.items) {
                if (n.node.getWindowId()!=window || !n.kind.equals(kind) || !n.id.equals(id)) continue;
                if (!activityFeed) {
                    if (n.path.equals(path)) return n;
                } else if (isActivityFeed(n)) {
                    // The profile header and day sections can remount as the feed scrolls.
                    // Its unique child under the named Activity view identifies the list
                    // even when Android has changed its absolute accessibility path.
                    if (feed!=null) return null;
                    feed=n;
                }
            }
            return feed;
        }
        private static boolean isActivityFeed(WalkTree.Item n) {
            return n.parent!=null && MuseProfile.ACTIVITY.equals(n.parent.id) && MuseProfile.muse(n)
                    && (n.node.isScrollable() || n.scrollAction(WalkTree.UP)!=0 || n.scrollAction(WalkTree.DOWN)!=0
                    || n.parent.node.getChildCount()==1);
        }
        static String contents(WalkTree tree,WalkTree.Item pane) {
            StringBuilder b=new StringBuilder();
            for (WalkTree.Item n:tree.items) if (n!=pane && n.inside(pane))
                b.append(n.kind).append('|').append(n.id).append('|').append(n.bounds).append('|')
                        .append(n.label.length()).append(':').append(n.label).append('|')
                        .append(n.description.length()).append(':').append(n.description).append('\n');
            return b.toString();
        }
    }

    private static final class ExitTransition {
        final boolean toActivity;
        final int window;
        final long began = now();
        long lastSent, sameSince;
        int attempts;
        String observed;
        ExitTransition(boolean toActivity,int window) { this.toActivity=toActivity; this.window=window; }
    }

    private static final class Run {
        final String pkg, panel;
        final Set<String> stepSelectors, expanders, seenExpanders = new HashSet<>();
        Level groups;
        Level steps;
        final Set<String> openedDays = new HashSet<>();
        int sheetY = -1, sheetAttempts;
        boolean compactSheet, seekReopenTop;
        long sheetDragAt, nullSince;
        ExitTransition exit;
        PreviewRow preview;
        TapHighlight.Settings markerSettings;
        FeedbackHud feedback;
        final WalkScroll scrolling = new WalkScroll();
        int captureRevision,displayRevision,captureDisplayRevision,captureWindow;
        boolean captureWillWrite,captureCallback;
        int captureGeneration,captureRetries;
        boolean fastView;
        String overviewKey;
        final List<String> verifiedActivities = new ArrayList<>(), detailCues = new ArrayList<>();
        long recordingAt;
        String activityTitle = "", pendingActivity = "";
        final boolean video;
        final File dir, images;
        final int dwell;
        final long start = now();
        Phase phase;
        Page page = new Page();
        boolean started, busy, savePng = true, videoTop;
        ListDrag drag;
        RangeWalk range;
        ConversationHistory conversation;
        PrivacyMasks privacy;
        long conversationNotBefore;
        GestureDescription.StrokeDescription heldPager;
        float heldX,heldY;
        String heldPath;
        int heldDirection;
        int frames, throttles, stepCount, groupCount, views;
        long stableSince, deadline, captureStart;
        long rangeProgressAt=start;
        final Set<String> rangeProgress=new HashSet<>();
        String observed = null, source = null;
        String action = "opening the target";

        Run(String pkg, String panel, String groupsCsv, String steps, String expand, boolean trajectory,
            boolean chatFirst, boolean video, int dwell, File dir) {
            this.pkg = pkg;
            this.panel = panel == null ? "" : panel.trim();
            this.groups = new Level(WalkTree.selectors(groupsCsv), "action card");
            this.stepSelectors = WalkTree.selectors(steps);
            this.expanders = WalkTree.selectors(expand);
            this.video = video;
            this.dwell = Math.max(650, Math.min(dwell, 60_000));
            this.dir = dir;
            images = dir == null ? null : new File(dir, "screenshots");
            phase = !trajectory ? Phase.BROWSE : (chatFirst ? Phase.CHAT : Phase.PANEL);
        }
    }

    public static boolean isConnected() { return connected != null; }
    public static boolean isActive() { return packaging || (connected != null && connected.run != null); }
    static boolean isPackaging() { return packaging; }
    public static String getStatus() { return status; }

    public static boolean arm(String pkg, String panel, String groupIdsCsv, String stepIdsCsv, String expanderLabelsCsv,
                              boolean trajectory, boolean record, int dwellMs, File dir) {
        return arm(pkg, panel, groupIdsCsv, stepIdsCsv, expanderLabelsCsv, trajectory, false, record, dwellMs, dir);
    }

    public static boolean arm(String pkg, String panel, String groupIdsCsv, String stepIdsCsv, String expanderLabelsCsv,
                              boolean trajectory, boolean chatFirst, boolean record, int dwellMs, File dir) {
        WalkService s = connected;
        if (s == null || isActive() || ManualCaptureService.isActive()) return false;
        if (pkg == null || pkg.trim().isEmpty() || dir == null) {
            status = "A target package and a run folder are required.";
            return false;
        }
        Run r = new Run(pkg.trim(), panel, groupIdsCsv, stepIdsCsv, expanderLabelsCsv, trajectory, chatFirst, record, dwellMs, dir);
        if (trajectory && (r.groups.selectors.isEmpty() || r.stepSelectors.isEmpty())) {
            status = "Set at least one action card selector and one step summary selector.";
            return false;
        }
        if (trajectory && chatFirst && r.panel.isEmpty()) {
            status = "A combined run requires an avatar selector.";
            return false;
        }
        r.savePng = !record;
        if (r.savePng && !r.images.mkdir()) {
            status = "Could not create a fresh screenshots folder.";
            return false;
        }
        r.privacy=PrivacyMasks.read(s);
        s.run = r;
        status = "Waiting for " + r.pkg + ".";
        s.later(POLL);
        return true;
    }

    public static boolean armConversation(String pkg,File dir) {
        if (isActive() || ManualCaptureService.isActive()) return false;
        WalkService service=connected;
        if(service==null || dir==null || !MuseProfile.supports(pkg)) {
            status="Enable accessibility and choose the Muse conversation.";return false;
        }
        Run r=new Run(pkg,"","","","",false,false,false,650,dir);
        if(!r.images.mkdir()) { status="Could not create a fresh conversation image folder.";return false; }
        r.privacy=PrivacyMasks.read(service);
        r.conversation=new ConversationHistory(service.new ConversationIO(r),r.scrolling);
        service.run=r;status="Opening the Muse conversation.";service.later(POLL);return true;
    }

    private final class ConversationIO implements ConversationHistory.Device {
        private final Run session;
        ConversationIO(Run r){session=r;}
        @Override public void save(File image) throws IOException {
            if(image==null)throw new IOException("No settled full-display conversation image was available.");
            String filename=String.format(Locale.ROOT,"%08d.png",session.frames+1);
            Files.move(image.toPath(),new File(session.images,filename).toPath());
            session.frames++;
            savedFeedback(session);
        }
        @Override public void pause(String text,long delay) {
            status=text;session.observed=null;session.conversationNotBefore=now()+delay;
            session.deadline=now()+45_000;later(delay);
        }
        @Override public void trace(String info) {
            if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)
                android.util.Log.i("WalkthroughMotion",info);
        }
        @Override public void back() {
            if(!performGlobalAction(GLOBAL_ACTION_BACK))fail("Android could not return to the preceding Muse screen.");
        }
        @Override public void swipe(WalkTree.Item pane,Rect area,int direction,float fraction) {
            boolean horizontal=WalkScroll.horizontal(direction);
            float delta=(horizontal?area.width():area.height())*fraction;
            if(horizontal && ConversationScene.paged(pane)) {
                pagerStroke(pane,area,direction);
                return;
            }
            float x=horizontal?area.exactCenterX():area.left+Math.min(area.width()*.12f,Math.max(32,area.width()*.07f));
            float y=area.exactCenterY();
            if(area.width()<30 || area.height()<18 || delta<8)fail("A scroll pane has too little visible space to navigate safely.");
            float shift=(direction==WalkTree.DOWN || direction==WalkTree.RIGHT)?-delta:delta;
            float sx=horizontal?x-shift/2:x,sy=horizontal?y:y-shift/2;
            float ex=horizontal?x+shift/2:x,ey=horizontal?y:y+shift/2;
            // A modest cross-axis tail removes fling velocity before right/left or up/down release.
            float finish=horizontal?Math.min(area.height()*.20f,delta*.19f):Math.min(area.width()*.16f,delta*.19f);
            Path line=new Path();line.moveTo(sx,sy);line.lineTo(ex,ey);
            if(horizontal) {
                // Keep every touch on the same y. An unresponsive embedded pane
                // must never hand the end of this stroke to the vertical chat.
                float tail=Math.min(3f,area.width()*.01f);
                for(int i=0;i<5;i++){line.lineTo(ex+tail,ey);line.lineTo(ex,ey);}
            } else line.lineTo(ex+finish,ey);
            session.scrolling.arm(pane,direction);
            GestureDescription movement=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(line,0,600)).build();
            if(!dispatchGesture(movement,null,handler))fail("Android did not accept the conversation scrolling gesture.");
            pause("Verifying the next conversation viewport.",820);
        }
        @Override public boolean releasePointer(){return releasePager(session);}
        @Override public boolean nativeScroll(WalkTree.Item pane,int direction) {
            int action=ConversationScene.direction(pane,direction);
            if(action==0)return false;
            session.scrolling.arm(pane,direction);
            if(!pane.node.performAction(action))return false;
            pause(direction==WalkTree.UP?"Loading earlier messages.":"Verifying the native horizontal repositioning.",
                    direction==WalkTree.UP?610:820);return true;
        }
        private void pagerStroke(WalkTree.Item pane,Rect area,int direction) {
            int sign=direction==WalkTree.RIGHT?-1:1;
            GestureDescription.StrokeDescription before=session.heldPager;
            float sx=before==null ? area.left+area.width()*(sign<0?.82f:.18f) : session.heldX;
            float sy=before==null ? area.exactCenterY() : session.heldY;
            boolean continuing=before!=null && pane.path.equals(session.heldPath) && direction==session.heldDirection;
            float ex=before==null?sx+sign*area.width()*.39f : (continuing?sx+sign*area.width()*.22f:sx);
            ex=Math.max(area.left+2,Math.min(area.right-2,ex));
            Path path=new Path();path.moveTo(sx,sy);path.lineTo(ex,sy);
            boolean hold=before==null;
            GestureDescription.StrokeDescription stroke=hold ? new GestureDescription.StrokeDescription(path,0,410,true)
                    : before.continueStroke(path,0,continuing?250:32,false);
            session.scrolling.arm(pane,direction);
            trace("pager dir="+direction+" hold="+hold+" continued="+continuing+" x="+Math.round(sx)+".."+Math.round(ex)+" y="+Math.round(sy));
            GestureResultCallback sent=new GestureResultCallback(){
                @Override public void onCancelled(GestureDescription g){trace("pager Android cancelled stroke");}
            };
            if(!dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),sent,handler))
                fail("Android declined the overlapping carousel gesture.");
            session.heldPager=hold?stroke:null;session.heldPath=pane.path;session.heldDirection=direction;
            session.heldX=ex;session.heldY=sy;
            pause(hold?"Verifying the intermediate carousel position.":"Verifying the settled carousel card.",hold?530:760);
        }
        @Override public void finish(boolean ok,String reason){end(ok,reason);}
    }

    public static boolean hasDiscovery() { return reviewed != null; }

    public static List<RangeWalk.Entry> entries() {
        return reviewed == null ? Collections.emptyList() : reviewed.review();
    }

    public static boolean discover(String pkg, String avatar, String groups, String steps, String timestampSelector,
                                   String titleSelector, String level, long fromInclusive, long toExclusive) {
        if (!isConnected() || isActive() || ManualCaptureService.isActive()) return false;
        reviewed = null;
        try {
            RangeWalk.Config cfg = new RangeWalk.Config(pkg, avatar, groups, steps, timestampSelector, titleSelector,
                    level, fromInclusive, toExclusive);
            Run r = new Run(cfg.pkg, cfg.avatar, cfg.groups, cfg.steps, "", true, false, false, 650, null);
            r.range = new RangeWalk(cfg);
            r.phase = Phase.RANGE_TOP;
            r.savePng = false;
            r.privacy=PrivacyMasks.read(connected);
            connected.run = r;
            status = "Waiting for the target to scan the time range.";
            connected.later(POLL);
            return true;
        } catch (RuntimeException ex) { status = message(ex); return false; }
    }

    public static boolean armRange(String pkg, String groups, String steps, String expanders,
                                   Set<String> selectedKeys, boolean savePng, boolean video, int dwellMs, File dir) {
        if (!isConnected() || isActive() || ManualCaptureService.isActive()) return false;
        if (reviewed == null || dir == null || (!savePng && !video) || selectedKeys == null) {
            status = "Review the range and choose a capture format before starting.";
            return false;
        }
        RangeWalk.Config cfg = reviewed.config;
        if (!cfg.pkg.equals(pkg.trim()) || !cfg.groups.equals(groups.trim()) || !cfg.steps.equals(steps.trim())) {
            status = "The capture profile changed after review. Scan again.";
            return false;
        }
        try {
            Run r = new Run(cfg.pkg, cfg.avatar, groups, steps, expanders, true, false, video, dwellMs, dir);
            r.range = new RangeWalk(reviewed, selectedKeys);
            r.phase = Phase.RANGE_TOP;
            r.savePng = savePng && !video;
            if (r.savePng && !r.images.mkdir()) throw new IllegalStateException("Could not create the screenshots folder.");
            r.privacy=PrivacyMasks.read(connected);
            connected.run = r;
            status = "Waiting to rewind the reviewed source.";
            connected.later(POLL);
            return true;
        } catch (RuntimeException ex) { status = message(ex); return false; }
    }

    public static boolean stopFromNotification() {
        if (connected == null || connected.run == null) return false;
        connected.end(false, "Stopped by the user.");
        return true;
    }

    public static void cancel() { stopFromNotification(); }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        marker = new TapHighlight(this);
        android.view.Display display=getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        previewWindows=createDisplayContext(display).createWindowContext(android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null)
                .getSystemService(android.view.WindowManager.class);
        connected = this;
        refreshManualGuidance();
    }

    /** Called on the main thread when a user-operated guided video starts, resizes or ends. */
    static void refreshManualGuidance() {
        WalkService service = connected;
        if (service == null) return;
        if (!ManualCaptureService.wantsTargetGuidance()) {
            service.stopManualHints();
            return;
        }
        if (service.manualGuide == null) service.manualGuide = new ManualTrajectoryGuide();
        service.scheduleManualHint();
    }

    private void stopManualHints() {
        handler.removeCallbacks(updateManualHint);
        handler.removeCallbacks(settleManualHint);
        if (manualGuide != null) manualGuide.reset();
        manualGuide = null;
        ManualCaptureService.updateGuidance(null, "");
    }

    private void scheduleManualHint() {
        if (!handler.hasCallbacks(updateManualHint)) handler.postDelayed(updateManualHint, 65);
        // Android can deliver a layout event before an expanding sheet finishes moving.
        handler.removeCallbacks(settleManualHint);
        handler.postDelayed(settleManualHint, 310);
    }

    private void readManualHint() {
        if (manualGuide == null || !ManualCaptureService.wantsTargetGuidance()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !ManualCaptureService.guidesPackage(root.getPackageName())) {
            if (root != null) root.recycle();
            manualGuide.clearPendingClick();
            ManualCaptureService.updateGuidance(null, "");
            return;
        }
        // Message bodies are unnecessary for locating the chat avatar.
        try (WalkTree tree = new WalkTree(root, true)) {
            ManualTrajectoryGuide.Hint hint = manualGuide.update(tree, previewDisplay());
            ManualCaptureService.updateGuidance(hint == null ? null : hint.bounds, hint == null ? "" : hint.text);
        } catch (RuntimeException changedWindow) {
            manualGuide.clearPendingClick();
            ManualCaptureService.updateGuidance(null, "");
        }
    }

    private void manualEvent(AccessibilityEvent event) {
        if (!ManualCaptureService.wantsTargetGuidance()) return;
        if (manualGuide == null) manualGuide = new ManualTrajectoryGuide();
        int type = event.getEventType();
        boolean fromMuse = ManualCaptureService.guidesPackage(event.getPackageName());
        if (!fromMuse) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                // An overlay's own window notification need not signify a change of app.
                readManualHint();
                handler.removeCallbacks(settleManualHint);
                handler.postDelayed(settleManualHint, 310);
            }
            return;
        }
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                try {
                    if (ManualCaptureService.guidesPackage(source.getPackageName())) {
                        Rect bounds = new Rect();
                        source.getBoundsInScreen(bounds);
                        if (!bounds.isEmpty()) manualGuide.clicked(bounds);
                    }
                } finally { source.recycle(); }
            }
        }
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            ManualCaptureService.updateGuidance(null, "");
        scheduleManualHint();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (ManualCaptureService.isActive()) { manualEvent(event); return; }
        if (run == null) {
            if (Inspection.wants(event)) Inspection.observe(this, event);
            return;
        }
        if (run.pkg.equals(WalkTree.string(event.getPackageName()))) {
            int kind=event.getEventType();
            if(kind==AccessibilityEvent.TYPE_VIEW_SCROLLED || kind==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                run.displayRevision++;
            if(run.scrolling.observe(event))run.stableSince=now();
        }
        // Stop as soon as a foreground change can be confirmed, including during PNG encoding.
        if (run.started && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && !run.pkg.contentEquals(WalkTree.string(event.getPackageName()))) {
            AccessibilityNodeInfo n = getRootInActiveWindow();
            if (n != null) {
                boolean target = target(n);
                n.recycle();
                if (!target) end(false, "The foreground app changed.");
            }
        }
    }

    @Override public void onInterrupt() {
        stopManualHints();
        if (run != null) end(false, "Android interrupted accessibility.");
    }

    @Override public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        disconnect();
        writer.shutdown();
        super.onDestroy();
    }

    private void disconnect() {
        stopManualHints();
        if (run != null) end(false, "The accessibility service disconnected.");
        if (connected == this) connected = null;
        handler.removeCallbacks(poll);
    }

    private static long now() { return SystemClock.elapsedRealtime(); }

    private boolean target(AccessibilityNodeInfo root) {
        return root != null && run != null && run.pkg.equals(WalkTree.string(root.getPackageName()));
    }

    private void later(long wait) {
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, wait);
    }

    private void guardedPoll() {
        try { pollRun(); }
        catch (RuntimeException e) { end(false, message(e)); }
    }

    private void pollRun() {
        Run r = run;
        if (r == null) return;
        long t = now();
        if (r.range!=null && t-r.rangeProgressAt>RUN_TIMEOUT) {
            end(false,"Trajectory navigation stopped after 30 minutes without a new verified viewport or completed step.");return;
        }
        if (r.conversation==null && r.range==null && (t-r.start>RUN_TIMEOUT || r.views>=MAX_FRAMES)) {
            end(false, "The run reached its time or screenshot limit.");
            return;
        }
        if (r.video && !CaptureRecorder.lastError().isEmpty()) {
            end(false, "Recording is unavailable. " + CaptureRecorder.lastError());
            return;
        }
        // Waiting for an Android screenshot or PNG write needs no synchronous
        // accessibility Binder read. Let the main executor deliver the callback.
        // Foreground changes are monitored by events and rechecked before/after encoding.
        if (r.busy) {
            long timeout=r.conversation==null?15_000:(r.captureCallback?90_000:36_000);
            if(t-r.captureStart<=timeout){later(POLL);return;}
            if(r.conversation!=null && !r.captureCallback && r.captureRetries++<2) {
                r.captureGeneration++;r.busy=false;r.observed=null;r.deadline=t+90_000;
                later(900);return;
            }
            end(false,r.captureCallback?"The local screenshot writer did not finish.":"Android did not return the requested screenshot after bounded retries.");
            return;
        }
        if(r.conversation!=null && r.started && t<r.conversationNotBefore) {
            later(r.conversationNotBefore-t);return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!target(root)) {
            if (root==null && r.started) {
                if (r.nullSince==0) r.nullSince=t;
                if (t-r.nullSince<1200) { later(POLL); return; }
            }
            if (root != null) root.recycle();
            if (r.started || t - r.start > LAUNCH_TIMEOUT) {
                end(false, r.started ? "The target lost the active window." : "Timed out waiting for the target.");
            } else later(POLL);
            return;
        }
        r.nullSince=0;
        if (r.video && r.videoTop && !CaptureRecorder.beginOnTarget()) {
            root.recycle();
            if (CaptureRecorder.isStarting() && t - r.start < LAUNCH_TIMEOUT) later(POLL);
            else end(false, "Recording is unavailable. " + CaptureRecorder.lastError());
            return;
        }
        if (r.video && r.videoTop && r.recordingAt==0) r.recordingAt = now();
        if (r.drag!=null && t-r.drag.began<880 && !r.drag.callbackDone) {
            root.recycle();
            later(POLL);
            return;
        }
        try (WalkTree tree = new WalkTree(root,r.conversation!=null && r.conversation.readingBack())) {
            if (!r.started) {
                r.started = true;
                r.deadline = r.conversation==null?t+ACTION_TIMEOUT+r.dwell:t+90_000;
            }
            if(r.conversation!=null) {
                if(t>r.deadline){end(false,"The conversation view did not settle before its next capture.");return;}
                if(!r.conversation.needFile()) {
                    // Rewind samples are never encoded. Later boundary confirmation
                    // still requires repeated stable pixels and native edge actions.
                    r.observed=tree.signature;requestFrame(r,tree);return;
                }
                if(!tree.signature.equals(r.observed)) { r.observed=tree.signature;r.stableSince=t; }
                if(t-r.stableSince<300){later(POLL);return;}
                requestFrame(r,tree); return;
            }
            if (r.preview!=null) {
                advancePreview(r,tree,t);
                return;
            }
            if (t > r.deadline) {
                end(false, "Could not confirm a stable screen after " + r.action + ".");
                return;
            }
            if (r.drag!=null && awaitDrag(r,tree,now())) return;
            if (r.sheetY>=0 && awaitExpansion(r,tree,t)) return;
            if (r.exit!=null && awaitExit(r,tree,t)) return;
            if (tree.items.isEmpty() || (tree.signature.equals(r.source) && !r.scrolling.moved)) { later(POLL); return; }
            if (!tree.signature.equals(r.observed)) {
                r.observed = tree.signature;
                r.stableSince = t;
                status = r.range != null && r.range.discovering ? "Scanning readable trajectory rows."
                        : "Holding view " + (r.views + 1) + " in " + r.pkg + ".";
            }
            boolean capturing = r.range == null ? (!r.video || r.videoTop) : (!r.range.discovering && r.range.verifiedTop);
            boolean overview = r.steps != null && r.steps.inventory!=null
                    && (r.phase==Phase.STEPS || r.phase==Phase.STEP_RETURN) && MuseProfile.summary(tree);
            // Overview inventory and revisiting previous rows need just enough time
            // for layout and scrolling to settle. Every new detail retains its full hold.
            boolean fast = overview && !r.steps.fullBeforeOpen;
            if (t-r.stableSince < (!capturing || fast ? 230 : r.dwell)) { later(POLL); return; }
            r.fastView=fast;
            r.overviewKey=overview ? overviewDigest(tree) : null;
            if (overview && !fast) r.steps.heldOverviews.add(r.overviewKey);
            boolean writeImage = capturing && r.savePng && (!overview
                    || ((!fast || r.steps.collecting) && !r.steps.savedOverviews.contains(r.overviewKey)));
            if (writeImage) requestFrame(r,tree);
            else {
                r.views++;
                next(r, tree);
            }
        }
    }

    private String overviewDigest(WalkTree tree) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(tree.signature.getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable",ex); }
    }

    private void savedFeedback(Run r) {
        if(r.video)return;
        if(r.feedback==null)r.feedback=new FeedbackHud(this);
        r.feedback.saved(r.frames);
    }

    private void requestFrame(Run r,WalkTree tree) {
        Rect display=previewWindows.getCurrentWindowMetrics().getBounds();
        List<Rect> masks=r.privacy==null?Collections.emptyList():r.privacy.regionsFor(tree,display,r.conversation!=null);
        boolean write=r.conversation==null || r.conversation.needFile();
        boolean protectGeometry=r.privacy!=null && (r.privacy.hasRegions() || (r.conversation!=null
                && (r.privacy.settings.emails || r.privacy.settings.phones || !r.privacy.settings.terms.isEmpty())));
        r.busy = true;
        r.captureStart = now();
        r.captureWindow=tree.all.get(0).node.getWindowId();r.captureWillWrite=write;
        int generation=++r.captureGeneration;r.captureCallback=false;
        r.captureRevision = r.scrolling.revision;
        r.captureDisplayRevision=r.displayRevision;
        PreviewRow capturedPreview=r.preview!=null && r.preview.waitingForImage ? r.preview : null;
        Rect marked=capturedPreview==null ? null : new Rect(capturedPreview.displayed);
        later(POLL);
        TakeScreenshotCallback callback=new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult screenshot) {
                HardwareBuffer buffer = screenshot.getHardwareBuffer();
                if(run!=r || generation!=r.captureGeneration){buffer.close();return;}
                r.captureCallback=true;
                // Compare the target and mask coordinates again before encoding any pixels to disk.
                AccessibilityNodeInfo current=getRootInActiveWindow();
                if(!target(current)) {
                    buffer.close();if(current!=null)current.recycle();r.busy=false;r.observed=null;
                    later(POLL);return;
                }
                try(WalkTree captured=new WalkTree(current,!write && r.conversation!=null && r.conversation.readingBack())) {
                    if(captured.all.get(0).node.getWindowId()!=r.captureWindow || r.captureRevision!=r.scrolling.revision
                            || write && (!captured.signature.equals(r.observed) || r.captureDisplayRevision!=r.displayRevision)) {
                        buffer.close();r.busy=false;r.observed=null;
                        if(r.preview!=null){marker.clear();r.preview.resetHold();}
                        later(POLL);return;
                    }
                    if(write && protectGeometry && (!display.equals(previewWindows.getCurrentWindowMetrics().getBounds())
                            || display.left!=0 || display.top!=0 || buffer.getWidth()!=display.width()
                            || buffer.getHeight()!=display.height() || !masks.equals(r.privacy.regionsFor(captured,display,r.conversation!=null)))) {
                        buffer.close();r.busy=false;end(false,"The captured display or privacy mask positions changed before encoding.");return;
                    }
                } catch(RuntimeException problem) {
                    buffer.close();r.busy=false;end(false,"The capture scope could not be verified before encoding.");return;
                }

                if(r.feedback!=null)r.feedback.betweenSamples();
                writer.execute(() -> {
                    File temp = null;
                    String error = null;
                    Bitmap wrapped = null, bitmap = null;
                    ConversationPixels pixels=null;
                    try (HardwareBuffer owned = buffer) {
                        wrapped = Bitmap.wrapHardwareBuffer(owned, screenshot.getColorSpace());
                        if (wrapped == null) throw new IOException("Android returned no readable pixels.");
                        bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, marked != null || !masks.isEmpty());
                        if (bitmap == null) throw new IOException("Could not copy screenshot pixels.");
                        if(r.conversation!=null)pixels=new ConversationPixels(bitmap);
                        if(write) {
                            if(marked!=null) marker.drawOn(bitmap,marked,capturedPreview.settings);
                            if(!masks.isEmpty()) PrivacyMasks.paint(bitmap,masks);
                            temp = File.createTempFile("walk-screen-", ".part", getCacheDir());
                            try (FileOutputStream out = new FileOutputStream(temp)) {
                                if (!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("PNG encoding failed.");
                            }
                        }
                    } catch (RuntimeException | IOException ex) {
                        error = message(ex);
                    } finally {
                        if (bitmap != null) bitmap.recycle();
                        if (wrapped != null && wrapped != bitmap) wrapped.recycle();
                    }
                    File pending = temp;
                    String problem = error;
                    ConversationPixels comparison=pixels;
                    handler.post(() -> publishFrame(r,pending,problem,comparison,generation));
                });
            }
            @Override public void onFailure(int code) {
                if(run!=r || generation!=r.captureGeneration)return;
                r.busy = false;
                if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && ++r.throttles <= 3) {
                    later(800);
                } else end(false, "Android could not capture this screen (error " + code + ").");
            }
        };
        Runnable capture=()->{
            if(run==r && generation==r.captureGeneration)takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),callback);
        };
        if(r.feedback==null)capture.run();
        else r.feedback.beforeSample(capture,()->{
            if(run==r && generation==r.captureGeneration)end(false,"Android could not confirm that the screenshot feedback had cleared.");
        });
    }

    private void publishFrame(Run r, File temp, String error,ConversationPixels comparison,int generation) {
        try {
            if(run!=r || generation!=r.captureGeneration)return;
            r.captureRetries=0;
            if (error != null) throw new IOException(error);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (!target(root)) {
                if (root==null) {
                    if (r.nullSince==0) r.nullSince=now();
                    r.busy=false; r.observed=null;
                    later(POLL);
                    return;
                }
                root.recycle();
                end(false, "The foreground app changed during capture.");
                return;
            }
            try (WalkTree tree = new WalkTree(root,!r.captureWillWrite && r.conversation!=null && r.conversation.readingBack())) {
                r.busy = false;
                if(tree.all.get(0).node.getWindowId()!=r.captureWindow || r.captureRevision!=r.scrolling.revision
                        || r.captureWillWrite && (!tree.signature.equals(r.observed) || r.captureDisplayRevision!=r.displayRevision)) {
                    r.observed = null;
                    if (r.preview!=null) {
                        marker.clear();
                        r.preview.resetHold();
                    }
                    later(POLL);
                    return;
                }
                if(r.conversation!=null) {
                    r.views++;r.throttles=0;
                    int margin=Math.max(2,Math.round(getResources().getDisplayMetrics().density*3));
                    r.conversation.frame(tree,previewDisplay(),margin,comparison,temp);return;
                }
                String name = String.format(Locale.ROOT, "%04d.png", r.frames + 1);
                Files.move(temp.toPath(), new File(r.images, name).toPath());
                if (r.overviewKey!=null && r.steps!=null) r.steps.savedOverviews.add(r.overviewKey);
                r.frames++;
                savedFeedback(r);
                r.views++;
                r.throttles = 0;
                if(r.preview!=null && r.preview.waitingForImage) {
                    r.preview.imageSaved=true;
                    advancePreview(r,tree,now());
                } else next(r, tree);
            }
        } catch (IOException | RuntimeException ex) {
            end(false, message(ex));
        } finally {
            if (temp != null && temp.exists()) temp.delete();
        }
    }

    private void next(Run r, WalkTree tree) {
        if(r.range!=null) {
            String progress=r.phase+"|"+r.groupCount+"|"+r.stepCount+"|"+overviewDigest(tree);
            if(r.rangeProgress.add(progress))r.rangeProgressAt=now();
        }
        r.scrolling.release();
        if (MuseProfile.supports(r.pkg)) {
            if ((r.phase == Phase.GROUP_OPEN || r.phase == Phase.STEP_RETURN || r.phase == Phase.STEPS
                    || r.phase == Phase.STEP_OPEN || r.phase == Phase.MUSE_RESUME) && MuseProfile.sheet(tree) && expandSheet(r,tree)) return;
            if ((r.phase == Phase.GROUPS || r.phase == Phase.GROUP_RETURN || r.phase == Phase.RANGE_ROWS)
                    && openDay(r,tree)) return;
        }
        if (r.phase==Phase.MUSE_REOPEN) {
            if (MuseProfile.id(tree,MuseProfile.ACTIVITY)==null) fail("Could not verify the Muse Activity list after closing the overview.");
            Map<String,WalkTree.Item> cards=r.range!=null && r.range.config.card ? visibleCards(r,tree) : rows(r.groups,tree);
            WalkTree.Item same=cards.get(r.groups.selected);
            if (same==null) {
                if (r.seekReopenTop && browseScroll(r,tree,Collections.emptyList(),null,WalkTree.UP)) return;
                r.seekReopenTop=false;
                if (scroll(r,tree,WalkTree.DOWN,new ArrayList<>(cards.values()),null)) return;
                fail("The inventoried Muse activity could not be reopened.");
            }
            r.phase=Phase.MUSE_RESUME;
            final String selected=r.groups.selected;
            previewOpen(r,tree,same,t -> (r.range!=null && r.range.config.card ? visibleCards(r,t) : rows(r.groups,t)).get(selected),
                    r.activityTitle,"reopening the inventoried Muse activity at its first step");
            return;
        }
        if (r.phase==Phase.MUSE_RESUME) {
            if (!MuseProfile.summary(tree)) fail("Muse did not reopen the inventoried step overview.");
            r.steps.inventory.beginSweep();
            r.steps.seekTop=false;
            r.phase=Phase.STEPS;
        }
        if (r.range != null && rangeStep(r, tree)) return;
        if (r.video && r.range == null && !r.videoTop) {
            if (browseScroll(r, tree, Collections.emptyList(), null, WalkTree.UP)
                    || browseScroll(r, tree, Collections.emptyList(), null, WalkTree.LEFT)) return;
            startAtTop(r);
            return;
        }
        if (r.phase == Phase.BROWSE || r.phase == Phase.CHAT) {
            if (browse(r, tree, Collections.emptyList(), null)) return;
            if (r.phase == Phase.BROWSE) { complete(r); return; }
            r.phase = Phase.REWIND;
        }
        if (r.phase == Phase.REWIND) {
            if (browseScroll(r, tree, Collections.emptyList(), null, WalkTree.UP)) return;
            if (browseScroll(r, tree, Collections.emptyList(), null, WalkTree.LEFT)) return;
            List<WalkTree.Item> panel = tree.matches(r.panel);
            if (panel.size() != 1) fail("Could not identify one configured avatar after rewinding the conversation.");
            r.phase = Phase.GROUPS;
            open(r, tree, panel.get(0), "opening the trajectory from the conversation");
            return;
        }
        if (r.phase == Phase.PANEL) {
            r.phase = Phase.GROUPS;
            // If a panel selector was configured, use it before trusting a structural
            // row match on an unrelated screen (such as the chat message list).
            List<WalkTree.Item> panel = tree.matches(r.panel);
            if (!r.panel.isEmpty()) {
                if (panel.size() != 1) fail("The configured avatar must match exactly one visible control.");
                open(r, tree, panel.get(0), "opening the trajectory panel");
                return;
            }
        }
        if (r.phase == Phase.GROUPS || r.phase == Phase.GROUP_RETURN) {
            Map<String,WalkTree.Item> groups = rows(r.groups, tree);
            if (r.phase == Phase.GROUP_RETURN && !r.groups.recognized(groups)) {
                fail("Could not confirm a return to the action card list.");
            }
            commitActivity(r);
            r.phase = Phase.GROUPS;
            if (visitList(r, tree, r.groups, groups, Phase.GROUP_OPEN)) return;
            if (r.range != null) back(r, tree, Phase.RANGE_BACK, "returning to the trajectory feed");
            else complete(r);
            return;
        }
        if (r.phase == Phase.GROUP_OPEN) {
            r.steps = new Level(r.stepSelectors, "step summary");
            if (r.stepSelectors.contains(MuseProfile.STEP) && MuseProfile.summary(tree)) {
                r.steps.inventory = new MuseSteps();
                r.steps.collecting = true;
                r.steps.seekTop = false;
            }
            Map<String,WalkTree.Item> groups = r.range != null && r.range.config.card
                    ? visibleCards(r, tree) : rows(r.groups, tree);
            Map<String,WalkTree.Item> steps = rows(r.steps, tree);
            if (steps.isEmpty()) {
                if (r.stepSelectors.contains(MuseProfile.STEP) && MuseProfile.summary(tree)
                        && browseScroll(r,tree,Collections.emptyList(),null,WalkTree.DOWN)) return;
                fail("Opening the action card did not reveal a configured step summary.");
            }
            r.groups.inline = r.groups.recognized(groups);
            // A tall overview may place the first step below the fold. We already visited
            // the overview while finding it; rewinding from here would leave the step list.
            if (r.stepSelectors.contains(MuseProfile.STEP) && MuseProfile.summary(tree)) {
                r.steps.seekTop = false;
                r.steps.collecting = true;
            }
            if (r.groups.inline && !Collections.disjoint(steps.keySet(), r.groups.previous)) {
                fail("The card list and summary menu have indistinguishable rows. Refine their selectors.");
            }
            r.groupCount++;
            r.phase = Phase.STEPS;
        }
        if (r.phase == Phase.STEPS || r.phase == Phase.STEP_RETURN) {
            if (r.steps.rewindCatalog) {
                if (browseScroll(r,tree,Collections.emptyList(),null,WalkTree.UP)) return;
                r.steps.rewindCatalog=false;
                r.steps.inventory.beginSweep();
                r.steps.seekTop=false;
            }
            Map<String,WalkTree.Item> steps = rows(r.steps, tree);
            if (r.steps.inventory != null && r.steps.inventory.needsTop) {
                r.steps.rewindCatalog = true;
                // Prior navigation was a verified detail -> summary click. Re-anchor at
                // the top when a restored block of identical adjacent rows is ambiguous.
                r.phase = Phase.STEPS;
                next(r,tree);
                return;
            }
            if (auditSteps(r,tree,steps)) return;
            for (String key : steps.keySet()) if (!r.steps.catalog.isEmpty() && !r.steps.catalog.contains(key))
                fail("The Muse step list changed after its overview was collected.");
            if (steps.isEmpty() && r.stepSelectors.contains(MuseProfile.STEP) && MuseProfile.summary(tree)
                    && browseScroll(r,tree,Collections.emptyList(),null,WalkTree.DOWN)) return;
            if (r.phase == Phase.STEP_RETURN && !r.steps.recognized(steps)
                    && !(r.steps.inventory!=null && !steps.isEmpty() && MuseProfile.summary(tree))) {
                fail("Could not confirm a return to the step summary menu.");
            }
            r.phase = Phase.STEPS;
            if (visitList(r, tree, r.steps, steps, Phase.STEP_OPEN)) return;
            if (!r.steps.catalog.isEmpty() && !r.steps.visited.equals(r.steps.catalog))
                fail("Some reviewed Muse steps did not reappear during traversal.");
            if (!r.activityTitle.isEmpty()) r.pendingActivity = r.activityTitle + " — " + r.steps.visited.size() + (r.steps.visited.size()==1 ? " verified step" : " verified steps");
            if (r.range != null && r.range.config.card) {
                if (r.groups.inline) {
                    WalkTree.Item visible = visibleCards(r, tree).get(r.groups.selected);
                    if (visible == null) fail("Could not locate the selected inline trajectory.");
                    r.phase = Phase.RANGE_BACK;
                    WalkTree.Item control = visible.has(WalkTree.COLLAPSE) ? visible : visible.clickable();
                    if (control != null && control.has(WalkTree.COLLAPSE)) act(r, tree, control, WalkTree.COLLAPSE, "closing the selected trajectory");
                    else next(r, tree);
                } else back(r, tree, Phase.RANGE_BACK, "returning to the trajectory list");
            } else finishSelection(r, tree, r.groups, Phase.GROUP_RETURN);
            return;
        }
        if (r.phase == Phase.STEP_OPEN) {
            if (r.stepSelectors.contains(MuseProfile.STEP) && MuseProfile.control(tree,"Go back") == null)
                fail("Muse did not open the selected step detail.");
            Map<String,WalkTree.Item> steps = rows(r.steps, tree);
            r.steps.inline = r.steps.recognized(steps);
            if (r.steps.inline && !steps.containsKey(r.steps.selected)) {
                fail("Cannot identify the selected summary in the inline step view.");
            }
            // A detached detail may reuse the same generic row widgets for its own content.
            // Its rows are never treated as entries in the summary menu.
            r.page = new Page();
            r.phase = Phase.DETAIL;
            r.stepCount++;
            if (r.recordingAt > 0 && r.stepSelectors.contains(MuseProfile.STEP)) {
                long cue = Math.max(0,Math.min(now(),r.stableSince + r.dwell/2) - r.recordingAt);
                r.detailCues.add(cue + " ms | activity " + r.groupCount + " | step " + (r.steps.visited.size()+1));
            }
        }
        if (r.phase == Phase.DETAIL) {
            Map<String,WalkTree.Item> steps = r.steps.inline ? rows(r.steps, tree) : Collections.emptyMap();
            WalkTree.Item current = steps.get(r.steps.selected);
            if (r.steps.inline && current == null) fail("Lost the selected summary in the inline step view.");
            List<WalkTree.Item> protectedRows = new ArrayList<>(steps.values());
            if (r.groups.inline && r.steps.inline) {
                protectedRows.addAll((r.range != null && r.range.config.card
                        ? visibleCards(r, tree) : rows(r.groups, tree)).values());
            }
            if (browse(r, tree, protectedRows, current)) return;
            finishSelection(r, tree, r.steps, Phase.STEP_RETURN);
        }
    }

    private Map<String,WalkTree.Item> visibleCards(Run r, WalkTree tree) {
        Map<String,WalkTree.Item> result = new LinkedHashMap<>();
        for (RangeWalk.Visible v : r.range.visible(tree)) result.put(v.entry.key, v.anchor);
        return result;
    }

    /** A continuous pass over the overview distinguishes overlapping pages from a
     * previously seen step repeated elsewhere. The subsequent walk must visit every key. */
    private boolean auditSteps(Run r, WalkTree tree, Map<String,WalkTree.Item> visible) {
        Level level = r.steps;
        if (level.collecting) {
            level.catalog.addAll(visible.keySet());
            if (scroll(r,tree,WalkTree.DOWN,new ArrayList<>(visible.values()),null)) return true;
            if (level.catalog.isEmpty()) fail("The overview exposed no identifiable Muse steps.");
            level.collecting = false;
            WalkTree.Item pane=tree.scroll(WalkTree.UP,new ArrayList<>(visible.values()),null,r.scrolling);
            if (pane!=null && r.groups.selected!=null && !r.groups.inline && MuseProfile.summary(tree)) {
                // Android Back preserves the Activity page, and reopening the same
                // verified card restores its overview at the top without a downward
                // sheet gesture that can collapse the sheet at its upper scroll edge.
                r.seekReopenTop=true;
                back(r,tree,Phase.MUSE_REOPEN,"closing the inventoried overview before replay");
                return true;
            }
            level.rewindCatalog = true;
            next(r,tree);
            return true;
        }
        return false;
    }

    private boolean openDay(Run r, WalkTree tree) {
        WalkTree.Item closed = MuseProfile.closedDay(tree,r.openedDays);
        if (closed == null) return false;
        act(r,tree,closed,WalkTree.CLICK,"expanding the Muse activity day");
        return true;
    }

    private boolean expandSheet(Run r, WalkTree tree) {
        WalkTree.Item grip = MuseProfile.control(tree,"Dismiss");
        Rect screen = tree.all.get(0).bounds;
        if (r.compactSheet || grip == null || screen.height() <= 0 || grip.bounds.centerY() <= screen.top + screen.height() * 0.34f
                || MuseProfile.contentFits(tree)) return false;
        r.sheetDragAt=now();
        r.sheetAttempts++;
        r.scrolling.reset();
        r.sheetY = grip.bounds.centerY();
        for (WalkTree.Item n : tree.items) if (r.sheetAttempts==1 && n.has(WalkTree.EXPAND) && (n==grip || grip.inside(n))) {
            act(r,tree,n,WalkTree.EXPAND,"expanding the Muse activity sheet");
            return true;
        }
        Path stroke = new Path();
        stroke.moveTo(grip.bounds.centerX(),grip.bounds.centerY());
        stroke.lineTo(grip.bounds.centerX(),screen.top + screen.height() * 0.0625f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(stroke,0,r.sheetAttempts<3 ? 670 : 800)).build();
        if (!dispatchGesture(gesture,new GestureResultCallback() {
            @Override public void onCancelled(GestureDescription ignored) {
                // A transient sheet animation can cancel a drag. The bounded settle
                // check re-reads the handle before any retry.
                if (run == r && r.sheetY >= 0) later(POLL);
            }
        },handler)) fail("Android could not expand the Muse sheet.");
        after(r,tree,"expanding the Muse activity sheet");
        return true;
    }

    private boolean awaitExpansion(Run r,WalkTree tree,long t) {
        WalkTree.Item grip=MuseProfile.control(tree,"Dismiss");
        int top=tree.all.get(0).bounds.top, height=tree.all.get(0).bounds.height();
        if (grip!=null && (grip.bounds.centerY()<=top+height*0.34f || MuseProfile.contentFits(tree))) {
            r.sheetY=-1; r.sheetAttempts=0; r.source=null; r.observed=null;
            return false;
        }
        if (t-r.sheetDragAt<1650) { later(POLL); return true; }
        if (grip==null || !MuseProfile.sheet(tree)) fail("Muse changed the sheet during expansion.");
        if (r.sheetAttempts<3 && expandSheet(r,tree)) return true;
        // A device can enforce a shorter detent. Its scrollable content has an
        // independent origin, so the ordinal walker can still verify all entries.
        for (WalkTree.Item n : tree.items) if (n.kind.endsWith(".ScrollView")
                && n.bounds.height()>=height*0.25f && (n.scrollAction(WalkTree.DOWN)!=0 || n.scrollAction(WalkTree.UP)!=0)) {
            r.compactSheet=true;
            r.sheetY=-1; r.sheetAttempts=0; r.source=null; r.observed=null;
            return false;
        }
        fail("Muse did not expose an expanded or traversable compact activity sheet.");
        return false;
    }

    /** A full-height sheet can collapse to a compact sheet on the first Back.
     * Only issue another action while that source sheet is still stably verified. */
    private boolean awaitExit(Run r,WalkTree tree,long t) {
        ExitTransition e=r.exit;
        if (!tree.signature.equals(e.observed)) { e.observed=tree.signature; e.sameSince=t; }
        boolean activity=MuseProfile.id(tree,MuseProfile.ACTIVITY)!=null;
        boolean summary=MuseProfile.summary(tree);
        WalkTree.Item goBack=MuseProfile.control(tree,"Go back");
        boolean titled=false, steps=false;
        for (WalkTree.Item n:tree.items) {
            if (!r.activityTitle.isEmpty() && n.label.equals(r.activityTitle)) titled=true;
            if (!e.toActivity && !steps && summary && MuseProfile.isStep(tree,n)) steps=true;
        }
        boolean arrived=e.toActivity ? activity && MuseProfile.control(tree,"Dismiss")==null
                : summary && goBack==null && (steps || titled || !tree.matches("MAIN").isEmpty());
        if (arrived) {
            // Android can expose the next accessibility window before a detent or
            // fragment animation is done. Verify the destination twice before counting.
            if (t-e.sameSince<250) { later(POLL); return true; }
            r.exit=null; r.source=null; r.observed=null;
            return false;
        }
        if (t-e.began>9000) fail("Muse did not return to the expected "+(e.toActivity?"Activity list.":"step overview."));
        if (t-e.lastSent<1050 || t-e.sameSince<400) { later(POLL); return true; }
        boolean originalWindow=tree.all.get(0).node.getWindowId()==e.window;
        boolean verifiedSource=e.toActivity ? summary && !activity && (originalWindow || titled)
                : !activity && MuseProfile.sheet(tree) && goBack!=null && originalWindow;
        if (verifiedSource) {
            if (e.attempts>=4) fail("Muse kept the "+(e.toActivity?"activity overview":"step detail")+" open after repeated verified exits.");
            boolean sent=e.toActivity ? performGlobalAction(GLOBAL_ACTION_BACK) : goBack.node.performAction(WalkTree.CLICK);
            if (!sent && !e.toActivity) fail("Muse rejected its step detail Go back control.");
            e.attempts++; e.lastSent=t; e.sameSince=t;
            after(r,tree,e.toActivity?"dismissing the verified Muse activity overview":"returning to the verified Muse step overview");
        } else later(POLL);
        return true;
    }

    private void back(Run r, WalkTree tree, Phase destination, String description) {
        r.phase = destination;
        r.compactSheet=false; r.sheetAttempts=0;
        r.scrolling.reset();
        if (MuseProfile.supports(r.pkg)) {
            boolean toActivity=destination==Phase.RANGE_BACK || destination==Phase.GROUP_RETURN || destination==Phase.MUSE_REOPEN;
            boolean fromSummary=toActivity && MuseProfile.summary(tree);
            WalkTree.Item goBack=MuseProfile.control(tree,"Go back");
            if (fromSummary || (destination==Phase.STEP_RETURN && goBack!=null && MuseProfile.sheet(tree))) {
                ExitTransition exit=new ExitTransition(fromSummary,tree.all.get(0).node.getWindowId());
                r.exit=exit;
                WalkTree.Item control=fromSummary?MuseProfile.control(tree,"Close sheet"):goBack;
                boolean sent=control!=null && control.node.performAction(WalkTree.CLICK);
                if (!sent && fromSummary) sent=performGlobalAction(GLOBAL_ACTION_BACK);
                if (!sent) fail("Could not initiate the verified Muse sheet exit.");
                exit.attempts=1; exit.lastSent=now(); exit.sameSince=exit.lastSent;
                after(r,tree,description);
                return;
            }
            if (destination==Phase.RANGE_HOME && MuseProfile.id(tree,MuseProfile.ACTIVITY)!=null) {
                WalkTree.Item close=MuseProfile.control(tree,"Close");
                if (close!=null) { act(r,tree,close,WalkTree.CLICK,description); return; }
            }
        }
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) fail("Could not go back while " + description + ".");
        after(r, tree, description);
    }

    private void startAtTop(Run r) {
        r.videoTop = true;
        r.source = null;
        r.observed = null;
        r.deadline = now() + ACTION_TIMEOUT + r.dwell;
        later(POLL);
    }

    /** Discovery performs only reading and navigation. Playback uses one recorder for all selected entries. */
    private boolean rangeStep(Run r, WalkTree tree) {
        RangeWalk range = r.range;
        RangeWalk.Config cfg = range.config;
        switch (r.phase) {
            case RANGE_TOP:
                if (cfg.card && MuseProfile.supports(cfg.pkg) && cfg.anchorRules.contains(MuseProfile.CARD)) {
                    // Muse's fixed avatar opens a separate Activity feed. The feed's top is
                    // the start of this range; rewinding the unrelated chat can load its
                    // entire message history before the first trajectory is shown.
                    if (MuseProfile.id(tree,MuseProfile.ACTIVITY) != null) {
                        range.directList = true;
                        r.phase = Phase.RANGE_LIST_TOP;
                        return rangeStep(r,tree);
                    }
                    List<WalkTree.Item> fixed = tree.matches(cfg.avatar);
                    if (fixed.size() != 1 || MuseProfile.id(tree,"hatch-chat-view") == null)
                        fail("Open the Muse conversation with its profile button or the Activity list before scanning.");
                    range.openedShared = true;
                    r.phase = Phase.RANGE_LIST_TOP;
                    open(r,tree,fixed.get(0),"opening Muse Activity from the fixed profile button");
                    return true;
                }
                if (browseScroll(r, tree, Collections.emptyList(), null, WalkTree.UP)
                        || browseScroll(r, tree, Collections.emptyList(), null, WalkTree.LEFT)) return true;
                if (cfg.card && !cfg.avatar.isEmpty()) {
                    List<WalkTree.Item> avatars = tree.matches(cfg.avatar);
                    if (avatars.size() > 1) fail("The shared avatar selector matches multiple controls.");
                    if (avatars.isEmpty()) {
                        if (MuseProfile.id(tree,MuseProfile.ACTIVITY) == null && range.visible(tree).isEmpty())
                            fail("At the top, neither a shared avatar nor a timestamped card list could be verified.");
                        range.directList = true;
                    }
                }
                r.phase = Phase.RANGE_FEED;
                if (!range.discovering) {
                    // Let the recorder start and hold the verified top before the first action.
                    range.verifiedTop = true;
                    startAtTop(r);
                    return true;
                }
                return rangeStep(r, tree);
            case RANGE_FEED:
                if (cfg.card && !range.directList && !cfg.avatar.isEmpty()) {
                    List<WalkTree.Item> avatar = tree.matches(cfg.avatar);
                    if (avatar.size() != 1) fail("The shared trajectory avatar changed after reaching the top.");
                    range.openedShared = true;
                    r.phase = Phase.RANGE_LIST_TOP;
                    open(r, tree, avatar.get(0), "opening the timestamped action cards");
                    return true;
                }
                r.phase = Phase.RANGE_ROWS;
                return rangeStep(r, tree);
            case RANGE_LIST_TOP:
                if (MuseProfile.supports(cfg.pkg) && cfg.anchorRules.contains(MuseProfile.CARD)
                        && MuseProfile.id(tree,MuseProfile.ACTIVITY) == null)
                    fail("The Muse profile did not reveal its Activity list.");
                if (browseScroll(r, tree, Collections.emptyList(), null, WalkTree.UP)
                        || browseScroll(r, tree, Collections.emptyList(), null, WalkTree.LEFT)) return true;
                r.phase = Phase.RANGE_ROWS;
                if (!range.discovering && !range.verifiedTop) {
                    range.verifiedTop = true;
                    startAtTop(r);
                    return true;
                }
                return rangeStep(r, tree);
            case RANGE_BACK:
                List<RangeWalk.Visible> returned = range.visible(tree);
                range.verifyReturn(returned);
                commitActivity(r);
                range.done++;
                if (range.done == range.selected.size()) {
                    if (range.openedShared) back(r, tree, Phase.RANGE_HOME, "returning to the shared trajectory avatar");
                    else complete(r);
                    return true;
                }
                r.phase = Phase.RANGE_ROWS;
                return rangeStep(r, tree);
            case RANGE_ROWS:
                if (MuseProfile.supports(r.pkg) && openDay(r,tree)) return true;
                List<RangeWalk.Visible> rows = range.visible(tree);
                if (range.discovering) range.collect(rows);
                else {
                    RangeWalk.Visible chosen = range.nextSelected(rows);
                    if (chosen != null) {
                        r.groups = new Level(WalkTree.selectors(cfg.groups), "action card");
                        if (cfg.card) {
                            if (MuseProfile.CARD.equals(chosen.anchor.id) && MuseProfile.muse(chosen.anchor)) r.activityTitle = chosen.entry.label;
                            r.groups.selected = chosen.entry.key;
                            r.groups.previous = new HashSet<>();
                            for (RangeWalk.Visible v : rows) r.groups.previous.add(v.entry.key);
                            r.phase = Phase.GROUP_OPEN;
                        } else r.phase = Phase.GROUPS;
                        String selectedKey=chosen.entry.key;
                        previewOpen(r,tree,chosen.anchor,t -> {
                            for(RangeWalk.Visible visible:range.visible(t))
                                if(visible.entry.key.equals(selectedKey)) return visible.anchor;
                            return null;
                        },chosen.entry.label,"opening reviewed trajectory " + (range.done + 1));
                        return true;
                    }
                }
                List<WalkTree.Item> anchors = new ArrayList<>();
                for (RangeWalk.Visible v : rows) anchors.add(v.anchor);
                if (scroll(r, tree, WalkTree.DOWN, anchors, null) || scroll(r, tree, WalkTree.RIGHT, anchors, null)) return true;
                if (!range.discovering) fail("The selected trajectory did not reappear during playback.");
                if (range.catalog.isEmpty()) fail("No configured trajectory rows were found on the accessible list.");
                if (range.openedShared) back(r, tree, Phase.RANGE_HOME, "returning to the conversation after discovery");
                else end(true, "The range is ready for review.");
                return true;
            case RANGE_HOME:
                if (tree.matches(cfg.avatar).size() != 1) fail("Could not confirm a return to the shared trajectory avatar.");
                if (range.discovering) end(true, "The range is ready for review.");
                else complete(r);
                return true;
            default: return false;
        }
    }

    private boolean visitList(Run r, WalkTree tree, Level level, Map<String,WalkTree.Item> rows, Phase next) {
        if (rows.isEmpty()) fail("No configured " + level.label + " is visible.");
        List<WalkTree.Item> nodes = new ArrayList<>(rows.values());
        if (level.seekTop) {
            if (scroll(r, tree, WalkTree.UP, nodes, null)) return true;
            level.seekTop = false;
        }
        if (level.seekLeft) {
            if (scroll(r, tree, WalkTree.LEFT, nodes, null)) return true;
            level.seekLeft = false;
        }
        if (level.inventory != null && level.visited.size()<level.inventory.size()) {
            int nextOrdinal = level.visited.size();
            if (!rows.containsKey(MuseSteps.key(nextOrdinal))) {
                int first = Integer.parseInt(rows.keySet().iterator().next().substring("Muse step ".length()))-1;
                if (first>nextOrdinal) {
                    level.rewindCatalog=true;
                    r.phase=Phase.STEPS;
                    next(r,tree);
                    return true;
                }
            }
        }
        for (Map.Entry<String,WalkTree.Item> row : rows.entrySet()) {
            if (level.inventory!=null && !row.getKey().equals(MuseSteps.key(level.visited.size()))) continue;
            if (level.visited.contains(row.getKey())) continue;
            if (level.inventory!=null && r.fastView && !level.heldOverviews.contains(overviewDigest(tree))) {
                level.fullBeforeOpen=true;
                r.source=null; r.observed=null;
                r.deadline=now()+ACTION_TIMEOUT+r.dwell;
                later(POLL);
                return true;
            }
            level.fullBeforeOpen=false;
            level.selected = row.getKey();
            if (level == r.groups && MuseProfile.CARD.equals(row.getValue().id) && MuseProfile.muse(row.getValue()))
                r.activityTitle = MuseProfile.cardFields(tree,row.getValue()).get(0);
            level.previous = new HashSet<>(rows.keySet());
            r.phase = next;
            String selectedKey=level.selected;
            previewOpen(r,tree,row.getValue(),t -> rows(level,t).get(selectedKey),
                    level==r.groups?r.activityTitle:"","opening " + level.label + " " + (level.visited.size() + 1));
            return true;
        }
        if (scroll(r, tree, WalkTree.RIGHT, nodes, null)) return true;
        if (level.inventory != null && !level.collecting && level.visited.size()<level.inventory.size()) {
            WalkTree.Item pane=tree.scroll(WalkTree.DOWN,nodes,null,r.scrolling);
            float distance=pane==null?0:level.inventory.seekFraction(level.visited.size(),pane.bounds.height());
            if (pane!=null && distance>1.15f) {
                int action=pane.scrollAction(WalkTree.DOWN);
                Bundle amount=null;
                if (Build.VERSION.SDK_INT>=35 && pane.node.isGranularScrollingSupported() && WalkTree.supportsOverlap(action)) {
                    amount=new Bundle();
                    amount.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT,distance);
                }
                // A non-granular provider advances one normal page toward the next
                // occurrence. No coordinate tap or assumption about the landing row.
                r.scrolling.arm(pane,WalkTree.DOWN);
                if (!pane.node.performAction(action,amount)) fail("Muse rejected navigation to an inventoried step.");
                after(r,tree,"seeking the next inventoried Muse step");
                return true;
            }
        }
        if (scroll(r, tree, WalkTree.DOWN, nodes, null)) {
            level.seekLeft = true;
            return true;
        }
        return false;
    }

    private void finishSelection(Run r, WalkTree tree, Level level, Phase returning) {
        level.visited.add(level.selected);
        r.phase = returning;
        if (level.inline) {
            WalkTree.Item selected = rows(level, tree).get(level.selected);
            if (selected == null) fail("Cannot recover the selected " + level.label + ".");
            WalkTree.Item control = selected.has(WalkTree.COLLAPSE) ? selected : selected.clickable();
            if (control != null && control.has(WalkTree.COLLAPSE)) {
                act(r, tree, control, WalkTree.COLLAPSE, "closing the previous " + level.label);
            } else {
                // In a split-pane layout the parent menu is still visible.
                next(r, tree);
            }
        } else {
            back(r,tree,returning,"returning from the " + level.label);
        }
    }

    private Map<String,WalkTree.Item> rows(Level level, WalkTree tree) {
        if (level.inventory != null) return level.inventory.rows(tree,run.scrolling,level.collecting,
                run.phase==Phase.STEP_RETURN, level.rewindCatalog);
        Map<String,WalkTree.Item> rows = new LinkedHashMap<>();
        for (WalkTree.Item n : tree.items) {
            for (String selector : level.selectors) {
                if (!n.matches(selector)) continue;
                String key;
                if (MuseProfile.CARD.equals(n.id) && MuseProfile.muse(n)) {
                    List<String> card = MuseProfile.cardFields(tree,n);
                    if (card == null) break;
                    key = card.get(0) + "|" + card.get(2);
                } else key = tree.rowKey(n);
                if (rows.put(key, n) != null) fail("Two visible " + level.label + " rows have the same identity. Refine the selectors.");
                break;
            }
        }
        return rows;
    }

    /** For inline steps, operate on the selected row's descendants and on separate detail panels. */
    private boolean allowed(WalkTree.Item n, List<WalkTree.Item> rows, WalkTree.Item current) {
        for (WalkTree.Item row : rows) {
            WalkTree.Item control = row.clickable();
            if (control == null) control = row;
            boolean containsSelected = current != null && current.inside(control);
            if (row.inside(n) || n == control || (!containsSelected && n.inside(control))) return false;
        }
        return true;
    }

    private boolean browse(Run r, WalkTree tree, List<WalkTree.Item> rows, WalkTree.Item current) {
        Page page = r.page;
        if (page.seekTop) {
            if (browseScroll(r, tree, rows, current, WalkTree.UP)) return true;
            page.seekTop = false;
        }
        if (page.seekLeft) {
            if (browseScroll(r, tree, rows, current, WalkTree.LEFT)) return true;
            page.seekLeft = false;
        }
        for (WalkTree.Item n : tree.items) {
            if (!allowed(n, rows, current) || !n.node.isEnabled()) continue;
            for (String selector : r.expanders) {
                if (!n.matches(selector)) continue;
                r.seenExpanders.add(selector);
                if (n.has(WalkTree.COLLAPSE)) continue;
                WalkTree.Item c = n.clickable();
                if (c == null || c.has(WalkTree.COLLAPSE) || !allowed(c, rows, current)) continue;
                String identity = selector + "|" + tree.rowKeyForControl(n);
                if (page.namedClicks.add(identity)) {
                    act(r, tree, n.has(WalkTree.EXPAND) ? n : c, n.has(WalkTree.EXPAND)
                            ? WalkTree.EXPAND : WalkTree.CLICK, "opening the configured control " + selector);
                    return true;
                }
            }
            if (n.has(WalkTree.EXPAND) && !n.has(WalkTree.COLLAPSE)) {
                act(r, tree, n, WalkTree.EXPAND, "expanding the accessible control");
                return true;
            }
        }
        if (browseScroll(r, tree, rows, current, WalkTree.RIGHT)) return true;
        if (browseScroll(r, tree, rows, current, WalkTree.DOWN)) {
            page.seekLeft = true;
            return true;
        }
        return false;
    }

    private boolean browseScroll(Run r, WalkTree tree, List<WalkTree.Item> rows, WalkTree.Item current, int dir) {
        WalkTree.Item best = null;
        for (WalkTree.Item n : tree.items) {
            if (!allowed(n, rows, current)) continue;
            n.checkScrollAxis();
            if (n.scrollAction(dir) != 0 && !r.scrolling.atEdge(n,dir) && (best == null || n.depth > best.depth)) best = n;
        }
        if (best == null) return false;
        act(r, tree, best, best.scrollAction(dir), "scrolling a content panel");
        return true;
    }

    private boolean scroll(Run r, WalkTree tree, int dir, List<WalkTree.Item> rows, WalkTree.Item subtree) {
        WalkTree.Item best = tree.scroll(dir, rows, subtree, r.scrolling);
        if (best == null) return false;
        act(r, tree, best, best.scrollAction(dir), "scrolling the trajectory list");
        return true;
    }

    private void previewOpen(Run r,WalkTree tree,WalkTree.Item target,PreviewRow.Finder finder,String title,String action) {
        if(r.markerSettings==null)r.markerSettings=TapHighlight.Settings.read(this);
        long now=now();
        r.preview=new PreviewRow(finder,r.markerSettings,title==null?"":title,action,
                target.node.getWindowId(),now+ACTION_TIMEOUT+Math.max(1000,r.markerSettings.dwellMs));
        advancePreview(r,tree,now);
    }

    private void advancePreview(Run r,WalkTree tree,long t) {
        PreviewRow p=r.preview;
        if(p==null)return;
        if(t>=p.expires)fail("The next row could not be fully presented before the tap.");
        if(t<p.waitUntil) { later(Math.min(POLL,p.waitUntil-t)); return; }
        WalkTree.Item tap=p.finder.find(tree);
        if(tap==null || tap.node.getWindowId()!=p.window) {
            marker.clear(); p.resetHold();
            if(p.missingSince==0)p.missingSince=t;
            if(t-p.missingSince>1700)fail("The next selected row changed during its preview.");
            later(POLL); return;
        }
        p.missingSince=0;
        WalkTree.Item row=p.displayItem(tree,tap);
        int margin=Math.max(3,Math.round(getResources().getDisplayMetrics().density*4));
        PreviewRow.Position pos=new PreviewRow.Position(tree,row,r.scrolling,margin,previewDisplay());
        if(pos.direction!=-1) {
            marker.clear(); p.resetHold();
            if(++p.adjustments>4)fail("The target could not expose the selected row fully before opening it.");
            int show=AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId();
            if(p.adjustments!=1 || !row.has(show) || !row.node.performAction(show))
                positionPreview(r,pos);
            p.waitUntil=t+800;
            later(POLL); return;
        }
        if(pos.visible.isEmpty())fail("The selected row has no visible display area.");
        if(!p.settings.enabled()) {
            r.preview=null;
            open(r,tree,tap,p.action);
            return;
        }
        if(p.displayed==null || !p.displayed.equals(pos.visible)) {
            marker.clear(); p.resetHold();
            if(!marker.show(pos.visible,p.settings))fail("Android could not show the selected row highlight.");
            p.displayed=new Rect(pos.visible);
            later(POLL); return;
        }
        if(!p.waitingForImage) {
            long painted=marker.paintedAt();
            if(painted==0 || t-painted<p.settings.dwellMs) { later(POLL); return; }
        }
        if(r.savePng && !p.imageSaved) {
            if(!p.waitingForImage) {
                // Capture an unmarked full display, then use the identical local painter.
                // This also handles Android builds which omit accessibility overlay windows from screenshots.
                marker.clear(); p.waitingForImage=true; p.waitUntil=t+170;
                later(180); return;
            }
            r.observed=tree.signature;
            requestFrame(r,tree); return;
        }
        marker.clear();
        if(!tap.node.refresh() || !tap.node.isVisibleToUser() || !r.pkg.contentEquals(WalkTree.string(tap.node.getPackageName())))
            fail("The target row changed immediately before its tap.");
        r.preview=null;
        open(r,tree,tap,p.action);
    }

    private Rect previewDisplay() {
        android.view.WindowMetrics metrics=previewWindows.getCurrentWindowMetrics();
        android.graphics.Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
        Rect bounds=new Rect(metrics.getBounds());
        bounds.inset(bars.left,bars.top,bars.right,bars.bottom);
        return bounds;
    }

    private void positionPreview(Run r,PreviewRow.Position position) {
        WalkTree.Item pane=position.pane;
        int direction=position.direction;
        Rect area=position.viewport;
        boolean horizontal=direction==WalkTree.LEFT || direction==WalkTree.RIGHT;
        int length=horizontal ? area.width() : area.height();
        float distance=Math.min(length*.25f,Math.max(32f,length*.17f));
        if(area.width()<28 || area.height()<28 || distance<12)fail("The selected row's scroll area is too small.");
        float x=area.exactCenterX(), y=area.exactCenterY();
        float shift=direction==WalkTree.UP || direction==WalkTree.LEFT ? distance : -distance;
        float endX=horizontal?x+shift:x, endY=horizontal?y:y+shift;
        Path line=new Path(); line.moveTo(x,y); line.lineTo(endX,endY);
        line.lineTo(endX+(horizontal?0:5f),endY+(horizontal?5f:0));
        r.scrolling.arm(pane,direction);
        GestureDescription movement=new GestureDescription.Builder().addStroke(
                new GestureDescription.StrokeDescription(line,0,420)).build();
        if(!dispatchGesture(movement,null,handler)) fail("Android rejected a scroll to reveal the selected row.");
    }

    private void open(Run r, WalkTree tree, WalkTree.Item n, String action) {
        if (n.has(WalkTree.EXPAND)) { act(r, tree, n, WalkTree.EXPAND, action); return; }
        WalkTree.Item c = n.clickable();
        if (c == null) fail("The configured control cannot be opened through accessibility.");
        act(r, tree, c, WalkTree.CLICK, action);
    }

    /** A single stroke avoids the cross-callback timing dependency of a continued gesture.
     * The final short horizontal segment keeps vertical velocity near zero before release. */
    private void scrollMuseList(Run r, WalkTree tree, WalkTree.Item n, String action, int attempt) {
        Rect box = new Rect(n.bounds);
        if (!box.intersect(tree.all.get(0).bounds) || box.height()<90 || box.width()<48)
            fail("The Muse scroll area is too small to traverse.");
        ListDrag drag=new ListDrag(tree,n,action,attempt);
        r.drag=drag;
        // Activity rows fill the feed. The leading gutter scrolls its parent without
        // starting on the action title or an embedded horizontal gesture surface.
        float x=drag.activityFeed ? box.left+Math.min(box.width()*.12f,
                16f*getResources().getDisplayMetrics().density) : box.left+box.width()*.44f;
        // Keep substantially more than one card in common on the compact Activity
        // page. Its header, date row and navigation insets reduce the usable area.
        float distance=box.height()*(drag.activityFeed ? .23f : .44f);
        float end=box.top+box.height()*.76f-distance;
        float tail=Math.min(box.width()*0.28f,Math.max(32f,distance*0.30f));
        Path slide=new Path();
        slide.moveTo(x,box.top+box.height()*0.76f);
        slide.lineTo(x,end);
        slide.lineTo(x+tail,end);
        GestureDescription swipe=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(slide,0,780)).build();
        GestureResultCallback callback=new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription ignored) { done(); }
            @Override public void onCancelled(GestureDescription ignored) { done(); }
            private void done() {
                if (run==r && r.drag==drag) { drag.callbackDone=true; later(POLL); }
            }
        };
        if (!dispatchGesture(swipe,callback,handler)) drag.callbackDone=true;
        after(r,tree,action);
    }

    private boolean awaitDrag(Run r,WalkTree tree,long time) {
        ListDrag drag=r.drag;
        WalkTree.Item pane=drag.find(tree);
        long age=time-drag.began;
        if (pane!=null && age>=820) {
            boolean changed=!drag.before.equals(ListDrag.contents(tree,pane));
            boolean edge=pane.scrollAction(WalkTree.DOWN)==0 || r.scrolling.atEdge(pane,WalkTree.DOWN);
            if (r.scrolling.moved || changed || edge) {
                // Finish from actual target state even when the system never delivered its
                // callback. Apply the normal settle/hold checks before capturing or tapping.
                r.drag=null;
                r.observed=null;
                if (edge) r.source=null;
                return false;
            }
        }
        long deadline=drag.callbackDone?1750:3700;
        if (age<deadline) { later(POLL); return true; }
        if (pane==null) fail("The Muse scroll pane changed before the move could be verified.");
        if (drag.attempt>0) fail("The verified Muse list stayed stationary after two scroll attempts.");
        // Only supersede an unconfirmed stroke after the exact same target pane has
        // remained stationary. Android cancels stale in-flight gestures on a new dispatch.
        r.scrolling.arm(pane,WalkTree.DOWN);
        scrollMuseList(r,tree,pane,drag.action,1);
        return true;
    }

    private void act(Run r, WalkTree tree, WalkTree.Item n, int actionId, String action) {
        int dir = -1;
        for (int i=WalkTree.UP;i<=WalkTree.DOWN;i++) if (n.scrollAction(i)==actionId) { dir=i; break; }
        if (dir >= 0) r.scrolling.arm(n,dir);
        else {
            r.scrolling.reset();
            if (r.sheetY<0) { r.compactSheet=false; r.sheetAttempts=0; }
        }
        Bundle args = null;
        boolean listDown = MuseProfile.supports(r.pkg) && n.scrollAction(WalkTree.DOWN)==actionId
                && (MuseProfile.id(tree,MuseProfile.ACTIVITY) != null || MuseProfile.summary(tree));
        boolean granular = Build.VERSION.SDK_INT >= 35 && n.node.isGranularScrollingSupported() && WalkTree.supportsOverlap(actionId);
        // Use the normal native action when Android reports the endpoint is already
        // within an overlapping increment. It cannot jump past an unseen row there.
        Integer remaining = listDown ? r.scrolling.remaining(n,WalkTree.DOWN) : null;
        boolean activityFeed=listDown && ListDrag.isActivityFeed(n);
        if (listDown && !granular && (activityFeed || remaining==null || remaining>n.bounds.height()*0.45f)) {
            scrollMuseList(r,tree,n,action,0);
            return;
        }
        if (granular) {
            args = new Bundle();
            args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT, activityFeed ? .23f : listDown ? .45f : .8f);
        }
        if (!n.node.performAction(actionId, args)) fail("The target rejected the action while " + action + ".");
        after(r, tree, action);
    }

    private void after(Run r, WalkTree tree, String action) {
        r.action = action;
        r.source = tree.signature;
        r.observed = null;
        r.deadline = now() + ACTION_TIMEOUT + r.dwell;
        status = action;
        later(POLL);
    }

    private void commitActivity(Run r) {
        if (!r.pendingActivity.isEmpty()) {
            r.verifiedActivities.add(r.pendingActivity);
            r.pendingActivity = "";
            r.activityTitle = "";
        }
    }

    private void complete(Run r) {
        Set<String> unused = new java.util.LinkedHashSet<>(r.expanders);
        unused.removeAll(r.seenExpanders);
        String note = unused.isEmpty() ? "" : "\nOptional expansion controls not present: " + String.join(", ", unused);
        end(true, "Reached the end of the exposed controls for this profile." + note);
    }

    private static void fail(String text) { throw new IllegalStateException(text); }

    private boolean releasePager(Run r) {
        if(r.heldPager==null)return false;
        GestureDescription.StrokeDescription held=r.heldPager;r.heldPager=null;
        Path release=new Path();release.moveTo(r.heldX,r.heldY);release.lineTo(r.heldX,r.heldY);
        GestureDescription.StrokeDescription up=held.continueStroke(release,0,32,false);
        dispatchGesture(new GestureDescription.Builder().addStroke(up).build(),null,handler);
        return true;
    }

    private void end(boolean success, String reason) {
        Run r = run;
        if (r == null) return;
        if(r.feedback!=null)r.feedback.close();
        releasePager(r);
        if (r.range!=null) r.range.releaseWindow();
        handler.removeCallbacks(poll);
        if(marker!=null)marker.clear();
        r.preview=null;
        r.scrolling.reset();
        if (r.range != null && r.range.discovering) {
            run = null;
            reviewed = success ? r.range : null;
            status = (success ? "Scan complete. " + reviewed.review().size() + " trajectories in range. " : "Scan incomplete. ") + reason;
            Intent review = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if(!success)review.putExtra("scanFailed",true);
            try { startActivity(review); } catch (RuntimeException ignored) { /* User can also return through Recents. */ }
            return;
        }
        if (r.video) {
            CaptureRecorder.finish(this);
            String error = CaptureRecorder.lastError();
            if (!error.isEmpty()) {
                success = false;
                if (!reason.contains(error)) reason += " " + error;
            }
        }
        String result = (success ? "Complete" : "Incomplete") + " — " + reason
                + "\nTarget: " + r.pkg + "\nScreenshots: " + r.frames
                + (r.range == null ? "" : "\nSelected trajectories completed: " + r.range.done + " / " + r.range.selected.size())
                + "\nAction cards opened: " + r.groupCount + "\nTrajectory steps opened: " + r.stepCount + "\n"
                + (r.verifiedActivities.isEmpty() ? "" : String.join("\n",r.verifiedActivities) + "\n")
                + (r.detailCues.isEmpty() ? "" : "Video detail times\n" + String.join("\n",r.detailCues) + "\n");
        if(r.conversation!=null) result=(success?"Complete":"Incomplete")+" — "+reason+"\nTarget: "+r.pkg+
                "\nScreenshots: "+r.frames+r.conversation.summary();
        status = result;
        try { Files.write(new File(r.dir, "status.txt").toPath(), result.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ex) { status = "Incomplete — could not write status.txt. " + message(ex); }
        run = null;
        if (r.video) {
            CaptureCompletion.present(this, r.dir);
        } else {
            String captureStatus = status;
            packaging = true;
            status = "Saving screenshot ZIP.";
            RunFiles.packageRun(r.dir, packaged -> handler.post(() -> {
                packaging = false;
                status = packaged ? captureStatus : "Could not create the ZIP. You can retry saving it from the Library.";
                if (packaged && r.frames > 0) CaptureCompletion.present(this, r.dir);
            }));
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
