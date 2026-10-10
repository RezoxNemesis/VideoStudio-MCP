package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
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

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

/** Compact native rig authoring. Every mutation is reviewed against the captured project revision. */
final class EditorRig2DInspector {
    interface Listener {
        boolean onCommit(String projectId,long revision,String operation,JSONObject args);
        void onSelectBone(String boneId);
        void onOverlay(boolean visible,boolean bindMode,boolean meshVisible);
        long currentOutputTimeMs();
    }
    private static final String[] RIG_EASINGS={"linear","ease_in","ease_out","ease_in_out","step","hold","cubic_bezier"};
    private final Activity activity;
    private final ProjectStore.Project project;
    private final ProjectStore.Clip clip;
    private final Listener listener;
    private long localTimeMs,authoredTimeMs;
    private final JSONObject rig;
    private JSONObject description;
    private final String selectedBone;

    EditorRig2DInspector(Activity activity,ProjectStore.Project snapshot,String clipId,long outputLocalMs,
                        String selectedBone,Listener listener)throws Exception {
        this.activity=activity;project=ProjectStore.copy(snapshot);clip=project.clip(clipId);this.listener=listener;
        if(clip==null)throw new IllegalArgumentException("Select an image clip first");
        localTimeMs=outputLocalMs;description=AnimationRigEdits.describe(project,clipId,outputLocalMs);
        rig=clip.effects.optJSONObject("rig2d");
        authoredTimeMs=description.optLong("authoredTimeMs",Math.max(0,outputLocalMs+clip.effects.optLong("animationOffsetMs",0)));
        JSONArray bones=rig==null?null:rig.optJSONArray("bones");
        this.selectedBone=find(bones,selectedBone)==null?(bones!=null&&bones.length()>0?bones.optJSONObject(0).optString("id"):""):selectedBone;
    }

    View build(boolean overlay,boolean bind,boolean mesh) {
        LinearLayout box=column();
        if(rig==null){
            box.addView(text("Articulate a still image with bones and a weighted mesh. The whole image is deformed; import isolated artwork when you want only the character to move."));
            box.addView(button("Create body rig",()->commit("create_rig",new JSONObject())));
            box.addView(button("Create connected two-bone chain",this::chainDialog));
            box.addView(button("Reuse another clip's rig",this::reuseRigDialog));return box;
        }
        box.addView(text(description.optInt("boneCount")+" bones · "+description.optInt("vertexCount")+" vertices · "+description.optInt("triangleCount")+" triangles · "+description.optInt("keyframeCount")+" keys"));
        box.addView(text("Clip "+seconds(localTimeMs)+" s · authored "+seconds(authoredTimeMs)+" s · r"+project.revision));
        LinearLayout display=new LinearLayout(activity);
        CheckBox show=check("Joints",overlay),bindMode=check("Bind",bind),showMesh=check("Weights",mesh);
        for(CheckBox control:new CheckBox[]{show,bindMode,showMesh})display.addView(control,new LinearLayout.LayoutParams(0,-2,1));
        View.OnClickListener displayListener=view->listener.onOverlay(show.isChecked(),bindMode.isChecked(),showMesh.isChecked());
        show.setOnClickListener(displayListener);bindMode.setOnClickListener(displayListener);showMesh.setOnClickListener(displayListener);box.addView(display);
        JSONObject bone=find(rig.optJSONArray("bones"),selectedBone);
        box.addView(button("Bone: "+(bone==null?"Select":bone.optString("name",selectedBone)),this::selectBoneDialog));
        LinearLayout pose=new LinearLayout(activity);
        pose.addView(button("Pose key",()->poseKeyDialog(null)),new LinearLayout.LayoutParams(0,dp(36),1));
        pose.addView(button("Bone keys",this::keyListDialog),new LinearLayout.LayoutParams(0,dp(36),1));
        pose.addView(button("Bind setup",()->boneDialog(bone)),new LinearLayout.LayoutParams(0,dp(36),1));box.addView(pose);
        LinearLayout library=new LinearLayout(activity);
        library.addView(button("Capture pose",this::capturePoseDialog),new LinearLayout.LayoutParams(0,dp(36),1));
        library.addView(button("Pose library",this::poseLibraryDialog),new LinearLayout.LayoutParams(0,dp(36),1));
        library.addView(button("IK targets",this::ikListDialog),new LinearLayout.LayoutParams(0,dp(36),1));box.addView(library);
        LinearLayout topology=new LinearLayout(activity);
        topology.addView(button("＋ Bone",()->boneDialog(null)),new LinearLayout.LayoutParams(0,dp(36),1));
        topology.addView(button("Vertex weights",()->vertexDialog(-1)),new LinearLayout.LayoutParams(0,dp(36),1));
        topology.addView(button("Auto weights",()->confirm("Replace painted weights?","Recalculates smooth capsule-distance weights for every mesh vertex.",()->commit("auto_weights",new JSONObject()))),new LinearLayout.LayoutParams(0,dp(36),1));box.addView(topology);
        box.addView(button(rig.optBoolean("enabled",true)?"Rig enabled · disable":"Rig disabled · enable",()->commit("set_enabled",json("enabled",!rig.optBoolean("enabled",true)))));
        box.addView(button("Apply another clip's rig",()->confirm("Replace this rig?","The chosen rig replaces this clip's bones, weights, keys, poses and IK in one undoable edit.",this::reuseRigDialog)));
        box.addView(button("Clear this rig",()->confirm("Clear rig?","Removes bones, pose keys, IK and weights from this clip. Undo remains available.",()->commit("clear_rig",new JSONObject()))));
        box.addView(text("Paused monitor: tap a joint to select a bone. Bind mode edits rest joints; Pose mode drags IK targets. Weights show the selected bone's influence."));
        return box;
    }

