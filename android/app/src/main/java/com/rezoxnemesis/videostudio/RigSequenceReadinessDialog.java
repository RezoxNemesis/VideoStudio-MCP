package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Read-only, occurrence-preserving preparation checklist. Native inspection remains separate. */
final class RigSequenceReadinessDialog {
    interface MissingActor {void open(String assetId,boolean black,JSONObject selection);}
    private final Activity activity;
    private final ProjectStore projects;
    private final String projectId;
    private final MissingActor missingActor;
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1));
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LinearLayout rows;
    private final TextView status;
    private final AlertDialog dialog;
    private AlertDialog programDialog;
    private JSONObject selection,result;
    private boolean busy;
    private volatile boolean closed;

    static void show(Activity owner,ProjectStore projects,String projectId,JSONObject selection,MissingActor missingActor){new RigSequenceReadinessDialog(owner,projects,projectId,selection,missingActor).open();}
    private RigSequenceReadinessDialog(Activity owner,ProjectStore projects,String projectId,JSONObject selection,MissingActor missingActor){
        this.activity=owner;this.projects=projects;this.projectId=projectId;this.selection=selection;this.missingActor=missingActor;
        LinearLayout content=new LinearLayout(owner);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(8),dp(4),dp(8),dp(4));
        status=label("Reading ordered preparation checklist…");content.addView(status);
        content.addView(label("Saved fields only. Source bytes, visible anatomy, contacts and cinematic approval require separate inspection/admission. No drafts are saved here."));
        LinearLayout actions=new LinearLayout(owner);actions.addView(button("Next missing actor",this::nextMissing),new LinearLayout.LayoutParams(0,dp(44),1));actions.addView(button("Program order…",this::chooseProgram),new LinearLayout.LayoutParams(0,dp(44),1));content.addView(actions);
        rows=new LinearLayout(owner);rows.setOrientation(LinearLayout.VERTICAL);ScrollView scroll=new ScrollView(owner);scroll.addView(rows);content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(330)));
        dialog=new AlertDialog.Builder(owner).setTitle("Sequence rig preparation").setView(content).setPositiveButton("Close",null).create();
        dialog.setOnDismissListener(d->dispose());
    }
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private TextView label(String text){TextView view=new TextView(activity);view.setText(text);view.setTextSize(11);view.setTextColor(Color.WHITE);view.setPadding(dp(4),dp(4),dp(4),dp(4));return view;}
    private Button button(String text,Runnable action){Button button=new Button(activity);button.setText(text);button.setTextSize(11);button.setAllCaps(false);button.setOnClickListener(v->{if(!busy&&!closed)action.run();});return button;}
    private void open(){dialog.show();dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View view){}public void onViewDetachedFromWindow(View view){dispose();}});load();}
    private void dispose(){if(closed)return;closed=true;worker.shutdownNow();if(programDialog!=null)programDialog.dismiss();}
    private interface Read {JSONObject run()throws Exception;}
    private interface Delivery {void show(JSONObject value)throws Exception;}
    private void background(String text,Read read,Delivery delivery){
        if(busy||closed)return;busy=true;status.setText(text);
        try{worker.execute(()->{JSONObject value=null;String failure=null;try{value=read.run();}catch(Exception|OutOfMemoryError error){failure=error.getMessage()==null?"preparation checklist unavailable":error.getMessage();}
            final JSONObject ready=value;final String problem=failure;main.post(()->{if(closed||activity.isFinishing()||activity.isDestroyed())return;busy=false;if(problem!=null){status.setText("Checklist unavailable: "+problem+". Original images and profiles remain unchanged.");return;}try{delivery.show(ready);}catch(Exception error){status.setText("Checklist unavailable: "+error.getMessage());}});
        });}catch(RejectedExecutionException full){busy=false;status.setText("Current checklist read is still finishing; reopen when it completes");}
    }
    private void load(){background("Reading exact ordered saved-profile fields…",()->{JSONObject request=selection==null?new JSONObject():new JSONObject(selection.toString());request.put("projectId",projectId);return NativeRigBatchAuthoring.status(activity.getApplicationContext(),projects,request);},this::display);}
    private void display(JSONObject value)throws Exception{
        result=value;rows.removeAllViews();JSONArray occurrences=value.getJSONArray("occurrences");
        status.setText(value.getString("orderOrigin")+" · "+occurrences.length()+" ordered source occurrences · "+(value.optBoolean("preparationComplete")?"saved fields present; inspection/admission still required":"missing actor preparation"));
        ProjectStore.Project project=projects.get(projectId);
        for(int i=0;i<occurrences.length();i++){
            JSONObject row=occurrences.getJSONObject(i);String id=row.getString("assetId");ProjectStore.Asset asset=project==null?null:project.asset(id);
            rows.addView(label((i+1)+" · "+(asset==null?"Unavailable source":asset.name)+"\n"+id));
            LinearLayout actors=new LinearLayout(activity);
            for(boolean black:new boolean[]{true,false}){
                String actor=black?"black":"white";JSONObject fields=row.getJSONObject(actor);JSONArray missing=fields.getJSONArray("missing");String text=(black?"Black":"White")+" · "+(fields.optBoolean("annotationPresent")?"fields supplied":missing.toString());
                Button open=button(text,()->navigate(id,black));open.setContentDescription("Open "+actor+" source annotation at ordered occurrence "+(i+1)+" for "+id);actors.addView(open,new LinearLayout.LayoutParams(0,dp(54),1));
            }
            rows.addView(actors);
        }
    }
    private void nextMissing(){if(result==null)return;JSONObject next=result.optJSONObject("nextMissing");if(next==null){status.setText("Saved fields are present. Verify each original and author/admit Contacts separately.");return;}navigate(next.optString("assetId"),"black".equals(next.optString("actor")));}
    private void navigate(String assetId,boolean black){JSONObject order=selection;dialog.dismiss();missingActor.open(assetId,black,order);}
    private void chooseProgram(){background("Reading saved ordered program identities…",()->new JSONObject().put("programs",new StoryboardProgramStore(activity.getApplicationContext(),projects).list(projectId)),value->{
        JSONArray programs=value.getJSONArray("programs");String[] labels=new String[programs.length()];for(int i=0;i<labels.length;i++){JSONObject program=programs.getJSONObject(i);labels[i]=program.optString("name")+" · "+program.getString("programId");}
        status.setText("Select a saved program to inspect its exact repeated source order and profile bindings");
        programDialog=new AlertDialog.Builder(activity).setTitle("Saved program sequence").setItems(labels,(d,index)->{try{JSONObject program=programs.getJSONObject(index);selection=new JSONObject().put("programId",program.getString("programId"));if(program.has("sha256"))selection.put("expectedProgramSha256",program.getString("sha256"));load();}catch(Exception error){status.setText("Program sequence unavailable: "+error.getMessage());}}).setNeutralButton("Imported image order",(d,w)->{selection=null;load();}).setNegativeButton("Cancel",null).create();programDialog.show();
    });}
}
