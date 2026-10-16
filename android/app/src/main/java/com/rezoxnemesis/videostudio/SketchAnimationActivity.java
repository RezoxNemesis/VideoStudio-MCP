package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.AtomicFile;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Local visual authoring for prepared sketches. Preview and ChatGPT use the same compiler. */
public final class SketchAnimationActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService quality=Executors.newSingleThreadExecutor();
    private Future<?> qualityTask;
    private final Handler main=new Handler(Looper.getMainLooper());
    private JSONObject source,program,sampled=new JSONObject();
    private String sketchId,animationId,selectedRig="",selectedJoint="",interpolation="smooth";
    private long revision,sourceRevision,playStarted;
    private int frame,displayedFrame=-1,playFirst,generation,snapshotGeneration=-1,qualityRequest;
    private JSONObject previewProgramSnapshot;
    private volatile boolean destroyed;
    private boolean loading=true,dirty,mapping=true,playing,previewInFlight,previewAgain,pickerUpdating,saving;
    private static final java.util.LinkedHashMap<String,String> DRAFTS=new java.util.LinkedHashMap<>();
    private SketchFrameRenderer renderer;
    private PoseCanvas canvas;
    private TextView status,frameLabel,selectionLabel;
    private SeekBar scrub;
    private Spinner rigs,joints;
    private Button play,mode;
    private String previewError="",skinObjectId="";
    private int skinVertex=-1;
    private final ArrayList<String> rigIds=new ArrayList<>(),jointIds=new ArrayList<>();

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(StudioUi.BG);getWindow().setNavigationBarColor(StudioUi.BG);
        sketchId=state==null?getIntent().getStringExtra("sketchId"):state.getString("sketchId");
        animationId=state==null?getIntent().getStringExtra("animationId"):state.getString("animationId");
        frame=state==null?0:state.getInt("frame");if(state!=null){mapping=state.getBoolean("mapping",true);selectedRig=state.getString("selectedRig","");selectedJoint=state.getString("selectedJoint","");}buildUi();load();
    }
    private void buildUi(){
        LinearLayout root=column();root.setBackgroundColor(StudioUi.BG);root.setPadding(dp(8),dp(4),dp(8),dp(4));setContentView(root);
        LinearLayout title=row(root);button(title,"‹ Sketch",this::requestBack,false);button(title,"2D animation · Guide",this::guide,false);
        status=label("Opening prepared sketch…",12);root.addView(status);
        canvas=new PoseCanvas(this);canvas.setContentDescription("Animation canvas. In rig mode drag a joint to fit the drawing. In pose mode drag a bone endpoint to rotate its parent, or drag the root to move the character.");
        root.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));
        frameLabel=label("Frame 1",12);root.addView(frameLabel);
        scrub=new SeekBar(this);scrub.setMinimumHeight(dp(48));scrub.setContentDescription("Animation frame");root.addView(scrub);
        scrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int value,boolean human){if(human&&!loading){stopPlayback();frame=value;requestPreview();refreshFrame();}}public void onStartTrackingTouch(SeekBar s){stopPlayback();}public void onStopTrackingTouch(SeekBar s){}});
        LinearLayout transport=row(root);button(transport,"◀ Key",()->jumpKey(false),false);play=button(transport,"Play",this::togglePlayback,true);button(transport,"Key ▶",()->jumpKey(true),false);button(transport,"Timing",this::timing,false);
        ScrollView panels=new ScrollView(this);panels.setFillViewport(true);LinearLayout controls=column();panels.addView(controls);root.addView(panels,new LinearLayout.LayoutParams(-1,dp(208)));
        LinearLayout selectors=row(controls);rigs=picker("Character");joints=picker("Selected joint");selectors.addView(rigs,new LinearLayout.LayoutParams(0,dp(48),1));selectors.addView(joints,new LinearLayout.LayoutParams(0,dp(48),1));
        rigs.setOnItemSelectedListener(new Selected(){public void selected(int position){if(!pickerUpdating&&position<rigIds.size()&&!rigIds.get(position).equals(selectedRig)){selectedRig=rigIds.get(position);selectedJoint="";refreshPickers();requestPreview();}}});
        joints.setOnItemSelectedListener(new Selected(){public void selected(int position){if(!pickerUpdating&&position<jointIds.size()){selectedJoint=jointIds.get(position);canvas.invalidate();refreshFrame();}}});
        selectionLabel=label("",12);controls.addView(selectionLabel);
        LinearLayout rig=row(controls);mode=button(rig,"Rig mode",()->{mapping=!mapping;mode.setText(mapping?"Rig mode":"Pose mode");requestPreview();refreshFrame();},false);button(rig,"Rig / bind",this::rigTools,false);button(rig,"Pose tools",this::poseTools,false);
        LinearLayout effects=row(controls);button(effects,"Foot contact",this::contacts,false);button(effects,"Camera",this::camera,false);button(effects,"Cels / dialogue",this::dialogueMenu,false);
        LinearLayout production=row(controls);button(production,"Acting presets",this::motionPresets,false);button(production,"Mouth timing",this::autoMouthTiming,false);button(production,"Production QA",this::productionCheck,false);
        LinearLayout files=row(controls);button(files,"Example",this::example,false);button(files,"Open",this::openAnimation,false);button(files,"Save scene",this::saveScene,false);button(files,"Bake animation",this::bake,true);
        StudioUi.prepareTouchLayout(root);
    }
    private void load(){
        loading=true;++generation;++qualityRequest;if(qualityTask!=null)qualityTask.cancel(true);sampled=new JSONObject();final String requested=animationId;
        io.execute(()->{try{
            JSONObject loadedSource,loadedProgram;long loadedRevision=0;String loadedId=requested;
            if(requested!=null&&!requested.isEmpty()){
                JSONObject saved=SketchAnimationOps.load(this,requested);
                loadedSource=saved.getJSONObject("sourceDocument");loadedProgram=saved.getJSONObject("program");loadedRevision=saved.getLong("revision");
            }else{
                if(sketchId==null)throw SketchDocument.bad("Open a prepared sketch before starting animation");
                try(SketchProjectStore store=new SketchProjectStore(this)){loadedSource=store.get(sketchId);}
                loadedProgram=SketchAnimationUiModel.createProgram(loadedSource);JSONObject draft=readDraft(loadedSource.getString("id"));
                if(draft!=null){
                    if(draft.getLong("sourceRevision")==loadedSource.getLong("revision")){
                        loadedProgram=draft.getJSONObject("program");loadedId=draft.getString("animationId");loadedRevision=draft.getLong("revision");
                    }else post(()->message("A draft from an earlier source revision is retained. Open its saved animation or start this new source revision."));
                }
            }
            JSONObject recent=readDraft(loadedSource.getString("id"));boolean restored=false;
            if(recent!=null&&recent.getLong("sourceRevision")==loadedSource.getLong("revision")&&recent.getLong("revision")==loadedRevision&&(loadedId==null||loadedId.equals(recent.getString("animationId")))){loadedProgram=recent.getJSONObject("program");loadedId=recent.getString("animationId");restored=recent.optBoolean("dirty",true);}
            boolean restoredDraft=restored;
            JSONObject acceptedSource=SketchDocument.copy(loadedSource),acceptedProgram=SketchDocument.copy(loadedProgram);long acceptedRevision=loadedRevision;String acceptedId=loadedId;
            post(()->{source=acceptedSource;program=acceptedProgram;sourceRevision=source.optLong("revision");sketchId=source.optString("id");animationId=acceptedId==null||acceptedId.isEmpty()?"animation_"+UUID.randomUUID().toString().replace("-",""):acceptedId;revision=acceptedRevision;dirty=restoredDraft;loading=false;canvas.replace(null);mode.setText(mapping?"Rig mode":"Pose mode");frame=Math.max(0,Math.min(frame,program.optInt("frameCount")-1));refreshPickers();refreshFrame();requestPreview();});
        }catch(Exception failure){post(()->{loading=false;status.setText("Cannot open animation: "+failure.getMessage());message(failure.getMessage());});}});
    }
    private void refreshPickers(){
        if(program==null)return;try{pickerUpdating=true;rigIds.clear();JSONArray values=program.getJSONArray("rigs");for(int i=0;i<values.length();i++)rigIds.add(values.getJSONObject(i).getString("id"));
            if(!rigIds.contains(selectedRig))selectedRig=rigIds.isEmpty()?"":rigIds.get(0);rigs.setAdapter(adapter(rigIds));rigs.setSelection(Math.max(0,rigIds.indexOf(selectedRig)));
            jointIds.clear();if(!selectedRig.isEmpty()){JSONArray values2=SketchAnimationUiModel.rig(program,selectedRig).getJSONArray("joints");for(int i=0;i<values2.length();i++)jointIds.add(values2.getJSONObject(i).getString("id"));}
            if(!jointIds.contains(selectedJoint))selectedJoint=jointIds.isEmpty()?"":jointIds.get(0);joints.setAdapter(adapter(jointIds));joints.setSelection(Math.max(0,jointIds.indexOf(selectedJoint)));refreshFrame();
        }catch(Exception invalid){message(invalid.getMessage());}finally{pickerUpdating=false;}
    }
    private ArrayAdapter<String> adapter(java.util.List<String> items){return new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,items){@Override public View getView(int position,View convert,android.view.ViewGroup parent){TextView result=(TextView)super.getView(position,convert,parent);result.setTextColor(StudioUi.TEXT);result.setTextSize(12);result.setSingleLine(true);return result;}};}
    private void refreshFrame(){
        if(program==null)return;int count=program.optInt("frameCount"),fps=program.optInt("fps");scrub.setMax(count-1);scrub.setProgress(frame);
        frameLabel.setText("Frame "+((playing&&displayedFrame>=0?displayedFrame:frame)+1)+" / "+count+" · "+String.format(java.util.Locale.ROOT,"%.2f s",frame/(double)fps)+" · "+fps+" FPS");
        selectionLabel.setText((mapping?"Fit rig · ":"Pose · ")+selectedJoint+(mapping?" · drag joints; then bind artwork":" · drag endpoint or use rotation controls"));
    }
    private void changed(){++qualityRequest;if(qualityTask!=null)qualityTask.cancel(true);dirty=true;++generation;previewError="";requestPreview();main.removeCallbacks(draftTick);main.postDelayed(draftTick,250);refreshFrame();}
    private final Runnable draftTick=()->persistDraft();
    private void persistDraft(){
        if(program==null||source==null||destroyed)return;try{
            JSONObject draft=new JSONObject().put("animationId",animationId).put("revision",revision).put("sourceRevision",sourceRevision).put("dirty",dirty).put("program",SketchDocument.copy(program));String id=sketchId;synchronized(DRAFTS){DRAFTS.remove(id);DRAFTS.put(id,draft.toString());while(DRAFTS.size()>4)DRAFTS.remove(DRAFTS.keySet().iterator().next());}
            io.execute(()->{try{writeDraft(id,draft);}catch(Exception failure){post(()->{status.setText("Draft not saved: "+failure.getMessage());message(failure.getMessage());});}});
        }catch(Exception failure){message(failure.getMessage());}
    }
    private File draftFile(String id)throws Exception {SketchDocument.id(new JSONObject().put("id",id),"id");File directory=new File(getFilesDir(),"sketch_animation_drafts");if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("Cannot create animation draft folder");return new File(directory,id+".json");}
    private JSONObject readDraft(String id)throws Exception {synchronized(DRAFTS){String cached=DRAFTS.get(id);if(cached!=null)return new JSONObject(cached);}AtomicFile file=new AtomicFile(draftFile(id));if(!file.getBaseFile().isFile())return null;byte[] bytes=file.readFully();if(bytes.length>SketchDocument.MAX_BYTES)throw SketchDocument.bad("Animation draft exceeds its size budget");return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    private void writeDraft(String id,JSONObject draft)throws Exception {byte[] bytes=draft.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>SketchDocument.MAX_BYTES)throw SketchDocument.bad("Animation draft exceeds its size budget");AtomicFile file=new AtomicFile(draftFile(id));FileOutputStream stream=null;try{stream=file.startWrite();stream.write(bytes);file.finishWrite(stream);}catch(Exception failure){if(stream!=null)file.failWrite(stream);throw failure;}}
    private void requestPreview(){main.removeCallbacks(previewTick);main.postDelayed(previewTick,playing?0:45);}
    private final Runnable previewTick=()->{
        if(destroyed||loading||source==null||program==null)return;
        if(previewInFlight){previewAgain=true;return;}previewInFlight=true;previewAgain=false;
        final int requestedGeneration=generation,requestedFrame=frame;final boolean showRest=mapping;
        try{
            if(previewProgramSnapshot==null||snapshotGeneration!=requestedGeneration){previewProgramSnapshot=SketchDocument.copy(program);snapshotGeneration=requestedGeneration;}
            JSONObject sourceSnapshot=source,programSnapshot=previewProgramSnapshot;
            io.execute(()->{Bitmap bitmap=null;JSONObject sampledJoints=null,report=null;String error="";
                try{
                    JSONObject frameDocument;
                    if(showRest){frameDocument=SketchDocument.copy(sourceSnapshot);}
                    else {JSONObject result=SketchAnimationCompiler.preview(sourceSnapshot,programSnapshot,requestedFrame,sourceSnapshot.getString("id"));frameDocument=result.getJSONObject("document");sampledJoints=result.getJSONObject("sample");}
                    SketchFrameRenderer next=new SketchFrameRenderer(this,frameDocument);
                    try {next.prepareRasterResources(640);next.prepareVectorGeometry();bitmap=next.render(showRest?Math.min(requestedFrame,sourceSnapshot.getInt("frameCount")-1):0,true,640,null);}
                    finally {next.close();}
                    if(showRest)sampledJoints=SketchAnimationCompiler.sample(sourceSnapshot,programSnapshot,requestedFrame);
                }catch(Exception|OutOfMemoryError failure){error=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();}
                Bitmap accepted=bitmap;JSONObject sample=sampledJoints,acceptedReport=report;String failure=error;
                main.post(()->{
                    if(destroyed){if(accepted!=null)accepted.recycle();return;}
                    previewInFlight=false;
                    if(requestedGeneration==generation&&(requestedFrame==frame||playing)&&showRest==mapping){
                        if(accepted!=null){canvas.replace(accepted);displayedFrame=requestedFrame;sampled=sample;refreshFrame();previewError=failure;if(failure.isEmpty())status.setText(source.optString("name")+" · "+(dirty?"local draft":revision==0?"unsaved scene":"saved scene r"+revision)+" · "+(mapping?"rig planning":"pose preview"));else status.setText("Drawing shown · animation needs attention: "+failure);}
                        else {previewError=failure;status.setText("Preview needs attention: "+failure);}
                        canvas.invalidate();
                    }else if(accepted!=null)accepted.recycle();
                    if(previewAgain||requestedGeneration!=generation||requestedFrame!=frame||showRest!=mapping)requestPreview();
                });
            });
        }catch(Exception failure){previewInFlight=false;message(failure.getMessage());}
    };
    private void rigTools(){if(!ready())return;menu("Rig and artwork",new String[]{"Bind artwork to selected joint","Deform vector artwork","Joint position / parent","Add joint","Add character","Remove selected joint"},this::bindings,this::skinWeights,this::jointSettings,this::addJoint,this::addRig,this::removeJoint);}
    private void bindings(){
        try{
            JSONObject rig=SketchAnimationUiModel.rig(program,selectedRig);JSONArray bindings=rig.getJSONArray("bindings"),layers=source.getJSONArray("layers");ArrayList<String> ids=new ArrayList<>(),labels=new ArrayList<>();
            for(int i=0;i<layers.length();i++){JSONObject layer=layers.getJSONObject(i);JSONArray objects=layer.getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject object=objects.getJSONObject(j);ids.add(object.getString("id"));labels.add(layer.getString("name")+" / "+object.getString("id")+" ("+object.getString("kind")+")");}}
            if(ids.isEmpty()){message("Draw separate body parts in Sketch Studio before binding them. A blank sketch cannot become a character.");return;}
            boolean[] checked=new boolean[ids.size()];for(int i=0;i<bindings.length();i++){JSONObject binding=bindings.getJSONObject(i);if(selectedJoint.equals(binding.optString("jointId"))){int at=ids.indexOf(binding.optString("objectId"));if(at>=0)checked[at]=true;}}
            final String targetRig=selectedRig,targetJoint=selectedJoint;
            new AlertDialog.Builder(this).setTitle("Artwork follows "+selectedJoint).setMultiChoiceItems(labels.toArray(new String[0]),checked,(d,index,value)->checked[index]=value).setNegativeButton("Cancel",null).setPositiveButton("Bind",(d,w)->{try{
                JSONObject current=SketchAnimationUiModel.rig(program,targetRig);JSONArray next=new JSONArray(),old=current.getJSONArray("bindings");
                for(int i=0;i<old.length();i++){JSONObject binding=old.getJSONObject(i);int at=ids.indexOf(binding.optString("objectId"));if(!targetJoint.equals(binding.optString("jointId"))&&(at<0||!checked[at]))next.put(binding);}
                for(int i=0;i<ids.size();i++)if(checked[i]){
                    JSONArray all=program.getJSONArray("rigs");for(int n=0;n<all.length();n++)if(!targetRig.equals(all.getJSONObject(n).getString("id"))){JSONArray others=all.getJSONObject(n).getJSONArray("bindings");for(int k=0;k<others.length();k++)if(ids.get(i).equals(others.getJSONObject(k).getString("objectId")))throw SketchDocument.bad("Artwork "+ids.get(i)+" already belongs to another character");}
                    JSONObject retained=null;for(int n=0;n<old.length();n++)if(ids.get(i).equals(old.getJSONObject(n).getString("objectId")))retained=SketchDocument.copy(old.getJSONObject(n));if(retained==null)retained=new JSONObject().put("objectId",ids.get(i));next.put(retained.put("jointId",targetJoint));
                }
                current.put("bindings",next);changed();
            }catch(Exception invalid){message(invalid.getMessage());}}).show();
        }catch(Exception invalid){message(invalid.getMessage());}
    }
    private JSONArray vertices(JSONObject object)throws Exception {
        JSONArray points=new JSONArray();String kind=object.getString("kind");
        if("path".equals(kind)){JSONArray commands=object.getJSONArray("commands");for(int i=0;i<commands.length();i++){JSONArray command=commands.getJSONArray(i);for(int j=1;j<command.length();j+=2)points.put(new JSONArray().put(command.getDouble(j)).put(command.getDouble(j+1)));}}
        else if(java.util.Arrays.asList("stroke","eraser","line","polygon").contains(kind)){JSONArray original=object.getJSONArray("points");for(int i=0;i<original.length();i++)points.put(new JSONArray().put(original.getJSONArray(i).getDouble(0)).put(original.getJSONArray(i).getDouble(1)));}
        return points;
    }
    private void skinWeights(){
        if(!ready())return;try{JSONObject rig=SketchAnimationUiModel.rig(program,selectedRig);JSONArray bindings=rig.getJSONArray("bindings");ArrayList<JSONObject> editable=new ArrayList<>();ArrayList<String> names=new ArrayList<>();
            for(int i=0;i<bindings.length();i++){JSONObject binding=bindings.getJSONObject(i),object=SketchDocument.object(source,binding.getString("objectId"));int count=vertices(object).length();if(count>0){editable.add(binding);names.add(binding.getString("objectId")+" · "+count+" vertices");}}
            if(editable.isEmpty()){message("Bind a stroke, path, line or polygon before editing its deformation weights. Raster parts use rigid joint bindings.");return;}
            new AlertDialog.Builder(this).setTitle("Deform vector artwork").setItems(names.toArray(new String[0]),(d,index)->editWeights(editable.get(index))).setNegativeButton("Cancel",null).show();
        }catch(Exception invalid){message(invalid.getMessage());}
    }
    private void editWeights(JSONObject binding){
        try{JSONObject draft=SketchDocument.copy(binding),object=SketchDocument.object(source,binding.getString("objectId"));JSONArray points=vertices(object);LinearLayout form=column();TextView position=label("Vertex 1 / "+points.length(),14);form.addView(position);SeekBar vertex=new SeekBar(this);vertex.setContentDescription("Deformation vertex");vertex.setMax(points.length()-1);vertex.setMinimumHeight(dp(48));form.addView(vertex);
            Spinner first=jointPicker(form,"First influence joint",binding.getString("jointId")),second=jointPicker(form,"Second influence joint",selectedJoint);TextView fraction=label("First joint 100% · second joint 0%",12);form.addView(fraction);SeekBar blend=new SeekBar(this);blend.setContentDescription("First joint influence percent");blend.setMax(100);blend.setProgress(100);blend.setMinimumHeight(dp(48));form.addView(blend);
            mapping=true;mode.setText("Rig mode");skinObjectId=binding.getString("objectId");skinVertex=0;requestPreview();
            vertex.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int index,boolean human){skinVertex=index;position.setText("Vertex "+(index+1)+" / "+points.length()+" · "+points.optJSONArray(index));canvas.invalidate();}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
            blend.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int amount,boolean human){fraction.setText("First joint "+amount+"% · second joint "+(100-amount)+"%");}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
            LinearLayout actions=row(form);button(actions,"Set vertex",()->{try{SketchAnimationUiModel.setVertexBlend(draft,points.length(),vertex.getProgress(),first.getSelectedItem().toString(),second.getSelectedItem().toString(),blend.getProgress()/100d);message("Vertex "+(vertex.getProgress()+1)+" blend prepared");}catch(Exception invalid){message(invalid.getMessage());}},false);
            button(actions,"Linear blend all",()->{try{for(int i=0;i<points.length();i++)SketchAnimationUiModel.setVertexBlend(draft,points.length(),i,first.getSelectedItem().toString(),second.getSelectedItem().toString(),points.length()==1?1:1-i/(double)(points.length()-1));message("Vertex-order gradient prepared. Refine individual vertices before saving.");}catch(Exception invalid){message(invalid.getMessage());}},false);
            button(form,"Use rigid binding",()->{draft.remove("weights");message("Rigid binding prepared");},false);
            form.addView(label("The orange point marks the selected rest vertex. Set vertex records a blend between two joints; all other vertices retain their current weights. Linear blend all follows vertex order. Saving applies the weights; Cancel leaves the rig unchanged. Large numbers of deforming vertices or long scenes may exceed the editable output budget.",12));
            ScrollView scroll=new ScrollView(this);scroll.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Skin weights · "+binding.getString("objectId")).setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save weights",null).create();dialog.setOnDismissListener(d->{skinObjectId="";skinVertex=-1;canvas.invalidate();});dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{if(draft.has("weights"))binding.put("weights",draft.getJSONArray("weights"));else binding.remove("weights");changed();dialog.dismiss();}catch(Exception invalid){message(invalid.getMessage());}});
        }catch(Exception invalid){message(invalid.getMessage());}
    }
    private void jointSettings(){
        try{JSONObject joint=SketchAnimationUiModel.joint(SketchAnimationUiModel.rig(program,selectedRig),selectedJoint);LinearLayout form=column();EditText x=field(form,"Rest joint X",String.valueOf(joint.getDouble("x")),true),y=field(form,"Rest joint Y",String.valueOf(joint.getDouble("y")),true);Spinner parent=picker("Parent joint");ArrayList<String> parents=new ArrayList<>();parents.add("Root (no parent)");for(String id:jointIds)if(!id.equals(selectedJoint))parents.add(id);parent.setAdapter(adapter(parents));parent.setSelection(Math.max(0,parents.indexOf(joint.optString("parent","Root (no parent)"))));form.addView(parent);
            dialog("Fit "+selectedJoint,form,"Apply",()->{double xx=number(x,0,1),yy=number(y,0,1);String ancestor=parent.getSelectedItemPosition()==0?"":parent.getSelectedItem().toString();if(!ancestor.isEmpty())requireAcyclic(SketchAnimationUiModel.rig(program,selectedRig),selectedJoint,ancestor);joint.put("x",xx).put("y",yy);if(ancestor.isEmpty())joint.remove("parent");else joint.put("parent",ancestor);mapping=true;mode.setText("Rig mode");changed();});
        }catch(Exception invalid){message(invalid.getMessage());}
    }
    private void requireAcyclic(JSONObject rig,String id,String ancestor)throws Exception {String cursor=ancestor;for(int i=0;i<128&&!cursor.isEmpty();i++){if(id.equals(cursor))throw SketchDocument.bad("A joint cannot be its own ancestor");cursor=SketchAnimationUiModel.joint(rig,cursor).optString("parent","");}}
    private void addJoint(){
        try{JSONObject rig=SketchAnimationUiModel.rig(program,selectedRig);LinearLayout form=column();EditText name=field(form,"New joint name","joint_"+(jointIds.size()+1),false),x=field(form,"Rest joint X","0.5",true),y=field(form,"Rest joint Y","0.5",true);TextView parent=label("Parent: "+selectedJoint,14);form.addView(parent);
            dialog("Add joint",form,"Add",()->{String id=name.getText().toString().trim();SketchDocument.id(new JSONObject().put("id",id),"id");JSONArray values=rig.getJSONArray("joints");for(int i=0;i<values.length();i++)if(id.equals(values.getJSONObject(i).getString("id")))throw SketchDocument.bad("Joint name already exists");JSONObject joint=new JSONObject().put("id",id).put("x",number(x,0,1)).put("y",number(y,0,1));if(!selectedJoint.isEmpty())joint.put("parent",selectedJoint);values.put(joint);selectedJoint=id;refreshPickers();changed();});
        }catch(Exception invalid){message(invalid.getMessage());}
    }
    private void addRig(){LinearLayout form=column();EditText name=field(form,"Character name","character_"+(rigIds.size()+1),false);dialog("Add character rig",form,"Add",()->{String id=name.getText().toString().trim();SketchDocument.id(new JSONObject().put("id",id),"id");if(rigIds.contains(id))throw SketchDocument.bad("Character name already exists");program.getJSONArray("rigs").put(SketchAnimationUiModel.humanoid(id));selectedRig=id;selectedJoint="root";refreshPickers();changed();});}
    private void removeJoint(){try{JSONObject rig=SketchAnimationUiModel.rig(program,selectedRig);JSONArray values=rig.getJSONArray("joints"),next=new JSONArray();for(int i=0;i<values.length();i++){JSONObject joint=values.getJSONObject(i);if(selectedJoint.equals(joint.optString("parent")))throw SketchDocument.bad("Remove or reparent child joints before removing this joint");if(!selectedJoint.equals(joint.getString("id")))next.put(joint);}JSONArray bindings=rig.getJSONArray("bindings");for(int i=0;i<bindings.length();i++)if(selectedJoint.equals(bindings.getJSONObject(i).getString("jointId")))throw SketchDocument.bad("Unbind this joint's artwork first");JSONArray contacts=rig.getJSONArray("ik");for(int i=0;i<contacts.length();i++)for(String name:new String[]{"hip","knee","ankle"})if(selectedJoint.equals(contacts.getJSONObject(i).getString(name)))throw SketchDocument.bad("Remove this joint's foot contact first");for(int i=0;i<bindings.length();i++){JSONArray weights=bindings.getJSONObject(i).optJSONArray("weights");if(weights!=null)for(int n=0;n<weights.length();n++)if(weights.getJSONObject(n).has(selectedJoint))throw SketchDocument.bad("Remove this joint's skin influences first");}if(next.length()==0)throw SketchDocument.bad("Keep a root joint");rig.put("joints",next);JSONArray poses=rig.getJSONArray("poses");for(int i=0;i<poses.length();i++)poses.getJSONObject(i).getJSONObject("rotations").remove(selectedJoint);selectedJoint="";refreshPickers();changed();}catch(Exception invalid){message(invalid.getMessage());}}
    private void motionPresets(){
        if(!ready())return;
        menu("Editable acting presets",new String[]{"Idle / breathing","Walk cycle","Run cycle","Anticipation → strike → recover","Plant gait feet (walk/run)"},
                ()->applyMotionPreset("idle"),()->applyMotionPreset("walk"),()->applyMotionPreset("run"),()->applyMotionPreset("strike"),this::plantGaitFeet);
    }
    private void applyMotionPreset(String style){
        if(!ready())return;
        LinearLayout form=column();
        int remaining=program.optInt("frameCount")-frame;
        EditText duration=field(form,"Duration in frames",String.valueOf(Math.min(remaining,48)),true);
        form.addView(label("Adds independent, editable joint pose keys to the selected character starting at frame "+(frame+1)+". Bind individual body parts first. Existing keys are never overwritten. After adding walk/run keys, choose Plant gait feet to calculate real IK contacts (requires separate foot drawings).",12));
        dialog("Add "+style+" motion",form,"Add keys",()->{
            int count=SketchAnimationUiModel.integer(duration.getText().toString(),9,360,"Preset duration");
            JSONObject result=SketchAnimationMotionLibrary.insert(program,selectedRig,frame,count,style);
            program=result;mapping=false;mode.setText("Pose mode");changed();
            message("Editable "+style+" pose keys added. Add gait foot contacts, then inspect each stride.");
        });
    }
    private void plantGaitFeet(){
        if(!ready()||saving)return;
        LinearLayout form=column();
        EditText first=field(form,"First gait frame",String.valueOf(frame+1),true),
                length=field(form,"Gait frames (24..240)",String.valueOf(Math.min(48,Math.max(24,program.optInt("frameCount")-frame))),true);
        Spinner kind=picker("Walking or running");
        kind.setAdapter(adapter(java.util.Arrays.asList("walk","run")));
        form.addView(label("Preset gait style",12));form.addView(kind);
        form.addView(label("Start with Walk or Run preset keys. Bind separately drawn left and right foot pieces, then calculate world-space sole targets from the actual rig. Contact blending eases the transition, but standing balance, ground surface, silhouettes and motion quality still need visual review. The original sketch is never overwritten.",12));
        dialog("Plant feet in the native rig",form,"Calculate IK",()->{
            final int firstFrame=index(first);
            final int count=SketchAnimationUiModel.integer(length.getText().toString(),24,240,"Foot-plant frames");
            final String style=kind.getSelectedItem().toString(),rigId=selectedRig;
            final JSONObject frozenSource=SketchDocument.copy(source),frozenProgram=SketchDocument.copy(program);
            final int sourceGeneration=generation,request=++qualityRequest;
            if(qualityTask!=null)qualityTask.cancel(true);
            status.setText("Solving real leg reach, plant windows and contact easing…");
            qualityTask=quality.submit(()->{
                try{
                    JSONObject solved=SketchAnimationFootwork.plant(frozenSource,frozenProgram,rigId,firstFrame,count,style);
                    post(()->{
                        if(sourceGeneration!=generation||request!=qualityRequest||!rigId.equals(selectedRig)){
                            status.setText("Gait changed during calculation; original edits preserved");
                            message("Gait changed: recalculate foot contacts on the latest poses");return;
                        }
                        program=solved;mapping=false;mode.setText("Pose mode");changed();
                        message("Planted-foot IK saved in local draft. Inspect the pose and run Production QA.");
                    });
                }catch(Exception error){post(()->{
                    if(sourceGeneration==generation&&request==qualityRequest){status.setText("Foot plants not changed · "+error.getMessage());message(error.getMessage());}
                });}
            });
        });
    }
    private void poseTools(){if(!ready())return;menu("Pose at frame "+(frame+1),new String[]{"Joint rotation / root motion","Save pose key","Delete pose key","Smooth timing","Linear timing","Hold timing","Action contact markers"},this::rotation,()->{try{SketchAnimationUiModel.pose(program,selectedRig,frame,interpolation);changed();}catch(Exception invalid){message(invalid.getMessage());}},this::deleteKey,()->setTiming("smooth"),()->setTiming("linear"),()->setTiming("hold"),this::interactionMenu);}
    private void interactionMenu(){
        if(!ready())return;
        menu("Physical choreography contacts",
                new String[]{"New two-actor contact at current frame","Inspect / remove authored contact"},
                this::addActionContact,this::deleteActionContact);
    }
    private void addActionContact(){
        if(!ready())return;
        try{
            JSONArray available=program.getJSONArray("rigs");
            if(available.length()<2){message("Add a second character and bind its artwork before a two-actor interaction.");return;}
            ArrayList<String> ids=new ArrayList<>();
            for(int i=0;i<available.length();i++)ids.add(available.getJSONObject(i).getString("id"));
            LinearLayout form=column();
            Spinner first=picker("First character");first.setAdapter(adapter(ids));
            first.setSelection(Math.max(0,ids.indexOf(selectedRig)));
            Spinner second=picker("Other character");second.setAdapter(adapter(ids));
            second.setSelection((Math.max(0,ids.indexOf(selectedRig))+1)%ids.size());
            Spinner firstJoint=picker("First hand/weapon joint");
            Spinner secondJoint=picker("Other hand/weapon joint");
            Runnable updateFirst=()->fillContactJoints(first,firstJoint);
            Runnable updateSecond=()->fillContactJoints(second,secondJoint);
            first.setOnItemSelectedListener(new Selected(){public void selected(int position){updateFirst.run();}});
            second.setOnItemSelectedListener(new Selected(){public void selected(int position){updateSecond.run();}});
            form.addView(label("First actor",12));form.addView(first);
            form.addView(label("Second actor",12));form.addView(second);
            form.addView(label("First contact joint",12));form.addView(firstJoint);
            form.addView(label("Second contact joint",12));form.addView(secondJoint);
            updateFirst.run();updateSecond.run();
            EditText distance=field(form,"Maximum contact distance in source pixels","80",true);
            EditText words=field(form,"Action description","Hand / weapon contact",false);
            form.addView(label("A contact marker is evaluated against actual joint world positions, not the drawn spark or camera transform. New marks are checked without blocking ordinary preview/save. Move the characters or change the tolerance if physical contact is not correct.",12));
            dialog("Contact frame "+(frame+1),form,"Verify and add",()->{
                if(first.getSelectedItem()==null||second.getSelectedItem()==null||
                    firstJoint.getSelectedItem()==null||secondJoint.getSelectedItem()==null)
                    throw SketchDocument.bad("Select both character rigs and their contact joints");
                String a=first.getSelectedItem().toString(),b=second.getSelectedItem().toString();
                if(a.equals(b))throw SketchDocument.bad("An action contact requires two different character rigs");
                double allowed=number(distance,.1,512);
                String desc=words.getText().toString().trim();
                if(desc.length()>120)throw SketchDocument.bad("Action description exceeds 120 characters");
                JSONObject sourceSnapshot=SketchDocument.copy(source),candidate=SketchDocument.copy(program);
                JSONArray authored=candidate.optJSONArray("interactionCues");
                if(authored==null){authored=new JSONArray();candidate.put("interactionCues",authored);}
                if(authored.length()>=64)throw SketchDocument.bad("At most 64 contact markers per animation");
                authored.put(new JSONObject().put("frame",frame).put("rigA",a)
                        .put("jointA",firstJoint.getSelectedItem().toString())
                        .put("rigB",b).put("jointB",secondJoint.getSelectedItem().toString())
                        .put("maxGapPx",allowed).put("description",desc));
                final int origin=generation,request=++qualityRequest;
                if(qualityTask!=null)qualityTask.cancel(true);
                status.setText("Checking real two-character contact at frame "+(frame+1)+"…");
                qualityTask=quality.submit(()->{
                    try{
                        JSONObject checked=SketchAnimationCompiler.verifyInteractionCues(sourceSnapshot,candidate);
                        post(()->{
                            if(origin!=generation||request!=qualityRequest)return;
                            program=candidate;changed();
                            message("Physical contact verified and added · "+candidate.optJSONArray("interactionCues").length()+" authored cue(s)");
                        });
                    }catch(Exception error){
                        post(()->{
                            if(origin!=generation||request!=qualityRequest)return;
                            status.setText("Interaction unchanged · "+error.getMessage());
                            message(error.getMessage());
                        });
                    }
                });
            });
        }catch(Exception failure){message(failure.getMessage());}
    }
    private void fillContactJoints(Spinner actor,Spinner picker){
        if(program==null||actor.getSelectedItem()==null)return;
        try{
            JSONObject rig=SketchAnimationUiModel.rig(program,actor.getSelectedItem().toString());
            JSONArray values=rig.getJSONArray("joints");ArrayList<String> names=new ArrayList<>();
            for(int i=0;i<values.length();i++)names.add(values.getJSONObject(i).getString("id"));
            picker.setAdapter(adapter(names));
            int selected=names.indexOf("wrist");
            if(selected<0)selected=names.indexOf("hand");
            if(selected<0)selected=names.indexOf("hand_right");
            picker.setSelection(Math.max(0,selected));
        }catch(Exception error){message(error.getMessage());}
    }
    private void deleteActionContact(){
        if(!ready())return;
        try{
            JSONArray old=program.optJSONArray("interactionCues");
            if(old==null||old.length()==0){message("This scene has no hand/weapon contact markers.");return;}
            String[] labels=new String[old.length()];
            for(int i=0;i<old.length();i++){
                JSONObject mark=old.getJSONObject(i);
                labels[i]=(mark.getInt("frame")+1)+" · "+mark.getString("rigA")+":"+mark.getString("jointA")
                        +" → "+mark.getString("rigB")+":"+mark.getString("jointB")
                        +" · "+mark.optString("description","");
            }
            new AlertDialog.Builder(this).setTitle("Delete a physical contact marker")
                    .setItems(labels,(d,index)->{
                        try{
                            JSONObject next=SketchDocument.copy(program);
                            JSONArray all=next.getJSONArray("interactionCues"),filtered=new JSONArray();
                            for(int i=0;i<all.length();i++)if(i!=index)filtered.put(all.getJSONObject(i));
                            next.put("interactionCues",filtered);program=next;changed();
                        }catch(Exception failure){message(failure.getMessage());}
                    }).setNegativeButton("Cancel",null).show();
        }catch(Exception failure){message(failure.getMessage());}
    }
    private void rotation(){
        try{JSONObject previous=SketchAnimationUiModel.previousPose(SketchAnimationUiModel.rig(program,selectedRig),frame);LinearLayout form=column();EditText rotation=field(form,"Joint rotation degrees",String.valueOf(previous.getJSONObject("rotations").optDouble(selectedJoint)),true),x=field(form,"Root motion X",String.valueOf(previous.optDouble("rootX")),true),y=field(form,"Root motion Y",String.valueOf(previous.optDouble("rootY")),true);
            LinearLayout nudge=row(form);button(nudge,"−5°",()->{try{rotation.setText(String.valueOf(number(rotation,-3600,3600)-5));}catch(Exception invalid){message(invalid.getMessage());}},false);button(nudge,"+5°",()->{try{rotation.setText(String.valueOf(number(rotation,-3600,3600)+5));}catch(Exception invalid){message(invalid.getMessage());}},false);
            dialog("Pose "+selectedJoint,form,"Set key",()->{double degrees=number(rotation,-3600,3600),xx=number(x,-4,4),yy=number(y,-4,4);SketchAnimationUiModel.setRotation(program,selectedRig,frame,selectedJoint,degrees,interpolation);SketchAnimationUiModel.pose(program,selectedRig,frame,interpolation).put("rootX",xx).put("rootY",yy);mapping=false;mode.setText("Pose mode");changed();});
        }catch(Exception invalid){message(invalid.getMessage());}
    }
    private void setTiming(String value){interpolation=value;try{SketchAnimationUiModel.pose(program,selectedRig,frame,value);changed();message("Segment timing: "+value);}catch(Exception invalid){message(invalid.getMessage());}}
    private void deleteKey(){try{JSONObject rig=SketchAnimationUiModel.rig(program,selectedRig);rig.put("poses",SketchAnimationUiModel.withoutFrame(rig.getJSONArray("poses"),frame));changed();}catch(Exception invalid){message(invalid.getMessage());}}
    private void jumpKey(boolean next){if(!ready())return;stopPlayback();try{JSONArray poses=SketchAnimationUiModel.rig(program,selectedRig).getJSONArray("poses");int destination=next?program.getInt("frameCount")-1:0;for(int i=0;i<poses.length();i++){int key=poses.getJSONObject(i).getInt("frame");if(next&&key>frame)destination=Math.min(destination,key);else if(!next&&key<frame)destination=Math.max(destination,key);}frame=destination;refreshFrame();requestPreview();}catch(Exception invalid){message(invalid.getMessage());}}
    private void timing(){if(!ready())return;LinearLayout form=column();EditText count=field(form,"Animation frames",String.valueOf(program.optInt("frameCount")),true),fps=field(form,"Frames per second",String.valueOf(program.optInt("fps")),true);form.addView(label("Frames are numbered from 1 in these controls. The native program uses zero-based frame indices. Shortening is rejected if a key, contact or dialogue would be lost.",12));dialog("Animation timing",form,"Apply",()->{int frames=SketchAnimationUiModel.integer(count.getText().toString(),1,1200,"Animation frames"),rate=SketchAnimationUiModel.integer(fps.getText().toString(),24,60,"Frames per second");NativeFrameClock.requireFps(rate);JSONObject candidate=SketchDocument.copy(program);candidate.put("frameCount",frames).put("fps",rate);requireTimingFits(candidate,frames);program=candidate;frame=Math.min(frame,frames-1);changed();});}
    private void requireTimingFits(JSONObject candidate,int count)throws Exception {
        JSONArray rigs=candidate.getJSONArray("rigs");for(int i=0;i<rigs.length();i++){JSONObject rig=rigs.getJSONObject(i);JSONArray poses=rig.getJSONArray("poses"),contacts=rig.getJSONArray("ik");for(int n=0;n<poses.length();n++)if(poses.getJSONObject(n).getInt("frame")>=count)throw SketchDocument.bad("A pose key is outside the new duration");for(int n=0;n<contacts.length();n++)if(contacts.getJSONObject(n).getInt("end")+contacts.getJSONObject(n).optInt("blendOutFrames",0)>=count)throw SketchDocument.bad("A foot contact or its blend ramp is outside the new duration");}
        for(String name:new String[]{"camera","dialogues","mouthCels","celRanges"}){JSONArray values=candidate.optJSONArray(name);if(values==null)continue;for(int n=0;n<values.length();n++){JSONObject value=values.getJSONObject(n);if(value.optInt("end",value.optInt("frame"))>=count)throw SketchDocument.bad(name+" contains a key outside the new duration");}}
    }
    private void contacts(){
        if(!ready())return;try{JSONObject rig=SketchAnimationUiModel.rig(program,selectedRig);LinearLayout form=column();Spinner hip=jointPicker(form,"IK hip joint","hip_left"),knee=jointPicker(form,"IK knee joint","knee_left"),ankle=jointPicker(form,"IK foot joint","foot_left");EditText start=field(form,"First contact frame",String.valueOf(frame+1),true),end=field(form,"Last contact frame",String.valueOf(Math.min(frame+12,program.optInt("frameCount"))),true),x=field(form,"Foot target X","0.43",true),y=field(form,"Foot target Y","0.91",true);Spinner bend=picker("Knee bend direction");bend.setAdapter(adapter(java.util.Arrays.asList("Bend +1","Bend −1")));form.addView(bend);CheckBox lockRotation=new CheckBox(this);lockRotation.setText("Lock foot world rotation");lockRotation.setTextColor(StudioUi.TEXT);lockRotation.setMinimumHeight(dp(48));lockRotation.setChecked(true);form.addView(lockRotation);EditText footRotation=field(form,"Foot world rotation degrees","0",true);form.addView(label("A planted foot stays at this world position over the chosen range. Hip → knee → foot must be direct parent links; unreachable targets are reported, never hidden.",12));
            button(form,"Clear contacts for this rig",()->{try{rig.put("ik",new JSONArray());changed();message("Contacts cleared");}catch(Exception invalid){message(invalid.getMessage());}},false);
            dialog("Planted foot / two-bone IK",form,"Add contact",()->{int first=index(start),last=index(end);if(last<first)throw SketchDocument.bad("Last contact frame must follow the first");JSONObject contact=new JSONObject().put("hip",hip.getSelectedItem().toString()).put("knee",knee.getSelectedItem().toString()).put("ankle",ankle.getSelectedItem().toString()).put("start",first).put("end",last).put("x",number(x,0,1)).put("y",number(y,0,1)).put("bend",bend.getSelectedItemPosition()==0?1:-1);if(lockRotation.isChecked())contact.put("rotation",number(footRotation,-3600,3600));JSONArray current=rig.getJSONArray("ik");current.put(contact);changed();});
        }catch(Exception invalid){message(invalid.getMessage());}
    }
    private Spinner jointPicker(LinearLayout form,String description,String preferred){Spinner picker=picker(description);picker.setAdapter(adapter(jointIds));picker.setSelection(Math.max(0,jointIds.indexOf(preferred)));form.addView(label(description,12));form.addView(picker);return picker;}
    private int index(EditText field)throws Exception {return SketchAnimationUiModel.integer(field.getText().toString(),1,program.getInt("frameCount"),String.valueOf(field.getContentDescription()))-1;}
    private void camera(){
        if(!ready())return;try{JSONObject prior=new JSONObject().put("x",0).put("y",0).put("zoom",1).put("rotation",0);JSONArray values=program.getJSONArray("camera");for(int i=0;i<values.length();i++)if(values.getJSONObject(i).getInt("frame")==frame)prior=values.getJSONObject(i);
            LinearLayout form=column();EditText x=field(form,"Camera X",String.valueOf(prior.optDouble("x")),true),y=field(form,"Camera Y",String.valueOf(prior.optDouble("y")),true),zoom=field(form,"Camera zoom",String.valueOf(prior.optDouble("zoom",1)),true),rotation=field(form,"Camera rotation degrees",String.valueOf(prior.optDouble("rotation")),true);form.addView(label("The camera transforms the artwork layers you select; dialogue remains readable when placed on a separate unselected layer. Use two camera keys for a continuous pan or zoom; a hold key creates a shot cut.",12));button(form,"Choose camera layers",this::cameraLayers,false);button(form,"Delete camera key here",()->{try{program.put("camera",SketchAnimationUiModel.withoutFrame(program.getJSONArray("camera"),frame));changed();message("Camera key removed");}catch(Exception invalid){message(invalid.getMessage());}},false);
            dialog("Camera key · frame "+(frame+1),form,"Set key",()->{JSONObject key=new JSONObject().put("frame",frame).put("x",number(x,-4,4)).put("y",number(y,-4,4)).put("zoom",number(zoom,.01,8)).put("rotation",number(rotation,-3600,3600)).put("interpolation",interpolation);JSONArray keys=SketchAnimationUiModel.withoutFrame(program.getJSONArray("camera"),frame);keys.put(key);program.put("camera",SketchAnimationUiModel.sortedFrames(keys));changed();});
        }catch(Exception invalid){message(invalid.getMessage());}
    }
    private void cameraLayers(){try{JSONArray layers=source.getJSONArray("layers"),selected=program.getJSONArray("cameraLayerIds");String[] labels=new String[layers.length()];boolean[] chosen=new boolean[layers.length()];for(int i=0;i<layers.length();i++){labels[i]=layers.getJSONObject(i).getString("name");for(int j=0;j<selected.length();j++)if(selected.getString(j).equals(layers.getJSONObject(i).getString("id")))chosen[i]=true;}new AlertDialog.Builder(this).setTitle("Camera affects layers").setMultiChoiceItems(labels,chosen,(d,i,value)->chosen[i]=value).setPositiveButton("Apply",(d,w)->{try{JSONArray next=new JSONArray();for(int i=0;i<chosen.length;i++)if(chosen[i])next.put(layers.getJSONObject(i).getString("id"));program.put("cameraLayerIds",next);changed();}catch(Exception invalid){message(invalid.getMessage());}}).setNegativeButton("Cancel",null).show();}catch(Exception invalid){message(invalid.getMessage());}}
    private void dialogueMenu(){if(!ready())return;menu("Dialogue and cel timing",new String[]{"Timed dialogue","Automatic text mouth timing","Mouth cel range","Artwork visibility range","Existing cel ranges"},this::dialogue,this::autoMouthTiming,()->celRange(true),()->celRange(false),this::celRangeList);}
    private void autoMouthTiming(){
        if(!ready())return;
        try{
            JSONArray cues=program.optJSONArray("dialogues");
            if(cues==null||cues.length()==0){message("Add a timed dialogue cue first; text timing needs a start and end frame.");return;}
            ArrayList<String> ids=new ArrayList<>(),names=new ArrayList<>();
            JSONArray layers=source.getJSONArray("layers");
            for(int i=0;i<layers.length();i++){
                JSONObject layer=layers.getJSONObject(i);
                JSONArray objects=layer.getJSONArray("objects");
                for(int j=0;j<objects.length();j++){
                    JSONObject object=objects.getJSONObject(j);
                    String kind=object.getString("kind");
                    if("image".equals(kind)||"paint".equals(kind)||"text".equals(kind))continue;
                    ids.add(object.getString("id"));
                    names.add(layer.getString("name")+" / "+object.getString("id"));
                }
            }
            if(ids.size()<4){message("Draw at least four different vector mouth shapes first: closed, open, round and teeth.");return;}
            LinearLayout form=column();
            ArrayList<String> cueLabels=new ArrayList<>();
            for(int i=0;i<cues.length();i++){
                JSONObject cue=cues.getJSONObject(i);cueLabels.add((cue.getInt("start")+1)+"–"+(cue.getInt("end")+1)+" · "+cue.getString("text"));
            }
            Spinner dialogue=picker("Existing dialogue");dialogue.setAdapter(adapter(cueLabels));form.addView(label("Timed dialogue",12));form.addView(dialogue);
            String[] roles={"closed","open","round","teeth"};
            Spinner[] selectors=new Spinner[roles.length];
            for(int k=0;k<roles.length;k++){
                selectors[k]=picker(roles[k]+" mouth");selectors[k].setAdapter(adapter(names));
                int preferred=Math.min(k,ids.size()-1);
                for(int j=0;j<ids.size();j++)if(ids.get(j).toLowerCase(java.util.Locale.ROOT).contains(roles[k])){preferred=j;break;}
                selectors[k].setSelection(preferred);form.addView(label(roles[k]+" mouth drawing",12));form.addView(selectors[k]);
            }
            form.addView(label("Text letters approximate mouth shapes at frame accuracy. This is NOT alignment to recorded dialogue or generated speech. Prepared mouth drawings should overlap anatomically at the same location. Existing timed cels are not overwritten.",12));
            dialog("Text → replacement mouth cels",form,"Generate timings",()->{
                JSONObject result=SketchAnimationVisemeTiming.apply(source,program,dialogue.getSelectedItemPosition(),
                        ids.get(selectors[0].getSelectedItemPosition()),ids.get(selectors[1].getSelectedItemPosition()),
                        ids.get(selectors[2].getSelectedItemPosition()),ids.get(selectors[3].getSelectedItemPosition()));
                program=result;mapping=false;mode.setText("Pose mode");changed();
                message("Mouth replacement cels added. Adjust their timing against actual voice audio.");
            });
        }catch(Exception invalid){message(invalid.getMessage());}
    }
    private void celRange(boolean mouth){
        try{LinearLayout form=column();ArrayList<String> ids=new ArrayList<>(),names=new ArrayList<>();JSONArray layers=source.getJSONArray("layers");for(int i=0;i<layers.length();i++){JSONObject layer=layers.getJSONObject(i);JSONArray objects=layer.getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject object=objects.getJSONObject(j);ids.add(object.getString("id"));names.add(layer.getString("name")+" / "+object.getString("id"));}}
            if(ids.isEmpty()){message("Draw the mouth shapes or alternate-view cels in Sketch Studio first");return;}
            Spinner artwork=picker("Prepared cel artwork");artwork.setAdapter(adapter(names));form.addView(artwork);EditText first=field(form,"First cel frame",String.valueOf(frame+1),true),last=field(form,"Last cel frame",String.valueOf(Math.min(frame+6,program.optInt("frameCount"))),true);form.addView(label("This selected prepared object is visible only in the chosen range. Mouth shapes must be drawn separately; this control does not generate speech or unseen views. One range per object; use separate cel objects for repeated ranges.",12));
            dialog(mouth?"Mouth cel range":"Artwork visibility range",form,"Set range",()->{int start=index(first),end=index(last);if(end<start)throw SketchDocument.bad("The last cel frame must follow the first");String object=ids.get(artwork.getSelectedItemPosition());for(String key:new String[]{"mouthCels","celRanges"}){JSONArray old=program.optJSONArray(key),next=new JSONArray();if(old!=null)for(int i=0;i<old.length();i++)if(!object.equals(old.getJSONObject(i).getString("objectId")))next.put(old.getJSONObject(i));program.put(key,next);}program.getJSONArray(mouth?"mouthCels":"celRanges").put(new JSONObject().put("objectId",object).put("start",start).put("end",end));changed();});
        }catch(Exception invalid){message(invalid.getMessage());}
    }
    private void celRangeList(){try{ArrayList<String> labels=new ArrayList<>(),keys=new ArrayList<>();ArrayList<Integer> positions=new ArrayList<>();for(String key:new String[]{"mouthCels","celRanges"}){JSONArray ranges=program.optJSONArray(key);if(ranges!=null)for(int i=0;i<ranges.length();i++){JSONObject value=ranges.getJSONObject(i);labels.add(value.getString("objectId")+" · "+(value.getInt("start")+1)+"–"+(value.getInt("end")+1)+(key.equals("mouthCels")?" · mouth":" · visibility"));keys.add(key);positions.add(i);}}new AlertDialog.Builder(this).setTitle("Delete a cel range").setItems(labels.toArray(new String[0]),(d,index)->{try{String key=keys.get(index);int at=positions.get(index);JSONArray old=program.getJSONArray(key),next=new JSONArray();for(int i=0;i<old.length();i++)if(i!=at)next.put(old.getJSONObject(i));program.put(key,next);changed();}catch(Exception invalid){message(invalid.getMessage());}}).setNegativeButton("Close",null).show();}catch(Exception invalid){message(invalid.getMessage());}}
    private void dialogue(){
        if(!ready())return;try{LinearLayout form=column();EditText text=field(form,"Dialogue text","",false),start=field(form,"First dialogue frame",String.valueOf(frame+1),true),end=field(form,"Last dialogue frame",String.valueOf(Math.min(frame+24,program.optInt("frameCount"))),true),x=field(form,"Dialogue X","0.08",true),y=field(form,"Dialogue Y","0.82",true),width=field(form,"Dialogue width","0.84",true),height=field(form,"Dialogue height","0.14",true),color=field(form,"Dialogue colour","#FFF5F5F1",false),size=field(form,"Dialogue font size","0.045",true);Spinner layer=picker("Dialogue layer");ArrayList<String> ids=new ArrayList<>(),names=new ArrayList<>();JSONArray layers=source.getJSONArray("layers");for(int i=0;i<layers.length();i++){ids.add(layers.getJSONObject(i).getString("id"));names.add(layers.getJSONObject(i).getString("name"));}layer.setAdapter(adapter(names));form.addView(label("Dialogue layer",12));form.addView(layer);button(form,"Existing dialogue / delete",this::dialogueList,false);
            dialog("Timed dialogue",form,"Add dialogue",()->{int first=index(start),last=index(end);if(last<first)throw SketchDocument.bad("Dialogue end must follow its start");String words=text.getText().toString().trim();if(words.isEmpty()||words.length()>4096)throw SketchDocument.bad("Use 1..4096 dialogue characters");String ink=color.getText().toString().trim();SketchDocument.color(ink);JSONObject bounds=new JSONObject().put("x",number(x,0,1)).put("y",number(y,0,1)).put("width",number(width,.0001,1)).put("height",number(height,.0001,1));SketchDocument.validateBounds(bounds);program.getJSONArray("dialogues").put(new JSONObject().put("start",first).put("end",last).put("text",words).put("layerId",ids.get(layer.getSelectedItemPosition())).put("bounds",bounds).put("color",ink).put("fontSize",number(size,.005,.3)));changed();});
        }catch(Exception invalid){message(invalid.getMessage());}
    }
    private void dialogueList(){try{JSONArray lines=program.getJSONArray("dialogues");String[] labels=new String[lines.length()];for(int i=0;i<labels.length;i++){JSONObject value=lines.getJSONObject(i);labels[i]=(value.getInt("start")+1)+"–"+(value.getInt("end")+1)+": "+value.getString("text");}new AlertDialog.Builder(this).setTitle("Delete dialogue").setItems(labels,(d,index)->{JSONArray next=new JSONArray();for(int i=0;i<lines.length();i++)if(i!=index)next.put(lines.opt(i));try{program.put("dialogues",next);changed();}catch(Exception invalid){message(invalid.getMessage());}}).setNegativeButton("Close",null).show();}catch(Exception invalid){message(invalid.getMessage());}}
    private void example(){
        if(loading||saving)return;
        new AlertDialog.Builder(this).setTitle("Editable 2D film exercises")
                .setItems(new String[]{"Rooftop Relay · single courier vs drone","Rooftop Standoff · two human actors / 3 camera views"},
                        (d,index)->openExample(index==1))
                .setNegativeButton("Cancel",null).show();
    }
    private void openExample(boolean dualActor){
        if(loading||saving)return;
        stopPlayback();persistDraft();loading=true;
        status.setText(dualActor?"Preparing two independently animated character rigs and staged contact…":
                "Preparing original Rooftop Relay exercise…");
        io.execute(()->{
            try{
                JSONObject created=dualActor?SketchDuelAnimationExample.create(this):SketchAnimationExample.create(this);
                post(()->{
                    animationId=created.optString("animationId");sketchId=created.optString("sketchId");
                    selectedRig="";selectedJoint="";mapping=false;load();
                });
            }catch(Exception failure){
                post(()->{loading=false;status.setText("Example could not be prepared: "+failure.getMessage());message(failure.getMessage());});
            }
        });
    }
    private void openAnimation(){if(!ready())return;stopPlayback();status.setText("Loading saved animations…");io.execute(()->{try{JSONArray values=SketchAnimationOps.execute(this,"sketch_animation_list",new JSONObject()).getJSONArray("animations");String[] labels=new String[values.length()];for(int i=0;i<labels.length;i++){JSONObject item=values.getJSONObject(i);labels[i]=item.optString("animationId")+" · r"+item.optLong("revision");}post(()->{new AlertDialog.Builder(this).setTitle("Saved animation scenes").setItems(labels,(d,index)->{persistDraft();animationId=values.optJSONObject(index).optString("animationId");selectedRig="";selectedJoint="";load();}).setNegativeButton("Cancel",null).show();});}catch(Exception failure){post(()->message(failure.getMessage()));}});}
    private void productionCheck(){
        if(!ready()||saving)return;
        final int checkedGeneration=generation,request=++qualityRequest;
        final JSONObject sourceSnapshot,programSnapshot;
        try{sourceSnapshot=SketchDocument.copy(source);programSnapshot=SketchDocument.copy(program);}
        catch(Exception failure){message(failure.getMessage());return;}
        status.setText("Checking authored pose timing, bound parts, cels and camera…");
        if(qualityTask!=null)qualityTask.cancel(true);
        qualityTask=quality.submit(()->{
            try{
                JSONObject proof=SketchAnimationProductionPreflight.inspect(sourceSnapshot,programSnapshot);
                JSONArray notes=proof.getJSONArray("diagnostics");
                StringBuilder message=new StringBuilder();
                message.append("Native compiler: PASS\n");
                message.append("Frames: ").append(proof.getInt("frameCount")).append(" · FPS: ").append(proof.getInt("fps")).append("\n");
                JSONObject report=proof.getJSONObject("compiledReport");
                message.append("Bound art: ").append(report.getInt("boundObjectCount"))
                    .append(" · moving joints: ").append(report.getInt("animatedJointCount"))
                    .append(" · articulated frames: ").append(report.getInt("articulatedMotionFrames")).append("\n");
                message.append("Dialogue cues: ").append(report.getInt("dialogueCount"))
                    .append(" · mouth cels: ").append(report.getInt("mouthCelCount")).append("\n\n");
                for(int i=0;i<notes.length();i++)message.append("• ").append(notes.getString(i)).append("\n");
                String summary=message.toString();
                post(()->{if(checkedGeneration!=generation||request!=qualityRequest)return;
                    status.setText("Animation structural preflight finished · artistic review still required");
                    new AlertDialog.Builder(this).setTitle("2D production QA · source-verified").setMessage(summary).setPositiveButton("Close",null).show();
                });
            }catch(Exception failure){post(()->{if(checkedGeneration!=generation||request!=qualityRequest)return;status.setText("Production QA found a compilation problem");new AlertDialog.Builder(this).setTitle("2D production QA · fix before export").setMessage(failure.getMessage()).setPositiveButton("Close",null).show();});}
        });
    }
    private void saveScene(){if(!ready()||saving)return;stopPlayback();try{saveSnapshot(false);}catch(Exception invalid){message(invalid.getMessage());}}
    private void bake(){if(!ready()||saving)return;stopPlayback();try{int bound=0;JSONArray values=program.getJSONArray("rigs");for(int i=0;i<values.length();i++)bound+=values.getJSONObject(i).getJSONArray("bindings").length();if(bound==0)throw SketchDocument.bad("Bind prepared body parts to joints before baking articulated animation. Camera motion alone is not character animation.");saveSnapshot(true);}catch(Exception invalid){message(invalid.getMessage());}}
    private void saveSnapshot(boolean bake)throws Exception {
        saving=true;final int acceptedGeneration=generation;JSONObject snapshot=SketchDocument.copy(program);String id=animationId;long expected=revision;String sourceId=sketchId;long expectedSource=sourceRevision;
        status.setText(bake?"Saving scene and baking a separate editable animation…":"Saving animation scene…");
        io.execute(()->{JSONObject saved=null;try{
            saved=SketchAnimationOps.execute(this,"sketch_animation_save",new JSONObject().put("animationId",id).put("sketchId",sourceId).put("sourceRevision",expectedSource).put("expectedRevision",expected).put("operationId",UUID.randomUUID().toString()).put("program",snapshot));
            long savedRevision=saved.getLong("revision");JSONObject baked=null;if(bake)baked=SketchAnimationOps.execute(this,"sketch_animation_bake",new JSONObject().put("animationId",id).put("expectedRevision",savedRevision).put("operationId",UUID.randomUUID().toString()).put("outputSketchId","animated_"+UUID.randomUUID().toString().replace("-","")));
            JSONObject output=baked;post(()->{saving=false;if(id.equals(animationId)){revision=savedRevision;if(acceptedGeneration==generation)dirty=false;persistDraft();}if(output!=null){String outputId=output.optString("outputSketchId");new AlertDialog.Builder(this).setTitle("Editable animation baked").setMessage("Your source sketch is preserved. The new drawing contains articulated pose keys, camera keys and timed dialogue. Open it to refine cels or export the video.").setPositiveButton("Open animation",(d,w)->startActivity(new Intent(this,SketchStudioActivity.class).putExtra("sketchId",outputId))).setNegativeButton("Keep editing scene",null).show();}status.setText(output==null?"Scene saved · r"+savedRevision:"Baked separate editable animation · "+output.optString("outputSketchId"));});
        }catch(Exception failure){JSONObject acceptedSave=saved;post(()->{saving=false;if(acceptedSave!=null&&id.equals(animationId)){revision=acceptedSave.optLong("revision",revision);if(acceptedGeneration==generation)dirty=false;persistDraft();}status.setText((acceptedSave==null?"Animation retained as a local draft":"Scene saved · r"+acceptedSave.optLong("revision")+" · bake needs attention")+" · "+failure.getMessage());message(failure.getMessage());});}});
    }
    private void togglePlayback(){if(!ready())return;if(playing){stopPlayback();return;}mapping=false;mode.setText("Pose mode");playing=true;playFirst=frame;playStarted=SystemClock.uptimeMillis();play.setText("Pause");main.post(playTick);}
    private final Runnable playTick=new Runnable(){public void run(){if(!playing||destroyed||program==null)return;int fps=program.optInt("fps");int next=playFirst+(int)((SystemClock.uptimeMillis()-playStarted)*fps/1000);if(next>=program.optInt("frameCount")){frame=program.optInt("frameCount")-1;stopPlayback();refreshFrame();requestPreview();return;}if(frame!=next){frame=next;refreshFrame();requestPreview();}main.postDelayed(this,Math.max(8,1000/fps));}};
    private void stopPlayback(){playing=false;main.removeCallbacks(playTick);if(play!=null)play.setText("Play");}
    private boolean ready(){if(loading||program==null){message("Open a prepared sketch first");return false;}return true;}
    private void guide(){new AlertDialog.Builder(this).setTitle("Prepared sketches → 2D animation").setMessage("1. Prepare separate head, torso, arm, forearm, hand, thigh, shin and foot objects. Keep backgrounds on separate layers. A single photo does not contain hidden body parts.\n\n2. In Rig mode, drag the joints over the matching parts. Rig / bind assigns artwork to the selected joint. Add character makes another actor.\n\n3. Switch to Pose mode. Scrub to a frame, drag bone endpoints or set joint rotations, then save pose keys. Smooth timing eases between keys; linear timing moves at a steady rate; hold timing holds until the next key.\n\n4. Use Acting presets for walk/run, then Plant gait feet to calculate reachable IK and softened transitions; or add manual hip–knee–foot contacts. Add camera keys and timed dialogue. Play to inspect the actual artwork moving through the poses.\n\n5. Example → Rooftop Standoff demonstrates two human actors, differently prepared camera views, authored collision/reaction and separate dialogue. Review the actual rig and scene instead of treating the effect flash as contact proof. Save scene preserves your rig and controls. Bake animation makes a NEW editable sketch; your source stays intact. Open it for cel refinements and use Export video.\n\nChatGPT uses sketch_animation_save, sketch_animation_get and sketch_animation_bake with the same native program. Saved scenes can be listed with sketch_animation_list. No Gallery access is used. Limits and contact errors are reported; a successful export does not establish artistic quality.").setPositiveButton("Got it",null).show();}
    private void requestBack(){persistDraft();finish();}
    @Override public void onBackPressed(){requestBack();}
    @Override protected void onPause(){stopPlayback();++qualityRequest;if(qualityTask!=null)qualityTask.cancel(true);persistDraft();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("sketchId",sketchId);state.putString("animationId",revision>0?animationId:null);state.putInt("frame",frame);state.putBoolean("mapping",mapping);state.putString("selectedRig",selectedRig);state.putString("selectedJoint",selectedJoint);persistDraft();super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){destroyed=true;stopPlayback();if(qualityTask!=null)qualityTask.cancel(true);quality.shutdownNow();main.removeCallbacksAndMessages(null);canvas.replace(null);io.execute(()->{if(renderer!=null){renderer.close();renderer=null;}});io.shutdown();super.onDestroy();}
    private void post(Runnable run){main.post(()->{if(!destroyed)run.run();});}
    private void message(String text){if(text!=null&&!destroyed)Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private int dp(int value){return StudioUi.dp(this,value);}
    private LinearLayout column(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.VERTICAL);value.setPadding(dp(3),dp(2),dp(3),dp(2));return value;}
    private LinearLayout row(LinearLayout parent){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.HORIZONTAL);parent.addView(value,new LinearLayout.LayoutParams(-1,-2));return value;}
    private TextView label(String text,int size){TextView value=new TextView(this);value.setText(text);value.setTextColor(StudioUi.MUTED);value.setTextSize(size);value.setPadding(dp(3),dp(2),dp(3),dp(2));return value;}
    private Button button(LinearLayout parent,String label,Runnable action,boolean primary){Button value=new Button(this);value.setText(label);value.setContentDescription(label);StudioUi.styleButton(value,primary);value.setTextSize(12);value.setOnClickListener(v->{try{action.run();}catch(Exception failure){message(failure.getMessage());}});parent.addView(value,new LinearLayout.LayoutParams(0,-2,1));return value;}
    private Spinner picker(String name){Spinner value=new Spinner(this);value.setContentDescription(name);value.setMinimumHeight(dp(48));return value;}
    private EditText field(LinearLayout form,String name,String text,boolean number){form.addView(label(name,12));EditText value=new EditText(this);value.setContentDescription(name);value.setText(text);value.setTextColor(StudioUi.TEXT);value.setHintTextColor(StudioUi.MUTED);value.setTextSize(14);value.setMinimumHeight(dp(48));if(number)value.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);form.addView(value,new LinearLayout.LayoutParams(-1,-2));return value;}
    private double number(EditText field,double low,double high)throws Exception{return SketchAnimationUiModel.bounded(field.getText().toString(),low,high,String.valueOf(field.getContentDescription()));}
    private interface EditAction {void run()throws Exception;}
    private void dialog(String title,LinearLayout form,String verb,EditAction action){ScrollView scroll=new ScrollView(this);scroll.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton("Cancel",null).setPositiveButton(verb,null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{action.run();dialog.dismiss();}catch(Exception invalid){message(invalid.getMessage());}});}
    private void menu(String title,String[] labels,Runnable... actions){new AlertDialog.Builder(this).setTitle(title).setItems(labels,(d,index)->{if(ready())actions[index].run();}).show();}
    private abstract static class Selected implements android.widget.AdapterView.OnItemSelectedListener {public abstract void selected(int position);public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){selected(position);}public void onNothingSelected(android.widget.AdapterView<?> parent){}}

    private final class PoseCanvas extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF paper=new RectF();
        private Bitmap bitmap;
        private String dragged="";
        private double downX,downY,rootX,rootY;
        PoseCanvas(Context context){super(context);setMinimumHeight(dp(140));setFocusable(true);}
        void replace(Bitmap next){Bitmap old=bitmap;bitmap=next;if(old!=null&&old!=next)old.recycle();invalidate();}
        @Override protected void onDraw(Canvas destination){
            destination.drawColor(StudioUi.BG);if(source==null)return;
            double ratio=source.optDouble("width")/source.optDouble("height");float width=Math.min(getWidth()-dp(8),(float)((getHeight()-dp(8))*ratio)),height=(float)(width/ratio);paper.set((getWidth()-width)/2,(getHeight()-height)/2,(getWidth()+width)/2,(getHeight()+height)/2);
            if(bitmap!=null)destination.drawBitmap(bitmap,null,paper,paint);else{paint.setColor(StudioUi.SURFACE);destination.drawRect(paper,paint);}
            if(program==null||playing)return;
            try{JSONObject rig=SketchAnimationUiModel.rig(program,selectedRig);JSONArray values=rig.getJSONArray("joints");
                for(int i=0;i<values.length();i++){JSONObject joint=values.getJSONObject(i),position=position(joint);float x=px(position.optDouble("x")),y=py(position.optDouble("y"));String parent=joint.optString("parent");if(!parent.isEmpty()){JSONObject parentPosition=position(SketchAnimationUiModel.joint(rig,parent));paint.setColor(0xcce6ffdd);paint.setStrokeWidth(dp(2));destination.drawLine(x,y,px(parentPosition.optDouble("x")),py(parentPosition.optDouble("y")),paint);}paint.setColor(joint.getString("id").equals(selectedJoint)?0xffffd36b:StudioUi.ACCENT);destination.drawCircle(x,y,dp(joint.getString("id").equals(selectedJoint)?7:5),paint);paint.setColor(0xff111318);destination.drawCircle(x,y,dp(2),paint);}
                if(mapping&&!skinObjectId.isEmpty()&&skinVertex>=0){JSONArray vertices=vertices(SketchDocument.object(source,skinObjectId));if(skinVertex<vertices.length()){JSONArray point=vertices.getJSONArray(skinVertex);paint.setColor(0xffff7854);destination.drawCircle(px(point.getDouble(0)),py(point.getDouble(1)),dp(9),paint);}}
                if(!selectedJoint.isEmpty()){JSONObject location=position(SketchAnimationUiModel.joint(rig,selectedJoint));paint.setColor(StudioUi.TEXT);paint.setTextSize(dp(12));destination.drawText(selectedJoint,px(location.optDouble("x"))+dp(9),py(location.optDouble("y"))-dp(8),paint);}
            }catch(Exception ignored){}
        }
        private float px(double x){return paper.left+(float)x*paper.width();}private float py(double y){return paper.top+(float)y*paper.height();}
        private JSONObject position(JSONObject joint)throws Exception {
            if(mapping)return joint;JSONArray values=sampled==null?null:sampled.optJSONArray("joints");if(values!=null)for(int i=0;i<values.length();i++){JSONObject value=values.getJSONObject(i);if(selectedRig.equals(value.optString("rigId"))&&joint.getString("id").equals(value.optString("jointId")))return new JSONObject().put("x",value.optDouble("displayX",value.optDouble("x"))).put("y",value.optDouble("displayY",value.optDouble("y"))).put("rotation",value.optDouble("rotation"));}return joint;
        }
        private JSONObject worldPosition(JSONObject joint)throws Exception {JSONArray values=sampled==null?null:sampled.optJSONArray("joints");if(values!=null)for(int i=0;i<values.length();i++){JSONObject value=values.getJSONObject(i);if(selectedRig.equals(value.optString("rigId"))&&joint.getString("id").equals(value.optString("jointId")))return value;}return joint;}
        private boolean cameraApplies(){JSONArray values=sampled==null?null:sampled.optJSONArray("joints");if(values!=null)for(int i=0;i<values.length();i++){JSONObject value=values.optJSONObject(i);if(selectedRig.equals(value.optString("rigId")))return value.optBoolean("cameraApplied",false);}return false;}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(source==null||program==null||paper.width()<=0||playing)return false;
            try{JSONObject rig=SketchAnimationUiModel.rig(program,selectedRig);double x=(event.getX()-paper.left)/paper.width(),y=(event.getY()-paper.top)/paper.height();if(!mapping&&cameraApplies()){double[] world=SketchAnimationUiModel.canvasToWorld(source,program,frame,x,y);x=world[0];y=world[1];}
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN){float closest=dp(28);String hit="";JSONArray values=rig.getJSONArray("joints");for(int i=0;i<values.length();i++){JSONObject joint=values.getJSONObject(i),point=position(joint);float distance=(float)Math.hypot(event.getX()-px(point.optDouble("x")),event.getY()-py(point.optDouble("y")));if(distance<=closest){closest=distance;hit=joint.getString("id");}}if(hit.isEmpty())return false;stopPlayback();dragged=hit;selectedJoint=hit;joints.setSelection(Math.max(0,jointIds.indexOf(hit)));downX=x;downY=y;JSONObject prior=SketchAnimationUiModel.previousPose(rig,frame);rootX=prior.optDouble("rootX");rootY=prior.optDouble("rootY");getParent().requestDisallowInterceptTouchEvent(true);refreshFrame();invalidate();return true;}
                if((event.getActionMasked()==MotionEvent.ACTION_MOVE||event.getActionMasked()==MotionEvent.ACTION_UP)&&!dragged.isEmpty()){
                    JSONObject joint=SketchAnimationUiModel.joint(rig,dragged);
                    if(mapping){joint.put("x",Math.max(0,Math.min(1,x))).put("y",Math.max(0,Math.min(1,y)));}
                    else if(joint.optString("parent").isEmpty()){SketchAnimationUiModel.pose(program,selectedRig,frame,interpolation).put("rootX",Math.max(-4,Math.min(4,rootX+x-downX))).put("rootY",Math.max(-4,Math.min(4,rootY+y-downY)));}
                    else{
                        String parentId=joint.getString("parent");JSONObject parent=SketchAnimationUiModel.joint(rig,parentId),parentWorld=worldPosition(parent);String grandId=parent.optString("parent");double grandRotation=grandId.isEmpty()?0:worldPosition(SketchAnimationUiModel.joint(rig,grandId)).optDouble("rotation");
                        double rest=Math.atan2((joint.getDouble("y")-parent.getDouble("y"))*source.getDouble("height"),(joint.getDouble("x")-parent.getDouble("x"))*source.getDouble("width"));
                        double target=Math.atan2((y-parentWorld.getDouble("y"))*source.getDouble("height"),(x-parentWorld.getDouble("x"))*source.getDouble("width"));
                        double raw=Math.toDegrees(target-rest)-grandRotation,current=SketchAnimationUiModel.previousPose(rig,frame).getJSONObject("rotations").optDouble(parentId);double delta=SketchAnimationUiModel.nearestRotation(raw,current);
                        SketchAnimationUiModel.setRotation(program,selectedRig,frame,parentId,delta,interpolation);
                    }
                    changed();invalidate();if(event.getActionMasked()==MotionEvent.ACTION_UP){dragged="";getParent().requestDisallowInterceptTouchEvent(false);performClick();}return true;
                }
                if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){dragged="";getParent().requestDisallowInterceptTouchEvent(false);return true;}
            }catch(Exception invalid){message(invalid.getMessage());dragged="";}
            return false;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
