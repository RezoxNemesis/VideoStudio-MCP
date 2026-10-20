package com.rezoxnemesis.videostudio;

/**
 * 3D film framing presets inspired by original video dimensions.
 * Uses existing bounded GPU raster profiles; no upscaled phantom detail.
 * The exact same dimensions are passed to native preview and export.
 */
final class CinematicOutputFraming {
    static final String PORTRAIT="portrait_9_16";
    static final String LANDSCAPE="landscape_16_9";
    static final String CLASSIC="classic_4_3";
    static final String[] MODES={PORTRAIT,LANDSCAPE,CLASSIC};
    static final String[] LABELS={
        "Portrait 9:16  •  720 × 1280",
        "Landscape 16:9  •  1280 × 720",
        "Classic animation 4:3  •  960 × 720"
    };
    private CinematicOutputFraming(){}
    static int[] dimensions(String mode){
        final int[] size;
        if(PORTRAIT.equals(mode))size=new int[]{720,1280};
        else if(LANDSCAPE.equals(mode))size=new int[]{1280,720};
        else if(CLASSIC.equals(mode))size=new int[]{960,720};
        else throw new IllegalArgumentException("Unrecognized cinematic framing preset");
        // Delegate actual GPU/bitmap safety policy to the production encoder.
        NativeCinematicSceneComposer.requireDimensions(size[0],size[1]);
        return size;
    }
    static String matching(int width,int height){
        for(String mode:MODES){
            int[] wh=dimensions(mode);
            if(wh[0]==width&&wh[1]==height)return mode;
        }
        throw new IllegalArgumentException("Unsupported cinematic preview/export dimensions");
    }
    static String label(String mode){
        for(int i=0;i<MODES.length;i++)if(MODES[i].equals(mode))return LABELS[i];
        throw new IllegalArgumentException("Unknown cinematic framing label");
    }
}
