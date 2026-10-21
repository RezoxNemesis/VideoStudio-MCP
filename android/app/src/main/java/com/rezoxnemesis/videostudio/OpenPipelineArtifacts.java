package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Inert, bounded job exchange. Hashes prove bytes, never model quality or producer identity. */
public final class OpenPipelineArtifacts {
    public static final String REQUEST_SCHEMA="videostudio.openpipeline.request.v1";
    public static final String RESULT_SCHEMA="videostudio.openpipeline.result.v1";
    public static final long MAX_ARCHIVE=256L*1024*1024, MAX_EXPANDED=512L*1024*1024;
    public static final int MAX_JSON=512*1024, MAX_FILES=256;
    private OpenPipelineArtifacts() {}

    public static String id(String value) {
        if(value==null||!value.matches("[A-Za-z0-9_-]{1,96}"))throw new IllegalArgumentException("Invalid pipeline identity");
        return value;
    }
    public static String sha(String value) {
        if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Required SHA-256 is invalid");
        return value;
    }
    public static String path(String value) {
        if(value==null||!value.matches("[A-Za-z0-9_./-]{1,180}")||value.startsWith("/"))throw new IllegalArgumentException("Invalid artifact path");
        for(String part:value.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("Artifact traversal unavailable");
        return value;
    }
    public static String hash(byte[] bytes)throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    public static String hash(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1){interrupted();digest.update(b,0,n);}}
        return hex(digest.digest());
    }
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
    static void interrupted()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Pipeline operation cancelled");}
    public static long copy(InputStream in,OutputStream out,long limit)throws Exception {
        byte[] b=new byte[128*1024];long total=0;int n;
        while((n=in.read(b))!=-1){interrupted();total+=n;if(total>limit)throw new IllegalArgumentException("Artifact exceeds resource limit");out.write(b,0,n);}
        return total;
    }
    public static void writeAtomic(File file,byte[] bytes)throws Exception {
        writeAtomic(file,bytes,MAX_JSON);
    }
    static void writeAtomic(File file,byte[] bytes,int maximumBytes)throws Exception {
        if(bytes.length>maximumBytes)throw new IllegalArgumentException("Metadata exceeds resource limit");
        File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create pipeline directory");
        File temp=new File(parent,".write_"+UUID.randomUUID());
        try{try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
        finally{temp.delete();}
    }
    public static JSONObject json(InputStream in)throws Exception {
        return json(in,MAX_JSON);
    }
    static JSONObject json(InputStream in,int maximumBytes)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(in,bytes,maximumBytes);
        JSONTokener reader=new JSONTokener(new String(bytes.toByteArray(),StandardCharsets.UTF_8));Object value=reader.nextValue();
        if(!(value instanceof JSONObject)||reader.nextClean()!=0)throw new IllegalArgumentException("One JSON object required");
        JSONObject result=(JSONObject)value;validateJson(result,0,new int[]{0});return result;
    }
    public static JSONObject json(File file)throws Exception {try(InputStream in=new FileInputStream(file)){return json(in);}}
    static JSONObject json(File file,int maximumBytes)throws Exception {try(InputStream in=new FileInputStream(file)){return json(in,maximumBytes);}}
    static void validateJson(Object value,int depth,int[] count)throws Exception {
        if(depth>24||++count[0]>65536)throw new IllegalArgumentException("Metadata nesting exceeds resource limit");
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;Iterator<String> keys=o.keys();while(keys.hasNext())validateJson(o.get(keys.next()),depth+1,count);}
        else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)validateJson(a.get(i),depth+1,count);}
        else if(value instanceof Number&&!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Finite JSON required");
    }
    public static void fields(JSONObject value,String... allowed)throws Exception {
        Set<String> known=new HashSet<>(Arrays.asList(allowed));Iterator<String> keys=value.keys();while(keys.hasNext())if(!known.contains(keys.next()))throw new IllegalArgumentException("Unknown pipeline parameter");
    }
    public static long integer(JSONObject value,String key,long min,long max)throws Exception {
        Object v=value.get(key);if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<min||((Number)v).longValue()>max)throw new IllegalArgumentException("Invalid integer: "+key);return ((Number)v).longValue();
    }
    static File target(File root,String relative)throws Exception {
        path(relative);File out=new File(root,relative), cursor=out;
        while(cursor!=null&&cursor.getAbsolutePath().startsWith(root.getAbsolutePath())){if(java.nio.file.Files.isSymbolicLink(cursor.toPath()))throw new IllegalArgumentException("Artifact symlink unavailable");cursor=cursor.getParentFile();}
        if(!out.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IllegalArgumentException("Artifact must belong to staging");return out;
    }
    public static void packageRequest(File root,JSONObject request,File output)throws Exception {
        byte[] metadata=request.toString().getBytes(StandardCharsets.UTF_8);if(metadata.length>MAX_JSON)throw new IllegalArgumentException("Request metadata exceeds limit");
        JSONArray sources=request.getJSONArray("sources");if(sources.length()>MAX_FILES-1)throw new IllegalArgumentException("Too many sources");
        if(output.exists())throw new IllegalStateException("Immutable request already exists");
        File temporary=new File(output.getParentFile(),output.getName()+".partial");long expanded=metadata.length;
        try{try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(temporary))){entry(zip,"request.json",metadata);Set<String> seen=new HashSet<>();seen.add("request.json");for(int i=0;i<sources.length();i++){JSONObject record=sources.getJSONObject(i);String relative=path(record.getString("path"));if(!seen.add(relative))throw new IllegalArgumentException("Duplicate request artifact");File source=target(root,relative);long size=integer(record,"bytes",1,MAX_ARCHIVE);expanded+=size;if(expanded>MAX_EXPANDED||!source.isFile()||source.length()!=size||!sha(record.getString("sha256")).equals(hash(source)))throw new IllegalArgumentException("Source changed before packaging");zip.putNextEntry(new ZipEntry(relative));try(InputStream in=new FileInputStream(source)){if(copy(in,zip,size)!=size)throw new IOException("Source copy incomplete");}zip.closeEntry();}}
            if(temporary.length()>MAX_ARCHIVE)throw new IllegalArgumentException("Request archive exceeds limit");if(!temporary.renameTo(output))throw new IOException("Cannot publish immutable request");
        }finally{temporary.delete();}
    }
    static void entry(ZipOutputStream zip,String name,byte[] bytes)throws Exception {ZipEntry entry=new ZipEntry(name);entry.setTime(0);zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry();}

    /** Extract only declared passive output files, binding the exact pending request. */
    public static JSONObject verifyResult(File archive,File stage,JSONObject request,String requestSha256)throws Exception {
        if(!archive.isFile()||archive.length()<1||archive.length()>MAX_ARCHIVE||stage.exists())throw new IllegalArgumentException("Invalid result archive/staging");
        if(!stage.mkdirs())throw new IOException("Cannot stage result");
        try(ZipFile zip=new ZipFile(archive)){
            Map<String,ZipEntry> entries=new LinkedHashMap<>();Enumeration<? extends ZipEntry> all=zip.entries();long declaredBytes=0;
            while(all.hasMoreElements()){ZipEntry item=all.nextElement();String name=path(item.getName());if(item.isDirectory()||entries.put(name,item)!=null||entries.size()>MAX_FILES||item.getSize()<0||item.getSize()>MAX_EXPANDED-declaredBytes)throw new IllegalArgumentException("Invalid result ZIP entries");declaredBytes+=item.getSize();}
            if(stage.getUsableSpace()<declaredBytes+ResumableTransferManager.STORAGE_RESERVE_BYTES)throw new IllegalStateException("Insufficient storage for verified result staging and reserve");
            ZipEntry manifestEntry=entries.get("manifest.json");if(manifestEntry==null)throw new IllegalArgumentException("Result manifest missing");JSONObject manifest;try(InputStream in=zip.getInputStream(manifestEntry)){manifest=json(in);}
            if(!RESULT_SCHEMA.equals(manifest.getString("schema"))||!request.getString("requestId").equals(manifest.getString("requestId"))||!request.getString("projectId").equals(manifest.getString("projectId"))||request.getLong("sourceProjectUpdatedAt")!=manifest.getLong("sourceProjectUpdatedAt")||!sha(requestSha256).equals(manifest.getString("requestSha256")))throw new IllegalArgumentException("Result does not bind the pending request");
            JSONArray files=manifest.getJSONArray("files");if(files.length()<1||files.length()>MAX_FILES-1||entries.size()!=files.length()+1)throw new IllegalArgumentException("Result file set differs");Set<String> declared=new HashSet<>();long expanded=manifestEntry.getSize();
            for(int i=0;i<files.length();i++){JSONObject record=files.getJSONObject(i);String name=path(record.getString("path"));if(name.equals("manifest.json")||!declared.add(name))throw new IllegalArgumentException("Duplicate result artifact");String lower=name.toLowerCase(Locale.US);if(!(lower.endsWith(".mp4")||lower.endsWith(".wav")||lower.endsWith(".png")||lower.endsWith(".jpg")||lower.endsWith(".jpeg")||lower.endsWith(".json")||lower.endsWith(".glb")||lower.endsWith(".vscinematic")))throw new IllegalArgumentException("Executable/unsupported result artifact");long bytes=integer(record,"bytes",1,MAX_EXPANDED);ZipEntry item=entries.get(name);expanded+=bytes;if(item==null||item.getSize()!=bytes||expanded>MAX_EXPANDED)throw new IllegalArgumentException("Result size differs");File file=target(stage,name);if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new IOException("Cannot stage artifact");try(InputStream in=zip.getInputStream(item);FileOutputStream out=new FileOutputStream(file)){if(copy(in,out,bytes)!=bytes)throw new IOException("Incomplete result artifact");out.getFD().sync();}if(!sha(record.getString("sha256")).equals(hash(file)))throw new IllegalArgumentException("Result artifact checksum differs");if(lower.endsWith(".json"))json(file);}
            writeAtomic(new File(stage,"manifest.json"),manifest.toString().getBytes(StandardCharsets.UTF_8));return manifest;
        }catch(Exception failure){deleteTree(stage);throw failure;}
    }
    static void deleteTree(File file){if(file==null)return;if(file.isDirectory()&&!java.nio.file.Files.isSymbolicLink(file.toPath())){File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);}file.delete();}
}
