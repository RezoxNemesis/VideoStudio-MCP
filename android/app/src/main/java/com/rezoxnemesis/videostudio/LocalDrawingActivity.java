package com.rezoxnemesis.videostudio;
import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.widget.*;
import android.view.View;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Human controls call the same bounded native operations as MCP. */
public final class LocalDrawingActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final List<Button> buttons=new ArrayList<>();private final List<String> imageIds=new ArrayList<>();
 private String sketchId,lastRequest="";private JSONObject opened;private boolean destroyed,busy;private Future<?> task;
 private final List<String> layerIds=new ArrayList<>();
 private Spinner keyLayer;private EditText keyStart,keyExposure;
 private Spinner source;private EditText prompt,negative,strength,steps,seed,guidance,x,y,w,h;private TextView status;private ProgressBar progress;private ImageView preview;private Bitmap shown;
 @Override public void onCreate(Bundle state){super.onCreate(state);sketchId=getIntent().getStringExtra("sketchId");
  ScrollView scroll=new ScrollView(this);LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,20,20,20);scroll.addView(form);setContentView(scroll);
  TextView title=new TextView(this);title.setText("Local AI drawing");title.setTextSize(24);form.addView(title);
  TextView help=new TextView(this);help.setText("Generate a 512 px reference-conditioned raster draft on this device. The optional model download is about 4 GiB and needs a 64-bit process with 6 GiB available RAM. Your current drawing stays unchanged. Drafts need visual review; they do not recover hand-drawn or character layers.");form.addView(help);
  source=new Spinner(this);source.setContentDescription("Owned reference image");form.addView(source);
  prompt=field(form,"Drawing instructions","A detailed graphite pencil drawing, accurate proportions, fine facial features and hair strands, anatomical shading, pencil texture on white paper",false);
  LinearLayout advanced=new LinearLayout(this);advanced.setOrientation(LinearLayout.VERTICAL);advanced.setVisibility(View.GONE);
  negative=field(advanced,"Avoid","distorted anatomy, extra fingers, blurry, flat vector shapes",false);
  strength=field(advanced,"Change strength 0.1–1","0.5",true);steps=field(advanced,"Denoising steps 4–30","20",true);seed=field(advanced,"Noise seed","41718",true);guidance=field(advanced,"Prompt guidance 1–12","7",true);
  TextView crop=new TextView(this);crop.setText("Region in the full oriented reference: 0–1. Use 0,0,1,1 for the whole image.");advanced.addView(crop);
  x=field(advanced,"Source x","0",true);y=field(advanced,"Source y","0",true);w=field(advanced,"Source width","1",true);h=field(advanced,"Source height","1",true);
  button(form,"Advanced settings",()->advanced.setVisibility(advanced.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));form.addView(advanced);
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);form.addView(progress);status=new TextView(this);status.setText("Checking references…");form.addView(status);
  button(form,"Refresh references",this::loadReferences);button(form,"Check local model",()->operation("local_drawing_models",new JSONObject()));
  button(form,"Download or resume model (about 4 GiB)",()->{try{operation("local_drawing_install",new JSONObject().put("packId",LocalDrawingModels.ID));}catch(Exception e){error(e);}});
  button(form,"Generate new draft",this::generate);
  button(form,"Resume saved study",()->saved(true));button(form,"Show saved draft",()->saved(false));
  button(form,"Use draft as a new drawing guide",this::openDraft);
  TextView keyHelp=new TextView(this);keyHelp.setText("AI key drawings · Choose an unlocked layer and exact exposure. This places the generated raster draft as an undoable image cel; it does not create in-betweens or recover drawing layers.");form.addView(keyHelp);
  keyLayer=new Spinner(this);keyLayer.setContentDescription("AI key target layer");form.addView(keyLayer);
  keyStart=field(form,"Start frame (1 based)",String.valueOf(getIntent().getIntExtra("frameIndex",0)+1),true);
  keyExposure=field(form,"Exposure (frames)","1",true);
  button(form,"Place draft as an animation key",()->placeKey(false));
  button(form,"Retry last key placement",()->placeKey(true));
  button(form,"Return to sketch",()->startActivity(new Intent(this,SketchStudioActivity.class).putExtra("sketchId",sketchId)));

  Button cancel=new Button(this);cancel.setText("Cancel current work; keep checkpoints");cancel.setOnClickListener(v->{if(task!=null&&busy){status.setText("Cancelling; completed checkpoints are kept.");task.cancel(true);worker.submit(()->ui(()->{busy=false;for(Button b:buttons)b.setEnabled(true);}));}});form.addView(cancel);
  preview=new ImageView(this);preview.setAdjustViewBounds(true);preview.setContentDescription("Locally generated raster draft preview");form.addView(preview,new LinearLayout.LayoutParams(-1,-2));loadReferences();
 }
 private EditText field(LinearLayout form,String label,String value,boolean number){TextView caption=new TextView(this);caption.setText(label);form.addView(caption);EditText e=new EditText(this);e.setContentDescription(label);e.setText(value);if(number){e.setSingleLine();e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);}form.addView(e);return e;}
 private void button(LinearLayout form,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());buttons.add(b);form.addView(b);}
 private void loadReferences(){if(busy)return;run(()->{if(sketchId==null||sketchId.isEmpty())throw new IllegalArgumentException("Open a sketch and import its reference first");JSONObject doc;try(SketchProjectStore store=new SketchProjectStore(this)){doc=store.get(sketchId);}List<String> ids=new ArrayList<>(),names=new ArrayList<>(),unlockedIds=new ArrayList<>(),unlockedNames=new ArrayList<>();JSONArray layers=doc.getJSONArray("layers");for(int i=0;i<layers.length();i++){JSONObject l=layers.getJSONObject(i);if(!l.optBoolean("locked",false)){unlockedIds.add(l.getString("id"));unlockedNames.add(l.getString("name"));}JSONArray objects=l.getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject o=objects.getJSONObject(j);if("image".equals(o.getString("kind"))){ids.add(o.getString("id"));names.add(l.getString("name")+" · "+o.getString("id"));}}}ui(()->{opened=doc;layerIds.clear();layerIds.addAll(unlockedIds);keyLayer.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,unlockedNames));imageIds.clear();imageIds.addAll(ids);source.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));lastRequest=getPreferences(MODE_PRIVATE).getString("lastRequest_"+sketchId,"");status.setText(ids.isEmpty()?"Import a reference into this sketch before generating.":"Choose a reference and check/download the local model. No hosted inference.");});});}
 private void generate(){try{if(opened==null||source.getSelectedItemPosition()<0||imageIds.isEmpty())throw new IllegalArgumentException("Choose an imported reference first");String object=imageIds.get(source.getSelectedItemPosition());JSONObject ref=SketchDocument.object(opened,object).getJSONObject("reference");JSONObject settings=new JSONObject().put("prompt",prompt.getText().toString()).put("negativePrompt",negative.getText().toString()).put("strength",Double.parseDouble(strength.getText().toString())).put("steps",Integer.parseInt(steps.getText().toString())).put("seed",Long.parseLong(seed.getText().toString())).put("guidance",Double.parseDouble(guidance.getText().toString()));JSONObject rect=new JSONObject().put("x",Double.parseDouble(x.getText().toString())).put("y",Double.parseDouble(y.getText().toString())).put("width",Double.parseDouble(w.getText().toString())).put("height",Double.parseDouble(h.getText().toString()));JSONObject request=new JSONObject().put("requestId","study_"+UUID.randomUUID().toString().replace("-","")).put("sketchId",sketchId).put("expectedRevision",opened.getLong("revision")).put("objectId",object).put("sourceSha256",ref.getString("sha256")).put("sourceRect",rect).put("settings",settings);LocalDrawingSource.validate(request);lastRequest=request.getString("requestId");getPreferences(MODE_PRIVATE).edit().putString("lastRequest_"+sketchId,lastRequest).putString("saved_"+lastRequest,request.toString()).commit();operation("local_drawing_generate",request);}catch(Exception e){error(e);}}
 private void saved(boolean resume){try{String id=getPreferences(MODE_PRIVATE).getString("lastRequest_"+sketchId,"");if(id.isEmpty())throw new IllegalArgumentException("No saved AI study for this sketch");lastRequest=id;if(resume){String request=getPreferences(MODE_PRIVATE).getString("saved_"+id,"");if(request.isEmpty())throw new IllegalArgumentException("Saved study request is unavailable");operation("local_drawing_generate",new JSONObject(request));}else operation("local_drawing_get",new JSONObject().put("requestId",id).put("includePreview",true));}catch(Exception e){error(e);}}
 private void operation(String action,JSONObject p){run(()->{try(AutoCloseable lane="local_drawing_generate".equals(action)?JobManager.acquireInteractiveHeavyLane():()->{}){JSONObject result=LocalDrawingOperations.execute(this,action,p,(n,s)->ui(()->{progress.setProgress(n);status.setText(s);}));
  if("completed".equals(result.optString("state")))result=LocalDrawingOperations.execute(this,"local_drawing_get",new JSONObject().put("requestId",result.getString("requestId")).put("includePreview",true),null);
  Bitmap image=null;if(result.has("localDrawingPreviewImage")){JSONObject png=result.getJSONObject("localDrawingPreviewImage");byte[] bytes=Base64.decode(png.getString("base64"),Base64.DEFAULT);if(bytes.length>4*1024*1024||!SketchDocument.sha256(bytes).equals(png.getString("sha256")))throw new IllegalStateException("AI preview checksum differs");image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(image==null||image.getWidth()!=512||image.getHeight()!=512){if(image!=null)image.recycle();throw new IllegalStateException("AI preview dimensions differ");}}
  String message=result.has("modelInstalled")?(result.optBoolean("modelInstalled")?"Model installed. ":"Model not installed. ")+"Available RAM: "+result.optLong("availableRamMb")+" MiB; required: 6144 MiB. "+(result.optBoolean("process64Bit")?"64-bit process.":"This process is 32-bit; the model cannot run."):"Study: "+result.optString("state")+" · "+result.optString("requestId")+"\n"+result.optString("stage")+"\nVisual quality requires review.";Bitmap ready=image;runOnUiThread(()->{if(destroyed){if(ready!=null)ready.recycle();return;}status.setText(message);if(ready!=null){Bitmap old=shown;shown=ready;preview.setImageBitmap(ready);if(old!=null)old.recycle();}});
 }});}
 private void placeKey(boolean retry){
  try{
   if(opened==null)throw new IllegalArgumentException("Refresh this sketch before placing a key");
   final JSONObject request;
   if(retry){String saved=getPreferences(MODE_PRIVATE).getString("keyPlacement_"+sketchId,"");if(saved.isEmpty())throw new IllegalArgumentException("No previous key placement to retry");request=new JSONObject(saved);}
   else{
    if(lastRequest.isEmpty()||layerIds.isEmpty()||keyLayer.getSelectedItemPosition()<0)throw new IllegalArgumentException("Choose a completed draft and an unlocked target layer");
    int first=Integer.parseInt(keyStart.getText().toString())-1,count=Integer.parseInt(keyExposure.getText().toString());
    if(first<0||count<1||(long)first+count>opened.getInt("frameCount"))throw new IllegalArgumentException("Key exposure must fit the existing sketch duration");
    request=new JSONObject().put("sketchId",sketchId).put("expectedRevision",opened.getLong("revision")).put("layerId",layerIds.get(keyLayer.getSelectedItemPosition())).put("objectId","ai_key_"+UUID.randomUUID().toString().replace("-","")).put("operationId","place_ai_"+UUID.randomUUID().toString().replace("-","")).put("frame",first).put("exposure",count);
   }
   final String studyId=lastRequest;
   run(()->{
    if(!retry){JSONObject draft=LocalDrawingOperations.completed(this,studyId);ProjectStore projects=new ProjectStore(this);ProjectStore.Project project=projects.get(draft.getString("outputProjectId"));if(project==null)throw new IllegalArgumentException("Draft project unavailable");request.put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId",draft.getString("outputAssetId")).put("localDrawing",new JSONObject().put("requestId",studyId).put("outputSha256",draft.getString("outputSha256")));if(!getPreferences(MODE_PRIVATE).edit().putString("keyPlacement_"+sketchId,request.toString()).commit())throw new IllegalStateException("Cannot save key placement for safe retry");}
    JSONObject result=SketchEditOps.execute(this,"sketch_import_reference",request);JSONObject fresh;try(SketchProjectStore sketches=new SketchProjectStore(this)){fresh=sketches.get(sketchId);}ui(()->{opened=fresh;status.setText("AI raster key placed at frame "+(request.optInt("frame")+1)+" for "+request.optInt("exposure")+" frame(s). Undo is available in Sketch Studio. Revision "+result.optLong("revision")+". Visual quality still requires review.");});
   });
  }catch(Exception e){error(e);}
 }
 private void openDraft(){if(lastRequest.isEmpty()){saved(false);return;}run(()->{JSONObject draft=LocalDrawingOperations.execute(this,"local_drawing_get",new JSONObject().put("requestId",lastRequest),null);if(!"completed".equals(draft.getString("state")))throw new IllegalArgumentException("Complete the local AI study before opening its draft");ProjectStore projects=new ProjectStore(this);ProjectStore.Project project=projects.get(draft.getString("outputProjectId"));if(project==null)throw new IllegalArgumentException("Draft project is unavailable");String id="ai_guide_"+UUID.randomUUID().toString().replace("-","");SketchEditOps.execute(this,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new_ai_guide").put("name","AI-generated raster draft · reference").put("width",512).put("height",512).put("frameCount",1));SketchEditOps.execute(this,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","import_ai_guide").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId",draft.getString("outputAssetId")).put("layerId","layer_1").put("objectId","ai_generated_reference").put("guideVisible",true));ui(()->startActivity(new Intent(this,SketchStudioActivity.class).putExtra("sketchId",id)));});}
 private interface Work{void run()throws Exception;}
 private void run(Work work){if(busy)return;busy=true;for(Button b:buttons)b.setEnabled(false);task=worker.submit(()->{try{work.run();}catch(Exception e){ui(()->error(e));}finally{ui(()->{busy=false;for(Button b:buttons)b.setEnabled(true);});}});}
 private void error(Exception e){if(!destroyed)status.setText(e.getMessage()==null?"Local drawing unavailable":e.getMessage());}
 private void ui(Runnable r){runOnUiThread(()->{if(!destroyed)r.run();});}
 @Override protected void onDestroy(){destroyed=true;worker.shutdownNow();if(shown!=null){preview.setImageDrawable(null);shown.recycle();shown=null;}super.onDestroy();}
}