    void vertexDialog(int selectedIndex) {
        if(rig==null)return;
        JSONArray vertices=rig.optJSONObject("mesh").optJSONArray("vertices");
        if(selectedIndex<0){LinearLayout box=dialogBox();EditText index=number(0);box.addView(text("Vertex index · 0 to "+(vertices.length()-1)));box.addView(index);
            dialog("Inspect vertex",box,()->{int value=integer(index,0,vertices.length()-1,"Vertex index");vertexDialog(value);return true;},null,null);return;}
        JSONObject vertex=vertices.optJSONObject(selectedIndex);JSONArray influences=vertex.optJSONArray("influences"),bones=rig.optJSONArray("bones");
        LinearLayout box=dialogBox();box.addView(text("Vertex "+selectedIndex+" · UV "+String.format(Locale.US,"%.3f, %.3f",vertex.optDouble("u"),vertex.optDouble("v"))+". Positive values are normalized on save; zero entries are removed."));
        ArrayList<String> ids=new ArrayList<>(),names=new ArrayList<>();ids.add("");names.add("Unused");
        for(int i=0;i<bones.length();i++){JSONObject bone=bones.optJSONObject(i);ids.add(bone.optString("id"));names.add(bone.optString("name",bone.optString("id")));}
        Spinner[] selectors=new Spinner[4];EditText[] weights=new EditText[4];
        for(int index=0;index<4;index++){JSONObject value=influences.optJSONObject(index);selectors[index]=spinner(names.toArray(new String[0]));selectors[index].setSelection(value==null?0:Math.max(0,ids.indexOf(value.optString("boneId"))));weights[index]=number(value==null?0:value.optDouble("weight"));LinearLayout row=new LinearLayout(activity);row.addView(selectors[index],new LinearLayout.LayoutParams(0,-2,2));row.addView(weights[index],new LinearLayout.LayoutParams(0,-2,1));box.addView(row);}
        dialog("Paint vertex influences",box,()->{JSONArray painted=new JSONArray();for(int index=0;index<4;index++){double weight=value(weights[index],0,1,"Weight");String id=ids.get(selectors[index].getSelectedItemPosition());if(!id.isEmpty()&&weight>0)painted.put(json("boneId",id,"weight",weight));}return commit("set_weights",json("vertexIndex",selectedIndex,"influences",painted));},null,null);
    }

