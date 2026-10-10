package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Sparse FK/IK timing lanes backed by the same authored-clock rig transactions as MCP. */
final class EditorAnimationDopeSheet extends LinearLayout {
    interface Listener {
        boolean onCommit(String projectId,long expectedRevision,String operation,JSONObject settings);
        void onSeek(long programPositionMs);
        long pauseAndGetOutputLocalMs();
        void onSelectBone(String boneId);
    }

    /** Rebuilds after graph commits retain the owner's viewport and selected lane. */
    static final class State {
        String projectId="",clipId="",kind="bone",id="",channel="rotation";
        long selectedAtMs=-1;
        double pixelsPerMs,scrollX,scrollY;
        boolean authoredView;
        boolean graphVisible=true;
        final EditorRigValueGraph.Viewport graphViewport=new EditorRigValueGraph.Viewport();
        final Set<String> expanded=new HashSet<>();
    }

    private static final String[] BONE_CHANNELS={"rotation","x","y","scaleX","scaleY"};
    private static final String[] BONE_LABELS={"Rotation","Local X","Local Y","Stretch X","Stretch Y"};
    private static final String[] IK_CHANNELS={"targetX","targetY","mix"};
    private static final String[] IK_LABELS={"Target X","Target Y","Mix"};
    private static final String[] EASINGS={"linear","ease_in","ease_out","ease_in_out","step","hold","cubic_bezier"};
    private final Activity activity;
    private final ProjectStore.Project project;
    private final ProjectStore.Clip clip;
    private final AnimationRig2D compiled;
    private final Listener listener;
    private final State state;
    private final long offsetMs,authoredDurationMs;
    private final boolean locked;
    private final List<Group> groups=new ArrayList<>();
    private final List<Row> rows=new ArrayList<>();
    private final Map<String,Group> groupLookup=new HashMap<>();
    private final LaneView lanes;
    private final EditorRigValueGraph valueGraph;
    private final LinearLayout graphSection;
    private String graphSourceIdentity="";
    private final TextView selectionText,clockText;
    private final Button seekButton,moveButton,deleteButton,addButton,editButton;
    private long outputLocalMs;
    private boolean playing;

    private static final class Key {
        final long atMs;
        final JSONObject json;
        Key(JSONObject value) { json=value;atMs=value.optLong("atMs"); }
    }
    private static final class Group {
        final String kind,id,name;
        final String[] channels,labels;
        final List<Key> keys=new ArrayList<>();
        Group(String kind,String id,String name,String[] channels,String[] labels){this.kind=kind;this.id=id;this.name=name;this.channels=channels;this.labels=labels;}
        String identity(){return kind+":"+id;}
        Key key(long at){for(Key key:keys)if(key.atMs==at)return key;return null;}
    }
    private static final class Row {
        final Group group;
        final String channel,label;
        final List<Key> keys=new ArrayList<>();
        Row(Group group,String channel,String label){this.group=group;this.channel=channel;this.label=label;for(Key key:group.keys)if(channel==null||key.json.has(channel))keys.add(key);}
    }

