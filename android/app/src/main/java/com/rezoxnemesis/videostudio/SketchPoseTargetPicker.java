package com.rezoxnemesis.videostudio;

import android.app.*;
import android.os.Bundle;
import android.widget.*;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Optional read-only target copying. It never edits the animation or source drawing. */
final class SketchPoseTargetPicker extends LinearLayout implements AutoCloseable {
 private final Activity activity;
 private final JSONObject source,program;
 private final EditText firstFrame,x,y;
 private final BooleanSupplier current;
 private final ArrayList<String[]> choices=new ArrayList<>();
 private final Spinner joint;
 private final Button use;
 private final TextView status;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private AlertDialog dialog;
 private Future<?> pending;
 private boolean closed,busy;
 private int generation;
 SketchPoseTargetPicker(Activity activity,JSONObject immutableSource,JSONObject draft,EditText firstFrame,EditText x,EditText y,BooleanSupplier current)throws Exception {
  super(activity);this.activity=activity;source=immutableSource;program=SketchDocument.copy(draft);this.firstFrame=firstFrame;this.x=x;this.y=y;this.current=current;
  setOrientation(VERTICAL);setContentDescription("Posed contact target picker");TextView help=new TextView(activity);help.setText("Choose a joint to copy its position at the first contact frame. Camera moves keep this drawing-space target in place. This uses your current draft; save the scene to share its pose with ChatGPT.");help.setTextColor(StudioUi.MUTED);addView(help);
  ArrayList<String> labels=new ArrayList<>();JSONArray rigs=program.getJSONArray("rigs");for(int i=0;i<rigs.length();i++){JSONObject rig=rigs.getJSONObject(i);JSONArray points=rig.getJSONArray("joints");for(int j=0;j<points.length();j++){String rigId=rig.getString("id"),jointId=points.getJSONObject(j).getString("id");choices.add(new String[]{rigId,jointId});labels.add(rigId+" / "+jointId);}}
  joint=new Spinner(activity);joint.setContentDescription("Posed contact target joint");joint.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));addView(joint);
  use=new Button(activity);use.setText("Use posed joint target");use.setContentDescription("Use posed joint target");StudioUi.styleButton(use,false);addView(use);
  status=new TextView(activity);status.setContentDescription("Posed contact target status");status.setText("Or enter a fixed target in the fields above. Preview hand, cloth and prop overlap before saving.");status.setTextColor(StudioUi.MUTED);addView(status);use.setEnabled(!choices.isEmpty());use.setOnClickListener(v->{try{sample();}catch(Exception error){status.setText(error.getMessage());}});
 }
 void attach(AlertDialog owner){dialog=owner;owner.setOnDismissListener(d->close());owner.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{close();owner.dismiss();});activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);}
 private void sample()throws Exception {
  if(closed||busy)return;if(!current.getAsBoolean())throw SketchDocument.bad("Animation changed; reopen contact controls");
  int frame=SketchAnimationUiModel.integer(firstFrame.getText().toString(),1,program.getInt("frameCount"),"First contact frame")-1,index=joint.getSelectedItemPosition();if(index<0||index>=choices.size())throw SketchDocument.bad("Choose a target joint");String[] choice=choices.get(index);String beforeFrame=firstFrame.getText().toString(),beforeX=x.getText().toString(),beforeY=y.getText().toString();final int token=++generation;busy=true;controls();status.setText("Reading the native pose…");
  pending=worker.submit(()->{try{JSONObject sample=SketchPoseCoordinates.sample(source,program,frame),point=SketchPoseCoordinates.joint(sample,choice[0],choice[1]);double xx=point.getDouble("x"),yy=point.getDouble("y");if(!Double.isFinite(xx)||!Double.isFinite(yy)||xx<0||xx>1||yy<0||yy>1)throw SketchDocument.bad("This joint lies outside the drawing; move it inside before pinning a contact");
    activity.runOnUiThread(()->{if(unavailable()||token!=generation)return;busy=false;
     if(!current.getAsBoolean()||!beforeFrame.equals(firstFrame.getText().toString())||index!=joint.getSelectedItemPosition()||!beforeX.equals(x.getText().toString())||!beforeY.equals(y.getText().toString()))status.setText("Animation, frame, joint or coordinates changed; manual edits preserved. Sample again.");
     else{x.setText(decimal(xx));y.setText(decimal(yy));status.setText("Copied "+choice[0]+" / "+choice[1]+" at frame "+(frame+1)+". The target stays fixed until you edit or sample again.");}controls();});
   }catch(Exception failure){activity.runOnUiThread(()->{if(unavailable()||token!=generation)return;busy=false;status.setText("Target unchanged: "+failure.getMessage());controls();});}});
 }
 private static String decimal(double value){return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
 private boolean unavailable(){return closed||dialog!=null&&!dialog.isShowing()||activity.isDestroyed()||activity.isFinishing();}
 private void controls(){use.setEnabled(!closed&&!busy&&!choices.isEmpty());if(dialog!=null&&dialog.getButton(AlertDialog.BUTTON_POSITIVE)!=null)dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!closed&&!busy);}
 private void pause(){if(closed||!busy)return;++generation;if(pending!=null)pending.cancel(true);busy=false;status.setText("Sampling paused; target unchanged. Sample again.");controls();}
 @Override public void close(){if(closed)return;closed=true;++generation;if(pending!=null)pending.cancel(true);worker.shutdownNow();activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);}
 private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){if(a==activity)pause();}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){if(a==activity)close();}};
}
