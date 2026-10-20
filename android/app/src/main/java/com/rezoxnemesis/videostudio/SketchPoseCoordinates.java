package com.rezoxnemesis.videostudio;

import android.os.Looper;
import org.json.*;
import java.io.InterruptedIOException;

/** Shared read-only posed geometry for manual target placement and ordinary saved-scene reads. */
final class SketchPoseCoordinates {
 private SketchPoseCoordinates(){}
 static void worker()throws InterruptedIOException {
  if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Pose coordinate preparation requires a worker");
  if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Pose coordinate preparation cancelled");
 }
 static JSONObject sample(JSONObject source,JSONObject program,int frame)throws Exception {
  worker();JSONObject sample=SketchAnimationCompiler.sampleFrames(SketchDocument.copy(source),SketchDocument.copy(program),frame,frame).getJSONObject(0);worker();return sample;
 }
 static JSONObject joint(JSONObject sample,String rig,String joint)throws Exception {
  JSONArray points=sample.getJSONArray("joints");for(int i=0;i<points.length();i++){JSONObject point=points.getJSONObject(i);if(rig.equals(point.getString("rigId"))&&joint.equals(point.getString("jointId")))return SketchDocument.copy(point);}throw SketchDocument.bad("The selected joint is no longer in this pose");
 }
}
