package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Explicit selected VIDEO ownership UI. Source reads/probes/private request I/O remain on its worker. */
final class SelectedVideoImportDialog implements AutoCloseable {
    private final Activity activity;private final String projectId;private final BooleanSupplier current;
    private final Consumer<JSONObject> completed;private final Consumer<SelectedVideoImportDialog> onClosed;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed=new AtomicBoolean(),cancelled=new AtomicBoolean();
    private final AtomicInteger generation=new AtomicInteger();
    private final AlertDialog dialog;private final TextView status;private final Button retry,cancel;
    private Future<?> task;private JSONObject selection;private boolean busy,done,reservationAttempted;

    static SelectedVideoImportDialog picked(Activity activity,String projectId,long revision,Uri uri,String operationId,
            BooleanSupplier current,Consumer<JSONObject> completed,Consumer<SelectedVideoImportDialog> onClosed)throws Exception{
        JSONObject selected=new JSONObject().put("projectId",projectId).put("expectedUpdatedAt",revision).put("operationId",operationId).put("uri",uri.toString());
        return new SelectedVideoImportDialog(activity,projectId,selected,current,completed,onClosed);
    }
    static SelectedVideoImportDialog retry(Activity activity,String projectId,BooleanSupplier current,
            Consumer<JSONObject> completed,Consumer<SelectedVideoImportDialog> onClosed)throws Exception{
        return new SelectedVideoImportDialog(activity,projectId,null,current,completed,onClosed);
    }
    private SelectedVideoImportDialog(Activity activity,String projectId,JSONObject selected,BooleanSupplier current,
            Consumer<JSONObject> completed,Consumer<SelectedVideoImportDialog> onClosed)throws Exception{
        if(projectId==null||!projectId.matches("[A-Za-z0-9_-]{1,96}"))throw new IllegalArgumentException("Open the destination project first");
        this.activity=activity;this.projectId=projectId;this.selection=selected;this.current=current;this.completed=completed;this.onClosed=onClosed;
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);int pad=StudioUi.dp(activity,18);form.setPadding(pad,pad,pad,pad);
        TextView note=new TextView(activity);note.setText("Copy only the video you chose in Files into this project's private media bin. The selected document stays unchanged. No base clip, audio track or overlay is added. The copy is limited to 10 GiB and needs 512 MiB of free reserve.");form.addView(note);
        status=new TextView(activity);status.setContentDescription("Owned video import status");status.setPadding(0,pad,0,pad);form.addView(status);
        retry=new Button(activity);retry.setText("Retry exact video copy");StudioUi.styleButton(retry,false);form.addView(retry);
        cancel=new Button(activity);cancel.setText("Cancel copy");StudioUi.styleButton(cancel,false);form.addView(cancel);
        dialog=new AlertDialog.Builder(activity).setTitle("Import video copy").setView(form).setNegativeButton("Close",null).create();
        dialog.setOnCancelListener(ignored->close());dialog.setOnDismissListener(ignored->close());dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(ignored->close());
        retry.setOnClickListener(ignored->{if(!busy&&!done&&active())start();});cancel.setOnClickListener(ignored->cancel());StudioUi.prepareTouchLayout(form);start();
    }

    private void start(){
        if(!active()||busy||done)return;if(!current.getAsBoolean()){status.setText("Video import not saved: the editing view changed. Reopen its source controls.");return;}
        final int token=generation.incrementAndGet();cancelled.set(false);busy=true;controls();status.setText("Preparing the exact selected video and checking available storage…");
        task=worker.submit(()->{
            try{
                BooleanSupplier admitted=()->token==generation.get()&&!closed.get()&&!cancelled.get();
                JSONObject exact=selection;
                if(exact==null){exact=read(admitted);reservationAttempted=true;}
                else{boolean reserve=!reservationAttempted;reservationAttempted=true;exact=save(exact,reserve,admitted);}
                selection=exact;
                SelectedDocumentVideoImport.Picked prepared;
                if(exact.has("prepared"))prepared=SelectedDocumentVideoImport.restorePicked(exact.getJSONObject("prepared"));
                else{
                    prepared=SelectedDocumentVideoImport.preparePicked(activity,projectId,exact.getLong("expectedUpdatedAt"),Uri.parse(exact.getString("uri")),exact.getString("operationId"));
                    exact.put("prepared",prepared.toJson());exact=save(exact,false,admitted);selection=exact;
                }
                JSONObject result=SelectedDocumentVideoImport.executePicked(activity,prepared,(bytes,total)->{
                    if(token!=generation.get()||cancelled.get()||closed.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Selected video import cancelled");
                    main.post(()->{if(token==generation.get()&&active()&&busy&&!cancelled.get()){if(!current.getAsBoolean()){cancel();return;}status.setText("Copying selected video: "+bytes+" / "+total+" bytes");}});
                },()->token==generation.get()&&!closed.get()&&!cancelled.get());
                main.post(()->{if(token!=generation.get()||!active())return;busy=false;done=true;status.setText("Imported owned video · "+result.optLong("bytes")+" bytes · "+result.optLong("durationMs")+" ms. Source and timeline preserved.");controls();if(current.getAsBoolean()){close();completed.accept(result);}});
            }catch(Exception error){
                main.post(()->{if(token!=generation.get()||!active())return;busy=false;String reason=cancelled.get()?"Cancelled. The exact request is retained for retry.":error.getMessage();status.setText("Video import not saved: "+(reason==null?"Copy failed; the selected source was preserved.":reason));controls();});
            }
        });
    }
    private void cancel(){if(!busy||done)return;generation.incrementAndGet();cancelled.set(true);if(task!=null)task.cancel(true);busy=false;status.setText("Cancellation requested. The source is unchanged; any already committed owned result is kept for exact retry.");controls();}
    private void controls(){retry.setEnabled(!busy&&!done);cancel.setEnabled(busy&&!done&&!cancelled.get());}
    private boolean active(){return !closed.get()&&dialog.isShowing()&&!activity.isFinishing()&&!activity.isDestroyed();}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;generation.incrementAndGet();cancelled.set(true);if(task!=null)task.cancel(true);worker.shutdownNow();main.removeCallbacksAndMessages(null);if(dialog.isShowing())dialog.dismiss();onClosed.accept(this);}

    private JSONObject save(JSONObject request,boolean reserve,BooleanSupplier admission)throws Exception{validateSaved(request);JSONObject saved=SelectedVideoRequestStore.save(activity,projectId,request,reserve,admission);validateSaved(saved);return saved;}
    private JSONObject read(BooleanSupplier admission)throws Exception{JSONObject request=SelectedVideoRequestStore.read(activity,projectId,admission);validateSaved(request);return request;}
    private void validateSaved(JSONObject request)throws Exception{
        java.util.Set<String> fields=new java.util.HashSet<>(java.util.Arrays.asList("projectId","expectedUpdatedAt","operationId","uri","prepared"));for(java.util.Iterator<String> keys=request.keys();keys.hasNext();)if(!fields.contains(keys.next()))throw new IOException("Unexpected saved video request field");
        if(!projectId.equals(request.getString("projectId"))||request.getLong("expectedUpdatedAt")<0||!request.getString("operationId").matches("[A-Za-z0-9_-]{1,96}"))throw new IOException("Selected video request identity changed");
        Uri uri=Uri.parse(request.getString("uri"));if(!"content".equals(uri.getScheme())||uri.getAuthority()==null||uri.getAuthority().isEmpty())throw new IOException("Selected video request is not a Files document");
        if(request.has("prepared")){
            JSONObject prepared=SelectedDocumentVideoImport.restorePicked(request.getJSONObject("prepared")).toJson();
            if(!projectId.equals(prepared.getString("projectId"))||request.getLong("expectedUpdatedAt")!=prepared.getLong("expectedUpdatedAt")||!request.getString("operationId").equals(prepared.getString("operationId"))||!uri.toString().equals(prepared.getJSONObject("source").getString("uri")))throw new IOException("Saved selected video binding changed");
        }
    }
}
