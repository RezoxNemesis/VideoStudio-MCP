package com.rezoxnemesis.videostudio;
import org.json.JSONObject;
final class SketchHumanCameraGuide {
 static JSONObject capabilities()throws Exception{return new JSONObject("{\"minimumNativeVersion\":\"4.10.6\",\"fields\":[\"viewYawDegrees\",\"viewPitchDegrees\"],\"figureStyle\":\"anatomy_sketch\",\"yawDegrees\":[-360,360],\"pitchDegrees\":[-75,75],\"negativePitch\":\"Look down from above\",\"projection\":\"orthographic native construction guide\",\"worldPoseFixed\":true,\"recoversUnseenCharacterViews\":false,\"creativeQualityCertified\":false,\"manualControls\":\"Natural acting > Figure style anatomy_sketch > Numeric camera guide\",\"omittedBehavior\":\"Existing named views and screen-normal lean remain unchanged\"}");}
}
