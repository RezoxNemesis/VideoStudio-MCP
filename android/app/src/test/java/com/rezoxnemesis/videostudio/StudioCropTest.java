package com.rezoxnemesis.videostudio;
import android.content.Context;import androidx.media3.common.Effect;import org.json.*;import org.junit.Test;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class StudioCropTest {
 @Test public void normalizedCropIsTheFirstSharedNativePreviewAndExportEffect() throws Exception {
  Context c=RuntimeEnvironment.getApplication();ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="owned";clip.inMs=0;clip.outMs=3000;clip.effects=new JSONObject().put("crop",new JSONObject().put("x",.25).put("y",.25).put("width",.5).put("height",.5));Effect effect=new NativeRenderEngine(c).buildPreviewEffects(clip,"16:9","720p").get(0);assertTrue(effect instanceof StudioCropEffect);StudioCropEffect crop=(StudioCropEffect)effect;assertEquals(320,crop.configure(640,480).getWidth());assertEquals(240,crop.configure(640,480).getHeight());float[] corners={-.5f,.5f,.5f,-.5f};crop.getMatrix(0).mapPoints(corners);assertArrayEquals(new float[]{-1,1,1,-1},corners,.00001f);assertTrue(StudioToolCatalog.isSupported("Crop"));assertTrue(StudioToolCatalog.isSupported("Reframe"));
 }
 @Test public void offsetViewportMapsItsCornersAndSurvivesLayeredComposition() throws Exception {
  JSONObject bounds=new JSONObject().put("x",.1).put("y",.2).put("width",.6).put("height",.4);float[] corners={-.8f,.6f,.4f,-.2f};new StudioCropEffect(bounds).getMatrix(0).mapPoints(corners);assertArrayEquals(new float[]{-1,1,1,-1},corners,.00001f);
  ProjectStore.Project project=new ProjectStore.Project();project.id="layered_crop";ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="source";asset.uri="file:///owned/source.png";asset.mime="image/png";project.assets.add(asset);ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="crop_clip";clip.assetId=asset.id;clip.outMs=1000;clip.effects=new JSONObject().put("crop",bounds).put("animatedScene",true).put("animationSpec",new JSONObject()).put("foregroundUri",asset.uri).put("backgroundUri",asset.uri);project.clips.add(clip);
  var composition=new NativeRenderEngine(RuntimeEnvironment.getApplication()).buildPreviewComposition(project,"9:16","720p");assertEquals(2,composition.sequences.size());for(var sequence:composition.sequences)assertTrue(sequence.editedMediaItems.get(0).effects.videoEffects.get(0) instanceof StudioCropEffect);
 }
 @Test public void outOfBoundsCropIsRejectedRatherThanSilentlyClamped() throws Exception {
  try{new StudioCropEffect(new JSONObject().put("x",.8).put("y",0).put("width",.4).put("height",1));fail("Invalid source extent accepted");}catch(IllegalArgumentException expected){}
 }
}
