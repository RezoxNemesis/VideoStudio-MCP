package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Real perspective-camera choreography for existing authored GLB / 3D scenes.
 *
 * No geometry is generated or replaced. Camera is evaluated in Y-up metres,
 * with bounded target/depth and smooth endpoints. Every pose can feed the
 * existing Filament Camera.lookAt() path for both native preview and export.
 *
 * These camera recipes are inspired by reviewed reference videos; they are
 * independently authored camera paths, not video-to-3D reconstruction.
 */
public final class CinematicReferenceCameraPath {
    public static final String CAR_TURNTABLE = "car_turntable";
    public static final String HERO_DOLLY = "hero_dolly";
    public static final String ACTOR_TRACK = "actor_track";
    public static final String ACTION_ARC = "action_arc";
    public static final String DETAIL_REVEAL = "detail_reveal";
    public static final int MAX_KEYS = 25;
    private CinematicReferenceCameraPath() {}

    static final class Pose {
        final double[] position, target, up;
        final double azimuthRad, pitchDeg, distance;
        Pose(double[] position,double[] target,double[] up,
             double azimuthRad,double pitchDeg,double distance){
            this.position=position.clone();this.target=target.clone();this.up=up.clone();
            this.azimuthRad=azimuthRad;this.pitchDeg=pitchDeg;this.distance=distance;
        }
        JSONArray positionJson() throws Exception { return vector(position); }
        JSONArray targetJson() throws Exception { return vector(target); }
        JSONArray upJson() throws Exception { return vector(up); }
    }

    static String[] presets(){
        return new String[]{CAR_TURNTABLE,HERO_DOLLY,ACTOR_TRACK,ACTION_ARC,DETAIL_REVEAL};
    }

