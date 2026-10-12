package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

/** Strict portable Y-up/metre timeline. Its frame clock is shared with the Blender producer. */
public final class Cinematic3DTimeline {
    public static final String SCHEMA="videostudio.cinematic3d.timeline.v1";
    public static final int MAX_JSON_BYTES=512*1024,MAX_SHOTS=80,MAX_FRAMES=12000;
    public static final String[] PRIMARY_JOINTS={"pelvis","chest","neck","head","swordShoulder","swordElbow","swordHand","freeShoulder","freeElbow","freeHand","leadingHip","leadingKnee","leadingFoot","trailingHip","trailingKnee","trailingFoot"};
    private static final Set<String> ACTIONS=new HashSet<>(Arrays.asList("STANDOFF","CHARGE","CLASH","BLADE_LOCK","FOLLOW_THROUGH","KNOCKBACK","LANDING","RECOVERY","AERIAL_FLIP","TUMBLE","MACRO_CLASH"));
    private Cinematic3DTimeline(){}

    public static JSONObject validate(JSONObject input)throws Exception {
        if(input==null)throw new IllegalArgumentException("Cinematic timeline required");JSONObject out=copy(input);
        fields(out,"schema","name","fps","motionProvider","shots");
        if(!SCHEMA.equals(text(out,"schema",64)))throw new IllegalArgumentException("Unknown cinematic timeline schema");
        String name=text(out,"name",256).trim();if(name.isEmpty())throw new IllegalArgumentException("Timeline name required");out.put("name",name);
        fps((int)integer(out,"fps",24,60));String provider=text(out,"motionProvider",64);
        if(!"procedural_mannequin".equals(provider)&&!"authored_3d".equals(provider))throw new IllegalArgumentException("Unavailable cinematic motion provider");
        JSONArray shots=array(out,"shots");if(shots.length()<1||shots.length()>MAX_SHOTS)throw new IllegalArgumentException("Use 1..80 cinematic shots");
        Set<String> ids=new HashSet<>();int frames=0;JSONArray accepted=new JSONArray();
        for(int i=0;i<shots.length();i++){JSONObject shot=validateShot(object(shots,i));if(!ids.add(shot.getString("id")))throw new IllegalArgumentException("Duplicate cinematic shot id");frames=Math.addExact(frames,shot.getInt("durationFrames"));if(frames>MAX_FRAMES)throw new IllegalArgumentException("Cinematic timeline exceeds 12000 frames");accepted.put(shot);}
        out.put("shots",accepted);canonical(out);return out;
    }

    public static JSONObject compile(JSONObject timeline)throws Exception {
        JSONObject out=validate(timeline);JSONArray shots=out.getJSONArray("shots");int start=0;
        for(int i=0;i<shots.length();i++){JSONObject shot=shots.getJSONObject(i);int end=start+shot.getInt("durationFrames");shot.put("startFrame",start).put("endFrame",end);start=end;}
        return out.put("totalFrames",start).put("frameCount",start).put("durationSeconds",start/(double)out.getInt("fps"));
    }

