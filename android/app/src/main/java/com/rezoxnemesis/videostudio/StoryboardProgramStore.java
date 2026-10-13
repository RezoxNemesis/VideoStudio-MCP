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
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** Ordered, source-bound owner/MCP shot drafts. Saving completeness is not cinematic approval. */
public final class StoryboardProgramStore {
    private static final Object PROGRAM_LOCK=new Object();
    private static final int MAX_BYTES=512*1024,MAX_PROGRAMS=64;
    private final Context context;
    private final ProjectStore projects;
    private final Rig2DProjectStore rigs;
    public StoryboardProgramStore(Context context,ProjectStore projects){
        if(context==null||projects==null)throw new IllegalArgumentException("Program store requires native context and project store");
        this.context=context.getApplicationContext();this.projects=projects;rigs=new Rig2DProjectStore(this.context,projects);
    }

    /** Worker-only, source writer -> program file -> profile lock. Empty digest means create only. */
    public JSONObject save(String projectId,long expectedSourceUpdatedAt,String expectedProgramSha256,JSONObject program)throws Exception{
        id(projectId);String expected=digest(expectedProgramSha256,true);
        if(program==null)throw new IllegalArgumentException("Ordered storyboard program required");
        JSONObject draft=copy(program);String programId=draft.optString("programId","");if(programId.isEmpty())programId=UUID.randomUUID().toString();id(programId);
        ProjectStore.Project snapshot=projects.get(projectId);if(snapshot==null)throw new IllegalArgumentException("Source project unavailable");
        if(snapshot.updatedAt!=expectedSourceUpdatedAt)throw new IllegalStateException("SOURCE_PROJECT_CHANGED: reopen the source before saving annotations");
        Measurement measured=measure(snapshot,draft.getJSONArray("sourceAssetIds"));
        final String acceptedId=programId;
        return projects.guardedProject(projectId,expectedSourceUpdatedAt,current->{
            synchronized(PROGRAM_LOCK){
                File dir=directory(projectId,true),target=new File(dir,acceptedId+".json");plain(target);
                if(target.exists()){
                    JSONObject prior=read(target);String actual=hash(prior);
                    if(expected.isEmpty()||!expected.equals(actual))throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: reopen the saved program before replacing it");
                }else if(!expected.isEmpty())throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: saved program no longer exists");
                else {File[] entries=dir.listFiles((folder,name)->name.endsWith(".json"));if(entries!=null&&entries.length>=MAX_PROGRAMS)throw new IllegalArgumentException("Program draft limit is 64");}
                JSONObject frozen=normalize(current,acceptedId,draft,null,measured);byte[] bytes=canonical(frozen).getBytes(StandardCharsets.UTF_8);JSONObject publicationReceipt=receipt(frozen);
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                writeAtomic(target,bytes);return publicationReceipt;
            }
        });
    }

    /** Worker-only; rejects stale sources/profiles instead of silently rebinding an old contact draft. */
    public JSONObject load(String projectId,String programId)throws Exception{
        id(projectId);id(programId);ProjectStore.Project current=projects.get(projectId);
        if(current==null)throw new IllegalArgumentException("Source project is unavailable");
        final JSONObject acceptedFile;synchronized(PROGRAM_LOCK){acceptedFile=read(new File(directory(projectId,false),programId+".json"));}
        final String acceptedDigest=hash(acceptedFile);final Measurement measured=measure(current,acceptedFile.getJSONArray("sourceAssetIds"));
        return projects.guardedProject(projectId,current.updatedAt,accepted->{
            synchronized(PROGRAM_LOCK){
                JSONObject saved=read(new File(directory(projectId,false),programId+".json"));
                if(!acceptedDigest.equals(hash(saved)))throw new IllegalStateException("SOURCE_PROGRAM_CHANGED: saved program changed during source verification");
                if(!projectId.equals(saved.getString("projectId"))||!programId.equals(saved.getString("programId")))throw new IllegalArgumentException("Saved program identity differs");
                normalize(accepted,programId,saved,saved.getJSONObject("sourceEntries"),measured);
                return receipt(saved);
            }
        });
    }

