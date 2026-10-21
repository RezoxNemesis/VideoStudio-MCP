package com.rezoxnemesis.videostudio;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.*;
import org.json.*;

/** Bind returned speech text/cast/timing and real WAV lengths to the pending request. */
final class DialogueOutput {
    static void verify(File root,JSONObject request,JSONArray files)throws Exception {
        JSONObject controls=DialogueControls.validate(request.getJSONObject("controls"));JSONArray lines=controls.getJSONArray("lines");
        Map<String,JSONObject> descriptors=new HashMap<>();
        for(int i=0;i<files.length();i++){JSONObject d=files.getJSONObject(i);String path=d.getString("path");String name=new File(path).getName().replaceFirst("^[0-9]{2}_","");if(descriptors.put(name,d)!=null)throw new IllegalArgumentException("Duplicate dialogue artifact");File actual=OpenPipelineArtifacts.target(root,path);if(!actual.isFile()||actual.length()!=d.getLong("bytes")||!OpenPipelineArtifacts.hash(actual).equals(d.getString("sha256")))throw new IllegalArgumentException("Dialogue artifact bytes changed");}
        if(descriptors.size()!=lines.length()+2)throw new IllegalArgumentException("Dialogue output file set differs");
        JSONObject timing=OpenPipelineArtifacts.json(file(root,descriptors,"dialogue.json","dialogue_timing","application/json"));
        if(!timing.getString("schema").equals("videostudio.dialogue.v1")||!timing.getString("language").equals(controls.getString("language"))||timing.getLong("durationMs")!=controls.getLong("durationMs")||timing.getJSONArray("lines").length()!=lines.length())throw new IllegalArgumentException("Dialogue result timing differs from request");
        JSONObject master=PcmWaveFile.inspect(file(root,descriptors,"dialogue.wav","audio","audio/wav"));
        if(master.getInt("sampleRate")!=24000||master.getInt("channels")!=1||master.getLong("durationMs")!=controls.getLong("durationMs"))throw new IllegalArgumentException("Dialogue master clock differs");
        for(int i=0;i<lines.length();i++){
            JSONObject expected=lines.getJSONObject(i),received=timing.getJSONArray("lines").getJSONObject(i);
            for(String key:new String[]{"lineId","characterId","voice","text","startMs","slotMs"})if(!expected.get(key).equals(received.get(key)))throw new IllegalArgumentException("Dialogue line differs: "+key);
            if(expected.optDouble("speed",1)!=received.optDouble("speed",1)||!expected.getString("voice").equals(timing.getJSONObject("cast").getString(expected.getString("characterId"))))throw new IllegalArgumentException("Dialogue cast differs");
            verifyPhrases(expected,received);
            String name=String.format(Locale.US,"line_%02d.wav",i);if(!name.equals(received.getString("path")))throw new IllegalArgumentException("Dialogue stem differs");File stemFile=file(root,descriptors,name,"dialogue_stem","audio/wav");JSONObject stem=PcmWaveFile.inspect(stemFile);
            if(stem.getInt("sampleRate")!=24000||stem.getInt("channels")!=1||stem.getLong("sampleFrames")>expected.getLong("slotMs")*24)throw new IllegalArgumentException("Dialogue exceeds its slot");
            // Older audio-only results remain importable. When predictions exist,
            // their clock must describe the actual unchanged PCM, not merely agree
            // with another number in the same untrusted timing JSON.
            if(expected.has("phrases")&&!received.has("timing"))throw new IllegalArgumentException("Dialogue phrase predictions unavailable");
            if(received.has("timing")){
                JSONArray segments=received.getJSONArray("timing");long cursor=0;
                List<Long> pauses=new ArrayList<>();long trailingPause=0;
                if(expected.has("phrases")){
                    JSONArray phrases=expected.getJSONArray("phrases");
                    if(segments.length()<phrases.length())throw new IllegalArgumentException("Dialogue phrase predictions unavailable");
                    for(int p=0;p<phrases.length();p++){
                        long pause=phrases.getJSONObject(p).optLong("pauseAfterMs",0)*24;
                        if(p==phrases.length()-1)trailingPause=pause;else if(pause>0)pauses.add(pause);
                    }
                }
                int pauseIndex=0;
                if(segments.length()<1||segments.length()>8)throw new IllegalArgumentException("Dialogue prediction segments unavailable");
                for(int n=0;n<segments.length();n++){
                    JSONObject segment=segments.getJSONObject(n);long start=OpenPipelineArtifacts.integer(segment,"startSample",0,stem.getLong("sampleFrames"));
                    long count=OpenPipelineArtifacts.integer(segment,"sampleCount",1,stem.getLong("sampleFrames"));
                    if(start<cursor||(n==0&&start!=0)||count>stem.getLong("sampleFrames")-start||segment.getInt("unitsPerSecond")!=40)throw new IllegalArgumentException("Dialogue prediction clock differs from actual PCM stem");
                    if(start>cursor){
                        if(pauseIndex>=pauses.size()||start-cursor!=pauses.get(pauseIndex++))throw new IllegalArgumentException("Dialogue phrase pause clock differs");
                        verifySilence(stemFile,cursor,start);
                    }
                    if(!segment.isNull("modelDurationUnits")){
                        JSONArray units=segment.getJSONArray("modelDurationUnits");long total=0;
                        if(units.length()<3||units.length()>512)throw new IllegalArgumentException("Dialogue token durations unavailable");
                        for(int u=0;u<units.length();u++)total+=OpenPipelineArtifacts.integer(new JSONObject().put("duration",units.get(u)),"duration",1,2400);
                        if(total*600!=count)throw new IllegalArgumentException("Dialogue token clock differs from actual PCM stem");
                    }
                    cursor=start+count;
                }
                if(pauseIndex!=pauses.size()||stem.getLong("sampleFrames")-cursor!=trailingPause)throw new IllegalArgumentException("Dialogue predicted duration differs from actual PCM stem");
                if(trailingPause>0)verifySilence(stemFile,cursor,stem.getLong("sampleFrames"));
            }
        }
    }
    private static void verifyPhrases(JSONObject expected,JSONObject received)throws Exception {
        if(expected.has("phrases")!=received.has("phrases"))throw new IllegalArgumentException("Dialogue phrases differ from request");
        if(!expected.has("phrases"))return;
        JSONArray a=expected.getJSONArray("phrases"),b=received.getJSONArray("phrases");
        if(a.length()!=b.length())throw new IllegalArgumentException("Dialogue phrases differ from request");
        for(int i=0;i<a.length();i++){
            JSONObject x=a.getJSONObject(i),y=b.getJSONObject(i);OpenPipelineArtifacts.fields(y,"text","speed","pauseAfterMs");
            if(!x.get("text").equals(y.get("text"))||x.has("speed")!=y.has("speed")||x.has("pauseAfterMs")!=y.has("pauseAfterMs"))throw new IllegalArgumentException("Dialogue phrase differs from request");
            if(x.has("speed")&&(!(y.get("speed") instanceof Number)||x.getDouble("speed")!=y.getDouble("speed")))throw new IllegalArgumentException("Dialogue phrase speed differs from request");
            if(x.has("pauseAfterMs")&&x.getLong("pauseAfterMs")!=OpenPipelineArtifacts.integer(y,"pauseAfterMs",0,1500))throw new IllegalArgumentException("Dialogue phrase pause differs from request");
        }
    }
    private static void verifySilence(File stem,long first,long end)throws Exception {
        try(FileInputStream in=new FileInputStream(stem)){
            PcmWaveFile.Layout pcm=PcmWaveFile.readPcm16(in.getChannel(),0,in.getChannel().size());
            if(pcm==null)throw new IllegalArgumentException("Dialogue PCM unavailable");
            long offset=pcm.dataOffset+first*pcm.blockAlign,remaining=(end-first)*pcm.blockAlign;ByteBuffer bytes=ByteBuffer.allocate(8192);
            while(remaining>0){bytes.clear();int count=(int)Math.min(remaining,bytes.capacity());bytes.limit(count);PcmWaveFile.readFully(in.getChannel(),bytes,offset);
                while(bytes.hasRemaining())if(bytes.get()!=0)throw new IllegalArgumentException("Dialogue phrase pause is not PCM silence");offset+=count;remaining-=count;}
        }
    }
    private static File file(File root,Map<String,JSONObject> descriptors,String name,String role,String mime)throws Exception {JSONObject d=descriptors.get(name);if(d==null||!role.equals(d.getString("role"))||!mime.equals(d.getString("mime")))throw new IllegalArgumentException("Missing dialogue artifact: "+name);return OpenPipelineArtifacts.target(root,d.getString("path"));}
}
