package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchImageBudgetTest {
    final Context context=RuntimeEnvironment.getApplication();
    private JSONObject document()throws Exception{return SketchDocument.create(new JSONObject().put("sketchId","budget_"+UUID.randomUUID()).put("width",256).put("height",256).put("frameCount",1));}
    private JSONObject reference(String hash,int width,int height)throws Exception{return new JSONObject().put("sha256",hash).put("file",hash+".png").put("width",width).put("height",height).put("sourceProjectId","owned_project").put("sourceAssetId","owned_asset");}
    private void image(JSONObject d,String id,JSONObject ref)throws Exception{
        d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","image").put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)).put("reference",ref)));
    }
    @Test public void retainedSourceImagesPermit32ButReject33()throws Exception{
        JSONObject d=document();for(int i=0;i<32;i++)image(d,"part_"+i,reference(String.format("%064x",i),256,256));SketchDocument.validate(d);
        image(d,"part_32",reference(String.format("%064x",32),256,256));assertThrows(IllegalArgumentException.class,()->SketchDocument.validate(d));
    }
    @Test public void actualRendererPrepares32DistinctOwnedImagesAndSelectsTheTopPart()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();try{worker.submit(()->{try{
            JSONObject target=document(),art=document();File root=new File(context.getFilesDir(),"sketch_studio/"+target.getString("id")+"/references");assertTrue(root.mkdirs());
            for(int i=0;i<32;i++){
                art.getJSONArray("layers").getJSONObject(0).put("objects",new JSONArray());
                SketchEditOps.apply(art,new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","native_rect").put("kind","rect").put("fill",true).put("color",String.format("#FF%02X%02X%02X",30+i,90+i,150+i)).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))));
                File temp=File.createTempFile("native-", ".png",root);try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,art)){Bitmap b=renderer.render(0);try(FileOutputStream out=new FileOutputStream(temp)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}}
                String hash=SketchProjectStore.fileHash(temp);assertTrue(temp.renameTo(new File(root,hash+".png")));image(target,"part_"+i,reference(hash,256,256));
            }
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,target)){
                renderer.enableImageSelections();Bitmap b=renderer.render(0);try{assertEquals(0xff3d79b5,b.getPixel(128,128));}finally{b.recycle();}
                assertEquals(32,renderer.imageSelections().size());assertEquals(32L*256*256/8,renderer.preparedSelectionBytes());
                assertEquals("part_31",SketchFrameRenderer.hitTest(target,0,.5,.5,0,renderer.imageSelections()));
            }
            return null;
        }catch(Exception e){throw new RuntimeException(e);}}).get(90,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
    }
    @Test public void expandedImageCountStillRejectsSelectionMapsBeyond8MiB()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();try{worker.submit(()->{try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document())){
            renderer.enableImageSelections();Method capture=SketchFrameRenderer.class.getDeclaredMethod("captureSelection",String.class,Bitmap.class);capture.setAccessible(true);
            Bitmap full=Bitmap.createBitmap(2048,2048,Bitmap.Config.ARGB_8888);full.eraseColor(0xff80a0c0);try{
                for(int i=0;i<16;i++)capture.invoke(renderer,"source_"+i,full);
                assertEquals(8L*1024*1024,renderer.preparedSelectionBytes());
                InvocationTargetException failure=assertThrows(InvocationTargetException.class,()->capture.invoke(renderer,"overflow",full));assertTrue(failure.getCause() instanceof IllegalStateException);
                assertEquals(16,renderer.imageSelections().size());assertEquals(8L*1024*1024,renderer.preparedSelectionBytes());
            }finally{full.recycle();}return null;
        }catch(Exception e){throw new RuntimeException(e);}}).get(90,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
    }
}
