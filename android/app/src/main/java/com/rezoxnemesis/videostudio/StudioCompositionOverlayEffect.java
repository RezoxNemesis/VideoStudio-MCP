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

    /**
     * Conservative GLES 2.0 fragment texture budget: reserve input frame plus
     * one spare slot. The GLES guaranteed minimum is eight texture image units.
     * At no point do we compile 28 TITLE or 48 letter samplers into one shader.
     */
    static final int MAX_OVERLAYS_PER_SHADER=6;

    private interface OverlayRecipe {
        TextureOverlay create();
    }

    /**
     * Compile source-order immutable recipes, not GPU textures. Each shader
     * generation materializes fresh owned overlays, preserving the existing
     * lifetime protection at cuts and scene/context rebuilds.
     */
    private List<OverlayRecipe> recipes(){
        ArrayList<OverlayRecipe> out=new ArrayList<>();
        int glyphOverlays=0;
        for(int index=0;index<entries.size();){
            Entry entry=entries.get(index);
            if(StudioLayer.TITLE.equals(entry.layer.type)
                    &&entry.layer.glyphAnimation==null){
                ArrayList<StudioLayer> run=new ArrayList<>();
                ArrayList<StudioLayer> parents=new ArrayList<>();
                long end=-1;
                int next=index;
                while(next<entries.size()
                        &&run.size()<StudioVirtualizedTitleOverlay.MAX_TITLES_PER_TEXTURE){
                    Entry nextEntry=entries.get(next);
                    if(!StudioLayer.TITLE.equals(nextEntry.layer.type)
                            ||nextEntry.layer.glyphAnimation!=null
                            ||(!run.isEmpty()&&nextEntry.layer.startMs<end))break;
                    run.add(nextEntry.layer);parents.add(nextEntry.parent);
                    end=Math.addExact(nextEntry.layer.startMs,nextEntry.layer.durationMs);
                    next++;
                }
                if(run.size()>=4){
                    final List<StudioLayer> c=Collections.unmodifiableList(new ArrayList<>(run));
                    final List<StudioLayer> p=Collections.unmodifiableList(new ArrayList<>(parents));
                    out.add(()->new StudioVirtualizedTitleOverlay(c,p));
                }else{
                    for(int i=0;i<run.size();i++){
                        final StudioLayer caption=run.get(i),parent=parents.get(i);
                        out.add(()->new StudioTimedTitleOverlay(caption,parent));
                    }
                }
                index=next;
                continue;
            }
            if(StudioLayer.TITLE.equals(entry.layer.type)){
                int codepoints=StudioGlyphAnimation.glyphCount(entry.layer.text);
                for(int at=0;at<codepoints;at++){
                    String codepoint=StudioGlyphAnimation.glyphAt(entry.layer.text,at);
                    if(Character.isWhitespace(codepoint.codePointAt(0)))continue;
                    if(++glyphOverlays>StudioGlyphAnimation.MAX_COMPOSITION_GLYPHS)
                        throw new IllegalArgumentException("Maximum 48 independently animated glyphs per composition");
                    final int letter=at;
                    out.add(()->StudioTimedGlyphOverlay.single(entry.layer,entry.parent,letter));
                }
            }else if(StudioLayer.SHAPE.equals(entry.layer.type))
                out.add(()->new StudioTimedShapeOverlay(entry.layer,entry.parent));
            else if(StudioLayer.VFX.equals(entry.layer.type))
                out.add(()->new StudioProceduralVfxOverlay(entry.layer));
            else out.add(()->new StudioTimedImageOverlay(context,entry.asset,entry.layer,entry.parent));
            index++;
        }
        return Collections.unmodifiableList(out);
    }

    /** Preserve the exact shader z-order with bounded sequential render passes. */
    List<GlEffect> batchedEffects(){
        List<OverlayRecipe> compiled=recipes();
        ArrayList<GlEffect> passes=new ArrayList<>();
        for(int i=0;i<compiled.size();i+=MAX_OVERLAYS_PER_SHADER){
            int end=Math.min(compiled.size(),i+MAX_OVERLAYS_PER_SHADER);
            passes.add(new Pass(new ArrayList<>(compiled.subList(i,end))));
        }
        return Collections.unmodifiableList(passes);
    }

    /** Metadata-only budget proof; GPU creation is deferred until playback/export. */
    List<Integer> plannedPassSizes(){
        int count=recipes().size();
        ArrayList<Integer> sizes=new ArrayList<>();
        for(int i=0;i<count;i+=MAX_OVERLAYS_PER_SHADER)
            sizes.add(Math.min(MAX_OVERLAYS_PER_SHADER,count-i));
        return sizes;
    }

    /** Regression/test helper; never caches a TextureOverlay across shader instances. */
    List<TextureOverlay> createOverlays(){
        return materialize(recipes());
    }

    private static List<TextureOverlay> materialize(List<OverlayRecipe> compiled){
        ArrayList<TextureOverlay> owned=new ArrayList<>();
        try{
            for(OverlayRecipe source:compiled)owned.add(source.create());
            return owned;
        }catch(RuntimeException|Error failure){
            releaseNew(owned,failure);throw failure;
        }
    }

    @Override public GlShaderProgram toGlShaderProgram(Context context,boolean useHdr)
            throws VideoFrameProcessingException {
        return shader(context,useHdr,recipes());
    }

    private static GlShaderProgram shader(Context context,boolean useHdr,
                                          List<OverlayRecipe> compiled)
            throws VideoFrameProcessingException{
        List<TextureOverlay> owned=materialize(compiled);
        try {
            return new OverlayEffect(owned).toGlShaderProgram(context,useHdr);
        }catch(VideoFrameProcessingException|RuntimeException|Error failure){
            releaseNew(owned,failure);throw failure;
        }
    }

    private static final class Pass implements GlEffect {
        final List<OverlayRecipe> compiled;
        Pass(List<OverlayRecipe> entries){
            if(entries.isEmpty()||entries.size()>MAX_OVERLAYS_PER_SHADER)
                throw new IllegalArgumentException("Unsafe compositor sampler budget");
            compiled=Collections.unmodifiableList(new ArrayList<>(entries));
        }
        @Override public GlShaderProgram toGlShaderProgram(Context context,boolean useHdr)
                throws VideoFrameProcessingException{
            return shader(context,useHdr,compiled);
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
