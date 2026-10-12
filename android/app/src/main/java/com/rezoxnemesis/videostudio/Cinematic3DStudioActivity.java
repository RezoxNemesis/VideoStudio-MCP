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
    private long expectedSourceUpdatedAt;
    private int selectedShot,operationGeneration;
    private boolean busy,dirty,savePending,pickerUpdating,bindingReady;
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
        expectedSourceUpdatedAt=project.updatedAt;buildUi();
        if(android.os.Build.VERSION.SDK_INT>=33){systemBackCallback=this::requestBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,systemBackCallback);}
        if(state!=null){
            try{
                state=readPrivateState(state);
                bundleId=state.getString("bundleId","");programId=state.getString("programId","");expectedProgramSha256=state.getString("programSha","");
                expectedSourceUpdatedAt=state.getLong("sourceRevision",project.updatedAt);selectedShot=state.getInt("selectedShot",0);
                acceptedTimeline=state.containsKey("acceptedTimeline")?new JSONObject(state.getString("acceptedTimeline")):null;
                editorBundle=state.containsKey("editorBundle")?new JSONObject(state.getString("editorBundle")):null;
                timeline=restoreTimeline(state,project.updatedAt);dirty=state.getBoolean("dirty")&&expectedSourceUpdatedAt==project.updatedAt;
                if(state.containsKey("pendingSaveRequest"))pendingSaveRequest=new JSONObject(state.getString("pendingSaveRequest"));
                savePending=state.getBoolean("savePending")&&pendingSaveRequest!=null;
                bindingReady=state.getBoolean("bindingReady")&&expectedSourceUpdatedAt==project.updatedAt;
                exportedRequest=state.getString("exportedRequest");
                if(expectedSourceUpdatedAt!=project.updatedAt){expectedSourceUpdatedAt=project.updatedAt;bindingReady=false;setStatus("Source project changed. Accepted shot controls restored; save explicitly to rebind before preview or export.");}
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
        root.addView(label("Baked 3D scenes with two skinned actors, wrist-attached swords and authored effects. Procedural mannequins are an additional workflow; original-image reconstruction is not established.",12));
        row(root,button("Bundled scenes",()->discardGuard(this::installBuiltin)),button("Import bundle",()->discardGuard(this::pickBundle)),button("Saved timelines",()->discardGuard(this::chooseProgram)));
        binding=label("",12);binding.setTextColor(ACCENT);root.addView(binding);
        row(root,button("Timeline name",this::renameTimeline),button("Frame rate",this::changeFps));
        shots=new Spinner(this);shots.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int index,long id){if(!pickerUpdating)selectedShot=index;}
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });root.addView(shots,new LinearLayout.LayoutParams(-1,dp(52)));
        row(root,button("Move earlier",()->moveSelected(-1)),button("Move later",()->moveSelected(1)),button("Edit shot",this::editSelected));
        addShotButton=button("Add shot",this::addShot);duplicateButton=button("Duplicate",this::duplicateShot);deleteShotButton=button("Delete shot",this::deleteShot);row(root,addShotButton,duplicateButton,deleteShotButton);
        root.addView(label("Edit shot order, duration, impact hold, camera and effects. Custom pose, root and foot-contact changes need a rebuilt Blender bundle.",12));
        row(root,button("Save timeline",this::saveTimeline),button("Restore saved",this::restoreAccepted));
        row(root,button("Live 3D preview",this::openPreview),button("Native MP4",this::renderNative));
        row(root,button("Export authoring request",this::exportAuthoringRequest),button("Return",()->discardGuard(this::finish)));
        status=label("",12);status.setTextColor(ACCENT);root.addView(status);setContentView(scroll);refresh();
    }

    private void refresh(){
        if(binding==null)return;
        binding.setText(project.name+" • "+(timeline==null?"no scene bundle":timeline.optString("name")+" • "+("procedural_mannequin".equals(timeline.optString("motionProvider"))?"procedural mannequins":"authored 3D"))+"\n"+(expectedProgramSha256.isEmpty()?"Timeline not saved":("Saved generation "+expectedProgramSha256.substring(0,Math.min(12,expectedProgramSha256.length()))))+(dirty?" • unsaved edits":"")+(busy?" • working":""));
        for(Button control:controls)control.setEnabled(!busy);shots.setEnabled(!busy&&timeline!=null);
        int count=timeline==null?0:timeline.optJSONArray("shots").length();boolean library=isActionLibrary();addShotButton.setEnabled(!busy&&library&&count<80);duplicateButton.setEnabled(!busy&&library&&count>0&&count<80);deleteShotButton.setEnabled(!busy&&library&&count>1);
        java.util.List<String> labels=new java.util.ArrayList<>();if(timeline!=null){JSONArray entries=timeline.optJSONArray("shots");if(entries!=null)for(int i=0;i<entries.length();i++){JSONObject shot=entries.optJSONObject(i);if(shot!=null)labels.add((i+1)+". "+shot.optString("action")+" • "+shot.optInt("durationFrames")+" frames • "+shot.optString("id"));}}
        pickerUpdating=true;shots.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));selectedShot=Math.max(0,Math.min(selectedShot,labels.size()-1));if(!labels.isEmpty())shots.setSelection(selectedShot);pickerUpdating=false;
    }
    private void setStatus(String value){if(status!=null)status.setText(value);}
    private static String message(Throwable error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    private boolean requireTimeline(){if(timeline==null){setStatus("Install or import a scene bundle first.");return false;}return true;}
    private boolean requireSaved(){if(!requireTimeline())return false;if(dirty||!bindingReady||expectedProgramSha256.isEmpty()){setStatus("Save this timeline before preview, rendering or authoring export.");return false;}ProjectStore.Project fresh=projectStore.get(project.id);if(fresh==null||fresh.updatedAt!=expectedSourceUpdatedAt){bindingReady=false;setStatus("Source project changed. Restore or save explicitly before preview or export.");return false;}return true;}
    private void discardGuard(Runnable work){if(!dirty){work.run();return;}new AlertDialog.Builder(this).setTitle("Leave the unsaved timeline?").setMessage("Save first to keep the current shot edits.").setPositiveButton("Leave draft",(d,w)->work.run()).setNegativeButton("Keep editing",null).show();}
    private void requestBack(){if(busy){setStatus("Wait for the current scene operation before leaving.");return;}discardGuard(this::finish);}
    @Override public void onBackPressed(){requestBack();}

    @FunctionalInterface private interface Work {JSONObject run() throws Exception;}
    @FunctionalInterface private interface Accept {void run(JSONObject receipt) throws Exception;}
    private void perform(String progress,Work work,Accept accept){
        if(busy||destroyed)return;busy=true;int generation=++operationGeneration;setStatus(progress);refresh();
        ownerWorker.execute(()->{JSONObject value=null;Throwable failure=null;try{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Operation cancelled");value=work.run();}catch(Exception|OutOfMemoryError error){failure=error;}
            final JSONObject receipt=value;final Throwable problem=failure;main.post(()->{if(destroyed||generation!=operationGeneration)return;busy=false;
                try{if(problem!=null){if(message(problem).contains("SOURCE_")&&message(problem).contains("_CHANGED"))bindingReady=false;if(message(problem).contains("SOURCE_PROJECT_CHANGED")&&savePending&&acceptedTimeline!=null){timeline=copy(acceptedTimeline);dirty=false;}setStatus("Operation failed: "+message(problem));}else accept.run(receipt);}catch(Exception error){setStatus("Operation failed: "+message(error));}finally{savePending=false;pendingSaveRequest=null;loadingProgramId="";refresh();}
            });
        });
    }
    private JSONObject sourceRequest() throws Exception{return new JSONObject().put("projectId",project.id).put("sourceProjectUpdatedAt",expectedSourceUpdatedAt);}
    private void installBuiltin(){try{JSONObject request=sourceRequest();perform("Installing bundled native 3D scenes…",()->NativeCinematicBundleStore.installBuiltin(getApplicationContext(),projectStore,request),this::acceptBundle);}catch(Exception error){setStatus(message(error));}}
    private void acceptBundle(JSONObject receipt) throws Exception {
        JSONObject next=Cinematic3DTimeline.validate(receipt.getJSONObject("timeline"));bundleId=receipt.getString("bundleId");editorBundle=editorProjection(receipt);timeline=next;acceptedTimeline=copy(next);programId=UUID.randomUUID().toString();expectedProgramSha256="";selectedShot=0;dirty=true;bindingReady=false;
        ProjectStore.Project fresh=projectStore.get(project.id);if(fresh!=null){project=fresh;expectedSourceUpdatedAt=fresh.updatedAt;}
        setStatus("Scene bundle installed. Review and save the timeline. Baked poses remain authored scene data.");
    }
    private JSONObject getRequest(String id,String sha) throws Exception {JSONObject request=new JSONObject().put("projectId",project.id).put("programId",id);if(sha!=null&&!sha.isEmpty())request.put("expectedProgramSha256",sha);return request;}
    private void acceptProgram(JSONObject receipt) throws Exception {
        timeline=Cinematic3DTimeline.validate(receipt.getJSONObject("timeline"));acceptedTimeline=copy(timeline);editorBundle=receipt.has("editorBundle")?copy(receipt.getJSONObject("editorBundle")):null;bundleId=receipt.getString("bundleId");programId=receipt.getString("programId");expectedProgramSha256=receipt.getString("sha256");boolean editOnly=receipt.optBoolean("editOnly",false);expectedSourceUpdatedAt=editOnly?receipt.getLong("currentSourceProjectUpdatedAt"):receipt.getLong("sourceProjectUpdatedAt");dirty=false;bindingReady=!editOnly;
        ProjectStore.Project fresh=projectStore.get(project.id);if(fresh!=null)project=fresh;setStatus(editOnly?"Saved timeline is open for editing. Save explicitly to verify and rebind this source before preview or export.":"Saved timeline loaded. Native execution and cinematic acceptance require device verification.");
    }
    private JSONObject readProgramForStudio(JSONObject request) throws Exception {JSONObject editable=Cinematic3DProgramStore.readForEdit(getApplicationContext(),projectStore,request);if(!editable.getBoolean("sourceRevisionChanged")){try{return Cinematic3DProgramStore.get(getApplicationContext(),projectStore,request);}catch(IllegalArgumentException|IllegalStateException unavailable){return editable;}}return editable;}
    private void loadProgram(String id){try{JSONObject request=getRequest(id,"");loadingProgramId=id;perform("Loading saved timeline…",()->readProgramForStudio(request),this::acceptProgram);}catch(Exception error){loadingProgramId="";setStatus(message(error));}}
    private void chooseProgram(){try{JSONObject request=new JSONObject().put("projectId",project.id);perform("Reading saved timelines…",()->Cinematic3DProgramStore.list(getApplicationContext(),projectStore,request),receipt->{JSONArray list=receipt.getJSONArray("programs");if(list.length()==0){setStatus("No cinematic timelines saved in this project.");return;}String[] names=new String[list.length()];for(int i=0;i<list.length();i++){JSONObject entry=list.getJSONObject(i);names[i]=entry.optString("name",entry.optJSONObject("timeline")==null?entry.optString("programId"):entry.getJSONObject("timeline").optString("name"))+" • "+entry.optString("programId");}new AlertDialog.Builder(this).setTitle("Saved cinematic timelines").setItems(names,(d,index)->{try{loadProgram(list.getJSONObject(index).getString("programId"));}catch(Exception error){setStatus(message(error));}}).setNegativeButton("Cancel",null).show();});}catch(Exception error){setStatus(message(error));}}
    private void saveTimeline(){if(!requireTimeline())return;try{
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
    private void restoreAccepted(){if(acceptedTimeline==null){setStatus("No accepted timeline is available to restore.");return;}try{timeline=copy(acceptedTimeline);dirty=false;bindingReady=false;ProjectStore.Project fresh=projectStore.get(project.id);if(fresh!=null){project=fresh;expectedSourceUpdatedAt=fresh.updatedAt;}refresh();if(expectedProgramSha256.isEmpty())setStatus("Installed timeline restored. Save to create its first generation.");else{JSONObject request=getRequest(programId,expectedProgramSha256);perform("Checking saved source bindings…",()->Cinematic3DProgramStore.get(getApplicationContext(),projectStore,request),this::acceptProgram);}}catch(Exception error){setStatus(message(error));}}

    private void renameTimeline(){if(!requireTimeline())return;EditText input=new EditText(this);input.setText(timeline.optString("name"));new AlertDialog.Builder(this).setTitle("Timeline name").setView(input).setPositiveButton("Apply",(d,w)->{try{JSONObject edited=copy(timeline);edited.put("name",input.getText().toString().trim());timeline=Cinematic3DTimeline.validate(edited);dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}).setNegativeButton("Cancel",null).show();}
    private void changeFps(){if(!requireTimeline())return;String[] values={"24 FPS","30 FPS","60 FPS"};new AlertDialog.Builder(this).setTitle("Timeline frame rate").setItems(values,(d,index)->{try{JSONObject edited=copy(timeline);edited.put("fps",new int[]{24,30,60}[index]);timeline=Cinematic3DTimeline.validate(edited);dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}).setNegativeButton("Cancel",null).show();}
    private void moveSelected(int direction){if(!requireTimeline())return;int next=selectedShot+direction;try{timeline=moveShot(timeline,selectedShot,next);selectedShot=next;dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}
    private boolean isActionLibrary(){try{return editorBundle!=null&&editorBundle.getJSONObject("manifest").getJSONObject("generator").optBoolean("actionLibrary",false);}catch(Exception ignored){return false;}}
    private java.util.List<String> libraryActions() throws Exception {requireLibrary(editorBundle);java.util.List<String> actions=new java.util.ArrayList<>();JSONArray scenes=editorBundle.getJSONObject("manifest").getJSONArray("scenes");for(int i=0;i<scenes.length();i++){String action=scenes.getJSONObject(i).getString("action");if(!actions.contains(action)){libraryTemplate(editorBundle,action);actions.add(action);}}return actions;}
    private void addShot(){if(!requireTimeline())return;try{java.util.List<String> actions=libraryActions();new AlertDialog.Builder(this).setTitle("Add a declared library action").setItems(actions.toArray(new String[0]),(d,index)->{try{timeline=addLibraryShot(timeline,editorBundle,actions.get(index),UUID.randomUUID().toString());selectedShot=timeline.getJSONArray("shots").length()-1;dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}).setNegativeButton("Cancel",null).show();}catch(Exception error){setStatus(message(error));}}
    private void duplicateShot(){if(!requireTimeline())return;try{timeline=duplicateLibraryShot(timeline,editorBundle,selectedShot,UUID.randomUUID().toString());selectedShot=timeline.getJSONArray("shots").length()-1;dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}
    private void deleteShot(){if(!requireTimeline())return;try{timeline=deleteLibraryShot(timeline,editorBundle,selectedShot);selectedShot=Math.min(selectedShot,timeline.getJSONArray("shots").length()-1);dirty=true;refresh();}catch(Exception error){setStatus(message(error));}}
    private EditText numeric(LinearLayout parent,String name,String value,boolean decimal){parent.addView(label(name,12));EditText input=new EditText(this);input.setText(value);input.setTextColor(Color.WHITE);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|(decimal?android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL:0));parent.addView(input);return input;}
    private CheckBox check(LinearLayout parent,String title,boolean checked){CheckBox control=new CheckBox(this);control.setText(title);control.setTextColor(Color.WHITE);control.setChecked(checked);parent.addView(control);return control;}
    private void editSelected(){if(!requireTimeline())return;try{
        JSONObject shot=timeline.getJSONArray("shots").getJSONObject(selectedShot),camera=shot.getJSONObject("camera"),vfx=shot.getJSONObject("vfx");int shotIndex=selectedShot;
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setBackgroundColor(BACKGROUND);ScrollView scroll=new ScrollView(this);scroll.addView(form);
        form.addView(label("Action",12));Spinner action=new Spinner(this);java.util.List<String> actionNames=isActionLibrary()?libraryActions():java.util.Collections.singletonList(shot.getString("action"));action.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,actionNames));action.setSelection(actionNames.indexOf(shot.getString("action")));action.setEnabled(isActionLibrary());form.addView(action);if(!isActionLibrary())form.addView(label("Custom baked actions need a rebuilt bundle.",11));
        EditText duration=numeric(form,"Duration in frames (8–600)",String.valueOf(shot.getInt("durationFrames")),false),hold=numeric(form,"Impact hold (0 or 2–5 frames)",String.valueOf(shot.getInt("hitStopFrames")),false);
        form.addView(label("Camera",12));Spinner mode=new Spinner(this);String[] modes={"wide","orbit","macro","top_down"};mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes));for(int i=0;i<modes.length;i++)if(modes[i].equals(camera.getString("mode")))mode.setSelection(i);form.addView(mode);
        final int authoredMode=mode.getSelectedItemPosition();CheckBox authoredTrack=camera.has("keyframes")?check(form,"Use authored camera track",true):null;if(authoredTrack!=null){mode.setEnabled(false);authoredTrack.setOnCheckedChangeListener((control,checked)->{if(checked)mode.setSelection(authoredMode);mode.setEnabled(!checked);});form.addView(label("Uncheck to replace the camera path with a preset. Actor and weapon poses keep their baked motion.",11));}
        EditText fov=numeric(form,"Field of view (35–100°)",String.valueOf(camera.getDouble("fovDegrees")),true),shake=numeric(form,"Camera shake (0–1)",String.valueOf(camera.getDouble("shakeIntensity")),true);
        CheckBox spark=check(form,"Spark burst",vfx.getBoolean("sparkBurst")),trail=check(form,"Slash trail",vfx.getBoolean("slashTrail")),rubble=check(form,"Contact debris",vfx.getBoolean("rubble"));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(shot.getString("action")+" • "+shot.getString("id")).setView(scroll).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String selectedAction=actionNames.get(action.getSelectedItemPosition());JSONObject edited=timeline;if(!selectedAction.equals(shot.getString("action")))edited=changeLibraryAction(timeline,editorBundle,shotIndex,selectedAction);if(authoredTrack!=null&&!authoredTrack.isChecked())edited=chooseCameraTrack(edited,shotIndex,false);int hitStop=Integer.parseInt(hold.getText().toString());if(!selectedAction.equals(shot.getString("action"))&&!isImpact(selectedAction))hitStop=0;edited=editShot(edited,shotIndex,Integer.parseInt(duration.getText().toString()),hitStop,modes[mode.getSelectedItemPosition()],Double.parseDouble(fov.getText().toString()),Double.parseDouble(shake.getText().toString()),spark.isChecked(),trail.isChecked(),rubble.isChecked());timeline=edited;dirty=true;refresh();dialog.dismiss();}catch(Exception error){Toast.makeText(this,message(error),Toast.LENGTH_LONG).show();}}));dialog.show();
    }catch(Exception error){setStatus(message(error));}}

    private void openPreview(){if(requireSaved())startActivity(previewIntent(this,project.id,programId,expectedProgramSha256));}
    private void renderNative(){if(!requireSaved())return;try{JSONObject request=getRequest(programId,expectedProgramSha256).put("width",OUTPUT_WIDTH).put("height",OUTPUT_HEIGHT).put("render",true).put("fileName","VideoStudio_Cinematic3D_"+System.currentTimeMillis()+".mp4");Intent intent=new Intent(this,ControlService.class).setAction(ControlService.ACTION_LOCAL_CINEMATIC_3D).putExtra("requestJson",request.toString());startForegroundService(intent);setStatus("Native 3D MP4 export queued. Progress and cancellation are available in VideoStudio jobs.");}catch(Exception error){setStatus("Cannot queue native export: "+message(error));}}
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

    private void smallState(Bundle state){state.putString("bundleId",bundleId);state.putString("programId",programId);state.putString("loadingProgramId",loadingProgramId);state.putString("programSha",expectedProgramSha256);state.putLong("sourceRevision",expectedSourceUpdatedAt);state.putInt("selectedShot",selectedShot);state.putBoolean("dirty",dirty);state.putBoolean("bindingReady",bindingReady);state.putBoolean("savePending",savePending);}
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);for(String key:new String[]{"draft","acceptedTimeline","pendingSaveRequest","editorBundle","exportedRequest"})state.remove(key);smallState(state);
        if(project==null)return;
        try{
            String id=UUID.randomUUID().toString();JSONObject snapshot=new JSONObject().put("schema","videostudio.cinematic3d.studio-state.v1").put("stateId",id).put("projectId",project.id)
                    .put("bundleId",bundleId).put("programId",programId).put("loadingProgramId",loadingProgramId).put("programSha",expectedProgramSha256).put("sourceRevision",expectedSourceUpdatedAt).put("selectedShot",selectedShot).put("dirty",dirty).put("bindingReady",bindingReady).put("savePending",savePending);
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
        Bundle restored=new Bundle(state);for(String key:new String[]{"bundleId","programId","loadingProgramId","programSha"})restored.putString(key,snapshot.getString(key));restored.putLong("sourceRevision",snapshot.getLong("sourceRevision"));restored.putInt("selectedShot",snapshot.getInt("selectedShot"));for(String key:new String[]{"dirty","bindingReady","savePending"})restored.putBoolean(key,snapshot.getBoolean(key));for(String key:new String[]{"draft","acceptedTimeline","pendingSaveRequest","editorBundle"})if(snapshot.has(key))restored.putString(key,snapshot.getJSONObject(key).toString());if(snapshot.has("exportedRequest"))restored.putString("exportedRequest",snapshot.getString("exportedRequest"));privateStateId=id;privateStateSha256=expected;return restored;
    }
    private void deleteStateFile(String id) throws Exception {File file=stateFile(stateFolder(false),id);if(file.exists()&&!file.delete())throw new IOException("Cannot remove superseded private draft snapshot");}
    @Override protected void onDestroy(){destroyed=true;operationGeneration++;ownerWorker.shutdownNow();if(android.os.Build.VERSION.SDK_INT>=33&&systemBackCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBackCallback);try{if(activeInput!=null)activeInput.close();}catch(Exception ignored){}try{if(activeOutput!=null)activeOutput.close();}catch(Exception ignored){}main.removeCallbacksAndMessages(null);if(isFinishing()&&!privateStateId.isEmpty())try{deleteStateFile(privateStateId);}catch(Exception error){ActivityLog.add(this,"user","Private draft cleanup failed",message(error),"failed",null,null,project==null?"":project.id);}super.onDestroy();}

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
    static JSONObject chooseCameraTrack(JSONObject original,int index,boolean keepAuthoredTrack) throws Exception {JSONObject result=copy(original);JSONObject camera=result.getJSONArray("shots").getJSONObject(index).getJSONObject("camera");if(!keepAuthoredTrack)camera.remove("keyframes");return Cinematic3DTimeline.validate(result);}
    static JSONObject saveRequest(String projectId,long revision,String bundleId,String programId,String sha,JSONObject timeline) throws Exception {JSONObject request=new JSONObject().put("projectId",projectId).put("sourceProjectUpdatedAt",revision).put("bundleId",bundleId).put("timeline",Cinematic3DTimeline.validate(timeline));if(programId!=null&&!programId.isEmpty())request.put("programId",programId);if(sha!=null&&!sha.isEmpty())request.put("expectedProgramSha256",sha);return request;}
    static JSONObject restoreTimeline(Bundle state,long revision) throws Exception {String value=state.getLong("sourceRevision",-1)==revision?state.getString("draft"):state.getString("acceptedTimeline");return value==null?null:Cinematic3DTimeline.validate(new JSONObject(value));}
    static Intent previewIntent(Context context,String projectId,String programId,String sha){return new Intent(context,FilamentGlbActivity.class).putExtra("projectId",projectId).putExtra("cinematicProgramId",programId).putExtra("expectedProgramSha256",sha);}
}
