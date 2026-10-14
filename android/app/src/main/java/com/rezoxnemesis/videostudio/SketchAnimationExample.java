package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Original, bundled vector example. Every opening creates independent editable owned artwork. */
final class SketchAnimationExample {
    private SketchAnimationExample(){}
    static JSONObject create(Context context)throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Prepare the example on a worker");
        JSONObject source=read(context,"rooftop-relay-source.json",SketchDocument.MAX_BYTES);
        JSONObject program=read(context,"rooftop-relay-program.json",512*1024);
        String suffix=UUID.randomUUID().toString().replace("-","");String sourceId="rooftop_example_"+suffix,animationId="rooftop_scene_"+suffix;
        source.put("id",sourceId).put("revision",1);SketchDocument.validate(source);
        try(SketchProjectStore store=new SketchProjectStore(context)){store.importOwnedDocument(source,"example_"+suffix);}
        return SketchAnimationOps.execute(context,"sketch_animation_save",new JSONObject().put("animationId",animationId).put("sketchId",sourceId).put("sourceRevision",1).put("expectedRevision",0).put("operationId","save_"+suffix).put("program",program));
    }
    private static JSONObject read(Context context,String name,int maximum)throws Exception {
        try(InputStream input=context.getAssets().open("sketch-animation/"+name)){byte[] bytes=input.readNBytes(maximum+1);if(bytes.length>maximum)throw SketchDocument.bad("Bundled animation exceeds its size budget");return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    }
}
