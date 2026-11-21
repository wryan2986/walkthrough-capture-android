package local.walkthrough;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Explicit user-triggered screenshots or video through one MediaProjection consent grant. */
public final class ManualCaptureService extends Service implements ManualControls.Listener {
    private static final String START="manual.start", SHOW="manual.show", STOP="manual.stop",
            MARKER="manual.marker", RECORD="manual.record";
    private static final String CHANNEL="manual_capture";
    private static final int NOTIFICATION=4702;
    private static final int MAX_RESIZE_RETRIES=2;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile boolean busy;
    private static volatile String message="";
    private static volatile ManualCaptureService current;
    private static long generation;

    private long token, captureTicket;
    private int lastStartId, width, height, density, count, failedTaps, resizeRetries;
    private boolean video, guided, pending, writing, ending;
    private String problem="", guidePackage;
    private File directory;
    private PrivacyMasks privacy;
    private ManualControls controls;
    private MediaProjection projection;
    private MediaProjection.Callback callback;
    private VirtualDisplay display;
    private ImageReader reader;
    private ManualVideo movie;
    private DisplayManager.DisplayListener displayListener;

    public static synchronized boolean start(Context context,int code,Intent grant,boolean video,boolean guide,String target) {
        if (busy || WalkService.isActive() || CaptureRecorder.isBusy()) {
            message="Another capture is in progress."; return false;
        }
        if (code!=Activity.RESULT_OK || grant==null) {
            message="Android screen sharing was not approved."; return false;
        }
        if (!Settings.canDrawOverlays(context)) {
            message="Allow Walkthrough Capture to display its floating controls."; return false;
        }
        if (guide && (!video || !MuseProfile.supports(target))) {
            message="Choose a supported app for tap guidance."; return false;
        }
        busy=true; message="Starting manual capture.";
        Intent intent=new Intent(context,ManualCaptureService.class).setAction(START)
                .putExtra("token",++generation).putExtra("code",code)
                .putExtra("grant",grant).putExtra("video",video).putExtra("guide",video && guide)
                .putExtra("guidePackage",guide ? target : "");
        try { context.startForegroundService(intent); return true; }
        catch (RuntimeException e) { busy=false; message="Android could not start manual capture."; return false; }
    }

    public static boolean isActive() { return busy; }
    public static String lastMessage() { return message; }

    // Main-thread bridge for the opt-in read-only observer. Bounds are physical screen pixels.
    static boolean wantsTargetGuidance() {
        ManualCaptureService session=current;
        return session!=null && session.guided && session.controls!=null && !session.ending;
    }

    static boolean guidesPackage(CharSequence candidate) {
        ManualCaptureService session=current;
        return session!=null && session.guided && MuseProfile.matchesSession(session.guidePackage,candidate);
    }

