package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** Owner-selected original-byte transfers. Status inspection never scans a remote folder. */
final class EditorSourceArchiveDialog {
    private static final String PREFS="videostudio_native_v1";
    private final Activity activity;
    private final ProjectStore store;
    private final StorageProfiles storage;
    private SourceMediaVault vault;
    private final ExecutorService worker;
    private ProjectStore.Project project;
    private ProjectStore.Asset asset;
    private final String projectId,assetId;
    private final Runnable onProjectChanged,onOpenStorage;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final TextView sourceStatus,archiveStatus,transferStatus;
    private final ProgressBar progress;
    private final Button archiveButton,restoreButton,cancelButton,resumeButton,forgetButton;
    private final AlertDialog dialog;
    private final String pendingKey;
    private JSONArray archives=new JSONArray();
    private boolean loading;
    private String refreshedRequest="";
    private String activeRequest="";
    private JSONObject lastSession=new JSONObject();
    private final Runnable poll=this::refresh;

    static void show(Activity activity,ProjectStore store,StorageProfiles storage,ExecutorService worker,
                     ProjectStore.Project project,ProjectStore.Asset asset,Runnable onProjectChanged,Runnable onOpenStorage) {
        new EditorSourceArchiveDialog(activity,store,storage,worker,project,asset,onProjectChanged,onOpenStorage);
    }

