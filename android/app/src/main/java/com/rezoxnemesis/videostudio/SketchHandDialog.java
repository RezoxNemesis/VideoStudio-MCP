package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Manual hand authoring through the shared native constructor and protected submit path. */
final class SketchHandDialog {
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    private static final String[] HANDS={"right","left"},DETAILS={"anatomical","contour"};
    private static final String[] RENDER_STYLES={"smooth","cel"};
    private static final String[] PART_LABELS={"Legacy default (complete hand)","All","Palm / forearm","Digits"};
    private static final String[] PARTS={"all","palm_forearm","digits"};
    private static final String[] STUDY_LABELS={"Open / close","Grasp","Finger cascade","Thumb opposition"};
    private static final String[] EASING={"Native default","linear","hold","smooth","ease_in","ease_out","ease_in_out","bezier"};
    private static final String[][] NUMBERS={{"centerX","Centre X (0.2..0.8)"},{"centerY","Centre Y (0.2..0.8)"},{"size","Size (0.4..1.2)"},{"motion","Motion (0..1)"},{"wristMotion","Wrist motion (0..1)"}};
    private final Activity activity;
    private final JSONObject document;
    private final SketchConstructionSubmit create;
    private final LinearLayout form,poseControls;
    private final TextView status;
    private final Spinner view,handedness,detail,renderStyle,parts,study,posePicker,easing;
    private final CheckBox custom;
    private final EditText skin,nail,keyFrame,previewFrame;
    private final Map<String,EditText> settingsFields=new LinkedHashMap<>(),channels=new LinkedHashMap<>();
    private final Map<String,EditText[]> fingerFields=new LinkedHashMap<>();
    private final EditText[] curve=new EditText[4];
    private final ImageView preview;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private JSONArray poses;
    private int selected,previewGeneration;
    private boolean binding,closed,submitting;
    private Bitmap previewBitmap;
    private Future<?> previewTask;
    private AlertDialog dialog;

    static void show(Activity activity,JSONObject document,SketchConstructionSubmit create)throws Exception {
        new SketchHandDialog(activity,document,create).show();
    }

