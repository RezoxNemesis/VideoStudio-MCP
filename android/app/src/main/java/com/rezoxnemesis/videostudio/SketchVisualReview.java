package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * An actual native-Canvas visual checkpoint that can be returned as an MCP image.
 *
 * Normal sketch_preview is a full-size lossless image and the private native relay
 * intentionally externalizes it. Native sketch review keeps a JPEG *thumbnail*
 * inside RelayTransportPolicy.RESULT_BYTES (2 KiB), allowing the existing worker's
 * sketchPreviewImage image-block path to show it directly to the assistant.
 *
 * It is read-only, revision-bound and never imports/retrieves a reference photo:
 * creative likeness assessment must happen with the user's photo in ChatGPT.
 */
final class SketchVisualReview {
    static final int RENDER_EDGE=256, MAX_FOCUS_BYTES=64;
    private static final int[] EDGES={144,128,112,96,80,72,64,56,48,40,32,24,16};
    private static final int[] QUALITIES={58,46,34,25,16};
    private SketchVisualReview(){}

    static JSONObject inspect(Context context,JSONObject source,JSONObject args)throws Exception {
        if(context==null||source==null||args==null)
            throw SketchDocument.bad("A native sketch source and bounded review request are required");
        if(Looper.myLooper()==Looper.getMainLooper())
            throw new IllegalStateException("Native visual review must render off the Android UI thread");
        SketchDocument.validate(source);
        String sketchId=source.getString("id");
        if(!sketchId.equals(SketchDocument.id(args,"sketchId")))
            throw SketchDocument.bad("Visual review cannot switch sketch owners");
        int frame=args.has("frameIndex")?(int)SketchDocument.integer(
                args,"frameIndex",0,source.getInt("frameCount")-1):0;
        JSONObject crop=args.optJSONObject("crop");
        if(args.has("crop")){
            crop=args.getJSONObject("crop");
            SketchDocument.validateBounds(crop);
        }
        String focus=args.optString("focus","overview").trim();
        if(focus.isEmpty()||focus.getBytes(StandardCharsets.UTF_8).length>MAX_FOCUS_BYTES)
            throw SketchDocument.bad("A visual-review focus must contain 1..64 UTF-8 bytes");
        long revision=source.getLong("revision");
        if(args.has("expectedRevision")&&
                SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1)!=revision)
            throw new IllegalStateException("SKETCH_REVISION_CONFLICT: visual review source changed");

        int visible=0,levels=0;
        JSONArray layers=source.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){
            JSONObject level=layers.getJSONObject(i);
            if(!level.getBoolean("visible")||level.getDouble("opacity")<=0)continue;
            levels++;
            JSONArray objects=level.getJSONArray("objects");
            for(int j=0;j<objects.length();j++)if(
                    SketchFrameRenderer.exposed(objects.getJSONObject(j),frame))visible++;
        }
        // Preserve exactly which normalized source region was rendered. It is not
        // a claim that the selected region contains a face, hair, hand, etc.
        JSONObject sourceRegion=crop==null?new JSONObject().put("x",0).put("y",0)
                .put("width",1).put("height",1):SketchDocument.copy(crop);
        Bitmap frameBitmap=null;
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,source)){
            frameBitmap=renderer.render(frame,true,RENDER_EDGE,crop);
            int sampledColors=colors(frameBitmap);
            JSONObject metadata=new JSONObject()
                    .put("sketchId",sketchId)
                    .put("revision",revision).put("frameIndex",frame)
                    .put("focus",focus).put("crop",sourceRegion)
                    .put("visibleObjects",visible).put("visibleLayers",levels)
                    .put("sampledColors",sampledColors)
                    .put("reviewOnly",true)
                    .put("referencePhotoReceived",false)
                    .put("referenceLikenessVerified",false)
                    .put("requiresChatVisualComparison",true);
            // Work from a single bounded 256-pixel Android Canvas proof.
            // Selecting the smallest-fitting JPEG does not render fake artwork.
            int baseWidth=frameBitmap.getWidth(),baseHeight=frameBitmap.getHeight();
            for(int edge:EDGES){
                double scale=Math.min(1,edge/(double)Math.max(baseWidth,baseHeight));
                int w=Math.max(1,(int)Math.round(baseWidth*scale)),
                        h=Math.max(1,(int)Math.round(baseHeight*scale));
                Bitmap thumb=w==baseWidth&&h==baseHeight?frameBitmap:
                        Bitmap.createScaledBitmap(frameBitmap,w,h,true);
                try{
                    for(int quality:QUALITIES){
                        if(Thread.currentThread().isInterrupted())
                            throw new InterruptedException("Visual checkpoint cancelled");
                        ByteArrayOutputStream output=new ByteArrayOutputStream(2048);
                        if(!thumb.compress(Bitmap.CompressFormat.JPEG,quality,output))
                            throw new IllegalStateException("Native visual review JPEG encoding failed");
                        byte[] image=output.toByteArray();
                        JSONObject review=SketchDocument.copy(metadata)
                                .put("sketchPreviewImage",new JSONObject()
                                        .put("mimeType","image/jpeg")
                                        .put("base64",Base64.encodeToString(image,Base64.NO_WRAP))
                                        .put("width",thumb.getWidth()).put("height",thumb.getHeight())
                                        .put("sha256",SketchDocument.sha256(image)))
                                .put("ok",true);
                        // The original private delivery contract is 2048 bytes.
                        // If exceeded, the relay would return a result locator
                        // instead of a directly observable MCP image.
                        if(review.toString().getBytes(StandardCharsets.UTF_8).length
                                <=RelayTransportPolicy.RESULT_BYTES)
                            return review;
                    }
                }finally{
                    if(thumb!=frameBitmap)thumb.recycle();
                }
            }
            throw SketchDocument.bad("VISUAL_REVIEW_BUDGET: source could not fit into the bounded direct image channel");
        }finally{
            if(frameBitmap!=null)frameBitmap.recycle();
        }
    }
    private static int colors(Bitmap bitmap){
        Set<Integer> groups=new HashSet<>();
        for(int y=0;y<bitmap.getHeight();y+=Math.max(1,bitmap.getHeight()/17))
            for(int x=0;x<bitmap.getWidth();x+=Math.max(1,bitmap.getWidth()/17)){
                int p=bitmap.getPixel(x,y);
                groups.add(((Color.red(p)>>5)<<6)|((Color.green(p)>>5)<<3)|(Color.blue(p)>>5));
            }
        return groups.size();
    }
}
