package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Selected-frame controls only. The durable native export service owns PNG creation. */
final class SketchStillPngDialog implements AutoCloseable {
    interface Source {
        String sketchId();
        long revision();
        int frame();
        int frameCount();
        int width();
        int height();
        String name();
        boolean current();
        void awaitReady(boolean retry) throws Exception;
    }

    private static final int OPEN=0,PREPARING=1,ADMITTED=2,CLOSED=3;
    private static final int MAX_REQUEST_CHARS=4096,MAX_JOB_SNAPSHOT_CHARS=512*1024;
    // Existing public RecoveryPlanStore retention and JobManager.persist() bounds.
    private static final int MAX_RECENT_PLANS=24,MAX_RECENT_JOBS=40;
    private final Activity activity;
    private final ExecutorService io;
    private final SharedPreferences preferences;
    private final Source source;
    private final Consumer<String> message;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object admissionLock=new Object();
    private final AtomicInteger state=new AtomicInteger(OPEN);
    private final AtomicBoolean closed=new AtomicBoolean(),polling=new AtomicBoolean();
    private final String preferenceKey;
    private final AlertDialog dialog;
    private final TextView status,savedSummary;
    private final Button save,retry;
    private String savedRequest="",observedRequest="";
    private long observationGeneration,observedSinceMs;
    private final Runnable poll=this::pollJob;

    static SketchStillPngDialog show(Activity activity,ExecutorService io,SharedPreferences preferences,
                                      Source source,Consumer<String> message) throws Exception {
        return new SketchStillPngDialog(activity,io,preferences,source,message);
    }

