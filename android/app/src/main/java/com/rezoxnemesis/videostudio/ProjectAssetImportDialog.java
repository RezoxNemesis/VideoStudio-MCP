package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.util.AtomicFile;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Explicit project-media selection and checksum inspection, then the shared cancellable service job. */
public final class ProjectAssetImportDialog {
    private final Activity activity;private final String destinationId;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<ProjectStore.Project> projects=new ArrayList<>();private final List<ProjectStore.Asset> assets=new ArrayList<>();
    private final Spinner source,asset;private final CheckBox append;private final EditText operation;private final TextView status;
    private final Button inspect,submit,retry;private AlertDialog dialog;private Future<?> task;
    private JSONObject inspected;private long destinationRevision;private boolean closed,busy,queued,binding,savedAvailable;private int generation;

    public static void show(Activity activity,String projectId){
        try{open(activity,projectId);}catch(Exception error){new AlertDialog.Builder(activity).setTitle("Import from project").setMessage(error.getMessage()).setPositiveButton("Close",null).show();}
    }
    static ProjectAssetImportDialog open(Activity activity,String projectId)throws Exception {ProjectAssetImportDialog owner=new ProjectAssetImportDialog(activity,projectId);owner.show();return owner;}
    private ProjectAssetImportDialog(Activity a,String projectId)throws Exception {
        if(a==null||projectId==null||!projectId.matches("[A-Za-z0-9_-]{1,96}"))throw new IllegalArgumentException("Open a destination project first");activity=a;destinationId=projectId;
        LinearLayout form=new LinearLayout(a);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,12,20,12);
        label(form,"Copy a selected app-owned video, image or audio file from another project. Independent bytes and source hashes are retained; existing audio lanes stay unchanged. Linked external files are not available here. Maximum 256 MiB.");
        source=spinner(form,"Source project");asset=spinner(form,"Source asset");
        append=new CheckBox(a);append.setText("Append video/image to timeline");append.setChecked(false);form.addView(append);
        label(form,"Audio is copied into the media bin; this action never adds another audio lane. Enable append for the silent film going into a Story Audio project.");
        label(form,"Operation ID (keep unchanged for retry)");operation=new EditText(a);operation.setContentDescription("Project asset operation ID");operation.setSingleLine(true);operation.setText(UUID.randomUUID().toString());form.addView(operation);
        inspect=new Button(a);inspect.setText("Inspect selected asset");form.addView(inspect);
        submit=new Button(a);submit.setText("Copy asset");submit.setEnabled(false);form.addView(submit);
        retry=new Button(a);retry.setText("Retry saved import");retry.setEnabled(false);form.addView(retry);
        label(form,"Retry saved import sends the exact last saved request, including its original revisions, operation ID, hash and append choice. Use it after cancellation or reopening; later project edits are preserved by the verified receipt. Copy asset starts a new selected import.");
        status=label(form,"Loading owned project assets…");status.setContentDescription("Project asset import status");
        ScrollView scroll=new ScrollView(a);scroll.addView(form);dialog=new AlertDialog.Builder(a).setTitle("Import from project").setView(scroll).setNegativeButton("Close",null).create();
        source.setOnItemSelectedListener(changed(()->{invalidate();loadAssets();}));asset.setOnItemSelectedListener(changed(this::invalidate));inspect.setOnClickListener(v->inspect());submit.setOnClickListener(v->queue());retry.setOnClickListener(v->retry());
        dialog.setOnDismissListener(d->{closed=true;generation++;if(task!=null)task.cancel(true);worker.shutdownNow();});
    }
    private void show(){dialog.show();load();}
    private void load(){run("Reading project media…",()->{
        ProjectStore store=new ProjectStore(activity);ProjectStore.Project destination=store.get(destinationId);if(destination==null)throw new IllegalArgumentException("Destination project no longer exists");
        List<ProjectStore.Project> found=new ArrayList<>();for(ProjectStore.Project p:store.list())if(!p.id.equals(destinationId)){
            boolean eligible=false;for(ProjectStore.Asset a:p.assets)try{ProjectAssetImport.sourceFile(activity,a);eligible=true;break;}catch(Exception ignored){}
            if(eligible)found.add(p);
        }
        String saved=readSaved();
        return ()->{destinationRevision=destination.updatedAt;savedAvailable=saved!=null;projects.clear();projects.addAll(found);binding=true;try{List<String> names=new ArrayList<>();for(ProjectStore.Project p:projects)names.add(p.name);source.setAdapter(adapter(names));}finally{binding=false;}loadAssets();status.setText(savedAvailable?"A saved exact import is available. Retry saved import preserves its original parameters, even after source removal or later destination edits.":projects.isEmpty()?"No other project has an app-owned media file. External links need an owned import first.":"Select the source project and asset, then inspect its SHA-256.");};
    });}
    private void loadAssets(){
        assets.clear();int i=source.getSelectedItemPosition();if(i>=0&&i<projects.size())for(ProjectStore.Asset a:projects.get(i).assets)try{ProjectAssetImport.sourceFile(activity,a);assets.add(a);}catch(Exception ignored){}
        binding=true;try{List<String> names=new ArrayList<>();for(ProjectStore.Asset a:assets)names.add(a.name);asset.setAdapter(adapter(names));}finally{binding=false;}controls();
    }
    private void inspect(){
        if(busy||queued)return;int p=source.getSelectedItemPosition(),a=asset.getSelectedItemPosition();if(p<0||p>=projects.size()||a<0||a>=assets.size())return;
        ProjectStore.Project selected=projects.get(p);ProjectStore.Asset chosen=assets.get(a);invalidate();run("Hashing selected owned media…",()->{
            JSONObject info=ProjectAssetImport.inspect(activity,new JSONObject().put("projectId",selected.id).put("expectedUpdatedAt",selected.updatedAt).put("assetId",chosen.id));
            return ()->{inspected=info;status.setText(info.optString("name")+" · "+info.optLong("bytes")+" bytes\nSHA-256 "+info.optString("sha256"));};
        });
    }
    private void queue(){
        if(busy||queued||inspected==null)return;
        try{
            JSONObject request=ProjectAssetImport.validate(new JSONObject().put("projectId",destinationId).put("expectedUpdatedAt",destinationRevision).put("sourceProjectId",inspected.getString("projectId"))
                    .put("sourceProjectUpdatedAt",inspected.getLong("updatedAt")).put("assetId",inspected.getString("assetId")).put("expectedSha256",inspected.getString("sha256"))
                    .put("operationId",operation.getText().toString()).put("appendToTimeline",append.isChecked()));
            final String exact=request.toString();run("Saving exact import request before dispatch…",()->{save(exact);return ()->{savedAvailable=true;dispatch(exact);};});
        }catch(Exception error){status.setText("Controls retained: "+error.getMessage());}
    }
    private void retry(){if(busy||!savedAvailable)return;run("Loading the exact saved import…",()->{String exact=readSaved();if(exact==null)throw new IllegalStateException("No saved import request");return ()->dispatch(exact);});}
    private void dispatch(String exact){
        try{activity.startForegroundService(new Intent(activity,ControlService.class).setAction("com.rezoxnemesis.videostudio.LOCAL_PROJECT_ASSET_IMPORT").putExtra("requestJson",exact));queued=true;
            status.setText("Copy requested. Open Jobs / Activity for actual completion. The exact request was saved before dispatch. Use Retry saved import after cancellation or reopen; do not mint another operation or append a duplicate.");
        }catch(Exception error){status.setText("Exact request retained; retry is available: "+error.getMessage());}controls();
    }
    private File savedFile()throws Exception {
        File file=new File(new File(activity.getFilesDir(),"project_asset_import_manual"),destinationId+".json");
        for(File candidate:new File[]{file,new File(file.getPath()+".bak"),new File(file.getPath()+".new")})for(java.nio.file.Path p=candidate.toPath().toAbsolutePath();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Saved request symlinks are unavailable");
        return file;
    }
    private String readSaved()throws Exception {
        File file=savedFile();if(!file.isFile()&&!new File(file.getPath()+".bak").isFile())return null;
        try(InputStream in=new AtomicFile(file).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];for(int n;(n=in.read(buffer))!=-1;){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(out.size()+n>32768)throw new IOException("Saved request exceeds its budget");out.write(buffer,0,n);}
            String exact=out.toString("UTF-8");JSONObject request=ProjectAssetImport.validate(new JSONObject(exact));if(!destinationId.equals(request.getString("projectId")))throw new IOException("Saved import belongs to another project");return exact;
        }
    }
    private void save(String exact)throws Exception {
        File file=savedFile();File folder=file.getParentFile();if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot save exact import request");
        byte[] bytes=exact.getBytes(StandardCharsets.UTF_8);if(bytes.length>32768)throw new IOException("Import request exceeds its budget");if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;try{out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}catch(Exception error){if(out!=null)atomic.failWrite(out);throw error;}
    }
    private void invalidate(){if(binding||queued)return;generation++;if(task!=null)task.cancel(true);busy=false;inspected=null;status.setText("Inspect the selected asset before copying.");controls();}
    private interface Work {Runnable run()throws Exception;}
    private void run(String message,Work work){
        if(closed)return;final int token=++generation;busy=true;controls();status.setText(message);
        task=worker.submit(()->{try{Runnable result=work.run();activity.runOnUiThread(()->{if(!unavailable()&&generation==token){busy=false;result.run();controls();}});}catch(Exception error){activity.runOnUiThread(()->{if(!unavailable()&&generation==token){busy=false;status.setText("Controls retained: "+error.getMessage());controls();}});}});
    }
    private boolean unavailable(){return closed||activity.isFinishing()||activity.isDestroyed();}
    private void controls(){source.setEnabled(!busy&&!queued);asset.setEnabled(!busy&&!queued);append.setEnabled(!busy&&!queued);operation.setEnabled(!busy&&!queued);inspect.setEnabled(!busy&&!queued&&!assets.isEmpty());submit.setEnabled(!busy&&!queued&&inspected!=null);retry.setEnabled(!busy&&savedAvailable);}
    private ArrayAdapter<String> adapter(List<String> values){return new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values);}
    private Spinner spinner(LinearLayout form,String name){label(form,name);Spinner s=new Spinner(activity);s.setContentDescription(name);form.addView(s);return s;}
    private TextView label(LinearLayout form,String text){TextView t=new TextView(activity);t.setText(text);t.setPadding(0,8,0,4);form.addView(t);return t;}
    private android.widget.AdapterView.OnItemSelectedListener changed(Runnable run){return new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){if(!binding)run.run();}};}
}
