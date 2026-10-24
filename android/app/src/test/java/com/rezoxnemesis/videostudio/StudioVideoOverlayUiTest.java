package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Manual VIDEO metadata/drafts/transactions. Unit graph and synthetic probe data prove no codec/GPU output. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,qualifiers="w480dp-h853dp-port-mdpi",shadows={StudioEditorPreviewGraphFixture.BuilderShadow.class,StudioVideoOverlayUiTest.ProbeShadow.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class StudioVideoOverlayUiTest {
    @Implements(MediaMetadataRetriever.class)
    public static class ProbeShadow extends ShadowMediaMetadataRetriever {
        static volatile boolean queriedOnUiThread;
        @Implementation @Override protected String extractMetadata(int key){
            if(Looper.myLooper()==Looper.getMainLooper())queriedOnUiThread=true;
            return super.extractMetadata(key);
        }
    }

    @Test public void mediaOffersOnlyEligibleOwnedVideoAndCancelDoesNotWriteOrReplaceItsSource()throws Exception{
        try(Fixture fixture=new Fixture()){
            String before=canonical(fixture.store.get(fixture.project.id));AlertDialog editor=openAdd(fixture);
            assertNotNull(textContaining(editor.getWindow().getDecorView(),"Silent video overlay"));
            assertNotNull(input(editor,"Video source in (seconds)"));assertNotNull(input(editor,"Video opacity"));
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();ShadowLooper.idleMainLooper();assertFalse(editor.isShowing());
            assertEquals(before,canonical(fixture.store.get(fixture.project.id)));assertFalse(ProbeShadow.queriedOnUiThread);
        }
    }

    @Test public void fitUsesVerifiedRotatedAspectAndOneSaveKeepsIndependentSourceAndOutputClocks()throws Exception{
        try(Fixture fixture=new Fixture()){
            String assets=canonical(fixture.project.toJson().getJSONArray("assets")),base=canonical(fixture.project.toJson().getJSONArray("clips"));AlertDialog editor=openAdd(fixture);
            input(editor,"Video start (seconds)").setText("1.750");input(editor,"Video duration (seconds)").setText("2.000");input(editor,"Video source in (seconds)").setText("1.500");
            input(editor,"Video width").setText("0.5");input(editor,"Video height").setText("1");button(editor.getWindow().getDecorView(),"Fit source proportions").performClick();
            assertEquals(.5,value(editor,"Video width"),.000001);assertEquals("Portrait input must not be stretched into a half-width/full-height box",.5,value(editor,"Video height"),.000001);
            input(editor,"Video rotation").setText("12");input(editor,"Video opacity").setText("0.75");applyAndAwait(editor);ShadowLooper.idleMainLooper();fixture.layout();assertFalse(editor.isShowing());
            ProjectStore.Project saved=fixture.store.get(fixture.project.id);StudioLayer video=onlyVideo(saved);JSONObject wire=video.toJson();
            assertEquals(fixture.video.id,video.assetId);assertEquals("silent",wire.getString("audioMode"));assertEquals(1750,video.startMs);assertEquals(2000,video.durationMs);assertEquals(1500,video.sourceInMs);assertEquals(12,video.rotation,0);assertEquals(.75,video.opacity,0);
            assertFalse(wire.has("gain"));assertFalse(wire.has("text"));assertEquals(8000,saved.outputDurationMs());assertEquals(assets,canonical(saved.toJson().getJSONArray("assets")));assertEquals(base,canonical(saved.toJson().getJSONArray("clips")));assertFalse(ProbeShadow.queriedOnUiThread);
            pane(fixture,"Timeline");click(fixture,"↶ Undo");assertEquals(0,videoCount(fixture.store.get(fixture.project.id)));click(fixture,"↷ Redo");assertEquals(1,videoCount(fixture.store.get(fixture.project.id)));
        }
    }

    @Test public void videoInspectorMotionAndOrdinaryPlacementShareOneRecipeWithoutChangingSourceTrim()throws Exception{
        try(Fixture fixture=new Fixture()){
            addDefault(fixture);selectVideo(fixture);AlertDialog motion=animateVideo(fixture);View form=motion.getWindow().getDecorView();
            putKey(form,0,0,1,0,1);putKey(form,1000,.8,2,90,0);
            input(form,"Video key time (ms)").setText("500");button(form,"Load time").performClick();assertEquals(.4,number(form,"Video motion x"),.000001);assertEquals(1.5,number(form,"Video motion scaleX"),.000001);assertEquals(45,number(form,"Video motion rotate"),.000001);assertEquals(.5,number(form,"Video motion opacity"),.000001);
            applyAndAwait(motion);ShadowLooper.idleMainLooper();fixture.layout();assertFalse(motion.isShowing());StudioLayer first=onlyVideo(fixture.store.get(fixture.project.id));String keys=first.toJson().getJSONArray("motionKeyframes").toString();long sourceIn=first.sourceInMs;
            selectVideo(fixture);click(fixture,"Placement / timing");AlertDialog editor=ShadowAlertDialog.getLatestAlertDialog();assertEquals("Video Overlay · "+fixture.video.name,org.robolectric.Shadows.shadowOf(editor).getTitle().toString());awaitProbe(editor);
            input(editor,"Video center X").setText("0.314159");applyAndAwait(editor);ShadowLooper.idleMainLooper();fixture.layout();StudioLayer edited=onlyVideo(fixture.store.get(fixture.project.id));assertEquals(.314159,edited.x,.000001);assertEquals(sourceIn,edited.sourceInMs);assertEquals(keys,edited.toJson().getJSONArray("motionKeyframes").toString());assertEquals(fixture.video.uri,fixture.store.get(fixture.project.id).asset(edited.assetId).uri);
        }
    }

    @Test public void projectRevisionAndNavigationChangesCannotReceiveAnOldVideoPlacementDraft()throws Exception{
        for(String change:new String[]{"project","storedRevision","navigation"}){
            try(Fixture fixture=new Fixture()){
                AlertDialog editor=openAdd(fixture);input(editor,"Video center X").setText("0.7");String first=canonical(fixture.store.get(fixture.project.id));ProjectStore.Project other=null;String second=null;
                if("project".equals(change)){other=fixture.store.create("Different video target");second=canonical(fixture.store.get(other.id));other.updatedAt=fixture.project.updatedAt;set(fixture.activity,"activeProject",other);}
                if("storedRevision".equals(change)){ProjectStore.Project newer=fixture.store.get(fixture.project.id);newer.name="Keep the newer edit";fixture.store.save(newer);first=canonical(fixture.store.get(newer.id));}
                if("navigation".equals(change)){invoke(fixture.activity,"setScreen",new Class<?>[]{View.class,String.class},new FrameLayout(fixture.activity),"home");invoke(fixture.activity,"setScreen",new Class<?>[]{View.class,String.class},new FrameLayout(fixture.activity),"editor");}
                applyAndAwait(editor);assertTrue("A rejected stale draft remains reviewable: "+change,editor.isShowing());assertEquals(first,canonical(fixture.store.get(fixture.project.id)));if(other!=null)assertEquals(second,canonical(fixture.store.get(other.id)));
            }
        }
    }

    @Test public void twoVideoLimitIsVisibleWithoutRemovingAccessToExistingVideoEdits()throws Exception{
        try(Fixture fixture=new Fixture()){
            addDefault(fixture);addDefault(fixture);assertEquals(2,videoCount(fixture.store.get(fixture.project.id)));pane(fixture,"Media");Button add=button(fixture.root,"+ Video Overlay");assertNotNull(add);assertFalse("Manual add must respect the native two-video decoder bound",add.isEnabled());
            String before=canonical(fixture.store.get(fixture.project.id));selectVideo(fixture);assertNotNull(button(fixture.root,"Animate video"));assertNotNull(button(fixture.root,"Placement / timing"));assertEquals(before,canonical(fixture.store.get(fixture.project.id)));
        }
    }

    private static AlertDialog openAdd(Fixture fixture)throws Exception{
        pane(fixture,"Media");Button add=button(fixture.root,"+ Video Overlay");assertNotNull("Media must expose + Video Overlay",add);assertTrue(add.isEnabled());Rect bounds=new Rect(0,0,add.getWidth(),add.getHeight());fixture.root.offsetDescendantRectToMyCoords(add,bounds);assertTrue("Video add must be reachable in the native workspace",new Rect(0,0,480,853).contains(bounds));assertTrue(add.getHeight()>=StudioUi.dp(fixture.activity,48));add.performClick();ShadowLooper.idleMainLooper();
        AlertDialog chooser=ShadowAlertDialog.getLatestAlertDialog();assertEquals("Choose an imported overlay video",org.robolectric.Shadows.shadowOf(chooser).getTitle().toString());ListView sources=chooser.getListView();assertNotNull(sources);assertEquals("Images, unseekable videos and unknown-duration videos are not eligible",1,sources.getCount());assertTrue(String.valueOf(sources.getAdapter().getItem(0)).startsWith(fixture.video.name));sources.performItemClick(sources.getAdapter().getView(0,null,sources),0,0);ShadowLooper.idleMainLooper();
        AlertDialog editor=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(chooser,editor);assertEquals("Video Overlay · "+fixture.video.name,org.robolectric.Shadows.shadowOf(editor).getTitle().toString());awaitProbe(editor);return editor;
    }
    private static void addDefault(Fixture fixture)throws Exception{AlertDialog editor=openAdd(fixture);applyAndAwait(editor);ShadowLooper.idleMainLooper();fixture.layout();assertFalse(editor.isShowing());}
    private static void selectVideo(Fixture fixture)throws Exception{
        pane(fixture,"Timeline");click(fixture,"Targets");AlertDialog chooser=ShadowAlertDialog.getLatestAlertDialog();ListView list=chooser.getListView();boolean selected=false;for(int i=0;i<list.getCount();i++)if(String.valueOf(list.getAdapter().getItem(i)).startsWith("Video overlay · "+fixture.video.name)){list.performItemClick(list.getAdapter().getView(i,null,list),i,i);selected=true;break;}assertTrue("Targets must identify VIDEO as Video overlay rather than Image/Title",selected);ShadowLooper.idleMainLooper();fixture.layout();assertNotNull(text(fixture.root,"Video overlay"));
    }
    private static AlertDialog animateVideo(Fixture fixture)throws Exception{click(fixture,"Animate video");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertEquals("Animate video overlay",org.robolectric.Shadows.shadowOf(dialog).getTitle().toString());awaitEnabled(dialog.getButton(AlertDialog.BUTTON_POSITIVE));return dialog;}
    private static void applyAndAwait(AlertDialog dialog)throws Exception{Button apply=dialog.getButton(AlertDialog.BUTTON_POSITIVE);assertTrue("Worker source verification must be ready before Apply",apply.isEnabled());apply.performClick();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);do{ShadowLooper.idleMainLooper();if(!dialog.isShowing()||apply.isEnabled())return;Thread.sleep(5);}while(System.nanoTime()<end);fail("Worker Apply did not complete or reject within its bounded wait");}
    private static void awaitEnabled(Button button)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);do{ShadowLooper.idleMainLooper();if(button.isEnabled())return;Thread.sleep(5);}while(System.nanoTime()<end);fail("Owned video source check did not finish within its bounded wait");}
    private static void awaitProbe(AlertDialog dialog)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);Button fit;do{ShadowLooper.idleMainLooper();fit=button(dialog.getWindow().getDecorView(),"Fit source proportions");if(fit!=null&&fit.isEnabled())return;Thread.sleep(5);}while(System.nanoTime()<end);fail("Worker source-aspect probe did not enable the proportion preset");}
    private static void putKey(View form,long at,double x,double scale,double rotation,double opacity){input(form,"Video key time (ms)").setText(Long.toString(at));String[] names={"x","y","scaleX","scaleY","rotate","opacity"};double[] values={x,0,scale,scale,rotation,opacity};for(int i=0;i<names.length;i++)input(form,"Video motion "+names[i]).setText(Double.toString(values[i]));Spinner easing=(Spinner)description(form,"Video motion easing");assertNotNull(easing);easing.setSelection(0);button(form,"Add / update key").performClick();assertNotNull(text(form,"Key at "+at+" ms"));}
    private static void pane(Fixture fixture,String name)throws Exception{click(fixture,name);}
    private static void click(Fixture fixture,String label)throws Exception{Button button=button(fixture.root,label);assertNotNull("Missing native control: "+label,button);button.performClick();ShadowLooper.idleMainLooper();fixture.layout();}
    private static double value(AlertDialog dialog,String name){return Double.parseDouble(input(dialog,name).getText().toString());}
    private static double number(View form,String name){return Double.parseDouble(input(form,name).getText().toString());}
    private static int videoCount(ProjectStore.Project project){int count=0;for(StudioLayer layer:project.studioLayers)if("video".equals(layer.type))count++;return count;}
    private static StudioLayer onlyVideo(ProjectStore.Project project){StudioLayer found=null;for(StudioLayer layer:project.studioLayers)if("video".equals(layer.type)){assertNull(found);found=layer;}assertNotNull(found);return found;}
    private static String canonical(Object value)throws Exception{return SketchDocument.canonical(value instanceof ProjectStore.Project?((ProjectStore.Project)value).toJson():value);}

    private static final class Fixture implements AutoCloseable {
        final MainActivity activity;final ProjectStore store;final ProjectStore.Project project;final ProjectStore.Asset video;final ViewGroup root;final File source,videoSource;
        Fixture()throws Exception{
            ProbeShadow.queriedOnUiThread=false;Context context=RuntimeEnvironment.getApplication();store=new ProjectStore(context);project=store.create("Video overlay manual metadata fixture");File directory=new File(context.getFilesDir(),"video_overlay_ui_fixtures");assertTrue(directory.isDirectory()||directory.mkdirs());source=new File(directory,UUID.randomUUID()+".png");Bitmap bitmap=Bitmap.createBitmap(32,64,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff526e60);try(FileOutputStream out=new FileOutputStream(source)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
            ProjectStore.Asset base=asset("Base technical image", "image/png",Uri.fromFile(source).toString(),0,true);project.assets.add(base);ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="base_"+UUID.randomUUID();clip.assetId=base.id;clip.outMs=8000;clip.volume=.83f;project.clips.add(clip);
            // Synthetic registered video metadata is not a decodable video or a rendered test asset.
            videoSource=new File(directory,UUID.randomUUID()+".mp4");try(FileOutputStream output=new FileOutputStream(videoSource)){output.write("UNIT_ONLY_SYNTHETIC_VIDEO_METADATA".getBytes(java.nio.charset.StandardCharsets.UTF_8));}video=asset("Owned portrait source","video/mp4",Uri.fromFile(videoSource).toString(),6000,true);video.sizeBytes=videoSource.length();project.assets.add(video);project.assets.add(asset("Unseekable source","video/mp4","file:///not-decoded-unseekable.mp4",6000,false));project.assets.add(asset("Unknown source duration","video/mp4","file:///not-decoded-unknown.mp4",0,true));
            DataSource data=DataSource.toDataSource(context,Uri.parse(video.uri));ShadowMediaMetadataRetriever.addMetadata(data,MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,"960");ShadowMediaMetadataRetriever.addMetadata(data,MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,"540");ShadowMediaMetadataRetriever.addMetadata(data,MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,"90");ShadowMediaMetadataRetriever.addMetadata(data,MediaMetadataRetriever.METADATA_KEY_DURATION,"6000");
            StudioLayer title=StudioLayer.fromJson(new JSONObject().put("id","keep_title_"+UUID.randomUUID()).put("type","title").put("text","Keep this caption").put("durationMs",1000));project.studioLayers.add(title);store.save(project);
            activity=Robolectric.buildActivity(MainActivity.class).get();set(activity,"store",store);set(activity,"prefs",context.getSharedPreferences("video-overlay-ui",0));set(activity,"activeProject",project);set(activity,"selectedClip",clip);set(activity,"renderEngine",new NativeRenderEngine(activity));set(activity,"studioWorkspacePane","Media");set(activity,"studioInspectorProjectId",project.id);root=(ViewGroup)invoke(activity,"buildShell",new Class<?>[]{});activity.setContentView(root);invoke(activity,"showEditor",new Class<?>[]{});ShadowLooper.idleMainLooper();layout();
        }
        private static ProjectStore.Asset asset(String name,String mime,String uri,long duration,boolean seekable){ProjectStore.Asset value=new ProjectStore.Asset();value.id="asset_"+UUID.randomUUID();value.name=name;value.mime=mime;value.uri=uri;value.durationMs=duration;value.seekable=seekable;return value;}
        void layout(){root.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(853,View.MeasureSpec.EXACTLY));root.layout(0,0,480,853);}
        @Override public void close()throws Exception{AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();if(dialog!=null)dialog.dismiss();invoke(activity,"onDestroy",new Class<?>[]{});Field db=ProjectStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(store)).close();assertTrue(source.delete());assertTrue(videoSource.delete());}
    }
    private static Object invoke(Object object,String name,Class<?>[] types,Object... values)throws Exception{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,values);}
    private static void set(Object object,String name,Object value)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static EditText input(AlertDialog dialog,String value){return input(dialog.getWindow().getDecorView(),value);}
    private static EditText input(View root,String value){View found=description(root,value);assertTrue("Missing native video input: "+value,found instanceof EditText);return (EditText)found;}
    private static View description(View root,String value){if(value.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=description(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static TextView text(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=text(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static TextView textContaining(View root,String value){if(root instanceof TextView&&((TextView)root).getText().toString().contains(value))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=textContaining(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static Button button(View root,String value){TextView found=text(root,value);return found instanceof Button?(Button)found:null;}
}
