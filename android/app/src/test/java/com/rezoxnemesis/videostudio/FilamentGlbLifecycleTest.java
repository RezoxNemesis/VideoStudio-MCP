package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import com.google.android.filament.utils.ModelViewer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

/** CPU lifecycle regressions. These do not certify a native GPU render. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class FilamentGlbLifecycleTest {
    @Test public void activityOwnedPreviewSurfaceSurvivesRepeatedDetachesWithoutSdkDestruction()throws Exception{
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).create().start().resume();
        try{
            SurfaceView surface=new FilamentGlbActivity.CinematicSurfaceView(controller.get());
            Class<?> hook=Class.forName("com.google.android.filament.utils.ModelViewer$addDetachListener$1");Constructor<?> constructor=hook.getDeclaredConstructor(ModelViewer.class);constructor.setAccessible(true);
            // Use the real pinned SDK hook. Invoking it with a null viewer would fail,
            // so a regression cannot hide behind a mock destructor or a native engine.
            surface.addOnAttachStateChangeListener((View.OnAttachStateChangeListener)constructor.newInstance(new Object[]{null}));
            int[] attachDetach={0,0};surface.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                @Override public void onViewAttachedToWindow(View view){attachDetach[0]++;}
                @Override public void onViewDetachedFromWindow(View view){attachDetach[1]++;}
            });
            controller.get().setContentView(surface);controller.visible();controller.get().setContentView(new FrameLayout(controller.get()));
            controller.get().setContentView(surface);controller.get().setContentView(new FrameLayout(controller.get()));
            assertEquals("Ordinary attachment observers must still run after reuse",2,attachDetach[0]);assertEquals("Only the SDK destructor is suppressed; Android detach observers stay intact",2,attachDetach[1]);
        }finally{controller.pause().stop().destroy();}
    }

    @Test public void savedModelPlaybackRetainsSelectedClipTimeRateAndCameraWithoutStartingGpu()throws Exception{
        // Construct and attach the Activity without onCreate: no Native3DRuntime,
        // Engine or ModelViewer is created to exercise CPU saved-state behavior.
        FilamentGlbActivity activity=Robolectric.buildActivity(FilamentGlbActivity.class).get();
        set(activity,"loadedModelId","exact-imported-glb");set(activity,"animationIndex",3);set(activity,"paused",true);set(activity,"cameraAngle",.6d);set(activity,"cameraPitch",25d);set(activity,"cameraDistance",7d);set(activity,"cinematicFrame",19);
        Preview3DPlayback clock=(Preview3DPlayback)get(activity,"clipClock");clock.seek(2.75,8);clock.rate(.5);
        Bundle state=new Bundle();activity.onSaveInstanceState(state);
        assertEquals("exact-imported-glb",state.getString("modelId"));assertEquals(3,state.getInt("animationIndex"));assertTrue(state.getBoolean("paused"));assertEquals(2.75,state.getDouble("clipPosition"),.000001);assertEquals(.5,state.getDouble("playbackRate"),.000001);
        assertEquals(.6,state.getDouble("cameraAngle"),.000001);assertEquals(25,state.getDouble("cameraPitch"),.000001);assertEquals(7,state.getDouble("cameraDistance"),.000001);assertEquals(19,state.getInt("cinematicFrame"));assertNull(get(activity,"viewer"));assertNull(get(activity,"engine"));
    }

    @Test public void secondRecreationBeforeModelReadCompletesKeepsPendingRestoredClipPosition()throws Exception{
        FilamentGlbActivity activity=Robolectric.buildActivity(FilamentGlbActivity.class).get();set(activity,"loadedModelId","pending-owned-model");set(activity,"restoringModel",true);set(activity,"restoredClipPosition",6.25d);
        assertEquals(0,((Preview3DPlayback)get(activity,"clipClock")).position(),0);Bundle state=new Bundle();activity.onSaveInstanceState(state);
        assertEquals("pending-owned-model",state.getString("modelId"));assertEquals("A second rotation cannot replace a pending saved clip with the empty clock",6.25,state.getDouble("clipPosition"),.000001);
    }

    private static Object get(Object owner,String name)throws Exception{Field field=FilamentGlbActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{Field field=FilamentGlbActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}
}
