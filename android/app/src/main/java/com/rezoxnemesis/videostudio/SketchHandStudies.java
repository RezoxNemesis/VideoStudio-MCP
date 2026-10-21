package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import org.json.*;

/** App-owned, editable recipes shared by manual study loading and native capabilities. */
final class SketchHandStudies {
    static final String[] PATTERNS={"open_close","grasp","finger_cascade","thumb_opposition"};
    private SketchHandStudies(){}

    static JSONArray poses(String pattern,int frames,int fps,JSONObject input)throws Exception {
        if(!Arrays.asList(PATTERNS).contains(pattern))throw SketchDocument.bad("Unknown hand study");
        double seconds=frames/(double)fps;
        if(frames<24||frames>240||(fps!=24&&fps!=30&&fps!=60)||seconds<4||seconds>8)
            throw SketchDocument.bad("Hand studies need4..8seconds,24..240frames and24/30/60FPS; preserve the existing canvas and clock");
        JSONObject settings=SketchHandAnimation.settings(input);
        if(pattern.equals("open_close"))return SketchHandAnimation.preset(frames,fps,settings);
        JSONArray result=new JSONArray();int[] originalFrames={0,18,36,60,78,95};
        for(int n=0;n<originalFrames.length;n++){
            double amount=n==0||n==5?.3:n==1?.65:n<4?.9:.25;
            JSONObject pose=new JSONObject().put("frame",(int)Math.round(originalFrames[n]*(frames-1)/95d)).put("interpolation","smooth")
                    .put("spread",.25).put("thumbOpposition",amount*.75).put("thumbCurl",amount*.7).put("wristFlex",n==0||n==5?0:5);
            String[] fingers={"indexCurl","middleCurl","ringCurl","littleCurl"};
            for(int finger=0;finger<4;finger++){
                double curl=amount;if(pattern.equals("finger_cascade"))curl=n>finger&&n<5?.08:.8;
                if(pattern.equals("thumb_opposition"))curl=finger==0?amount*.6:.25;
                pose.put(fingers[finger],curl);
            }
            result.put(pose);
        }
        SketchHandAnimation.validatePoses(result,frames);return result;
    }

    /** Fresh minimal operations. Callers replace identities and select hand/view explicitly. */
    static JSONArray examples()throws Exception {
        JSONArray examples=new JSONArray();
        for(String pattern:PATTERNS){
            JSONObject settings=new JSONObject().put("view","front").put("handedness","right").put("size",.95).put("renderStyle","cel");
            JSONObject operation=new JSONObject().put("type","add_hand_animation").put("layerId","layer_1").put("handId","hand").put("settings",settings);
            if(!pattern.equals("open_close"))operation.put("poses",poses(pattern,96,24,settings));
            examples.put(new JSONObject().put("id",pattern).put("width",1280).put("height",720).put("frameCount",96).put("fps",24).put("operation",operation));
        }
        return examples;
    }
}
