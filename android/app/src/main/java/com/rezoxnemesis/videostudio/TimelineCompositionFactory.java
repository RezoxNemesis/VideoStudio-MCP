package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.GaussianBlur;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public final class TimelineCompositionFactory {
    private final Context context;
    private int frameRate = 30;
    public TimelineCompositionFactory(Context context) { this.context = context.getApplicationContext(); }

    Composition buildVideoWindow(ProjectStore.Project project,String aspect,String quality,TimelineWindow window){
        if(project==null||project.clips.isEmpty())throw new IllegalArgumentException("Timeline is empty");
        ProjectStore.Project captured=ProjectStore.Project.fromJson(project.snapshotJson());
        if(window.endUs>exactProgramDurationUs(captured))throw new IllegalArgumentException("Render window extends past the program");
        frameRate=captured.settings.optInt("fps",30);
        for(ProjectStore.Clip clip:captured.clips)if(captured.track(clip.trackId)==null)throw new IllegalArgumentException("Clip refers to a missing track: "+clip.id);
        boolean solo=false;for(ProjectStore.Track track:captured.tracks)solo|=track.solo;
        ArrayList<ProjectStore.Track> tracks=new ArrayList<>(captured.tracks);tracks.sort((a,b)->Integer.compare(b.order,a.order));
        ArrayList<EditedMediaItemSequence> sequences=new ArrayList<>();
        for(ProjectStore.Track track:tracks){
            if(track.audioOnly()||!track.visible||(solo&&!track.solo))continue;
            ArrayList<ProjectStore.Clip> clips=new ArrayList<>();for(ProjectStore.Clip clip:captured.clips)if(track.id.equals(clip.trackId))clips.add(clip);
            clips.sort(java.util.Comparator.comparingLong(c->c.startMs));ArrayList<WindowSlice> slices=new ArrayList<>();
            long cursorMs=0,cursorUs=0;
            for(ProjectStore.Clip clip:clips){
                if(clip.startMs<cursorMs)throw new IllegalArgumentException("Clips overlap on track "+track.name);
                cursorUs=Math.addExact(cursorUs,Math.multiplyExact(clip.startMs-cursorMs,1000L));
                ProjectStore.Asset asset=captured.asset(clip.assetId);if(asset==null||asset.mime==null)throw new IllegalArgumentException("Missing source for clip "+clip.id);
                long endUs=Math.addExact(cursorUs,itemOutputUs(clip,asset)),start=Math.max(window.startUs,cursorUs),end=Math.min(window.endUs,endUs);
                if(end>start)slices.add(new WindowSlice(asset,clip,cursorUs,start,end));
                cursorUs=endUs;cursorMs=TimelineMath.add(clip.startMs,clip.outputDurationMs());
            }
            for(String role:new String[]{"head","torso","lower","foreground","main"}){
                EditedMediaItemSequence.Builder sequence=new EditedMediaItemSequence.Builder(java.util.Collections.singleton(C.TRACK_TYPE_VIDEO));long positionUs=0;boolean used=false;
                for(WindowSlice slice:slices){
                    long begin=slice.startUs-window.startUs;if(begin>positionUs)sequence.addGap(begin-positionUs);
                    String uri="main".equals(role)?"":slice.clip.effects.optString(role+"Uri","");
                    if("foreground".equals(role)&&!slice.clip.effects.optString("headUri").isEmpty())uri="";
                    if(!slice.clip.effects.optBoolean("animatedScene"))uri="";
                    if("main".equals(role)&&!slice.asset.mime.startsWith("audio/")){
                        String background=slice.clip.effects.optBoolean("animatedScene")?slice.clip.effects.optString("backgroundUri",""):"";
                        if(!background.isEmpty())sequence.addItem(buildWindowLayer(background,slice,aspect,quality,"background",window));
                        else sequence.addItem(buildWindowItem(slice,aspect,quality,window));
                        used=true;
                    }else if(!uri.isEmpty()){sequence.addItem(buildWindowLayer(uri,slice,aspect,quality,role,window));used=true;}
                    else sequence.addGap(slice.endUs-slice.startUs);
                    positionUs=slice.endUs-window.startUs;
                }
                if(positionUs<window.durationUs())sequence.addGap(window.durationUs()-positionUs);
                if(used)sequences.add(sequence.build());
            }
        }
        ProjectStore.Asset black=new ProjectStore.Asset();black.uri=opaqueBlackUri();black.mime="image/png";
        ProjectStore.Clip base=new ProjectStore.Clip();base.outMs=(window.durationUs()+999)/1000;
        sequences.add(EditedMediaItemSequence.withVideoFrom(Collections.singletonList(buildItem(black,base,aspect,quality,true,false,true,0).buildUpon().setDurationUs(window.durationUs()).build())));
        return new Composition.Builder(sequences).build();
    }

    static long programDurationUs(ProjectStore.Project project){return exactProgramDurationUs(ProjectStore.Project.fromJson(project.snapshotJson()));}
    private static long exactProgramDurationUs(ProjectStore.Project captured){
        long end=Math.multiplyExact(captured.outputDurationMs(),1000L);
        boolean solo=false;for(ProjectStore.Track track:captured.tracks)solo|=track.solo;
        for(ProjectStore.Track track:captured.tracks){
            if(solo&&!track.solo)continue;
            ArrayList<ProjectStore.Clip> clips=new ArrayList<>();for(ProjectStore.Clip clip:captured.clips)if(track.id.equals(clip.trackId))clips.add(clip);
            clips.sort(java.util.Comparator.comparingLong(c->c.startMs));long cursorMs=0,cursorUs=0;
            for(ProjectStore.Clip clip:clips){
                if(clip.startMs<cursorMs)throw new IllegalArgumentException("Clips overlap on track "+track.name);
                ProjectStore.Asset asset=captured.asset(clip.assetId);if(asset==null||asset.mime==null)throw new IllegalArgumentException("Missing source for clip "+clip.id);
                cursorUs=Math.addExact(cursorUs,Math.multiplyExact(clip.startMs-cursorMs,1000L));cursorUs=Math.addExact(cursorUs,itemOutputUs(clip,asset));cursorMs=TimelineMath.add(clip.startMs,clip.outputDurationMs());
            }
            end=Math.max(end,cursorUs);
        }
        return end;
    }

    private static final class WindowSlice{
        final ProjectStore.Asset asset;final ProjectStore.Clip clip;final long originalStartUs,startUs,endUs;
        WindowSlice(ProjectStore.Asset asset,ProjectStore.Clip clip,long originalStartUs,long startUs,long endUs){this.asset=asset;this.clip=clip;this.originalStartUs=originalStartUs;this.startUs=startUs;this.endUs=endUs;}
    }
    private EditedMediaItem buildWindowLayer(String uri,WindowSlice slice,String aspect,String quality,String role,TimelineWindow window){
        MediaItem media=new MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs((slice.endUs-slice.startUs+999)/1000).build();
        return new EditedMediaItem.Builder(media).setDurationUs(slice.endUs-slice.startUs).setFrameRate(frameRate).setRemoveAudio(true).setEffects(new Effects(Collections.emptyList(),
                buildLayerEffects(slice.clip,aspect,quality,slice.clip.outputDurationMs(),slice.clip.effects.optJSONObject("animationSpec"),role,slice.originalStartUs-window.startUs))).build();
    }
    private EditedMediaItem buildWindowItem(WindowSlice slice,String aspect,String quality,TimelineWindow window){
        boolean image=slice.asset.mime.startsWith("image/");MediaItem.Builder media=new MediaItem.Builder().setUri(slice.asset.uri).setMimeType(slice.asset.mime);
        float speed=Math.abs(slice.clip.speed-1f)>.01f?Math.max(.25f,Math.min(4f,slice.clip.speed)):1f;
        if(image)media.setImageDurationMs((slice.endUs-slice.startUs+999)/1000);
        else{
            long sourceBase=Math.multiplyExact(slice.clip.inMs,1000L),sourceEnd=Math.multiplyExact(slice.clip.outMs,1000L);
            long start=Math.min(sourceEnd,Math.addExact(sourceBase,Math.round((slice.startUs-slice.originalStartUs)*(double)speed)));
            long end=Math.min(sourceEnd,Math.addExact(sourceBase,Math.round((slice.endUs-slice.originalStartUs)*(double)speed)));
            if(end<=start)throw new IllegalArgumentException("Render window contains no source samples for "+slice.clip.id);
            media.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionUs(start).setEndPositionUs(end).build());
        }
        EditedMediaItem.Builder item=new EditedMediaItem.Builder(media.build()).setRemoveAudio(true);
        if(image)item.setDurationUs(slice.endUs-slice.startUs).setFrameRate(frameRate);else{
            item.setDurationUs(sourceDurationUs(slice.asset));
            if(speed!=1f)item.setSpeed(new SpeedProvider(){public float getSpeed(long timeUs){return speed;}public long getNextSpeedChangeTimeUs(long timeUs){return C.TIME_UNSET;}});
        }
        return item.setEffects(new Effects(Collections.emptyList(),buildEffects(slice.clip,aspect,quality,Math.max(100,slice.clip.outMs-slice.clip.inMs),slice.originalStartUs-window.startUs))).build();
    }

    public Composition build(ProjectStore.Project original, String aspect, String quality, boolean useProxies) {
        if (original == null || original.clips.isEmpty()) throw new IllegalArgumentException("Timeline is empty");
        ProjectStore.Project project = ProjectStore.Project.fromJson(original.snapshotJson());
        for(ProjectStore.Clip c:project.clips)if(project.track(c.trackId)==null)throw new IllegalArgumentException("Clip refers to a missing track: "+c.id);
        frameRate = project.settings.optInt("fps", 30);
        if (useProxies) for (ProjectStore.Asset a : project.assets) a.uri = ProxyManager.previewUri(original, a);
        boolean solo = false;
        for (ProjectStore.Track t : project.tracks) solo |= t.solo;
        ArrayList<ProjectStore.Track> tracks = new ArrayList<>(project.tracks);
        tracks.sort((a, b) -> Integer.compare(b.order, a.order));
        ArrayList<EditedMediaItemSequence> sequences = new ArrayList<>();
        ArrayList<EditedMediaItemSequence> audioSequences = new ArrayList<>();
        long durationMs = project.outputDurationMs();
        for (ProjectStore.Track track : tracks) {
            if (solo && !track.solo) continue;
            ArrayList<ProjectStore.Clip> clips = new ArrayList<>();
            boolean audio = false, video = false, animated = false;
            java.util.HashSet<String> audible=new java.util.HashSet<>();
            for (ProjectStore.Clip c : project.clips) if (track.id.equals(c.trackId)) {
                ProjectStore.Asset a = project.asset(c.assetId);
                if (a == null) throw new IllegalArgumentException("Missing source for clip " + c.id);
                if (a.mime == null) throw new IllegalArgumentException("Unknown source format");
                clips.add(c);
                if(!track.muted&&hasSourceAudio(original.asset(a.id))){audio=true;audible.add(c.id);}
                video |= !track.audioOnly() && track.visible && !a.mime.startsWith("audio/");
                animated |= c.effects.optBoolean("animatedScene", false);
            }
            if (clips.isEmpty() || (!audio && !video)) continue;
            clips.sort(java.util.Comparator.comparingLong(c -> c.startMs));
            if(audio){
                EditedMediaItemSequence.Builder sound=new EditedMediaItemSequence.Builder(java.util.Collections.singleton(C.TRACK_TYPE_AUDIO));
                long cursor=0;
                for(ProjectStore.Clip c:clips){
                    if(c.startMs<cursor)throw new IllegalArgumentException("Clips overlap on track "+track.name);
                    if(c.startMs>cursor)sound.addGap(Math.multiplyExact(c.startMs-cursor,1000L));
                    long length=c.outputDurationMs();
                    if(audible.contains(c.id))sound.addItem(buildItem(original.asset(c.assetId),c,aspect,quality,false,true,false));
                    else sound.addGap(itemOutputUs(c,project.asset(c.assetId)));
                    cursor=TimelineMath.add(c.startMs,length);
                }
                if(cursor<durationMs)sound.addGap(Math.multiplyExact(durationMs-cursor,1000L));
                audioSequences.add(sound.build());
            }
            if(!video)continue;
            if (animated && video) {
                for (String role : new String[]{"head", "torso", "lower", "foreground"}) {
                    EditedMediaItemSequence.Builder layer = new EditedMediaItemSequence.Builder(java.util.Collections.singleton(C.TRACK_TYPE_VIDEO));
                    long cursor = 0, cursorUs=0; boolean used = false;
                    for (ProjectStore.Clip c : clips) {
                        if(c.startMs>cursor){long gapUs=Math.multiplyExact(c.startMs-cursor,1000L);layer.addGap(gapUs);cursorUs=Math.addExact(cursorUs,gapUs);}
                        boolean articulated = !c.effects.optString("headUri").isEmpty();
                        String uri = "foreground".equals(role) && articulated ? "" : c.effects.optString(role + "Uri", "");
                        long length = c.outputDurationMs();
                        if (!uri.isEmpty()) {
                            layer.addItem(buildLayerItem(uri,c,aspect,quality,length,c.effects.optJSONObject("animationSpec"),role,cursorUs)); used = true;
                        }else layer.addGap(itemOutputUs(c,project.asset(c.assetId)));
                        cursorUs=Math.addExact(cursorUs,itemOutputUs(c,project.asset(c.assetId)));
                        cursor = TimelineMath.add(c.startMs, length);
                    }
                    if (cursor < durationMs) layer.addGap(Math.multiplyExact(durationMs - cursor, 1000L));
                    if (used) sequences.add(layer.build());
                }
            }
            // Keep media types in separate sequences. Mixed forced A/V gaps in
            // Media3 1.11.1 can dereference an unready synthetic audio consumer.
            EditedMediaItemSequence.Builder sequence = new EditedMediaItemSequence.Builder(java.util.Collections.singleton(C.TRACK_TYPE_VIDEO));
            long cursor = 0,cursorUs=0;
            for (ProjectStore.Clip c : clips) {
                if (c.startMs < cursor) throw new IllegalArgumentException("Clips overlap on track " + track.name);
                if(c.startMs>cursor){long gapUs=Math.multiplyExact(c.startMs-cursor,1000L);sequence.addGap(gapUs);cursorUs=Math.addExact(cursorUs,gapUs);}
                ProjectStore.Asset a = project.asset(c.assetId);
                String background = c.effects.optString("backgroundUri", "");
                if (video && c.effects.optBoolean("animatedScene", false) && !background.isEmpty())
                    sequence.addItem(buildLayerItem(background,c,aspect,quality,c.outputDurationMs(),c.effects.optJSONObject("animationSpec"),"background",cursorUs));
                else sequence.addItem(buildItem(a,c,aspect,quality,a.mime.startsWith("image/"),false,true,cursorUs));
                cursorUs=Math.addExact(cursorUs,itemOutputUs(c,a));
                cursor = TimelineMath.add(c.startMs, c.outputDurationMs());
            }
            if (cursor < durationMs) sequence.addGap(Math.multiplyExact(durationMs - cursor, 1000L));
            sequences.add(sequence.build());
        }
        if (sequences.isEmpty()&&audioSequences.isEmpty()) throw new IllegalArgumentException("No visible or audible tracks");
        // Encoders have no alpha channel. A real opaque base makes the compositor
        // blend transparent images/effects instead of simply discarding alpha.
        ProjectStore.Asset black=new ProjectStore.Asset();black.uri=opaqueBlackUri();black.mime="image/png";black.name="Program background";
        ProjectStore.Clip base=new ProjectStore.Clip();base.outMs=durationMs;
        sequences.add(EditedMediaItemSequence.withVideoFrom(java.util.Collections.singletonList(buildItem(black,base,aspect,quality,true,false,true))));
        sequences.addAll(audioSequences);
        return new Composition.Builder(sequences).build();
    }

    static long itemOutputUs(ProjectStore.Clip clip,ProjectStore.Asset asset){
        if(asset.mime.startsWith("image/")||clip.effects.optBoolean("animatedScene"))return Math.multiplyExact(Math.max(1,clip.outputDurationMs()),1000L);
        long clipped=Math.multiplyExact(Math.max(100,clip.outMs-clip.inMs),1000L);
        float speed=Math.abs(clip.speed-1f)>.01f?Math.max(.25f,Math.min(4f,clip.speed)):1f;
        return androidx.media3.common.util.Util.getPlayoutDurationForMediaDuration(clipped,speed);
    }
    private long sourceDurationUs(ProjectStore.Asset asset){
        long durationMs=Math.max(asset.durationMs,asset.generationMetadata.optLong("containerDurationMs",0));
        if(durationMs<=0)try{durationMs=MediaTrackProbe.inspect(context.getContentResolver(),Uri.parse(asset.uri)).getLong("containerDurationMs");}
        catch(Exception unavailable){throw new IllegalArgumentException("Cannot read original duration for "+asset.name,unavailable);}
        if(durationMs<=0)throw new IllegalArgumentException("Original source duration is unavailable for "+asset.name);
        return Math.multiplyExact(durationMs,1000L);
    }
    private String opaqueBlackUri(){
        java.io.File file=new java.io.File(context.getCacheDir(),"studio_opaque_black_v1.png");
        synchronized(TimelineCompositionFactory.class){
            if(!file.isFile()){
                android.graphics.Bitmap pixel=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888);pixel.eraseColor(android.graphics.Color.BLACK);
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){if(!pixel.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("Background image compression failed");}
                catch(java.io.IOException error){throw new IllegalStateException("Program background is unavailable",error);}finally{pixel.recycle();}
            }
        }
        return Uri.fromFile(file).toString();
    }

    private boolean hasSourceAudio(ProjectStore.Asset asset){
        if(asset.mime.startsWith("audio/"))return true;if(!asset.mime.startsWith("video/"))return false;
        if(asset.generationMetadata.optBoolean("audioMetadataKnown"))return asset.generationMetadata.optBoolean("hasAudio");
        try{return MediaTrackProbe.inspect(context.getContentResolver(),Uri.parse(asset.uri)).getBoolean("hasAudio");}
        catch(Exception error){throw new IllegalArgumentException("Could not inspect audio tracks for "+asset.name+": "+error.getMessage(),error);}
    }

    private EditedMediaItem buildLayerItem(String uri,
                                           ProjectStore.Clip clip,
                                           String aspect,
                                           String quality,
                                           long durationMs,
                                           JSONObject animationSpec,
                                           String layerRole,long sequenceStartUs) {
        MediaItem media = new MediaItem.Builder()
                .setUri(Uri.parse(uri))
                .setMimeType(MimeTypes.IMAGE_PNG)
                .setImageDurationMs(durationMs)
                .build();

        EditedMediaItem.Builder item = new EditedMediaItem.Builder(media)
                .setFrameRate(frameRate)
                .setRemoveAudio(true);

        List<Effect> video = buildLayerEffects(clip,aspect,quality,durationMs,animationSpec,layerRole,sequenceStartUs);
        item.setEffects(new Effects(Collections.emptyList(), video));
        return item.build();
    }

    private List<Effect> buildLayerEffects(ProjectStore.Clip clip,
                                           String aspect,
                                           String quality,
                                           long durationMs,
                                           JSONObject animationSpec,
                                           String layerRole,long sequenceStartUs) {
        ArrayList<Effect> effects = new ArrayList<>();
        JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;

        effects.add(Presentation.createForAspectRatio(aspectRatio(aspect), Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
        int height = "720p".equalsIgnoreCase(quality)
                ? ("16:9".equals(aspect) ? 720 : 1280)
                : ("16:9".equals(aspect) ? 1080 : 1920);
        if ("540p".equalsIgnoreCase(quality)) height = 540;
        if ("360p".equalsIgnoreCase(quality)) height = 360;
        effects.add(Presentation.createForHeight(height));

        // Keep a safety overscan so parallax never reveals the edge of a plate.
        float overscan = "background".equals(layerRole) ? 1.10f : 1.035f;
        effects.add(new ScaleAndRotateTransformation.Builder()
                .setScale(overscan, overscan)
                .build());

        applyColourEffects(effects,fx,clip,sequenceStartUs);

        applyBlurEffects(effects,fx);

        JSONObject renderSpec=animationSpec;
        if(animationSpec!=null&&(fx.optBoolean("motionStyleOverride")||fx.optBoolean("transitionStyleOverride"))){
            try{
                renderSpec=new JSONObject(animationSpec.toString());
                if(fx.optBoolean("motionStyleOverride")){
                    renderSpec.remove("keyframes");renderSpec.put("cameraPreset",fx.optString("motionPreset","none"));
                }
                if(fx.optBoolean("transitionStyleOverride"))renderSpec.put("transitionPreset",clip.transition);
            }catch(Exception invalid){throw new IllegalArgumentException("Invalid animation style override",invalid);}
        }
        String preset = renderSpec == null
                ? fx.optString("motionPreset", "push_in")
                : renderSpec.optString("cameraPreset", fx.optString("motionPreset", "push_in"));
        long durationUs = Math.max(100_000L, durationMs * 1000L);
        effects.add(new MotionMatrixEffect(
                preset,
                durationUs,
                Math.min(320_000L, Math.max(180_000L, durationUs / 12)),
                renderSpec,
                layerRole,sequenceStartUs
        ));

        // Atmosphere is drawn only once on the topmost subject sequence.
        // Articulated renders use the head layer; older layered projects use
        // the single foreground layer.
        if (("head".equals(layerRole) || "foreground".equals(layerRole)) && animationSpec != null) {
            String environment = animationSpec.optString("environmentMotion", "ambient_drift");
            double atmosphere = animationSpec.optDouble("atmosphereIntensity", .42);
            effects.add(new OverlayEffect(Collections.singletonList(
                    new AtmosphereOverlay(environment,atmosphere,durationUs,sequenceStartUs)
            )));
        }
        effects.add(new ClipTransformEffect(clip,sequenceStartUs));
        effects.add(new ClipOpacityEffect(clip,sequenceStartUs));
        return effects;
    }

    private void applyBlurEffects(List<Effect> effects,JSONObject fx){
        String preset=fx.optString("effectPreset",fx.optString("colorPreset",""));
        double blur=fx.optDouble("blur",0);
        if("gaussian_blur".equals(preset))blur=Math.max(blur,5);
        if("soft_glow".equals(preset)||"dream".equals(preset))blur=Math.max(blur,1.6);
        if(blur>.1)effects.add(new GaussianBlur((float)Math.min(18,blur)));
    }

    private void applyColourEffects(List<Effect> effects,JSONObject fx,ProjectStore.Clip clip,long sequenceStartUs){
        boolean animated=false;
        for(int i=0;i<clip.keyframes.length();i++){
            JSONObject frame=clip.keyframes.optJSONObject(i);if(frame==null)continue;
            String property=frame.optString("property");
            animated |= "brightness".equals(property)||"contrast".equals(property)||"saturationAdjust".equals(property)||"lightnessAdjust".equals(property);
        }
        if(animated){effects.add(new ClipColourEffect(clip,sequenceStartUs));return;}

        double brightness = fx.optDouble("brightness", 0);
        double contrast = fx.optDouble("contrast", 0);
        double saturation = fx.optDouble("saturationAdjust", fx.optDouble("saturation", 0));
        double lightness = fx.optDouble("lightnessAdjust", 0);
        String preset = fx.optString("colorPreset", fx.optString("effectPreset", ""));
        if (!preset.isEmpty() && !"none".equals(preset)) {
            JSONObject p = CreatorCatalog.effectPreset(preset);
            if (!fx.has("brightness")) brightness = p.optDouble("brightness", brightness);
            if (!fx.has("contrast")) contrast = p.optDouble("contrast", contrast);
            if (!fx.has("saturationAdjust") && !fx.has("saturation")) saturation = p.optDouble("saturationAdjust", saturation);
            if (!fx.has("lightnessAdjust")) lightness = p.optDouble("lightnessAdjust", lightness);
        }

        brightness = clamp(brightness, -1, 1);
        contrast = clamp(contrast, -1, 1);
        saturation = clamp(saturation, -100, 100);
        lightness = clamp(lightness, -100, 100);
        if (Math.abs(brightness) > .001) effects.add(new Brightness((float) brightness));
        if (Math.abs(contrast) > .001) effects.add(new Contrast((float) contrast));
        if (Math.abs(saturation) > .001 || Math.abs(lightness) > .001) {
            effects.add(new HslAdjustment.Builder()
                    .adjustSaturation((float) saturation)
                    .adjustLightness((float) lightness)
                    .build());
        }
    }

    private EditedMediaItem buildItem(ProjectStore.Asset asset, ProjectStore.Clip clip, String aspect, String quality, boolean image, boolean audioOnly, boolean removeAudio) {
        return buildItem(asset,clip,aspect,quality,image,audioOnly,removeAudio,Math.multiplyExact(Math.max(0,clip.startMs),1000L));
    }
    private EditedMediaItem buildItem(ProjectStore.Asset asset,ProjectStore.Clip clip,String aspect,String quality,boolean image,boolean audioOnly,boolean removeAudio,long sequenceStartUs){
        long inputDurationMs = Math.max(100, clip.outMs - clip.inMs);
        MediaItem.Builder media = new MediaItem.Builder().setUri(Uri.parse(asset.uri)).setMimeType(asset.mime);

        if (image) {
            media.setImageDurationMs(Math.max(1, clip.outputDurationMs()));
        } else {
            media.setClippingConfiguration(
                    new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(Math.max(0, clip.inMs))
                            .setEndPositionMs(Math.max(clip.inMs + 100, clip.outMs))
                            .build());
        }

        EditedMediaItem.Builder edited = new EditedMediaItem.Builder(media.build());
        if(image)edited.setFrameRate(frameRate);else edited.setDurationUs(sourceDurationUs(asset));

        if (!image && Math.abs(clip.speed - 1f) > .01f) {
            final float speed = Math.max(.25f, Math.min(4f, clip.speed));
            edited.setSpeed(new SpeedProvider() {
                @Override public float getSpeed(long timeUs) { return speed; }
                @Override public long getNextSpeedChangeTimeUs(long timeUs) { return C.TIME_UNSET; }
            });
        }

        ArrayList<AudioProcessor> audio = new ArrayList<>();
        if (!removeAudio && !image) audio.add(new ClipAudioProcessor(clip));
        List<Effect> video = audioOnly ? Collections.emptyList() : buildEffects(clip,aspect,quality,inputDurationMs,sequenceStartUs);
        edited.setEffects(new Effects(audio, video));
        edited.setRemoveVideo(audioOnly);
        if (removeAudio || image) edited.setRemoveAudio(true);
        return edited.build();
    }

    private List<Effect> buildEffects(ProjectStore.Clip clip,String aspect,String quality,long inputDurationMs,long sequenceStartUs){
        ArrayList<Effect> effects = new ArrayList<>();
        JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;

        float targetAspect = aspectRatio(aspect);
        effects.add(Presentation.createForAspectRatio(targetAspect, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
        int height = "720p".equalsIgnoreCase(quality) ? ("16:9".equals(aspect) ? 720 : 1280) : ("16:9".equals(aspect) ? 1080 : 1920);
        if ("540p".equalsIgnoreCase(quality)) height = 540;
        if ("360p".equalsIgnoreCase(quality)) height = 360;
        effects.add(Presentation.createForHeight(height));

        JSONObject proceduralGraph = fx.optJSONObject("proceduralScene");
        if (proceduralGraph != null) {
            try {
                effects.add(new OverlayEffect(Collections.singletonList(new ProceduralSceneOverlay(
                        proceduralGraph,Math.max(100_000,clip.outputDurationMs()*1000L),sequenceStartUs))));
            } catch (Exception error) { throw new IllegalArgumentException("Invalid procedural scene", error); }
        }

        applyColourEffects(effects,fx,clip,sequenceStartUs);
        applyBlurEffects(effects,fx);

        effects.add(new ClipTransformEffect(clip,sequenceStartUs));
        float cropLeft=(float)fx.optDouble("cropLeft"), cropRight=(float)fx.optDouble("cropRight");
        float cropTop=(float)fx.optDouble("cropTop"), cropBottom=(float)fx.optDouble("cropBottom");
        if(cropLeft+cropRight+cropTop+cropBottom>0)
            effects.add(new androidx.media3.effect.Crop(-1+2*cropLeft,1-2*cropRight,-1+2*cropBottom,1-2*cropTop));
        if(!clip.title.isEmpty()) {
            effects.add(new OverlayEffect(Collections.singletonList(new ClipTitleOverlay(clip,sequenceStartUs))));
        }

        String motion = fx.optString("motionPreset", "none");
        String transition = clip.transition == null ? "none" : clip.transition;
        if (!"none".equals(motion) || (!"none".equals(transition) && !"cut".equals(transition))) {
            String matrixPreset = "none".equals(motion) ? transition : motion;
            effects.add(new MotionMatrixEffect(matrixPreset,Math.max(100_000,clip.outputDurationMs()*1000L),280_000,null,"flat",sequenceStartUs));
        }
        effects.add(new ClipOpacityEffect(clip,sequenceStartUs));
        return effects;
    }

    private static float aspectRatio(String aspect) {
        if ("16:9".equals(aspect)) return 16f/9f;
        if ("1:1".equals(aspect)) return 1f;
        if ("4:5".equals(aspect)) return 4f/5f;
        return 9f/16f;
    }
    private static double clamp(double v,double min,double max){return Math.max(min,Math.min(max,v));}
}
