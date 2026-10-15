package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import android.graphics.Color;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.UUID;

/** Real native vector/cel studio. Touch and numeric changes call the same facade as ChatGPT. */
public final class SketchStudioActivity extends Activity implements SketchCanvasView.Listener {
    private SketchLivePlayer livePlayer;private String liveSessionId="",liveDrawingObject,liveCaption;private long liveDisplayedRevision;
    private String notifiedSketchId,notifiedSessionId,liveMethod="";
    private boolean notificationReplayPending;
    private JSONObject document;
    private SketchEditorSession session;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService transfers=Executors.newSingleThreadExecutor();
    private boolean loadingDrawing;
    private boolean destroyed,resumed,watching=true,watchInFlight,grid,symmetry;
    private JSONObject symmetrySettings;
    private String brushPreset="ink",bookId;
    private JSONObject book;
    private int bookPage,loadGeneration,pageGeneration,liveLoadGeneration;
    private float brushOpacity=1,brushHardness=.7f,brushAngle=35,smoothing=.2f;
    private float paintSize=12,paintFlow=.65f,paintSpacing=.2f;
    private boolean paintPressure=true,paintAlphaLock,paintClipEnabled,sampleCloneReference;
    private String paintTip="round",paintTexture="smooth",paintMode="paint",pendingPaintCelId;
    private int paintSeed;
    private JSONObject paintClip,paintCloneSource;
    private SketchFilmstripView filmstrip;
    private TextView toolStatus;
    private Spinner toolPicker;
    private int humanToolPosition,pendingAgentToolSelection=-1;
    private boolean syncingAgentTool,agentGlobalTool;
    private android.graphics.drawable.Drawable agentToolIndicator;
    private final String[] toolValues={"stroke","eraser","line","rect","ellipse","polygon","select","eyedropper","pan","curve","nodes","paint","paint_erase","clone"};
    private String sketchId;
    private SketchCanvasView canvas;
    private TextView status;
    private SeekBar frames;
    private Spinner layers;
    private final ArrayList<String> layerIds = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean updating, playing, scrubbing;
    private boolean bookPlaybackInFlight;private int playbackGeneration;
    private long playStarted;
    private int playFirst;
    private String brushColor = "#FF161C2B";
    private float brushWidth = .008f;
    private boolean brushFill;
    private Button playButton,liveButton;private boolean canvasFocus;private final java.util.ArrayList<View> drawingChrome=new java.util.ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        watching=getPreferences(MODE_PRIVATE).getBoolean("watchLiveDrawing",true);
        readDrawingInvitation(getIntent());
        if(notifiedSketchId==null&&state!=null){notifiedSketchId=state.getString("notifiedSketchId");notifiedSessionId=state.getString("notifiedSessionId");notificationReplayPending=notifiedSessionId!=null;}
        if(notifiedSessionId!=null)watching=true;
        LinearLayout root = column(); root.setPadding(dp(6), dp(2), dp(6), dp(2)); root.setBackgroundColor(StudioUi.BG);getWindow().setStatusBarColor(StudioUi.BG);getWindow().setNavigationBarColor(StudioUi.BG); setContentView(root);
        status = new TextView(this); status.setTextSize(12);status.setTextColor(StudioUi.MUTED);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setText("Sketch Studio · opening drawing…");root.addView(status);
        LinearLayout projects = row(root);
        button(projects,"File",this::fileMenu);
        liveButton=button(projects,"Draw myself",this::toggleLiveWatch);liveButton.setLayoutParams(new LinearLayout.LayoutParams(0,-2,2));updateLiveControl();
        LinearLayout history = row(root);
        button(history,"Undo",()->history(false));button(history,"Redo",()->history(true));button(history,"Export",this::export);
        canvas = new SketchCanvasView(this); canvas.setListener(this);canvas.setAgentHandEnabled(getPreferences(MODE_PRIVATE).getBoolean("agentHand",true));
        try{
            symmetrySettings=SketchSymmetry.configuration(new JSONObject(getPreferences(MODE_PRIVATE).getString("symmetrySettings","{\"mode\":\"vertical\"}")));
            symmetry=getPreferences(MODE_PRIVATE).getBoolean("symmetry",false);grid=getPreferences(MODE_PRIVATE).getBoolean("grid",false);
            canvas.setSymmetry(symmetrySettings);canvas.setGuides(grid,symmetry);
        }catch(Exception invalid){symmetry=false;grid=false;try{symmetrySettings=SketchSymmetry.configuration(new JSONObject().put("mode","vertical"));canvas.setSymmetry(null);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
        onionEnabled=getPreferences(MODE_PRIVATE).getBoolean("onionEnabled",true);onionRange=getPreferences(MODE_PRIVATE).getInt("onionRange",1);onionAlpha=getPreferences(MODE_PRIVATE).getInt("onionAlpha",48);canvas.setOnion(onionEnabled);canvas.setOnionOptions(onionRange,onionAlpha);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout tools = row(root); Spinner tool = new Spinner(this);toolPicker=tool;
        String[] names = {"Pen", "Eraser", "Line", "Rectangle", "Ellipse", "Polygon", "Select / move", "Eyedropper", "Pan canvas", "Curve pen", "Edit curve nodes", "Paint brush", "Erase paint", "Clone brush"};
        tool.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names){@Override public View getView(int position,View convert,android.view.ViewGroup parent){TextView view=(TextView)super.getView(position,convert,parent);view.setTextColor(StudioUi.TEXT);view.setTextSize(12);view.setSingleLine(true);view.setEllipsize(android.text.TextUtils.TruncateAt.END);return view;}});
        tool.setContentDescription("Drawing tool");tool.setMinimumHeight(dp(48));tools.addView(tool,new LinearLayout.LayoutParams(0,dp(48),1));
        tool.setOnItemSelectedListener(new SimpleSelection() { @Override public void selected(int position) { if(canvas!=null){if(syncingAgentTool||position==pendingAgentToolSelection){pendingAgentToolSelection=-1;return;}humanToolPosition=position;if(position>=11)runAction("Choose paint tool",()->activatePaint(position==12?"erase":position==13?"clone":"paint"));else canvas.setTool(toolValues[position]);} } });
        button(tools,"Brush / Text",this::brushMenu);button(tools,"More",this::moreMenu);
        toolStatus=new TextView(this);toolStatus.setTextColor(StudioUi.MUTED);toolStatus.setTextSize(12);
        filmstrip=new SketchFilmstripView(this);filmstrip.setListener(index->runAction("Select frame",()->selectFilmstrip(index)));root.addView(filmstrip,new LinearLayout.LayoutParams(-1,dp(48)));
        frames = new SeekBar(this);frames.setMinimumHeight(dp(48));frames.setContentDescription("Frame position");frames.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (fromUser&&!scrubbing) { runAction("Select frame",()->selectFilmstrip(progress)); } }
            public void onStartTrackingTouch(SeekBar bar) { scrubbing=true; }
            public void onStopTrackingTouch(SeekBar bar) { scrubbing=false;int position=bar.getProgress();runAction("Select frame",()->selectFilmstrip(position)); }
        });
        LinearLayout timing = row(root);
        button(timing,"◀",()->selectFilmstrip((bookId==null?canvas.getFrame():bookPage)-1)).setContentDescription("Previous frame or page");playButton=button(timing,"Play",this::togglePlayback);
        button(timing,"▶",()->selectFilmstrip((bookId==null?canvas.getFrame():bookPage)+1)).setContentDescription("Next frame or page");
        button(timing,"Go",this::scrubDialog).setContentDescription("Go to an exact frame or book page");
        layers = new Spinner(this);
        layers.setOnItemSelectedListener(new SimpleSelection() { @Override public void selected(int position) {
            if (!updating && position < layerIds.size()) { canvas.setLayerId(layerIds.get(position)); updateStatus(); }
        } });
        drawingChrome.add(history);drawingChrome.add(tools);drawingChrome.add(filmstrip);drawingChrome.add(timing);
        canvasFocus=getPreferences(MODE_PRIVATE).getBoolean("canvasFocus",false);applyCanvasFocus();
        try {
            bookId=state==null?getPreferences(MODE_PRIVATE).getString("lastBookId",null):state.getString("bookId");bookPage=state==null?getPreferences(MODE_PRIVATE).getInt("lastBookPage",0):state.getInt("bookPage",0);
            sketchId = state == null ? getIntent().getStringExtra("sketchId") : state.getString("sketchId");
            if(notifiedSketchId!=null){bookId=null;book=null;sketchId=notifiedSketchId;}
            if (sketchId == null) sketchId = getPreferences(MODE_PRIVATE).getString("lastSketchId", null);
            if (sketchId == null) {
                try (SketchProjectStore store = new SketchProjectStore(this)) { JSONArray list = store.list(); if (list.length() > 0) sketchId = list.getJSONObject(0).getString("sketchId"); }
            }
            if(bookId!=null)openBookPage(bookId,bookPage);else if (sketchId == null) createDocument("Untitled sketch"); else reload();
            if (state != null&&document!=null) setFrame(state.getInt("frame", 0));
            handler.postDelayed(watchTick,250);
        } catch (Exception e) { onMessage(e.getMessage()); openDocument(); }
    }

    private boolean readDrawingInvitation(Intent intent){
        if(intent==null||!SketchLiveNotification.ACTION_WATCH.equals(intent.getAction()))return false;
        try{JSONObject ids=new JSONObject().put("sketchId",intent.getStringExtra(SketchLiveNotification.EXTRA_SKETCH_ID)).put("sessionId",intent.getStringExtra(SketchLiveNotification.EXTRA_SESSION_ID));
            String target=SketchDocument.id(ids,"sketchId"),stream=SketchDocument.id(ids,"sessionId");
            notifiedSketchId=target;notifiedSessionId=stream;notificationReplayPending=true;watching=true;
            return true;
        }catch(Exception invalid){return false;}
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);if(intent==null||!SketchLiveNotification.ACTION_WATCH.equals(intent.getAction()))return;
        if(!readDrawingInvitation(intent))return;setIntent(intent);updateLiveControl();
        final String target=notifiedSketchId,stream=notifiedSessionId;SketchEditorSession active=session;
        ++liveLoadGeneration;++pageGeneration;playing=false;handler.removeCallbacks(playTick);playButton.setText("Play");stopLivePlayer();loadingDrawing=true;
        Runnable open=()->postUi(()->{if(destroyed||!target.equals(notifiedSketchId)||!stream.equals(notifiedSessionId))return;bookId=null;book=null;loadSketch(target,0);});
        if(active!=null&&active.pending()>0)afterSaves(()->open.run());else open.run();
    }
    private String liveMethodLabel(){return SketchDrawingPolicy.ORIGINAL.equals(liveMethod)?"Original strokes":"Reference-assisted";}

    private void reload() { loadSketch(sketchId,canvas.getFrame()); }
    private void loadSketch(String id,int frame) {
        if(destroyed)return;loadingDrawing=true;stopLivePlayer();
        if(notifiedSketchId!=null&&!notifiedSketchId.equals(id)){notifiedSketchId=null;notifiedSessionId=null;notificationReplayPending=false;}
        if(session!=null&&session.pending()>0){onMessage("Finishing the current drawing save");return;}
        if(session!=null){session.close();session=null;}canvas.setEnabled(false);
        int generation=++loadGeneration,requestedPageGeneration=pageGeneration;io.execute(()->{try{JSONObject loaded;String target=id;try(SketchProjectStore store=new SketchProjectStore(this)){loaded=store.get(id);}SketchEditorSession opened=new SketchEditorSession(this,loaded);boolean recovered=opened.error()!=null;if(recovered){opened.close();target=SketchEditorSession.recoverAsCopy(this,id);try(SketchProjectStore store=new SketchProjectStore(this)){loaded=store.get(target);}opened=new SketchEditorSession(this,loaded);}SketchEditorSession prepared=opened;String selectedId=target;if(destroyed){prepared.close();return;}postUi(()->{if(destroyed||generation!=loadGeneration||requestedPageGeneration!=pageGeneration){prepared.close();return;}try{if(recovered){bookId=null;book=null;onMessage("Your interrupted drawing was recovered as a separate sketch");}sketchId=selectedId;session=prepared;session.setListener(new SketchEditorSession.Listener(){
                    private boolean current(){return !destroyed&&session==prepared&&generation==loadGeneration&&requestedPageGeneration==pageGeneration;}
                    public void saved(int count){if(current())updateStatus();}
                    public void preparedSaved(JSONObject drawing,JSONArray operations,int count){if(!current())return;try{canvas.acknowledgePaint(operations);present(prepared.document());if(pendingPaintCelId!=null){try{SketchDocument.object(document,pendingPaintCelId);canvas.selectObject(pendingPaintCelId);pendingPaintCelId=null;canvas.setTool("erase".equals(paintMode)?"paint_erase":"clone".equals(paintMode)?"clone":"paint");int selectedTool="erase".equals(paintMode)?12:"clone".equals(paintMode)?13:11;if(toolPicker.getSelectedItemPosition()!=selectedTool)toolPicker.setSelection(selectedTool);}catch(Exception missing){}}updateStatus();}catch(Exception error){onMessage(error.getMessage());}}
                    public void failed(String error){if(current()){status.setText("Drawing retained · local stroke intent available");onMessage(error+". Workspace → Recover local draft keeps it as a separate sketch.");}}
                });present(session.document());setFrame(frame);prepareBookOnion(prepared,selectedId,generation,requestedPageGeneration);loadingDrawing=false;canvas.setEnabled(!loadingDrawing&&!playing&&livePlayer==null);}catch(Exception error){onMessage(error.getMessage());}});}catch(Exception error){postUi(()->onMessage(error.getMessage()));}});
    }
    private void prepareBookOnion(SketchEditorSession active,String owner,int generation,int pageGenerationAtLoad){
        if(bookId==null||book==null)return;String selectedBook=bookId;int selectedPage=bookPage,total=book.optInt("pageCount");
        io.execute(()->{try{JSONObject previous=null,next=null;try(SketchProjectStore store=new SketchProjectStore(this)){
            if(selectedPage>0)try{previous=store.get(selectedBook+"_p"+(selectedPage-1));}catch(IllegalArgumentException missing){if(!missing.getMessage().startsWith("UNKNOWN_SKETCH"))throw missing;}
            if(selectedPage+1<total)try{next=store.get(selectedBook+"_p"+(selectedPage+1));}catch(IllegalArgumentException missing){if(!missing.getMessage().startsWith("UNKNOWN_SKETCH"))throw missing;}
        }JSONObject before=previous,after=next;postUi(()->{if(session!=active||generation!=loadGeneration||pageGenerationAtLoad!=pageGeneration||!selectedBook.equals(bookId)||selectedPage!=bookPage)return;try{canvas.setBookOnionDocuments(owner,before,after);}catch(Exception failure){onMessage(failure.getMessage());}});
        }catch(Exception failure){postUi(()->{if(session==active&&generation==loadGeneration)onMessage("Adjacent onion page: "+failure.getMessage());});}});
    }
    private void present(JSONObject value) throws Exception {
        canvas.setDocument(value);presentMetadata(value);
    }
    private void presentMetadata(JSONObject value) throws Exception {
        document=value;updating=true;layerIds.clear();ArrayList<String> names=new ArrayList<>();JSONArray items=document.getJSONArray("layers");
        for(int i=0;i<items.length();i++){JSONObject layer=items.getJSONObject(i);layerIds.add(layer.getString("id"));names.add(layer.getString("name")+(layer.getBoolean("locked")?" 🔒":"")+(!layer.getBoolean("visible")?" (hidden)":""));}
        layers.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));layers.setSelection(Math.max(0,layerIds.indexOf(canvas.getLayerId())));frames.setMax((bookId==null?document.getInt("frameCount"):book.getInt("pageCount"))-1);frames.setProgress(bookId==null?canvas.getFrame():bookPage);updating=false;
        getPreferences(MODE_PRIVATE).edit().putString("lastSketchId",sketchId).putString("lastBookId",bookId).putInt("lastBookPage",bookPage).apply();updateBrush();updateStatus();
    }
    @Override public void onOperation(JSONObject operation) {
        try{apply(new JSONArray().put(operation));}catch(Exception error){onMessage(error.getMessage());}
    }
    private void apply(JSONArray operations) {
        if(session==null||loadingDrawing)return;if(livePlayer!=null){onMessage("Tap Draw myself to pause live drawing before editing");return;}stopPlayback();try{if(SketchPaintOps.containsPaint(operations)||session.hasPreparedSaves()){session.submitPrepared(operations);updateStatus();}else{session.submit(operations);present(session.document());}}catch(Exception error){onMessage(error.getMessage());}
    }
    @Override public void onColorPicked(String value){brushColor=value;if(toolPicker.getSelectedItemPosition()<11)toolPicker.setSelection(0);updateBrush();}
    @Override public SketchCanvasView.PaintCommitter beginPaintGesture(String id,long revision,int frame,String objectId){
        SketchEditorSession origin=session;int generation=loadGeneration,page=pageGeneration;
        if(origin==null||loadingDrawing||!id.equals(sketchId))return operation->{throw SketchDocument.bad("Open the selected drawing before painting");};
        return operation->{origin.submitPrepared(new JSONArray().put(operation));if(session==origin&&generation==loadGeneration&&page==pageGeneration)updateStatus();};
    }
    @Override public void onSampleRequested(double x,double y,int frame){
        if(session==null||document==null)return;SketchEditorSession active=session;String id=sketchId;long revision=document.optLong("revision");int generation=loadGeneration,page=pageGeneration;
        JSONObject reference=sampleCloneReference?paintCloneSource:null;
        io.execute(()->{try{active.awaitIdle(15000);if(active.error()!=null)throw new IllegalStateException(active.error());
            JSONObject args=new JSONObject().put("sketchId",id).put("expectedRevision",active.document().getLong("revision")).put("x",x).put("y",y).put("frameIndex",frame).put("source",reference==null?"composite":"reference");
            if(reference!=null)args.put("objectId",reference.getString("objectId")).put("sha256",reference.getString("sha256"));
            String sampled=SketchEditOps.execute(this,"sketch_sample_color",args).getString("color");
            postUi(()->{if(session==active&&generation==loadGeneration&&page==pageGeneration){onColorPicked(sampled);onMessage("Sampled "+sampled);}});
        }catch(Exception failure){postUi(()->{if(session==active&&generation==loadGeneration&&page==pageGeneration)onMessage(failure.getMessage());});}});
    }
    private void updateBrush(){if(canvas==null)return;canvas.setBrush(brushColor,brushWidth,brushFill);try{canvas.setBrushStyle(SketchBrushes.style(brushPreset,brushOpacity,brushHardness,true,brushAngle),smoothing);canvas.setPaintBrush(paintStyle(),paintAlphaLock,paintClipEnabled?paintClip:null,paintCloneSource);}catch(Exception error){onMessage(error.getMessage());}if(toolStatus!=null)toolStatus.setText(brushPreset+" · "+(document==null?"":Math.round(brushWidth*Math.min(document.optInt("width"),document.optInt("height")))+" px · ")+brushColor+" · pinch to zoom / two fingers to pan");}
    private void postUi(Runnable work){if(!destroyed)handler.post(()->{if(!destroyed)work.run();});}
    private interface IoAction{void run() throws Exception;}
    private void afterSaves(IoAction work){if(destroyed)return;SketchEditorSession active=session;io.execute(()->{try{if(active!=null){active.awaitIdle(15000);if(active.error()!=null)throw new IllegalStateException(active.error());}work.run();}catch(Exception error){postUi(()->onMessage(error.getMessage()));}});}
    private JSONObject request() throws Exception { return new JSONObject().put("sketchId", sketchId).put("operationId", UUID.randomUUID().toString()).put("expectedRevision", document.getLong("revision")); }
    @Override public void onSelection(String id) { updateStatus(); }
    @Override public void onMessage(String message) { if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void updateStatus() {
        try { if (document != null) {status.setText((bookId==null?document.getString("name"):book.optString("name")+" · page "+(bookPage+1)+"/"+book.optInt("pageCount")) + " · r" + document.getLong("revision") + " · frame " + (canvas.getFrame() + 1) + "/" + document.getInt("frameCount") + " · " + (bookId==null?document.getInt("fps"):book.getInt("fps")) + " FPS" + (bookId==null?"":" · hold ×"+SketchTimelineTools.exposure(book.optJSONObject("exposures"),bookPage)) + (canvas.getSelectedId() == null ? "" : " · selected")+(session!=null&&session.pending()>0?" · saving":"")+(watching?" · live agent":""));filmstrip.setTimelineKey(bookId==null?"sketch:"+sketchId:"book:"+bookId);filmstrip.setExposures(bookId==null?null:book.optJSONObject("exposures"));filmstrip.setFrames(bookId==null?document.getInt("frameCount"):book.getInt("pageCount"),bookId==null?canvas.getFrame():bookPage);} }
        catch (Exception ignored) {}
    }
    private void history(boolean redo) {stopPlayback();afterSaves(()->{SketchEditOps.execute(this,redo?"sketch_redo":"sketch_undo",request());postUi(this::reload);});}
    private void createDocument(String name) {stopPlayback();afterSaves(()->{String id=UUID.randomUUID().toString();SketchEditOps.execute(this,"sketch_create",new JSONObject().put("sketchId",id).put("operationId",UUID.randomUUID().toString()).put("name",name));postUi(()->{bookId=null;book=null;loadSketch(id,0);});});}
    private void newDocument() {
        EditText name = input("Sketch name", "Untitled sketch", false); new AlertDialog.Builder(this).setTitle("New sketch").setView(name).setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (d, w) -> { try { createDocument(name.getText().toString().trim()); } catch (Exception e) { onMessage(e.getMessage()); } }).show();
    }
    private void openDocument() {
        stopPlayback(); try (SketchProjectStore store = new SketchProjectStore(this)) {
            JSONArray sketches = store.list(); String[] names = new String[sketches.length()]; for (int i = 0; i < names.length; i++) names[i] = sketches.getJSONObject(i).getString("name");
            if (names.length == 0) { newDocument(); return; }
            new AlertDialog.Builder(this).setTitle("Open sketch").setItems(names, (d, index) -> { try { bookId=null;book=null;sketchId = sketches.getJSONObject(index).getString("sketchId"); reload(); } catch (Exception e) { onMessage(e.getMessage()); } }).show();
        } catch (Exception e) { onMessage(e.getMessage()); }
    }
    private void export() {
        if(document==null)return;stopPlayback();afterSaves(()->{JSONObject args=request().put("aspect","original").put("quality","720p");
            if(bookId!=null)args=new JSONObject().put("bookId",bookId).put("expectedRevision",book.getLong("revision")).put("operationId",UUID.randomUUID().toString()).put("aspect","original").put("quality","720p");
            Intent intent=new Intent(this,ControlService.class).setAction(bookId==null?"com.rezoxnemesis.videostudio.LOCAL_SKETCH_EXPORT":"com.rezoxnemesis.videostudio.LOCAL_SKETCH_BOOK_EXPORT").putExtra("requestJson",args.toString());startForegroundService(intent);postUi(()->onMessage("Native animation export started; keep drawing while it runs."));});
    }

    private void brushDialog() {
        if(document==null)return;LinearLayout form=column();Spinner preset=new Spinner(this);preset.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,SketchBrushes.PRESETS));preset.setSelection(java.util.Arrays.asList(SketchBrushes.PRESETS).indexOf(brushPreset));form.addView(preset);
        EditText color=input("Colour #AARRGGBB",brushColor,false),width=input("Width in pixels",String.valueOf(brushWidth*Math.min(document.optInt("width"),document.optInt("height"))),true),opacity=input("Opacity 0..1",String.valueOf(brushOpacity),true),hardness=input("Hardness 0..1 (airbrush)",String.valueOf(brushHardness),true),angle=input("Nib angle in degrees",String.valueOf(brushAngle),true),smooth=input("Stabilisation 0..0.9",String.valueOf(smoothing),true);
        SketchColourPicker picker=new SketchColourPicker(this,brushColor,value->color.setText(value));form.addView(picker,new LinearLayout.LayoutParams(-1,dp(180)));for(EditText value:new EditText[]{color,width,opacity,hardness,angle,smooth})form.addView(value);CheckBox fill=new CheckBox(this);fill.setText("Fill shapes");fill.setTextColor(Color.WHITE);fill.setChecked(brushFill);form.addView(fill);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Brush and colour").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String next=color.getText().toString();SketchDocument.color(next);float size=Float.parseFloat(width.getText().toString())/Math.min(document.getInt("width"),document.getInt("height"));if(!Float.isFinite(size)||size<.0005||size>.2)throw SketchDocument.bad("Choose a positive canvas stroke width");float nextSmoothing=Float.parseFloat(smooth.getText().toString());if(!Float.isFinite(nextSmoothing)||nextSmoothing<0||nextSmoothing>.9)throw SketchDocument.bad("Stabilisation must be 0..0.9");JSONObject style=SketchBrushes.style(preset.getSelectedItem().toString(),Double.parseDouble(opacity.getText().toString()),Double.parseDouble(hardness.getText().toString()),true,Double.parseDouble(angle.getText().toString()));brushColor=next;brushWidth=size;brushFill=fill.isChecked();brushPreset=style.getString("preset");brushOpacity=(float)style.getDouble("opacity");brushHardness=(float)style.getDouble("hardness");brushAngle=(float)style.getDouble("angle");smoothing=nextSmoothing;updateBrush();dialog.dismiss();}catch(Exception error){onMessage(error.getMessage());}});
    }
    private void bookTimingDialog(){
        if(bookId==null||book==null){onMessage("Open a sketch book to edit its page exposure sheet");return;}
        final String targetBook=bookId;final long revision=book.optLong("revision");final int count=book.optInt("pageCount");
        LinearLayout form=column();
        TextView summary=new TextView(this);summary.setTextColor(StudioUi.TEXT);summary.setText(String.format(java.util.Locale.ROOT,"%d pages · %d exposed frames · %.2f seconds\nSet up to 128 pages per edit. Page numbers start at 1.",count,SketchTimelineTools.totalFrames(book.optJSONObject("exposures"),count),SketchTimelineTools.totalFrames(book.optJSONObject("exposures"),count)/(double)book.optInt("fps",24)));form.addView(summary);
        EditText first=input("First page (1.."+count+")",String.valueOf(bookPage+1),true),last=input("Last page (inclusive)",String.valueOf(bookPage+1),true),hold=input("Exposure per page (1..600 frames)",String.valueOf(SketchTimelineTools.exposure(book.optJSONObject("exposures"),bookPage)),true),fps=input("Book FPS 24, 30 or 60",String.valueOf(book.optInt("fps")),true);
        first.setContentDescription("First exposure page");last.setContentDescription("Last exposure page");hold.setContentDescription("Page exposure frames");fps.setContentDescription("Book frames per second");
        form.addView(first);form.addView(last);form.addView(hold);LinearLayout presets=row(form);
        button(presets,"On ones",()->hold.setText("1"));button(presets,"On twos",()->hold.setText("2"));button(presets,"On threes",()->hold.setText("3"));form.addView(fps);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Book exposure sheet").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            JSONArray edits=SketchTimelineTools.exposureEdits(first.getText().toString(),last.getText().toString(),hold.getText().toString(),count);int nextFps=Integer.parseInt(fps.getText().toString().trim());NativeFrameClock.requireFps(nextFps);
            if(!targetBook.equals(bookId)){onMessage("The book changed. Open its exposure sheet again.");return;}
            JSONObject args=new JSONObject().put("bookId",targetBook).put("expectedRevision",revision).put("operationId",UUID.randomUUID().toString()).put("fps",nextFps).put("exposures",edits);
            runAction("Apply exposure sheet",()->{if(!targetBook.equals(bookId)){onMessage("The book changed. Open its exposure sheet again.");return;}afterSaves(()->{
                JSONObject saved=SketchEditOps.execute(this,"sketch_book_edit",args).getJSONObject("book");
                postUi(()->{if(targetBook.equals(bookId)&&book!=null&&saved.optLong("revision")>=book.optLong("revision")){book=saved;updateStatus();onMessage("Updated "+edits.length()+" page exposures");}});
            });dialog.dismiss();});
        }catch(Exception error){onMessage(error.getMessage());}});
    }
    private void timingDialog() {
        if(document==null)return;
        if(bookId!=null){bookTimingDialog();return;}LinearLayout form = column(); EditText fps = input("FPS: 24, 30 or 60", String.valueOf(document.optInt("fps")), true), count = input("Frame count (1..1200)", String.valueOf(document.optInt("frameCount")), true);
        EditText width = input("Canvas width (even, 256..1280)", String.valueOf(document.optInt("width")), true), height = input("Canvas height (even, 256..1280)", String.valueOf(document.optInt("height")), true), background = input("Background #AARRGGBB", document.optString("background"), false);
        form.addView(fps); form.addView(count); form.addView(width); form.addView(height); form.addView(background);
        new AlertDialog.Builder(this).setTitle("Timing and canvas").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Apply", (d,w) -> {
            try { apply(new JSONArray().put(new JSONObject().put("type", "set_timing").put("fps", Integer.parseInt(fps.getText().toString())).put("frameCount", Integer.parseInt(count.getText().toString())))
                    .put(new JSONObject().put("type", "set_canvas").put("width", Integer.parseInt(width.getText().toString())).put("height", Integer.parseInt(height.getText().toString())).put("background", background.getText().toString()))); }
            catch (Exception e) { onMessage(e.getMessage()); }
        }).show();
    }
    private void addLayer() { try { onOperation(new JSONObject().put("type", "add_layer").put("layer", new JSONObject().put("id", UUID.randomUUID().toString()).put("name", "Layer " + (layerIds.size() + 1)))); } catch (Exception e) { onMessage(e.getMessage()); } }
    private void layerDialog() {
        if(document==null)return;
        try {
            JSONObject layer = SketchDocument.layer(document, canvas.getLayerId()); LinearLayout form = column(); EditText name = input("Layer name", layer.getString("name"), false), opacity = input("Opacity 0..1", String.valueOf(layer.getDouble("opacity")), true);
            CheckBox visible = new CheckBox(this); visible.setText("Visible"); visible.setChecked(layer.getBoolean("visible")); CheckBox locked = new CheckBox(this); locked.setText("Locked"); locked.setChecked(layer.getBoolean("locked"));
            form.addView(name); form.addView(opacity);visible.setTextColor(Color.WHITE);locked.setTextColor(Color.WHITE); form.addView(visible); form.addView(locked);Spinner blend=new Spinner(this);String[] blends={"normal","multiply","screen","add"};blend.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,blends));blend.setSelection(java.util.Arrays.asList(blends).indexOf(layer.optString("blendMode","normal")));form.addView(blend);
            new AlertDialog.Builder(this).setTitle("Layer settings").setView(form).setNegativeButton("Cancel", null).setNeutralButton("Delete layer", (d,w) -> { try { onOperation(new JSONObject().put("type", "delete_layer").put("layerId", canvas.getLayerId())); } catch (Exception e) { onMessage(e.getMessage()); } })
                    .setPositiveButton("Apply", (d,w) -> { try { onOperation(new JSONObject().put("type", "update_layer").put("layerId", canvas.getLayerId()).put("patch", new JSONObject().put("name", name.getText().toString()).put("opacity", Double.parseDouble(opacity.getText().toString())).put("visible", visible.isChecked()).put("locked", locked.isChecked()).put("blendMode",blend.getSelectedItem().toString()))); } catch (Exception e) { onMessage(e.getMessage()); } }).show();
        } catch (Exception e) { onMessage(e.getMessage()); }
    }
    private void reorderLayer(int direction) { try { int index = layerIds.indexOf(canvas.getLayerId()), target = Math.max(0, Math.min(layerIds.size() - 1, index + direction)); onOperation(new JSONObject().put("type", "reorder_layer").put("layerId", canvas.getLayerId()).put("index", target)); } catch (Exception e) { onMessage(e.getMessage()); } }

    private void transformDialog() {
        if(document==null)return;
        try {
            String objectId = canvas.getSelectedId(); JSONObject item = objectId == null ? SketchDocument.layer(document, canvas.getLayerId()) : SketchDocument.object(document, objectId);
            JSONObject current = SketchDocument.transformAt(item, canvas.getFrame()); LinearLayout form = column();
            String[] keys = {"x","y","scaleX","scaleY","rotation","opacity"}; String[] labels = {"X translation in pixels","Y translation in pixels","X scale","Y scale","Rotation in degrees","Opacity 0..1"}; EditText[] values = new EditText[keys.length];
            for (int i = 0; i < keys.length; i++) { double value = current.getDouble(keys[i]); if (i == 0) value *= document.getInt("width"); if (i == 1) value *= document.getInt("height"); values[i] = input(labels[i], String.valueOf(value), true); form.addView(values[i]); }
            CheckBox key = new CheckBox(this); key.setText("Set a transform key at current frame"); key.setChecked(item.getJSONArray("keys").length() > 0); form.addView(key);
            CheckBox hold = new CheckBox(this); hold.setText("Hold until next key"); form.addView(hold);
            new AlertDialog.Builder(this).setTitle(objectId == null ? "Layer transform" : "Object transform").setView(form).setNegativeButton("Cancel", null)
                    .setNeutralButton("Remove key", (d,w) -> { try { onOperation(targetOperation("set_keyframe", objectId).put("frame", canvas.getFrame()).put("remove", true)); } catch (Exception e) { onMessage(e.getMessage()); } })
                    .setPositiveButton("Apply", (d,w) -> { try { JSONObject transform = new JSONObject(); for (int i = 0; i < keys.length; i++) { double value = Double.parseDouble(values[i].getText().toString()); if (i == 0) value /= document.getInt("width"); if (i == 1) value /= document.getInt("height"); transform.put(keys[i], value); }
                        JSONObject operation = targetOperation(key.isChecked() ? "set_keyframe" : "set_transform", objectId).put("transform", transform);
                        if (key.isChecked()) operation.put("frame", canvas.getFrame()).put("interpolation", hold.isChecked() ? "hold" : "linear"); onOperation(operation);
                    } catch (Exception e) { onMessage(e.getMessage()); } }).show();
        } catch (Exception e) { onMessage(e.getMessage()); }
    }
    private JSONObject targetOperation(String type, String objectId) throws Exception { return new JSONObject().put("type", type).put(objectId == null ? "layerId" : "objectId", objectId == null ? canvas.getLayerId() : objectId); }
    private void textDialog(){
        if(document==null||canvas==null)return;
        try{String selected=canvas.getSelectedId();JSONObject existing=selected==null?null:SketchDocument.object(document,selected);boolean edit=existing!=null&&"text".equals(existing.getString("kind"));
            LinearLayout form=column();EditText words=input("Text",edit?existing.getString("text"):"Your lettering",false);words.setSingleLine(false);words.setMinLines(2);form.addView(words);
            Spinner fonts=new Spinner(this),styles=new Spinner(this),alignment=new Spinner(this);fonts.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,SketchText.FAMILIES));styles.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,SketchText.STYLES));alignment.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"left","center","right"}));form.addView(fonts);form.addView(styles);form.addView(alignment);
            if(edit){fonts.setSelection(java.util.Arrays.asList(SketchText.FAMILIES).indexOf(existing.optString("fontFamily","sans-serif")));styles.setSelection(java.util.Arrays.asList(SketchText.STYLES).indexOf(existing.optString("fontStyle","normal")));alignment.setSelection(java.util.Arrays.asList("left","center","right").indexOf(existing.optString("textAlign","left")));}
            EditText size=input("Font size as canvas height (.005.. .3)",edit?String.valueOf(existing.optDouble("fontSize",.04)):".04",true),spacing=input("Letter spacing (-.1.. .5)",edit?String.valueOf(existing.optDouble("letterSpacing",0)):"0",true);form.addView(size);form.addView(spacing);
            new AlertDialog.Builder(this).setTitle(edit?"Edit lettering":"Add lettering").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{try{JSONObject values=new JSONObject().put("text",words.getText().toString()).put("fontFamily",fonts.getSelectedItem().toString()).put("fontStyle",styles.getSelectedItem().toString()).put("textAlign",alignment.getSelectedItem().toString()).put("fontSize",Double.parseDouble(size.getText().toString())).put("letterSpacing",Double.parseDouble(spacing.getText().toString())).put("color",brushColor);
                if(edit)onOperation(new JSONObject().put("type","update_object").put("objectId",selected).put("patch",values));else{String id=UUID.randomUUID().toString();values.put("id",id).put("kind","text").put("frame",canvas.getFrame()).put("exposure",1).put("bounds",new JSONObject().put("x",.1).put("y",.4).put("width",.8).put("height",.2));onOperation(new JSONObject().put("type","add_object").put("layerId",canvas.getLayerId()).put("object",values));canvas.selectObject(id);}}
                catch(Exception error){onMessage(error.getMessage());}}).show();
        }catch(Exception error){onMessage(error.getMessage());}
    }
    private void exposureDialog() {
        if(document==null)return;
        if(bookId!=null){bookTimingDialog();return;}
        try {
            if (canvas.getSelectedId() == null) throw SketchDocument.bad("Select an object first"); JSONObject object = SketchDocument.object(document, canvas.getSelectedId()); LinearLayout form = column();
            EditText start = input("First frame (1-based)", String.valueOf(object.getInt("frame") + 1), true), exposure = input("Exposure in frames", String.valueOf(object.getInt("exposure")), true); form.addView(start); form.addView(exposure);
            new AlertDialog.Builder(this).setTitle("Cel exposure").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Apply", (d,w) -> { try { onOperation(new JSONObject().put("type", "set_exposure").put("objectId", canvas.getSelectedId()).put("frame", Integer.parseInt(start.getText().toString()) - 1).put("exposure", Integer.parseInt(exposure.getText().toString()))); } catch (Exception e) { onMessage(e.getMessage()); } }).show();
        } catch (Exception e) { onMessage(e.getMessage()); }
    }
    private void duplicateCel() {
        if(document==null)return;
        if(bookId!=null){int next=bookPage+1;if(next>=book.optInt("pageCount")){onMessage("This is the last book page");return;}String source=sketchId;afterSaves(()->{JSONObject page=SketchEditOps.execute(this,"sketch_book_open_page",new JSONObject().put("bookId",bookId).put("page",next));JSONObject from,to;try(SketchProjectStore store=new SketchProjectStore(this)){from=store.get(source);to=store.get(page.getString("sketchId"));if(hasArtwork(to))throw SketchDocument.bad("Destination page already has artwork; open it to edit rather than overwrite");store.copyDrawingToPage(from,to);}postUi(()->openBookPage(bookId,next));});return;}
        int next=canvas.getFrame()+1;try{onOperation(new JSONObject().put("type","duplicate_cel").put("layerId",canvas.getLayerId()).put("fromFrame",canvas.getFrame()).put("toFrame",next).put("idPrefix",UUID.randomUUID().toString()));setFrame(next);}catch(Exception error){onMessage(error.getMessage());}
    }
    private void deleteObject() { try { if (canvas.getSelectedId() == null) throw SketchDocument.bad("Select an object first"); onOperation(new JSONObject().put("type", "delete_object").put("objectId", canvas.getSelectedId())); } catch (Exception e) { onMessage(e.getMessage()); } }
    private void referenceDialog() {
        if(document==null)return;
        try {
            ProjectStore projects = new ProjectStore(this); ArrayList<ProjectStore.Project> owners = new ArrayList<>(); ArrayList<ProjectStore.Asset> assets = new ArrayList<>(); ArrayList<String> labels = new ArrayList<>();
            for (ProjectStore.Project project : projects.list()) for (ProjectStore.Asset asset : project.assets) if (asset.mime != null && asset.mime.startsWith("image/")) { owners.add(project); assets.add(asset); labels.add(project.name + " / " + asset.name); }
            if (assets.isEmpty()) throw SketchDocument.bad("Import an image into a VideoStudio project first");
            new AlertDialog.Builder(this).setTitle("Copy a project reference image").setItems(labels.toArray(new String[0]), (d,index) -> {
                try {
                    SketchEditorSession active=session;if(active==null)throw SketchDocument.bad("Open a drawing before importing its guide");
                    String target=sketchId;int generation=loadGeneration,page=pageGeneration;
                    ProjectStore.Project project = owners.get(index); JSONObject args = request().put("projectId", project.id).put("sourceProjectUpdatedAt", project.updatedAt).put("assetId", assets.get(index).id)
                        .put("layerId", canvas.getLayerId()).put("objectId", UUID.randomUUID().toString()).put("frame", canvas.getFrame()).put("exposure", document.getInt("frameCount") - canvas.getFrame()).put("guideVisible",false);
                    afterSaves(()->{
                        args.put("expectedRevision",active.document().getLong("revision"));
                        // Visibility commits with the reference itself: an interruption
                        // cannot leave a completed photograph masquerading as artwork.
                        SketchEditOps.execute(this,"sketch_import_reference",args);
                        postUi(()->{if(session==active&&generation==loadGeneration&&page==pageGeneration&&target.equals(sketchId))reload();});
                    });
                } catch (Exception e) { onMessage(e.getMessage()); }
            }).show();
        } catch (Exception e) { onMessage(e.getMessage()); }
    }

    private static boolean hasArtwork(JSONObject doc) throws Exception {JSONArray layers=doc.getJSONArray("layers");for(int i=0;i<layers.length();i++)if(layers.getJSONObject(i).getJSONArray("objects").length()>0)return true;return false;}
    private void workspaceDialog(){
        if(document==null)return;String[] choices={"Fit canvas","Grid guides","Symmetry drawing","Onion skin settings","Live ChatGPT drawing","Reference crop / tracing","Recover local draft as a separate sketch","Focus selected object","Import drawing packet","Drawing content audit","ChatGPT hand","2D animation"};
        new AlertDialog.Builder(this).setTitle("Drawing workspace").setItems(choices,(d,index)->{try{switch(index){case 0:canvas.resetView();break;case 1:grid=!grid;canvas.setGuides(grid,symmetry);getPreferences(MODE_PRIVATE).edit().putBoolean("grid",grid).apply();break;case 2:symmetryDialog();break;case 3:onionDialog();break;case 4:toggleLiveWatch();break;case 5:referenceTools();break;case 6:String source=sketchId;io.execute(()->{try{if(session!=null)session.awaitIdle(15000);String recovered=SketchEditorSession.recoverAsCopy(this,source);postUi(()->{bookId=null;book=null;loadSketch(recovered,0);});}catch(Exception error){postUi(()->onMessage(error.getMessage()));}});break;case 7:canvas.focusSelection();break;case 8:importDrawingDialog();break;case 9:inspectDrawing();break;case 10:boolean hand=!getPreferences(MODE_PRIVATE).getBoolean("agentHand",true);getPreferences(MODE_PRIVATE).edit().putBoolean("agentHand",hand).apply();canvas.setAgentHandEnabled(hand);updateAgentToolIndicator();onMessage(hand?"ChatGPT hand visible":"ChatGPT hand hidden");break;case 11:runAction("2D animation",this::openAnimationStudio);break;}}catch(Exception error){onMessage(error.getMessage());}}).show();
    }
    private void symmetryDialog(){
        try{
            JSONObject previous=symmetrySettings==null?SketchSymmetry.configuration(new JSONObject().put("mode","vertical")):symmetrySettings;
            LinearLayout form=column();CheckBox enabled=new CheckBox(this);enabled.setText("Enable vector symmetry");enabled.setTextColor(StudioUi.TEXT);enabled.setChecked(symmetry);enabled.setMinimumHeight(dp(48));form.addView(enabled);
            Spinner mode=new Spinner(this);String[] modes={"vertical","horizontal","both","radial"};mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes));mode.setContentDescription("Symmetry mode");mode.setMinimumHeight(dp(48));mode.setSelection(java.util.Arrays.asList(modes).indexOf(previous.getString("mode")));form.addView(mode);
            EditText centerX=input("Center X 0..1",String.valueOf(previous.getDouble("centerX")),true),centerY=input("Center Y 0..1",String.valueOf(previous.getDouble("centerY")),true),segments=input("Radial sectors 2..8",String.valueOf(previous.optInt("segments",4)),true);
            form.addView(centerX);form.addView(centerY);form.addView(segments);segments.setEnabled("radial".equals(previous.getString("mode")));
            mode.setOnItemSelectedListener(new SimpleSelection(){@Override public void selected(int position){segments.setEnabled(position==3);}});
            TextView explanation=new TextView(this);explanation.setText("Pen and vector eraser only. The center follows the selected layer. Every reflected point must stay inside its canvas.");explanation.setTextColor(StudioUi.MUTED);form.addView(explanation);
            ScrollView scroll=new ScrollView(this);scroll.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Symmetry drawing").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
                JSONObject requested=new JSONObject().put("mode",mode.getSelectedItem().toString()).put("centerX",Double.parseDouble(centerX.getText().toString())).put("centerY",Double.parseDouble(centerY.getText().toString()));
                if("radial".equals(requested.getString("mode")))requested.put("segments",Integer.parseInt(segments.getText().toString()));
                JSONObject next=SketchSymmetry.configuration(requested);canvas.setSymmetry(next);canvas.setGuides(grid,enabled.isChecked());symmetry=enabled.isChecked();symmetrySettings=next;
                getPreferences(MODE_PRIVATE).edit().putString("symmetrySettings",next.toString()).putBoolean("symmetry",symmetry).apply();dialog.dismiss();
            }catch(Exception invalid){onMessage(invalid.getMessage());}});
        }catch(Exception invalid){onMessage(invalid.getMessage());}
    }
    private void applyCanvasFocus(){for(View view:drawingChrome)view.setVisibility(canvasFocus?View.GONE:View.VISIBLE);}
    private void toggleCanvasFocus(){canvasFocus=!canvasFocus;getPreferences(MODE_PRIVATE).edit().putBoolean("canvasFocus",canvasFocus).apply();applyCanvasFocus();}
    private void importDrawingDialog(){
        new AlertDialog.Builder(this).setTitle("Import drawing packet")
                .setItems(new String[]{"Imported project files","Linked workspace folder"},(d,index)->{
                    if(index==0)projectDrawingImportDialog();else linkedDrawingImportDialog();
                }).setNegativeButton("Cancel",null).show();
    }
    private void linkedDrawingImportDialog(){
        LinearLayout form=column();EditText manifest=input("JSONL in your linked workspace","drawing.jsonl",false),source=input("Source image, video or prompt TXT in that folder","reference.png",false),count=input("Ordered frame count (1..1200)","1",true);form.addView(manifest);form.addView(source);form.addView(count);
        TextView method=new TextView(this);method.setText("Linked packets retain source-assisted drawing provenance.");method.setTextColor(StudioUi.MUTED);form.addView(method);
        new AlertDialog.Builder(this).setTitle("Import linked drawing packet").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Import",(d,w)->{try{String manifestName=manifest.getText().toString(),sourceName=source.getText().toString();int frames=Integer.parseInt(count.getText().toString());afterSaves(()->{
            JSONObject header;try(java.io.InputStream in=new DriveWorkspaceProvider(this).openLinkedSourceFile(manifestName)){header=drawingPacketHeader(in,manifestName.endsWith(".gz"));}
            String drawingId=SketchDocument.id(header,"targetSketchId"),hash=SketchFrameManifestImporter.sourceHash(this,sourceName);JSONObject args=new JSONObject().put("sketchId",drawingId).put("manifestName",manifestName).put("videoName",sourceName).put("expectedSourceSha256",hash).put("expectedFrameCount",frames);SketchFrameManifestImporter.importFromWorkspace(this,args,(done,total)->postUi(()->status.setText("Importing editable frames · "+done+"/"+total)));postUi(()->{bookId=null;book=null;loadSketch(drawingId,0);});});}catch(Exception error){onMessage(error.getMessage());}}).show();
    }
    private static final class DrawingImportProject {
        final ProjectStore.Project project;
        final ArrayList<ProjectStore.Asset> packets=new ArrayList<>(),sources=new ArrayList<>();
        DrawingImportProject(ProjectStore.Project project){this.project=project;}
    }
    private java.io.File drawingImportFile(ProjectStore.Asset asset,long maximum)throws Exception {
        android.net.Uri uri=android.net.Uri.parse(asset.uri);
        if(!"file".equals(uri.getScheme())||uri.getPath()==null||uri.getQuery()!=null||uri.getFragment()!=null
                ||uri.getAuthority()!=null&&!uri.getAuthority().isEmpty())throw SketchDocument.bad("Choose an imported private project file");
        java.io.File input=new java.io.File(uri.getPath()),file=input.getCanonicalFile();
        if(!file.getPath().startsWith(getFilesDir().getCanonicalPath()+java.io.File.separator)
                ||java.nio.file.Files.isSymbolicLink(input.toPath())||!file.isFile()||file.length()<1
                ||file.length()>maximum||file.length()!=asset.sizeBytes)throw SketchDocument.bad("Imported project file is unavailable or changed");
        return file;
    }
    private void projectDrawingImportDialog(){
        afterSaves(()->{
            ArrayList<DrawingImportProject> choices=new ArrayList<>();
            for(ProjectStore.Project project:new ProjectStore(this).list()){
                DrawingImportProject candidate=new DrawingImportProject(project);
                for(ProjectStore.Asset asset:project.assets){
                    if(asset.name==null||!asset.name.matches("[A-Za-z0-9_.-]{1,120}")||asset.name.contains(".."))continue;
                    boolean packet=asset.name.endsWith(".jsonl")||asset.name.endsWith(".jsonl.gz"),source=asset.name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|png|jpg|jpeg|webp|txt)");
                    if(!packet&&!source)continue;
                    try{drawingImportFile(asset,packet?4L*1024*1024+65536:256L*1024*1024);if(packet)candidate.packets.add(asset);else candidate.sources.add(asset);}catch(Exception unavailable){}
                }
                if(!candidate.packets.isEmpty()&&!candidate.sources.isEmpty())choices.add(candidate);
            }
            postUi(()->{
                if(choices.isEmpty()){onMessage("Import a drawing packet and its source into a VideoStudio project first.");return;}
                String[] names=new String[choices.size()];for(int i=0;i<names.length;i++)names[i]=choices.get(i).project.name;
                new AlertDialog.Builder(this).setTitle("Choose source project").setItems(names,(d,index)->chooseProjectDrawingPacket(choices.get(index))).setNegativeButton("Cancel",null).show();
            });
        });
    }
    private void chooseProjectDrawingPacket(DrawingImportProject selected){
        String[] names=new String[selected.packets.size()];for(int i=0;i<names.length;i++)names[i]=selected.packets.get(i).name;
        new AlertDialog.Builder(this).setTitle("Choose drawing packet").setItems(names,(d,index)->{
            ProjectStore.Asset packet=selected.packets.get(index);String[] sources=new String[selected.sources.size()];for(int i=0;i<sources.length;i++)sources[i]=selected.sources.get(i).name;
            new AlertDialog.Builder(this).setTitle("Choose drawing source").setItems(sources,(sourceDialog,sourceIndex)->afterSaves(()->{
                JSONObject header;try(java.io.InputStream in=new java.io.FileInputStream(drawingImportFile(packet,4L*1024*1024+65536))){header=drawingPacketHeader(in,packet.name.endsWith(".gz"));}
                postUi(()->drawingAssetSettings(selected,packet,selected.sources.get(sourceIndex),header));
            })).setNegativeButton("Cancel",null).show();
        }).setNegativeButton("Cancel",null).show();
    }
    private static JSONObject drawingPacketHeader(java.io.InputStream input,boolean gzip)throws Exception {
        byte[] bytes=SketchFrameManifestImporter.readPacket(input,gzip);
        String first=new java.io.BufferedReader(new java.io.StringReader(new String(bytes,java.nio.charset.StandardCharsets.UTF_8))).readLine();
        if(first==null)throw SketchDocument.bad("Drawing packet is empty");JSONObject header=new JSONObject(first).getJSONObject("header");
        if(!"videostudio.sketch.frame-ops.v1".equals(header.getString("schema")))throw SketchDocument.bad("Choose a supported drawing packet");
        SketchDocument.id(header,"targetSketchId");SketchDocument.integer(header,"frameCount",1,1200);return header;
    }
    private String drawingAssetHash(ProjectStore.Asset asset,long maximum)throws Exception {
        try(java.io.InputStream input=new java.io.FileInputStream(drawingImportFile(asset,maximum))){return SketchFrameManifestImporter.sourceHash(input);}
    }
    private void drawingAssetSettings(DrawingImportProject selected,ProjectStore.Asset packet,ProjectStore.Asset source,JSONObject header){
        LinearLayout form=column();TextView files=new TextView(this);files.setText(packet.name+"\nSource: "+source.name);files.setTextColor(StudioUi.TEXT);form.addView(files);
        EditText count=input("Drawing packet frame count",String.valueOf(header.optInt("frameCount")),true);count.setInputType(InputType.TYPE_CLASS_NUMBER);form.addView(count);
        Spinner method=new Spinner(this);method.setContentDescription("Drawing method");method.setMinimumHeight(dp(48));
        method.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Choose drawing method","Source assisted","Original strokes"}));form.addView(method);
        TextView explanation=new TextView(this);explanation.setText("Confirm the ordered frame count (1..1200). Choose Original strokes only for artwork authored without tracing or copying a reference.");explanation.setTextColor(StudioUi.MUTED);form.addView(explanation);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Import editable drawing").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Import",null).create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            int frames=Integer.parseInt(count.getText().toString());if(frames<1||frames>1200)throw SketchDocument.bad("Frame count must be 1..1200");
            int choice=method.getSelectedItemPosition();if(choice<1)throw SketchDocument.bad("Choose the drawing method before importing");
            String declared=choice==2?SketchDrawingPolicy.ORIGINAL:SketchDrawingPolicy.ASSISTED,drawingId=SketchDocument.id(header,"targetSketchId");
            SketchEditorSession opened=session;int generation=loadGeneration;stopPlayback();dialog.dismiss();status.setText("Verifying drawing packet…");
            afterSaves(()->{
                String packetHash=drawingAssetHash(packet,4L*1024*1024+65536),sourceHash=drawingAssetHash(source,256L*1024*1024);
                JSONObject request=new JSONObject().put("projectId",selected.project.id).put("sourceProjectUpdatedAt",selected.project.updatedAt)
                        .put("sketchId",drawingId).put("manifestAssetId",packet.id).put("expectedManifestSha256",packetHash)
                        .put("sourceAssetId",source.id).put("expectedSourceSha256",sourceHash).put("expectedFrameCount",frames).put("drawingMethod",declared);
                SketchFrameManifestImporter.importFromProjectAssets(this,new ProjectStore(this),request,(done,total)->postUi(()->status.setText("Importing editable frames · "+done+"/"+total)));
                postUi(()->{if(session!=opened||loadGeneration!=generation||session!=null&&session.pending()>0){onMessage("Drawing imported. Find it in File → Open.");return;}bookId=null;book=null;loadSketch(drawingId,0);});
            });
        }catch(Exception error){onMessage(error.getMessage());}});
    }
    private void inspectDrawing(){
        if(document==null)return;try{JSONObject args=new JSONObject().put("sketchId",sketchId).put("expectedRevision",document.getLong("revision")).put("frameOffset",Math.max(0,canvas.getFrame()-1)).put("frameLimit",32);
            afterSaves(()->{JSONObject report=SketchEditOps.execute(this,"sketch_inspect",args);StringBuilder message=new StringBuilder("Saved drawing: ").append(report.getInt("frameCount")).append(" frames · ").append(report.getJSONObject("usage").getInt("objects")).append(" objects\n");JSONArray rows=report.getJSONArray("frames");for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);message.append("Frame ").append(row.getInt("frame")+1).append(": ").append(row.getInt("exposedObjects")).append(" exposed marks");if(!row.getBoolean("contentChanged"))message.append(" · same content");message.append('\n');}message.append("\nContent counts do not certify likeness or articulated motion.");postUi(()->new AlertDialog.Builder(this).setTitle("Drawing content audit").setMessage(message.toString()).setPositiveButton("OK",null).show());});
        }catch(Exception error){onMessage(error.getMessage());}
    }
    private void onionDialog(){
        LinearLayout form=column();CheckBox enabled=new CheckBox(this);enabled.setText("Show onion skin");enabled.setTextColor(StudioUi.TEXT);enabled.setChecked(onionEnabled);form.addView(enabled);
        EditText distance=input(bookId==null?"Frames on each side (1..3)":"Book shows the adjacent page on each side",String.valueOf(onionRange),true),opacity=input("Onion opacity (0..128)",String.valueOf(onionAlpha),true);distance.setEnabled(bookId==null);form.addView(distance);form.addView(opacity);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Red previous / blue next").setView(form).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int range=Integer.parseInt(distance.getText().toString()),alpha=Integer.parseInt(opacity.getText().toString());if(range<1||range>3||alpha<0||alpha>128)throw new IllegalArgumentException("Choose 1..3 onion frames and opacity 0..128");onionEnabled=enabled.isChecked();onionRange=range;onionAlpha=alpha;canvas.setOnion(onionEnabled);canvas.setOnionOptions(range,alpha);saveOnionOptions();dialog.dismiss();}catch(Exception error){onMessage(error.getMessage());}});
    }
    private void saveOnionOptions(){getPreferences(MODE_PRIVATE).edit().putBoolean("onionEnabled",onionEnabled).putInt("onionRange",onionRange).putInt("onionAlpha",onionAlpha).apply();}
    private void celDialog(){if(document==null)return;String[] choices=bookId==null?new String[]{"Duplicate drawing to next cel","Insert blank frame here","Delete current frame","Clear current layer cel"}:new String[]{"Duplicate drawing to next page","Clear current layer"};new AlertDialog.Builder(this).setTitle("Flipbook cels").setItems(choices,(d,index)->{try{if(index==0){duplicateCel();return;}if(bookId!=null){onOperation(new JSONObject().put("type","clear_cel").put("layerId",canvas.getLayerId()).put("frame",0));return;}String kind=index==1?"insert_frame":index==2?"delete_frame":"clear_cel";JSONObject op=new JSONObject().put("type",kind).put("frame",canvas.getFrame());if(index==3)op.put("layerId",canvas.getLayerId());onOperation(op);setFrame(canvas.getFrame());}catch(Exception error){onMessage(error.getMessage());}}).show();}
    private void referenceTools(){
        if(document==null)return;
        try{
            String selected=canvas.getSelectedId();
            if(selected!=null&&"image".equals(SketchDocument.object(document,selected).getString("kind"))){referenceTools(selected);return;}
            ArrayList<String> ids=new ArrayList<>(),labels=new ArrayList<>();JSONArray layers=document.getJSONArray("layers");
            for(int n=0;n<layers.length();n++){JSONObject layer=layers.getJSONObject(n);JSONArray objects=layer.getJSONArray("objects");for(int k=0;k<objects.length();k++){JSONObject object=objects.getJSONObject(k);if(!"image".equals(object.getString("kind")))continue;ids.add(object.getString("id"));labels.add(layer.getString("name")+" · reference "+ids.size()+(object.getJSONObject("transform").getDouble("opacity")==0?" (hidden)":""));}}
            if(ids.isEmpty()){onMessage("Import a reference image from Brush / Text first");return;}
            if(ids.size()==1){canvas.selectObject(ids.get(0));referenceTools(ids.get(0));return;}
            new AlertDialog.Builder(this).setTitle("Choose a reference guide").setItems(labels.toArray(new String[0]),(dialog,index)->{canvas.selectObject(ids.get(index));referenceTools(ids.get(index));}).show();
        }catch(Exception error){onMessage(error.getMessage());}
    }
    private void referenceTools(String objectId){
        try{
            JSONObject image=SketchDocument.object(document,objectId),crop=image.optJSONObject("referenceCrop");LinearLayout form=column();String[] names={"x","y","width","height"};EditText[] fields=new EditText[4];
            for(int i=0;i<4;i++){fields[i]=input("Reference crop "+names[i]+" (0..1)",String.valueOf(crop==null?(i<2?0:1):crop.getDouble(names[i])),true);form.addView(fields[i]);}
            EditText alpha=input("Reference opacity 0..1",String.valueOf(image.getJSONObject("transform").getDouble("opacity")),true),threshold=input("Edge trace threshold .01..1","0.15",true);form.addView(alpha);form.addView(threshold);
            Spinner traceMode=new Spinner(this);traceMode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Colour regions (editable fills)","Edge strokes"}));form.addView(traceMode);
            EditText resolution=input("Trace detail (32..384)","192",true),levels=input("Colour levels (2..32)","8",true);form.addView(resolution);form.addView(levels);
            CheckBox guide=new CheckBox(this);guide.setText("Show the reference guide while tracing");form.addView(guide);
            ScrollView scroll=new ScrollView(this);scroll.addView(form,new ScrollView.LayoutParams(-1,-2));
            new AlertDialog.Builder(this).setTitle("Crop, fade and trace owned reference").setView(scroll).setNegativeButton("Cancel",null)
                    .setPositiveButton("Apply crop / opacity",(dialog,which)->runAction("Apply reference settings",()->{try{JSONObject value=new JSONObject();for(int i=0;i<4;i++)value.put(names[i],Double.parseDouble(fields[i].getText().toString()));apply(new JSONArray().put(new JSONObject().put("type","update_object").put("objectId",objectId).put("patch",new JSONObject().put("referenceCrop",value))).put(new JSONObject().put("type","set_transform").put("objectId",objectId).put("transform",new JSONObject().put("opacity",Double.parseDouble(alpha.getText().toString())))));}catch(Exception error){onMessage(error.getMessage());}}))
                    .setNeutralButton("Trace to editable layer",(dialog,which)->runAction("Trace reference",()->{try{
                        JSONObject trace=new JSONObject().put("type","trace_reference").put("objectId",objectId).put("layerId",UUID.randomUUID().toString()).put("frame",canvas.getFrame()).put("threshold",Double.parseDouble(threshold.getText().toString())).put("idPrefix",UUID.randomUUID().toString()).put("mode",traceMode.getSelectedItemPosition()==0?"colour_regions":"edges").put("maxDimension",Integer.parseInt(resolution.getText().toString())).put("colourLevels",Integer.parseInt(levels.getText().toString()));
                        traceReference(trace,guide.isChecked());
                    }catch(Exception error){onMessage(error.getMessage());}})).show();
        }catch(Exception error){onMessage(error.getMessage());}
    }
    /** Trace geometry uses the same durable operation stream as agent-authored marks.
     * A hidden guide is persisted before the snapshot so replay starts with artwork,
     * never with the photograph that the user asked the studio to draw. */
    private void traceReference(JSONObject trace,boolean keepGuide){
        if(session==null||loadingDrawing)return;
        SketchEditorSession active=session;String id=sketchId;int generation=loadGeneration,page=pageGeneration,liveGeneration=++liveLoadGeneration;
        loadingDrawing=true;stopLivePlayer();
        // Expose takeover while preparation is still running, rather than only
        // after the worker finishes and begins replaying the accepted marks.
        watching=true;getPreferences(MODE_PRIVATE).edit().putBoolean("watchLiveDrawing",true).apply();updateLiveControl();
        canvas.setEnabled(false);status.setText("Preparing editable reference drawing…");
        io.execute(()->{
            String streamId=null;
            try{
                active.awaitIdle(15000);if(active.error()!=null)throw new IllegalStateException(active.error());
                JSONObject current;try(SketchProjectStore store=new SketchProjectStore(this)){current=store.get(id);}
                SketchProjectStore.requireRevision(current,active.document().getLong("revision"));
                // Keyframes override the base transform. Change guide opacity
                // across its whole animation atomically, preserving every pose.
                SketchEditOps.execute(this,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId",UUID.randomUUID().toString()).put("expectedRevision",current.getLong("revision"))
                        .put("operations",new JSONArray().put(new JSONObject().put("type","set_reference_visibility").put("objectId",trace.getString("objectId")).put("visible",keepGuide))));
                try(SketchProjectStore store=new SketchProjectStore(this)){current=store.get(id);}
                JSONObject baseline=current;
                streamId=UUID.randomUUID().toString();SketchEditOps.execute(this,"sketch_live_begin",new JSONObject().put("sketchId",id).put("operationId",streamId).put("expectedRevision",baseline.getLong("revision")));
                postUi(()->{if(session!=active||generation!=loadGeneration||page!=pageGeneration||liveGeneration!=liveLoadGeneration)return;try{if(active.adopt(baseline))present(active.document());status.setText("Preparing editable reference drawing…");}catch(Exception error){onMessage(error.getMessage());}});
                JSONArray operations=new JSONArray().put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",trace.getString("layerId")).put("name","colour_regions".equals(trace.getString("mode"))?"Reference colour regions":"Reference contours"))).put(trace);
                JSONObject result=SketchEditOps.execute(this,"sketch_edit",new JSONObject().put("sketchId",id).put("operationId",UUID.randomUUID().toString()).put("expectedRevision",baseline.getLong("revision")).put("operations",operations));
                SketchEditOps.execute(this,"sketch_live_end",new JSONObject().put("sketchId",id).put("sessionId",streamId).put("expectedRevision",result.getLong("revision")));
                String completedStream=streamId;
                postUi(()->{if(session!=active||generation!=loadGeneration||page!=pageGeneration||liveGeneration!=liveLoadGeneration)return;loadingDrawing=false;watching=true;getPreferences(MODE_PRIVATE).edit().putBoolean("watchLiveDrawing",true).apply();updateLiveControl();startLivePlayer(completedStream);});
            }catch(Exception|OutOfMemoryError error){
                if(streamId!=null)try(SketchProjectStore store=new SketchProjectStore(this)){store.endLiveSession(id,streamId,store.get(id).getLong("revision"));}catch(Exception ignored){}
                postUi(()->{if(session!=active||generation!=loadGeneration||page!=pageGeneration||liveGeneration!=liveLoadGeneration)return;loadingDrawing=false;onMessage(error.getMessage());reload();});
            }
        });
    }
    private void booksDialog(){stopPlayback();io.execute(()->{try{JSONArray books=SketchEditOps.execute(this,"sketch_book_list",new JSONObject()).getJSONArray("books");String[] names=new String[books.length()+1];names[0]="New sketch book · up to 10000 pages";for(int i=0;i<books.length();i++)names[i+1]=books.getJSONObject(i).getString("name")+" · "+books.getJSONObject(i).getInt("pageCount")+" pages";postUi(()->new AlertDialog.Builder(this).setTitle("Paged animation books").setItems(names,(d,index)->{try{if(index==0)newBookDialog();else openBookPage(books.getJSONObject(index-1).getString("bookId"),0);}catch(Exception error){onMessage(error.getMessage());}}).show());}catch(Exception error){postUi(()->onMessage(error.getMessage()));}});}
    private void newBookDialog(){LinearLayout form=column();EditText name=input("Book name","Untitled animation book",false),count=input("Pages 1..10000","1000",true),fps=input("FPS 24, 30 or 60","24",true);form.addView(name);form.addView(count);form.addView(fps);new AlertDialog.Builder(this).setTitle("New frame-by-frame sketch book").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Create",(d,w)->{try{JSONObject args=new JSONObject().put("bookId",UUID.randomUUID().toString()).put("operationId",UUID.randomUUID().toString()).put("name",name.getText().toString()).put("pageCount",Integer.parseInt(count.getText().toString())).put("fps",Integer.parseInt(fps.getText().toString()));afterSaves(()->{SketchEditOps.execute(this,"sketch_book_create",args);postUi(()->openBookPage(args.optString("bookId"),0));});}catch(Exception error){onMessage(error.getMessage());}}).show();}
    private void openBookPage(String id,int page){if(destroyed)return;stopPlayback();loadingDrawing=true;canvas.setEnabled(false);if(session!=null&&session.pending()>0){afterSaves(()->postUi(()->openBookPage(id,page)));return;}int generation=++pageGeneration;io.execute(()->{try{JSONObject result=SketchEditOps.execute(this,"sketch_book_open_page",new JSONObject().put("bookId",id).put("page",page));postUi(()->{if(destroyed||generation!=pageGeneration)return;bookId=id;book=result.optJSONObject("book");bookPage=page;loadSketch(result.optString("sketchId"),0);});}catch(Exception error){postUi(()->onMessage(error.getMessage()));}});}
    private void selectFilmstrip(int index){if(document==null)return;if(bookId==null)setFrame(index);else if(index>=0&&index<book.optInt("pageCount")&&index!=bookPage)openBookPage(bookId,index);}
    private void playBookTick(){
        if(book==null)return;if(bookPlaybackInFlight){handler.postDelayed(playTick,16);return;}
        JSONObject playingBook=book;int fps=playingBook.optInt("fps"),count=playingBook.optInt("pageCount"),page=(bookPage+1)%count,generation=playbackGeneration,pageAtRequest=pageGeneration;String id=bookId;bookPlaybackInFlight=true;
        io.execute(()->{try{JSONObject loaded;String sketch=id+"_p"+page;try(SketchProjectStore store=new SketchProjectStore(this)){try{loaded=store.get(sketch);}catch(IllegalArgumentException unknown){if(!unknown.getMessage().startsWith("UNKNOWN_SKETCH"))throw unknown;loaded=SketchDocument.create(new JSONObject().put("sketchId",sketch).put("name",playingBook.optString("name")).put("width",playingBook.optInt("width")).put("height",playingBook.optInt("height")).put("fps",fps).put("frameCount",1).put("background",playingBook.optString("background")));}}
            JSONObject drawing=loaded;postUi(()->{if(generation!=playbackGeneration)return;bookPlaybackInFlight=false;if(!playing||destroyed||pageAtRequest!=pageGeneration||!id.equals(bookId))return;try{bookPage=page;canvas.setDocument(drawing);frames.setProgress(page);updateStatus();handler.postDelayed(playTick,NativeFrameClock.durationMs(SketchTimelineTools.exposure(playingBook.optJSONObject("exposures"),page),fps));}catch(Exception error){stopPlayback();onMessage(error.getMessage());}});
        }catch(Exception error){postUi(()->{if(generation!=playbackGeneration)return;bookPlaybackInFlight=false;stopPlayback();onMessage(error.getMessage());});}});
    }
    private void stopLivePlayer(){if(livePlayer!=null){livePlayer.close();livePlayer=null;}canvas.hideAgentHand();agentGlobalTool=false;if(status!=null)status.setCompoundDrawables(null,null,null,null);showAgentToolPosition(humanToolPosition);canvas.setPresentation(null,1);canvas.setEnabled(session!=null&&!loadingDrawing&&!playing&&!destroyed);liveDrawingObject=null;}
    private void showAgentToolPosition(int position){if(toolPicker==null||position<0||position>=toolValues.length||toolPicker.getSelectedItemPosition()==position)return;pendingAgentToolSelection=position;syncingAgentTool=true;try{toolPicker.setSelection(position);}finally{syncingAgentTool=false;}}
    private static int agentToolPosition(String label){
        switch(label){case "Brush":return 11;case "Eraser":return 12;case "Clone brush":return 13;case "Line":return 2;case "Rectangle":return 3;case "Ellipse":return 4;case "Path":return 9;case "Ink":case "Pen":case "Pencil":case "Marker":case "Airbrush":case "Charcoal":case "Calligraphy":return 0;default:return -1;}
    }
    private void showActualAgentTool(String label){int position=agentToolPosition(label);if(position>=0)showAgentToolPosition(position);}
    private void updateAgentToolIndicator(){if(status==null)return;boolean enabled=getPreferences(MODE_PRIVATE).getBoolean("agentHand",true);if(enabled&&agentGlobalTool){if(agentToolIndicator==null)agentToolIndicator=SketchAgentHand.toolIndicator(this,18);status.setCompoundDrawablePadding(dp(4));status.setCompoundDrawablesWithIntrinsicBounds(agentToolIndicator,null,null,null);}else status.setCompoundDrawables(null,null,null,null);}
    private void updateLiveControl(){if(liveButton!=null){liveButton.setText(watching?"Draw myself":"Watch ChatGPT");liveButton.setContentDescription(watching?"Pause live drawing and draw yourself":"Resume watching every drawing step");}}
    private void toggleLiveWatch(){
        if(watching){takeOverLive(null);return;}
        ++liveLoadGeneration;stopPlayback();watching=true;if(notifiedSessionId!=null)notificationReplayPending=true;getPreferences(MODE_PRIVATE).edit().putBoolean("watchLiveDrawing",true).apply();updateLiveControl();
        if(loadingDrawing&&session!=null){if(session.pending()==0)reload();else afterSaves(()->postUi(this::reload));}updateStatus();
    }
    /** A local action pauses presentation, then adopts the authoritative revision.
     * Accepted events remain on disk so watching can resume without discarding steps. */
    private void takeOverLive(Runnable action){
        watching=false;getPreferences(MODE_PRIVATE).edit().putBoolean("watchLiveDrawing",false).apply();updateLiveControl();
        int liveGeneration=++liveLoadGeneration;loadingDrawing=true;stopLivePlayer();
        SketchEditorSession active=session;String id=sketchId;int generation=loadGeneration,page=pageGeneration;int frame=canvas.getFrame();
        if(active==null){updateStatus();return;}
        io.execute(()->{try{
            active.awaitIdle(15000);if(active.error()!=null)throw new IllegalStateException(active.error());
            JSONObject current;try(SketchProjectStore store=new SketchProjectStore(this)){current=store.takeOverDrawing(id);}
            postUi(()->{if(!resumed||liveGeneration!=liveLoadGeneration||session!=active||generation!=loadGeneration||page!=pageGeneration)return;try{
                if(!session.adopt(current))throw new IllegalStateException("Your local drawing save is still pending");
                present(session.document());canvas.setFrame(Math.min(frame,document.optInt("frameCount")-1));loadingDrawing=false;canvas.setEnabled(!playing);updateStatus();
                if(action!=null)action.run();
            }catch(Exception error){status.setText("Drawing retained · "+error.getMessage());onMessage(error.getMessage());}});
        }catch(Exception error){postUi(()->{if(session==active&&liveGeneration==liveLoadGeneration){loadingDrawing=false;canvas.setEnabled(active.error()==null&&!playing);status.setText("Drawing retained · "+error.getMessage());onMessage(error.getMessage());}});}});
    }
    private boolean currentLive(int generation,SketchEditorSession active){return !destroyed&&resumed&&watching&&generation==liveLoadGeneration&&session==active;}
    private void startLivePlayer(String streamId){watchInFlight=true;String id=sketchId;SketchEditorSession active=session;int generation=++liveLoadGeneration;io.execute(()->{try{JSONObject baseline=SketchLiveDrawing.read(this,id,streamId);postUi(()->{watchInFlight=false;if(!currentLive(generation,active)||loadingDrawing||session.pending()>0)return;try{stopLivePlayer();liveSessionId=streamId;liveMethod=SketchDrawingPolicy.method(baseline);if(streamId.equals(notifiedSessionId)&&id.equals(notifiedSketchId))notificationReplayPending=false;liveDisplayedRevision=-1;livePlayer=new SketchLivePlayer(this,baseline,new SketchLivePlayer.Listener(){
        private boolean preparedShown,paintShown;private SketchLivePreparation.Prepared shownPaint;
        public void drawing(JSONObject drawing,String objectId,float fraction,String caption){showDrawing(drawing,null,objectId,fraction,caption);}
        public void preparedDrawing(SketchLivePreparation.Prepared prepared,String objectId,float fraction,String caption){showDrawing(prepared.document,prepared,objectId,fraction,caption);}
        public void agentHand(SketchAgentHand.State state){if(currentLive(generation,active))canvas.setAgentHand(state);}
        public void agentTool(String toolLabel,String caption){if(!currentLive(generation,active))return;agentGlobalTool=agentToolPosition(toolLabel)<0;showActualAgentTool(toolLabel);updateAgentToolIndicator();status.setText(liveMethodLabel()+" · "+caption);}
        public SketchPaintReplay.Viewport paintViewport(String objectId){return canvas.paintViewport(objectId);}
        public SketchPaintReplay.Viewport paintViewport(String objectId,int frameIndex){return canvas.paintViewport(objectId,frameIndex);}
        public void preparedPaintDrawing(SketchLivePreparation.Prepared prepared,SketchPaintReplay.Frame image,String objectId,float fraction,String caption){
            if(!currentLive(generation,active)){image.close();return;}
            try{if(shownPaint!=prepared){canvas.setPreparedDocument(prepared);shownPaint=prepared;preparedShown=true;}paintShown=true;liveDrawingObject=objectId;liveCaption=caption;canvas.setFrame(prepared.frameIndex);canvas.setPaintPresentation(objectId,image);canvas.setPresentation(objectId,fraction);canvas.setEnabled(false);status.setText(caption);}catch(Exception error){image.close();failed(error.getMessage());}
        }
        private void showDrawing(JSONObject drawing,SketchLivePreparation.Prepared prepared,String objectId,float fraction,String caption){if(!currentLive(generation,active))return;try{if(objectId==null||!objectId.equals(liveDrawingObject)||!caption.equals(liveCaption)){if(prepared==null)canvas.setDocument(drawing);else{canvas.setPreparedDocument(prepared);preparedShown=true;}liveDrawingObject=objectId;liveCaption=caption;}if(objectId!=null)canvas.setFrame(SketchDocument.object(drawing,objectId).getInt("frame"));canvas.setPresentation(objectId,fraction);canvas.setEnabled(false);status.setText(caption);}catch(Exception error){failed(error.getMessage());}}
        public void committed(JSONObject drawing){if(!currentLive(generation,active))return;try{if(session.adopt(drawing)){if(paintShown){canvas.setCommittedPaintDocument(session.document());presentMetadata(session.document());paintShown=false;}else if(preparedShown)presentMetadata(session.document());else present(session.document());canvas.setPresentation(null,1);liveDrawingObject=null;liveDisplayedRevision=drawing.optLong("revision");}}catch(Exception error){failed(error.getMessage());}}
        public void finished(long cursor){if(!currentLive(generation,active))return;io.execute(()->{try(SketchProjectStore store=new SketchProjectStore(SketchStudioActivity.this)){store.consumeLiveSession(cursor);}});if(notifiedSessionId==null)getPreferences(MODE_PRIVATE).edit().putLong("lastLiveSequence",cursor).apply();stopLivePlayer();if(notifiedSessionId==null)reload();else status.setText(liveMethodLabel()+" · session replay complete · Draw myself to edit");}
        public void idle(){if(!currentLive(generation,active))return;canvas.setPresentation(null,1);canvas.setEnabled(false);status.setText(liveMethodLabel()+" · live · waiting for drawing steps · Draw myself is available");}
        public void failed(String error){if(!currentLive(generation,active))return;loadingDrawing=true;stopLivePlayer();status.setText(error);onMessage(error);}
    });livePlayer.start();}catch(Exception error){notificationReplayPending=false;onMessage(error.getMessage());}});}catch(Exception error){postUi(()->{watchInFlight=false;notificationReplayPending=false;onMessage(error.getMessage());});}});}
    private final Runnable watchTick=new Runnable(){public void run(){
        if(destroyed)return;
        if(resumed&&watching&&!loadingDrawing&&!playing&&livePlayer==null&&session!=null&&session.pending()==0&&session.error()==null&&!canvas.isDrawing()&&!watchInFlight){
            if(notificationReplayPending){startLivePlayer(notifiedSessionId);handler.postDelayed(this,250);return;}
            // An explicit invitation stays on its drawing, even while other books have pending sessions.
            if(notifiedSessionId!=null){handler.postDelayed(this,250);return;}
            watchInFlight=true;long cursor=getPreferences(MODE_PRIVATE).getLong("lastLiveSequence",0),revision=document.optLong("revision");String id=sketchId;SketchEditorSession active=session;
            io.execute(()->{try{JSONObject next,changed=null;try(SketchProjectStore store=new SketchProjectStore(SketchStudioActivity.this)){next=store.nextLiveSession(cursor);if(next==null&&store.revision(id)!=revision)changed=store.get(id);}JSONObject pending=next,incoming=changed;
                postUi(()->{watchInFlight=false;if(destroyed||!resumed||!watching||active!=session||session.pending()>0||canvas.isDrawing())return;try{
                    if(pending!=null){String target=pending.getString("sketchId"),token=pending.getString("sessionId");if(!target.equals(sketchId)){if(pending.has("bookId"))openBookPage(pending.getString("bookId"),pending.getInt("page"));else{bookId=null;book=null;loadSketch(target,0);}}else startLivePlayer(token);return;}
                    android.content.SharedPreferences signal=getSharedPreferences("sketch_live_workspace",MODE_PRIVATE);String target=signal.getString("sketchId","");if(!target.isEmpty()&&!target.equals(sketchId)){String targetBook=signal.getString("bookId","");if(targetBook.isEmpty()){bookId=null;book=null;loadSketch(target,0);}else openBookPage(targetBook,signal.getInt("page",0));return;}
                    if(incoming!=null&&session.adopt(incoming))present(session.document());
                }catch(Exception error){onMessage(error.getMessage());}});
            }catch(Exception error){postUi(()->{watchInFlight=false;});}});
        }
        handler.postDelayed(this,250);
    }};

    private void setFrame(int index) { stopPlayback(); if (document == null) return; int next = Math.max(0, Math.min(document.optInt("frameCount") - 1, index)); canvas.setFrame(next); frames.setProgress(bookId==null?next:bookPage); updateStatus(); }
    private void togglePlayback() { if (playing) { stopPlayback(); return; } if (document == null) return; playing = true;++playbackGeneration;bookPlaybackInFlight=false;canvas.setEnabled(false); playStarted = SystemClock.uptimeMillis(); playFirst = canvas.getFrame();if(bookId!=null&&session.pending()>0){playing=false;afterSaves(()->postUi(this::togglePlayback));return;} playButton.setText("Pause");if(bookId==null)postUi(playTick);else handler.postDelayed(playTick,NativeFrameClock.durationMs(SketchTimelineTools.exposure(book.optJSONObject("exposures"),bookPage),book.optInt("fps"))); }
    private final Runnable playTick = new Runnable() { @Override public void run() {
        if (!playing || document == null) return; if(bookId!=null){playBookTick();return;}int fps = document.optInt("fps"), count = document.optInt("frameCount"); long elapsed = SystemClock.uptimeMillis() - playStarted;
        long index = elapsed * fps / 1000; int frame = (int) ((playFirst + index) % count); canvas.setFrame(frame); frames.setProgress(frame); updateStatus();
        long nextAt = NativeFrameClock.presentationTimeMs(index + 1, fps); handler.postDelayed(this, Math.max(1, nextAt - elapsed));
    } };
    private void stopPlayback() { boolean wasPlaying=playing;playing = false;++playbackGeneration;bookPlaybackInFlight=false;if(wasPlaying&&bookId!=null&&session!=null&&!destroyed){openBookPage(bookId,bookPage);} handler.removeCallbacks(playTick); if(canvas!=null&&!destroyed)canvas.setEnabled(!loadingDrawing&&(!wasPlaying||bookId==null)&&session!=null&&livePlayer==null);if (playButton != null) playButton.setText("Play"); }
    @Override protected void onPause() { resumed=false;++liveLoadGeneration;if(livePlayer!=null||watchInFlight){loadingDrawing=true;if(notifiedSessionId!=null)notificationReplayPending=true;}stopLivePlayer();stopPlayback();handler.removeCallbacks(watchTick);super.onPause(); }
    @Override protected void onResume() { super.onResume();resumed=true;liveSessionId="";if(loadingDrawing&&session!=null){if(session.pending()==0)reload();else afterSaves(()->postUi(this::reload));}handler.removeCallbacks(watchTick);handler.postDelayed(watchTick,250); }
    @Override protected void onDestroy() { destroyed=true;stopLivePlayer();stopPlayback();handler.removeCallbacksAndMessages(null);if(session!=null)session.close();io.shutdown();transfers.shutdown();if(canvas!=null)canvas.dispose();super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("bookId",bookId);state.putInt("bookPage",bookPage);state.putString("sketchId", sketchId); state.putInt("frame", canvas.getFrame());state.putString("notifiedSketchId",notifiedSketchId);state.putString("notifiedSessionId",notifiedSessionId); super.onSaveInstanceState(state); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(StudioUi.SURFACE);layout.setPadding(dp(6),dp(2),dp(6),dp(2));return layout; }
    private LinearLayout row(LinearLayout root) { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL);root.addView(layout,new LinearLayout.LayoutParams(-1,-2));return layout; }
    private Button button(LinearLayout row, String title, Runnable action) { Button button = new Button(this);button.setText(title);StudioUi.styleButton(button,title.equals("Play"));button.setTextSize(12);button.setPadding(dp(4),dp(4),dp(4),dp(4));button.setOnClickListener(v->runAction(title,action));row.addView(button,new LinearLayout.LayoutParams(0,-2,1));return button; }
    // Browsing a menu never takes ownership of an active drawing. Its chosen action
    // goes through the same loading, playback and live-takeover gate as the toolbar.
    private void runAction(String title,Runnable action){try{
        boolean navigation=java.util.Arrays.asList("File","More","Brush / Text","Layers","Frames / Cels","Workspace","Canvas view","Drawing status","Drawing alerts","Colours","Select layer","Go to frame","Go","Exposure sheet","Onion skin","Onion settings","First","−100","+100","Last","On ones","On twos","On threes","Paint studio","Paint settings","Clone source").contains(title);
        if(navigation){action.run();return;}
        if(loadingDrawing&&!java.util.Arrays.asList("New","Open","Sketch books","Draw myself").contains(title)){onMessage("Opening the selected drawing");return;}
        if(playing&&!title.equals("Play")&&!title.equals("Draw myself")){onMessage("Pause playback before changing artwork");return;}
        if((livePlayer!=null||watchInFlight)&&watching&&!title.equals("Draw myself")){takeOverLive(action);return;}
        action.run();
    }catch(Exception error){onMessage(error.getMessage());}}
    private void menu(String title,String[] labels,Runnable... actions){new AlertDialog.Builder(this).setTitle(title).setItems(labels,(d,index)->runAction(labels[index],actions[index])).show();}
    private void fileMenu(){menu("Sketch files",new String[]{"New","Open","Sketch books","Export video","Archive sketch","Restore archive","Canvas view","Drawing status","Story → 2D film"},this::newDocument,this::openDocument,this::booksDialog,this::export,this::archiveSketch,this::restoreArchiveDialog,this::toggleCanvasFocus,()->new AlertDialog.Builder(this).setTitle("Drawing status").setMessage(status.getText()+"\n\n"+toolStatus.getText()).setPositiveButton("OK",null).show(),this::storyFilmDialog);}
    private void moreMenu(){menu("Drawing tools",new String[]{"Layers","Frames / Cels","2D animation","Workspace","Drawing alerts","Story → 2D film"},this::layersMenu,this::framesMenu,this::openAnimationStudio,this::workspaceDialog,this::drawingAlerts,this::storyFilmDialog);}
    private void openAnimationStudio(){if(document==null)return;stopPlayback();afterSaves(()->postUi(()->startActivity(new Intent(this,SketchAnimationActivity.class).putExtra("sketchId",sketchId))));}

    /** Prompt-driven original storyboard; all created cels remain ordinary editable Sketch Studio parts. */
    private void storyFilmDialog(){
        LinearLayout form=column();form.setPadding(dp(12),dp(8),dp(12),dp(8));
        EditText story=new EditText(this);story.setTextColor(StudioUi.TEXT);story.setHintTextColor(StudioUi.MUTED);
        story.setHint("Describe your character, struggle, action or journey…");
        story.setContentDescription("Story for original vector animation");
        story.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        story.setMinLines(4);story.setText("The struggle of a man");
        form.addView(story);
        EditText duration=input("Duration in seconds (4–12)","10",true);form.addView(duration);
        CheckBox watch=new CheckBox(this);watch.setText("Watch the actual vector drawing journal");
        watch.setChecked(true);watch.setTextColor(StudioUi.TEXT);form.addView(watch);
        TextView note=new TextView(this);note.setTextColor(StudioUi.MUTED);note.setTextSize(12);
        note.setText("Creates three connected shots with separated character parts, timed poses, camera depth, full-colour environments and editable cels. The text engine stages an original animation draft; it does not infer unseen anatomy or produce neural image generation.");
        form.addView(note);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Story → 2D film")
                .setView(scroll).setNegativeButton("Cancel",null)
                .setPositiveButton("Create film",(d,w)->{
                    final String narrative=story.getText().toString();
                    final boolean liveStory=watch.isChecked();
                    final int seconds;try{seconds=Integer.parseInt(duration.getText().toString().trim());}
                    catch(NumberFormatException invalid){onMessage("Duration must be a whole number from 4 to 12");return;}
                    stopPlayback();status.setText("Preparing original storyboard and keyframed character artwork…");
                    // The transfer worker is separate from UI sketch loading, enabling a real live viewer.
                    afterSaves(()->transfers.execute(()->{
                        try{
                            JSONObject receipt=SketchStoryFilmGenerator.create(this,narrative,seconds,24,liveStory,new SketchStoryFilmGenerator.Listener(){
                                public void opened(String id,String stream){
                                    postUi(()->{
                                        watching=true;notifiedSketchId=id;notifiedSessionId=stream;notificationReplayPending=true;
                                        getPreferences(MODE_PRIVATE).edit().putBoolean("watchLiveDrawing",true).apply();
                                        updateLiveControl();bookId=null;book=null;loadSketch(id,0);
                                    });
                                }
                                public void progress(int finished,int total){
                                    postUi(()->{if(!destroyed&&finished<total)status.setText("Drawing story frames · "+finished+" / "+total+" parts · all original vector operations saved");});
                                }
                            });
                            String created=receipt.getString("sketchId");
                            postUi(()->{
                                if(!receipt.optBoolean("live")){bookId=null;book=null;loadSketch(created,0);}
                                onMessage("Story film saved · "+receipt.optInt("frameCount")+" frames, 24 FPS · Play or Export video");
                            });
                        }catch(Exception error){postUi(()->{
                            status.setText("Story film retained where already saved · "+error.getMessage());
                            onMessage(error.getMessage());
                        });}
                    }));
                }).show();
    }
    private void drawingAlerts(){
        JSONObject availability=SketchLiveNotification.status(this);boolean enabled=availability.optBoolean("available");
        new AlertDialog.Builder(this).setTitle("Drawing alerts").setMessage(enabled?
                "Drawing alerts are enabled. Tap a drawing notification to open its exact session and watch the saved strokes catch up with live work.":
                "Android notifications are disabled for drawing. Allow alerts to receive an invitation when ChatGPT begins. Your drawing is saved even when alerts are off.")
                .setNegativeButton("Close",null).setPositiveButton(enabled?"Settings":"Enable alerts",(d,w)->{
                    if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                        if(!getPreferences(MODE_PRIVATE).getBoolean("drawingAlertPermissionRequested",false)||shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)){
                            getPreferences(MODE_PRIVATE).edit().putBoolean("drawingAlertPermissionRequested",true).apply();requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},701);return;
                        }
                    }
                    Intent settings=new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,SketchLiveNotification.CHANNEL_ID);
                    try{startActivity(settings);}catch(android.content.ActivityNotFoundException unavailable){onMessage("Open Android app settings to allow drawing notifications");}
                }).show();
    }
    private void brushMenu(){menu("Brush / Text",new String[]{"Brush","Colours","Paint studio","Text","Finish path","Transform / key","Delete object","Reference image"},this::brushDialog,this::paletteDialog,this::paintMenu,this::textDialog,()->canvas.finishPolygon(),this::transformDialog,this::deleteObject,this::referenceDialog);}
    private void layersMenu(){menu("Layers",new String[]{"Select layer","+ Layer","Layer settings","Up","Down"},this::chooseLayer,this::addLayer,this::layerDialog,()->reorderLayer(1),()->reorderLayer(-1));}
    private void chooseLayer(){
        if(document==null)return;
        // Live metadata can replace/reorder the backing spinner while this dialog is open.
        // Keep each displayed label bound to the identity the user actually saw.
        ArrayList<String> choices=new ArrayList<>(layerIds);
        String[] labels=new String[choices.size()];
        for(int i=0;i<labels.length;i++)labels[i]=String.valueOf(layers.getAdapter().getItem(i));
        new AlertDialog.Builder(this).setTitle("Select layer").setSingleChoiceItems(labels,Math.max(0,choices.indexOf(canvas.getLayerId())),(d,index)->{
            String selectedLayer=choices.get(index);
            runAction("Choose layer",()->{
                // runAction may asynchronously adopt a newer authoritative document.
                int currentIndex=layerIds.indexOf(selectedLayer);
                if(document==null||currentIndex<0){onMessage("That layer is no longer available. Open Layers to choose again.");return;}
                layers.setSelection(currentIndex);canvas.setLayerId(selectedLayer);updateStatus();
            });
            d.dismiss();
        }).show();
    }
    private boolean onionEnabled=true;private int onionRange=1,onionAlpha=48;
    private void framesMenu(){menu("Frames / Cels",new String[]{"Go to frame","Timing / canvas","Exposure","Exposure sheet","Copy → next","Cel tools","Onion skin","Onion settings"},this::scrubDialog,this::timingDialog,this::exposureDialog,this::bookTimingDialog,this::duplicateCel,this::celDialog,()->{onionEnabled=!onionEnabled;canvas.setOnion(onionEnabled);saveOnionOptions();onMessage(onionEnabled?"Onion skin on":"Onion skin off");},this::onionDialog);}
    private boolean navigatorStillCurrent(String openedBook,String openedSketch){return document!=null&&java.util.Objects.equals(openedBook,bookId)&&(openedBook!=null||java.util.Objects.equals(openedSketch,sketchId));}
    private void scrubDialog(){
        if(document==null)return;final String openedBook=bookId,openedSketch=sketchId;final int count=bookId==null?document.optInt("frameCount"):book.optInt("pageCount"),current=bookId==null?canvas.getFrame():bookPage;
        LinearLayout form=column();EditText number=input("Frame / page number (1.."+count+")",String.valueOf(current+1),true);number.setInputType(InputType.TYPE_CLASS_NUMBER);number.setContentDescription("Frame or page number");number.selectAll();form.addView(number);
        SeekBar slider=new SeekBar(this);slider.setMax(count-1);slider.setProgress(current);slider.setMinimumHeight(dp(48));slider.setContentDescription("Preview frame or page position");form.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(fromUser)number.setText(String.valueOf(progress+1));}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
        LinearLayout jumps=row(form);
        java.util.function.IntConsumer preview=index->{int clamped=Math.max(0,Math.min(count-1,index));number.setText(String.valueOf(clamped+1));slider.setProgress(clamped);};
        button(jumps,"First",()->preview.accept(0));button(jumps,"−100",()->{try{preview.accept(SketchTimelineTools.position(number.getText().toString(),count)-100);}catch(Exception error){onMessage(error.getMessage());}});button(jumps,"+100",()->{try{preview.accept(SketchTimelineTools.position(number.getText().toString(),count)+100);}catch(Exception error){onMessage(error.getMessage());}});button(jumps,"Last",()->preview.accept(count-1));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Go to frame / page").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Go",null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            int selected=SketchTimelineTools.position(number.getText().toString(),count);
            if(!navigatorStillCurrent(openedBook,openedSketch)){onMessage("The drawing changed. Open Go again.");return;}
            runAction("Select frame",()->{if(!navigatorStillCurrent(openedBook,openedSketch)){onMessage("The drawing changed. Open Go again.");return;}selectFilmstrip(selected);dialog.dismiss();});
        }catch(Exception error){onMessage(error.getMessage());}});
    }
    private void archiveSketch(){
        if(session==null||document==null)return;SketchEditorSession active=session;String id=sketchId,title=document.optString("name");
        io.execute(()->{try{active.awaitIdle(15000);if(active.error()!=null)throw new IllegalStateException(active.error());long revision=active.document().getLong("revision");
            JSONObject request=null;try{String retained=getPreferences(MODE_PRIVATE).getString("pendingEditableArchive","");if(!retained.isEmpty()){JSONObject prior=new JSONObject(retained);if(id.equals(prior.optString("sketchId"))&&revision==prior.optLong("expectedRevision"))request=prior;}}catch(Exception ignored){}
            if(request==null)request=new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",UUID.randomUUID().toString());
            getPreferences(MODE_PRIVATE).edit().putString("pendingEditableArchive",request.toString()).commit();JSONObject frozen=request;
            postUi(()->onMessage("Archiving editable drawing to your linked workspace. You can keep drawing."));
            transfers.execute(()->{try{JSONObject result=SketchPaintArchive.archive(this,frozen);if(!result.optBoolean("remoteVerified"))throw new IllegalStateException("Archive transfer was not verified");
                getPreferences(MODE_PRIVATE).edit().remove("pendingEditableArchive").putString("lastEditableArchive",result.toString()).commit();
                postUi(()->new AlertDialog.Builder(this).setTitle("Editable sketch archived").setMessage(title+" · revision "+result.optLong("sourceRevision")+"\n\nPaint, reference images and editable strokes are verified in your linked workspace.\n\n"+result.optString("archiveName")).setPositiveButton("Done",null).show());
            }catch(Exception failure){postUi(()->onMessage("Archive retained for retry: "+failure.getMessage()));}});
        }catch(Exception failure){postUi(()->onMessage(failure.getMessage()));}});
    }
    private ArrayList<JSONObject> knownEditableArchives()throws Exception {
        ArrayList<JSONObject> archives=new ArrayList<>();java.util.HashSet<String> seen=new java.util.HashSet<>();java.io.File root=new java.io.File(getFilesDir(),"sketch_studio");java.io.File[] sketches=root.listFiles();
        if(sketches!=null)for(java.io.File sketch:sketches){if(archives.size()>=128)break;if(!sketch.isDirectory()||java.nio.file.Files.isSymbolicLink(sketch.toPath()))continue;java.io.File[] generations=new java.io.File(sketch,"archives").listFiles();if(generations==null)continue;
            for(java.io.File generation:generations){if(archives.size()>=128)break;java.io.File receipt=new java.io.File(generation,"completed.json");if(!receipt.isFile()||receipt.length()>65536||java.nio.file.Files.isSymbolicLink(generation.toPath())||java.nio.file.Files.isSymbolicLink(receipt.toPath())||!receipt.getCanonicalPath().startsWith(root.getCanonicalPath()+java.io.File.separator))continue;
                try(java.io.InputStream input=new java.io.FileInputStream(receipt)){JSONObject value=new JSONObject(new String(input.readNBytes(65537),java.nio.charset.StandardCharsets.UTF_8));String owner=SketchDocument.id(value,"archiveProjectId"),name=value.getString("archiveName"),hash=value.getString("archiveSha256");if(!name.matches("[A-Za-z0-9_.-]{1,120}")||!name.endsWith(".vssketch")||name.contains("..")||!hash.matches("[a-f0-9]{64}"))continue;if(seen.add(owner+"/"+name)){try(SketchProjectStore store=new SketchProjectStore(this)){value.put("displayName",store.get(owner).optString("name",name));}catch(Exception missing){value.put("displayName",name);}archives.add(value);}}catch(Exception invalid){}
            }
        }
        return archives;
    }
    private void restoreArchiveDialog(){
        transfers.execute(()->{try{ArrayList<JSONObject> archives=knownEditableArchives();String[] labels=new String[archives.size()+1];for(int i=0;i<archives.size();i++){JSONObject archive=archives.get(i);labels[i]=archive.optString("displayName")+" · revision "+archive.optLong("sourceRevision");}labels[archives.size()]="Restore with archive details";
            postUi(()->new AlertDialog.Builder(this).setTitle("Restore editable archive as a new sketch").setItems(labels,(d,index)->runAction("Restore selected archive",()->{if(index==archives.size())archiveDetailsDialog();else beginArchiveRestore(archives.get(index));})).setNegativeButton("Cancel",null).show());
        }catch(Exception failure){postUi(()->onMessage(failure.getMessage()));}});
    }
    private void archiveDetailsDialog(){
        JSONObject last=null;try{last=new JSONObject(getPreferences(MODE_PRIVATE).getString("lastEditableArchive","{}"));}catch(Exception ignored){}
        LinearLayout form=column();EditText owner=input("Archive sketch ID",last==null?"":last.optString("archiveProjectId"),false),filename=input("Exact linked .vssketch filename",last==null?"":last.optString("archiveName"),false),hash=input("Archive SHA-256",last==null?"":last.optString("archiveSha256"),false);form.addView(owner);form.addView(filename);form.addView(hash);ScrollView scroll=new ScrollView(this);scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("Verified linked-workspace archive").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Restore new sketch",(d,w)->runAction("Restore selected archive",()->{try{beginArchiveRestore(new JSONObject().put("archiveProjectId",owner.getText().toString()).put("archiveName",filename.getText().toString()).put("archiveSha256",hash.getText().toString()));}catch(Exception failure){onMessage(failure.getMessage());}})).show();
    }
    private void beginArchiveRestore(JSONObject archive){
        try{JSONObject args=new JSONObject().put("archiveProjectId",archive.getString("archiveProjectId")).put("archiveName",archive.getString("archiveName")).put("expectedSha256",archive.getString("archiveSha256")).put("operationId",UUID.randomUUID().toString());
            String prior=getPreferences(MODE_PRIVATE).getString("pendingEditableRestore","");if(!prior.isEmpty()){JSONObject retained=new JSONObject(prior);if(args.getString("archiveProjectId").equals(retained.optString("archiveProjectId"))&&args.getString("archiveName").equals(retained.optString("archiveName"))&&args.getString("expectedSha256").equals(retained.optString("expectedSha256")))args=retained;}
            getPreferences(MODE_PRIVATE).edit().putString("pendingEditableRestore",args.toString()).apply();JSONObject frozen=args;SketchEditorSession active=session;int generation=loadGeneration,page=pageGeneration;
            io.execute(()->{try{if(active!=null){active.awaitIdle(15000);if(active.error()!=null)throw new IllegalStateException(active.error());}transfers.execute(()->{try{JSONObject result=SketchPaintArchive.restore(this,frozen);getPreferences(MODE_PRIVATE).edit().remove("pendingEditableRestore").apply();String restored=result.getString("sketchId");postUi(()->{if(session!=active||generation!=loadGeneration||page!=pageGeneration){onMessage("Archive restored as a new sketch. Find it in File → Open.");return;}bookId=null;book=null;loadSketch(restored,0);onMessage("Verified editable archive restored. Your existing drawing is preserved.");});}catch(Exception failure){postUi(()->onMessage("Restore retained for retry: "+failure.getMessage()));}});}catch(Exception failure){postUi(()->onMessage(failure.getMessage()));}});
        }catch(Exception failure){onMessage(failure.getMessage());}
    }
    private JSONObject paintStyle()throws Exception{return new JSONObject().put("mode",paintMode).put("color",brushColor).put("sizePx",paintSize).put("opacity",brushOpacity).put("flow",paintFlow).put("hardness",brushHardness).put("spacing",paintSpacing).put("pressure",paintPressure).put("texture",paintTexture).put("seed",paintSeed).put("angle",brushAngle).put("tip",paintTip);}
    private void paintMenu(){menu("Paint studio",new String[]{"New paint cel","Paint brush","Erase paint","Clone brush","Paint settings","Clone source","Sample clone colour"},this::newPaintCel,()->activatePaint("paint"),()->activatePaint("erase"),()->activatePaint("clone"),this::paintSettings,this::chooseCloneSource,()->{if(paintCloneSource==null){onMessage("Choose an owned Clone source first");return;}sampleCloneReference=true;toolPicker.setSelection(7);canvas.setTool("eyedropper");onMessage("Tap the canvas to sample the owned reference");});}
    private void newPaintCel(){
        if(document==null||session==null)return;
        try{String id=UUID.randomUUID().toString();pendingPaintCelId=id;
            if(watching&&livePlayer==null&&!watchInFlight){watching=false;++liveLoadGeneration;getPreferences(MODE_PRIVATE).edit().putBoolean("watchLiveDrawing",false).apply();updateLiveControl();}
            apply(new JSONArray().put(new JSONObject().put("type","add_paint_cel").put("layerId",canvas.getLayerId()).put("objectId",id).put("frame",canvas.getFrame()).put("exposure",1)));
        }catch(Exception error){pendingPaintCelId=null;onMessage(error.getMessage());}
    }
    private void activatePaint(String mode){
        if(document==null)return;
        if("clone".equals(mode)&&paintCloneSource==null){onMessage("Choose an owned Clone source first");chooseCloneSource();return;}
        paintMode=mode;sampleCloneReference=false;if("erase".equals(mode))paintAlphaLock=false;
        if(watching&&livePlayer==null&&!watchInFlight){watching=false;++liveLoadGeneration;getPreferences(MODE_PRIVATE).edit().putBoolean("watchLiveDrawing",false).apply();updateLiveControl();}
        String target=null;try{if(canvas.getSelectedId()!=null&&"paint".equals(SketchDocument.object(document,canvas.getSelectedId()).optString("kind")))target=canvas.getSelectedId();
            if(target==null){JSONArray objects=SketchDocument.layer(document,canvas.getLayerId()).getJSONArray("objects");for(int i=objects.length()-1;i>=0;i--){JSONObject object=objects.getJSONObject(i);if("paint".equals(object.optString("kind"))&&SketchFrameRenderer.exposed(object,canvas.getFrame())){target=object.getString("id");break;}}}
            if(target==null)newPaintCel();else canvas.selectObject(target);
            canvas.setTool("erase".equals(mode)?"paint_erase":"clone".equals(mode)?"clone":"paint");int selectedTool="erase".equals(mode)?12:"clone".equals(mode)?13:11;if(toolPicker.getSelectedItemPosition()!=selectedTool)toolPicker.setSelection(selectedTool);updateBrush();updateStatus();
        }catch(Exception error){onMessage(error.getMessage());}
    }
    private void chooseCloneSource(){
        if(document==null)return;ArrayList<String> ids=new ArrayList<>(),hashes=new ArrayList<>(),labels=new ArrayList<>();
        try{JSONArray values=document.getJSONArray("layers");for(int i=0;i<values.length();i++){JSONObject layer=values.getJSONObject(i);JSONArray objects=layer.getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject object=objects.getJSONObject(j);if("image".equals(object.optString("kind"))){ids.add(object.getString("id"));hashes.add(object.getJSONObject("reference").getString("sha256"));labels.add(layer.getString("name")+" · "+object.getString("id")+" · "+object.getJSONObject("reference").getInt("width")+" × "+object.getJSONObject("reference").getInt("height"));}}}
            if(ids.isEmpty()){onMessage("Import a reference from this project's assets first. The guide stays hidden.");return;}
            String owner=sketchId;new AlertDialog.Builder(this).setTitle("Owned clone source").setItems(labels.toArray(new String[0]),(d,index)->runAction("Choose clone source",()->{try{
                if(!owner.equals(sketchId))throw SketchDocument.bad("The drawing changed. Choose its source again.");JSONObject object=SketchDocument.object(document,ids.get(index));
                if(!hashes.get(index).equals(object.getJSONObject("reference").getString("sha256")))throw SketchDocument.bad("Reference changed. Choose the clone source again.");
                paintCloneSource=new JSONObject().put("objectId",ids.get(index)).put("sha256",hashes.get(index)).put("offsetX",0).put("offsetY",0).put("scale",1).put("rotation",0);paintMode="clone";updateBrush();activatePaint("clone");
            }catch(Exception error){onMessage(error.getMessage());}})).setNegativeButton("Cancel",null).show();
        }catch(Exception error){onMessage(error.getMessage());}
    }
    private static double bounded(EditText input,double min,double max,String name)throws Exception{double value=Double.parseDouble(input.getText().toString());if(!Double.isFinite(value)||value<min||value>max)throw SketchDocument.bad(name+" must be "+min+".."+max);return value;}
    private void paintSettings(){
        if(document==null)return;String sourceOwner=sketchId;LinearLayout form=column();
        EditText colour=input("Paint colour #AARRGGBB",brushColor,false),size=input("Paint size in pixels 0.5..256",String.valueOf(paintSize),true),opacity=input("Paint opacity 0..1",String.valueOf(brushOpacity),true),flow=input("Flow 0..1",String.valueOf(paintFlow),true),hardness=input("Hardness 0..1",String.valueOf(brushHardness),true),spacing=input("Dab spacing 0.05..1",String.valueOf(paintSpacing),true),angle=input("Paint tip angle -180..180",String.valueOf(brushAngle),true),seed=input("Grain seed 0..2147483647",String.valueOf(paintSeed),true);
        form.addView(new SketchColourPicker(this,brushColor,colour::setText),new LinearLayout.LayoutParams(-1,dp(180)));for(EditText value:new EditText[]{colour,size,opacity,flow,hardness,spacing,angle,seed})form.addView(value);
        Spinner tip=new Spinner(this),texture=new Spinner(this);tip.setContentDescription("Paint tip");tip.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"round","flat"}));tip.setSelection("flat".equals(paintTip)?1:0);texture.setContentDescription("Paint texture");texture.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"smooth","grain"}));texture.setSelection("grain".equals(paintTexture)?1:0);tip.setMinimumHeight(dp(48));texture.setMinimumHeight(dp(48));form.addView(tip);form.addView(texture);
        CheckBox pressure=new CheckBox(this),alpha=new CheckBox(this),clip=new CheckBox(this);pressure.setText("Pressure changes stroke width");pressure.setChecked(paintPressure);alpha.setText("Alpha lock · keep existing painted alpha");alpha.setChecked(paintAlphaLock);clip.setText("Rectangular clip");clip.setChecked(paintClipEnabled);for(CheckBox value:new CheckBox[]{pressure,alpha,clip}){value.setTextColor(StudioUi.TEXT);value.setMinimumHeight(dp(48));form.addView(value);}
        JSONObject currentClip=paintClip;EditText clipX=input("Clip x 0..1",currentClip==null?"0":String.valueOf(currentClip.optDouble("x")),true),clipY=input("Clip y 0..1",currentClip==null?"0":String.valueOf(currentClip.optDouble("y")),true),clipW=input("Clip width 0..1",currentClip==null?"1":String.valueOf(currentClip.optDouble("width")),true),clipH=input("Clip height 0..1",currentClip==null?"1":String.valueOf(currentClip.optDouble("height")),true);for(EditText value:new EditText[]{clipX,clipY,clipW,clipH})form.addView(value);
        JSONObject currentSource=paintCloneSource;EditText offX=input("Clone source offset x -1..1",currentSource==null?"0":String.valueOf(currentSource.optDouble("offsetX")),true),offY=input("Clone source offset y -1..1",currentSource==null?"0":String.valueOf(currentSource.optDouble("offsetY")),true),scale=input("Clone source scale 0.01..8",currentSource==null?"1":String.valueOf(currentSource.optDouble("scale",1)),true),rotation=input("Clone source rotation -180..180",currentSource==null?"0":String.valueOf(currentSource.optDouble("rotation")),true);for(EditText value:new EditText[]{offX,offY,scale,rotation})form.addView(value);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Native paint settings").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->runAction("Apply paint settings",()->{try{
            String nextColour=colour.getText().toString();SketchDocument.color(nextColour);float nextSize=(float)bounded(size,.5,256,"Paint size"),nextOpacity=(float)bounded(opacity,0,1,"Opacity"),nextFlow=(float)bounded(flow,0,1,"Flow"),nextHardness=(float)bounded(hardness,0,1,"Hardness"),nextSpacing=(float)bounded(spacing,.05,1,"Spacing"),nextAngle=(float)bounded(angle,-180,180,"Angle");double nextSeed=bounded(seed,0,Integer.MAX_VALUE,"Seed");if(nextSeed!=(int)nextSeed)throw SketchDocument.bad("Grain seed must be an integer");
            if(alpha.isChecked()&&"erase".equals(paintMode))throw SketchDocument.bad("Alpha lock cannot be used while erasing");
            JSONObject nextClip=new JSONObject().put("x",bounded(clipX,0,1,"Clip x")).put("y",bounded(clipY,0,1,"Clip y")).put("width",bounded(clipW,.0001,1,"Clip width")).put("height",bounded(clipH,.0001,1,"Clip height"));SketchDocument.validateBounds(nextClip);
            JSONObject nextSource=currentSource==null?null:SketchDocument.copy(currentSource);if(nextSource!=null){if(!sourceOwner.equals(sketchId)||!nextSource.getString("sha256").equals(SketchDocument.object(document,nextSource.getString("objectId")).getJSONObject("reference").getString("sha256")))throw SketchDocument.bad("Clone source changed. Choose it again.");nextSource.put("offsetX",bounded(offX,-1,1,"Clone x offset")).put("offsetY",bounded(offY,-1,1,"Clone y offset")).put("scale",bounded(scale,.01,8,"Clone scale")).put("rotation",bounded(rotation,-180,180,"Clone rotation"));}
            brushColor=nextColour;paintSize=nextSize;brushOpacity=nextOpacity;paintFlow=nextFlow;brushHardness=nextHardness;paintSpacing=nextSpacing;brushAngle=nextAngle;paintSeed=(int)nextSeed;paintPressure=pressure.isChecked();paintAlphaLock=alpha.isChecked();paintClipEnabled=clip.isChecked();paintClip=nextClip;paintTip=tip.getSelectedItem().toString();paintTexture=texture.getSelectedItem().toString();paintCloneSource=nextSource;updateBrush();dialog.dismiss();
        }catch(Exception error){onMessage(error.getMessage());}}));
    }
    private void savePaletteColour(String colour)throws Exception{
        SketchDocument.color(colour);JSONArray saved=new JSONArray(getPreferences(MODE_PRIVATE).getString("savedPalette","[]"));for(int i=0;i<saved.length();i++)if(colour.equals(saved.getString(i)))return;if(saved.length()>=64)saved.remove(0);saved.put(colour);getPreferences(MODE_PRIVATE).edit().putString("savedPalette",saved.toString()).apply();
    }
    /** Dense but touch-friendly colour library with exact ARGB entry and saved personal pigments. */
    private void paletteDialog(){
        LinearLayout form=column();form.setPadding(dp(12),dp(8),dp(12),dp(8));
        java.util.ArrayList<String> categories=new java.util.ArrayList<>();
        for(int i=0;i<SketchPaletteLibrary.groupCount();i++)categories.add(SketchPaletteLibrary.name(i));
        categories.add("My saved colours");
        Spinner picker=new Spinner(this);picker.setContentDescription("Palette category");
        picker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,categories));
        picker.setMinimumHeight(dp(48));form.addView(picker);
        LinearLayout swatches=column();form.addView(swatches);
        EditText exact=input("Exact colour #AARRGGBB",brushColor,false);
        form.addView(exact);
        LinearLayout buttons=row(form);
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Colour studio · 176 pigments")
                .setView(scroll).setNegativeButton("Close",null).create();
        Runnable refresh=()->{
            swatches.removeAllViews();int group=picker.getSelectedItemPosition();
            if(group<0)return;
            String[] colours;
            if(group==SketchPaletteLibrary.groupCount()){
                java.util.ArrayList<String> saved=new java.util.ArrayList<>();
                try{
                    JSONArray stored=new JSONArray(getPreferences(MODE_PRIVATE).getString("savedPalette","[]"));
                    for(int i=0;i<stored.length();i++){
                        String colour=stored.getString(i);SketchDocument.color(colour);
                        if(!saved.contains(colour))saved.add(colour);
                    }
                }catch(Exception invalid){onMessage("Some saved colours were invalid and were skipped");}
                colours=saved.toArray(new String[0]);
            }else colours=SketchPaletteLibrary.colors(group);
            if(colours.length==0){
                TextView empty=new TextView(this);empty.setText("No saved colours yet. Choose a pigment or enter an exact ARGB value.");
                empty.setTextColor(StudioUi.MUTED);swatches.addView(empty);return;
            }
            for(int i=0;i<colours.length;i+=4){
                LinearLayout line=row(swatches);
                for(int n=i;n<Math.min(i+4,colours.length);n++){
                    String pigment=colours[n];Button swatch=button(line,"●",()->{
                        brushColor=pigment;updateBrush();dialog.dismiss();
                    });
                    swatch.setTextColor(SketchDocument.color(pigment));
                    swatch.setContentDescription("Colour "+pigment);
                    swatch.setMinHeight(dp(42));
                }
            }
        };
        picker.setOnItemSelectedListener(new SimpleSelection(){@Override public void selected(int index){refresh.run();}});
        button(buttons,"Use exact colour",()->{
            try{String next=exact.getText().toString().trim();SketchDocument.color(next);
                brushColor=next;savePaletteColour(next);updateBrush();dialog.dismiss();
            }catch(Exception error){onMessage(error.getMessage());}
        });
        button(buttons,"Save current",()->{
            try{savePaletteColour(brushColor);picker.setSelection(SketchPaletteLibrary.groupCount());refresh.run();}
            catch(Exception error){onMessage(error.getMessage());}
        });
        button(form,"Advanced HSV / opacity / brush settings",()->{dialog.dismiss();brushDialog();});
        dialog.show();refresh.run();
    }
    private EditText input(String hint, String value, boolean number) { EditText input = new EditText(this); input.setHint(hint);input.setTextColor(StudioUi.TEXT);input.setHintTextColor(StudioUi.MUTED);input.setContentDescription(hint);input.setText(value); input.setSingleLine();input.setMinimumHeight(dp(48)); if (number) input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED); return input; }
    private int dp(int pixels) { return StudioUi.dp(this,pixels); }
    private abstract static class SimpleSelection implements android.widget.AdapterView.OnItemSelectedListener {
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { selected(position); }
        public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        public abstract void selected(int position);
    }
}