    EditorAnimationDopeSheet(Activity activity,ProjectStore.Project snapshot,String clipId,
                             String selectedBoneId,State state,Listener listener) throws Exception {
        super(activity);setOrientation(VERTICAL);this.activity=activity;this.listener=listener;this.state=state;
        project=ProjectStore.copy(snapshot);clip=project.clip(clipId);
        if(clip==null||clip.effects.optJSONObject("rig2d")==null)throw new IllegalArgumentException("Select a clip with an articulated rig");
        ProjectStore.Track track=project.track(clip.trackId);locked=track==null||track.locked;
        offsetMs=clip.effects.optLong("animationOffsetMs",0);
        authoredDurationMs=Math.max(1,clip.effects.optLong("animationDurationMs",clip.outputDurationMs()));
        compiled=AnimationRig2D.compileForClip(clip,AnimationRigEdits.sourceAspect(project.asset(clip.assetId)));
        readGroups(compiled.definition());
        if(!project.id.equals(state.projectId)||!clip.id.equals(state.clipId)){
            state.projectId=project.id;state.clipId=clip.id;state.kind="bone";state.id=selectedBoneId==null?"":selectedBoneId;
            state.channel="rotation";state.selectedAtMs=-1;state.scrollX=state.scrollY=state.pixelsPerMs=0;state.authoredView=false;state.expanded.clear();
        }
        if(selectedGroup()==null&&!groups.isEmpty()){state.kind=groups.get(0).kind;state.id=groups.get(0).id;state.channel=groups.get(0).channels[0];}
        Group initial=selectedGroup();if(initial!=null)state.expanded.add(initial.identity());
        if(selectedKey()==null)state.selectedAtMs=-1;
        rebuildRows();

        clockText=text("");addView(clockText);
        lanes=new LaneView();
        valueGraph=new EditorRigValueGraph(activity,state.graphViewport,new EditorRigValueGraph.Listener(){
            @Override public void onSelect(long atMs){state.selectedAtMs=atMs;refreshDetails();lanes.invalidate();if(inClip(atMs))seekSelected();}
            @Override public boolean onBeginDrag(long atMs){Group group=selectedGroup();Key key=group==null?null:group.key(atMs);if(locked||key==null||!key.json.has(state.channel))return false;outputLocalMs=listener.pauseAndGetOutputLocalMs();playing=false;refreshDetails();return true;}
            @Override public boolean onCommit(long atMs,double value){return commitGraphValue(atMs,value);}
            @Override public void onNotice(String message){notice(message);}
        });
        graphSection=new LinearLayout(activity);graphSection.setOrientation(VERTICAL);
        LinearLayout viewTools=new LinearLayout(activity);
        CheckBox authored=new CheckBox(activity);authored.setText("Whole authored clock");authored.setTextColor(Color.WHITE);authored.setTextSize(10);authored.setChecked(state.authoredView);
        authored.setOnCheckedChangeListener((button,checked)->{state.authoredView=checked;state.scrollX=0;lanes.fit();refreshDetails();});
        viewTools.addView(authored,new LayoutParams(0,-2,1));
        viewTools.addView(button("−",()->lanes.zoom(.75)));viewTools.addView(button("＋",()->lanes.zoom(1.33)));viewTools.addView(button("Fit",()->lanes.fit()));addView(viewTools);
        Button graphToggle=button(state.graphVisible?"Value graph ●":"Value graph",()->{});graphToggle.setOnClickListener(view->{valueGraph.discardDraft();state.graphVisible=!state.graphVisible;graphToggle.setText(state.graphVisible?"Value graph ●":"Value graph");graphSection.setVisibility(state.graphVisible?VISIBLE:GONE);});addView(graphToggle);
        addView(lanes,new LayoutParams(-1,dp(226)));
        HorizontalScrollView graphTools=new HorizontalScrollView(activity);graphTools.setHorizontalScrollBarEnabled(false);LinearLayout graphCommands=new LinearLayout(activity);
        graphCommands.addView(button("Graph Fit",valueGraph::fit));graphCommands.addView(button("Time −",()->valueGraph.zoomTime(.75)));graphCommands.addView(button("Time ＋",()->valueGraph.zoomTime(1.33)));graphCommands.addView(button("Value −",()->valueGraph.zoomValue(.75)));graphCommands.addView(button("Value ＋",()->valueGraph.zoomValue(1.33)));graphTools.addView(graphCommands);graphSection.addView(graphTools);
        graphSection.addView(valueGraph,new LayoutParams(-1,dp(224)));graphSection.addView(text("Authored values before IK. Drag an existing channel key vertically; release saves one edit. The graph is sampled up to 256 points, including visible key steps when space permits. Drafts change this graph only; time edits use Move whole key."));graphSection.setVisibility(state.graphVisible?VISIBLE:GONE);addView(graphSection);
        selectionText=text("");selectionText.setMinHeight(dp(35));addView(selectionText);
        HorizontalScrollView commands=new HorizontalScrollView(activity);commands.setHorizontalScrollBarEnabled(false);LinearLayout commandRow=new LinearLayout(activity);
        addButton=button("＋ Key at playhead",this::addKeyDialog);commandRow.addView(addButton);
        seekButton=button("Seek key",this::seekSelected);commandRow.addView(seekButton);
        editButton=button("Edit sparse key",this::editSelected);commandRow.addView(editButton);
        moveButton=button("Move whole key",this::moveDialog);commandRow.addView(moveButton);
        deleteButton=button("Delete whole key",this::deleteDialog);commandRow.addView(deleteButton);
        commands.addView(commandRow);addView(commands);
        addView(text("Diamonds show only authored channels. Tap labels to expand bones / IK, tap a key to select and seek. Swipe lanes to pan; pinch to zoom. Moving or deleting affects the whole sparse key at that bone / IK time. FK values precede IK; use target / Mix keys when the solver controls a bone."));
        setContentDescription("Animation dope sheet: sparse bone and IK keyframe channels");refreshDetails();
    }

