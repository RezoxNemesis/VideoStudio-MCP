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
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Original 10-second, 240-frame film exported from precisely the same native
 * Canvas renderer as Sketch Studio and its native video-export input. The
 * resulting MP4 is encoded by CI FFmpeg, NOT represented as an on-phone export.
 * Off by default locally; enabled for isolated CI artistic acceptance.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchStoryFilmNativeProofTest {
    @Test public void everyFrameOfOriginalStoryRendersAsNativeEditableArt()throws Exception{
        assumeTrue("VIDEOSTUDIO_STORY_FILM_PROOF must be explicitly set",
                "1".equals(System.getenv("VIDEOSTUDIO_STORY_FILM_PROOF")));
        final int fps=24,seconds=10,frameCount=fps*seconds;
        Context app=RuntimeEnvironment.getApplication();
        JSONObject source=SketchStoryFilmGenerator.build("struggle_10s_original",
                "The struggle of a man: alone in the rain, rejected and exhausted, he falls before finding the strength to rise and continue.",
                seconds,fps);
        SketchDocument.validate(source);
        assertEquals(frameCount,source.getInt("frameCount"));
        File base=new File("build/sketch-story-film-proof"),frames=new File(base,"frames");
        assertTrue(frames.isDirectory()||frames.mkdirs());
        Files.write(new File(base,"editable-original-source.json").toPath(),
                source.toString(2).getBytes(StandardCharsets.UTF_8));

        int distinctAdjacent=0,firstHash=0,previousHash=0;
        JSONArray keyframes=new JSONArray();
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(app,source)){
            for(int frame=0;frame<frameCount;frame++){
                Bitmap bitmap=renderer.render(frame,true);
                try{
                    assertEquals(1280,bitmap.getWidth());assertEquals(720,bitmap.getHeight());
                    File file=new File(frames,String.format(java.util.Locale.ROOT,"frame-%04d.png",frame));
                    try(FileOutputStream output=new FileOutputStream(file)){
                        assertTrue("Native Canvas PNG must be encoded",bitmap.compress(Bitmap.CompressFormat.PNG,100,output));
                    }
                    assertTrue(file.length()>1000);
                    int hash=sample(bitmap);
                    if(frame==0)firstHash=hash;
                    else if(previousHash!=hash)distinctAdjacent++;
                    previousHash=hash;
                    if(frame%24==0)keyframes.put(new JSONObject().put("frame",frame).put("time",frame/(double)fps)
                            .put("pixelSignature",hash).put("pngSha256",sha(file)));
                }finally{bitmap.recycle();}
            }
        }
        assertTrue("A real animated film needs independently changed frame pixels, not a static slideshow",distinctAdjacent>=180);
        assertTrue("First and last frame have different authored scenery",firstHash!=previousHash);
        JSONObject report=new JSONObject()
                .put("execution","Android Canvas via Robolectric native graphics inside GitHub CI; not a phone")
                .put("renderer","SketchFrameRenderer")
                .put("originalVectorArtwork",true)
                .put("script","The struggle of a man")
                .put("width",1280).put("height",720).put("fps",fps).put("frameCount",frameCount)
                .put("durationSeconds",seconds).put("changedAdjacentFrames",distinctAdjacent)
                .put("sourceSha256",sha(new File(base,"editable-original-source.json")))
                .put("samples",keyframes);
        Files.write(new File(base,"native-frame-proof.json").toPath(),
                report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private static int sample(Bitmap image){
        int result=1;
        for(int y=19;y<image.getHeight();y+=24)
            for(int x=11;x<image.getWidth();x+=27)
                result=result*31+image.getPixel(x,y);
        return result;
    }
    private static String sha(File path)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(java.io.InputStream input=Files.newInputStream(path.toPath())){
            byte[] buffer=new byte[65536];int n;
            while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);
        }
        StringBuilder value=new StringBuilder();for(byte b:digest.digest())value.append(String.format("%02x",b&0xff));
        return value.toString();
    }
}
