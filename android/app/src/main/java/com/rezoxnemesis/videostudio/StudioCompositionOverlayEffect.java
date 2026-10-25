package com.rezoxnemesis.videostudio;

import android.content.Context;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextureOverlay;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * Immutable timed-layer recipe, with independent resource ownership for every
 * Media3 shader-program generation. Per-clip effect changes may retire one
 * program and request another from this same composition effect.
 */
final class StudioCompositionOverlayEffect implements GlEffect {
    private final Context context;
    private final List<Entry> entries;

    StudioCompositionOverlayEffect(Context context,ProjectStore.Project project){
        if(context==null||project==null)throw new IllegalArgumentException("Composition context and project required");
        this.context=context.getApplicationContext();
        ArrayList<Entry> snapshot=new ArrayList<>();
        java.util.HashMap<String,StudioLayer> groups=new java.util.HashMap<>();
        for(StudioLayer layer:project.studioLayers)
            if(StudioLayer.GROUP.equals(layer.type))groups.put(layer.id,layer.copy());
        for(StudioLayer layer:project.studioLayers){
            if(StudioLayer.GROUP.equals(layer.type))continue;
            StudioLayer group=layer.parentId==null||layer.parentId.isEmpty()?null:groups.get(layer.parentId);
            if(layer.parentId!=null&&!layer.parentId.isEmpty()&&group==null)
                throw new IllegalArgumentException("Animated child has no valid motion group");
            if(StudioLayer.TITLE.equals(layer.type))snapshot.add(new Entry(layer,null,group));
            else if(StudioLayer.IMAGE.equals(layer.type))snapshot.add(new Entry(layer,project.asset(layer.assetId),group));
            else if(StudioLayer.SHAPE.equals(layer.type)||StudioLayer.VFX.equals(layer.type))snapshot.add(new Entry(layer,null,group));
        }
        entries=Collections.unmodifiableList(snapshot);
    }

    boolean isEmpty(){return entries.isEmpty();}

    /** Each caller owns and must release this fresh generation's native overlays. */
    List<TextureOverlay> createOverlays(){
        ArrayList<TextureOverlay> overlays=new ArrayList<>();
        try{
            int glyphOverlays=0;
            for(int index=0;index<entries.size();){
                Entry entry=entries.get(index);
                if(StudioLayer.TITLE.equals(entry.layer.type)
                        &&entry.layer.glyphAnimation==null){
                    // Preserve native z-order. Only merge consecutive TITLE rows
                    // whose exact time windows cannot overlap. Each virtual slot
                    // dynamically switches between their existing styled TextOverlays.
                    ArrayList<StudioLayer> run=new ArrayList<>();
                    ArrayList<StudioLayer> parents=new ArrayList<>();
                    long end=-1;
                    int next=index;
                    while(next<entries.size()
                            &&run.size()<StudioVirtualizedTitleOverlay.MAX_TITLES_PER_TEXTURE){
                        Entry lane=entries.get(next);
                        if(!StudioLayer.TITLE.equals(lane.layer.type)
                                ||lane.layer.glyphAnimation!=null
                                ||(!run.isEmpty()&&lane.layer.startMs<end))break;
                        run.add(lane.layer);parents.add(lane.parent);
                        end=Math.addExact(lane.layer.startMs,lane.layer.durationMs);
                        next++;
                    }
                    if(run.size()>=4)overlays.add(new StudioVirtualizedTitleOverlay(run,parents));
                    else for(int i=0;i<run.size();i++)
                        overlays.add(new StudioTimedTitleOverlay(run.get(i),parents.get(i)));
                    index=next;
                    continue;
                }
                if(StudioLayer.TITLE.equals(entry.layer.type)){
                    int count=StudioGlyphAnimation.visibleGlyphCount(entry.layer.text);
                    glyphOverlays+=count;
                    if(glyphOverlays>StudioGlyphAnimation.MAX_COMPOSITION_GLYPHS)
                        throw new IllegalArgumentException("Maximum 48 independently animated glyphs per composition");
                    overlays.addAll(StudioTimedGlyphOverlay.expand(entry.layer,entry.parent));
                }else if(StudioLayer.SHAPE.equals(entry.layer.type))
                    overlays.add(new StudioTimedShapeOverlay(entry.layer,entry.parent));
                else if(StudioLayer.VFX.equals(entry.layer.type))
                    overlays.add(new StudioProceduralVfxOverlay(entry.layer));
                else overlays.add(new StudioTimedImageOverlay(context,entry.asset,entry.layer,entry.parent));
                index++;
            }
            return overlays;
        }catch(RuntimeException|Error failure){
            releaseNew(overlays,failure);throw failure;
        }
    }

    @Override public GlShaderProgram toGlShaderProgram(Context context,boolean useHdr)
            throws VideoFrameProcessingException {
        List<TextureOverlay> overlays=createOverlays();
        try{
            return new OverlayEffect(overlays).toGlShaderProgram(context,useHdr);
        }catch(VideoFrameProcessingException|RuntimeException|Error failure){
            releaseNew(overlays,failure);throw failure;
        }
    }

    private static void releaseNew(List<TextureOverlay> overlays,Throwable failure){
        for(TextureOverlay overlay:overlays){
            try{overlay.release();}
            catch(VideoFrameProcessingException|RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
        }
    }

    private static final class Entry {
        final StudioLayer layer,parent;
        final ProjectStore.Asset asset;
        Entry(StudioLayer source,ProjectStore.Asset image,StudioLayer motionGroup){
            layer=source.copy();
            parent=motionGroup==null?null:motionGroup.copy();
            if(StudioLayer.IMAGE.equals(layer.type)){
                if(image==null||image.mime==null||!image.mime.startsWith("image/")
                        ||image.uri==null||image.uri.isEmpty()||!image.id.equals(layer.assetId))
                    throw new IllegalArgumentException("Project-owned image layer required");
                try{asset=ProjectStore.Asset.fromJson(new JSONObject(image.toJson().toString()));}
                catch(org.json.JSONException error){throw new IllegalArgumentException("Could not snapshot composition image identity",error);}
            }else asset=null;
        }
    }
}
