package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AtomicFile;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Native manual JSON authoring for the shared story plan API; the host owns book navigation. */
public final class SketchStoryPlanDialog {
    private static final int PLAN_BYTES=2*1024*1024;
    private final Activity activity;private final Consumer<String> openBook;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel=new AtomicBoolean();private volatile Thread running;
    private final EditText json,shot,frame;private final TextView status;private final ImageView preview;
    private final Button previewButton,buildButton,cancelButton,bookButton,historyButton,exampleButton;
    private AlertDialog dialog;private boolean closed,busy;private Bitmap image;
    private JSONObject cached;private String cachedText,cachedShot,readyBook;

    public static void show(Activity activity,Consumer<String> openBook)throws Exception {show(activity,null,openBook);}
    /** initialPlan can come from a shipped asset or another native authoring surface. */
    public static void show(Activity activity,JSONObject initialPlan,Consumer<String> openBook)throws Exception {
        new SketchStoryPlanDialog(activity,initialPlan,openBook).show(initialPlan==null);
    }
    private SketchStoryPlanDialog(Activity a,JSONObject initial,Consumer<String> openBook)throws Exception {
        if(a==null||openBook==null)throw SketchDocument.bad("Story activity and book navigation are required");activity=a;this.openBook=openBook;
        LinearLayout form=new LinearLayout(a);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(20,12,20,12);
        TextView help=new TextView(a);help.setText("Load the original example, paste a story plan, or load a saved request. Preview any shot before creating its book. Build checks every shot and saves native editable pages. Cancel retains saved pages; resume with the same JSON. Preview is silent; add the original speech master in the project audio lane.");form.addView(help);
        json=input(form,"Story plan JSON",initial==null?"":initial.toString());json.setSingleLine(false);json.setMinLines(7);json.setMaxLines(16);
        shot=input(form,"Preview shot (1 based)","1");frame=input(form,"Preview frame (1 based)","1");
        previewButton=button(form,"Preview shot",this::preview);buildButton=button(form,"Build / resume",this::build);
        cancelButton=button(form,"Cancel work",this::cancel);historyButton=button(form,"Load saved request",this::history);
        exampleButton=button(form,"Load original example",this::example);
        preview=new ImageView(a);preview.setContentDescription("Story plan preview");preview.setScaleType(ImageView.ScaleType.FIT_CENTER);form.addView(preview,new LinearLayout.LayoutParams(-1,260));
        status=new TextView(a);status.setContentDescription("Story plan status");form.addView(status);
        bookButton=button(form,"Open book",()->{if(readyBook!=null){String id=readyBook;dialog.dismiss();this.openBook.accept(id);}});
        ScrollView scroll=new ScrollView(a);scroll.addView(form);dialog=new AlertDialog.Builder(a).setTitle("Story plan").setView(scroll).setNegativeButton("Close",null).create();
        json.addTextChangedListener(watcher(()->{invalidate(true);readyBook=null;controls();}));shot.addTextChangedListener(watcher(()->invalidate(true)));frame.addTextChangedListener(watcher(()->invalidate(false)));
        dialog.setOnDismissListener(d->{closed=true;cancel();worker.shutdownNow();releaseImage();cached=null;});
        controls();status.setText("Preview a selected shot, then build the complete plan.");
        if(initial!=null&&initial.toString().getBytes(StandardCharsets.UTF_8).length>PLAN_BYTES)throw SketchDocument.bad("Story plan exceeds 2 MiB");
    }
    private void show(boolean restore){dialog.show();if(restore)run("Loading saved request…",()->{
        File last=new File(root(),"last.json");if(!present(last))return Result.message("Paste plan JSON or load a saved request.");
        String file=read(last,1024).getString("file");return loaded(file);
    });}
    private JSONObject plan()throws Exception {
        byte[] bytes=json.getText().toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>PLAN_BYTES)throw SketchDocument.bad("Story plan exceeds 2 MiB");
        return SketchPaintArchive.parseOwnedJson(bytes);
    }
    private static int ordinal(EditText field,int count,String name)throws Exception {
        try{int n=Integer.parseInt(field.getText().toString().trim());if(n<1||n>count)throw SketchDocument.bad("Choose a "+name+" inside the plan timeline");return n-1;}
        catch(NumberFormatException e){throw SketchDocument.bad("Choose a whole-number "+name);}
    }
    private void preview()throws Exception {
        JSONObject args=plan();JSONArray shots=args.getJSONArray("shots");JSONObject selected=shots.getJSONObject(ordinal(shot,shots.length(),"shot"));String id=selected.getString("shotId");
        int sample=ordinal(frame,selected.getInt("frameCount"),"frame");String text=json.getText().toString();
        final JSONObject reuse=text.equals(cachedText)&&id.equals(cachedShot)?cached:null;
        run("Preparing native shot preview…",()->{
            saveRequest(args);checkCancelled();JSONObject prepared=reuse==null?SketchStoryPlanOps.preparePreview(activity,args,id):reuse;
            checkCancelled();JSONObject response=SketchAnimationCompiler.preview(prepared.getJSONObject("sourceDocument"),prepared.getJSONObject("program"),sample,"story_manual_preview");
            Bitmap bitmap;try(SketchFrameRenderer renderer=new SketchFrameRenderer(activity,response.getJSONObject("document"))){bitmap=renderer.render(0);}
            Result result=Result.message("Native preview · "+id+" · frame "+(sample+1)+" / "+selected.getInt("frameCount")+". Build validates the full plan; no audio playback.");
            result.bitmap=bitmap;result.prepared=prepared;result.cacheText=text;result.shotId=id;return result;
        });
    }
    private void build()throws Exception {
        JSONObject args=plan();run("Building native story book… Cancel retains completed pages for an exact retry.",()->{
            saveRequest(args);checkCancelled();
            try{
                JSONObject receipt=SketchStoryPlanOps.execute(activity,"sketch_story_create",args);
                Result result=Result.message("Book ready · "+receipt.getInt("frameCount")+" frames at "+receipt.getInt("fps")+" FPS · "+receipt.getJSONArray("shots").length()+" shots. Audio stays in the project audio lane.");result.bookId=receipt.getString("bookId");return result;
            }catch(Exception failure){
                // Execution has stopped. Only inspect any retained book; never rebase or amend it.
                Thread.interrupted();Result result=Result.message("Build stopped; controls and saved pages retained: "+failure.getMessage()+". Resume with unchanged JSON.");
                try(SketchBookStore books=new SketchBookStore(activity)){books.get(args.getString("bookId"));result.bookId=args.getString("bookId");}catch(Exception missing){/* No book was published. */}
                return result;
            }
        });
    }
    private void history(){run("Reading saved story requests…",()->{
        File[] files=root().listFiles(f->f.isFile()&&f.getName().matches("request_[a-f0-9]{64}\\.json"));JSONArray choices=new JSONArray();
        if(files!=null){Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());if(files.length>128)throw SketchDocument.bad("Story request history exceeds its 128-file budget");
            for(File file:files){checkCancelled();JSONObject p=read(file,PLAN_BYTES);choices.put(new JSONObject().put("file",file.getName()).put("label",p.optString("name",p.optString("bookId","Story"))+" · "+p.optString("bookId")+" · "+p.optString("operationId")));}}
        Result result=Result.message(choices.length()==0?"No saved story requests yet.":"Choose an exact saved request.");result.choices=choices;return result;
    });}
    private void example(){run("Loading original story example…",()->{
        // Same explicit fetch as MCP; Build / resume and saved-request load never mint new IDs.
        JSONObject plan=SketchStoryPlanOps.example(activity).getJSONObject("plan");
        checkCancelled();saveRequest(plan);Result result=Result.message("Original example loaded as a new book. Preview any shot; Build / resume keeps these saved request IDs.");result.text=plan.toString();return result;
    });}
    private Result loaded(String file)throws Exception {
        if(!file.matches("request_[a-f0-9]{64}\\.json"))throw SketchDocument.bad("Invalid saved story request identity");JSONObject p=read(new File(root(),file),PLAN_BYTES);
        String hash=SketchDocument.sha256(SketchDocument.canonical(p).getBytes(StandardCharsets.UTF_8));if(!file.equals("request_"+hash+".json"))throw SketchDocument.bad("Saved story request changed");
        Result result=Result.message("Saved exact request loaded. Preview or build/resume; existing pages are preserved.");result.text=p.toString();return result;
    }
    private void saveRequest(JSONObject args)throws Exception {
        // JSONObject may serialize integral doubles/-0.0 as integers/-0. Hash the representation
        // that will actually be read back, or a freshly loaded asset falsely fails its own receipt.
        args=SketchDocument.copy(args);
        File folder=root();if(!folder.isDirectory()&&!folder.mkdirs())throw SketchDocument.bad("Cannot save the manual story request");
        String hash=SketchDocument.sha256(SketchDocument.canonical(args).getBytes(StandardCharsets.UTF_8));String name="request_"+hash+".json";File target=new File(folder,name);
        if(present(target)){
            if(!hash.equals(SketchDocument.sha256(SketchDocument.canonical(read(target,PLAN_BYTES)).getBytes(StandardCharsets.UTF_8))))throw SketchDocument.bad("Saved story request changed");
        }else{
            File[] files=folder.listFiles(f->f.isFile()&&f.getName().matches("request_[a-f0-9]{64}\\.json"));if(files!=null&&files.length>=128)throw SketchDocument.bad("Saved story request limit reached; existing requests remain available");
            write(target,args);
        }
        write(new File(folder,"last.json"),new JSONObject().put("file",name));
    }
    private File root(){return new File(activity.getFilesDir(),"sketch_story_manual_requests");}
    private static boolean present(File file){return file.isFile()||new File(file.getPath()+".bak").isFile();}
    private JSONObject read(File file,int limit)throws Exception {
        try(InputStream in=new AtomicFile(file).openRead()){return read(in,limit);}
    }
    private JSONObject read(InputStream in,int limit)throws Exception {
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1){checkCancelled();if(bytes.size()+count>limit)throw SketchDocument.bad("Saved story request exceeds byte budget");bytes.write(buffer,0,count);}return SketchPaintArchive.parseOwnedJson(bytes.toByteArray());
        }
    }
    private static void write(File file,JSONObject value)throws Exception {
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;try{out=atomic.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception failure){if(out!=null)atomic.failWrite(out);throw failure;}
    }
    private void run(String message,Job job){
        if(closed||busy)return;busy=true;cancel.set(false);status.setText(message);controls();
        worker.execute(()->{
            running=Thread.currentThread();Result result;
            try{result=job.run();}catch(Exception e){result=Result.message("Controls retained: "+e.getMessage());}finally{running=null;Thread.interrupted();}
            final Result finished=result;activity.runOnUiThread(()->{
                if(closed||activity.isFinishing()||activity.isDestroyed()){if(finished.bitmap!=null)finished.bitmap.recycle();return;}
                busy=false;
                if(finished.text!=null){json.setText(finished.text);shot.setText("1");frame.setText("1");}
                if(finished.bitmap!=null){releaseImage();image=finished.bitmap;preview.setImageBitmap(image);cached=finished.prepared;cachedText=finished.cacheText;cachedShot=finished.shotId;}
                if(finished.bookId!=null)readyBook=finished.bookId;
                status.setText(finished.message);controls();
                if(finished.choices!=null&&finished.choices.length()>0){JSONArray choices=finished.choices;String[] labels=new String[choices.length()];for(int i=0;i<labels.length;i++)labels[i]=choices.optJSONObject(i).optString("label");
                    new AlertDialog.Builder(activity).setTitle("Saved story requests").setItems(labels,(d,n)->run("Loading exact request…",()->loaded(choices.getJSONObject(n).getString("file")))).setNegativeButton("Cancel",null).show();}
            });
        });
    }
    private void cancel(){cancel.set(true);Thread thread=running;if(thread!=null)thread.interrupt();if(!closed&&busy)status.setText("Story work cancellation requested; saved pages will be retained.");}
    private void checkCancelled()throws InterruptedException {if(cancel.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Story work cancelled; retry unchanged JSON");}
    private void invalidate(boolean source){releaseImage();if(source){cached=null;cachedText=null;cachedShot=null;}}
    private void releaseImage(){preview.setImageDrawable(null);if(image!=null){image.recycle();image=null;}}
    private void controls(){json.setEnabled(!busy);shot.setEnabled(!busy);frame.setEnabled(!busy);previewButton.setEnabled(!busy);buildButton.setEnabled(!busy);historyButton.setEnabled(!busy);exampleButton.setEnabled(!busy);cancelButton.setEnabled(busy);bookButton.setEnabled(!busy&&readyBook!=null);}
    private EditText input(LinearLayout form,String label,String value){TextView title=new TextView(activity);title.setText(label);form.addView(title);EditText input=new EditText(activity);input.setContentDescription(label);input.setSingleLine(true);input.setText(value);form.addView(input);return input;}
    private Button button(LinearLayout form,String label,Action action){Button b=new Button(activity);b.setText(label);form.addView(b);b.setOnClickListener(v->{if(closed||activity.isFinishing()||activity.isDestroyed())return;try{action.run();}catch(Exception e){status.setText("Controls retained: "+e.getMessage());}});return b;}
    private static TextWatcher watcher(Runnable changed){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changed.run();}public void afterTextChanged(Editable value){}};}
    private interface Action{void run()throws Exception;}private interface Job{Result run()throws Exception;}
    private static final class Result {
        String message,text,bookId,cacheText,shotId;Bitmap bitmap;JSONObject prepared;JSONArray choices;
        static Result message(String text){Result result=new Result();result.message=text;return result;}
    }
}
