package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Separate native offline Video Generator. Not the legacy procedural prompt
 * screen. Model installation uses the user's single selected ZIP document.
 * Never enumerate Gallery and never ask for an API key or cloud subscription.
 */
public final class NativeOfflineVideoActivity extends Activity {
    private static final int PICK_LOCAL_PACK=4201;
    private ProjectStore store;
    private ProjectStore.Project source;
    private EditText prompt,seed;
    private Spinner mode;
    private TextView modelStatus,anchorsStatus;
    private Button generate;
    private final ArrayList<String> selected=new ArrayList<>();

    @Override public void onCreate(Bundle bundle){
        super.onCreate(bundle);
        store=new ProjectStore(this);
        source=store.active();
        if(source==null)source=store.create("Offline Video References");
        build();
    }

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView description(String content,int sp){
        TextView t=new TextView(this);t.setText(content);t.setTextSize(sp);
        t.setPadding(0,dp(5),0,dp(5));return t;
    }
    private Button action(LinearLayout panel,String title,Runnable handler){
        Button b=new Button(this);b.setText(title);
        b.setMinHeight(dp(48));b.setOnClickListener(v->handler.run());
        panel.addView(b,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52)));
        return b;
    }
    private void build(){
        ScrollView scroll=new ScrollView(this);
        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(19),dp(20),dp(19),dp(30));
        scroll.addView(body);
        body.addView(description("Native Video Generator",25));
        body.addView(description("Local neural image-to-video / text-to-video. No paid API, credits, trial or hosted inference. This is a separate research runtime, not the slideshow prompt editor.",14));
        modelStatus=description("",14);body.addView(modelStatus);
        action(body,"Refresh offline model status",this::refreshStatus);
        action(body,"Import an owned offline ONNX model ZIP",this::choosePack);
        body.addView(description("Generation mode",17));
        mode=new Spinner(this);
        ArrayAdapter<String> modes=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Image to video","Text to video"});
        mode.setAdapter(modes);body.addView(mode,new LinearLayout.LayoutParams(-1,dp(52)));
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int position,long id){
                if(anchorsStatus!=null)anchorsStatus.setText(position==1?"No reference image needed":
                        selected.size()+" owned reference images selected");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        action(body,"Choose images from THIS VideoStudio project",this::selectReferences);
        anchorsStatus=description("No references selected",14);body.addView(anchorsStatus);
        body.addView(description("Motion / scene prompt",17));
        prompt=new EditText(this);prompt.setMinLines(4);prompt.setMaxLines(10);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        prompt.setHint("A young swordsman steps forward in driving rain; wind moves his hair and robe. Camera pushes from wide establishing shot to a close-up.");
        body.addView(prompt,new LinearLayout.LayoutParams(-1,dp(150)));
        body.addView(description("Seed (repeatable generation)",15));
        seed=new EditText(this);seed.setSingleLine(true);
        seed.setInputType(InputType.TYPE_CLASS_NUMBER);seed.setText("42");
        body.addView(seed,new LinearLayout.LayoutParams(-1,dp(50)));
        body.addView(description("Research model ABI v1: 128×128, 9 newly generated frames per reference, 9 FPS, up to six shots. No claim of Google Flow/Kling quality until an actual compatible temporal model is installed and tested.",13));
        generate=action(body,"Generate new frames locally",this::generate);
        action(body,"Return to VideoStudio",this::finish);
        setContentView(scroll);
        refreshStatus();
    }

    private void refreshStatus(){
        try{
            JSONObject status=new NativeOfflineVideoGenerator(this).capabilities();
            boolean installed=status.getBoolean("modelPackInstalled");
            modelStatus.setText(installed
                    ?"Local temporal pack files detected. Exact licences, hashes, RAM and ONNX shapes will be checked before generation."
                    :"No compatible local temporal model installed. Neural generation is unavailable; VideoStudio will not substitute still-image zooming or request a paid API.");
            if(generate!=null)generate.setEnabled(installed);
        }catch(Exception error){
            if(modelStatus!=null)modelStatus.setText("Cannot check native model: "+error.getMessage());
            if(generate!=null)generate.setEnabled(false);
        }
    }

    private void selectReferences(){
        source=store.get(source.id);
        if(source==null){Toast.makeText(this,"Source project is no longer available",Toast.LENGTH_LONG).show();return;}
        ArrayList<ProjectStore.Asset> images=new ArrayList<>();
        for(ProjectStore.Asset a:source.assets)if(a.mime!=null&&a.mime.startsWith("image/"))images.add(a);
        if(images.isEmpty()){
            new AlertDialog.Builder(this).setTitle("Add references to the project")
                    .setMessage("Import the storyboard images into this VideoStudio project first. The generator cannot browse your Gallery.")
                    .setPositiveButton("OK",null).show();return;
        }
        String[] labels=new String[images.size()];boolean[] checked=new boolean[images.size()];
        for(int i=0;i<images.size();i++){
            ProjectStore.Asset a=images.get(i);
            labels[i]=a.name==null?"Owned image "+(i+1):a.name;
            checked[i]=selected.contains(a.id);
        }
        new AlertDialog.Builder(this).setTitle("Select up to 6 owned storyboard images")
                .setMultiChoiceItems(labels,checked,(dialog,index,chosen)->checked[index]=chosen)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Use these images",(dialog,which)->{
                    selected.clear();
                    for(int i=0;i<checked.length;i++)if(checked[i])selected.add(images.get(i).id);
                    anchorsStatus.setText(selected.size()+" owned reference image(s) selected");
                }).show();
    }

    private void choosePack(){
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("application/zip");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(pick,PICK_LOCAL_PACK);
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_LOCAL_PACK||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            source=store.get(source.id);
            if(source==null)throw new IllegalStateException("Source project changed");
            String name="offline_video_model.zip";long size=0;
            try(Cursor cursor=getContentResolver().query(uri,new String[]{
                    OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                if(cursor!=null&&cursor.moveToFirst()){
                    int display=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int bytes=cursor.getColumnIndex(OpenableColumns.SIZE);
                    if(display>=0)name=cursor.getString(display);
                    if(bytes>=0&&!cursor.isNull(bytes))size=Math.max(0,cursor.getLong(bytes));
                }
            }
            ProjectStore.Asset asset=store.registerImportedAsset(source.id,uri,
                    name==null?"offline_video_model.zip":name,"application/zip",0,size);
            Intent intent=new Intent(this,ControlService.class)
                    .setAction(ControlService.ACTION_LOCAL_VIDEO_INSTALL)
                    .putExtra("projectId",source.id).putExtra("assetId",asset.id);
            startForegroundService(intent);
            Toast.makeText(this,"Local model pack installation queued. Review Native Jobs; no online API used.",
                    Toast.LENGTH_LONG).show();
        }catch(Exception error){
            new AlertDialog.Builder(this).setTitle("Offline model import failed")
                    .setMessage(error.getMessage()).setPositiveButton("OK",null).show();
        }
    }

    private void generate(){
        try{
            source=store.get(source.id);
            if(source==null)throw new IllegalStateException("Owned source project unavailable");
            JSONArray anchors=new JSONArray();
            if(mode.getSelectedItemPosition()==0)for(String id:selected)anchors.put(id);
            JSONObject request=new JSONObject().put("sourceProjectId",source.id)
                    .put("sourceUpdatedAt",source.updatedAt)
                    .put("operationId",UUID.randomUUID().toString())
                    .put("mode",mode.getSelectedItemPosition()==0?"image_to_video":"text_to_video")
                    .put("sourceAssetIds",anchors)
                    .put("prompt",prompt.getText().toString().trim())
                    .put("seed",Long.parseLong(seed.getText().toString().trim()));
            NativeOfflineVideoPlan.validate(source,request);
            if(!new NativeOfflineVideoGenerator(this).capabilities().getBoolean("modelPackInstalled"))
                throw new IllegalStateException("No installed offline temporal model. Import model pack first.");
            startForegroundService(new Intent(this,ControlService.class)
                    .setAction(ControlService.ACTION_LOCAL_VIDEO_GENERATE)
                    .putExtra("requestJson",request.toString()));
            Toast.makeText(this,"Native offline neural video task submitted. See Native Jobs.",
                    Toast.LENGTH_LONG).show();
        }catch(Exception error){
            new AlertDialog.Builder(this).setTitle("Cannot start offline neural video")
                    .setMessage(error.getMessage()==null?"Check your image references and seed":error.getMessage())
                    .setPositiveButton("OK",null).show();
        }
    }

    @Override protected void onDestroy(){
        if(store!=null)store.close();
        super.onDestroy();
    }
}
