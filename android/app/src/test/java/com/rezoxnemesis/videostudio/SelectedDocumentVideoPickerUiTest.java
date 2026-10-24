package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

/** Explicit native picker/destination admission. Unit metadata and graph do not prove real video decoding. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,qualifiers="w480dp-h853dp-port-mdpi",shadows={StudioEditorPreviewGraphFixture.BuilderShadow.class,SelectedDocumentVideoImportTest.VideoMetadata.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SelectedDocumentVideoPickerUiTest {
    @Test public void missingOwnedSourcesOfferAnExplicitSingleVideoDocumentPickerInsteadOfTheLinkedMediaImporter()throws Exception{
        try(Fixture f=new Fixture()){
            String before=f.projectJson();ShadowActivity.IntentForResult choice=f.chooseFile();
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,choice.intent.getAction());assertEquals("video/*",choice.intent.getType());assertTrue(choice.intent.hasCategory(Intent.CATEGORY_OPENABLE));assertFalse(choice.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));
            assertEquals(before,f.projectJson());assertEquals(0,f.media.provider.opened);
        }
    }

    @Test public void selectedVideoBecomesOneIndependentOwnedAssetWithoutAppendingBaseOrOverlayTracks()throws Exception{
        try(Fixture f=new Fixture()){
            String clips=canonical(f.project.toJson().getJSONArray("clips")),layers=canonical(StudioLayerContract.toJson(f.project.studioLayers)),source=f.media.sourceJson();ShadowActivity.IntentForResult choice=f.chooseFile();
            f.result(choice,Activity.RESULT_OK,new Intent().setData(f.media.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);ProjectStore.Project saved;
            do{ShadowLooper.idleMainLooper();saved=f.media.store.get(f.project.id);if(saved.assets.size()==2)break;Thread.sleep(5);}while(System.nanoTime()<deadline);
            assertEquals("The explicit selected document must become one owned video asset",2,saved.assets.size());assertEquals(clips,canonical(saved.toJson().getJSONArray("clips")));assertEquals(layers,canonical(StudioLayerContract.toJson(saved.studioLayers)));assertEquals(source,f.media.sourceJson());
            ProjectStore.Asset video=null;for(ProjectStore.Asset asset:saved.assets)if(asset.mime.startsWith("video/")){assertNull(video);video=asset;}assertNotNull(video);assertFalse(video.generated);assertTrue(video.seekable);assertEquals(6000,video.durationMs);
            File owned=ProjectAssetImport.sourceFile(f.activity,video);assertArrayEquals(f.media.bytes,java.nio.file.Files.readAllBytes(owned.toPath()));assertArrayEquals(f.media.bytes,java.nio.file.Files.readAllBytes(f.media.file.toPath()));assertFalse(SelectedDocumentVideoImportTest.VideoMetadata.queriedOnMain);
        }
    }

    @Test public void cancelledOrStalePickerResultsCannotStartReadingOrWriteIntoAnotherEditorSurface()throws Exception{
        for(String variant:new String[]{"cancel","project","navigation"})try(Fixture f=new Fixture()){
            ShadowActivity.IntentForResult choice=f.chooseFile();String before=f.projectJson();ProjectStore.Project other=null;String otherBefore=null;
            if("project".equals(variant)){other=f.media.store.create("Other target");otherBefore=canonical(other.toJson());set(f.activity,"activeProject",other);}
            if("navigation".equals(variant)){invoke(f.activity,"setScreen",new Class<?>[]{View.class,String.class},new FrameLayout(f.activity),"home");invoke(f.activity,"setScreen",new Class<?>[]{View.class,String.class},new FrameLayout(f.activity),"editor");}
            ShadowToast.reset();f.result(choice,"cancel".equals(variant)?Activity.RESULT_CANCELED:Activity.RESULT_OK,new Intent().setData(f.media.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));ShadowLooper.idleMainLooper();
            if(!"cancel".equals(variant)){String toast=ShadowToast.getTextOfLatestToast();assertNotNull("Stale selection must be rejected before dispatching a copy",toast);assertTrue(toast.toLowerCase(java.util.Locale.ROOT).contains("editing view changed"));}
            assertEquals(before,f.projectJson());if(other!=null)assertEquals(otherBefore,canonical(f.media.store.get(other.id).toJson()));assertEquals("A cancelled/stale picker must reject before reading the selected provider",0,f.media.provider.opened);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final SelectedDocumentVideoImportTest.Fixture media=new SelectedDocumentVideoImportTest.Fixture();final ProjectStore.Project project=media.destination;final MainActivity activity;final ViewGroup root;final File png;
        Fixture()throws Exception{
            png=new File(media.context.getFilesDir(),"video_picker_base_"+UUID.randomUUID()+".png");Bitmap bitmap=Bitmap.createBitmap(32,64,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff526e60);try(FileOutputStream out=new FileOutputStream(png)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
            ProjectStore.Asset base=new ProjectStore.Asset();base.id="base_"+UUID.randomUUID();base.name="Technical base.png";base.mime="image/png";base.uri=Uri.fromFile(png).toString();base.seekable=true;project.assets.add(base);ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip_"+UUID.randomUUID();clip.assetId=base.id;clip.outMs=8000;project.clips.add(clip);media.store.save(project);
            activity=Robolectric.buildActivity(MainActivity.class).get();set(activity,"store",media.store);set(activity,"prefs",media.context.getSharedPreferences("selected-video-picker-ui",0));set(activity,"activeProject",project);set(activity,"selectedClip",clip);set(activity,"renderEngine",new NativeRenderEngine(activity));set(activity,"studioWorkspacePane","Media");set(activity,"studioInspectorProjectId",project.id);root=(ViewGroup)invoke(activity,"buildShell",new Class<?>[]{});activity.setContentView(root);invoke(activity,"showEditor",new Class<?>[]{});ShadowLooper.idleMainLooper();layout();
        }
        ShadowActivity.IntentForResult chooseFile()throws Exception{
            Button add=button(root,"+ Video Overlay");assertNotNull(add);assertTrue(add.isEnabled());add.performClick();ShadowLooper.idleMainLooper();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);Button choose=button(dialog.getWindow().getDecorView(),"Choose video file");assertNotNull("Missing owned VIDEO input route: Choose video file",choose);choose.performClick();ShadowLooper.idleMainLooper();ShadowActivity.IntentForResult choice=Shadows.shadowOf(activity).getNextStartedActivityForResult();assertNotNull(choice);return choice;
        }
        void result(ShadowActivity.IntentForResult choice,int code,Intent data)throws Exception{invoke(activity,"onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},choice.requestCode,code,data);}
        void layout(){root.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(853,View.MeasureSpec.EXACTLY));root.layout(0,0,480,853);}
        String projectJson()throws Exception{return canonical(media.store.get(project.id).toJson());}
        @Override public void close()throws Exception{AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();if(dialog!=null)dialog.dismiss();invoke(activity,"onDestroy",new Class<?>[]{});media.close();assertTrue(png.delete());}
    }
    private static String canonical(Object value)throws Exception{return SketchDocument.canonical(value);}
    private static void set(Object object,String name,Object value)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static Object invoke(Object object,String name,Class<?>[] signature,Object... arguments)throws Exception{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(name,signature);method.setAccessible(true);return method.invoke(object,arguments);}
    private static Button button(View root,String text){if(root instanceof Button&&text.contentEquals(((Button)root).getText()))return (Button)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Button found=button(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
}
