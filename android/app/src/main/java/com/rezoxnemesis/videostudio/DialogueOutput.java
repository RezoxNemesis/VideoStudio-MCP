package com.rezoxnemesis.videostudio;
import java.io.File;
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
            String name=String.format(Locale.US,"line_%02d.wav",i);if(!name.equals(received.getString("path")))throw new IllegalArgumentException("Dialogue stem differs");JSONObject stem=PcmWaveFile.inspect(file(root,descriptors,name,"dialogue_stem","audio/wav"));
            if(stem.getInt("sampleRate")!=24000||stem.getInt("channels")!=1||stem.getLong("sampleFrames")>expected.getLong("slotMs")*24)throw new IllegalArgumentException("Dialogue exceeds its slot");
        }
    }
    private static File file(File root,Map<String,JSONObject> descriptors,String name,String role,String mime)throws Exception {JSONObject d=descriptors.get(name);if(d==null||!role.equals(d.getString("role"))||!mime.equals(d.getString("mime")))throw new IllegalArgumentException("Missing dialogue artifact: "+name);return OpenPipelineArtifacts.target(root,d.getString("path"));}
}
