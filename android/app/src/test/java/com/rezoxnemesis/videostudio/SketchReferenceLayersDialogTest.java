package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.*;
import java.lang.reflect.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Native touch/UI boundary tests; core extraction belongs to its own suite. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SketchReferenceLayersDialogTest {
    private Activity activity;
    private Object owner;
    private QueueWorker worker;
    private JSONObject document;
    private final List<JSONObject> requests=new ArrayList<>();
    private final List<JSONObject> previews=new ArrayList<>();
    private final List<Bitmap> decoded=new ArrayList<>();
    private final AtomicInteger callbacks=new AtomicInteger();
    private int failures;
    private String sourceFailure;
    private boolean wrongPreviewSource;
    private static final String WORKING=String.join("",Collections.nCopies(64,"a"));
    private static final String ORIGINAL=String.join("",Collections.nCopies(64,"b"));

    @After public void cleanup()throws Exception {
        if(owner!=null)owner.getClass().getMethod("close").invoke(owner);
        if(activity!=null)activity.finish();
        for(Bitmap b:decoded)if(!b.isRecycled())b.recycle();
    }
    private Class<?> dialogType() {
        try{return Class.forName("com.rezoxnemesis.videostudio.SketchReferenceLayersDialog");}
        catch(ClassNotFoundException absent){throw new AssertionError("Missing native named-reference mask dialog",absent);}
    }
    @Test public void entryPointMatchesParentMenuContract()throws Exception {
        assertNotNull(dialogType().getDeclaredMethod("show",Activity.class,JSONObject.class,int.class,Consumer.class));
        assertNotNull(dialogType().getMethod("close"));
    }
    private JSONObject source(String id)throws Exception {
        return SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","image").put("frame",0).put("exposure",24)
            .put("bounds",new JSONObject().put("x",.1).put("y",.2).put("width",.8).put("height",.6))
            .put("reference",new JSONObject().put("file",WORKING+".png").put("sha256",WORKING).put("width",64).put("height",32)
                .put("sourceProjectId","owned_project").put("sourceAssetId",id+"_asset").put("sourceSha256",ORIGINAL)
                .put("sourceWidth",64).put("sourceHeight",32).put("sourceOrientation",1)));
    }
    private void setup()throws Exception {setup(1);}
    private void setup(int revision)throws Exception {setup(revision,3);}
    private void setup(int revision,int frame)throws Exception {
        activity=Robolectric.buildActivity(Activity.class).setup().get();
        document=SketchDocument.create(new JSONObject().put("sketchId","mask_dialog").put("width",320).put("height",320).put("frameCount",24));
        document.put("revision",revision);
        document.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(source("reference_one"));
        JSONObject other=SketchDocument.copy(document.getJSONArray("layers").getJSONObject(0));
        other.put("id","other_layer").put("name","Second owned image").put("objects",new JSONArray().put(source("reference_two")));
        document.getJSONArray("layers").put(other);
        Class<?> type=dialogType(),backend=Class.forName(type.getName()+"$Backend"),loader=Class.forName(type.getName()+"$SourceLoader");
        Object engine=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{backend},(p,m,a)->{
            assertNotSame("Native edit must leave UI thread",Looper.getMainLooper(),Looper.myLooper());
            JSONObject request=new JSONObject(a[2].toString());
            if("sketch_reference_layers_preview".equals(a[1])){
                previews.add(request);Bitmap overlay=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888);overlay.eraseColor(0xff33aadd);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();overlay.compress(Bitmap.CompressFormat.PNG,100,bytes);overlay.recycle();
                return new JSONObject().put("ok",true).put("sourceModified",false).put("sourceRevision",wrongPreviewSource?99:request.getLong("expectedRevision"))
                    .put("sourceReferenceSha256",WORKING).put("sourceSha256",ORIGINAL).put("acceptedMasks",request.getJSONArray("masks"))
                    .put("edgeAdjustedPointCount",0).put("sketchPreviewImage",new JSONObject().put("mimeType","image/png")
                        .put("base64",Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)).put("sha256",SketchDocument.sha256(bytes.toByteArray())).put("width",64).put("height",32));
            }
            assertEquals("sketch_import_reference_layers",a[1]);requests.add(request);
            if(failures-->0)throw new IllegalStateException("SKETCH_REVISION_CONFLICT: drawing changed");
            return new JSONObject().put("sketchId","mask_dialog").put("revision",2).put("persisted",true);
        });
        Object imageLoader=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{loader},(p,m,a)->{
            assertNotSame("Decode must leave UI thread",Looper.getMainLooper(),Looper.myLooper());
            if(sourceFailure!=null)throw new IllegalStateException(sourceFailure);
            Bitmap b=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888);b.eraseColor(0x804488cc);decoded.add(b);return b;
        });
        worker=new QueueWorker();Constructor<?> ctor=type.getDeclaredConstructor(Activity.class,JSONObject.class,int.class,Consumer.class,backend,loader,ExecutorService.class);
        ctor.setAccessible(true);owner=ctor.newInstance(activity,document,frame,(Consumer<JSONObject>)r->{assertEquals("mask_dialog",r.optString("sketchId"));callbacks.incrementAndGet();},engine,imageLoader,worker);
        Method open=type.getDeclaredMethod("open");open.setAccessible(true);open.invoke(owner);idle();
    }
    private View root()throws Exception {Field f=owner.getClass().getDeclaredField("form");f.setAccessible(true);return (View)f.get(owner);}
    private View find(View v,String label){
        if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())||v instanceof Button&&label.contentEquals(((Button)v).getText()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private View view(String label)throws Exception {View v=find(root(),label);assertNotNull(label,v);return v;}
    private void click(String label)throws Exception {assertTrue("Enabled: "+label,view(label).isEnabled());view(label).performClick();idle();}
    private void text(String label,String value)throws Exception {((EditText)view(label)).setText(value);idle();}
    private String status()throws Exception{return ((TextView)view("Reference parts status")).getText().toString();}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void finishWork()throws Exception {worker.runNext();idle();}
    private View canvas()throws Exception {View v=view("Reference polygon editor");v.layout(0,0,320,320);return v;}
    private void point(double x,double y)throws Exception {tap(canvas(),(float)(x*320),(float)(80+y*160));}
    private static void tap(View v,float x,float y){
        MotionEvent down=MotionEvent.obtain(1,1,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(1,2,MotionEvent.ACTION_UP,x,y,0);
        v.onTouchEvent(down);v.onTouchEvent(up);down.recycle();up.recycle();idle();
    }
    private void square(double x,double y,double size)throws Exception {point(x,y);point(x+size,y);point(x+size,y+size);point(x,y+size);}
    private void accept(String name,double x,double y,double size)throws Exception {text("Part name",name);square(x,y,size);click("Accept ring");}
    private void preview()throws Exception {click("Preview parts");finishWork();}

    @Test public void touchBuildsNamedPartsAndHoleWithExactSourceCasWithoutMutatingOpenedDocument()throws Exception {
        setup();String initial=SketchDocument.canonical(document);assertFalse(view("Create layers").isEnabled());finishWork();
        accept("Head",.05,.05,.4);click("New hole");square(.15,.15,.1);click("Accept ring");
        assertTrue(((Spinner)view("Accepted parts")).getSelectedItem().toString().contains("Head"));
        click("New part");accept("Hand",.65,.1,.2);
        ((CheckBox)view("Keep unselected pixels as background")).setChecked(true);
        ((CheckBox)view("Hide source image")).setChecked(true);
        Bitmap preview=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888);canvas().draw(new Canvas(preview));
        assertNotEquals("Accepted outlines are visible",preview.getPixel(16,88),preview.getPixel(160,160));preview.recycle();
        assertFalse(view("Create layers").isEnabled());preview();click("Create layers");assertEquals(initial,SketchDocument.canonical(document));finishWork();
        JSONObject r=requests.get(0);assertEquals(1,callbacks.get());assertEquals(1,r.getLong("expectedRevision"));assertEquals(3,r.getInt("frame"));assertEquals(21,r.getInt("exposure"));
        assertEquals(previews.get(0).toString(),r.toString());
        assertEquals(WORKING,r.getString("expectedReferenceSha256"));assertEquals(ORIGINAL,r.getString("expectedSourceSha256"));assertTrue(r.getBoolean("hideSource"));assertTrue(r.has("background"));
        JSONArray masks=r.getJSONArray("masks");assertEquals(2,masks.length());assertEquals("Head",masks.getJSONObject(0).getString("name"));assertEquals(2,masks.getJSONObject(0).getJSONArray("rings").length());
        assertEquals(.05,masks.getJSONObject(0).getJSONArray("rings").getJSONArray(0).getJSONArray(0).getDouble(0),.00001);
        Set<String> ids=new HashSet<>();for(int i=0;i<masks.length();i++)for(String k:new String[]{"maskId","layerId","objectId"})assertTrue(ids.add(masks.getJSONObject(i).getString(k)));
        assertEquals(initial,SketchDocument.canonical(document));
    }
    @Test public void failuresKeepAcceptedDraftAndRetryExactOperationAfterDialogCloseStillReportsCommit()throws Exception {
        setup();finishWork();accept("Torso",.1,.1,.5);preview();failures=1;click("Create layers");finishWork();assertTrue(status(),status().contains("SKETCH_REVISION_CONFLICT"));assertTrue(view("Create layers").isEnabled());
        click("Create layers");owner.getClass().getMethod("close").invoke(owner);finishWork();assertEquals(1,callbacks.get());assertEquals(requests.get(0).toString(),requests.get(1).toString());
    }
    @Test public void invalidSourceShowsDedicatedLayerRequirementAndDoesNotDecode()throws Exception {
        setup();sourceFailure="Source layer must be dedicated, visible and unlocked";finishWork();
        assertFalse(view("Create layers").isEnabled());assertTrue(status(),status().contains(sourceFailure));assertTrue(decoded.isEmpty());
        assertTrue(((TextView)view("Reference parts requirements")).getText().toString().contains("Hidden reference images are supported."));
    }
    @Test public void undoClearAndSourceSwitchRetainIndependentDraftsAndDiscardStaleDecode()throws Exception {
        setup();finishWork();point(.1,.1);point(.8,.1);click("Undo point");assertFalse(view("Accept ring").isEnabled());click("Clear ring");
        accept("First image part",.1,.1,.3);
        ((Spinner)view("Owned reference image")).setSelection(1);idle();
        ((Spinner)view("Owned reference image")).setSelection(0);idle();
        finishWork();assertTrue("Stale source bitmap released",decoded.get(1).isRecycled());finishWork();
        assertTrue(((Spinner)view("Accepted parts")).getSelectedItem().toString().contains("First image part"));
        preview();click("Create layers");finishWork();assertEquals("reference_one",requests.get(0).getString("sourceObjectId"));
    }
    @Test public void pointRingAndPartLimitsAreVisibleAndCannotBeExceeded()throws Exception {
        setup();finishWork();
        View v=canvas();tap(v,100,20);assertFalse(view("Accept ring").isEnabled());
        for(int i=0;i<64;i++){double a=2*Math.PI*i/64;point(.5+.4*Math.cos(a),.5+.4*Math.sin(a));}
        point(.5,.5);assertTrue(status(),status().contains("64"));click("Accept ring");
        for(int i=1;i<8;i++){click("New hole");square(.1+i*.02,.1+i*.02,.01);click("Accept ring");}
        assertFalse(view("New hole").isEnabled());
        for(int i=1;i<8;i++){click("New part");accept("Part "+(i+1),.1,.1,.2);}
        assertFalse(view("New part").isEnabled());
    }
    @Test public void closedDialogReleasesDecodedBitmapAndNeverPublishesAnUncommittedDraft()throws Exception {
        setup();finishWork();accept("Draft",.1,.1,.4);owner.getClass().getMethod("close").invoke(owner);
        assertTrue(decoded.get(0).isRecycled());assertEquals(0,requests.size());assertEquals(0,callbacks.get());
    }
    @Test public void previewRequiresCurrentSourceAndInvalidatesAfterNamesOptionsOrGeometryChange()throws Exception {
        setup();finishWork();accept("Face",.1,.1,.4);preview();assertTrue(view("Create layers").isEnabled());
        assertEquals(View.VISIBLE,view("Native parts preview").getVisibility());
        text("Part name","Face revised");assertFalse(view("Create layers").isEnabled());assertEquals(View.GONE,view("Native parts preview").getVisibility());
        ((CheckBox)view("Snap to nearby edges")).setChecked(true);((SeekBar)view("Edge snap radius")).setProgress(6);preview();
        assertEquals(6,previews.get(1).getInt("edgeSnapRadius"));assertTrue(view("Create layers").isEnabled());
        ((CheckBox)view("Keep unselected pixels as background")).setChecked(true);assertFalse(view("Create layers").isEnabled());
        wrongPreviewSource=true;preview();assertFalse(view("Create layers").isEnabled());assertTrue(status(),status().contains("source"));
        wrongPreviewSource=false;preview();point(.7,.8);assertFalse(view("Create layers").isEnabled());
        click("Clear ring");assertFalse("Clearing does not accept the earlier preview",view("Create layers").isEnabled());
    }
    @Test public void zoomPanAndVertexDragUseOriginalSourceCoordinates()throws Exception {
        setup();finishWork();click("Zoom in");((CheckBox)view("Pan image")).setChecked(true);drag(canvas(),160,160,192,160);
        ((CheckBox)view("Pan image")).setChecked(false);tap(canvas(),160,160);tap(canvas(),224,160);tap(canvas(),224,224);
        drag(canvas(),160,160,176,176);click("Accept ring");preview();
        JSONArray first=previews.get(0).getJSONArray("masks").getJSONObject(0).getJSONArray("rings").getJSONArray(0).getJSONArray(0);
        assertEquals(.475,first.getDouble(0),.0001);assertEquals(.55,first.getDouble(1),.0001);
        assertEquals(3,previews.get(0).getJSONArray("masks").getJSONObject(0).getJSONArray("rings").getJSONArray(0).length());
    }
    @Test public void reopeningRetainsSourceBoundDraftButNeverRestoresPreviewAcceptance()throws Exception {
        setup();finishWork();accept("Reusable face",.1,.1,.4);preview();assertTrue(view("Create layers").isEnabled());
        owner.getClass().getMethod("close").invoke(owner);owner=null;setup();finishWork();
        assertTrue(((Spinner)view("Accepted parts")).getSelectedItem().toString().contains("Reusable face"));
        assertFalse("A reopened editor needs a fresh native preview",view("Create layers").isEnabled());
        assertTrue(view("Preview parts").isEnabled());
        owner.getClass().getMethod("close").invoke(owner);owner=null;setup(2);finishWork();
        assertFalse("Old source revisions never silently rebase draft geometry",view("Preview parts").isEnabled());
        assertTrue(status(),status().contains("older source"));
    }
    private void reopen(int revision,int frame)throws Exception {
        owner.getClass().getMethod("close").invoke(owner);owner=null;activity.finish();activity=null;setup(revision,frame);finishWork();
    }
    @Test public void editingFrameFourKeepsFrameThreeGeometryAndItsOwnPendingPoints()throws Exception {
        setup(1,3);finishWork();accept("Frame three face",.1,.1,.3);preview();JSONObject original=new JSONObject(previews.get(0).toString());
        reopen(1,4);text("Part name","Frame four hand");point(.6,.6);
        reopen(1,3);assertTrue(((Spinner)view("Accepted parts")).getSelectedItem().toString().contains("Frame three face"));
        assertFalse("Preview acceptance is not retained",view("Create layers").isEnabled());preview();
        JSONObject recovered=previews.get(previews.size()-1);assertEquals(3,recovered.getInt("frame"));
        assertEquals(SketchDocument.canonical(original.getJSONArray("masks").getJSONObject(0)),SketchDocument.canonical(recovered.getJSONArray("masks").getJSONObject(0)));
        assertEquals(original.getString("operationId"),recovered.getString("operationId"));
        reopen(1,4);assertEquals("Frame four hand",((EditText)view("Part name")).getText().toString());assertTrue(view("Undo point").isEnabled());
        point(.8,.65);point(.75,.85);click("Accept ring");preview();
        JSONArray first=previews.get(previews.size()-1).getJSONArray("masks").getJSONObject(0).getJSONArray("rings").getJSONArray(0).getJSONArray(0);
        assertEquals(.6,first.getDouble(0),.0001);assertEquals(.6,first.getDouble(1),.0001);
    }
    @Test public void editingNewRevisionKeepsBothRevisionDraftsRecoverable()throws Exception {
        setup(1,3);finishWork();accept("Revision one",.1,.1,.2);preview();JSONObject original=previews.get(0);
        reopen(2,3);accept("Revision two",.6,.6,.2);preview();
        reopen(1,3);assertEquals("Revision one",((EditText)view("Part name")).getText().toString());preview();
        JSONObject recovered=previews.get(previews.size()-1);assertEquals(1,recovered.getLong("expectedRevision"));assertEquals(original.getString("operationId"),recovered.getString("operationId"));
        reopen(2,3);assertEquals("Revision two",((EditText)view("Part name")).getText().toString());assertFalse(view("Create layers").isEnabled());
    }
    private SharedPreferences drafts(){return activity.getSharedPreferences("sketch_reference_layer_drafts",Context.MODE_PRIVATE);}
    private String savedDraft()throws Exception {
        for(Object value:drafts().getAll().values())if(value instanceof String&&new JSONObject((String)value).has("binding"))return (String)value;
        throw new AssertionError("Expected a saved source-bound draft");
    }
    private static String boundKey(JSONObject binding)throws Exception {return "v2/"+SketchDocument.sha256(SketchDocument.canonical(binding).getBytes(StandardCharsets.UTF_8));}
    @Test public void legacyDraftForAnotherFrameMigratesBeforeFreshEditsWithoutLoss()throws Exception {
        setup(1,3);finishWork();accept("Legacy frame three",.1,.1,.3);String legacy=savedDraft();
        owner.getClass().getMethod("close").invoke(owner);owner=null;
        drafts().edit().clear().putString("mask_dialog/reference_one",legacy).apply();activity.finish();activity=null;setup(1,4);finishWork();
        text("Part name","New frame four");point(.7,.7);
        assertEquals(legacy,drafts().getString(boundKey(new JSONObject(legacy).getJSONObject("binding")),null));
        assertFalse(drafts().contains("mask_dialog/reference_one"));
        reopen(1,3);assertEquals("Legacy frame three",((EditText)view("Part name")).getText().toString());assertTrue(view("Preview parts").isEnabled());assertFalse(view("Create layers").isEnabled());
    }
    @Test public void legacyMigrationNeverReplacesAnExistingDifferentBoundDraft()throws Exception {
        setup();finishWork();accept("Current bound draft",.1,.1,.3);String current=savedDraft();JSONObject legacy=new JSONObject(current);
        legacy.getJSONArray("masks").getJSONObject(0).put("name","Older legacy copy");String old=legacy.toString(),key=boundKey(legacy.getJSONObject("binding"));
        owner.getClass().getMethod("close").invoke(owner);owner=null;drafts().edit().clear().putString(key,current).putString("mask_dialog/reference_one",old).apply();activity.finish();activity=null;
        setup(1,4);finishWork();text("Part name","Other frame");point(.7,.7);
        assertEquals(current,drafts().getString(key,null));assertEquals(old,drafts().getString("mask_dialog/reference_one",null));
        reopen(1,3);assertEquals("Current bound draft",((EditText)view("Part name")).getText().toString());
    }
    @Test public void boundedRetentionRefusesNewBindingsWithoutEvictingSavedGeometry()throws Exception {
        setup();finishWork();accept("Retained geometry",.1,.1,.3);String saved=savedDraft();owner.getClass().getMethod("close").invoke(owner);owner=null;
        SharedPreferences.Editor edit=drafts().edit().clear();Map<String,String> retained=new LinkedHashMap<>();
        for(int i=1;i<=32;i++){JSONObject record=new JSONObject(saved);record.getJSONObject("binding").put("sourceRevision",i);String key=boundKey(record.getJSONObject("binding")),value=record.toString();retained.put(key,value);edit.putString(key,value);}edit.apply();
        activity.finish();activity=null;setup(99,4);finishWork();text("Part name","Unsaved new binding");
        assertTrue(status(),status().contains("storage full"));assertEquals(32,drafts().getAll().size());
        for(Map.Entry<String,String> e:retained.entrySet())assertEquals(e.getValue(),drafts().getString(e.getKey(),null));
    }
    @Test public void malformedLegacyDraftIsPreservedWhenAnotherBindingIsEdited()throws Exception {
        setup();finishWork();owner.getClass().getMethod("close").invoke(owner);owner=null;
        String legacy="{unfinished legacy data";drafts().edit().putString("mask_dialog/reference_one",legacy).apply();activity.finish();activity=null;
        setup(1,4);finishWork();text("Part name","Separate fresh draft");point(.7,.7);
        assertEquals(legacy,drafts().getString("mask_dialog/reference_one",null));assertEquals(2,drafts().getAll().size());
    }
    @Test public void byteRetentionLimitAlsoPreservesExistingValuesWithoutNewWrites()throws Exception {
        setup();finishWork();owner.getClass().getMethod("close").invoke(owner);owner=null;
        SharedPreferences.Editor edit=drafts().edit().clear();String large=String.join("",Collections.nCopies(500*1024,"x"));
        for(int i=0;i<17;i++)edit.putString("retained_"+i,large);edit.apply();activity.finish();activity=null;
        setup(99,4);finishWork();text("Part name","New binding above byte budget");
        assertTrue(status(),status().contains("storage full"));assertEquals(17,drafts().getAll().size());assertEquals(large,drafts().getString("retained_0",null));
    }
    @Test public void actualOwnedSourceLoaderAndNativePreviewApplyUseParentCore()throws Exception {
        setup();owner.getClass().getMethod("close").invoke(owner);owner=null;
        Bitmap source=Bitmap.createBitmap(256,128,Bitmap.Config.ARGB_8888);source.eraseColor(0x805588cc);
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.PNG,100,encoded);source.recycle();
        String hash=SketchDocument.sha256(encoded.toByteArray());File root=new File(activity.getFilesDir(),"sketch_studio/mask_dialog/references");assertTrue(root.mkdirs()||root.isDirectory());
        try(FileOutputStream out=new FileOutputStream(new File(root,hash+".png"))){out.write(encoded.toByteArray());}
        JSONObject image=source("reference_one");image.getJSONObject("reference").put("file",hash+".png").put("sha256",hash).put("sourceSha256",hash).put("width",256).put("height",128).put("sourceWidth",256).put("sourceHeight",128);
        SketchEditOps.execute(activity,"sketch_create",new JSONObject().put("sketchId","mask_dialog").put("operationId","owned_fixture").put("width",320).put("height",320).put("frameCount",24));
        SketchEditOps.execute(activity,"sketch_edit",new JSONObject().put("sketchId","mask_dialog").put("operationId","fixture_image").put("expectedRevision",1)
            .put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",image))));
        try(SketchProjectStore store=new SketchProjectStore(activity)){document=store.get("mask_dialog");}
        Method show=dialogType().getDeclaredMethod("show",Activity.class,JSONObject.class,int.class,Consumer.class);show.setAccessible(true);
        owner=show.invoke(null,activity,document,3,(Consumer<JSONObject>)r->callbacks.incrementAndGet());idle();
        awaitUi(()->view("Reference polygon editor").isEnabled());accept("Native face",.1,.1,.4);
        click("Preview parts");awaitUi(()->view("Create layers").isEnabled());
        assertEquals(View.VISIBLE,view("Native parts preview").getVisibility());
        try(SketchProjectStore store=new SketchProjectStore(activity)){assertEquals(2,store.get("mask_dialog").getLong("revision"));}
        captureNativeDialog();
        click("Create layers");awaitUi(()->callbacks.get()==1);
        try(SketchProjectStore store=new SketchProjectStore(activity)){
            JSONObject actual=store.get("mask_dialog");assertEquals(3,actual.getLong("revision"));assertEquals(2,actual.getJSONArray("layers").length());
            JSONObject part=actual.getJSONArray("layers").getJSONObject(1).getJSONArray("objects").getJSONObject(0);
            assertEquals("source_assisted",part.getJSONObject("reference").getJSONObject("sourceMask").getString("method"));
            assertEquals(256,part.getJSONObject("reference").getInt("width"));assertEquals(128,part.getJSONObject("reference").getInt("height"));
            Bitmap pixels=android.graphics.BitmapFactory.decodeFile(store.referenceFile("mask_dialog",part.getJSONObject("reference")).getPath());
            assertEquals(128,pixels.getPixel(60,30)>>>24);assertEquals(0,pixels.getPixel(230,110)>>>24);pixels.recycle();
        }
    }
    private void captureNativeDialog()throws Exception {
        String output=System.getProperty("reference.layers.ui.images");if(output==null)return;
        File directory=new File(output);assertTrue(directory.mkdirs()||directory.isDirectory());
        Field field=owner.getClass().getDeclaredField("dialog");field.setAccessible(true);AlertDialog actual=(AlertDialog)field.get(owner);
        View decor=actual.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY));decor.layout(0,0,720,1280);idle();
        ScrollView scroll=(ScrollView)root().getParent();scroll.scrollTo(0,0);idle();writeNativeView(new File(directory,"native-dialog-guided-tools.png"),decor);
        scroll.fullScroll(View.FOCUS_DOWN);idle();writeNativeView(new File(directory,"native-dialog-selection-preview.png"),decor);
        JSONObject label=new JSONObject().put("execution","Robolectric native graphics / Android Canvas")
            .put("surface","Actual SketchReferenceLayersDialog AlertDialog decor; top and bottom scroll positions")
            .put("source","App-private native-generated 256x128 RGBA test fixture")
            .put("preview","Actual parent sketch_reference_layers_preview result; source revision still 2 before import")
            .put("width",720).put("height",1280).put("deviceScreenshot",false).put("externalDrawing",false);
        try(FileOutputStream out=new FileOutputStream(new File(directory,"Evidence.json"))){out.write(label.toString(2).getBytes(StandardCharsets.UTF_8));}
    }
    private static void writeNativeView(File output,View actual)throws Exception {
        Bitmap pixels=Bitmap.createBitmap(actual.getWidth(),actual.getHeight(),Bitmap.Config.ARGB_8888);
        try{actual.draw(new Canvas(pixels));try(FileOutputStream out=new FileOutputStream(output)){assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,out));}}
        finally{pixels.recycle();}
    }
    private interface Condition {boolean ready()throws Exception;}
    private void awaitUi(Condition condition)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<end){idle();if(condition.ready())return;Thread.sleep(10);}
        fail("Native UI did not become ready: "+status());
    }
    private static void drag(View v,float x,float y,float endX,float endY){
        for(int i=0;i<3;i++){MotionEvent e=MotionEvent.obtain(1,2+i,i==0?MotionEvent.ACTION_DOWN:i==1?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_UP,i==0?x:endX,i==0?y:endY,0);v.onTouchEvent(e);e.recycle();}idle();
    }
    private static final class QueueWorker extends AbstractExecutorService {
        private final Queue<Runnable> jobs=new ArrayDeque<>();private boolean stopped;
        public void execute(Runnable r){if(stopped)throw new RejectedExecutionException();jobs.add(r);}
        public void shutdown(){stopped=true;}public List<Runnable> shutdownNow(){stopped=true;List<Runnable> rest=new ArrayList<>(jobs);jobs.clear();return rest;}
        public boolean isShutdown(){return stopped;}public boolean isTerminated(){return stopped&&jobs.isEmpty();}public boolean awaitTermination(long t,TimeUnit u){return isTerminated();}
        void runNext()throws Exception {Runnable job=jobs.poll();assertNotNull("Expected queued native work",job);Thread t=new Thread(job,"mask-ui-test-worker");t.start();t.join(10000);assertFalse("Worker completed",t.isAlive());}
    }
}
