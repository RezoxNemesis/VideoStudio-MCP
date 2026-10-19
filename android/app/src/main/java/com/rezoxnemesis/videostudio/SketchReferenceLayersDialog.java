package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Manual source-assisted client. Only Create parts publishes a native operation. */
final class SketchReferenceLayersDialog implements AutoCloseable {
    private static final int MAX_DRAFTS=32,MAX_DRAFT_BYTES=512*1024,MAX_RETAINED_DRAFT_BYTES=8*1024*1024;
    interface Backend {JSONObject execute(Context context,String action,JSONObject request)throws Exception;}
    interface SourceLoader {Bitmap load(Context context,JSONObject document,String sourceId,String workingSha,String sourceSha)throws Exception;}
    private interface Action {void run()throws Exception;}
    private static final class Draft {
        final List<JSONObject> parts=new ArrayList<>();
        String backgroundLayer=id("background"),backgroundObject=id("background_image"),operationId=id("reference_parts");
        int selected,editing=-1;
        JSONArray pendingPoints=new JSONArray();
        boolean background,hideSource=true;
        boolean snap;
        int radius;
        JSONObject pendingRequest;
        String previewFingerprint;
        JSONArray previewMasks;
        boolean dirty;
        Draft()throws Exception{parts.add(part(1));}
    }
    private final Activity activity;
    private final JSONObject opened;
    private final int frame;
    private final Consumer<JSONObject> onApplied;
    private final Backend backend;
    private final SourceLoader sourceLoader;
    private final ExecutorService worker;
    private final LinearLayout form;
    private final SketchReferenceMaskView maskView;
    private final Spinner sources,parts,rings;
    private final EditText name;
    private final CheckBox background,hideSource,snap,pan;
    private final SeekBar snapRadius;
    private final TextView radiusLabel;
    private final TextView status;
    private final Button accept,newPart,newHole,create,preview,undo,clear,removePart,removeRing;
    private final ImageView previewImage;
    private final List<JSONObject> sourceObjects=new ArrayList<>();
    private final Map<String,Draft> drafts=new LinkedHashMap<>();
    private AlertDialog dialog;
    private Bitmap bitmap;
    private Bitmap previewBitmap;
    private Draft draft;
    private String draftWarning="";
    private int sourceIndex=-1,imageGeneration;
    private boolean binding,ready,applying,previewing,closed,reportedApply,draftSaveFailed;

