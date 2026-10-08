package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable, hashed native scene revisions. The AtomicFile head is the commit point. */
public final class SceneMemoryStore {
    private static final Object LOCK = new Object();
    private final CreativeWorkspace workspace;
    private final Context context;
    public SceneMemoryStore(Context context) { this.context=context.getApplicationContext(); workspace=new CreativeWorkspace(context); }
    private File directory(String projectId,String name) {
        VslCompiler.id(name);
        File dir=new File(workspace.projectRoot(projectId),"scenes/vsl_"+name);
        if(!dir.isDirectory()&&!dir.mkdirs()) throw new IllegalStateException("Cannot create scene memory"); return dir;
    }
    public JSONObject commit(ProjectStore.Project project,String source,int expectedRevision) throws Exception {
        JSONObject compiled=new VslCompiler().compile(source,project.id);
        String name=compiled.getString("name");
        synchronized(LOCK) {
            JSONObject prior=read(project.id,name,0); int base=prior==null?0:prior.getInt("revision");
            if(expectedRevision>=0 && base!=expectedRevision) throw new IllegalStateException("Scene revision conflict: expected "+expectedRevision+", actual "+base);
            JSONObject bindings=new JSONObject(); JSONArray objects=compiled.getJSONObject("graph").getJSONArray("objects");
            for(int i=0;i<objects.length();i++) {
                JSONObject entity=objects.getJSONObject(i); if(!entity.has("assetId")) continue;
                ProjectStore.Asset asset=project.asset(entity.getString("assetId"));
                if(asset==null||asset.mime==null||!(entity.getString("type").equals("video") ? asset.mime.startsWith("video/") : asset.mime.startsWith("image/"))) throw new IllegalArgumentException("Scene image must reference an imported image asset: "+entity.getString("id"));
                String hash;
                try(InputStream input=context.getContentResolver().openInputStream(Uri.parse(asset.uri))) {
                    if(input==null) throw new IOException("Reference image cannot be opened"); hash=hashStream(input,350L*1024*1024);
                }
                entity.put("uri",asset.uri); entity.put("sourceSha256",hash);
                bindings.put(entity.getString("id"),new JSONObject().put("assetId",asset.id).put("sha256",hash).put("identityValidation","unchecked"));
            }
            compiled.put("revision",base+1); compiled.put("parentRevision",base); compiled.put("source",source);
            compiled.put("identityMemory",bindings); compiled.put("createdAt",System.currentTimeMillis());
            compiled.put("delta",delta(prior,compiled));
            String payload=compiled.toString(); String sha=hash(payload.getBytes(StandardCharsets.UTF_8));
            File dir=directory(project.id,name), artifact=new File(dir,sha+".json");
            if(!artifact.isFile()) atomicWrite(artifact,payload);
            atomicWrite(new File(dir,"revision_"+(base+1)+".json"),new JSONObject().put("sha256",sha).toString());
            atomicWrite(new File(dir,"head.json"),new JSONObject().put("revision",base+1).put("sha256",sha).toString());
            compiled.put("artifactSha256",sha); return compiled;
        }
    }
    public JSONObject read(String projectId,String name,int revision) throws Exception {
        synchronized(LOCK) {
            File dir=directory(projectId,name), pointer=new File(dir,revision>0?"revision_"+revision+".json":"head.json");
            if(!pointer.isFile()) return null;
            JSONObject head=new JSONObject(readSmall(pointer)); String sha=head.getString("sha256");
            if(!sha.matches("[a-f0-9]{64}")) throw new IOException("Invalid scene digest");
            byte[] bytes=readSmall(new File(dir,sha+".json")).getBytes(StandardCharsets.UTF_8);
            if(!hash(bytes).equals(sha)) throw new IOException("Scene memory integrity mismatch");
            JSONObject value=new JSONObject(new String(bytes,StandardCharsets.UTF_8)); value.put("artifactSha256",sha); return value;
        }
    }
    public void verifyBindings(JSONObject scene,ProjectStore.Project project) throws Exception {
        JSONArray objects=scene.getJSONObject("graph").getJSONArray("objects");
        for(int i=0;i<objects.length();i++) {
            JSONObject o=objects.getJSONObject(i); if(!o.has("assetId")) continue;
            ProjectStore.Asset a=project.asset(o.getString("assetId")); if(a==null || !a.uri.equals(o.getString("uri"))) throw new IOException("Scene reference removed or replaced");
            try(InputStream input=context.getContentResolver().openInputStream(Uri.parse(a.uri))) {
                if(input==null || !hashStream(input,350L*1024*1024).equals(o.getString("sourceSha256"))) throw new IOException("Locked scene reference bytes changed");
            }
        }
    }
    public void checkpoint(JSONObject scene,JSONObject state) throws Exception {
        synchronized(LOCK) { atomicWrite(new File(directory(scene.getString("projectId"),scene.getString("name")),"run_"+scene.getInt("revision")+".json"),state.toString()); }
    }
    public JSONObject status(String projectId,String name) throws Exception {
        JSONObject scene=read(projectId,name,0); JSONObject result=new JSONObject().put("ok",true).put("scene",scene==null?JSONObject.NULL:scene);
        if(scene!=null) {
            File file=new File(directory(projectId,name),"run_"+scene.getInt("revision")+".json");
            if(file.isFile()) result.put("run",new JSONObject(readSmall(file)));
        }
        return result;
    }
    static JSONObject delta(JSONObject old,JSONObject next) throws Exception {
        JSONArray changed=new JSONArray(), preserved=new JSONArray(), deleted=new JSONArray();
        Map<String,String> before=new HashMap<>();
        if(old!=null) {
            JSONArray a=old.getJSONObject("graph").getJSONArray("objects");
            for(int i=0;i<a.length();i++) before.put(a.getJSONObject(i).getString("id"),canonical(a.getJSONObject(i)));
        }
        JSONArray a=next.getJSONObject("graph").getJSONArray("objects");
        for(int i=0;i<a.length();i++) { JSONObject o=a.getJSONObject(i); String id=o.getString("id");
            if(canonical(o).equals(before.remove(id))) preserved.put(id); else changed.put(id);
        }
        for(String id:before.keySet()) deleted.put(id);
        boolean global=old==null || !globalState(old).equals(globalState(next));
        return new JSONObject().put("baseRevision",old==null?0:old.optInt("revision"))
                .put("nextRevision",next.optInt("revision")).put("changedEntities",changed).put("preservedEntities",preserved)
                .put("deletedEntities",deleted).put("fullFrame",global).put("intervalMs",new JSONArray(new long[]{0,next.getLong("durationMs")}));
    }
    private static String globalState(JSONObject scene) throws Exception {
        JSONObject g=new JSONObject(scene.getJSONObject("graph").toString()); g.remove("objects");
        return canonical(g)+scene.optInt("width")+":"+scene.optInt("height")+":"+scene.optInt("fps")+":"+scene.optLong("durationMs");
    }
    public static String canonical(Object value) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject o=(JSONObject)value; List<String> keys=new ArrayList<>(); o.keys().forEachRemaining(keys::add); Collections.sort(keys);
            StringBuilder b=new StringBuilder("{"); for(String k:keys) b.append(JSONObject.quote(k)).append(':').append(canonical(o.get(k))).append(','); return b.append('}').toString();
        }
        if(value instanceof JSONArray) { StringBuilder b=new StringBuilder("["); JSONArray a=(JSONArray)value; for(int i=0;i<a.length();i++) b.append(canonical(a.get(i))).append(','); return b.append(']').toString(); }
        if (value instanceof Number) return new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        return value == null || value == JSONObject.NULL ? "null" : value instanceof String ? JSONObject.quote((String)value) : value.toString();
    }
    public static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public static String hashStream(InputStream input,long limit) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[32768]; int n; long count=0;
        while((n=input.read(buffer))!=-1) {if(Thread.currentThread().isInterrupted()) throw new InterruptedException(); count+=n; if(count>limit) throw new IOException("Artifact exceeds size budget"); digest.update(buffer,0,n);} return hex(digest.digest());
    }
    private static String hex(byte[] bytes) { StringBuilder b=new StringBuilder(); for(byte v:bytes) b.append(String.format(Locale.US,"%02x",v&255)); return b.toString(); }
    static String readSmall(File file) throws IOException {
        if(file.length()>1024*1024) throw new IOException("Scene manifest exceeds 1MB");
        try(InputStream input=new FileInputStream(file)) { ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n; while((n=input.read(b))!=-1) {out.write(b,0,n); if(out.size()>1024*1024) throw new IOException("Manifest too large");} return out.toString(StandardCharsets.UTF_8.name()); }
    }
    static void atomicWrite(File path,String value) throws IOException {
        AtomicFile file=new AtomicFile(path); FileOutputStream out=null;
        try {out=file.startWrite(); out.write(value.getBytes(StandardCharsets.UTF_8)); file.finishWrite(out);} catch(IOException e) {if(out!=null) file.failWrite(out); throw e;}
    }
}
