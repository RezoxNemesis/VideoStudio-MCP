package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeSceneTest {
    private Context context() {return RuntimeEnvironment.getApplication();}
    private JSONObject compile(String source) throws Exception {return new VslCompiler().compile(source,"project");}
    @Test public void demoCompilesAndDeclaresExecutableNativeTarget() throws Exception {
        JSONObject scene=compile(VslCompiler.DEMO);assertTrue(scene.getBoolean("ready")); assertEquals("android_native",scene.getString("runtimeTarget"));
        assertEquals(2,scene.getJSONObject("graph").getJSONArray("objects").length()); assertEquals(4000,scene.getLong("durationMs"));
    }
    @Test public void unsupportedPoseCannotBecomeSuccessfulAnimation() throws Exception {
        JSONObject scene=compile("vsl 0.1\nscene portrait { subject A { identity = lock(image_id); pose = turn_head(18deg, 1.3s); } }");
        assertFalse(scene.getBoolean("ready"));assertEquals("image.synthesis.pose",scene.getJSONArray("missingCapabilities").getString(0));
    }
    @Test public void malformedAndUnboundedProgramsAreRejected() throws Exception {
        for(String source:new String[]{VslCompiler.DEMO.replace("4s","NaNs"), VslCompiler.DEMO.replace("4s","4000s"),VslCompiler.DEMO.replace("720x1280","9000x9000"),VslCompiler.DEMO+"}",VslCompiler.DEMO.replace("spin = 35deg","system = shell(\"touch pwned\")"),VslCompiler.DEMO.replace("cloth flag","cloth crystal")}) {
            try {compile(source);fail("Invalid VSL accepted");} catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void canonicalStateIgnoresJSONObjectInsertionOrder() throws Exception {
        assertEquals(SceneMemoryStore.canonical(new JSONObject().put("b",2).put("a",1)),SceneMemoryStore.canonical(new JSONObject().put("a",1).put("b",2)));
    }
    @Test public void revisionsSurviveReopenAndRejectStaleWriters() throws Exception {
        ProjectStore.Project project=new ProjectStore(context()).create("Revision test");SceneMemoryStore memory=new SceneMemoryStore(context());
        JSONObject first=memory.commit(project,VslCompiler.DEMO,0);JSONObject next=memory.commit(project,VslCompiler.DEMO.replace("spin = 35deg","spin = 20deg"),1);
        assertEquals(2,next.getInt("revision")); assertEquals(1,new SceneMemoryStore(context()).read(project.id,"living_world",1).getInt("revision"));
        assertEquals(1,next.getJSONObject("delta").getJSONArray("changedEntities").length()); assertEquals("flag",next.getJSONObject("delta").getJSONArray("preservedEntities").getString(0));
        try {memory.commit(project,VslCompiler.DEMO,1);fail("Stale writer committed");}catch(IllegalStateException expected) {}
        assertEquals(2,memory.read(project.id,"living_world",0).getInt("revision"));
    }
    @Test public void tamperedMemoryFailsDigestVerification() throws Exception {
        ProjectStore.Project project=new ProjectStore(context()).create("Integrity test");SceneMemoryStore memory=new SceneMemoryStore(context());JSONObject scene=memory.commit(project,VslCompiler.DEMO,0);
        File file=new File(new CreativeWorkspace(context()).projectRoot(project.id),"scenes/vsl_living_world/"+scene.getString("artifactSha256")+".json");Files.write(file.toPath(),"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {memory.read(project.id,"living_world",0);fail("Tampered memory accepted");} catch(IOException expected) {}
    }
    @Test public void sparsePixelsMatchFullRenderAndOldObjectLocationIsCleared() throws Exception {
        JSONObject scene=compile("vsl 0.1\nscene sparse { background = \"#101020\"; duration = 4s; object dot { type = circle; position = vector(0.2,0.5,0); move = vector(0.8,0.5,0); width = 0.1; color = \"#22dcff\"; } }");
        Bitmap sparse=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888),full=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);
        try(NativeSceneRenderer cached=new NativeSceneRenderer(context(),scene)) {
            cached.draw(new Canvas(sparse),0);assertEquals(Color.parseColor("#22dcff"),sparse.getPixel(32,80));
            for(double seconds:new double[]{.2,1.3,2.1,3.9,4}) {
                cached.draw(new Canvas(sparse),seconds);
                try(NativeSceneRenderer fresh=new NativeSceneRenderer(context(),scene)) {fresh.draw(new Canvas(full),seconds);assertTrue("Sparse differs at "+seconds,sparse.sameAs(full));}
            }
            assertEquals(Color.parseColor("#101020"),sparse.getPixel(32,80));assertTrue(cached.statistics().getDouble("repaintedPixelRatio")<1);
        } finally {sparse.recycle();full.recycle();}
    }
    @Test public void staticScenePreservesRasterAndCameraInvalidatesFullFrame() throws Exception {
        JSONObject scene=compile(VslCompiler.DEMO); assertEquals(new Rect(0,0,100,160),NativeSceneRenderer.dirtyRegion(scene.getJSONObject("graph"),0,1,4,100,160));
        JSONObject stationary=compile("scene still { object dot { type = circle; position = vector(0.5,0.5,0); } }");
        assertTrue(NativeSceneRenderer.dirtyRegion(stationary.getJSONObject("graph"),0,1,4,100,160).isEmpty());
    }
    @Test public void sceneReferencePinsBytesAndDetectsChangedIdentity() throws Exception {
        File image=new File(context().getFilesDir(),"identity.png");Bitmap b=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);b.eraseColor(Color.RED);
        try(FileOutputStream out=new FileOutputStream(image)) {b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
        ProjectStore store=new ProjectStore(context());ProjectStore.Project project=store.create("Identity");ProjectStore.Asset a=store.registerImportedAsset(project.id,Uri.fromFile(image),"portrait","image/png",0);project=store.get(project.id);
        SceneMemoryStore memory=new SceneMemoryStore(context());JSONObject scene=memory.commit(project,"scene face { subject A { identity = lock("+a.id+"); } }",0);memory.verifyBindings(scene,project);
        Files.write(image.toPath(),"changed".getBytes(java.nio.charset.StandardCharsets.UTF_8));try {memory.verifyBindings(scene,project);fail("Changed identity bytes accepted");}catch(IOException expected) {}
    }
    @Test public void raftRequiresExplicitPackContractAndCHWRgbRange() throws Exception {
        assertFalse(NativeRaftEngine.compatible(new JSONObject().put("backend","onnx")));
        assertFalse(NativeRaftEngine.compatible(new JSONObject().put("backend","onnx-raft-v1").put("raft",new JSONObject()).put("files",new JSONObject())));
        Bitmap b=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);b.eraseColor(Color.rgb(12,34,56));assertArrayEquals(new float[]{12,34,56},NativeRaftEngine.chw(b,1,1),0);b.recycle();
    }
    @Test public void neuralReplacementPreservesEveryPixelOutsideScope() {
        Bitmap base=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888),generated=Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888);
        base.eraseColor(Color.BLUE);generated.eraseColor(Color.RED);Bitmap repaired=NativeRegionRepair.compose(base,generated,.25,.25,.75,.75);
        try {for(int y=0;y<40;y++) for(int x=0;x<40;x++) assertEquals(x>=10&&x<30&&y>=10&&y<30?Color.RED:Color.BLUE,repaired.getPixel(x,y));assertEquals(Color.BLUE,base.getPixel(20,20));}
        finally {base.recycle();generated.recycle();repaired.recycle();}
    }
    @Test public void uncertaintyMapMarksUnavailableSemanticWorkRed() throws Exception {
        JSONObject scene=compile(VslCompiler.DEMO);JSONObject map=SceneUncertainty.describe(scene,0,2);assertFalse(map.getBoolean("confidenceCalibrated"));assertEquals(256,map.getInt("reconstructTiles"));
        scene.getJSONArray("missingCapabilities").put("image.synthesis.pose");map=SceneUncertainty.describe(scene,0,2);assertEquals(256,map.getInt("missingProviderTiles"));
    }
    @Test public void semanticNodeKeysSurviveRevisionAndTemperatureMetadata() throws Exception {
        JSONObject scene=compile(VslCompiler.DEMO);JSONObject first=NativeScenePlanner.plan(context(),scene,"720p");
        scene.put("revision",12).put("createdAt",999);
        JSONObject second=NativeScenePlanner.plan(context(),scene,"720p");
        assertEquals(first.getJSONArray("nodes").getJSONObject(0).getString("cacheKey"),second.getJSONArray("nodes").getJSONObject(0).getString("cacheKey"));
        scene.getJSONObject("graph").getJSONArray("objects").getJSONObject(0).put("spin",19);
        JSONObject changed=NativeScenePlanner.plan(context(),scene,"720p");assertNotEquals(first.getJSONArray("nodes").getJSONObject(0).getString("cacheKey"),changed.getJSONArray("nodes").getJSONObject(0).getString("cacheKey"));
    }
    @Test public void progressiveResolutionsPreserveOutputAspect() {
        assertEquals(568,NativeRenderEngine.outputHeight("9:16","320p"));assertEquals(1280,NativeRenderEngine.outputHeight("9:16","720p"));assertEquals(1080,NativeRenderEngine.outputHeight("16:9","1080p"));assertEquals(1350,NativeRenderEngine.outputHeight("4:5","1080p"));
    }
}
