package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real product-generated speech fixtures; original editable native mouth and face drawings. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchSpeechStudyTest {
    static byte[] resource(String name)throws Exception {
        try(InputStream in=SketchSpeechStudyTest.class.getResourceAsStream("/speech/"+name)){
            if(in==null)throw new FileNotFoundException(name);return in.readAllBytes();
        }
    }
    static void text(Path path,String value)throws Exception {Files.write(path,value.getBytes(StandardCharsets.UTF_8));}
    @Test public void isolatedSpeakingMouthsUseActualEnglishAndHindiTimingInSixViews()throws Exception {study(false);}
    @Test public void composedSpeakingFacesUseActualEnglishAndHindiTimingInSixViews()throws Exception {study(true);}
    private void study(boolean face)throws Exception {
        Context context=RuntimeEnvironment.getApplication();String evidence=System.getenv("VIDEOSTUDIO_SPEECH_EVIDENCE");
        for(String language:new String[]{"english","hindi"})for(String view:new String[]{"front","left","right","back","high","low"}){
            String name=(face?"face_":"mouth_")+language+"_"+view;
            File root=new File(evidence==null?"build/speech-evidence":evidence,name);root.mkdirs();
            JSONObject dialogue=new JSONObject(new String(resource(language+"-dialogue.json"),StandardCharsets.UTF_8));
            JSONObject speech=SketchSpeechTiming.fromDialogue(dialogue,0),operation=SketchSpeechAnimationTest.op(face,view,speech);
            if(face)operation.getJSONObject("settings").put("shape","hindi".equals(language)?"round":"oval").put("eyeStyle","almond");
            else operation.getJSONObject("settings").put("style","hindi".equals(language)?"cartoon":"soft");
            String id=SketchMouthAnimationTest.create(context,180,30);
            SketchEditOps.execute(context,"sketch_edit",SketchMouthAnimationTest.request(id,operation));JSONObject document;
            try(SketchProjectStore store=new SketchProjectStore(context)){document=store.get(id);}
            Files.write(new File(root,"audio.wav").toPath(),resource(language+".wav"));
            text(new File(root,"dialogue.json").toPath(),dialogue.toString(2));
            text(new File(root,"resolved-cues.json").toPath(),SketchSpeechTiming.resolve(speech,6000).toString(2));
            text(new File(root,"document.json").toPath(),document.toString());
            JSONArray hashes=new JSONArray();
            if(evidence!=null){
                SketchNativeExport.Prepared export=SketchNativeExport.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",2)
                        .put("operationId","speech_export").put("aspect","original").put("quality","720p"));
                assertEquals(180,export.metadata.getInt("frameCount"));assertEquals(30,export.metadata.getInt("fps"));
                assertEquals(1280,export.metadata.getInt("width"));assertEquals(720,export.metadata.getInt("height"));
                text(new File(root,"native-export-manifest.json").toPath(),export.metadata.toString(2));
                for(int f=0;f<180;f++){
                    File source=new File(export.file.getParentFile(),String.format(Locale.ROOT,"frame_%04d.png",f)),target=new File(root,String.format(Locale.ROOT,"frames/frame-%04d.png",f));
                    target.getParentFile().mkdirs();Files.copy(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
                    String sha=SketchProjectStore.fileHash(target);assertEquals(export.metadata.getJSONArray("frames").getJSONObject(f).getString("sha256"),sha);
                    hashes.put(new JSONObject().put("frame",f).put("sha256",sha));
                }
            }else try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document)){
                for(int f:new int[]{0,30,40,44,46,55,70,90,100,179}){
                    Bitmap image=renderer.render(f);File target=new File(root,String.format(Locale.ROOT,"frames/frame-%04d.png",f));
                    try{SketchActionFilmTest.png(target,image);}finally{image.recycle();}
                    hashes.put(new JSONObject().put("frame",f).put("sha256",SketchProjectStore.fileHash(target)));
                }
            }
            SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","speech_archive"));
            Files.copy(archive.file.toPath(),new File(root,"editable.vssketch").toPath(),StandardCopyOption.REPLACE_EXISTING);
            JSONObject restored=SketchPaintArchive.restoreFile(context,archive.file,SketchProjectStore.fileHash(archive.file),"restored_"+name,"Restored speaking study");
            try(SketchProjectStore store=new SketchProjectStore(context);SketchFrameRenderer before=new SketchFrameRenderer(context,document);
                SketchFrameRenderer after=new SketchFrameRenderer(context,store.get(restored.getString("sketchId")))){
                for(int f:new int[]{0,30,40,44,46,55,70,100,179}){
                    Bitmap a=before.render(f),b=after.render(f);try{assertTrue("Editable speech archive frame "+f,a.sameAs(b));}finally{a.recycle();b.recycle();}
                }
            }
            assertNull("Leading silence",SketchSpeechAnimationTest.part(document,"_cavity",0));
            assertNull("Trailing silence",SketchSpeechAnimationTest.part(document,"_cavity",179));
            if(!"back".equals(view))assertNotNull("A spoken vowel",SketchSpeechAnimationTest.part(document,"_cavity","hindi".equals(language)?87:72));
            text(new File(root,"native-evidence.json").toPath(),new JSONObject().put("operation",operation).put("frames",hashes)
                    .put("frameCount",180).put("fps",30).put("width",1280).put("height",720).put("archivePixelComparisons",9)
                    .put("audioSha256",SketchProjectStore.fileHash(new File(root,"audio.wav"))).put("actualProductGeneratedAudio",true)
                    .put("audioEngine","VideoStudio openpipeline Kokoro / self-hosted CPU").put("timingMethod","model predicted phoneme durations; not forced alignment")
                    .put("renderer","VideoStudio native SketchNativeExport / Android Canvas in workspace Robolectric")
                    .put("phoneExecution",false).put("androidSystemTtsExecution",false).put("androidVideoEncoding",false)
                    .put("intelligibilityReviewed",false).put("lipSyncHumanAccepted",false).toString(2));
        }
    }
}
