package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** App-owned deterministic synthesis and streaming PCM mixing, without a renderer or decoder.
 * Source samples retain their clock. This does not recognize speech or certify playback/acting.
 */
public final class SketchStoryAudio {
    private static final int RATE=24000, BUFFER=4096;
    private static final double HEADROOM=.95, TAU=2*Math.PI;
    private static final String VERSION="videostudio.story.audio.v1";
    private static final String[] TYPES={"footstep","wood","metal","cloth","chime"};
    private static final int[] LENGTHS={180,240,600,350,1200};
    private SketchStoryAudio(){}

    public static JSONObject defaults()throws Exception {
        return new JSONObject().put("musicGain",.035).put("ambienceGain",.008).put("dialogueGain",1)
            .put("tempo",72).put("theme","warm").put("ambience","rain").put("seed",13717).put("events",new JSONArray());
    }
    public static JSONObject validateSettings(JSONObject input,long sampleFrames)throws Exception {
        if(sampleFrames<2400||sampleFrames>600L*RATE)throw bad("PCM duration must be 100..600000ms");
        fields(input,"musicGain","ambienceGain","dialogueGain","tempo","theme","ambience","seed","events");
        JSONObject s=defaults();
        for(String key:new String[]{"musicGain","ambienceGain","dialogueGain","tempo"})if(input.has(key))
            s.put(key,number(input,key,key.equals("tempo")?30:0,key.equals("tempo")?180:key.equals("dialogueGain")?2:1));
        for(String key:new String[]{"theme","ambience"})if(input.has(key)){
            Object raw=input.get(key);if(!(raw instanceof String))throw bad("Invalid "+key);s.put(key,raw);
        }
        if(!Arrays.asList("warm","calm","tense").contains(s.getString("theme")))throw bad("Unknown pad theme");
        if(!Arrays.asList("rain","wind","night","none").contains(s.getString("ambience")))throw bad("Unknown ambience");
        if(input.has("seed"))s.put("seed",integer(input,"seed",0,Integer.MAX_VALUE));
        JSONArray events=input.has("events")?input.getJSONArray("events"):new JSONArray();
        if(events.length()>512)throw bad("Use at most 512 audio events");
        List<JSONObject> sorted=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<events.length();i++){
            JSONObject e=events.getJSONObject(i);fields(e,"id","atMs","type","intensity","durationMs");String id=id(e,"id");
            if(!ids.add(id))throw bad("Duplicate audio event ID");
            Object type=e.get("type");int kind=Arrays.asList(TYPES).indexOf(type);
            if(kind<0)throw bad("Unknown audio event type");long at=integer(e,"atMs",0,599999);
            long duration=e.has("durationMs")?integer(e,"durationMs",1,LENGTHS[kind]):LENGTHS[kind];
            if(at*24+duration*24L>sampleFrames)throw bad("Audio event including its tail exceeds the PCM clock");
            JSONObject normalized=new JSONObject().put("id",id).put("atMs",at).put("type",type).put("intensity",number(e,"intensity",0,1));if(e.has("durationMs"))normalized.put("durationMs",duration);sorted.add(normalized);
        }
        sorted.sort(Comparator.comparingLong((JSONObject e)->e.optLong("atMs")).thenComparing(e->e.optString("id")));
        JSONArray normalized=new JSONArray();for(JSONObject e:sorted)normalized.put(e);s.put("events",normalized);
        return s;
    }

    /** Direct native CPU API. Output must be a new regular file in an existing directory.
     * PCM16 mono 24kHz only, <=600s. Events must fit including their natural tails.
     * At most two fixed PCM/mix buffers are live; never allocate a duration-sized track.
     */
    public static JSONObject render(File dialogue,File output,JSONObject settings)throws Exception {
        cancelled();regular(dialogue);size(dialogue);safePath(output);
        if(Files.exists(output.toPath(),LinkOption.NOFOLLOW_LINKS)||!output.getAbsoluteFile().getParentFile().isDirectory())throw bad("Choose a new output in an existing directory");
        String sourceHash=sha256(dialogue);File stage=null;
        try(FileInputStream in=new FileInputStream(dialogue)){
            PcmWaveFile.Layout pcm=PcmWaveFile.readPcm16(in.getChannel(),0,in.getChannel().size());
            if(pcm==null||pcm.sampleRate!=RATE||pcm.channels!=1||pcm.sampleFrames>600L*RATE)throw bad("Use owned 24kHz mono PCM16 WAV");
            JSONObject normalized=validateSettings(settings,pcm.sampleFrames);Synth synth=new Synth(normalized,pcm.sampleFrames);
            double peak=pass(in.getChannel(),pcm,synth,null,1)[0];
            if(!sourceHash.equals(sha256(dialogue)))throw bad("STORY_AUDIO_SOURCE_CHANGED");
            double attenuation=peak>HEADROOM?HEADROOM/peak:1;
            cancelled();stage=File.createTempFile(".story_audio_",".partial",output.getAbsoluteFile().getParentFile());
            double[] measured;
            try(FileOutputStream out=new FileOutputStream(stage)){
                out.write(header(pcm.sampleFrames));measured=pass(in.getChannel(),pcm,synth,out,attenuation);out.getFD().sync();
            }
            if(!sourceHash.equals(sha256(dialogue)))throw bad("STORY_AUDIO_SOURCE_CHANGED");
            JSONObject proof=PcmWaveFile.inspect(stage).put("schema",VERSION).put("settings",normalized)
                .put("sourceSha256",sourceHash).put("sha256",sha256(stage)).put("bytes",stage.length())
                .put("peakBeforeGain",peak).put("appliedGain",attenuation).put("peakPcm16",(long)measured[0])
                .put("rmsPcm16",Math.sqrt(measured[1]/pcm.sampleFrames)).put("nonSilent",measured[0]>0)
                .put("bufferFrames",BUFFER).put("passes",2).put("headroom",HEADROOM).put("sampleClockUnchanged",true)
                .put("hardClippedSamples",0).put("playbackVerified",false).put("listening","UNREVIEWED");
            cancelled();publish(stage,output);return proof;
        }catch(ClosedByInterruptException interrupted){
            // An interrupt may arrive inside NIO after our explicit checkpoint.
            // Preserve the normal cancellation contract, cause and interrupt flag.
            InterruptedIOException cancellation=new InterruptedIOException("Story audio cancelled; source retained");
            cancellation.initCause(interrupted);throw cancellation;
        }finally{if(stage!=null)Files.deleteIfExists(stage.toPath());}
    }
    private static double[] pass(FileChannel source,PcmWaveFile.Layout pcm,Synth synth,OutputStream out,double gain)throws Exception {
        ByteBuffer bytes=ByteBuffer.allocate(BUFFER*2).order(ByteOrder.LITTLE_ENDIAN);double[] mix=new double[BUFFER];
        double peak=0,sum=0;
        for(long first=0;first<pcm.sampleFrames;first+=BUFFER){
            cancelled();int count=(int)Math.min(BUFFER,pcm.sampleFrames-first);bytes.clear();bytes.limit(count*2);
            PcmWaveFile.readFully(source,bytes,pcm.dataOffset+first*2);
            for(int i=0;i<count;i++)mix[i]=bytes.getShort()/32768d*synth.dialogue;
            synth.add(mix,first,count);bytes.clear();
            for(int i=0;i<count;i++){
                double value=mix[i];if(!Double.isFinite(value))throw bad("Invalid synthesized sample");
                if(out==null)peak=Math.max(peak,Math.abs(value));
                else {long sample=Math.round(value*gain*32768);if(sample<Short.MIN_VALUE||sample>Short.MAX_VALUE)throw bad("PCM headroom calculation failed");
                    bytes.putShort((short)sample);peak=Math.max(peak,Math.abs(sample));sum+=(double)sample*sample;}
            }
            if(out!=null)out.write(bytes.array(),0,count*2);
        }
        return new double[]{peak,sum};
    }
    private static final class Synth {
        final double music,ambience,dialogue,beat,root,third;final String atmosphere;final long seed,frames;final Event[] events;
        Synth(JSONObject s,long frames)throws Exception {
            music=s.getDouble("musicGain");ambience=s.getDouble("ambienceGain");dialogue=s.getDouble("dialogueGain");beat=s.getDouble("tempo")/60;
            atmosphere=s.getString("ambience");seed=s.getLong("seed");this.frames=frames;
            String theme=s.getString("theme");root=theme.equals("warm")?130.81278265:theme.equals("calm")?110:123.47082531;
            third=theme.equals("warm")?Math.pow(2,4d/12):theme.equals("calm")?2:Math.pow(2,1d/12);
            JSONArray values=s.getJSONArray("events");events=new Event[values.length()];
            for(int i=0;i<events.length;i++)events[i]=new Event(values.getJSONObject(i),seed);
        }
        void add(double[] samples,long first,int count)throws Exception {
            for(int i=0;i<count;i++){
                long frame=first+i;double t=frame/(double)RATE,edge=Math.min(1,Math.min(frame,frames-1-frame)/(RATE*.35));double value=0;
                if(music>0){double breath=.75-.25*Math.cos(TAU*beat*t/8);
                    value+=music*breath*(.45*Math.sin(TAU*root*t)+.3*Math.sin(TAU*root*third*t)+.25*Math.sin(TAU*root*1.5*t));}
                if(ambience>0&&!atmosphere.equals("none")){
                    double air;
                    if(atmosphere.equals("rain"))air=.65*noise(frame,seed)+.35*smooth(frame,seed+3,16);
                    else if(atmosphere.equals("wind"))air=smooth(frame,seed+7,160)*(.65+.35*Math.sin(TAU*t*.19));
                    else air=.2*noise(frame,seed+11)+.35*Math.sin(TAU*2800*t)*Math.pow(Math.max(0,Math.sin(TAU*t*1.7)),12);
                    value+=ambience*air;
                }
                samples[i]+=value*edge;
            }
            long last=first+count;
            for(Event e:events){
                if(e.start>=last)break;if(e.end<=first||e.gain==0)continue;cancelled();
                for(long frame=Math.max(first,e.start);frame<Math.min(last,e.end);frame++)samples[(int)(frame-first)]+=e.sample(frame-e.start);
            }
        }
    }
    private static final class Event {
        final long start,end,seed;final int kind,durationMs;final double gain;
        Event(JSONObject e,long seed)throws Exception {kind=Arrays.asList(TYPES).indexOf(e.getString("type"));start=e.getLong("atMs")*24;durationMs=e.optInt("durationMs",LENGTHS[kind]);end=start+durationMs*24L;gain=e.getDouble("intensity");this.seed=seed^e.getString("id").hashCode();}
        double sample(long frame){
            double t=frame/(double)RATE,length=durationMs/1000d;
            // Smooth attack/release and an exponential body prevent discontinuities.
            double envelope=Math.min(1,t/.004)*Math.min(1,(length-t)/.025)*Math.exp(-t/(length*.24));
            double value;
            switch(kind){
                case 0:value=.15*Math.sin(TAU*85*t)+.09*smooth(frame,seed,8);break;
                case 1:value=.12*Math.sin(TAU*230*t)+.07*Math.sin(TAU*397*t)+.06*noise(frame,seed);break;
                case 2:value=.12*Math.sin(TAU*780*t)+.09*Math.sin(TAU*1261*t)+.05*Math.sin(TAU*2093*t);break;
                case 3:value=.1*smooth(frame,seed,4)+.035*noise(frame,seed+1);break;
                default:value=.14*Math.sin(TAU*880*t)+.07*Math.sin(TAU*1763*t)+.04*Math.sin(TAU*2647*t);
            }
            return gain*envelope*value;
        }
    }
    private static double noise(long sample,long seed){long x=sample+seed*0x9e3779b97f4a7c15L;x=(x^(x>>>30))*0xbf58476d1ce4e5b9L;x=(x^(x>>>27))*0x94d049bb133111ebL;x^=x>>>31;return (x>>>11)*0x1.0p-52-1;}
    private static double smooth(long frame,long seed,int step){long index=frame/step;double u=(frame%step)/(double)step;u=u*u*(3-2*u);return noise(index,seed)*(1-u)+noise(index+1,seed)*u;}

    /** Manual/MCP entry. Only an explicitly selected file-backed project asset under filesDir is admitted. */
    public static JSONObject execute(Context context,JSONObject args)throws Exception {
        synchronized(SketchStoryAudio.class){return executeLocked(context.getApplicationContext(),args);}
    }
    private static JSONObject executeLocked(Context c,JSONObject args)throws Exception {
        cancelled();fields(args,"projectId","sourceProjectUpdatedAt","assetId","operationId","settings");
        String projectId=id(args,"projectId"),assetId=id(args,"assetId"),op=id(args,"operationId");long revision=integer(args,"sourceProjectUpdatedAt",0,Long.MAX_VALUE-1);
        ProjectStore projects=new ProjectStore(c);ProjectStore.Project source=projects.get(projectId);
        if(source==null||source.updatedAt!=revision)throw bad("STORY_AUDIO_SOURCE_PROJECT_CHANGED");
        File dialogue=ownedSource(c,source,assetId);JSONObject pcm=PcmWaveFile.inspect(dialogue);
        if(pcm.getInt("sampleRate")!=RATE||pcm.getInt("channels")!=1)throw bad("Use owned 24kHz mono PCM16 WAV");
        JSONObject settings=validateSettings(args.getJSONObject("settings"),pcm.getLong("sampleFrames"));String sourceHash=sha256(dialogue);
        JSONObject identity=new JSONObject().put("projectId",projectId).put("sourceProjectUpdatedAt",revision).put("assetId",assetId).put("operationId",op).put("settings",settings).put("sourceSha256",sourceHash);
        String fingerprint=hash(canonical(identity).getBytes(StandardCharsets.UTF_8));
        File root=new File(c.getFilesDir(),"story_audio");directory(root);File lock=new File(root,"operations.lock");safePath(lock);
        try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock ignored=channel.lock()){
            File folder=new File(root,op);directory(folder);File journalFile=new File(folder,"operation.json");safePath(journalFile);
            JSONObject journal;
            if(journalFile.isFile()||Files.exists(new File(journalFile.getPath()+".bak").toPath(),LinkOption.NOFOLLOW_LINKS)){
                journal=readJournal(journalFile);
                if(!VERSION.equals(journal.getString("schema")))throw bad("Unknown audio journal schema");
                if(!fingerprint.equals(journal.getString("fingerprint")))throw bad("STORY_AUDIO_OPERATION_ID_CONFLICT");
            }else {
                journal=new JSONObject().put("schema",VERSION).put("fingerprint",fingerprint).put("identity",identity).put("state","prepared")
                    .put("outputProjectId",UUID.nameUUIDFromBytes(("story-audio-project:"+op).getBytes(StandardCharsets.UTF_8)).toString())
                    .put("outputAssetId",UUID.nameUUIDFromBytes(("story-audio-asset:"+op).getBytes(StandardCharsets.UTF_8)).toString());
                writeJournal(journalFile,journal);
            }
            if(journal.optString("state").equals("completed")){
                JSONObject receipt=journal.getJSONObject("receipt");verifyResult(c,projects,journal,folder);
                return new JSONObject(receipt.toString()).put("replayed",true);
            }
            if(!journal.optString("state").equals("rendered")){
                // Unique generations retain an interrupted render without reusing unproven bytes.
                File mix=new File(folder,"mix_"+UUID.randomUUID()+".wav");JSONObject proof=render(dialogue,mix,settings);
                if(!sourceHash.equals(proof.getString("sourceSha256")))throw bad("STORY_AUDIO_SOURCE_CHANGED");
                journal.put("outputFile",mix.getName()).put("proof",proof).put("state","rendered");writeJournal(journalFile,journal);
            }
            File mixed=resultFile(folder,journal);JSONObject proof=journal.getJSONObject("proof");
            if(!sourceHash.equals(proof.getString("sourceSha256")))throw bad("STORY_AUDIO_SOURCE_CHANGED");
            if(!sha256(mixed).equals(proof.getString("sha256")))throw bad("STORY_AUDIO_RESULT_CHANGED");
            cancelled();
            JSONObject receipt=projects.guardedProject(projectId,revision,current->{
                File currentSource=ownedSource(c,current,assetId);if(!sourceHash.equals(sha256(currentSource)))throw bad("STORY_AUDIO_SOURCE_CHANGED");cancelled();
                ProjectStore.Project existing=projects.get(journal.getString("outputProjectId"));
                if(existing!=null){verifyResult(c,projects,journal,folder);return resultReceipt(existing.id,journal,mixed,op,projectId,assetId,sourceHash);}
                ProjectStore.Project output=new ProjectStore.Project();output.id=journal.getString("outputProjectId");output.name="Story audio • "+current.name;
                ProjectStore.Asset generated=new ProjectStore.Asset();generated.id=journal.getString("outputAssetId");generated.uri=Uri.fromFile(mixed).toString();generated.name="Story audio.wav";generated.mime="audio/wav";generated.durationMs=proof.getLong("durationMs");generated.sizeBytes=mixed.length();generated.seekable=true;generated.persistedReadAccess=true;generated.generated=true;generated.role="story_audio_mix";
                generated.generationMetadata=new JSONObject().put("operationId",op).put("fingerprint",fingerprint).put("sourceProjectId",projectId).put("sourceAssetId",assetId).put("sourceSha256",sourceHash).put("proof",proof);
                output.assets.add(generated);StudioLayer lane=new StudioLayer();lane.type=StudioLayer.AUDIO;lane.assetId=generated.id;lane.startMs=0;lane.sourceInMs=0;lane.durationMs=generated.durationMs;lane.gain=1;lane.validate();output.studioLayers.add(lane);
                projects.save(output);return resultReceipt(output.id,journal,mixed,op,projectId,assetId,sourceHash);
            });
            journal.put("state","completed").put("receipt",receipt);writeJournal(journalFile,journal);return receipt;
        }
    }
    private static JSONObject resultReceipt(String projectId,JSONObject journal,File mixed,String op,String sourceProject,String sourceAsset,String sourceHash)throws Exception {
        JSONObject proof=journal.getJSONObject("proof");return new JSONObject().put("schema",VERSION).put("projectId",projectId).put("assetId",journal.getString("outputAssetId")).put("uri",Uri.fromFile(mixed).toString())
            .put("operationId",op).put("sourceProjectId",sourceProject).put("sourceAssetId",sourceAsset).put("sourceSha256",sourceHash).put("durationMs",proof.getLong("durationMs"))
            .put("sha256",proof.getString("sha256")).put("pcm",proof).put("audioOnly",true).put("replayed",false).put("playbackVerified",false).put("listening","UNREVIEWED");
    }
    private static void verifyResult(Context c,ProjectStore projects,JSONObject journal,File folder)throws Exception {
        File file=resultFile(folder,journal);JSONObject proof=journal.getJSONObject("proof");ProjectStore.Project p=projects.get(journal.getString("outputProjectId"));
        if(p==null)throw bad("STORY_AUDIO_RESULT_PROJECT_CHANGED");ProjectStore.Asset a=p.asset(journal.getString("outputAssetId"));
        if(a==null||!a.generated||!"story_audio_mix".equals(a.role)||!"audio/wav".equals(a.mime)||!Uri.fromFile(file).toString().equals(a.uri)||!journal.getString("fingerprint").equals(a.generationMetadata.optString("fingerprint"))||file.length()!=proof.getLong("bytes")||!sha256(file).equals(proof.getString("sha256")))throw bad("STORY_AUDIO_RESULT_CHANGED");
        owned(c,file);PcmWaveFile.inspect(file);
    }
    private static File resultFile(File folder,JSONObject journal)throws Exception {
        String name=journal.getString("outputFile");if(!name.matches("mix_[a-f0-9-]{36}\\.wav"))throw bad("Unsafe story audio output");File file=new File(folder,name);regular(file);return file;
    }
    private static File ownedSource(Context c,ProjectStore.Project project,String assetId)throws Exception {
        ProjectStore.Asset asset=project.asset(assetId);if(asset==null||asset.uri==null)throw bad("Choose an existing project audio asset");
        Uri uri=Uri.parse(asset.uri);if(!"file".equals(uri.getScheme())||uri.getPath()==null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getAuthority()==null||uri.getAuthority().isEmpty())||!("audio/wav".equals(asset.mime)||"audio/x-wav".equals(asset.mime)))throw bad("Import an explicit PCM WAV through the editor Files workflow first");
        File file=new File(uri.getPath());owned(c,file);regular(file);size(file);return file;
    }
    private static void owned(Context c,File file)throws Exception {safePath(file);if(!file.getCanonicalPath().startsWith(c.getFilesDir().getCanonicalPath()+File.separator))throw bad("Audio must be owned by this application");}
    private static void safePath(File file)throws Exception {Path path=file.toPath().toAbsolutePath();for(Path cursor=path;cursor!=null;cursor=cursor.getParent())if(Files.isSymbolicLink(cursor))throw bad("Audio symlinks are unavailable");if(!path.normalize().equals(path))throw bad("Unsafe audio path");}
    private static void regular(File file)throws Exception {safePath(file);if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw bad("Audio file unavailable");}
    private static void size(File file){if(file.length()<44||file.length()>32L*1024*1024)throw bad("Audio WAV must be at most 32 MiB");}
    private static void directory(File file)throws Exception {safePath(file);Files.createDirectories(file.toPath());if(!file.isDirectory())throw bad("Audio storage unavailable");}
    private static void publish(File stage,File output)throws Exception {
        // link() is atomic and refuses existing targets. A CREATE_NEW streaming copy
        // supports filesystems without hard links while never replacing a user file.
        try{Files.createLink(output.toPath(),stage.toPath());return;}catch(FileAlreadyExistsException exists){throw exists;}
        catch(UnsupportedOperationException|FileSystemException unavailable){if(Files.exists(output.toPath(),LinkOption.NOFOLLOW_LINKS))throw bad("Audio output already exists");}
        boolean created=false,complete=false;
        try(OutputStream out=Files.newOutputStream(output.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            created=true;try(InputStream in=new FileInputStream(stage)){byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;){cancelled();out.write(buffer,0,n);}}complete=true;
        }finally{if(created&&!complete)Files.deleteIfExists(output.toPath());}
    }
    private static byte[] header(long count){ByteBuffer b=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int)(36+count*2)).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(RATE).putInt(RATE*2).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int)(count*2));return b.array();}
    public static String sha256(File file)throws Exception {regular(file);MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] bytes=new byte[32768];for(int n;(n=in.read(bytes))!=-1;){cancelled();d.update(bytes,0,n);}}return hex(d.digest());}
    private static String hash(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static void fields(JSONObject value,String... allowed)throws Exception {if(value==null)throw bad("Settings required");Set<String> names=new HashSet<>(Arrays.asList(allowed));for(Iterator<String> i=value.keys();i.hasNext();)if(!names.contains(i.next()))throw bad("Unknown story audio field");}
    private static long integer(JSONObject value,String key,long min,long max)throws Exception {Object v=value.get(key);if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<min||((Number)v).longValue()>max)throw bad("Invalid integer: "+key);return ((Number)v).longValue();}
    private static double number(JSONObject value,String key,double min,double max)throws Exception {Object v=value.get(key);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue())||((Number)v).doubleValue()<min||((Number)v).doubleValue()>max)throw bad("Invalid number: "+key);return ((Number)v).doubleValue();}
    private static String id(JSONObject value,String key)throws Exception {Object v=value.get(key);if(!(v instanceof String)||!((String)v).matches("[A-Za-z0-9][A-Za-z0-9_-]{0,95}"))throw bad("Invalid ID: "+key);return (String)v;}
    private static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;List<String> keys=new ArrayList<>();o.keys().forEachRemaining(keys::add);Collections.sort(keys);StringJoiner s=new StringJoiner(",","{","}");for(String k:keys)s.add(JSONObject.quote(k)+":"+canonical(o.get(k)));return s.toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringJoiner s=new StringJoiner(",","[","]");for(int i=0;i<a.length();i++)s.add(canonical(a.get(i)));return s.toString();}
        if(value instanceof String)return JSONObject.quote((String)value);if(value instanceof Number)return JSONObject.numberToString((Number)value);return String.valueOf(value);
    }
    private static JSONObject readJournal(File file)throws Exception {
        safePath(file);File backup=new File(file.getPath()+".bak");
        if(Files.exists(backup.toPath(),LinkOption.NOFOLLOW_LINKS))regular(backup);
        // openRead restores an AtomicFile backup when the primary was lost during a write.
        try(InputStream in=new AtomicFile(file).openRead()){
            regular(file);if(file.length()>524288)throw bad("Audio journal exceeds limit");
            return new JSONObject(new String(in.readNBytes(524289),StandardCharsets.UTF_8));
        }
    }
    private static void writeJournal(File file,JSONObject value)throws Exception {safePath(file);byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>524288)throw bad("Audio journal exceeds limit");AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;try{out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}catch(Exception error){if(out!=null)atomic.failWrite(out);throw error;}}
    private static void cancelled()throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Story audio cancelled; source retained");}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
}
