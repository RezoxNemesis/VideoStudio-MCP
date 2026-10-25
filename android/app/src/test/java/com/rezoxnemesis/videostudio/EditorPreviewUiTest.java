package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class EditorPreviewUiTest {
    @Test public void unknownDurationHasPersistentErrorAndReachableRetry()throws Exception{
        try(Fixture f=new Fixture(false,0)){
            TextView error=f.content.findViewWithTag("studio-preview-error");
            assertNotNull("Failure must have a persistent editor message",error);
            assertEquals(View.VISIBLE,error.getVisibility());assertTrue(error.getText().toString().contains("duration"));
            assertEquals("An empty black still placeholder must not cover the retained player",View.GONE,
                    ((View)get(f.activity,"studioStillPreview")).getVisibility());
            View retry=f.content.findViewWithTag("studio-preview-retry");assertNotNull(retry);assertEquals(View.VISIBLE,retry.getVisibility());
            invoke(f.activity,"showEditor");
            error=f.content.findViewWithTag("studio-preview-error");assertEquals(View.VISIBLE,error.getVisibility());
            f.project.assets.get(0).durationMs=10000;f.store.save(f.project);
            f.content.findViewWithTag("studio-preview-retry").performClick();
            assertEquals(View.GONE,error.getVisibility());assertTrue(f.live().isEditedPreview());
        }
    }

    @Test public void previewCommandStartsStillEditWithoutPriorExport()throws Exception{
        try(Fixture f=new Fixture(true,0)){
            assertEquals("",f.project.latestExportUri);
            assertFalse(f.live().snapshotState().playWhenReady);
            JSONObject result=f.preview();
            assertTrue("Command must start the same edited player as Play Edit",f.live().isEditedPreview());
            assertTrue("Still preview must actually request playback",f.live().snapshotState().playWhenReady);
            assertTrue(result.getBoolean("ok"));assertTrue(result.getBoolean("admitted"));
            assertFalse("Admission does not prove decoded on-screen playback",result.getBoolean("previewing"));
            assertEquals("edited_composition",result.getString("previewMode"));
        }
    }

    @Test public void previewCommandDoesNotClaimAdmissionForUnknownDuration()throws Exception{
        try(Fixture f=new Fixture(false,0)){
            JSONObject result=f.preview();
            assertFalse(result.getBoolean("ok"));assertFalse(result.getBoolean("admitted"));
            assertFalse(result.getBoolean("previewing"));assertTrue(result.getString("error").contains("duration"));
        }
    }

    @Test public void asynchronousDecoderErrorRemainsVisibleUntilSuccessfulRetry()throws Exception{
        try(Fixture f=new Fixture(false,10000)){
            LiveEditPlayerRecoveryTest.failPlayback((androidx.media3.common.Player)get(f.live(),"editPlayer"));
            TextView error=f.content.findViewWithTag("studio-preview-error");
            assertEquals(View.VISIBLE,error.getVisibility());assertTrue(error.getText().toString().contains("decoder rejected"));
            View retry=f.content.findViewWithTag("studio-preview-retry");assertEquals(View.VISIBLE,retry.getVisibility());
            retry.performClick();
            // If the fake decoder fails to admit another graph, the error must
            // remain visible rather than pretending the retry was successful.
            if(f.live().isEditedPreview()){
                assertEquals(View.GONE,error.getVisibility());
            }else{
                assertEquals(View.VISIBLE,error.getVisibility());
                assertFalse(error.getText().toString().isEmpty());
            }
        }
    }

    @Test public void decoderFailureSurvivesMediaBinLayoutRebuildUntilExplicitRetry()throws Exception{
        try(Fixture f=new Fixture(false,10000)){
            LiveEditPlayerRecoveryTest.failPlayback((androidx.media3.common.Player)get(f.live(),"editPlayer"));
            TextView error=f.content.findViewWithTag("studio-preview-error");String message=error.getText().toString();
            Object retained=get(f.live(),"editPlayer");
            // The media-bin toggle changes layout only and calls the real showEditor path.
            View toggle=findText(f.content,"Show media");assertNotNull(toggle);toggle.performClick();
            error=f.content.findViewWithTag("studio-preview-error");
            assertEquals("Layout rebuild must not dismiss an undecoded preview failure",View.VISIBLE,error.getVisibility());
            assertEquals(message,error.getText().toString());assertSame("No implicit admission for the unchanged failed edit",retained,get(f.live(),"editPlayer"));
            f.content.findViewWithTag("studio-preview-retry").performClick();
            assertEquals(View.GONE,error.getVisibility());assertTrue(f.live().isEditedPreview());
            LiveEditPlayerRecoveryTest.failPlayback((androidx.media3.common.Player)get(f.live(),"editPlayer"));
            f.project.clips.get(0).outMs=3500;f.store.save(f.project);invoke(f.activity,"showEditor");
            error=f.content.findViewWithTag("studio-preview-error");
            assertEquals("A real edit revision permits fresh admission",View.GONE,error.getVisibility());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        final ProjectStore store=new ProjectStore(activity);
        final AppProtocol protocol=new AppProtocol(activity,null);
        final ProjectStore.Project project=NativePreviewAdmissionTest.project();
        final FrameLayout content=new FrameLayout(activity);
        Fixture(boolean image,long duration)throws Exception{
            protocol.stop();project.id=UUID.randomUUID().toString();project.name="Preview test";
            project.assets.get(0).durationMs=duration;
            if(image){project.assets.get(0).mime="image/png";project.assets.get(0).uri="file:///not-decoded.png";project.clips.get(0).inMs=0;project.clips.get(0).outMs=3000;}
            store.save(project);store.setActive(project.id);
            set(activity,"store",store);set(activity,"protocol",protocol);set(activity,"activeProject",project);set(activity,"selectedClip",project.clips.get(0));
            set(activity,"prefs",activity.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE));set(activity,"content",content);
            set(activity,"renderEngine",new NativeRenderEngine(activity));invoke(activity,"showEditor");
        }
        LiveEditPlayer live()throws Exception{return (LiveEditPlayer)get(activity,"livePlayer");}
        JSONObject preview()throws Exception{
            String id=UUID.randomUUID().toString();activity.onCommand(new JSONObject().put("id",id).put("action","preview_project").put("parameters",new JSONObject()));
            org.json.JSONArray pending=((CommandOutbox)get(protocol,"outbox")).pending();
            for(int i=0;i<pending.length();i++)if(id.equals(pending.getJSONObject(i).optString("id")))return pending.getJSONObject(i).getJSONObject("result");
            throw new AssertionError("Command must produce a durable result");
        }
        public void close()throws Exception{
            invoke(activity,"stopStillSequenceOnly");
            Runnable refresh=(Runnable)get(activity,"activityRefresh");if(refresh!=null)((android.os.Handler)get(activity,"ui")).removeCallbacks(refresh);
            live().release();((StudioAudioWaveforms)get(activity,"studioAudioWaveforms")).shutdown();protocol.stop();((CommandOutbox)get(protocol,"outbox")).close();
        }
    }
    private static void invoke(Object o,String name)throws Exception{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static View findText(View view,String text){
        if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){View found=findText(group.getChildAt(i),text);if(found!=null)return found;}}
        return null;
    }
}
