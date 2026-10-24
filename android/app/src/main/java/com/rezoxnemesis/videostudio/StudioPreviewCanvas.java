package com.rezoxnemesis.videostudio;

/** Native editor preview ratios accepted by the same Media3 export path. */
public final class StudioPreviewCanvas {
    public static final String[] ASPECTS={"9:16","16:9","1:1","4:5"};
    private StudioPreviewCanvas(){}
    public static String coerce(String choice){
        if(choice!=null)for(String supported:ASPECTS)
            if(supported.equals(choice))return supported;
        return "9:16";
    }
    public static float ratio(String aspect){
        switch(coerce(aspect)){
            case "16:9": return 16f/9f;
            case "1:1": return 1f;
            case "4:5": return 4f/5f;
            default: return 9f/16f;
        }
    }
}
