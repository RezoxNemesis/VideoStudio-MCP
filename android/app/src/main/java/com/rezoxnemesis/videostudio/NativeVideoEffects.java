package com.rezoxnemesis.videostudio;

import androidx.media3.common.Effect;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.Crop;
import androidx.media3.effect.GaussianBlur;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** One effect compiler for source-clock previews and output-clock final renders. */
@UnstableApi
public final class NativeVideoEffects {
    private static final List<String> GRADES = Arrays.asList("cinematic", "teal_orange", "warm_film",
            "cool_night", "noir", "high_contrast", "soft_portrait", "matte", "golden_hour", "cyberpunk");
    private static final List<String> PIXEL_PRESETS = Arrays.asList("film_grain", "vignette", "sharpen",
            "clarity", "gaussian_blur", "soft_glow", "bloom", "dream", "scanlines", "posterize", "pixelate",
            "rgb_split", "chromatic_aberration");
    private static final List<String> STACK_TYPES = Arrays.asList("grade", "color", "brightness", "contrast",
            "hsl", "blur", "transform", "crop", "mask", "chroma_key", "pixel", "preset", "motion", "keyframes");

    private NativeVideoEffects() {}

    public static boolean supportsPreset(String preset) {
        return preset == null || preset.isEmpty() || "none".equals(preset)
                || GRADES.contains(preset) || PIXEL_PRESETS.contains(preset);
    }

    public static boolean supportsStackType(String type) { return STACK_TYPES.contains(type); }

    public static JSONObject resolvedSettings(ProjectStore.Clip clip) {
        JSONObject fx=copy(clip.effects);
        String color=fx.optString("colorPreset","none"),effect=fx.optString("effectPreset","none");
        if(!"none".equals(color)&&!color.isEmpty())applyPreset(fx,color);
        if("none".equals(color)||!GRADES.contains(effect))applyPreset(fx,effect);
        return fx;
    }

    /** Operations that a plain ImageView + motion/ColorMatrix preview cannot reproduce.
     * A luma-based ColorMatrix saturation is an approximation to the native HSL effect.
     */
    public static List<String> stillPreviewUnsupported(ProjectStore.Clip clip) {
        ArrayList<String> missing=new ArrayList<>(unsupported(clip));
        if(!missing.isEmpty())return missing;
        JSONObject fx=resolvedSettings(clip);
        for(String key:Arrays.asList("blur","grain","scanlines","posterize","pixelSize","rgbSplit","sharpen","glow","vignette"))
            if(fx.optDouble(key,0)>0)missing.add(key+" pixel effect");
        if(fx.optBoolean("chromaKey",false))missing.add("chroma key pixel effect");
        if(!Arrays.asList("none","rect").contains(fx.optString("mask","none")))missing.add("feathered shape mask");
        if(Arrays.asList("dip_black","dip_white").contains(clip.transition))missing.add("dip color transition");
        if(fx.optJSONObject("crop")!=null)missing.add("authored source crop");
        if(stack(fx)!=null&&stack(fx).length()>0)missing.add("ordered effect stack");
        if(fx.optJSONObject("proceduralScene")!=null)missing.add("animated procedural scene");
        if(fx.optJSONObject("rig2d")!=null&&fx.optJSONObject("rig2d").optBoolean("enabled",true))missing.add("2D rig mesh deformation");
        if(fx.optBoolean("animatedScene",false))missing.add("independent generated portrait layers");
        if(!"none".equals(fx.optString("textAnimation","none")))missing.add("title animation");
        return missing;
    }