    /** Sample output n/fps. Camera wall time continues during the exact actor/prop hold. */
    public static JSONObject sampleShot(JSONObject input,int frame,int fps)throws Exception {
        fps(fps);JSONObject shot=copy(input);shot.remove("startFrame");shot.remove("endFrame");shot=validateShot(shot);
        int n=shot.getInt("durationFrames"),h=shot.getInt("hitStopFrames");if(frame<0||frame>=n)throw new IllegalArgumentException("Shot sample frame outside declared interval");
        int impact=h>0?(n-h)/2:n/2;boolean held=h>0&&frame>=impact&&frame<impact+h;double phase;
        if(h==0)phase=frame/(double)n;
        else if(frame<impact)phase=.5*frame/impact;
        else if(held)phase=.5;
        else {int resume=impact+h-1;phase=.5+.5*(frame-resume)/(n-(double)resume);}
        JSONObject settings=shot.getJSONObject("camera");String mode=settings.getString("mode");double p=frame/(double)n;double[] position,target={0,1.1,0},up=cameraUp(mode);boolean authored=settings.has("keyframes");
        if(authored){double[][] sampled=sampleCameraKeys(settings.getJSONArray("keyframes"),p,mode);position=sampled[0];target=sampled[1];up=sampled[2];}
        else switch(mode){case "orbit":double a=-.35+.7*p;position=new double[]{5.2*Math.sin(a),1.9,5.2*Math.cos(a)};break;case "macro":position=new double[]{.15,1.45,2.2};break;case "top_down":position=new double[]{0,6,.001};target=new double[]{0,.8,0};break;default:position=new double[]{0,1.65,5.5};}
        if(impact(shot.getString("action"))&&frame>=impact){double elapsed=(frame-impact)/(double)fps,A=.04*settings.getDouble("shakeIntensity")*Math.exp(-7*elapsed);position[0]+=A*Math.sin(53*elapsed+.7);position[1]+=.6*A*Math.sin(71*elapsed+1.3);position[2]+=.4*A*Math.cos(47*elapsed);}
        cameraBasis(position,target,up);
        JSONObject camera=new JSONObject().put("position",vector(position)).put("target",vector(target)).put("fovDegrees",settings.getDouble("fovDegrees"));if(authored||"top_down".equals(mode))camera.put("up",vector(up));
        return new JSONObject().put("phase",phase).put("motionSeconds",phase*(n-h)/fps).put("hitStop",held).put("impactFrame",impact).put("camera",camera);
    }

    public static JSONObject sample(JSONObject timeline,int frame)throws Exception {
        JSONObject compiled=compile(timeline);if(frame<0||frame>=compiled.getInt("totalFrames"))throw new IllegalArgumentException("Cinematic frame outside timeline");JSONArray shots=compiled.getJSONArray("shots");
        for(int i=0;i<shots.length();i++){JSONObject shot=shots.getJSONObject(i);if(frame<shot.getInt("endFrame")){int local=frame-shot.getInt("startFrame");return new JSONObject().put("shotIndex",i).put("shotFrame",local).put("shot",shot).put("sample",sampleShot(shot,local,compiled.getInt("fps")));}}
        throw new IllegalArgumentException("Cinematic frame unavailable");
    }

