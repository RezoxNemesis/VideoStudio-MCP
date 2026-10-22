package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Detached shared-motion recipe; only the existing prepared editor save publishes it. */
final class SketchLayerGroupMotionDialog implements AutoCloseable {
    interface Save {void submit(JSONObject operation)throws Exception;}
    private static final String[] EASING={"linear","hold","smooth","ease_in","ease_out","ease_in_out","bezier"};
    private final Activity activity;
    private final JSONObject source;
    private final Save save;
    private final LinearLayout form;
    private final LinkedHashMap<String,CheckBox> layers=new LinkedHashMap<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final TextView status;
    private final Spinner posePicker,easing;
    private final EditText pivotX,pivotY,frame,deltaX,deltaY,rotation,previewFrame;
    private final EditText[] curve=new EditText[4];
    private final ImageView preview;
    private JSONArray poses=new JSONArray();
    private AlertDialog dialog;
    private Bitmap bitmap;
    private boolean closed,binding;
    private int selected,generation;
    static SketchLayerGroupMotionDialog show(Activity activity,JSONObject document,int currentFrame,Save save)throws Exception {
        SketchLayerGroupMotionDialog owner=new SketchLayerGroupMotionDialog(activity,document,currentFrame,save);owner.open();return owner;
    }
    private SketchLayerGroupMotionDialog(Activity activity,JSONObject document,int currentFrame,Save save)throws Exception {
        this.activity=activity;source=SketchDocument.copy(document);this.save=save;
        int frames=source.getInt("frameCount");if(frames<1||frames>240)throw SketchDocument.bad("Group motion supports a complete shot of1..240 frames");
        form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(16),dp(10),dp(16),dp(12));
        text("Select the parts that should travel together, such as a hand and its held prop. Their original artwork, separate anchors and existing animation stay intact. Delta positions use canvas pixels; rotation is around the shared pivot. This is rigid 2D placement, not automatic grasp physics.");
        status=text("Choose2..32 visible unlocked layers. The first and last poses cover this complete shot.");status.setContentDescription("Group motion status");
        JSONArray rows=source.getJSONArray("layers");for(int i=0;i<rows.length();i++){
            JSONObject layer=rows.getJSONObject(i);if(layer.getBoolean("locked")||!layer.getBoolean("visible")||layer.getDouble("opacity")<=0)continue;
            String id=layer.getString("id");CheckBox check=new CheckBox(activity);check.setText(layer.getString("name"));check.setContentDescription("Group layer "+id);layers.put(id,check);form.addView(check);check.setOnCheckedChangeListener((v,checked)->invalidatePreview());
        }
        if(layers.size()<2)throw SketchDocument.bad("Group motion requires at least two visible unlocked layers");
        pivotX=input("Group pivot X (pixels)",String.valueOf(source.getInt("width")/2d));pivotY=input("Group pivot Y (pixels)",String.valueOf(source.getInt("height")/2d));
        poses.put(pose(0));if(frames>1)poses.put(pose(frames-1));posePicker=spinner("Group pose",new String[]{"Frame1"});
        frame=input("Group pose frame (1 based)","1");deltaX=input("Group delta X (pixels)","0");deltaY=input("Group delta Y (pixels)","0");rotation=input("Group rotation (degrees)","0");easing=spinner("Group easing",EASING);
        String[] curveNames={"Group Bezier X1","Group Bezier Y1","Group Bezier X2","Group Bezier Y2"};String[] defaults={"0.25","0.1","0.25","1"};for(int i=0;i<4;i++)curve[i]=input(curveNames[i],defaults[i]);
        button("Add / update pose",()->{upsertPose();refreshPoses();status.setText("Pose updated in this draft. Preview or Apply to validate the full motion.");});
        button("Remove pose",()->{int f=poses.getJSONObject(selected).getInt("frame");if(f==0||f==source.getInt("frameCount")-1)throw SketchDocument.bad("Keep the shot's first and last poses");poses.remove(selected);selected=Math.max(0,selected-1);invalidatePreview();refreshPoses();});
        previewFrame=input("Group preview frame (1 based)",String.valueOf(currentFrame+1));button("Preview group motion",this::preview);
        preview=new ImageView(activity);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setContentDescription("Group motion preview");form.addView(preview,new LinearLayout.LayoutParams(-1,dp(240)));
        posePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int at,long id){if(binding)return;selected=at;loadPose();}});
        easing.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int at,long id){if(!binding)invalidatePreview();for(EditText field:curve)field.setEnabled(at==6);}});
        refreshPoses();
    }
    private void open(){ScrollView scroll=new ScrollView(activity);scroll.addView(form);dialog=new AlertDialog.Builder(activity).setTitle("Move layers together").setView(scroll).setNegativeButton("Close",null).setPositiveButton("Apply group motion",null).create();dialog.setOnDismissListener(d->close());dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{JSONObject op=operation();save.submit(op);close();}catch(Exception failure){message(failure);}});}
    private JSONObject pose(int f)throws Exception{return new JSONObject().put("frame",f).put("x",0).put("y",0).put("rotation",0).put("interpolation","linear");}
    private void upsertPose()throws Exception {
        int f=integer(frame,1,source.getInt("frameCount"))-1;
        JSONObject row=new JSONObject().put("frame",f).put("x",number(deltaX,-4d*source.getInt("width"),4d*source.getInt("width"))/source.getInt("width")).put("y",number(deltaY,-4d*source.getInt("height"),4d*source.getInt("height"))/source.getInt("height")).put("rotation",number(rotation,-3600,3600)).put("interpolation",EASING[easing.getSelectedItemPosition()]);
        if(easing.getSelectedItemPosition()==6){JSONArray values=new JSONArray();for(EditText field:curve)values.put(number(field,0,1));row.put("curve",values);}
        int replace=-1;for(int i=0;i<poses.length();i++)if(poses.getJSONObject(i).getInt("frame")==f)replace=i;
        if(replace<0&&poses.length()>=32)throw SketchDocument.bad("Use at most32 group poses");if(replace<0)poses.put(row);else poses.put(replace,row);
        poses=SketchAnimationUiModel.sortedFrames(poses);for(int i=0;i<poses.length();i++)if(poses.getJSONObject(i).getInt("frame")==f)selected=i;invalidatePreview();
    }
    private JSONObject operation()throws Exception {
        upsertPose();JSONArray ids=new JSONArray();for(Map.Entry<String,CheckBox> row:layers.entrySet())if(row.getValue().isChecked())ids.put(row.getKey());if(ids.length()<2||ids.length()>32)throw SketchDocument.bad("Choose2..32 layers");
        if(poses.getJSONObject(0).getInt("frame")!=0||poses.getJSONObject(poses.length()-1).getInt("frame")!=source.getInt("frameCount")-1)throw SketchDocument.bad("Keep poses on the first and last shot frames");
        return new JSONObject().put("type","set_layer_group_motion").put("layerIds",ids).put("pivot",new JSONArray().put(number(pivotX,0,source.getInt("width"))/source.getInt("width")).put(number(pivotY,0,source.getInt("height"))/source.getInt("height"))).put("poses",new JSONArray(poses.toString()));
    }
    private void preview()throws Exception {
        JSONObject op=operation();int at=integer(previewFrame,1,source.getInt("frameCount"))-1,token=++generation;status.setText("Preparing native group motion preview…");
        worker.execute(()->{Bitmap rendered=null;try{JSONObject draft=SketchDocument.copy(source);SketchLayerGroupMotion.apply(draft,op);if(Thread.currentThread().isInterrupted())return;try(SketchFrameRenderer renderer=new SketchFrameRenderer(activity,draft)){rendered=renderer.render(at,true,640,null);}Bitmap image=rendered;activity.runOnUiThread(()->{if(closed||token!=generation||activity.isDestroyed()||activity.isFinishing()){image.recycle();return;}clearBitmap();bitmap=image;preview.setImageBitmap(image);status.setText("Preview · frame "+(at+1)+". Original drawing unchanged; Apply saves one Undo step.");});}catch(Exception|OutOfMemoryError failure){if(rendered!=null)rendered.recycle();activity.runOnUiThread(()->{if(!closed&&token==generation)message(failure);});}});
    }
    private void refreshPoses()throws Exception {binding=true;try{String[] labels=new String[poses.length()];for(int i=0;i<labels.length;i++)labels[i]="Frame "+(poses.getJSONObject(i).getInt("frame")+1);posePicker.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));posePicker.setSelection(selected);loadPose();}finally{binding=false;}}
    private void loadPose(){boolean old=binding;binding=true;try{JSONObject row=poses.getJSONObject(selected);frame.setText(String.valueOf(row.getInt("frame")+1));deltaX.setText(String.valueOf(row.getDouble("x")*source.getInt("width")));deltaY.setText(String.valueOf(row.getDouble("y")*source.getInt("height")));rotation.setText(String.valueOf(row.getDouble("rotation")));easing.setSelection(Arrays.asList(EASING).indexOf(row.getString("interpolation")));if(row.has("curve"))for(int i=0;i<4;i++)curve[i].setText(String.valueOf(row.getJSONArray("curve").getDouble(i)));}catch(Exception failure){message(failure);}finally{binding=old;}}
    private void invalidatePreview(){if(binding||closed)return;++generation;if(preview!=null){clearBitmap();}if(status!=null)status.setText("Draft changed. Preview again to inspect the updated motion.");}
    private void clearBitmap(){preview.setImageDrawable(null);if(bitmap!=null){bitmap.recycle();bitmap=null;}}
    private void message(Throwable failure){status.setText("Motion not applied: "+failure.getMessage());}
    @Override public void close(){if(closed)return;closed=true;++generation;worker.shutdownNow();clearBitmap();if(dialog!=null&&dialog.isShowing())dialog.dismiss();}
    private interface Action{void run()throws Exception;}
    private int dp(int n){return StudioUi.dp(activity,n);}
    private TextView text(String value){TextView v=new TextView(activity);v.setText(value);form.addView(v);return v;}
    private EditText input(String label,String value){text(label);EditText v=new EditText(activity);v.setContentDescription(label);v.setSingleLine(true);v.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|InputType.TYPE_NUMBER_FLAG_DECIMAL);v.setText(value);form.addView(v);v.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){invalidatePreview();}public void afterTextChanged(Editable value){}});return v;}
    private Spinner spinner(String label,String[] rows){text(label);Spinner v=new Spinner(activity);v.setContentDescription(label);v.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,rows));form.addView(v);return v;}
    private void button(String label,Action action){Button v=new Button(activity);v.setText(label);form.addView(v);v.setOnClickListener(view->{if(closed)return;try{action.run();}catch(Exception failure){message(failure);}});}
    private static int integer(EditText v,int min,int max)throws Exception {String raw=v.getText().toString().trim();if(!raw.matches("[0-9]+"))throw SketchDocument.bad("Enter a whole frame number");int n=Integer.parseInt(raw);if(n<min||n>max)throw SketchDocument.bad("Choose a frame inside the shot");return n;}
    private static double number(EditText v,double min,double max)throws Exception {double n=Double.parseDouble(v.getText().toString());if(!Double.isFinite(n)||n<min||n>max)throw SketchDocument.bad(v.getContentDescription()+" must be "+min+".."+max);return n;}
}
