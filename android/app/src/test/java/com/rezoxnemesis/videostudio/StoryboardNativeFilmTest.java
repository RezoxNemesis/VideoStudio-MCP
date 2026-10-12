package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import static org.junit.Assert.*;

/** Native Canvas source-deformation fixture; synthetic rectangles do not prove articulated cinema or MP4 timing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class StoryboardNativeFilmTest {
    private static final int W=256,H=432;

    private File input(Context ctx,int step) throws Exception {
        Bitmap original=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);
        int[] pixels=new int[W*H];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            pixels[y*W+x]=0xff000000|
                    ((130+x/5)<<16)|((72+y/13)<<8)|(45+x/10);
        }
        box(pixels,10+step*46,180,27,85,0xff15131a);
        box(pixels,210-step*16,182,22,75,0xfff0f2eb);
        original.setPixels(pixels,0,W,0,0,W,H);
        File output=new File(ctx.getFilesDir(),"source_test_"+step+"_"+UUID.randomUUID()+".png");
        try(FileOutputStream out=new FileOutputStream(output)){
            assertTrue(original.compress(Bitmap.CompressFormat.PNG,100,out));
        }
        original.recycle();
        return output;
    }
    private void box(int[] image,int x,int y,int width,int height,int color){
        for(int row=y;row<y+height;row++)
            for(int col=x;col<x+width;col++)
                image[row*W+col]=color;
    }
    @Test public void buildsSourceDeformationFramesAndExplicitAmbienceWithoutAlteringSource()
            throws Exception {
        Context ctx=RuntimeEnvironment.getApplication();
        ProjectStore store=new ProjectStore(ctx);
        ProjectStore.Project source=store.create("Two moving illustrated actors");
        source.sourcePrompt="Illustrated action";
        for(int i=0;i<2;i++){
            File file=input(ctx,i);
            ProjectStore.Asset asset=new ProjectStore.Asset();
            asset.id=UUID.randomUUID().toString();
            asset.name=String.format(java.util.Locale.US,"%02d_Action.png",i);
            asset.uri=Uri.fromFile(file).toString();
            asset.mime="image/png";
            asset.sizeBytes=file.length();asset.seekable=true;
            asset.persistedReadAccess=true;
            source.assets.add(asset);
            ProjectStore.Clip clip=new ProjectStore.Clip();
            clip.id=UUID.randomUUID().toString();
            clip.assetId=asset.id;clip.inMs=0;clip.outMs=3000;
            source.clips.add(clip);
        }
        store.save(source);
        String originalGraph=source.toJson().toString();
        // Emit source-mask telemetry so a failed renderer cannot hide
        // "no visible actor" behind a passing full-image pixel score.
        Bitmap frameA=BitmapFactory.decodeFile(Uri.parse(source.assets.get(0).uri).getPath());
        Bitmap frameB=BitmapFactory.decodeFile(Uri.parse(source.assets.get(1).uri).getPath());
        int[] firstImage=new int[W*H],lastImage=new int[W*H];
        frameA.getPixels(firstImage,0,W,0,0,W,H);
        frameB.getPixels(lastImage,0,W,0,0,W,H);
        frameA.recycle();frameB.recycle();
        JSONObject sourceRecord=StoryboardSourceManifest.captureSource(ctx,source.assets.get(0));
        Bitmap verified=NativeStoryboardSourceImage.readVerifiedFit(ctx,source.assets.get(0),W,H,sourceRecord);
        int[] verifiedPixels=new int[W*H];
        try{verified.getPixels(verifiedPixels,0,W,0,0,W,H);}finally{verified.recycle();}
        assertArrayEquals("Same-aspect verified contain fit preserves actual original pixels",firstImage,verifiedPixels);
        Bitmap verifiedB=NativeStoryboardSourceImage.readVerifiedFit(ctx,source.assets.get(1),W,H,StoryboardSourceManifest.captureSource(ctx,source.assets.get(1)));
        int[] verifiedLast=new int[W*H];try{verifiedB.getPixels(verifiedLast,0,W,0,0,W,H);}finally{verifiedB.recycle();}
        assertArrayEquals("Verified B preserves the same moving source subjects",lastImage,verifiedLast);
        int changedPixels=0;for(int i=0;i<verifiedPixels.length;i++)if(verifiedPixels[i]!=verifiedLast[i])changedPixels++;
        assertTrue("Verified source pair contains the authored subject displacement",changedPixels>1000);
        StoryboardLayerIsolation.Result tracked=StoryboardLayerIsolation.analyse(
                firstImage,lastImage,W,H);
        System.out.println("NATIVE_STORYBOARD_MASKS status="+tracked.status+
                " tracks="+tracked.tracks.size()+" reason="+tracked.reason);
        for(int i=0;i<tracked.tracks.size();i++){
            StoryboardLayerIsolation.Track t=tracked.tracks.get(i);
            System.out.println("NATIVE_STORYBOARD_TRACK "+i+
                    " from=("+t.from.cx+","+t.from.cy+")"+
                    " RGB="+t.from.meanR+","+t.from.meanG+","+t.from.meanB+
                    " pixels="+t.from.pixels+
                    " to=("+t.to.cx+","+t.to.cy+")"+
                    " RGB="+t.to.meanR+","+t.to.meanG+","+t.to.meanB+
                    " pixels="+t.to.pixels+
                    " confidence="+t.confidence);
        }
        JSONObject params=new JSONObject().put("backend","AUTO_LAYERS")
                .put("motionIntent","source_deformation")
                .put("genre","action").put("fps",24)
                .put("framesPerPair",6).put("foley",true);
        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,params);
        ProjectStore.Project generated=store.create("2D source movement test");
        StoryboardSeriesOrchestrator.Result finished;
        try {
            finished=StoryboardSeriesOrchestrator.run(ctx,store,source,generated,
                    plan,0,W,H,params,null);
        }catch(Exception failure){
            // The composer has already written this shot's images before
            // enforcing the real per-object quality gate. Keep the actual
            // rejected midpoint for engineer review instead of hiding it
            // behind a green smoke test or synthetic mockup.
            if(generated.clips.size()>=7)
                writeVisualQaSheet(ctx,source,generated,null);
            throw failure;
        }
        assertEquals(7,finished.frames);
        assertEquals(7,generated.clips.size());
        assertEquals("Seven actual generated images plus one explicit ambient WAV",8,generated.assets.size());
        assertEquals(1,generated.studioLayers.size());
        assertEquals("audio",generated.studioLayers.get(0).type);
        assertTrue(finished.soundtrack);assertFalse("Ambience is not captured action audio",finished.actionAudio);
        JSONObject audioEvidence=finished.audioEvidence;
        assertEquals("PROCEDURAL_AMBIENCE_ONLY",audioEvidence.getString("timingScope"));
        assertEquals("NONE",audioEvidence.getString("origin"));assertTrue(audioEvidence.getBoolean("ambienceOnly"));
        assertEquals(0,audioEvidence.getInt("audibleEventCount"));assertEquals(0,audioEvidence.getJSONArray("events").length());
        assertFalse(audioEvidence.getBoolean("eventSynchronizationVerified"));
        assertFalse(audioEvidence.getBoolean("decodedExportSynchronizationVerified"));
        JSONObject completed=finished.toJson();assertFalse(completed.getBoolean("actionAudio"));
        assertFalse(completed.getBoolean("cinematicAcceptance"));
        ProjectStore.Asset ambient=generated.asset(generated.studioLayers.get(0).assetId);
        assertNotNull(ambient);assertEquals("audio/wav",ambient.mime);
        assertTrue(ambient.generationMetadata.getBoolean("ambienceOnly"));
        assertEquals(0,ambient.generationMetadata.getInt("events"));assertFalse(ambient.generationMetadata.getBoolean("actionAudio"));
        assertNotNull(generated.asset(generated.clips.get(3).assetId));
        assertTrue(new File(Uri.parse(generated.asset(
                generated.clips.get(3).assetId).uri).getPath()).isFile());
        ProjectStore.Project unchanged=store.get(source.id);
        assertEquals(2,unchanged.clips.size());
        assertEquals(2,unchanged.assets.size());
        assertEquals("The entire owner source graph remains unchanged",originalGraph,unchanged.toJson().toString());
        assertEquals(7,store.get(generated.id).clips.size());
        // Visual regression from the actual encoded 2D compositing frame:
        // a passing mean-pixel motion score is NOT enough if the black
        // fighter disappears while the white one is still visible.
        ProjectStore.Asset halfway=generated.asset(
                generated.clips.get(3).assetId);
        Bitmap middle=BitmapFactory.decodeFile(
                Uri.parse(halfway.uri).getPath());
        assertNotNull("Native Canvas produced the mid-action frame",middle);
        int black=0,white=0;
        for(int y=164;y<282;y++)for(int x=0;x<W;x++){
            int color=middle.getPixel(x,y);
            int red=(color>>>16)&255,green=(color>>>8)&255,blue=color&255;
            if(red<65&&green<65&&blue<65)black++;
            if(red>210&&green>210&&blue>210)white++;
        }
        middle.recycle();
        assertTrue("Black original fighter must remain visible throughout motion",black>350);
        assertTrue("White original fighter must remain visible throughout motion",white>350);
        writeVisualQaSheet(ctx,source,generated,finished);
    }

    /**
     * Saves decoded generated JPEG snapshots from the native Canvas renderer,
     * plus a structured source-deformation report and explicit procedural ambience.
     * GitHub Actions uploads these as a compact, inspectable test artifact.
     * This is visual proof from synthetic sample art, NOT proof that all
     * human/anime storyboard scenes meet cinematic quality.
     */
    private static void writeVisualQaSheet(Context context,
                                            ProjectStore.Project source,
                                            ProjectStore.Project result,
                                            StoryboardSeriesOrchestrator.Result quality)
            throws Exception {
        File directory=new File("build/storyboard-qa");
        if(!directory.isDirectory()&&!directory.mkdirs())
            throw new IllegalStateException("Cannot prepare storyboard visual review");
        final int[] samples={0,1,3,5,6};
        Bitmap sheet=Bitmap.createBitmap(W*samples.length,H+42,
                Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(sheet);
        canvas.drawColor(0xff101522);
        Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(0xfff2ead8);ink.setTextSize(19);
        String[] labels={"Source A","In-between 1","Midpoint","In-between 2","Source B"};
        try{
            for(int i=0;i<samples.length;i++){
                ProjectStore.Asset image=result.asset(
                        result.clips.get(samples[i]).assetId);
                Bitmap frame=BitmapFactory.decodeFile(
                        Uri.parse(image.uri).getPath());
                if(frame==null)throw new IllegalStateException(
                        "The exported storyboard test frame is unreadable");
                if(frame.getWidth()!=W||frame.getHeight()!=H)
                    throw new IllegalStateException("Unexpected frame size");
                canvas.drawBitmap(frame,i*W,42,null);
                canvas.drawText(labels[i],i*W+7,27,ink);
                frame.recycle();
            }
            File visual=new File(directory,"original_artwork_motion_qa.png");
            try(FileOutputStream saved=new FileOutputStream(visual)){
                if(!sheet.compress(Bitmap.CompressFormat.PNG,100,saved))
                    throw new IllegalStateException("QA image output failed");
                saved.flush();
            }
            assertTrue("Actual native storyboard frames available for review",
                    visual.isFile()&&visual.length()>4000);
            JSONObject metadata=(quality==null
                    ?new JSONObject().put("ok",false)
                        .put("status","VISUAL_QA_REJECTED")
                    :quality.toJson())
                    .put("sourceProjectId",source.id)
                    .put("generatedProjectId",result.id)
                    .put("motionImage","original_artwork_motion_qa.png")
                    .put("qaSource","programmatic synthetic source drawing")
                    .put("fixtureScope","SOURCE_DEFORMATION_WITH_PROCEDURAL_AMBIENCE")
                    .put("decodedMp4TimingVerified",false)
                    .put("manualSourceArtVisualApproval",false)
                    .put("sourceProjectUnmodified",true)
                    .put("renderedNativeFramesSampled",samples.length);
            File report=new File(directory,"motion_metrics.json");
            try(FileOutputStream saved=new FileOutputStream(report)){
                saved.write(metadata.toString(2).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
            }
            if(!result.studioLayers.isEmpty()){
                File soundtrack=new File(Uri.parse(result.asset(
                        result.studioLayers.get(0).assetId).uri).getPath());
                assertTrue("Explicit ambient soundtrack generated",soundtrack.exists());
                java.nio.file.Files.copy(soundtrack.toPath(),
                        new File(directory,"native_procedural_ambience.wav").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }finally{sheet.recycle();}
    }
}
