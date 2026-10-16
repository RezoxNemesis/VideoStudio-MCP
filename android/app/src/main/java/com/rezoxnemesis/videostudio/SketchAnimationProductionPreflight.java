package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A structural and timing preflight. The compiler generates the same edit-ready output
 * that baking uses; these diagnostics never claim to judge storytelling or film-grade art.
 */
final class SketchAnimationProductionPreflight {
    private SketchAnimationProductionPreflight(){}

    static JSONObject inspect(JSONObject source,JSONObject program)throws Exception{
        JSONObject compiled=SketchAnimationCompiler.compile(source,program,
                "preflight_"+java.util.UUID.randomUUID().toString().replace("-",""));
        JSONObject r=compiled.getJSONObject("report"),result=new JSONObject();
        JSONArray notes=new JSONArray();
        int frames=r.getInt("frameCount"),fps=r.getInt("fps");
        int bindings=r.getInt("boundObjectCount"),mouth=r.getInt("mouthCelCount"),dialogue=r.getInt("dialogueCount");
        if(bindings<2)notes.put("Character motion: bind at least two independently movable artwork pieces to a rig.");
        if(!r.getBoolean("independentArticulation"))notes.put("Motion: no independent visible body-part articulation was measured. Camera movement alone is insufficient.");
        if(r.getInt("articulatedMotionFrames")<Math.max(1,frames/12))
            notes.put("Acting: articulated movement appears in fewer than 1 in 12 frames; inspect action and recovery.");
        if(dialogue>0&&mouth==0)notes.put("Dialogue: captions exist but no mouth replacement cels are timed.");
        if(dialogue>0&&mouth>0)notes.put("Dialogue: mouth timing is visual only; align it to a voice recording for accurate lip sync.");
        if(r.getInt("contactCount")>0&&r.optInt("blendedContactCount",0)==0)notes.put("Footwork: contacts have no entry/exit blending; check for snapping at touchdown and release.");
        if(r.getInt("contactCount")==0&&r.getInt("rootMotionFrames")>0)
            notes.put("Footwork: moving roots have no planted-foot IK contacts; check for sliding.");
        if(r.getDouble("maxFootContactErrorPixels")>2)
            notes.put("Contacts: a planted foot misses the target by more than 2 source pixels.");
        if(r.getDouble("maxFootContactRotationErrorDegrees")>2)
            notes.put("Contacts: a planted foot rotates more than 2 degrees from the target.");
        int bytes=r.getInt("outputBytes");
        if(bytes>SketchDocument.MAX_BYTES*9/10)
            notes.put("Document: compiled artwork is near the 4 MiB limit; reduce dense geometry before adding long scenes.");
        if(r.getInt("cameraSampleCount")==0)
            notes.put("Cinematography: no camera track; this is optional when a static camera is intentional.");
        notes.put("Art review needed: inspect full-speed acting, silhouettes, contact, colour and every shot transition.");
        notes.put("Export check needed: inspect the actual phone MP4 for repeated/black frames and sound synchronization.");
        result.put("schema","videostudio.sketch-animation.preflight.v1")
                .put("structuralCompilationPassed",true).put("filmQualityCertified",false)
                .put("renderOrAudioInspected",false).put("frameCount",frames).put("fps",fps)
                .put("durationSeconds",frames/(double)fps).put("diagnostics",notes)
                .put("compiledReport",r);
        return result;
    }
}
