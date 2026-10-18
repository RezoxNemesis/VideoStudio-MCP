package com.rezoxnemesis.videostudio;
import org.json.*;
import java.util.*;
/** Independent physical-time eye motion; never inserts or resamples body pose keys. */
final class SketchHumanBlink {
 private SketchHumanBlink(){}
 static JSONObject defaults()throws Exception{return new JSONObject().put("intervalMs",3600).put("phaseMs",1800).put("closeMs",70).put("holdMs",80).put("openMs",110).put("strength",1);}
 static JSONObject settings(JSONObject input)throws Exception {
  SketchDocument.fields(input,"intervalMs,phaseMs,closeMs,holdMs,openMs,strength");JSONObject value=defaults();Iterator<String> keys=input.keys();while(keys.hasNext()){String key=keys.next();value.put(key,input.get(key));}
  SketchDocument.integer(value,"intervalMs",1000,30000);SketchDocument.integer(value,"phaseMs",0,30000);SketchDocument.integer(value,"closeMs",20,300);SketchDocument.integer(value,"holdMs",0,300);SketchDocument.integer(value,"openMs",20,400);SketchDocument.number(value,"strength",0,1);
  if(value.getLong("closeMs")+value.getLong("holdMs")+value.getLong("openMs")>value.getLong("intervalMs"))throw SketchDocument.bad("Blink duration must fit its interval");return value;
 }
 static double sample(JSONObject input,double ms)throws Exception {
  JSONObject s=settings(input);double elapsed=ms-s.getLong("phaseMs");if(elapsed<0)return 0;double t=elapsed%s.getLong("intervalMs"),close=s.getLong("closeMs"),hold=s.getLong("holdMs"),open=s.getLong("openMs"),strength=s.getDouble("strength");
  if(t<close)return strength*SketchCharacterAnimation.smooth(t/close);if(t<close+hold)return strength;if(t<close+hold+open)return strength*(1-SketchCharacterAnimation.smooth((t-close-hold)/open));return 0;
 }
}
