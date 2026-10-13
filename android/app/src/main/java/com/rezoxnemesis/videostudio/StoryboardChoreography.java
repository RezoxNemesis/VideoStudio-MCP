package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Typed source-bound direction, not inference of missing anatomy or contact. */
public final class StoryboardChoreography {
    public enum Beat {
        STANDOFF, BLACK_STEP, WHITE_ADVANCE, CHARGE, DUAL_CHARGE,
        PRE_IMPACT, CLASH, BLADE_LOCK, FOLLOW_THROUGH, WHITE_KNOCKBACK,
        BLACK_LANDING, AFTERMATH_RECOVERY
    }
    private StoryboardChoreography() {}

    public static Beat parse(String value) {
        if(value==null || value.trim().isEmpty()) throw new IllegalArgumentException("Authored choreography beat required");
        try { return Beat.valueOf(value.trim().toUpperCase(Locale.US)); }
        catch(IllegalArgumentException invalid) { throw new IllegalArgumentException("Unsupported choreography beat: "+value); }
    }

    /** Array position, rather than an asset-pair map, preserves repeated beats. */
    public static Beat resolveBeat(JSONObject parameters,int shotIndex,String fromId,String toId) {
        if(parameters==null || !parameters.has("choreography")) return null;
        JSONArray shots=parameters.optJSONArray("choreography");
        if(shots==null || shots.length()==0 || shots.length()>159)
            throw new IllegalArgumentException("choreography must contain 1..159 ordered source transitions");
        if(shotIndex<0 || shotIndex>=shots.length()) throw new IllegalArgumentException("Missing authored choreography for shot "+shotIndex);
        JSONObject shot=shots.optJSONObject(shotIndex);
        if(shot==null || !fromId.equals(shot.optString("fromAssetId")) || !toId.equals(shot.optString("toAssetId")))
            throw new IllegalArgumentException("Choreography source pair differs at shot "+shotIndex);
        if(shot.has("shotIndex")) {
            Object declared=shot.opt("shotIndex");
            if(!(declared instanceof Number) || ((Number)declared).doubleValue()!=shotIndex)
                throw new IllegalArgumentException("Choreography shotIndex differs from its array position");
        }
        if(!shot.has("beat") || shot.isNull("beat") || shot.optString("beat").trim().isEmpty())return null;
        return parse(shot.optString("beat"));
    }

    /**
     * Independent actor pacing between exact AUTHORED endpoints. These phases
     * cannot turn identical/affine endpoints into proven articulated acting;
     * admission and the evidence gate still enforce that distinction.
     */
    public static double phase(Beat beat,boolean black,double time) {
        if(beat==null || !Double.isFinite(time) || time<0 || time>1)
            throw new IllegalArgumentException("Invalid choreography clock");
        if(time==0 || time==1) return time;
        switch(beat) {
            case STANDOFF: return smooth(time);
            case BLACK_STEP: return black?smooth(time):smooth(delayed(time,.25));
            case WHITE_ADVANCE: return black?smooth(delayed(time,.55)):time*time;
            case CHARGE: return black?time*time:smooth(delayed(time,.20));
            case DUAL_CHARGE: return black?time*time:Math.pow(time,1.8);
            case PRE_IMPACT: return black?Math.pow(time,1.65):Math.pow(time,1.45);
            case CLASH: return impactPhase(time,black?.50:.52);
            case BLADE_LOCK: return smooth(time);
            case FOLLOW_THROUGH: return black?1-(1-time)*(1-time):smooth(delayed(time,.10));
            case WHITE_KNOCKBACK: return black?smooth(time):1-Math.pow(1-time,3);
            case BLACK_LANDING: return black?1-(1-time)*(1-time):smooth(delayed(time,.20));
            case AFTERMATH_RECOVERY: return black?smooth(time):smooth(delayed(time,.15));
            default: throw new IllegalArgumentException("Unknown beat");
        }
    }

    public static StoryboardPoseMotion.Action actionFor(Beat beat,boolean black) {
        if(beat==null)throw new IllegalArgumentException("Authored beat required");
        switch(beat) {
            case PRE_IMPACT: case FOLLOW_THROUGH: return StoryboardPoseMotion.Action.STRIKE;
            case CLASH: return StoryboardPoseMotion.Action.IMPACT;
            case BLADE_LOCK: return StoryboardPoseMotion.Action.PARRY;
            case WHITE_KNOCKBACK: return black?StoryboardPoseMotion.Action.ADVANCE:StoryboardPoseMotion.Action.KNOCKBACK;
            default: return StoryboardPoseMotion.Action.ADVANCE;
        }
    }
    private static double impactPhase(double t,double contact) {
        return t<contact?.78*Math.pow(t/contact,2):.78+.22*(1-Math.pow(1-(t-contact)/(1-contact),2));
    }
    private static double delayed(double t,double hold) { return Math.max(0,(t-hold)/(1-hold)); }
    private static double smooth(double t) { return t*t*(3-2*t); }
}