    private void selectBoneDialog(){JSONArray bones=rig.optJSONArray("bones");String[] labels=new String[bones.length()];for(int i=0;i<labels.length;i++){JSONObject bone=bones.optJSONObject(i);labels[i]=bone.optString("name",bone.optString("id"))+" · "+bone.optString("id");}
        new AlertDialog.Builder(activity).setTitle("Select bone").setItems(labels,(d,index)->listener.onSelectBone(bones.optJSONObject(index).optString("id"))).setNegativeButton("Close",null).show();}

    private void chainDialog(){LinearLayout box=dialogBox();String[] labels={"Root head X","Root head Y","Shared joint X","Shared joint Y","Child tail X","Child tail Y"};double[] defaults={.5,.8,.5,.5,.5,.2};EditText[] inputs=fields(box,labels,defaults);
        box.addView(text("Normalized source coordinates: X grows right, Y grows down. Both bones meet at the shared joint. A smooth full-image mesh is created."));
        dialog("Create custom chain",box,()->{double[] values=new double[6];for(int i=0;i<6;i++)values[i]=value(inputs[i],0,1,labels[i]);JSONArray bones=new JSONArray().put(json("id","root","name","Root","parentId","","x",values[0],"y",values[1],"endX",values[2],"endY",values[3])).put(json("id","child","name","Child","parentId","root","x",values[2],"y",values[3],"endX",values[4],"endY",values[5]));return commit("create_rig",json("bones",bones));},null,null);}

    private void reuseRigDialog(){ArrayList<ProjectStore.Clip> candidates=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();for(ProjectStore.Clip source:project.clips)if(!source.id.equals(clip.id)&&source.effects.optJSONObject("rig2d")!=null){ProjectStore.Asset asset=project.asset(source.assetId);candidates.add(source);labels.add((source.title.isEmpty()?asset==null?source.id:asset.name:source.title)+" · "+source.id);}
        if(candidates.isEmpty()){notice("No other clip in this project has a reusable rig");return;}
        new AlertDialog.Builder(activity).setTitle("Reuse rig · normalized image coordinates").setItems(labels.toArray(new String[0]),(d,index)->commit("apply_rig",json("rig",candidates.get(index).effects.optJSONObject("rig2d")))).setNegativeButton("Cancel",null).show();}

    private void boneDialog(JSONObject existing){LinearLayout box=dialogBox();boolean adding=existing==null;
        EditText name=new EditText(activity);name.setSingleLine();name.setText(adding?"Bone":existing.optString("name",existing.optString("id")));box.addView(text("Bone name"));box.addView(name);
        ArrayList<String> ids=new ArrayList<>(),names=new ArrayList<>();ids.add("");names.add("No parent · root");JSONArray bones=rig.optJSONArray("bones");
        for(int index=0;index<bones.length();index++){JSONObject candidate=bones.optJSONObject(index);if(existing!=null&&existing.optString("id").equals(candidate.optString("id")))continue;ids.add(candidate.optString("id"));names.add(candidate.optString("name",candidate.optString("id")));}
        Spinner parent=spinner(names.toArray(new String[0]));parent.setSelection(adding?Math.max(0,ids.indexOf(selectedBone)):Math.max(0,ids.indexOf(existing.optString("parentId"))));box.addView(text("Parent"));box.addView(parent);
        JSONObject source=adding?find(bones,selectedBone):existing;
        double headX=adding&&source!=null?source.optDouble("endX",.5):source==null?.5:source.optDouble("x"),headY=adding&&source!=null?source.optDouble("endY",.5):source==null?.5:source.optDouble("y");
        String[] labels={"Head X · 0 to 1","Head Y · 0 to 1","Tail X · 0 to 1","Tail Y · 0 to 1"};
        EditText[] inputs=fields(box,labels,new double[]{headX,headY,adding?headX:source.optDouble("endX"),adding?Math.max(0,headY-.2):source.optDouble("endY")});
        CheckBox auto=check("Recalculate every vertex's weights",adding);box.addView(auto);
        CheckBox connections=check("Keep touching bind joints connected",true);box.addView(connections);
        box.addView(text("Keep weight recalculation off to preserve painted weights. Connected IK requires a child's head to meet its parent's tail."));
        final String boneId=adding?"bone-"+UUID.randomUUID():existing.optString("id");
        dialog(adding?"Add bone":"Edit bind bone",box,()->{String label=name.getText().toString().trim();if(label.isEmpty()||label.length()>128)throw new IllegalArgumentException("Enter a bone name up to 128 characters");JSONObject bone=json("id",boneId,"name",label,"parentId",ids.get(parent.getSelectedItemPosition()),"x",value(inputs[0],0,1,"Head X"),"y",value(inputs[1],0,1,"Head Y"),"endX",value(inputs[2],0,1,"Tail X"),"endY",value(inputs[3],0,1,"Tail Y"));return commit("set_bone",json("bone",bone,"autoWeights",auto.isChecked(),"preserveConnections",connections.isChecked()));},adding?null:"Remove bone",adding?null:()->commit("remove_bone",json("boneId",boneId)));}