    private static JSONObject validateShot(JSONObject source)throws Exception {
        JSONObject shot=copy(source);fields(shot,"id","action","durationFrames","hitStopFrames","camera","vfx","sourceAnchors","poseKeyframes","footContacts");id(text(shot,"id",96));
        String action=text(shot,"action",32);if(!ACTIONS.contains(action))throw new IllegalArgumentException("Unknown cinematic action");integer(shot,"durationFrames",8,600);int hold=(int)integer(shot,"hitStopFrames",0,5);
        if(hold==1||hold>0&&!impact(action))throw new IllegalArgumentException("Hit-stop requires an impact action and 2..5 frames");
        JSONObject camera=object(shot,"camera");fields(camera,"mode","fovDegrees","shakeIntensity","keyframes");String mode=text(camera,"mode",16);if(!Arrays.asList("wide","orbit","macro","top_down").contains(mode))throw new IllegalArgumentException("Unknown cinematic camera mode");number(camera,"fovDegrees",35,100);number(camera,"shakeIntensity",0,1);
        if(camera.has("keyframes"))validateCameraKeys(array(camera,"keyframes"),mode);
        JSONObject vfx=object(shot,"vfx");fields(vfx,"sparkBurst","slashTrail","rubble");for(String key:new String[]{"sparkBurst","slashTrail","rubble"})bool(vfx,key);
        if(shot.has("sourceAnchors")){JSONArray refs=array(shot,"sourceAnchors");if(refs.length()>160)throw new IllegalArgumentException("Source anchor occurrence limit exceeded");for(int i=0;i<refs.length();i++){JSONObject ref=object(refs,i);fields(ref,"assetId","sha256");id(text(ref,"assetId",96));digest(text(ref,"sha256",64),false);}}
        if(shot.has("poseKeyframes")){JSONArray keys=array(shot,"poseKeyframes");if(keys.length()<1||keys.length()>128)throw new IllegalArgumentException("Use 1..128 pose keys");double prior=-1;
            for(int i=0;i<keys.length();i++){JSONObject key=object(keys,i);fields(key,"phase","actors");double phase=number(key,"phase",0,1);if(phase<=prior)throw new IllegalArgumentException("Pose phases must be strictly increasing");prior=phase;JSONObject actors=object(key,"actors");fields(actors,"black","white");
                for(String actor:new String[]{"black","white"}){JSONObject pose=object(actors,actor);fields(pose,"root","rotations");finiteVector(array(pose,"root"),3);JSONArray rotations=array(pose,"rotations");if(rotations.length()!=16)throw new IllegalArgumentException("Exactly sixteen wxyz local rotations required");
                    for(int j=0;j<16;j++){JSONArray q=rotations.getJSONArray(j);finiteVector(q,4);double max=0;for(int k=0;k<4;k++)max=Math.max(max,Math.abs(q.getDouble(k)));if(max==0)throw new IllegalArgumentException("Zero quaternion rejected");double norm=0;for(int k=0;k<4;k++){double v=q.getDouble(k)/max;norm+=v*v;}norm=Math.sqrt(norm);
                        // Preserve previously accepted components during unrelated UI edits.
                        if(Math.abs(max*norm-1)>1e-12)for(int k=0;k<4;k++)q.put(k,(q.getDouble(k)/max)/norm);
                    }
                }
            }
        }
        if(shot.has("footContacts")){JSONArray contacts=array(shot,"footContacts");if(contacts.length()>64)throw new IllegalArgumentException("Foot contact limit exceeded");
            for(int i=0;i<contacts.length();i++){JSONObject c=object(contacts,i);fields(c,"actor","foot","startPhase","endPhase","anchor");String actor=text(c,"actor",8),foot=text(c,"foot",8);if(!Arrays.asList("black","white").contains(actor)||!Arrays.asList("leading","trailing").contains(foot))throw new IllegalArgumentException("Unknown contact actor/foot");double start=number(c,"startPhase",0,1),end=number(c,"endPhase",0,1);if(end<=start)throw new IllegalArgumentException("Impossible foot contact interval");finiteVector(array(c,"anchor"),3);
                for(int j=0;j<i;j++){JSONObject other=contacts.getJSONObject(j);if(actor.equals(other.getString("actor"))&&foot.equals(other.getString("foot"))&&start<other.getDouble("endPhase"))throw new IllegalArgumentException("Same-foot contacts must be chronological and nonoverlapping");}
            }
        }
        return shot;
    }
    private static boolean impact(String action){return "CLASH".equals(action)||"BLADE_LOCK".equals(action)||"MACRO_CLASH".equals(action);}
    private static void fps(int fps){if(fps!=24&&fps!=30&&fps!=60)throw new IllegalArgumentException("Cinematic fps must be 24, 30 or 60");}
    private static JSONArray vector(double[] values)throws Exception {JSONArray out=new JSONArray();for(double v:values)out.put(v);return out;}
    private static void finiteVector(JSONArray a,int size)throws Exception{if(a.length()!=size)throw new IllegalArgumentException("Invalid cinematic vector length");for(int i=0;i<size;i++){Object raw=a.get(i);if(!(raw instanceof Number)||!Double.isFinite(((Number)raw).doubleValue()))throw new IllegalArgumentException("Finite numeric cinematic vector required");}}

