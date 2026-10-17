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
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchAnimationImportTest {
    final Context context=RuntimeEnvironment.getApplication();
    final ProjectStore projects=new ProjectStore(context);
    JSONObject request(boolean unsafe)throws Exception {
        File root=new File(context.getFilesDir(),"imports/"+UUID.randomUUID());assertTrue(root.mkdirs());File zip=new File(root,"cels.zip");
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(zip))){
            int[] numbers={10,2,1},colors={Color.BLUE,Color.GREEN,Color.RED};
            for(int i=0;i<3;i++){
                out.putNextEntry(new ZipEntry((unsafe&&i==0?"../":"frames/")+"cel"+numbers[i]+".png"));Bitmap b=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);b.eraseColor(colors[i]);try{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}out.closeEntry();
            }
        }
        ProjectStore.Project project=projects.create("2D animation source");ProjectStore.Asset asset=projects.registerImportedAsset(project.id,Uri.fromFile(zip),"cels.zip","application/zip",0,zip.length());
        return new JSONObject().put("projectId",project.id).put("sourceProjectUpdatedAt",projects.get(project.id).updatedAt).put("assetId",asset.id).put("expectedSha256",SketchProjectStore.fileHash(zip)).put("bookId",UUID.randomUUID().toString()).put("operationId","import").put("frameCount",3).put("fps",24);
    }
    @Test public void numberedPngsBecomeOwnedCelsInNaturalOrderAndResumeWithoutOverwritingEdits()throws Exception {
        JSONObject args=request(false);String book=args.getString("bookId");
        try{SketchAnimationImporter.importAnimation(context,projects,args,(f,n)->{if(f==1)throw new InterruptedException("pause");});fail();}catch(InterruptedException expected){}
        String id=book+"_p0";JSONObject before;try(SketchProjectStore store=new SketchProjectStore(context)){before=store.get(id);}
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","annotation").put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","human_mark").put("kind","line").put("points",new JSONArray("[[0,0,1],[1,1,1]]"))))));
        JSONObject result=SketchAnimationImporter.importAnimation(context,projects,args,(f,n)->{});assertEquals(3,result.getInt("importedFrames"));assertFalse(result.getBoolean("originalLayersRecovered"));
        for(int i=0;i<3;i++)try(SketchProjectStore store=new SketchProjectStore(context)){
            JSONObject doc=store.get(book+"_p"+i);try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){Bitmap b=renderer.render(0);try{assertEquals(new int[]{Color.RED,Color.GREEN,Color.BLUE}[i],b.getPixel(60,190));}finally{b.recycle();}}
            if(i==0){assertEquals(2,doc.getLong("revision"));assertNotNull(SketchDocument.object(doc,"human_mark"));}
        }
        assertEquals(result.toString(),SketchAnimationImporter.importAnimation(context,projects,args,(f,n)->{}).toString());
        assertEquals(args.getString("expectedSha256"),SketchProjectStore.fileHash(new File(Uri.parse(projects.get(args.getString("projectId")).asset(args.getString("assetId")).uri).getPath())));
    }
    @Test public void unsafeZipOrChangedSourceCannotCreateOrReplaceABook()throws Exception {
        JSONObject args=request(true);try{SketchAnimationImporter.importAnimation(context,projects,args,(f,n)->{});fail();}catch(IllegalArgumentException expected){}
        try{SketchEditOps.execute(context,"sketch_book_get",new JSONObject().put("bookId",args.getString("bookId")));fail();}catch(IllegalArgumentException expected){}
        JSONObject valid=request(false);valid.put("expectedSha256",String.join("",Collections.nCopies(64,"0")));
        try{SketchAnimationImporter.importAnimation(context,projects,valid,(f,n)->{});fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SHA256"));}
    }
}
