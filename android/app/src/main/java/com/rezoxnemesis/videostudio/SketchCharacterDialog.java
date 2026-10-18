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

/** Manual character performance and optional speech through shared native construction. */
final class SketchCharacterDialog {
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    private static final String[] BUILDS={"slender","broad"},MATERIALS={"wool","leather"},DETAILS={"contour","tailored"},GESTURES={"pendant","explain"};
    private final Activity activity;
    private final JSONObject document;
    private final SketchConstructionSubmit create;
    private final LinearLayout form,speechControls;
    private final TextView status,previewContext;
    private final Spinner view,build,material,detail,gesture,cuePicker,cueShape;
    private final CheckBox timedSpeech;
    private final EditText armLift,headTilt,skinColor,hairColor,coatColor,previewFrame;
    private final EditText transition,start,end,strength;
    private final ImageView preview;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private JSONArray cues;
    private int selected,previewGeneration;
    private boolean binding,closed,submitting;
    private Bitmap previewBitmap;
    private Future<?> previewTask,loadTask;
    private AlertDialog dialog,sourcePicker;
    private boolean loading;

    static void show(Activity activity,JSONObject document,SketchConstructionSubmit create)throws Exception {
        new SketchCharacterDialog(activity,document,create).show();
    }

    private SketchCharacterDialog(Activity activity,JSONObject document,SketchConstructionSubmit create)throws Exception {
        this.activity=activity;this.document=SketchDocument.copy(document);this.create=create;
        int frames=document.getInt("frameCount"),fps=document.getInt("fps");
        double seconds=frames/(double)fps;
        if(frames<24||frames>240||(fps!=24&&fps!=30&&fps!=60)||seconds<4||seconds>8)
            throw SketchDocument.bad("Character performance needs 4–8 seconds, 24–240 frames and 24/30/60 FPS; use Timing / canvas first");
        form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,12,20,12);
        status=new TextView(activity);status.setContentDescription("Character performance status");status.setText("Character ready. Speech is optional; preview is silent.");
        TextView description=new TextView(activity);description.setContentDescription("Character performance description");
        description.setText("Create an original travelling scholar on a new editable layer. Six authored 2D views; these are not a 3D model. Current sketch: "+frames+" frames at "+fps+" FPS. Timing and canvas stay unchanged.\nFor speech, generate English or Hindi in Open Pipeline, import the completed dialogue, then load its line below. Preview and Sketch export are silent. Align the animation with that project's original audio at the same timeline offset in the main editor. Model timing is an estimate; review and edit against the audio.");form.addView(description);
        view=spinner(form,"Character view",new String[]{"Front","Left","Right","Back (front features hidden)","High","Low"});
        build=spinner(form,"Character build",new String[]{"Slender","Broad"});
        material=spinner(form,"Coat material",new String[]{"Wool","Leather"});
        detail=spinner(form,"Character detail",new String[]{"Contour","Tailored"});detail.setSelection(1);
        gesture=spinner(form,"Character gesture",new String[]{"Hold pendant","Explain"});
        armLift=input(form,"Arm lift (0..1)","0.8",true);
        headTilt=input(form,"Head tilt (-12..12 degrees)","0",true);
        skinColor=colour("Skin colour (ARGB)","#FFD6A078");hairColor=colour("Hair colour (ARGB)","#FF252D3D");coatColor=colour("Coat colour (ARGB)","#FF263847");
        timedSpeech=new CheckBox(activity);timedSpeech.setText("Timed speech");timedSpeech.setContentDescription("Timed speech");form.addView(timedSpeech);
        speechControls=new LinearLayout(activity);speechControls.setOrientation(LinearLayout.VERTICAL);form.addView(speechControls);
        button(speechControls,"Load generated dialogue",this::loadGenerated);
        TextView speechHelp=new TextView(activity);speechHelp.setText("Initial cues are an authored demonstration, not audio alignment. Loading a line replaces them with editable model timing; no speech recognition or synthesis happens here.");speechControls.addView(speechHelp);
        transition=input(speechControls,"Transition (ms)","60",true);
        TextView help=new TextView(activity);help.setText("Use 1–240 cues; start is inclusive and end is exclusive. Whole milliseconds from sketch start; gaps rest. Transition: 0–200 ms; strength: 0–1. Edit the selected cue and apply, or enter a new interval and add it. Cues stay sorted; overlaps are rejected. Use the selected cue's frame to inspect speech; short cues may fall between frames.");speechControls.addView(help);
        cues=demonstration();cuePicker=spinner(speechControls,"Speech cue",new String[]{"Cue"});
        start=input(speechControls,"Cue start (ms)","0",false);end=input(speechControls,"Cue end (ms)","1",false);
        cueShape=spinner(speechControls,"Cue shape",SketchSpeechTiming.SHAPES);
        strength=input(speechControls,"Cue strength (0..1)","1",true);
        button(speechControls,"Apply cue edits",()->{saveCurrent();refresh();status.setText("Cue edits applied. Gaps rest.");});
        button(speechControls,"Add cue",this::addCue);button(speechControls,"Delete cue",this::deleteCue);
        speechControls.setVisibility(View.GONE);
        previewFrame=input(form,"Preview frame (1 based)",String.valueOf(frames/2+1),false);
        button(speechControls,"Use selected cue frame",()->{
            saveCurrent();JSONObject cue=cues.getJSONObject(selected);
            int frame=Math.min(document.getInt("frameCount")-1,(int)Math.floor((cue.getDouble("startMs")+cue.getDouble("endMs"))*document.getInt("fps")/2000d));
            previewFrame.setText(String.valueOf(frame+1));status.setText("Cue frame selected. Use Preview character to render it.");
        });
        previewContext=new TextView(activity);previewContext.setContentDescription("Character preview context");form.addView(previewContext);
        preview=new ImageView(activity);preview.setContentDescription("Character preview");preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button(form,"Preview character",this::preview);form.addView(preview,new LinearLayout.LayoutParams(-1,(int)(220*activity.getResources().getDisplayMetrics().density)));
        form.addView(status);
        cuePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View child,int position,long id){
                if(binding||closed||submitting||position==selected)return;
                try{JSONObject next=cues.getJSONObject(position);saveCurrent();selected=indexOf(next);refresh();}
                catch(Exception error){binding=true;cuePicker.setSelection(selected);binding=false;status.setText("Character unchanged: "+error.getMessage());}
            }
        });
        timedSpeech.setOnCheckedChangeListener((control,checked)->{if(closed||submitting)return;clearPreview();speechControls.setVisibility(checked?View.VISIBLE:View.GONE);updatePreviewContext();});
        view.setOnItemSelectedListener(changed(this::updatePreviewContext));
        for(Spinner control:new Spinner[]{build,material,detail,gesture,cueShape})control.setOnItemSelectedListener(changed(()->{}));
        TextWatcher edited=new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int from,int count,int after){}
            public void onTextChanged(CharSequence s,int from,int before,int count){if(!binding&&!closed&&!submitting)clearPreview();}
            public void afterTextChanged(Editable value){}
        };
        for(EditText control:new EditText[]{armLift,headTilt,skinColor,hairColor,coatColor,previewFrame,transition,start,end,strength})control.addTextChangedListener(edited);
        refresh();updatePreviewContext();
    }

    private void show(){
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        dialog=new AlertDialog.Builder(activity).setTitle("Character performance").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Create character",null).create();
        dialog.setOnDismissListener(d->{closed=true;if(sourcePicker!=null)sourcePicker.dismiss();if(loadTask!=null)loadTask.cancel(true);clearPreview();worker.shutdownNow();});dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{if(!submitting)dialog.cancel();});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(submitting||closed||loading)return;
            try{
                JSONObject settings=readSettings();if(timedSpeech.isChecked())saveCurrent();String id="char_"+UUID.randomUUID().toString().replace("-","").substring(0,15);
                JSONObject layer=new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Character performance"));
                JSONObject request=new JSONObject().put("operations",new JSONArray().put(layer).put(operation(id,settings)));
                clearPreview();setSubmitting(dialog,true);status.setText("Saving character performance…");
                AtomicBoolean completed=new AtomicBoolean();Consumer<Exception> completion=error->{
                    if(!completed.compareAndSet(false,true))return;
                    activity.runOnUiThread(()->{if(closed)return;if(error==null){dialog.dismiss();return;}setSubmitting(dialog,false);status.setText("Character not created: "+error.getMessage());});
                };
                try{create.submit(request,completion);}catch(Exception error){completion.accept(error);}
            }catch(Exception error){status.setText("Character not created: "+error.getMessage());}
        });
    }

    private double durationMs()throws Exception{return document.getInt("frameCount")*1000d/document.getInt("fps");}

    private void loadGenerated(){
        clearPreview();setLoading(true);status.setText("Finding imported generated dialogue…");
        loadTask=worker.submit(()->{
            try{
                JSONArray choices=SketchSpeechAssets.choices(activity);
                activity.runOnUiThread(()->{
                    if(unavailable())return;
                    if(choices.length()==0){setLoading(false);status.setText("No imported generated dialogue yet. Generate English or Hindi in Open Pipeline and import its completed result, then return here.");return;}
                    String[] labels=new String[choices.length()];for(int i=0;i<labels.length;i++)labels[i]=choices.optJSONObject(i).optString("label");
                    sourcePicker=new AlertDialog.Builder(activity).setTitle("Choose imported spoken line").setItems(labels,(picker,index)->{
                        JSONObject choice=choices.optJSONObject(index);status.setText("Checking the original dialogue and audio…");
                        loadTask=worker.submit(()->{
                            try{
                                JSONObject loaded=SketchSpeechAssets.load(activity,choice.getString("requestId"),choice.getInt("lineIndex"),durationMs());
                                activity.runOnUiThread(()->{
                                    if(unavailable())return;
                                    try{JSONObject speech=loaded.getJSONObject("speech");cues=speech.getJSONArray("cues");selected=0;
                                        transition.setText(String.valueOf(speech.get("transitionMs")));refresh();setLoading(false);
                                        status.setText("Loaded: "+loaded.getString("text")+"\nModel timing is an estimate. Edit cues against the original audio, which stays in the generated dialogue project's audio lane at the same timeline offset.");
                                    }catch(Exception error){loadFailed(error);}
                                });
                            }catch(Exception error){activity.runOnUiThread(()->loadFailed(error));}
                        });
                    }).setNegativeButton("Cancel",(picker,which)->{if(!unavailable()){setLoading(false);status.setText("Current speech cues retained.");}})
                            .setOnCancelListener(picker->{if(!unavailable()){setLoading(false);status.setText("Current speech cues retained.");}}).create();sourcePicker.show();
                });
            }catch(Exception error){activity.runOnUiThread(()->loadFailed(error));}
        });
    }
    private boolean unavailable(){return closed||activity.isFinishing()||activity.isDestroyed();}
    private void loadFailed(Exception error){if(unavailable())return;setLoading(false);status.setText("Dialogue not loaded; current cues retained: "+error.getMessage());}
    private void setLoading(boolean busy){loading=busy;enable(form,!busy);if(dialog!=null)dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);}

    private JSONArray demonstration()throws Exception {
        double duration=durationMs();double[][] intervals={{.125,.1625},{.18,.26},{.26,.34},{.40,.50},{.50,.59},{.64,.74}};
        JSONArray result=new JSONArray();
        for(int i=0;i<intervals.length;i++)result.put(new JSONObject().put("startMs",Math.round(duration*intervals[i][0])).put("endMs",Math.round(duration*intervals[i][1])).put("shape",SketchSpeechTiming.SHAPES[i+1]).put("strength",1));
        return result;
    }

    private JSONObject readCue()throws Exception {
        try{return new JSONObject().put("startMs",Long.parseLong(start.getText().toString().trim())).put("endMs",Long.parseLong(end.getText().toString().trim()))
                .put("shape",SketchSpeechTiming.SHAPES[cueShape.getSelectedItemPosition()]).put("strength",Double.parseDouble(strength.getText().toString().trim()));}
        catch(NumberFormatException error){throw SketchDocument.bad("Enter whole start/end milliseconds and a numeric strength from 0 to 1");}
    }

    private JSONObject speech(JSONArray values)throws Exception {
        final double ms;
        try{ms=Double.parseDouble(transition.getText().toString().trim());}catch(NumberFormatException error){throw SketchDocument.bad("Enter a transition from 0 to 200 ms");}
        JSONObject speech=new JSONObject().put("transitionMs",ms).put("cues",new JSONArray(values.toString()));
        SketchSpeechTiming.validate(speech,durationMs());return speech;
    }

    private void saveCurrent()throws Exception {
        JSONObject edited=readCue();JSONArray candidate=new JSONArray();
        for(int i=0;i<cues.length();i++)candidate.put(i==selected?edited:cues.getJSONObject(i));
        candidate=sorted(candidate);speech(candidate);cues=candidate;selected=indexOf(edited);clearPreview();
    }

    private void addCue()throws Exception {
        JSONObject added=readCue();
        for(int i=0;i<cues.length();i++)if(cues.getJSONObject(i).getLong("startMs")==added.getLong("startMs")){
            selected=i;refresh();status.setText("Selected existing cue. Apply cue edits to change it.");return;
        }
        if(cues.length()>=240)throw SketchDocument.bad("Use at most 240 speech cues");
        JSONArray candidate=new JSONArray();for(int i=0;i<cues.length();i++)candidate.put(cues.getJSONObject(i));candidate.put(added);candidate=sorted(candidate);
        speech(candidate);cues=candidate;selected=indexOf(added);refresh();status.setText("Cue added; gaps rest.");
    }

    private void deleteCue()throws Exception {
        if(cues.length()==1)throw SketchDocument.bad("Keep at least one cue; choose rest for silence");
        JSONArray candidate=new JSONArray();for(int i=0;i<cues.length();i++)if(i!=selected)candidate.put(cues.getJSONObject(i));
        speech(candidate);cues=candidate;selected=Math.min(selected,cues.length()-1);refresh();status.setText("Cue deleted; uncovered time rests.");
    }

    private static JSONArray sorted(JSONArray values)throws Exception {
        List<JSONObject> list=new ArrayList<>();for(int i=0;i<values.length();i++)list.add(values.getJSONObject(i));
        list.sort(Comparator.comparingLong(c->c.optLong("startMs")));return new JSONArray(list);
    }

    private int indexOf(JSONObject cue)throws Exception {for(int i=0;i<cues.length();i++)if(cues.getJSONObject(i)==cue)return i;throw new IllegalStateException("Cue selection lost");}

    private void refresh()throws Exception {
        clearPreview();binding=true;
        try{
            String[] labels=new String[cues.length()];for(int i=0;i<labels.length;i++){JSONObject cue=cues.getJSONObject(i);labels[i]=(i+1)+" · "+cue.getLong("startMs")+"–"+cue.getLong("endMs")+" ms · "+cue.getString("shape")+" · "+cue.optDouble("strength",1);}
            cuePicker.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));cuePicker.setSelection(selected);
            JSONObject cue=cues.getJSONObject(selected);start.setText(String.valueOf(cue.getLong("startMs")));end.setText(String.valueOf(cue.getLong("endMs")));
            cueShape.setSelection(Arrays.asList(SketchSpeechTiming.SHAPES).indexOf(cue.getString("shape")));strength.setText(String.valueOf(cue.optDouble("strength",1)));
        }finally{binding=false;}
    }

    private JSONObject readSettings()throws Exception {
        JSONObject settings=new JSONObject().put("view",VIEWS[view.getSelectedItemPosition()]).put("build",BUILDS[build.getSelectedItemPosition()])
                .put("material",MATERIALS[material.getSelectedItemPosition()]).put("detail",DETAILS[detail.getSelectedItemPosition()]).put("gesture",GESTURES[gesture.getSelectedItemPosition()]);
        try{settings.put("armLift",Double.parseDouble(armLift.getText().toString().trim())).put("headTilt",Double.parseDouble(headTilt.getText().toString().trim()));}
        catch(NumberFormatException error){throw SketchDocument.bad("Enter numeric arm lift (0..1) and head tilt (-12..12 degrees)");}
        settings.put("skinColor",skinColor.getText().toString().trim()).put("hairColor",hairColor.getText().toString().trim()).put("coatColor",coatColor.getText().toString().trim());
        SketchCharacterAnimation.settings(settings);return settings;
    }

    private JSONObject operation(String id,JSONObject settings)throws Exception {
        JSONObject op=new JSONObject().put("type","add_character_animation").put("layerId",id).put("characterId",id).put("settings",settings);
        if(timedSpeech.isChecked())op.put("speech",speech(cues));return op;
    }

    private void preview()throws Exception {
        JSONObject settings=readSettings();if(timedSpeech.isChecked())saveCurrent();
        String id="char_"+UUID.randomUUID().toString().replace("-","").substring(0,15);JSONObject op=operation(id,settings);
        final int frame;
        try{frame=Integer.parseInt(previewFrame.getText().toString().trim())-1;}catch(NumberFormatException error){throw SketchDocument.bad("Enter a whole preview frame");}
        if(frame<0||frame>=document.getInt("frameCount"))throw SketchDocument.bad("Preview frame must be inside the unchanged sketch timeline");
        clearPreview();
        int generation=++previewGeneration;status.setText("Preparing native character preview…");
        previewTask=worker.submit(()->{
            try{
                if(Thread.currentThread().isInterrupted())return;
                JSONObject candidate=SketchDocument.copy(document);
                SketchEditOps.apply(candidate,new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Character preview")));
                SketchCharacterAnimation.apply(candidate,op);SketchDocument.validate(candidate);if(Thread.currentThread().isInterrupted())return;
                final Bitmap image;try(SketchFrameRenderer renderer=new SketchFrameRenderer(activity,candidate)){image=renderer.render(frame);}
                if(Thread.currentThread().isInterrupted()){image.recycle();return;}
                activity.runOnUiThread(()->{
                    if(closed||generation!=previewGeneration||activity.isFinishing()||activity.isDestroyed()){image.recycle();return;}
                    Bitmap old=previewBitmap;previewBitmap=image;preview.setImageBitmap(image);if(old!=null)old.recycle();
                    status.setText("Preview · "+op.optJSONObject("settings").optString("view")+" view · frame "+(frame+1)+". Drawing unchanged. No audio playback.");
                    preview.post(()->{if(!closed)preview.requestRectangleOnScreen(new Rect(0,0,preview.getWidth(),preview.getHeight()),true);});
                });
            }catch(Exception error){activity.runOnUiThread(()->{if(!closed&&generation==previewGeneration)status.setText("Preview unavailable: "+error.getMessage());});}
        });
    }

    private void clearPreview(){
        previewGeneration++;if(previewTask!=null){previewTask.cancel(true);previewTask=null;}
        preview.setImageDrawable(null);if(previewBitmap!=null){previewBitmap.recycle();previewBitmap=null;}
    }

    private void updatePreviewContext(){
        int index=view.getSelectedItemPosition();previewContext.setText(VIEWS[index]+" view. "+(index==3?"Front features and ornaments are occluded. ":"")+"Preview uses the chosen frame on the existing clock. "+(timedSpeech.isChecked()?"Timed speech enabled. ":"")+"No audio playback.");
    }

    private void setSubmitting(AlertDialog dialog,boolean saving){
        submitting=saving;enable(form,!saving);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!saving);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!saving);
        dialog.setCancelable(!saving);dialog.setCanceledOnTouchOutside(!saving);
    }
    private static void enable(View control,boolean enabled){control.setEnabled(enabled);if(control instanceof ViewGroup){ViewGroup group=(ViewGroup)control;for(int i=0;i<group.getChildCount();i++)enable(group.getChildAt(i),enabled);}}
    private AdapterView.OnItemSelectedListener changed(Runnable action){return new AdapterView.OnItemSelectedListener(){
        public void onNothingSelected(AdapterView<?> parent){}
        public void onItemSelected(AdapterView<?> parent,View child,int position,long id){if(binding||closed||submitting)return;clearPreview();action.run();}
    };}
    private EditText input(LinearLayout row,String label,String value,boolean decimal){
        TextView title=new TextView(activity);title.setText(label);row.addView(title);EditText field=new EditText(activity);field.setContentDescription(label);field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));field.setText(value);row.addView(field);return field;
    }
    private EditText colour(String label,String value){EditText field=input(form,label,value,false);field.setInputType(InputType.TYPE_CLASS_TEXT);return field;}
    private Spinner spinner(LinearLayout row,String label,String[] values){TextView title=new TextView(activity);title.setText(label);row.addView(title);Spinner spinner=new Spinner(activity);spinner.setContentDescription(label);spinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values));row.addView(spinner);return spinner;}
    private interface Action {void run()throws Exception;}
    private void button(LinearLayout row,String label,Action action){Button button=new Button(activity);button.setText(label);row.addView(button);button.setOnClickListener(v->{if(closed||submitting||loading)return;try{action.run();}catch(Exception error){status.setText("Character unchanged: "+error.getMessage());}});}
}
