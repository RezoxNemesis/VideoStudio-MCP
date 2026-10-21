package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Manual complete-face authoring through the shared editable native operation. */
final class SketchFaceDialog {
    private static final String[] SHAPES={"oval","round","angular"};
    private static final String[] EYES={"round","almond","slit"};
    private static final String[] MOUTHS={"soft","cartoon","fang"};
    private static final String[] PRESETS={"reaction","playful","chew"};
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    private static final String[] VIEW_LABELS={"Front","Left","Right","Back (facial features hidden)","High","Low"};
    private static final String[] CHANNEL_NAMES={"Horizontal gaze","Vertical gaze","Left eyelid opening","Right eyelid opening","Pupil dilation","Near focus","Mouth opening","Mouth width","Smile / frown","Jaw horizontal offset","Tongue extension","Upper teeth","Lower teeth","Lip roundness","Brow raise","Brow tilt"};
    private static final double[] MINIMUM={-1,-1,0,0,.5,0,0,.5,-1,-1,0,0,0,0,-1,-1};
    private static final double[] STEP={.01,.01,.01,.01,.01,.005,.01,.01,.01,.01,.01,.01,.01,.01,.01,.01};
    private static final Set<String> COLOURS=Set.of("skinColor","hairColor","irisColor","lipColor","ink");
    private final Activity activity;
    private final JSONObject document;
    private final SketchConstructionSubmit create;
    private final LinearLayout form;
    private final TextView status,previewContext;
    private final Spinner shape,eyeStyle,mouthStyle,motion,posePicker,faceView;
    private final CheckBox hair;
    private final EditText frame;
    private final LinkedHashMap<String,EditText> fields=new LinkedHashMap<>();
    private final SeekBar[] sliders=new SeekBar[16];
    private final TextView[] readings=new TextView[16];
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ImageView preview;
    private JSONArray poses;
    private int selected,presetIndex,previewGeneration;
    private boolean binding,closed,submitting;
    private Bitmap previewBitmap;
    private Future<?> previewTask;

    static void show(Activity activity,JSONObject document,Consumer<JSONObject> create)throws Exception {
        new SketchFaceDialog(activity,document,create).show();
    }

    static void show(Activity activity,JSONObject document,SketchConstructionSubmit create)throws Exception {
        new SketchFaceDialog(activity,document,create).show();
    }

    private SketchFaceDialog(Activity activity,JSONObject document,Consumer<JSONObject> create)throws Exception {
        this(activity,document,SketchConstructionSubmit.from(create));
    }

