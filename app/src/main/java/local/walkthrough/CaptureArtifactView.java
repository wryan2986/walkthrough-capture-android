package local.walkthrough;

import android.content.Context;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** In-app review of the same local media that Save to Files exports. */
final class CaptureArtifactView extends LinearLayout {
    private final File directory;
    private final FrameLayout stage;
    private final FrameLayout previewBox;
    private final LinearLayout controls, summary, saveRow;
    private final ScrollView controlsScroll;
    private final Ui.Choice expand;
    private final FrameLayout.LayoutParams expandOverlay;
    private LinearLayout transport;
    private final Ui.Glyph expandGlyph;
    private final boolean landscape;
    private boolean expanded;
    private List<String> screenshots = Collections.emptyList();
    private int index, position, duration;
    private TextView counter;
    private Button previous, next, play;
    private VideoView player;
    private SeekBar seek;
    private boolean seeking, ready, playingRequested, closed;
    private final Runnable track = new Runnable() {
        @Override public void run() {
            if (closed || !ready || !playingRequested) return;
            if (!seeking) updateProgress(player.getCurrentPosition());
            postDelayed(this, 300);
        }
    };

    CaptureArtifactView(Context context, File directory, boolean video, String title, String metadata,
                        int initialIndex, int initialPosition, Runnable save, Runnable onResize) {
        super(context);
        this.directory = directory;
        index = Math.max(0, initialIndex);
        position = Math.max(0, initialPosition);
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setOrientation(landscape ? HORIZONTAL : VERTICAL);
        setBackgroundColor(Ui.PAPER);
        Ui.pad(this, 16, 12, 16, 10);

        previewBox = new FrameLayout(context);
        stage = new FrameLayout(context);
        stage.setId(R.id.capture_detail_preview);
        stage.setBackground(Ui.shape(context, 0xffdae6df, Ui.LINE, 14));
        stage.setClipToOutline(true);
        previewBox.addView(stage);
        expand = new Ui.Choice(context,"android.widget.Button");
        expand.setGravity(Gravity.CENTER);
        expand.setBackground(Ui.ripple(context,Ui.SURFACE,Ui.LINE,12));
        expand.setContentDescription("Enlarge preview");
        expandGlyph = new Ui.Glyph(context,"expand",Ui.TEAL);
        expand.addView(expandGlyph, Ui.lp(context,20,20));
        expandOverlay = new FrameLayout.LayoutParams(Ui.dp(context,44),Ui.dp(context,44),Gravity.TOP|Gravity.END);
        expandOverlay.topMargin=Ui.dp(context,5); expandOverlay.setMarginEnd(expandOverlay.topMargin);
        previewBox.addView(expand,expandOverlay);
        expand.setOnClickListener(v -> { setExpanded(!expanded); onResize.run(); });
        addView(previewBox, landscape ? new LayoutParams(0,-1,1) : new LayoutParams(-1,0,1));
        Ui.gap(this,landscape ? 14 : 10);

        controlsScroll = new ScrollView(context);
        controlsScroll.setFillViewport(false);
        controls = Ui.column(context);
        controlsScroll.addView(controls);
        addView(controlsScroll, landscape ? new LayoutParams(0,-1,1) : Ui.lp(context,-1,-2));
        summary = Ui.column(context); controls.addView(summary);
        TextView kind = Ui.text(context, video ? "Trajectory video" : "Screenshots", 21, Ui.INK, true);
        kind.setAccessibilityHeading(true);
        summary.addView(kind);
        Ui.gap(summary,3);
        summary.addView(Ui.text(context, title + "  ·  " + metadata, 13, Ui.MUTED, false));
        Ui.gap(summary,9);
        if (video) setupVideo(); else setupImages();
        saveRow=Ui.column(context); controls.addView(saveRow);
        Ui.gap(saveRow,8);
        saveRow.addView(Ui.button(context, "Save to Files", true, save), Ui.lp(context,-1,-2));
    }

    boolean isExpanded() { return expanded; }
    void setExpanded(boolean enlarge) {
        if(expanded==enlarge)return;
        expanded=enlarge;
        boolean sideways=landscape && !expanded;
        setOrientation(sideways ? HORIZONTAL : VERTICAL);
        previewBox.setLayoutParams(sideways ? new LayoutParams(0,-1,1) : new LayoutParams(-1,0,1));
        controlsScroll.setLayoutParams(sideways ? new LayoutParams(0,-1,1) : new LayoutParams(-1,-2));
        summary.setVisibility(expanded ? GONE : VISIBLE);
        saveRow.setVisibility(expanded ? GONE : VISIBLE);
        expand.setContentDescription(expanded ? "Reduce preview" : "Enlarge preview");
        expandGlyph.setRotation(expanded ? 180 : 0);
        if(expanded) {
            previewBox.removeView(expand);
            LayoutParams buttonSize=Ui.lp(getContext(),44,44);
            buttonSize.setMarginStart(Ui.dp(getContext(),6));
            transport.addView(expand,buttonSize);
        } else {
            transport.removeView(expand);
            previewBox.addView(expand,expandOverlay);
        }
    }

