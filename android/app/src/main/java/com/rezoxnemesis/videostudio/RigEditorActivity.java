package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owner-only source annotation on already imported artwork. Draft handles never certify motion quality. */
public final class RigEditorActivity extends Activity {
    private static final int BACKGROUND=0xff090d16,ACCENT=0xff58d9f2;
    private enum Mode { JOINTS, BODY, HEAD, HILT, TIP, BLADE }
    private ProjectStore projectStore;
    private Rig2DProjectStore rigStore;
    private ProjectStore.Project project;
    private ProjectStore.Asset selected;
    private Bitmap picture;
    private RigCanvasView drawing;
    private TextView instructions,sourceName,readiness;
    private Spinner pointPicker;
    private boolean black=true,transparent,dirty,pickerUpdating,insertVertex;
    private float[] swordHilt,swordTip;
    private Mode mode=Mode.JOINTS;
    private int selectedJoint,selectedVertex=-1;
    private String rigId="",expectedRigSha256="";
    private String displayedSourceSha256="",annotationSourceSha256="";
    private JSONObject readinessSelection;
    private int sourceWidth,sourceHeight,loadGeneration;
    private boolean imageLoading,saving,reconcilePendingSave;
    private volatile boolean destroyed;
    private final ExecutorService ownerWorker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private long expectedSourceUpdatedAt;
    private final List<float[]> joints=new ArrayList<>(),outline=new ArrayList<>(),swordOutline=new ArrayList<>(),headOutline=new ArrayList<>();
    private final ArrayDeque<String> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BACKGROUND);getWindow().setNavigationBarColor(BACKGROUND);
        projectStore=new ProjectStore(this);rigStore=new Rig2DProjectStore(this,projectStore);
        project=projectStore.get(getIntent().getStringExtra("projectId"));if(project==null){finish();return;}
        if(state!=null&&state.containsKey("readinessSelection"))try{readinessSelection=new JSONObject(state.getString("readinessSelection"));}catch(Exception ignored){}
        String assetId=state==null?getIntent().getStringExtra("assetId"):state.getString("assetId");
        selected=project.asset(assetId==null?"":assetId);
        if(!isImage(selected))for(ProjectStore.Asset asset:project.assets)if(isImage(asset)){selected=asset;break;}
        if(!isImage(selected)){notice("Import a source image into VideoStudio first");finish();return;}
        setupUi();selectImage(selected,()->{try{
            if(state!=null&&state.containsKey("draft")){
                applyDraft(state.getString("draft"));expectedSourceUpdatedAt=state.getLong("sourceRevision");expectedRigSha256=state.getString("rigSha","");
                dirty=state.getBoolean("dirty");restoreHistory(state.getString("undo","[]"),undo);restoreHistory(state.getString("redo","[]"),redo);
                reconcilePendingSave=state.getBoolean("savePending");
            }else{
                String requested=getIntent().getStringExtra("rigId");if(requested!=null&&!requested.isEmpty())loadProfile(requested);else chooseUniqueProfile();
            }
        }catch(Exception error){notice("Saved annotations unavailable: "+error.getMessage());}updateUi();});
    }

    private boolean isImage(ProjectStore.Asset asset){return asset!=null&&asset.mime!=null&&asset.mime.startsWith("image/")&&asset.uri!=null&&!asset.uri.isEmpty();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private Button button(String title,Runnable action){Button button=new Button(this);button.setText(title);button.setTextSize(10);button.setAllCaps(false);button.setTextColor(Color.WHITE);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(6),0,dp(6),0);button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff253047));button.setOnClickListener(v->{if(imageLoading||saving){notice("Wait for the accepted source operation");return;}action.run();});return button;}
    private TextView label(String title,int size){TextView text=new TextView(this);text.setText(title);text.setTextSize(size);text.setTextColor(Color.WHITE);text.setPadding(dp(8),dp(4),dp(8),dp(4));return text;}
    private void setupUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BACKGROUND);
        root.addView(label("Source Actor + Original Sword",20));
        TextView help=label("Mark visible landmarks and original masks. Draft completeness is not cinematic approval.",11);help.setMaxLines(2);root.addView(help);
        sourceName=label("",11);sourceName.setTextColor(ACCENT);root.addView(sourceName);
        LinearLayout selectors=new LinearLayout(this);
        for(Button button:new Button[]{button("Frame",this::chooseImage),button("Black",()->switchActor(true)),button("White",()->switchActor(false)),button("Profiles",this::chooseProfile),button("Fit",()->drawing.fit())})selectors.addView(button,new LinearLayout.LayoutParams(0,dp(38),1));
        root.addView(selectors);
        HorizontalScrollView tools=new HorizontalScrollView(this);tools.setHorizontalScrollBarEnabled(false);LinearLayout modes=new LinearLayout(this);
        for(Mode choice:Mode.values())modes.addView(button(choice==Mode.BODY?"Body mask":choice==Mode.HEAD?"Head mask":choice==Mode.BLADE?"Sword mask":choice.name().toLowerCase(),()->{drawing.cancelGesture();mode=choice;selectedVertex=-1;insertVertex=false;updateUi();}),new LinearLayout.LayoutParams(-2,dp(36)));
        tools.addView(modes);root.addView(tools);
        modes.addView(button("Review legacy source",this::reviewLegacySource),new LinearLayout.LayoutParams(-2,dp(36)));
        modes.addView(button("Pose diagnostic",this::poseDiagnostic),new LinearLayout.LayoutParams(-2,dp(36)));
        modes.addView(button("Sequence readiness",this::sequenceReadiness),new LinearLayout.LayoutParams(-2,dp(36)));
        instructions=label("",11);instructions.setMaxLines(2);instructions.setTextColor(ACCENT);root.addView(instructions);
        pointPicker=new Spinner(this);pointPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int index,long id){if(pickerUpdating)return;if(mode==Mode.JOINTS)selectedJoint=index;else selectedVertex=index;updateInstruction();drawing.invalidate();}
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });root.addView(pointPicker,new LinearLayout.LayoutParams(-1,dp(34)));
        drawing=new RigCanvasView();root.addView(drawing,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout points=new LinearLayout(this);points.addView(button("Coordinates",this::coordinatesDialog),new LinearLayout.LayoutParams(0,dp(36),1));
        points.addView(button("+ Vertex",()->{if(!polygonMode()){notice("Choose a body, head or sword polygon first");return;}insertVertex=true;updateInstruction();}),new LinearLayout.LayoutParams(0,dp(36),1));
        points.addView(button("Delete point",this::deletePoint),new LinearLayout.LayoutParams(0,dp(36),1));points.addView(button("Transparent PNG",()->changeDraft(()->transparent=!transparent)),new LinearLayout.LayoutParams(0,dp(36),1));root.addView(points);
        readiness=label("",10);readiness.setMaxLines(2);readiness.setTextColor(0xffadbacf);readiness.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Source annotation readiness").setMessage(readiness.getText()).setPositiveButton("Close",null).show());root.addView(readiness);
        LinearLayout actions=new LinearLayout(this);
        for(Button button:new Button[]{button("Undo",()->history(false)),button("Redo",()->history(true)),button("Contacts",this::contactDialog),button("Save Actor Rig",this::saveRig),button("Done",()->confirmDiscard(this::finish))})actions.addView(button,new LinearLayout.LayoutParams(0,dp(44),1));
        root.addView(actions);setContentView(root);
    }

    private void chooseImage(){confirmDiscard(()->{
        ProjectStore.Project fresh=projectStore.get(project.id);if(fresh==null){notice("Source project was removed");return;}
        ArrayList<ProjectStore.Asset> choices=new ArrayList<>();for(ProjectStore.Asset asset:fresh.assets)if(isImage(asset))choices.add(asset);
        String[] labels=new String[choices.size()];for(int index=0;index<labels.length;index++)labels[index]=choices.get(index).name;
        new AlertDialog.Builder(this).setTitle("Project-owned source frames").setItems(labels,(dialog,index)->{project=fresh;selectImage(choices.get(index),this::chooseUniqueProfile);}).setNegativeButton("Cancel",null).show();
    });}
    private void selectImage(ProjectStore.Asset asset,Runnable after){
        if(saving){notice("Wait for the accepted annotation save");return;}final int generation=++loadGeneration;imageLoading=true;drawing.cancelGesture();sourceName.setText("Reading verified original · "+asset.name);
        final String acceptedProjectId=project.id;
        ownerWorker.execute(()->{Bitmap loaded=null;JSONObject entry=null;ProjectStore.Project current=null;String failure=null;try{
            entry=NativeSourceArtworkInspector.inspect(getApplicationContext(),projectStore,new JSONObject().put("projectId",acceptedProjectId).put("assetId",asset.id).put("maxDimension",1024));
            JSONObject mapping=entry.getJSONObject("previewToOriginalUv");
            if(mapping.getDouble("scaleX")!=1||mapping.getDouble("scaleY")!=1||mapping.getDouble("offsetX")!=0||mapping.getDouble("offsetY")!=0)throw new IllegalArgumentException("Full-frame original U/V mapping is required for source authoring");
            loaded=RigWorkbenchImages.decode(entry.getJSONObject("sourceArtworkImage"),1024);
            current=projectStore.get(acceptedProjectId);
            if(current==null||current.updatedAt!=entry.getLong("sourceProjectUpdatedAt"))throw new IllegalStateException("Source project changed while reading; reopen the original");
        }catch(Exception|OutOfMemoryError error){if(loaded!=null&&!loaded.isRecycled())loaded.recycle();loaded=null;failure=error.getMessage()==null?"insufficient preview memory":error.getMessage();}
            final Bitmap ready=loaded;final JSONObject source=entry;final String problem=failure;
            final ProjectStore.Project accepted=current;
            boolean posted=main.post(()->{if(destroyed||generation!=loadGeneration){if(ready!=null&&!ready.isRecycled())ready.recycle();return;}imageLoading=false;if(problem!=null){notice("Image unavailable: "+problem);updateUi();return;}
                if(picture!=null&&!picture.isRecycled())picture.recycle();picture=ready;project=accepted;selected=accepted.asset(asset.id);displayedSourceSha256=source.optString("sourceSha256");sourceWidth=source.optInt("orientedWidth");sourceHeight=source.optInt("orientedHeight");newDraft();drawing.fit();after.run();updateUi();
            });if(!posted&&ready!=null&&!ready.isRecycled())ready.recycle();
        });
    }
    private void newDraft(){rigId=UUID.randomUUID().toString();expectedRigSha256="";reconcilePendingSave=false;expectedSourceUpdatedAt=project.updatedAt;annotationSourceSha256=displayedSourceSha256;joints.clear();outline.clear();swordOutline.clear();headOutline.clear();swordHilt=swordTip=null;transparent=false;dirty=false;undo.clear();redo.clear();selectedJoint=0;selectedVertex=-1;mode=Mode.JOINTS;}
    private List<Rig2DProfile> matchingProfiles(){ArrayList<Rig2DProfile> result=new ArrayList<>();for(Rig2DProfile rig:rigStore.list(project.id))if(selected.id.equals(rig.sourceAssetId)&&rig.black==black)result.add(rig);result.sort(Comparator.comparing(rig->rig.id));return result;}
    private void chooseUniqueProfile(){List<Rig2DProfile> profiles=matchingProfiles();try{if(profiles.size()==1)loadProfile(profiles.get(0).id);else if(profiles.size()>1)profileChoices(profiles);}catch(Exception error){notice(error.getMessage());}updateUi();}
    private void switchActor(boolean actor){if(black==actor)return;confirmDiscard(()->{drawing.cancelGesture();black=actor;newDraft();chooseUniqueProfile();});}
    private void chooseProfile(){confirmDiscard(()->{List<Rig2DProfile> profiles=matchingProfiles();if(profiles.isEmpty()){notice("No saved profile for this frame and actor; save the current source marks first");return;}profileChoices(profiles);});}
    private void profileChoices(List<Rig2DProfile> profiles){String[] labels=new String[profiles.size()];for(int index=0;index<labels.length;index++)labels[index]=(black?"Black":"White")+" · "+profiles.get(index).id;
        new AlertDialog.Builder(this).setTitle("Select exact saved actor profile").setItems(labels,(dialog,index)->{try{loadProfile(profiles.get(index).id);updateUi();}catch(Exception error){notice(error.getMessage());}}).setNeutralButton("New profile",(dialog,which)->{newDraft();updateUi();}).setNegativeButton("Cancel",null).show();}
    private void loadProfile(String id)throws Exception{
        JSONObject binding=rigStore.captureBinding(project.id,id);Rig2DProfile rig=rigStore.loadVerified(project.id,id,binding.getString("sha256"));
        if(!selected.id.equals(rig.sourceAssetId))throw new IllegalArgumentException("This profile belongs to another source frame");
        drawing.cancelGesture();applyDraft(rig.toJson().put("rigId",rig.id).toString());black=rig.black;expectedRigSha256=binding.getString("sha256");reconcilePendingSave=false;expectedSourceUpdatedAt=project.updatedAt;dirty=false;undo.clear();redo.clear();selectedJoint=0;mode=Mode.JOINTS;updateUi();
    }
    private void confirmDiscard(Runnable action){if(imageLoading||saving){notice("Wait for the accepted source operation");return;}if(!dirty){action.run();return;}new AlertDialog.Builder(this).setTitle("Leave this unsaved annotation draft?").setMessage("Saved profiles and original images remain unchanged. Save first to retain these edits.").setPositiveButton("Leave draft",(dialog,which)->action.run()).setNegativeButton("Keep editing",null).show();}

    private boolean polygonMode(){return mode==Mode.BODY||mode==Mode.BLADE||mode==Mode.HEAD;}
    private List<float[]> polygon(){return mode==Mode.BLADE?swordOutline:mode==Mode.HEAD?headOutline:outline;}
    private int markedJoints(){int count=0;for(float[] point:joints)if(point!=null)count++;return count;}
    private void updateUi(){if(sourceName==null)return;sourceName.setText((black?"BLACK":"WHITE")+" · "+selected.name+" · "+(expectedRigSha256.isEmpty()?"new profile":rigId)+" "+(dirty?"• unsaved":""));
        pickerUpdating=true;ArrayList<String> names=new ArrayList<>();if(mode==Mode.JOINTS)for(int index=0;index<Rig2DProfile.JOINTS.length;index++)names.add(Rig2DProfile.JOINTS[index]+(index<joints.size()&&joints.get(index)!=null?" ✓":" · missing"));
        else if(polygonMode())for(int index=0;index<polygon().size();index++)names.add("Vertex "+(index+1));
        if(names.isEmpty())names.add(mode==Mode.HILT?"Sword hilt":mode==Mode.TIP?"Sword tip":"No polygon vertices");
        pointPicker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));pointPicker.setSelection(Math.max(0,Math.min(names.size()-1,mode==Mode.JOINTS?selectedJoint:selectedVertex)));pickerUpdating=false;
        updateInstruction();ArrayList<String> missing=new ArrayList<>();if(markedJoints()!=16)missing.add((16-markedJoints())+" source joints");if(outline.size()<3)missing.add("body polygon");if(swordHilt==null)missing.add("sword hilt");if(swordTip==null)missing.add("sword tip");if(swordOutline.size()<3)missing.add("original sword polygon");
        if(annotationSourceSha256.isEmpty())missing.add("legacy marks have no reviewed source-byte binding");else if(!annotationSourceSha256.equals(displayedSourceSha256))missing.add("source image bytes differ from saved marks; redraw from a new profile");
        readiness.setText((missing.isEmpty()?"Actor fields supplied; geometry/contact admission still required.":"Missing action annotations: "+android.text.TextUtils.join(", ",missing)+".")+" Shot contacts are authored separately. Native rendering and cinematic approval remain unverified.");drawing.invalidate();
    }
    private void updateInstruction(){String text=(black?"BLACK":"WHITE")+" · "+markedJoints()+"/16 joints · ";
        if(mode==Mode.JOINTS)text+=Rig2DProfile.JOINTS[selectedJoint]+" · drag a handle, or tap to mark this missing joint.";
        else if(mode==Mode.HILT||mode==Mode.TIP)text+="tap/drag the real sword "+(mode==Mode.HILT?"hilt":"tip")+". Ownership: this actor's swordHand.";
        else text+=(mode==Mode.BODY?"Body excludes the sword":mode==Mode.HEAD?"Optional head and hair; follows neck/head controls":"Original sword outline")+" · "+polygon().size()+"/64 vertices · "+(insertVertex?"tap to insert after the selected vertex.":"drag vertices; tap to append.");
        instructions.setText(text+" Two fingers pan/zoom; empty space pans when all joints are marked.");}
    private String draft()throws Exception{JSONObject object=new JSONObject().put("rigId",rigId).put("black",black).put("transparentSource",transparent).put("joints",pairs(joints)).put("outline",pairs(outline)).put("swordOutline",pairs(swordOutline));if(!headOutline.isEmpty())object.put("headOutline",pairs(headOutline));if(!annotationSourceSha256.isEmpty())object.put("sourceSha256",annotationSourceSha256);if(swordHilt!=null)object.put("swordHilt",pair(swordHilt));if(swordTip!=null)object.put("swordTip",pair(swordTip));return object.toString();}
    private void applyDraft(String value)throws Exception{
        JSONObject object=new JSONObject(value);ArrayList<float[]> acceptedJoints=new ArrayList<>(),acceptedOutline=new ArrayList<>(),acceptedSwordOutline=new ArrayList<>(),acceptedHeadOutline=new ArrayList<>();
        readPairs(object.optJSONArray("joints"),acceptedJoints,true,16);readPairs(object.optJSONArray("outline"),acceptedOutline,false,64);readPairs(object.optJSONArray("swordOutline"),acceptedSwordOutline,false,64);
        if(object.has("headOutline")&&object.optJSONArray("headOutline")==null)throw new IllegalArgumentException("Head mask must be source coordinate pairs");
        readPairs(object.optJSONArray("headOutline"),acceptedHeadOutline,false,64);
        float[] acceptedHilt=readPair(object.optJSONArray("swordHilt")),acceptedTip=readPair(object.optJSONArray("swordTip"));
        // Reject the entire restore before changing its actor/source identity or any accepted points.
        rigId=object.optString("rigId",object.optString("id",rigId));black=object.optBoolean("black",black);annotationSourceSha256=object.optString("sourceSha256","");transparent=object.optBoolean("transparentSource",false);
        joints.clear();joints.addAll(acceptedJoints);outline.clear();outline.addAll(acceptedOutline);swordOutline.clear();swordOutline.addAll(acceptedSwordOutline);headOutline.clear();headOutline.addAll(acceptedHeadOutline);swordHilt=acceptedHilt;swordTip=acceptedTip;
    }
    private static void requireNormalized(float[] point){if(point==null||point.length!=2||!Float.isFinite(point[0])||!Float.isFinite(point[1])||point[0]<0||point[0]>1||point[1]<0||point[1]>1)throw new IllegalArgumentException("Both coordinates must be finite values in 0..1");}
    private static JSONArray pair(float[] point)throws Exception{requireNormalized(point);return new JSONArray().put(point[0]).put(point[1]);}
    private static JSONArray pairs(List<float[]> values)throws Exception{JSONArray result=new JSONArray();for(float[] point:values)result.put(point==null?JSONObject.NULL:pair(point));return result;}
    private static float[] readPair(JSONArray value)throws Exception{if(value==null)return null;if(value.length()!=2)throw new IllegalArgumentException("A source point needs exactly two coordinates");double u=value.getDouble(0),v=value.getDouble(1);if(!Double.isFinite(u)||!Double.isFinite(v)||u<0||u>1||v<0||v>1)throw new IllegalArgumentException("Both coordinates must be finite values in 0..1");float[] point={(float)u,(float)v};requireNormalized(point);return point;}
    private static void readPairs(JSONArray values,List<float[]> target,boolean sparse,int maximum)throws Exception{if(values==null)return;if(values.length()>maximum)throw new IllegalArgumentException("Too many source points");for(int index=0;index<values.length();index++){if(values.isNull(index)&&sparse){target.add(null);continue;}if(!(values.get(index) instanceof JSONArray))throw new IllegalArgumentException("Source points must be coordinate pairs");target.add(readPair(values.getJSONArray(index)));}}
    private void changed(String before){try{if(before.equals(draft()))return;undo.addLast(before);while(undo.size()>32)undo.removeFirst();redo.clear();dirty=true;updateUi();}catch(Exception error){notice(error.getMessage());}}
    private void changeDraft(Runnable action){if(imageLoading||saving){notice("Wait for the accepted source operation");return;}try{drawing.cancelGesture();String before=draft();action.run();changed(before);}catch(Exception error){notice(error.getMessage());}}
    private void history(boolean forward){if(imageLoading||saving)return;drawing.cancelGesture();ArrayDeque<String> source=forward?redo:undo,target=forward?undo:redo;if(source.isEmpty())return;try{target.addLast(draft());applyDraft(source.removeLast());dirty=true;updateUi();}catch(Exception error){notice(error.getMessage());}}
    private void setJoint(int index,float[] point){while(joints.size()<=index)joints.add(null);joints.set(index,point);}
    private float[] selectedPoint(){if(mode==Mode.JOINTS)return selectedJoint<joints.size()?joints.get(selectedJoint):null;if(mode==Mode.HILT)return swordHilt;if(mode==Mode.TIP)return swordTip;return selectedVertex>=0&&selectedVertex<polygon().size()?polygon().get(selectedVertex):null;}
    private void assignSelected(float[] point){requireNormalized(point);if(mode==Mode.JOINTS)setJoint(selectedJoint,point);else if(mode==Mode.HILT)swordHilt=point;else if(mode==Mode.TIP)swordTip=point;else if(selectedVertex>=0&&selectedVertex<polygon().size())polygon().set(selectedVertex,point);}
    private void deletePoint(){changeDraft(()->{if(mode==Mode.JOINTS){if(selectedJoint<joints.size())joints.set(selectedJoint,null);}else if(mode==Mode.HILT)swordHilt=null;else if(mode==Mode.TIP)swordTip=null;else if(selectedVertex>=0&&selectedVertex<polygon().size()){polygon().remove(selectedVertex);selectedVertex=Math.min(selectedVertex,polygon().size()-1);}});}
    private void coordinatesDialog(){drawing.cancelGesture();if(polygonMode()&&selectedPoint()==null){notice("Select a polygon vertex first");return;}float[] point=selectedPoint();LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(16),dp(8),dp(16),dp(8));
        String name=mode==Mode.JOINTS?Rig2DProfile.JOINTS[selectedJoint]:mode==Mode.HILT?"Sword hilt":mode==Mode.TIP?"Sword tip":(mode==Mode.BODY?"Body":mode==Mode.HEAD?"Head":"Sword")+" vertex "+(selectedVertex+1);
        fields.addView(label("Normalized source coordinates · 0 to 1. One pixel uses the original "+sourceWidth+" × "+sourceHeight+" image, at any zoom.",11));
        android.widget.EditText x=coordinate(point==null?.5f:point[0]),y=coordinate(point==null?.5f:point[1]);x.setTag("rig-coordinate-u");y.setTag("rig-coordinate-v");x.setContentDescription("Normalized source X, U, 0 to 1");y.setContentDescription("Normalized source Y, V, 0 to 1");fields.addView(label("X / U · left to right",11));fields.addView(x);fields.addView(label("Y / V · top to bottom",11));fields.addView(y);
        LinearLayout nudges=new LinearLayout(this);String[] titles={"X −1 px","X +1 px","Y −1 px","Y +1 px"};for(int index=0;index<titles.length;index++){final int direction=index;nudges.addView(button(titles[index],()->{try{float[] staged=coordinateValues(x,y);int dimension=direction<2?sourceWidth:sourceHeight;if(dimension<1)throw new IllegalArgumentException("Read the original image before nudging");int axis=direction<2?0:1;staged[axis]=(float)Math.max(0,Math.min(1,staged[axis]+(direction%2==0?-1d:1d)/dimension));x.setText(Float.toString(staged[0]));y.setText(Float.toString(staged[1]));}catch(Exception error){notice(error.getMessage());}}),new LinearLayout.LayoutParams(0,dp(44),1));}fields.addView(nudges);
        ScrollView scroll=new ScrollView(this);scroll.addView(fields);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Exact point · "+name).setView(scroll).setPositiveButton("Set point",null).setNegativeButton("Cancel",null).create();
        // show() creates the buttons synchronously; OnShowListener is queued. Bind
        // validation before returning so the visible button never has a no-op handler.
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{float[] accepted=coordinateValues(x,y);changeDraft(()->assignSelected(accepted));dialog.dismiss();}catch(Exception error){notice(error.getMessage());}});}
    private static float[] coordinateValues(android.widget.EditText x,android.widget.EditText y){float[] point={Float.parseFloat(x.getText().toString().trim()),Float.parseFloat(y.getText().toString().trim())};requireNormalized(point);return point;}
    private android.widget.EditText coordinate(float value){android.widget.EditText field=new android.widget.EditText(this);field.setSingleLine();field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);field.setText(Float.toString(value));return field;}
    private void saveRig(){
        if(imageLoading||saving)return;drawing.cancelGesture();if(picture==null){notice("The original image must be readable before saving source marks");return;}if(markedJoints()!=16||(!transparent&&outline.size()<3)){notice("Mark all sixteen named source joints and a body outline first");return;}
        if(!annotationSourceSha256.isEmpty()&&!annotationSourceSha256.equals(displayedSourceSha256)){notice("Saved marks bind different source bytes. Create a new profile and mark this original image again.");return;}
        try{JSONObject request=new JSONObject(draft());if(swordOutline.isEmpty())request.remove("swordOutline");final Rig2DProfile profile=NativeRigAuthoring.parse(project.id,selected.id,request);final long revision=expectedSourceUpdatedAt;final String expected=expectedRigSha256;final boolean reconcile=reconcilePendingSave;saving=true;sourceName.setText("Saving exact actor annotation · "+rigId);
            ownerWorker.execute(()->{JSONObject receipt=null;String problem=null;try{
                String acceptedExpected=expected;
                if(reconcile)try{JSONObject binding=rigStore.captureBinding(profile.projectId,profile.id);Rig2DProfile published=rigStore.loadVerified(profile.projectId,profile.id,binding.getString("sha256"));if(Rig2DProjectStore.sha256(profile).equals(Rig2DProjectStore.sha256(published)))acceptedExpected=binding.getString("sha256");}catch(Exception unavailable){/* A missing or different publication retains the original CAS expectation. */}
                receipt=rigStore.saveWithBinding(profile,revision,acceptedExpected);
            }catch(Exception|OutOfMemoryError error){problem=error.getMessage()==null?"insufficient memory":error.getMessage();}
                final JSONObject saved=receipt;final String failure=problem;main.post(()->{if(destroyed)return;saving=false;if(failure!=null){notice("Annotation not saved: "+failure+". Your draft is retained.");updateUi();return;}
                    expectedRigSha256=saved.optString("sha256");reconcilePendingSave=false;dirty=false;undo.clear();redo.clear();notice("Actor annotation saved to "+profile.id+". Contact and cinematic evidence still require admission.");try{updateUi();}catch(RuntimeException|OutOfMemoryError refresh){sourceName.setText("Saved actor annotation · reopen to refresh");drawing.setEnabled(false);}
                });
            });
        }catch(Exception error){notice("Annotation not saved: "+error.getMessage()+". Your draft is retained.");}
    }
    private void reviewLegacySource(){if(imageLoading||saving)return;if(!annotationSourceSha256.isEmpty()){notice(annotationSourceSha256.equals(displayedSourceSha256)?"This draft already binds the displayed source bytes":"Existing marks bind different bytes; start a new source profile");return;}
        new AlertDialog.Builder(this).setTitle("Review existing source marks").setMessage("Inspect all sixteen named joints, the actor boundary, sword ownership, hilt, tip and original sword polygon against this displayed original. Hidden anatomy must be left unmarked. This records your explicit source review; it does not approve motion quality.").setPositiveButton("I reviewed these marks",(dialog,which)->changeDraft(()->annotationSourceSha256=displayedSourceSha256)).setNegativeButton("Keep unbound",null).show();}
    private void contactDialog(){if(imageLoading||saving)return;drawing.cancelGesture();StoryboardContactEditorDialog.show(this,projectStore,rigStore,project.id,selected.id);}
    private void poseDiagnostic(){
        drawing.cancelGesture();if(dirty){notice("Save or undo source-rest edits before testing this exact saved profile");return;}
        if(expectedRigSha256.isEmpty()){notice("Save source actor annotations before opening a pose diagnostic");return;}
        startActivity(new Intent(this,RigPoseDiagnosticActivity.class).putExtra("projectId",project.id).putExtra("rigId",rigId).putExtra("expectedRigSha256",expectedRigSha256));
    }
    private void sequenceReadiness(){
        drawing.cancelGesture();try{
            JSONObject selection=readinessSelection==null?new JSONObject():new JSONObject(readinessSelection.toString());String programId=getIntent().getStringExtra("programId");
            if(readinessSelection!=null){RigSequenceReadinessDialog.show(this,projectStore,project.id,selection,this::openMissingActor);return;}
            if(programId!=null&&!programId.isEmpty()){selection.put("programId",programId);String sha=getIntent().getStringExtra("expectedProgramSha256");if(sha!=null&&!sha.isEmpty())selection.put("expectedProgramSha256",sha);}
            else{String raw=getIntent().getStringExtra("assetIds");if(raw==null)raw=getIntent().getStringExtra("sourceAssetIds");if(raw!=null)selection.put("assetIds",new JSONArray(raw));}
            RigSequenceReadinessDialog.show(this,projectStore,project.id,selection,this::openMissingActor);
        }catch(Exception error){notice("Source sequence unavailable: "+error.getMessage());}
    }
    private void openMissingActor(String assetId,boolean actorBlack,JSONObject order){readinessSelection=order==null?new JSONObject():order;confirmDiscard(()->{
        ProjectStore.Project fresh=projectStore.get(project.id);ProjectStore.Asset asset=fresh==null?null:fresh.asset(assetId);
        if(!isImage(asset)){notice("This exact ordered source is unavailable; originals and drafts are retained");return;}
        project=fresh;selectImage(asset,()->{black=actorBlack;chooseUniqueProfile();});
    });}
    private void notice(String message){Toast.makeText(this,message==null?"Annotation operation unavailable":message,Toast.LENGTH_LONG).show();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);try{if(selected!=null)state.putString("assetId",selected.id);if(readinessSelection!=null)state.putString("readinessSelection",readinessSelection.toString());if(drawing==null||picture==null||rigId.isEmpty()||displayedSourceSha256.isEmpty())return;drawing.cancelGesture();state.putString("draft",draft());state.putLong("sourceRevision",expectedSourceUpdatedAt);state.putString("rigSha",expectedRigSha256);state.putBoolean("dirty",dirty);state.putBoolean("savePending",saving||reconcilePendingSave);state.putString("undo",new JSONArray(undo).toString());state.putString("redo",new JSONArray(redo).toString());}catch(Exception error){notice("Draft state could not be retained");}}
    private static void restoreHistory(String text,ArrayDeque<String> target)throws Exception{JSONArray values=new JSONArray(text);target.clear();for(int index=Math.max(0,values.length()-32);index<values.length();index++)target.addLast(values.getString(index));}
    @Override protected void onDestroy(){destroyed=true;loadGeneration++;if(drawing!=null)drawing.cancelGesture();if(picture!=null&&!picture.isRecycled())picture.recycle();ownerWorker.shutdown();super.onDestroy();}

    private final class RigCanvasView extends View {
        private final Paint imagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG),overlay=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dest=new RectF();
        private final ScaleGestureDetector pinch;
        private float zoom=1,panX,panY,focusX,focusY,lastX,lastY;
        private boolean editing,navigating,panning;
        private String before;
        RigCanvasView(){super(RigEditorActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);pinch=new ScaleGestureDetector(RigEditorActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector detector){cancelGesture();navigating=true;return true;}
            @Override public boolean onScale(ScaleGestureDetector detector){float factor=detector.getScaleFactor(),x=detector.getFocusX(),y=detector.getFocusY();if(!Float.isFinite(factor)||factor<=0||!Float.isFinite(x)||!Float.isFinite(y))return false;float next=Math.max(1,Math.min(12,zoom*factor)),ratio=next/zoom;panX=(panX+getWidth()/2f-x)*ratio+x-getWidth()/2f;panY=(panY+getHeight()/2f-y)*ratio+y-getHeight()/2f;zoom=next;clampPan();invalidate();return true;}
        });setContentDescription("Source artwork annotation canvas. Drag named joints and polygon vertices; two fingers zoom and pan. Original media remains unchanged.");}
        void fit(){cancelGesture();zoom=1;panX=panY=0;invalidate();}
        private void geometry(){if(picture==null||sourceWidth<1||sourceHeight<1||getWidth()<1||getHeight()<1){dest.setEmpty();return;}float scale=Math.min(getWidth()/(float)sourceWidth,getHeight()/(float)sourceHeight)*zoom,w=sourceWidth*scale,h=sourceHeight*scale;dest.set((getWidth()-w)/2+panX,(getHeight()-h)/2+panY,(getWidth()+w)/2+panX,(getHeight()+h)/2+panY);}
        private void clampPan(){geometry();float maxX=Math.max(0,(dest.width()-getWidth())/2),maxY=Math.max(0,(dest.height()-getHeight())/2);panX=Math.max(-maxX,Math.min(maxX,panX));panY=Math.max(-maxY,Math.min(maxY,panY));geometry();}
        private float[] uv(float x,float y){return new float[]{Math.max(0,Math.min(1,(x-dest.left)/dest.width())),Math.max(0,Math.min(1,(y-dest.top)/dest.height()))};}
        private float px(float u){return dest.left+u*dest.width();}private float py(float v){return dest.top+v*dest.height();}
        @Override protected void onDraw(Canvas canvas){canvas.drawColor(0xff080b12);if(picture==null)return;geometry();if(dest.isEmpty())return;canvas.drawBitmap(picture,null,dest,imagePaint);drawPolygon(canvas,outline,0xfffa9b64,mode==Mode.BODY);drawPolygon(canvas,swordOutline,0xffffdb77,mode==Mode.BLADE);drawPolygon(canvas,headOutline,0xffb1e19b,mode==Mode.HEAD);
            overlay.setColor(black?0xffffa665:0xff53d9ff);overlay.setStyle(Paint.Style.STROKE);overlay.setStrokeWidth(dp(1.4f));
            for(int[] bone:Rig2DProfile.BONES)if(bone[0]<joints.size()&&bone[1]<joints.size()&&joints.get(bone[0])!=null&&joints.get(bone[1])!=null){float[] a=joints.get(bone[0]),b=joints.get(bone[1]);canvas.drawLine(px(a[0]),py(a[1]),px(b[0]),py(b[1]),overlay);}
            overlay.setStyle(Paint.Style.FILL);overlay.setTextSize(dp(9));for(int index=0;index<joints.size();index++){float[] point=joints.get(index);if(point==null)continue;canvas.drawCircle(px(point[0]),py(point[1]),dp(index==selectedJoint&&mode==Mode.JOINTS?5:3.5f),overlay);canvas.drawText(String.valueOf(index+1),px(point[0])+dp(6),py(point[1])-dp(5),overlay);}
            drawWeaponPoint(canvas,swordHilt,"HILT",mode==Mode.HILT);drawWeaponPoint(canvas,swordTip,"TIP",mode==Mode.TIP);if(swordHilt!=null&&swordTip!=null){overlay.setColor(0xffffe5a5);overlay.setStrokeWidth(dp(1.5f));canvas.drawLine(px(swordHilt[0]),py(swordHilt[1]),px(swordTip[0]),py(swordTip[1]),overlay);}
        }
        private void drawPolygon(Canvas canvas,List<float[]> points,int color,boolean active){if(points.isEmpty())return;Path shape=new Path();for(int index=0;index<points.size();index++){float[] point=points.get(index);if(index==0)shape.moveTo(px(point[0]),py(point[1]));else shape.lineTo(px(point[0]),py(point[1]));}if(points.size()>=3)shape.close();overlay.setStyle(Paint.Style.FILL);overlay.setColor((color&0x00ffffff)|0x33000000);canvas.drawPath(shape,overlay);overlay.setStyle(Paint.Style.STROKE);overlay.setColor(color);overlay.setStrokeWidth(dp(active?2:1));canvas.drawPath(shape,overlay);overlay.setStyle(Paint.Style.FILL);if(active)for(int index=0;index<points.size();index++){float[] point=points.get(index);canvas.drawCircle(px(point[0]),py(point[1]),dp(index==selectedVertex?5:3),overlay);}}
        private void drawWeaponPoint(Canvas canvas,float[] point,String name,boolean active){if(point==null)return;overlay.setColor(0xffffe5a5);overlay.setStyle(Paint.Style.STROKE);overlay.setStrokeWidth(dp(1.5f));canvas.drawCircle(px(point[0]),py(point[1]),dp(active?7:5),overlay);overlay.setStyle(Paint.Style.FILL);overlay.setTextSize(dp(10));canvas.drawText(name,px(point[0])+dp(8),py(point[1]),overlay);}
        private int hit(List<float[]> points,float x,float y){int picked=-1;double distance=dp(22);for(int index=0;index<points.size();index++){float[] point=points.get(index);if(point==null)continue;double next=Math.hypot(x-px(point[0]),y-py(point[1]));if(next<distance){distance=next;picked=index;}}return picked;}
        void cancelGesture(){if(editing&&before!=null)try{applyDraft(before);}catch(Exception ignored){}editing=panning=false;before=null;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);invalidate();}
        private void finishGesture(){String saved=before;editing=false;before=null;if(saved!=null)changed(saved);if(mode==Mode.JOINTS&&markedJoints()<16&&selectedJoint<joints.size()&&joints.get(selectedJoint)!=null){for(int next=1;next<=16;next++){int candidate=(selectedJoint+next)%16;if(candidate>=joints.size()||joints.get(candidate)==null){selectedJoint=candidate;break;}}updateUi();}if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);invalidate();}
        @Override public boolean onTouchEvent(MotionEvent event){if(picture==null||imageLoading||saving||!isShown()||!isEnabled())return false;
            if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){pinch.onTouchEvent(event);cancelGesture();navigating=false;return true;}if(event.getActionMasked()==MotionEvent.ACTION_DOWN)navigating=false;
            for(int index=0;index<event.getPointerCount();index++)if(!Float.isFinite(event.getX(index))||!Float.isFinite(event.getY(index))){cancelGesture();navigating=true;return true;}
            geometry();if(dest.isEmpty()){cancelGesture();navigating=true;return true;}
            pinch.onTouchEvent(event);geometry();if(event.getPointerCount()>1){cancelGesture();float fx=event.getX(0)/2+event.getX(1)/2,fy=event.getY(0)/2+event.getY(1)/2;if(navigating&&!pinch.isInProgress()&&event.getActionMasked()==MotionEvent.ACTION_MOVE){panX+=fx-focusX;panY+=fy-focusY;clampPan();invalidate();}focusX=fx;focusY=fy;navigating=true;return true;}
            if(navigating){if(event.getActionMasked()==MotionEvent.ACTION_UP){navigating=false;cancelGesture();}return true;}
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:{lastX=event.getX();lastY=event.getY();if(!dest.contains(lastX,lastY))return true;try{before=draft();float[] point=uv(lastX,lastY);
                    if(mode==Mode.JOINTS){int picked=hit(joints,lastX,lastY);if(picked>=0)selectedJoint=picked;else if(selectedJoint<joints.size()&&joints.get(selectedJoint)!=null){panning=true;before=null;break;}else setJoint(selectedJoint,point);}
                    else if(mode==Mode.HILT)swordHilt=point;else if(mode==Mode.TIP)swordTip=point;else{List<float[]> polygon=polygon();int picked=insertVertex?-1:hit(polygon,lastX,lastY);if(picked>=0)selectedVertex=picked;else{if(polygon.size()>=64){before=null;notice("Polygon is bounded to 64 vertices; select, move or delete a point");break;}int index=insertVertex?Math.max(0,Math.min(polygon.size(),selectedVertex+1)):polygon.size();polygon.add(index,point);selectedVertex=index;insertVertex=false;}}
                    editing=true;updateUi();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                }catch(Exception error){cancelGesture();notice(error.getMessage());}break;}
                case MotionEvent.ACTION_MOVE:{if(editing){assignSelected(uv(event.getX(),event.getY()));invalidate();}else if(panning){panX+=event.getX()-lastX;panY+=event.getY()-lastY;clampPan();invalidate();}lastX=event.getX();lastY=event.getY();break;}
                case MotionEvent.ACTION_UP:{if(editing){assignSelected(uv(event.getX(),event.getY()));finishGesture();}else cancelGesture();performClick();break;}
            }return true;
        }
        @Override protected void onDetachedFromWindow(){cancelGesture();navigating=false;super.onDetachedFromWindow();}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
