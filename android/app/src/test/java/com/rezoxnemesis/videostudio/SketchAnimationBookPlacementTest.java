package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

/** Behavioural coverage of the saved native programme -> editable sequence boundary. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchAnimationBookPlacementTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private String id(){return UUID.randomUUID().toString();}
    private JSONObject source,saveRequest;

    private JSONObject fixture(int count)throws Exception {
        String animation=id(),book=id();
        source=SketchDocument.create(new JSONObject().put("sketchId",id()).put("width",320).put("height",480).put("fps",24).put("frameCount",1));
        source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject()
                .put("id","arm").put("kind","line").put("color","#FF206080").put("width",.02)
                .put("points",new JSONArray().put(new JSONArray().put(.4).put(.4).put(1)).put(new JSONArray().put(.65).put(.4).put(1)))));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.importOwnedDocument(source,"source");}
        JSONObject program=new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",72).put("fps",24)
                .put("rigs",new JSONArray().put(new JSONObject().put("id","actor")
                .put("joints",new JSONArray().put(new JSONObject().put("id","root").put("x",.4).put("y",.4)))
                .put("bindings",new JSONArray().put(new JSONObject().put("objectId","arm").put("jointId","root")))
                .put("poses",new JSONArray().put(pose(0,0)).put(pose(71,70)))));
        saveRequest=new JSONObject().put("animationId",animation).put("sketchId",source.getString("id")).put("sourceRevision",1)
                .put("expectedRevision",0).put("operationId","save").put("program",program);
        SketchAnimationOps.execute(context,"sketch_animation_save",saveRequest);
        SketchBookStore.execute(context,"sketch_book_create",new JSONObject().put("bookId",book).put("operationId","book")
                .put("pageCount",2880).put("width",320).put("height",480).put("fps",24));
        return new JSONObject().put("animationId",animation).put("expectedAnimationRevision",1).put("bookId",book)
                .put("expectedBookRevision",1).put("sourceFrameStart",7).put("frameCount",count).put("destinationPageStart",120).put("operationId","place");
    }
    private JSONObject pose(int frame,int rotation)throws Exception{return new JSONObject().put("frame",frame).put("interpolation","linear").put("rotations",new JSONObject().put("root",rotation));}
    private JSONObject place(JSONObject args)throws Exception{return SketchAnimationOps.execute(context,"sketch_animation_place_in_book",args);}
    private JSONObject book(JSONObject args)throws Exception{try(SketchBookStore books=new SketchBookStore(context)){return books.get(args.getString("bookId"));}}
    private String cel(JSONObject args,int offset)throws Exception{return args.getString("bookId")+"_p"+(args.getInt("destinationPageStart")+offset);}
    private void rename(String sketch)throws Exception{try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.mutate(sketch,id(),sketches.revision(sketch),"a".repeat(64),d->d.put("name","Human edits retained"));}}
    private interface Checked {void run()throws Exception;}
    private void rejects(Checked run,String message)throws Exception{try{run.run();fail("Expected "+message);}catch(Exception error){assertTrue(error.toString(),error.toString().contains(message));}}
    private void noPages(JSONObject args)throws Exception{assertEquals(0,book(args).getInt("createdPages"));assertEquals(1,book(args).getLong("revision"));try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(-1,sketches.revision(cel(args,0)));}}

    @Test public void dispatchSamplesExactSavedFramesAsEditableCelsAndKeepsSource()throws Exception {
        JSONObject args=fixture(3),frozen=SketchAnimationOps.load(context,args.getString("animationId"));
        String original=SketchDocument.canonical(source);JSONObject receipt=place(args);
        assertEquals(3,receipt.getInt("frameCount"));assertEquals(2,receipt.getLong("bookRevision"));
        assertEquals(frozen.getString("sourceSha256"),receipt.getString("sourceSha256"));assertFalse(receipt.getBoolean("audioMixedIntoSketch"));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){
            for(int i=0;i<3;i++){
                JSONObject expected=SketchAnimationCompiler.preview(frozen.getJSONObject("sourceDocument"),frozen.getJSONObject("program"),7+i,cel(args,i)).getJSONObject("document");
                JSONObject actual=sketches.get(cel(args,i));assertEquals(SketchDocument.canonical(expected),SketchDocument.canonical(actual));
                try(SketchFrameRenderer a=new SketchFrameRenderer(context,expected);SketchFrameRenderer b=new SketchFrameRenderer(context,actual)){
                    Bitmap x=a.render(0),y=b.render(0);assertTrue(x.sameAs(y));x.recycle();y.recycle();
                }
            }
            assertEquals(original,SketchDocument.canonical(sketches.get(source.getString("id"))));
        }
        assertEquals(3,book(args).getInt("createdPages"));assertEquals(2880,new SketchSequenceTimeline(book(args),0,2880).frames());
    }

    @Test public void lastOccupiedBlankPageRejectsWholeBatchBeforePublishingFirstPage()throws Exception {
        JSONObject args=fixture(3);SketchBookStore.execute(context,"sketch_book_open_page",new JSONObject().put("bookId",args.get("bookId")).put("page",122));
        String occupied;try(SketchProjectStore sketches=new SketchProjectStore(context)){occupied=SketchDocument.canonical(sketches.get(cel(args,2)));}
        rejects(()->place(args),"OCCUPIED");assertEquals(1,book(args).getInt("createdPages"));assertEquals(1,book(args).getLong("revision"));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(-1,sketches.revision(cel(args,0)));assertEquals(occupied,SketchDocument.canonical(sketches.get(cel(args,2))));}
    }

    @Test public void fullSixtyFourFrameBatchPreservesPagesOutsideItsRange()throws Exception {
        JSONObject args=fixture(64).put("sourceFrameStart",0);SketchBookStore.execute(context,"sketch_book_open_page",new JSONObject().put("bookId",args.get("bookId")).put("page",0));
        String untouched;try(SketchProjectStore sketches=new SketchProjectStore(context)){untouched=SketchDocument.canonical(sketches.get(args.getString("bookId")+"_p0"));}
        assertEquals(64,place(args).getInt("frameCount"));assertEquals(65,book(args).getInt("createdPages"));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(untouched,SketchDocument.canonical(sketches.get(args.getString("bookId")+"_p0")));assertEquals(1,sketches.get(cel(args,63)).getInt("frameCount"));}
    }

    @Test public void integralSampledTransformsKeepTheSameFingerprintAcrossJsonPersistence()throws Exception {
        for(int frame:new int[]{0,71}){
            JSONObject args=fixture(1).put("sourceFrameStart",frame),saved=SketchAnimationOps.load(context,args.getString("animationId"));
            JSONObject expected=SketchDocument.copy(SketchAnimationCompiler.preview(saved.getJSONObject("sourceDocument"),saved.getJSONObject("program"),frame,cel(args,0)).getJSONObject("document"));
            JSONObject receipt=place(args);
            try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(SketchDocument.canonical(expected),SketchDocument.canonical(sketches.get(cel(args,0))));}
            assertEquals(SketchDocument.canonical(receipt),SketchDocument.canonical(place(args)));
        }
    }

    @Test public void unattachedExistingSketchAlsoCountsAsOccupied()throws Exception {
        JSONObject args=fixture(2);try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.importOwnedDocument(SketchDocument.copy(source).put("id",cel(args,1)),"other_owner");}
        rejects(()->place(args),"OCCUPIED");noPages(args);
    }

    @Test public void rejectsBoundsStaleRevisionsUnknownFieldsAndClockMismatchWithoutWrites()throws Exception {
        JSONObject args=fixture(1);
        for(int count:new int[]{0,65})rejects(()->place(new JSONObject(args.toString()).put("frameCount",count)),"frameCount");
        rejects(()->place(new JSONObject(args.toString()).put("sourceFrameStart",72)),"range");
        rejects(()->place(new JSONObject(args.toString()).put("destinationPageStart",2880)),"range");
        rejects(()->place(new JSONObject(args.toString()).put("expectedAnimationRevision",2)),"ANIMATION_REVISION_CONFLICT");
        rejects(()->place(new JSONObject(args.toString()).put("expectedBookRevision",2)),"BOOK_REVISION_CONFLICT");
        rejects(()->place(new JSONObject(args.toString()).put("unknown",true)),"Unknown");noPages(args);
        SketchBookStore.execute(context,"sketch_book_edit",new JSONObject().put("bookId",args.get("bookId")).put("expectedRevision",1).put("operationId","fps").put("fps",30));
        rejects(()->place(new JSONObject(args.toString()).put("expectedBookRevision",2)),"TIMING_OR_CANVAS");assertEquals(0,book(args).getInt("createdPages"));
    }

    @Test public void rejectsHeldDestinationPagesInsteadOfSilentlyChangingFilmTiming()throws Exception {
        JSONObject args=fixture(2);SketchBookStore.execute(context,"sketch_book_edit",new JSONObject().put("bookId",args.get("bookId")).put("expectedRevision",1).put("operationId","hold")
                .put("exposures",new JSONArray().put(new JSONObject().put("page",121).put("frames",2))));
        rejects(()->place(args.put("expectedBookRevision",2)),"EXPOSURE");assertEquals(0,book(args).getInt("createdPages"));assertEquals(2,book(args).getLong("revision"));
    }

    @Test public void completedRetryReturnsOriginalReceiptAfterBookAnimationAndCelEdits()throws Exception {
        JSONObject args=fixture(2),receipt=place(args);rename(cel(args,0));
        SketchBookStore.execute(context,"sketch_book_edit",new JSONObject().put("bookId",args.get("bookId")).put("expectedRevision",2).put("operationId","later").put("name","Later book edit"));
        SketchAnimationOps.execute(context,"sketch_animation_save",new JSONObject(saveRequest.toString()).put("expectedRevision",1).put("operationId","later"));
        assertEquals(SketchDocument.canonical(receipt),SketchDocument.canonical(place(new JSONObject(args.toString()))));
        assertEquals(3,book(args).getLong("revision"));try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals("Human edits retained",sketches.get(cel(args,0)).getString("name"));}
        rejects(()->place(new JSONObject(args.toString()).put("destinationPageStart",130)),"OPERATION_ID_CONFLICT");
    }

    @Test public void cancellationResumesFrozenBatchWithoutReclaimingRevisionOrOverwritingEdits()throws Exception {
        JSONObject args=fixture(3);
        rejects(()->SketchAnimationBookPlacement.place(context,args,(done,total)->{if(done==1)throw new InterruptedException("cancel after one");}),"cancel after one");
        assertEquals(1,book(args).getInt("createdPages"));assertEquals(2,book(args).getLong("revision"));rename(cel(args,0));
        JSONObject updated=new JSONObject(saveRequest.toString()).put("expectedRevision",1).put("operationId","new-take");updated.getJSONObject("program").getJSONArray("rigs").getJSONObject(0).getJSONArray("poses").getJSONObject(1).getJSONObject("rotations").put("root",-20);
        SketchAnimationOps.execute(context,"sketch_animation_save",updated);JSONObject result=place(args);
        assertEquals(1,result.getLong("animationRevision"));assertEquals(2,book(args).getLong("revision"));assertEquals(3,book(args).getInt("createdPages"));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals("Human edits retained",sketches.get(cel(args,0)).getString("name"));assertEquals(70*9/71d,SketchDocument.object(sketches.get(cel(args,2)),"arm").getJSONObject("transform").getDouble("rotation"),1e-7);}
    }

    @Test public void foreignPageDuringInterruptedBatchBlocksRemainingPublication()throws Exception {
        JSONObject args=fixture(3);rejects(()->SketchAnimationBookPlacement.place(context,args,(done,total)->{throw new InterruptedException("pause");}),"pause");
        SketchBookStore.execute(context,"sketch_book_open_page",new JSONObject().put("bookId",args.get("bookId")).put("page",122));
        rejects(()->place(args),"OCCUPIED");try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(-1,sketches.revision(cel(args,1)));assertEquals(1,sketches.revision(cel(args,2)));}
    }

    @Test public void changedBookDuringInterruptedBatchRequiresOriginalRequestAndNeverRebases()throws Exception {
        JSONObject args=fixture(2);rejects(()->SketchAnimationBookPlacement.place(context,args,(done,total)->{throw new InterruptedException("pause");}),"pause");
        SketchBookStore.execute(context,"sketch_book_edit",new JSONObject().put("bookId",args.get("bookId")).put("expectedRevision",2).put("operationId","human").put("name","Human book edit"));
        rejects(()->place(args),"BOOK_REVISION_CONFLICT");rejects(()->place(new JSONObject(args.toString()).put("expectedBookRevision",3)),"OPERATION_ID_CONFLICT");
        try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(-1,sketches.revision(cel(args,1)));}
    }

    @Test public void preCancelledRequestDoesNotPublishOrClaimBookRevision()throws Exception {
        JSONObject args=fixture(1);Thread.currentThread().interrupt();try{rejects(()->place(args),"cancel");}finally{Thread.interrupted();}noPages(args);
    }

    @Test public void sampledRetainedHandKeepsItsActualSourceFrameAndNativePixels()throws Exception {
        JSONObject args=fixture(2);String animation=args.getString("animationId"),handBook=id();
        JSONObject hand=SketchDocument.create(new JSONObject().put("sketchId",id()).put("width",1280).put("height",720).put("frameCount",96).put("fps",24));
        SketchHandAnimation.apply(hand,new JSONObject().put("type","add_hand_animation").put("layerId","layer_1").put("handId","gesture").put("settings",new JSONObject()));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.importOwnedDocument(hand,"hand_source");}
        SketchAnimationOps.execute(context,"sketch_animation_save",new JSONObject().put("animationId",animation).put("sketchId",hand.getString("id"))
                .put("sourceRevision",1).put("expectedRevision",1).put("operationId","hand-take")
                .put("program",new JSONObject().put("schema",SketchAnimationCompiler.SCHEMA).put("frameCount",96).put("fps",24).put("rigs",new JSONArray())));
        SketchBookStore.execute(context,"sketch_book_create",new JSONObject().put("bookId",handBook).put("operationId","book").put("pageCount",2880).put("width",1280).put("height",720).put("fps",24));
        args.put("bookId",handBook).put("expectedAnimationRevision",2).put("sourceFrameStart",20);place(args);
        try(SketchProjectStore sketches=new SketchProjectStore(context);SketchFrameRenderer original=new SketchFrameRenderer(context,hand)){
            for(int i=0;i<2;i++){
                JSONObject page=sketches.get(cel(args,i)),object=page.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0);
                assertEquals("hand",object.getString("kind"));assertEquals(20+i,object.getJSONObject("hand").getInt("renderFrame"));
                try(SketchFrameRenderer sampled=new SketchFrameRenderer(context,page)){
                    Bitmap a=original.render(20+i),b=sampled.render(0);try{assertTrue("Retained hand must not restart at source frame zero",a.sameAs(b));}finally{a.recycle();b.recycle();}
                }
            }
        }
    }

    @Test public void ownedReferenceBytesAreCopiedToEveryPublishedPage()throws Exception {
        JSONObject args=fixture(2);String sourceId=source.getString("id");
        Bitmap bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff246080);
        java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,encoded);bitmap.recycle();
        byte[] bytes=encoded.toByteArray();String sha=SketchDocument.sha256(bytes);File refs=new File(context.getFilesDir(),"sketch_studio/"+sourceId+"/references");assertTrue(refs.mkdirs());Files.write(new File(refs,sha+".png").toPath(),bytes);
        JSONObject reference=new JSONObject().put("file",sha+".png").put("sha256",sha).put("width",32).put("height",32).put("sourceProjectId","owned").put("sourceAssetId","owned_image");
        JSONObject image=SketchEditOps.completeObject(new JSONObject().put("id","background").put("kind","image").put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)).put("reference",reference));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){sketches.mutate(sourceId,"image",1,"b".repeat(64),d->{d.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(image);return d;});}
        SketchAnimationOps.execute(context,"sketch_animation_save",new JSONObject(saveRequest.toString()).put("sourceRevision",2).put("expectedRevision",1).put("operationId","image-take"));
        place(args.put("expectedAnimationRevision",2));
        try(SketchProjectStore sketches=new SketchProjectStore(context)){
            for(int i=0;i<2;i++){sketches.verifyReferences(sketches.get(cel(args,i)));assertArrayEquals(bytes,Files.readAllBytes(sketches.referenceFile(cel(args,i),reference).toPath()));}
        }
    }

    @Test public void stagedCelTamperingRejectsResumeBeforePublishingMorePages()throws Exception {
        JSONObject args=fixture(2);rejects(()->SketchAnimationBookPlacement.place(context,args,(done,total)->{throw new InterruptedException("pause");}),"pause");
        String key=SketchDocument.sha256((args.getString("bookId")+"\n"+args.getString("operationId")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File staged=new File(context.getFilesDir(),"sketch_animation_book_placements/"+key+"/frame_1.json");
        JSONObject changed=new JSONObject(new String(Files.readAllBytes(staged.toPath()),java.nio.charset.StandardCharsets.UTF_8)).put("name","Tampered cel");
        Files.write(staged.toPath(),changed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rejects(()->place(args),"STAGED_OUTPUT_CHANGED");try(SketchProjectStore sketches=new SketchProjectStore(context)){assertEquals(-1,sketches.revision(cel(args,1)));}assertEquals(2,book(args).getLong("revision"));
    }
}
