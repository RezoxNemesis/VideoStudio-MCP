package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Manual client of the same retained native correction coordinator used by MCP. */
final class SketchCorrectionDialog implements AutoCloseable {
    interface Backend { JSONObject execute(Context context,String action,JSONObject request)throws Exception; }
    private interface Work { Result run()throws Exception; }
    private interface Action { void run()throws Exception; }
    private static final class Result {
        JSONObject reply,state;Bitmap image;String side,message;boolean applied;
        Result(JSONObject reply){this.reply=reply;}
    }
    private final Activity activity;
    private final JSONObject opened;
    private final String sketchId;
    private final Consumer<JSONObject> onApplied;
    private final Backend backend;
    private final ExecutorService worker;
    private final LinearLayout form,advanced;
    private LinearLayout building;
    private final List<String> objectIds=new ArrayList<>(),proposalIds=new ArrayList<>(),noteIds=new ArrayList<>();
    private final EditText reviewId,target,first,last,step,region,noteId,note,noteRefs,proposalId,summary,operations,previewFrame;
    private final Spinner objects,outcome,noteSide,proposal,mode,recipes,evidenceNotes;
    private final CheckBox motion;
    private final TextView status,history,recipeDetails,scope,technical;
    private final ImageView before,after;
    private final Button start,resume,saved,record,loadOptions,relax,prepare,showBefore,showAfter,apply,cancel;
    private AlertDialog dialog,detailDialog,savedDialog;
    private String rememberedProposal="",noteChoice="";
    private ImageView detailImage;
    private Future<?> task;
    private int generation;
    private boolean closed,paused,busy,binding,sourceMatches,reportedApply;
    private JSONObject review,pendingNote,pendingProposal,pendingApply;
    private JSONArray options=new JSONArray();
    private Bitmap beforeBitmap,afterBitmap;

