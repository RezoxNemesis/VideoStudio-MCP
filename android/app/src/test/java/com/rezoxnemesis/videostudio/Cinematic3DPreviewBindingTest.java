package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class Cinematic3DPreviewBindingTest {
    @Test public void previewRequiresTheSavedProgramGenerationAndExactSourceProject() throws Exception {
        Class<?> studio;
        try{studio=Class.forName("com.rezoxnemesis.videostudio.Cinematic3DStudioActivity");}catch(ClassNotFoundException missing){throw new AssertionError("Saved cinematic preview entry missing",missing);}
        Method make=studio.getDeclaredMethod("previewIntent",Context.class,String.class,String.class,String.class);make.setAccessible(true);
        Intent intent=(Intent)make.invoke(null,RuntimeEnvironment.getApplication(),"source-project","saved-program","c".repeat(64));
        assertEquals(FilamentGlbActivity.class.getName(),intent.getComponent().getClassName());assertEquals("source-project",intent.getStringExtra("projectId"));assertEquals("saved-program",intent.getStringExtra("cinematicProgramId"));assertEquals("c".repeat(64),intent.getStringExtra("expectedProgramSha256"));
    }

    @Test public void fullScenePreviewPreservesAuthoredWorldRoots() throws Exception {
        Method policy;
        try{policy=FilamentGlbActivity.class.getDeclaredMethod("normalizeSceneForPreview",boolean.class);}catch(NoSuchMethodException missing){throw new AssertionError("Faithful full-scene preview policy missing",missing);}
        policy.setAccessible(true);
        assertFalse("Cinematic full scenes must retain their authored world roots/metres",(Boolean)policy.invoke(null,true));
        assertTrue("Existing standalone model preview keeps its default framing",(Boolean)policy.invoke(null,false));
    }

    @Test public void previewChecksTheActuallyLoadedSceneBytesWithoutMovingTheirReadPosition() throws Exception {
        Method check=FilamentGlbActivity.class.getDeclaredMethod("verifyLoadedSceneBytes",ByteBuffer.class,String.class);check.setAccessible(true);
        byte[] loaded=new byte[]{1,2,3,4};ByteBuffer buffer=ByteBuffer.allocateDirect(4);buffer.put(loaded).flip();StringBuilder digest=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(loaded))digest.append(String.format("%02x",value&255));
        check.invoke(null,buffer,digest.toString());assertEquals(0,buffer.position());assertEquals(4,buffer.remaining());buffer.put(0,(byte)9);
        try{check.invoke(null,buffer,digest.toString());fail("Changed readback bytes must not reach GPU scene creation");}catch(InvocationTargetException expected){assertTrue(expected.getCause() instanceof IllegalArgumentException);assertTrue(expected.getCause().getMessage().contains("BUNDLE_SCENE_CHANGED"));}
    }

    @Test public void cinematicSurfaceKeepsTheSdkDetachHookFromDestroyingAnActivityOwnedViewer() throws Exception {
        Class<?> surfaceClass;try{surfaceClass=Class.forName("com.rezoxnemesis.videostudio.FilamentGlbActivity$CinematicSurfaceView");}catch(ClassNotFoundException missing){throw new AssertionError("Cinematic viewer destruction needs one lifecycle owner",missing);}
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).create().start().resume();
        try{java.lang.reflect.Constructor<?> surfaceConstructor=surfaceClass.getDeclaredConstructor(Context.class);surfaceConstructor.setAccessible(true);SurfaceView surface=(SurfaceView)surfaceConstructor.newInstance(controller.get());
            // This is the actual pinned SDK detach hook. A null viewer deliberately makes
            // an accidental automatic invocation fail, without creating a native GPU engine.
            Class<?> sdk=Class.forName("com.google.android.filament.utils.ModelViewer$addDetachListener$1");java.lang.reflect.Constructor<?> constructor=sdk.getDeclaredConstructor(com.google.android.filament.utils.ModelViewer.class);constructor.setAccessible(true);surface.addOnAttachStateChangeListener((View.OnAttachStateChangeListener)constructor.newInstance(new Object[]{null}));
            int[] ordinaryDetaches={0};surface.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){@Override public void onViewAttachedToWindow(View view){}@Override public void onViewDetachedFromWindow(View view){ordinaryDetaches[0]++;}});controller.get().setContentView(surface);controller.visible();controller.get().setContentView(new FrameLayout(controller.get()));assertEquals("Ordinary Android detach callbacks must remain intact",1,ordinaryDetaches[0]);
        }finally{controller.pause().stop().destroy();}
    }
}
