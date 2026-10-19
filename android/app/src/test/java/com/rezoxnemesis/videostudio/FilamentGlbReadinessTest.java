package com.rezoxnemesis.videostudio;

import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Real Activity readiness/UI callbacks with driver observations supplied as data.
 * Does not create JNI, submit GPU frames or establish visible pixels. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class FilamentGlbReadinessTest {
    @Test public void oldAssetCannotSatisfyReadinessDuringReplacementRead() throws Exception {
        FilamentGlbActivity activity=fixture(false);int[] calls={0};
        installDriver(activity,new float[]{1},new boolean[]{true},calls);
        call(activity,"beginPreviewReadiness");set(activity,"modelLoading",true);
        activity.doFrame(1_000_000_000L);
        assertEquals("Old model must not submit as the requested replacement",0,calls[0]);
        assertEquals(View.VISIBLE,hint(activity).getVisibility());activity.onPause();
    }

    @Test public void actualStandaloneFramePathHonorsDriverSubmissionResult() throws Exception {
        FilamentGlbActivity activity=fixture(false);float[] progress={.3f};boolean[] submitted={true};int[] calls={0};
        installDriver(activity,progress,submitted,calls);call(activity,"beginPreviewReadiness");
        activity.doFrame(1_000_000_000L);
        assertEquals(View.VISIBLE,hint(activity).getVisibility());
        progress[0]=1;submitted[0]=false;activity.doFrame(2_000_000_000L);
        assertEquals(View.VISIBLE,hint(activity).getVisibility());
        submitted[0]=true;activity.doFrame(3_000_000_000L);
        assertEquals(View.GONE,hint(activity).getVisibility());assertEquals(3,calls[0]);
        activity.onPause();
    }

    @Test public void actualCinematicFramePathDoesNotRecordARefusedFrame() throws Exception {
        FilamentGlbActivity activity=fixture(true);float[] progress={1};boolean[] submitted={false};int[] calls={0};
        installDriver(activity,progress,submitted,calls);set(activity,"cinematicFrame",19);
        call(activity,"beginPreviewReadiness");activity.doFrame(1_000_000_000L);
        assertEquals(-1,get(activity,"cinematicLastRenderedFrame"));
        submitted[0]=true;activity.doFrame(2_000_000_000L);
        assertEquals(19,get(activity,"cinematicLastRenderedFrame"));assertEquals(2,calls[0]);activity.onPause();
    }

    @Test public void standaloneWaitsForResourcesAndSubmissionBeforeHidingLoadingHint() throws Exception {
        FilamentGlbActivity activity=fixture(false);
        call(activity,"beginPreviewReadiness");
        observe(activity,.4f,true);
        assertEquals(View.VISIBLE,hint(activity).getVisibility());
        assertFalse(scrub(activity).isEnabled());
        observe(activity,1f,false);
        assertEquals(View.VISIBLE,hint(activity).getVisibility());
        assertTrue(status(activity).contains("submission"));
        observe(activity,1f,true);
        assertEquals(View.GONE,hint(activity).getVisibility());
        assertTrue(status(activity).contains("submitted"));
        assertEquals(View.GONE,retry(activity).getVisibility());
    }

    @Test public void persistentForegroundRefusalOffersRetryAndSuspendsClipClock() throws Exception {
        FilamentGlbActivity activity=fixture(false);
        call(activity,"beginPreviewReadiness");
        Preview3DPlayback clock=(Preview3DPlayback)get(activity,"clipClock");
        clock.seek(2,10);clock.frame(1_000_000_000L,true,10);
        observe(activity,1f,false);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31));observe(activity,1f,false);
        assertTrue(status(activity).contains("submission"));
        assertTrue(status(activity).contains("Retry"));
        assertEquals(View.VISIBLE,retry(activity).getVisibility());
        assertEquals(2,clock.frame(100_000_000_000L,true,10),0);
        assertEquals(View.VISIBLE,hint(activity).getVisibility());
    }

    @Test public void cinematicCannotClaimLastRenderedFrameWhenDriverRefuses() throws Exception {
        FilamentGlbActivity activity=fixture(true);
        set(activity,"cinematicFrame",17);set(activity,"cinematicLastRenderedFrame",-1);
        call(activity,"beginPreviewReadiness");observe(activity,1f,false);
        assertEquals(-1,get(activity,"cinematicLastRenderedFrame"));
        observe(activity,.5f,true);
        assertEquals(-1,get(activity,"cinematicLastRenderedFrame"));
        observe(activity,1f,true);
        assertEquals(17,get(activity,"cinematicLastRenderedFrame"));
    }

    @Test public void activityPauseExcludesBackgroundFromPendingDeadline() throws Exception {
        FilamentGlbActivity activity=fixture(false);
        call(activity,"beginPreviewReadiness");
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20));observe(activity,.5f,false);
        activity.onPause();ShadowSystemClock.advanceBy(Duration.ofMinutes(5));activity.onResume();
        observe(activity,.5f,false);
        assertEquals(View.GONE,retry(activity).getVisibility());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(9));observe(activity,.5f,false);
        assertEquals(View.GONE,retry(activity).getVisibility());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));observe(activity,.5f,false);
        assertEquals(View.VISIBLE,retry(activity).getVisibility());
        assertTrue(status(activity).contains("materials"));
    }

    @Test public void newAttemptClearsFailureAndRequiresFreshSubmission() throws Exception {
        FilamentGlbActivity activity=fixture(false);
        call(activity,"beginPreviewReadiness");
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31));observe(activity,1f,false);
        assertEquals(View.VISIBLE,retry(activity).getVisibility());
        call(activity,"beginPreviewReadiness");observe(activity,1f,false);
        assertEquals(View.GONE,retry(activity).getVisibility());
        assertEquals(View.VISIBLE,hint(activity).getVisibility());
        observe(activity,1f,true);
        assertEquals(View.GONE,hint(activity).getVisibility());
    }

    @Test public void submissionLossAfterReadyRestoresWaitingWithoutCountingBackground() throws Exception {
        FilamentGlbActivity activity=fixture(false);
        call(activity,"beginPreviewReadiness");observe(activity,1f,true);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2));observe(activity,1f,true);
        observe(activity,1f,false);
        assertEquals(View.VISIBLE,hint(activity).getVisibility());
        assertEquals(View.GONE,retry(activity).getVisibility());
        activity.onPause();ShadowSystemClock.advanceBy(Duration.ofMinutes(5));activity.onResume();
        observe(activity,1f,true);
        assertEquals(View.GONE,hint(activity).getVisibility());
    }

    private static FilamentGlbActivity fixture(boolean cinematic) throws Exception {
        // Attach without onCreate: readiness callbacks are real, the renderer is absent.
        FilamentGlbActivity activity=Robolectric.buildActivity(FilamentGlbActivity.class).get();
        set(activity,"cinematic",cinematic);set(activity,"status",new TextView(activity));
        set(activity,"modelHint",new TextView(activity));set(activity,"modelScrub",new SeekBar(activity));
        set(activity,"cinematicScrub",new SeekBar(activity));set(activity,"clipTime",new TextView(activity));
        set(activity,"retryButton",new Button(activity));activity.onResume();return activity;
    }
    private static void installDriver(FilamentGlbActivity activity,float[] progress,boolean[] submitted,int[] calls)throws Exception {
        Class<?> type;
        try{type=Class.forName(FilamentGlbActivity.class.getName()+"$PreviewFrameDriver");}
        catch(ClassNotFoundException missing){throw new AssertionError("Native frame submission observation boundary missing",missing);}
        Object driver=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            switch(method.getName()){
                case "hasAsset":return true;
                case "progress":return progress[0];
                case "render":calls[0]++;return submitted[0];
                default:throw new AssertionError(method.getName());
            }
        });
        set(activity,"previewDriver",driver);set(activity,"loopActive",true);
    }
    private static void observe(FilamentGlbActivity activity,float progress,boolean submitted) throws Exception {
        method("observePreviewSubmission",float.class,boolean.class).invoke(activity,progress,submitted);
    }
    private static Method method(String name,Class<?>... parameters) {
        try {Method method=FilamentGlbActivity.class.getDeclaredMethod(name,parameters);method.setAccessible(true);return method;}
        catch(ReflectiveOperationException missing){throw new AssertionError("Native preview readiness callback missing: "+name,missing);}
    }
    private static void call(Object activity,String name)throws Exception {method(name).invoke(activity);}
    private static Object get(Object owner,String name)throws Exception {Field f=FilamentGlbActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception {
        try {Field f=FilamentGlbActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
        catch(NoSuchFieldException missing){throw new AssertionError("Native preview readiness UI missing: "+name,missing);}
    }
    private static TextView hint(Object a)throws Exception{return (TextView)get(a,"modelHint");}
    private static Button retry(Object a)throws Exception{return (Button)get(a,"retryButton");}
    private static SeekBar scrub(Object a)throws Exception{return (SeekBar)get(a,"modelScrub");}
    private static String status(Object a)throws Exception{return ((TextView)get(a,"status")).getText().toString();}
}
