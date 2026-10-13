package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeSourceArtworkInspectorTest {
    @Test public void exactCropMappingShowsOriginalMaterialAndBindsSourceBytesWithoutExposingLocations()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            JSONObject request=f.inspection().put("crop",new JSONObject().put("left",.25).put("top",.25).put("right",.75).put("bottom",.75));
            JSONObject response=NativeSourceWorkbenchFixture.inspect(f,request),image=response.getJSONObject("sourceArtworkImage");
            assertEquals(f.sourceSha,response.getString("sourceSha256"));
            assertEquals(f.project.updatedAt,response.getLong("sourceProjectUpdatedAt"));
            assertEquals(256,response.getInt("orientedWidth"));assertEquals(448,response.getInt("orientedHeight"));
            JSONObject map=response.getJSONObject("previewToOriginalUv");
            assertEquals("oriented-source-uv",map.getString("coordinateSpace"));
            assertEquals(.25,map.getDouble("offsetX"),0);assertEquals(.25,map.getDouble("offsetY"),0);
            assertEquals(.5,map.getDouble("scaleX"),0);assertEquals(.5,map.getDouble("scaleY"),0);
            Bitmap bitmap=NativeSourceWorkbenchFixture.image(response,"sourceArtworkImage");
            try {
                assertEquals(256,bitmap.getWidth());assertEquals(448,bitmap.getHeight());
                assertEquals("Cropped image must show original source pixels at the inverse UV position",SourceArtworkRasterFixture.MARKER,bitmap.getPixel(77,135));
                assertEquals(bitmap.getWidth(),image.getInt("width"));assertEquals(bitmap.getHeight(),image.getInt("height"));
            }finally{bitmap.recycle();}
            assertEquals("image/png",image.getString("mimeType"));
            assertTrue(image.getString("base64").length()<=768*1024);
            assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(Base64.decode(image.getString("base64"),Base64.DEFAULT))),image.getString("sha256"));
            assertFalse(response.toString().contains(f.art.from.uri));assertFalse(response.toString().contains("file://"));
            assertEquals("Preview encoding must never replace original bytes",f.sourceSha,StoryboardSourceManifest.captureSource(f.context,f.art.from).getString("sourceSha256"));
        }
    }
    @Test public void changedOriginalBytesAndForeignSourceCannotReturnAStaleImage()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            JSONObject foreign=f.inspection().put("assetId",f.art.to.id);
            rejects(f,foreign);
            File original=new File(android.net.Uri.parse(f.art.from.uri).getPath());
            Bitmap replacement=Bitmap.createBitmap(256,448,Bitmap.Config.ARGB_8888);replacement.eraseColor(Color.BLUE);
            try(FileOutputStream out=new FileOutputStream(original)){assertTrue(replacement.compress(Bitmap.CompressFormat.PNG,100,out));}
            finally{replacement.recycle();}
            rejects(f,f.inspection());
        }
    }
    @Test public void overlaysRequireAProfileOwnedByThisSourceAndBoundToItsDisplayedBytes()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            JSONObject response=NativeSourceWorkbenchFixture.inspect(f,f.inspection().put("rigIds",new JSONArray().put(f.rig.id)));
            JSONArray overlays=response.getJSONArray("rigOverlays");assertEquals(1,overlays.length());
            assertEquals(f.rigSha,overlays.getJSONObject(0).getString("rigSha256"));
            assertEquals(16,overlays.getJSONObject(0).getJSONArray("joints").length());
            rejects(f,f.inspection().put("rigIds",new JSONArray().put("unknown-rig")));
        }
    }
    @Test public void malformedOrOutOfRangeCropAndImageBudgetAreRejected()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            rejects(f,f.inspection().put("maxDimension",1025));rejects(f,f.inspection().put("maxDimension",127));
            rejects(f,f.inspection().put("crop",new JSONObject().put("left",-.1).put("top",0).put("right",1).put("bottom",1)));
            rejects(f,f.inspection().put("crop",new JSONObject().put("left",.8).put("top",0).put("right",.2).put("bottom",1)));
            rejects(f,f.inspection().put("crop",new JSONObject().put("left","0").put("top",0).put("right",1).put("bottom",1)));
        }
    }
    @Test public void exifClockwiseCropUsesOrientedOriginalUvRatherThanCodedJpegUv()throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();ProjectStore projects=new ProjectStore(context);
        ProjectStore.Project project=projects.create("Oriented original fixture");
        File file=new File(context.getFilesDir(),"workbench-exif-"+java.util.UUID.randomUUID()+".jpg");
        Bitmap original=Bitmap.createBitmap(256,448,Bitmap.Config.ARGB_8888);
        for(int y=0;y<448;y++)for(int x=0;x<256;x++)original.setPixel(x,y,y<224?(x<128?Color.RED:Color.GREEN):(x<128?Color.BLUE:Color.YELLOW));
        try {
            try(FileOutputStream out=new FileOutputStream(file)){assertTrue(original.compress(Bitmap.CompressFormat.JPEG,98,out));}
            android.media.ExifInterface exif=new android.media.ExifInterface(file.getAbsolutePath());
            exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();
            ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=java.util.UUID.randomUUID().toString();
            asset.uri=android.net.Uri.fromFile(file).toString();asset.mime="image/jpeg";project.assets.add(asset);projects.save(project);
            JSONObject request=new JSONObject().put("projectId",project.id).put("assetId",asset.id).put("maxDimension",448)
                    .put("crop",new JSONObject().put("left",.5).put("top",0).put("right",1).put("bottom",.5));
            JSONObject response=NativeSourceWorkbenchFixture.invoke("NativeSourceArtworkInspector","inspect",context,projects,request);
            assertEquals(6,response.getInt("exifOrientation"));assertEquals(448,response.getInt("orientedWidth"));assertEquals(256,response.getInt("orientedHeight"));
            Bitmap image=NativeSourceWorkbenchFixture.image(response,"sourceArtworkImage");
            try {int color=image.getPixel(image.getWidth()/2,image.getHeight()/2);assertTrue("EXIF-6 top-right oriented quadrant is the coded top-left red original",Color.red(color)>220&&Color.green(color)<30&&Color.blue(color)<30);}
            finally{image.recycle();}
            assertEquals(.5,response.getJSONObject("previewToOriginalUv").getDouble("offsetX"),0);
        }finally{original.recycle();file.delete();projects.delete(project.id);}
    }
    @Test public void highEntropyOriginalFitsEncodedBudgetByResizingThePreviewOnly()throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();ProjectStore projects=new ProjectStore(context);
        ProjectStore.Project project=projects.create("Bounded original fixture");
        File file=new File(context.getFilesDir(),"workbench-noise-"+java.util.UUID.randomUUID()+".png");
        Bitmap original=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);java.util.Random random=new java.util.Random(452);
        for(int y=0;y<512;y++)for(int x=0;x<512;x++)original.setPixel(x,y,0xff000000|random.nextInt(1<<24));
        try {
            try(FileOutputStream out=new FileOutputStream(file)){assertTrue(original.compress(Bitmap.CompressFormat.PNG,100,out));}
            ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=java.util.UUID.randomUUID().toString();
            asset.uri=android.net.Uri.fromFile(file).toString();asset.mime="image/png";project.assets.add(asset);projects.save(project);
            String sourceSha=StoryboardSourceManifest.captureSource(context,asset).getString("sourceSha256");
            JSONObject response=NativeSourceWorkbenchFixture.invoke("NativeSourceArtworkInspector","inspect",context,projects,
                    new JSONObject().put("projectId",project.id).put("assetId",asset.id).put("maxDimension",512));
            JSONObject image=response.getJSONObject("sourceArtworkImage");
            assertTrue(image.getString("base64").length()<=768*1024);assertTrue(image.getInt("width")<512);
            assertEquals(image.getInt("width"),image.getInt("height"));assertEquals(512,response.getInt("orientedWidth"));
            assertEquals(sourceSha,StoryboardSourceManifest.captureSource(context,asset).getString("sourceSha256"));
            File probes=new File(context.getCacheDir(),"storyboard_source_probes");assertEquals("Bounded original probe must be released after inspection",0,probes.listFiles().length);
        }finally{original.recycle();file.delete();projects.delete(project.id);}
    }
    @Test public void existingExecuteImageKeyIsIdenticalToCanonicalArtworkAndKeepsTheWholeResultBounded()throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            JSONObject response=NativeSourceWorkbenchFixture.inspect(f,f.inspection());
            assertTrue("Successful native artwork inspection must be completed as a success by ControlService",response.optBoolean("ok",false));
            assertEquals(response.getJSONObject("sourceArtworkImage").toString(),response.getJSONObject("contactSheet").toString());
            assertTrue(response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=2*1024*1024);
        }
    }
    private static void rejects(NativeSourceWorkbenchFixture f,JSONObject request)throws Exception {
        try {NativeSourceWorkbenchFixture.inspect(f,request);fail("Unverified, foreign or invalid original inspection must fail");}
        catch(IllegalArgumentException|IllegalStateException expected){}
    }
    private static String hex(byte[] value){StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(java.util.Locale.US,"%02x",b&255));return out.toString();}
}