    private static double[] cameraUp(String mode){return "top_down".equals(mode)?new double[]{0,0,-1}:new double[]{0,1,0};}
    private static double[] cameraVector(JSONArray value)throws Exception {
        finiteVector(value,3);double[] out=new double[3];for(int i=0;i<3;i++){out[i]=value.getDouble(i);if(Math.abs(out[i])>10000)throw new IllegalArgumentException("Camera vector exceeds 10000 metre/component range");}return out;
    }
    private static double norm(double[] value){return Math.hypot(Math.hypot(value[0],value[1]),value[2]);}
    private static void cameraBasis(double[] position,double[] target,double[] up){
        double scale=1;for(double[] value:new double[][]{position,target,up})for(double component:value)if(!Double.isFinite(component)||Math.abs(component)>10000)throw new IllegalArgumentException("Camera vectors must be finite and within 10000 metres/components");
        double[] direction=new double[3];for(int i=0;i<3;i++){direction[i]=target[i]-position[i];scale=Math.max(scale,Math.max(Math.abs(position[i]),Math.abs(target[i])));}
        double length=norm(direction),upLength=norm(up);if(length<=1e-6*scale||upLength<=1e-9)throw new IllegalArgumentException("Camera direction/up must be nondegenerate at float32 precision");
        double[] unitUp=new double[3];for(int i=0;i<3;i++){direction[i]/=length;unitUp[i]=up[i]/upLength;}
        double[] cross={direction[1]*unitUp[2]-direction[2]*unitUp[1],direction[2]*unitUp[0]-direction[0]*unitUp[2],direction[0]*unitUp[1]-direction[1]*unitUp[0]};
        if(norm(cross)<=1e-6)throw new IllegalArgumentException("Camera up must not be parallel to look direction");
    }
    private static void validateCameraKeys(JSONArray keys,String mode)throws Exception {
        if(keys.length()<1||keys.length()>128)throw new IllegalArgumentException("Use 1..128 camera keys");double prior=-1;
        for(int i=0;i<keys.length();i++){JSONObject key=object(keys,i);fields(key,"phase","position","target","up");double phase=number(key,"phase",0,1);if(phase<=prior)throw new IllegalArgumentException("Camera key phases must be strictly increasing");prior=phase;
            cameraBasis(cameraVector(array(key,"position")),cameraVector(array(key,"target")),key.has("up")?cameraVector(array(key,"up")):cameraUp(mode));
        }
    }
    private static double[][] sampleCameraKeys(JSONArray keys,double phase,String mode)throws Exception {
        JSONObject left=keys.getJSONObject(0),right=left;
        if(phase>=keys.getJSONObject(keys.length()-1).getDouble("phase")){left=keys.getJSONObject(keys.length()-1);right=left;}
        else if(phase>left.getDouble("phase")){for(int i=1;i<keys.length();i++){right=keys.getJSONObject(i);if(phase<=right.getDouble("phase"))break;left=right;}}
        double interval=right.getDouble("phase")-left.getDouble("phase"),alpha=interval==0?0:(phase-left.getDouble("phase"))/interval;double[][] out=new double[3][3];String[] names={"position","target","up"};
        for(int field=0;field<names.length;field++){String name=names[field];double[] a=left.has(name)?cameraVector(array(left,name)):cameraUp(mode),b=right.has(name)?cameraVector(array(right,name)):cameraUp(mode);
            for(int i=0;i<3;i++)out[field][i]=(1-alpha)*a[i]+alpha*b[i];
        }return out;
    }

