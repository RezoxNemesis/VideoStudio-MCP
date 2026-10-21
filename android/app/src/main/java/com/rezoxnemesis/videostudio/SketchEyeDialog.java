package com.rezoxnemesis.videostudio;

import android.app.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Human eye pose editor; emits precisely the same native request as MCP. */
final class SketchEyeDialog {
    private final Activity activity;private final JSONObject document;private final Consumer<JSONObject> create;
    private final LinearLayout form;private final TextView status;private final Spinner style,motion,posePicker;
    private final LinkedHashMap<String,EditText> fields=new LinkedHashMap<>();private final SeekBar[] sliders=new SeekBar[6];private final TextView[] readings=new TextView[6];
    private final EditText frame;private final CheckBox gloss;private JSONArray poses;private int selected=0,presetIndex=0;private boolean binding;
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private ImageView preview;private android.graphics.Bitmap previewBitmap;private int previewGeneration;private boolean closed;
    private static final String[] PRESETS={"look_blink","tracking","double_blink"};
    static void show(Activity activity,JSONObject document,Consumer<JSONObject> create)throws Exception{new SketchEyeDialog(activity,document,create).show();}
    private SketchEyeDialog(Activity a,JSONObject doc,Consumer<JSONObject> callback)throws Exception {
        activity=a;document=doc;create=callback;form=new LinearLayout(a);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,12,20,12);
        status=new TextView(a);status.setText("Create editable eyes on a new layer. Look, track, blink or wink; adjust each pose before creating. Current sketch: "+doc.getInt("frameCount")+" frames at "+doc.getInt("fps")+" FPS. Supports 24–240 frames.");form.addView(status);
        style=spinner("Eye shape",new String[]{"Round / cartoon","Almond","Slit pupil / creature"});style.setSelection(1);
        motion=spinner("Eye motion",new String[]{"Look around / blink / near focus","Follow a target","Glance / double blink / wink"});
        JSONObject defaults=SketchEyeAnimation.defaults();
        String[][] geometry={{"centerX","Centre X (%)"},{"centerY","Centre Y (%)"},{"width","Eye width (%)"},{"height","Eye height (%)"},{"spacing","Eye spacing (%)"},{"irisSize","Iris size (%)"},{"pupilSize","Pupil size (%)"},{"outlinePx","Outline width (px)"},{"tilt","Eye pair tilt (degrees)"}};
        for(String[] entry:geometry)input(entry[0],entry[1],String.valueOf(defaults.getDouble(entry[0])*(Set.of("outlinePx","tilt").contains(entry[0])?1:100)));
        input("irisColor","Iris colour",defaults.getString("irisColor"));input("scleraColor","Eye white colour",defaults.getString("scleraColor"));input("ink","Outline colour",defaults.getString("ink"));
        gloss=new CheckBox(a);gloss.setText("Light reflections");gloss.setContentDescription("Light reflections");gloss.setChecked(true);form.addView(gloss);
        poses=SketchEyeAnimation.preset(doc,"look_blink");posePicker=spinner("Eye pose",new String[]{"Pose"});
        frame=input("newFrame","New key frame (1 based)","1");
        LinearLayout buttons=new LinearLayout(a);form.addView(buttons);button(buttons,"Add / select key",()->addKey());button(buttons,"Delete key",()->deleteKey());
        String[] names={"Horizontal gaze","Vertical gaze","Left eyelid opening","Right eyelid opening","Pupil dilation","Near focus"};
        for(int i=0;i<6;i++){
            final int channel=i;readings[i]=new TextView(a);form.addView(readings[i]);sliders[i]=new SeekBar(a);sliders[i].setContentDescription(names[i]);sliders[i].setMax(i<2?200:100);form.addView(sliders[i]);
            sliders[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int p,boolean user){if(binding)return;try{
                double value=channel<2?(p-100)/100d:channel==4?.5+p/100d:channel==5?p/200d:p/100d;
                poses.getJSONObject(selected).put(SketchEyeAnimation.CHANNELS[channel],value);readings[channel].setText(names[channel]+": "+Math.round(value*100)+"%");
            }catch(Exception e){status.setText(e.getMessage());}}});
        }
        posePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int index,long id){if(binding)return;selected=index;loadPose();}});
        motion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int index,long id){if(index==presetIndex)return;try{presetIndex=index;poses=SketchEyeAnimation.preset(document,PRESETS[index]);selected=0;refresh();}catch(Exception e){status.setText(e.getMessage());}}});
        button(form,"Preview pose",this::preview);preview=new ImageView(a);preview.setContentDescription("Eye pose preview");preview.setScaleType(ImageView.ScaleType.FIT_CENTER);form.addView(preview,new LinearLayout.LayoutParams(-1,(int)(220*a.getResources().getDisplayMetrics().density)));
        refresh();
    }
    private void show(){ScrollView scroll=new ScrollView(activity);scroll.addView(form);AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Eye animation").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Create eyes",null).create();
        dialog.setOnDismissListener(d->{closed=true;previewGeneration++;worker.shutdownNow();preview.setImageDrawable(null);if(previewBitmap!=null){previewBitmap.recycle();previewBitmap=null;}});dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{try{
            JSONObject settings=readSettings();SketchEyeAnimation.validatePoses(poses,document.getInt("frameCount"));
            String id="eyes_"+UUID.randomUUID().toString().replace("-","").substring(0,24);
            create.accept(new JSONObject().put("operations",new JSONArray().put(new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Eye animation")))
                    .put(new JSONObject().put("type","add_eye_animation").put("layerId",id).put("eyeId",id).put("settings",settings).put("preset",PRESETS[presetIndex]).put("poses",new JSONArray(poses.toString())))));dialog.dismiss();
        }catch(Exception error){status.setText("Eyes not created: "+error.getMessage());}});
    }
    private JSONObject readSettings()throws Exception {
        JSONObject settings=new JSONObject().put("style",new String[]{"round","almond","slit"}[style.getSelectedItemPosition()]).put("gloss",gloss.isChecked());
        for(Map.Entry<String,EditText> field:fields.entrySet()){
            String key=field.getKey();if("newFrame".equals(key))continue;
            if(Set.of("irisColor","scleraColor","ink").contains(key))settings.put(key,field.getValue().getText().toString());
            else settings.put(key,Double.parseDouble(field.getValue().getText().toString())/(Set.of("outlinePx","tilt").contains(key)?1:100));
        }
        SketchEyeAnimation.settings(settings);return settings;
    }
    private void preview()throws Exception {
        JSONObject settings=readSettings();JSONArray keys=new JSONArray(poses.toString());SketchEyeAnimation.validatePoses(keys,document.getInt("frameCount"));
        int frame=keys.getJSONObject(selected).getInt("frame"),generation=++previewGeneration;String motion=PRESETS[presetIndex];status.setText("Preparing eye pose preview…");
        worker.execute(()->{try{
            JSONObject candidate=SketchDocument.copy(document);String id="preview_"+UUID.randomUUID().toString().replace("-","").substring(0,24);
            SketchEditOps.apply(candidate,new JSONObject().put("type","add_layer").put("layer",new JSONObject().put("id",id).put("name","Eye preview")));
            SketchEyeAnimation.apply(candidate,new JSONObject().put("type","add_eye_animation").put("eyeId",id).put("layerId",id).put("settings",settings).put("poses",keys).put("preset",motion));
            final android.graphics.Bitmap image;try(SketchFrameRenderer renderer=new SketchFrameRenderer(activity,candidate)){image=renderer.render(frame);}
            activity.runOnUiThread(()->{if(closed||generation!=previewGeneration||activity.isFinishing()||activity.isDestroyed()){image.recycle();return;}
                android.graphics.Bitmap old=previewBitmap;previewBitmap=image;preview.setImageBitmap(image);if(old!=null)old.recycle();status.setText("Preview · frame "+(frame+1)+". Drawing unchanged.");
                preview.post(()->preview.requestRectangleOnScreen(new android.graphics.Rect(0,0,preview.getWidth(),preview.getHeight()),true));});
        }catch(Exception failure){activity.runOnUiThread(()->{if(!closed&&generation==previewGeneration)status.setText("Preview unavailable: "+failure.getMessage());});}});
    }
    private void addKey()throws Exception {
        int f=Integer.parseInt(frame.getText().toString())-1;if(f<0||f>=document.getInt("frameCount"))throw SketchDocument.bad("Choose a key inside the sketch");
        for(int n=0;n<poses.length();n++)if(poses.getJSONObject(n).getInt("frame")==f){selected=n;refresh();return;}
        JSONObject key=SketchEyeAnimation.pose(f,0,0,1,1,1,0);double[] values=SketchEyeAnimation.sample(poses,f);for(int i=0;i<6;i++)key.put(SketchEyeAnimation.CHANNELS[i],values[i]);poses.put(key);poses=SketchAnimationUiModel.sortedFrames(poses);
        for(int n=0;n<poses.length();n++)if(poses.getJSONObject(n).getInt("frame")==f)selected=n;refresh();
    }
    private void deleteKey()throws Exception {if(poses.getJSONObject(selected).getInt("frame")==0)throw SketchDocument.bad("Keep the frame-one starting pose");JSONArray next=new JSONArray();for(int i=0;i<poses.length();i++)if(i!=selected)next.put(poses.get(i));poses=next;selected=Math.max(0,selected-1);refresh();}
    private void refresh()throws Exception {binding=true;String[] names=new String[poses.length()];for(int i=0;i<names.length;i++)names[i]="Frame "+(poses.getJSONObject(i).getInt("frame")+1);posePicker.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,names));posePicker.setSelection(selected);binding=false;loadPose();}
    private void loadPose(){try{binding=true;JSONObject p=poses.getJSONObject(selected);frame.setText(String.valueOf(p.getInt("frame")+1));
        for(int i=0;i<6;i++){double value=p.optDouble(SketchEyeAnimation.CHANNELS[i],i==2||i==3||i==4?1:0);sliders[i].setProgress((int)Math.round(i<2?value*100+100:i==4?(value-.5)*100:i==5?value*200:value*100));readings[i].setText(sliders[i].getContentDescription()+": "+Math.round(value*100)+"%");}
    }catch(Exception e){status.setText(e.getMessage());}finally{binding=false;}}
    private EditText input(String key,String name,String value){TextView title=new TextView(activity);title.setText(name);form.addView(title);EditText field=new EditText(activity);field.setContentDescription(name);field.setSingleLine(true);field.setText(value);if(!Set.of("irisColor","scleraColor","ink").contains(key))field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);form.addView(field);fields.put(key,field);return field;}
    private Spinner spinner(String label,String[] options){TextView title=new TextView(activity);title.setText(label);form.addView(title);Spinner spinner=new Spinner(activity);spinner.setContentDescription(label);spinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,options));form.addView(spinner);return spinner;}
    private interface Action{void run()throws Exception;}
    private void button(LinearLayout row,String label,Action action){Button button=new Button(activity);button.setText(label);row.addView(button);button.setOnClickListener(v->{try{action.run();}catch(Exception e){status.setText(e.getMessage());}});}
}
