package com.rezoxnemesis.videostudio;
import org.json.*;
import java.util.*;

/** Shared manual/MCP validation for explicitly cast, timed speech. */
final class DialogueControls {
    static final String[] LANGUAGES={"en-US","hi-IN"};
    static final String[][] VOICES={{"af_heart","am_adam"},{"hf_alpha","hm_omega"}};
    static JSONObject validate(JSONObject input)throws Exception {
        JSONObject c=new JSONObject(input.toString());OpenPipelineArtifacts.fields(c,"language","durationMs","lines");
        int locale=Arrays.asList(LANGUAGES).indexOf(c.getString("language"));
        if(locale<0)throw new IllegalArgumentException("DIALOGUE_LANGUAGE_UNAVAILABLE");
        long duration=OpenPipelineArtifacts.integer(c,"durationMs",100,600000);JSONArray lines=c.getJSONArray("lines");
        if(lines.length()<1||lines.length()>32)throw new IllegalArgumentException("Use 1..32 dialogue lines per request");
        Set<String> ids=new HashSet<>();Map<String,String> cast=new HashMap<>();long end=-1;int characters=0;
        for(int i=0;i<lines.length();i++){
            JSONObject line=lines.getJSONObject(i);OpenPipelineArtifacts.fields(line,"lineId","characterId","voice","text","startMs","slotMs","speed","phrases");
            String id=OpenPipelineArtifacts.id(line.getString("lineId")),actor=OpenPipelineArtifacts.id(line.getString("characterId")),voice=line.getString("voice");
            if(!ids.add(id))throw new IllegalArgumentException("Duplicate dialogue line");
            if(!Arrays.asList(VOICES[locale]).contains(voice))throw new IllegalArgumentException("DIALOGUE_VOICE_LANGUAGE_MISMATCH");
            String prior=cast.put(actor,voice);if(prior!=null&&!prior.equals(voice))throw new IllegalArgumentException("CHARACTER_VOICE_CHANGED");
            OpenPipelineOperations.text(line,"text",240,true);String text=line.getString("text");characters+=text.length();
            for(int n=0;n<text.length();n++)if(text.charAt(n)<32&&text.charAt(n)!='\n'&&text.charAt(n)!='\t')throw new IllegalArgumentException("Invalid dialogue text");
            long start=OpenPipelineArtifacts.integer(line,"startMs",0,duration-1),slot=OpenPipelineArtifacts.integer(line,"slotMs",100,60000);
            if(start<end||start+slot>duration)throw new IllegalArgumentException("Dialogue slots must be ordered, nonoverlapping and inside the scene");end=start+slot;
            if(line.has("speed")&&(!(line.get("speed") instanceof Number)||!Double.isFinite(line.getDouble("speed"))||line.getDouble("speed")<.75||line.getDouble("speed")>1.25))throw new IllegalArgumentException("Dialogue speed must be 0.75..1.25");
            if(line.has("phrases")){
                // Validate original numeric types before JSONObject's copy can normalize 1.0 to 1.
                JSONArray phrases=input.getJSONArray("lines").getJSONObject(i).getJSONArray("phrases");StringJoiner joined=new StringJoiner(" ");
                if(phrases.length()<1||phrases.length()>8)throw new IllegalArgumentException("Use 1..8 dialogue phrases per line");
                for(int p=0;p<phrases.length();p++){
                    JSONObject phrase=phrases.getJSONObject(p);OpenPipelineArtifacts.fields(phrase,"text","speed","pauseAfterMs");
                    OpenPipelineOperations.text(phrase,"text",240,true);String part=phrase.getString("text");
                    for(int n=0;n<part.length();n++)if(part.charAt(n)<32&&part.charAt(n)!='\n'&&part.charAt(n)!='\t')throw new IllegalArgumentException("Invalid dialogue phrase text");
                    joined.add(part);
                    if(phrase.has("speed")&&(!(phrase.get("speed") instanceof Number)||!Double.isFinite(phrase.getDouble("speed"))||phrase.getDouble("speed")<.75||phrase.getDouble("speed")>1.25))throw new IllegalArgumentException("Dialogue phrase speed must be 0.75..1.25");
                    if(phrase.has("pauseAfterMs"))OpenPipelineArtifacts.integer(phrase,"pauseAfterMs",0,1500);
                }
                if(!text.equals(joined.toString()))throw new IllegalArgumentException("DIALOGUE_PHRASE_TEXT_MISMATCH");
            }
        }
        if(characters>6000)throw new IllegalArgumentException("Dialogue text budget exceeded");return c;
    }
}
