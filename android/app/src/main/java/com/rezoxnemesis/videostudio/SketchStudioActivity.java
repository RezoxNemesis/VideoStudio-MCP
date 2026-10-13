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
    private JSONObject document;
    private SketchEditorSession session;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private boolean loadingDrawing;
    private boolean destroyed,resumed,watching=true,watchInFlight,grid,symmetry;
    private String brushPreset="ink",bookId;
    private JSONObject book;
    private int bookPage,loadGeneration,pageGeneration,liveLoadGeneration;
    private float brushOpacity=1,brushHardness=.7f,brushAngle=35,smoothing=.2f;
    private SketchFilmstripView filmstrip;
    private TextView toolStatus;
    private Spinner toolPicker;
    private final String[] toolValues={"stroke","eraser","line","rect","ellipse","polygon","select","eyedropper","pan","curve","nodes"};
    private String sketchId;
    private SketchCanvasView canvas;
    private TextView status;
    private SeekBar frames;
    private Spinner layers;
    private final ArrayList<String> layerIds = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean updating, playing;
    private long playStarted;
    private int playFirst;
    private String brushColor = "#FF161C2B";
    private float brushWidth = .008f;
    private boolean brushFill;
    private Button playButton,liveButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        watching=getPreferences(MODE_PRIVATE).getBoolean("watchLiveDrawing",true);
        LinearLayout root = column(); root.setPadding(dp(8), dp(8), dp(8), dp(8)); root.setBackgroundColor(0xff080f1c);getWindow().setStatusBarColor(0xff080f1c);getWindow().setNavigationBarColor(0xff080f1c); setContentView(root);
        status = new TextView(this); status.setTextSize(13);status.setTextColor(0xffcbddeb);status.setText("Sketch Studio · opening drawing…"); root.addView(status);
        LinearLayout projects = row(root);
        liveButton=button(projects,"Draw myself",this::toggleLiveWatch);updateLiveControl();
        button(projects, "New", this::newDocument); button(projects, "Open", this::openDocument);button(projects,"Sketch books",this::booksDialog);
        button(projects, "Undo", () -> history(false)); button(projects, "Redo", () -> history(true)); button(projects, "Export video", this::export);
        LinearLayout tools = row(root); Spinner tool = new Spinner(this);toolPicker=tool;
        String[] names = {"Pen", "Eraser", "Line", "Rectangle", "Ellipse", "Polygon", "Select / move", "Eyedropper", "Pan canvas", "Curve pen", "Edit curve nodes"};
        String[] values=toolValues;
        tool.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names)); tools.addView(tool);
        tool.setOnItemSelectedListener(new SimpleSelection() { @Override public void selected(int position) { if(canvas!=null)canvas.setTool(values[position]); } });
        button(tools, "Brush", this::brushDialog);button(tools,"Workspace",this::workspaceDialog); button(tools, "Finish path", () -> canvas.finishPolygon());
        canvas = new SketchCanvasView(this); canvas.setListener(this); root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout palette=row(root);
        for(String hex:new String[]{"#FF161C2B","#FFFFFFFF","#FF756157","#FFECC6A8","#FFB87958","#FFE45468","#FFEF9B52","#FFF3DA77","#FF61B787","#FF63BADA","#FF4E77BF","#FF8E6CB7"}){Button swatch=button(palette,"●",()->{brushColor=hex;updateBrush();});swatch.setTextColor(SketchDocument.color(hex));swatch.setMinWidth(dp(38));swatch.setMinimumWidth(dp(38));swatch.setContentDescription("Colour "+hex);}
        toolStatus=new TextView(this);toolStatus.setTextColor(0xff78cad8);toolStatus.setTextSize(11);root.addView(toolStatus);
        filmstrip=new SketchFilmstripView(this);filmstrip.setListener(this::selectFilmstrip);root.addView(filmstrip,new LinearLayout.LayoutParams(-1,dp(44)));
        frames = new SeekBar(this); root.addView(frames); frames.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (fromUser) { selectFilmstrip(progress); } }
            public void onStartTrackingTouch(SeekBar bar) { stopPlayback(); }
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout timing = row(root);
        button(timing, "◀", () -> selectFilmstrip((bookId==null?canvas.getFrame():bookPage)-1)); playButton = button(timing, "Play", this::togglePlayback);
        button(timing, "▶", () -> selectFilmstrip((bookId==null?canvas.getFrame():bookPage)+1));
        CheckBox onion = new CheckBox(this); onion.setText("Onion");onion.setTextColor(0xffcbddeb); onion.setChecked(true); onion.setOnCheckedChangeListener((v, checked) -> canvas.setOnion(checked)); timing.addView(onion);
        button(timing, "Timing / canvas", this::timingDialog);
        LinearLayout layerControls = row(root); layers = new Spinner(this); layerControls.addView(layers);
        layers.setOnItemSelectedListener(new SimpleSelection() { @Override public void selected(int position) {
            if (!updating && position < layerIds.size()) { canvas.setLayerId(layerIds.get(position)); updateStatus(); }
        } });
        button(layerControls, "+ Layer", this::addLayer); button(layerControls, "Layer settings", this::layerDialog);
        button(layerControls, "Up", () -> reorderLayer(1)); button(layerControls, "Down", () -> reorderLayer(-1));
        LinearLayout edits = row(root);
        button(edits, "Transform / key", this::transformDialog); button(edits, "Exposure", this::exposureDialog);
        button(edits, "Copy → next", this::duplicateCel);button(edits,"Cel tools",this::celDialog); button(edits, "Delete object", this::deleteObject); button(edits, "Reference image", this::referenceDialog);
        try {
            bookId=state==null?getPreferences(MODE_PRIVATE).getString("lastBookId",null):state.getString("bookId");bookPage=state==null?getPreferences(MODE_PRIVATE).getInt("lastBookPage",0):state.getInt("bookPage",0);
            sketchId = state == null ? getIntent().getStringExtra("sketchId") : state.getString("sketchId");
            if (sketchId == null) sketchId = getPreferences(MODE_PRIVATE).getString("lastSketchId", null);
            if (sketchId == null) {
                try (SketchProjectStore store = new SketchProjectStore(this)) { JSONArray list = store.list(); if (list.length() > 0) sketchId = list.getJSONObject(0).getString("sketchId"); }
            }
            if(bookId!=null)openBookPage(bookId,bookPage);else if (sketchId == null) createDocument("Untitled sketch"); else reload();
            if (state != null&&document!=null) setFrame(state.getInt("frame", 0));
            handler.postDelayed(watchTick,250);
        } catch (Exception e) { onMessage(e.getMessage()); openDocument(); }
    }

    private void reload() { loadSketch(sketchId,canvas.getFrame()); }
    private void loadSketch(String id,int frame) {
        if(destroyed)return;loadingDrawing=true;stopLivePlayer();
        if(session!=null&&session.pending()>0){onMessage("Finishing the current drawing save");return;}
        if(session!=null){session.close();session=null;}canvas.setEnabled(false);
        int generation=++loadGeneration,requestedPageGeneration=pageGeneration;io.execute(()->{try{JSONObject loaded;String target=id;try(SketchProjectStore store=new SketchProjectStore(this)){loaded=store.get(id);}SketchEditorSession opened=new SketchEditorSession(this,loaded);boolean recovered=opened.error()!=null;if(recovered){opened.close();target=SketchEditorSession.recoverAsCopy(this,id);try(SketchProjectStore store=new SketchProjectStore(this)){loaded=store.get(target);}opened=new SketchEditorSession(this,loaded);}SketchEditorSession prepared=opened;String selectedId=target;if(destroyed){prepared.close();return;}postUi(()->{if(destroyed||generation!=loadGeneration||requestedPageGeneration!=pageGeneration){prepared.close();return;}try{if(recovered){bookId=null;book=null;onMessage("Your interrupted drawing was recovered as a separate sketch");}sketchId=selectedId;session=prepared;session.setListener(new SketchEditorSession.Listener(){public void saved(int count){updateStatus();}public void failed(String error){status.setText("Drawing conflict · local draft retained");onMessage(error+". Workspace → Recover local draft keeps it as a separate sketch.");}});present(session.document());setFrame(frame);loadingDrawing=false;canvas.setEnabled(!loadingDrawing&&!playing&&livePlayer==null);}catch(Exception error){onMessage(error.getMessage());}});}catch(Exception error){postUi(()->onMessage(error.getMessage()));}});
    }
    private void present(JSONObject value) throws Exception {
        document=value;canvas.setDocument(document);updating=true;layerIds.clear();ArrayList<String> names=new ArrayList<>();JSONArray items=document.getJSONArray("layers");
        for(int i=0;i<items.length();i++){JSONObject layer=items.getJSONObject(i);layerIds.add(layer.getString("id"));names.add(layer.getString("name")+(layer.getBoolean("locked")?" 🔒":"")+(!layer.getBoolean("visible")?" (hidden)":""));}
        layers.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));layers.setSelection(Math.max(0,layerIds.indexOf(canvas.getLayerId())));frames.setMax((bookId==null?document.getInt("frameCount"):book.getInt("pageCount"))-1);frames.setProgress(bookId==null?canvas.getFrame():bookPage);updating=false;
        getPreferences(MODE_PRIVATE).edit().putString("lastSketchId",sketchId).putString("lastBookId",bookId).putInt("lastBookPage",bookPage).apply();updateBrush();updateStatus();
    }
    @Override public void onOperation(JSONObject operation) {
        try{if("mirror_gesture".equals(operation.optString("type"))){apply(new JSONArray().put(new JSONObject().put("type","add_object").put("layerId",operation.getString("layerId")).put("object",operation.getJSONObject("object"))).put(new JSONObject().put("type","add_object").put("layerId",operation.getString("layerId")).put("object",operation.getJSONObject("mirror"))));}else apply(new JSONArray().put(operation));}catch(Exception error){onMessage(error.getMessage());}
    }
    private void apply(JSONArray operations) {
        if(session==null||loadingDrawing)return;if(livePlayer!=null){onMessage("Tap Draw myself to pause live drawing before editing");return;}stopPlayback();try{session.submit(operations);present(session.document());}catch(Exception error){onMessage(error.getMessage());}
    }
    @Override public void onColorPicked(String value){brushColor=value;toolPicker.setSelection(0);updateBrush();}
    private void updateBrush(){if(canvas==null)return;canvas.setBrush(brushColor,brushWidth,brushFill);try{canvas.setBrushStyle(SketchBrushes.style(brushPreset,brushOpacity,brushHardness,true,brushAngle),smoothing);}catch(Exception error){onMessage(error.getMessage());}if(toolStatus!=null)toolStatus.setText(brushPreset+" · "+(document==null?"":Math.round(brushWidth*Math.min(document.optInt("width"),document.optInt("height")))+" px · ")+brushColor+" · pinch to zoom / two fingers to pan");}
    private void postUi(Runnable work){if(!destroyed)handler.post(()->{if(!destroyed)work.run();});}
    private interface IoAction{void run() throws Exception;}
    private void afterSaves(IoAction work){if(destroyed)return;SketchEditorSession active=session;io.execute(()->{try{if(active!=null){active.awaitIdle(15000);if(active.error()!=null)throw new IllegalStateException(active.error());}work.run();}catch(Exception error){postUi(()->onMessage(error.getMessage()));}});}
    private JSONObject request() throws Exception { return new JSONObject().put("sketchId", sketchId).put("operationId", UUID.randomUUID().toString()).put("expectedRevision", document.getLong("revision")); }
    @Override public void onSelection(String id) { updateStatus(); }
    @Override public void onMessage(String message) { if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void updateStatus() {
        try { if (document != null) status.setText((bookId==null?document.getString("name"):book.optString("name")+" · page "+(bookPage+1)+"/"+book.optInt("pageCount")) + " · r" + document.getLong("revision") + " · frame " + (canvas.getFrame() + 1) + "/" + document.getInt("frameCount") + " · " + document.getInt("fps") + " FPS" + (canvas.getSelectedId() == null ? "" : " · selected")+(session!=null&&session.pending()>0?" · saving":"")+(watching?" · live agent":""));filmstrip.setFrames(bookId==null?document.getInt("frameCount"):book.getInt("pageCount"),bookId==null?canvas.getFrame():bookPage); }
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
    private void bookTimingDialog(){LinearLayout form=column();EditText fps=input("Book FPS 24, 30 or 60",String.valueOf(book.optInt("fps")),true),hold=input("Current page exposure in frames",String.valueOf(book.optJSONObject("exposures")==null?1:book.optJSONObject("exposures").optInt(String.valueOf(bookPage),1)),true);form.addView(fps);form.addView(hold);new AlertDialog.Builder(this).setTitle("Flipbook exposure timing").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{try{JSONObject args=new JSONObject().put("bookId",bookId).put("expectedRevision",book.getLong("revision")).put("operationId",UUID.randomUUID().toString()).put("fps",Integer.parseInt(fps.getText().toString())).put("exposures",new JSONArray().put(new JSONObject().put("page",bookPage).put("frames",Integer.parseInt(hold.getText().toString()))));afterSaves(()->{JSONObject result=SketchEditOps.execute(this,"sketch_book_edit",args);postUi(()->{book=result.optJSONObject("book");updateStatus();});});}catch(Exception error){onMessage(error.getMessage());}}).show();}
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
    private void exposureDialog() {
        if(document==null)return;
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
                try { ProjectStore.Project project = owners.get(index); JSONObject args = request().put("projectId", project.id).put("sourceProjectUpdatedAt", project.updatedAt).put("assetId", assets.get(index).id)
                        .put("layerId", canvas.getLayerId()).put("objectId", UUID.randomUUID().toString()).put("frame", canvas.getFrame()).put("exposure", document.getInt("frameCount") - canvas.getFrame());
                    afterSaves(()->{args.put("expectedRevision",document.getLong("revision"));SketchEditOps.execute(this,"sketch_import_reference",args);postUi(this::reload);});
                } catch (Exception e) { onMessage(e.getMessage()); }
            }).show();
        } catch (Exception e) { onMessage(e.getMessage()); }
    }

    private static boolean hasArtwork(JSONObject doc) throws Exception {JSONArray layers=doc.getJSONArray("layers");for(int i=0;i<layers.length();i++)if(layers.getJSONObject(i).getJSONArray("objects").length()>0)return true;return false;}
    private void workspaceDialog(){
        if(document==null)return;String[] choices={"Fit canvas","Grid guides","Mirror drawing","Onion skin settings","Live ChatGPT drawing","Reference crop / tracing","Recover local draft as a separate sketch"};
        new AlertDialog.Builder(this).setTitle("Drawing workspace").setItems(choices,(d,index)->{try{switch(index){case 0:canvas.resetView();break;case 1:grid=!grid;canvas.setGuides(grid,symmetry);break;case 2:symmetry=!symmetry;canvas.setGuides(grid,symmetry);break;case 3:onionDialog();break;case 4:toggleLiveWatch();break;case 5:referenceTools();break;case 6:String source=sketchId;io.execute(()->{try{if(session!=null)session.awaitIdle(15000);String recovered=SketchEditorSession.recoverAsCopy(this,source);postUi(()->{bookId=null;book=null;loadSketch(recovered,0);});}catch(Exception error){postUi(()->onMessage(error.getMessage()));}});break;}}catch(Exception error){onMessage(error.getMessage());}}).show();
    }
    private void onionDialog(){LinearLayout form=column();EditText distance=input("Frames on each side (1..3)","1",true),opacity=input("Onion opacity (0..128)","48",true);form.addView(distance);form.addView(opacity);new AlertDialog.Builder(this).setTitle("Red previous / blue next").setView(form).setPositiveButton("Apply",(d,w)->{try{canvas.setOnionOptions(Integer.parseInt(distance.getText().toString()),Integer.parseInt(opacity.getText().toString()));}catch(Exception error){onMessage(error.getMessage());}}).setNegativeButton("Cancel",null).show();}
    private void celDialog(){if(document==null)return;String[] choices=bookId==null?new String[]{"Duplicate drawing to next cel","Insert blank frame here","Delete current frame","Clear current layer cel"}:new String[]{"Duplicate drawing to next page","Clear current layer"};new AlertDialog.Builder(this).setTitle("Flipbook cels").setItems(choices,(d,index)->{try{if(index==0){duplicateCel();return;}if(bookId!=null){onOperation(new JSONObject().put("type","clear_cel").put("layerId",canvas.getLayerId()).put("frame",0));return;}String kind=index==1?"insert_frame":index==2?"delete_frame":"clear_cel";JSONObject op=new JSONObject().put("type",kind).put("frame",canvas.getFrame());if(index==3)op.put("layerId",canvas.getLayerId());onOperation(op);setFrame(canvas.getFrame());}catch(Exception error){onMessage(error.getMessage());}}).show();}
    private void referenceTools(){
        try{String objectId=canvas.getSelectedId();if(objectId==null||!"image".equals(SketchDocument.object(document,objectId).getString("kind"))){onMessage("Choose Select / move and select a reference image first");return;}JSONObject image=SketchDocument.object(document,objectId),crop=image.optJSONObject("referenceCrop");LinearLayout form=column();String[] names={"x","y","width","height"};EditText[] fields=new EditText[4];for(int i=0;i<4;i++){fields[i]=input("Reference crop "+names[i]+" (0..1)",String.valueOf(crop==null?(i<2?0:1):crop.getDouble(names[i])),true);form.addView(fields[i]);}EditText alpha=input("Reference opacity 0..1",String.valueOf(image.getJSONObject("transform").getDouble("opacity")),true),threshold=input("Edge trace threshold .01..1","0.15",true);form.addView(alpha);form.addView(threshold);
            new AlertDialog.Builder(this).setTitle("Crop, fade and trace owned reference").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Apply crop / opacity",(d,w)->{try{JSONObject value=new JSONObject();for(int i=0;i<4;i++)value.put(names[i],Double.parseDouble(fields[i].getText().toString()));apply(new JSONArray().put(new JSONObject().put("type","update_object").put("objectId",objectId).put("patch",new JSONObject().put("referenceCrop",value))).put(new JSONObject().put("type","set_transform").put("objectId",objectId).put("transform",new JSONObject().put("opacity",Double.parseDouble(alpha.getText().toString())))));}catch(Exception error){onMessage(error.getMessage());}})
                .setNeutralButton("Trace to editable layer",(d,w)->{try{String layerId=UUID.randomUUID().toString();JSONObject trace=new JSONObject().put("type","trace_reference").put("objectId",objectId).put("layerId",layerId).put("frame",canvas.getFrame()).put("threshold",Double.parseDouble(threshold.getText().toString())).put("idPrefix",UUID.randomUUID().toString());JSONArray ops=new JSONArray().put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",layerId).put("name","Reference contours"))).put(trace);afterSaves(()->{SketchEditOps.execute(this,"sketch_edit",request().put("operations",ops));postUi(this::reload);});}catch(Exception error){onMessage(error.getMessage());}}).show();
        }catch(Exception error){onMessage(error.getMessage());}
    }
    private void booksDialog(){stopPlayback();io.execute(()->{try{JSONArray books=SketchEditOps.execute(this,"sketch_book_list",new JSONObject()).getJSONArray("books");String[] names=new String[books.length()+1];names[0]="New sketch book · up to 10000 pages";for(int i=0;i<books.length();i++)names[i+1]=books.getJSONObject(i).getString("name")+" · "+books.getJSONObject(i).getInt("pageCount")+" pages";postUi(()->new AlertDialog.Builder(this).setTitle("Paged animation books").setItems(names,(d,index)->{try{if(index==0)newBookDialog();else openBookPage(books.getJSONObject(index-1).getString("bookId"),0);}catch(Exception error){onMessage(error.getMessage());}}).show());}catch(Exception error){postUi(()->onMessage(error.getMessage()));}});}
    private void newBookDialog(){LinearLayout form=column();EditText name=input("Book name","Untitled animation book",false),count=input("Pages 1..10000","1000",true),fps=input("FPS 24, 30 or 60","24",true);form.addView(name);form.addView(count);form.addView(fps);new AlertDialog.Builder(this).setTitle("New frame-by-frame sketch book").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Create",(d,w)->{try{JSONObject args=new JSONObject().put("bookId",UUID.randomUUID().toString()).put("operationId",UUID.randomUUID().toString()).put("name",name.getText().toString()).put("pageCount",Integer.parseInt(count.getText().toString())).put("fps",Integer.parseInt(fps.getText().toString()));afterSaves(()->{SketchEditOps.execute(this,"sketch_book_create",args);postUi(()->openBookPage(args.optString("bookId"),0));});}catch(Exception error){onMessage(error.getMessage());}}).show();}
    private void openBookPage(String id,int page){if(destroyed)return;stopPlayback();loadingDrawing=true;canvas.setEnabled(false);if(session!=null&&session.pending()>0){afterSaves(()->postUi(()->openBookPage(id,page)));return;}int generation=++pageGeneration;io.execute(()->{try{JSONObject result=SketchEditOps.execute(this,"sketch_book_open_page",new JSONObject().put("bookId",id).put("page",page));postUi(()->{if(destroyed||generation!=pageGeneration)return;bookId=id;book=result.optJSONObject("book");bookPage=page;loadSketch(result.optString("sketchId"),0);});}catch(Exception error){postUi(()->onMessage(error.getMessage()));}});}
    private void selectFilmstrip(int index){if(document==null)return;if(bookId==null)setFrame(index);else if(index>=0&&index<book.optInt("pageCount")&&index!=bookPage)openBookPage(bookId,index);}
    private void playBookTick(){
        if(book==null||watchInFlight){handler.postDelayed(playTick,16);return;}int fps=book.optInt("fps"),count=book.optInt("pageCount");int page=(int)((bookPage+1)%count);String id=bookId;watchInFlight=true;
        io.execute(()->{try{JSONObject loaded;String sketch=id+"_p"+page;try(SketchProjectStore store=new SketchProjectStore(this)){try{loaded=store.get(sketch);}catch(IllegalArgumentException unknown){if(!unknown.getMessage().startsWith("UNKNOWN_SKETCH"))throw unknown;loaded=SketchDocument.create(new JSONObject().put("sketchId",sketch).put("name",book.optString("name")).put("width",book.optInt("width")).put("height",book.optInt("height")).put("fps",fps).put("frameCount",1).put("background",book.optString("background")));}}
            JSONObject drawing=loaded;postUi(()->{watchInFlight=false;if(!playing||destroyed)return;try{bookPage=page;canvas.setDocument(drawing);frames.setProgress(page);filmstrip.setFrames(count,page);handler.postDelayed(playTick,Math.max(1,1000*(book.optJSONObject("exposures")==null?1:book.optJSONObject("exposures").optInt(String.valueOf(page),1))/fps));}catch(Exception error){stopPlayback();onMessage(error.getMessage());}});
        }catch(Exception error){postUi(()->{watchInFlight=false;stopPlayback();onMessage(error.getMessage());});}});
    }
    private void stopLivePlayer(){if(livePlayer!=null){livePlayer.close();livePlayer=null;}canvas.setPresentation(null,1);canvas.setEnabled(session!=null&&!loadingDrawing&&!playing&&!destroyed);liveDrawingObject=null;}
    private void updateLiveControl(){if(liveButton!=null){liveButton.setText(watching?"Draw myself":"Watch ChatGPT");liveButton.setContentDescription(watching?"Pause live drawing and draw yourself":"Resume watching every drawing step");}}
    private void toggleLiveWatch(){
        if(watching){takeOverLive(null);return;}
        ++liveLoadGeneration;stopPlayback();watching=true;getPreferences(MODE_PRIVATE).edit().putBoolean("watchLiveDrawing",true).apply();updateLiveControl();
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
            JSONObject current;try(SketchProjectStore store=new SketchProjectStore(this)){current=store.get(id);}
            postUi(()->{if(!resumed||liveGeneration!=liveLoadGeneration||session!=active||generation!=loadGeneration||page!=pageGeneration)return;try{
                if(!session.adopt(current))throw new IllegalStateException("Your local drawing save is still pending");
                present(session.document());canvas.setFrame(Math.min(frame,document.optInt("frameCount")-1));loadingDrawing=false;canvas.setEnabled(!playing);updateStatus();
                if(action!=null)action.run();
            }catch(Exception error){status.setText("Drawing retained · "+error.getMessage());onMessage(error.getMessage());}});
        }catch(Exception error){postUi(()->{if(session==active){status.setText("Drawing retained · "+error.getMessage());onMessage(error.getMessage());}});}});
    }
    private boolean currentLive(int generation,SketchEditorSession active){return !destroyed&&resumed&&watching&&generation==liveLoadGeneration&&session==active;}
    private void startLivePlayer(String streamId){watchInFlight=true;String id=sketchId;SketchEditorSession active=session;int generation=++liveLoadGeneration;io.execute(()->{try{JSONObject baseline=SketchLiveDrawing.read(this,id,streamId);postUi(()->{watchInFlight=false;if(!currentLive(generation,active)||loadingDrawing||session.pending()>0)return;try{stopLivePlayer();liveSessionId=streamId;liveDisplayedRevision=-1;livePlayer=new SketchLivePlayer(this,baseline,new SketchLivePlayer.Listener(){
        public void drawing(JSONObject drawing,String objectId,float fraction,String caption){if(!currentLive(generation,active))return;try{if(objectId==null||!objectId.equals(liveDrawingObject)||!caption.equals(liveCaption)){canvas.setDocument(drawing);liveDrawingObject=objectId;liveCaption=caption;}if(objectId!=null)canvas.setFrame(SketchDocument.object(drawing,objectId).getInt("frame"));canvas.setPresentation(objectId,fraction);canvas.setEnabled(false);status.setText(caption);}catch(Exception error){failed(error.getMessage());}}
        public void committed(JSONObject drawing){if(!currentLive(generation,active))return;try{if(session.adopt(drawing)){present(session.document());canvas.setPresentation(null,1);liveDrawingObject=null;liveDisplayedRevision=drawing.optLong("revision");}}catch(Exception error){failed(error.getMessage());}}
        public void finished(long cursor){if(!currentLive(generation,active))return;io.execute(()->{try(SketchProjectStore store=new SketchProjectStore(SketchStudioActivity.this)){store.consumeLiveSession(cursor);}});getPreferences(MODE_PRIVATE).edit().putLong("lastLiveSequence",cursor).apply();stopLivePlayer();reload();}
        public void idle(){if(!currentLive(generation,active))return;canvas.setPresentation(null,1);canvas.setEnabled(false);status.setText("Live ChatGPT · waiting for drawing steps · Play or Draw myself is available");}
        public void failed(String error){if(!currentLive(generation,active))return;loadingDrawing=true;stopLivePlayer();status.setText(error);onMessage(error);}
    });livePlayer.start();}catch(Exception error){onMessage(error.getMessage());}});}catch(Exception error){postUi(()->{watchInFlight=false;onMessage(error.getMessage());});}});}
    private final Runnable watchTick=new Runnable(){public void run(){
        if(destroyed)return;
        if(resumed&&watching&&!loadingDrawing&&!playing&&livePlayer==null&&session!=null&&session.pending()==0&&session.error()==null&&!canvas.isDrawing()&&!watchInFlight){
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
    private void togglePlayback() { if (playing) { stopPlayback(); return; } if (document == null) return; playing = true;canvas.setEnabled(false); playStarted = SystemClock.uptimeMillis(); playFirst = canvas.getFrame();if(bookId!=null&&session.pending()>0){playing=false;afterSaves(()->postUi(this::togglePlayback));return;} playButton.setText("Pause"); postUi(playTick); }
    private final Runnable playTick = new Runnable() { @Override public void run() {
        if (!playing || document == null) return; if(bookId!=null){playBookTick();return;}int fps = document.optInt("fps"), count = document.optInt("frameCount"); long elapsed = SystemClock.uptimeMillis() - playStarted;
        long index = elapsed * fps / 1000; int frame = (int) ((playFirst + index) % count); canvas.setFrame(frame); frames.setProgress(frame); updateStatus();
        long nextAt = NativeFrameClock.presentationTimeMs(index + 1, fps); handler.postDelayed(this, Math.max(1, nextAt - elapsed));
    } };
    private void stopPlayback() { boolean wasPlaying=playing;playing = false;if(wasPlaying&&bookId!=null&&session!=null&&!destroyed){openBookPage(bookId,bookPage);} handler.removeCallbacks(playTick); if(canvas!=null&&!destroyed)canvas.setEnabled(!loadingDrawing&&(!wasPlaying||bookId==null)&&session!=null&&livePlayer==null);if (playButton != null) playButton.setText("Play"); }
    @Override protected void onPause() { resumed=false;++liveLoadGeneration;if(livePlayer!=null||watchInFlight)loadingDrawing=true;stopLivePlayer();stopPlayback();handler.removeCallbacks(watchTick);super.onPause(); }
    @Override protected void onResume() { super.onResume();resumed=true;liveSessionId="";if(loadingDrawing&&session!=null){if(session.pending()==0)reload();else afterSaves(()->postUi(this::reload));}handler.removeCallbacks(watchTick);handler.postDelayed(watchTick,250); }
    @Override protected void onDestroy() { destroyed=true;stopLivePlayer();stopPlayback();handler.removeCallbacksAndMessages(null);if(session!=null)session.close();io.shutdown();if(canvas!=null)canvas.dispose();super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("bookId",bookId);state.putInt("bookPage",bookPage);state.putString("sketchId", sketchId); state.putInt("frame", canvas.getFrame()); super.onSaveInstanceState(state); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(0xff0e1828);layout.setPadding(dp(6),dp(2),dp(6),dp(2));return layout; }
    private LinearLayout row(LinearLayout root) { HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); scroll.addView(layout); root.addView(scroll); return layout; }
    private Button button(LinearLayout row, String title, Runnable action) { Button button = new Button(this); button.setText(title); button.setTextSize(11);button.setAllCaps(false);button.setTextColor(0xffdce9f5);button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff1b2d46));button.setOnClickListener(v -> {try{if(loadingDrawing&&!java.util.Arrays.asList("New","Open","Sketch books","Workspace","Draw myself").contains(title)){onMessage("Opening the selected drawing");return;}if(playing&&!title.equals("Play")&&!title.equals("Draw myself")){onMessage("Pause playback before changing artwork");return;}if((livePlayer!=null||watchInFlight)&&watching&&!title.equals("Workspace")&&!title.equals("Draw myself")){takeOverLive(action);return;}action.run();}catch(Exception error){onMessage(error.getMessage());}}); row.addView(button); return button; }
    private EditText input(String hint, String value, boolean number) { EditText input = new EditText(this); input.setHint(hint);input.setTextColor(Color.WHITE);input.setHintTextColor(0xff90a7bf);input.setContentDescription(hint);input.setText(value); input.setSingleLine(); if (number) input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED); return input; }
    private int dp(int pixels) { return Math.round(pixels * getResources().getDisplayMetrics().density); }
    private abstract static class SimpleSelection implements android.widget.AdapterView.OnItemSelectedListener {
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { selected(position); }
        public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        public abstract void selected(int position);
    }
}