    /** Returns explicit unsupported operations. Metadata and inactive legacy flags are retained. */
    public static List<String> unsupported(ProjectStore.Clip clip) {
        ArrayList<String> errors = new ArrayList<>();
        JSONObject fx = clip.effects == null ? new JSONObject() : clip.effects;
        validateStructure(fx,errors);
        validateNumbers(fx,errors);
        errors.addAll(NativeRig2DEffect.validate(clip));
        NativeAnimationCadence.validateExposure(fx.optJSONObject("celExposure"),errors);
        if(fx.has("easing"))errors.add("Clip timing uses ease; easing belongs to a keyframe or animationSpec");
        if(fx.has("audio")&&fx.optJSONObject("audio")==null)errors.add("audio must be a settings object");
        errors.addAll(AudioDspProcessor.validate(fx.optJSONObject("audio")));
        if(fx.optJSONObject("transform")!=null)validateNumbers(fx.optJSONObject("transform"),errors);
        if (!MotionTimeline.supportsMotion(fx.optString("motionPreset", "none"))) errors.add("motion:"+fx.optString("motionPreset"));
        if (!MotionTimeline.supportsTransition(clip.transition)) errors.add("transition:"+clip.transition);
        String preset = fx.optString("effectPreset", fx.optString("colorPreset", "none"));
        if (!supportsPreset(preset)) errors.add("effect:"+preset);
        if (!supportsPreset(fx.optString("colorPreset","none"))) errors.add("color:"+fx.optString("colorPreset"));
        JSONObject spec=fx.optJSONObject("animationSpec");
        if (spec!=null && !MotionTimeline.supportsMotion(spec.optString("cameraPreset", "none"))) errors.add("camera:"+spec.optString("cameraPreset"));
        if(spec!=null){
            validateStructure(spec,errors);
            validateKeys(spec.optJSONArray("keyframes"),false,errors);
            for(String key:Arrays.asList("headDepth","torsoDepth","lowerDepth","foregroundDepth","backgroundDepth","parallaxStrength","breathingAmplitude","breathingCycles","swayAmplitudeX","swayAmplitudeY","swayCycles","microRotation","headSwayAmplitudeX","headSwayAmplitudeY","headNodDegrees","torsoBreathScale","torsoBreathShiftY","lowerSwayAmplitudeX","lowerSwayDegrees")) {
                if(!spec.has(key))continue;
                double value=spec.optDouble(key,Double.NaN);
                if(!Double.isFinite(value)||Math.abs(value)>100)errors.add("invalid bounded animation parameter:"+key);
            }
        }
        if (fx.optBoolean("reverse",false)) errors.add("reverse");
        if (fx.has("freezeAtMs")) errors.add("freeze-frame");
        if (fx.optDouble("motionBlur",0)>0) errors.add("optical motion blur");
        if (fx.optBoolean("audioDucking",false)) errors.add("sidechain audio ducking");
        if (!NativeTextOverlay.supportsAnimation(fx.optString("textAnimation","none"))) errors.add("text animation:"+fx.optString("textAnimation"));
        if(!NativeTextOverlay.supportsFont(fx.optString("fontFamily","sans-serif-medium")))errors.add("uninstalled custom font:"+fx.optString("fontFamily"));
        validateKeys(fx.optJSONArray("keyframes"),false,errors);
        validateKeys(fx.optJSONArray("audioKeyframes"),true,errors);
        if (fx.has("reframe") && !"center_cover".equals(fx.optString("reframe"))) errors.add("subject-tracked reframe");
        String mask=fx.optString("mask", "none");
        if (!Arrays.asList("none","rect","circle","ellipse","rounded_rect").contains(mask)) errors.add("mask:"+mask);
        Object crop=fx.opt("crop");
        if (crop instanceof String && !Arrays.asList("center_cover","fit","none").contains((String)crop)) errors.add("crop:"+crop);
        if(fx.optJSONObject("crop")!=null)try{crop(fx.optJSONObject("crop"));}catch(Exception error){errors.add("invalid source crop:"+error.getMessage());}
        JSONArray stack=stack(fx);
        if(stack!=null&&stack.length()>16){errors.add("Effect stack exceeds the 16-stage rendering limit");return errors;}
        if (stack!=null) for(int i=0;i<stack.length();i++) {
            JSONObject item=stack.optJSONObject(i);
            if(item==null) { errors.add("invalid effect stack entry "+i); continue; }
            if(!item.optBoolean("enabled",true)) continue;
            String type=item.optString("type", "");
            if(!supportsStackType(type)) errors.add("stack:"+type);
            JSONObject settings=item.optJSONObject("settings");
            if(item.has("settings")&&settings==null)errors.add("stack settings must be an object at "+i);
            if (settings==null) settings=item;
            validateStructure(settings,errors);
            if(settings.has("easing"))errors.add("Effect-stage timing uses ease; easing belongs to its keyframes");
            validateNumbers(settings,errors);
            if ("preset".equals(type) && !supportsPreset(settings.optString("preset", "none"))) errors.add("stack preset:"+settings.optString("preset"));
            if (("grade".equals(type)||"color".equals(type))&& !supportsPreset(settings.optString("preset","none")))errors.add("stack color preset:"+settings.optString("preset"));
            if ("motion".equals(type) && !MotionTimeline.supportsMotion(settings.optString("preset", "none"))) errors.add("stack motion:"+settings.optString("preset"));
            if("mask".equals(type)&&!Arrays.asList("none","rect","circle","ellipse","rounded_rect").contains(settings.optString("shape",settings.optString("mask","rounded_rect"))))errors.add("stack mask:"+settings.optString("shape"));
            if("crop".equals(type))try{crop(settings);}catch(Exception error){errors.add("invalid stack crop:"+error.getMessage());}
            validateKeys(settings.optJSONArray("keyframes"),false,errors);
        }
        return errors;
    }

