package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Full native raster/provenance tests, not video encoding or artistic acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchStillExportTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void pngSavesOnlyTheChosenFullResolutionFrameWithExactNativeAlphaAndNoVideo()throws Exception{
        Fixture f=new Fixture();String source=f.document().toString();Bitmap expected=f.render(1);
        SketchNativeExport.Prepared png=f.prepare();
        try{
            assertTrue(png.completed);assertTrue(png.file.getName().endsWith(".png"));assertEquals(1,png.project.assets.size());assertTrue(png.project.clips.isEmpty());
            ProjectStore.Asset asset=png.project.asset(png.metadata.getString("assetId"));assertNotNull(asset);
            assertEquals("image/png",asset.mime);assertTrue(asset.generated);assertEquals(Uri.fromFile(png.file).toString(),asset.uri);
            assertEquals(518,png.metadata.getInt("width"));assertEquals(748,png.metadata.getInt("height"));assertEquals(1,png.metadata.getInt("frame"));
            assertEquals("png",png.metadata.getString("format"));assertEquals("image/png",png.metadata.getString("mime"));
            assertEquals(png.project.id,png.metadata.getString("outputProjectId"));assertEquals(png.project.id,png.metadata.getString("projectId"));
            assertEquals(f.document().getLong("revision"),png.metadata.getLong("sourceRevision"));
            assertEquals(png.file.length(),png.metadata.getLong("bytes"));assertEquals(SketchProjectStore.fileHash(png.file),png.metadata.getString("sha256"));
            assertEquals(png.metadata.getString("sha256"),png.metadata.getString("outputSha256"));assertFalse(png.metadata.optBoolean("encoded",false));
            assertFalse(new File(f.generation(),"sketch.mp4").exists());assertFalse(new File(f.generation(),"sketch.encoding.mp4").exists());
            Bitmap actual=BitmapFactory.decodeFile(png.file.getPath());assertNotNull(actual);
            try{assertPixels(expected,actual);assertEquals(0,actual.getPixel(0,0));assertEquals(128,actual.getPixel(150,250)>>>24);}finally{actual.recycle();}
            assertEquals(source,f.document().toString());
        }finally{expected.recycle();}
    }

    @Test public void outputIsSeparateAndNeverChangesTheActiveProjectItsLanesOrMasterClock()throws Exception{
        Fixture f=new Fixture();ProjectStore projects=new ProjectStore(context);ProjectStore.Project active=projects.create("Earlier authored edit");
        ProjectStore.Asset source=new ProjectStore.Asset();source.id="keep-media";source.mime="image/png";source.uri=Uri.fromFile(f.input()).toString();active.assets.add(source);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="keep-clip";clip.assetId=source.id;clip.inMs=0;clip.outMs=8000;active.clips.add(clip);
        StudioLayer title=new StudioLayer();title.text="Preserve my title";title.startMs=500;title.durationMs=1000;active.studioLayers.add(title);projects.save(active);
        String before=projects.get(active.id).toJson().toString();SketchNativeExport.Prepared png=f.prepare();
        assertNotEquals(active.id,png.project.id);assertEquals(active.id,projects.active().id);assertEquals(before,projects.get(active.id).toJson().toString());
        assertEquals(8000,projects.get(active.id).outputDurationMs());assertTrue(png.project.clips.isEmpty());
    }

    @Test public void strictPngArgumentsRejectBeforePublishingAnyAsset()throws Exception{
        Fixture f=new Fixture();ProjectStore projects=new ProjectStore(context);int before=projects.list().size();
        JSONArray invalid=new JSONArray();
        for(Object value:new Object[]{JSONObject.NULL,"mp4","PNG",1,true})invalid.put(f.args().put("format",value));
        JSONObject missingFrame=f.args();missingFrame.remove("frame");invalid.put(missingFrame);
        for(Object value:new Object[]{JSONObject.NULL,"1",true,-1,.5,3})invalid.put(f.args().put("frame",value));
        for(String field:new String[]{"aspect","quality","bookId","crop","targetProjectId","unknown"})invalid.put(f.args().put(field,"unexpected"));
        JSONObject noFormat=f.args();noFormat.remove("format");invalid.put(noFormat);
        for(int i=0;i<invalid.length();i++){
            JSONObject request=invalid.getJSONObject(i);request.put("operationId","invalid_"+i);
            assertThrows("Invalid PNG request "+request,Exception.class,()->SketchNativeExport.prepare(context,request));
            assertEquals(before,projects.list().size());
        }
    }

    @Test public void sourceRevisionMustMatchBeforeTheFirstFrozenCapture()throws Exception{
        Fixture f=new Fixture();JSONObject stale=f.args();f.recolor();String changed=f.document().toString();
        assertThrows(Exception.class,()->SketchNativeExport.prepare(context,stale));assertFalse(new File(f.generation(),"frame_0001.png").exists());
        assertEquals(changed,f.document().toString());
    }

    @Test public void committedReplayRetainsLaterHumanProjectEditsAndDoesNotAddAnotherAsset()throws Exception{
        Fixture f=new Fixture();SketchNativeExport.Prepared first=f.prepare();ProjectStore projects=new ProjectStore(context);
        ProjectStore.Project human=projects.get(first.project.id);human.name="My reviewed frame";projects.save(human);
        long revision=human.updatedAt;String fileHash=SketchProjectStore.fileHash(first.file);
        SketchNativeExport.Prepared replay=f.prepare();assertTrue(replay.metadata.getBoolean("replayed"));
        assertEquals(first.project.id,replay.project.id);assertEquals(1,replay.project.assets.size());assertTrue(replay.project.clips.isEmpty());
        assertEquals("My reviewed frame",replay.project.name);assertEquals(revision,replay.project.updatedAt);assertEquals(fileHash,SketchProjectStore.fileHash(replay.file));
    }

    @Test public void deletedProjectOrRemovedAssetIsNotRecreatedByReceiptReplay()throws Exception{
        for(boolean removeProject:new boolean[]{false,true}){
            Fixture f=new Fixture();SketchNativeExport.Prepared first=f.prepare();ProjectStore projects=new ProjectStore(context);
            if(removeProject)projects.delete(first.project.id);else{ProjectStore.Project human=projects.get(first.project.id);human.assets.clear();projects.save(human);}
            assertThrows("Respect later explicit removal",Exception.class,f::prepare);
            if(removeProject)assertNull(projects.get(first.project.id));else assertTrue(projects.get(first.project.id).assets.isEmpty());
            assertTrue("Published image is retained as evidence",first.file.isFile());
        }
    }

    @Test public void changedRequestOrPublishedImageRejectsWithoutSilentlyRepairingIt()throws Exception{
        Fixture f=new Fixture();SketchNativeExport.Prepared first=f.prepare();String hash=SketchProjectStore.fileHash(first.file);
        assertThrows(Exception.class,()->SketchNativeExport.prepare(context,f.args().put("frame",2)));assertEquals(hash,SketchProjectStore.fileHash(first.file));
        Files.write(first.file.toPath(),new byte[]{1,2,3});assertThrows(Exception.class,f::prepare);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(first.file.toPath()));
        assertEquals(1,new ProjectStore(context).get(first.project.id).assets.size());
    }

    @Test public void cancellationBeforePublicationCreatesNoOutputAndIdenticalRetrySucceeds()throws Exception{
        Fixture f=new Fixture();Thread.currentThread().interrupt();
        try{assertThrows(InterruptedException.class,f::prepare);}finally{Thread.interrupted();}
        assertNull(new ProjectStore(context).get(f.outputId()));
        assertTrue(f.prepare().completed);
    }

    @Test public void cancellationAfterPngPublicationKeepsItsBytesAndFrozenSourceForRetry()throws Exception{
        Fixture f=new Fixture();Bitmap expected=f.render(1);AtomicBoolean captured=new AtomicBoolean();
        assertThrows(InterruptedException.class,()->SketchNativeExport.prepare(context,f.args(),(done,total)->{
            File output=new File(f.generation(),"frame_0001.png");
            if(output.isFile()){captured.set(true);throw new InterruptedException("Cancel before committing generated asset");}
        }));
        assertTrue("Cancellation must be observable between PNG publication and asset commit",captured.get());
        File published=new File(f.generation(),"frame_0001.png");String hash=SketchProjectStore.fileHash(published);
        assertNull(new ProjectStore(context).get(f.outputId()));f.recolor();String human=f.document().toString();
        SketchNativeExport.Prepared resumed=f.prepare();assertEquals(hash,SketchProjectStore.fileHash(resumed.file));
        Bitmap actual=BitmapFactory.decodeFile(resumed.file.getPath());try{assertPixels(expected,actual);}finally{expected.recycle();actual.recycle();}
        assertEquals("Retry uses its frozen source without undoing later drawing edits",human,f.document().toString());assertEquals(1,resumed.project.assets.size());
    }

    @Test public void pngAndLegacyMp4CannotReuseOneOperationWithDifferentMeaning()throws Exception{
        Fixture png=new Fixture();SketchNativeExport.Prepared still=png.prepare();String hash=SketchProjectStore.fileHash(still.file);
        JSONObject video=png.args();video.remove("format");video.remove("frame");
        assertThrows(Exception.class,()->SketchNativeExport.prepare(context,video));assertEquals(hash,SketchProjectStore.fileHash(still.file));
        Fixture mp4=new Fixture();JSONObject original=mp4.args();original.remove("format");original.remove("frame");
        SketchNativeExport.Prepared legacy=SketchNativeExport.prepare(context,original);String project=new ProjectStore(context).get(legacy.project.id).toJson().toString();
        assertThrows(Exception.class,mp4::prepare);assertEquals(project,new ProjectStore(context).get(legacy.project.id).toJson().toString());
    }

    @Test public void explicitPngOutputFeedsExistingIndependentProjectAssetImportWithDestinationCas()throws Exception{
        Fixture f=new Fixture();SketchNativeExport.Prepared png=f.prepare();ProjectStore projects=new ProjectStore(context);
        ProjectStore.Project destination=projects.create("Use as an image layer");String sourceId=png.metadata.getString("assetId");
        JSONObject request=new JSONObject().put("projectId",destination.id).put("expectedUpdatedAt",destination.updatedAt)
                .put("sourceProjectId",png.project.id).put("sourceProjectUpdatedAt",projects.get(png.project.id).updatedAt)
                .put("assetId",sourceId).put("expectedSha256",png.metadata.getString("sha256")).put("operationId","owned_png_copy").put("appendToTimeline",false);
        JSONObject copied=ProjectAssetImport.execute(context,request);assertEquals("image/png",copied.getString("mime"));
        ProjectStore.Project output=projects.get(destination.id);assertEquals(1,output.assets.size());assertTrue(output.clips.isEmpty());
        assertNotEquals(png.project.assets.get(0).uri,output.assets.get(0).uri);
        assertEquals(png.metadata.getString("sha256"),ProjectAssetImport.inspect(context,new JSONObject().put("projectId",output.id)
                .put("expectedUpdatedAt",output.updatedAt).put("assetId",copied.getString("assetId"))).getString("sha256"));
        assertTrue(ProjectAssetImport.execute(context,request).getBoolean("replayed"));assertEquals(1,projects.get(destination.id).assets.size());
    }

    @Test public void omittedFormatRetainsLegacyRequestFingerprintAndFullSequence()throws Exception{
        Fixture f=new Fixture("legacy_png_dispatch");JSONObject request=new JSONObject().put("sketchId","legacy_png_dispatch").put("expectedRevision",2)
                .put("operationId","old_mp4").put("aspect","original").put("quality","720p");
        SketchNativeExport.Prepared legacy=SketchNativeExport.prepare(context,request);
        assertFalse(legacy.completed);assertEquals(3,legacy.project.clips.size());assertEquals(3,legacy.project.assets.size());
        JSONObject snapshot=new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(),"sketch_studio/legacy_png_dispatch/exports/old_mp4/snapshot.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("ddffb653c09e5def78bb3972c74090c903c75f8a271a0b213b026cbb2e075131",snapshot.getString("requestSha256"));
        assertFalse(snapshot.getJSONObject("document").has("format"));
    }

    private final class Fixture {
        final String id,operation=UUID.randomUUID().toString();long revision;private File input;
        Fixture()throws Exception{this(UUID.randomUUID().toString());}
        Fixture(String id)throws Exception{
            this.id=id;SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create")
                    .put("width",518).put("height",748).put("frameCount",3).put("fps",24).put("background","#00000000"));
            JSONArray operations=new JSONArray();String[] colors={"#FFFF0000","#800000FF","#FF00FF00"};
            for(int frame=0;frame<3;frame++)operations.put(new JSONObject().put("type","add_object").put("layerId","layer_1")
                    .put("object",new JSONObject().put("id","frame_"+frame).put("kind","rect").put("frame",frame).put("exposure",1)
                            .put("color",colors[frame]).put("fill",true).put("bounds",bounds(.125,.125,.5,.5))));
            SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","marks").put("operations",operations));
            revision=document().getLong("revision");
        }
        JSONObject args()throws Exception{return new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",operation).put("format","png").put("frame",1);}
        SketchNativeExport.Prepared prepare()throws Exception{return SketchNativeExport.prepare(context,args());}
        JSONObject document()throws Exception{try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
        Bitmap render(int frame)throws Exception{try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document())){return renderer.render(frame);}}
        File generation(){return new File(context.getFilesDir(),"sketch_studio/"+id+"/exports/"+operation);}
        String outputId(){return UUID.nameUUIDFromBytes(("sketch-output/"+id+"/"+operation).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
        void recolor()throws Exception{JSONObject current=document();SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",current.getLong("revision"))
                .put("operationId",UUID.randomUUID().toString()).put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","frame_1")
                        .put("patch",new JSONObject().put("color","#FFFF00FF")))));}
        File input()throws Exception{
            if(input==null){input=new File(context.getFilesDir(),UUID.randomUUID()+".png");Bitmap bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff112233);
                try(FileOutputStream out=new FileOutputStream(input)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}}
            return input;
        }
    }
    private static JSONObject bounds(double x,double y,double width,double height)throws Exception{return new JSONObject().put("x",x).put("y",y).put("width",width).put("height",height);}
    private static void assertPixels(Bitmap expected,Bitmap actual){
        assertEquals(expected.getWidth(),actual.getWidth());assertEquals(expected.getHeight(),actual.getHeight());
        int width=expected.getWidth(),height=expected.getHeight();int[] a=new int[width],b=new int[width];
        for(int y=0;y<height;y++){expected.getPixels(a,0,width,0,y,width,1);actual.getPixels(b,0,width,0,y,width,1);assertArrayEquals("Native RGBA row"+y,a,b);}
    }
}
