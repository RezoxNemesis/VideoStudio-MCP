package com.rezoxnemesis.videostudio;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Guided authored drawing. Imported pixels appear only in the separately labelled reference. */
final class SketchLayeredDrawingDialog implements AutoCloseable {
    interface Backend {
        JSONObject edit(Context context,JSONObject request)throws Exception;
        JSONObject load(Context context,String id)throws Exception;
        JSONObject preview(Context context,JSONObject document,JSONObject request)throws Exception;
    }
    interface Draw {void open(String layerId,String tool);}
    private interface Action {void run()throws Exception;}
    private static final String[] STAGES={"construction","refinement","details","clean_line","separation"};
    private static final String[] TITLES={"1 · Construction","2 · Refinement","3 · Details","4 · Clean line","5 · Separation"};
    private final Activity activity;
    private final Backend backend;
    private final ExecutorService worker;
    private final Consumer<JSONObject> saved;
    private final Draw draw;
    private final int frame;
    private final LinearLayout form,start,progress;
    private final Spinner reference,layer,tool;
    private final EditText names,note;
    private final TextView status,instructions,reviewState;
    private final Button reload,begin,previewReference,previewArtwork,previewComparison,acceptable,rework,finish,drawLayer;
    private final ArrayList<Button> stageButtons=new ArrayList<>();
    private final ArrayList<String> referenceIds=new ArrayList<>(),activeIds=new ArrayList<>();
    private final ImageView previewImage;
    private JSONObject document,pendingRequest;
    private Bitmap previewBitmap;
    private AlertDialog dialog;
    private boolean closed,busy,committing;
    private int generation,stageIndex=-1;
    private boolean nextAllowed,canFinish;
    private long comparedRevision=-1;
    private String comparedStage="",prefix="drawing_"+UUID.randomUUID().toString().replace("-","").substring(0,16);