    static SketchReferenceLayersDialog show(Activity activity,JSONObject document,int frame,Consumer<JSONObject> onApplied)throws Exception {
        SketchReferenceLayersDialog owner=new SketchReferenceLayersDialog(activity,document,frame,onApplied,
            SketchEditOps::execute,SketchReferenceLayersDialog::loadSource,Executors.newSingleThreadExecutor());
        owner.open();return owner;
    }
    SketchReferenceLayersDialog(Activity activity,JSONObject document,int frame,Consumer<JSONObject> onApplied,
        Backend backend,SourceLoader loader,ExecutorService worker)throws Exception {
        this.activity=activity;opened=SketchDocument.copy(document);this.frame=frame;
        if(frame<0||frame>=opened.getInt("frameCount"))throw SketchDocument.bad("Choose a frame inside the drawing");
        this.onApplied=onApplied;this.backend=backend;sourceLoader=loader;this.worker=worker;
        form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(16),dp(8),dp(16),dp(12));
        text("Select an imported image, outline its parts, then preview and create editable layers.");
        TextView requirements=text("Use an image on an unlocked layer of its own. Reset image and layer movement, opacity and cropping before separating parts. Hidden reference images are supported.");
        requirements.setContentDescription("Reference parts requirements");
        sources=spinner("Owned reference image");
        List<String> labels=new ArrayList<>();JSONArray layers=opened.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){
            JSONObject layer=layers.getJSONObject(i);JSONArray objects=layer.getJSONArray("objects");
            for(int j=0;j<objects.length();j++){
                JSONObject object=objects.getJSONObject(j);
                if("image".equals(object.optString("kind"))){sourceObjects.add(SketchDocument.copy(object));labels.add(layer.getString("name")+" · image "+(j+1));}
            }
        }
        sources.setAdapter(adapter(labels));
        maskView=new SketchReferenceMaskView(activity);form.addView(maskView,new LinearLayout.LayoutParams(-1,dp(280)));
        LinearLayout navigation=new LinearLayout(activity);form.addView(navigation);
        navigation.addView(navigationButton("Zoom in",maskView::zoomIn));navigation.addView(navigationButton("Zoom out",maskView::zoomOut));navigation.addView(navigationButton("Fit image",maskView::resetViewport));
        pan=new CheckBox(activity);pan.setText("Pan image");pan.setContentDescription("Pan image");form.addView(pan);pan.setOnCheckedChangeListener((b,v)->maskView.setPanMode(v));
        text("Tap 3–64 points around a part, then Accept ring. Drag a draft point to adjust it. Zoom with the buttons or pinch; use Pan image or two fingers to move the view. Add holes inside the outline. Accepted parts have coloured outlines; Preview parts checks the boundaries before creation.");
        parts=spinner("Accepted parts");name=input("Part name");rings=spinner("Ring to edit");
        status=text(sourceObjects.isEmpty()?"Import an owned reference image into Sketch first.":"Loading owned reference…");status.setContentDescription("Reference parts status");
        undo=button("Undo point",()->maskView.undoPoint());clear=button("Clear ring",()->{draft.editing=-1;maskView.clearRing();refreshRings();});
        accept=button("Accept ring",this::acceptRing);
        newHole=button("New hole",()->{draft.editing=-1;maskView.clearRing();refreshRings();status.setText("Tap a hole inside the accepted outline, then Accept ring.");});
        removeRing=button("Remove ring",this::removeRing);
        newPart=button("New part",this::newPart);removePart=button("Remove part",this::removePart);
        background=new CheckBox(activity);background.setText("Keep unselected pixels as background");background.setContentDescription("Keep unselected pixels as background");form.addView(background);
        hideSource=new CheckBox(activity);hideSource.setText("Hide source image");hideSource.setContentDescription("Hide source image");hideSource.setChecked(true);form.addView(hideSource);
        snap=new CheckBox(activity);snap.setText("Snap to nearby edges");snap.setContentDescription("Snap to nearby edges");form.addView(snap);
        radiusLabel=text("Edge radius: 0 source pixels (exact coordinates)");
        snapRadius=new SeekBar(activity);snapRadius.setMax(16);snapRadius.setContentDescription("Edge snap radius");form.addView(snapRadius);
        text("Optional edge assistance follows nearby source-pixel contrast, not object recognition. Radius 0 keeps exact points. Inspect the native preview before creation; saved parts retain full source precision.");
        text("Moving parts can uncover transparent gaps; paint missing background in Sketch Studio.");
        preview=button("Preview parts",this::preview);
        previewImage=new ImageView(activity);previewImage.setContentDescription("Native parts preview");previewImage.setAdjustViewBounds(true);previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);previewImage.setVisibility(View.GONE);form.addView(previewImage,new LinearLayout.LayoutParams(-1,-2));
        create=button("Create layers",this::apply);
        sources.setOnItemSelectedListener(selected(()->selectSource(sources.getSelectedItemPosition())));
        parts.setOnItemSelectedListener(selected(()->selectPart(parts.getSelectedItemPosition())));
        rings.setOnItemSelectedListener(selected(()->editRing(rings.getSelectedItemPosition()-1)));
        name.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(!binding&&draft!=null){try{currentPart().put("name",s.toString());changed();}catch(Exception error){message(error);}}}
            public void afterTextChanged(Editable e){}
        });
        background.setOnCheckedChangeListener((b,value)->{if(!binding&&draft!=null){draft.background=value;changed();}});
        hideSource.setOnCheckedChangeListener((b,value)->{if(!binding&&draft!=null){draft.hideSource=value;changed();}});
        snap.setOnCheckedChangeListener((b,value)->{if(!binding&&draft!=null){draft.snap=value;changed();}});
        snapRadius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int progress,boolean fromUser){radiusLabel.setText("Edge radius: "+progress+" source pixels"+(progress==0?" (exact coordinates)":""));if(!binding&&draft!=null){draft.radius=progress;changed();}}
            public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}
        });
        maskView.setCallbacks(()->{
            if(binding||draft==null)return;
            try{draft.pendingPoints=maskView.ring();changed();if(!draftSaveFailed)status.setText("Tap source points · "+maskView.pointCount()+"/64 in this draft ring. Accept before previewing parts.");}
            catch(Exception error){message(error);}
        },s->status.setText(s));
        sync();
    }
    void open(){
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        dialog=new AlertDialog.Builder(activity).setTitle("Separate reference into parts").setView(scroll).setNegativeButton("Close",(d,w)->close()).create();
        dialog.setOnDismissListener(d->close());dialog.show();
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        if(!sourceObjects.isEmpty())try{selectSource(0);}catch(Exception error){message(error);}
    }
    private void selectSource(int index)throws Exception {
        if(closed||applying||previewing||index<0||index>=sourceObjects.size()||index==sourceIndex)return;
        saveDraft();sourceIndex=index;ready=false;releaseBitmap();clearPreviewImage();final int generation=++imageGeneration;
        JSONObject source=sourceObjects.get(index);String sourceId=source.getString("id");
        draftWarning="";draft=drafts.get(sourceId);if(draft==null){draft=restoreDraft();drafts.put(sourceId,draft);}
        final String savedWarning=draftWarning;
        refreshParts();adoptPart();binding=true;
        try{background.setChecked(draft.background);hideSource.setChecked(draft.hideSource);snap.setChecked(draft.snap);snapRadius.setProgress(draft.radius);}finally{binding=false;}
        draft.previewFingerprint=null;draft.previewMasks=null;
        refreshOutlines();
        status.setText("Loading and validating the owned source off the UI thread…");sync();
        final JSONObject snapshot=SketchDocument.copy(opened);
        final String working=source.getJSONObject("reference").getString("sha256"),original=source.getJSONObject("reference").optString("sourceSha256","");
        try{worker.execute(()->{
            Bitmap loaded=null;
            try{
                loaded=sourceLoader.load(activity.getApplicationContext(),snapshot,sourceId,working,original);
                if(loaded==null)throw SketchDocument.bad("Owned image could not be decoded");
                final Bitmap image=loaded;
                activity.runOnUiThread(()->{
                    if(unavailable()||generation!=imageGeneration){image.recycle();return;}
                    bitmap=image;maskView.setBitmap(bitmap);ready=true;
                    status.setText(savedWarning.isEmpty()?"Tap source points to outline a named part, then Preview parts. Creation requires a current source-bound preview.":savedWarning);sync();
                });
            }catch(Exception|OutOfMemoryError error){
                if(loaded!=null)loaded.recycle();activity.runOnUiThread(()->{
                    if(unavailable()||generation!=imageGeneration)return;ready=false;message(error);sync();
                });
            }
        });}catch(Exception error){message(error);sync();}
    }
    private static Bitmap loadSource(Context context,JSONObject opened,String sourceId,String working,String original)throws Exception {
        JSONObject source=SketchReferenceLayers.requireSource(opened,sourceId,working,original);
        JSONObject ref=source.getJSONObject("reference");
        try(SketchProjectStore store=new SketchProjectStore(context)){
            File file=store.referenceFile(opened.getString("id"),ref);
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
            if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>2048||bounds.outHeight>2048||bounds.outWidth!=ref.getInt("width")||bounds.outHeight!=ref.getInt("height"))
                throw SketchDocument.bad("Owned reference dimensions changed or exceed 2048 pixels");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap image=BitmapFactory.decodeFile(file.getPath(),options);
            if(image==null)throw SketchDocument.bad("Owned reference decode failed");
            try{
                if(image.getWidth()!=bounds.outWidth||image.getHeight()!=bounds.outHeight)throw SketchDocument.bad("Owned reference decode dimensions changed");
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Source decode interrupted");
                store.referenceFile(opened.getString("id"),ref);return image;
            }catch(Exception error){image.recycle();throw error;}
        }
    }
    private JSONObject currentPart(){return draft.parts.get(draft.selected);}
    private static JSONObject part(int ordinal)throws Exception {
        return new JSONObject().put("maskId",id("mask")).put("layerId",id("part_layer")).put("objectId",id("part_image")).put("name","Part "+ordinal).put("rings",new JSONArray());
    }
    private void selectPart(int index)throws Exception {
        if(draft==null||index<0||index>=draft.parts.size()||index==draft.selected)return;
        if(maskView.pointCount()>0){status.setText("Accept or clear this draft ring before selecting another part.");refreshParts();return;}
        draft.selected=index;draft.editing=-1;draft.pendingPoints=new JSONArray();adoptPart();
        saveDraft();
    }
    private void adoptPart()throws Exception {
        binding=true;try{name.setText(currentPart().getString("name"));maskView.setRing(draft.pendingPoints);refreshRings();refreshOutlines();}finally{binding=false;}sync();
    }
    private void refreshParts()throws Exception {
        boolean was=binding;binding=true;try{
            List<String> labels=new ArrayList<>();for(JSONObject p:draft.parts)labels.add(p.getString("name")+" · "+p.getJSONArray("rings").length()+" rings");
            parts.setAdapter(adapter(labels));parts.setSelection(draft.selected);
        }finally{binding=was;}
    }
    private void refreshRings()throws Exception {
        boolean was=binding;binding=true;try{
            List<String> labels=new ArrayList<>();labels.add("New ring / hole");
            int count=currentPart().getJSONArray("rings").length();for(int i=0;i<count;i++)labels.add(i==0?"Outer boundary":"Hole "+i);
            rings.setAdapter(adapter(labels));rings.setSelection(draft.editing+1);
        }finally{binding=was;}
    }
    private void refreshOutlines()throws Exception {
        JSONArray masks=draft.previewMasks;if(masks==null){masks=new JSONArray();for(JSONObject p:draft.parts)masks.put(p);}maskView.setAccepted(masks,draft.selected);
    }
    private void editRing(int index)throws Exception {
        if(draft==null||index==draft.editing)return;
        JSONArray accepted=currentPart().getJSONArray("rings");if(index>=accepted.length())return;
        if(maskView.pointCount()>0&&index!=draft.editing){status.setText("Accept or clear this draft ring first.");refreshRings();return;}
        draft.editing=index;maskView.setRing(index<0?new JSONArray():accepted.getJSONArray(index));sync();
    }
    private void acceptRing()throws Exception {
        String title=name.getText().toString().trim();if(title.isEmpty()||title.length()>160)throw SketchDocument.bad("Give this part a name of 1–160 characters");
        JSONArray points=maskView.ring();if(points.length()<3||points.length()>64)throw SketchDocument.bad("A ring needs 3–64 source points");
        JSONArray accepted=currentPart().getJSONArray("rings");if(draft.editing<0&&accepted.length()>=8)throw SketchDocument.bad("A part allows at most eight rings");
        currentPart().put("name",title);if(draft.editing<0)accepted.put(points);else accepted.put(draft.editing,points);
        draft.editing=-1;maskView.clearRing();refreshParts();refreshRings();refreshOutlines();sync();
        status.setText("Accepted "+title+" · "+accepted.length()+" rings. Inspect coloured boundaries before Create parts.");
    }
    private void newPart()throws Exception {
        if(draft.parts.size()>=8)throw SketchDocument.bad("At most eight parts per operation");
        draft.parts.add(part(draft.parts.size()+1));draft.selected=draft.parts.size()-1;draft.editing=-1;draft.pendingPoints=new JSONArray();refreshParts();adoptPart();changed();
        status.setText("Name this new part and tap its outer boundary.");
    }
    private void removePart()throws Exception {
        draft.parts.remove(draft.selected);if(draft.parts.isEmpty())draft.parts.add(part(1));
        draft.selected=Math.min(draft.selected,draft.parts.size()-1);draft.editing=-1;draft.pendingPoints=new JSONArray();refreshParts();adoptPart();changed();
    }
    private void removeRing()throws Exception {
        if(draft.editing<0)return;currentPart().getJSONArray("rings").remove(draft.editing);draft.editing=-1;maskView.clearRing();refreshParts();refreshRings();refreshOutlines();sync();
    }
    private JSONObject request()throws Exception {
        if(draft.pendingRequest!=null)return SketchDocument.copy(draft.pendingRequest);
        if(maskView.pointCount()>0)throw SketchDocument.bad("Accept or clear the draft ring before creating parts");
        JSONArray masks=new JSONArray();for(JSONObject p:draft.parts){if(p.getJSONArray("rings").length()==0)throw SketchDocument.bad("Every part needs an accepted outer ring");masks.put(SketchDocument.copy(p));}
        JSONObject ref=sourceObjects.get(sourceIndex).getJSONObject("reference");
        JSONObject request=new JSONObject().put("sketchId",opened.getString("id")).put("expectedRevision",opened.getLong("revision")).put("operationId",draft.operationId)
            .put("sourceObjectId",sourceObjects.get(sourceIndex).getString("id")).put("expectedReferenceSha256",ref.getString("sha256")).put("expectedSourceSha256",ref.getString("sourceSha256"))
            .put("frame",frame).put("exposure",opened.getInt("frameCount")-frame).put("hideSource",draft.hideSource).put("masks",masks);
        if(draft.background)request.put("background",new JSONObject().put("layerId",draft.backgroundLayer).put("objectId",draft.backgroundObject).put("name","Unselected background"));
        if(draft.snap)request.put("edgeSnapRadius",draft.radius);
        draft.pendingRequest=SketchDocument.copy(request);return request;
    }
    private static String fingerprint(JSONObject request)throws Exception {return SketchDocument.sha256(SketchDocument.canonical(request).getBytes(StandardCharsets.UTF_8));}
    private void changed(){
        if(draft==null)return;draft.operationId=id("reference_parts");draft.pendingRequest=null;draft.previewFingerprint=null;draft.previewMasks=null;draft.dirty=true;clearPreviewImage();
        try{refreshOutlines();}catch(Exception error){message(error);}sync();saveDraft();
    }
    private void clearPreviewImage(){previewImage.setImageDrawable(null);previewImage.setVisibility(View.GONE);if(previewBitmap!=null&&!previewBitmap.isRecycled())previewBitmap.recycle();previewBitmap=null;}
    private void preview()throws Exception {
        final JSONObject request=request();final String intent=fingerprint(request);final int generation=imageGeneration;
        draft.previewFingerprint=null;draft.previewMasks=null;clearPreviewImage();previewing=true;sync();status.setText("Checking masks and rendering the native source overlay…");
        try{worker.execute(()->{
            Bitmap image=null;
            try{
                JSONObject reply=backend.execute(activity.getApplicationContext(),"sketch_reference_layers_preview",request);
                if(reply==null||!reply.getBoolean("ok")||reply.getBoolean("sourceModified")||reply.getLong("sourceRevision")!=request.getLong("expectedRevision")
                    ||!request.getString("expectedReferenceSha256").equals(reply.getString("sourceReferenceSha256"))||!request.getString("expectedSourceSha256").equals(reply.getString("sourceSha256")))
                    throw SketchDocument.bad("Preview source no longer matches this draft. Keep the draft and reopen the current source before creating layers.");
                JSONArray accepted=reply.getJSONArray("acceptedMasks");if(accepted.length()!=request.getJSONArray("masks").length())throw SketchDocument.bad("Native preview returned a different part count");
                for(int i=0;i<accepted.length();i++)if(!request.getJSONArray("masks").getJSONObject(i).getString("maskId").equals(accepted.getJSONObject(i).getString("maskId")))throw SketchDocument.bad("Native preview returned different part identities");
                JSONObject encoded=reply.getJSONObject("sketchPreviewImage");String mime=encoded.getString("mimeType");
                if(!"image/png".equals(mime)&&!"image/jpeg".equals(mime))throw SketchDocument.bad("Unsupported native preview image format");
                String base64=encoded.getString("base64");if(base64.length()>12*1024*1024)throw SketchDocument.bad("Native preview exceeds the image budget");
                byte[] bytes=Base64.decode(base64,Base64.DEFAULT);if(bytes.length>8*1024*1024||!SketchDocument.sha256(bytes).equals(encoded.getString("sha256")))throw SketchDocument.bad("Native preview image hash changed");
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>2048||bounds.outHeight>2048||bounds.outWidth!=encoded.getInt("width")||bounds.outHeight!=encoded.getInt("height"))throw SketchDocument.bad("Native preview dimensions rejected");
                image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(image==null)throw SketchDocument.bad("Native preview could not be decoded");
                final Bitmap done=image;activity.runOnUiThread(()->{
                    previewing=false;if(unavailable()||generation!=imageGeneration){done.recycle();return;}
                    try{
                        if(!intent.equals(fingerprint(request()))){done.recycle();status.setText("Draft changed; Preview parts again.");sync();return;}
                        draft.previewFingerprint=intent;draft.previewMasks=new JSONArray(accepted.toString());previewBitmap=done;previewImage.setImageBitmap(done);previewImage.setVisibility(View.VISIBLE);refreshOutlines();
                        status.setText("Native preview accepted · "+reply.optInt("edgeAdjustedPointCount")+" points adjusted near source edges. Inspect the overlay, then Create layers. Source unchanged.");sync();
                    }catch(Exception error){done.recycle();message(error);sync();}
                });
            }catch(Exception|OutOfMemoryError error){if(image!=null)image.recycle();activity.runOnUiThread(()->{previewing=false;if(unavailable()||generation!=imageGeneration)return;message(error);sync();});}
        });}catch(Exception error){previewing=false;message(error);sync();}
    }
    private void apply()throws Exception {
        final JSONObject request=request();if(!fingerprint(request).equals(draft.previewFingerprint))throw SketchDocument.bad("Preview parts again after changing this source-bound draft");
        final String committedDraftKey=draftKey();
        applying=true;sync();status.setText("Creating persistent native parts…");
        try{worker.execute(()->{
            try{
                JSONObject reply=backend.execute(activity.getApplicationContext(),"sketch_import_reference_layers",request);
                if(reply==null||reply.has("ok")&&!reply.optBoolean("ok")||!reply.optBoolean("persisted"))throw SketchDocument.bad(reply==null?"Native operation returned no receipt":reply.optString("error","Native operation did not confirm a commit"));
                if(!opened.getString("id").equals(reply.optString("sketchId")))throw SketchDocument.bad("Native receipt belongs to another drawing");
                activity.runOnUiThread(()->{
                    applying=false;
                    draft.dirty=false;draftPreferences().edit().remove(committedDraftKey).apply();
                    // A known commit still belongs to the captured editor even after dismiss.
                    if(!reportedApply&&!activity.isDestroyed()&&!activity.isFinishing()){
                        reportedApply=true;
                        try{onApplied.accept(reply);}catch(RuntimeException refresh){if(!closed)status.setText("Parts committed; editor refresh failed: "+refresh.getMessage());}
                    }
                    if(dialog!=null)dialog.dismiss();close();
                });
            }catch(Exception|OutOfMemoryError error){activity.runOnUiThread(()->{
                applying=false;if(unavailable())return;message(error);sync();
            });}
        });}catch(Exception error){applying=false;message(error);sync();}
    }
    private void sync(){
        boolean edit=!closed&&!applying&&!previewing&&draft!=null&&ready;
        sources.setEnabled(!closed&&!applying&&!previewing&&!sourceObjects.isEmpty());
        maskView.setEnabled(edit);parts.setEnabled(edit);rings.setEnabled(edit);name.setEnabled(edit);background.setEnabled(edit);hideSource.setEnabled(edit);snap.setEnabled(edit);snapRadius.setEnabled(edit&&draft.snap);pan.setEnabled(edit);
        int points=maskView.pointCount(),count=draft==null?0:currentPart().optJSONArray("rings").length();
        accept.setEnabled(edit&&points>=3&&points<=64&&!name.getText().toString().trim().isEmpty());undo.setEnabled(edit&&points>0);clear.setEnabled(edit&&points>0);
        newHole.setEnabled(edit&&points==0&&count>0&&count<8);newPart.setEnabled(edit&&points==0&&count>0&&draft.parts.size()<8);
        removePart.setEnabled(edit);removeRing.setEnabled(edit&&draft.editing>=0);
        boolean complete=edit&&points==0; if(draft!=null)for(JSONObject p:draft.parts)complete&=p.optJSONArray("rings").length()>0;
        preview.setEnabled(complete);boolean current=false;try{current=complete&&fingerprint(request()).equals(draft.previewFingerprint);}catch(Exception invalid){message(invalid);}create.setEnabled(current);
    }
    private void message(Throwable error){status.setText("Draft retained: "+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()));}
    private void releaseBitmap(){maskView.setBitmap(null);if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();bitmap=null;}
    private boolean unavailable(){return closed||activity.isDestroyed()||activity.isFinishing();}
    @Override public void close(){
        if(closed)return;saveDraft();closed=true;++imageGeneration;releaseBitmap();clearPreviewImage();
        // Do not interrupt an acknowledged Create request and lose its eventual commit receipt.
        if(applying)worker.shutdown();else worker.shutdownNow();
        activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        if(dialog!=null&&dialog.isShowing())dialog.dismiss();
    }
    private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}
        public void onActivityDestroyed(Activity a){if(a==activity)close();}
    };
    private SharedPreferences draftPreferences(){return activity.getSharedPreferences("sketch_reference_layer_drafts",Context.MODE_PRIVATE);}
    private String legacyDraftKey(){return opened.optString("id")+"/"+sourceObjects.get(sourceIndex).optString("id");}
    private String draftKey()throws Exception {return bindingKey(sourceBinding());}
    private static String bindingKey(JSONObject binding)throws Exception {return "v2/"+SketchDocument.sha256(SketchDocument.canonical(binding).getBytes(StandardCharsets.UTF_8));}
    private JSONObject sourceBinding()throws Exception {
        JSONObject source=sourceObjects.get(sourceIndex),ref=source.getJSONObject("reference");
        return new JSONObject().put("sketchId",opened.getString("id")).put("sourceRevision",opened.getLong("revision")).put("sourceObjectId",source.getString("id"))
            .put("sourceReferenceSha256",ref.getString("sha256")).put("sourceSha256",ref.optString("sourceSha256","")).put("frame",frame)
            .put("width",opened.getInt("width")).put("height",opened.getInt("height")).put("fps",opened.getInt("fps"));
    }
    private void saveDraft(){
        if(draft==null||sourceIndex<0||!draft.dirty)return;
        try{
            JSONArray masks=new JSONArray();for(JSONObject part:draft.parts)masks.put(SketchDocument.copy(part));
            JSONObject saved=new JSONObject().put("version",1).put("binding",sourceBinding()).put("masks",masks).put("selected",draft.selected).put("editing",draft.editing)
                .put("points",draft.pendingPoints).put("background",draft.background).put("hideSource",draft.hideSource).put("snap",draft.snap).put("radius",draft.radius)
                .put("backgroundLayer",draft.backgroundLayer).put("backgroundObject",draft.backgroundObject).put("operationId",draft.operationId);
            storeDraft(draftKey(),saved.toString(),null,null);draftSaveFailed=false;
        }catch(Exception error){draftSaveFailed=true;status.setText("Draft remains in this dialog; local draft saving unavailable: "+error.getMessage());}
    }
    private Draft restoreDraft()throws Exception {
        Draft restored=new Draft();
        try{migrateLegacyDraft();}catch(Exception error){draftWarning="Existing legacy draft is retained; migration unavailable: "+error.getMessage();}
        String json=draftPreferences().getString(draftKey(),"");
        if(json.isEmpty()){
            if(draftWarning.isEmpty()&&hasOtherDraft())draftWarning="A draft for an older source or another frame is retained separately. Start this source's fresh draft; preview again before creating layers.";
            return restored;
        }
        try{
            if(json.getBytes(StandardCharsets.UTF_8).length>MAX_DRAFT_BYTES)throw SketchDocument.bad("Stored draft exceeds 512 KiB");
            JSONObject saved=new JSONObject(json);
            if(saved.getInt("version")!=1||!SketchDocument.canonical(sourceBinding()).equals(SketchDocument.canonical(saved.getJSONObject("binding")))){
                draftWarning="Saved draft belongs to an older source, frame or revision. It is kept separately; start fresh here or return to that source. No preview is accepted.";return restored;
            }
            return decodeDraft(saved);
        }catch(Exception error){draftWarning="Saved draft could not be loaded: "+error.getMessage()+". Start a fresh draft; the source is unchanged.";return new Draft();}
    }
    private static Draft decodeDraft(JSONObject saved)throws Exception {
            SketchDocument.fields(saved,"version,binding,masks,selected,editing,points,background,hideSource,snap,radius,backgroundLayer,backgroundObject,operationId");
            if(saved.getInt("version")!=1)throw SketchDocument.bad("Unsupported stored draft version");validateBinding(saved.getJSONObject("binding"));Draft restored=new Draft();
            JSONArray masks=saved.getJSONArray("masks");if(masks.length()<1||masks.length()>8)throw SketchDocument.bad("Stored draft part count rejected");restored.parts.clear();
            for(int i=0;i<masks.length();i++){
                JSONObject part=SketchDocument.copy(masks.getJSONObject(i));SketchDocument.fields(part,"maskId,layerId,objectId,name,rings");
                for(String field:new String[]{"maskId","layerId","objectId"})SketchDocument.id(part,field);SketchDocument.text(part,"name",160);
                JSONArray rings=part.getJSONArray("rings");if(rings.length()>8)throw SketchDocument.bad("Stored draft ring count rejected");for(int j=0;j<rings.length();j++)validateDraftPoints(rings.getJSONArray(j),3);restored.parts.add(part);
            }
            restored.selected=(int)SketchDocument.integer(saved,"selected",0,restored.parts.size()-1);restored.editing=(int)SketchDocument.integer(saved,"editing",-1,restored.parts.get(restored.selected).getJSONArray("rings").length()-1);
            restored.pendingPoints=new JSONArray(saved.getJSONArray("points").toString());validateDraftPoints(restored.pendingPoints,0);
            restored.background=saved.getBoolean("background");restored.hideSource=saved.getBoolean("hideSource");restored.snap=saved.getBoolean("snap");restored.radius=(int)SketchDocument.integer(saved,"radius",0,16);
            restored.backgroundLayer=SketchDocument.id(saved,"backgroundLayer");restored.backgroundObject=SketchDocument.id(saved,"backgroundObject");restored.operationId=SketchDocument.id(saved,"operationId");restored.dirty=true;return restored;
    }
    private static void validateBinding(JSONObject value)throws Exception {
        SketchDocument.fields(value,"sketchId,sourceRevision,sourceObjectId,sourceReferenceSha256,sourceSha256,frame,width,height,fps");
        SketchDocument.id(value,"sketchId");SketchDocument.id(value,"sourceObjectId");SketchDocument.integer(value,"sourceRevision",1,Long.MAX_VALUE-1);SketchDocument.integer(value,"frame",0,1199);
        for(String field:new String[]{"sourceReferenceSha256","sourceSha256"})if(!value.getString(field).matches("[a-f0-9]{64}"))throw SketchDocument.bad("Stored draft source hash rejected");
        for(String field:new String[]{"width","height"})if((SketchDocument.integer(value,field,256,2048)&1)!=0)throw SketchDocument.bad("Stored draft canvas rejected");
        NativeFrameClock.requireFps((int)SketchDocument.integer(value,"fps",24,60));
    }
    /** Move legacy values under THEIR binding, including when another frame is being opened. */
    private void migrateLegacyDraft()throws Exception {
        SharedPreferences preferences=draftPreferences();String legacyKey=legacyDraftKey();
        synchronized(SketchReferenceLayersDialog.class){
            String legacy=preferences.getString(legacyKey,"");if(legacy.isEmpty())return;
            if(legacy.getBytes(StandardCharsets.UTF_8).length>MAX_DRAFT_BYTES)throw SketchDocument.bad("Legacy draft exceeds 512 KiB");
            JSONObject saved=new JSONObject(legacy);decodeDraft(saved);String destination=bindingKey(saved.getJSONObject("binding"));
            String existing=preferences.getString(destination,null);
            // A newer full-binding value wins; preserve the different legacy value, too.
            if(existing!=null&&!existing.equals(legacy))return;
            storeDraft(destination,legacy,legacyKey,legacy);
        }
    }
    /** Bounded retention never silently evicts existing geometry to save a different draft. */
    private void storeDraft(String key,String json,String oldKey,String oldValue)throws Exception {
        int bytes=json.getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_DRAFT_BYTES)throw SketchDocument.bad("Draft exceeds 512 KiB");
        synchronized(SketchReferenceLayersDialog.class){
            SharedPreferences preferences=draftPreferences();boolean removeOld=oldKey!=null&&oldValue.equals(preferences.getString(oldKey,null));
            long retained=bytes+key.getBytes(StandardCharsets.UTF_8).length;int entries=1;
            for(Map.Entry<String,?> e:preferences.getAll().entrySet()){
                if(e.getKey().equals(key)||removeOld&&e.getKey().equals(oldKey))continue;
                entries++;retained+=e.getKey().getBytes(StandardCharsets.UTF_8).length+String.valueOf(e.getValue()).getBytes(StandardCharsets.UTF_8).length;
            }
            if(entries>MAX_DRAFTS||retained>MAX_RETAINED_DRAFT_BYTES)throw SketchDocument.bad("Draft storage full (32 drafts / 8 MiB); existing drafts kept. This draft remains in the open dialog.");
            SharedPreferences.Editor edit=preferences.edit().putString(key,json);if(removeOld)edit.remove(oldKey);edit.apply();
        }
    }
    private boolean hasOtherDraft(){
        for(Object value:draftPreferences().getAll().values())try{
            if(!(value instanceof String)||((String)value).getBytes(StandardCharsets.UTF_8).length>MAX_DRAFT_BYTES)continue;
            JSONObject saved=new JSONObject((String)value),bound=saved.getJSONObject("binding");
            if(opened.getString("id").equals(bound.optString("sketchId"))&&sourceObjects.get(sourceIndex).getString("id").equals(bound.optString("sourceObjectId")))return true;
        }catch(Exception ignored){/* Keep unrelated or unsupported entries without attempting a rebase. */}
        return false;
    }
    private static void validateDraftPoints(JSONArray points,int minimum)throws Exception {
        if(points.length()<minimum||points.length()>64)throw SketchDocument.bad("Stored draft needs bounded points");
        for(int i=0;i<points.length();i++){JSONArray p=points.getJSONArray(i);if(p.length()!=2)throw SketchDocument.bad("Stored draft coordinate pair required");for(int j=0;j<2;j++)SketchDocument.scalar(p.get(j),0,1,"Source point");}
    }
    private static String id(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-","");}
    private int dp(int n){return StudioUi.dp(activity,n);}
    private TextView text(String value){TextView v=new TextView(activity);v.setText(value);form.addView(v);return v;}
    private EditText input(String label){text(label);EditText v=new EditText(activity);v.setSingleLine(true);v.setContentDescription(label);form.addView(v);return v;}
    private Spinner spinner(String label){text(label);Spinner s=new Spinner(activity);s.setContentDescription(label);form.addView(s);return s;}
    private ArrayAdapter<String> adapter(List<String> values){return new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values);}
    private Button button(String label,Action action){Button b=new Button(activity);b.setText(label);StudioUi.styleButton(b,label.equals("Create layers"));form.addView(b);b.setOnClickListener(v->{if(closed||applying||previewing)return;try{action.run();}catch(Exception error){message(error);}});return b;}
    private Button navigationButton(String label,Runnable action){Button b=new Button(activity);b.setText(label);StudioUi.styleButton(b,false);b.setOnClickListener(v->{if(!closed&&!applying&&!previewing)action.run();});return b;}
    private android.widget.AdapterView.OnItemSelectedListener selected(Action action){return new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){if(binding||closed||applying||previewing)return;try{action.run();}catch(Exception error){message(error);}}};}
}