    public static List<String> unsupportedAudio(ProjectStore.Clip clip) {
        ArrayList<String> errors=new ArrayList<>();JSONObject fx=clip.effects==null?new JSONObject():clip.effects;
        validateStructure(fx,errors);
        if(fx.has("easing"))errors.add("Clip timing uses ease; easing belongs to its keyframes");
        validateNumbers(fx,errors);
        if(fx.has("audio")&&fx.optJSONObject("audio")==null)errors.add("audio must be a settings object");
        errors.addAll(AudioDspProcessor.validate(fx.optJSONObject("audio")));
        if(!Arrays.asList("none","cut","fade","dip_black","dip_white").contains(clip.transition))errors.add("geometric transition on audio-only track");
        if(fx.optBoolean("reverse",false))errors.add("reverse audio");
        if(fx.optBoolean("audioDucking",false))errors.add("sidechain audio ducking");
        if(fx.has("freezeAtMs"))errors.add("freeze audio");
        for(String key:Arrays.asList("effectPreset","colorPreset","motionPreset","mask"))
            if(fx.has(key)&&!"none".equals(fx.optString(key)))errors.add("visual "+key+" on audio-only track");
        for(String key:Arrays.asList("brightness","contrast","saturationAdjust","lightnessAdjust","hueAdjust","blur","grain","scanlines","vignette","rgbSplit","sharpen","glow"))
            if(fx.optDouble(key,0)!=0)errors.add("visual "+key+" on audio-only track");
        for(String key:Arrays.asList("scale","scaleX","scaleY","x","y","translateX","translateY","rotate","rotation","opacity","transform","crop","reframe","textAnimation","fontFamily"))
            if(fx.has(key))errors.add("visual "+key+" on audio-only track");
        if(fx.optBoolean("chromaKey",false)||fx.optBoolean("animatedScene",false)||fx.optJSONObject("proceduralScene")!=null)errors.add("visual compositing on audio-only track");
        if(fx.has("animationSpec"))errors.add("visual animationSpec on audio-only track");
        if(fx.has("rig2d"))errors.add("visual rig2d on audio-only track");
        if(fx.has("motionPath"))errors.add("visual motionPath on audio-only track");
        if(stack(fx)!=null&&stack(fx).length()>0)errors.add("visual effect stack on audio-only track");
        if(clip.title!=null&&!clip.title.isEmpty())errors.add("title on audio-only track");
        validateKeys(fx.optJSONArray("keyframes"),true,errors);validateKeys(fx.optJSONArray("audioKeyframes"),true,errors);
        return errors;
    }

