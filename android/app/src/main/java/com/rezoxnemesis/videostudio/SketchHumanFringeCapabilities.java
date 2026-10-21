package com.rezoxnemesis.videostudio;
import org.json.JSONObject;
/** Shared explicit native/MCP construction-guide contract; no automatic likeness claim. */
final class SketchHumanFringeCapabilities {
 private SketchHumanFringeCapabilities(){}
 static JSONObject get()throws Exception{return new JSONObject("{\"minimumNativeVersion\":\"4.10.10\",\"field\":\"fringeStyle\",\"styles\":[\"swept\",\"center_part\"],\"figureStyle\":\"anatomy_sketch\",\"storedGeometry\":\"Native editable paths or retained human settings; source pixels are not copied\",\"manualControls\":\"Human acting > Figure style anatomy_sketch > Hair fringe > Center part\",\"omittedBehavior\":\"Existing swept fringe geometry and omitted request fingerprints remain unchanged; manual Swept omits the field\",\"limitations\":\"An original cranial-volume construction guide, not fitted character identity, recovered artist strokes or guaranteed unseen views. Rear curtain ordering is an authored view-aware composition policy, not per-pixel3D hair simulation.\"}");}
}
