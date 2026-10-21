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

/** Bounded manual timed visemes through the same native operations as MCP. */
final class SketchSpeechDialog {
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    private static final String[] MOUTHS={"soft","cartoon","fang"},HEADS={"oval","round","angular"},EYES={"round","almond","slit"};
    private final Activity activity;
    private final JSONObject document;
    private final SketchConstructionSubmit create;
    private final LinearLayout form,faceControls;
    private final TextView status,previewContext;
    private final Spinner target,view,mouthStyle,headShape,eyeStyle,cuePicker,cueShape;
    private final EditText transition,start,end,strength,shotStart;
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
        new SketchSpeechDialog(activity,document,create).show();
    }

    private SketchSpeechDialog(Activity activity,JSONObject document,SketchConstructionSubmit create)throws Exception {
        this.activity=activity;this.document=SketchDocument.copy(document);this.create=create;
        int frames=document.getInt("frameCount");
        if(frames<24||frames>240)throw SketchDocument.bad("Speech animation supports sketches with 24–240 frames; timing is preserved");
        form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,12,20,12);
        status=new TextView(activity);status.setContentDescription("Speech animation status");status.setText("Authored demonstration ready. No audio is played by preview.");
        shotStart=input("Shot start in master (ms)","0",false);
        button("Load generated dialogue",this::loadGenerated);
        TextView description=new TextView(activity);description.setContentDescription("Speech animation description");
        description.setText("Author timed visemes (mouth shapes), with no speech recognition. Audio is not synthesized or mixed by this dialog. Use existing generated/imported audio in the main editor at the same timeline offset. The initial cues are an authored demonstration, not audio alignment. Create editable cels on a new layer. For a short shot within a longer master, enter its global start in Shot start in master (ms); only fully contained lines load, without clipping or timewarp.\nCurrent sketch: "+frames+" frames at "+document.getInt("fps")+" FPS ("+Math.round(durationMs())+" ms). Supports 24–240 frames without changing timing.");form.addView(description);
        target=spinner(form,"Speech target",new String[]{"Isolated mouth","Complete face"});
        view=spinner(form,"Speech view",new String[]{"Front","Left","Right","Back (features hidden)","High","Low"});
        mouthStyle=spinner(form,"Mouth style",new String[]{"Soft lips","Cartoon","Fangs / creature"});
        faceControls=new LinearLayout(activity);faceControls.setOrientation(LinearLayout.VERTICAL);form.addView(faceControls);
        headShape=spinner(faceControls,"Head shape",new String[]{"Oval","Round","Angular"});
        eyeStyle=spinner(faceControls,"Eye style",new String[]{"Round / cartoon","Almond","Slit pupil / creature"});eyeStyle.setSelection(1);
        TextView gaze=new TextView(activity);gaze.setText("Complete face uses the reaction preset for base gaze; authored cues control the mouth.");faceControls.addView(gaze);faceControls.setVisibility(View.GONE);
        transition=input("Transition (ms)","60",true);
        TextView help=new TextView(activity);help.setText("Use 1–240 cues; start is inclusive and end is exclusive. Whole milliseconds from sketch start; gaps rest. Transition: 0–200 ms; strength: 0–1. Edit the selected cue and apply, or enter a new interval and add it. Cues stay sorted; overlaps are rejected. Preview shows the frame nearest before the selected cue midpoint; short cues may fall between frames.");form.addView(help);
        cues=demonstration();cuePicker=spinner(form,"Speech cue",new String[]{"Cue"});
        start=input("Cue start (ms)","0",false);end=input("Cue end (ms)","1",false);
        cueShape=spinner(form,"Cue shape",SketchSpeechTiming.SHAPES);
        strength=input("Cue strength (0..1)","1",true);
        button("Apply cue edits",()->{saveCurrent();refresh();status.setText("Cue edits applied. Gaps rest.");});
        button("Add cue",this::addCue);button("Delete cue",this::deleteCue);
        previewContext=new TextView(activity);previewContext.setContentDescription("Speech preview context");form.addView(previewContext);
        preview=new ImageView(activity);preview.setContentDescription("Speech cue preview");preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button("Preview selected cue",this::preview);form.addView(preview,new LinearLayout.LayoutParams(-1,(int)(220*activity.getResources().getDisplayMetrics().density)));
        form.addView(status);
        cuePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View child,int position,long id){
                if(binding||closed||submitting||position==selected)return;
                try{JSONObject next=cues.getJSONObject(position);saveCurrent();selected=indexOf(next);refresh();}
                catch(Exception error){binding=true;cuePicker.setSelection(selected);binding=false;status.setText("Cue unchanged: "+error.getMessage());}
            }
        });
        target.setOnItemSelectedListener(changed(()->{faceControls.setVisibility(target.getSelectedItemPosition()==1?View.VISIBLE:View.GONE);updatePreviewContext();}));
        view.setOnItemSelectedListener(changed(this::updatePreviewContext));
        for(Spinner control:new Spinner[]{mouthStyle,headShape,eyeStyle,cueShape})control.setOnItemSelectedListener(changed(()->{}));
        TextWatcher edited=new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int from,int count,int after){}
            public void onTextChanged(CharSequence s,int from,int before,int count){if(!binding&&!closed&&!submitting)clearPreview();}
            public void afterTextChanged(Editable value){}
        };
        for(EditText control:new EditText[]{transition,start,end,strength,shotStart})control.addTextChangedListener(edited);
        refresh();updatePreviewContext();
    }

    private void show(){
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        dialog=new AlertDialog.Builder(activity).setTitle("Speech animation").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Create speech cels",null).create();
        dialog.setOnDismissListener(d->{closed=true;if(sourcePicker!=null)sourcePicker.dismiss();if(loadTask!=null)loadTask.cancel(true);clearPreview();worker.shutdownNow();});dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{if(!submitting)dialog.cancel();});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(submitting||closed||loading)return;
            try{
                saveCurrent();JSONObject speech=speech(cues);String id="speech_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
                JSONObject layer=new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Authored speech animation"));
                JSONObject request=new JSONObject().put("operations",new JSONArray().put(layer).put(operation(id,speech)));
                clearPreview();setSubmitting(dialog,true);status.setText("Saving authored speech cels…");
                AtomicBoolean completed=new AtomicBoolean();Consumer<Exception> completion=error->{
                    if(!completed.compareAndSet(false,true))return;
                    activity.runOnUiThread(()->{if(closed)return;if(error==null){dialog.dismiss();return;}setSubmitting(dialog,false);status.setText("Speech cels not created: "+error.getMessage());});
                };
                try{create.submit(request,completion);}catch(Exception error){completion.accept(error);}
            }catch(Exception error){status.setText("Speech cels not created: "+error.getMessage());}
        });
    }

    private double durationMs()throws Exception{return document.getInt("frameCount")*1000d/document.getInt("fps");}

    private void loadGenerated()throws Exception {
        final long shotStartMs;
        try{shotStartMs=Long.parseLong(shotStart.getText().toString().trim());}
        catch(NumberFormatException error){throw SketchDocument.bad("Enter whole shot start milliseconds from 0 to 600000");}
        if(shotStartMs<0||shotStartMs>600000)throw SketchDocument.bad("Shot start must be 0..600000 milliseconds in the master");
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
                                JSONObject loaded=SketchSpeechAssets.load(activity,choice.getString("requestId"),choice.getInt("lineIndex"),durationMs(),shotStartMs);
                                activity.runOnUiThread(()->{
                                    if(unavailable())return;
                                    try{JSONObject speech=loaded.getJSONObject("speech");cues=speech.getJSONArray("cues");selected=0;
                                        transition.setText(String.valueOf(speech.get("transitionMs")));refresh();setLoading(false);
                                        status.setText("Loaded: "+loaded.getString("text")+"\nModel timing is an estimate. Edit cues against the unchanged master audio. Place this shot at "+loaded.getLong("audioTimelineOffsetMs")+" ms on the master timeline; keep the master audio at its original origin. No clipping or timewarp.");
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

    private JSONObject operation(String id,JSONObject speech)throws Exception {
        boolean face=target.getSelectedItemPosition()==1;
        JSONObject settings=new JSONObject().put("view",VIEWS[view.getSelectedItemPosition()]);
        if(face)settings.put("shape",HEADS[headShape.getSelectedItemPosition()]).put("eyeStyle",EYES[eyeStyle.getSelectedItemPosition()]).put("mouthStyle",MOUTHS[mouthStyle.getSelectedItemPosition()]);
        else settings.put("style",MOUTHS[mouthStyle.getSelectedItemPosition()]);
        return new JSONObject().put("type",face?"add_face_animation":"add_mouth_animation").put("layerId",id).put(face?"faceId":"mouthId",id)
                .put("settings",settings).put("speech",speech);
    }

    private void preview()throws Exception {
        saveCurrent();JSONObject speech=speech(cues);String id="preview_"+UUID.randomUUID().toString().replace("-","").substring(0,20);JSONObject op=operation(id,speech);
        JSONObject cue=cues.getJSONObject(selected);int frame=Math.min(document.getInt("frameCount")-1,(int)Math.floor((cue.getDouble("startMs")+cue.getDouble("endMs"))*document.getInt("fps")/2000d));
        int generation=++previewGeneration;status.setText("Preparing native cue preview…");
        previewTask=worker.submit(()->{
            try{
                if(Thread.currentThread().isInterrupted())return;
                JSONObject candidate=SketchDocument.copy(document);
                SketchEditOps.apply(candidate,new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Speech preview")));
                SketchEditOps.apply(candidate,op);if(Thread.currentThread().isInterrupted())return;
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
        int index=view.getSelectedItemPosition();previewContext.setText(VIEWS[index]+" view. "+(index==3?"Features are hidden by the head from behind. ":"")+"Preview uses the selected cue midpoint on the existing frame clock.");
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
    private EditText input(String label,String value,boolean decimal){
        TextView title=new TextView(activity);title.setText(label);form.addView(title);EditText field=new EditText(activity);field.setContentDescription(label);field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));field.setText(value);form.addView(field);return field;
    }
    private Spinner spinner(LinearLayout row,String label,String[] values){TextView title=new TextView(activity);title.setText(label);row.addView(title);Spinner spinner=new Spinner(activity);spinner.setContentDescription(label);spinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values));row.addView(spinner);return spinner;}
    private interface Action {void run()throws Exception;}
    private void button(String label,Action action){Button button=new Button(activity);button.setText(label);form.addView(button);button.setOnClickListener(v->{if(closed||submitting)return;try{action.run();}catch(Exception error){status.setText("Cue unchanged: "+error.getMessage());}});}
}
