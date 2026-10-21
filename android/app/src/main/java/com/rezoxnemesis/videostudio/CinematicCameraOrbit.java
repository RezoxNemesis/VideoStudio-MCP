package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import static com.rezoxnemesis.videostudio.Cinematic3DTimeline.*;

/** Analytic camera motion around existing geometry; never synthesizes hidden artwork. */
public final class CinematicCameraOrbit {
    private CinematicCameraOrbit() {}

    public static JSONObject validate(JSONObject source) throws Exception {
        JSONObject orbit=copy(source);
        fields(orbit,"target","radius","startAzimuthDegrees","endAzimuthDegrees",
                "startElevationDegrees","endElevationDegrees");
        JSONArray target=array(orbit,"target");
        if(target.length()!=3)throw new IllegalArgumentException("Orbit target needs three world coordinates");
        for(int i=0;i<3;i++){
            Object value=target.get(i);
            if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())
                    ||Math.abs(((Number)value).doubleValue())>9900)
                throw new IllegalArgumentException("Orbit target must be finite within9900 metres");
        }
        number(orbit,"radius",.5,30);
        number(orbit,"startAzimuthDegrees",-3600,3600);
        number(orbit,"endAzimuthDegrees",-3600,3600);
        number(orbit,"startElevationDegrees",-90,90);
        number(orbit,"endElevationDegrees",-90,90);
        return orbit;
    }

    /** Only the explicit orbit uses endpoint-inclusive camera phase; actor clocks stay intact. */
    public static JSONObject sample(JSONObject source,int frame,int frameCount) throws Exception {
        if(frameCount<2||frameCount>600||frame<0||frame>=frameCount)
            throw new IllegalArgumentException("Orbit frame must fit a2..600-frame shot");
        JSONObject orbit=validate(source);
        double phase=frame/(double)(frameCount-1);
        double azimuth=lerp(orbit.getDouble("startAzimuthDegrees"),orbit.getDouble("endAzimuthDegrees"),phase);
        double elevation=lerp(orbit.getDouble("startElevationDegrees"),orbit.getDouble("endElevationDegrees"),phase);
        double a=Math.toRadians(azimuth%360),e=Math.toRadians(elevation);
        double sa=clean(Math.sin(a)),ca=clean(Math.cos(a)),se=clean(Math.sin(e)),ce=clean(Math.cos(e));
        JSONArray target=orbit.getJSONArray("target");double r=orbit.getDouble("radius");
        return new JSONObject().put("position",new JSONArray(new double[]{
                target.getDouble(0)+r*ce*sa,target.getDouble(1)+r*se,target.getDouble(2)+r*ce*ca}))
                .put("target",new JSONArray(target.toString()))
                .put("up",new JSONArray(new double[]{clean(-se*sa),ce,clean(-se*ca)}))
                .put("azimuthDegrees",azimuth).put("elevationDegrees",elevation)
                .put("radius",r).put("cameraPhase",phase);
    }
    private static double lerp(double a,double b,double t){return t==0?a:t==1?b:a+(b-a)*t;}
    private static double clean(double value){return Math.abs(value)<1e-15?0:value;}

    static JSONObject capabilities() throws Exception {
        return new JSONObject("{\"minimumNativeVersion\":\"4.10.3\",\"field\":\"camera.orbit\",\"editAction\":\"cinematic_3d_edit_program\",\"operations\":[\"set_camera_orbit\",\"clear_camera_orbit\"],\"sampleAction\":\"cinematic_3d_get_program\",\"sampleField\":\"sampleFrame\",\"sampleRequiresExpectedProgramSha256\":true,\"azimuthDegrees\":[-3600,3600],\"elevationDegrees\":[-90,90],\"radiusMetres\":[0.5,30],\"worldAxes\":\"Y up; azimuth0 at+Z,90 at+X; positive elevation above target\",\"cameraClock\":\"frame/(shotFrameCount-1); selected endpoints included\",\"actorClockChanged\":false,\"requiresZeroShake\":true,\"cartesianKeysMutuallyExclusive\":true,\"changesSceneGeometry\":false,\"hiddenViewsReconstructed\":false,\"drawingQualityCertified\":false,\"manualControls\":\"3D Studio > Exact camera angles\",\"limitations\":\"Requires existing authored3D geometry. Camera mathematics does not create accurate hidden anatomy, detailed drawings or a consistent neural character. Native GPU/phone replay requires separate evidence.\",\"compatibilityAliases\":{\"studio_cinematic_3d_edit_program_v4103\":\"cinematic_3d_edit_program\",\"studio_cinematic_3d_get_program_v4103\":\"cinematic_3d_get_program\"}}");
    }
}
