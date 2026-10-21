package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHandStudyTest {
    @Test public void captureNativeHandViewsAndRestoreTheirEditableArchives()throws Exception {
        String evidence=System.getenv("VIDEOSTUDIO_HAND_EVIDENCE");boolean full="true".equals(System.getenv("VIDEOSTUDIO_HAND_FULL"));
        for(String view:new String[]{"front","left","right","back","high","low"}){
            File root=new File(evidence==null?"build/hand-evidence":evidence,view);root.mkdirs();String id=SketchMouthAnimationTest.create(RuntimeEnvironment.getApplication(),180,30);
            JSONObject op=SketchHandAnimationTest.op(view);JSONObject request=SketchMouthAnimationTest.request(id,op);SketchEditOps.execute(RuntimeEnvironment.getApplication(),"sketch_edit",request);
            JSONObject doc;try(SketchProjectStore store=new SketchProjectStore(RuntimeEnvironment.getApplication())){doc=store.get(id);}SketchSpeechStudyTest.text(new File(root,"document.json").toPath(),doc.toString());SketchSpeechStudyTest.text(new File(root,"request.json").toPath(),request.toString(2));
            JSONArray hashes=new JSONArray();try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){
                for(int f=0;f<180;f++){if(!full&&!Set.of(0,18,36,54,78,108,144,179).contains(f))continue;Bitmap image=renderer.render(f);File out=new File(root,String.format(Locale.ROOT,"frames/frame-%04d.png",f));try{SketchActionFilmTest.png(out,image);}finally{image.recycle();}hashes.put(new JSONObject().put("frame",f).put("sha256",SketchProjectStore.fileHash(out)));}
            }
            SketchPaintArchive.Prepared a=SketchPaintArchive.prepare(RuntimeEnvironment.getApplication(),new JSONObject().put("sketchId",id).put("expectedRevision",2).put("operationId","hand_archive"));Files.copy(a.file.toPath(),new File(root,"editable.vssketch").toPath(),StandardCopyOption.REPLACE_EXISTING);
            JSONObject restored=SketchPaintArchive.restoreFile(RuntimeEnvironment.getApplication(),a.file,SketchProjectStore.fileHash(a.file),"restored_hand_"+view,"Restored hand");
            try(SketchProjectStore store=new SketchProjectStore(RuntimeEnvironment.getApplication());SketchFrameRenderer before=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc);SketchFrameRenderer after=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),store.get(restored.getString("sketchId")))){
                for(int f:new int[]{0,54,108,179}){Bitmap x=before.render(f),y=after.render(f);try{assertTrue(x.sameAs(y));}finally{x.recycle();y.recycle();}}
            }
            SketchSpeechStudyTest.text(new File(root,"native-evidence.json").toPath(),new JSONObject().put("frames",hashes).put("frameCount",180).put("fps",30).put("durationSeconds",6).put("width",1280).put("height",720).put("renderer","VideoStudio Android Canvas / workspace Robolectric").put("archivePixelComparisons",4).put("audio","Silent isolated hand mechanics").put("technical","PASS").put("visual","UNREVIEWED").put("phoneExecution",false).toString(2));
        }
    }
}
