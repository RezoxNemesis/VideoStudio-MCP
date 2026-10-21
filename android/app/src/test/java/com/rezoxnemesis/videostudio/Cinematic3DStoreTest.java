package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class Cinematic3DStoreTest {
    private static JSONObject invoke(String cls,String method,Context c,ProjectStore s,JSONObject req)throws Exception {
        try{return(JSONObject)Class.forName("com.rezoxnemesis.videostudio."+cls).getMethod(method,Context.class,ProjectStore.class,JSONObject.class).invoke(null,c,s,req);}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw(Exception)e.getCause();throw e;}
    }
    @Test public void immutableBundleAndProgramCasAreIndependentOfSourceRigPrograms()throws Exception {
        Fixture f=new Fixture();JSONObject installed=f.install();assertTrue(installed.getBoolean("artifactIntegrityVerified"));assertFalse(installed.getBoolean("nativeExecutionVerified"));
        JSONObject req=f.saveRequest(installed);JSONObject first=invoke("Cinematic3DProgramStore","save",f.c,f.projects,req);
        assertTrue(first.getBoolean("ok"));assertFalse(first.getBoolean("cinematicApproved"));
        req.put("programId",first.getString("programId"));JSONObject same=invoke("Cinematic3DProgramStore","save",f.c,f.projects,req);assertEquals(first.getString("sha256"),same.getString("sha256"));
        JSONObject edited=new JSONObject(f.timeline.toString()).put("name","Edited camera");edited.getJSONArray("shots").getJSONObject(0).getJSONObject("camera").put("fovDegrees",70);
        req.put("timeline",edited).put("expectedProgramSha256",first.getString("sha256"));JSONObject next=invoke("Cinematic3DProgramStore","save",f.c,f.projects,req);assertNotEquals(first.getString("sha256"),next.getString("sha256"));
        req.put("timeline",f.timeline);try{invoke("Cinematic3DProgramStore","save",f.c,f.projects,req);fail("Stale CAS admitted");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("PROGRAM_CHANGED"));}
        JSONObject loaded=invoke("Cinematic3DProgramStore","get",f.c,f.projects,new JSONObject().put("projectId",f.project.id).put("programId",first.getString("programId")));
        assertEquals(next.getString("sha256"),loaded.getString("sha256"));assertEquals(0,new StoryboardProgramStore(f.c,f.projects).list(f.project.id).length());
    }
    @Test public void corruptHashTraversalExternalGlbAndForeignBundleAreRejectedBeforePublication()throws Exception {
        for(String failure:new String[]{"hash","traversal","external","foreign","symlink","bomb","magic","accessor"}){Fixture f=new Fixture(failure);try{f.install();fail("Malformed bundle admitted: "+failure);}catch(IllegalArgumentException expected){}}
    }
    @Test public void authoredCameraPolicyPersistsThroughRealArchiveImportAndInvalidPolicyRejects()throws Exception {
        Fixture f=new Fixture("authoredCamera");
        JSONObject installed=f.install();
        JSONObject bound=installed.getJSONObject("manifest").getJSONArray("scenes").getJSONObject(0);
        assertEquals("authored",bound.getString("cameraReplay"));
        JSONObject saved=invoke("Cinematic3DProgramStore","save",f.c,f.projects,f.saveRequest(installed));
        JSONObject changed=new JSONObject(f.timeline.toString());
        changed.getJSONArray("shots").getJSONObject(0).getJSONObject("camera").put("fovDegrees",70);
        try { invoke("Cinematic3DProgramStore","save",f.c,f.projects,f.saveRequest(installed)
                .put("programId",saved.getString("programId")).put("expectedProgramSha256",saved.getString("sha256")).put("timeline",changed));
            fail("Ignored camera edit was published"); }
        catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("BAKED_CAMERA_REBUILD_REQUIRED")); }
        assertEquals(saved.getString("sha256"),invoke("Cinematic3DProgramStore","get",f.c,f.projects,
                new JSONObject().put("projectId",f.project.id).put("programId",saved.getString("programId"))).getString("sha256"));
        try { new Fixture("invalidCamera").install(); fail("Misspelled camera replay accepted"); }
        catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Camera replay")); }
    }
    @Test public void staleSourceRevisionAndChangedInstalledSceneNeverAuthorizeRead()throws Exception {
        Fixture f=new Fixture();JSONObject receipt=f.install();long old=f.project.updatedAt;f.project.name="Source changed";f.projects.save(f.project);
        try{invoke("Cinematic3DProgramStore","save",f.c,f.projects,f.saveRequest(receipt).put("sourceProjectUpdatedAt",old));fail("Stale source admitted");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SOURCE_PROJECT_CHANGED"));}
        Fixture second=new Fixture();JSONObject bundle=second.install();JSONObject program=invoke("Cinematic3DProgramStore","save",second.c,second.projects,second.saveRequest(bundle));
        File scene=(File)Class.forName("com.rezoxnemesis.videostudio.NativeCinematicBundleStore").getMethod("sceneFile",Context.class,String.class,String.class,String.class).invoke(null,second.c,second.project.id,bundle.getString("bundleId"),"scene.glb");
        try(FileOutputStream out=new FileOutputStream(scene)){out.write(new byte[]{1,2,3});}
        try{invoke("Cinematic3DProgramStore","get",second.c,second.projects,new JSONObject().put("projectId",second.project.id).put("programId",program.getString("programId")));fail("Changed scene admitted");}catch(IllegalStateException|IllegalArgumentException expected){}
    }
    @Test public void actualOriginalByteMutationRejectsWithoutChangingRevisionOrLiteralRepeatedOrder()throws Exception {
        Fixture f=new Fixture("sources");JSONObject bundle=f.install(),saved=invoke("Cinematic3DProgramStore","save",f.c,f.projects,f.saveRequest(bundle));
        JSONArray refs=saved.getJSONObject("timeline").getJSONArray("shots").getJSONObject(0).getJSONArray("sourceAnchors");assertEquals(3,refs.length());assertEquals(refs.getJSONObject(0).getString("assetId"),refs.getJSONObject(2).getString("assetId"));assertEquals(2,saved.getJSONObject("sourceEntries").length());
        long revision=f.project.updatedAt;f.paint(f.originals[0],Color.MAGENTA);assertEquals(revision,f.projects.get(f.project.id).updatedAt);
        try{invoke("Cinematic3DProgramStore","get",f.c,f.projects,new JSONObject().put("projectId",f.project.id).put("programId",saved.getString("programId")));fail("Changed original bytes admitted");}catch(IllegalArgumentException|IllegalStateException expected){assertTrue(expected.getMessage().contains("SOURCE_IMAGE_CHANGED"));}
        JSONObject listing=invoke("Cinematic3DProgramStore","list",f.c,f.projects,new JSONObject().put("projectId",f.project.id));assertFalse(listing.getBoolean("sourceBytesVerified"));assertEquals(saved.getString("sha256"),listing.getJSONArray("programs").getJSONObject(0).getString("sha256"));
    }
    @Test public void projectRenameRequiresExplicitRevisionRebindingAndRetainsVerifiedBundle()throws Exception {
        Fixture f=new Fixture();JSONObject bundle=f.install(),saved=invoke("Cinematic3DProgramStore","save",f.c,f.projects,f.saveRequest(bundle));f.project.name="Renamed source";f.projects.save(f.project);
        try{invoke("Cinematic3DProgramStore","get",f.c,f.projects,new JSONObject().put("projectId",f.project.id).put("programId",saved.getString("programId")));fail("Old source revision admitted");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SOURCE_PROJECT_CHANGED"));}
        JSONObject editing=invoke("Cinematic3DProgramStore","readForEdit",f.c,f.projects,new JSONObject().put("projectId",f.project.id).put("programId",saved.getString("programId")).put("editOnly",true));
        assertTrue(editing.getBoolean("editOnly"));assertFalse(editing.getBoolean("sourceBytesVerified"));assertFalse(editing.getBoolean("artifactIntegrityVerified"));assertFalse(editing.getBoolean("nativeExecutionVerified"));assertFalse(editing.getBoolean("renderReady"));
        assertEquals(saved.getString("sha256"),editing.getString("sha256"));assertEquals(saved.getJSONObject("timeline").toString(),editing.getJSONObject("timeline").toString());assertEquals(f.project.updatedAt,editing.getLong("currentSourceProjectUpdatedAt"));assertNotNull(editing.getJSONObject("editorBundle"));
        JSONObject next=invoke("Cinematic3DProgramStore","save",f.c,f.projects,f.saveRequest(bundle).put("programId",saved.getString("programId")).put("expectedProgramSha256",saved.getString("sha256")));
        assertNotEquals(saved.getString("sha256"),next.getString("sha256"));assertEquals(f.project.updatedAt,next.getLong("sourceProjectUpdatedAt"));assertEquals(bundle.getString("sha256"),next.getString("bundleSha256"));
    }
    @Test public void clientReceiptTamperingAndBakedContactChangesCannotReuseVerifiedScenes()throws Exception {
        Fixture f=new Fixture();JSONObject bundle=f.install(),saved=invoke("Cinematic3DProgramStore","save",f.c,f.projects,f.saveRequest(bundle));JSONObject altered=new JSONObject(saved.toString());altered.getJSONObject("timeline").getJSONArray("shots").getJSONObject(0).getJSONObject("camera").put("fovDegrees",90);
        try{Class.forName("com.rezoxnemesis.videostudio.Cinematic3DProgramStore").getMethod("requireUnchanged",Context.class,ProjectStore.class,JSONObject.class,JSONObject.class).invoke(null,f.c,f.projects,altered,bundle);fail("Altered receipt admitted");}catch(InvocationTargetException expected){assertTrue(expected.getCause() instanceof IllegalStateException);}
        JSONObject edited=new JSONObject(f.timeline.toString());edited.getJSONArray("shots").getJSONObject(0).put("footContacts",new JSONArray().put(new JSONObject().put("actor","black").put("foot","leading").put("startPhase",.1).put("endPhase",.4).put("anchor",new JSONArray().put(0).put(0).put(0))));
        try{invoke("Cinematic3DProgramStore","save",f.c,f.projects,f.saveRequest(bundle).put("programId",saved.getString("programId")).put("expectedProgramSha256",saved.getString("sha256")).put("timeline",edited));fail("Baked contact edit admitted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("BAKED_POSE_REBUILD_REQUIRED"));}
        assertEquals(saved.getString("sha256"),invoke("Cinematic3DProgramStore","get",f.c,f.projects,new JSONObject().put("projectId",f.project.id).put("programId",saved.getString("programId"))).getString("sha256"));
    }
    @Test public void onlyExplicitActionLibraryCanBindNewShotIdentityToExactDeclaredClip()throws Exception {
        Fixture f=new Fixture("library");JSONObject bundle=f.install(),edited=new JSONObject(f.timeline.toString());edited.getJSONArray("shots").getJSONObject(0).put("id","new_clash");JSONObject saved=invoke("Cinematic3DProgramStore","save",f.c,f.projects,f.saveRequest(bundle).put("timeline",edited));
        assertEquals("new_clash",saved.getJSONArray("scenes").getJSONObject(0).getString("shotId"));assertEquals("cinematic_clash",saved.getJSONArray("scenes").getJSONObject(0).getString("animationName"));
        Fixture exact=new Fixture();JSONObject exactBundle=exact.install();try{invoke("Cinematic3DProgramStore","save",exact.c,exact.projects,exact.saveRequest(exactBundle).put("timeline",edited));fail("Implicit library fallback admitted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("BAKED_POSE_REBUILD_REQUIRED"));}
    }
    @Test public void interruptedFirstSaveDirectoryCanRetryWithoutInventingAProgramDigest()throws Exception {
        Fixture f=new Fixture();JSONObject bundle=f.install();String id="interrupted_program";File orphan=new File(f.c.getFilesDir(),"creative_workspace/projects/"+f.project.id+"/cinematic3d/programs/"+id);assertTrue(orphan.mkdirs());
        JSONObject saved=invoke("Cinematic3DProgramStore","save",f.c,f.projects,f.saveRequest(bundle).put("programId",id));assertEquals(id,saved.getString("programId"));
        JSONObject loaded=invoke("Cinematic3DProgramStore","get",f.c,f.projects,new JSONObject().put("projectId",f.project.id).put("programId",id));assertEquals(saved.getString("sha256"),loaded.getString("sha256"));
    }
    static String hash(byte[] data)throws Exception {StringBuilder b=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(data))b.append(String.format("%02x",v&255));return b.toString();}
    static byte[] glb(boolean external)throws Exception {return glb(external,false);}
    static byte[] glb(boolean external,boolean accessor)throws Exception {JSONObject j=new JSONObject().put("asset",new JSONObject().put("version","2.0")).put("scenes",new JSONArray().put(new JSONObject())).put("scene",0);if(external)j.put("buffers",new JSONArray().put(new JSONObject().put("uri","https://invalid/scene.bin").put("byteLength",4)));if(accessor)j.put("accessors",new JSONArray().put(new JSONObject().put("componentType",5126).put("type","VEC4").put("count",Integer.MAX_VALUE)));byte[] json=j.toString().getBytes(StandardCharsets.UTF_8);int padded=(json.length+3)&~3;ByteBuffer b=ByteBuffer.allocate(20+padded).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0x46546c67).putInt(2).putInt(b.capacity()).putInt(padded).putInt(0x4e4f534a).put(json);while(b.hasRemaining())b.put((byte)' ');return b.array();}
    static final class Fixture {
        final Context c=RuntimeEnvironment.getApplication();final ProjectStore projects=new ProjectStore(c);final ProjectStore.Project project=projects.create("Synthetic cinematic bundle");final JSONObject timeline=Cinematic3DTimelineTest.timeline();final ProjectStore.Asset archive=new ProjectStore.Asset();final File[] originals=new File[2];
        Fixture()throws Exception{this("");}
        Fixture(String failure)throws Exception {
            if("sources".equals(failure)){JSONArray refs=new JSONArray();JSONObject[] entries=new JSONObject[2];for(int i=0;i<2;i++){originals[i]=new File(c.getFilesDir(),UUID.randomUUID()+".png");paint(originals[i],i==0?Color.DKGRAY:Color.WHITE);ProjectStore.Asset a=new ProjectStore.Asset();a.id=UUID.randomUUID().toString();a.uri=Uri.fromFile(originals[i]).toString();a.name="Synthetic original";a.mime="image/png";a.sizeBytes=originals[i].length();project.assets.add(a);entries[i]=new JSONObject().put("assetId",a.id).put("sha256",hash(java.nio.file.Files.readAllBytes(originals[i].toPath())));}refs.put(entries[0]).put(entries[1]).put(new JSONObject(entries[0].toString()));timeline.getJSONArray("shots").getJSONObject(0).put("sourceAnchors",refs);}
            if("foreign".equals(failure))timeline.getJSONArray("shots").getJSONObject(0).put("sourceAnchors",new JSONArray().put(new JSONObject().put("assetId","foreign_source").put("sha256",new String(new char[64]).replace('\0','a'))));
            byte[] t=timeline.toString().getBytes(StandardCharsets.UTF_8),g="magic".equals(failure)?new byte[]{1,2,3}:glb("external".equals(failure),"accessor".equals(failure));String sha=hash(g);if("hash".equals(failure))sha=new String(new char[64]).replace('\0','a');
            JSONObject m=new JSONObject().put("schema","videostudio.cinematic3d.bundle.v1").put("timelineFile","timeline.json")
                .put("files",new JSONArray().put(new JSONObject().put("path","timeline.json").put("role","timeline").put("sha256",hash(t)).put("bytes",t.length)).put(new JSONObject().put("path","scene.glb").put("role","scene").put("sha256",sha).put("bytes",g.length)))
                .put("scenes",new JSONArray().put(new JSONObject().put("shotId","clash").put("action","CLASH").put("path","scene.glb").put("animationName","cinematic_clash").put("cameraName","CinematicCamera").put("clipDurationSeconds",1).put("impactPhase",.5)))
                .put("generator",new JSONObject().put("name","synthetic_test")).put("evidence",new JSONObject().put("execution","fixture"));
            if("library".equals(failure))m.getJSONObject("generator").put("actionLibrary",true);
            if("authoredCamera".equals(failure)||"invalidCamera".equals(failure))
                m.getJSONArray("scenes").getJSONObject(0).put("cameraReplay","authoredCamera".equals(failure)?"authored":"autored");
            File zip=new File(c.getFilesDir(),UUID.randomUUID()+".vscinematic");try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(zip))){entry(out,"manifest.json",m.toString().getBytes(StandardCharsets.UTF_8));entry(out,"timeline.json",t);entry(out,"scene.glb",g);if("traversal".equals(failure))entry(out,"../escape",new byte[]{1});if("bomb".equals(failure))entry(out,"bomb.bin",new byte[2*1024*1024]);}
            if("symlink".equals(failure)){byte[] bytes=java.nio.file.Files.readAllBytes(zip.toPath());ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<bytes.length-46;i++)if(b.getInt(i)==0x02014b50){b.putShort(i+4,(short)((3<<8)|20));b.putInt(i+38,0120777<<16);break;}java.nio.file.Files.write(zip.toPath(),bytes);}
            archive.id=UUID.randomUUID().toString();archive.uri=Uri.fromFile(zip).toString();archive.mime="application/zip";archive.name="Synthetic fixture";project.assets.add(archive);projects.save(project);
        }
        private static void entry(ZipOutputStream out,String name,byte[] bytes)throws Exception {out.putNextEntry(new ZipEntry(name));out.write(bytes);out.closeEntry();}
        void paint(File file,int color)throws Exception {Bitmap image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);image.eraseColor(color);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}}
        JSONObject install()throws Exception{return invoke("NativeCinematicBundleStore","install",c,projects,new JSONObject().put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("bundleAssetId",archive.id));}
        JSONObject saveRequest(JSONObject installed)throws Exception{return new JSONObject().put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("bundleId",installed.getString("bundleId")).put("timeline",timeline);}
    }
}
