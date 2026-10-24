package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.Spinner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

/** Actual Main chooser-to-service Intent wiring; never starts a service, decoder or renderer. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public final class StudioExportDelayUiTest {
    private static final String FIELD="maxOutputSampleDelayMs",TAG="export_output_sample_delay";

    @Before public void freshChoices(){
        RuntimeEnvironment.getApplication().getSharedPreferences("studio_export_choices",0).edit().clear().commit();
        while(nextService()!=null){}
    }

    @Test public void defaultSubmissionKeepsTheExactOldIntentFieldsAndNoRenderEngine()throws Exception{
        ProjectStore.Project p=project();MainActivity activity=activity(p);String before=SketchDocument.canonical(p.toJson());
        AlertDialog dialog=open(activity);assertNull("Opening must not queue a native job",nextService());
        confirm(dialog);
        Intent request=nextService();assertNotNull(request);
        assertEquals(Set.of("projectId","aspect","quality","fileName"),request.getExtras().keySet());
        assertFalse("Default is omission, not an explicit25-second override",request.hasExtra(FIELD));
        assertEquals(p.id,request.getStringExtra("projectId"));assertEquals("16:9",request.getStringExtra("aspect"));
        assertEquals("720p",request.getStringExtra("quality"));assertEquals(ControlService.ACTION_LOCAL_EXPORT,request.getAction());
        assertEquals(before,SketchDocument.canonical(p.toJson()));assertNull(field(activity,"renderEngine"));assertNull(nextService());dismissLatest();
    }

    @Test public void actualManualChooserExposesDefaultSixtyAndOneHundredTwentyWithoutSubmitting()throws Exception{
        MainActivity activity=activity(project());AlertDialog dialog=open(activity);Spinner wait=waitChoice(dialog);
        assertEquals(3,wait.getCount());assertEquals(0,wait.getSelectedItemPosition());
        assertTrue(String.valueOf(wait.getItemAtPosition(0)).toLowerCase(java.util.Locale.ROOT).contains("default"));
        assertTrue(String.valueOf(wait.getItemAtPosition(1)).contains("60"));assertTrue(String.valueOf(wait.getItemAtPosition(2)).contains("120"));
        wait.setSelection(1);wait.setSelection(2);assertNull(nextService());assertNull(field(activity,"renderEngine"));dialog.dismiss();
    }

    @Test public void confirmedSlowDecodingChoicesReachTheSameNativeServiceAsExactNumbers()throws Exception{
        for(int selected=1;selected<=2;selected++){
            ProjectStore.Project p=project();MainActivity activity=activity(p);AlertDialog dialog=open(activity);
            waitChoice(dialog).setSelection(selected);assertNull(nextService());confirm(dialog);
            Intent request=nextService();assertNotNull(request);assertEquals(ControlService.ACTION_LOCAL_EXPORT,request.getAction());
            assertEquals(ControlService.class.getName(),request.getComponent().getClassName());assertEquals(p.id,request.getStringExtra("projectId"));
            assertTrue(request.hasExtra(FIELD));Object raw=request.getExtras().get(FIELD);assertTrue(raw instanceof Number);
            assertEquals(selected==1?60000:120000,((Number)raw).longValue());
            assertNull(field(activity,"renderEngine"));assertNull(nextService());dismissLatest();
        }
    }

    @Test public void returningToDefaultRemovesTheExplicitFieldAfterAPreviousConfirmedChoice()throws Exception{
        MainActivity activity=activity(project());AlertDialog first=open(activity);waitChoice(first).setSelection(1);confirm(first);
        Intent explicit=nextService();assertNotNull(explicit);assertEquals(60000,((Number)explicit.getExtras().get(FIELD)).longValue());dismissLatest();
        AlertDialog clear=open(activity);waitChoice(clear).setSelection(0);assertNull(nextService());confirm(clear);
        Intent omitted=nextService();assertNotNull(omitted);assertFalse(omitted.hasExtra(FIELD));
        assertNull(field(activity,"renderEngine"));assertNull(nextService());dismissLatest();
    }

    @Test public void cancellingDoesNotSubmitOrRememberAnUnconfirmedSlowChoice()throws Exception{
        MainActivity activity=activity(project());AlertDialog cancelled=open(activity);waitChoice(cancelled).setSelection(2);
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNull(nextService());assertNull(field(activity,"renderEngine"));
        AlertDialog reopened=open(activity);assertEquals(0,waitChoice(reopened).getSelectedItemPosition());assertNull(nextService());reopened.dismiss();
    }

    @Test public void changingActiveProjectWhileChoosingKeepsTheCapturedSourceAndWait()throws Exception{
        ProjectStore.Project chosen=project(),other=project();MainActivity activity=activity(chosen);String chosenBefore=SketchDocument.canonical(chosen.toJson());
        AlertDialog dialog=open(activity);waitChoice(dialog).setSelection(1);set(activity,"activeProject",other);confirm(dialog);
        Intent request=nextService();assertNotNull(request);assertEquals(chosen.id,request.getStringExtra("projectId"));
        assertEquals(60000,((Number)request.getExtras().get(FIELD)).longValue());
        assertEquals(chosenBefore,SketchDocument.canonical(chosen.toJson()));assertNull(field(activity,"renderEngine"));assertNull(nextService());dismissLatest();
    }

    private static MainActivity activity(ProjectStore.Project p)throws Exception{MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();set(activity,"activeProject",p);return activity;}
    private static ProjectStore.Project project(){
        ProjectStore.Project p=new ProjectStore.Project();p.id=UUID.randomUUID().toString();p.name="Native wait chooser";
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned-video";asset.mime="video/mp4";asset.uri="file:///not-opened-wait.mp4";asset.durationMs=10000;p.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip";clip.assetId=asset.id;clip.outMs=5000;p.clips.add(clip);return p;
    }
    private static AlertDialog open(MainActivity activity)throws Exception{Method m=MainActivity.class.getDeclaredMethod("queueStudioExport");m.setAccessible(true);m.invoke(activity);AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(d);return d;}
    private static Spinner waitChoice(AlertDialog dialog){View found=dialog.getWindow().getDecorView().findViewWithTag(TAG);assertTrue("Manual export must expose explicit Default/60s/120s wait",found instanceof Spinner);return (Spinner)found;}
    private static void confirm(AlertDialog dialog){dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    private static void dismissLatest(){AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();if(d!=null)d.dismiss();}
    private static Intent nextService(){return Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
}