    static void fields(JSONObject object,String... allowed){Set<String> names=new HashSet<>(Arrays.asList(allowed));for(Iterator<String> i=object.keys();i.hasNext();)if(!names.contains(i.next()))throw new IllegalArgumentException("Unknown cinematic schema field");}
    static JSONObject object(JSONObject o,String key){Object raw=o.opt(key);if(!(raw instanceof JSONObject))throw new IllegalArgumentException(key+" must be an object");return(JSONObject)raw;}
    static JSONObject object(JSONArray a,int index){Object raw=a.opt(index);if(!(raw instanceof JSONObject))throw new IllegalArgumentException("Cinematic record must be an object");return(JSONObject)raw;}
    static JSONArray array(JSONObject o,String key){Object raw=o.opt(key);if(!(raw instanceof JSONArray))throw new IllegalArgumentException(key+" must be an array");return(JSONArray)raw;}
    static String text(JSONObject o,String key,int max){Object raw=o.opt(key);if(!(raw instanceof String)||((String)raw).length()<1||((String)raw).length()>max)throw new IllegalArgumentException("Invalid cinematic text: "+key);return(String)raw;}
    static String id(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,95}"))throw new IllegalArgumentException("Unsafe cinematic identity");return value;}
    static String digest(String value,boolean empty){if(empty&&(value==null||value.isEmpty()))return "";if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Exact lowercase SHA-256 required");return value;}
    static boolean bool(JSONObject o,String key){Object value=o.opt(key);if(!(value instanceof Boolean))throw new IllegalArgumentException("Cinematic boolean required: "+key);return(Boolean)value;}
    static double number(JSONObject o,String key,double min,double max){Object raw=o.opt(key);if(!(raw instanceof Number))throw new IllegalArgumentException("Cinematic number required: "+key);double value=((Number)raw).doubleValue();if(!Double.isFinite(value)||value<min||value>max)throw new IllegalArgumentException("Cinematic number outside bounds: "+key);return value;}
    static long integer(JSONObject o,String key,long min,long max){double value=number(o,key,min,max);if(value!=Math.rint(value))throw new IllegalArgumentException("Cinematic integer required: "+key);return(long)value;}
    static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(canonical(value));}
    static String sha(Object value)throws Exception{return hash(canonical(value).getBytes(StandardCharsets.UTF_8));}
    static String hash(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes){out.append("0123456789abcdef".charAt((b&255)>>>4));out.append("0123456789abcdef".charAt(b&15));}return out.toString();}
    static String canonical(Object value)throws Exception{StringBuilder out=new StringBuilder();encode(value,out,0,new int[]{0});String text=out.toString();if(text.getBytes(StandardCharsets.UTF_8).length>MAX_JSON_BYTES)throw new IllegalArgumentException("Cinematic JSON exceeds 512 KiB");return text;}
    private static void encode(Object value,StringBuilder out,int depth,int[] nodes)throws Exception {
        if(depth>16||++nodes[0]>65536)throw new IllegalArgumentException("Cinematic JSON exceeds structural bounds");
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;TreeSet<String> keys=new TreeSet<>();for(Iterator<String> i=o.keys();i.hasNext();){String k=i.next();if(k.length()>128||keys.size()>=256)throw new IllegalArgumentException("Cinematic JSON object exceeds bounds");keys.add(k);}out.append('{');boolean first=true;for(String k:keys){if(!first)out.append(',');first=false;out.append(JSONObject.quote(k)).append(':');encode(o.get(k),out,depth+1,nodes);}out.append('}');}
        else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;if(a.length()>1024)throw new IllegalArgumentException("Cinematic JSON array exceeds bounds");out.append('[');for(int i=0;i<a.length();i++){if(i>0)out.append(',');encode(a.get(i),out,depth+1,nodes);}out.append(']');}
        else if(value instanceof String){if(((String)value).length()>8192)throw new IllegalArgumentException("Cinematic JSON string exceeds bounds");out.append(JSONObject.quote((String)value));}
        else if(value instanceof Number){if(!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Nonfinite cinematic JSON number");out.append(JSONObject.numberToString((Number)value));}
        else if(value instanceof Boolean)out.append(value);else if(value==null||value==JSONObject.NULL)out.append("null");else throw new IllegalArgumentException("Unsupported cinematic JSON value");
        if(out.length()>MAX_JSON_BYTES)throw new IllegalArgumentException("Cinematic JSON exceeds 512 KiB");
    }
}