    private void keyListDialog(){JSONArray keys=rig.optJSONArray("keyframes");ArrayList<JSONObject> values=new ArrayList<>();ArrayList<String> names=new ArrayList<>();long offset=clip.effects.optLong("animationOffsetMs",0);
        if(keys!=null)for(int index=0;index<keys.length();index++){JSONObject key=keys.optJSONObject(index);if(selectedBone.equals(key.optString("boneId"))){long time=key.optLong("atMs")-offset;if(time>=0&&time<=clip.outputDurationMs()){values.add(key);names.add(seconds(time)+" s · "+key.optString("ease","linear"));}}}
        new AlertDialog.Builder(activity).setTitle("Bone keys · visible clip window").setItems(names.toArray(new String[0]),(d,index)->poseKeyDialog(values.get(index))).setPositiveButton("Add at playhead",(d,w)->poseKeyDialog(null)).setNegativeButton("Close",null).show();}

    private void poseKeyDialog(JSONObject key){if(!refreshTime())return;JSONObject sampled=find(description.optJSONArray("bones"),selectedBone);if(sampled==null)return;
        if(key!=null)sampled=find(AnimationRig2D.compile(rig,description.optDouble("sourceAspect",1)>0?(float)description.optDouble("sourceAspect",1):1).describe(key.optLong("atMs")).optJSONArray("bones"),selectedBone);
        LinearLayout box=dialogBox();long offset=clip.effects.optLong("animationOffsetMs",0);long at=key==null?authoredTimeMs:key.optLong("atMs");
        EditText time=number((key==null?localTimeMs:at-offset)/1000d);box.addView(text("Time in this clip · seconds"));box.addView(time);
        String[] labels={"Local rotation · degrees","Local X · −2 to 2","Local Y · −2 to 2","Stretch X · 0.05 to 20","Stretch Y · 0.05 to 20"};String[] channels={"rotation","x","y","scaleX","scaleY"};
        JSONObject authoredChannels=AnimationRig2D.compileForClip(clip,AnimationRigEdits.sourceAspect(project.asset(clip.assetId))).authoredPose(at).optJSONObject(selectedBone);
        double[] defaults={authoredChannels.optDouble("rotation"),authoredChannels.optDouble("x"),authoredChannels.optDouble("y"),authoredChannels.optDouble("scaleX",1),authoredChannels.optDouble("scaleY",1)};
        if(key!=null)for(int i=0;i<defaults.length;i++)defaults[i]=key.optDouble(channels[i],defaults[i]);EditText[] inputs=fields(box,labels,defaults);
        EditorEasingControls easing=new EditorEasingControls(activity,key==null?"linear":key.optString("ease","linear"),key==null?null:key.optJSONArray("bezier"),RIG_EASINGS,AnimationRig2D::namedEasing);box.addView(easing);
        box.addView(text("Writes all five selected-bone channels at this frame. IK may control rotation; disable that chain's mix to edit FK. Unit-scale IK chains cannot stretch."));
        dialog("Pose key · "+sampled.optString("name",selectedBone),box,()->{
            long local=Math.round(value(time,0,clip.outputDurationMs()/1000d,"Clip time")*1000),authored=ProjectTimeline.safeAdd(local,offset);
            long duration=Math.max(1,clip.effects.optLong("animationDurationMs",clip.outputDurationMs()));if(authored<0||authored>duration)throw new IllegalArgumentException("This extended trim holds the nearest pose; place the key inside the authored window");
            JSONObject next=json("boneId",selectedBone,"atMs",authored,"rotation",value(inputs[0],-36000,36000,"Rotation"),"x",value(inputs[1],-2,2,"Local X"),"y",value(inputs[2],-2,2,"Local Y"),"scaleX",value(inputs[3],.05,20,"Stretch X"),"scaleY",value(inputs[4],.05,20,"Stretch Y"),"ease",easing.selectedName());JSONArray bezier=easing.bezier();if(bezier!=null)next.put("bezier",bezier);
            // The shared move rejects occupied destinations before values are edited.
            if(key!=null&&authored!=key.optLong("atMs"))return commit("move_keyframe",json("oldAtMs",key.optLong("atMs"),"keyframe",next));
            return commit("set_keyframe",json("keyframe",next));
        },key==null?null:"Remove key",key==null?null:()->commit("remove_keyframe",json("boneId",selectedBone,"atMs",key.optLong("atMs"))));}

