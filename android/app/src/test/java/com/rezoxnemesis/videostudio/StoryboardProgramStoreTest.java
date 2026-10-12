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
import java.util.UUID;
import static org.junit.Assert.*;

/** Synthetic persistence/provenance regressions, never private-anchor cinematic evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StoryboardProgramStoreTest {
    @Test public void repeatedPairsKeepIndependentShotContactsAndBeats()throws Exception{
        Fixture f=new Fixture();JSONObject saved=f.programs.save(f.project.id,f.project.updatedAt,"",f.draft());
        JSONObject loaded=f.programs.load(f.project.id,saved.getString("programId"));JSONArray contacts=loaded.getJSONArray("contactPlans");
        assertEquals(3,contacts.length());assertEquals(0,contacts.getJSONObject(0).getInt("shotIndex"));assertEquals(2,contacts.getJSONObject(2).getInt("shotIndex"));
        assertEquals(.25,contacts.getJSONObject(0).getJSONObject("contactPlan").getJSONArray("black").getJSONObject(0).getDouble("start"),0);
        assertEquals(.55,contacts.getJSONObject(2).getJSONObject("contactPlan").getJSONArray("black").getJSONObject(0).getDouble("start"),0);
        assertEquals("BLACK_STEP",loaded.getJSONArray("choreography").getJSONObject(0).getString("beat"));assertEquals("CLASH",loaded.getJSONArray("choreography").getJSONObject(2).getString("beat"));
        assertEquals(saved.getString("sha256"),loaded.getString("sha256"));assertFalse(loaded.getBoolean("cinematicApproved"));assertFalse(loaded.getBoolean("annotationComplete"));
    }
    @Test public void staleProgramDigestCannotOverwriteSavedShotDraft()throws Exception{
        Fixture f=new Fixture();JSONObject first=f.programs.save(f.project.id,f.project.updatedAt,"",f.draft());JSONObject secondDraft=new JSONObject(first.toString()).put("name","Reviewed revision two");
        JSONObject second=f.programs.save(f.project.id,f.project.updatedAt,first.getString("sha256"),secondDraft);
        try{f.programs.save(f.project.id,f.project.updatedAt,first.getString("sha256"),first);fail("Stale digest must fail closed");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SOURCE_PROGRAM_CHANGED"));}
        assertEquals(second.getString("sha256"),f.programs.load(f.project.id,first.getString("programId")).getString("sha256"));
    }
    @Test public void externalOriginalMutationInvalidatesSavedProgramWithoutProjectRevisionChange()throws Exception{
        Fixture f=new Fixture();JSONObject saved=f.programs.save(f.project.id,f.project.updatedAt,"",f.draft());f.write(f.files[0],Color.MAGENTA);
        assertEquals(f.project.updatedAt,f.projects.get(f.project.id).updatedAt);
        try{f.programs.load(f.project.id,saved.getString("programId"));fail("Saved source bytes must be checked");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SOURCE_IMAGE_CHANGED"));}
    }
    @Test public void staleSourceRevisionCannotPublishNewProgram()throws Exception{
        Fixture f=new Fixture();long accepted=f.project.updatedAt;f.project.name="Owner renamed source project";f.projects.save(f.project);
        try{f.programs.save(f.project.id,accepted,"",f.draft());fail("Source revision must guard publication");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SOURCE_PROJECT_CHANGED"));}
        assertEquals(0,f.programs.list(f.project.id).length());
    }
    @Test public void sameAspectDifferentResolutionRetainsEachShotsFromSourceGeometry()throws Exception{
        Fixture f=new Fixture(200);JSONObject saved=f.programs.save(f.project.id,f.project.updatedAt,"",f.draft());JSONArray plans=f.programs.load(f.project.id,saved.getString("programId")).getJSONArray("contactPlans");
        assertEquals(100,plans.getJSONObject(0).getInt("sourceWidth"));assertEquals(200,plans.getJSONObject(1).getInt("sourceWidth"));assertEquals(100,plans.getJSONObject(2).getInt("sourceWidth"));
        assertEquals(200,plans.getJSONObject(1).getJSONObject("bindingProof").getInt("sourceHeight"));
    }
    @Test public void changedRigCanReopenForEditWhileStrictLoadStillRejectsItsOldProof()throws Exception{
        Fixture f=new Fixture();JSONObject saved=f.programs.save(f.project.id,f.project.updatedAt,"",f.draft());JSONObject binding=f.bindings.getJSONObject(f.assets[0]);String rigId=binding.getString("blackRigId");Rig2DProfile rig=f.rigs.loadVerified(f.project.id,rigId,binding.getString("blackRigSha256"));JSONObject changed=rig.toJson();changed.getJSONArray("joints").getJSONArray(Rig2DProfile.PELVIS).put(0,.43);JSONObject currentBinding=f.rigs.saveWithBinding(Rig2DProfile.fromJson(changed),f.project.updatedAt,binding.getString("blackRigSha256"));
        try{f.programs.load(f.project.id,saved.getString("programId"));fail("Render/recovery strict load must reject changed profile");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SOURCE_RIG_CHANGED"));}
        JSONObject editable=f.programs.readForEdit(f.project.id,saved.getString("programId"));assertTrue(editable.getBoolean("editOnly"));assertTrue(editable.getBoolean("stale"));assertFalse(editable.getBoolean("renderReady"));assertFalse(editable.getBoolean("sourceBytesVerified"));assertEquals(saved.getString("sha256"),editable.getString("sha256"));assertTrue(editable.getJSONArray("staleWarnings").length()>0);
        JSONObject selected=editable.getJSONObject("sourceRigBindings").getJSONObject(f.assets[0]);selected.put("blackRigSha256",currentBinding.getString("sha256"));
        try{f.programs.save(f.project.id,f.project.updatedAt,saved.getString("sha256"),editable);fail("Selecting current profile cannot silently accept old contact proof");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SOURCE_RIG_CHANGED"));}
        JSONArray plans=editable.getJSONArray("contactPlans");for(int i=0;i<plans.length();i++){JSONObject plan=plans.getJSONObject(i);plan.remove("bindingProof");plan.getJSONObject("contactPlan").put("black",new JSONArray());}
        JSONObject rebound=f.programs.save(f.project.id,f.project.updatedAt,saved.getString("sha256"),editable);assertNotEquals(saved.getString("sha256"),rebound.getString("sha256"));assertEquals(currentBinding.getString("sha256"),f.programs.load(f.project.id,rebound.getString("programId")).getJSONObject("sourceRigBindings").getJSONObject(f.assets[0]).getString("blackRigSha256"));assertFalse(rebound.getBoolean("annotationComplete"));
    }
    @Test public void missingRegisteredSourceRetainsEditOnlyOrderAndProofWhileStrictPathsReject()throws Exception{
        Fixture f=new Fixture();JSONObject saved=f.programs.save(f.project.id,f.project.updatedAt,"",f.draft());String proof=saved.getJSONArray("contactPlans").getJSONObject(0).getJSONObject("bindingProof").toString();f.project.assets.removeIf(asset->f.assets[1].equals(asset.id));f.projects.save(f.project);
        JSONObject editable=f.programs.readForEdit(f.project.id,saved.getString("programId"));assertTrue(editable.getBoolean("editOnly"));assertTrue(editable.getBoolean("stale"));assertFalse(editable.getBoolean("renderReady"));assertEquals(saved.getString("sha256"),editable.getString("sha256"));assertEquals(f.assets[1],editable.getJSONArray("sourceAssetIds").getString(1));assertEquals(f.assets[1],editable.getJSONArray("sourceAssetIds").getString(3));assertEquals(proof,editable.getJSONArray("contactPlans").getJSONObject(0).getJSONObject("bindingProof").toString());assertTrue(editable.getJSONArray("staleWarnings").toString().contains("remove or explicitly replace"));
        try{f.programs.load(f.project.id,saved.getString("programId"));fail("Strict render/recovery must reject missing registration");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("registered image"));}
        try{f.programs.save(f.project.id,f.project.updatedAt,saved.getString("sha256"),editable);fail("Saving unrepaired missing occurrence must reject");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("registered image"));}
        assertEquals(saved.getString("sha256"),f.programs.readForEdit(f.project.id,saved.getString("programId")).getString("sha256"));
    }
    private static final class Fixture {
        final Context context=RuntimeEnvironment.getApplication();final ProjectStore projects=new ProjectStore(context);final Rig2DProjectStore rigs=new Rig2DProjectStore(context,projects);final StoryboardProgramStore programs=new StoryboardProgramStore(context,projects);
        final ProjectStore.Project project=projects.create("Synthetic repeated source pairs");final String[] assets=new String[2];final File[] files=new File[2];final JSONObject bindings=new JSONObject(),dimensions=new JSONObject();
        Fixture()throws Exception{this(100);}
        Fixture(int secondSide)throws Exception{
            for(int i=0;i<2;i++){ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();assets[i]=asset.id;asset.mime="image/png";asset.name="synthetic-source-"+i;files[i]=new File(context.getFilesDir(),UUID.randomUUID()+".png");write(files[i],i==0?Color.DKGRAY:Color.GRAY,i==0?100:secondSide);asset.uri=Uri.fromFile(files[i]).toString();project.assets.add(asset);}projects.save(project);
            for(int i=0;i<2;i++){JSONObject row=new JSONObject(),source=StoryboardSourceManifest.captureSource(context,project.asset(assets[i]));dimensions.put(assets[i],source);String sourceHash=source.getString("sourceSha256");for(boolean black:new boolean[]{true,false}){Rig2DProfile rig=Rig2DProfile.create(project.id,assets[i],black,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline(),new float[]{.82f,.35f});rig=Rig2DProfile.fromJson(rig.toJson().put("sourceSha256",sourceHash));JSONObject receipt=rigs.saveWithBinding(rig,project.updatedAt,"");String role=black?"black":"white";row.put(role+"RigId",rig.id).put(role+"RigSha256",receipt.getString("sha256"));}bindings.put(assets[i],row);}
        }
        void write(File file,int color)throws Exception{write(file,color,100);}
        void write(File file,int color,int side)throws Exception{Bitmap bitmap=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}}
        JSONObject draft()throws Exception{JSONArray order=new JSONArray().put(assets[0]).put(assets[1]).put(assets[0]).put(assets[1]),contacts=new JSONArray(),choreography=new JSONArray();for(int i=0;i<3;i++){JSONObject source=dimensions.getJSONObject(order.getString(i)),plant=new JSONObject().put("foot","leadingFoot").put("start",i==2?.55:.25).put("end",i==2?.8:.45).put("anchor",new JSONArray().put(.4).put(.8));contacts.put(new JSONObject().put("shotIndex",i).put("fromAssetId",order.getString(i)).put("toAssetId",order.getString(i+1)).put("sourceWidth",source.getInt("orientedWidth")).put("sourceHeight",source.getInt("orientedHeight")).put("contactPlan",new JSONObject().put("black",new JSONArray().put(plant)).put("white",new JSONArray())));choreography.put(new JSONObject().put("shotIndex",i).put("fromAssetId",order.getString(i)).put("toAssetId",order.getString(i+1)).put("beat",i==2?"CLASH":"BLACK_STEP"));}return new JSONObject().put("name","Repeated actor beats").put("sourceAssetIds",order).put("sourceRigBindings",bindings).put("contactPlans",contacts).put("choreography",choreography);}
    }
}