    void setPlayhead(long localMs,boolean isPlaying) {
        outputLocalMs=Math.max(0,Math.min(clip.outputDurationMs(),localMs));playing=isPlaying;
        lanes.invalidate();refreshDetails();
    }

    private void readGroups(JSONObject rig) {
        JSONArray bones=rig.optJSONArray("bones");
        for(int index=0;bones!=null&&index<bones.length();index++){
            JSONObject bone=bones.optJSONObject(index);Group group=new Group("bone",bone.optString("id"),bone.optString("name",bone.optString("id")),BONE_CHANNELS,BONE_LABELS);
            groups.add(group);groupLookup.put(group.identity(),group);
        }
        JSONArray keys=rig.optJSONArray("keyframes");for(int index=0;keys!=null&&index<keys.length();index++){
            JSONObject value=keys.optJSONObject(index);Group group=value==null?null:groupLookup.get("bone:"+value.optString("boneId"));if(group!=null)group.keys.add(new Key(value));
        }
        JSONArray ik=rig.optJSONArray("ik");for(int index=0;ik!=null&&index<ik.length();index++){
            JSONObject target=ik.optJSONObject(index);Group group=new Group("ik",target.optString("id"),"IK "+target.optString("id"),IK_CHANNELS,IK_LABELS);
            JSONArray targetKeys=target.optJSONArray("keyframes");for(int keyIndex=0;targetKeys!=null&&keyIndex<targetKeys.length();keyIndex++){JSONObject value=targetKeys.optJSONObject(keyIndex);if(value!=null)group.keys.add(new Key(value));}
            groups.add(group);groupLookup.put(group.identity(),group);
        }
        for(Group group:groups)group.keys.sort(Comparator.comparingLong(key->key.atMs));
    }

    private void rebuildRows(){rows.clear();for(Group group:groups){rows.add(new Row(group,null,group.name));if(state.expanded.contains(group.identity()))for(int index=0;index<group.channels.length;index++)rows.add(new Row(group,group.channels[index],group.labels[index]));}}
    private Group selectedGroup(){return groupLookup.get(state.kind+":"+state.id);}
    private Key selectedKey(){Group group=selectedGroup();return group==null?null:group.key(state.selectedAtMs);}
    private long rawAuthored(long local){return ProjectTimeline.safeAdd(local,offsetMs);}
    private long clockTime(long authored){return state.authoredView?authored:authored-offsetMs;}
    private long clockDuration(){return state.authoredView?authoredDurationMs:clip.outputDurationMs();}
    private boolean inClip(long authored){long local=authored-offsetMs;return local>=0&&local<=clip.outputDurationMs();}

    private void refreshDetails(){
        if(clockText==null)return;
        long raw=rawAuthored(outputLocalMs),sampled=Math.max(0,Math.min(authoredDurationMs,raw));
        clockText.setText((playing?"Playing":"Paused")+" · clip "+seconds(outputLocalMs)+" s · authored "+sampled+" ms · r"+project.revision+(raw!=sampled?" · held state":"")+(locked?" · locked":""));
        Group group=selectedGroup();Key key=selectedKey();
        if(selectionText!=null){String detail=group==null?"Select a bone or IK lane":group.name+(state.channel.isEmpty()?" · whole pose":" · "+state.channel);
            if(key!=null){detail+=" · authored "+key.atMs+" ms · clip "+seconds(key.atMs-offsetMs)+" s\n"+sparseDescription(group,key.json)+(inClip(key.atMs)?"":" · outside current clip");}
            else detail+=" · select a diamond or add at the playhead";
            selectionText.setText(detail);
        }
        if(addButton!=null)addButton.setEnabled(group!=null&&!locked&&raw>=0&&raw<=authoredDurationMs&&raw<=AnimationRig2D.MAX_TIME_MS);
        if(seekButton!=null)seekButton.setEnabled(key!=null&&inClip(key.atMs));
        if(moveButton!=null)moveButton.setEnabled(key!=null&&!locked);
        if(deleteButton!=null)deleteButton.setEnabled(key!=null&&!locked);
        if(editButton!=null)editButton.setEnabled(key!=null&&!locked);
        refreshValueGraph();
    }

