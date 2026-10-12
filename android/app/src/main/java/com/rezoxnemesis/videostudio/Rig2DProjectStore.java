package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/** Source-guarded private annotation files, included in the existing Drive archive. */
public final class Rig2DProjectStore {
    private static final Object PROFILE_LOCK=new Object();
    private static final int MAX_BYTES=65536,MAX_PROFILES=100;
    private final Context context;
    private final ProjectStore projects;
    public Rig2DProjectStore(Context context,ProjectStore projects) {
        if(context==null||projects==null)throw new IllegalArgumentException("Store is required");
        this.context=context.getApplicationContext();this.projects=projects;
    }
    /** Compatibility create-only path; an existing ID always requires an exact digest. */
    public File save(Rig2DProfile rig) throws Exception {
        if(rig==null)throw new IllegalArgumentException("Rig required");
        return save(rig,project(rig.projectId).updatedAt,"");
    }
    public File save(Rig2DProfile rig,long expectedSourceUpdatedAt,String expectedRigSha256) throws Exception {
        if(rig==null)throw new IllegalArgumentException("Rig required");
        File target=new File(directory(rig.projectId,"rigs",false),rig.id+".json");
        saveWithBinding(rig,expectedSourceUpdatedAt,expectedRigSha256);
        return target;
    }
    /** Exact publication receipt; another writer cannot be acknowledged as this draft. Worker-only. */
    public JSONObject saveWithBinding(Rig2DProfile rig,long expectedSourceUpdatedAt,String expectedRigSha256) throws Exception {
        if(rig==null)throw new IllegalArgumentException("Rig required");
        Rig2DProfile frozen=Rig2DProfile.fromJson(rig.toJson());
        String expected=digestArgument(expectedRigSha256);byte[] data=bytes(frozen.toJson());
        ProjectStore.Project sourceProject=project(frozen.projectId);requireSource(sourceProject,frozen);
        if(sourceProject.updatedAt!=expectedSourceUpdatedAt)
            throw new IllegalStateException("SOURCE_PROJECT_CHANGED: reopen the source before saving annotations");
        ProjectStore.Asset acceptedAsset=sourceProject.asset(frozen.sourceAssetId);
        String acceptedMetadata=canonicalJson(acceptedAsset.toJson());
        if(frozen.sourceSha256!=null) {
            JSONObject source=StoryboardSourceManifest.captureSource(context,acceptedAsset);
            if(!frozen.sourceSha256.equals(source.getString("sourceSha256")))
                throw new IllegalStateException("SOURCE_IMAGE_CHANGED: annotations belong to different image bytes");
        }
        return projects.guardedProject(frozen.projectId,expectedSourceUpdatedAt,current->{
            requireSource(current,frozen);
            if(!acceptedMetadata.equals(canonicalJson(current.asset(frozen.sourceAssetId).toJson())))
                throw new IllegalStateException("SOURCE_IMAGE_CHANGED: registered source metadata changed before publication");
            synchronized(PROFILE_LOCK) {
                File dir=directory(frozen.projectId,"rigs",true),target=new File(dir,frozen.id+".json");requirePlainChild(target);
                if(target.exists()) {
                    Rig2DProfile old=readRig(current,target,frozen.id);
                    requireExpected(expected,sha256(old),"SOURCE_RIG_CHANGED");
                    if(!old.sourceAssetId.equals(frozen.sourceAssetId)||old.black!=frozen.black)
                        throw new IllegalArgumentException("Existing rig source and actor ownership cannot change");
                } else {
                    if(!expected.isEmpty())throw new IllegalStateException("SOURCE_RIG_CHANGED: profile no longer exists");
                    if(profileCount(dir)>=MAX_PROFILES)throw new IllegalArgumentException("Project rig limit is 100");
                }
                JSONObject receipt=binding(frozen);
                writeAtomic(target,data);return receipt;
            }
        });
    }
    public Rig2DProfile load(String projectId,String rigId) throws Exception {
        checkedId(rigId);ProjectStore.Project current=project(projectId);
        synchronized(PROFILE_LOCK){return readRig(current,new File(directory(projectId,"rigs",false),rigId+".json"),rigId);}
    }
    public Rig2DProfile loadVerified(String projectId,String rigId,String expectedSha256) throws Exception {
        return loadVerified(project(projectId),rigId,expectedSha256);
    }
    /** Use the guard's current project inside writer callbacks; never acquire another store monitor there. */
    public Rig2DProfile loadVerified(ProjectStore.Project current,String rigId,String expectedSha256) throws Exception {
        String expected=digestArgument(expectedSha256);
        if(expected.isEmpty())throw new IllegalArgumentException("Exact rig digest required");
        if(current==null)throw new IllegalArgumentException("Current owned source project required");
        checkedId(current.id);checkedId(rigId);
        synchronized(PROFILE_LOCK) {
            Rig2DProfile rig=readRig(current,new File(directory(current.id,"rigs",false),rigId+".json"),rigId);
            requireExpected(expected,sha256(rig),"SOURCE_RIG_CHANGED");return rig;
        }
    }
    public JSONObject captureBinding(String projectId,String rigId) throws Exception {
        checkedId(rigId);ProjectStore.Project current=project(projectId);
        synchronized(PROFILE_LOCK){return binding(readRig(current,new File(directory(projectId,"rigs",false),rigId+".json"),rigId));}
    }
    /** Reads obtain the project before the file lock; no reverse SQLite writer acquisition. */
    public List<Rig2DProfile> list(String projectId) {
        ArrayList<Rig2DProfile> result=new ArrayList<>();ProjectStore.Project current=project(projectId);
        synchronized(PROFILE_LOCK) {
            File[] files=directory(projectId,"rigs",false).listFiles();if(files==null)return result;
            Arrays.sort(files,java.util.Comparator.comparing(File::getName));
            for(File file:files) {
                if(!file.getName().endsWith(".json"))continue;
                String id=file.getName().substring(0,file.getName().length()-5);
                try{checkedId(id);result.add(readRig(current,file,id));}catch(Exception invalid){ /* Not an accepted profile. */ }
                if(result.size()>=MAX_PROFILES)break;
            }
        }
        return result;
    }