    static SketchCorrectionDialog show(Activity activity,JSONObject document,int frame,Consumer<JSONObject> onApplied)throws Exception {
        SketchCorrectionDialog owner=new SketchCorrectionDialog(activity,document,frame,onApplied,SketchCorrectionOps::execute,Executors.newSingleThreadExecutor());
        owner.open();return owner;
    }
    SketchCorrectionDialog(Activity a,JSONObject document,int frame,Consumer<JSONObject> applied,Backend engine,ExecutorService executor)throws Exception {
        activity=a;opened=SketchDocument.copy(document);sketchId=opened.getString("id");onApplied=applied;backend=engine;worker=executor;
        form=new LinearLayout(a);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(16),dp(8),dp(16),dp(8));building=form;
        advanced=new LinearLayout(a);advanced.setOrientation(LinearLayout.VERTICAL);advanced.setContentDescription("Advanced correction controls");advanced.setVisibility(View.GONE);
        text("Record what needs attention, choose a correction, then compare before applying. You decide whether the artwork is acceptable.");
        target=input("Review target","");
        first=input("First inspected frame (1 based)",String.valueOf(frame+1));last=input("Last inspected frame (1 based)",String.valueOf(Math.min(opened.getInt("frameCount"),frame+24)));
        motion=new CheckBox(a);motion.setText("Review requires motion");form.addView(motion);
        objects=spinner("Target object",new String[]{"Whole drawing / no object"});objectIds.add("");
        JSONArray layers=opened.getJSONArray("layers");List<String> labels=new ArrayList<>();labels.add("Whole drawing / no object");
        for(int l=0;l<layers.length();l++){JSONArray items=layers.getJSONObject(l).getJSONArray("objects");for(int i=0;i<items.length();i++){JSONObject o=items.getJSONObject(i);objectIds.add(o.getString("id"));labels.add(o.getString("kind")+" · "+o.getString("id"));}}
        objects.setAdapter(adapter(labels.toArray(new String[0])));if(objectIds.size()>1)objects.setSelection(1);
        start=button("Start review",this::start);saved=button("Saved reviews",()->savedReviews(0));
        button("New review",this::newReview);
        status=text("Start a review, or choose Saved reviews to continue.");status.setContentDescription("Correction status");
        history=text("");history.setContentDescription("Correction history");
        outcome=spinner("Visual outcome",new String[]{"Not reviewed","Needs rework","Acceptable"});
        noteSide=spinner("Note evidence",new String[]{"Captured source","Selected candidate"});note=input("Review note","");note.setSingleLine(false);note.setMinLines(2);
        record=button("Record note",this::note);
        evidenceNotes=spinner("Note for this correction",new String[]{"Record a note first"});noteIds.add("");
        loadOptions=button("Load recipe choices",()->options(null));recipes=spinner("Native correction recipe",new String[]{"Choose an object and load recipes"});
        recipeDetails=text("Each recipe describes its change and the checks to inspect.");recipeDetails.setContentDescription("Recipe expected changes");
        relax=button("Relax arms",()->options("neutral_arms"));
        prepare=button("Prepare candidate",this::propose);proposal=spinner("Prepared candidate",new String[]{"No candidate selected"});proposalIds.add("");
        scope=text("Preparing a correction leaves your drawing unchanged.");scope.setContentDescription("Correction scope");
        previewFrame=input("Preview frame (1 based)",String.valueOf(frame+1));showBefore=button("Preview before",()->preview("before"));before=image("Before correction preview");
        showAfter=button("Preview after",()->preview("after"));after=image("After correction preview");
        text("Tap either preview for native-pixel inspection. Normal editor Undo restores an applied correction.");
        apply=button("Apply selected candidate",this::apply);cancel=button("Cancel work",this::cancelWork);
        button("Advanced controls",()->{advanced.setVisibility(advanced.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);});
        building=advanced;
        text("Optional IDs, multi-note references and native vector edits. Entries remain available when collapsed.");
        reviewId=input("Review ID (start or resume)",id("review"));resume=button("Resume / refresh review",this::resume);
        step=input("Audit frame step (1–16)","1");region=input("Region / preview crop (normalized JSON, optional)","");
        noteId=input("Note ID",id("note"));noteRefs=input("Notes supporting correction (comma-separated IDs)","");proposalId=input("New proposal ID",id("proposal"));summary=input("Correction summary","");
        mode=spinner("Correction route",new String[]{"Native recipe","Advanced vector operations"});
        operations=input("Correction operations (JSON array)","[]");operations.setSingleLine(false);operations.setMinLines(3);
        technical=text("");technical.setContentDescription("Correction technical metadata");
        building=form;form.addView(advanced);
        restoreDraft();bindChanges();sync();
    }
    void open(){
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        dialog=new AlertDialog.Builder(activity).setTitle("Review & correct").setView(scroll).setNegativeButton("Close",null).create();
        dialog.setOnDismissListener(d->close());activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);dialog.show();
    }
    private void newReview()throws Exception{reviewId.setText(id("review"));pendingNote=null;pendingProposal=null;pendingApply=null;noteRefs.setText("");noteId.setText(id("note"));proposalId.setText(id("proposal"));status.setText("Enter a target and start a new review of this source.");saveDraft();}
    private JSONObject base()throws Exception {return new JSONObject().put("sketchId",sketchId).put("reviewId",reviewId.getText().toString().trim());}
    private JSONObject reviewed()throws Exception {if(review==null)throw SketchDocument.bad("Start or resume this review first");return base().put("expectedReviewRevision",review.getLong("revision"));}
    private static String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","").substring(0,20);}
    private static int number(EditText field)throws Exception {try{return Integer.parseInt(field.getText().toString().trim());}catch(NumberFormatException bad){throw SketchDocument.bad("Enter an integer for "+field.getContentDescription());}}
    private JSONObject bounds()throws Exception {String value=region.getText().toString().trim();return value.isEmpty()?null:new JSONObject(value);}
    private String objectId(){int n=objects.getSelectedItemPosition();return n>=0&&n<objectIds.size()?objectIds.get(n):"";}
    private String selectedProposal(){int n=proposal.getSelectedItemPosition();return n>=0&&n<proposalIds.size()?proposalIds.get(n):"";}
    private JSONObject selectedRecipe(){int n=recipes.getSelectedItemPosition();return n>=0&&n<options.length()?options.optJSONObject(n):null;}
    private void start()throws Exception {
        int a=number(first)-1,b=number(last)-1,s=number(step);if(a<0||b<a||b>=opened.getInt("frameCount")||s<1||s>16)throw SketchDocument.bad("Choose a valid frame range and audit step1..16");
        JSONObject request=base().put("expectedRevision",opened.getLong("revision")).put("target",target.getText().toString().trim())
            .put("frameOffset",a).put("frameLimit",Math.min(24,(b-a)/s+1)).put("frameStep",s).put("motionRequired",motion.isChecked());
        submit("Capturing the source and native audit…",()->mutate("sketch_correction_start",request,"Review captured; source unchanged."));
    }
    private void resume()throws Exception {JSONObject request=base();submit("Loading saved review…",()->{Result r=new Result(call("sketch_correction_get",request));r.state=r.reply;r.message="Saved review loaded; source unchanged.";return r;});}
    private void savedReviews(int offset)throws Exception {
        JSONObject request=new JSONObject().put("sketchId",sketchId).put("offset",offset).put("limit",16);
        submit("Loading saved reviews…",()->{Result r=new Result(call("sketch_correction_list",request));r.side="list";return r;});
    }
    private void reviewPicker(JSONObject response)throws Exception {
        JSONArray entries=response.getJSONArray("reviews");boolean more=response.getBoolean("more");String[] names=new String[entries.length()+(more?1:0)];
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);names[i]=entry.getString("target")+" · "+entry.getString("status")+" · "+entry.getInt("noteCount")+" notes / "+entry.getInt("proposalCount")+" candidates";}
        if(more)names[names.length-1]="More saved reviews";
        if(names.length==0){status.setText("No saved reviews for this drawing.");return;}
        if(savedDialog!=null)savedDialog.dismiss();
        savedDialog=new AlertDialog.Builder(activity).setTitle("Saved reviews").setItems(names,(d,n)->{if(unavailable())return;try{if(n==entries.length())savedReviews(response.getInt("offset")+entries.length());else{String selected=entries.getJSONObject(n).getString("reviewId");if(!selected.equals(reviewId.getText().toString().trim()))reviewId.setText(selected);noteRefs.setText("");resume();}}catch(Exception error){status.setText("Entries retained: "+error.getMessage());}}).setNegativeButton("Close",null).show();
    }
    private JSONObject retry(JSONObject pending,JSONObject next)throws Exception {
        if(pending!=null){JSONObject a=SketchDocument.copy(pending),b=SketchDocument.copy(next);a.remove("expectedReviewRevision");b.remove("expectedReviewRevision");if(SketchDocument.canonical(a).equals(SketchDocument.canonical(b)))return SketchDocument.copy(pending);}return next;
    }
    private void note()throws Exception {
        JSONObject request=reviewed().put("noteId",noteId.getText().toString().trim()).put("outcome",new String[]{"UNREVIEWED","NEEDS_REWORK","ACCEPTABLE"}[outcome.getSelectedItemPosition()])
            .put("note",note.getText().toString().trim()).put("frameStart",number(first)-1).put("frameEnd",number(last)-1);
        if(!objectId().isEmpty())request.put("objectId",objectId());JSONObject crop=bounds();if(crop!=null)request.put("bounds",crop);
        if(noteSide.getSelectedItemPosition()==1){if(selectedProposal().isEmpty())throw SketchDocument.bad("Select the candidate described by this note");request.put("proposalId",selectedProposal());}
        pendingNote=retry(pendingNote,request);JSONObject frozen=SketchDocument.copy(pendingNote);saveDraft();
        submit("Recording your visual decision…",()->{Result r=mutate("sketch_correction_note",frozen,"Note retained; source unchanged.");r.side="note";return r;});
    }
    private void options(String desired)throws Exception {
        JSONObject request=base();if(objectId().isEmpty())throw SketchDocument.bad("Choose an object for native recipes");request.put("objectId",objectId());
        submit("Reading native recipe choices…",()->{Result r=new Result(call("sketch_correction_options",request));r.side="options";r.message=desired;return r;});
    }
    private void propose()throws Exception {
        String description=summary.getText().toString().trim();JSONObject chosen=selectedRecipe();if(description.isEmpty()&&mode.getSelectedItemPosition()==0&&chosen!=null)description=chosen.getString("label");
        JSONObject request=reviewed().put("proposalId",proposalId.getText().toString().trim()).put("summary",description);JSONArray refs=new JSONArray();
        for(String value:noteRefs.getText().toString().split(","))if(!value.trim().isEmpty())refs.put(value.trim());request.put("noteIds",refs);
        if(mode.getSelectedItemPosition()==0){JSONObject recipe=selectedRecipe();if(recipe==null||!recipe.optBoolean("available"))throw SketchDocument.bad("Select an available native recipe");request.put("recipeId",recipe.getString("id")).put("objectId",objectId());}
        else request.put("operations",new JSONArray(operations.getText().toString()));
        pendingProposal=retry(pendingProposal,request);JSONObject frozen=SketchDocument.copy(pendingProposal);saveDraft();
        submit("Preparing a detached native candidate…",()->{Result r=mutate("sketch_correction_propose",frozen,"Candidate prepared; source unchanged.");r.side="proposal";return r;});
    }
    private void preview(String side)throws Exception {
        JSONObject request=base().put("side",side).put("frameIndex",number(previewFrame)-1);if(side.equals("after")){if(selectedProposal().isEmpty())throw SketchDocument.bad("Select a prepared candidate");request.put("proposalId",selectedProposal());}
        JSONObject crop=bounds();if(crop!=null)request.put("crop",crop);clearImage(side);
        submit("Rendering native "+side+" detail…",()->{Result r=new Result(call("sketch_correction_preview",request));JSONObject image=r.reply.getJSONObject("sketchPreviewImage");byte[] bytes=Base64.decode(image.getString("base64"),Base64.DEFAULT);r.image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(r.image==null)throw SketchDocument.bad("Native detail image could not be decoded");r.side=side;r.message="Native "+side+" · frame "+(request.getInt("frameIndex")+1)+". Tap the image for native-pixel inspection.";return r;});
    }
    private void apply()throws Exception {
        if(selectedProposal().isEmpty())throw SketchDocument.bad("Select a prepared candidate");
        JSONObject request=reviewed().put("proposalId",selectedProposal()).put("expectedRevision",review.getLong("sourceRevision"));
        if(pendingApply!=null&&pendingApply.optString("reviewId").equals(request.getString("reviewId"))&&pendingApply.optString("proposalId").equals(selectedProposal()))request=SketchDocument.copy(pendingApply);
        else request.put("operationId",id("correction_apply"));pendingApply=request;saveDraft();JSONObject frozen=SketchDocument.copy(request);
        submit("Applying the selected candidate to its captured source…",()->{Result r=mutate("sketch_correction_apply",frozen,"Correction applied. Return to the editor for normal Undo.");r.applied=true;return r;});
    }
    private JSONObject call(String action,JSONObject request)throws Exception {return backend.execute(activity,action,SketchDocument.copy(request));}
    private Result mutate(String action,JSONObject request,String message)throws Exception {
        Result r=new Result(call(action,request));r.applied=action.equals("sketch_correction_apply");r.message=message;
        try{r.state=call("sketch_correction_get",new JSONObject().put("sketchId",sketchId).put("reviewId",request.getString("reviewId")));}
        catch(Exception refresh){if(!r.applied)throw refresh;r.message="Correction applied; review refresh unavailable. Normal editor Undo remains available.";}return r;
    }
    private void submit(String message,Work work){
        if(closed||paused)return;saveDraft();busy=true;final int request=++generation;sync();status.setText(message);
        try{task=worker.submit(()->{Result result=null;try{result=work.run();final Result done=result;activity.runOnUiThread(()->{
            if(unavailable()||request!=generation){if(done.image!=null)done.image.recycle();if(done.applied){pendingApply=null;saveDraft();if(!reportedApply&&!activity.isDestroyed()&&!activity.isFinishing()){reportedApply=true;onApplied.accept(done.reply);}if(dialog!=null)dialog.dismiss();}return;}
            busy=false;task=null;try{
                if(done.state!=null)adopt(done.state);
                if("options".equals(done.side))adoptOptions(done.reply,done.message);
                else if("list".equals(done.side))reviewPicker(done.reply);
                else if(done.image!=null){clearImage(done.side);if(done.side.equals("before")){beforeBitmap=done.image;before.setImageBitmap(done.image);before.setVisibility(View.VISIBLE);}else{afterBitmap=done.image;after.setImageBitmap(done.image);after.setVisibility(View.VISIBLE);}}
                else if("note".equals(done.side)){String saved=pendingNote.getString("noteId");noteRefs.setText(saved);int index=noteIds.indexOf(saved);if(index>=0){noteChoice=saved;evidenceNotes.setSelection(index);}pendingNote=null;noteId.setText(id("note"));}
                else if("proposal".equals(done.side)){selectProposal(pendingProposal.getString("proposalId"));pendingProposal=null;proposalId.setText(id("proposal"));clearImages();}
                if(!"list".equals(done.side))status.setText("options".equals(done.side)?"Native recipe choices loaded; source unchanged.":done.message);
                if(done.applied){pendingApply=null;saveDraft();if(!reportedApply){reportedApply=true;onApplied.accept(done.reply);}dialog.dismiss();return;}
                sync();saveDraft();
            }catch(Exception error){sync();status.setText("Entries retained: "+error.getMessage());}
        });}catch(Exception error){if(result!=null&&result.image!=null)result.image.recycle();activity.runOnUiThread(()->{if(unavailable()||request!=generation)return;busy=false;task=null;sync();status.setText("Entries retained: "+error.getMessage());saveDraft();});}});
        }catch(Exception error){busy=false;task=null;sync();status.setText("Entries retained: "+error.getMessage());}
    }
    private void adopt(JSONObject response)throws Exception {
        review=SketchDocument.copy(response.getJSONObject("review"));sourceMatches=response.optBoolean("sourceMatches",false);
        String selected=selectedProposal();if(selected.isEmpty())selected=rememberedProposal;JSONArray proposals=review.getJSONArray("proposals");proposalIds.clear();proposalIds.add("");List<String> names=new ArrayList<>();names.add("No candidate selected");
        for(int i=0;i<proposals.length();i++){JSONObject p=proposals.getJSONObject(i);String id=p.getString("proposalId");proposalIds.add(id);names.add(p.optString("summary","Candidate "+(i+1)));}
        binding=true;try{proposal.setAdapter(adapter(names.toArray(new String[0])));selectProposal(selected);}finally{binding=false;}
        StringBuilder text=new StringBuilder(review.getString("status").equals("OPEN")?"Review open":"Correction applied");
        text.append(sourceMatches?"\nCaptured source is current.":"\nSource changed: this review cannot apply a new correction.");
        JSONObject audit=review.optJSONObject("audit");if(audit!=null)text.append("\nSource ").append(auditSummary(audit));
        JSONArray notes=review.getJSONArray("notes");for(int i=0;i<notes.length();i++){JSONObject n=notes.getJSONObject(i);text.append("\n").append(n.getString("outcome").replace('_',' ')).append(" · frames ").append(n.getInt("frameStart")+1).append("–").append(n.getInt("frameEnd")+1).append("\n").append(n.getString("note"));}
        String selectedNote=noteRefs.getText().toString();noteIds.clear();List<String> noteLabels=new ArrayList<>();
        for(int i=0;i<notes.length();i++){JSONObject n=notes.getJSONObject(i);noteIds.add(n.getString("noteId"));noteLabels.add(n.getString("note"));}
        binding=true;try{if(noteLabels.isEmpty()){noteIds.add("");noteLabels.add("Record a note first");}evidenceNotes.setAdapter(adapter(noteLabels.toArray(new String[0])));int index=noteIds.indexOf(selectedNote);if(index<0)index=noteIds.size()-1;noteChoice=noteIds.get(index);evidenceNotes.setSelection(index);if(selectedNote.isEmpty())noteRefs.setText(noteChoice);}finally{binding=false;}
        history.setText(text.toString());if(review.has("target"))target.setText(review.getString("target"));candidateDescription();
    }
    private void selectProposal(String id){int n=proposalIds.indexOf(id);proposal.setSelection(n<0?0:n);}
    private void adoptOptions(JSONObject response,String desired)throws Exception {
        options=new JSONArray(response.getJSONArray("options").toString());List<String> labels=new ArrayList<>();int chosen=-1;
        for(int i=0;i<options.length();i++){JSONObject o=options.getJSONObject(i);labels.add(o.getString("label")+(o.getBoolean("available")?"":" — unavailable: "+o.optString("reason")));if(o.optString("id").equals(desired))chosen=i;}
        ArrayAdapter<String> a=new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,labels){@Override public boolean isEnabled(int position){return position>=0&&position<options.length()&&options.optJSONObject(position).optBoolean("available");}@Override public boolean areAllItemsEnabled(){return false;}};
        binding=true;try{recipes.setAdapter(a);if(chosen>=0)recipes.setSelection(chosen);}finally{binding=false;}
        if(desired!=null)mode.setSelection(0);recipeDescription();
    }
    private void recipeDescription(){
        JSONObject recipe=selectedRecipe();StringBuilder description=new StringBuilder("Load choices for the selected object.");
        if(recipe!=null){description.setLength(0);description.append(recipe.optString("expectedChange"));JSONArray checks=recipe.optJSONArray("checks");if(checks!=null)for(int i=0;i<checks.length();i++)description.append("\nCheck: ").append(checks.optString(i));if(!recipe.optBoolean("available"))description.append("\nUnavailable: ").append(recipe.optString("reason"));}
        recipeDetails.setText(description);sync();
    }

    private void sync(){
        enable(form,!busy&&!closed);cancel.setEnabled(busy&&!closed);
        boolean loaded=review!=null,open=loaded&&review.optString("status").equals("OPEN"),current=open&&sourceMatches;
        record.setEnabled(!busy&&loaded);loadOptions.setEnabled(!busy&&current&&!objectId().isEmpty());relax.setEnabled(loadOptions.isEnabled());
        boolean recipe=mode.getSelectedItemPosition()==0;recipes.setEnabled(!busy&&recipe&&current);operations.setEnabled(!busy&&!recipe&&current);
        JSONObject selected=selectedRecipe();prepare.setEnabled(!busy&&current&&(!recipe||selected!=null&&selected.optBoolean("available")));
        evidenceNotes.setEnabled(!busy&&loaded&&noteIds.size()>0);
        showBefore.setEnabled(!busy&&loaded);showAfter.setEnabled(!busy&&loaded&&!selectedProposal().isEmpty());
        apply.setEnabled(!busy&&!selectedProposal().isEmpty()&&(current||loaded&&review.optString("status").equals("APPLIED")&&pendingApply!=null));
        apply.setText(loaded&&review.optString("status").equals("APPLIED")?"Confirm applied result":"Apply selected candidate");
    }
    private void bindChanges(){
        TextWatcher previewChange=watch(()->{if(!binding){clearImages();saveDraft();}});region.addTextChangedListener(previewChange);previewFrame.addTextChangedListener(previewChange);
        reviewId.addTextChangedListener(watch(()->{if(binding)return;cancelWork();review=null;sourceMatches=false;rememberedProposal="";pendingNote=null;pendingProposal=null;pendingApply=null;binding=true;try{proposalIds.clear();proposalIds.add("");proposal.setAdapter(adapter(new String[]{"No candidate selected"}));}finally{binding=false;}options=new JSONArray();history.setText("");sync();saveDraft();}));
        objects.setOnItemSelectedListener(changed(()->{options=new JSONArray();recipes.setAdapter(adapter(new String[]{"Load choices for the selected object"}));clearImages();recipeDescription();saveDraft();}));
        evidenceNotes.setOnItemSelectedListener(changed(()->{int n=evidenceNotes.getSelectedItemPosition();if(n>=0&&n<noteIds.size()&&!noteIds.get(n).equals(noteChoice)){noteChoice=noteIds.get(n);noteRefs.setText(noteChoice);saveDraft();}}));
        recipes.setOnItemSelectedListener(changed(()->{recipeDescription();saveDraft();}));mode.setOnItemSelectedListener(changed(()->{sync();saveDraft();}));
        proposal.setOnItemSelectedListener(changed(()->{if(review==null)return;String id=selectedProposal();if(!id.equals(rememberedProposal))clearImages();rememberedProposal=id;candidateDescription();sync();saveDraft();}));
        before.setOnClickListener(v->detail("Before",beforeBitmap));after.setOnClickListener(v->detail("After",afterBitmap));
    }
    private void detail(String title,Bitmap image){if(image==null||image.isRecycled())return;clearDetail();detailImage=new ImageView(activity);detailImage.setImageBitmap(image);detailImage.setLayoutParams(new android.widget.FrameLayout.LayoutParams(image.getWidth(),image.getHeight()));HorizontalScrollView horizontal=new HorizontalScrollView(activity);horizontal.addView(detailImage);ScrollView vertical=new ScrollView(activity);vertical.addView(horizontal);detailDialog=new AlertDialog.Builder(activity).setTitle(title+" · native pixels").setView(vertical).setNegativeButton("Close",null).create();detailDialog.show();}
    private void clearDetail(){if(detailImage!=null)detailImage.setImageDrawable(null);if(detailDialog!=null)detailDialog.dismiss();detailDialog=null;detailImage=null;}
    private void clearImage(String side){clearDetail();if(side.equals("before")){before.setImageDrawable(null);before.setVisibility(View.GONE);if(beforeBitmap!=null)beforeBitmap.recycle();beforeBitmap=null;}else{after.setImageDrawable(null);after.setVisibility(View.GONE);if(afterBitmap!=null)afterBitmap.recycle();afterBitmap=null;}}
    private void clearImages(){clearImage("before");clearImage("after");}
    private void cancelWork(){generation++;if(task!=null)task.cancel(true);task=null;busy=false;clearImages();sync();status.setText("Work cancelled; entries retained. Resume the review to confirm any completed apply.");saveDraft();}
    private boolean unavailable(){return closed||paused||activity.isFinishing()||activity.isDestroyed();}
    private static String auditSummary(JSONObject audit){
        StringBuilder s=new StringBuilder("native audit: ").append(audit.optInt("sampleCount")).append(" sampled frames; ").append(audit.optInt("alertCount")).append(" technical alerts. Visual inspection is required.");
        JSONArray alerts=audit.optJSONArray("alerts");if(alerts!=null)for(int i=0;i<Math.min(8,alerts.length());i++){JSONObject a=alerts.optJSONObject(i);if(a!=null)s.append("\nFrame ").append(a.optInt("frame")+1).append(": ").append(a.optString("message",a.optString("code")));}return s.toString();
    }
    private void candidateDescription(){
        String id=selectedProposal();String value="Preparing a correction leaves your drawing unchanged.";technical.setText("");
        if(review!=null&&!id.isEmpty()){JSONArray all=review.optJSONArray("proposals");if(all!=null)for(int i=0;i<all.length();i++){JSONObject p=all.optJSONObject(i);if(p!=null&&id.equals(p.optString("proposalId"))){value=p.optString("summary")+"\nTiming and canvas preserved. "+p.optInt("operationCount")+" native edit(s)."+(p.has("candidateAudit")?"\n"+auditSummary(p.optJSONObject("candidateAudit")):"")+"\nCompare the previews before applying.";try{technical.setText(p.toString(2));}catch(Exception error){technical.setText(p.toString());}break;}}}
        scope.setText(value);
    }
    @Override public void close(){if(closed)return;saveDraft();closed=true;generation++;if(task!=null)task.cancel(true);clearImages();if(savedDialog!=null)savedDialog.dismiss();if(dialog!=null&&dialog.isShowing())dialog.dismiss();worker.shutdownNow();activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);}
    private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(a==activity){paused=false;sync();}}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){if(a==activity){paused=true;cancelWork();}}public void onActivitySaveInstanceState(Activity a,Bundle b){if(a==activity)saveDraft();}public void onActivityDestroyed(Activity a){if(a==activity){if(dialog!=null)dialog.dismiss();close();}}
    };
    private EditText[] fields(){return new EditText[]{reviewId,target,first,last,step,region,noteId,note,noteRefs,proposalId,summary,operations,previewFrame};}
    private void saveDraft(){try{JSONObject draft=new JSONObject();for(EditText f:fields())draft.put(f.getContentDescription().toString(),f.getText().toString());draft.put("selectedProposal",rememberedProposal).put("outcome",outcome.getSelectedItemPosition()).put("mode",mode.getSelectedItemPosition()).put("noteSide",noteSide.getSelectedItemPosition()).put("objectId",objectId()).put("motion",motion.isChecked());if(pendingNote!=null)draft.put("pendingNote",pendingNote);if(pendingProposal!=null)draft.put("pendingProposal",pendingProposal);if(pendingApply!=null)draft.put("pendingApply",pendingApply);activity.getSharedPreferences("sketch_correction_drafts",Context.MODE_PRIVATE).edit().putString(sketchId,draft.toString()).apply();}catch(Exception ignored){/* A draft failure must not change coordinator/source state. */}}
    private void restoreDraft(){try{JSONObject draft=new JSONObject(activity.getSharedPreferences("sketch_correction_drafts",Context.MODE_PRIVATE).getString(sketchId,"{}"));binding=true;for(EditText f:fields())if(draft.has(f.getContentDescription().toString()))f.setText(draft.getString(f.getContentDescription().toString()));outcome.setSelection(draft.optInt("outcome",0));mode.setSelection(draft.optInt("mode",0));noteSide.setSelection(draft.optInt("noteSide",0));int n=objectIds.indexOf(draft.optString("objectId"));if(draft.has("objectId")&&n>=0)objects.setSelection(n);motion.setChecked(draft.optBoolean("motion"));pendingNote=draft.optJSONObject("pendingNote");pendingProposal=draft.optJSONObject("pendingProposal");pendingApply=draft.optJSONObject("pendingApply");rememberedProposal=draft.optString("selectedProposal",pendingApply==null?"":pendingApply.optString("proposalId"));}catch(Exception error){status.setText("Saved draft could not be loaded: "+error.getMessage());}finally{binding=false;}}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private TextView text(String value){TextView v=new TextView(activity);v.setText(value);building.addView(v);return v;}
    private EditText input(String label,String value){text(label);EditText v=new EditText(activity);v.setContentDescription(label);v.setSingleLine(true);v.setText(value);building.addView(v);return v;}
    private ArrayAdapter<String> adapter(String[] values){return new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values);}
    private Spinner spinner(String label,String[] values){text(label);Spinner s=new Spinner(activity);s.setContentDescription(label);s.setAdapter(adapter(values));building.addView(s);return s;}
    private Button button(String label,Action action){Button b=new Button(activity);b.setText(label);building.addView(b);b.setOnClickListener(v->{if(unavailable()||busy&&b!=cancel)return;try{action.run();}catch(Exception e){status.setText("Entries retained: "+e.getMessage());saveDraft();}});return b;}
    private ImageView image(String label){ImageView v=new ImageView(activity);v.setContentDescription(label);v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setAdjustViewBounds(true);v.setVisibility(View.GONE);building.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    private static TextWatcher watch(Runnable change){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){change.run();}public void afterTextChanged(Editable e){}};}
    private android.widget.AdapterView.OnItemSelectedListener changed(Runnable r){return new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int n,long id){if(!binding&&!closed&&!busy)r.run();}};}
    private static void enable(View v,boolean enabled){v.setEnabled(enabled);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)enable(((ViewGroup)v).getChildAt(i),enabled);}
}
