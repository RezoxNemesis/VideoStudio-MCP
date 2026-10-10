package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/** Actual original-artwork/rest-UV brush, separate from the posed programme monitor. */
final class EditorRigWeightDialog {
    interface Listener {
        ProjectStore.Project onCommit(String projectId,long expectedRevision,JSONObject stroke);
        ProjectStore.Project onUndo(String projectId,long expectedRevision);
        void onSelectBone(String boneId);
    }

    static void show(Activity activity,ProjectStore.Project snapshot,String clipId,String selectedBone,Listener listener){
        EditorRigWeightDialog editor=null;try{editor=new EditorRigWeightDialog(activity,snapshot,clipId,selectedBone,listener);editor.open();}
        catch(Exception|OutOfMemoryError error){if(editor!=null){editor.dialog.dismiss();editor.dispose();}android.widget.Toast.makeText(activity,error.getMessage()==null?"Weight canvas could not be opened":error.getMessage(),android.widget.Toast.LENGTH_LONG).show();}
    }

    private final Activity activity;
    private final Listener listener;
    private final String clipId,assetUri;
    private final Dialog dialog;
    private final Handler main=new Handler(Looper.getMainLooper());
    private com.google.common.util.concurrent.ListenableFuture<Bitmap> decoding;
    private final FrameLayout canvasHost;
    private final TextView status,brushLabel;
    private final Spinner bonePicker,falloffPicker;
    private final ArrayList<String> boneIds=new ArrayList<>();
    private ProjectStore.Project project;
    private EditorRig2DView view;
    private Bitmap artwork;
    private String selectedBone;
    private float radius=.1f,strength=.25f;
    private String falloff="smooth";
    private volatile boolean closed;
    private boolean reloading,stale;