    private void capturePoseDialog(){if(!refreshTime())return;EditText name=new EditText(activity);name.setSingleLine();name.setText("Pose "+(rig.optJSONArray("poses")==null?1:rig.optJSONArray("poses").length()+1));LinearLayout box=dialogBox();box.addView(text("Capture all sampled bones, including the current IK solution, at "+seconds(localTimeMs)+" seconds."));box.addView(name);
        dialog("Capture reusable pose",box,()->{String entered=name.getText().toString().trim();if(entered.isEmpty()||entered.length()>128)throw new IllegalArgumentException("Enter a pose name up to 128 characters");return commit("capture_pose",json("poseId","pose-"+UUID.randomUUID(),"name",entered,"atMs",authoredTimeMs));},null,null);}

    private void poseLibraryDialog(){if(!refreshTime())return;JSONArray poses=rig.optJSONArray("poses");if(poses==null||poses.length()==0){notice("Capture a pose first");return;}String[] names=new String[poses.length()];for(int i=0;i<names.length;i++)names[i]=poses.optJSONObject(i).optString("name","Pose");
        new AlertDialog.Builder(activity).setTitle("Reusable poses").setItems(names,(d,index)->{JSONObject pose=poses.optJSONObject(index);new AlertDialog.Builder(activity).setTitle(pose.optString("name","Pose")).setMessage("Applying writes all pose bones at the current frame and turns IK mix to zero there.").setPositiveButton("Key at playhead",(dialog,w)->{try{commit("apply_pose",json("poseId",pose.optString("id"),"atMs",authoredEditTime(),"ease","linear"));}catch(Exception error){notice(error.getMessage());}}).setNeutralButton("Remove pose",(dialog,w)->commit("remove_pose",json("poseId",pose.optString("id")))).setNegativeButton("Cancel",null).show();}).setNegativeButton("Close",null).show();}

    private void ikListDialog(){JSONArray targets=rig.optJSONArray("ik");ArrayList<String> names=new ArrayList<>();ArrayList<JSONObject> values=new ArrayList<>();if(targets!=null)for(int i=0;i<targets.length();i++){JSONObject target=targets.optJSONObject(i);values.add(target);names.add(target.optString("id")+" · "+target.optString("rootBoneId")+" → "+target.optString("childBoneId"));}
        new AlertDialog.Builder(activity).setTitle("Connected two-link IK").setItems(names.toArray(new String[0]),(d,index)->ikDialog(values.get(index))).setPositiveButton("Add chain",(d,w)->ikDialog(null)).setNegativeButton("Close",null).show();}

