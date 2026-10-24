package com.rezoxnemesis.videostudio;

import android.util.Pair;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.util.Size;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.VideoCompositorFramePolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Opt-in native video-layer policy. Base input0 keeps its source clock/audio;
 * VIDEO inputs1..2 are silent, independently trimmed sequences. No image substitute.
 */
public final class StudioVideoCompositorSettings implements VideoCompositorFramePolicy {
    private final int outputWidth,outputHeight;
    private final List<StudioLayer> layers;
    private final List<StudioLayerMotion> motion;
    // Media3 supplies actual selected texture sizes, including16x16 gap frames,
    // before asking for each frame's settings on its GL thread.
    private List<Size> inputSizes;
    private static final OverlaySettings BASE=new StaticOverlaySettings.Builder().build();

    public StudioVideoCompositorSettings(int width,int height,List<StudioLayer> videos){
        if(width<=0||height<=0||videos==null||videos.isEmpty()||videos.size()>2)
            throw new IllegalArgumentException("One or two VIDEO layers and a valid output canvas are required");
        outputWidth=width;outputHeight=height;
        ArrayList<StudioLayer> copies=new ArrayList<>();ArrayList<StudioLayerMotion> samplers=new ArrayList<>();
        for(StudioLayer video:videos){
            if(video==null||!StudioLayer.VIDEO.equals(video.type))throw new IllegalArgumentException("VIDEO compositor requires video layers");
            video.validate();StudioLayer copy=video.copy();copies.add(copy);samplers.add(StudioLayerMotion.fromLayer(copy));
        }
        layers=Collections.unmodifiableList(copies);motion=Collections.unmodifiableList(samplers);
    }

    @Override public Size getOutputSize(List<Size> sizes){
        if(sizes==null||sizes.size()!=layers.size()+1)throw new IllegalArgumentException("Unexpected native VIDEO input sizes");
        ArrayList<Size> copy=new ArrayList<>();
        for(Size size:sizes){
            if(size==null||size.getWidth()<=0||size.getHeight()<=0)throw new IllegalArgumentException("Invalid native VIDEO texture size");
            copy.add(size);
        }
        inputSizes=Collections.unmodifiableList(copy);
        return new Size(outputWidth,outputHeight);
    }

    @Override public List<Integer> getInputIdsInTopToBottomOrder(List<Integer> inputIds){
        if(inputIds==null||inputIds.size()!=layers.size()+1||!Integer.valueOf(0).equals(inputIds.get(0)))
            throw new IllegalArgumentException("Native VIDEO requires unchanged primary input0");
        boolean[] seen=new boolean[layers.size()+1];
        for(Integer id:inputIds){
            if(id==null||id<0||id>=seen.length||seen[id])throw new IllegalArgumentException("Unexpected native VIDEO input ID");
            seen[id]=true;
        }
        ArrayList<Integer> order=new ArrayList<>();
        for(int id=layers.size();id>=0;id--)order.add(id);
        return Collections.unmodifiableList(order);
    }

    @Override public boolean isInputFrameEligibleForOutput(int id,long sourceUs,long outputUs){
        if(id==0)return true;
        StudioLayer layer=layer(id);
        // Active outputs may never select an adjacent opaque gap texture. The
        // compositor keeps nearest-frame selection among real source-phase frames.
        return !active(layer,outputUs)||active(layer,sourceUs);
    }

    @Override public OverlaySettings getOverlaySettings(int id,long outputUs){
        if(id==0)return BASE;
        StudioLayer layer=layer(id);boolean active=active(layer,outputUs);
        List<Size> sizes=inputSizes;
        if(sizes==null)throw new IllegalStateException("Native VIDEO texture dimensions are not configured");
        Size texture=sizes.get(id);
        StudioClipKeyframes.Values value=motion.get(id-1).atUs(active?outputUs-layer.startMs*1000L:0);
        // Preserve the existing IMAGE operation order and unbounded motion anchors.
        float x=(2*layer.x-1)+(float)(2*value.x),y=(1-2*layer.y)-(float)(2*value.y);
        float sx=(outputWidth*layer.width/texture.getWidth())*(float)value.scaleX;
        float sy=(outputHeight*layer.height/texture.getHeight())*(float)value.scaleY;
        return new Settings(x,y,sx,sy,layer.rotation+(float)value.rotate,
                active?layer.opacity*(float)value.opacity:0);
    }

    private StudioLayer layer(int id){
        if(id<1||id>layers.size())throw new IllegalArgumentException("Unknown native VIDEO overlay input");
        return layers.get(id-1);
    }
    private static boolean active(StudioLayer layer,long timeUs){
        return timeUs>=layer.startMs*1000L&&timeUs<(layer.startMs+layer.durationMs)*1000L;
    }
    private static final class Settings implements OverlaySettings {
        private final Pair<Float,Float> background,scale;
        private final float rotation,alpha;
        private Settings(float x,float y,float sx,float sy,float rotation,float alpha){
            background=Pair.create(x,y);scale=Pair.create(sx,sy);this.rotation=rotation;this.alpha=alpha;
        }
        @Override public Pair<Float,Float> getBackgroundFrameAnchor(){return background;}
        @Override public Pair<Float,Float> getOverlayFrameAnchor(){return Pair.create(0f,0f);}
        @Override public Pair<Float,Float> getScale(){return scale;}
        @Override public float getRotationDegrees(){return rotation;}
        @Override public float getAlphaScale(){return alpha;}
    }
}