    /** Pair records are working drafts; repeated ordered shots belong to StoryboardProgramStore. */
    public JSONObject saveContactPlan(StoryboardContactPlan plan,StoryboardChoreography.Beat beat,
            long expectedSourceUpdatedAt,String expectedPlanSha256) throws Exception {
        if(plan==null||beat==null)throw new IllegalArgumentException("Authored contacts and beat required");
        String expected=digestArgument(expectedPlanSha256);JSONObject proof=plan.bindingProof(),authored=plan.toJson();
        return projects.guardedProject(plan.projectId,expectedSourceUpdatedAt,current->{
            synchronized(PROFILE_LOCK) {
                StoryboardContactPlan verified=verifiedPlan(current,authored,proof,plan.sourceWidth,plan.sourceHeight);
                File dir=directory(plan.projectId,"contacts",true),target=new File(dir,pairKey(plan.fromAssetId,plan.toAssetId)+".json");requirePlainChild(target);
                if(target.exists())requireExpected(expected,contactDigest(readObject(target)),"SOURCE_CONTACTS_CHANGED");
                else if(!expected.isEmpty())throw new IllegalStateException("SOURCE_CONTACTS_CHANGED: draft no longer exists");
                if(!target.exists()&&profileCount(dir)>=160)throw new IllegalArgumentException("Contact draft limit is 160");
                JSONObject record=new JSONObject().put("version",1).put("projectId",plan.projectId)
                        .put("fromAssetId",plan.fromAssetId).put("toAssetId",plan.toAssetId)
                        .put("sourceWidth",plan.sourceWidth).put("sourceHeight",plan.sourceHeight)
                        .put("sourceUpdatedAt",current.updatedAt).put("beat",beat.name())
                        .put("contactPlan",verified.toJson()).put("bindingProof",verified.bindingProof());
                byte[] data=bytes(record);
                JSONObject receipt=record.put("sha256",contactDigest(record));
                writeAtomic(target,data);return receipt;
            }
        });
    }
    public JSONObject loadContactRecord(String projectId,String fromAssetId,String toAssetId,int sourceWidth,int sourceHeight) throws Exception {
        checkedId(fromAssetId);checkedId(toAssetId);ProjectStore.Project current=project(projectId);
        synchronized(PROFILE_LOCK) {
            JSONObject record=readObject(new File(directory(projectId,"contacts",false),pairKey(fromAssetId,toAssetId)+".json"));
            if(!projectId.equals(record.getString("projectId"))||!fromAssetId.equals(record.getString("fromAssetId"))||!toAssetId.equals(record.getString("toAssetId"))
                    ||record.getInt("sourceWidth")!=sourceWidth||record.getInt("sourceHeight")!=sourceHeight)
                throw new IllegalArgumentException("Contact source identity or oriented geometry changed");
            verifiedPlan(current,record.getJSONObject("contactPlan"),record.getJSONObject("bindingProof"),sourceWidth,sourceHeight);
            StoryboardChoreography.parse(record.getString("beat"));return record.put("sha256",contactDigest(record));
        }
    }
    private StoryboardContactPlan verifiedPlan(ProjectStore.Project current,JSONObject authored,JSONObject proof,int width,int height) throws Exception {
        Rig2DProfile[] rigs=new Rig2DProfile[4];int i=0;
        for(String key:new String[]{"blackFrom","whiteFrom","blackTo","whiteTo"}) {
            JSONObject bound=proof.getJSONObject(key);String id=bound.getString("rigId");checkedId(id);
            rigs[i]=readRig(current,new File(directory(current.id,"rigs",false),id+".json"),id);
            if(!canonicalJson(binding(rigs[i])).equals(canonicalJson(bound)))throw new IllegalStateException("SOURCE_RIG_CHANGED: contact draft rig differs");i++;
        }
        StoryboardContactPlan plan=StoryboardContactPlan.parse(authored,rigs[0],rigs[1],rigs[2],rigs[3],width,height);plan.requireBindingProof(proof);return plan;
    }

