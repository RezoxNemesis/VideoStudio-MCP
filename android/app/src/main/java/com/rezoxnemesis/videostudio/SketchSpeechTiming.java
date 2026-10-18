package com.rezoxnemesis.videostudio;

import org.json.*;
import java.util.Set;

/** Authored timed visemes, evaluated on the native physical-time clock.
 * Not a speech recognizer or an audio synthesizer. Output is ordinary editable cels.
 */
final class SketchSpeechTiming {
    static final String[] SHAPES={"rest","closed","open","wide","round","teeth","tongue"};
    private static final Set<String> NAMES=Set.of(SHAPES);
    private SketchSpeechTiming(){}

    static void validate(JSONObject speech,double durationMs)throws Exception {
        resolve(speech,durationMs);
    }
    static JSONObject resolve(JSONObject speech,double durationMs)throws Exception {
        SketchDocument.fields(speech,"cues,prediction,transitionMs");
        if(speech.has("transitionMs"))SketchDocument.number(speech,"transitionMs",0,200);
        if(speech.has("cues")==speech.has("prediction"))throw SketchDocument.bad("Choose authored cues or model predictions");
        JSONArray cues=speech.has("cues")?speech.getJSONArray("cues"):prediction(speech.getJSONObject("prediction"),durationMs);
        if(cues.length()<1||cues.length()>240)throw SketchDocument.bad("Speech needs 1..240 timed cues");
        double previous=0;
        for(int i=0;i<cues.length();i++){
            JSONObject cue=cues.getJSONObject(i);SketchDocument.fields(cue,"startMs,endMs,shape,strength");
            double first=SketchDocument.number(cue,"startMs",0,durationMs),last=SketchDocument.number(cue,"endMs",0,durationMs);
            if(first<previous||last<=first)throw SketchDocument.bad("Speech cues must be ordered, nonoverlapping and inside the unchanged sketch timeline");
            if(!NAMES.contains(cue.getString("shape")))throw SketchDocument.bad("Unknown speech shape");
            if(cue.has("strength"))SketchDocument.number(cue,"strength",0,1);
            previous=last;
        }
        JSONArray normalized=new JSONArray(cues.toString());
        for(int i=0;i<normalized.length();i++)if(!normalized.getJSONObject(i).has("strength"))normalized.getJSONObject(i).put("strength",1);
        return new JSONObject().put("transitionMs",speech.optDouble("transitionMs",60)).put("cues",normalized);
    }

    static JSONArray poses(JSONObject doc,JSONObject speech)throws Exception {
        int frames=doc.getInt("frameCount"),fps=doc.getInt("fps");JSONObject resolved=resolve(speech,frames*1000d/fps);
        JSONArray keys=new JSONArray();
        for(int f=0;f<frames;f++){
            double[] v=sample(resolved,f*1000d/fps);JSONObject p=new JSONObject().put("frame",f).put("interpolation","hold");
            for(int c=0;c<8;c++)p.put(SketchMouthAnimation.CHANNELS[c],v[c]);keys.put(p);
        }
        return keys;
    }

    /** Original Kokoro records copied from the app's generated dialogue result.
     * Durations include boundary, stress and modifier tokens. Never rescale them.
     * Unknown/filtered symbols reject rather than silently shifting every later cue.
     */
    private static JSONArray prediction(JSONObject prediction,double durationMs)throws Exception {
        SketchDocument.fields(prediction,"startMs,segments");double start=SketchDocument.number(prediction,"startMs",0,durationMs);
        JSONArray segments=prediction.getJSONArray("segments"),cues=new JSONArray();long previous=0;
        if(segments.length()<1||segments.length()>8)throw SketchDocument.bad("Use 1..8 bounded speech prediction segments");
        for(int s=0;s<segments.length();s++){
            JSONObject segment=segments.getJSONObject(s);SketchDocument.fields(segment,"phonemes,startSample,sampleCount,modelDurationUnits,unitsPerSecond,forcedAlignmentVerified");
            long first=SketchDocument.integer(segment,"startSample",0,240000),count=SketchDocument.integer(segment,"sampleCount",1,240000);
            if(first<previous||start+(first+count)/24d>durationMs)throw SketchDocument.bad("Predicted audio segments overlap or exceed the sketch");previous=first+count;
            if(SketchDocument.integer(segment,"unitsPerSecond",40,40)!=40||!Boolean.FALSE.equals(segment.get("forcedAlignmentVerified")))throw SketchDocument.bad("Only explicitly unaligned 40 Hz model duration predictions are supported");
            String phonemes=segment.getString("phonemes");int[] symbols=phonemes.codePoints().toArray();JSONArray units=segment.getJSONArray("modelDurationUnits");
            if(symbols.length<1||symbols.length>240||units.length()!=symbols.length+2)throw SketchDocument.bad("Model token durations must match every Unicode phoneme plus two boundary tokens; filtered symbols need manual timing");
            long total=0;for(int i=0;i<units.length();i++)total+=SketchDocument.integer(new JSONObject().put("duration",units.get(i)),"duration",1,400);
            if(total*600!=count)throw SketchDocument.bad("Model duration clock does not match the actual audio sample count");
            long offset=0;
            for(int i=0;i<units.length();i++){
                long length=units.getLong(i);String shape="rest";
                if(i>0&&i<=symbols.length){
                    int index=i-1,cp=symbols[index];
                    // Stress precedes its vowel; length/nasal/aspiration modifiers
                    // extend the preceding sound. They must never introduce false rests.
                    if(cp=='ˈ'||cp=='ˌ'){while(index+1<symbols.length&&(symbols[index]=='ˈ'||symbols[index]=='ˌ'))index++;cp=symbols[index];}
                    else if(cp=='ː'||cp=='ˑ'||cp=='̃'||cp=='ʰ'){while(index>0&&isModifier(symbols[index]))index--;cp=symbols[index];}
                    shape=phoneme(cp);
                }
                double from=start+first/24d+offset*25d,to=from+length*25d;offset+=length;
                if(cues.length()>0){JSONObject last=cues.getJSONObject(cues.length()-1);
                    if(last.getString("shape").equals(shape)&&last.getDouble("endMs")==from){last.put("endMs",to);continue;}}
                cues.put(new JSONObject().put("startMs",from).put("endMs",to).put("shape",shape));
            }
        }
        return cues;
    }
    private static boolean isModifier(int c){return c=='ː'||c=='ˑ'||c=='̃'||c=='ʰ'||c=='ˈ'||c=='ˌ';}
    private static String phoneme(int cp){
        if(cp=='m'||cp=='b'||cp=='p')return "closed";
        if("oɔuʊʉɯwOUW".indexOf(cp)>=0)return "round";
        if("ieɪɛɨɘɜjyIA".indexOf(cp)>=0)return "wide";
        if("aɑɐəᵊʌæɒhɦ".indexOf(cp)>=0)return "open";
        if("fvθðszʃʒʂʐtdnlɭɾɽrɹɻʁɡgkqxcɟŋɲɳʈɖʧʤɕʑçχʎɣβʋɥɬɮ".indexOf(cp)>=0)return "teeth";
        if(Character.isWhitespace(cp)||".,!?;:—-…".indexOf(cp)>=0)return "rest";
        throw SketchDocument.bad("Unsupported speech symbol U+"+Integer.toHexString(cp)+"; use reviewed manual cues");
    }

