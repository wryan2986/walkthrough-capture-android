package local.walkthrough;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;

public final class MainActivity extends Activity {
    private static final boolean MANUAL_MODE_AVAILABLE = false;
    private static final int PROJECTION = 101, EXPORT = 102, AUTO_NOTIFICATIONS = 103,
            MANUAL_OVERLAY = 104, MANUAL_NOTIFICATIONS = 105, MANUAL_PROJECTION = 106;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout form, profile, rules, videoPane, chatPane, manualPane;
    private LinearLayout feedbackControl, manualFeedbackSlot, automaticFeedbackSlot;
    private TextView serviceState, statusView, configLabel, headerName, targetLabel;
    private Ui.Choice targetButton;
    private Ui.Glyph targetArrow;
    private LinearLayout rootView, header, bottomBox, libraryContent;
    private ScrollView homeScroll, libraryScroll;
    private FrameLayout body;
    private Ui.Choice captureTab, libraryTab, videoTile, stillTile, backButton, serviceButton, settingsButton;
    private Ui.Glyph rulesArrow;
    private View serviceDot;
    private ImageView headerMark;
    private Button statusMore, manualStart, manualRestore;
    private Ui.Choice manualMethod, autoMethod, guidedChoice, freehandChoice;
    private LinearLayout manualGuideOptions;
    private TextView manualHelp, methodHint;
    private boolean manual, manualVideo, manualGuided, pendingManualVideo, pendingGuided;
    private int manualRequest;
    private String manualNotice;
    private int systemTop, systemBottom;
    private String currentPage="capture", previousStatus, targetPackage;
    private Boolean previousConnection;
    private CaptureReview reviewPanel;
    private CaptureArtifactView artifactView;
    private Export displayedArtifact;
    private boolean justSaved;
    private boolean video, pendingScanNotice;