    private static void validateKeys(JSONArray keys,boolean audioOnly,List<String> errors) {
        if(keys==null)return;
        if(keys.length()>4096){errors.add("Keyframe track exceeds the 4096-key editing limit");return;}
        List<String> allowed=audioOnly?Arrays.asList("t","timeMs","timeUs","easing","bezier","volume")
                :Arrays.asList("t","timeMs","timeUs","easing","bezier","scale","scaleX","scaleY","x","y","translateX","translateY","rotate","rotation","opacity","volume","transform");
        for(int i=0;i<keys.length();i++) {
            JSONObject frame=keys.optJSONObject(i);if(frame==null){errors.add("invalid keyframe "+i);continue;}
            if(frame.has("easing")&&!MotionTimeline.supportsEasing(frame.optString("easing","")))errors.add("unsupported keyframe easing:"+frame.opt("easing"));
            CubicBezierEasing.validateKeyframe(frame,"easing",errors);
            if(frame.has("transform")&&frame.optJSONObject("transform")==null)errors.add("keyframe transform must be an object");
            if(frame.has("t")&&(frame.optDouble("t",-1)<0||frame.optDouble("t",2)>1))errors.add("normalized keyframe time must be between 0 and 1");
            for(String clock:Arrays.asList("timeMs","timeUs"))if(frame.has(clock)&&frame.optDouble(clock,-1)<0)errors.add("keyframe time must not be negative");
            java.util.Iterator<String> properties=frame.keys();
            while(properties.hasNext()){
                String key=properties.next();
                if(!allowed.contains(key))errors.add("unsupported keyframe property:"+key);
                else if(!"easing".equals(key)&&!"bezier".equals(key)&&!"transform".equals(key)&&!Double.isFinite(frame.optDouble(key,Double.NaN)))errors.add("invalid numeric keyframe:"+key);
            }
            if(frame.has("volume")&&(frame.optDouble("volume",-1)<0||frame.optDouble("volume",3)>2))errors.add("volume keyframe must be between 0 and 2");
            validateNumbers(frame,errors);
            JSONObject transform=frame.optJSONObject("transform");
            if(transform!=null){
                java.util.Iterator<String> fields=transform.keys();
                while(fields.hasNext()){
                    String field=fields.next();
                    if(!Arrays.asList("scale","scaleX","scaleY","x","y","translateX","translateY","rotate","rotation","opacity").contains(field))errors.add("unsupported keyframe transform:"+field);
                    else if(!Double.isFinite(transform.optDouble(field,Double.NaN)))errors.add("invalid numeric keyframe transform:"+field);
                }
                validateNumbers(transform,errors);
            }
        }
    }

    private static void validateStructure(JSONObject settings,List<String> errors) {
        for(String key:Arrays.asList("keyframes","audioKeyframes","stack","effectStack"))
            if(settings.has(key)&&settings.optJSONArray(key)==null)errors.add(key+" must be an array");
        for(String key:Arrays.asList("transform","animationSpec","proceduralScene","rig2d","motionPath","celExposure"))
            if(settings.has(key)&&settings.optJSONObject(key)==null)errors.add(key+" must be an object");
        for(String key:Arrays.asList("audioDetached","audioExtractionDetached","titleOnly","animatedScene","chromaKey","reverse","audioDucking"))
            if(settings.has(key)&&!(settings.opt(key) instanceof Boolean))errors.add(key+" must be boolean");
        for(String key:Arrays.asList("ease","easing","audioEasing"))
            if(settings.has(key)&&!MotionTimeline.supportsEasing(settings.optString(key,"")))errors.add("unsupported "+key+":"+settings.opt(key));
        String timingKey=settings.has("ease")?"ease":settings.has("easing")?"easing":"ease";
        CubicBezierEasing.validateDefault(settings,timingKey,"bezier","smooth",errors);
        CubicBezierEasing.validateDefault(settings,"audioEasing","audioBezier","linear",errors);
        if(settings.optJSONObject("motionPath")!=null)MotionPath2D.validate(settings.optJSONObject("motionPath"),errors);
        if(settings.has("crop")&&!(settings.opt("crop") instanceof String)&&settings.optJSONObject("crop")==null)errors.add("crop must be a preset string or normalized rectangle object");
    }

    private static void validateNumbers(JSONObject values,List<String> errors) {
        for(String key:Arrays.asList("brightness","contrast","saturationAdjust","saturation","lightnessAdjust","lightness","hueAdjust","blur","scale","zoom","scaleX","scaleY","x","y","translateX","translateY","rotate","rotation","opacity","motionStrength","vignette","grain","scanlines","posterize","pixelSize","rgbSplit","sharpen","glow","maskFeather","chromaTolerance","spillSuppression","transitionDurationMs","animationDurationMs","animationOffsetMs","volume")) {
            if(!values.has(key))continue;
            double value=values.optDouble(key,Double.NaN);
            if(!Double.isFinite(value)){errors.add("invalid numeric effect:"+key);continue;}
            double min=0,max=Double.MAX_VALUE;
            if(Arrays.asList("brightness","contrast").contains(key)){min=-1;max=1;}
            else if(Arrays.asList("saturationAdjust","saturation","lightnessAdjust","lightness").contains(key)){min=-100;max=100;}
            else if("hueAdjust".equals(key)){min=-180;max=180;}
            else if(Arrays.asList("rotate","rotation").contains(key)){min=-3600;max=3600;}
            else if(Arrays.asList("x","y","translateX","translateY").contains(key)){min=-10;max=10;}
            else if(Arrays.asList("scale","zoom","scaleX","scaleY").contains(key)){min=.01;max=10;}
            else if("blur".equals(key))max=18;
            else if("motionStrength".equals(key))max=3;
            else if(Arrays.asList("opacity","vignette","grain","scanlines","spillSuppression").contains(key))max=1;
            else if("maskFeather".equals(key)){min=.001;max=.5;}
            else if("chromaTolerance".equals(key)){min=.001;max=1;}
            else if("rgbSplit".equals(key))max=.05;
            else if("animationOffsetMs".equals(key)){min=-Long.MAX_VALUE/1000L;max=Long.MAX_VALUE/1000L;}
            else if(Arrays.asList("sharpen","glow","volume").contains(key))max=2;
            else if("posterize".equals(key))max=64;
            else if("pixelSize".equals(key))max=128;
            else if(Arrays.asList("animationDurationMs","animationOffsetMs","transitionDurationMs").contains(key))max=Long.MAX_VALUE/1000L;
            if(value<min||value>max)errors.add(key+" must be between "+min+" and "+max);
        }
    }

