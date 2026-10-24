package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.UUID;
import static org.junit.Assert.*;

/** Separate baseline cases; frozen seven layout assertions are not modified. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE,shadows=StudioEditorPreviewGraphFixture.BuilderShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class StudioEditorFollowupUiTest {
    @Test public void manuallyAuthoredStillCanLastFourSecondsWithoutAPlaybackSpeedWorkaround()throws Exception{
        try(Fixture fixture=new Fixture(true)){
            byte[] original=Files.readAllBytes(fixture.source.toPath());invoke(fixture.activity,"trimDialog");
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ShadowLooper.idleMainLooper();ArrayList<EditText> inputs=new ArrayList<>();inputs(dialog.getWindow().getDecorView(),inputs);assertEquals(2,inputs.size());
            inputs.get(0).setText("0.000");inputs.get(1).setText("4.000");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ProjectStore.Project saved=fixture.store.get(fixture.project.id);
            assertEquals("A still's authored duration is not bounded by its default3second import",4000,saved.clips.get(0).outMs);
            assertEquals(1,saved.clips.get(0).speed,0);assertEquals(4000,saved.outputDurationMs());assertEquals(1,saved.assets.size());assertEquals(fixture.project.assets.get(0).id,saved.clips.get(0).assetId);
            assertArrayEquals("Duration edits must not alter source PNG bytes",original,Files.readAllBytes(fixture.source.toPath()));
            assertFalse("A successful manual duration edit closes its form",dialog.isShowing());
        }
    }

    @Test public void videoTrimStillRejectsAnOutPointPastItsDeclaredSourceDuration()throws Exception{
        try(Fixture fixture=new Fixture(false)){
            long revision=fixture.project.updatedAt;invoke(fixture.activity,"trimDialog");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ShadowLooper.idleMainLooper();ArrayList<EditText> inputs=new ArrayList<>();inputs(dialog.getWindow().getDecorView(),inputs);
            inputs.get(1).setText("4.000");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ProjectStore.Project saved=fixture.store.get(fixture.project.id);
            assertEquals(revision,saved.updatedAt);assertEquals(3000,saved.clips.get(0).outMs);assertEquals(1,saved.clips.get(0).speed,0);assertTrue("An invalid video trim remains reviewable",dialog.isShowing());dialog.dismiss();
        }
    }

    @Test public void unchangedPausedPreviewDoesNotRepublishTimeTextButAPausedSeekStillUpdatesIt()throws Exception{
        try(Fixture fixture=new Fixture(true)){
            invoke(fixture.activity,"showEditor");ShadowLooper.idleMainLooper();LiveEditPlayer live=(LiveEditPlayer)field(fixture.activity,"livePlayer");
            assertTrue(live.isEditedPreview());assertFalse(live.snapshotState().playWhenReady);
            TextView label=(TextView)field(fixture.activity,"studioTimeLabel");int[] edits={0};label.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(Editable e){edits[0]++;}});
            Runnable clock=(Runnable)field(fixture.activity,"studioCaptionClock");assertNotNull(clock);clock.run();clock.run();
            assertEquals("A paused unchanged clock must not repeatedly publish identical text/accessibility updates",0,edits[0]);
            live.playEdit(fixture.project,new NativeRenderEngine(fixture.activity),"9:16","720p",500,false,null,error->fail(error));ShadowLooper.idleMainLooper();clock.run();
            assertEquals("A real paused seek still updates the visible edited time",500,(long)field(fixture.activity,"studioPlayheadMs"));
            assertTrue(label.getText().toString().contains(StudioEditCursor.timecode(500)));assertEquals(1,edits[0]);clock.run();assertEquals(1,edits[0]);
            assertFalse(live.snapshotState().playWhenReady);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();final ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());final ProjectStore.Project project;final File source;
        Fixture(boolean image)throws Exception{
            Context context=RuntimeEnvironment.getApplication();project=store.create("Editor UI duration/paused metadata fixture");
            File folder=new File(context.getFilesDir(),"editor_followup_fixtures");assertTrue(folder.isDirectory()||folder.mkdirs());source=new File(folder,UUID.randomUUID()+".png");
            Bitmap bitmap=Bitmap.createBitmap(32,64,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff638272);try(FileOutputStream output=new FileOutputStream(source)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}
            ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="source_"+UUID.randomUUID();asset.name=image?"Own still.png":"Declared3second video boundary fixture";asset.mime=image?"image/png":"video/mp4";asset.durationMs=image?0:3000;
            // The video case tests metadata admission only; it never decodes an invented movie.
            asset.uri=image?Uri.fromFile(source).toString():"file:///not-decoded-video-boundary.mp4";project.assets.add(asset);
            ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip_"+UUID.randomUUID();clip.assetId=asset.id;clip.outMs=3000;project.clips.add(clip);store.save(project);
            set(activity,"store",store);set(activity,"prefs",context.getSharedPreferences("editor-followup-ui",0));set(activity,"activeProject",project);set(activity,"selectedClip",clip);set(activity,"content",new FrameLayout(activity));set(activity,"currentScreen","editor");set(activity,"renderEngine",new NativeRenderEngine(activity));
        }
        @Override public void close()throws Exception{AlertDialog shown=ShadowAlertDialog.getLatestAlertDialog();if(shown!=null)shown.dismiss();invoke(activity,"onDestroy");Field db=ProjectStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(store)).close();assertTrue(source.delete());}
    }
    private static void inputs(View view,ArrayList<EditText> found){if(view instanceof EditText)found.add((EditText)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)inputs(((ViewGroup)view).getChildAt(i),found);}
    private static Object field(Object object,String name)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void set(Object object,String name,Object value)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static Object invoke(Object object,String name)throws Exception{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);return method.invoke(object);}
}