    static void updateGuidance(Rect screenBounds,String hint) {
        ManualCaptureService session=current;
        if(session==null || !wantsTargetGuidance())return;
        Rect target=screenBounds==null?null:new Rect(screenBounds);
        Runnable apply=()->{
            if(current==session && wantsTargetGuidance())session.controls.setGuidance(target,hint);
        };
        if(Looper.myLooper()==Looper.getMainLooper())apply.run();
        else MAIN.post(apply);
    }
    public static void showControls(Context context) {
        MAIN.post(()->{ManualCaptureService s=current; if(s!=null && !s.ending)s.openControls();});
    }
    public static void finish(Context context) {
        synchronized (ManualCaptureService.class) {
            if (!busy) return;
            ManualCaptureService s=current;
            if (s==null) { ++generation; busy=false; message="Manual capture canceled."; return; }
            MAIN.post(()->{if(current==s)s.end("");});
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        lastStartId=startId;
        if(intent==null) { if(current==null)stopSelf(startId); return START_NOT_STICKY; }
        String action=intent.getAction();
        if (!START.equals(action)) {
            if (current==this && !ending && intent.getLongExtra("token",-1)==token) {
                if(STOP.equals(action))end("");
                else if(SHOW.equals(action))openControls();
                else if(MARKER.equals(action) && movie!=null && !guided)controls.placeMarker();
                else if(RECORD.equals(action) && guided)onCapture();
            } else if(current==null && !busy)stopSelf(startId);
            return START_NOT_STICKY;
        }

        if(current!=null)return START_NOT_STICKY;
        token=intent.getLongExtra("token",-1);
        video=intent.getBooleanExtra("video",false);
        guided=video && intent.getBooleanExtra("guide",false);
        guidePackage=intent.getStringExtra("guidePackage");
        try {
            notifyForeground();
            synchronized(ManualCaptureService.class) {
                if(!busy || token!=generation) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY;
                }
                current=this;
            }
            if(WalkService.isActive() || CaptureRecorder.isBusy())throw new IOException("Another capture is in progress.");
            setup(intent);
        } catch (RuntimeException | IOException error) {
            if(current==null && busy && token==generation)current=this;
            end("Manual capture could not start.");
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void setup(Intent intent) throws IOException {
        privacy=PrivacyMasks.read(this);
        Intent grant=intent.getParcelableExtra("grant");
        projection=getSystemService(MediaProjectionManager.class)
                .getMediaProjection(intent.getIntExtra("code",Activity.RESULT_CANCELED),grant);
        if(projection==null)throw new IOException("Android screen sharing expired.");
        callback=new MediaProjection.Callback() {
            @Override public void onStop() { if(current==ManualCaptureService.this)end("Android ended screen sharing."); }
            @Override public void onCapturedContentResize(int w,int h) { sizeChanged(w,h); }
        };
        projection.registerCallback(callback,MAIN);
        Rect bounds=screen();
        width=bounds.width(); height=bounds.height();
        if(width<=0 || height<=0)throw new IOException("Display size unavailable.");
        // A detached display grants no frames to this app while awaiting a manual tap.
        display=projection.createVirtualDisplay("Manual screen capture",width,height,density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,null,null,null);
        if(display==null)throw new IOException("Android could not share the display.");
        if(Build.VERSION.SDK_INT<34) {
            displayListener=new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) {}
                @Override public void onDisplayRemoved(int id) { if(id==Display.DEFAULT_DISPLAY)end("Display disconnected."); }
                @Override public void onDisplayChanged(int id) {
                    if(id==Display.DEFAULT_DISPLAY && !ending) {
                        Rect physical=screen(); sizeChanged(physical.width(),physical.height());
                    }
                }
            };
            getSystemService(DisplayManager.class).registerDisplayListener(displayListener,MAIN);
        }
        controls=new ManualControls(this,video,guided,this);
        if(!controls.show())throw new IOException("Floating controls unavailable.");
        directory=RunFiles.create(this);
        // Persist a recoverable run identity. Only status, committed images and final media are exported.
        if(!new File(directory,"manual-session").createNewFile())throw new IOException("Cannot identify the run.");
        if(video)RunFiles.registerVideo(directory);
        if(guided)WalkService.refreshManualGuidance();
        message=guided?"Guided video ready. You tap each outlined item.":video?"Manual video ready. Tap Record when ready.":"Manual screenshots ready.";
        notifyForeground();
    }