    static SketchLayeredDrawingDialog show(Activity activity,JSONObject document,int frame,Consumer<JSONObject> saved,Draw draw)throws Exception {
        SketchLayeredDrawingDialog owner=new SketchLayeredDrawingDialog(activity,document,frame,saved,draw,new Backend(){
            public JSONObject edit(Context c,JSONObject request)throws Exception{return SketchEditOps.execute(c,"sketch_edit",request);}
            public JSONObject load(Context c,String id)throws Exception{try(SketchProjectStore store=new SketchProjectStore(c)){return store.get(id);}}
            public JSONObject preview(Context c,JSONObject document,JSONObject request)throws Exception{return SketchLayeredDrawingPreview.inspect(c,document,request);}
        },Executors.newSingleThreadExecutor());owner.open();return owner;
    }
    SketchLayeredDrawingDialog(Activity activity,JSONObject document,int frame,Consumer<JSONObject> saved,Draw draw,Backend backend,ExecutorService worker)throws Exception {
        this.activity=activity;this.document=SketchDocument.copy(document);this.frame=frame;this.saved=saved;this.draw=draw;this.backend=backend;this.worker=worker;
        if(frame<0||frame>=document.getInt("frameCount"))throw SketchDocument.bad("Open a frame inside this drawing");
        form=column();form.setPadding(dp(16),dp(8),dp(16),dp(12));
        text(form,"Build the drawing in five passes: construction, refinement, details, clean line, then separate parts. Draw each pass with the native pen or pressure brush. Review the actual result before moving forward.");
        status=text(form,"");status.setContentDescription("Layered drawing status");reload=button(form,"Reload saved drawing",this::reload);
        start=column();form.addView(start);text(start,"Start with an imported reference on its own. The reference becomes a locked, hidden guide; it stays separate from your authored layers.");
        reference=picker(start,"Drawing reference");ArrayList<String> labels=new ArrayList<>();JSONArray layers=document.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){JSONObject row=layers.getJSONObject(i);JSONArray objects=row.getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject object=objects.getJSONObject(j);if("image".equals(object.optString("kind"))){referenceIds.add(object.getString("id"));labels.add(row.getString("name")+" · reference "+referenceIds.size());}}}
        reference.setAdapter(adapter(labels));names=field(start,"Clean part names · one per line","Front character\nRear character\nHair and hands\nClothing\nProps and background",false);names.setMinLines(5);names.setSingleLine(false);
        begin=button(start,"Begin layered drawing",this::begin);
        progress=column();form.addView(progress);instructions=text(progress,"");instructions.setContentDescription("Drawing stage instructions");reviewState=text(progress,"");reviewState.setContentDescription("Drawing stage reviews");
        for(int i=0;i<STAGES.length;i++){final String stage=STAGES[i];stageButtons.add(button(progress,TITLES[i],()->submit(new JSONObject().put("type","layered_drawing_stage").put("stage",stage))));}
        layer=picker(progress,"Draw on layer");tool=picker(progress,"Layer drawing tool");tool.setAdapter(adapter(Arrays.asList("Pen","Precision Bézier pen","Pressure paint")));
        drawLayer=button(progress,"Draw selected layer",()->{int at=layer.getSelectedItemPosition();if(at<0||at>=activeIds.size())throw SketchDocument.bad("Choose an authored layer");String id=activeIds.get(at),mode=tool.getSelectedItemPosition()==1?"precision":tool.getSelectedItemPosition()==2?"paint":"stroke";close();draw.open(id,mode);});
        text(progress,"Return here from More → Layer-by-layer drawing after drawing. Earlier stages stay available for corrections. The ordinary Undo button restores saved edits.");
        previewReference=button(progress,"View reference only",()->preview("reference"));previewArtwork=button(progress,"Preview artwork only",()->preview("artwork"));previewComparison=button(progress,"Compare reference and artwork",()->preview("comparison"));
        previewImage=new ImageView(activity);previewImage.setContentDescription("Layered drawing native preview");previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);previewImage.setBackgroundColor(0xffd9d9d9);previewImage.setVisibility(View.GONE);progress.addView(previewImage,new LinearLayout.LayoutParams(-1,dp(300)));
        note=field(progress,"Review note · what works or needs correction","",false);note.setSingleLine(false);note.setMinLines(2);
        acceptable=button(progress,"Accept reviewed stage",()->review("ACCEPTABLE"));rework=button(progress,"Stage needs work",()->review("NEEDS_REWORK"));
        finish=button(progress,"Finish reviewed drawing",()->submit(new JSONObject().put("type","layered_drawing_finish")));
        text(progress,"Reviews record your decision about the displayed drawing. The app does not certify likeness, quality or animation readiness.");
        refresh();
    }
    void open(){ScrollView scroll=new ScrollView(activity);scroll.addView(form);dialog=new AlertDialog.Builder(activity).setTitle("Layer-by-layer drawing").setView(scroll).setNegativeButton("Close",(d,w)->close()).create();dialog.setOnDismissListener(d->close());dialog.show();activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);}
    private void begin()throws Exception {
        int selected=reference.getSelectedItemPosition();if(selected<0||selected>=referenceIds.size())throw SketchDocument.bad("Import a reference image before starting the drawing");
        String[] lines=names.getText().toString().split("\\r?\\n");JSONArray parts=new JSONArray();for(String line:lines){String name=line.trim();if(name.isEmpty())continue;if(name.length()>160)throw SketchDocument.bad("Part names allow at most 160 characters");parts.put(new JSONObject().put("id","part_"+(parts.length()+1)).put("name",name));}if(parts.length()<2||parts.length()>12)throw SketchDocument.bad("Name 2–12 clean parts, one per line");
        submit(new JSONObject().put("type","layered_drawing_begin").put("referenceObjectId",referenceIds.get(selected)).put("prefix",prefix).put("parts",parts));
    }
    private void review(String decision)throws Exception {
        JSONObject summary=SketchLayeredDrawing.summary(document);if(comparedRevision!=document.getLong("revision")||!comparedStage.equals(summary.getString("stage")))throw SketchDocument.bad("Compare the current reference and artwork before reviewing this stage");
        String text=note.getText().toString().trim();if(text.isEmpty()||text.length()>1000)throw SketchDocument.bad("Write a review note of 1–1000 characters before recording a decision");
        submit(new JSONObject().put("type","layered_drawing_review").put("stage",summary.getString("stage")).put("reviewedRevision",comparedRevision).put("frameIndex",frame).put("status",decision).put("note",text));
    }
    private void submit(JSONObject operation)throws Exception {
        String fingerprint=SketchDocument.canonical(operation);if(pendingRequest==null||!fingerprint.equals(SketchDocument.canonical(pendingRequest.getJSONArray("operations").getJSONObject(0))))pendingRequest=new JSONObject().put("sketchId",document.getString("id")).put("expectedRevision",document.getLong("revision")).put("operationId","layered_"+UUID.randomUUID().toString().replace("-","")).put("operations",new JSONArray().put(operation));
        final JSONObject request=SketchDocument.copy(pendingRequest);final int token=++generation;busy=committing=true;sync();status.setText("Saving this drawing step…");
        try{worker.execute(()->{try{
            JSONObject receipt=backend.edit(activity.getApplicationContext(),request);if(!receipt.optBoolean("persisted")||!request.getString("sketchId").equals(receipt.getString("sketchId")))throw SketchDocument.bad("Drawing step did not confirm a save");
            JSONObject loaded=backend.load(activity.getApplicationContext(),request.getString("sketchId"));if(!request.getString("sketchId").equals(loaded.getString("id"))||loaded.getLong("revision")<receipt.getLong("revision"))throw SketchDocument.bad("Saved drawing could not be reloaded");
            activity.runOnUiThread(()->{busy=committing=false;try{if(!activity.isDestroyed()&&!activity.isFinishing())saved.accept(loaded);if(unavailable()||token!=generation)return;document=loaded;pendingRequest=null;invalidatePreview();refresh();status.setText("Drawing step saved. Continue drawing or inspect the native preview.");}catch(Exception failure){if(!closed)message(failure);}});
        }catch(Exception|OutOfMemoryError failure){activity.runOnUiThread(()->{busy=committing=false;if(unavailable()||token!=generation)return;message(failure);sync();});}});}catch(Exception failure){busy=committing=false;message(failure);sync();}
    }
    private void reload()throws Exception{
        final String id=document.getString("id");final int token=++generation;busy=true;sync();status.setText("Opening the saved drawing…");
        try{worker.execute(()->{try{JSONObject loaded=backend.load(activity.getApplicationContext(),id);if(!id.equals(loaded.getString("id")))throw SketchDocument.bad("Loaded drawing identity changed");activity.runOnUiThread(()->{busy=false;if(unavailable()||token!=generation)return;try{document=loaded;pendingRequest=null;invalidatePreview();saved.accept(loaded);refresh();}catch(Exception failure){message(failure);sync();}});}catch(Exception failure){activity.runOnUiThread(()->{busy=false;if(unavailable()||token!=generation)return;message(failure);sync();});}});}catch(Exception failure){busy=false;message(failure);sync();}
    }
    private void preview(String view)throws Exception {
        final JSONObject snapshot=SketchDocument.copy(document),request=new JSONObject().put("view",view).put("frameIndex",frame).put("maxDimension",640);final int token=++generation;busy=true;sync();status.setText("Rendering the native "+view+" view…");
        try{worker.execute(()->{Bitmap image=null;try{
            JSONObject reply=backend.preview(activity.getApplicationContext(),snapshot,request);JSONObject workflow=snapshot.getJSONObject("layeredDrawing");if(reply.getLong("sourceRevision")!=snapshot.getLong("revision")||!snapshot.getString("id").equals(reply.getString("sketchId"))||!workflow.getString("referenceSha256").equals(reply.getString("referenceSha256"))||!workflow.getString("stage").equals(reply.getString("stage"))||!view.equals(reply.getString("view"))||reply.getInt("frameIndex")!=frame||reply.getBoolean("sourceModified")||!reply.getBoolean("artworkDoesNotContainReferencePixels"))throw SketchDocument.bad("Preview belongs to another drawing revision, stage or view");
            image=decodePreview(reply.getJSONObject("sketchPreviewImage"),640);final Bitmap shown=image;final String inspectedStage=SketchLayeredDrawing.summary(snapshot).optString("stage");
            activity.runOnUiThread(()->{busy=false;if(unavailable()||token!=generation){shown.recycle();return;}if(previewBitmap!=null)previewBitmap.recycle();previewBitmap=shown;previewImage.setImageBitmap(shown);previewImage.setVisibility(View.VISIBLE);if("comparison".equals(view)){comparedRevision=snapshot.optLong("revision");comparedStage=inspectedStage;}status.setText("reference".equals(view)?"Reference only · these imported pixels are separate from your authored drawing.":"comparison".equals(view)?"Native comparison displayed. Inspect the drawing, then write your review note.":"Authored artwork only. Compare with the reference before recording a review.");sync();});
        }catch(Exception|OutOfMemoryError failure){if(image!=null)image.recycle();activity.runOnUiThread(()->{busy=false;if(unavailable()||token!=generation)return;message(failure);sync();});}});}catch(Exception failure){busy=false;message(failure);sync();}
    }
    static Bitmap decodePreview(JSONObject encoded,int maximum)throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw SketchDocument.bad("Drawing preview decoding requires a background worker");
        if(!"image/png".equals(encoded.getString("mimeType")))throw SketchDocument.bad("Native drawing preview must be PNG");String base64=encoded.getString("base64");if(base64.length()>8*1024*1024)throw SketchDocument.bad("Drawing preview exceeds the image budget");byte[] bytes=Base64.decode(base64,Base64.DEFAULT);
        if(bytes.length<8||bytes.length>6*1024*1024||encoded.getLong("sizeBytes")!=bytes.length||!SketchDocument.sha256(bytes).equals(encoded.getString("sha256")))throw SketchDocument.bad("Drawing preview bytes changed");if(bytes[0]!=(byte)137||bytes[1]!=80||bytes[2]!=78||bytes[3]!=71||bytes[4]!=13||bytes[5]!=10||bytes[6]!=26||bytes[7]!=10)throw SketchDocument.bad("Drawing preview PNG signature rejected");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>maximum||bounds.outHeight>maximum||bounds.outWidth!=encoded.getInt("width")||bounds.outHeight!=encoded.getInt("height"))throw SketchDocument.bad("Drawing preview dimensions rejected");BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(image==null)throw SketchDocument.bad("Drawing preview could not be decoded");return image;
    }
    private void refresh()throws Exception {
        JSONObject summary=SketchLayeredDrawing.summary(document);boolean enabled=summary.optBoolean("enabled");start.setVisibility(enabled?View.GONE:View.VISIBLE);progress.setVisibility(enabled?View.VISIBLE:View.GONE);activeIds.clear();ArrayList<String> labels=new ArrayList<>();
        if(enabled){String stage=summary.getString("stage");for(int i=0;i<STAGES.length;i++)if(STAGES[i].equals(stage))stageIndex=i;nextAllowed=false;canFinish=true;JSONArray ids=SketchLayeredDrawing.activeLayerIds(document);for(int i=0;i<ids.length();i++){String id=ids.getString(i);activeIds.add(id);labels.add(SketchDocument.layer(document,id).getString("name"));}instructions.setText(stageTitle(stage)+"\n"+guidance(stage));StringBuilder reviews=new StringBuilder();JSONArray values=summary.optJSONArray("stageReviews");if(values!=null)for(int i=0;i<values.length();i++){JSONObject row=values.getJSONObject(i);canFinish&=row.optBoolean("acceptable");if(stage.equals(row.optString("stage")))nextAllowed=row.optBoolean("acceptable");reviews.append(stageTitle(row.optString("stage"))).append(row.optBoolean("acceptable")?" · reviewed":" · review needed").append('\n');}reviewState.setText(reviews.toString());status.setText("Current pass: "+stageTitle(stage)+("REVIEWED".equals(summary.optString("status"))?" · completion recorded":""));}else status.setText(referenceIds.isEmpty()?"Import a reference image from Brush / Text → Reference image first.":"Choose the reference and name the final editable parts.");
        layer.setAdapter(adapter(labels));sync();
    }
    private static String stageTitle(String stage){for(int i=0;i<STAGES.length;i++)if(STAGES[i].equals(stage))return TITLES[i];return stage;}
    private static String guidance(String stage){switch(stage){case "construction":return "Lay out the overall gesture, proportions and placement with loose native strokes.";case "refinement":return "Refine silhouettes and anatomy on the new layer; keep construction available underneath.";case "details":return "Draw hair, hands, expressions, fabric and other important details.";case "clean_line":return "Redraw the clean contours on the named part layers. Keep each intended part on its own layer.";default:return "Check the clean part layers independently. Repair joins, overlaps and missing authored lines before finishing.";}}
    private void sync(){boolean edit=!closed&&!busy;reload.setEnabled(edit);begin.setEnabled(edit&&!referenceIds.isEmpty());reference.setEnabled(edit);names.setEnabled(edit);for(int i=0;i<stageButtons.size();i++)stageButtons.get(i).setEnabled(edit&&(i<stageIndex||i==stageIndex+1&&nextAllowed));layer.setEnabled(edit);tool.setEnabled(edit);drawLayer.setEnabled(edit&&!activeIds.isEmpty());previewReference.setEnabled(edit);previewArtwork.setEnabled(edit);previewComparison.setEnabled(edit);note.setEnabled(edit);boolean reviewed=edit&&comparedRevision==document.optLong("revision");acceptable.setEnabled(reviewed);rework.setEnabled(reviewed);finish.setEnabled(edit&&stageIndex==4&&canFinish);}
    private void invalidatePreview(){comparedRevision=-1;comparedStage="";previewImage.setImageDrawable(null);previewImage.setVisibility(View.GONE);if(previewBitmap!=null){previewBitmap.recycle();previewBitmap=null;}}
    private boolean unavailable(){return closed||activity.isDestroyed()||activity.isFinishing();}
    private void message(Throwable failure){status.setText("Drawing retained: "+(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage()));}
    @Override public void close(){if(closed)return;closed=true;++generation;invalidatePreview();if(committing)worker.shutdown();else worker.shutdownNow();activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);if(dialog!=null&&dialog.isShowing())dialog.dismiss();}
    private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){if(a==activity)close();}};
    private int dp(int value){return StudioUi.dp(activity,value);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(LinearLayout parent,String value){TextView view=new TextView(activity);view.setText(value);parent.addView(view);return view;}
    private EditText field(LinearLayout parent,String label,String value,boolean numeric){text(parent,label);EditText view=new EditText(activity);view.setContentDescription(label);view.setText(value);view.setSingleLine(true);parent.addView(view);return view;}
    private Spinner picker(LinearLayout parent,String label){text(parent,label);Spinner view=new Spinner(activity);view.setContentDescription(label);parent.addView(view);return view;}
    private ArrayAdapter<String> adapter(List<String> values){return new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values);}
    private Button button(LinearLayout parent,String label,Action action){Button view=new Button(activity);view.setText(label);StudioUi.styleButton(view,label.equals("Draw selected layer"));parent.addView(view);view.setOnClickListener(v->{if(closed||busy)return;try{action.run();}catch(Exception failure){message(failure);}});return view;}
}