    private SketchHandDialog(Activity activity,JSONObject document,SketchConstructionSubmit create)throws Exception {
        this.activity=activity;this.document=SketchDocument.copy(document);this.create=create;
        int frames=document.getInt("frameCount"),fps=document.getInt("fps");double seconds=frames/(double)fps;
        if(frames<24||frames>240||(fps!=24&&fps!=30&&fps!=60)||seconds<4||seconds>8)
            throw SketchDocument.bad("Hand animation needs 4–8 seconds, 24–240 frames and 24/30/60 FPS; use Timing / canvas first");
        JSONObject defaults=SketchHandAnimation.defaults();
        form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,12,20,12);
        status=new TextView(activity);status.setContentDescription("Hand animation status");status.setText("Hand ready. Preview uses the editable native joint programme.");
        TextView description=new TextView(activity);description.setContentDescription("Hand animation description");
        description.setText("Create an animated hand and forearm on a new editable layer. Current sketch: "+frames+" frames at "+fps+" FPS.\nLoad study replaces custom pose keys while keeping your settings and timeline. Example setup: 1280 x 720, 96 frames / 24 FPS, Size 0.95, Render style cel. Use Timing / canvas before opening this dialog to change the timeline.\nOpen / close follows Motion and Wrist motion. Other studies load editable pose keys. Reset preset returns to the default motion.");form.addView(description);
        view=spinner(form,"Hand view",VIEWS);view.setSelection(Arrays.asList(VIEWS).indexOf(defaults.getString("view")));
        handedness=spinner(form,"Handedness",HANDS);handedness.setSelection(Arrays.asList(HANDS).indexOf(defaults.getString("handedness")));
        detail=spinner(form,"Hand detail",DETAILS);detail.setSelection(Arrays.asList(DETAILS).indexOf(defaults.getString("detail")));
        renderStyle=spinner(form,"Render style",RENDER_STYLES);renderStyle.setSelection(Arrays.asList(RENDER_STYLES).indexOf(defaults.getString("renderStyle")));
        parts=spinner(form,"Hand parts",PART_LABELS);
        TextView partHelp=new TextView(activity);partHelp.setText("Partial passes require cel and exactly one hand object on their own layer. Use matching pose and transform keys on separate rear palm/forearm and front digit layers around a prop. Visibility is resolved on the full hand first. Contact and thumb pose remain authored; this does not solve grip anatomy. Partial output uses native pixels up to 2048 squared; later cached preview zoom may soften its boundary.");form.addView(partHelp);
        skin=input(form,"Skin colour (ARGB)",defaults.getString("skinColor"),true);skin.setInputType(InputType.TYPE_CLASS_TEXT);
        nail=input(form,"Nail colour (ARGB)",defaults.getString("nailColor"),true);nail.setInputType(InputType.TYPE_CLASS_TEXT);
        for(String[] field:NUMBERS)settingsFields.put(field[0],input(form,field[1],String.valueOf(defaults.get(field[0])),true));
        custom=new CheckBox(activity);custom.setText("Custom poses");custom.setContentDescription("Custom poses");form.addView(custom);
        button(form,"Reset preset",this::resetPreset);
        study=spinner(form,"Study example",STUDY_LABELS);
        button(form,"Load study",this::loadStudy);
        poseControls=new LinearLayout(activity);poseControls.setOrientation(LinearLayout.VERTICAL);form.addView(poseControls);
        TextView help=new TextView(activity);help.setText("Use 1–32 ordered keys. Frame 1 must remain the starting key. Select a key to edit; selection, Preview and Create apply pending edits. To add a key, enter a free frame and channel values, then Add pose. Duplicate frames are rejected. Blank channels use native neutral defaults for that key. Curl, opposition and spread: 0–1; wrist flex/turn: −25–25 degrees. Easing controls the interval after this key; Bezier needs four values from 0 to 1.");poseControls.addView(help);
        poses=SketchHandAnimation.preset(frames,fps,defaults);
        posePicker=spinner(poseControls,"Hand pose",new String[]{"Pose"});keyFrame=input(poseControls,"Key frame (1 based)","1",false);
        for(String channel:SketchHandAnimation.CHANNELS)channels.put(channel,input(poseControls,channel,"",true));
        TextView fingerHelp=new TextView(activity);fingerHelp.setText("Optional finger joint degrees override curl for that finger at this key. Fill all three values or leave all blank: MCP 0–110, PIP 0–120, DIP 0–90. Intervals ease between resolved endpoint angles; a blank endpoint uses its own curl. Thumb controls are unchanged. These authored angles do not solve prop contact.");poseControls.addView(fingerHelp);
        for(String finger:SketchFingerJointAngles.FINGERS){EditText[] fields=new EditText[3];String[] joints={"MCP","PIP","DIP"};for(int j=0;j<3;j++)fields[j]=input(poseControls,finger+" "+joints[j]+" (degrees)","",true);fingerFields.put(finger,fields);}
        easing=spinner(poseControls,"Pose easing",EASING);
        for(int i=0;i<4;i++)curve[i]=input(poseControls,"Bezier "+(i+1),i<2?"0":"1",true);
        button(poseControls,"Apply pose edits",()->{saveCurrent();refresh();status.setText("Pose edits applied; keys are ordered.");});
        button(poseControls,"Add pose",this::addPose);button(poseControls,"Delete pose",this::deletePose);
        previewFrame=input(form,"Preview frame (1 based)",String.valueOf(frames/2+1),false);
        button(poseControls,"Use selected pose frame",()->{saveCurrent();refresh();previewFrame.setText(String.valueOf(poses.getJSONObject(selected).getInt("frame")+1));});
        preview=new ImageView(activity);preview.setContentDescription("Hand preview");preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button(form,"Preview hand",this::preview);form.addView(preview,new LinearLayout.LayoutParams(-1,(int)(220*activity.getResources().getDisplayMetrics().density)));form.addView(status);
        posePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View child,int position,long id){
                if(binding||unavailable()||submitting||!custom.isChecked()||position==selected)return;
                try{JSONObject next=poses.getJSONObject(position);saveCurrent();selected=indexOf(next);refresh();}
                catch(Exception error){binding=true;posePicker.setSelection(selected);binding=false;status.setText("Hand unchanged: "+error.getMessage());}
            }
        });
        custom.setOnCheckedChangeListener((control,checked)->{if(binding||unavailable()||submitting)return;clearPreview();enable(poseControls,checked);status.setText(checked?"Custom keys enabled. Blank channels use native neutral defaults.":"Native preset enabled with the current settings. Custom keys are retained until Reset preset.");});
        for(Spinner control:new Spinner[]{view,handedness,detail,renderStyle,parts,easing})control.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View child,int position,long id){if(!binding&&!unavailable()&&!submitting)clearPreview();}
        });
        TextWatcher edited=new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int from,int count,int after){}
            public void onTextChanged(CharSequence s,int from,int before,int count){if(!binding&&!unavailable()&&!submitting)clearPreview();}
            public void afterTextChanged(Editable value){}
        };
        List<EditText> fields=new ArrayList<>(settingsFields.values());fields.addAll(channels.values());fields.addAll(Arrays.asList(curve));fields.addAll(Arrays.asList(skin,nail,keyFrame,previewFrame));
        for(EditText[] finger:fingerFields.values())fields.addAll(Arrays.asList(finger));
        for(EditText field:fields)field.addTextChangedListener(edited);
        refresh();enable(poseControls,false);
    }

    private void show(){
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        dialog=new AlertDialog.Builder(activity).setTitle("Hand animation").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Create hand",null).create();
        dialog.setOnDismissListener(d->{closed=true;clearPreview();worker.shutdownNow();});dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{if(!submitting)dialog.cancel();});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(unavailable()||submitting)return;
            try{
                JSONObject request=request();clearPreview();setSubmitting(true);status.setText("Saving hand animation…");
                AtomicBoolean completed=new AtomicBoolean();Consumer<Exception> completion=error->{
                    if(!completed.compareAndSet(false,true))return;
                    activity.runOnUiThread(()->{if(unavailable())return;if(error==null){dialog.dismiss();return;}setSubmitting(false);status.setText("Hand not created: "+error.getMessage());});
                };
                try{create.submit(request,completion);}catch(Exception error){completion.accept(error);}
            }catch(Exception error){status.setText("Hand not created: "+error.getMessage());}
        });
    }

    private JSONObject readSettings()throws Exception {
        JSONObject settings=new JSONObject().put("view",VIEWS[view.getSelectedItemPosition()]).put("handedness",HANDS[handedness.getSelectedItemPosition()])
                .put("detail",DETAILS[detail.getSelectedItemPosition()]).put("renderStyle",RENDER_STYLES[renderStyle.getSelectedItemPosition()]).put("skinColor",skin.getText().toString().trim()).put("nailColor",nail.getText().toString().trim());
        for(Map.Entry<String,EditText> field:settingsFields.entrySet())settings.put(field.getKey(),number(field.getValue()));
        return SketchHandAnimation.settings(settings);
    }

    private JSONObject request()throws Exception {
        JSONObject settings=readSettings();if(custom.isChecked()){saveCurrent();refresh();}
        String id="hand_"+UUID.randomUUID().toString().replace("-","").substring(0,15);
        JSONObject operation=new JSONObject().put("type","add_hand_animation").put("layerId",id).put("handId",id).put("settings",settings);
        if(parts.getSelectedItemPosition()>0)operation.put("parts",PARTS[parts.getSelectedItemPosition()-1]);
        SketchHandAnimation.parts(operation,settings);
        if(custom.isChecked())operation.put("poses",new JSONArray(poses.toString()));
        return new JSONObject().put("operations",new JSONArray().put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Hand animation"))).put(operation));
    }

    private JSONObject readPose()throws Exception {
        JSONObject pose=new JSONObject().put("frame",frame(keyFrame));
        for(Map.Entry<String,EditText> channel:channels.entrySet())if(!channel.getValue().getText().toString().trim().isEmpty())pose.put(channel.getKey(),number(channel.getValue()));
        JSONObject fingers=new JSONObject();for(Map.Entry<String,EditText[]> entry:fingerFields.entrySet()){
            int entered=0;for(EditText field:entry.getValue())if(!field.getText().toString().trim().isEmpty())entered++;
            if(entered==0)continue;if(entered!=3)throw SketchDocument.bad("Fill all three "+entry.getKey()+" joint degrees or leave all blank");
            JSONArray triple=new JSONArray();for(EditText field:entry.getValue())triple.put(number(field));fingers.put(entry.getKey(),triple);
        }
        if(fingers.length()>0)pose.put("fingerJointAngles",fingers);SketchFingerJointAngles.validate(pose);
        int mode=easing.getSelectedItemPosition();if(mode>0)pose.put("interpolation",EASING[mode]);
        if("bezier".equals(EASING[mode])){JSONArray values=new JSONArray();for(EditText field:curve)values.put(number(field));pose.put("curve",values);}
        return pose;
    }

    private void saveCurrent()throws Exception {
        JSONObject edited=readPose();JSONArray candidate=new JSONArray();for(int i=0;i<poses.length();i++)candidate.put(i==selected?edited:poses.getJSONObject(i));
        candidate=sorted(candidate);SketchHandAnimation.validatePoses(candidate,document.getInt("frameCount"));
        poses=candidate;selected=indexOf(edited);clearPreview();
    }

    private void addPose()throws Exception {
        JSONObject added=readPose();if(poses.length()>=32)throw SketchDocument.bad("Use at most 32 hand poses");
        JSONArray candidate=new JSONArray();for(int i=0;i<poses.length();i++)candidate.put(poses.getJSONObject(i));candidate.put(added);candidate=sorted(candidate);
        SketchHandAnimation.validatePoses(candidate,document.getInt("frameCount"));poses=candidate;selected=indexOf(added);refresh();status.setText("Pose added; keys are ordered.");
    }

    private void deletePose()throws Exception {
        if(poses.getJSONObject(selected).getInt("frame")==0)throw SketchDocument.bad("Keep the frame-one starting pose");
        JSONArray candidate=new JSONArray();for(int i=0;i<poses.length();i++)if(i!=selected)candidate.put(poses.getJSONObject(i));
        SketchHandAnimation.validatePoses(candidate,document.getInt("frameCount"));poses=candidate;selected=Math.max(0,selected-1);refresh();status.setText("Pose deleted; remaining keys are retained.");
    }

    private void loadStudy()throws Exception {
        String pattern=SketchHandStudies.PATTERNS[study.getSelectedItemPosition()];int frames=document.getInt("frameCount"),fps=document.getInt("fps");
        JSONArray candidate=SketchHandStudies.poses(pattern,frames,fps,readSettings());
        poses=candidate;selected=0;custom.setChecked(true);refresh();enable(poseControls,true);
        status.setText("Loaded "+study.getSelectedItem()+" into Custom poses · "+frames+" frames / "+fps+" FPS. Settings, render style and canvas retained.");
    }

    private void resetPreset()throws Exception {
        JSONObject settings=readSettings();JSONArray candidate=SketchHandAnimation.preset(document.getInt("frameCount"),document.getInt("fps"),settings);
        SketchHandAnimation.validatePoses(candidate,document.getInt("frameCount"));poses=candidate;selected=0;custom.setChecked(false);refresh();enable(poseControls,false);status.setText("Native preset reset using the current settings. Enable Custom poses to edit its keys.");
    }

    private void refresh()throws Exception {
        clearPreview();binding=true;
        try{
            String[] labels=new String[poses.length()];for(int i=0;i<labels.length;i++)labels[i]="Frame "+(poses.getJSONObject(i).getInt("frame")+1);
            posePicker.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));posePicker.setSelection(selected);
            JSONObject pose=poses.getJSONObject(selected);keyFrame.setText(String.valueOf(pose.getInt("frame")+1));
            for(Map.Entry<String,EditText> channel:channels.entrySet())channel.getValue().setText(pose.has(channel.getKey())?String.valueOf(pose.get(channel.getKey())):"");
            JSONObject fingers=pose.optJSONObject("fingerJointAngles");for(Map.Entry<String,EditText[]> entry:fingerFields.entrySet()){JSONArray tuple=fingers==null?null:fingers.optJSONArray(entry.getKey());for(int j=0;j<3;j++)entry.getValue()[j].setText(tuple==null?"":String.valueOf(tuple.get(j)));}
            easing.setSelection(Math.max(0,Arrays.asList(EASING).indexOf(pose.optString("interpolation","Native default"))));
            JSONArray values=pose.optJSONArray("curve");for(int i=0;i<4;i++)curve[i].setText(values==null?(i<2?"0":"1"):String.valueOf(values.get(i)));
        }finally{binding=false;}
    }

    private void preview()throws Exception {
        int frame=frame(previewFrame);JSONObject request=request();clearPreview();int generation=++previewGeneration;status.setText("Preparing native hand preview…");
        previewTask=worker.submit(()->{
            try{
                if(Thread.currentThread().isInterrupted())return;
                JSONObject candidate=SketchDocument.copy(document);JSONArray operations=request.getJSONArray("operations");
                SketchEditOps.apply(candidate,operations.getJSONObject(0));SketchHandAnimation.apply(candidate,operations.getJSONObject(1));
                SketchDocument.validate(candidate);if(Thread.currentThread().isInterrupted())return;
                final Bitmap image;try(SketchFrameRenderer renderer=new SketchFrameRenderer(activity,candidate)){image=renderer.render(frame);}
                if(Thread.currentThread().isInterrupted()){image.recycle();return;}
                activity.runOnUiThread(()->{
                    if(unavailable()||generation!=previewGeneration){image.recycle();return;}
                    Bitmap old=previewBitmap;previewBitmap=image;preview.setImageBitmap(image);if(old!=null)old.recycle();
                    status.setText("Preview · "+operations.optJSONObject(1).optJSONObject("settings").optString("view")+" view · frame "+(frame+1)+". Drawing unchanged.");
                    preview.post(()->{if(!unavailable())preview.requestRectangleOnScreen(new Rect(0,0,preview.getWidth(),preview.getHeight()),true);});
                });
            }catch(Exception error){activity.runOnUiThread(()->{if(!unavailable()&&generation==previewGeneration)status.setText("Preview unavailable: "+error.getMessage());});}
        });
    }

    private void clearPreview(){
        previewGeneration++;if(previewTask!=null){previewTask.cancel(true);previewTask=null;}
        preview.setImageDrawable(null);if(previewBitmap!=null){previewBitmap.recycle();previewBitmap=null;}
    }

    private boolean unavailable(){return closed||activity.isFinishing()||activity.isDestroyed();}
    private void setSubmitting(boolean saving){
        submitting=saving;enable(form,!saving);if(!saving)enable(poseControls,custom.isChecked());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!saving);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!saving);dialog.setCancelable(!saving);dialog.setCanceledOnTouchOutside(!saving);
    }
    private static void enable(View control,boolean enabled){control.setEnabled(enabled);if(control instanceof ViewGroup){ViewGroup group=(ViewGroup)control;for(int i=0;i<group.getChildCount();i++)enable(group.getChildAt(i),enabled);}}
    private static JSONArray sorted(JSONArray values)throws Exception {List<JSONObject> list=new ArrayList<>();for(int i=0;i<values.length();i++)list.add(values.getJSONObject(i));list.sort(Comparator.comparingInt(p->p.optInt("frame")));return new JSONArray(list);}
    private int indexOf(JSONObject pose)throws Exception {for(int i=0;i<poses.length();i++)if(poses.getJSONObject(i)==pose)return i;throw new IllegalStateException("Hand pose selection lost");}
    private int frame(EditText field)throws Exception {
        int value;try{value=Integer.parseInt(field.getText().toString().trim());}catch(NumberFormatException error){throw SketchDocument.bad("Enter a whole frame inside the existing sketch timeline");}
        if(value<1||value>document.getInt("frameCount"))throw SketchDocument.bad("Choose a frame inside the existing sketch timeline");return value-1;
    }
    private static double number(EditText field)throws Exception {
        try{double value=Double.parseDouble(field.getText().toString().trim());if(Double.isFinite(value))return value;}catch(NumberFormatException ignored){}
        throw SketchDocument.bad("Enter a finite number for "+field.getContentDescription());
    }
    private EditText input(LinearLayout row,String label,String value,boolean decimal){
        TextView title=new TextView(activity);title.setText(label);row.addView(title);EditText field=new EditText(activity);field.setContentDescription(label);field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));field.setText(value);row.addView(field);return field;
    }
    private Spinner spinner(LinearLayout row,String label,String[] values){TextView title=new TextView(activity);title.setText(label);row.addView(title);Spinner control=new Spinner(activity);control.setContentDescription(label);control.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values));row.addView(control);return control;}
    private interface Action {void run()throws Exception;}
    private void button(LinearLayout row,String label,Action action){Button button=new Button(activity);button.setText(label);row.addView(button);button.setOnClickListener(v->{if(unavailable()||submitting)return;try{action.run();}catch(Exception error){status.setText("Hand unchanged: "+error.getMessage());}});}
}