    private static void requirePreset(String preset){
        for(String name:presets())if(name.equals(preset))return;
        throw new IllegalArgumentException("Unknown reference camera preset");
    }
    private static void requireFinite(double...values){
        for(double v:values)if(!Double.isFinite(v))
            throw new IllegalArgumentException("Nonfinite camera choreography value");
    }
    private static JSONArray vector(double[]v) throws Exception {
        JSONArray out=new JSONArray();for(double x:v)out.put(x);return out;
    }
    private static double smooth(double t){return t*t*(3-2*t);}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}

    /**
     * Deterministically samples actual 3D space.
     *
     * target is a point in the same metres as the Filament scene, not a
     * normalized screen coordinate. radius is the camera-to-target distance.
     */
    static Pose scene(String preset,double phase,double[] focus,double radius,double strength) {
        requirePreset(preset);
        if(focus==null||focus.length!=3)
            throw new IllegalArgumentException("Explicit 3D look-at point required");
        requireFinite(phase,radius,strength,focus[0],focus[1],focus[2]);
        if(phase<0||phase>1||radius<1.8||radius>30||strength<.1||strength>1
                ||Math.abs(focus[0])>20||Math.abs(focus[1])>20||Math.abs(focus[2])>20)
            throw new IllegalArgumentException("Camera choreography outside bounded scene");
        double t=smooth(phase),u=phase,angle,pitch,dist;
        double trackX=0,trackY=0,trackZ=0,roll=0;
        switch(preset) {
            case CAR_TURNTABLE:
                angle=Math.toRadians(-55+360*t*strength);
                pitch=13+7*Math.sin(Math.PI*u)*strength;
                dist=radius*(1-.08*Math.sin(Math.PI*u)*strength);
                break;
            case HERO_DOLLY:
                angle=Math.toRadians(-58+81*t*strength);
                pitch=13+8*t*strength;
                dist=radius*(1.38-.76*t*strength);
                break;
            case ACTOR_TRACK:
                angle=Math.toRadians(-35+65*t*strength);
                pitch=12+5*t;
                dist=radius*(1.05-.08*Math.sin(Math.PI*u)*strength);
                trackX=lerp(-.55,.55,t)*strength;
                trackY=.05*Math.sin(Math.PI*u);
                break;
            case ACTION_ARC:
                // Fast front-quarter arc around the mid-shot impact with a
                // recoverable settling movement. Does not move the actor.
                double impact=smooth(Math.min(1,u/.52));
                double recovery=smooth(Math.max(0,(u-.52)/.48));
                angle=Math.toRadians(-80+125*impact*strength+20*recovery*strength);
                pitch=14+13*impact-5*recovery;
                dist=radius*(1.2-.44*impact*strength+.16*recovery);
                roll=Math.toRadians(6*Math.sin(Math.PI*u)*strength);
                break;
            case DETAIL_REVEAL:
                angle=Math.toRadians(-23+44*t*strength);
                pitch=29+9*t*strength;
                dist=radius*(1.5-.75*t*strength);
                trackY=.10*t*strength;
                break;
            default:throw new IllegalStateException("Unreachable preset");
        }
        double[] target={focus[0]+trackX,focus[1]+trackY,focus[2]+trackZ};
        double incline=Math.toRadians(pitch);
        double horizontal=dist*Math.cos(incline);
        double[] position={
                target[0]+horizontal*Math.sin(angle),
                target[1]+dist*Math.sin(incline),
                target[2]+horizontal*Math.cos(angle)
        };
        double[] up={Math.sin(roll),Math.cos(roll),0};
        // Keep lookAt's right and up basis far from degenerate.
        double dx=target[0]-position[0],dy=target[1]-position[1],dz=target[2]-position[2];
        double cx=dy*up[2]-dz*up[1],cy=dz*up[0]-dx*up[2],cz=dx*up[1]-dy*up[0];
        if(Math.hypot(Math.hypot(cx,cy),cz)<1e-4)
            throw new IllegalArgumentException("Camera basis too close to singular");
        return new Pose(position,target,up,angle,pitch,dist);
    }

    /**
     * Apply one camera path inside the shared editable Cinematic3D timeline.
     * 25 samples keep the existing piecewise-linear timeline renderer visually
     * smooth without adding an incompatible schema or changing baked actors.
     */
    static JSONArray shotKeys(String preset,double[]focus,double radius,double strength) throws Exception {
        JSONArray keys=new JSONArray();
        for(int i=0;i<MAX_KEYS;i++){
            double phase=i/(double)(MAX_KEYS-1);
            Pose p=scene(preset,phase,focus,radius,strength);
            keys.put(new JSONObject().put("phase",phase)
                .put("position",p.positionJson()).put("target",p.targetJson())
                .put("up",p.upJson()));
        }
        return keys;
    }

    /** Native GLB preview uses the same 3D camera with its true model-space center. */
    static Pose model(String preset,double progress,double originalAzimuth,
                      double distance,double[]modelCenter) {
        requireFinite(originalAzimuth,distance);
        if(distance<2||distance>12)
            throw new IllegalArgumentException("Model camera distance outside preview range");
        Pose base=scene(preset,progress,modelCenter,
                Math.max(1.8,Math.min(30,distance)),
                CAR_TURNTABLE.equals(preset)?1:.85);
        // For a turntable we must preserve the chosen starting angle.
        if(!CAR_TURNTABLE.equals(preset))return base;
        // The first frame is the exact owner-selected viewpoint; one complete
        // revolution returns to it without a sudden -55 degree startup cut.
        double adjusted=base.azimuthRad+originalAzimuth+Math.toRadians(55);
        double inclination=Math.toRadians(base.pitchDeg);
        double horizontal=base.distance*Math.cos(inclination);
        double[] position={
                base.target[0]+horizontal*Math.sin(adjusted),
                base.target[1]+base.distance*Math.sin(inclination),
                base.target[2]+horizontal*Math.cos(adjusted)
        };
        return new Pose(position,base.target,base.up,adjusted,base.pitchDeg,base.distance);
    }
}