    private SketchFaceDialog(Activity activity,JSONObject document,SketchConstructionSubmit create)throws Exception {
        this.activity=activity;this.document=SketchDocument.copy(document);this.create=create;
        int frames=document.getInt("frameCount");
        if(frames<24||frames>240)throw SketchDocument.bad("Face animation supports sketches with 24–240 frames");
        form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,12,20,12);
        status=new TextView(activity);status.setContentDescription("Face animation status");
        status.setText("Create an editable face on a new layer. Adjust the head, eyes, nose, mouth and brows, then edit gaze and expression poses. Current sketch: "+frames+" frames at "+document.getInt("fps")+" FPS. Supports 24–240 frames.");form.addView(status);
        JSONObject defaults=SketchFaceAnimation.defaults();
        shape=spinner("Face shape",new String[]{"Oval","Round","Angular"});shape.setSelection(Arrays.asList(SHAPES).indexOf(defaults.getString("shape")));
        eyeStyle=spinner("Eye shape",new String[]{"Round / cartoon","Almond","Slit pupil / creature"});eyeStyle.setSelection(Arrays.asList(EYES).indexOf(defaults.getString("eyeStyle")));
        mouthStyle=spinner("Mouth shape",new String[]{"Soft lips","Cartoon","Fangs / creature"});mouthStyle.setSelection(Arrays.asList(MOUTHS).indexOf(defaults.getString("mouthStyle")));
        faceView=spinner("Face view",VIEW_LABELS);faceView.setSelection(0);
        motion=spinner("Face motion",new String[]{"Reaction / gaze / expression","Playful / wink / tongue","Chewing"});
        String[][] geometry={{"centerX","Centre X (%)"},{"centerY","Centre Y (%)"},{"width","Face width (%)"},{"height","Face height (%)"},{"jawWidth","Jaw width (%)"},{"noseWidth","Nose width (%)"},{"noseLength","Nose length (%)"},{"eyeSize","Eye size (%)"},{"mouthSize","Mouth size (%)"},{"outlinePx","Outline width (px)"},{"tilt","Face tilt (degrees)"}};
        for(String[] entry:geometry)input(entry[0],entry[1],String.valueOf(defaults.getDouble(entry[0])*(absolute(entry[0])?1:100)));
        input("skinColor","Skin colour",defaults.getString("skinColor"));
        input("hairColor","Hair colour",defaults.getString("hairColor"));
        input("irisColor","Iris colour",defaults.getString("irisColor"));
        input("lipColor","Lip colour",defaults.getString("lipColor"));
        input("ink","Outline colour",defaults.getString("ink"));
        hair=new CheckBox(activity);hair.setText("Hair");hair.setContentDescription("Hair");hair.setChecked(defaults.getBoolean("hair"));form.addView(hair);
        poses=SketchFaceAnimation.preset(this.document,PRESETS[0]);posePicker=spinner("Face pose",new String[]{"Pose"});
        frame=input("newFrame","New key frame (1 based)","1");
        LinearLayout buttons=new LinearLayout(activity);form.addView(buttons);button(buttons,"Add / select key",this::addKey);button(buttons,"Delete key",this::deleteKey);
        for(int i=0;i<sliders.length;i++){
            final int channel=i;readings[i]=new TextView(activity);form.addView(readings[i]);
            sliders[i]=new SeekBar(activity);sliders[i].setContentDescription(CHANNEL_NAMES[i]);sliders[i].setMax(MINIMUM[i]<0?200:100);form.addView(sliders[i]);
            sliders[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onStartTrackingTouch(SeekBar bar){}
                public void onStopTrackingTouch(SeekBar bar){}
                public void onProgressChanged(SeekBar bar,int progress,boolean user){
                    if(binding||submitting)return;
                    try{double value=Math.round((MINIMUM[channel]+progress*STEP[channel])*1000)/1000d;
                        poses.getJSONObject(selected).put(SketchFaceAnimation.CHANNELS[channel],value);reading(channel,value);
                    }catch(Exception error){status.setText(error.getMessage());}
                }
            });
        }
        preview=new ImageView(activity);preview.setContentDescription("Face pose preview");preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewContext=new TextView(activity);previewContext.setContentDescription("Face preview context");form.addView(previewContext);updatePreviewContext();
        button(form,"Preview pose",this::preview);form.addView(preview,new LinearLayout.LayoutParams(-1,(int)(220*activity.getResources().getDisplayMetrics().density)));
        faceView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){if(submitting)return;clearPreview();updatePreviewContext();}
        });
        posePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){if(binding||submitting)return;selected=position;loadPose();}
        });
        motion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(submitting||position==presetIndex)return;
                try{JSONArray next=SketchFaceAnimation.preset(SketchFaceDialog.this.document,PRESETS[position]);presetIndex=position;poses=next;selected=0;refresh();}
                catch(Exception error){status.setText(error.getMessage());}
            }
        });
        refresh();
    }

    private void show(){
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Face animation").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Create face",null).create();
        dialog.setOnDismissListener(d->{closed=true;clearPreview();worker.shutdownNow();});
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view->{if(!submitting)dialog.cancel();});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            if(submitting||closed)return;
            try{
                JSONObject settings=readSettings();SketchFaceAnimation.validatePoses(poses,document.getInt("frameCount"));
                String id="face_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
                JSONObject layer=new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Face animation"));
                JSONObject face=operation(id,settings,new JSONArray(poses.toString()),PRESETS[presetIndex]);
                beginSubmit(dialog,new JSONObject().put("operations",new JSONArray().put(layer).put(face)));
            }catch(Exception error){status.setText("Face not created: "+error.getMessage());}
        });
    }

    private void beginSubmit(AlertDialog dialog,JSONObject request){
        clearPreview();setSubmitting(dialog,true);status.setText("Saving face animation…");
        java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<Exception> completion=error->{
            if(!completed.compareAndSet(false,true))return;
            activity.runOnUiThread(()->{
                if(closed)return;
                if(error==null){closed=true;dialog.dismiss();return;}
                setSubmitting(dialog,false);status.setText("Face not created: "+error.getMessage());
            });
        };
        try{create.submit(request,completion);}catch(Exception error){completion.accept(error);}
    }

    private void setSubmitting(AlertDialog dialog,boolean saving){
        submitting=saving;setControlsEnabled(form,!saving);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!saving);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!saving);
        dialog.setCancelable(!saving);dialog.setCanceledOnTouchOutside(!saving);
    }

    private static void setControlsEnabled(View view,boolean enabled){
        view.setEnabled(enabled);
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)setControlsEnabled(group.getChildAt(i),enabled);}
    }

    private JSONObject readSettings()throws Exception {
        JSONObject settings=new JSONObject().put("shape",SHAPES[shape.getSelectedItemPosition()]).put("eyeStyle",EYES[eyeStyle.getSelectedItemPosition()])
                .put("mouthStyle",MOUTHS[mouthStyle.getSelectedItemPosition()]).put("view",VIEWS[faceView.getSelectedItemPosition()]).put("hair",hair.isChecked());
        for(Map.Entry<String,EditText> field:fields.entrySet()){
            String key=field.getKey(),text=field.getValue().getText().toString().trim();if("newFrame".equals(key))continue;
            if(COLOURS.contains(key))settings.put(key,text);
            else settings.put(key,Double.parseDouble(text)/(absolute(key)?1:100));
        }
        SketchFaceAnimation.settings(settings);return settings;
    }

    private JSONObject operation(String id,JSONObject settings,JSONArray keys,String preset)throws Exception {
        return new JSONObject().put("type","add_face_animation").put("layerId",id).put("faceId",id).put("settings",settings).put("preset",preset).put("poses",keys);
    }

    private void preview()throws Exception {
        JSONObject settings=readSettings();JSONArray keys=new JSONArray(poses.toString());SketchFaceAnimation.validatePoses(keys,document.getInt("frameCount"));
        int frame=keys.getJSONObject(selected).getInt("frame"),generation=++previewGeneration;String preset=PRESETS[presetIndex];
        if(previewTask!=null)previewTask.cancel(true);status.setText("Preparing face pose preview…");
        previewTask=worker.submit(()->{
            try{
                if(Thread.currentThread().isInterrupted())return;
                JSONObject candidate=SketchDocument.copy(document);String id="preview_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
                SketchEditOps.apply(candidate,new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Face preview")));
                SketchFaceAnimation.apply(candidate,operation(id,settings,keys,preset));
                if(Thread.currentThread().isInterrupted())return;
                final Bitmap image;try(SketchFrameRenderer renderer=new SketchFrameRenderer(activity,candidate)){image=renderer.render(frame);}
                if(Thread.currentThread().isInterrupted()){image.recycle();return;}
                activity.runOnUiThread(()->{
                    if(closed||generation!=previewGeneration||activity.isFinishing()||activity.isDestroyed()){image.recycle();return;}
                    Bitmap old=previewBitmap;previewBitmap=image;preview.setImageBitmap(image);if(old!=null)old.recycle();
                    status.setText("Preview · "+settings.optString("view","front")+" view · frame "+(frame+1)+". Drawing unchanged.");
                    preview.post(()->{if(!closed)preview.requestRectangleOnScreen(new Rect(0,0,preview.getWidth(),preview.getHeight()),true);});
                });
            }catch(Exception error){activity.runOnUiThread(()->{if(!closed&&generation==previewGeneration)status.setText("Preview unavailable: "+error.getMessage());});}
        });
    }

    private void clearPreview(){
        previewGeneration++;if(previewTask!=null)previewTask.cancel(true);preview.setImageDrawable(null);
        if(previewBitmap!=null){previewBitmap.recycle();previewBitmap=null;}
    }

    private void updatePreviewContext(){
        int index=faceView.getSelectedItemPosition();
        previewContext.setText(index==3?"Back view. Eyes, nose, mouth and brows are hidden because the head occludes them from behind.":VIEW_LABELS[index]+" view. Preview uses the selected face view.");
    }

    private void addKey()throws Exception {
        int value=Integer.parseInt(frame.getText().toString().trim())-1;
        if(value<0||value>=document.getInt("frameCount"))throw SketchDocument.bad("Choose a key inside the sketch");
        for(int i=0;i<poses.length();i++)if(poses.getJSONObject(i).getInt("frame")==value){selected=i;refresh();return;}
        if(poses.length()>=240)throw SketchDocument.bad("Use at most 240 face pose keys");
        double[] state=SketchFaceAnimation.sample(poses,value);
        JSONObject key=SketchFaceAnimation.pose(value,state);
        poses.put(key);poses=SketchAnimationUiModel.sortedFrames(poses);
        for(int i=0;i<poses.length();i++)if(poses.getJSONObject(i).getInt("frame")==value)selected=i;
        refresh();
    }

    private void deleteKey()throws Exception {
        if(poses.getJSONObject(selected).getInt("frame")==0)throw SketchDocument.bad("Keep the frame-one starting pose");
        JSONArray next=new JSONArray();for(int i=0;i<poses.length();i++)if(i!=selected)next.put(poses.get(i));
        poses=next;selected=Math.max(0,selected-1);refresh();
    }

    private void refresh()throws Exception {
        binding=true;
        try{String[] names=new String[poses.length()];for(int i=0;i<names.length;i++)names[i]="Frame "+(poses.getJSONObject(i).getInt("frame")+1);
            posePicker.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,names));posePicker.setSelection(selected);
        }finally{binding=false;}
        loadPose();
    }

    private void loadPose(){
        try{
            binding=true;int keyFrame=poses.getJSONObject(selected).getInt("frame");frame.setText(String.valueOf(keyFrame+1));
            double[] state=SketchFaceAnimation.sample(poses,keyFrame);
            for(int i=0;i<sliders.length;i++){double value=state[i];sliders[i].setProgress((int)Math.round((value-MINIMUM[i])/STEP[i]));reading(i,value);}
        }catch(Exception error){status.setText(error.getMessage());}finally{binding=false;}
    }

    private void reading(int channel,double value){readings[channel].setText(CHANNEL_NAMES[channel]+": "+Math.round(value*100)+"%");}
    private static boolean absolute(String key){return "outlinePx".equals(key)||"tilt".equals(key);}
    private EditText input(String key,String label,String value){
        TextView title=new TextView(activity);title.setText(label);form.addView(title);
        EditText field=new EditText(activity);field.setContentDescription(label);field.setSingleLine(true);field.setText(value);
        if(!COLOURS.contains(key))field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        form.addView(field);fields.put(key,field);return field;
    }
    private Spinner spinner(String label,String[] options){
        TextView title=new TextView(activity);title.setText(label);form.addView(title);Spinner spinner=new Spinner(activity);spinner.setContentDescription(label);
        spinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,options));form.addView(spinner);return spinner;
    }
    private interface Action{void run()throws Exception;}
    private void button(LinearLayout row,String label,Action action){Button button=new Button(activity);button.setText(label);row.addView(button);button.setOnClickListener(view->{if(submitting||closed)return;try{action.run();}catch(Exception error){status.setText(error.getMessage());}});}
}