    public static List<Effect> build(ProjectStore.Clip clip, String aspect, String quality,
                                     long timestampOffsetUs, float clockSpeed, String layerRole) {
        List<String> unsupported=unsupported(clip);
        if(!unsupported.isEmpty())throw new IllegalArgumentException("Native effects unavailable: "+String.join(", ",unsupported));
        JSONObject fx=resolvedSettings(clip);
        long durationUs=Math.max(1,clip.outputDurationMs())*1000L;
        long authoredDurationUs=Math.max(1,fx.optLong("animationDurationMs",clip.outputDurationMs()))*1000L;
        long animationOffsetUs=AnimationClock.microseconds(fx.optLong("animationOffsetMs",0));
        ArrayList<Effect> effects=new ArrayList<>();
        JSONObject rig=fx.optJSONObject("rig2d");
        if(rig!=null&&rig.optBoolean("enabled",true)){
            effects.add(new NativeRig2DEffect(clip,timestampOffsetUs,clockSpeed));
            // Folded triangles use premultiplied source-over. All later grade,
            // pixel and overlay effects use the shared straight-alpha graph.
            effects.add(new BitmapInputAlphaEffect());
        }
        JSONObject crop=fx.optJSONObject("crop");
        if (crop!=null) effects.add(crop(crop));
        int layout="fit".equals(fx.optString("crop", "center_cover"))
                ? Presentation.LAYOUT_SCALE_TO_FIT : Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP;
        effects.add(Presentation.createForAspectRatio(aspectRatio(aspect),layout));
        effects.add(Presentation.createForHeight(outputSize(aspect,quality)[1]));
        if (!"flat".equals(layerRole)) {
            float overscan="background".equals(layerRole)?1.10f:1.035f;
            effects.add(new ScaleAndRotateTransformation.Builder().setScale(overscan,overscan).build());
        }
        JSONObject graph=fx.optJSONObject("proceduralScene");
        if (graph!=null) {
            try { effects.add(new OverlayEffect(Collections.singletonList(new ClockedCanvasOverlay(
                    new ProceduralSceneOverlay(graph,authoredDurationUs),timestampOffsetUs,clockSpeed,animationOffsetUs)))); }
            catch(Exception error) { throw new IllegalArgumentException("Invalid procedural scene",error); }
        }
        addGrade(effects,fx);
        addBlur(effects,fx);
        MotionTimeline timeline=new MotionTimeline(fx,durationUs,clip.transition,layerRole);
        effects.add(new NativePixelEffect(fx,new MotionTimeline(new JSONObject(),durationUs,"none","flat"),timestampOffsetUs,clockSpeed));
        boolean topLayer="flat".equals(layerRole)||Arrays.asList("head","foreground").contains(layerRole);
        boolean titleOnly=fx.optBoolean("titleOnly",false);
        if(titleOnly&&topLayer&&clip.title!=null&&!clip.title.trim().isEmpty())effects.add(new OverlayEffect(Collections.singletonList(
                new ClockedCanvasOverlay(new NativeTextOverlay(clip.title,fx,0,1,authoredDurationUs),timestampOffsetUs,clockSpeed,animationOffsetUs))));
        effects.add(new MotionMatrixEffect(timeline,timestampOffsetUs,clockSpeed));
        JSONArray stack=stack(fx);
        if (stack!=null) for(int i=0;i<stack.length();i++) {
            JSONObject entry=stack.optJSONObject(i);
            if(entry==null||!entry.optBoolean("enabled",true)) continue;
            JSONObject settings=copy(entry.optJSONObject("settings")==null?entry:entry.optJSONObject("settings"));
            if(fx.has("animationDurationMs")&&!settings.has("animationDurationMs"))put(settings,"animationDurationMs",fx.opt("animationDurationMs"));
            if(fx.has("animationOffsetMs")&&!settings.has("animationOffsetMs"))put(settings,"animationOffsetMs",fx.opt("animationOffsetMs"));
            String type=entry.optString("type","");
            if (Arrays.asList("preset","grade","color").contains(type)) applyPreset(settings,settings.optString("preset","none"));
            if("brightness".equals(type)&&settings.has("value"))put(settings,"brightness",settings.optDouble("value"));
            if("contrast".equals(type)&&settings.has("value"))put(settings,"contrast",settings.optDouble("value"));
            if (Arrays.asList("grade","color","brightness","contrast","hsl","preset").contains(type)) addGrade(effects,settings);
            if ("blur".equals(type)) { put(settings,"blur",settings.optDouble("sigma",settings.optDouble("blur",4))); addBlur(effects,settings); }
            if (Arrays.asList("preset","grade","color").contains(type)) addBlur(effects,settings);
            if ("crop".equals(type)) effects.add(crop(settings));
            if ("motion".equals(type)) put(settings,"motionPreset",settings.optString("preset","none"));
            if ("transform".equals(type)||"motion".equals(type)||"keyframes".equals(type)) {
                MotionTimeline stage=new MotionTimeline(settings,durationUs,"none","flat");
                effects.add(new MotionMatrixEffect(stage,timestampOffsetUs,clockSpeed));
                effects.add(new NativePixelEffect(settings,stage,timestampOffsetUs,clockSpeed));
            } else if (Arrays.asList("pixel","mask","chroma_key","preset","grade","color").contains(type)) {
                if ("chroma_key".equals(type)) { put(settings,"chromaKey",true); put(settings,"chromaColor",settings.optString("color",settings.optString("chromaColor","#00FF00")));put(settings,"chromaTolerance",settings.optDouble("tolerance",settings.optDouble("chromaTolerance",.18)));put(settings,"spillSuppression",settings.optDouble("spill",settings.optDouble("spillSuppression",.35))); }
                if ("mask".equals(type)) put(settings,"mask",settings.optString("shape",settings.optString("mask","rounded_rect")));
                effects.add(new NativePixelEffect(settings,new MotionTimeline(new JSONObject(),durationUs,"none","flat"),timestampOffsetUs,clockSpeed));
            }
        }
        if(!titleOnly&&topLayer&&clip.title!=null&&!clip.title.trim().isEmpty())effects.add(new OverlayEffect(Collections.singletonList(
                new ClockedCanvasOverlay(new NativeTextOverlay(clip.title,fx,0,1,authoredDurationUs),timestampOffsetUs,clockSpeed,animationOffsetUs))));
        if (Arrays.asList("head","foreground").contains(layerRole) && fx.optJSONObject("animationSpec")!=null) {
            JSONObject spec=fx.optJSONObject("animationSpec");
            double intensity=spec.optDouble("atmosphereIntensity",.42);
            if(intensity>0) effects.add(new OverlayEffect(Collections.singletonList(new ClockedCanvasOverlay(new AtmosphereOverlay(
                    spec.optString("environmentMotion","ambient_drift"),intensity,authoredDurationUs),timestampOffsetUs,clockSpeed,animationOffsetUs))));
        }
        // Alpha and dip colors are evaluated once, after titles/atmosphere too.
        effects.add(new NativePixelEffect(new JSONObject(),timeline,timestampOffsetUs,clockSpeed));
        return effects;
    }

