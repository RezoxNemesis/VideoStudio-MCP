package com.rezoxnemesis.videostudio;

import java.io.File;
import java.nio.ByteBuffer;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class NativeCinematicSceneComposerTest {
    private static Object call(String method,Class<?>[] types,Object... args) {
        try { return Class.forName("com.rezoxnemesis.videostudio.NativeCinematicSceneComposer").getMethod(method,types).invoke(null,args); }
        catch(java.lang.reflect.InvocationTargetException failure) {
            if(failure.getCause() instanceof RuntimeException) throw (RuntimeException)failure.getCause();
            throw new AssertionError(failure.getCause());
        }
        catch(ReflectiveOperationException absent) { throw new AssertionError("Native cinematic renderer is absent",absent); }
    }
    private static int requireDimensions(int width,int height) { return (Integer)call("requireDimensions",new Class<?>[]{int.class,int.class},width,height); }
    private static void copyTopLeftArgb(ByteBuffer rgba,int width,int height,int[] pixels) { call("copyTopLeftArgb",new Class<?>[]{ByteBuffer.class,int.class,int.class,int[].class},rgba,width,height,pixels); }
    private static JSONObject clockMetadata(int frame,int fps) { return (JSONObject)call("clockMetadata",new Class<?>[]{int.class,int.class},frame,fps); }
    private static File createGenerationDirectory(android.content.Context context,String project,String generation) { return (File)call("createGenerationDirectory",new Class<?>[]{android.content.Context.class,String.class,String.class},context,project,generation); }
    private static void requireNewOutput(ProjectStore.Project source,ProjectStore.Project output) { call("requireNewOutput",new Class<?>[]{ProjectStore.Project.class,ProjectStore.Project.class},source,output); }
    @Test public void dimensionBudgetAccepts720pInBothOrientationsAndRejectsUnsafeRaster() {
        assertEquals(720*1280*4,requireDimensions(720,1280));
        assertEquals(720*1280*4,requireDimensions(1280,720));
        for(int[] dimensions:new int[][]{{1080,1920},{1280,1280},{721,1280},{720,1281},{0,720},{128,256}}) {
            try { requireDimensions(dimensions[0],dimensions[1]); fail("Unsafe GPU raster accepted"); }
            catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void reusableReadbackPreservesOriginAlphaAndBufferPosition() {
        ByteBuffer rgba=ByteBuffer.wrap(new byte[]{9,9,9,9, (byte)255,0,0,50, 0,0,(byte)255,(byte)128});
        rgba.position(4);
        int[] pixels=new int[2];
        copyTopLeftArgb(rgba,1,2,pixels);
        assertArrayEquals(new int[]{0x800000ff,0x32ff0000},pixels);
        assertEquals(4,rgba.position());
        rgba.put(4,(byte)12);
        copyTopLeftArgb(rgba,1,2,pixels);
        assertEquals(0x320c0000,pixels[1]);
    }
    @Test public void reusableReadbackRejectsShortDestinationAndTruncatedBuffer() {
        for(int[] sizes:new int[][]{{8,1},{4,2}}) {
            try { copyTopLeftArgb(ByteBuffer.allocate(sizes[0]),1,2,new int[sizes[1]]); fail("Short capture accepted"); }
            catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void clocksRemainRationalFor12000FramesAtEachSupportedFps() throws Exception {
        for(int fps:new int[]{24,30,60}) {
            long durationUs=0,durationMs=0;
            for(int frame=0;frame<12000;frame++) {
                JSONObject clock=clockMetadata(frame,fps);
                assertEquals("rational-authored-v1",clock.getString("frameClock"));
                assertEquals(frame,clock.getInt("frameIndex"));
                assertEquals(fps,clock.getInt("fps"));
                assertEquals(NativeFrameClock.presentationTimeUs(frame,fps),clock.getLong("presentationTimeUs"));
                durationUs+=clock.getLong("frameDurationUs");
                durationMs+=NativeFrameClock.frameDurationMs(frame,fps);
            }
            assertEquals(12000L*1000000/fps,durationUs);
            assertEquals(NativeFrameClock.durationMs(12000,fps),durationMs);
        }
    }
    @Test public void generationDirectoryCannotReuseOrTraverseAcceptedFrames() throws Exception {
        String project="11111111-1111-1111-1111-111111111111";
        String generation="22222222-2222-2222-2222-222222222222";
        File accepted=createGenerationDirectory(RuntimeEnvironment.getApplication(),project,generation);
        assertTrue(accepted.isDirectory());
        try { createGenerationDirectory(RuntimeEnvironment.getApplication(),project,generation); fail("Accepted generation overwritten"); }
        catch(IllegalStateException expected) { }
        for(String unsafe:new String[]{"../outside","/tmp/outside","a/b",""}) {
            try { createGenerationDirectory(RuntimeEnvironment.getApplication(),unsafe,generation); fail("Unsafe project accepted"); }
            catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void outputAdmissionRejectsSourceReuseAndExistingMedia() {
        ProjectStore.Project source=new ProjectStore.Project(); source.id="source";
        ProjectStore.Project output=new ProjectStore.Project(); output.id="output";
        requireNewOutput(source,output);
        try { requireNewOutput(source,source); fail("Source overwritten"); }
        catch(IllegalArgumentException expected) { }
        output.assets.add(new ProjectStore.Asset());
        try { requireNewOutput(source,output); fail("Accepted media overwritten"); }
        catch(IllegalArgumentException expected) { }
    }
}