    private void ikDialog(JSONObject existing){if(!refreshTime())return;JSONArray bones=rig.optJSONArray("bones");ArrayList<String> ids=new ArrayList<>(),names=new ArrayList<>();for(int i=0;i<bones.length();i++){JSONObject bone=bones.optJSONObject(i);ids.add(bone.optString("id"));names.add(bone.optString("name",bone.optString("id")));}
        LinearLayout box=dialogBox();Spinner root=spinner(names.toArray(new String[0])),child=spinner(names.toArray(new String[0]));root.setSelection(Math.max(0,ids.indexOf(existing==null?selectedBone:existing.optString("rootBoneId"))));child.setSelection(existing==null?Math.min(1,ids.size()-1):Math.max(0,ids.indexOf(existing.optString("childBoneId"))));box.addView(text("Root bone"));box.addView(root);box.addView(text("Immediate connected child"));box.addView(child);
        JSONObject sampled=existing==null?null:find(description.optJSONArray("ik"),existing.optString("id"));
        String[] labels={"Target source X · −2 to 3","Target source Y · −2 to 3","Mix · 0 to 1"};EditText[] inputs=fields(box,labels,new double[]{sampled==null?.5:sampled.optDouble("targetX"),sampled==null?.2:sampled.optDouble("targetY"),sampled==null?1:sampled.optDouble("mix",1)});
        CheckBox bend=check("Reverse elbow bend",existing!=null&&existing.optInt("bend",1)<0);box.addView(bend);
        box.addView(text("Requires the child head at the root tail, zero child translation and unit scales along the chain. An unreachable target clamps to the chain's reach."));
        final String id=existing==null?"ik-"+UUID.randomUUID():existing.optString("id");
        if(existing!=null)box.addView(button("Key target at playhead",()->{try{commit("set_ik_keyframe",json("ikId",id,"keyframe",json("atMs",authoredEditTime(),"targetX",value(inputs[0],-2,3,"Target X"),"targetY",value(inputs[1],-2,3,"Target Y"),"mix",value(inputs[2],0,1,"Mix"),"ease","linear")));}catch(Exception error){notice(error.getMessage());}}));
        if(existing!=null)box.addView(button("Target keyframes",()->ikKeyListDialog(existing)));
        dialog(existing==null?"Add IK chain":"Edit IK baseline",box,()->{JSONObject next=json("id",id,"rootBoneId",ids.get(root.getSelectedItemPosition()),"childBoneId",ids.get(child.getSelectedItemPosition()),"targetX",value(inputs[0],-2,3,"Target X"),"targetY",value(inputs[1],-2,3,"Target Y"),"mix",value(inputs[2],0,1,"Mix"),"bend",bend.isChecked()?-1:1);if(existing!=null&&existing.has("keyframes"))next.put("keyframes",existing.get("keyframes"));return commit("set_ik",json("ik",next));},existing==null?null:"Remove chain",existing==null?null:()->commit("remove_ik",json("ikId",id)));}

    private void ikKeyListDialog(JSONObject target){JSONArray keys=target.optJSONArray("keyframes");ArrayList<JSONObject> values=new ArrayList<>();ArrayList<String> names=new ArrayList<>();if(keys!=null)for(int i=0;i<keys.length();i++){JSONObject key=keys.optJSONObject(i);values.add(key);names.add(seconds(key.optLong("atMs")-clip.effects.optLong("animationOffsetMs",0))+" s · "+key.optString("ease","linear"));}
        new AlertDialog.Builder(activity).setTitle("IK target keys").setItems(names.toArray(new String[0]),(d,index)->{JSONObject key=values.get(index);new AlertDialog.Builder(activity).setTitle(names.get(index)).setMessage("Remove this target/mix key? The other keys stay in the graph.").setPositiveButton("Remove key",(dialog,w)->commit("remove_ik_keyframe",json("ikId",target.optString("id"),"atMs",key.optLong("atMs")))).setNegativeButton("Cancel",null).show();}).setNegativeButton("Close",null).show();}

    private boolean commit(String operation,JSONObject args){try{args.put("clipId",clip.id);ProjectStore.Project candidate=ProjectStore.copy(project);
        if("move_keyframe".equals(operation)){JSONObject key=args.getJSONObject("keyframe");AnimationRigEdits.apply(candidate,"move_keyframe",json("clipId",clip.id,"boneId",key.getString("boneId"),"fromAtMs",args.getLong("oldAtMs"),"toAtMs",key.getLong("atMs")));AnimationRigEdits.apply(candidate,"set_keyframe",json("clipId",clip.id,"keyframe",key));}
        else AnimationRigEdits.apply(candidate,operation,args);
        return listener.onCommit(project.id,project.revision,operation,args);
    }catch(Exception error){notice(error.getMessage()==null?"Could not apply this rig edit":error.getMessage());return false;}}