    public static JSONObject binding(Rig2DProfile rig) throws Exception {
        return new JSONObject().put("rigId",rig.id).put("projectId",rig.projectId).put("sourceAssetId",rig.sourceAssetId).put("black",rig.black).put("sha256",sha256(rig));
    }
    public static String sha256(Rig2DProfile rig) throws Exception { return sha256Json(Rig2DProfile.fromJson(rig.toJson()).toJson()); }
    public static String sha256Json(JSONObject value) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(canonicalJson(value).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(64);
        char[] hex="0123456789abcdef".toCharArray();
        for(byte b:digest){out.append(hex[(b&255)>>>4]);out.append(hex[b&15]);}return out.toString();
    }
    /** Stable semantic annotation digest independent of object insertion order. */
    public static String canonicalJson(JSONObject value) throws Exception {
        StringBuilder out=new StringBuilder();canonical(value,out,0,new int[]{0});return out.toString();
    }
    private static void canonical(Object value,StringBuilder out,int depth,int[] nodes) throws Exception {
        if(depth>12||++nodes[0]>8192)throw new IllegalArgumentException("Annotation JSON exceeds structural bounds");
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;TreeSet<String> keys=new TreeSet<>();
            for(Iterator<String> it=object.keys();it.hasNext();){String key=it.next();if(key.length()>128||keys.size()>=128)throw new IllegalArgumentException("Annotation object too large");keys.add(key);}
            out.append('{');boolean first=true;
            for(String key:keys){if(!first)out.append(',');first=false;out.append(JSONObject.quote(key)).append(':');canonical(object.get(key),out,depth+1,nodes);}out.append('}');
        } else if(value instanceof JSONArray) {
            JSONArray array=(JSONArray)value;if(array.length()>256)throw new IllegalArgumentException("Annotation array too large");
            out.append('[');for(int i=0;i<array.length();i++){if(i>0)out.append(',');canonical(array.get(i),out,depth+1,nodes);}out.append(']');
        } else if(value instanceof String) {
            if(((String)value).length()>1024)throw new IllegalArgumentException("Annotation string too long");out.append(JSONObject.quote((String)value));
        } else if(value instanceof Number) {
            if(!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Nonfinite annotation number");out.append(JSONObject.numberToString((Number)value));
        } else if(value instanceof Boolean)out.append(value);
        else if(value==null||value==JSONObject.NULL)out.append("null");
        else throw new IllegalArgumentException("Unsupported annotation value");
        if(out.length()>MAX_BYTES)throw new IllegalArgumentException("Annotation proof too large");
    }
    private ProjectStore.Project project(String id) {
        checkedId(id);ProjectStore.Project current=projects.get(id);if(current==null)throw new IllegalArgumentException("Rig project does not exist");return current;
    }
    private static void requireSource(ProjectStore.Project current,Rig2DProfile rig) {
        ProjectStore.Asset asset=current.asset(rig.sourceAssetId);
        if(!current.id.equals(rig.projectId)||asset==null||asset.mime==null||!asset.mime.startsWith("image/"))throw new IllegalArgumentException("Rig image must be imported into this project");
    }
    private Rig2DProfile readRig(ProjectStore.Project current,File file,String id) throws Exception {
        Rig2DProfile rig=Rig2DProfile.fromJson(readObject(file));
        if(!current.id.equals(rig.projectId)||!id.equals(rig.id))throw new IllegalArgumentException("Rig/profile identity mismatch");requireSource(current,rig);return rig;
    }
    private File directory(String projectId,String child,boolean create) {
        checkedId(projectId);
        try {
            File cursor=context.getFilesDir().getCanonicalFile();
            for(String part:new String[]{"creative_workspace","projects",projectId,child}) {
                cursor=new File(cursor,part);requirePlainChild(cursor);
                if(create&&!cursor.exists()&&!cursor.mkdir())throw new IllegalStateException("Cannot create annotation workspace");
                if(cursor.exists()&&!cursor.isDirectory())throw new IllegalStateException("Annotation path is not a directory");
            }
            return cursor;
        } catch(java.io.IOException error){throw new IllegalStateException("Unsafe annotation workspace",error);}
    }
    private static void requirePlainChild(File file) throws java.io.IOException {
        if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IllegalArgumentException("Annotation symlinks are not allowed");
    }
    private static JSONObject readObject(File file) throws Exception {
        requirePlainChild(file);
        if(!file.isFile()||file.length()<2||file.length()>MAX_BYTES)throw new IllegalArgumentException("Annotation not found or exceeds bounds");
        byte[] data=new byte[(int)file.length()];
        try(FileInputStream in=new FileInputStream(file)) {
            int total=0;while(total<data.length){int n=in.read(data,total,data.length-total);if(n<=0)throw new IllegalStateException("Incomplete annotation");total+=n;}
            if(in.read()!=-1)throw new IllegalStateException("Annotation changed during read");
        }
        String text=new String(data,StandardCharsets.UTF_8);int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<text.length();i++) {
            char ch=text.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(ch=='\\')escaped=true;else if(ch=='"')quoted=false;}
            else if(ch=='"')quoted=true;
            else if(ch=='{'||ch=='['){if(++depth>12)throw new IllegalArgumentException("Annotation nesting exceeds bounds");}
            else if(ch=='}'||ch==']'){if(--depth<0)throw new IllegalArgumentException("Unbalanced annotation record");}
        }
        if(quoted||depth!=0)throw new IllegalArgumentException("Incomplete annotation JSON");
        return new JSONObject(text);
    }
    private static byte[] bytes(JSONObject object) throws Exception {
        byte[] data=canonicalJson(object).getBytes(StandardCharsets.UTF_8);if(data.length>MAX_BYTES)throw new IllegalArgumentException("Annotation too large");return data;
    }
    private static void writeAtomic(File target,byte[] data) throws Exception {
        requirePlainChild(target);File temp=new File(target.getParentFile(),target.getName()+".tmp_"+UUID.randomUUID());
        try {
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);out.getFD().sync();}
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {if(temp.exists())temp.delete();}
    }
    private static int profileCount(File dir) {
        File[] files=dir.listFiles();if(files==null)throw new IllegalStateException("Cannot inspect annotation capacity");int count=0;
        for(File file:files)if(file.getName().endsWith(".json"))count++;return count;
    }
    private static String pairKey(String from,String to) throws Exception {checkedId(from);checkedId(to);return sha256Json(new JSONObject().put("from",from).put("to",to));}
    private static String contactDigest(JSONObject record) throws Exception {JSONObject copy=new JSONObject(record.toString());copy.remove("sha256");return sha256Json(copy);}
    private static String digestArgument(String value) {
        if(value==null||!value.isEmpty()&&!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Lowercase SHA-256 or empty create-only digest required");return value;
    }
    private static void requireExpected(String expected,String actual,String code) {if(expected.isEmpty()||!expected.equals(actual))throw new IllegalStateException(code+": reopen annotations before saving");}
    private static void checkedId(String value) {if(value==null||!value.matches("[A-Za-z0-9_-]{8,128}"))throw new IllegalArgumentException("Invalid annotation identity");}
}
