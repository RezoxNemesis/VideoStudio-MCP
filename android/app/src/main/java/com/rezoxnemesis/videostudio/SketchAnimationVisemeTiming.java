package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Text-based timing helper for artist-drawn mouth replacement cels.
 * Four distinct prepared shapes are required. This does NOT analyze audio or
 * synthesize speech; for performance-quality sync, adjust cels to recorded voice.
 */
final class SketchAnimationVisemeTiming {
    private SketchAnimationVisemeTiming(){}

    static JSONObject apply(JSONObject source,JSONObject original,int dialogueIndex,String closed,String open,String round,String teeth)throws Exception {
        SketchDocument.validate(source);
        JSONObject result=SketchDocument.copy(original);
        JSONArray dialogues=result.optJSONArray("dialogues");
        if(dialogues==null||dialogueIndex<0||dialogueIndex>=dialogues.length())
            throw SketchDocument.bad("Select an existing timed dialogue cue first");
        JSONObject line=dialogues.getJSONObject(dialogueIndex);
        int max=(int)SketchDocument.integer(result,"frameCount",1,SketchDocument.MAX_FRAMES);
        int start=(int)SketchDocument.integer(line,"start",0,max-1);
        int end=(int)SketchDocument.integer(line,"end",start,max-1);
        String utterance=line.getString("text").trim();
        if(utterance.isEmpty()||utterance.length()>4096)throw SketchDocument.bad("Dialogue requires 1..4096 characters");
        String[] art={closed,open,round,teeth};
        Set<String> unique=new HashSet<>();
        for(String id:art){
            SketchDocument.id(new JSONObject().put("objectId",id),"objectId");
            if(!unique.add(id))throw SketchDocument.bad("Each viseme requires a different prepared mouth object");
            JSONObject object=SketchDocument.object(source,id);
            String kind=object.getString("kind");
            if("image".equals(kind)||"paint".equals(kind)||"text".equals(kind))
                throw SketchDocument.bad("Use separate vector mouth cels, not a flattened image, paint layer or text");
        }
        JSONArray ranges=result.optJSONArray("mouthCels");if(ranges==null)ranges=new JSONArray();
        JSONArray visibility=result.optJSONArray("celRanges");
        if(visibility!=null)for(int i=0;i<visibility.length();i++)if(unique.contains(visibility.getJSONObject(i).getString("objectId")))
            throw SketchDocument.bad("A selected mouth shape already has a visibility range; remove that range first");
        JSONArray hidden=result.optJSONArray("hiddenArtwork");if(hidden==null)hidden=new JSONArray();
        Set<String> hiddenIds=new HashSet<>();for(int i=0;i<hidden.length();i++)hiddenIds.add(hidden.getString(i));
        for(String id:art){
            if(!hiddenIds.contains(id)){
                // Existing manual mouth ranges would otherwise make the newly hidden asset ambiguous.
                for(int i=0;i<ranges.length();i++)if(id.equals(ranges.getJSONObject(i).getString("objectId")))
                    throw SketchDocument.bad("Clear existing manual ranges for "+id+" before using automatic timing");
                hidden.put(id);hiddenIds.add(id);
            }
        }
        // A single speaker/mouth set cannot perform two overlapping dialogue tracks.
        for(int i=0;i<ranges.length();i++){
            JSONObject previous=ranges.getJSONObject(i);
            if(unique.contains(previous.getString("objectId"))&&previous.getInt("start")<=end&&start<=previous.getInt("end"))
                throw SketchDocument.bad("The mouth artwork already speaks in this frame interval");
        }
        List<Integer> tokens=visemes(utterance);
        int total=end-start+1;
        int current=-1,first=start;
        for(int frame=start;frame<=end+1;frame++){
            int next=-1;
            if(frame<=end){
                double position=(frame-start+.5)*tokens.size()/total;
                next=tokens.get(Math.min(tokens.size()-1,(int)position));
                // A closed/rest frame at the end prevents permanently open last cels.
                if(frame==end)next=0;
            }
            if(next!=current){
                if(current>=0)ranges.put(new JSONObject().put("objectId",art[current]).put("start",first).put("end",frame-1));
                current=next;first=frame;
            }
        }
        result.put("mouthCels",ranges).put("hiddenArtwork",hidden);
        if(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>SketchAnimationCompiler.MAX_PROGRAM_BYTES)
            throw SketchDocument.bad("Mouth-cel timing exceeds the animation program budget");
        return result;
    }

    /** Deterministic English orthographic approximations; not true phonetic recognition. */
    private static List<Integer> visemes(String utterance) {
        String text=utterance.toLowerCase(Locale.ROOT);
        List<Integer> timeline=new ArrayList<>();timeline.add(0);
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            int value;
            if(c=='p'||c=='b'||c=='m')value=0;                 // pressed lips
            else if(c=='o'||c=='u'||c=='w'||c=='r'||c=='q')value=2; // rounded mouth
            else if(c=='f'||c=='v'||c=='t'||c=='d'||c=='s'||c=='z'||c=='n'||c=='l'||c=='c')value=3;
            else if(c=='a'||c=='e'||c=='i'||c=='y'||c=='h')value=1;
            else if(c=='\''||c==' '||c=='!'||c==','||c=='.'||c=='?'||c==';'||c==':')value=0;
            else if(Character.isLetter(c))value=1;
            else value=0;
            if(timeline.get(timeline.size()-1)!=value)timeline.add(value);
        }
        timeline.add(0);
        return timeline;
    }
}
