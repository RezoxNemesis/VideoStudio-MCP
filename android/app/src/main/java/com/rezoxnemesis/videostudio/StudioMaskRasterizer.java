package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.JSONObject;
import java.util.Set;

/**
 * Native alpha-track masks for project-owned image and generated vector layers.
 * Runs exactly once on a bounded decoded bitmap. Never samples the phone Gallery,
 * never changes source bytes, and never allocates memory inside the GPU frame loop.
 *
 * Mask raster has zero/feathered alpha outside the shape. The media3 overlay
 * composer owns final placement, keyframes and alpha at composition timestamps.
 */
public final class StudioMaskRasterizer {
    public static final int MAX_PIXELS = 1_048_576;
    private static final Set<String> KEYS =
            Set.of("kind", "feather", "invert", "position", "roundness");
    private static final Set<String> KINDS =
            Set.of("ellipse", "rectangle", "round_rect", "linear_wipe");
    private StudioMaskRasterizer() {}

    public static JSONObject validate(JSONObject raw) {
        if (raw == null) throw new IllegalArgumentException("Mask object required");
        java.util.Iterator<String> iter = raw.keys();
        while (iter.hasNext()) {
            if (!KEYS.contains(iter.next()))
                throw new IllegalArgumentException("Unsupported mask field");
        }
        String kind = raw.optString("kind", "");
        if (!KINDS.contains(kind))
            throw new IllegalArgumentException("Use ellipse, rectangle, round_rect or linear_wipe");
        double feather = StudioClipKeyframes.number(raw,"feather",0.0,0,0.4);
        double position = StudioClipKeyframes.number(raw,"position",0.5,0,1);
        double roundness = StudioClipKeyframes.number(raw,"roundness",0.18,0,0.5);
        if(raw.has("invert") && !(raw.opt("invert") instanceof Boolean))
            throw new IllegalArgumentException("Mask invert must be boolean");
        try {
            return new JSONObject().put("kind",kind)
                    .put("feather",feather)
                    .put("position",position)
                    .put("roundness",roundness)
                    .put("invert",raw.optBoolean("invert",false));
        } catch (org.json.JSONException impossible) {
            throw new IllegalArgumentException("Could not normalize mask",impossible);
        }
    }

    /** Mutates only the transient, app-owned decoded bitmap, never the media file. */
    public static Bitmap apply(Bitmap image, JSONObject recipe) {
        if (recipe == null) return image;
        JSONObject spec = validate(recipe);
        if (image == null || image.isRecycled() || !image.isMutable()
                || image.getConfig() != Bitmap.Config.ARGB_8888)
            throw new IllegalArgumentException("Mask needs mutable decoded ARGB_8888 pixels");
        int width=image.getWidth(),height=image.getHeight();
        long pixels=(long)width*height;
        if (width<1||height<1||pixels>MAX_PIXELS)
            throw new IllegalArgumentException("Mask raster exceeds native 1 MP budget");
        int[] colors=new int[(int)pixels];
        image.getPixels(colors,0,width,0,0,width,height);
        final String kind=spec.optString("kind");
        final double feather=spec.optDouble("feather");
        final double position=spec.optDouble("position");
        final double radius=spec.optDouble("roundness");
        final boolean invert=spec.optBoolean("invert");
        final double smooth=Math.max(0.00000001,feather);
        for(int y=0;y<height;y++){
            double v=2*(y+.5)/height-1.0;
            for(int x=0;x<width;x++){
                int offset=y*width+x;
                int argb=colors[offset];
                int original=argb>>>24;
                if(original==0)continue;
                double u=2*(x+.5)/width-1.0;
                double sd;
                switch(kind){
                    case "ellipse":
                        sd=Math.sqrt(u*u+v*v)-0.91;break;
                    case "rectangle":
                        sd=Math.max(Math.abs(u)-0.93,Math.abs(v)-0.93);break;
                    case "round_rect": {
                        double px=Math.abs(u)-(0.93-radius),py=Math.abs(v)-(0.93-radius);
                        sd=Math.hypot(Math.max(px,0),Math.max(py,0))+
                            Math.min(Math.max(px,py),0)-radius;break;
                    }
                    case "linear_wipe":
                        // 0 means completely hidden, 1 means fully visible.
                        // A feather width is normalized to the output frame.
                        sd=((u+1)*0.5)-position;break;
                    default: throw new IllegalArgumentException("Unsupported mask");
                }
                double strength;
                if("linear_wipe".equals(kind)){
                    strength=feather==0?(sd<=0?1:0):clamp01((feather-sd)/(2*feather));
                    if(position<=0.000001)strength=0;
                    if(position>=0.999999)strength=1;
                }else strength=feather==0?(sd<=0?1:0):clamp01((smooth-sd)/(2*smooth));
                if(invert)strength=1-strength;
                int opacity=(int)Math.round(original*strength);
                colors[offset]=(argb&0x00ffffff)|(opacity<<24);
            }
        }
        image.setPixels(colors,0,width,0,0,width,height);
        return image;
    }

    private static double clamp01(double x){return Math.max(0,Math.min(1,x));}
}