    private EditorRigWeightDialog(Activity activity,ProjectStore.Project snapshot,String clipId,String selectedBone,Listener listener)throws Exception{
        this.activity=activity;this.listener=listener;this.clipId=clipId;this.selectedBone=selectedBone;project=ProjectStore.copy(snapshot);
        ProjectStore.Clip clip=project.clip(clipId);if(clip==null||clip.effects.optJSONObject("rig2d")==null)throw new IllegalArgumentException("Select an image rig before painting weights");
        ProjectStore.Track track=project.track(clip.trackId);if(track==null||track.locked)throw new IllegalArgumentException("Unlock this track before painting weights");
        ProjectStore.Asset asset=project.asset(clip.assetId);if(asset==null||asset.mime==null||!asset.mime.startsWith("image/"))throw new IllegalArgumentException("Weight painting requires registered still-image artwork");if(asset.width<=0||asset.height<=0)throw new IllegalArgumentException("The source image dimensions are missing. Relink the artwork to inspect it before painting weights.");assetUri=asset.uri;
        dialog=new Dialog(activity,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout page=new LinearLayout(activity);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(10),dp(8),dp(10),dp(8));page.setBackgroundColor(Color.rgb(12,18,28));
        TextView title=text("Bind weight painting",16);page.addView(title);
        page.addView(text("Original artwork / rest mesh. Red means more selected-bone influence. This canvas excludes poses, crops and clip transforms.",10));
        bonePicker=new Spinner(activity);page.addView(bonePicker);
        bonePicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View selected,int index,long id){if(reloading||index<0||index>=boneIds.size())return;selectedBone=boneIds.get(index);if(view!=null)view.setSelectedBone(selectedBone);listener.onSelectBone(selectedBone);}
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        brushLabel=text("",11);page.addView(brushLabel);
        LinearLayout sliders=new LinearLayout(activity);sliders.setGravity(Gravity.CENTER_VERTICAL);
        SeekBar radiusSlider=new SeekBar(activity);radiusSlider.setMax(1999);radiusSlider.setProgress(99);sliders.addView(radiusSlider,new LinearLayout.LayoutParams(0,dp(34),1));
        SeekBar strengthSlider=new SeekBar(activity);strengthSlider.setMax(200);strengthSlider.setProgress(125);sliders.addView(strengthSlider,new LinearLayout.LayoutParams(0,dp(34),1));page.addView(sliders);
        radiusSlider.setOnSeekBarChangeListener(slider(progress->{radius=.001f+progress/1000f;updateBrush();}));strengthSlider.setOnSeekBarChangeListener(slider(progress->{strength=(progress-100)/100f;updateBrush();}));
        LinearLayout options=new LinearLayout(activity);falloffPicker=new Spinner(activity);falloffPicker.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"smooth","linear","hard"}));options.addView(falloffPicker,new LinearLayout.LayoutParams(0,-2,1));
        options.addView(button("Exact brush",()->exactBrushDialog(radiusSlider,strengthSlider)));options.addView(button("Fit",()->{if(view!=null)view.fitWeightCanvas();}));page.addView(options);
        falloffPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View selected,int index,long id){falloff=String.valueOf(falloffPicker.getSelectedItem());updateBrush();}@Override public void onNothingSelected(android.widget.AdapterView<?> parent){}});
        canvasHost=new FrameLayout(activity);page.addView(canvasHost,new LinearLayout.LayoutParams(-1,0,1));
        status=text("Loading actual artwork…",10);status.setMinHeight(dp(35));page.addView(status);
        page.addView(text("Paint with one finger. Pinch with two fingers to zoom / pan. A release saves one undoable stroke, up to 256 evenly spaced source-UV dabs. Negative strength erases; every vertex keeps a normalized binding.",10));
        LinearLayout commands=new LinearLayout(activity);commands.addView(button("Undo project edit",this::undo),new LinearLayout.LayoutParams(0,dp(38),1));commands.addView(button("Done",dialog::dismiss),new LinearLayout.LayoutParams(0,dp(38),1));page.addView(commands);
        dialog.setContentView(page);dialog.setOnDismissListener(d->dispose());
        dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(android.view.View decor){}
            @Override public void onViewDetachedFromWindow(android.view.View decor){dispose();}
        });
        rebuildCanvas();updateBrush();
    }

    private void open(){dialog.show();dialog.getWindow().setLayout(-1,-1);
        // Match the monitor/export's bounded, EXIF-oriented still-image decoder.
        decoding=new StreamingBitmapLoader(activity,1024).loadBitmap(Uri.parse(assetUri));
        final com.google.common.util.concurrent.ListenableFuture<Bitmap> request=decoding;
        request.addListener(()->{
        Bitmap result=null;String failure="";try{result=request.get();}
        catch(Exception|OutOfMemoryError error){Throwable cause=error.getCause()==null?error:error.getCause();failure=cause.getMessage()==null?"Artwork decoding failed":cause.getMessage();}
        final Bitmap loaded=result;final String detail=failure;if(closed){if(loaded!=null)loaded.recycle();return;}
        if(!main.post(()->{if(closed){if(loaded!=null)loaded.recycle();return;}artwork=loaded;view.setWeightCanvas(artwork);if(stale)view.setEnabled(false);else status.setText(loaded==null?"Cannot paint: "+detail:"Ready · original source UV · r"+project.revision+" · zero weight is unchanged when a vertex has no alternative binding");})){if(loaded!=null)loaded.recycle();}
    },Runnable::run);}

    private void rebuildCanvas()throws Exception{
        ProjectStore.Clip clip=project.clip(clipId);if(clip==null||clip.effects.optJSONObject("rig2d")==null)throw new IllegalArgumentException("This clip or rig was removed; reopen weight painting");
        ProjectStore.Asset asset=project.asset(clip.assetId);if(asset==null||!assetUri.equals(asset.uri))throw new IllegalArgumentException("The artwork source changed; reopen weight painting");
        JSONArray bones=clip.effects.optJSONObject("rig2d").optJSONArray("bones");ArrayList<String> names=new ArrayList<>();boneIds.clear();for(int index=0;index<bones.length();index++){JSONObject bone=bones.optJSONObject(index);boneIds.add(bone.optString("id"));names.add(bone.optString("name",bone.optString("id")));}
        if(!boneIds.contains(selectedBone))selectedBone=boneIds.get(0);reloading=true;bonePicker.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,names));bonePicker.setSelection(boneIds.indexOf(selectedBone));reloading=false;
        float[] viewport=view==null?null:view.weightViewport();
        view=new EditorRig2DView(activity,project,clip,AnimationRigEdits.sourceAspect(asset),new EditorRig2DView.Listener(){
            @Override public void onBoneSelected(String id){}
            @Override public void onVertexSelected(int index){}
            @Override public void onBindCommit(String boneId,boolean tail,float u,float v){}
            @Override public void onIkCommit(String ikId,long atMs,float u,float v){}
            @Override public boolean onWeightCommit(String boneId,JSONArray points,float brushRadius,float brushStrength,String brushFalloff){
                if(stale||closed)return false;boolean committed=false;long savedRevision=-1;try{
                    JSONObject stroke=new JSONObject().put("clipId",clipId).put("boneId",boneId).put("points",new JSONArray(points.toString())).put("radius",brushRadius).put("strength",brushStrength).put("falloff",brushFalloff);
                    // The same pure helper supplies the exact changed-vertex receipt and
                    // rejects unchanged strokes before asking the store to commit.
                    JSONObject receipt=AnimationRigEdits.paintWeights(ProjectStore.copy(project),stroke);
                    ProjectStore.Project updated=listener.onCommit(project.id,project.revision,stroke);
                    if(updated==null){stale=true;view.setEnabled(false);status.setText("Stroke not saved. The original committed weights are shown. Close and reopen this canvas after resolving the edit notice.");return false;}
                    committed=true;savedRevision=updated.revision;
                    project=ProjectStore.copy(updated);rebuildCanvas();updateBrush();status.setText("Stroke saved · "+receipt.optInt("affectedVertexCount")+" changed vertices · "+points.length()+" dabs · r"+project.revision);return true;
                }catch(Exception|OutOfMemoryError error){if(committed){stale=true;view.setEnabled(false);status.setText("Stroke saved at revision "+savedRevision+". Close and reopen to refresh the weight canvas.");return true;}status.setText("Stroke not saved · "+(error.getMessage()==null?"preview memory unavailable":error.getMessage()));return false;}
            }
            @Override public void onBrushNotice(String message){if(!stale)status.setText(message);}
        });view.setSelectedBone(selectedBone);view.setWeightCanvas(artwork);view.restoreWeightViewport(viewport);view.setWeightBrush(radius,strength,falloff);canvasHost.removeAllViews();canvasHost.addView(view,new FrameLayout.LayoutParams(-1,-1));
    }

    private void undo(){if(stale||closed)return;boolean applied=false;long savedRevision=-1;try{ProjectStore.Project updated=listener.onUndo(project.id,project.revision);if(updated==null){stale=true;view.setEnabled(false);status.setText("Undo was not applied. Close and reopen this canvas to review the current project.");return;}applied=true;savedRevision=updated.revision;project=ProjectStore.copy(updated);rebuildCanvas();status.setText("Project undo applied · r"+project.revision);}catch(Exception|OutOfMemoryError error){stale=true;status.setText(applied?"Project undo applied at revision "+savedRevision+". Close and reopen to refresh this canvas.":"Undo could not be confirmed; reopen the project before retrying.");if(view!=null){view.setWeightCanvas(null);view.setEnabled(false);}}}
    private void dispose(){if(closed)return;closed=true;if(view!=null)view.setWeightCanvas(null);if(artwork!=null){artwork.recycle();artwork=null;}/* The accepted decoder result remains owned by its completion listener, which recycles it after closure. */}
    private void updateBrush(){if(brushLabel!=null)brushLabel.setText(String.format(Locale.US,"Radius %.3f × image height   ·   Strength %+.2f (%s)",radius,strength,strength<0?"erase":"add"));if(view!=null)view.setWeightBrush(radius,strength,falloff);}
    private void exactBrushDialog(SeekBar radiusSlider,SeekBar strengthSlider){LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(8),dp(18),dp(8));box.addView(text("Radius · .001 to 2 image heights",12));EditText r=number(radius);box.addView(r);box.addView(text("Strength · −1 to 1 per dab",12));EditText s=number(strength);box.addView(s);
        AlertDialog edit=new AlertDialog.Builder(activity).setTitle("Exact weight brush").setView(box).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();edit.setOnShowListener(d->edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{float nextRadius=Float.parseFloat(r.getText().toString()),nextStrength=Float.parseFloat(s.getText().toString());if(!Float.isFinite(nextRadius)||nextRadius<.001f||nextRadius>2||!Float.isFinite(nextStrength)||nextStrength< -1||nextStrength>1)throw new IllegalArgumentException("Use radius .001..2 and strength −1..1");radiusSlider.setProgress(Math.round((nextRadius-.001f)*1000));strengthSlider.setProgress(Math.round((nextStrength+1)*100));radius=nextRadius;strength=nextStrength;updateBrush();edit.dismiss();}catch(Exception error){android.widget.Toast.makeText(activity,error.getMessage(),android.widget.Toast.LENGTH_LONG).show();}}));edit.show();}
    private interface SliderAction{void onChange(int progress);}
    private SeekBar.OnSeekBarChangeListener slider(SliderAction action){return new SeekBar.OnSeekBarChangeListener(){@Override public void onProgressChanged(SeekBar slider,int progress,boolean user){if(user)action.onChange(progress);}@Override public void onStartTrackingTouch(SeekBar slider){}@Override public void onStopTrackingTouch(SeekBar slider){}};}
    private EditText number(float value){EditText field=new EditText(activity);field.setSingleLine();field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);field.setText(Float.toString(value));return field;}
    private TextView text(String value,int size){TextView text=new TextView(activity);text.setText(value);text.setTextSize(size);text.setTextColor(Color.rgb(195,208,227));text.setPadding(0,dp(3),0,dp(3));return text;}
    private Button button(String label,Runnable action){Button button=new Button(activity);button.setText(label);button.setTextSize(10);button.setAllCaps(false);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(8),0,dp(8),0);button.setOnClickListener(v->action.run());return button;}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
