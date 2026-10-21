package com.rezoxnemesis.videostudio;

import android.app.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Manual numeric controls share SketchStoryAudio.execute with the MCP entry. */
public final class SketchStoryAudioDialog {
    private final Activity activity;private final String projectId;private final long revision;private final Consumer<JSONObject> completed;
    private final List<ProjectStore.Asset> assets=new ArrayList<>();private final LinearLayout form;
    private final Spinner source,theme,ambience,type;private final EditText music,air,dialogue,tempo,seed,operation,events,eventId,eventTime,intensity,eventDuration;
    private final TextView status;private final ExecutorService worker=Executors.newSingleThreadExecutor();private Future<?> task;
    private boolean closed,busy;private int generation;private AlertDialog dialog;

    public static AlertDialog show(Activity activity,String projectId){return show(activity,projectId,null);}
    public static AlertDialog show(Activity activity,String projectId,Consumer<JSONObject> completed){
        try{return new SketchStoryAudioDialog(activity,projectId,completed).open();}
        catch(Exception error){return new AlertDialog.Builder(activity).setTitle("Story audio").setMessage(error.getMessage()).setPositiveButton("Close",null).show();}
    }
    private SketchStoryAudioDialog(Activity a,String id,Consumer<JSONObject> completed)throws Exception {
        activity=a;projectId=id;this.completed=completed;ProjectStore.Project project=new ProjectStore(a).get(id);
        if(project==null)throw new IllegalArgumentException("Choose an existing project with imported PCM audio");revision=project.updatedAt;
        for(ProjectStore.Asset asset:project.assets)if("audio/wav".equals(asset.mime)||"audio/x-wav".equals(asset.mime))assets.add(asset);
        form=new LinearLayout(a);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,10,20,20);
        label("Mix a selected 24kHz mono PCM WAV with a quiet pad, ambience and your sound cues. The original audio and its timing stay intact; a new audio project receives the mix.");
        label("Import the source WAV through the editor Files workflow first. Linked external files must be copied into app storage before mixing.");
        String[] names=new String[assets.size()];for(int i=0;i<names.length;i++)names[i]=assets.get(i).name;
        source=spinner("Dialogue asset",names);music=input("Music gain","0.035",true);air=input("Ambience gain","0.008",true);dialogue=input("Dialogue gain","1",true);tempo=input("Tempo","72",true);
        label("Music and ambience gain: 0–1. Dialogue gain: 0–2. Tempo: 30–180 beats/minute. Lower gains keep dialogue clear.");
        theme=spinner("Pad theme",new String[]{"warm","calm","tense"});ambience=spinner("Ambience",new String[]{"rain","wind","night","none"});seed=input("Seed","13717",false);seed.setInputType(InputType.TYPE_CLASS_NUMBER);
        label("Add timed sound cues. Optional duration shortens a cue with a smooth release. Default tails must fit the source audio: footstep 180ms, wood 240ms, metal 600ms, cloth 350ms, chime 1200ms.");
        eventId=input("Event ID","event_1",false);eventTime=input("Event time (ms)","0",false);eventTime.setInputType(InputType.TYPE_CLASS_NUMBER);eventDuration=input("Event duration (ms, optional)","",false);eventDuration.setInputType(InputType.TYPE_CLASS_NUMBER);type=spinner("Event type",new String[]{"footstep","wood","metal","cloth","chime"});intensity=input("Event intensity","0.5",true);
        events=input("Events JSON","[]",false);events.setSingleLine(false);events.setMinLines(3);events.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        button("Add cue",()->{
            JSONArray cues=new JSONArray(events.getText().toString());if(cues.length()>=512)throw new IllegalArgumentException("Use at most 512 sound cues");
            JSONObject cue=new JSONObject().put("id",eventId.getText().toString().trim()).put("atMs",Long.parseLong(eventTime.getText().toString().trim())).put("type",type.getSelectedItem()).put("intensity",Double.parseDouble(intensity.getText().toString().trim()));String duration=eventDuration.getText().toString().trim();if(!duration.isEmpty())cue.put("durationMs",Long.parseLong(duration));cues.put(cue);SketchStoryAudio.validateSettings(new JSONObject().put("events",cues),600L*24000);
            events.setText(cues.toString(2));eventId.setText("event_"+(cues.length()+1));
        });
        label("The cue list is editable. Remove a cue by deleting its object. IDs must be unique; times are milliseconds, intensity is 0–1.");
        operation=input("Operation ID",UUID.randomUUID().toString(),false);label("Retry with the same operation ID to reuse its result. Use a new operation ID after changing the mix settings.");
        button("Load Last Lantern cues",this::loadOriginalCues);
        button("New operation ID",()->operation.setText(UUID.randomUUID().toString()));button("Generate story audio",this::generate);
        status=label(assets.isEmpty()?"No PCM WAV assets in this project. Import one through Files, then reopen these controls.":"Ready. Listening and playback have not been checked.");
    }
    private void loadOriginalCues()throws Exception {
        try(java.io.InputStream in=activity.getAssets().open("animation_stories/the-last-lantern-audio-settings.json");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);JSONObject value=new JSONObject(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
            music.setText(String.valueOf(value.getDouble("musicGain")));air.setText(String.valueOf(value.getDouble("ambienceGain")));dialogue.setText(String.valueOf(value.getDouble("dialogueGain")));tempo.setText(String.valueOf(value.getDouble("tempo")));seed.setText(String.valueOf(value.getLong("seed")));
            theme.setSelection(java.util.Arrays.asList("warm","calm","tense").indexOf(value.getString("theme")));ambience.setSelection(java.util.Arrays.asList("rain","wind","night","none").indexOf(value.getString("ambience")));events.setText(value.getJSONArray("events").toString(2));operation.setText(UUID.randomUUID().toString());status.setText("Original story cues loaded. Select its unchanged 124-second dialogue master before generating.");
        }
    }
    private AlertDialog open(){
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        dialog=new AlertDialog.Builder(activity).setTitle("Story audio").setView(scroll).setNegativeButton("Close",null).setNeutralButton("Cancel generation",null).create();
        dialog.setOnDismissListener(d->{closed=true;generation++;if(task!=null)task.cancel(true);worker.shutdownNow();});dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{if(!busy)return;generation++;if(task!=null)task.cancel(true);busy=false;enable(form,true);status.setText("Cancellation requested. Controls retained; retry keeps the operation identity.");});
        return dialog;
    }
    private void generate()throws Exception {
        if(busy||closed)return;if(assets.isEmpty()||source.getSelectedItemPosition()<0)throw new IllegalArgumentException("Import a PCM WAV through Files first");
        JSONObject s=new JSONObject().put("musicGain",Double.parseDouble(music.getText().toString().trim())).put("ambienceGain",Double.parseDouble(air.getText().toString().trim()))
            .put("dialogueGain",Double.parseDouble(dialogue.getText().toString().trim())).put("tempo",Double.parseDouble(tempo.getText().toString().trim()))
            .put("theme",theme.getSelectedItem()).put("ambience",ambience.getSelectedItem()).put("seed",Long.parseLong(seed.getText().toString().trim())).put("events",new JSONArray(events.getText().toString()));
        JSONObject args=new JSONObject().put("projectId",projectId).put("sourceProjectUpdatedAt",revision).put("assetId",assets.get(source.getSelectedItemPosition()).id).put("operationId",operation.getText().toString().trim()).put("settings",s);
        busy=true;int token=++generation;enable(form,false);status.setText("Mixing audio… Close or cancel to stop.");
        task=worker.submit(()->{
            try{JSONObject result=SketchStoryAudio.execute(activity.getApplicationContext(),args);activity.runOnUiThread(()->{
                if(unavailable()||generation!=token)return;
                status.setText("Saved a new audio project: "+result.optString("projectId")+"\nThe original project is unchanged. Listening and playback remain unreviewed.");
                if(completed!=null)try{completed.accept(result);}catch(Exception error){status.setText("Audio saved; opening the result failed: "+error.getMessage());}
            });}catch(Exception error){activity.runOnUiThread(()->{if(!unavailable()&&generation==token)status.setText("Controls retained: "+error.getMessage());});}
            finally{activity.runOnUiThread(()->{if(!unavailable()&&generation==token){busy=false;enable(form,true);}});}
        });
    }
    private boolean unavailable(){return closed||activity.isFinishing()||activity.isDestroyed();}
    private TextView label(String text){TextView view=new TextView(activity);view.setText(text);view.setPadding(0,10,0,4);form.addView(view);return view;}
    private EditText input(String name,String value,boolean decimal){label(name);EditText field=new EditText(activity);field.setContentDescription(name);field.setSingleLine(true);field.setInputType(decimal?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT);field.setText(value);form.addView(field);return field;}
    private Spinner spinner(String name,String[] choices){label(name);Spinner view=new Spinner(activity);view.setContentDescription(name);view.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,choices));form.addView(view);return view;}
    private interface Action{void run()throws Exception;}
    private void button(String name,Action action){Button b=new Button(activity);b.setText(name);b.setContentDescription(name);b.setOnClickListener(v->{try{if(!closed&&!busy)action.run();}catch(Exception error){status.setText("Controls retained: "+error.getMessage());}});form.addView(b);}
    private static void enable(View view,boolean enabled){view.setEnabled(enabled);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)enable(group.getChildAt(i),enabled);}}
}