    private EditText pkg, avatar, groups, steps, expanders, hold, timeRule, titleRule;
    private RangeControls range;
    // Discovery and its list identifiers share the service process lifetime.
    private static String scannedProfile = "";
    private static boolean offerReview;
    private static final Set<String> selected = new HashSet<>();
    // Discovery and its frozen selection have the same process lifetime, including rotation.
    private static Set<String> selectedForRun;
    private boolean awaitingRangeGrant;
    private Button start, chatStart, cancel, review;
    private File exportDir;
    private boolean exportVideo;
    private SharedPreferences prefs, targetPrefs;
    private boolean offlineLab, labInstalled;
    private String pendingTarget;
    private boolean inspecting, lesson, launching, visible;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("profile", MODE_PRIVATE);
        offlineLab=prefs.getBoolean("useOfflineLab",false);
        targetPrefs=offlineLab ? getSharedPreferences("labProfile",MODE_PRIVATE) : prefs;
        labInstalled=getPackageManager().getLaunchIntentForPackage(MuseProfile.LAB)!=null;
        inspecting = saved != null && saved.getBoolean("inspecting");
        lesson = saved != null && saved.getBoolean("lesson");
        launching = saved != null && saved.getBoolean("launching");
        awaitingRangeGrant = saved != null && saved.getBoolean("rangeGrant");
        if (saved != null && saved.getString("export") != null)
            exportDir = new File(saved.getString("export"));
        exportVideo = saved != null && saved.getBoolean("exportVideo");
        if(MANUAL_MODE_AVAILABLE && saved!=null) {
            manualRequest=saved.getInt("manualRequest");
            pendingManualVideo=saved.getBoolean("pendingManualVideo");
            pendingGuided=saved.getBoolean("pendingGuided");
            pendingTarget=saved.getString("pendingTarget", builtInPackage());
            manualNotice=saved.getString("manualNotice");
        }
        takeScanNotice(getIntent());
        makeForm();
        if(saved!=null) {
            if(saved.containsKey("advanced"))showRules(saved.getBoolean("advanced"));
            String previous=saved.getString("page","capture");
            if("library".equals(previous)) page("library");
            if("artifact".equals(previous)) {
                String run=saved.getString("displayedRun","");
                File dir=new File(new File(getFilesDir(),"runs"),run);
                if(!run.isEmpty() && dir.isDirectory()) {
                    showArtifact(new Export(dir,saved.getBoolean("displayedVideo")),saved.getBoolean("justSaved"),
                            saved.getInt("screenshotIndex"),saved.getInt("videoPosition"));
                    artifactView.restoreVideoDuration(saved.getInt("videoDuration"));
                    artifactView.setExpanded(saved.getBoolean("expandedPreview")); adjustBars();
                } else page("library");
            }
            if(!manual && "review".equals(previous) && !WalkService.isActive() && hasReview()) {
                renderReview();
                if(reviewPanel!=null)reviewPanel.restore(saved.getParcelable("reviewPosition"));
            }
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); takeScanNotice(intent);
        if(visible)refresh();
    }
    private void takeScanNotice(Intent intent) {
        if(intent!=null && intent.getBooleanExtra("scanFailed",false)) {
            pendingScanNotice=true; intent.removeExtra("scanFailed");
        }
    }

    private void makeForm() {
        // Prior builds stored independent output/trajectory switches. Map the old
        // intent once, then persist only the two complete capture workflows.
        video=prefs.contains("captureMode") ? "trajectory".equals(prefs.getString("captureMode","trajectory"))
                : prefs.getBoolean("trajectory",true) && prefs.getBoolean("record",true);
        manual=MANUAL_MODE_AVAILABLE && (ManualCaptureService.isActive() || manualRequest!=0 ||
                (prefs.getBoolean("manualMethod",true) && !pendingScanNotice && !WalkService.isActive()));
        manualVideo=prefs.getBoolean("manualVideo",false);
        manualGuided=prefs.getBoolean("manualGuidedTaps",true);
        if(manual)endInspection();
        getWindow().setStatusBarColor(Ui.HEADER);
        getWindow().setNavigationBarColor(Ui.SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        rootView=Ui.column(this); rootView.setBackgroundColor(Ui.PAPER); setContentView(rootView);
        header=Ui.row(this); header.setBackgroundColor(Ui.HEADER); rootView.addView(header);
        headerMark=new ImageView(this);
        int logo=getResources().getIdentifier("ic_capture_mark","drawable",getPackageName());
        if(logo!=0)headerMark.setImageResource(logo);
        else headerMark.setImageResource(android.R.drawable.ic_menu_camera);
        headerMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(headerMark,Ui.lp(this,30,30));
        backButton=new Ui.Choice(this,"android.widget.Button"); backButton.setGravity(Gravity.CENTER);
        backButton.setBackground(Ui.ripple(this,Ui.HEADER,0,12)); backButton.setContentDescription("Back to capture setup");
        backButton.addView(new Ui.Glyph(this,"back",Color.WHITE),Ui.lp(this,25,25));
        backButton.setOnClickListener(v -> onBackPressed()); header.addView(backButton,Ui.lp(this,44,48));
        Ui.gap(header,8);
        headerName=Ui.text(this,"Walkthrough Capture",21,Color.WHITE,true); header.addView(headerName,Ui.weight());
        serviceButton=new Ui.Choice(this,"android.widget.Button"); serviceButton.setGravity(Gravity.CENTER);
        serviceButton.setBackground(Ui.ripple(this,0xff205247,0xff467569,22));
        Ui.pad(serviceButton,10,0,10,0); serviceButton.setMinimumHeight(dp(48));
        serviceDot=new View(this); serviceButton.addView(serviceDot,Ui.lp(this,7,7)); Ui.gap(serviceButton,6);
        serviceState=Ui.text(this,"",12,Color.WHITE,true); serviceButton.addView(serviceState);
        serviceButton.setOnClickListener(v->accessibilitySettings()); header.addView(serviceButton);
        body=new FrameLayout(this); rootView.addView(body,new LinearLayout.LayoutParams(-1,0,1));

        homeScroll=new ScrollView(this); homeScroll.setFillViewport(true); homeScroll.setVerticalScrollBarEnabled(false);
        form=Ui.column(this); Ui.pad(form,20,20,20,20); form.setFocusableInTouchMode(true); homeScroll.addView(form);
        body.addView(homeScroll);
        LinearLayout intro=Ui.row(this); form.addView(intro);
        TextView newCapture=Ui.text(this,"New capture",28,Ui.INK,true); newCapture.setAccessibilityHeading(true);
        intro.addView(newCapture,Ui.weight());
        targetButton=new Ui.Choice(this,"android.widget.Button"); targetButton.setId(R.id.capture_target);
        targetButton.setGravity(Gravity.CENTER_VERTICAL); targetButton.setMinimumHeight(dp(44));
        targetLabel=Ui.text(this,"",14,Ui.MUTED,true); targetLabel.setMaxWidth(dp(112));
        targetLabel.setGravity(Gravity.END); targetButton.addView(targetLabel);
        targetArrow=new Ui.Glyph(this,"down",Ui.TEAL); targetButton.addView(targetArrow,Ui.lp(this,16,16));
        targetButton.setOnClickListener(v->chooseTarget()); intro.addView(targetButton);
        Ui.gap(form,14);
        LinearLayout methods=Ui.row(this); Ui.pad(methods,3,3,3,3);
        methods.setBackground(Ui.shape(this,Ui.SURFACE,Ui.LINE,15)); form.addView(methods);
        methods.setVisibility(MANUAL_MODE_AVAILABLE?View.VISIBLE:View.GONE);
        manualMethod=methodTile(methods,"Manual",true); Ui.gap(methods,3); autoMethod=methodTile(methods,"Automatic",false);
        Ui.gap(form,6); methodHint=note(form,""); Ui.gap(form,12);
        LinearLayout modes=Ui.column(this); form.addView(modes);
        stillTile=modeTile(modes,false); Ui.gap(modes,7); videoTile=modeTile(modes,true);
        Ui.gap(form,18);
        PrivacyOptions privacy=new PrivacyOptions(this);
        manualPane=Ui.column(this); form.addView(manualPane); buildManual(privacy);
        chatPane=Ui.column(this); form.addView(chatPane);
        TextView chatHeading=Ui.text(this,"Open your conversation in "+builtInName(),18,Ui.INK,true);
        chatHeading.setAccessibilityHeading(true); chatPane.addView(chatHeading);
        Ui.gap(chatPane,5);
        note(chatPane,"Leave the chat open, then return here. Each screen is saved as a separate PNG, with overlap between images.");
        Ui.gap(chatPane,12);
        chatStart=Ui.button(this,"Capture conversation",true,this::startConversation);
        chatStart.setId(R.id.capture_conversation); chatPane.addView(chatStart,Ui.lp(this,-1,-2));
        Ui.gap(chatPane,9);
        automaticFeedbackSlot=Ui.column(this);chatPane.addView(automaticFeedbackSlot);
        feedbackControl=Ui.column(this);new FeedbackOptions(this,feedbackControl);
        Ui.gap(chatPane,7); privacy.add(chatPane,true);
        videoPane=Ui.column(this); form.addView(videoPane);
        range=new RangeControls(this,videoPane,targetPrefs,this::refresh);
        Ui.gap(videoPane,12);
        start=Ui.button(this,"Find trajectories",true,this::findTrajectories); start.setId(R.id.find_trajectories);
        videoPane.addView(start,Ui.lp(this,-1,-2));
        review=button(videoPane,"Review trajectories",this::showReview);
        Ui.gap(videoPane,13);
        new HighlightOptions(this,videoPane); Ui.gap(videoPane,7); privacy.add(videoPane,false); Ui.gap(videoPane,7);
        settingsButton=new Ui.Choice(this,"android.widget.Button"); settingsButton.setGravity(Gravity.CENTER_VERTICAL);
        settingsButton.setMinimumHeight(dp(50)); Ui.pad(settingsButton,12,7,12,7);
        settingsButton.setBackground(Ui.ripple(this,Ui.SURFACE,Ui.LINE,12));
        settingsButton.addView(new Ui.Glyph(this,"settings",Ui.TEAL),Ui.lp(this,21,21)); Ui.gap(settingsButton,10);
        settingsButton.addView(Ui.text(this,"Advanced settings",15,Ui.INK,true),Ui.weight());
        rulesArrow=new Ui.Glyph(this,"down",Ui.TEAL); settingsButton.addView(rulesArrow,Ui.lp(this,18,18));
        settingsButton.setOnClickListener(v -> showRules(rules.getVisibility()!=View.VISIBLE));
        videoPane.addView(settingsButton,Ui.lp(this,-1,-2));
        rules=Ui.column(this); Ui.pad(rules,14,12,14,14); rules.setBackground(Ui.shape(this,Ui.SURFACE,Ui.LINE,12)); videoPane.addView(rules);
        range.attachSource(rules);
        hold=field(rules,"Time to show each view (seconds)",targetPrefs.getString("hold","2"));
        hold.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        pkg=field(rules,"Android app package",offlineLab ? MuseProfile.LAB : targetPrefs.getString("pkg",MuseProfile.PKG));
        if(offlineLab)pkg.setEnabled(false);
        boolean useMuseDefaults=MuseProfile.supports(value(pkg)) && !targetPrefs.getBoolean("museDefaultsReviewed",false)
                && targetPrefs.getString("avatar","").isEmpty() && targetPrefs.getString("groups","").isEmpty() && targetPrefs.getString("steps","").isEmpty();
        profile=Ui.column(this); rules.addView(profile);
        configLabel=note(profile,MuseProfile.supports(value(pkg)) ? builtInName()+" uses the avatar’s Activity tab."
                : "Teach the avatar, action card and step summary for the selected app.");
        button(profile,"Use built-in Muse controls",()-> {
            if(!MuseProfile.supports(value(pkg))) { toast("Choose Muse as the target app first."); return; }
            new AlertDialog.Builder(this).setTitle("Use built-in Muse controls?")
                    .setMessage("Replace the avatar, action card, step, timestamp and title rules with the Muse Activity settings?")
                    .setNegativeButton("Cancel",null).setPositiveButton("Use Muse controls",(d,w)->{
                        avatar.setText(MuseProfile.AVATAR); groups.setText(MuseProfile.CARD); steps.setText(MuseProfile.STEP);
                        timeRule.setText(""); titleRule.setText(""); range.useCardTimes(); remember(); refresh();
                    }).show();
        });
        button(profile,"Teach three taps",()->inspect(true));
        avatar=field(profile,"Avatar control",useMuseDefaults?MuseProfile.AVATAR:targetPrefs.getString("avatar",""));
        groups=field(profile,"Action card controls",useMuseDefaults?MuseProfile.CARD:targetPrefs.getString("groups",""));
        steps=field(profile,"Step summary controls",useMuseDefaults?MuseProfile.STEP:targetPrefs.getString("steps",""));
        timeRule=field(profile,"Timestamp control (optional identifier)",targetPrefs.getString("timeRule",""));
        titleRule=field(profile,"Title control (optional identifier)",targetPrefs.getString("titleRule",""));
        expanders=field(rules,"Extra expansion controls (optional, comma-separated)",targetPrefs.getString("expanders",""));
        button(rules,"Inspect controls",()->inspect(false));
        showRules(value(groups).isEmpty() || value(steps).isEmpty());
        Ui.gap(form,20); heading(form,"Run status",15); Ui.gap(form,5);
        LinearLayout status=Ui.row(this); form.addView(status,Ui.lp(this,-1,-2));
        status.addView(new Ui.Glyph(this,"clock",Ui.MUTED),Ui.lp(this,18,18)); Ui.gap(status,8);
        statusView=Ui.text(this,"",14,Ui.MUTED,false); statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.addView(statusView,Ui.weight());
        statusMore=button(form,"View run details",this::statusDetails); cancel=button(form,"Stop walkthrough",()->{
            if(ManualCaptureService.isActive()) ManualCaptureService.finish(this); else WalkService.cancel();
        });

        libraryScroll=new ScrollView(this); libraryScroll.setFillViewport(true); libraryScroll.setVerticalScrollBarEnabled(false);
        libraryContent=Ui.column(this); Ui.pad(libraryContent,20,20,20,18); libraryScroll.addView(libraryContent); body.addView(libraryScroll);
        bottomBox=Ui.column(this); bottomBox.setBackgroundColor(Ui.SURFACE); rootView.addView(bottomBox);
        View line=new View(this); line.setBackgroundColor(Ui.LINE); bottomBox.addView(line,Ui.lp(this,-1,1));
        LinearLayout nav=Ui.row(this); Ui.pad(nav,12,4,12,4); bottomBox.addView(nav);
        captureTab=tab(nav,"Capture","capture"); Ui.gap(nav,8); libraryTab=tab(nav,"Library","library");
        rootView.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets system=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime()); systemTop=system.top; systemBottom=system.bottom;
            view.setPadding(system.left,0,system.right,Math.max(0,keyboard.bottom-system.bottom));
            adjustBars(); return insets;
        });
        updateMode(); page("capture"); refresh();
    }

    private void adjustBars() {
        boolean reviewing="review".equals(currentPage) || "artifact".equals(currentPage);
        boolean expanded=artifactView!=null && artifactView.isExpanded();
        header.setVisibility(expanded?View.GONE:View.VISIBLE);
        bottomBox.setVisibility(expanded || "review".equals(currentPage)?View.GONE:View.VISIBLE);
        if(artifactView!=null) artifactView.setPaddingRelative(dp(expanded?4:16),expanded?systemTop+dp(4):dp(12),
                dp(expanded?4:16),expanded?systemBottom+dp(4):dp(10));
        header.setPaddingRelative(dp(reviewing?8:18),systemTop+dp(5),dp(16),dp(5));
        header.setMinimumHeight(systemTop+dp(58));
        bottomBox.setPadding(0,0,0,systemBottom);
        if(reviewPanel!=null)reviewPanel.footer.setPadding(0,0,0,systemBottom);
    }

    private Ui.Choice tab(LinearLayout parent,String label,String icon) {
        Ui.Choice tab=new Ui.Choice(this,"android.widget.Button"); tab.setGravity(Gravity.CENTER);
        tab.setMinimumHeight(dp(48)); tab.addView(new Ui.Glyph(this,icon,Ui.MUTED),Ui.lp(this,21,21)); Ui.gap(tab,8);
        tab.addView(Ui.text(this,label,14,Ui.MUTED,true)); tab.setContentDescription(label);
        tab.setOnClickListener(v -> page(icon)); parent.addView(tab,Ui.weight()); return tab;
    }
    private void markTab(Ui.Choice tab,boolean active) {
        tab.mark(active); tab.setBackground(Ui.ripple(this,active?Ui.PALE:Ui.SURFACE,0,14));
        ((Ui.Glyph)tab.getChildAt(0)).tint(active?Ui.TEAL:Ui.MUTED);
        ((TextView)tab.getChildAt(2)).setTextColor(active?Ui.TEAL:Ui.MUTED);
    }

    private void buildManual(PrivacyOptions privacy) {
        manualGuideOptions=Ui.column(this); manualPane.addView(manualGuideOptions);
        heading(manualGuideOptions,"Tap guidance",18);
        Ui.gap(manualGuideOptions,7);
        LinearLayout choices=Ui.row(this); Ui.pad(choices,3,3,3,3);
        choices.setBackground(Ui.shape(this,Ui.SURFACE,Ui.LINE,15)); manualGuideOptions.addView(choices);
        guidedChoice=videoGuidanceTile(choices,true); Ui.gap(choices,3); freehandChoice=videoGuidanceTile(choices,false);
        Ui.gap(manualGuideOptions,10);
        manualHelp=Ui.text(this,"",15,Ui.INK,false);
        manualPane.addView(manualHelp);
        Ui.gap(manualPane,12);
        manualStart=Ui.button(this,"",true,this::startManual);
        manualStart.setId(R.id.manual_start);manualPane.addView(manualStart,Ui.lp(this,-1,-2));
        manualRestore=button(manualPane,"Show controls",()->{
            ManualCaptureService.showControls(this); moveTaskToBack(true);
        });
        manualRestore.setId(R.id.manual_show_controls);
        manualFeedbackSlot=Ui.column(this);Ui.pad(manualFeedbackSlot,0,8,0,0);manualPane.addView(manualFeedbackSlot);
        Ui.gap(manualPane,8);
        LinearLayout maskHolder=Ui.column(this);manualPane.addView(maskHolder);
        privacy.add(maskHolder,false);
        maskHolder.getChildAt(0).setId(R.id.manual_privacy_masks);
        Ui.gap(manualPane,6);
        note(manualPane,"When the toolbar is hidden, use the capture notification or return here through Recents to show controls or finish.");
    }

    private Ui.Choice videoGuidanceTile(LinearLayout parent,boolean guided) {
        String title=guided?"Guide my taps":"Freehand";
        Ui.Choice tile=new Ui.Choice(this,"android.widget.RadioButton");
        tile.setId(guided?R.id.manual_guided:R.id.manual_freehand);
        tile.setGravity(Gravity.CENTER); tile.setMinimumHeight(dp(48)); Ui.pad(tile,6,3,6,3);
        tile.addView(Ui.text(this,title,15,Ui.INK,true)); tile.setContentDescription(title);
        tile.setOnClickListener(v->{
            if(captureBusy() || manualGuided==guided)return;
            manualGuided=guided;manualNotice=null;updateMode();remember();refresh();
        });
        parent.addView(tile,Ui.weight());return tile;
    }

    private Ui.Choice methodTile(LinearLayout row,String label,boolean chooseManual) {
        Ui.Choice tile=new Ui.Choice(this,"android.widget.RadioButton");
        tile.setGravity(Gravity.CENTER); tile.setMinimumHeight(dp(48)); Ui.pad(tile,8,3,8,3);
        tile.setId(chooseManual?R.id.method_manual:R.id.method_automatic);
        tile.addView(Ui.text(this,label,16,Ui.INK,true));
        tile.setContentDescription(label);
        tile.setOnClickListener(v->{
            if((chooseManual && !MANUAL_MODE_AVAILABLE) || manual==chooseManual || captureBusy())return;
            manual=chooseManual;
            if(manual) { endInspection(); offerReview=false; }
            manualNotice=null;previousStatus=null;
            updateMode(); remember(); refresh();
        });
        row.addView(tile,Ui.weight());return tile;
    }

    private void endInspection() {
        Inspection.stop(); inspecting=false; lesson=false;
    }
    private boolean captureBusy() {
        return launching || manualRequest!=0 || ManualCaptureService.isActive() || WalkService.isActive();
    }

    private Ui.Choice modeTile(LinearLayout parent,boolean isVideo) {
        String title=isVideo?"Trajectory video":"Conversation screenshots";
        Ui.Choice tile=new Ui.Choice(this,"android.widget.RadioButton"); tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setId(isVideo?R.id.capture_mode_trajectory:R.id.capture_mode_conversation);
        tile.setMinimumHeight(dp(56)); Ui.pad(tile,13,8,12,8);
        tile.addView(new Ui.Glyph(this,isVideo?"video":"photos",Ui.TEAL),Ui.lp(this,24,24)); Ui.gap(tile,11);
        tile.addView(Ui.text(this,title,17,Ui.INK,true),Ui.weight()); Ui.gap(tile,6);
        tile.addView(Ui.text(this,isVideo?"MP4":"ZIP",12,Ui.MUTED,true)); Ui.gap(tile,6);
        tile.addView(new Ui.Glyph(this,"check",Ui.TEAL),Ui.lp(this,17,17));
        tile.setContentDescription(title+(isVideo?", one continuous MP4":", overlapping screen-sized PNGs in one ZIP"));
        tile.setOnClickListener(v->{
            if((manual?manualVideo:video)==isVideo || captureBusy())return;
            if(manual) { manualVideo=isVideo;manualNotice=null; } else video=isVideo;
            updateMode(); remember(); refresh();
        });
        parent.addView(tile,Ui.lp(this,-1,-2)); return tile;
    }
    private void markChoice(Ui.Choice choice,boolean active) {
        choice.mark(active);choice.setBackground(Ui.ripple(this,active?Ui.TEAL:Ui.SURFACE,0,11));
        ((TextView)choice.getChildAt(0)).setTextColor(active?Ui.SURFACE:Ui.INK);
    }
    private void updateMode() {
        markChoice(manualMethod,manual);markChoice(autoMethod,!manual);
        markChoice(guidedChoice,manualGuided);markChoice(freehandChoice,!manualGuided);
        methodHint.setText(manual?"You scroll, open details and choose what to capture.":"Automatic capture opens and scrolls through the views you choose.");
        for(Ui.Choice choice:new Ui.Choice[]{videoTile,stillTile}) {
            boolean movie=choice==videoTile;
            boolean selected=movie==(manual?manualVideo:video); choice.mark(selected);
            String name=movie?"Trajectory video":manual?"Screenshots":"Conversation screenshots";
            ((TextView)choice.getChildAt(2)).setText(name);
            choice.setContentDescription(name+(movie?", one continuous silent MP4":manual?", one full screen per tap in a ZIP":", overlapping screen-sized PNGs in one ZIP"));
            choice.setId(manual?(movie?R.id.manual_video:R.id.manual_screenshots):(movie?R.id.capture_mode_trajectory:R.id.capture_mode_conversation));
            choice.setBackground(Ui.ripple(this,selected?Ui.PALE:Ui.SURFACE,selected?Ui.TEAL:Ui.LINE,14));
            choice.getChildAt(choice.getChildCount()-1).setVisibility(selected?View.VISIBLE:View.INVISIBLE);
        }
        LinearLayout feedbackSlot=manual?manualFeedbackSlot:automaticFeedbackSlot;
        if(feedbackControl.getParent()!=feedbackSlot) {
            if(feedbackControl.getParent()!=null)((ViewGroup)feedbackControl.getParent()).removeView(feedbackControl);
            feedbackSlot.addView(feedbackControl,Ui.lp(this,-1,-2));
        }
        manualFeedbackSlot.setVisibility(manual && !manualVideo?View.VISIBLE:View.GONE);
        manualGuideOptions.setVisibility(manual && manualVideo?View.VISIBLE:View.GONE);
        manualPane.setVisibility(manual?View.VISIBLE:View.GONE);
        videoPane.setVisibility(!manual && video?View.VISIBLE:View.GONE);
        chatPane.setVisibility(!manual && !video?View.VISIBLE:View.GONE);
        manualStart.setText(manualVideo?(manualGuided?"Set up guided recording":"Set up recording"):"Set up screenshots");
        manualHelp.setText(manualVideo
                ? (manualGuided?"Open "+builtInName()+", then tap Record when ready. Accessibility reads the controls on this device and outlines the next one. You choose the activity, tap each step and scroll."
                :"Tap Record to start a silent video in any app. Use Place marker if you want to draw your own outline.")
                : "In your app, tap Save screen once for each PNG. After the first save, use Guides and Show reference. Scroll the bottom landmark to the upper guide or the right-hand landmark to the left guide.");
        serviceButton.setVisibility(showServiceButton()?View.VISIBLE:View.GONE);
        targetPackage=null;
    }

    private void page(String destination) {
        currentPage=destination;
        boolean reviewing="review".equals(destination), library="library".equals(destination), artifact="artifact".equals(destination);
        boolean nested=reviewing || artifact;
        if(!reviewing && reviewPanel!=null) { body.removeView(reviewPanel.view); reviewPanel=null; }
        if(!artifact) clearArtifact();
        homeScroll.setVisibility(nested||library?View.GONE:View.VISIBLE);
        libraryScroll.setVisibility(library?View.VISIBLE:View.GONE);
        bottomBox.setVisibility(reviewing?View.GONE:View.VISIBLE);
        serviceButton.setVisibility(showServiceButton()?View.VISIBLE:View.GONE); headerMark.setVisibility(nested?View.GONE:View.VISIBLE);
        backButton.setVisibility(nested?View.VISIBLE:View.GONE);
        backButton.setContentDescription(artifact?"Back to Library":"Back to capture setup");
        headerName.setText(artifact ? justSaved ? "Capture saved" : "Capture preview" : "Walkthrough Capture");
        markTab(captureTab,!library && !artifact); markTab(libraryTab,library || artifact);
        if(library)populateLibrary(); adjustBars();
    }
    @Override public void onBackPressed() {
        if(artifactView!=null && artifactView.isExpanded()) { artifactView.setExpanded(false); adjustBars(); }
        else if("artifact".equals(currentPage)) page("library");
        else if(!"capture".equals(currentPage)) page("capture"); else super.onBackPressed();
    }

    private void clearArtifact() {
        if(artifactView==null)return;
        artifactView.close(); body.removeView(artifactView);
        artifactView=null; displayedArtifact=null;
    }
    private void openArtifact(Export capture) { showArtifact(capture,false,0,0); }
    private void showArtifact(Export capture,boolean saved,int index,int position) {
        clearArtifact();
        displayedArtifact=capture; justSaved=saved;
        artifactView=new CaptureArtifactView(this,capture.dir,capture.video,capture.title,capture.label,
                index,position,()->export(capture),this::adjustBars);
        body.addView(artifactView);
        page("artifact");
        if(saved) headerName.announceForAccessibility("Capture saved");
    }

    private boolean showServiceButton() {
        return "capture".equals(currentPage) && (!manual || manualVideo && manualGuided);
    }

    private void accessibilitySettings() {
        new AlertDialog.Builder(this).setTitle("Accessibility settings")
                .setMessage(manual && manualVideo && manualGuided
                       ?"To show the next tap, enable Walkthrough Capture in Android Accessibility, return here, then start guided recording. During the session the guide reads visible controls in "+builtInName()+". You perform every tap and scroll."
                       :"Walkthrough Capture reads the selected app’s controls to open and scroll through the actions you choose. It saves a silent video or full-display screenshots on this device. Inspection reads the identifiers you visit.")
                .setNegativeButton("Cancel",null).setPositiveButton("Open settings",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();
    }

    private void inspect(boolean teach) {
        if (!readyToOpen()) return;
        String message = teach
            ? "Open the intended conversation in the target app first. Return here using Recents. After you press Start, tap the avatar, one action card, then one step summary. Pause briefly between taps. Return here via Recents to review the learned controls."
            : "After you press Start, visit the screens whose controls you want to inspect. Return here via Recents to view and copy the exposed identifiers. Inspection lasts up to three minutes.";
        new AlertDialog.Builder(this).setTitle(teach ? "Teach a trajectory" : "Inspect this app")
            .setMessage(message).setNegativeButton("Cancel", null)
            .setPositiveButton("Start", (d,w) -> {
                String p = value(pkg);
                if (teach) Inspection.teach(p); else Inspection.start(p);
                inspecting = true; lesson = teach;
                openTarget();
            }).show();
    }

    private boolean readyToOpen() {
        if (manual || captureBusy()) { toast("A capture is already pending."); return false; }
        if (!WalkService.isConnected()) { toast("Enable Walkthrough Capture in Accessibility settings first."); return false; }
        String target = target();
        if (target.equals(getPackageName()) || target.isEmpty()
                || getPackageManager().getLaunchIntentForPackage(target) == null) {
            toast("Install or enter the package of a launchable target app."); return false;
        }
        return true;
    }

    private void showRules(boolean show) {
        rules.setVisibility(show ? View.VISIBLE : View.GONE);
        rulesArrow.setRotation(show?180f:0f);
        settingsButton.setContentDescription("Advanced settings");
        settingsButton.setStateDescription(show?"Expanded":"Collapsed");
    }

    private String builtInPackage() { return offlineLab ? MuseProfile.LAB : MuseProfile.PKG; }
    private String builtInName() { return offlineLab ? "Muse Test Lab" : "Muse"; }

    private String target() {
        return offlineLab || !video ? builtInPackage() : value(pkg);
    }

    private void chooseTarget() {
        if(captureBusy() || (!labInstalled && !offlineLab))return;
        String standard=!offlineLab ? value(pkg) : prefs.getString("pkg",MuseProfile.PKG);
        String standardLabel="Muse";
        if(video && !manual && !MuseProfile.PKG.equals(standard)) {
            try { standardLabel=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(standard,0)).toString(); }
            catch(PackageManager.NameNotFoundException ignored) { standardLabel=standard; }
        }
        new AlertDialog.Builder(this).setTitle("Capture target")
                .setSingleChoiceItems(new String[]{standardLabel,"Muse Test Lab"},offlineLab?1:0,(dialog,index)->{
                    dialog.dismiss();
                    boolean lab=index==1;
                    if(lab==offlineLab || captureBusy())return;
                    if(lab && getPackageManager().getLaunchIntentForPackage(MuseProfile.LAB)==null) {
                        toast("Install Muse Test Lab first."); return;
                    }
                    endInspection();
                    remember();
                    offerReview=false; scannedProfile=""; selected.clear(); selectedForRun=null;
                    prefs.edit().putBoolean("useOfflineLab",lab).apply();
                    recreate();
                }).setNegativeButton("Cancel",null).show();
    }

    private String currentProfile() {
        return new JSONArray().put(value(pkg)).put(value(avatar)).put(value(groups)).put(value(steps))
                .put(value(timeRule)).put(value(titleRule)).put(range.level()).put(range.start())
                .put(range.endExclusive()).put(java.time.ZoneId.systemDefault().getId()).toString();
    }
    private boolean hasReview() {
        return !manual && video && WalkService.hasDiscovery() && scannedProfile.equals(currentProfile());
    }

    private boolean privacyReady() {
        try { PrivacyMasks.Settings.read(this); return true; }
        catch(RuntimeException unreadable) { toast("Open Privacy masks, review the settings and tap Apply before capture."); return false; }
    }
    private void startConversation() {
        if(video || !readyToOpen() || !privacyReady())return;
        remember();
        try {
            File dir=RunFiles.create(this);
            if(!WalkService.armConversation(target(),dir)) {
                markFailed(dir,WalkService.getStatus()); toast(WalkService.getStatus()); return;
            }
            openTarget();
        } catch(IOException | RuntimeException e) {
            WalkService.cancel(); toast("Could not start the conversation capture. "+e.getMessage());
        }
    }

    private void findTrajectories() {
        if(!video || !readyToOpen())return;
        if(value(groups).isEmpty() || value(steps).isEmpty()) {
            showRules(true); toast("Teach the trajectory controls or enter the action card and step summary rules."); return;
        }
        if(dwell()<0) { toast("Use a hold time from 1 to 20 seconds."); return; }
        if(!range.valid()) { toast("Choose an end minute on or after the start."); return; }
        remember();
        if(WalkService.discover(value(pkg),value(avatar),value(groups),value(steps),
                value(timeRule),value(titleRule),range.level(),range.start(),range.endExclusive())) {
            scannedProfile=currentProfile(); selected.clear(); selectedForRun=null;
            offerReview=true; openTarget();
        } else toast(WalkService.getStatus());
    }

    private Set<String> selectedKeys() {
        Set<String> result=new HashSet<>();
        for(RangeWalk.Entry item:WalkService.entries())if(selected.contains(item.key))result.add(item.key);
        return result;
    }

    private void showReview() {
        if(launching || WalkService.isActive())return;
        renderReview();
    }

    private void renderReview() {
        if(!hasReview() || reviewPanel!=null)return;
        List<RangeWalk.Entry> entries=WalkService.entries();
        if(entries.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No trajectories in this range")
                    .setMessage("Change the start or end minute and scan again.").setPositiveButton("Done",null).show();
            return;
        }
        reviewPanel=new CaptureReview(this,range,entries,selected,()-> {
            if(!readyToOpen() || !hasReview())return;
            if(dwell()<0){ toast("Use a hold time from 1 to 20 seconds."); return; }
            selectedForRun=selectedKeys();
            if(!selectedForRun.isEmpty()) { offerReview=false; startVideo(); }
        });
        body.addView(reviewPanel.view); page("review");
    }

    private void startVideo() {
        if(!privacyReady())return;
        awaitingRangeGrant = true;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("askedNotify", false)) {
            prefs.edit().putBoolean("askedNotify", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, AUTO_NOTIFICATIONS);
            return;
        }
        askForProjection();
    }

    private void startManual() {
        if(!manual || captureBusy() || !privacyReady())return;
        if(manualVideo && manualGuided && !WalkService.isConnected()) { accessibilitySettings(); return; }
        if(offlineLab && getPackageManager().getLaunchIntentForPackage(MuseProfile.LAB)==null) {
            toast("Install Muse Test Lab to start this capture."); return;
        }
        endInspection(); remember();
        pendingTarget=builtInPackage();
        pendingManualVideo=manualVideo; pendingGuided=manualVideo && manualGuided; setManualNotice(null);
        if(Settings.canDrawOverlays(this)) { manualNotifications(); return; }
        new AlertDialog.Builder(this).setTitle("Allow floating capture controls")
                .setMessage("In Android settings, choose Walkthrough Capture and turn on Allow display over other apps. Return here with Back to continue.")
                .setNegativeButton("Cancel",(d,w)->setManualNotice("Setup canceled. You can start again at any time."))
                .setPositiveButton("Open settings",(d,w)->{
                    manualRequest=MANUAL_OVERLAY;
                    Intent overlay=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()));
                    try { startActivityForResult(overlay,MANUAL_OVERLAY); }
                    catch(RuntimeException unavailable) {
                        manualRequest=0;setManualNotice("Android could not open Display over other apps. Open that page in Settings, then try again.");
                    }
                }).show();
    }
    private void manualNotifications() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("askedNotify",false)) {
            manualRequest=MANUAL_NOTIFICATIONS;prefs.edit().putBoolean("askedNotify",true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},MANUAL_NOTIFICATIONS);
        } else requestManualProjection();
    }
    private void requestManualProjection() {
        manualRequest=MANUAL_PROJECTION;
        try { startActivityForResult(projectionPrompt(),MANUAL_PROJECTION); }
        catch(RuntimeException unavailable) {
            manualRequest=0;setManualNotice("Screen capture is unavailable. You can try setup again.");
        }
    }
    private Intent projectionPrompt() {
        MediaProjectionManager pm=getSystemService(MediaProjectionManager.class);
        if(pm==null)throw new IllegalStateException("Screen capture unavailable");
        return Build.VERSION.SDK_INT>=34?pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):pm.createScreenCaptureIntent();
    }
    private void setManualNotice(String message) {
        manualNotice=message; previousStatus=null;
        if(visible)refresh();
        if(message!=null)toast(message);
    }

    private void askForProjection() {
        launching = true;
        try { startActivityForResult(projectionPrompt(), PROJECTION); }
        catch (RuntimeException e) { launching = false; toast("Screen capture is unavailable on this device."); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        super.onRequestPermissionsResult(code, names, results);
        if(code==MANUAL_NOTIFICATIONS && manualRequest==code && !isFinishing())requestManualProjection();
        if(code==AUTO_NOTIFICATIONS && !isFinishing() && !manual && !WalkService.isActive())askForProjection();
    }

    private void beginVideo(int result,Intent grant) {
        if(!awaitingRangeGrant || grant==null)return;
        awaitingRangeGrant=false;
        if(!privacyReady())return;
        if(!hasReview()) { toast("Scan the time range again before recording."); return; }
        if(selectedForRun==null || selectedForRun.isEmpty()) { toast("Choose the trajectories to record."); return; }
        try {
            File dir=RunFiles.create(this); RunFiles.registerVideo(dir);
            CaptureRecorder.start(this,result,grant,dir);
            if(!WalkService.armRange(value(pkg),value(groups),value(steps),value(expanders),
                    selectedForRun,false,true,dwell(),dir)) {
                CaptureRecorder.finish(this); markFailed(dir,WalkService.getStatus()); toast(WalkService.getStatus()); return;
            }
            offerReview=false;
            if(reviewPanel!=null)page("capture");
            openTarget();
        } catch(IOException | RuntimeException e) {
            WalkService.cancel(); CaptureRecorder.finish(this);
            toast("Could not start the recording. "+e.getMessage());
        }
    }
    private void markFailed(File dir,String reason) throws IOException {
        try(FileWriter out=new FileWriter(new File(dir,"status.txt"))) { out.write("Incomplete. Could not start the walkthrough.\n"+reason); }
        RunFiles.packageRun(dir);
    }

    private void openTarget() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(target());
            if (intent == null) throw new ActivityNotFoundException();
            startActivity(intent);
        } catch (RuntimeException e) {
            inspecting = false; Inspection.stop(); WalkService.cancel();
            toast("Could not open the selected app.");
        }
    }

    private int dwell() {
        try {
            double sec = Double.parseDouble(value(hold));
            return sec >= 1 && sec <= 20 ? (int) Math.round(sec * 1000) : -1;
        } catch (NumberFormatException e) { return -1; }
    }

    private int libraryRequest;

    private void populateLibrary() {
        int request=++libraryRequest;
        boolean manualActive=ManualCaptureService.isActive(), active=manualActive || WalkService.isActive();
        RunFiles.io.execute(()->{
            List<Export> captures=listCaptures(active,manualActive);
            ui.post(()->{
                if(request==libraryRequest && "library".equals(currentPage) && !isDestroyed())renderLibrary(captures);
            });
        });
    }
    private List<Export> listCaptures(boolean active,boolean manualActive) {
        List<Export> captures=new ArrayList<>();
        for(File dir:RunFiles.sessions(this)) {
            if(active && !new File(dir,"status.txt").isFile())continue;
            if(RunFiles.video(dir).isFile())captures.add(new Export(dir,true));
            if(!RunFiles.isVideo(dir)) {
                if(new File(dir,"manual-session").isFile()) {
                    if(manualActive && !RunFiles.archive(dir).isFile())continue;
                    String[] images=new File(dir,"screenshots").list((folder,name)->name.endsWith(".png"));
                    if((images==null || images.length==0) && CaptureThumbnail.screenshotEntries(dir).isEmpty())continue;
                }
                captures.add(new Export(dir,false));
            }
        }
        return captures;
    }
    private void renderLibrary(List<Export> captures) {
        libraryContent.removeAllViews(); heading(libraryContent,"Capture library",28);
        Ui.gap(libraryContent,6); note(libraryContent,"Captures are stored on this device.");
        if(captures.isEmpty()) {
            Ui.gap(libraryContent,18);
            LinearLayout panel=Ui.column(this); panel.setGravity(Gravity.CENTER); Ui.pad(panel,18,28,18,28);
            panel.setBackground(Ui.shape(this,Ui.SURFACE,Ui.LINE,16)); libraryContent.addView(panel,Ui.lp(this,-1,-2));
            panel.addView(new Ui.Glyph(this,"library",Ui.TEAL),Ui.lp(this,34,34)); Ui.gap(panel,13);
            panel.addView(Ui.text(this,"No captures yet",19,Ui.INK,true)); Ui.gap(panel,6);
            TextView empty=Ui.text(this,"Your videos and screenshot ZIPs will appear here.",14,Ui.MUTED,false);
            empty.setGravity(Gravity.CENTER); panel.addView(empty); return;
        }
        Ui.gap(libraryContent,12);
        for(Export capture:captures) {
            LinearLayout item=Ui.column(this); item.setBackground(Ui.shape(this,Ui.SURFACE,Ui.LINE,15));
            libraryContent.addView(item,Ui.lp(this,-1,-2));
            Ui.Choice summary=new Ui.Choice(this,"android.widget.Button");
            summary.setGravity(Gravity.CENTER_VERTICAL);
            summary.setBackground(Ui.ripple(this,Color.TRANSPARENT,Color.TRANSPARENT,15));
            summary.setContentDescription("Open capture, "+capture.title+", "+capture.label);
            summary.setOnClickListener(v->openArtifact(capture));
            Ui.pad(summary,12,12,12,6); item.addView(summary,Ui.lp(this,-1,-2));
            CaptureThumbnail thumb=new CaptureThumbnail(this,capture.dir,capture.video,Ui.dp(this,88));
            thumb.setId(R.id.library_thumbnail); summary.addView(thumb,Ui.lp(this,66,84)); Ui.gap(summary,12);
            LinearLayout copy=Ui.column(this); summary.addView(copy,Ui.weight());
            copy.addView(Ui.text(this,capture.title,16,Ui.INK,true)); Ui.gap(copy,5);
            copy.addView(Ui.text(this,capture.label,13,Ui.MUTED,false)); Ui.gap(summary,6);
            Ui.Glyph next=new Ui.Glyph(this,"back",Ui.TEAL);
            next.setScaleX(getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_RTL?1:-1);
            summary.addView(next,Ui.lp(this,18,18));
            Button export=Ui.button(this,"Save to Files",false,()->export(capture));
            LinearLayout.LayoutParams saveLayout=Ui.lp(this,-1,-2);
            int inset=Ui.dp(this,12); saveLayout.setMargins(inset,Ui.dp(this,4),inset,inset);
            item.addView(export,saveLayout);
            Ui.gap(libraryContent,9);
        }
    }
    private void export(Export capture) {
        exportDir=capture.dir; exportVideo=capture.video;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(exportVideo?"video/mp4":"application/zip").putExtra(Intent.EXTRA_TITLE,capture.filename);
        startActivityForResult(intent,EXPORT);
    }

    private static final class Export {
        final File dir;
        final boolean video;
        final String label, filename, title;
        Export(File dir,boolean video) {
            this.dir=dir; this.video=video;
            String name=dir.getName(), stamp=name.substring(0,Math.min(19,name.length()));
            boolean complete=isComplete(dir);
            String localStamp;
            try {
                java.time.LocalDateTime parsed=java.time.LocalDateTime.parse(stamp,
                        java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss",java.util.Locale.US));
                localStamp=java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM,
                        java.time.format.FormatStyle.SHORT).format(parsed);
            } catch(java.time.DateTimeException ex) { localStamp=stamp.replace('_',' '); }
            title=localStamp;
            label=(new File(dir,"manual-session").isFile()?"Manual · ":"")+(video?"Video · MP4":"Screenshots · ZIP")+" · "+(complete?"Complete":"Partial");
            filename="Walkthrough_" + stamp + (complete ? "" : "_partial") + (video ? ".mp4" : ".zip");
        }
    }
    private static boolean isComplete(File dir) {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(dir,"status.txt")))) {
            String first=r.readLine(); return first!=null && first.startsWith("Complete");
        } catch (IOException e) { return false; }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code,result,data);
        if(code==MANUAL_OVERLAY && manualRequest==code) {
            manualRequest=0;
            if(Settings.canDrawOverlays(this))manualNotifications();
            else setManualNotice("Floating controls are off. Tap setup again to allow them.");
            return;
        }
        if(code==MANUAL_PROJECTION && manualRequest==code) {
            manualRequest=0;
            if(result!=RESULT_OK || data==null) {
                setManualNotice("Screen sharing was canceled. Tap setup to try again.");
            } else if(!Settings.canDrawOverlays(this)) {
                setManualNotice("Floating controls are off. Tap setup again to allow them.");
            } else if(pendingGuided && !WalkService.isConnected()) {
                setManualNotice("Tap guidance lost Accessibility access. Enable it and start setup again.");
            } else if(WalkService.isActive() || !ManualCaptureService.start(this,result,data,pendingManualVideo,pendingGuided,pendingTarget)) {
                setManualNotice("Could not start manual capture. Check Run status and try again.");
            } else {
                manualNotice=null;previousStatus=null;
                if(MuseProfile.LAB.equals(pendingTarget)) {
                    try {
                        Intent lab=getPackageManager().getLaunchIntentForPackage(MuseProfile.LAB);
                        if(lab==null)throw new ActivityNotFoundException();
                        startActivity(lab);
                    } catch(RuntimeException unavailable) {
                        ManualCaptureService.finish(this);
                        setManualNotice("Could not open Muse Test Lab. Install it and try again.");
                    }
                } else moveTaskToBack(true);
            }
            return;
        }
        if (code == PROJECTION) {
            launching = false;
            if (result == RESULT_OK && data != null && !WalkService.isActive()) beginVideo(result, data);
            else { awaitingRangeGrant=false; toast("Recording was not started."); }
        }
        if (code == EXPORT && result == RESULT_OK && data != null && data.getData() != null && exportDir != null) {
            final Uri uri = data.getData(); final File dir = exportDir; final boolean video = exportVideo;
            RunFiles.io.execute(() -> {
                try {
                    File file = video ? RunFiles.video(dir) : RunFiles.build(dir);
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new IOException("Could not open destination.");
                        Files.copy(file.toPath(), out);
                    }
                    runOnUiThread(() -> toast(video ? "Video saved." : "ZIP saved."));
                } catch (IOException | RuntimeException e) {
                    runOnUiThread(() -> toast("Export failed. Your session is still available: " + e.getMessage()));
                }
            });
        }
    }

    @Override protected void onResume() {
        super.onResume(); visible = true;
        labInstalled=getPackageManager().getLaunchIntentForPackage(MuseProfile.LAB)!=null;
        targetPackage=null;
        if (inspecting) {
            inspecting = false; Inspection.stop();
            if (lesson && Inspection.hasLesson()) {
                avatar.setText(Inspection.learned(0)); groups.setText(Inspection.learned(1));
                steps.setText(Inspection.learned(2)); remember();
                configLabel.setText(Inspection.lessonStatus());
            } else {
                TextView text = new TextView(this);
                text.setTextIsSelectable(true); text.setPadding(dp(18),dp(12),dp(18),dp(12));
                text.setText((lesson ? Inspection.lessonStatus() + "\n\n" : "") + Inspection.result());
                ScrollView scroll = new ScrollView(this); scroll.addView(text);
                AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                        .setTitle("Controls exposed by the app").setView(scroll).setNegativeButton("Done",null);
                if (video && Inspection.choices().length > 0) {
                    dialog.setPositiveButton("Choose trajectory controls",
                            (d,w) -> pickTaughtControl(new String[3], 0));
                }
                dialog.show();
            }
        }
        ui.removeCallbacks(ticker); ticker.run();
    }
    private void pickTaughtControl(String[] chosen, int stage) {
        String[] prompts = { "Choose the avatar", "Choose a broad action card", "Choose a step summary" };
        new AlertDialog.Builder(this).setTitle(prompts[stage])
                .setNegativeButton("Cancel", null)
                .setItems(Inspection.choices(), (dialog, index) -> {
                    chosen[stage] = Inspection.choice(index);
                    if (stage < 2) {
                        pickTaughtControl(chosen, stage + 1);
                    } else {
                        avatar.setText(chosen[0]); groups.setText(chosen[1]); steps.setText(chosen[2]);
                        remember(); configLabel.setText("Control rules saved for this walkthrough.");
                    }
                }).show();
    }
    private final Runnable ticker = new Runnable() {
        @Override public void run() { if (visible) { refresh(); ui.postDelayed(this, 650); } }
    };
    private void refresh() {
        boolean manualActive=ManualCaptureService.isActive();
        boolean active=WalkService.isActive() || manualActive;
        String target=manual && !offlineLab ? (manualVideo && manualGuided ? builtInPackage() : "") : target();
        if(!target.equals(targetPackage)) {
            targetPackage=target;
            String label;
            if(target.isEmpty()) label="Any app";
            else if(MuseProfile.LAB.equals(target)) label="Muse Test Lab";
            else if(MuseProfile.PKG.equals(target)) label="Muse";
            else {
                try { label=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(target,0)).toString(); }
                catch(PackageManager.NameNotFoundException ex) { label=target; }
            }
            boolean canChoose=labInstalled || offlineLab;
            targetLabel.setText(label);
            targetButton.setContentDescription((canChoose ? "Choose capture target: " : "Target app: ")+label);
            targetButton.setClickable(canChoose); targetButton.setFocusable(canChoose);
            targetArrow.setVisibility(canChoose?View.VISIBLE:View.GONE);
            targetButton.setBackground(canChoose?Ui.ripple(this,Ui.PALE,Ui.LINE,14):null);
            Ui.pad(targetButton,canChoose?10:0,0,canChoose?8:0,0);
        }
        boolean connected=WalkService.isConnected();
        if(previousConnection==null || previousConnection!=connected) {
            previousConnection=connected;
            serviceState.setText(connected?"Service on":"Set up"); serviceButton.setContentDescription(connected?
                "Accessibility service enabled. Open settings":"Accessibility service is off. Open settings");
            serviceDot.setBackground(Ui.shape(this,connected?0xff8fe0bc:0xfff0cc8e,0,8));
        }
        String status=displayedStatus();
        if(!status.equals(previousStatus)) {
            previousStatus=status; int line=status.indexOf('\n');
            statusView.setText(line<0?status:status.substring(0,line)); statusMore.setVisibility(line<0?View.GONE:View.VISIBLE);
            if("library".equals(currentPage))populateLibrary();
        }
        if(visible && pendingScanNotice) {
            pendingScanNotice=false;
            if(!active && !launching && status.startsWith("Scan incomplete")) {
                page("capture");
                statusView.post(()->{
                    android.graphics.Rect bounds=new android.graphics.Rect();
                    statusView.getDrawingRect(bounds); statusView.requestRectangleOnScreen(bounds,true);
                    statusView.announceForAccessibility(statusView.getText());
                });
            }
        }
        boolean canReview = !active && !launching && hasReview();
        review.setVisibility(canReview ? View.VISIBLE : View.GONE);
        if(visible && !captureBusy()) {
            File completed=CaptureCompletion.take(this);
            if(completed!=null) {
                offerReview=false;
                showArtifact(new Export(completed,RunFiles.isVideo(completed)),true,0,0);
            }
        }
        if (visible && offerReview && !active && !launching && !"artifact".equals(currentPage)) {
            offerReview=false;
            if (canReview) ui.post(this::showReview);
        }
        boolean idle=!captureBusy();
        targetButton.setEnabled(idle);
        Ui.enable(start,idle); Ui.enable(chatStart,idle);Ui.enable(manualStart,idle);
        manualStart.setVisibility(manualActive?View.GONE:View.VISIBLE);
        manualRestore.setVisibility(manualActive?View.VISIBLE:View.GONE);
        videoTile.setEnabled(idle);stillTile.setEnabled(idle);manualMethod.setEnabled(idle);autoMethod.setEnabled(idle);
        guidedChoice.setEnabled(idle);freehandChoice.setEnabled(idle);
        cancel.setText(manualActive?"Finish capture":"Stop walkthrough");
        cancel.setVisibility(active && !WalkService.isPackaging()?View.VISIBLE:View.GONE);
    }
    @Override protected void onPause() {
        if(artifactView!=null)artifactView.onHostPause();
        remember(); visible = false; ui.removeCallbacks(ticker); super.onPause();
    }
    @Override protected void onDestroy() { clearArtifact(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putBoolean("inspecting",inspecting);
        out.putBoolean("rangeGrant",awaitingRangeGrant); out.putBoolean("lesson",lesson); out.putBoolean("launching", launching);
        out.putInt("manualRequest",manualRequest); out.putBoolean("pendingManualVideo",pendingManualVideo);
        out.putBoolean("pendingGuided",pendingGuided); out.putString("pendingTarget",pendingTarget);
        out.putString("manualNotice",manualNotice);
        out.putString("page",currentPage); out.putBoolean("advanced",rules.getVisibility()==View.VISIBLE);
        if(displayedArtifact!=null) {
            out.putString("displayedRun",displayedArtifact.dir.getName()); out.putBoolean("displayedVideo",displayedArtifact.video);
            out.putBoolean("justSaved",justSaved); out.putInt("screenshotIndex",artifactView.screenshotIndex());
            out.putBoolean("expandedPreview",artifactView.isExpanded());
            out.putInt("videoPosition",artifactView.videoPosition());
            out.putInt("videoDuration",artifactView.videoDuration());
        }
        if(reviewPanel!=null)out.putParcelable("reviewPosition",reviewPanel.scrollState());
        out.putBoolean("exportVideo",exportVideo);
        if (exportDir != null) out.putString("export", exportDir.getAbsolutePath());
    }
    private void remember() {
        SharedPreferences.Editor edit = targetPrefs.edit();
        if (MuseProfile.supports(value(pkg))) edit.putBoolean("museDefaultsReviewed",true);
        range.remember(edit);
        prefs.edit().putBoolean("manualMethod",manual).putBoolean("manualVideo",manualVideo).putBoolean("manualGuidedTaps",manualGuided)
            .putString("captureMode",video?"trajectory":"conversation").apply();
        edit.putString("timeRule",value(timeRule)).putString("titleRule",value(titleRule)).putString("pkg",value(pkg)).putString("avatar",value(avatar)).putString("groups",value(groups))
            .putString("steps",value(steps)).putString("expanders",value(expanders)).putString("hold",value(hold))
            .apply();
    }
    private String value(EditText box) { return box.getText().toString().trim(); }
    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void heading(LinearLayout parent,String title,int size) {
        TextView t=Ui.text(this,title,size,Ui.INK,true); t.setAccessibilityHeading(true); parent.addView(t);
    }
    private TextView note(LinearLayout parent,String text) {
        TextView t=Ui.text(this,text,14,Ui.MUTED,false); Ui.pad(t,0,3,0,4); parent.addView(t); return t;
    }
    private EditText field(LinearLayout parent,String label,String initial) {
        Ui.gap(parent,10); TextView caption=Ui.text(this,label,14,Ui.INK,true); parent.addView(caption); Ui.gap(parent,4);
        EditText e=new EditText(this); e.setSingleLine(true); e.setTextSize(15); e.setText(initial);
        e.setTextColor(Ui.INK); e.setBackground(Ui.shape(this,Ui.PAPER,Ui.LINE,9)); Ui.pad(e,11,8,11,8); e.setMinHeight(dp(46));
        e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setId(View.generateViewId()); caption.setLabelFor(e.getId()); parent.addView(e,Ui.lp(this,-1,-2)); return e;
    }
    private Button button(LinearLayout parent,String s,Runnable action) {
        Button b=Ui.button(this,s,false,action); LinearLayout.LayoutParams lp=Ui.lp(this,-1,-2);
        lp.topMargin=dp(6); parent.addView(b,lp); return b;
    }
    private String displayedStatus() {
        if(!manual)return WalkService.getStatus();
        if(manualNotice!=null)return manualNotice;
        String status=ManualCaptureService.lastMessage();
        return status==null || status.isEmpty()?"Ready. Finish from the controls or return here through Recents.":status;
    }
    private void statusDetails() {
        TextView info=Ui.text(this,displayedStatus(),14,Ui.INK,false);
        info.setTextIsSelectable(true); Ui.pad(info,18,10,18,10); ScrollView scroll=new ScrollView(this);
        scroll.addView(info); new AlertDialog.Builder(this).setTitle("Run details").setView(scroll).setPositiveButton("Done",null).show();
    }
}
