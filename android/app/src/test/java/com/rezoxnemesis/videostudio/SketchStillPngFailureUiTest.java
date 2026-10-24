package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Durable failed/cancelled status before an output exists; no job executor or PNG rendering. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SketchStillPngFailureUiTest {
    @Test public void failedJobWithoutAResultShowsItsDetailAndKeepsTheExactSavedRetry()throws Exception{
        try(Fixture fixture=new Fixture()){
            fixture.attach("sketch_render",fixture.request,fixture.output,"failed-before-result");
            fixture.publish("failed-before-result",JobManager.STATE_FAILED,"Atomic PNG publication failed before a result",null);fixture.poll();
            assertTrue("A matching failed native job must not remain forever submitted",fixture.status().contains("PNG save failed: Atomic PNG publication failed before a result"));
            fixture.assertExactRetry();
        }
    }

    @Test public void cancelledJobWithoutAResultShowsCancellationAndKeepsTheExactSavedRetry()throws Exception{
        try(Fixture fixture=new Fixture()){
            fixture.attach("sketch_render",fixture.request,fixture.output,"cancelled-before-result");
            fixture.publish("cancelled-before-result",JobManager.STATE_CANCELLED,"Cancelled while preparing the native frame",null);fixture.poll();
            assertTrue("A matching cancelled job must not remain forever submitted",fixture.status().contains("PNG save cancelled: Cancelled while preparing the native frame"));
            fixture.assertExactRetry();
        }
    }

    @Test public void equivalentPublicRequestOrderAndInternalCommandMetadataDoNotBreakFailureBinding()throws Exception{
        try(Fixture fixture=new Fixture()){
            JSONObject durable=new JSONObject().put("frame",fixture.request.getInt("frame")).put("format","png")
                    .put("operationId",fixture.request.getString("operationId")).put("expectedRevision",fixture.request.getLong("expectedRevision"))
                    .put("sketchId",fixture.request.getString("sketchId")).put("_mcpCommandId","synthetic-status-test-command");
            fixture.attach("sketch_render",durable,fixture.output,"reordered-exact-job");
            fixture.publish("reordered-exact-job",JobManager.STATE_FAILED,"Exact request failed",null);fixture.poll();
            assertTrue("Binding uses validated public values, not JSON field order or internal command metadata",fixture.status().contains("PNG save failed: Exact request failed"));
            fixture.assertExactRetry();
        }
    }

    @Test public void anotherRequestCannotSupplyFailureEvenWhenPartOfItsIdentityMatches()throws Exception{
        for(String changed:new String[]{"sketchId","expectedRevision","frame","operationId","action","output"}){
            try(Fixture fixture=new Fixture()){
                JSONObject other=new JSONObject(fixture.request.toString());String action="sketch_render",output=fixture.output;
                if("sketchId".equals(changed))other.put(changed,"other_"+UUID.randomUUID());
                if("expectedRevision".equals(changed))other.put(changed,fixture.request.getLong(changed)+1);
                if("frame".equals(changed))other.put(changed,2);
                if("operationId".equals(changed))other.put(changed,UUID.randomUUID().toString());
                if("action".equals(changed))action="sketch_book_render";
                if("output".equals(changed))output=UUID.randomUUID().toString();
                fixture.attach(action,other,output,"unrelated-job");fixture.publish("unrelated-job",JobManager.STATE_FAILED,"UNRELATED_FAILURE_MUST_NOT_APPEAR",null);fixture.poll();
                assertFalse("Full request/action/output identity must bind status: "+changed,fixture.status().contains("UNRELATED_FAILURE_MUST_NOT_APPEAR"));
                assertFalse(fixture.status().contains("PNG save failed:"));assertTrue(fixture.retry().isEnabled());
            }
        }
    }

    @Test public void aShallowUnboundPngResultCannotAttributeAnotherJobsFailureToThisRequest()throws Exception{
        try(Fixture fixture=new Fixture()){
            JSONObject result=new JSONObject().put("format","png").put("outputProjectId",fixture.output)
                    .put("sketchId","unrelated_source").put("operationId","unrelated_operation").put("sourceRevision",1).put("frame",2);
            fixture.publish("unbound-job",JobManager.STATE_FAILED,"UNBOUND_RESULT_FAILURE_MUST_NOT_APPEAR",result);fixture.poll();
            assertFalse("A format/project-only result is not proof of the exact selected source request",fixture.status().contains("UNBOUND_RESULT_FAILURE_MUST_NOT_APPEAR"));
            assertFalse(fixture.status().contains("PNG save failed:"));assertTrue(fixture.retry().isEnabled());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Context context=RuntimeEnvironment.getApplication();
        final ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();
        final Activity activity=controller.get();final ExecutorService io=Executors.newSingleThreadExecutor();
        final JSONObject request;final String output,raw;final SharedPreferences saved;
        final SketchStillPngDialog owner;final AlertDialog dialog;
        Fixture()throws Exception{
            String id="png_status_"+UUID.randomUUID();request=new JSONObject().put("sketchId",id).put("expectedRevision",7).put("operationId",UUID.randomUUID().toString()).put("format","png").put("frame",1);raw=request.toString();
            output=SketchStillExport.outputProjectId(id,request.getString("operationId"));saved=activity.getSharedPreferences("png-failure-status-"+id,0);saved.edit().putString("pendingStillPng_"+id,raw).commit();drain();
            owner=SketchStillPngDialog.show(activity,io,saved,new SketchStillPngDialog.Source(){
                public String sketchId(){return id;}public long revision(){return 7;}public int frame(){return 1;}
                public int frameCount(){return 3;}public int width(){return 512;}public int height(){return 256;}public String name(){return "Known saved PNG request";}
                public boolean current(){return true;}public void awaitReady(boolean retry){}
            },ignored->{});
            dialog=ShadowAlertDialog.getLatestAlertDialog();ShadowLooper.idleMainLooper();retry().performClick();await();Intent intent=nextExport();assertNotNull("Existing exact saved request must queue normally",intent);assertEquals(raw,intent.getStringExtra("requestJson"));
        }
        void attach(String action,JSONObject parameters,String target,String job)throws Exception{RecoveryPlanStore plans=new RecoveryPlanStore(context);String plan=plans.begin(action,parameters,target);plans.attachJob(plan,job);}
        void publish(String id,String state,String detail,JSONObject result)throws Exception{
            JSONObject job=new JSONObject().put("id",id).put("name","Save sketch PNG").put("state",state).put("detail",detail).put("stage","Saving sketch PNG").put("progress",5);
            if(result!=null)job.put("result",result);
            context.getSharedPreferences("videostudio_native_v1",0).edit().putString(ExecutionTruthPolicy.JOB_RECOVERY_PREF_KEY,new JSONArray().put(job).toString()).commit();
        }
        void poll()throws Exception{java.lang.reflect.Method poll=SketchStillPngDialog.class.getDeclaredMethod("pollJob");poll.setAccessible(true);poll.invoke(owner);await();}
        String status()throws Exception{return ((TextView)field(owner,"status")).getText().toString();}
        Button retry(){Button retry=button(dialog.getWindow().getDecorView(),"Retry saved PNG");assertNotNull(retry);return retry;}
        void assertExactRetry()throws Exception{assertTrue("The saved request must remain available after failure/cancellation",retry().isEnabled());retry().performClick();await();Intent intent=nextExport();assertNotNull(intent);assertEquals("Retry cannot mint a new operation or change source/frame/revision",raw,intent.getStringExtra("requestJson"));assertEquals(raw,saved.getString("pendingStillPng_"+request.getString("sketchId"),""));}
        void await()throws Exception{for(int i=0;i<3;i++){io.submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
        @Override public void close()throws Exception{owner.close();io.shutdownNow();assertTrue(io.awaitTermination(10,TimeUnit.SECONDS));controller.pause().stop().destroy();drain();}
    }
    private static Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static void drain(){while(Shadows.shadowOf((Application)RuntimeEnvironment.getApplication()).getNextStartedService()!=null){}}
    private static Intent nextExport(){Intent next;while((next=Shadows.shadowOf((Application)RuntimeEnvironment.getApplication()).getNextStartedService())!=null)if(ControlService.ACTION_LOCAL_SKETCH_EXPORT.equals(next.getAction()))return next;return null;}
    private static TextView text(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=text(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static Button button(View root,String value){TextView found=text(root,value);return found instanceof Button?(Button)found:null;}
}
