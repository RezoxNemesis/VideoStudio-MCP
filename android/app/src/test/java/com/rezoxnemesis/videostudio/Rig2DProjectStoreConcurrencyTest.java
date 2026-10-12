package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class Rig2DProjectStoreConcurrencyTest {
    private static final class Fixture {
        final Context context=RuntimeEnvironment.getApplication();
        final ProjectStore projects=new ProjectStore(context);
        final ProjectStore.Project project=projects.create("Source-bound CAS");
        final Rig2DProjectStore first=new Rig2DProjectStore(context,projects);
        final Rig2DProjectStore second=new Rig2DProjectStore(context,new ProjectStore(context));
        final Rig2DProfile rig;
        Fixture() {
            ProjectStore.Asset a=new ProjectStore.Asset();a.id=java.util.UUID.randomUUID().toString();a.mime="image/png";a.uri="file:///not-opened-legacy.png";
            project.assets.add(a);projects.save(project);
            rig=Rig2DProfile.create(project.id,a.id,true,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline(),new float[]{.8f,.5f});
        }
        Rig2DProfile changed(Rig2DProfile old,float shift) {
            float[][] joints=new float[16][];for(int i=0;i<16;i++)joints[i]=old.joints[i].clone();joints[0][0]+=shift;
            return new Rig2DProfile(old.id,old.projectId,old.sourceAssetId,old.black,false,joints,old.outline,old.swordTip,
                    old.swordHilt,old.swordOutline.length==0?null:old.swordOutline,old.sourceSha256);
        }
    }
    @Test public void twoStoreInstancesCannotOverwriteFromTheSameOldDigest() throws Exception {
        Fixture f=new Fixture();long revision=f.projects.get(f.project.id).updatedAt;
        JSONObject initial=f.first.saveWithBinding(f.rig,revision,"");String sha=initial.getString("sha256");
        Rig2DProfile owner=f.changed(f.rig,.01f),remote=f.changed(f.rig,-.01f);
        JSONObject receipt=f.first.saveWithBinding(owner,revision,sha);
        try{f.second.saveWithBinding(remote,revision,sha);fail("Stale profile CAS must fail");}catch(IllegalStateException expected){}
        assertEquals(receipt.getString("sha256"),f.second.captureBinding(f.project.id,f.rig.id).getString("sha256"));
        assertEquals(owner.joints[0][0],f.second.load(f.project.id,f.rig.id).joints[0][0],0);
    }
    @Test public void exactSaveAckDoesNotAdoptAConcurrentLaterProfile() throws Exception {
        Fixture f=new Fixture();long revision=f.projects.get(f.project.id).updatedAt;
        JSONObject initial=f.first.saveWithBinding(f.rig,revision,"");Rig2DProfile owner=f.changed(f.rig,.01f);
        JSONObject ack=f.first.saveWithBinding(owner,revision,initial.getString("sha256"));
        f.second.saveWithBinding(f.changed(owner,.01f),revision,ack.getString("sha256"));
        assertEquals(Rig2DProjectStore.sha256(owner),ack.getString("sha256"));
        assertNotEquals(ack.getString("sha256"),f.first.captureBinding(f.project.id,f.rig.id).getString("sha256"));
    }
    @Test public void changedSourceProjectRejectsBeforeAnyFilePublication() throws Exception {
        Fixture f=new Fixture();long old=f.projects.get(f.project.id).updatedAt;
        f.project.name="Owner changed source";f.projects.save(f.project);
        try{f.first.saveWithBinding(f.rig,old,"");fail("Source guard must reject stale project");}catch(IllegalStateException expected){}
        File file=new File(f.context.getFilesDir(),"creative_workspace/projects/"+f.project.id+"/rigs/"+f.rig.id+".json");
        assertFalse(file.exists());
    }
    @Test public void legacySaveCannotSilentlyReplaceAnExistingProfile() throws Exception {
        Fixture f=new Fixture();f.first.save(f.rig);
        try{f.second.save(f.changed(f.rig,.01f));fail("Legacy API must be create-only");}catch(IllegalStateException expected){}
        assertEquals(Rig2DProjectStore.sha256(f.rig),f.first.captureBinding(f.project.id,f.rig.id).getString("sha256"));
    }
    @Test public void verifiedBindingRejectsAChangedActorAndCanonicalHashIgnoresJsonKeyOrder() throws Exception {
        Fixture f=new Fixture();long revision=f.projects.get(f.project.id).updatedAt;JSONObject binding=f.first.saveWithBinding(f.rig,revision,"");
        f.second.saveWithBinding(f.changed(f.rig,.01f),revision,binding.getString("sha256"));
        try{f.first.loadVerified(f.project.id,f.rig.id,binding.getString("sha256"));fail();}catch(IllegalStateException expected){}
        JSONObject a=new JSONObject().put("first",1).put("second",new JSONObject().put("y",2).put("x",1));
        JSONObject b=new JSONObject().put("second",new JSONObject().put("x",1).put("y",2)).put("first",1);
        assertEquals(Rig2DProjectStore.sha256Json(a),Rig2DProjectStore.sha256Json(b));
    }
    @Test public void changedProviderBytesCannotPromoteOldLandmarksWithoutChangingProjectRevision() throws Exception {
        Fixture f=new Fixture();File image=new File(f.context.getCacheDir(),"rig-source-"+java.util.UUID.randomUUID()+".png");
        writeImage(image,android.graphics.Color.RED);
        f.project.asset(f.rig.sourceAssetId).uri=android.net.Uri.fromFile(image).toString();f.projects.save(f.project);
        String sourceSha=StoryboardSourceManifest.captureSource(f.context,f.projects.get(f.project.id).asset(f.rig.sourceAssetId)).getString("sourceSha256");
        Rig2DProfile proven=new Rig2DProfile(f.rig.id,f.rig.projectId,f.rig.sourceAssetId,true,false,f.rig.joints,f.rig.outline,f.rig.swordTip,null,null,sourceSha);
        long revision=f.projects.get(f.project.id).updatedAt;JSONObject original=f.first.saveWithBinding(proven,revision,"");
        writeImage(image,android.graphics.Color.BLUE);
        assertEquals(revision,f.projects.get(f.project.id).updatedAt);
        try{f.second.saveWithBinding(f.changed(proven,.01f),revision,original.getString("sha256"));fail("Old source-byte proof must fail actual image verification");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SOURCE_IMAGE_CHANGED"));}
        assertEquals(original.getString("sha256"),f.first.captureBinding(f.project.id,f.rig.id).getString("sha256"));
    }
    private static void writeImage(File file,int color) throws Exception {
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));}
        finally{bitmap.recycle();}
    }
}
