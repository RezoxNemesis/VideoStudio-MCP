package com.rezoxnemesis.videostudio;
import org.json.*;
/** Explicit agent instructions for original, prompt-first artwork and visible stroke authoring. */
public final class SketchCapabilities {
    private SketchCapabilities(){}
    public static JSONObject get() throws Exception {
        return new JSONObject().put("minimumNativeVersion","3.9.1").put("promptFirstDrawing",true).put("referenceImageRequired",false).put("humanAndAgentShareOperations",true)
                .put("brushes",new JSONArray(SketchBrushes.PRESETS)).put("geometry",new JSONArray(new String[]{"pressure strokes","M/L/Q/C/Z Bezier paths","lines","polygons","rectangles","ellipses","linear gradient fills","normal/multiply/screen/add layers"}))
                .put("liveDrawing",new JSONObject().put("begin","sketch_live_begin").put("draw","sketch_edit").put("incrementalStroke","append_points").put("end","sketch_live_end").put("everyAcceptedOperationRecorded",true).put("closedPagesPlayedInOrder",true).put("phoneMustHaveSketchStudioOpen",true))
                .put("sketchBook",new JSONObject().put("maximumPages",10000).put("lazyPageAllocation",true).put("maximumFramesPerNativeExport",1200).put("framesPerSecond",new JSONArray(new int[]{24,30,60})))
                .put("promptWorkflow",new JSONArray(new String[]{"Interpret the user's text as an original composition: subject, silhouette, anatomy, face features, pose, lighting, colours and background. No image is required.","Create a blank sketch or paged sketch book. Use separate named layers for construction, body/face, outlines, shading and background.","Start sketch_live_begin before the first mark. Keep its sessionId and current revision.","Draw planned geometry using sketch_edit, one stable-ID object per stroke/feature. Use append_points for incremental freehand strokes. Author portraits/landscapes with measured curves and fills, not a small fixed motif library.","Inspect sketch_preview, adjust actual object/node coordinates with revision-bound edits, then continue drawing. Human tools see the same geometry.","End each page with sketch_live_end. Open the next page and begin another session; the phone displays every page and operation in sequence.","Render the authored flipbook through sketch_render or sketch_book_render. Geometry tools do not themselves certify portrait likeness or artistic quality."}));
    }
}