    private void refreshValueGraph(){
        if(valueGraph==null)return;Group group=selectedGroup();String channel=state.channel;
        if(group==null||channel.isEmpty()){graphSourceIdentity="";valueGraph.setSource(null);return;}
        String identity=project.id+":"+clip.id+":"+project.revision+":"+group.identity()+":"+channel+":"+state.authoredView;
        if(!identity.equals(graphSourceIdentity)){
            graphSourceIdentity=identity;List<EditorRigValueGraph.Key> keys=new ArrayList<>();String previousEase="linear";
            for(Key key:group.keys)if(key.json.has(channel)){keys.add(new EditorRigValueGraph.Key(key.atMs,key.json.optDouble(channel),"step".equals(previousEase)||"hold".equals(previousEase)));previousEase=key.json.optString("ease","linear");}
            double[] bounds=channelBounds(group,channel);final Group sampledGroup=group;final String sampledChannel=channel;
            String viewportIdentity=project.id+":"+clip.id+":"+group.identity()+":"+channel+":"+state.authoredView;
            valueGraph.setSource(new EditorRigValueGraph.Source(identity,viewportIdentity,group.name+" · "+channel,clockDuration(),state.authoredView?0:offsetMs,authoredDurationMs,bounds[0],bounds[1],locked,keys,
                    (atMs,keyAtMs)->"bone".equals(sampledGroup.kind)?compiled.authoredChannelSample(sampledGroup.id,sampledChannel,atMs,keyAtMs):compiled.authoredIkChannelSample(sampledGroup.id,sampledChannel,atMs,keyAtMs)));
        }
        valueGraph.setSelectedKey(state.selectedAtMs);valueGraph.setPlayhead(state.authoredView?Math.max(0,Math.min(authoredDurationMs,rawAuthored(outputLocalMs))):outputLocalMs,playing);
    }

    private boolean commitGraphValue(long atMs,double value){
        Group group=selectedGroup();Key existing=group==null?null:group.key(atMs);String channel=state.channel;
        if(locked||existing==null||channel.isEmpty()||!existing.json.has(channel))return false;
        try{validateValue(group,channel,value);JSONObject key=new JSONObject(existing.json.toString());key.put(channel,value);JSONObject args=json("clipId",clip.id,"keyframe",key);if("ik".equals(group.kind))args.put("ikId",group.id);
            return commit("bone".equals(group.kind)?"set_keyframe":"set_ik_keyframe",args);
        }catch(Exception|OutOfMemoryError error){notice(error.getMessage()==null?"Animation graph memory is unavailable":error.getMessage());return false;}
    }

    private String sparseDescription(Group group,JSONObject key){StringBuilder text=new StringBuilder();for(String channel:group.channels)if(key.has(channel)){if(text.length()>0)text.append(" · ");text.append(channel).append(' ').append(String.format(Locale.US,"%.3f",key.optDouble(channel)));}text.append(" · ").append(key.optString("ease","linear"));return text.toString();}

    private void select(Row row,Key key){state.kind=row.group.kind;state.id=row.group.id;state.channel=row.channel==null?"":row.channel;state.selectedAtMs=key==null?-1:key.atMs;
        if("bone".equals(row.group.kind))listener.onSelectBone(row.group.id);refreshDetails();lanes.invalidate();
        if(key!=null&&inClip(key.atMs))seekSelected();
    }

    private void seekSelected(){Key key=selectedKey();if(key==null)return;if(!inClip(key.atMs)){notice("This authored key lies outside the retained clip window");return;}listener.onSeek(ProjectTimeline.safeAdd(clip.startMs,key.atMs-offsetMs));}

    private void addKeyDialog(){Group group=selectedGroup();if(group==null||locked)return;try{
        outputLocalMs=listener.pauseAndGetOutputLocalMs();playing=false;long at=rawAuthored(outputLocalMs);
        if(at<0||at>authoredDurationMs)throw new IllegalArgumentException("This extended trim holds its nearest pose. Move inside the authored animation window to add a key.");
        JSONObject values;
        if("bone".equals(group.kind)){values=compiled.authoredPoseClip(outputLocalMs).optJSONObject(group.id);if(values==null)throw new IllegalArgumentException("Selected bone is unavailable");}
        else {values=find(compiled.describeClip(outputLocalMs).optJSONArray("ik"),group.id);if(values==null)throw new IllegalArgumentException("Selected IK chain is unavailable");}
        Key existing=group.key(at);
        JSONObject draft=new JSONObject().put("atMs",at).put("ease",existing==null?"linear":existing.json.optString("ease","linear"));
        if(existing!=null&&existing.json.has("bezier"))draft.put("bezier",existing.json.get("bezier"));
        for(String channel:group.channels)if(state.channel.isEmpty()||state.channel.equals(channel))draft.put(channel,values.optDouble(channel));
        editKeyDialog(group,draft,true);
    }catch(Exception|OutOfMemoryError error){notice(error.getMessage()==null?"Animation preview memory is unavailable":error.getMessage());}}