    private Rect screen() {
        Display physical=getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Context window=createDisplayContext(physical).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION,null);
        density=window.getResources().getConfiguration().densityDpi;
        return window.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
    }

    private void sizeChanged(int w,int h) {
        if(display==null || ending || w<=0 || h<=0 || (w==width && h==height))return;
        if(guided && controls!=null) {
            controls.guideDisplayChanged();
            WalkService.refreshManualGuidance();
        }
        if(movie!=null) {
            // Android fits the rotated scene into the original encoder canvas. Fixed masks
            // were expressed in the original screen coordinates and cannot follow that fit.
            if(privacy.hasRegions()) {
                end("The shared screen changed size. Saved the protected video recorded so far.");
            } else { width=w; height=h; }
            return;
        }
        boolean retryStill=pending && !writing && !video;
        if(retryStill)disconnectReader();
        width=w; height=h;
        try {
            display.resize(w,h,density);
            if(controls!=null)controls.setLandmarks(null);
            if(retryStill)retryAfterResize();
        } catch(RuntimeException lost) { end("The shared display became unavailable."); }
    }

    private void openControls() {
        if(controls!=null && !controls.show())end("The floating control permission was removed.");
    }

    @Override public void onStop() { end(""); }

    @Override public void onCapture() {
        if(ending || pending || display==null || controls==null || movie!=null)return;
        if(!video) {
            // A detached projection can defer its size callback until a surface is attached.
            try {
                Rect physical=screen();
                sizeChanged(physical.width(),physical.height());
            } catch(RuntimeException lost) { end("The shared display became unavailable."); }
            if(ending)return;
        }
        pending=true;
        resizeRetries=0;
        final long request=++captureTicket;
        controls.setCapturePending(true);
        MAIN.postDelayed(()->{
            if(!ending && pending && !writing && captureTicket==request) {
                cancelFrame("No new screen frame was available. Tap again.");
            }
        },8000);
        prepareFrame(request);
    }

    private void retryAfterResize() {
        if(++resizeRetries>MAX_RESIZE_RETRIES) {
            cancelFrame("The screen is still changing size. Hold your device steady and tap again.");
            return;
        }
        prepareFrame(captureTicket);
    }

    private void prepareFrame(long request) {
        int attempt=resizeRetries;
        controls.pauseForScreenshot(()->{
            if(ending || !pending || writing || request!=captureTicket || attempt!=resizeRetries || current!=this)return;
            try {
                if(video)startMovie();
                else waitForStill(request);
            } catch(RuntimeException | IOException e) {
                if(video)end("The video encoder could not start.");
                else cancelFrame("Could not capture this screen. Tap again.");
            }
        });
    }

    private void startMovie() throws IOException {
        movie=new ManualVideo(this,directory,width,height,privacy,
                ()->MAIN.post(()->{if(current==this && !ending)end("The video encoder was interrupted.");}));
        controls.setCapturePending(false);
        controls.setRecording(true);
        controls.hide();
        movie.begin();
        display.resize(movie.width,movie.height,density);
        display.setSurface(movie.input());
        pending=false;
        message=guided?"Recording silently with guided taps.":"Manual video recording.";
        notifyForeground();
    }

    private void waitForStill(long request) {
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
        reader.setOnImageAvailableListener(source->receive(source,request),MAIN);
        display.setSurface(reader.getSurface());
    }

    private void receive(ImageReader source,long request) {
        if(source!=reader || !pending || ending || request!=captureTicket)return;
        Image image;
        try {image=source.acquireLatestImage();}
        catch(RuntimeException e) {cancelFrame("The requested frame was unavailable. Tap again.");return;}
        if(image==null)return;
        // Accept the frame at shutter acquisition. Once detached it stays immutable even if
        // the user rotates again during PNG compression.
        try {
            Rect physical=screen();
            if(physical.width()!=width || physical.height()!=height) {
                image.close();
                sizeChanged(physical.width(),physical.height());
                return;
            }
            if(image.getWidth()!=width || image.getHeight()!=height ||
                    !image.getCropRect().equals(new Rect(0,0,width,height))) {
                image.close();
                disconnectReader();
                retryAfterResize();
                return;
            }
        } catch(RuntimeException lost) {
            image.close();cancelFrame("The requested frame was unavailable. Tap again.");return;
        }
        source.setOnImageAvailableListener(null,null);
        reader=null;
        try {display.setSurface(null);}
        catch(RuntimeException e) {image.close();source.close();end("The shared display became unavailable.");return;}
        writing=true;
        final int w=width,h=height,next=count+1;
        final File dir=directory;
        RunFiles.io.execute(()->{
            ManualLandmarks landmark=null;
            try (ImageReader owned=source; Image frame=image) {
                landmark=saveStill(frame,dir,w,h,next);
            } catch(RuntimeException | IOException | OutOfMemoryError ignored) { /* Report on main; no partial PNG is retained. */ }
            final ManualLandmarks result=landmark;
            MAIN.post(()->saved(request,next,result,w,h));
        });
    }

    private ManualLandmarks saveStill(Image frame,File dir,int w,int h,int next) throws IOException {
        Rect full=new Rect(0,0,w,h);
        if(frame.getWidth()!=w || frame.getHeight()!=h || !frame.getCropRect().equals(full))
            throw new IOException("Captured screen geometry changed.");
        Image.Plane plane=frame.getPlanes()[0];int stride=plane.getRowStride(),rowBytes=Math.multiplyExact(w,4);
        if(plane.getPixelStride()!=4 || stride<rowBytes)throw new IOException("Unsupported screen pixel format.");
        ByteBuffer pixels=plane.getBuffer().duplicate();
        int base=pixels.position(),limit=pixels.limit();
        if((long)base+(long)(h-1)*stride+rowBytes>limit)throw new IOException("Incomplete screen image.");
        ByteBuffer packed;
        if(stride==rowBytes)packed=pixels;
        else {
            packed=ByteBuffer.allocateDirect(Math.multiplyExact(rowBytes,h));
            for(int y=0;y<h;y++) {
                int start=base+y*stride;
                pixels.limit(limit).position(start).limit(start+rowBytes);
                packed.put(pixels);
            }
            packed.flip();
        }
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        File temp=null;
        try {
            bitmap.copyPixelsFromBuffer(packed);
            PrivacyMasks.paint(bitmap,PrivacyMasks.fixed(privacy.settings.regions,full));
            ManualLandmarks landmark=ManualLandmarks.from(bitmap);
            File photos=new File(dir,"screenshots");
            if(!photos.isDirectory() && !photos.mkdirs())throw new IOException("Cannot create screenshot folder.");
            File output=new File(photos,String.format(Locale.US,"%08d.png",next));
            temp=File.createTempFile("frame-",".part",photos);
            try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(temp))) {
                if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Could not encode screenshot.");
            }
            Files.move(temp.toPath(),output.toPath());
            return landmark;
        } finally {
            bitmap.recycle();
            if(temp!=null)temp.delete();
        }
    }

    private void saved(long request,int next,ManualLandmarks result,int w,int h) {
        if(current!=this || request!=captureTicket)return;
        writing=false;
        pending=false;
        if(result!=null) {count=next;message="Saved "+count+(count==1?" screenshot.":" screenshots.");}
        else {failedTaps++;message="Screenshot could not be saved. Tap again.";}
        if(ending) { finalizeRun(); return; }
        controls.setCapturePending(false);
        controls.resumeAfterScreenshot();
        if(result!=null) {
            if(w==width && h==height)controls.setLandmarks(result);
            controls.feedbackOnSave(count);
        } else controls.showProblem(message);
        notifyForeground();
    }

    private void cancelFrame(String reason) {
        ++captureTicket;
        failedTaps++;
        pending=false; message=reason;
        disconnectReader();
        if(controls!=null) {
            controls.setCapturePending(false);
            controls.resumeAfterScreenshot();
            controls.showProblem(reason);
        }
        notifyForeground();
    }

    private void disconnectReader() {
        if(reader==null)return;
        try { if(display!=null)display.setSurface(null); } catch(RuntimeException ignored) {}
        try {reader.setOnImageAvailableListener(null,null);reader.close();}catch(RuntimeException ignored) {}
        reader=null;
    }

    private void end(String reason) {
        if(ending)return;
        ending=true;problem=reason;
        if(guided)WalkService.refreshManualGuidance();
        if(pending && !writing && !video)failedTaps++;
        if(!writing)++captureTicket;
        if(controls!=null) {controls.close();controls=null;}
        disconnectReader();
        if(displayListener!=null) {
            getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);displayListener=null;
        }
        if(projection!=null && callback!=null)try {projection.unregisterCallback(callback);}catch(RuntimeException ignored) {}
        if(display!=null)try {display.release();}catch(RuntimeException ignored) {}
        display=null;
        if(projection!=null)try {projection.stop();}catch(RuntimeException ignored) {}
        projection=null;
        message="Finishing manual capture.";
        if(!writing)finalizeRun();
    }

    private void finalizeRun() {
        File dir=directory;
        ManualVideo encoder=movie;
        int savedCount=count,missed=failedTaps;
        String reason=problem;
        RunFiles.io.execute(()->{
            String detail=reason;
            if(video) {
                if(encoder!=null){
                    try {encoder.finish();}
                    catch(IOException | RuntimeException e){
                        detail=RunFiles.video(dir).isFile()?"Saved the playable part of the interrupted video.":"No playable video could be saved.";
                    }
                } else if(detail.isEmpty())detail="Record was never started.";
            } else if(detail.isEmpty() && savedCount==0)detail="No screenshots were saved.";
            if(detail.isEmpty() && missed>0)detail="Some requested screenshots could not be saved.";
            String outcome=(detail.isEmpty()?"Complete":"Incomplete")+" — "+
                    (detail.isEmpty()?(video?"Manual video saved.":"Manual screenshots saved."):detail);
            if(dir!=null) {
                try {
                    writeStatus(dir,outcome,savedCount,missed);
                    if(!video)RunFiles.build(dir);
                } catch(IOException | RuntimeException e) {
                    outcome="Incomplete — Manual export could not be finalized.";
                    try { writeStatus(dir,outcome,savedCount,missed); }
                    catch(IOException | RuntimeException storageUnavailable) { /* Library can recover surviving files. */ }
                }
            }
            String last=outcome;
            MAIN.post(()->{
                synchronized(ManualCaptureService.class) {
                    if(current==this || (current==null && token==generation)){
                        current=null;busy=false;message=last;
                    }
                }
                if (dir != null && (video || savedCount > 0)) CaptureCompletion.present(this, dir);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(lastStartId);
            });
        });
    }

    private void writeStatus(File dir,String outcome,int savedCount,int missed) throws IOException {
        File stage=new File(dir,"manual-status.part");
        Files.write(stage.toPath(),(outcome+"\nMode: Manual "+(video?"video":"screenshots")+
                "\nScreenshots: "+savedCount+"\nUncommitted screenshot requests: "+missed+"\n").getBytes(StandardCharsets.UTF_8));
        Files.move(stage.toPath(),new File(dir,"status.txt").toPath(),StandardCopyOption.REPLACE_EXISTING);
    }

    private PendingIntent action(String name) {
        Intent intent=new Intent(this,ManualCaptureService.class).setAction(name)
                .setData(Uri.parse("walkthrough:manual/"+token+"/"+name)).putExtra("token",token);
        return PendingIntent.getService(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    private void notifyForeground() {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL,"Manual capture",NotificationManager.IMPORTANCE_LOW));
        Notification.Builder n=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_capture_mark)
                .setContentTitle(video?(movie==null?"Manual video ready":"Recording manual video"):"Manual screenshots")
                .setContentText(video?(movie==null?"Open the desired screen, then tap Record.":"Recording silently. Tap Stop to finish.")
                        :count+" saved · Tap Capture for one screen")
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(action(SHOW))
                .addAction(new Notification.Action.Builder(null,"Show controls",action(SHOW)).build());
        if(guided && movie==null)n.addAction(new Notification.Action.Builder(null,"Record",action(RECORD)).build());
        if(video && movie!=null && !guided)n.addAction(new Notification.Action.Builder(null,"Place marker",action(MARKER)).build());
        n.addAction(new Notification.Action.Builder(null,"Stop",action(STOP)).build());
        if(Build.VERSION.SDK_INT>=31)n.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        startForeground(NOTIFICATION,n.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    @Override public void onDestroy() {
        if(current==this && !ending)end("Android stopped the manual capture service.");
        super.onDestroy();
    }
}