    static JSONObject fromDialogue(JSONObject dialogue,int lineIndex)throws Exception {
        if(!"videostudio.dialogue.v1".equals(dialogue.getString("schema")))throw SketchDocument.bad("Choose a generated VideoStudio dialogue timing result");
        JSONArray lines=dialogue.getJSONArray("lines");if(lineIndex<0||lineIndex>=lines.length())throw SketchDocument.bad("Choose an existing dialogue line");
        JSONObject line=lines.getJSONObject(lineIndex);
        return new JSONObject().put("transitionMs",60).put("prediction",new JSONObject().put("startMs",line.get("startMs")).put("segments",new JSONArray(line.getJSONArray("timing").toString())));
    }

    static JSONArray facePoses(JSONObject doc,JSONObject speech,JSONArray base)throws Exception {
        JSONArray mouth=poses(doc,speech),keys=new JSONArray();
        for(int f=0;f<doc.getInt("frameCount");f++){
            double[] v=SketchFaceAnimation.sample(base,f),m=SketchMouthAnimation.sample(mouth,f);
            System.arraycopy(m,0,v,6,8);keys.put(SketchFaceAnimation.pose(f,v).put("interpolation","hold"));
        }
        return keys;
    }

    static double[] sample(JSONObject speech,double timeMs)throws Exception {
        JSONArray cues=speech.getJSONArray("cues");double transition=speech.optDouble("transitionMs",60);
        for(int i=0;i<cues.length();i++){
            JSONObject cue=cues.getJSONObject(i);double first=cue.getDouble("startMs"),last=cue.getDouble("endMs");
            if(timeMs<first)return shape("rest",1);
            if(timeMs>=last)continue;
            String name=cue.getString("shape");double[] target=shape(name,cue.optDouble("strength",1));
            // Closure and silence are barriers, never interpolated into an open vowel.
            if(closed(name)||transition==0)return target;
            double window=Math.min(transition,(last-first)/2);
            if(timeMs<first+window){
                JSONObject before=i>0?cues.getJSONObject(i-1):null;
                boolean adjacent=before!=null&&before.getDouble("endMs")==first;
                String previous=adjacent?before.getString("shape"):"rest";
                double u=(timeMs-first)/window;
                if(!closed(previous))u=.5+u*.5;
                return blend(shape(previous,adjacent?before.optDouble("strength",1):1),target,smooth(u));
            }
            if(timeMs>last-window){
                JSONObject after=i+1<cues.length()?cues.getJSONObject(i+1):null;
                boolean adjacent=after!=null&&after.getDouble("startMs")==last;
                String next=adjacent?after.getString("shape"):"rest";
                double u=(timeMs-last+window)/window;
                if(!closed(next))u*=.5;
                return blend(target,shape(next,adjacent?after.optDouble("strength",1):1),smooth(u));
            }
            return target;
        }
        return shape("rest",1);
    }
    private static boolean closed(String shape){return "rest".equals(shape)||"closed".equals(shape);}
    private static double smooth(double u){return u*u*(3-2*u);}
    private static double[] blend(double[] a,double[] b,double u){for(int i=0;i<a.length;i++){a[i]+=u*(b[i]-a[i]);if(a[i]==0)a[i]=0;}return a;}
    private static double[] shape(String name,double strength){
        double[] v;
        switch(name){
            case "open": v=new double[]{.72,1,0,0,0,.40,.15,.10};break;
            case "wide": v=new double[]{.35,1.24,.08,0,0,.65,.18,0};break;
            case "round": v=new double[]{.50,.60,0,0,0,.20,.08,1};break;
            case "teeth": v=new double[]{.17,1.08,0,0,0,.85,.08,0};break;
            case "tongue": v=new double[]{.32,1,0,0,.28,.35,.10,0};break;
            default: v=new double[]{0,1,0,0,0,.40,.15,0};
        }
        v[0]*=strength;v[1]=1+(v[1]-1)*strength;v[2]*=strength;v[4]*=strength;v[7]*=strength;return v;
    }
}
