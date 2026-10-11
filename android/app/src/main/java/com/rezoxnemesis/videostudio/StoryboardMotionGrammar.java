package com.rezoxnemesis.videostudio;

import java.util.Locale;

/**
 * A cross-genre animation grammar. Actions are stage directions, not claims
 * that 2D image interpolation can hallucinate hidden anatomy.
 *
 * These normalized trajectories work with ANY independently segmented
 * source-painted foreground: comic characters, vehicles, props, typography,
 * products, landscapes, effects and educational diagrams.
 */
public final class StoryboardMotionGrammar {
    public enum Action {
        TRACK, WALK, RUN, LEAP, FALL, DODGE, STRIKE, IMPACT, RECOIL,
        SWAY, FLOAT, ORBIT, BOUNCE, REVEAL, ROTATE, PUSH, PULL,
        PULSE, DRIFT, SPLASH, EXPLODE, PAN, HOLD, CUT
    }
    public enum SubjectKind {
        CHARACTER, ANIMAL, VEHICLE, OBJECT, TEXT, ENVIRONMENT, ABSTRACT, UNKNOWN
    }
    public enum SceneKind {
        ACTION, PORTRAIT, COMIC, PRODUCT, LANDSCAPE, EXPLAINER,
        TYPOGRAPHY, ABSTRACT, MIXED
    }

    public static final class Motion {
        public final float x,y,rotation,scale,opacity,shake,impact;
        public final boolean bodyMotion;
        Motion(float x,float y,float rotation,float scale,float opacity,
               float shake,float impact,boolean bodyMotion) {
            this.x=x;this.y=y;this.rotation=rotation;this.scale=scale;
            this.opacity=opacity;this.shake=shake;this.impact=impact;
            this.bodyMotion=bodyMotion;
        }
    }
    private StoryboardMotionGrammar() {}

