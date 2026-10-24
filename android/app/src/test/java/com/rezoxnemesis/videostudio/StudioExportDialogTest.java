package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.Spinner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** Manual chooser and actual service-request wiring; no service or renderer is started. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public final class StudioExportDialogTest {
    @Test public void currentCanvasAspectIsPreselectedWithoutModifyingLegacySavedExportChoices(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        AlertDialog baseline=StudioExportDialog.show(activity,(aspect,quality)->{});
        int previous=choice(baseline,"export_aspect").getSelectedItemPosition();
        baseline.dismiss();
        AlertDialog source=StudioExportDialog.showOptions(activity,(aspect,quality,wait)->{}, "4:5");
        assertEquals(3,choice(source,"export_aspect").getSelectedItemPosition());
        click(source,AlertDialog.BUTTON_NEGATIVE);
        AlertDialog legacy=StudioExportDialog.show(activity,(aspect,quality)->fail("Opening export cannot send a render"));
        assertEquals(previous,choice(legacy,"export_aspect").getSelectedItemPosition());
        assertNull(nextService());
        legacy.dismiss();
    }

    @Test public void chooserDefaultsToLandscapeAndRemembersConfirmedPortraitAndQuality(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();String[] selected=new String[2];
        AlertDialog dialog=StudioExportDialog.show(activity,(aspect,quality)->{selected[0]=aspect;selected[1]=quality;});
        assertEquals(0,choice(dialog,"export_aspect").getSelectedItemPosition());
        assertEquals(0,choice(dialog,"export_quality").getSelectedItemPosition());
        assertNull(selected[0]);assertNull(nextService());
        choice(dialog,"export_aspect").setSelection(1);choice(dialog,"export_quality").setSelection(1);
        click(dialog,AlertDialog.BUTTON_POSITIVE);assertArrayEquals(new String[]{"9:16","1080p"},selected);
        Activity reopened=Robolectric.buildActivity(Activity.class).setup().get();
        AlertDialog remembered=StudioExportDialog.show(reopened,(aspect,quality)->fail("Opening controls must not submit an export"));
        assertEquals(1,choice(remembered,"export_aspect").getSelectedItemPosition());
        assertEquals(1,choice(remembered,"export_quality").getSelectedItemPosition());
        assertNull(nextService());assertNull(Shadows.shadowOf(reopened).getNextStartedActivity());remembered.dismiss();
    }

    @Test public void cancellingDoesNotSubmitOrRememberUnconfirmedChanges(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();int[] calls={0};
        AlertDialog dialog=StudioExportDialog.show(activity,(aspect,quality)->calls[0]++);
        choice(dialog,"export_aspect").setSelection(1);choice(dialog,"export_quality").setSelection(1);
        click(dialog,AlertDialog.BUTTON_NEGATIVE);assertEquals(0,calls[0]);
        AlertDialog reopened=StudioExportDialog.show(activity,(aspect,quality)->calls[0]++);
        assertEquals(0,choice(reopened,"export_aspect").getSelectedItemPosition());
        assertEquals(0,choice(reopened,"export_quality").getSelectedItemPosition());
        assertNull(nextService());reopened.dismiss();
    }

    @Test public void manualExportQueuesEachExplicitAspectAndQualityToTheNativeService()throws Exception {
        for(int aspect=0;aspect<4;aspect++)for(int quality=0;quality<2;quality++){
            MainActivity activity=activity(project("story"));openExport(activity);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
            Spinner shape=choice(dialog,"export_aspect"),detail=choice(dialog,"export_quality");
            shape.setSelection(aspect);detail.setSelection(quality);
            assertNull("Choosing controls must not queue work",nextService());
            click(dialog,AlertDialog.BUTTON_POSITIVE);
            Intent request=nextService();assertNotNull("Confirming must queue a native export",request);
            assertEquals(ControlService.class.getName(),request.getComponent().getClassName());
            assertEquals(ControlService.ACTION_LOCAL_EXPORT,request.getAction());
            assertEquals("story",request.getStringExtra("projectId"));
            assertEquals(new String[]{"16:9","9:16","1:1","4:5"}[aspect],request.getStringExtra("aspect"));
            assertEquals(quality==0?"720p":"1080p",request.getStringExtra("quality"));
            assertTrue(request.getStringExtra("fileName").matches("VideoStudio_[0-9]+\\.mp4"));
            assertNull("Export selection must not construct a render engine",field(activity,"renderEngine"));
            assertNull(nextService());ShadowAlertDialog.getLatestAlertDialog().dismiss();
        }
    }

    @Test public void emptyTimelineAndNoProjectNeverOpenAChooserOrQueueAService()throws Exception {
        MainActivity activity=activity(null);openExport(activity);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());assertNull(nextService());
        set(activity,"activeProject",new ProjectStore.Project());openExport(activity);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());assertNull(nextService());
        assertNull(field(activity,"renderEngine"));
    }

    @Test public void timelineEmptiedWhileChooserIsOpenDoesNotQueueAnExport()throws Exception {
        ProjectStore.Project project=project("story");MainActivity activity=activity(project);openExport(activity);
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();project.clips.clear();
        click(dialog,AlertDialog.BUTTON_POSITIVE);
        assertNull(nextService());assertNull(field(activity,"renderEngine"));
    }

    @Test public void changingActiveProjectWhileChoosingDoesNotExportAnotherTimeline()throws Exception {
        ProjectStore.Project original=project("chosen-story");MainActivity activity=activity(original);openExport(activity);
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();set(activity,"activeProject",project("another-story"));
        click(dialog,AlertDialog.BUTTON_POSITIVE);
        Intent request=nextService();assertNotNull(request);assertEquals(original.id,request.getStringExtra("projectId"));
        assertNull(field(activity,"renderEngine"));ShadowAlertDialog.getLatestAlertDialog().dismiss();
    }

    private static MainActivity activity(ProjectStore.Project project)throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();set(activity,"activeProject",project);return activity;
    }
    private static ProjectStore.Project project(String id){
        ProjectStore.Project p=new ProjectStore.Project();p.id=id;p.name="Native story";
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned-video";asset.mime="video/mp4";asset.uri="file:///not-opened.mp4";asset.durationMs=124000;p.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip";clip.assetId=asset.id;clip.outMs=124000;p.clips.add(clip);return p;
    }
    private static void openExport(MainActivity activity)throws Exception {
        java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("queueStudioExport");method.setAccessible(true);method.invoke(activity);
    }
    private static Spinner choice(AlertDialog dialog,String tag){
        android.view.View view=dialog.getWindow().getDecorView().findViewWithTag(tag);
        assertTrue("Export chooser must expose "+tag,view instanceof Spinner);return (Spinner)view;
    }
    private static Intent nextService(){return Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();}
    private static void click(AlertDialog dialog,int button){dialog.getButton(button).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    private static Object field(MainActivity activity,String name)throws Exception {java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private static void set(MainActivity activity,String name,Object value)throws Exception {java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
}
