package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPaintArchiveTest {
    final Context context=RuntimeEnvironment.getApplication();
    private static <T>T worker(Callable<T> action)throws Exception {ExecutorService executor=Executors.newSingleThreadExecutor();try{return executor.submit(action).get(60,TimeUnit.SECONDS);}finally{executor.shutdownNow();}}
    JSONObject create()throws Exception {
        String id=UUID.randomUUID().toString();JSONObject args=new JSONObject().put("sketchId",id).put("operationId","new").put("name","Native owned sketch").put("width",256).put("height",256).put("frameCount",1);
        SketchEditOps.execute(context,"sketch_create",args);
        SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","drawing").put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","red").put("kind","rect").put("fill",true).put("color","#FFFF0000").put("bounds",new JSONObject().put("x",.2).put("y",.2).put("width",.6).put("height",.6))))));
        try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}
    }
    JSONObject args(JSONObject d,String op)throws Exception {return new JSONObject().put("sketchId",d.getString("id")).put("expectedRevision",d.getLong("revision")).put("operationId",op);}
    File altered(File original,String path,byte[] content)throws Exception {
        LinkedHashMap<String,byte[]> entries=new LinkedHashMap<>();try(ZipFile zip=new ZipFile(original)){Enumeration<? extends ZipEntry> all=zip.entries();while(all.hasMoreElements()){ZipEntry entry=all.nextElement();try(InputStream in=zip.getInputStream(entry)){entries.put(entry.getName(),in.readAllBytes());}}}
        entries.put(path,content);File result=new File(context.getCacheDir(),UUID.randomUUID()+".vssketch");try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(result))){for(Map.Entry<String,byte[]> e:entries.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}return result;
    }
    int count()throws Exception {try(SketchProjectStore store=new SketchProjectStore(context)){return store.list().length();}}
    @Test public void archiveRestoresEditableDocumentAsNewIdentityAndKeepsOldWork()throws Exception {
        JSONObject source=create();SketchPaintArchive.Prepared prepared=SketchPaintArchive.prepare(context,args(source,"archive"));
        assertEquals(SketchPaintArchive.SCHEMA,prepared.metadata.getString("schema"));assertEquals(source.getString("id"),prepared.metadata.getString("sourceSketchId"));
        String hash=SketchProjectStore.fileHash(prepared.file);JSONObject result=SketchPaintArchive.restoreFile(context,prepared.file,hash,"restore","Restored sketch");
        assertTrue(result.getBoolean("persisted"));assertNotEquals(source.getString("id"),result.getString("sketchId"));
        try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject restored=store.get(result.getString("sketchId"));assertEquals(1,restored.getLong("revision"));assertEquals("Restored sketch",restored.getString("name"));assertEquals(SketchDocument.canonical(source.getJSONArray("layers")),SketchDocument.canonical(restored.getJSONArray("layers")));assertEquals(SketchDocument.canonical(source),SketchDocument.canonical(store.get(source.getString("id"))));}
    }
    @Test public void restoreRetryKeepsSameIdentityAndConflictingIntentRejects()throws Exception {
        JSONObject source=create();SketchPaintArchive.Prepared prepared=SketchPaintArchive.prepare(context,args(source,"archive"));String hash=SketchProjectStore.fileHash(prepared.file);
        JSONObject first=SketchPaintArchive.restoreFile(context,prepared.file,hash,"restore","Restored");int count=count();JSONObject retry=SketchPaintArchive.restoreFile(context,prepared.file,hash,"restore","Restored");assertEquals(first.getString("sketchId"),retry.getString("sketchId"));assertEquals(count,count());
        assertThrows(Exception.class,()->SketchPaintArchive.restoreFile(context,prepared.file,hash,"restore","Different name"));
    }
    @Test public void completedRestoreRetryReturnsDurableReceiptWhenTransferSourceIsGone()throws Exception {
        JSONObject source=create();SketchPaintArchive.Prepared prepared=SketchPaintArchive.prepare(context,args(source,"archive"));String hash=SketchProjectStore.fileHash(prepared.file);JSONObject first=SketchPaintArchive.restoreFile(context,prepared.file,hash,"restore_offline","Restored");int before=count();assertTrue(prepared.file.delete());JSONObject retry=SketchPaintArchive.restoreFile(context,prepared.file,hash,"restore_offline","Restored");assertEquals(first.getString("sketchId"),retry.getString("sketchId"));assertEquals(before,count());
    }
    @Test public void committedDatabaseRestoreRecoversIfCompletedReceiptPublicationWasInterrupted()throws Exception {
        JSONObject source=create();SketchPaintArchive.Prepared prepared=SketchPaintArchive.prepare(context,args(source,"archive"));String hash=SketchProjectStore.fileHash(prepared.file);JSONObject first=SketchPaintArchive.restoreFile(context,prepared.file,hash,"restore_interrupted","Restored");int before=count();assertTrue(new File(context.getFilesDir(),"sketch_archive_receipts/restore_interrupted.completed.json").delete());assertTrue(prepared.file.delete());JSONObject retry=SketchPaintArchive.restoreFile(context,prepared.file,hash,"restore_interrupted","Restored");assertEquals(first.getString("sketchId"),retry.getString("sketchId"));assertEquals(first.getString("documentSha256"),retry.getString("documentSha256"));assertEquals(before,count());assertTrue(new File(context.getFilesDir(),"sketch_archive_receipts/restore_interrupted.completed.json").isFile());
    }
    @Test public void traversalAndUnexpectedEntriesLeaveDatabaseUnchanged()throws Exception {
        JSONObject source=create();SketchPaintArchive.Prepared prepared=SketchPaintArchive.prepare(context,args(source,"archive"));int before=count();
        for(String bad:new String[]{"../outside.png","paint/tiles/extra.png","unexpected.json"}){File altered=altered(prepared.file,bad,new byte[]{1,2,3});assertThrows(Exception.class,()->SketchPaintArchive.restoreFile(context,altered,SketchProjectStore.fileHash(altered),"restore_"+UUID.randomUUID(),null));assertEquals(before,count());}
    }
    @Test public void wrongArchiveHashAndChangedDocumentProduceNoNewSketch()throws Exception {
        JSONObject source=create();SketchPaintArchive.Prepared prepared=SketchPaintArchive.prepare(context,args(source,"archive"));int before=count();assertThrows(Exception.class,()->SketchPaintArchive.restoreFile(context,prepared.file,"0".repeat(64),"restore",null));
        File altered=altered(prepared.file,"document.json",source.toString().replace("#FFFF0000","#FF0000FF").getBytes(StandardCharsets.UTF_8));assertThrows(Exception.class,()->SketchPaintArchive.restoreFile(context,altered,SketchProjectStore.fileHash(altered),"restore_other",null));assertEquals(before,count());
    }
    @Test public void deeplyNestedArchiveJsonIsRejectedBeforeRecursiveParserCanCrash()throws Exception {
        JSONObject source=create();SketchPaintArchive.Prepared prepared=SketchPaintArchive.prepare(context,args(source,"archive"));String malicious="{\"unexpected\":"+"[".repeat(10000)+"0"+"]".repeat(10000)+"}";File bad=altered(prepared.file,"archive.json",malicious.getBytes(StandardCharsets.UTF_8));int before=count();Exception rejection=assertThrows(Exception.class,()->SketchPaintArchive.restoreFile(context,bad,SketchProjectStore.fileHash(bad),"restore_depth",null));assertTrue(rejection.getMessage(),rejection.getMessage().toLowerCase(java.util.Locale.ROOT).contains("nesting"));assertEquals(before,count());
    }
    @Test public void unixSymlinkZipEntryIsRejectedWithoutPublishingDatabase()throws Exception {
        JSONObject source=create();SketchPaintArchive.Prepared prepared=SketchPaintArchive.prepare(context,args(source,"archive"));byte[] zip=Files.readAllBytes(prepared.file.toPath());boolean patched=false;
        for(int i=0;i<zip.length-46;i++)if(zip[i]==0x50&&zip[i+1]==0x4b&&zip[i+2]==1&&zip[i+3]==2){zip[i+4]=20;zip[i+5]=3;long attrs=((long)0120777)<<16;for(int k=0;k<4;k++)zip[i+38+k]=(byte)(attrs>>>(8*k));patched=true;break;}
        assertTrue(patched);File bad=new File(context.getCacheDir(),UUID.randomUUID()+".vssketch");Files.write(bad.toPath(),zip);int before=count();assertThrows(Exception.class,()->SketchPaintArchive.restoreFile(context,bad,SketchProjectStore.fileHash(bad),"restore_symlink",null));assertEquals(before,count());
    }
    @Test public void matchingHashesDoNotAllowReferencePngDimensionMismatch()throws Exception {
        JSONObject source=create();String id=source.getString("id");Bitmap image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);image.eraseColor(Color.GREEN);File root=new File(context.getFilesDir(),"sketch_studio/"+id+"/references");assertTrue(root.mkdirs());File temporary=new File(root,"input.png");try(FileOutputStream out=new FileOutputStream(temporary)){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}String hash=SketchProjectStore.fileHash(temporary);assertTrue(temporary.renameTo(new File(root,hash+".png")));
        source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","guide").put("kind","image").put("reference",new JSONObject().put("file",hash+".png").put("sha256",hash).put("width",64).put("height",64).put("sourceProjectId","fixture").put("sourceAssetId","guide")).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))));
        JSONObject drawing=source;try(SketchProjectStore store=new SketchProjectStore(context)){store.mutate(id,"save_guide",source.getLong("revision"),"test",ignored->drawing);source=store.get(id);}
        SketchPaintArchive.Prepared prepared=SketchPaintArchive.prepare(context,args(source,"archive"));JSONObject inventory;try(ZipFile zip=new ZipFile(prepared.file);InputStream in=zip.getInputStream(zip.getEntry("archive.json"))){inventory=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        SketchDocument.object(source,"guide").getJSONObject("reference").put("width",128);byte[] document=SketchDocument.canonical(source).getBytes(StandardCharsets.UTF_8);String docHash=SketchDocument.sha256(document);inventory.put("documentSha256",docHash);JSONArray files=inventory.getJSONArray("files");for(int i=0;i<files.length();i++)if("document.json".equals(files.getJSONObject(i).getString("path")))files.getJSONObject(i).put("sha256",docHash).put("bytes",document.length);
        File bad=altered(altered(prepared.file,"document.json",document),"archive.json",SketchDocument.canonical(inventory).getBytes(StandardCharsets.UTF_8));int before=count();assertThrows(Exception.class,()->SketchPaintArchive.restoreFile(context,bad,SketchProjectStore.fileHash(bad),"restore_png",null));assertEquals(before,count());
    }
    @Test public void validHashesAndPngHeaderCannotPublishMalformedPixelPayload()throws Exception {
        JSONObject original=create(),document=SketchDocument.copy(original);Bitmap tile=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);tile.eraseColor(Color.GREEN);ByteArrayOutputStream encoded=new ByteArrayOutputStream();try{assertTrue(tile.compress(Bitmap.CompressFormat.PNG,100,encoded));}finally{tile.recycle();}byte[] corrupt=encoded.toByteArray();boolean broken=false;
        for(int position=8;position+12<=corrupt.length;){int length=java.nio.ByteBuffer.wrap(corrupt,position,4).getInt();if(length<0||position+12L+length>corrupt.length)fail("Invalid test PNG chunk");if("IDAT".equals(new String(corrupt,position+4,4,StandardCharsets.US_ASCII))){assertTrue(length>=2);corrupt[position+8]=0;corrupt[position+9]=0;java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(corrupt,position+4,length+4);java.nio.ByteBuffer.wrap(corrupt,position+8+length,4).putInt((int)crc.getValue());broken=true;break;}position+=length+12;}
        assertTrue(broken);File headerFile=new File(context.getCacheDir(),UUID.randomUUID()+".png");Files.write(headerFile.toPath(),corrupt);android.graphics.BitmapFactory.Options header=new android.graphics.BitmapFactory.Options();header.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(headerFile.getPath(),header);assertEquals("The prior header-only guard accepts this file",256,header.outWidth);assertEquals(256,header.outHeight);
        String tileHash=SketchDocument.sha256(corrupt);JSONObject snapshot=new JSONObject().put("schema","videostudio.sketch.paint.snapshot.v1").put("width",256).put("height",256).put("tileSize",256).put("strokeCount",0).put("tiles",new JSONArray().put(new JSONObject().put("x",0).put("y",0).put("sha256",tileHash)));byte[] snapshotBytes=SketchDocument.canonical(snapshot).getBytes(StandardCharsets.UTF_8);String snapshotHash=SketchDocument.sha256(snapshotBytes);JSONObject descriptor=new JSONObject().put("file",snapshotHash+".json").put("sha256",snapshotHash).put("width",256).put("height",256).put("tileSize",256);document.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","cel").put("kind","paint").put("paint",descriptor).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))));
        LinkedHashMap<String,byte[]> files=new LinkedHashMap<>();files.put("document.json",SketchDocument.canonical(document).getBytes(StandardCharsets.UTF_8));files.put("assets/paint/"+snapshotHash+".json",snapshotBytes);files.put("assets/paint/tiles/"+tileHash+".png",corrupt);JSONArray inventoryFiles=new JSONArray();for(Map.Entry<String,byte[]> file:files.entrySet())inventoryFiles.put(new JSONObject().put("path",file.getKey()).put("sha256",SketchDocument.sha256(file.getValue())).put("bytes",file.getValue().length));JSONObject inventory=new JSONObject().put("schema",SketchPaintArchive.SCHEMA).put("sourceSketchId",document.getString("id")).put("sourceRevision",document.getLong("revision")).put("documentSha256",SketchDocument.sha256(files.get("document.json"))).put("files",inventoryFiles);files.put("archive.json",SketchDocument.canonical(inventory).getBytes(StandardCharsets.UTF_8));File archive=new File(context.getCacheDir(),UUID.randomUUID()+".vssketch");try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(archive))){for(Map.Entry<String,byte[]> file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue());zip.closeEntry();}}
        int before=count();assertThrows(Exception.class,()->SketchPaintArchive.restoreFile(context,archive,SketchProjectStore.fileHash(archive),"restore_corrupt_pixels",null));assertEquals(before,count());try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals(SketchDocument.canonical(original),SketchDocument.canonical(store.get(original.getString("id"))));}
    }
    @Test public void dependencyFreezeIsIndependentAndPixelFaithful()throws Exception {
        String id=UUID.randomUUID().toString();JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",id).put("width",256).put("height",256).put("frameCount",1).put("background","#00000000"));
        try(SketchPaintStore paint=new SketchPaintStore(context,id)){
            JSONObject empty=paint.empty(256,256);doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id","cel").put("kind","paint").put("paint",empty).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))));
            JSONObject op=new JSONObject().put("type","paint_stroke").put("objectId","cel").put("strokeId","line").put("points",new JSONArray().put(new JSONArray().put(.1).put(.5).put(1)).put(new JSONArray().put(.9).put(.5).put(1))).put("brush",new JSONObject().put("mode","paint").put("color","#FF33AA77").put("sizePx",16));
            SketchPaintStore.PreparedChange change=paint.prepare(doc,op);SketchDocument.object(doc,"cel").put("paint",change.descriptor);File frozen=new File(context.getFilesDir(),"sketch_studio/"+id+"/exports/frozen/assets");JSONObject inventory=SketchPaintArchive.freezeDependencies(context,doc,frozen);assertTrue(inventory.getJSONArray("files").length()>=3);
            File originalTile=paint.dependencies(change.descriptor).entrySet().stream().filter(e->e.getKey().startsWith("paint/tiles/")).findFirst().get().getValue();assertTrue(originalTile.delete());
            Bitmap rendered=worker(()->{try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc,frozen)){return renderer.render(0);}});try{assertEquals(Color.rgb(51,170,119),rendered.getPixel(128,128));assertEquals(0,rendered.getPixel(128,20));}finally{rendered.recycle();}
        }
    }
    @Test public void deletedGuideCloneSourceAndJournalSurviveEditableArchiveRestore()throws Exception {
        JSONObject doc=create();String sourceId=doc.getString("id");
        Bitmap texture=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);for(int y=0;y<64;y++)for(int x=0;x<64;x++)texture.setPixel(x,y,x%4<2?Color.RED:Color.BLUE);
        File referenceRoot=new File(context.getFilesDir(),"sketch_studio/"+sourceId+"/references");assertTrue(referenceRoot.mkdirs());File tmp=new File(referenceRoot,"input.png");try(FileOutputStream out=new FileOutputStream(tmp)){texture.compress(Bitmap.CompressFormat.PNG,100,out);}finally{texture.recycle();}String sourceHash=SketchProjectStore.fileHash(tmp);File reference=new File(referenceRoot,sourceHash+".png");assertTrue(tmp.renameTo(reference));
        JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");objects.put(SketchEditOps.completeObject(new JSONObject().put("id","guide").put("kind","image").put("reference",new JSONObject().put("file",sourceHash+".png").put("sha256",sourceHash).put("width",64).put("height",64).put("sourceProjectId","synthetic").put("sourceAssetId","texture")).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)).put("transform",SketchDocument.transform().put("opacity",0))));
        try(SketchPaintStore paint=new SketchPaintStore(context,sourceId)){
            objects.put(SketchEditOps.completeObject(new JSONObject().put("id","cel").put("kind","paint").put("paint",paint.empty(256,256)).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1))));
            JSONObject clone=new JSONObject().put("type","paint_stroke").put("objectId","cel").put("strokeId","clone_texture").put("points",new JSONArray().put(new JSONArray().put(.1).put(.5).put(1)).put(new JSONArray().put(.9).put(.5).put(1))).put("brush",new JSONObject().put("mode","clone").put("color","#FF000000").put("sizePx",32)).put("cloneSource",new JSONObject().put("objectId","guide").put("sha256",sourceHash));
            SketchPaintStore.PreparedChange change=paint.prepare(doc,clone);SketchDocument.object(doc,"cel").put("paint",change.descriptor);for(int i=objects.length()-1;i>=0;i--)if("guide".equals(objects.getJSONObject(i).getString("id")))objects.remove(i);assertTrue(reference.delete());
            JSONObject savedDrawing=doc;try(SketchProjectStore store=new SketchProjectStore(context)){store.mutate(sourceId,"save_paint",doc.getLong("revision"),"test",ignored->savedDrawing);doc=store.get(sourceId);}
            SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,args(doc,"clone_archive"));JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restore_clone",null);
            try(SketchProjectStore store=new SketchProjectStore(context);SketchPaintStore copy=new SketchPaintStore(context,restored.getString("sketchId"))){JSONObject copied=store.get(restored.getString("sketchId"));assertEquals(change.descriptor.getString("sha256"),SketchDocument.object(copied,"cel").getJSONObject("paint").getString("sha256"));assertEquals(1,copy.journal(change.descriptor,0,16).length());assertTrue(copy.dependencies(change.descriptor).keySet().stream().anyMatch(path->path.startsWith("paint/sources/")));Bitmap actual=copy.renderPrefix(change.visualOperation,1,256,null),expected=paint.render(change.descriptor,256,null);try{assertTrue(actual.sameAs(expected));}finally{actual.recycle();expected.recycle();}}
            File pinned=paint.dependencies(change.descriptor).entrySet().stream().filter(e->e.getKey().startsWith("paint/sources/")).findFirst().get().getValue();assertTrue(pinned.delete());int before=count();JSONObject frozenDoc=doc;assertThrows(Exception.class,()->SketchPaintArchive.prepare(context,args(frozenDoc,"missing_source")));assertEquals(before,count());
        }
    }
}