    private SketchStillPngDialog(Activity activity,ExecutorService io,SharedPreferences preferences,
                                 Source source,Consumer<String> message) throws Exception {
        this.activity=activity;this.io=io;this.preferences=preferences;this.source=source;this.message=message;
        preferenceKey="pendingStillPng_"+source.sketchId();
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);
        form.setBackgroundColor(StudioUi.SURFACE);
        int padding=dp(16);form.setPadding(padding,padding,padding,padding);
        TextView title=label(source.name());title.setTextSize(17);form.addView(title);
        form.addView(label("Frame "+(source.frame()+1)+" of "+source.frameCount()));
        form.addView(label(source.width()+" × "+source.height()+" pixels · revision "+source.revision()));
        form.addView(label("Save this complete drawing frame at its original size. Existing transparency is preserved. "
                +"VideoStudio creates one PNG in a separate media project without changing your drawing or its timing."));
        savedSummary=label("");form.addView(savedSummary);
        status=label("After saving, use Media Bin → Import from project to choose the PNG for an editor project.");form.addView(status);
        save=new Button(activity);save.setText("Save PNG");save.setMinHeight(dp(48));StudioUi.styleButton(save,true);form.addView(save);
        retry=new Button(activity);retry.setText("Retry saved PNG");retry.setMinHeight(dp(48));StudioUi.styleButton(retry,false);form.addView(retry);
        restoreRequest();
        ScrollView scroll=new ScrollView(activity);scroll.setBackgroundColor(StudioUi.SURFACE);scroll.addView(form);
        dialog=new AlertDialog.Builder(activity).setTitle("Save current frame as PNG").setView(scroll)
                .setNegativeButton("Close",null).create();
        dialog.setOnCancelListener(d->close());
        dialog.setOnDismissListener(d->close());
        save.setOnClickListener(v->{
            try {
                JSONObject request=new JSONObject().put("sketchId",source.sketchId()).put("expectedRevision",source.revision())
                        .put("operationId",UUID.randomUUID().toString()).put("format","png").put("frame",source.frame());
                submit(request.toString(),false);
            }catch(Exception failure){message.accept(failure.getMessage());}
        });
        retry.setOnClickListener(v->{if(!savedRequest.isEmpty())submit(savedRequest,true);});
        dialog.show();
        // Builder button callbacks are posted to the dialog Handler. Revoke admission
        // in the click itself, before a queued worker can overtake that callback.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->close());
    }

    private TextView label(String text) {
        TextView view=new TextView(activity);view.setText(text);view.setTextColor(StudioUi.TEXT);
        view.setTextSize(14);view.setPadding(0,dp(5),0,dp(5));return view;
    }
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private boolean live(){return !closed.get()&&dialog.isShowing()&&!activity.isFinishing()&&!activity.isDestroyed()&&source.current();}

    private void restoreRequest() {
        String raw=preferences.getString(preferenceKey,"");
        try {
            if(raw==null||raw.isEmpty())return;
            if(raw.length()>MAX_REQUEST_CHARS)throw new IllegalArgumentException("Saved PNG request exceeds its limit");
            JSONObject request=SketchStillExport.validate(new JSONObject(raw));
            if(!source.sketchId().equals(request.getString("sketchId")))throw new IllegalArgumentException("Saved PNG belongs to another drawing");
            savedRequest=raw;
            savedSummary.setText("Saved request: frame "+(request.getInt("frame")+1)+" · revision "+request.getLong("expectedRevision")
                    +". Retry keeps this exact source, frame and operation; it does not use the newly selected frame.");
        }catch(Exception invalid){savedSummary.setText("The saved request cannot be retried: "+invalid.getMessage());}
        finally{retry.setEnabled(!savedRequest.isEmpty());}
    }

    private void submit(String raw,boolean exactRetry) {
        if(!live()||!state.compareAndSet(OPEN,PREPARING))return;
        final long observation=++observationGeneration;
        main.removeCallbacks(poll);
        save.setEnabled(false);retry.setEnabled(false);status.setText(exactRetry?"Checking the exact saved PNG request…":"Checking the selected saved frame…");
        io.execute(()->{
            try {
                if(!live()||state.get()!=PREPARING)return;
                JSONObject request=SketchStillExport.validate(new JSONObject(raw));
                if(!source.sketchId().equals(request.getString("sketchId")))throw new IllegalStateException("The saved request belongs to another drawing");
                source.awaitReady(exactRetry);
                if(!live()||state.get()!=PREPARING)return;
                // Close/navigation and this admission checkpoint have one ordering.
                synchronized(admissionLock){if(!live()||!state.compareAndSet(PREPARING,ADMITTED))return;}
                if(!preferences.edit().putString(preferenceKey,raw).commit())throw new IllegalStateException("Could not retain the PNG request for retry");
                final long submittedAt=System.currentTimeMillis();
                activity.startForegroundService(new Intent(activity,ControlService.class)
                        .setAction(ControlService.ACTION_LOCAL_SKETCH_EXPORT).putExtra("requestJson",raw));
                post(()->{
                    if(observation!=observationGeneration)return;
                    savedRequest=raw;observedRequest=raw;
                    observedSinceMs=submittedAt;
                    savedSummary.setText("Saved request: frame "+(request.optInt("frame")+1)+" · revision "+request.optLong("expectedRevision")
                            +". Retry keeps this exact source, frame and operation.");
                    status.setText("PNG save submitted. Jobs / Activity shows its native progress. After completion: Media Bin → Import from project.");
                    state.compareAndSet(ADMITTED,OPEN);save.setEnabled(true);retry.setEnabled(true);
                    main.removeCallbacks(poll);main.postDelayed(poll,1000);
                });
            }catch(Exception failure){post(()->{
                if(observation!=observationGeneration)return;
                state.compareAndSet(PREPARING,OPEN);state.compareAndSet(ADMITTED,OPEN);
                restoreRequest();save.setEnabled(true);status.setText("PNG was not confirmed: "+failure.getMessage()+". Your drawing is unchanged; an exact saved request remains available for retry.");
            });}
        });
    }

    /** Read the existing service's durable snapshot; never construct a second JobManager. */
    private void pollJob() {
        if(!live()||observedRequest.isEmpty()||!polling.compareAndSet(false,true))return;
        final String requestText=observedRequest;
        final long observation=observationGeneration,since=observedSinceMs;
        io.execute(()->{
            JSONObject found=null,verified=null;String error=null;
            try {
                JSONObject request=SketchStillExport.validate(new JSONObject(requestText));
                String target=SketchStillExport.outputProjectId(request.getString("sketchId"),request.getString("operationId"));
                String requestHash=requestHash(request);
                JSONObject plan=matchingPlan(requestHash,target,since);
                String jobId=plan!=null&&plan.opt("jobId") instanceof String?plan.getString("jobId"):"";
                String raw=activity.getSharedPreferences("videostudio_native_v1",Activity.MODE_PRIVATE)
                        .getString(ExecutionTruthPolicy.JOB_RECOVERY_PREF_KEY,"[]");
                if(raw!=null&&raw.length()>MAX_JOB_SNAPSHOT_CHARS)throw new IllegalStateException("Native PNG job status exceeds the read limit");
                if(raw!=null){
                    JSONArray jobs=new JSONArray(raw);
                    for(int i=0;i<Math.min(MAX_RECENT_JOBS,jobs.length());i++){
                        JSONObject job=jobs.optJSONObject(i),result=job==null?null:job.optJSONObject("result");
                        if(job==null)continue;
                        // The service attaches the exact plan/job before writing any output.
                        // A failure/cancellation therefore needs no result payload to be visible.
                        boolean attached=!jobId.isEmpty()&&jobId.equals(job.opt("id"));
                        boolean completed=JobManager.STATE_COMPLETED.equals(job.opt("state"));
                        // Receipt-first completion can still be recognized after old plans age
                        // out, but a shallow format/output result cannot attribute a failure.
                        if(!attached&&(plan!=null||!completed||!receiptMatches(result,requestHash,target)))continue;
                        found=job;
                        if(completed){
                            if(!receiptMatches(result,requestHash,target))throw new IllegalStateException("Completed PNG job has no receipt bound to the saved request");
                            verified=SketchStillExport.verify(activity,result);
                        }
                        break;
                    }
                }
            }catch(Exception failure){error=failure.getMessage();}
            final JSONObject job=found,receipt=verified;final String failure=error;
            polling.set(false);
            post(()->{
                if(observation!=observationGeneration||!requestText.equals(observedRequest))return;
                if(failure!=null){status.setText("PNG completion could not be verified: "+failure+". See Jobs / Activity, or retry the same saved request.");return;}
                if(receipt!=null){
                    String project=receipt.optString("outputProjectName",receipt.optString("projectName",receipt.optString("outputProjectId")));
                    String asset=receipt.optString("assetName","PNG frame "+(receipt.optInt("frame")+1));
                    status.setText("PNG saved and verified · "+receipt.optInt("width")+" × "+receipt.optInt("height")+" pixels.\n"
                            +"Media Bin → Import from project → "+project+" → "+asset+". Your active editor project was not changed.");
                    return;
                }
                if(job!=null){String jobState=job.optString("state");
                    if(JobManager.STATE_FAILED.equals(jobState)||JobManager.STATE_CANCELLED.equals(jobState)){
                        status.setText("PNG save "+jobState+": "+job.optString("detail")+". Retry saved PNG keeps the original request.");return;
                    }
                }
                main.postDelayed(poll,1500);
            });
        });
    }

    /** Only the strict public PNG request binds status; service metadata is not caller proof. */
    private JSONObject matchingPlan(String expectedHash,String target,long since)throws Exception {
        JSONArray plans=new RecoveryPlanStore(activity).recent(MAX_RECENT_PLANS);
        for(int i=0;i<Math.min(MAX_RECENT_PLANS,plans.length());i++){
            JSONObject plan=plans.optJSONObject(i);
            if(plan==null||!"sketch_render".equals(plan.opt("action"))||!target.equals(plan.opt("projectId")))continue;
            // A newly submitted exact retry observes its new attempt rather than an older
            // terminal attempt while the service is still admitting the queued request.
            if(plan.optLong("createdAt",0)<since)continue;
            JSONObject parameters=plan.optJSONObject("parameters");if(parameters==null||parameters.length()>MAX_REQUEST_CHARS)continue;
            try{
                JSONObject publicRequest=new JSONObject();java.util.Iterator<String> keys=parameters.keys();
                while(keys.hasNext()){String key=keys.next();if(!key.startsWith("_"))publicRequest.put(key,parameters.get(key));}
                JSONObject normalized=SketchStillExport.validate(publicRequest);
                if(expectedHash.equals(requestHash(normalized)))return plan;
            }catch(Exception invalid){/* Another/malformed request is never this dialog's status. */}
        }
        return null;
    }

    private static boolean receiptMatches(JSONObject receipt,String expectedHash,String target){
        if(receipt==null||!"png".equals(receipt.opt("format"))||!target.equals(receipt.opt("projectId"))
                ||!target.equals(receipt.opt("outputProjectId"))||!expectedHash.equals(receipt.opt("requestSha256")))return false;
        try{
            JSONObject request=SketchStillExport.validate(new JSONObject().put("sketchId",receipt.get("sketchId"))
                    .put("expectedRevision",receipt.get("sourceRevision")).put("operationId",receipt.get("operationId"))
                    .put("format",receipt.get("format")).put("frame",receipt.get("frame")));
            return expectedHash.equals(requestHash(request));
        }catch(Exception invalid){return false;}
    }

    private static String requestHash(JSONObject request)throws Exception {
        return SketchDocument.sha256(SketchDocument.canonical(request).getBytes(StandardCharsets.UTF_8));
    }

    private void post(Runnable work){if(!closed.get())main.post(()->{if(live())work.run();});}
    boolean matches(String sketchId,long revision){return source.sketchId().equals(sketchId)&&source.revision()==revision;}
    @Override public void close(){
        synchronized(admissionLock){if(!closed.compareAndSet(false,true))return;state.set(CLOSED);}
        main.removeCallbacksAndMessages(null);
        if(dialog.isShowing())dialog.dismiss();
    }
}