    private void setupImages() {
        Context c = getContext();
        LinearLayout row = transport = Ui.row(c);
        controls.addView(row);
        previous = Ui.button(c, "Previous", false, () -> showImage(index-1));
        next = Ui.button(c, "Next", false, () -> showImage(index+1));
        row.addView(previous);
        counter = Ui.text(c, "Loading screenshots…", 14, Ui.MUTED, true);
        counter.setGravity(Gravity.CENTER);
        counter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(counter, Ui.weight());
        row.addView(next);
        Ui.enable(previous,false);
        Ui.enable(next,false);
        RunFiles.io.execute(() -> {
            List<String> entries;
            try { entries = CaptureThumbnail.screenshotEntries(directory); }
            catch (Exception unreadable) { entries = Collections.emptyList(); }
            final List<String> loaded = entries;
            post(() -> {
                if (closed) return;
                screenshots = loaded;
                if (!loaded.isEmpty()) showImage(Math.min(index, loaded.size()-1));
                else {
                    counter.setText("No preview available");
                    previous.setVisibility(GONE);
                    next.setVisibility(GONE);
                }
            });
        });
    }

    private void showImage(int requested) {
        if (requested < 0 || requested >= screenshots.size()) return;
        index = requested;
        String label = "Screenshot " + (index+1) + " of " + screenshots.size();
        stage.removeAllViews();
        CaptureThumbnail frame = new CaptureThumbnail(getContext(), directory, screenshots.get(index), 1200);
        frame.setContentDescription(label);
        stage.addView(frame);
        counter.setText(label);
        Ui.enable(previous, index>0);
        Ui.enable(next, index+1<screenshots.size());
    }

    private void setupVideo() {
        Context c = getContext();
        showVideoCover();
        LinearLayout row = transport = Ui.row(c);
        controls.addView(row);
        play = Ui.button(c, "Play video", false, this::togglePlayback);
        row.addView(play);
        Ui.gap(row,8);
        seek = new SeekBar(c);
        seek.setContentDescription("Video position");
        seek.setEnabled(false);
        row.addView(seek, Ui.weight());
        counter = Ui.text(c, clock(position), 12, Ui.MUTED, true);
        row.addView(counter);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { seeking=true; }
            public void onProgressChanged(SeekBar bar,int millis,boolean user) {
                if (!user || duration==0) return;
                counter.setText(clock(millis) + " / " + clock(bar.getMax()));
                if (!seeking) seekVideo(millis);
            }
            public void onStopTrackingTouch(SeekBar bar) {
                seeking=false;
                if (duration>0) seekVideo(bar.getProgress());
            }
        });
    }

    private void seekVideo(int millis) {
        position=millis;
        if (ready) player.seekTo(millis);
        else if (player==null) showVideoCover();
    }
    private void showVideoCover() {
        stage.removeAllViews();
        CaptureThumbnail cover=position>0 ? new CaptureThumbnail(getContext(),directory,(long)position,1200)
                : new CaptureThumbnail(getContext(),directory,true,960);
        cover.setContentDescription("Video preview");
        stage.addView(cover);
    }
    int videoDuration() { return duration; }
    void restoreVideoDuration(int millis) {
        if (seek==null || millis<=0)return;
        duration=millis;
        seek.setMax(millis); seek.setEnabled(true);
        updateProgress(position);
    }
    private void togglePlayback() {
        if (playingRequested) { pausePlayback(); return; }
        playingRequested=true;
        play.setText("Pause video");
        if (player != null) {
            if (ready) { player.start(); removeCallbacks(track); track.run(); }
            return;
        }
        preparePlayer();
    }

    private void preparePlayer() {
        VideoView video = new VideoView(getContext());
        player=video;
        video.setContentDescription("Trajectory video playback");
        stage.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        video.setOnPreparedListener(media -> {
            if (closed || player!=video) return;
            ready=true;
            // Captures are silent. Keep playback muted for legacy files as well.
            media.setVolume(0f,0f);
            restoreVideoDuration(video.getDuration());
            media.seekTo(position, android.media.MediaPlayer.SEEK_CLOSEST);
            if (playingRequested) { video.start(); track.run(); }
        });
        video.setOnCompletionListener(media -> {
            if (player != video) return;
            removeCallbacks(track);
            playingRequested=false;
            video.seekTo(0); updateProgress(0); play.setText("Play video");
        });
        video.setOnErrorListener((media,what,extra) -> {
            if (player!=video) return true;
            releasePlayer(); seek.setEnabled(false);
            play.setText("Play video");
            counter.setText("Preview unavailable");
            return true;
        });
        video.setVideoPath(RunFiles.video(directory).getAbsolutePath());
    }

    private static String clock(int millis) {
        int seconds=Math.max(0,millis)/1000;
        return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
    }
    private void updateProgress(int millis) {
        position=Math.max(0,millis);
        seek.setProgress(position);
        counter.setText(clock(position)+" / "+clock(seek.getMax()));
    }
    private void releasePlayer() {
        removeCallbacks(track);
        if (player!=null) {
            VideoView old=player;
            player=null;
            old.stopPlayback();
            stage.removeView(old);
        }
        ready=false; playingRequested=false;
    }
    private void pausePlayback() {
        removeCallbacks(track); playingRequested=false;
        if (ready && player!=null) { player.pause(); updateProgress(player.getCurrentPosition()); }
        if (play!=null) play.setText("Play video");
    }
    void onHostPause() {
        if (player==null) return;
        pausePlayback();
        releasePlayer(); showVideoCover();
    }
    int screenshotIndex() { return index; }
    int videoPosition() { return ready && player!=null ? player.getCurrentPosition() : position; }
    void close() { closed=true; releasePlayer(); }
}
