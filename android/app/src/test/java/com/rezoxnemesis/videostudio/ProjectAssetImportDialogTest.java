package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class ProjectAssetImportDialogTest {
    @Test public void explicitSelectionRequiresHashInspectionAndQueuesTheSameCancellableNativeOperation()throws Exception {
        ProjectAssetImportTest.Fixture f=new ProjectAssetImportTest.Fixture();
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(activity.get(),f.destination.id);drain(owner);select("Source project","Picture");drain(owner);
            assertFalse(button("Copy asset").isEnabled());assertFalse(check().isChecked());assertEquals("Native picture.mp4",((Spinner)control("Source asset")).getSelectedItem().toString());
            button("Inspect selected asset").performClick();drain(owner);assertTrue(button("Copy asset").isEnabled());assertTrue(((TextView)control("Project asset import status")).getText().toString().contains(f.request.getString("expectedSha256")));
            check().setChecked(true);button("Copy asset").performClick();drain(owner);Intent intent=Shadows.shadowOf(activity.get()).getNextStartedService();assertNotNull(intent);assertEquals("com.rezoxnemesis.videostudio.LOCAL_PROJECT_ASSET_IMPORT",intent.getAction());
            JSONObject request=new JSONObject(intent.getStringExtra("requestJson"));assertEquals(f.destination.id,request.getString("projectId"));assertEquals(f.source.id,request.getString("sourceProjectId"));assertEquals("picture",request.getString("assetId"));assertEquals(f.source.updatedAt,request.getLong("sourceProjectUpdatedAt"));assertEquals(f.destination.updatedAt,request.getLong("expectedUpdatedAt"));assertEquals(f.request.getString("expectedSha256"),request.getString("expectedSha256"));assertTrue(request.getBoolean("appendToTimeline"));
            assertEquals(1,f.output().assets.size());assertFalse(button("Copy asset").isEnabled());dialog().dismiss();
        }
    }
    @Test public void changingSelectionInvalidatesAnEarlierHashAndClosingStopsTheInspector()throws Exception {
        ProjectAssetImportTest.Fixture f=new ProjectAssetImportTest.Fixture();
        ProjectStore.Asset second=ProjectStore.Asset.fromJson(f.source.assets.get(0).toJson());second.id="other";second.name="Second picture.mp4";f.source.assets.add(second);f.store.save(f.source);
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            Object owner=open(activity.get(),f.destination.id);drain(owner);select("Source project","Picture");drain(owner);button("Inspect selected asset").performClick();drain(owner);assertTrue(button("Copy asset").isEnabled());
            ((Spinner)control("Source asset")).setSelection(1);ShadowLooper.idleMainLooper();assertFalse(button("Copy asset").isEnabled());dialog().dismiss();ShadowLooper.idleMainLooper();assertTrue(worker(owner).isShutdown());
        }
    }
    @Test public void cancelledAfterPublicationReopenRetriesTheExactSavedRequestWithoutDuplicatingOrReplacingHumanEdits()throws Exception {
        ProjectAssetImportTest.Fixture f=new ProjectAssetImportTest.Fixture();
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            String original=queueNew(activity.get(),f);JSONObject request=new JSONObject(original);JSONObject result=ProjectAssetImport.execute(f.context,request);
            // Publication succeeded, but no completion is delivered to this dialog (for example,
            // cancellation after commit or the activity closing before the receipt is displayed).
            ProjectStore.Project edited=f.output();assertEquals(1,edited.clips.size());edited.clips.get(0).title="Human edit after publication";f.store.save(edited);long revision=f.output().updatedAt;
            dialog().dismiss();ShadowLooper.idleMainLooper();f.store.delete(f.source.id);java.nio.file.Files.delete(f.file.toPath());
            Object reopened=open(activity.get(),f.destination.id);drain(reopened);((EditText)control("Project asset operation ID")).setText("different_new_operation");check().setChecked(false);
            assertTrue(button("Retry saved import").isEnabled());button("Retry saved import").performClick();drain(reopened);
            Intent retry=Shadows.shadowOf(activity.get()).getNextStartedService();assertNotNull(retry);assertEquals("Every saved request byte must survive reopen",original,retry.getStringExtra("requestJson"));
            JSONObject replay=ProjectAssetImport.execute(f.context,new JSONObject(retry.getStringExtra("requestJson")));assertTrue(replay.getBoolean("replayed"));assertEquals(result.getString("assetId"),replay.getString("assetId"));
            assertEquals(2,f.output().assets.size());assertEquals(1,f.output().clips.size());assertEquals("Human edit after publication",f.output().clips.get(0).title);assertEquals(revision,f.output().updatedAt);dialog().dismiss();ShadowLooper.idleMainLooper();
        }
    }
    @Test public void cancelledStreamingCopyReopensAndRetriesSavedParametersWithoutMintingAnotherOperation()throws Exception {
        ProjectAssetImportTest.Fixture f=new ProjectAssetImportTest.Fixture();
        try(ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup()){
            String original=queueNew(activity.get(),f);JSONObject request=new JSONObject(original);
            try{assertThrows(InterruptedException.class,()->ProjectAssetImport.execute(f.context,request,(n,total)->{if(n>0)Thread.currentThread().interrupt();}));}finally{Thread.interrupted();}
            assertEquals(1,f.output().assets.size());dialog().dismiss();ShadowLooper.idleMainLooper();Object reopened=open(activity.get(),f.destination.id);drain(reopened);button("Retry saved import").performClick();drain(reopened);
            Intent retry=Shadows.shadowOf(activity.get()).getNextStartedService();assertEquals(original,retry.getStringExtra("requestJson"));ProjectAssetImport.execute(f.context,new JSONObject(retry.getStringExtra("requestJson")));assertEquals(2,f.output().assets.size());assertEquals(1,f.output().clips.size());dialog().dismiss();ShadowLooper.idleMainLooper();
        }
    }
    private static String queueNew(Activity activity,ProjectAssetImportTest.Fixture f)throws Exception {
        Object owner=open(activity,f.destination.id);drain(owner);select("Source project","Picture");drain(owner);button("Inspect selected asset").performClick();drain(owner);check().setChecked(true);button("Copy asset").performClick();drain(owner);
        Intent first=Shadows.shadowOf(activity).getNextStartedService();assertNotNull(first);return first.getStringExtra("requestJson");
    }
    private static Object open(Activity activity,String projectId)throws Exception {
        Class<?> type=Class.forName("com.rezoxnemesis.videostudio.ProjectAssetImportDialog");Method open=type.getDeclaredMethod("open",Activity.class,String.class);open.setAccessible(true);return open.invoke(null,activity,projectId);
    }
    private static ExecutorService worker(Object owner)throws Exception {Field field=owner.getClass().getDeclaredField("worker");field.setAccessible(true);return (ExecutorService)field.get(owner);}
    private static void drain(Object owner)throws Exception {for(int i=0;i<3;i++){worker(owner).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
    private static AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    private static View control(String label){View v=SketchSolidUiTest.description(dialog().getWindow().getDecorView(),label);assertNotNull(label,v);return v;}
    private static Button button(String text){Button b=SketchSolidUiTest.button(dialog().getWindow().getDecorView(),text);assertNotNull(text,b);return b;}
    private static CheckBox check(){return findCheck(dialog().getWindow().getDecorView());}
    private static CheckBox findCheck(View view){if(view instanceof CheckBox)return (CheckBox)view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){CheckBox box=findCheck(g.getChildAt(i));if(box!=null)return box;}}return null;}
    private static void select(String label,String value){Spinner spinner=(Spinner)control(label);for(int i=0;i<spinner.getCount();i++)if(spinner.getItemAtPosition(i).toString().equals(value)){spinner.setSelection(i);ShadowLooper.idleMainLooper();return;}fail("Missing selection "+value);}
}