    private interface SaveAction {boolean run()throws Exception;}
    private boolean refreshTime(){try{localTimeMs=listener.currentOutputTimeMs();description=AnimationRigEdits.describe(project,clip.id,localTimeMs);authoredTimeMs=description.optLong("authoredTimeMs",0);return true;}catch(Exception error){notice(error.getMessage());return false;}}
    private long authoredEditTime(){long at=ProjectTimeline.safeAdd(localTimeMs,clip.effects.optLong("animationOffsetMs",0));long duration=Math.max(1,clip.effects.optLong("animationDurationMs",clip.outputDurationMs()));if(at<0||at>duration)throw new IllegalArgumentException("This extended trim holds the nearest pose; move inside the authored animation window to add keys");return at;}
    private void dialog(String title,LinearLayout contents,SaveAction action,String extraLabel,SaveAction extra){ScrollView scroll=new ScrollView(activity);scroll.addView(contents);AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();if(extra!=null)dialog.setButton(AlertDialog.BUTTON_NEUTRAL,extraLabel,(d,w)->{});
        dialog.setOnShowListener(shown->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{try{if(action.run())dialog.dismiss();}catch(Exception error){notice(error.getMessage());}});if(extra!=null)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view->{try{if(extra.run())dialog.dismiss();}catch(Exception error){notice(error.getMessage());}});});dialog.show();}
    private void confirm(String title,String message,Runnable action){new AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("Apply",(d,w)->action.run()).setNegativeButton("Cancel",null).show();}
    private LinearLayout column(){LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);return box;}
    private LinearLayout dialogBox(){LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(12));return box;}
    private TextView text(String value){TextView text=new TextView(activity);text.setText(value);text.setTextColor(Color.rgb(180,192,215));text.setTextSize(11);text.setPadding(0,dp(4),0,dp(4));return text;}
    private Button button(String label,Runnable action){Button button=new Button(activity);button.setText(label);button.setTextSize(10);button.setAllCaps(false);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(7),0,dp(7),0);button.setOnClickListener(view->action.run());return button;}
    private CheckBox check(String label,boolean checked){CheckBox box=new CheckBox(activity);box.setText(label);box.setTextSize(11);box.setTextColor(Color.WHITE);box.setChecked(checked);return box;}
    private EditText number(double number){EditText text=new EditText(activity);text.setText(String.format(Locale.US,"%.4f",number));text.setSingleLine();text.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);return text;}
    private EditText[] fields(LinearLayout box,String[] labels,double[] values){EditText[] inputs=new EditText[values.length];for(int i=0;i<values.length;i++){box.addView(text(labels[i]));inputs[i]=number(values[i]);box.addView(inputs[i]);}return inputs;}
    private Spinner spinner(String[] values){Spinner spinner=new Spinner(activity);spinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values));return spinner;}
    private double value(EditText input,double min,double max,String label){double number;try{number=Double.parseDouble(input.getText().toString());}catch(Exception error){throw new IllegalArgumentException("Enter "+label);}if(!Double.isFinite(number)||number<min||number>max)throw new IllegalArgumentException(label+" must be "+min+" to "+max);return number;}
    private int integer(EditText input,int min,int max,String label){double value=value(input,min,max,label);if(value!=Math.rint(value))throw new IllegalArgumentException(label+" must be a whole number");return(int)value;}
    private static JSONObject find(JSONArray values,String id){if(values!=null)for(int i=0;i<values.length();i++){JSONObject value=values.optJSONObject(i);if(value!=null&&value.optString("id").equals(id))return value;}return null;}
    private static JSONObject json(Object... values){JSONObject result=new JSONObject();try{for(int i=0;i<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]);}catch(Exception error){throw new IllegalArgumentException(error);}return result;}
    private static String seconds(double ms){return String.format(Locale.US,"%.3f",ms/1000d);}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private void notice(String message){Toast.makeText(activity,message==null?"Could not apply this edit":message,Toast.LENGTH_LONG).show();}
}