    private void editSelected(){Group group=selectedGroup();Key key=selectedKey();if(group==null||key==null||locked)return;listener.pauseAndGetOutputLocalMs();try{editKeyDialog(group,new JSONObject(key.json.toString()),false);}catch(Exception|OutOfMemoryError error){notice(error.getMessage()==null?"Animation preview memory is unavailable":error.getMessage());}}

    private void editKeyDialog(Group group,JSONObject original,boolean adding) throws Exception {
        LinearLayout box=dialogBox();box.addView(text("Authored "+original.optLong("atMs")+" ms · clip "+seconds(original.optLong("atMs")-offsetMs)+" s. Only checked channels are authored; unchecked channels are retained when adding to an existing key. The curve is shared by all channels at this key time."));
        CheckBox[] enabled=new CheckBox[group.channels.length];EditText[] inputs=new EditText[group.channels.length];
        JSONObject sampled="bone".equals(group.kind)?compiled.authoredPose(original.optLong("atMs")).optJSONObject(group.id):find(compiled.describe(original.optLong("atMs")).optJSONArray("ik"),group.id);
        for(int index=0;index<group.channels.length;index++){
            String channel=group.channels[index];enabled[index]=new CheckBox(activity);enabled[index].setText(group.labels[index]);enabled[index].setTextColor(Color.WHITE);enabled[index].setChecked(original.has(channel));
            double fallback=sampled==null?channel.startsWith("scale")?1:0:sampled.optDouble(channel,channel.startsWith("scale")?1:0);
            inputs[index]=number(original.optDouble(channel,fallback));inputs[index].setEnabled(enabled[index].isChecked());final EditText input=inputs[index];enabled[index].setOnCheckedChangeListener((button,checked)->input.setEnabled(checked));box.addView(enabled[index]);box.addView(inputs[index]);
        }
        EditorEasingControls easing=new EditorEasingControls(activity,original.optString("ease","linear"),original.optJSONArray("bezier"),EASINGS,AnimationRig2D::namedEasing);box.addView(easing);
        if(!adding)box.addView(text("Saving an unchecked channel removes it from this sparse row. At least one channel must remain."));
        ScrollView scroll=new ScrollView(activity);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(adding?"Add sparse key · "+group.name:"Edit sparse key · "+group.name).setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(shown->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{try{
            JSONObject key=new JSONObject().put("atMs",original.optLong("atMs")).put("ease",easing.selectedName());int count=0;
            for(int index=0;index<group.channels.length;index++)if(enabled[index].isChecked()){String channel=group.channels[index];double value=Double.parseDouble(inputs[index].getText().toString());validateValue(group,channel,value);key.put(channel,value);count++;}
            if(count==0)throw new IllegalArgumentException("Keep at least one authored channel");JSONArray controls=easing.bezier();if(controls!=null)key.put("bezier",controls);
            if("bone".equals(group.kind))key.put("boneId",group.id);
            JSONObject args=json("clipId",clip.id,"keyframe",key);if("ik".equals(group.kind))args.put("ikId",group.id);
            // Editing replaces the original sparse row; adding uses the normal shared merge policy.
            if(!adding)args.put("replaceKeyframe",true);
            long previous=state.selectedAtMs;state.selectedAtMs=key.optLong("atMs");
            if(commit("bone".equals(group.kind)?"set_keyframe":"set_ik_keyframe",args)){dialog.dismiss();}else state.selectedAtMs=previous;
        }catch(Exception error){notice(error.getMessage());}}));dialog.show();
    }

    private void moveDialog(){Group group=selectedGroup();Key key=selectedKey();if(group==null||key==null||locked)return;listener.pauseAndGetOutputLocalMs();
        LinearLayout box=dialogBox();box.addView(text("Move the whole sparse "+("bone".equals(group.kind)?"bone":"IK")+" key. Values and easing are preserved. Occupied destinations are rejected."));
        box.addView(text("Destination authored milliseconds · 0 to "+authoredDurationMs));EditText time=new EditText(activity);time.setSingleLine();time.setInputType(InputType.TYPE_CLASS_NUMBER);time.setText(Long.toString(key.atMs));box.addView(time);
        box.addView(text("Current clip time: "+seconds(key.atMs-offsetMs)+" seconds. Authored offset is "+offsetMs+" ms."));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Move key · "+group.name).setView(box).setPositiveButton("Move",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(shown->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{try{long destination=Long.parseLong(time.getText().toString().trim());if(destination<0||destination>authoredDurationMs)throw new IllegalArgumentException("Move inside the authored animation window");
            JSONObject args=json("clipId",clip.id,"bone".equals(group.kind)?"boneId":"ikId",group.id,"fromAtMs",key.atMs,"toAtMs",destination);
            long previous=state.selectedAtMs;state.selectedAtMs=destination;if(commit("bone".equals(group.kind)?"move_keyframe":"move_ik_keyframe",args))dialog.dismiss();else state.selectedAtMs=previous;
        }catch(Exception error){notice(error.getMessage());}}));dialog.show();
    }

    private void deleteDialog(){Group group=selectedGroup();Key key=selectedKey();if(group==null||key==null||locked)return;listener.pauseAndGetOutputLocalMs();
        new AlertDialog.Builder(activity).setTitle("Delete whole sparse key?").setMessage(group.name+" at authored "+key.atMs+" ms\n"+sparseDescription(group,key.json)+"\nOther key times stay in the graph. Undo remains available.")
                .setPositiveButton("Delete",(dialog,w)->{long previous=state.selectedAtMs;state.selectedAtMs=-1;JSONObject args=json("clipId",clip.id,"bone".equals(group.kind)?"boneId":"ikId",group.id,"atMs",key.atMs);if(!commit("bone".equals(group.kind)?"remove_keyframe":"remove_ik_keyframe",args))state.selectedAtMs=previous;}).setNegativeButton("Cancel",null).show();
    }

    private boolean commit(String operation,JSONObject args){try{ProjectStore.Project candidate=ProjectStore.copy(project);
        AnimationRigEdits.apply(candidate,operation,args);return listener.onCommit(project.id,project.revision,operation,args);
    }catch(Exception|OutOfMemoryError error){notice(error.getMessage()==null?"Animation preview memory is unavailable":error.getMessage());return false;}}

    private static double[] channelBounds(Group group,String channel){return "bone".equals(group.kind)?new double[]{"rotation".equals(channel)?-36000:channel.startsWith("scale")?.05:-2,"rotation".equals(channel)?36000:channel.startsWith("scale")?20:2}:new double[]{"mix".equals(channel)?0:-2,"mix".equals(channel)?1:3};}
    private static void validateValue(Group group,String channel,double value){double[] bounds=channelBounds(group,channel);if(!Double.isFinite(value)||value<bounds[0]||value>bounds[1])throw new IllegalArgumentException(channel+" must be "+bounds[0]+" to "+bounds[1]);}

    private final class LaneView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path diamond=new Path();
        private final ScaleGestureDetector scale;
        private float downX,downY,lastX,lastY;
        private boolean moved,scrubbing,scaling,wasScaling;
        LaneView(){super(activity);setContentDescription("Sparse animation key lanes. Tap diamonds; pan lanes or pinch to zoom.");scale=new ScaleGestureDetector(activity,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector detector){scaling=true;wasScaling=true;scrubbing=false;return true;}
            @Override public boolean onScale(ScaleGestureDetector detector){double anchor=(state.scrollX+detector.getFocusX()-labelWidth())/Math.max(1e-12,state.pixelsPerMs);state.pixelsPerMs=Math.max(1e-9,Math.min(dp(3),state.pixelsPerMs*detector.getScaleFactor()));state.scrollX=Math.max(0,anchor*state.pixelsPerMs-detector.getFocusX()+labelWidth());clamp();invalidate();return true;}
            @Override public void onScaleEnd(ScaleGestureDetector detector){scaling=false;}
        });}
        private float labelWidth(){return dp(118);}
        private float rulerHeight(){return dp(30);}
        private float rowHeight(){return dp(29);}
        private float x(long time){return(float)(labelWidth()+time*state.pixelsPerMs-state.scrollX);}
        private long time(float x){return Math.max(0,Math.min(clockDuration(),Math.round((state.scrollX+x-labelWidth())/Math.max(1e-12,state.pixelsPerMs))));}
        private void clamp(){state.scrollX=Math.max(0,Math.min(state.scrollX,Math.max(0,clockDuration()*state.pixelsPerMs-(getWidth()-labelWidth()))));state.scrollY=Math.max(0,Math.min(state.scrollY,Math.max(0,rows.size()*rowHeight()-(getHeight()-rulerHeight()))));}
        void fit(){if(getWidth()<=labelWidth())return;state.pixelsPerMs=Math.max(1e-9,Math.min(dp(3),(getWidth()-labelWidth()-dp(18))/Math.max(1d,clockDuration())));state.scrollX=0;clamp();invalidate();}
        void zoom(double factor){long focus=state.authoredView?Math.max(0,Math.min(authoredDurationMs,rawAuthored(outputLocalMs))):outputLocalMs;double oldX=x(focus);state.pixelsPerMs=Math.max(1e-9,Math.min(dp(3),state.pixelsPerMs*factor));state.scrollX=Math.max(0,labelWidth()+focus*state.pixelsPerMs-oldX);clamp();invalidate();}
        @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){if(state.pixelsPerMs<=0)fit();else clamp();}
        @Override protected void onDraw(Canvas canvas){canvas.drawColor(Color.rgb(10,15,24));if(state.pixelsPerMs<=0)return;clamp();
            double desired=dp(65)/state.pixelsPerMs,power=Math.pow(10,Math.floor(Math.log10(Math.max(1,desired)))),unit=desired/power;
            long interval=Math.max(1,Math.round((unit<=1?1:unit<=2?2:unit<=5?5:10)*power));long first=Math.max(0,(long)(state.scrollX/state.pixelsPerMs)/interval*interval);
            canvas.save();canvas.clipRect(labelWidth(),0,getWidth(),getHeight());paint.setStrokeWidth(dp(1));
            long tick=first;for(int count=0;count<200&&x(tick)<getWidth();count++){paint.setColor(Color.rgb(35,45,61));canvas.drawLine(x(tick),rulerHeight(),x(tick),getHeight(),paint);paint.setColor(Color.rgb(154,172,196));paint.setTextSize(dp(9));canvas.drawText(seconds(tick),x(tick)+dp(3),dp(17),paint);if(tick>Long.MAX_VALUE-interval)break;tick+=interval;}
            canvas.restore();
            int start=Math.max(0,(int)(state.scrollY/rowHeight())),end=Math.min(rows.size(),start+(int)Math.ceil(getHeight()/rowHeight())+1);
            for(int index=start;index<end;index++){Row row=rows.get(index);float top=rulerHeight()+index*rowHeight()-(float)state.scrollY;if(top<rulerHeight()-rowHeight())continue;
                boolean selected=row.group.kind.equals(state.kind)&&row.group.id.equals(state.id)&&(row.channel==null?state.channel.isEmpty():row.channel.equals(state.channel));
                paint.setColor(selected?Color.rgb(25,47,65):row.channel==null?Color.rgb(22,30,43):Color.rgb(12,19,30));canvas.drawRect(0,Math.max(rulerHeight(),top),getWidth(),top+rowHeight(),paint);
                canvas.save();canvas.clipRect(labelWidth(),Math.max(rulerHeight(),top),getWidth(),top+rowHeight());
                for(Key key:row.keys){long time=clockTime(key.atMs);float center=x(time);if(time<0||time>clockDuration()||center<labelWidth()-dp(6)||center>getWidth()+dp(6))continue;
                    boolean selectedKey=key.atMs==state.selectedAtMs&&row.group.kind.equals(state.kind)&&row.group.id.equals(state.id);paint.setColor(selectedKey?Color.rgb(255,203,81):"bone".equals(row.group.kind)?Color.rgb(52,219,236):Color.rgb(226,115,250));
                    float radius=dp(selectedKey?5:3.5f),cy=top+rowHeight()/2;diamond.reset();diamond.moveTo(center,cy-radius);diamond.lineTo(center+radius,cy);diamond.lineTo(center,cy+radius);diamond.lineTo(center-radius,cy);diamond.close();canvas.drawPath(diamond,paint);
                }canvas.restore();canvas.save();canvas.clipRect(0,Math.max(rulerHeight(),top),labelWidth()-dp(3),top+rowHeight());paint.setColor(Color.rgb(186,204,226));paint.setTextSize(dp(10));String label=(row.channel==null?(state.expanded.contains(row.group.identity())?"▾ ":"▸ "):"    ")+row.label;
                canvas.drawText(label,dp(4),top+dp(19),paint);canvas.restore();
            }
            long play=state.authoredView?Math.max(0,Math.min(authoredDurationMs,rawAuthored(outputLocalMs))):outputLocalMs;float line=x(play);if(line>=labelWidth()&&line<=getWidth()){paint.setColor(Color.rgb(253,88,110));paint.setStrokeWidth(dp(1.5f));canvas.drawLine(line,0,line,getHeight(),paint);}
            paint.setColor(Color.rgb(24,33,47));canvas.drawRect(0,0,labelWidth(),rulerHeight(),paint);paint.setColor(Color.rgb(158,176,199));paint.setTextSize(dp(9));canvas.drawText(state.authoredView?"Authored seconds":"Clip seconds",dp(5),dp(18),paint);
        }
        private Row row(float y){int index=(int)Math.floor((y-rulerHeight()+state.scrollY)/rowHeight());return y>=rulerHeight()&&index>=0&&index<rows.size()?rows.get(index):null;}
        private Key hit(Row row,float x){Key hit=null;double nearest=dp(11);for(Key key:row.keys){long time=clockTime(key.atMs);if(time<0||time>clockDuration())continue;double distance=Math.abs(x-x(time));if(distance<nearest){hit=key;nearest=distance;}}return hit;}
        private void seek(float x){long at=time(x),local=state.authoredView?at-offsetMs:at;if(local<0||local>clip.outputDurationMs()){selectionText.setText("This authored time lies outside the retained clip window");return;}listener.onSeek(ProjectTimeline.safeAdd(clip.startMs,local));}
        @Override public boolean onTouchEvent(MotionEvent event){scale.onTouchEvent(event);switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:downX=lastX=event.getX();downY=lastY=event.getY();moved=false;wasScaling=false;scrubbing=downY<rulerHeight()&&downX>=labelWidth();getParent().requestDisallowInterceptTouchEvent(true);if(scrubbing)seek(downX);return true;
            case MotionEvent.ACTION_MOVE:if(scaling||event.getPointerCount()>1)return true;if(Math.hypot(event.getX()-downX,event.getY()-downY)>dp(5))moved=true;if(scrubbing)seek(event.getX());else if(moved){state.scrollX-=event.getX()-lastX;state.scrollY-=event.getY()-lastY;clamp();invalidate();}lastX=event.getX();lastY=event.getY();return true;
            case MotionEvent.ACTION_UP:if(!wasScaling&&!moved&&!scrubbing){Row row=row(event.getY());if(row!=null){if(event.getX()<labelWidth()){if(row.channel==null){if(!state.expanded.add(row.group.identity()))state.expanded.remove(row.group.identity());rebuildRows();clamp();}select(row,null);}else select(row,hit(row,event.getX()));}}scrubbing=false;getParent().requestDisallowInterceptTouchEvent(false);invalidate();performClick();return true;
            case MotionEvent.ACTION_CANCEL:scrubbing=false;getParent().requestDisallowInterceptTouchEvent(false);invalidate();return true;
            default:return true;
        }}
        @Override public boolean performClick(){super.performClick();return true;}
    }

    private LinearLayout dialogBox(){LinearLayout box=new LinearLayout(activity);box.setOrientation(VERTICAL);box.setPadding(dp(18),dp(8),dp(18),dp(12));return box;}
    private EditText number(double value){EditText input=new EditText(activity);input.setSingleLine();input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);input.setText(Double.toString(value));return input;}
    private TextView text(String value){TextView text=new TextView(activity);text.setText(value);text.setTextColor(Color.rgb(169,188,214));text.setTextSize(10);text.setPadding(0,dp(3),0,dp(3));return text;}
    private Button button(String label,Runnable action){Button button=new Button(activity);button.setText(label);button.setTextSize(10);button.setAllCaps(false);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(8),0,dp(8),0);button.setLayoutParams(new LayoutParams(-2,dp(32)));button.setOnClickListener(view->action.run());return button;}
    private static JSONObject find(JSONArray values,String id){for(int index=0;values!=null&&index<values.length();index++){JSONObject value=values.optJSONObject(index);if(value!=null&&id.equals(value.optString("id")))return value;}return null;}
    private static JSONObject json(Object... values){JSONObject result=new JSONObject();try{for(int index=0;index<values.length;index+=2)result.put(String.valueOf(values[index]),values[index+1]);}catch(Exception error){throw new IllegalArgumentException(error);}return result;}
    private static String seconds(long ms){return String.format(Locale.US,"%.3f",ms/1000d);}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private void notice(String message){Toast.makeText(activity,message==null?"Could not edit this animation key":message,Toast.LENGTH_LONG).show();}
}