    public static Action parseAction(String input, Action fallback) {
        if(input==null||input.trim().isEmpty())return fallback;
        String s=input.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]"," ");
        if(any(s,"parry","clash","collision","strike","swing","slash","punch","hit"))
            return any(s,"clash","impact","collision","hit")?Action.IMPACT:Action.STRIKE;
        if(any(s,"run","rush","chase","charge","sprint"))return Action.RUN;
        if(any(s,"walk","advance","approach","step","follow"))return Action.WALK;
        if(any(s,"jump","leap","vault","airborne","takeoff"))return Action.LEAP;
        if(any(s,"fall","collapse","drop","descend"))return Action.FALL;
        if(any(s,"knockback","recoil","stagger"))return Action.RECOIL;
        if(any(s,"dodge","evade","duck","slide"))return Action.DODGE;
        if(any(s,"orbit","circle","spiral"))return Action.ORBIT;
        if(any(s,"float","levitate","hover"))return Action.FLOAT;
        if(any(s,"bounce","spring"))return Action.BOUNCE;
        if(any(s,"sway","wind","wave","breath"))return Action.SWAY;
        if(any(s,"turn","rotate","spin"))return Action.ROTATE;
        if(any(s,"reveal","uncover","appear","write"))return Action.REVEAL;
        if(any(s,"explode","burst","shatter"))return Action.EXPLODE;
        if(any(s,"splash","pour","ripple"))return Action.SPLASH;
        if(any(s,"pulse","beat","glow"))return Action.PULSE;
        if(any(s,"pan","establishing","landscape"))return Action.PAN;
        if(any(s,"push","zoom in"))return Action.PUSH;
        if(any(s,"pull","zoom out"))return Action.PULL;
        if(any(s,"drift","cloud","fog","smoke"))return Action.DRIFT;
        if(any(s,"cut","switch scene"))return Action.CUT;
        if(any(s,"hold","still","pause"))return Action.HOLD;
        return fallback;
    }
    public static SceneKind scene(String input) {
        String s=input==null?"":input.toLowerCase(Locale.US);
        if(any(s,"manhwa","manga","comic","panel","anime"))return SceneKind.COMIC;
        if(any(s,"fight","duel","combat","battle","chase","action"))return SceneKind.ACTION;
        if(any(s,"portrait","face","selfie","human","person"))return SceneKind.PORTRAIT;
        if(any(s,"product","showcase","advertisement","commercial"))return SceneKind.PRODUCT;
        if(any(s,"nature","landscape","mountain","forest","sky","ocean"))return SceneKind.LANDSCAPE;
        if(any(s,"tutorial","explainer","education","diagram","chart"))return SceneKind.EXPLAINER;
        if(any(s,"typography","text animation","title","credits"))return SceneKind.TYPOGRAPHY;
        if(any(s,"abstract","particle","geometry","fractal"))return SceneKind.ABSTRACT;
        return SceneKind.MIXED;
    }
    private static boolean any(String text,String... terms) {
        for(String term:terms)if(text.contains(term))return true;
        return false;
    }
    public static float phase(Action action,float t) {
        valid(t);
        switch(action) {
            case IMPACT: case STRIKE: case DODGE:
                // Acceleration before contact; momentary braking after it.
                return t<.62f? .78f*(t/.62f)*(t/.62f)
                        : .78f+.22f*(1-(1-(t-.62f)/.38f)*(1-(t-.62f)/.38f));
            case RECOIL: case FALL: case EXPLODE: case SPLASH:
                return 1-(1-t)*(1-t);
            case REVEAL: case PUSH: case PULL: case ROTATE:
                return t*t*(3-2*t);
            case WALK: case RUN: case LEAP: case PAN: case TRACK:
                return t;
            case HOLD: return 0;
            default:return t*t*(3-2*t);
        }
    }

    /**
     * Relative changes from a source object's START transform to its END.
     * The final output still lands exactly on the artist's specified
     * endpoint; no generated displacement is baked into source images.
     */
    public static Motion at(Action action,float t,float dx,float dy,
                            float fromRotation,float toRotation,
                            float fromScale,float toScale,
                            SubjectKind kind) {
        valid(t);
        if(action==null||kind==null)throw new IllegalArgumentException("Specify subject and motion kind");
        float eased=phase(action,t);
        float x=eased*dx,y=eased*dy;
        float rotation=fromRotation+(toRotation-fromRotation)*eased;
        float scale=fromScale+(toScale-fromScale)*eased;
        float opacity=1f,shake=0f,impact=0f;
        float pulse=(float)Math.sin(Math.PI*t);
        switch(action) {
            case LEAP:
                y-=.085f*4*t*(1-t);
                rotation+=8f*pulse;
                break;
            case FALL:
                y+=.03f*4*t*(1-t);
                rotation+=4f*pulse;
                break;
            case BOUNCE:
                y-=.05f*Math.abs((float)Math.sin(2*Math.PI*t))*pulse;
                scale*=1+.04f*pulse;
                break;
            case FLOAT:case SWAY:case DRIFT:
                y-=.012f*(float)Math.sin(Math.PI*2*t)*pulse;
                x+=.012f*(float)Math.sin(Math.PI*2*t)*pulse;
                rotation+=3f*(float)Math.sin(2*Math.PI*t)*pulse;
                break;
            case ORBIT:
                x+=.02f*(float)Math.sin(2*Math.PI*t)*pulse;
                y+=.02f*(float)Math.cos(2*Math.PI*t)*pulse;
                rotation+=12f*pulse;
                break;
            case ROTATE:
                rotation+=90f*pulse;break;
            case PULSE:
                scale*=1+.11f*pulse;break;
            case IMPACT:case STRIKE:
                impact=(float)Math.exp(-Math.pow((t-.62f)/.10f,2));
                shake=impact*.009f;
                break;
            case SPLASH:case EXPLODE:
                impact=(float)Math.exp(-Math.pow((t-.55f)/.20f,2));
                scale*=1+.08f*pulse;
                break;
            case REVEAL:
                opacity=Math.min(1f,t*3f);
                break;
            case PUSH:
                scale*=1+.15f*pulse;break;
            case PULL:
                scale*=1-.12f*pulse;break;
            case RECOIL:
                rotation+=-7f*pulse;break;
            default:break;
        }
        // Camera-only and environment modes must not claim articulated
        // character motion unless the original subject actually moves.
        boolean bodyMotion=(kind==SubjectKind.CHARACTER||kind==SubjectKind.ANIMAL)
                &&action!=Action.PAN&&action!=Action.HOLD&&action!=Action.CUT;
        return new Motion(x,y,rotation,Math.max(.1f,scale),opacity,
                shake,impact,bodyMotion);
    }
    private static void valid(float t) {
        if(!Float.isFinite(t)||t<0||t>1)throw new IllegalArgumentException("time outside 0..1");
    }
}
