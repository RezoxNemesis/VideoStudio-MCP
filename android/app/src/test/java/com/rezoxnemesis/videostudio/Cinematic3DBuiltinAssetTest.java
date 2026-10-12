package com.rezoxnemesis.videostudio;
import android.content.Context;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
/** Install the actual generated APK asset, rather than a synthetic GLB fixture. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class Cinematic3DBuiltinAssetTest {
 @Test public void realExtendedPackFitsBoundedPhoneSceneAndReplaysNewActionsWithOriginalCombat()throws Exception{
  Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);ProjectStore.Project source=store.create("Extended offline action scene");
  JSONObject installed=NativeCinematicBundleStore.installBuiltin(context,store,new JSONObject().put("projectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt).put("pack","extended"));
  assertTrue(installed.getBoolean("artifactIntegrityVerified"));assertFalse(installed.getBoolean("nativeExecutionVerified"));
  JSONObject bundle=NativeCinematicBundleStore.get(context,store,source.id,installed.getString("bundleId"));
  assertEquals(21,bundle.getJSONObject("manifest").getJSONArray("scenes").length());
  File scene=NativeCinematicBundleStore.sceneFile(context,source.id,installed.getString("bundleId"),"scene.glb");byte[] bytes=Files.readAllBytes(scene.toPath());
  int length=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(12);assertTrue(length>512*1024);assertTrue(length<=NativeCinematicBundleStore.MAX_GLB_JSON_BYTES);
  JSONObject gltf=new JSONObject(new String(bytes,20,length,StandardCharsets.UTF_8));assertEquals(2,gltf.getJSONArray("skins").length());assertEquals(21,gltf.getJSONArray("animations").length());
  JSONObject timeline=new JSONObject(bundle.getJSONObject("timeline").toString());JSONArray originals=timeline.getJSONArray("shots"),shots=new JSONArray();
  for(String action:new String[]{"WAVE","KICK","WALK","CLASH"})for(int i=0;i<originals.length();i++)if(action.equals(originals.getJSONObject(i).getString("action"))){shots.put(new JSONObject(originals.getJSONObject(i).toString()).put("id","extended_"+shots.length()).put("durationFrames",60));break;}
  timeline.put("shots",shots).put("fps",60);
  JSONObject saved=Cinematic3DProgramStore.save(context,store,new JSONObject().put("projectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt).put("bundleId",installed.getString("bundleId")).put("timeline",timeline));
  JSONObject plan=Cinematic3DProgramStore.plan(context,store,new JSONObject().put("projectId",source.id).put("programId",saved.getString("programId")).put("expectedProgramSha256",saved.getString("sha256")));
  assertEquals(240,plan.getInt("frameCount"));assertEquals("C3D_WAVE",plan.getJSONArray("scenes").getJSONObject(0).getString("animationName"));assertEquals("C3D_CLASH",plan.getJSONArray("scenes").getJSONObject(3).getString("animationName"));
  assertEquals(0,store.get(source.id).clips.size());assertEquals(source.updatedAt,store.get(source.id).updatedAt);
 }
 @Test public void actualBundledSceneInstallsWithTwoSkinsElevenClipsAndEditableActionBindings()throws Exception{
  Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);ProjectStore.Project source=store.create("Offline cinematic scene");
  JSONObject installed=NativeCinematicBundleStore.installBuiltin(context,store,new JSONObject().put("projectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt));
  assertTrue(installed.getBoolean("artifactIntegrityVerified"));assertFalse(installed.getBoolean("nativeExecutionVerified"));
  JSONObject bundle=NativeCinematicBundleStore.get(context,store,source.id,installed.getString("bundleId"));assertEquals(11,bundle.getJSONObject("manifest").getJSONArray("scenes").length());
  assertTrue(bundle.getJSONObject("manifest").getJSONObject("generator").getBoolean("actionLibrary"));
  File scene=NativeCinematicBundleStore.sceneFile(context,source.id,installed.getString("bundleId"),"scene.glb");byte[] bytes=Files.readAllBytes(scene.toPath());int length=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
  JSONObject gltf=new JSONObject(new String(bytes,20,length,StandardCharsets.UTF_8));assertEquals(2,gltf.getJSONArray("skins").length());assertEquals(11,gltf.getJSONArray("animations").length());assertTrue(length<=512*1024);
  JSONObject timeline=new JSONObject(bundle.getJSONObject("timeline").toString());JSONArray original=timeline.getJSONArray("shots"),ordered=new JSONArray();
  for(String action:new String[]{"CHARGE","CLASH","RECOVERY","CLASH"})for(int i=0;i<original.length();i++)if(action.equals(original.getJSONObject(i).getString("action"))){ordered.put(new JSONObject(original.getJSONObject(i).toString()).put("id","human_"+ordered.length()).put("durationFrames",60));break;}
  timeline.put("shots",ordered).put("fps",60);
  JSONObject program=Cinematic3DProgramStore.save(context,store,new JSONObject().put("projectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt).put("bundleId",installed.getString("bundleId")).put("timeline",timeline));
  JSONObject plan=Cinematic3DProgramStore.plan(context,store,new JSONObject().put("projectId",source.id).put("programId",program.getString("programId")).put("expectedProgramSha256",program.getString("sha256")));
  assertEquals(240,plan.getInt("frameCount"));assertEquals(60,plan.getInt("fps"));assertEquals(4,plan.getJSONArray("scenes").length());assertEquals("C3D_CLASH",plan.getJSONArray("scenes").getJSONObject(3).getString("animationName"));assertEquals(0,store.get(source.id).clips.size());
 }
}