    /** Edit-only bounded read. It preserves old proofs and digest; it cannot authorize a render or recovery. */
    public JSONObject readForEdit(String projectId,String programId)throws Exception{
        id(projectId);id(programId);ProjectStore.Project current=projects.get(projectId);if(current==null)throw new IllegalArgumentException("Source project unavailable");
        final JSONObject saved;synchronized(PROGRAM_LOCK){saved=read(new File(directory(projectId,false),programId+".json"));}
        fields(saved,"version","programId","projectId","name","sourceProjectUpdatedAt","sourceAssetIds","sourceRigBindings","sourceEntries","contactPlans","choreography","missingAnnotations","annotationComplete","cinematicApproved");
        if(saved.getInt("version")!=1||!projectId.equals(saved.getString("projectId"))||!programId.equals(saved.getString("programId")))throw new IllegalArgumentException("Saved program identity/schema differs");
        JSONArray order=saved.getJSONArray("sourceAssetIds"),contacts=saved.getJSONArray("contactPlans"),beats=saved.getJSONArray("choreography"),warnings=new JSONArray();JSONObject bindings=saved.getJSONObject("sourceRigBindings"),entries=saved.getJSONObject("sourceEntries");
        if(order.length()<2||order.length()>160||contacts.length()!=order.length()-1||beats.length()!=contacts.length())throw new IllegalArgumentException("Saved program order is outside bounds");
        if(saved.getLong("sourceProjectUpdatedAt")!=current.updatedAt)warnings.put("Source project revision changed; reopen/review the selected source geometry before saving.");
        TreeSet<String> seen=new TreeSet<>();for(int index=0;index<order.length();index++){
            String assetId=order.getString(index);id(assetId);if(!seen.add(assetId))continue;ProjectStore.Asset asset=current.asset(assetId);if(asset==null||asset.mime==null||!asset.mime.startsWith("image/"))warnings.put(assetId+": saved source is no longer a registered project image; remove or explicitly replace its ordered occurrences. Old proofs are retained for editing only.");
            JSONObject source=entries.getJSONObject(assetId);fields(source,"assetId","mime","metadataSha256","sourceSha256","bytes","codedWidth","codedHeight","exifOrientation","orientedWidth","orientedHeight");if(!assetId.equals(source.getString("assetId"))||!source.getString("mime").startsWith("image/"))throw new IllegalArgumentException("Saved source record identity differs");digest(source.getString("sourceSha256"),false);digest(source.getString("metadataSha256"),false);long sw=boundedInteger(source,"orientedWidth",16,8192),sh=boundedInteger(source,"orientedHeight",16,8192),cw=boundedInteger(source,"codedWidth",16,8192),ch=boundedInteger(source,"codedHeight",16,8192),orientation=boundedInteger(source,"exifOrientation",1,8);boundedInteger(source,"bytes",1,StoryboardSourceManifest.MAX_IMAGE_BYTES);if(sw!=(orientation>=5?ch:cw)||sh!=(orientation>=5?cw:ch))throw new IllegalArgumentException("Saved source orientation geometry differs");
            JSONObject row=bindings.getJSONObject(assetId);fields(row,"blackRigId","whiteRigId","blackRigSha256","whiteRigSha256");for(boolean black:new boolean[]{true,false}){String role=black?"black":"white",rigId=row.getString(role+"RigId"),sha=digest(row.getString(role+"RigSha256"),false);id(rigId);
                try{JSONObject actual=rigs.captureBinding(projectId,rigId);if(!assetId.equals(actual.getString("sourceAssetId"))||actual.getBoolean("black")!=black||!sha.equals(actual.getString("sha256")))warnings.put(assetId+" "+role+": saved actor binding changed; select the current exact profile and reauthor affected foot plants.");}catch(Exception unavailable){warnings.put(assetId+" "+role+": saved profile unavailable; explicitly choose a replacement profile.");}
            }
        }
        if(bindings.length()!=seen.size()||entries.length()!=seen.size())throw new IllegalArgumentException("Saved program has foreign source records");for(Iterator<String> keys=entries.keys();keys.hasNext();)if(!seen.contains(keys.next()))throw new IllegalArgumentException("Saved program has foreign source records");for(Iterator<String> keys=bindings.keys();keys.hasNext();)if(!seen.contains(keys.next()))throw new IllegalArgumentException("Saved program has foreign actor records");
        for(int index=0;index<contacts.length();index++){
            String from=order.getString(index),to=order.getString(index+1);JSONObject contact=contacts.getJSONObject(index),beat=beats.getJSONObject(index);fields(contact,"shotIndex","fromAssetId","toAssetId","sourceWidth","sourceHeight","contactPlan","bindingProof");fields(beat,"shotIndex","fromAssetId","toAssetId","beat");pair(contact,index,from,to);pair(beat,index,from,to);if(beat.has("beat"))StoryboardChoreography.parse(beat.getString("beat"));
            long width=boundedInteger(contact,"sourceWidth",16,8192),height=boundedInteger(contact,"sourceHeight",16,8192);JSONObject authored=contact.getJSONObject("contactPlan");validateEditIntervals(authored);
            JSONObject proof=contact.getJSONObject("bindingProof");fields(proof,"version","projectId","fromAssetId","toAssetId","sourceWidth","sourceHeight","coordinateSpace","contactSha256","blackFrom","whiteFrom","blackTo","whiteTo");if(!projectId.equals(proof.getString("projectId"))||!from.equals(proof.getString("fromAssetId"))||!to.equals(proof.getString("toAssetId"))||proof.getInt("sourceWidth")!=width||proof.getInt("sourceHeight")!=height||!"oriented-source-uv".equals(proof.getString("coordinateSpace"))||!Rig2DProjectStore.sha256Json(authored).equals(proof.getString("contactSha256")))throw new IllegalArgumentException("Saved contact proof/schema differs at shot "+index);
            for(String field:new String[]{"blackFrom","whiteFrom","blackTo","whiteTo"}){boolean black=field.startsWith("black");String assetId=field.endsWith("From")?from:to,role=black?"black":"white";JSONObject bound=proof.getJSONObject(field),row=bindings.getJSONObject(assetId);fields(bound,"rigId","projectId","sourceAssetId","black","sha256");if(!projectId.equals(bound.getString("projectId"))||!assetId.equals(bound.getString("sourceAssetId"))||bound.getBoolean("black")!=black||!row.getString(role+"RigId").equals(bound.getString("rigId"))||!row.getString(role+"RigSha256").equals(bound.getString("sha256")))throw new IllegalArgumentException("Saved contact profile proof differs");}
        }
        return receipt(saved).put("editOnly",true).put("sourceBytesVerified",false).put("renderReady",false).put("currentSourceProjectUpdatedAt",current.updatedAt).put("stale",warnings.length()>0).put("staleWarnings",warnings);
    }
    private static long boundedInteger(JSONObject row,String field,long min,long max){Object raw=row.opt(field);if(!(raw instanceof Number))throw new IllegalArgumentException("Invalid saved program number: "+field);double value=((Number)raw).doubleValue();if(!Double.isFinite(value)||value!=Math.rint(value)||value<min||value>max)throw new IllegalArgumentException("Saved program number outside bounds: "+field);return(long)value;}
    private static double phase(Object raw){if(!(raw instanceof Number))throw new IllegalArgumentException("Saved contact coordinates must be numeric");double value=((Number)raw).doubleValue();if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Saved contact coordinates are outside 0..1");return value;}
    private static void fields(JSONObject value,String... allowed){java.util.Set<String> names=new java.util.HashSet<>(Arrays.asList(allowed));for(Iterator<String> i=value.keys();i.hasNext();)if(!names.contains(i.next()))throw new IllegalArgumentException("Unknown saved program schema field");}
    private static void validateEditIntervals(JSONObject authored)throws Exception{
        fields(authored,"black","white");for(String actor:new String[]{"black","white"}){JSONArray intervals=authored.getJSONArray(actor);if(intervals.length()>32)throw new IllegalArgumentException("Saved contact interval limit exceeded");for(int i=0;i<intervals.length();i++){JSONObject interval=intervals.getJSONObject(i);fields(interval,"foot","start","end","anchor");String foot=interval.getString("foot");if(!"leadingFoot".equals(foot)&&!"trailingFoot".equals(foot))throw new IllegalArgumentException("Saved contact foot is invalid");if(phase(interval.get("start"))>=phase(interval.get("end")))throw new IllegalArgumentException("Saved contact interval is invalid");JSONArray anchor=interval.getJSONArray("anchor");if(anchor.length()!=2)throw new IllegalArgumentException("Saved contact anchor is invalid");phase(anchor.get(0));phase(anchor.get(1));}}
    }

    /** Local metadata listing only. A listed draft is validated against current sources by load. */
    public JSONArray list(String projectId)throws Exception{
        id(projectId);JSONArray result=new JSONArray();final File[] files;synchronized(PROGRAM_LOCK){files=directory(projectId,false).listFiles((folder,name)->name.endsWith(".json"));}if(files==null)return result;
        Arrays.sort(files,java.util.Comparator.comparing(File::getName));
        for(File file:files){if(result.length()>=MAX_PROGRAMS)break;try{JSONObject item=read(file);result.put(new JSONObject().put("programId",item.getString("programId")).put("name",item.optString("name","Storyboard program")).put("sha256",hash(item)).put("shotCount",item.getJSONArray("contactPlans").length()).put("sourceProjectUpdatedAt",item.getLong("sourceProjectUpdatedAt")));}catch(Exception malformed){/* Invalid or concurrently replaced listings are not accepted source proof. */}}
        return result;
    }

    private static final class Measurement {final JSONObject entries=new JSONObject();final Map<String,String> metadata=new LinkedHashMap<>();}
    /** Provider reads run before the shared SQLite writer. Publication checks the exact asset registry snapshot. */
    private Measurement measure(ProjectStore.Project project,JSONArray order)throws Exception{
        if(order.length()<2||order.length()>160)throw new IllegalArgumentException("Select 2..160 ordered source occurrences");Measurement measured=new Measurement();long total=0;
        for(int i=0;i<order.length();i++){String assetId=order.getString(i);id(assetId);if(measured.entries.has(assetId))continue;ProjectStore.Asset asset=project.asset(assetId);if(asset==null||asset.mime==null||!asset.mime.startsWith("image/"))throw new IllegalArgumentException("Program source must be a registered image: "+assetId);
            measured.metadata.put(assetId,canonical(asset.toJson()));JSONObject entry=StoryboardSourceManifest.captureSource(context,asset);total=Math.addExact(total,entry.getLong("bytes"));if(total>StoryboardSourceManifest.MAX_TOTAL_BYTES)throw new IllegalArgumentException("Programme original sources exceed 512 MiB");measured.entries.put(assetId,entry);
        }return measured;
    }
    private JSONObject normalize(ProjectStore.Project project,String programId,JSONObject given,JSONObject expectedSources,Measurement measured)throws Exception{
        JSONArray order=given.getJSONArray("sourceAssetIds");if(order.length()<2||order.length()>160)throw new IllegalArgumentException("Select 2..160 ordered source frame occurrences");
        JSONObject declared=given.getJSONObject("sourceRigBindings"),bound=new JSONObject(),sourceEntries=new JSONObject();
        Map<String,Rig2DProfile[]> profiles=new LinkedHashMap<>();JSONArray acceptedOrder=new JSONArray();
        for(int i=0;i<order.length();i++){
            String assetId=order.getString(i);id(assetId);acceptedOrder.put(assetId);if(profiles.containsKey(assetId))continue;
            ProjectStore.Asset asset=project.asset(assetId);if(asset==null||asset.mime==null||!asset.mime.startsWith("image/"))throw new IllegalArgumentException("Program source must be a registered image: "+assetId);
            if(!canonical(asset.toJson()).equals(measured.metadata.get(assetId)))throw new IllegalStateException("SOURCE_IMAGE_CHANGED: registered source metadata changed during verification");
            JSONObject source=measured.entries.getJSONObject(assetId);
            if(expectedSources!=null&&!canonical(source).equals(canonical(expectedSources.getJSONObject(assetId))))throw new IllegalStateException("SOURCE_IMAGE_CHANGED: saved program artwork changed: "+assetId);
            sourceEntries.put(assetId,source);JSONObject row=declared.getJSONObject(assetId);Rig2DProfile[] pair=new Rig2DProfile[2];JSONObject binding=new JSONObject();
            for(int actor=0;actor<2;actor++){
                String role=actor==0?"black":"white",rigId=row.getString(role+"RigId"),sha=digest(row.getString(role+"RigSha256"),false);id(rigId);
                Rig2DProfile rig=rigs.loadVerified(project,rigId,sha);
                if(!assetId.equals(rig.sourceAssetId)||rig.black!=(actor==0))throw new IllegalArgumentException("Exact source profile ownership differs: "+rigId);
                if(rig.sourceSha256!=null&&!rig.sourceSha256.equals(source.getString("sourceSha256")))throw new IllegalStateException("SOURCE_IMAGE_CHANGED: actor marks belong to different image bytes");
                pair[actor]=rig;binding.put(role+"RigId",rigId).put(role+"RigSha256",sha);
            }
            profiles.put(assetId,pair);bound.put(assetId,binding);
        }
        JSONArray contacts=given.getJSONArray("contactPlans"),beats=given.getJSONArray("choreography");int count=order.length()-1;
        if(contacts.length()!=count||beats.length()!=count)throw new IllegalArgumentException("One ordered contact and choreography record is required per shot, including repeated source pairs");
        JSONArray acceptedContacts=new JSONArray(),acceptedBeats=new JSONArray(),missing=new JSONArray();
        for(int index=0;index<count;index++){
            String from=order.getString(index),to=order.getString(index+1);JSONObject contact=contacts.getJSONObject(index),beat=beats.getJSONObject(index);pair(contact,index,from,to);pair(beat,index,from,to);
            JSONObject a=sourceEntries.getJSONObject(from),b=sourceEntries.getJSONObject(to);int width=a.getInt("orientedWidth"),height=a.getInt("orientedHeight");
            if((long)width*b.getInt("orientedHeight")!=(long)b.getInt("orientedWidth")*height)throw new IllegalArgumentException("Shot requires matching oriented source aspect; register images before authoring contacts");
            if(contact.getInt("sourceWidth")!=width||contact.getInt("sourceHeight")!=height)throw new IllegalArgumentException("Contact source geometry differs at shot "+index);
            Rig2DProfile[] start=profiles.get(from),end=profiles.get(to);
            StoryboardContactPlan plan=StoryboardContactPlan.parse(contact.getJSONObject("contactPlan"),start[0],start[1],end[0],end[1],width,height);
            if(contact.has("bindingProof"))plan.requireBindingProof(contact.getJSONObject("bindingProof"));
            acceptedContacts.put(new JSONObject().put("shotIndex",index).put("fromAssetId",from).put("toAssetId",to).put("sourceWidth",width).put("sourceHeight",height).put("contactPlan",plan.toJson()).put("bindingProof",plan.bindingProof()));
            JSONObject acceptedBeat=new JSONObject().put("shotIndex",index).put("fromAssetId",from).put("toAssetId",to);String direction=beat.optString("beat","");
            if(direction.isEmpty())missing.put("Shot "+(index+1)+": choreography beat");else acceptedBeat.put("beat",StoryboardChoreography.parse(direction).name());acceptedBeats.put(acceptedBeat);
            if(!plan.hasContacts(true))missing.put("Shot "+(index+1)+": black foot contacts");if(!plan.hasContacts(false))missing.put("Shot "+(index+1)+": white foot contacts");
        }
        for(Map.Entry<String,Rig2DProfile[]> entry:profiles.entrySet())for(Rig2DProfile rig:entry.getValue()){
            String actor=rig.black?"black":"white";if(rig.sourceSha256==null)missing.put(entry.getKey()+" "+actor+": reviewed source byte binding");
            if(rig.outline.length<3)missing.put(entry.getKey()+" "+actor+": explicit actor polygon");
            if(rig.swordHilt==null||rig.swordTip==null||rig.swordOutline==null||rig.swordOutline.length<3)missing.put(entry.getKey()+" "+actor+": original sword hilt/tip/mask");
        }
        String name=given.optString("name","Storyboard program").trim();if(name.isEmpty()||name.length()>128)throw new IllegalArgumentException("Program name must contain 1..128 characters");
        return new JSONObject().put("version",1).put("programId",programId).put("projectId",project.id).put("name",name).put("sourceProjectUpdatedAt",project.updatedAt).put("sourceAssetIds",acceptedOrder).put("sourceRigBindings",bound).put("sourceEntries",sourceEntries).put("contactPlans",acceptedContacts).put("choreography",acceptedBeats).put("missingAnnotations",missing).put("annotationComplete",missing.length()==0).put("cinematicApproved",false);
    }
    private static void pair(JSONObject row,int index,String from,String to)throws Exception{
        Object declared=row.opt("shotIndex");if(!(declared instanceof Number)||((Number)declared).doubleValue()!=index||!from.equals(row.getString("fromAssetId"))||!to.equals(row.getString("toAssetId")))throw new IllegalArgumentException("Shot identity differs from exact array position "+index);
    }
    private static JSONObject receipt(JSONObject payload)throws Exception{return copy(payload).put("sha256",hash(payload));}
    private static String hash(JSONObject payload)throws Exception{JSONObject value=copy(payload);value.remove("sha256");byte[] bytes=MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(64);for(byte b:bytes){out.append("0123456789abcdef".charAt((b&255)>>>4));out.append("0123456789abcdef".charAt(b&15));}return out.toString();}
    private static JSONObject copy(JSONObject value)throws Exception{String text=canonical(value);return new JSONObject(text);}
    private static String canonical(Object value)throws Exception{StringBuilder out=new StringBuilder();canonical(value,out,0,new int[]{0});return out.toString();}
    private static void canonical(Object value,StringBuilder out,int depth,int[] nodes)throws Exception{
        if(depth>16||++nodes[0]>65536)throw new IllegalArgumentException("Program JSON exceeds structural bounds");
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;TreeSet<String> keys=new TreeSet<>();for(Iterator<String> i=object.keys();i.hasNext();){String key=i.next();if(key.length()>128||keys.size()>=256)throw new IllegalArgumentException("Program object exceeds bounds");keys.add(key);}out.append('{');boolean first=true;for(String key:keys){if(!first)out.append(',');first=false;out.append(JSONObject.quote(key)).append(':');canonical(object.get(key),out,depth+1,nodes);}out.append('}');}
        else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;if(a.length()>1024)throw new IllegalArgumentException("Program array exceeds bounds");out.append('[');for(int i=0;i<a.length();i++){if(i>0)out.append(',');canonical(a.get(i),out,depth+1,nodes);}out.append(']');}
        else if(value instanceof String){if(((String)value).length()>1024)throw new IllegalArgumentException("Program string exceeds bounds");out.append(JSONObject.quote((String)value));}
        else if(value instanceof Number){if(!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Program number is nonfinite");out.append(JSONObject.numberToString((Number)value));}
        else if(value instanceof Boolean)out.append(value);else if(value==null||value==JSONObject.NULL)out.append("null");else throw new IllegalArgumentException("Unsupported program value");
        if(out.length()>MAX_BYTES)throw new IllegalArgumentException("Program exceeds 512 KiB");
    }
    private static String digest(String value,boolean empty){if(value==null)value="";if(empty&&value.isEmpty())return value;if(!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Exact lowercase SHA-256 required");return value;}
    private static void id(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("Unsafe source/program identity");}
    private static void plain(File file)throws Exception{if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IllegalArgumentException("Symlink annotation path rejected");}
    private File directory(String projectId,boolean create)throws Exception{File cursor=context.getFilesDir().getCanonicalFile();for(String part:new String[]{"creative_workspace","projects",projectId,"storyboard_programs"}){cursor=new File(cursor,part);plain(cursor);if(create&&!cursor.exists()&&!cursor.mkdir())throw new IllegalStateException("Cannot create storyboard workspace");if(cursor.exists()&&!cursor.isDirectory())throw new IllegalArgumentException("Storyboard workspace is not a directory");}return cursor;}
    private static JSONObject read(File file)throws Exception{plain(file);if(!file.isFile()||file.length()>MAX_BYTES)throw new IllegalArgumentException("Saved storyboard program unavailable or too large");byte[] data=new byte[(int)file.length()];try(FileInputStream in=new FileInputStream(file)){int offset=0,count;while(offset<data.length&&(count=in.read(data,offset,data.length-offset))>0)offset+=count;if(offset!=data.length||in.read()!=-1)throw new IllegalArgumentException("Saved program changed while reading");}return new JSONObject(new String(data,StandardCharsets.UTF_8));}
    private static void writeAtomic(File target,byte[] bytes)throws Exception{if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("Program exceeds 512 KiB");if(Thread.currentThread().isInterrupted())throw new InterruptedException();File temp=File.createTempFile(".program_",".tmp",target.getParentFile());try{try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}if(Thread.currentThread().isInterrupted())throw new InterruptedException();try{Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(java.nio.file.AtomicMoveNotSupportedException unavailable){Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}}finally{if(temp.exists())temp.delete();}}
}