    private static void addGrade(List<Effect> effects,JSONObject fx) {
        float brightness=number(fx,"brightness",0,-1,1), contrast=number(fx,"contrast",0,-1,1);
        float saturation=number(fx,"saturationAdjust",number(fx,"saturation",0,-100,100),-100,100);
        float lightness=number(fx,"lightnessAdjust",number(fx,"lightness",0,-100,100),-100,100);
        float hue=number(fx,"hueAdjust",0,-180,180);
        if(brightness!=0) effects.add(new Brightness(brightness));
        if(contrast!=0) effects.add(new Contrast(contrast));
        if(saturation!=0||lightness!=0||hue!=0) effects.add(new HslAdjustment.Builder()
                .adjustSaturation(saturation).adjustLightness(lightness).adjustHue(hue).build());
    }
    private static void addBlur(List<Effect> effects,JSONObject fx) {
        float blur=number(fx,"blur",0,0,18);
        if(blur>0) effects.add(new GaussianBlur(blur));
    }
    private static void applyPreset(JSONObject fx,String preset) {
        if (!supportsPreset(preset)) throw new IllegalArgumentException("Unsupported native effect: "+preset);
        if(GRADES.contains(preset)) {
            JSONObject recipe=CreatorCatalog.effectPreset(preset);
            for(String key:Arrays.asList("brightness","contrast","saturationAdjust","lightnessAdjust","blur","vignette","rgbSplit")) {
                if(!fx.has(key)&& !("saturationAdjust".equals(key)&&fx.has("saturation"))) put(fx,key,recipe.opt(key));
            }
        } else {
            switch(preset==null?"none":preset) {
                case "film_grain": defaultValue(fx,"grain",.55); break;
                case "vignette": defaultValue(fx,"vignette",.45); break;
                case "sharpen": defaultValue(fx,"sharpen",1); break;
                case "clarity": defaultValue(fx,"sharpen",.5); break;
                case "gaussian_blur": defaultValue(fx,"blur",5); break;
                case "soft_glow": case "bloom": defaultValue(fx,"glow",.65); break;
                case "dream": defaultValue(fx,"blur",1.6); defaultValue(fx,"brightness",.04); break;
                case "scanlines": defaultValue(fx,"scanlines",.3); break;
                case "posterize": defaultValue(fx,"posterize",6); break;
                case "pixelate": defaultValue(fx,"pixelSize",12); break;
                case "rgb_split": case "chromatic_aberration": defaultValue(fx,"rgbSplit",.005); break;
                default: break;
            }
        }
    }
    private static Crop crop(JSONObject crop) {
        float left=number(crop,"left",-1,-1,1),right=number(crop,"right",1,-1,1);
        float bottom=number(crop,"bottom",-1,-1,1),top=number(crop,"top",1,-1,1);
        if(left>=right||bottom>=top) throw new IllegalArgumentException("Crop must have positive width and height in normalized GL coordinates");
        return new Crop(left,right,bottom,top);
    }
    public static int[] outputSize(String aspect,String quality) {
        if(!Arrays.asList("9:16","16:9","1:1","4:5").contains(aspect)) throw new IllegalArgumentException("Unsupported export aspect: "+aspect);
        if(!Arrays.asList("720p","1080p").contains(quality.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Unsupported native export quality: "+quality);
        int edge="720p".equalsIgnoreCase(quality)?720:1080;
        if("16:9".equals(aspect)) return new int[]{edge*16/9,edge};
        if("9:16".equals(aspect)) return new int[]{edge,edge*16/9};
        if("4:5".equals(aspect)) return new int[]{edge,(edge*5/4+1)/2*2};
        return new int[]{edge,edge};
    }
    private static float aspectRatio(String aspect) {
        if("16:9".equals(aspect))return 16f/9; if("1:1".equals(aspect))return 1; if("4:5".equals(aspect))return 4f/5; return 9f/16;
    }
    private static JSONArray stack(JSONObject fx) { JSONArray value=fx.optJSONArray("stack");return value==null?fx.optJSONArray("effectStack"):value; }
    private static void defaultValue(JSONObject fx,String key,Object value) { if(!fx.has(key))put(fx,key,value); }
    private static void put(JSONObject object,String key,Object value) {
        try{object.put(key,value);}catch(Exception error){throw new IllegalArgumentException("Invalid effect settings",error);}
    }
    private static JSONObject copy(JSONObject object) {
        try{return object==null?new JSONObject():new JSONObject(object.toString());}catch(Exception error){throw new IllegalArgumentException("Invalid effect settings",error);}
    }
    private static float number(JSONObject fx,String key,float fallback,float min,float max) {
        double value=fx.optDouble(key,fallback);return Double.isFinite(value)?(float)Math.max(min,Math.min(max,value)):fallback;
    }
}
