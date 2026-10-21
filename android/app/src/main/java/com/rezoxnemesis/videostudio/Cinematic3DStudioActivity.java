package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owner-facing editor for verified baked scenes; imported bundles are inert private data. */
public final class Cinematic3DStudioActivity extends Activity {
    static final int OUTPUT_WIDTH=720,OUTPUT_HEIGHT=1280;
    private static final int PICK_BUNDLE=5381,WRITE_REQUEST=5382;
    private static final long MAX_ARCHIVE_BYTES=64L*1024*1024;
    private static final int MAX_STATE_BYTES=4*1024*1024,MAX_STATE_FILES=64;
    private static final long MAX_STATE_STORAGE=64L*1024*1024;
    private static final int BACKGROUND=0xff090d17,ACCENT=0xff5adff4;
    private final ExecutorService ownerWorker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final java.util.List<Button> controls=new java.util.ArrayList<>();
    private ProjectStore projectStore;
    private ProjectStore.Project project;
    private JSONObject timeline,acceptedTimeline,editorBundle;
    private String bundleId="",programId="",expectedProgramSha256="",loadingProgramId="";
    private String outputFraming=CinematicOutputFraming.PORTRAIT;
    private long expectedSourceUpdatedAt;
    private int selectedShot,operationGeneration;
    private boolean busy,dirty,savePending,pickerUpdating,bindingReady,sceneRebuildRequired;
    private boolean cameraEditPending;
    private volatile boolean cameraUiActive;
    private volatile int cameraEditGeneration;
    private AlertDialog pendingCameraDialog;
    private volatile boolean destroyed;
    private volatile InputStream activeInput;
    private volatile OutputStream activeOutput;
    private JSONObject pendingSaveRequest;
    private String exportedRequest;
    private String privateStateId="",privateStateSha256="";
    private Spinner shots;
    private TextView binding,status;
    private Button addShotButton,duplicateButton,deleteShotButton;
    private android.window.OnBackInvokedCallback systemBackCallback;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BACKGROUND);getWindow().setNavigationBarColor(BACKGROUND);
        projectStore=new ProjectStore(this);String id=getIntent().getStringExtra("projectId");
        project=id==null?projectStore.active():projectStore.get(id);if(project==null){finish();return;}
        try{Cinematic3DTimeline.id(project.id);}catch(IllegalArgumentException error){Toast.makeText(this,"This source project has an unsupported identity for cinematic bundles",Toast.LENGTH_LONG).show();finish();return;}
        outputFraming=getPreferences(MODE_PRIVATE).getString(
                "cinematicOutput."+project.id,CinematicOutputFraming.PORTRAIT);
        try{CinematicOutputFraming.dimensions(outputFraming);}
        catch(IllegalArgumentException invalid){outputFraming=CinematicOutputFraming.PORTRAIT;}
        expectedSourceUpdatedAt=project.updatedAt;buildUi();
        if(android.os.Build.VERSION.SDK_INT>=33){systemBackCallback=this::requestBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,systemBackCallback);}
        if(state!=null){
            try{
                state=readPrivateState(state);
                bundleId=state.getString("bundleId","");programId=state.getString("programId","");expectedProgramSha256=state.getString("programSha","");
                expectedSourceUpdatedAt=state.getLong("sourceRevision",project.updatedAt);selectedShot=state.getInt("selectedShot",0);
                acceptedTimeline=state.containsKey("acceptedTimeline")?new JSONObject(state.getString("acceptedTimeline")):null;
                editorBundle=state.containsKey("editorBundle")?new JSONObject(state.getString("editorBundle")):null;
                sceneRebuildRequired=state.getBoolean("sceneRebuildRequired",false);
                timeline=sceneRebuildRequired&&state.containsKey("draft")?Cinematic3DTimeline.validate(new JSONObject(state.getString("draft"))):restoreTimeline(state,project.updatedAt);
                dirty=sceneRebuildRequired||state.getBoolean("dirty")&&expectedSourceUpdatedAt==project.updatedAt;
                if(state.containsKey("pendingSaveRequest"))pendingSaveRequest=new JSONObject(state.getString("pendingSaveRequest"));
                savePending=state.getBoolean("savePending")&&pendingSaveRequest!=null;
                bindingReady=!sceneRebuildRequired&&state.getBoolean("bindingReady")&&expectedSourceUpdatedAt==project.updatedAt;
                exportedRequest=state.getString("exportedRequest");
                if(expectedSourceUpdatedAt!=project.updatedAt){expectedSourceUpdatedAt=project.updatedAt;bindingReady=false;setStatus(sceneRebuildRequired?"Source project changed. Your scene draft is retained; source anchors must be verified again before rebuilding.":"Source project changed. Accepted shot controls restored; save explicitly to rebind before preview or export.");}
                refresh();if(savePending)reconcileSave();else{String requested=state.getString("loadingProgramId","");if(requested.isEmpty()&&timeline==null)requested=state.getString("programId","");if((requested==null||requested.isEmpty())&&timeline==null)requested=getIntent().getStringExtra("programId");if(requested!=null&&!requested.isEmpty())loadProgram(requested);}if(state.containsKey("draftStateSaveError"))setStatus(state.getString("draftStateSaveError"));return;
            }catch(Exception error){setStatus("Draft restoration failed: "+message(error));}
        }
        String requested=state==null?getIntent().getStringExtra("programId"):state.getString("programId",getIntent().getStringExtra("programId"));
        if(requested!=null&&!requested.isEmpty())loadProgram(requested);else setStatus("Install the bundled action scenes or import a cinematic bundle, then save your timeline.");
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(Color.WHITE);text.setPadding(dp(10),dp(5),dp(10),dp(5));return text;}
    private Button button(String name,Runnable work){Button button=new Button(this);button.setText(name);button.setAllCaps(false);button.setTextSize(12);button.setTextColor(Color.WHITE);button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff202f49));button.setOnClickListener(v->{if(busy){setStatus("Wait for the current scene operation.");return;}work.run();});controls.add(button);return button;}
    private void row(LinearLayout parent,Button...buttons){LinearLayout row=new LinearLayout(this);for(Button button:buttons)row.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));parent.addView(row);}
    private void buildUi(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BACKGROUND);scroll.addView(root);
        root.addView(label("Cinematic 3D Studio",22));
        root.addView(label("Play authored character scenes or build a sequence with bundled actions. Import a scene bundle to keep its characters, costumes and performance.",12));
        row(root,button("Bundled scenes",()->discardGuard(this::installBuiltin)),button("Import bundle",()->discardGuard(this::pickBundle)),button("Saved timelines",()->discardGuard(this::chooseProgram)));
        binding=label("",12);binding.setTextColor(ACCENT);root.addView(binding);
        row(root,button("Timeline name",this::renameTimeline),button("Frame rate",this::changeFps));
        shots=new Spinner(this);shots.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int index,long id){if(!pickerUpdating)selectedShot=index;}
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });root.addView(shots,new LinearLayout.LayoutParams(-1,dp(52)));
        row(root,button("Move earlier",()->moveSelected(-1)),button("Move later",()->moveSelected(1)),button("Edit shot",this::editSelected));
        row(root,button("Camera keys",this::editCameraKeys),button("Reference camera paths",this::chooseReferenceCameraPath));
        row(root,button("Exact camera angles",this::editExactOrbit));
        addShotButton=button("Add shot",this::addShot);duplicateButton=button("Duplicate",this::duplicateShot);deleteShotButton=button("Delete shot",this::deleteShot);row(root,addShotButton,duplicateButton,deleteShotButton);
        root.addView(label("Edit shot order, duration, impact hold, camera and effects. Custom pose, root and foot-contact changes need a rebuilt Blender bundle.",12));
        row(root,button("Actor body / material",this::chooseActor),button("Motion style",this::editMotionStyle));
        row(root,button("Pose keys / roots",this::editPoseKeys),button("Foot contacts",this::editFootContacts));
        root.addView(label("Body, material, motion style, local joint rotations and planted ankle anchors are cloud scene drafts. Rebuild in Blender and import the new scene before native preview or MP4. No live joint manipulation or original-image reconstruction is claimed.",12));
        row(root,button("Build updated scene",this::buildUpdatedScene),button("Export rebuild draft",this::exportRebuildDraft));
        row(root,button("Save timeline",this::saveTimeline),button("Restore saved",this::restoreAccepted));
        row(root,button("Output framing",this::chooseOutputFraming));
        row(root,button("Live 3D preview",this::openPreview),button("Native MP4",this::renderNative));
        row(root,button("Preview selected shot",this::previewSelectedShot),button("Shot timing",this::showShotTiming));
        row(root,button("Export authoring request",this::exportAuthoringRequest),button("Return",()->discardGuard(this::finish)));
        status=label("",12);status.setTextColor(ACCENT);root.addView(status);setContentView(scroll);refresh();
    }

    private void refresh(){
        if(binding==null)return;
        binding.setText(project.name+" • "+(timeline==null?"no scene bundle":timeline.optString("name")+" • "+("procedural_mannequin".equals(timeline.optString("motionProvider"))?"procedural mannequins":"authored 3D"))+"\n"+(expectedProgramSha256.isEmpty()?"Timeline not saved":("Saved generation "+expectedProgramSha256.substring(0,Math.min(12,expectedProgramSha256.length()))))+(sceneRebuildRequired?" • REBUILD REQUIRED":dirty?" • unsaved edits":"")+(busy?" • working":"")+"\n"+CinematicOutputFraming.label(outputFraming));
        for(Button control:controls)control.setEnabled(!busy);shots.setEnabled(!busy&&timeline!=null);
        int count=timeline==null?0:timeline.optJSONArray("shots").length();boolean library=isActionLibrary();addShotButton.setEnabled(!busy&&library&&count<80);duplicateButton.setEnabled(!busy&&library&&count>0&&count<80);deleteShotButton.setEnabled(!busy&&library&&count>1);
        java.util.List<String> labels=new java.util.ArrayList<>();if(timeline!=null){JSONArray entries=timeline.optJSONArray("shots");if(entries!=null)for(int i=0;i<entries.length();i++){JSONObject shot=entries.optJSONObject(i);if(shot!=null)labels.add((i+1)+". "+shot.optString("action")+" • "+shot.optInt("durationFrames")+" frames • "+shot.optString("id"));}}
        pickerUpdating=true;shots.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));selectedShot=Math.max(0,Math.min(selectedShot,labels.size()-1));if(!labels.isEmpty())shots.setSelection(selectedShot);pickerUpdating=false;
    }
    private void setStatus(String value){if(status!=null)status.setText(value);}
    private static String message(Throwable error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    private boolean requireTimeline(){if(timeline==null){setStatus("Install or import a scene bundle first.");return false;}return true;}
    private boolean requireSaved(){if(!requireTimeline())return false;if(sceneRebuildRequired){setStatus("This scene draft needs a Blender rebuild. Use Build updated scene, then import its new bundle before native preview or MP4.");return false;}if(dirty||!bindingReady||expectedProgramSha256.isEmpty()){setStatus("Save this timeline before preview, rendering or authoring export.");return false;}ProjectStore.Project fresh=projectStore.get(project.id);if(fresh==null||fresh.updatedAt!=expectedSourceUpdatedAt){bindingReady=false;setStatus("Source project changed. Restore or save explicitly before preview or export.");return false;}return true;}
    private void discardGuard(Runnable work){if(!dirty){work.run();return;}new AlertDialog.Builder(this).setTitle("Leave the unsaved timeline?").setMessage("Save first to keep the current shot edits.").setPositiveButton("Leave draft",(d,w)->work.run()).setNegativeButton("Keep editing",null).show();}
    private void requestBack(){if(busy){setStatus("Wait for the current scene operation before leaving.");return;}discardGuard(this::finish);}
    @Override public void onBackPressed(){requestBack();}

    @FunctionalInterface private interface Work {JSONObject run() throws Exception;}
    @FunctionalInterface private interface Accept {void run(JSONObject receipt) throws Exception;}
    @FunctionalInterface private interface Failure {void run(Throwable problem);}
    private void perform(String progress,Work work,Accept accept){
        perform(progress,work,accept,null);
    }
    private void perform(String progress,Work work,Accept accept,Failure onFailure){
        if(busy||destroyed)return;busy=true;int generation=++operationGeneration;setStatus(progress);refresh();
        ownerWorker.execute(()->{JSONObject value=null;Throwable failure=null;try{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Operation cancelled");value=work.run();}catch(Exception|OutOfMemoryError error){failure=error;}
            final JSONObject receipt=value;final Throwable problem=failure;main.post(()->{if(destroyed||generation!=operationGeneration)return;busy=false;
                try{if(problem!=null){if(message(problem).contains("SOURCE_")&&message(problem).contains("_CHANGED"))bindingReady=false;if(message(problem).contains("SOURCE_PROJECT_CHANGED")&&savePending&&acceptedTimeline!=null){timeline=copy(acceptedTimeline);dirty=false;}setStatus("Operation failed: "+message(problem));if(onFailure!=null)onFailure.run(problem);}else accept.run(receipt);}catch(Exception error){setStatus("Operation failed: "+message(error));}finally{savePending=false;pendingSaveRequest=null;loadingProgramId="";refresh();}
            });
        });
    }
    private JSONObject sourceRequest() throws Exception{return new JSONObject().put("projectId",project.id).put("sourceProjectUpdatedAt",expectedSourceUpdatedAt);}
    private void installBuiltin(){new AlertDialog.Builder(this).setTitle("Choose offline action library").setItems(new String[]{"21 actions · combat, walk, jump, gestures", "Original 11-action sword library"},(dialog,index)->{try{JSONObject request=sourceRequest().put("pack",index==0?"extended":"starter");perform("Installing bundled native 3D scenes…",()->NativeCinematicBundleStore.installBuiltin(getApplicationContext(),projectStore,request),this::acceptBundle);}catch(Exception error){setStatus(message(error));}}).setNegativeButton("Cancel",null).show();}
    private void acceptBundle(JSONObject receipt) throws Exception {
        JSONObject next=Cinematic3DTimeline.validate(receipt.getJSONObject("timeline"));bundleId=receipt.getString("bundleId");editorBundle=editorProjection(receipt);timeline=next;acceptedTimeline=copy(next);programId=UUID.randomUUID().toString();expectedProgramSha256="";selectedShot=0;dirty=true;bindingReady=false;sceneRebuildRequired=false;
        ProjectStore.Project fresh=projectStore.get(project.id);if(fresh!=null){project=fresh;expectedSourceUpdatedAt=fresh.updatedAt;}
        setStatus("Scene bundle installed. Review and save the timeline. Baked poses remain authored scene data.");
    }
    private JSONObject getRequest(String id,String sha) throws Exception {JSONObject request=new JSONObject().put("projectId",project.id).put("programId",id);if(sha!=null&&!sha.isEmpty())request.put("expectedProgramSha256",sha);return request;}
    private void acceptProgram(JSONObject receipt) throws Exception {
        timeline=Cinematic3DTimeline.validate(receipt.getJSONObject("timeline"));acceptedTimeline=copy(timeline);editorBundle=receipt.has("editorBundle")?copy(receipt.getJSONObject("editorBundle")):null;bundleId=receipt.getString("bundleId");programId=receipt.getString("programId");expectedProgramSha256=receipt.getString("sha256");boolean editOnly=receipt.optBoolean("editOnly",false);expectedSourceUpdatedAt=editOnly?receipt.getLong("currentSourceProjectUpdatedAt"):receipt.getLong("sourceProjectUpdatedAt");dirty=false;bindingReady=!editOnly;sceneRebuildRequired=false;
        ProjectStore.Project fresh=projectStore.get(project.id);if(fresh!=null)project=fresh;setStatus(editOnly?"Saved timeline is open for editing. Save explicitly to verify and rebind this source before preview or export.":"Saved timeline loaded. Native execution and cinematic acceptance require device verification.");
    }
    private JSONObject readProgramForStudio(JSONObject request) throws Exception {JSONObject editable=Cinematic3DProgramStore.readForEdit(getApplicationContext(),projectStore,request);if(!editable.getBoolean("sourceRevisionChanged")){try{return Cinematic3DProgramStore.get(getApplicationContext(),projectStore,request);}catch(IllegalArgumentException|IllegalStateException unavailable){return editable;}}return editable;}
    private void loadProgram(String id){try{JSONObject request=getRequest(id,"");loadingProgramId=id;perform("Loading saved timeline…",()->readProgramForStudio(request),this::acceptProgram);}catch(Exception error){loadingProgramId="";setStatus(message(error));}}
    private void chooseProgram(){try{JSONObject request=new JSONObject().put("projectId",project.id);perform("Reading saved timelines…",()->Cinematic3DProgramStore.list(getApplicationContext(),projectStore,request),receipt->{JSONArray list=receipt.getJSONArray("programs");if(list.length()==0){setStatus("No cinematic timelines saved in this project.");return;}String[] names=new String[list.length()];for(int i=0;i<list.length();i++){JSONObject entry=list.getJSONObject(i);names[i]=entry.optString("name",entry.optJSONObject("timeline")==null?entry.optString("programId"):entry.getJSONObject("timeline").optString("name"))+" • "+entry.optString("programId");}new AlertDialog.Builder(this).setTitle("Saved cinematic timelines").setItems(names,(d,index)->{try{loadProgram(list.getJSONObject(index).getString("programId"));}catch(Exception error){setStatus(message(error));}}).setNegativeButton("Cancel",null).show();});}catch(Exception error){setStatus(message(error));}}
    private void saveTimeline(){if(!requireTimeline())return;if(sceneRebuildRequired){setStatus("The saved scene remains intact. Body, style, pose and contact edits require Build updated scene; they cannot bind to the old geometry.");return;}try{
        ProjectStore.Project fresh=projectStore.get(project.id);if(fresh==null)throw new IllegalArgumentException("Source project no longer exists");
        if(fresh.updatedAt!=expectedSourceUpdatedAt){if(acceptedTimeline!=null)timeline=copy(acceptedTimeline);dirty=false;bindingReady=false;expectedSourceUpdatedAt=fresh.updatedAt;project=fresh;refresh();setStatus("Source changed. Accepted controls restored. Review and press Save again to rebind to this source revision.");return;}
        if(programId.isEmpty())programId=UUID.randomUUID().toString();JSONObject request=saveRequest(project.id,expectedSourceUpdatedAt,bundleId,programId,expectedProgramSha256,timeline);pendingSaveRequest=copy(request);savePending=true;
        perform("Saving this exact timeline generation…",()->Cinematic3DProgramStore.save(getApplicationContext(),projectStore,request),receipt->{acceptProgram(receipt);setStatus("Timeline saved. Live preview and native MP4 use this exact generation.");});
    }catch(Exception error){savePending=false;pendingSaveRequest=null;setStatus(message(error));}}
    private void reconcileSave(){
        final JSONObject frozen=pendingSaveRequest;
        try{JSONObject request=getRequest(programId,"");perform("Checking the interrupted save…",()->Cinematic3DProgramStore.get(getApplicationContext(),projectStore,request),receipt->{
            if(frozen!=null&&receipt.getString("bundleId").equals(frozen.getString("bundleId"))&&receipt.getJSONObject("timeline").toString().equals(Cinematic3DTimeline.validate(frozen.getJSONObject("timeline")).toString())){acceptProgram(receipt);setStatus("The interrupted save was already published; its generation has been restored.");}
            else{setStatus("The save was not accepted. Your draft is retained; reload the saved timeline before retrying.");dirty=true;}
        });}catch(Exception error){savePending=false;setStatus(message(error));}
    }
    private void restoreAccepted(){if(acceptedTimeline==null){setStatus("No accepted timeline is available to restore.");return;}try{timeline=copy(acceptedTimeline);dirty=false;bindingReady=false;sceneRebuildRequired=false;ProjectStore.Project fresh=projectStore.get(project.id);if(fresh!=null){project=fresh;expectedSourceUpdatedAt=fresh.updatedAt;}refresh();if(expectedProgramSha256.isEmpty())setStatus("Installed timeline restored. Save to create its first generation.");else{JSONObject request=getRequest(programId,expectedProgramSha256);perform("Checking saved source bindings…",()->Cinematic3DProgramStore.get(getApplicationContext(),projectStore,request),this::acceptProgram);}}catch(Exception error){setStatus(message(error));}}

    private void renameTimeline(){if(!requireTimeline())return;EditText input=new EditText(this);input.setText(timeline.optString("name"));new AlertDialog.Builder(this).setTitle("Timeline name").setView(input).setPositiveButton("Apply",(d,w)->{try{JSONObject edited=copy(timeline);edited.put("name",input.getText().toString().trim());timeline=Cinematic3DTimeline.validate(edited);dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}).setNegativeButton("Cancel",null).show();}
    private void changeFps(){if(!requireTimeline())return;String[] values={"24 FPS","30 FPS","60 FPS"};new AlertDialog.Builder(this).setTitle("Timeline frame rate").setItems(values,(d,index)->{try{JSONObject edited=copy(timeline);edited.put("fps",new int[]{24,30,60}[index]);timeline=Cinematic3DTimeline.validate(edited);dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}).setNegativeButton("Cancel",null).show();}
    private void moveSelected(int direction){if(!requireTimeline())return;int next=selectedShot+direction;try{timeline=moveShot(timeline,selectedShot,next);selectedShot=next;dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}
    private boolean authoredCameraSelected() throws Exception {return editorBundle!=null&&"authored".equals(Cinematic3DSceneSampling.cameraReplay(Cinematic3DSceneSampling.sceneForShot(editorBundle,timeline.getJSONArray("shots").getJSONObject(selectedShot))));}
    private boolean isActionLibrary(){try{return editorBundle!=null&&editorBundle.getJSONObject("manifest").getJSONObject("generator").optBoolean("actionLibrary",false);}catch(Exception ignored){return false;}}
    private java.util.List<String> libraryActions() throws Exception {requireLibrary(editorBundle);java.util.List<String> actions=new java.util.ArrayList<>();JSONArray scenes=editorBundle.getJSONObject("manifest").getJSONArray("scenes");for(int i=0;i<scenes.length();i++){String action=scenes.getJSONObject(i).getString("action");if(!actions.contains(action)){libraryTemplate(editorBundle,action);actions.add(action);}}return actions;}
    private void addShot(){if(!requireTimeline())return;try{java.util.List<String> actions=libraryActions();new AlertDialog.Builder(this).setTitle("Add a declared library action").setItems(actions.toArray(new String[0]),(d,index)->{try{timeline=addLibraryShot(timeline,editorBundle,actions.get(index),UUID.randomUUID().toString());selectedShot=timeline.getJSONArray("shots").length()-1;dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}).setNegativeButton("Cancel",null).show();}catch(Exception error){setStatus(message(error));}}
    private void duplicateShot(){if(!requireTimeline())return;try{timeline=duplicateLibraryShot(timeline,editorBundle,selectedShot,UUID.randomUUID().toString());selectedShot=timeline.getJSONArray("shots").length()-1;dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}
    private void deleteShot(){if(!requireTimeline())return;try{timeline=deleteLibraryShot(timeline,editorBundle,selectedShot);selectedShot=Math.min(selectedShot,timeline.getJSONArray("shots").length()-1);dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}
    private EditText numeric(LinearLayout parent,String name,String value,boolean decimal){parent.addView(label(name,12));EditText input=new EditText(this);input.setText(value);input.setTextColor(Color.WHITE);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED|(decimal?android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL:0));parent.addView(input);return input;}
    private CheckBox check(LinearLayout parent,String title,boolean checked){CheckBox control=new CheckBox(this);control.setText(title);control.setTextColor(Color.WHITE);control.setChecked(checked);parent.addView(control);return control;}
    private void editSelected(){if(!requireTimeline())return;try{
        JSONObject shot=timeline.getJSONArray("shots").getJSONObject(selectedShot),camera=shot.getJSONObject("camera"),vfx=shot.getJSONObject("vfx");int shotIndex=selectedShot;
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setBackgroundColor(BACKGROUND);ScrollView scroll=new ScrollView(this);scroll.addView(form);
        form.addView(label("Action",12));Spinner action=new Spinner(this);java.util.List<String> actionNames=isActionLibrary()?libraryActions():java.util.Collections.singletonList(shot.getString("action"));action.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,actionNames));action.setSelection(actionNames.indexOf(shot.getString("action")));action.setEnabled(isActionLibrary());form.addView(action);if(!isActionLibrary())form.addView(label("Custom baked actions need a rebuilt bundle.",11));
        EditText duration=numeric(form,"Duration in frames (8–600)",String.valueOf(shot.getInt("durationFrames")),false),hold=numeric(form,"Impact hold (0 or 2–5 frames)",String.valueOf(shot.getInt("hitStopFrames")),false);
        form.addView(label("Camera",12));Spinner mode=new Spinner(this);String[] modes={"wide","orbit","macro","top_down"};mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes));for(int i=0;i<modes.length;i++)if(modes[i].equals(camera.getString("mode")))mode.setSelection(i);form.addView(mode);
        final int authoredMode=mode.getSelectedItemPosition();CheckBox authoredTrack=camera.has("keyframes")||camera.has("orbit")?check(form,camera.has("orbit")?"Keep exact orbit":"Use authored camera track",true):null;if(authoredTrack!=null){mode.setEnabled(false);authoredTrack.setOnCheckedChangeListener((control,checked)->{if(checked)mode.setSelection(authoredMode);mode.setEnabled(!checked);});form.addView(label("Uncheck to replace the camera path with a preset. Actor and weapon poses keep their baked motion.",11));}
        EditText fov=numeric(form,"Field of view (35–100°)",String.valueOf(camera.getDouble("fovDegrees")),true),shake=numeric(form,"Camera shake (0–1)",String.valueOf(camera.getDouble("shakeIntensity")),true);
        if(authoredCameraSelected()){mode.setEnabled(false);fov.setEnabled(false);shake.setEnabled(false);if(authoredTrack!=null)authoredTrack.setEnabled(false);form.addView(label("This scene keeps its authored camera. Rebuild the bundle to change framing.",11));}
        CheckBox spark=check(form,"Spark burst",vfx.getBoolean("sparkBurst")),trail=check(form,"Slash trail",vfx.getBoolean("slashTrail")),rubble=check(form,"Contact debris",vfx.getBoolean("rubble"));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(shot.getString("action")+" • "+shot.getString("id")).setView(scroll).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String selectedAction=actionNames.get(action.getSelectedItemPosition());JSONObject edited=timeline;if(!selectedAction.equals(shot.getString("action")))edited=changeLibraryAction(timeline,editorBundle,shotIndex,selectedAction);if(authoredTrack!=null&&!authoredTrack.isChecked())edited=chooseCameraTrack(edited,shotIndex,false);int hitStop=Integer.parseInt(hold.getText().toString());if(!selectedAction.equals(shot.getString("action"))&&!isImpact(selectedAction))hitStop=0;edited=editShot(edited,shotIndex,Integer.parseInt(duration.getText().toString()),hitStop,modes[mode.getSelectedItemPosition()],Double.parseDouble(fov.getText().toString()),Double.parseDouble(shake.getText().toString()),spark.isChecked(),trail.isChecked(),rubble.isChecked());timeline=edited;dirty=true;refresh();dialog.dismiss();}catch(Exception error){Toast.makeText(this,message(error),Toast.LENGTH_LONG).show();}}));dialog.show();
    }catch(Exception error){setStatus(message(error));}}

    private void acceptCameraOperations(JSONArray operations,AlertDialog dialog,TextView error) throws Exception {
        if(busy||destroyed||!cameraUiActive)throw new IllegalStateException("Wait for the current camera operation");
        final JSONObject frozen=copy(timeline);final JSONArray frozenOperations=new JSONArray(operations.toString());
        final String fingerprint=Cinematic3DTimeline.canonical(frozen),frozenProject=project.id,frozenBundle=bundleId,frozenProgram=programId,frozenProgramSha=expectedProgramSha256;
        final long revision=expectedSourceUpdatedAt;final int generation=++cameraEditGeneration;
        cameraEditPending=true;pendingCameraDialog=dialog;error.setText("");dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        perform("Checking the camera path on the scene worker…",()->{
            if(!cameraUiActive||generation!=cameraEditGeneration)throw new InterruptedException("Camera edit cancelled while leaving the studio");
            return Cinematic3DCameraEditOps.apply(frozen,frozenOperations,()->!cameraUiActive||generation!=cameraEditGeneration);
        },edited->{
            cameraEditPending=false;pendingCameraDialog=null;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            ProjectStore.Project current=projectStore.get(frozenProject);
            if(!cameraUiActive||generation!=cameraEditGeneration)return;
            if(current==null||current.updatedAt!=revision||!frozenProject.equals(project.id)||!frozenBundle.equals(bundleId)||!frozenProgram.equals(programId)||!frozenProgramSha.equals(expectedProgramSha256)||expectedSourceUpdatedAt!=revision||timeline==null||!fingerprint.equals(Cinematic3DTimeline.canonical(timeline))){
                String warning="The source or draft changed. This camera edit was not applied; reopen Camera keys for the current draft.";error.setText(warning);setStatus(warning);return;
            }
            timeline=edited;dirty=true;refresh();setStatus("Camera path updated in this draft. Save the timeline before native preview or MP4.");dialog.dismiss();
        },problem->{cameraEditPending=false;pendingCameraDialog=null;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);error.setText(message(problem));});
    }
    private EditText cameraNumber(LinearLayout form,String title,String value,String tag) {
        EditText input=numeric(form,title,value,true);input.setTag(tag);input.setContentDescription(title);return input;
    }
    /**
     * One-tap cinema choreography using existing native Filament/Y-up camera
     * keyframes; real actor pose, 3D geometry and source assets are unchanged.
     * The existing worker performs complete per-frame preflight before CAS
     * applying the draft; nothing saves until Save timeline is selected.
     */
    private void chooseReferenceCameraPath(){if(!requireTimeline())return;try{
        if(authoredCameraSelected()){
            setStatus("This baked scene uses its authored 3D camera. Rebuild the bundle to change its framing.");
            return;
        }
        JSONObject shot=timeline.getJSONArray("shots").getJSONObject(selectedShot);
        String shotId=shot.getString("id");
        if(shot.getJSONObject("camera").has("orbit")){setStatus("Clear the exact orbit before replacing it with a keyframed camera path.");return;}
        JSONArray initial=Cinematic3DTimeline.sampleShot(shot,0,timeline.getInt("fps"))
                .getJSONObject("camera").getJSONArray("target");
        LinearLayout form=draftForm();
        form.addView(label("Real perspective views, not fake zoom on still art. "
                +"Creates editable keys for this shot only; your 3D asset, rig, source proof, "
                +"other shots and lighting remain unchanged.",12));
        String[] choices={"360° car / model turntable","Hero push-in / three-quarter",
                "Follow an acting character","Anime action arc","Fine-detail reveal"};
        String[] presets=CinematicReferenceCameraPath.presets();
        Spinner picker=select(form,"Camera movement",choices,choices[0]);
        EditText x=cameraNumber(form,"Look-at X (world metres)",
                String.valueOf(initial.getDouble(0)),"reference_target_x");
        EditText y=cameraNumber(form,"Look-at Y (world metres)",
                String.valueOf(initial.getDouble(1)),"reference_target_y");
        EditText z=cameraNumber(form,"Look-at Z (world metres)",
                String.valueOf(initial.getDouble(2)),"reference_target_z");
        EditText distance=cameraNumber(form,"Camera distance (1.8–30 m)","5.2","reference_radius");
        EditText strength=cameraNumber(form,"Camera motion strength (0.1–1)","1","reference_strength");
        TextView error=label("",12);error.setTextColor(0xffff8888);
        form.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("Reference camera • "+shot.getString("action"))
                .setView(scrollForm(form))
                .setPositiveButton("Preview and apply",null)
                .setNegativeButton("Cancel",null).create();
        dialog.setOnDismissListener(ignored->{
            if(pendingCameraDialog==dialog)cancelCameraEdit();
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                int selected=picker.getSelectedItemPosition();
                if(selected<0||selected>=presets.length)
                    throw new IllegalArgumentException("Select a camera movement");
                JSONArray target=new JSONArray().put(Double.parseDouble(x.getText().toString()))
                        .put(Double.parseDouble(y.getText().toString()))
                        .put(Double.parseDouble(z.getText().toString()));
                JSONObject operation=new JSONObject().put("type","reference_camera_path")
                        .put("shotId",shotId).put("preset",presets[selected])
                        .put("target",target)
                        .put("radius",Double.parseDouble(distance.getText().toString()))
                        .put("strength",Double.parseDouble(strength.getText().toString()));
                acceptCameraOperations(new JSONArray().put(operation),dialog,error);
            }catch(Exception invalid){error.setText(message(invalid));}
        });
    }catch(Exception error){setStatus(message(error));}}

    private void editExactOrbit(){if(!requireTimeline())return;try{
        if(authoredCameraSelected()){setStatus("This scene keeps its authored camera. Its framing cannot be replaced by an orbit.");return;}
        JSONObject shot=timeline.getJSONArray("shots").getJSONObject(selectedShot),camera=shot.getJSONObject("camera");
        JSONObject existing=camera.optJSONObject("orbit"),initial=Cinematic3DTimeline.sampleShot(shot,0,timeline.getInt("fps")).getJSONObject("camera");
        JSONArray target=initial.getJSONArray("target"),position=initial.getJSONArray("position");
        double dx=position.getDouble(0)-target.getDouble(0),dy=position.getDouble(1)-target.getDouble(1),dz=position.getDouble(2)-target.getDouble(2);
        double azimuth=Math.toDegrees(Math.atan2(dx,dz)),elevation=Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
        LinearLayout form=draftForm();form.addView(label("Orbit existing 3D geometry at a constant distance. 0° is world +Z, 90° is +X. Positive elevation looks from above. This does not draw unseen anatomy or reconstruct your reference.",12));
        form.addView(label("Apply replaces this shot's XYZ camera keys and disables shake. The selected endpoints occupy its first and last frames; actor timing and source geometry stay unchanged. Equal start/end angles hold one exact view.",12));
        EditText x=cameraNumber(form,"Target X (metres)",String.valueOf(target.getDouble(0)),"orbit_x"),y=cameraNumber(form,"Target Y (metres)",String.valueOf(target.getDouble(1)),"orbit_y"),z=cameraNumber(form,"Target Z (metres)",String.valueOf(target.getDouble(2)),"orbit_z");
        EditText radius=cameraNumber(form,"Orbit radius (0.5–30 m)",String.valueOf(existing==null?Math.hypot(Math.hypot(dx,dy),dz):existing.getDouble("radius")),"orbit_radius");
        EditText start=cameraNumber(form,"Start azimuth (degrees)",String.valueOf(existing==null?azimuth:existing.getDouble("startAzimuthDegrees")),"orbit_start_azimuth"),end=cameraNumber(form,"End azimuth (degrees)",String.valueOf(existing==null?azimuth+360:existing.getDouble("endAzimuthDegrees")),"orbit_end_azimuth");
        EditText low=cameraNumber(form,"Start elevation (degrees)",String.valueOf(existing==null?elevation:existing.getDouble("startElevationDegrees")),"orbit_start_elevation"),high=cameraNumber(form,"End elevation (degrees)",String.valueOf(existing==null?elevation:existing.getDouble("endElevationDegrees")),"orbit_end_elevation");
        TextView error=label("",12);error.setTextColor(0xffff8888);error.setTag("orbit_error");form.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Exact camera angles").setView(scrollForm(form)).setPositiveButton("Apply orbit",null).setNegativeButton("Cancel",null).create();
        Button clear=button("Clear exact orbit",()->{try{acceptCameraOperations(new JSONArray().put(new JSONObject().put("type","clear_camera_orbit").put("shotId",shot.getString("id"))),dialog,error);}catch(Exception invalid){error.setText(message(invalid));}});form.addView(clear);
        dialog.setOnDismissListener(ignored->{controls.remove(clear);if(pendingCameraDialog==dialog)cancelCameraEdit();});dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            JSONObject orbit=new JSONObject().put("target",new JSONArray().put(Double.parseDouble(x.getText().toString())).put(Double.parseDouble(y.getText().toString())).put(Double.parseDouble(z.getText().toString())))
                    .put("radius",Double.parseDouble(radius.getText().toString())).put("startAzimuthDegrees",Double.parseDouble(start.getText().toString())).put("endAzimuthDegrees",Double.parseDouble(end.getText().toString()))
                    .put("startElevationDegrees",Double.parseDouble(low.getText().toString())).put("endElevationDegrees",Double.parseDouble(high.getText().toString()));
            acceptCameraOperations(new JSONArray().put(new JSONObject().put("type","set_camera_orbit").put("shotId",shot.getString("id")).put("orbit",orbit)),dialog,error);
        }catch(Exception invalid){error.setText(message(invalid));}});
    }catch(Exception error){setStatus(message(error));}}

    private void editCameraKeys(){if(!requireTimeline())return;try{
        if(authoredCameraSelected()){setStatus("This scene uses its authored camera. Rebuild the bundle to change its framing.");return;}
        JSONObject shot=timeline.getJSONArray("shots").getJSONObject(selectedShot),camera=shot.getJSONObject("camera");String shotId=shot.getString("id");if(camera.has("orbit")){setStatus("Clear the exact orbit before editing XYZ camera keys.");return;}JSONArray keys=camera.optJSONArray("keyframes");
        LinearLayout form=draftForm();form.addView(label("Author a camera path in Y-up metres. Keys interpolate over shot time while actor impact holds keep their own clock.",12));
        java.util.List<String> names=new java.util.ArrayList<>();names.add("New key");for(int i=0;keys!=null&&i<keys.length();i++)names.add(String.format(java.util.Locale.US,"Key %d · phase %.6f",i+1,keys.getJSONObject(i).getDouble("phase")));
        Spinner picker=select(form,"Camera key",names.toArray(new String[0]),names.get(keys==null?0:1));
        EditText phase=cameraNumber(form,"Shot phase (0–1)","0","camera_phase");
        EditText[][] vectors=new EditText[3][3];String[] fields={"position","target","up"},titles={"Camera position","Look-at target","Camera up"},axes={"X right","Y up","Z depth"};
        for(int vector=0;vector<3;vector++)for(int axis=0;axis<3;axis++)vectors[vector][axis]=cameraNumber(form,titles[vector]+" · "+axes[axis]+" (±10000)","0","camera_"+fields[vector]+"_"+axis);
        CheckBox explicitUp=check(form,"Use an explicit up vector",true);
        JSONObject initial=Cinematic3DTimeline.sampleShot(shot,0,timeline.getInt("fps")).getJSONObject("camera");
        JSONArray defaultUp=new JSONArray("top_down".equals(camera.getString("mode"))?new double[]{0,0,-1}:new double[]{0,1,0});
        Runnable load=()->{try{
            int selected=picker.getSelectedItemPosition();JSONObject key=selected>0&&keys!=null?keys.getJSONObject(selected-1):initial;phase.setText(selected>0?String.valueOf(key.getDouble("phase")):"0");explicitUp.setChecked(selected==0||key.has("up"));
            for(int vector=0;vector<3;vector++){JSONArray value=key.optJSONArray(fields[vector]);if(value==null)value=defaultUp;for(int axis=0;axis<3;axis++)vectors[vector][axis].setText(String.valueOf(value.getDouble(axis)));}
        }catch(Exception error){setStatus(message(error));}};
        selection(picker,load);load.run();
        TextView error=label("",12);error.setTextColor(0xffff8888);error.setTag("camera_error");form.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(shot.getString("action")+" · camera path").setView(scrollForm(form)).setPositiveButton("Apply key",null).setNegativeButton("Close",null).create();
        Button remove=button("Remove selected key",()->{try{if(picker.getSelectedItemPosition()<1||keys==null)throw new IllegalArgumentException("Choose an existing camera key to remove");JSONObject operation=new JSONObject().put("type","remove_camera_key").put("shotId",shotId).put("phase",keys.getJSONObject(picker.getSelectedItemPosition()-1).getDouble("phase"));acceptCameraOperations(new JSONArray().put(operation),dialog,error);}catch(Exception invalid){error.setText(message(invalid));}});form.addView(remove);
        Button clear=button("Clear camera path",()->{try{acceptCameraOperations(new JSONArray().put(new JSONObject().put("type","clear_camera_keys").put("shotId",shotId)),dialog,error);}catch(Exception invalid){error.setText(message(invalid));}});form.addView(clear);
        dialog.setOnDismissListener(ignored->{controls.remove(remove);controls.remove(clear);if(pendingCameraDialog==dialog)cancelCameraEdit();});
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            JSONObject operation=new JSONObject().put("type","upsert_camera_key").put("shotId",shotId).put("phase",Double.parseDouble(phase.getText().toString()));
            for(int vector=0;vector<3;vector++)if(vector<2||explicitUp.isChecked()){JSONArray value=new JSONArray();for(int axis=0;axis<3;axis++)value.put(Double.parseDouble(vectors[vector][axis].getText().toString()));operation.put(fields[vector],value);}
            acceptCameraOperations(new JSONArray().put(operation),dialog,error);
        }catch(Exception invalid){error.setText(message(invalid));}});
    }catch(Exception error){setStatus(message(error));}}

    private LinearLayout draftForm(){LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(10),dp(4),dp(10),dp(4));form.setBackgroundColor(BACKGROUND);return form;}
    private ScrollView scrollForm(LinearLayout form){ScrollView scroll=new ScrollView(this);scroll.addView(form);return scroll;}
    private Spinner select(LinearLayout form,String title,String[] values,String selected){form.addView(label(title,12));Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));for(int i=0;i<values.length;i++)if(values[i].equals(selected))spinner.setSelection(i);form.addView(spinner);return spinner;}
    private void selection(Spinner spinner,Runnable work){spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> parent){}public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){work.run();}});}
    private void acceptSceneDraft(JSONObject edited){timeline=edited;dirty=true;bindingReady=false;sceneRebuildRequired=true;refresh();setStatus("Scene draft retained. Build updated scene runs Blender on your inference computer; import its new bundle before native preview or MP4. The saved bundle and timeline remain intact.");}
    private void chooseActor(){if(!requireTimeline())return;new AlertDialog.Builder(this).setTitle("Choose actor to author").setItems(new String[]{"Black actor","White actor"},(d,index)->editActor(Cinematic3DDraftForms.ACTORS[index])).setNegativeButton("Cancel",null).show();}
    private void editActor(String actor){try{
        JSONObject current=Cinematic3DDraftForms.actor(timeline,actor),material=current.optJSONObject("material");LinearLayout form=draftForm();
        form.addView(label("Procedural humanoid authoring; requires a rebuilt scene. First body edit explicitly configures both actors; the other actor starts with the athletic preset.",12));
        Spinner preset=select(form,"Body preset",Cinematic3DDraftForms.PRESETS,current.getString("preset"));
        EditText height=numeric(form,"Height multiplier (0.8–1.2)",String.valueOf(current.optDouble("heightScale",1)),true),build=numeric(form,"Body build multiplier (0.8–1.2)",String.valueOf(current.optDouble("buildScale",1)),true),head=numeric(form,"Head multiplier (0.8–1.2)",String.valueOf(current.optDouble("headScale",1)),true);
        CheckBox custom=check(form,"Author material color and surface",material!=null);JSONArray rgb=material==null?Cinematic3DDraftForms.vector("black".equals(actor)?.03:.9,"black".equals(actor)?.03:.9,"black".equals(actor)?.04:.92):material.getJSONArray("color");
        EditText red=numeric(form,"Linear red (0–1)",String.valueOf(rgb.getDouble(0)),true),green=numeric(form,"Linear green (0–1)",String.valueOf(rgb.getDouble(1)),true),blue=numeric(form,"Linear blue (0–1)",String.valueOf(rgb.getDouble(2)),true),metallic=numeric(form,"Metallic (0–1)",String.valueOf(material==null?0:material.getDouble("metallic")),true),roughness=numeric(form,"Roughness (0.1–1)",String.valueOf(material==null?.6:material.getDouble("roughness")),true);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(actor+" actor · rebuild draft").setView(scrollForm(form)).setPositiveButton("Apply draft",null).setNegativeButton("Cancel",null).create();
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            JSONObject value=new JSONObject().put("preset",preset.getSelectedItem()).put("heightScale",Double.parseDouble(height.getText().toString())).put("buildScale",Double.parseDouble(build.getText().toString())).put("headScale",Double.parseDouble(head.getText().toString()));
            if(custom.isChecked())value.put("material",new JSONObject().put("color",Cinematic3DDraftForms.vector(Double.parseDouble(red.getText().toString()),Double.parseDouble(green.getText().toString()),Double.parseDouble(blue.getText().toString()))).put("metallic",Double.parseDouble(metallic.getText().toString())).put("roughness",Double.parseDouble(roughness.getText().toString())));
            acceptSceneDraft(Cinematic3DDraftForms.editActor(timeline,actor,value));dialog.dismiss();
        }catch(Exception error){Toast.makeText(this,message(error),Toast.LENGTH_LONG).show();}});
    }catch(Exception error){setStatus(message(error));}}
    private void editMotionStyle(){if(!requireTimeline())return;try{
        int shotIndex=selectedShot;JSONObject shot=timeline.getJSONArray("shots").getJSONObject(shotIndex),style=shot.optJSONObject("motionStyle");LinearLayout form=draftForm();form.addView(label("Motion style changes the authored body clock in Blender. Native playback waits for the rebuilt scene.",12));
        Spinner preset=select(form,"Shot motion style",Cinematic3DDraftForms.STYLES,style==null?"grounded":style.getString("preset"));EditText intensity=numeric(form,"Style intensity (0–1)",String.valueOf(style==null?.5:style.optDouble("intensity",.5)),true);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(shot.getString("action")+" · motion draft").setView(scrollForm(form)).setPositiveButton("Apply draft",null).setNegativeButton("Cancel",null).setNeutralButton("Use unstyled motion",(d,w)->{try{acceptSceneDraft(Cinematic3DDraftForms.editStyle(timeline,shotIndex,"grounded",0,true));}catch(Exception error){setStatus(message(error));}}).create();
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{acceptSceneDraft(Cinematic3DDraftForms.editStyle(timeline,shotIndex,(String)preset.getSelectedItem(),Double.parseDouble(intensity.getText().toString()),false));dialog.dismiss();}catch(Exception error){Toast.makeText(this,message(error),Toast.LENGTH_LONG).show();}});
    }catch(Exception error){setStatus(message(error));}}
    private void editPoseKeys(){if(!requireTimeline())return;try{
        int shotIndex=selectedShot;JSONObject shot=timeline.getJSONArray("shots").getJSONObject(shotIndex);JSONArray existing=shot.optJSONArray("poseKeyframes");LinearLayout form=draftForm();
        form.addView(label("Sixteen local joint rotations, in the named rest-skeleton order. New keys copy the nearest earlier authored key; with no keys they start from an explicit rest pose. This is numeric authoring, with no recovered source pose or live joint preview.",12));
        String[] keyNames=new String[1+(existing==null?0:existing.length())];keyNames[0]="New key / typed phase";for(int i=1;i<keyNames.length;i++)keyNames[i]="Phase "+existing.getJSONObject(i-1).getDouble("phase");Spinner keyPicker=select(form,"Authored pose key",keyNames,keyNames[0]);
        EditText phase=numeric(form,"Shot phase (0–1)",existing==null?"0":"0.5",true);Spinner actor=select(form,"Actor",Cinematic3DDraftForms.ACTORS,"black"),joint=select(form,"Local joint",Cinematic3DTimeline.PRIMARY_JOINTS,"pelvis");
        EditText[] root=new EditText[3];String[] axis={"X right","Y up","Z depth"};for(int i=0;i<3;i++)root[i]=numeric(form,"World root "+axis[i]+" in metres (±20)","0",true);
        EditText[] quaternion=new EditText[4];String[] qNames={"w","x","y","z"};for(int i=0;i<4;i++)quaternion[i]=numeric(form,"Local quaternion "+qNames[i],i==0?"1":"0",true);
        form.addView(label("Optional Euler conversion: apply X, then Y, then Z. Convert updates wxyz fields; Apply joint saves them.",12));EditText[] euler=new EditText[3];for(int i=0;i<3;i++)euler[i]=numeric(form,"Euler "+new String[]{"X","Y","Z"}[i]+" degrees (±3600)","0",true);
        Button convert=button("Convert Euler XYZ to wxyz",()->{try{JSONArray q=Cinematic3DDraftForms.eulerXYZ(Double.parseDouble(euler[0].getText().toString()),Double.parseDouble(euler[1].getText().toString()),Double.parseDouble(euler[2].getText().toString()));for(int i=0;i<4;i++)quaternion[i].setText(String.valueOf(q.getDouble(i)));}catch(Exception error){Toast.makeText(this,message(error),Toast.LENGTH_LONG).show();}});form.addView(convert);
        Runnable load=()->{try{JSONObject key=Cinematic3DDraftForms.poseBase(timeline,shotIndex,Double.parseDouble(phase.getText().toString())),pose=key.getJSONObject("actors").getJSONObject((String)actor.getSelectedItem());JSONArray r=pose.getJSONArray("root"),q=pose.getJSONArray("rotations").getJSONArray(joint.getSelectedItemPosition());for(int i=0;i<3;i++)root[i].setText(String.valueOf(r.getDouble(i)));for(int i=0;i<4;i++)quaternion[i].setText(String.valueOf(q.getDouble(i)));}catch(Exception error){setStatus(message(error));}};
        selection(keyPicker,()->{try{if(keyPicker.getSelectedItemPosition()>0)phase.setText(String.valueOf(existing.getJSONObject(keyPicker.getSelectedItemPosition()-1).getDouble("phase")));load.run();}catch(Exception error){setStatus(message(error));}});selection(actor,load);selection(joint,load);
        Button loadPhase=button("Load typed phase / nearest key",load);form.addView(loadPhase);load.run();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(shot.getString("action")+" · local pose draft").setView(scrollForm(form)).setPositiveButton("Apply joint",null).setNegativeButton("Close",null).setNeutralButton("Remove typed key",(d,w)->{try{acceptSceneDraft(Cinematic3DDraftForms.removePose(timeline,shotIndex,Double.parseDouble(phase.getText().toString())));}catch(Exception error){setStatus(message(error));}}).create();
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{JSONArray q=Cinematic3DDraftForms.vector(Double.parseDouble(quaternion[0].getText().toString()),Double.parseDouble(quaternion[1].getText().toString()),Double.parseDouble(quaternion[2].getText().toString()),Double.parseDouble(quaternion[3].getText().toString())),r=Cinematic3DDraftForms.vector(Double.parseDouble(root[0].getText().toString()),Double.parseDouble(root[1].getText().toString()),Double.parseDouble(root[2].getText().toString()));
            acceptSceneDraft(Cinematic3DDraftForms.editPose(timeline,shotIndex,(String)actor.getSelectedItem(),joint.getSelectedItemPosition(),Double.parseDouble(phase.getText().toString()),q,r));Toast.makeText(this,"Joint saved to the scene draft. Select another joint to continue; rebuild before preview.",Toast.LENGTH_LONG).show();
        }catch(Exception error){Toast.makeText(this,message(error),Toast.LENGTH_LONG).show();}});
    }catch(Exception error){setStatus(message(error));}}
    private void editFootContacts(){if(!requireTimeline())return;try{
        int shotIndex=selectedShot;JSONObject shot=timeline.getJSONArray("shots").getJSONObject(shotIndex);JSONArray existing=shot.optJSONArray("footContacts");LinearLayout form=draftForm();
        form.addView(label("Plant a world ankle anchor during a half-open shot phase interval. Y is ankle height above the ground plane, not a hidden shoe offset. Blender solves and measures the contact after rebuild; this form does not claim measured grounding.",12));
        String[] names=new String[1+(existing==null?0:existing.length())];names[0]="New planted contact";for(int i=1;i<names.length;i++){JSONObject c=existing.getJSONObject(i-1);names[i]=c.getString("actor")+" "+c.getString("foot")+" · "+c.getDouble("startPhase")+"–"+c.getDouble("endPhase");}Spinner contact=select(form,"Contact interval",names,names[0]);
        Spinner actor=select(form,"Actor",Cinematic3DDraftForms.ACTORS,"black"),foot=select(form,"Ankle",new String[]{"leading","trailing"},"leading");EditText start=numeric(form,"Start phase (0–1 inclusive)","0",true),end=numeric(form,"End phase (0–1 exclusive)","1",true);EditText[] anchor=new EditText[3];for(int i=0;i<3;i++)anchor[i]=numeric(form,"World ankle "+new String[]{"X right","Y up","Z depth"}[i]+" in metres (±20)","0",true);
        selection(contact,()->{try{if(contact.getSelectedItemPosition()==0)return;JSONObject c=existing.getJSONObject(contact.getSelectedItemPosition()-1);actor.setSelection("black".equals(c.getString("actor"))?0:1);foot.setSelection("leading".equals(c.getString("foot"))?0:1);start.setText(String.valueOf(c.getDouble("startPhase")));end.setText(String.valueOf(c.getDouble("endPhase")));for(int i=0;i<3;i++)anchor[i].setText(String.valueOf(c.getJSONArray("anchor").getDouble(i)));}catch(Exception error){setStatus(message(error));}});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(shot.getString("action")+" · planted ankle draft").setView(scrollForm(form)).setPositiveButton("Apply contact",null).setNegativeButton("Cancel",null).setNeutralButton("Remove contact",(d,w)->{try{if(contact.getSelectedItemPosition()==0)throw new IllegalArgumentException("Choose an existing contact to remove");acceptSceneDraft(Cinematic3DDraftForms.editContact(timeline,shotIndex,contact.getSelectedItemPosition()-1,(String)actor.getSelectedItem(),(String)foot.getSelectedItem(),0,1,Cinematic3DDraftForms.vector(0,0,0),true));}catch(Exception error){setStatus(message(error));}}).create();
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{acceptSceneDraft(Cinematic3DDraftForms.editContact(timeline,shotIndex,contact.getSelectedItemPosition()-1,(String)actor.getSelectedItem(),(String)foot.getSelectedItem(),Double.parseDouble(start.getText().toString()),Double.parseDouble(end.getText().toString()),Cinematic3DDraftForms.vector(Double.parseDouble(anchor[0].getText().toString()),Double.parseDouble(anchor[1].getText().toString()),Double.parseDouble(anchor[2].getText().toString())),false));dialog.dismiss();}catch(Exception error){Toast.makeText(this,message(error),Toast.LENGTH_LONG).show();}});
    }catch(Exception error){setStatus(message(error));}}
    private JSONObject rebuildRequest() throws Exception {return projectStore.guardedProject(project.id,expectedSourceUpdatedAt,fresh->Cinematic3DDraftForms.rebuildTemplate(fresh,expectedSourceUpdatedAt,timeline));}
    private void buildUpdatedScene(){if(!requireTimeline())return;try{JSONObject request=rebuildRequest();String json=request.toString();Intent next=new Intent(this,OpenPipelineActivity.class).putExtra("projectId",project.id);
        if(json.length()<=200_000)next.putExtra("requestTemplate",json);else{JSONObject retained=retainRebuildTemplate(request);next.putExtra("requestTemplateFileName",retained.getString("fileName")).putExtra("requestTemplateSha256",retained.getString("sha256"));}
        startActivity(next);setStatus("Validated draft opened in Open Animation Workbench. Export its immutable request, run Blender, then import the new bundle or result project. Original-image reconstruction is not established.");}catch(Exception error){setStatus(message(error));}}
    private JSONObject retainRebuildTemplate(JSONObject request) throws Exception {
        byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>512*1024)throw new IOException("Rebuild template exceeds 512 KiB");
        File folder=new File(getFilesDir(),"open_pipeline_templates");if(!folder.getCanonicalPath().equals(getFilesDir().getCanonicalPath()+"/open_pipeline_templates"))throw new IOException("Unsafe rebuild template storage");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Rebuild template storage unavailable");long retained=0;int count=0;File[] files=folder.listFiles();if(files==null)throw new IOException("Cannot inspect rebuild templates");
        for(File file:files)if(file.getName().matches("[0-9a-f-]{36}\\.json")){if(java.nio.file.Files.isSymbolicLink(file.toPath())||!file.isFile())throw new IOException("Unsafe rebuild template entry");retained+=file.length();count++;}
        if(count>=64||retained+bytes.length>16L*1024*1024)throw new IOException("Rebuild template storage budget reached; Export rebuild draft retains this request separately");
        String name=UUID.randomUUID()+".json";File target=new File(folder,name),temporary=new File(folder,name+".part");if(!temporary.createNewFile())throw new IOException("Cannot stage rebuild template");
        try{try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}if(target.exists()||!temporary.renameTo(target))throw new IOException("Cannot publish rebuild template");}finally{temporary.delete();}
        return new JSONObject().put("fileName",name).put("sha256",Cinematic3DTimeline.hash(bytes));
    }
    private void exportRebuildDraft(){if(!requireTimeline())return;try{exportedRequest=rebuildRequest().toString(2);startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"cinematic3d-rebuild-template.json"),WRITE_REQUEST);}catch(Exception error){setStatus(message(error));}}

    private void chooseOutputFraming(){
        final String[] names=CinematicOutputFraming.LABELS;
        new AlertDialog.Builder(this).setTitle("Frame shape for 3D preview and MP4")
                .setSingleChoiceItems(names,
                        java.util.Arrays.asList(CinematicOutputFraming.MODES)
                                .indexOf(outputFraming),
                        (dialog,index)->{
                            if(index<0||index>=CinematicOutputFraming.MODES.length)return;
                            outputFraming=CinematicOutputFraming.MODES[index];
                            getPreferences(MODE_PRIVATE).edit().putString(
                                    "cinematicOutput."+project.id,outputFraming).apply();
                            refresh();
                            setStatus("3D preview and export: "+CinematicOutputFraming.label(outputFraming));
                            dialog.dismiss();
                        })
                .setNegativeButton("Cancel",null).show();
    }
    private Intent framedPreview(Intent intent){
        int[] wh=CinematicOutputFraming.dimensions(outputFraming);
        return intent.putExtra("cinematicOutputWidth",wh[0])
                .putExtra("cinematicOutputHeight",wh[1]);
    }
    private void openPreview(){if(requireSaved())startActivity(
            framedPreview(previewIntent(this,project.id,programId,expectedProgramSha256)));}

    private void previewSelectedShot(){if(!requireSaved())return;try{JSONObject compiled=Cinematic3DTimeline.compile(timeline);int frame=compiled.getJSONArray("shots").getJSONObject(selectedShot).getInt("startFrame");startActivity(framedPreview(previewIntent(
                this,project.id,programId,expectedProgramSha256,frame)));}catch(Exception error){setStatus(message(error));}}
    private void showShotTiming(){if(!requireTimeline())return;try{
        JSONObject compiled=Cinematic3DTimeline.compile(timeline);JSONArray entries=compiled.getJSONArray("shots");int fps=compiled.getInt("fps");StringBuilder text=new StringBuilder();
        for(int index=0;index<entries.length();index++){JSONObject shot=entries.getJSONObject(index);text.append(index==selectedShot?"▶ ":"").append(index+1).append(". ").append(shot.getString("action")).append(String.format(java.util.Locale.US,"\n%.2f–%.2f s • frames %d–%d • hold %d\n\n",shot.getInt("startFrame")/(double)fps,shot.getInt("endFrame")/(double)fps,shot.getInt("startFrame")+1,shot.getInt("endFrame"),shot.getInt("hitStopFrames")));}
        new AlertDialog.Builder(this).setTitle(String.format(java.util.Locale.US,"%d shots • %.2f seconds",entries.length(),compiled.getInt("totalFrames")/(double)fps)).setMessage(text.toString()).setPositiveButton("Close",null).show();
    }catch(Exception error){setStatus(message(error));}}
    private void renderNative(){if(!requireSaved())return;try{
        int[] size=CinematicOutputFraming.dimensions(outputFraming);
        JSONObject request=getRequest(programId,expectedProgramSha256)
                .put("width",size[0]).put("height",size[1])
                .put("render",true)
                .put("fileName","VideoStudio_Cinematic3D_"+System.currentTimeMillis()+".mp4");Intent intent=new Intent(this,ControlService.class).setAction(ControlService.ACTION_LOCAL_CINEMATIC_3D).putExtra("requestJson",request.toString());startForegroundService(intent);setStatus("Native 3D MP4 export queued. Progress and cancellation are available in VideoStudio jobs.");}catch(Exception error){setStatus("Cannot queue native export: "+message(error));}}
    private void exportAuthoringRequest(){if(!requireSaved())return;try{JSONObject request=getRequest(programId,expectedProgramSha256);perform("Preparing the Blender authoring request…",()->Cinematic3DProgramStore.exportRequest(getApplicationContext(),projectStore,request),receipt->{exportedRequest=receipt.getJSONObject("exportRequest").toString(2);Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"cinematic3d-authoring-request.json");startActivityForResult(create,WRITE_REQUEST);});}catch(Exception error){setStatus(message(error));}}
    private void pickBundle(){Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/octet-stream"});startActivityForResult(pick,PICK_BUNDLE);}
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null||data.getData()==null||busy)return;Uri uri=data.getData();
        if(code==PICK_BUNDLE){String name=displayName(uri);final long revision=expectedSourceUpdatedAt;perform("Copying and verifying the selected private bundle…",()->{ProjectStore.Asset asset=registerSelectedBundle(uri,name,revision);ProjectStore.Project registered=projectStore.get(project.id);return NativeCinematicBundleStore.install(getApplicationContext(),projectStore,new JSONObject().put("projectId",project.id).put("sourceProjectUpdatedAt",registered.updatedAt).put("bundleAssetId",asset.id));},this::acceptBundle);}
        else if(code==WRITE_REQUEST&&exportedRequest!=null){String content=exportedRequest;perform("Writing the authoring request…",()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IllegalArgumentException("Selected document is not writable");activeOutput=out;if(Thread.currentThread().isInterrupted())throw new InterruptedException("Request export cancelled");out.write(content.getBytes(StandardCharsets.UTF_8));out.flush();}finally{activeOutput=null;}return new JSONObject().put("ok",true);},receipt->setStatus("Authoring request exported. Rebuild custom pose edits through the Blender pipeline, then import the resulting verified bundle."));}
    }
    private String displayName(Uri uri){String name="cinematic3d-bundle.zip";try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst()&&cursor.getString(0)!=null)name=cursor.getString(0);}catch(Exception ignored){}name=name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();if(name.isEmpty())name="cinematic3d-bundle.zip";return name.substring(0,Math.min(256,name.length()));}
    private ProjectStore.Asset registerSelectedBundle(Uri uri,String name,long revision) throws Exception {
        File folder=new File(getFilesDir(),"creative_workspace/projects/"+project.id+"/cinematic3d_imports");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Cannot prepare private bundle storage");
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();File file=new File(folder,asset.id+".zip");long bytes=0;boolean registered=false;
        try{
            try(InputStream input=getContentResolver().openInputStream(uri);FileOutputStream output=new FileOutputStream(file)){if(input==null)throw new IllegalArgumentException("Selected bundle cannot be read");activeInput=input;activeOutput=output;byte[] buffer=new byte[64*1024];int length;while((length=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted()||destroyed)throw new InterruptedException("Bundle import cancelled");bytes+=length;if(bytes>MAX_ARCHIVE_BYTES)throw new IllegalArgumentException("Bundle archives are limited to 64 MiB");output.write(buffer,0,length);}output.getFD().sync();}finally{activeInput=null;activeOutput=null;}
            if(bytes<22)throw new IllegalArgumentException("Selected bundle archive is empty");asset.name=name;asset.mime="application/zip";asset.uri=Uri.fromFile(file).toString();asset.sizeBytes=bytes;asset.seekable=true;asset.persistedReadAccess=true;asset.role="cinematic3d_bundle";
            projectStore.guardedProject(project.id,revision,fresh->{fresh.assets.add(asset);projectStore.save(fresh);return null;});registered=true;return asset;
        }finally{if(!registered)file.delete();}
    }

    private void smallState(Bundle state){state.putString("bundleId",bundleId);state.putString("programId",programId);state.putString("loadingProgramId",loadingProgramId);state.putString("programSha",expectedProgramSha256);state.putLong("sourceRevision",expectedSourceUpdatedAt);state.putInt("selectedShot",selectedShot);state.putBoolean("dirty",dirty);state.putBoolean("bindingReady",bindingReady);state.putBoolean("savePending",savePending);state.putBoolean("sceneRebuildRequired",sceneRebuildRequired);}
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);for(String key:new String[]{"draft","acceptedTimeline","pendingSaveRequest","editorBundle","exportedRequest"})state.remove(key);smallState(state);
        if(project==null)return;
        try{
            String id=UUID.randomUUID().toString();JSONObject snapshot=new JSONObject().put("schema","videostudio.cinematic3d.studio-state.v1").put("stateId",id).put("projectId",project.id)
                    .put("bundleId",bundleId).put("programId",programId).put("loadingProgramId",loadingProgramId).put("programSha",expectedProgramSha256).put("sourceRevision",expectedSourceUpdatedAt).put("selectedShot",selectedShot).put("dirty",dirty).put("bindingReady",bindingReady).put("savePending",savePending).put("sceneRebuildRequired",sceneRebuildRequired);
            if(timeline!=null)snapshot.put("draft",timeline);if(acceptedTimeline!=null)snapshot.put("acceptedTimeline",acceptedTimeline);if(editorBundle!=null)snapshot.put("editorBundle",editorBundle);if(pendingSaveRequest!=null)snapshot.put("pendingSaveRequest",pendingSaveRequest);if(exportedRequest!=null)snapshot.put("exportedRequest",exportedRequest);
            byte[] bytes=snapshot.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_STATE_BYTES)throw new IOException("Private draft snapshot exceeds 4 MiB");
            File folder=stateFolder(true);checkStateBudget(folder,bytes.length,privateStateId);File target=stateFile(folder,id),temporary=new File(folder,id+".tmp");
            if(!temporary.createNewFile())throw new IOException("Cannot stage private draft state");
            try{try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}if(target.exists()||!temporary.renameTo(target))throw new IOException("Cannot atomically retain private draft state");}finally{temporary.delete();}
            String previous=privateStateId;privateStateId=id;privateStateSha256=Cinematic3DTimeline.hash(bytes);state.putString("draftStateId",privateStateId);state.putString("draftStateSha256",privateStateSha256);
            if(!previous.isEmpty())deleteStateFile(previous);
        }catch(Exception error){
            if(!privateStateId.isEmpty()){state.putString("draftStateId",privateStateId);state.putString("draftStateSha256",privateStateSha256);}
            String warning="Draft state could not be updated: "+message(error)+". Saved programs remain available; keep this screen open to retain current edits.";state.putString("draftStateSaveError",warning);setStatus(warning);Toast.makeText(this,warning,Toast.LENGTH_LONG).show();
        }
    }
    private File stateFolder(boolean create) throws Exception {File folder=new File(getFilesDir(),"cinematic3d_studio_state");if(java.nio.file.Files.isSymbolicLink(folder.toPath()))throw new IOException("Unsafe private draft directory");if(create&&!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot prepare private draft storage");if(!folder.isDirectory())throw new IOException("Private draft state is unavailable");return folder;}
    private static File stateFile(File folder,String id) throws Exception {if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IOException("Unsafe private draft identity");File file=new File(folder,id+".json");if(java.nio.file.Files.isSymbolicLink(file.toPath())||!file.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile()))throw new IOException("Unsafe private draft path");return file;}
    private static void checkStateBudget(File folder,int incoming,String previous) throws Exception {long bytes=0;int count=0;File[] files=folder.listFiles();if(files==null)throw new IOException("Cannot inspect private draft storage");for(File file:files){if(!file.getName().matches("[0-9a-f-]{36}\\.(json|tmp)"))continue;if(java.nio.file.Files.isSymbolicLink(file.toPath())||!file.isFile())throw new IOException("Unsafe private draft storage entry");if(file.getName().endsWith(".tmp")&&System.currentTimeMillis()-file.lastModified()>60_000){if(!file.delete())throw new IOException("Cannot remove abandoned private draft staging");continue;}if(file.getName().equals(previous+".json"))continue;bytes=Math.addExact(bytes,file.length());count++;}if(count>=MAX_STATE_FILES||bytes+incoming>MAX_STATE_STORAGE)throw new IOException("Private draft storage budget reached (64 MiB/64 snapshots)");}
    private Bundle readPrivateState(Bundle state) throws Exception {
        if(!state.containsKey("draftStateId"))return state;
        String id=state.getString("draftStateId"),expected=state.getString("draftStateSha256");if(expected==null||!expected.matches("[0-9a-f]{64}"))throw new IOException("Private draft state proof is missing");File file=stateFile(stateFolder(false),id);if(!file.isFile()||file.length()>MAX_STATE_BYTES)throw new IOException("Private draft state is missing or oversized");byte[] bytes;
        try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream((int)file.length())){byte[] part=new byte[64*1024];int length;while((length=input.read(part))!=-1){if(output.size()+length>MAX_STATE_BYTES)throw new IOException("Private draft state exceeds 4 MiB");output.write(part,0,length);}bytes=output.toByteArray();}
        if(!expected.equals(Cinematic3DTimeline.hash(bytes)))throw new IOException("Private draft snapshot changed");JSONObject snapshot=new JSONObject(new String(bytes,StandardCharsets.UTF_8));if(!"videostudio.cinematic3d.studio-state.v1".equals(snapshot.getString("schema"))||!project.id.equals(snapshot.getString("projectId"))||!id.equals(snapshot.getString("stateId")))throw new IOException("Private draft snapshot identity changed");
        Bundle restored=new Bundle(state);for(String key:new String[]{"bundleId","programId","loadingProgramId","programSha"})restored.putString(key,snapshot.getString(key));restored.putLong("sourceRevision",snapshot.getLong("sourceRevision"));restored.putInt("selectedShot",snapshot.getInt("selectedShot"));for(String key:new String[]{"dirty","bindingReady","savePending"})restored.putBoolean(key,snapshot.getBoolean(key));restored.putBoolean("sceneRebuildRequired",snapshot.optBoolean("sceneRebuildRequired",false));for(String key:new String[]{"draft","acceptedTimeline","pendingSaveRequest","editorBundle"})if(snapshot.has(key))restored.putString(key,snapshot.getJSONObject(key).toString());if(snapshot.has("exportedRequest"))restored.putString("exportedRequest",snapshot.getString("exportedRequest"));privateStateId=id;privateStateSha256=expected;return restored;
    }
    private void deleteStateFile(String id) throws Exception {File file=stateFile(stateFolder(false),id);if(file.exists()&&!file.delete())throw new IOException("Cannot remove superseded private draft snapshot");}
    private void cancelCameraEdit(){
        if(!cameraEditPending)return;cameraEditGeneration++;operationGeneration++;cameraEditPending=false;busy=false;
        if(pendingCameraDialog!=null)pendingCameraDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);pendingCameraDialog=null;
        setStatus("Camera checking cancelled. The current draft is unchanged.");refresh();
    }
    @Override protected void onResume(){super.onResume();cameraUiActive=true;}
    @Override protected void onPause(){cameraUiActive=false;cancelCameraEdit();super.onPause();}
    @Override protected void onDestroy(){cameraUiActive=false;AlertDialog cameraDialog=pendingCameraDialog;cancelCameraEdit();if(cameraDialog!=null&&cameraDialog.isShowing())cameraDialog.dismiss();destroyed=true;operationGeneration++;ownerWorker.shutdownNow();if(android.os.Build.VERSION.SDK_INT>=33&&systemBackCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBackCallback);try{if(activeInput!=null)activeInput.close();}catch(Exception ignored){}try{if(activeOutput!=null)activeOutput.close();}catch(Exception ignored){}main.removeCallbacksAndMessages(null);if(isFinishing()&&!privateStateId.isEmpty())try{deleteStateFile(privateStateId);}catch(Exception error){ActivityLog.add(this,"user","Private draft cleanup failed",message(error),"failed",null,null,project==null?"":project.id);}super.onDestroy();}

    private static JSONObject copy(JSONObject object) throws Exception{return new JSONObject(object.toString());}
    private static JSONObject editorProjection(JSONObject bundle) throws Exception {JSONObject manifest=bundle.getJSONObject("manifest");return new JSONObject().put("manifest",new JSONObject().put("generator",copy(manifest.getJSONObject("generator"))).put("scenes",new JSONArray(manifest.getJSONArray("scenes").toString()))).put("timeline",Cinematic3DTimeline.validate(bundle.getJSONObject("timeline")));}
    private static void requireLibrary(JSONObject library) throws Exception {if(library==null||!library.getJSONObject("manifest").getJSONObject("generator").optBoolean("actionLibrary",false))throw new IllegalArgumentException("BAKED_POSE_REBUILD_REQUIRED: custom scene composition requires a rebuilt bundle");}
    private static JSONObject libraryTemplate(JSONObject library,String action) throws Exception {requireLibrary(library);JSONArray scenes=library.getJSONObject("manifest").getJSONArray("scenes");JSONObject scene=null;for(int i=0;i<scenes.length();i++){JSONObject candidate=scenes.getJSONObject(i);if(action.equals(candidate.getString("action"))){if(scene!=null)throw new IllegalArgumentException("An ambiguous library action requires a rebuilt exact binding");scene=candidate;}}if(scene==null)throw new IllegalArgumentException("BAKED_POSE_REBUILD_REQUIRED: action is not declared by this library");JSONArray originals=Cinematic3DTimeline.validate(library.getJSONObject("timeline")).getJSONArray("shots");for(int i=0;i<originals.length();i++){JSONObject shot=originals.getJSONObject(i);if(scene.getString("shotId").equals(shot.getString("id"))&&action.equals(shot.getString("action")))return copy(shot);}throw new IllegalArgumentException("The declared library action has no baked template");}
    private static boolean isImpact(String action){return "CLASH".equals(action)||"BLADE_LOCK".equals(action)||"MACRO_CLASH".equals(action);}
    static JSONObject addLibraryShot(JSONObject original,JSONObject library,String action,String id) throws Exception {JSONObject shot=libraryTemplate(library,action).put("id",id),result=copy(original);result.getJSONArray("shots").put(shot);return Cinematic3DTimeline.validate(result);}
    static JSONObject duplicateLibraryShot(JSONObject original,JSONObject library,int index,String id) throws Exception {requireLibrary(library);JSONObject result=copy(original);JSONArray shots=result.getJSONArray("shots");if(index<0||index>=shots.length())throw new IllegalArgumentException("Select an existing shot to duplicate");JSONObject shot=copy(shots.getJSONObject(index));libraryTemplate(library,shot.getString("action"));shot.put("id",id);shots.put(shot);return Cinematic3DTimeline.validate(result);}
    static JSONObject deleteLibraryShot(JSONObject original,JSONObject library,int index) throws Exception {requireLibrary(library);JSONObject result=copy(original);JSONArray shots=result.getJSONArray("shots");if(index<0||index>=shots.length()||shots.length()<2)throw new IllegalArgumentException("A cinematic timeline must keep at least one shot");JSONArray remaining=new JSONArray();for(int i=0;i<shots.length();i++)if(i!=index)remaining.put(shots.getJSONObject(i));result.put("shots",remaining);return Cinematic3DTimeline.validate(result);}
    static JSONObject changeLibraryAction(JSONObject original,JSONObject library,int index,String action) throws Exception {JSONObject template=libraryTemplate(library,action),result=copy(original);JSONArray shots=result.getJSONArray("shots");if(index<0||index>=shots.length())throw new IllegalArgumentException("Select an existing shot to change");JSONObject shot=shots.getJSONObject(index).put("action",action);if(!isImpact(action))shot.put("hitStopFrames",0);for(String key:new String[]{"sourceAnchors","poseKeyframes","footContacts"}){shot.remove(key);if(template.has(key))shot.put(key,template.get(key));}return Cinematic3DTimeline.validate(result);}
    static JSONObject moveShot(JSONObject original,int from,int to) throws Exception {JSONObject result=copy(original);JSONArray entries=result.getJSONArray("shots");if(from<0||from>=entries.length()||to<0||to>=entries.length())throw new IllegalArgumentException("Shot is already at the timeline boundary");JSONObject selected=entries.getJSONObject(from);JSONArray ordered=new JSONArray();for(int i=0;i<entries.length();i++){int source=i;if(i==to){ordered.put(selected);continue;}if(from<to&&i>=from&&i<to)source=i+1;else if(from>to&&i>to&&i<=from)source=i-1;ordered.put(entries.getJSONObject(source));}result.put("shots",ordered);return Cinematic3DTimeline.validate(result);}
    static JSONObject editShot(JSONObject original,int index,int frames,int hold,String mode,double fov,double shake,boolean spark,boolean trail,boolean rubble) throws Exception {JSONObject result=copy(original);JSONObject shot=result.getJSONArray("shots").getJSONObject(index);shot.put("durationFrames",frames).put("hitStopFrames",hold);shot.getJSONObject("camera").put("mode",mode).put("fovDegrees",fov).put("shakeIntensity",shake);shot.put("vfx",new JSONObject().put("sparkBurst",spark).put("slashTrail",trail).put("rubble",rubble));return Cinematic3DTimeline.validate(result);}
    static JSONObject chooseCameraTrack(JSONObject original,int index,boolean keepAuthoredTrack) throws Exception {JSONObject result=copy(original);JSONObject camera=result.getJSONArray("shots").getJSONObject(index).getJSONObject("camera");if(!keepAuthoredTrack){camera.remove("keyframes");camera.remove("orbit");}return Cinematic3DTimeline.validate(result);}
    static JSONObject saveRequest(String projectId,long revision,String bundleId,String programId,String sha,JSONObject timeline) throws Exception {JSONObject request=new JSONObject().put("projectId",projectId).put("sourceProjectUpdatedAt",revision).put("bundleId",bundleId).put("timeline",Cinematic3DTimeline.validate(timeline));if(programId!=null&&!programId.isEmpty())request.put("programId",programId);if(sha!=null&&!sha.isEmpty())request.put("expectedProgramSha256",sha);return request;}
    static JSONObject restoreTimeline(Bundle state,long revision) throws Exception {String value=state.getLong("sourceRevision",-1)==revision?state.getString("draft"):state.getString("acceptedTimeline");return value==null?null:Cinematic3DTimeline.validate(new JSONObject(value));}
    static Intent previewIntent(Context context,String projectId,String programId,String sha){return previewIntent(context,projectId,programId,sha,0);}
    static Intent previewIntent(Context context,String projectId,String programId,String sha,int startFrame){if(startFrame<0)throw new IllegalArgumentException("Preview start frame must be nonnegative");return new Intent(context,FilamentGlbActivity.class).putExtra("projectId",projectId).putExtra("cinematicProgramId",programId).putExtra("expectedProgramSha256",sha).putExtra("previewStartFrame",startFrame);}
}