    private EditorSourceArchiveDialog(Activity activity,ProjectStore store,StorageProfiles storage,ExecutorService worker,
                                      ProjectStore.Project project,ProjectStore.Asset asset,Runnable onProjectChanged,Runnable onOpenStorage) {
        this.activity=activity;this.store=store;this.storage=storage;this.worker=worker;
        this.project=project;this.asset=asset;this.projectId=project.id;this.assetId=asset.id;
        this.onProjectChanged=onProjectChanged;this.onOpenStorage=onOpenStorage;
        prefs=activity.getSharedPreferences(PREFS,Activity.MODE_PRIVATE);
        pendingKey="source_archive_owner_request";
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(12),dp(20),dp(16));
        content.addView(text("Archive this original's bytes in resumable chunks to a selected storage folder. Your current source stays in place. Restoring verifies the bytes, creates an owned copy and attempts a revision-checked relink."));
        sourceStatus=text(sourceSummary());content.addView(sourceStatus);
        archiveStatus=text("Reading local archive records…");content.addView(archiveStatus);
        transferStatus=text("");content.addView(transferStatus);
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setVisibility(android.view.View.GONE);content.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        archiveButton=button("Archive original to selected folder",this::archive);content.addView(archiveButton);
        restoreButton=button("Choose archived generation to restore",this::chooseRestore);restoreButton.setEnabled(false);content.addView(restoreButton);
        cancelButton=button("Cancel current transfer",this::cancel);cancelButton.setEnabled(false);content.addView(cancelButton);
        resumeButton=button("Resume retained transfer",this::resume);resumeButton.setEnabled(false);content.addView(resumeButton);
        forgetButton=button("Forget cancelled retained transfer",this::forget);forgetButton.setEnabled(false);content.addView(forgetButton);
        content.addView(button("Refresh local status",this::refresh));
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        dialog=new AlertDialog.Builder(activity).setTitle("Original source archive").setView(scroll).setNegativeButton("Close",null).create();
        dialog.setOnDismissListener(ignored->main.removeCallbacks(poll));dialog.show();refresh();
    }

    private void refresh() {
        main.removeCallbacks(poll);if(!active()||loading)return;loading=true;
        try { worker.execute(()->{
            JSONArray listed=new JSONArray();JSONObject local=new JSONObject(),session=new JSONObject();String failure="";
            ProjectStore.Project current=null;
            try{if(vault==null)vault=new SourceMediaVault(activity,store);listed=vault.localArchives(projectId,assetId);local=vault.localStatus(projectId,assetId);session=readSession();current=store.get(projectId);}
            catch(Exception error){failure=error.getMessage()==null?"Could not read local archive status":error.getMessage();}
            final JSONArray result=listed;final JSONObject state=local,transfer=session;final String error=failure;final ProjectStore.Project latest=current;
            main.post(()->{loading=false;if(!active())return;archives=result;
                if(latest!=null&&latest.asset(assetId)!=null){project=latest;asset=latest.asset(assetId);sourceStatus.setText(sourceSummary());}
                archiveStatus.setText(error.isEmpty()?localSummary(state,result):error);
                renderTransfer(transfer);main.postDelayed(poll,2000);
            });
        });}catch(RejectedExecutionException ignored){loading=false;}
    }

    private JSONObject readSession() throws Exception {
        String key=ControlService.KEY_MANUAL_SOURCE_MEDIA_SESSION;
        JSONObject pending=new JSONObject(prefs.getString(pendingKey,"{}"));
        if(!projectId.equals(pending.optString("projectId"))||!assetId.equals(pending.optString("assetId")))pending=new JSONObject();
        JSONObject latest=new JSONObject(prefs.getString(key+":"+projectId+":"+assetId,"{}"));
        if(!pending.has("requestId")||latest.optLong("updatedAt")>=pending.optLong("startedAt"))return latest;
        JSONObject exact=new JSONObject(prefs.getString(key+":"+pending.optString("requestId"),"{}"));
        if(pending.optString("requestId").equals(exact.optString("requestId"))&&exact.optLong("updatedAt")>=pending.optLong("startedAt"))return exact;
        pending.put("status","preparing");pending.put("detail","Waiting for native service admission");return pending;
    }

    private String localSummary(JSONObject status,JSONArray records) {
        int committed=0;for(int index=0;index<records.length();index++){JSONObject record=records.optJSONObject(index);if(record!=null&&record.optBoolean("committed"))committed++;}
        String summary=committed==0?"No completed archive recorded for this original.":committed+" completed archive generation"+(committed==1?"":"s")+" recorded locally.";
        JSONArray uploads=status.optJSONArray("uploads");
        if(uploads!=null)for(int index=0;index<Math.min(3,uploads.length());index++){
            JSONObject upload=uploads.optJSONObject(index);if(upload==null)continue;
            summary+="\nUpload journal · "+upload.optString("state","pending").replace('_',' ')+" · "+bytes(upload.optLong("verifiedUploadedBytes",0))+" chunks verified";
        }
        if(uploads!=null&&uploads.length()>3)summary+="\n+"+(uploads.length()-3)+" retained upload journals";
        JSONArray restores=status.optJSONArray("restores");if(restores!=null&&restores.length()>0)summary+="\n"+restores.length()+" retained restore publication record"+(restores.length()==1?"":"s");
        if(status.optLong("chunkBytes")>0)summary+="\nChunk size: "+bytes(status.optLong("chunkBytes"))+" · source limit: "+bytes(status.optLong("maxSourceBytes",-1));
        return summary+"\nArchive records describe committed metadata. Restore checks the actual source bytes.";
    }

    private void renderTransfer(JSONObject session) {
        String status=session.optString("status"),request=session.optString("requestId");
        lastSession=session;
        activeRequest=request;
        boolean suspended="checkpointed".equals(status)||"cancelled".equals(status);
        boolean running=!status.isEmpty()&&!terminal(status)&&!suspended;
        String message=status.isEmpty()?"Choose Archive or Restore to start an owner transfer.":session.optString("operation","Source transfer")+" · "+status.replace('_',' ')+"\n"+session.optString("detail");
        JSONObject receipt=session.optJSONObject("receipt");
        if(receipt!=null&&"restore".equals(session.optString("operation"))&&terminal(status)&&("completed".equals(status)||"success".equals(status))){
            message+=receipt.optBoolean("relinked")?"\nVerified owned copy relinked to this source.":"\nVerified owned copy saved in Media Bin. The original source registration was not changed.";
        }
        transferStatus.setText(message);progress.setVisibility(status.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);
        progress.setIndeterminate(running&&!session.has("progress"));progress.setProgress(session.optInt("progress",0));
        archiveButton.setEnabled(!running);restoreButton.setEnabled(!running&&hasCommittedArchive());
        cancelButton.setText("checkpointed".equals(status)?"Cancel retained transfer":"Cancel current transfer");
        cancelButton.setEnabled(!request.isEmpty()&&((running&&!"preparing".equals(status)&&!"forgetting".equals(status))||("checkpointed".equals(status)&&session.optBoolean("resumeReady"))));
        resumeButton.setEnabled(suspended&&!request.isEmpty()&&session.optBoolean("resumeReady",false));
        forgetButton.setEnabled("cancelled".equals(status)&&!request.isEmpty()&&session.optBoolean("forgetReady",false));
        if(!request.isEmpty()&&"restore".equals(session.optString("operation"))&&("completed".equals(status)||"success".equals(status))&&!request.equals(refreshedRequest)){refreshedRequest=request;onProjectChanged.run();}
    }

    private void archive() {
        Uri tree=storage.activeTree();
        if(tree==null){new AlertDialog.Builder(activity).setTitle("Choose a storage folder").setMessage("Link and select a folder in Storage Hub before archiving this original.").setPositiveButton("Open Storage Hub",(d,w)->{dialog.dismiss();onOpenStorage.run();}).setNegativeButton("Cancel",null).show();return;}
        final String selectedTree=tree.toString();
        final long revision=project.revision;
        new AlertDialog.Builder(activity).setTitle("Archive original bytes").setMessage(asset.name+"\n"+bytes(asset.sizeBytes)+"\nProvider: "+tree.getAuthority()+"\n\nThis copies the registered source to the exact selected folder. Your current source is kept. Available folder space is not reported by Android.")
                .setPositiveButton("Archive original",(d,w)->start("archive",selectedTree,"",revision)).setNegativeButton("Cancel",null).show();
    }

    private boolean hasCommittedArchive(){for(int index=0;index<archives.length();index++){JSONObject record=archives.optJSONObject(index);if(record!=null&&record.optBoolean("committed")&&!record.optString("generationId").isEmpty())return true;}return false;}

    private void chooseRestore() {
        ArrayList<JSONObject> choices=new ArrayList<>();
        for(int index=0;index<archives.length();index++){JSONObject record=archives.optJSONObject(index);if(record!=null&&record.optBoolean("committed")&&!record.optString("generationId").isEmpty())choices.add(record);}
        String[] labels=new String[choices.size()];for(int index=0;index<labels.length;index++){
            JSONObject record=choices.get(index);long created=record.optLong("createdAt");
            String generation=record.optString("generationId");
            labels[index]=(created>0?DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(created)):"Recorded archive")+" · "+bytes(record.optLong("sourceBytes",-1))+"\n"+record.optString("providerAuthority","Selected provider")+" · "+record.optLong("chunkCount")+" chunks · "+generation.substring(0,Math.min(8,generation.length()));
        }
        new AlertDialog.Builder(activity).setTitle("Restore an exact source generation").setItems(labels,(d,index)->{
            JSONObject record=choices.get(index);String tree=record.optString("archiveTreeUri"),generation=record.optString("generationId");final long revision=project.revision;
            new AlertDialog.Builder(activity).setTitle("Verify and restore source").setMessage("Download and hash-check "+bytes(record.optLong("sourceBytes",-1))+" from this generation's recorded folder. The owned copy will replace this source only if the admitted revision and clip constraints allow it; otherwise it will be registered separately in Media Bin.")
                    .setPositiveButton("Restore owned copy",(confirmed,w)->start("restore",tree,generation,revision)).setNegativeButton("Cancel",null).show();
        }).setNegativeButton("Cancel",null).show();
    }

    private void start(String operation,String tree,String generation,long revision) {
        String request=UUID.randomUUID().toString();
        Intent intent=new Intent(activity,ControlService.class).setAction(ControlService.ACTION_LOCAL_SOURCE_MEDIA)
                .putExtra("operation",operation).putExtra("projectId",projectId).putExtra("assetId",assetId)
                .putExtra("expectedRevision",revision).putExtra("requestId",request).putExtra("storageTreeUri",tree);
        if(!generation.isEmpty())intent.putExtra("generationId",generation);
        try{
            JSONObject pending=new JSONObject();pending.put("requestId",request);pending.put("projectId",projectId);pending.put("assetId",assetId);pending.put("operation",operation);pending.put("startedAt",System.currentTimeMillis());
            prefs.edit().putString(pendingKey,pending.toString()).apply();activity.startForegroundService(intent);
            transferStatus.setText("Preparing owner "+operation+" request…");archiveButton.setEnabled(false);restoreButton.setEnabled(false);progress.setVisibility(android.view.View.VISIBLE);progress.setIndeterminate(true);refresh();
        }catch(Exception error){prefs.edit().remove(pendingKey).apply();Toast.makeText(activity,error.getMessage()==null?"Could not start original-source transfer":error.getMessage(),Toast.LENGTH_LONG).show();refresh();}
    }

    private void cancel() {
        if(activeRequest.isEmpty())return;final String request=activeRequest;
        new AlertDialog.Builder(activity).setTitle("Cancel this owner transfer?").setMessage("Stop this source transfer. Verified chunks and the retained upload journal stay available for explicit recovery. The original source remains in place.")
                .setPositiveButton("Cancel transfer",(d,w)->{
                    try{activity.startForegroundService(new Intent(activity,ControlService.class).setAction(ControlService.ACTION_CANCEL_LOCAL_SOURCE_MEDIA).putExtra("requestId",request));cancelButton.setEnabled(false);transferStatus.setText("Cancellation requested · waiting for the native transfer checkpoint");main.postDelayed(poll,500);}
                    catch(Exception error){Toast.makeText(activity,error.getMessage()==null?"Could not cancel this transfer":error.getMessage(),Toast.LENGTH_LONG).show();refresh();}
                }).setNegativeButton("Keep transferring",null).show();
    }

    private void resume() {
        final String request=lastSession.optString("requestId"),operation=lastSession.optString("operation");
        if(request.isEmpty()||!lastSession.optBoolean("resumeReady")||!("archive".equals(operation)||"restore".equals(operation)))return;
        new AlertDialog.Builder(activity).setTitle("Resume retained owner transfer?").setMessage("Continue the same source admission and recorded folder. Verified chunks or the completed owned restore are reused after their checks. The currently selected storage folder does not replace the retained destination.")
                .setPositiveButton("Resume transfer",(d,w)->{
                    try{
                        JSONObject pending=new JSONObject();pending.put("requestId",request);pending.put("projectId",projectId);pending.put("assetId",assetId);pending.put("operation",operation);pending.put("startedAt",System.currentTimeMillis());prefs.edit().putString(pendingKey,pending.toString()).apply();
                        activity.startForegroundService(new Intent(activity,ControlService.class).setAction(ControlService.ACTION_LOCAL_SOURCE_MEDIA).putExtra("requestId",request).putExtra("projectId",projectId).putExtra("assetId",assetId).putExtra("operation",operation).putExtra("resume",true));
                        resumeButton.setEnabled(false);archiveButton.setEnabled(false);restoreButton.setEnabled(false);transferStatus.setText("Preparing explicit owner resume…");refresh();
                    }catch(Exception error){prefs.edit().remove(pendingKey).apply();Toast.makeText(activity,error.getMessage()==null?"Could not resume retained transfer":error.getMessage(),Toast.LENGTH_LONG).show();refresh();}
                }).setNegativeButton("Cancel",null).show();
    }

    private void forget() {
        final String request=lastSession.optString("requestId");
        if(request.isEmpty()||!"cancelled".equals(lastSession.optString("status"))||!lastSession.optBoolean("forgetReady"))return;
        new AlertDialog.Builder(activity).setTitle("Forget retained transfer?").setMessage("Release this cancelled request's local journal, source pin and recovery record. This request will no longer resume. Original media, committed archive records and remote archive files stay in place.")
                .setPositiveButton("Forget retained transfer",(d,w)->{
                    try{activity.startForegroundService(new Intent(activity,ControlService.class).setAction(ControlService.ACTION_FORGET_LOCAL_SOURCE_MEDIA).putExtra("requestId",request));forgetButton.setEnabled(false);resumeButton.setEnabled(false);archiveButton.setEnabled(false);restoreButton.setEnabled(false);transferStatus.setText("Releasing this exact cancelled transfer…");main.postDelayed(poll,500);}
                    catch(Exception error){Toast.makeText(activity,error.getMessage()==null?"Could not forget this cancelled transfer":error.getMessage(),Toast.LENGTH_LONG).show();refresh();}
                }).setNegativeButton("Keep recovery record",null).show();
    }

    private boolean active(){return dialog.isShowing()&&!activity.isFinishing()&&!activity.isDestroyed();}
    private String sourceSummary(){return "Original: "+asset.name+"\nSize: "+bytes(asset.sizeBytes)+"\nNative revision: "+project.revision+"\nFolder quota: unknown";}
    private static boolean terminal(String state){return "completed".equals(state)||"success".equals(state)||"failed".equals(state)||"cancelled".equals(state)||"forgotten".equals(state);}
    private static String bytes(long size){return size<0?"Not reported":String.format(Locale.US,"%.1f %s",size>1073741824L?size/1073741824d:size/1048576d,size>1073741824L?"GiB":"MiB");}
    private TextView text(String value){TextView view=new TextView(activity);view.setTextColor(Color.rgb(230,235,246));view.setTextSize(13);view.setText(value);view.setPadding(0,dp(6),0,dp(6));return view;}
    private Button button(String label,Runnable action){Button view=new Button(activity);view.setText(label);view.setOnClickListener(v->action.run());view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));return view;}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
