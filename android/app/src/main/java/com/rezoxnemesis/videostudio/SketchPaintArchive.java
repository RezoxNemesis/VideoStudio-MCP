package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.*;

/** Self-contained editable native sketch generations, never a database-file backup. */
public final class SketchPaintArchive {
    public static final String SCHEMA="videostudio.sketch.archive.v1";
    public static final long MAX_ARCHIVE_BYTES=256L*1024*1024, MAX_EXPANDED_BYTES=672L*1024*1024;
    public static final int MAX_ENTRIES=32768;
    private static final int MAX_INVENTORY=16*1024*1024;
    private static final long RESERVE=64L*1024*1024;
    public static final class Prepared {
        public final File file; public final JSONObject metadata;
        Prepared(File file,JSONObject metadata){this.file=file;this.metadata=metadata;}
    }
    private SketchPaintArchive(){}
    public static Prepared prepare(Context context,JSONObject args)throws Exception {
        SketchDocument.fields(args,"sketchId,expectedRevision,operationId");String id=SketchDocument.id(args,"sketchId"),op=SketchDocument.id(args,"operationId");long revision=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1);
        String fingerprint=hashJson(args);File generation=owned(context,"sketch_studio/"+id+"/archives/"+op),output=new File(generation,"sketch-"+op+".vssketch");
        synchronized(SketchPaintArchive.class){
            mkdir(generation);File intent=new File(generation,"request.json");immutableJson(intent,new JSONObject().put("requestSha256",fingerprint));File receipt=new File(generation,"completed.json");
            if(receipt.isFile()){JSONObject metadata=json(receipt,MAX_INVENTORY);if(!fingerprint.equals(metadata.getString("requestSha256"))||!output.isFile()||output.length()!=metadata.getLong("archiveBytes")||!metadata.getString("archiveSha256").equals(SketchProjectStore.fileHash(output)))throw new IllegalStateException("SKETCH_ARCHIVE_CHANGED");return new Prepared(output,metadata);}
            File documentFile=new File(generation,"document.json");JSONObject document;
            if(documentFile.isFile())document=json(documentFile,SketchDocument.MAX_BYTES);
            else {try(SketchProjectStore store=new SketchProjectStore(context)){document=store.get(id);SketchProjectStore.requireRevision(document,revision);store.verifyReferences(document);}immutableJson(documentFile,document);}
            SketchDocument.validate(document);if(!id.equals(document.getString("id"))||revision!=document.getLong("revision"))throw new IllegalStateException("SKETCH_ARCHIVE_SNAPSHOT_CHANGED");
            File assets=new File(generation,"assets"),assetsInventory=new File(generation,"assets.json");JSONObject frozen;
            if(assetsInventory.isFile()){frozen=json(assetsInventory,MAX_INVENTORY);verifyFrozen(context,document,assets,frozen);}
            else {frozen=freezeDependencies(context,document,assets);immutableJson(assetsInventory,frozen);}
            JSONArray files=new JSONArray().put(record("document.json",documentFile));JSONArray deps=frozen.getJSONArray("files");for(int i=0;i<deps.length();i++){JSONObject item=SketchDocument.copy(deps.getJSONObject(i));item.put("path","assets/"+item.getString("path"));files.put(item);}
            if(files.length()+1>MAX_ENTRIES)throw budget("Too many editable archive entries");
            JSONObject inventory=new JSONObject().put("schema",SCHEMA).put("sourceSketchId",id).put("sourceRevision",revision).put("documentSha256",hashJson(document)).put("files",files);byte[] manifest=canonical(inventory);if(manifest.length>MAX_INVENTORY)throw budget("Editable inventory exceeds 16 MiB");
            File partial=new File(generation,"archive.partial");long expanded=manifest.length;
            try {
                if(context.getFilesDir().getUsableSpace()<RESERVE+Math.min(MAX_ARCHIVE_BYTES,documentFile.length()+frozen.getLong("bytes")))throw budget("Insufficient archive storage reserve");
                try(FileOutputStream stream=new FileOutputStream(partial);ZipOutputStream zip=new ZipOutputStream(new LimitedOutput(stream,MAX_ARCHIVE_BYTES))){entry(zip,"archive.json",manifest);for(int i=0;i<files.length();i++){JSONObject row=files.getJSONObject(i);String path=row.getString("path");File source=inside(generation,path);expanded=Math.addExact(expanded,row.getLong("bytes"));if(expanded>MAX_EXPANDED_BYTES)throw budget("Editable archive expands beyond 672 MiB");checkFile(source,row);ZipEntry entry=new ZipEntry(path);entry.setTime(0);zip.putNextEntry(entry);try(InputStream in=new FileInputStream(source)){if(copy(in,zip,row.getLong("bytes"))!=row.getLong("bytes"))throw new IOException("Editable archive source changed");}zip.closeEntry();checkFile(source,row);}zip.finish();stream.getFD().sync();}
                if(output.exists()){if(!output.isFile()||output.length()!=partial.length()||!SketchProjectStore.fileHash(output).equals(SketchProjectStore.fileHash(partial)))throw new IllegalStateException("SKETCH_ARCHIVE_RECOVERY: uncommitted immutable archive changed");}
                else Files.move(partial.toPath(),output.toPath(),StandardCopyOption.ATOMIC_MOVE);
            }finally{partial.delete();}
            JSONObject metadata=new JSONObject().put("schema",SCHEMA).put("sourceSketchId",id).put("sourceRevision",revision).put("documentSha256",inventory.getString("documentSha256")).put("inventorySha256",SketchDocument.sha256(manifest)).put("requestSha256",fingerprint).put("archiveName",output.getName()).put("archiveProjectId",id).put("archiveSha256",SketchProjectStore.fileHash(output)).put("archiveBytes",output.length()).put("dependencyCount",deps.length()).put("editable",true).put("sourceUnchanged",true).put("galleryAccess",false);
            immutableJson(receipt,metadata);return new Prepared(output,metadata);
        }
    }
    public static JSONObject archive(Context context,JSONObject args)throws Exception {
        Prepared prepared=prepare(context,args);JSONObject remote=new DriveWorkspaceProvider(context).archiveArtifact(args.getString("sketchId"),"Results",prepared.file,prepared.metadata.getString("archiveSha256"),null);
        return SketchDocument.copy(prepared.metadata).put("ok",true).put("remoteVerified",remote.getBoolean("verified")).put("workspace",remote);
    }
    public static JSONObject restore(Context context,JSONObject args)throws Exception {
        SketchDocument.fields(args,"archiveProjectId,archiveName,expectedSha256,operationId,name");String project=SketchDocument.id(args,"archiveProjectId"),op=SketchDocument.id(args,"operationId"),name=args.getString("archiveName");
        if(!name.matches("[A-Za-z0-9_.-]{1,120}")||name.contains("..")||!name.endsWith(".vssketch"))throw SketchDocument.bad("Exact linked editable archive filename required");String expected=sha(args.getString("expectedSha256"));if(args.has("name"))SketchDocument.text(args,"name",160);String title=args.has("name")?args.getString("name"):null;
        synchronized(SketchPaintArchive.class){File receipts=owned(context,"sketch_archive_receipts");mkdir(receipts);immutableJson(new File(receipts,op+".remote.json"),new JSONObject().put("requestSha256",hashJson(args)));JSONObject completed=completedRestore(context,expected,op,title);if(completed!=null)return completed.put("archiveProjectId",project).put("archiveName",name).put("receiptReused",true);}
        File stage=owned(context,"sketch_archive_staging/transfer_"+UUID.randomUUID());mkdir(stage);File source=new File(stage,"input.vssketch");
        try {new DriveWorkspaceProvider(context).restoreArtifact(project,"Results",name,source,expected,null);return restoreFile(context,source,expected,op,title).put("archiveProjectId",project).put("archiveName",name).put("remoteVerified",true);}finally{OpenPipelineArtifacts.deleteTree(stage);}
    }
    static JSONObject restoreFile(Context context,File file,String expectedSha256,String operationId,String name)throws Exception {
        String expected=sha(expectedSha256),op=SketchDocument.id(new JSONObject().put("id",operationId),"id");if(name!=null)SketchDocument.text(new JSONObject().put("name",name),"name",160);
        synchronized(SketchPaintArchive.class){JSONObject completed=completedRestore(context,expected,op,name);if(completed!=null)return completed;}
        if(file==null||!file.isFile()||Files.isSymbolicLink(file.toPath())||file.length()<1||file.length()>MAX_ARCHIVE_BYTES||!expected.equals(SketchProjectStore.fileHash(file)))throw SketchDocument.bad("Editable archive checksum or size mismatch");
        String targetId=restoreId(op);String fingerprint=restoreFingerprint(expected,op,name);
        synchronized(SketchPaintArchive.class){
            JSONObject completed=completedRestore(context,expected,op,name);if(completed!=null)return completed;
            File receipts=owned(context,"sketch_archive_receipts"),intent=new File(receipts,op+".json");mkdir(receipts);immutableJson(intent,new JSONObject().put("requestSha256",fingerprint).put("sketchId",targetId));
            File stage=owned(context,"sketch_archive_staging/"+op+"_"+UUID.randomUUID());mkdir(stage);
            try {
                verifyZipAttributes(file);JSONObject inventory;Map<String,ZipEntry> entries=new LinkedHashMap<>();
                try(ZipFile zip=new ZipFile(file)){
                    long total=0;Enumeration<? extends ZipEntry> all=zip.entries();while(all.hasMoreElements()){ZipEntry e=all.nextElement();String path=archivePath(e.getName());if(e.isDirectory()||entries.put(path,e)!=null||entries.size()>MAX_ENTRIES||e.getSize()<1||e.getSize()>entryLimit(path)||e.getSize()>MAX_EXPANDED_BYTES-total)throw SketchDocument.bad("Editable archive entry budget or duplicate");total+=e.getSize();}
                    ZipEntry manifest=entries.get("archive.json");if(manifest==null)throw SketchDocument.bad("Editable archive inventory missing");try(InputStream in=zip.getInputStream(manifest)){inventory=json(in,MAX_INVENTORY);}
                    SketchDocument.fields(inventory,"schema,sourceSketchId,sourceRevision,documentSha256,files");if(!SCHEMA.equals(inventory.getString("schema")))throw SketchDocument.bad("Unknown editable archive schema");SketchDocument.id(inventory,"sourceSketchId");SketchDocument.integer(inventory,"sourceRevision",1,Long.MAX_VALUE-1);sha(inventory.getString("documentSha256"));JSONArray files=inventory.getJSONArray("files");if(files.length()<1||files.length()+1!=entries.size()||files.length()+1>MAX_ENTRIES)throw SketchDocument.bad("Editable archive entry set differs");if(stage.getUsableSpace()<total+RESERVE)throw budget("Insufficient restore storage reserve");Set<String> declared=new HashSet<>();
                    for(int i=0;i<files.length();i++){JSONObject row=files.getJSONObject(i);SketchDocument.fields(row,"path,sha256,bytes");String path=archivePath(row.getString("path"));if("archive.json".equals(path)||!declared.add(path))throw SketchDocument.bad("Editable archive duplicate inventory");sha(row.getString("sha256"));long bytes=SketchDocument.integer(row,"bytes",1,entryLimit(path));ZipEntry e=entries.get(path);if(e==null||e.getSize()!=bytes)throw SketchDocument.bad("Editable archive inventory size differs");File out=inside(stage,path);mkdir(out.getParentFile());try(InputStream in=zip.getInputStream(e);FileOutputStream stream=new FileOutputStream(out)){if(copy(in,stream,bytes)!=bytes)throw new IOException("Incomplete editable archive entry");stream.getFD().sync();}checkFile(out,row);}
                    if(!declared.contains("document.json"))throw SketchDocument.bad("Editable archive document missing");
                }
                JSONObject document=json(new File(stage,"document.json"),SketchDocument.MAX_BYTES);SketchDocument.validate(document);if(!inventory.getString("sourceSketchId").equals(document.getString("id"))||inventory.getLong("sourceRevision")!=document.getLong("revision")||!inventory.getString("documentSha256").equals(hashJson(document)))throw SketchDocument.bad("Editable archive source document binding differs");
                JSONObject dependencies=inventoryFromArchive(inventory);File assets=new File(stage,"assets");mkdir(assets);verifyFrozen(context,document,assets,dependencies);
                verifyRestoredPixels(assets,dependencies);
                File target=owned(context,"sketch_studio/"+targetId);mkdir(target);JSONArray rows=dependencies.getJSONArray("files");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);immutableCopy(inside(assets,row.getString("path")),inside(target,row.getString("path")),row);}
                SketchPaintStore.invalidateQuota(context,targetId);
                document.put("id",targetId).put("revision",1);if(name!=null)document.put("name",name);
                JSONObject metadata=restoreMetadata(inventory,expected,rows.length());String storeFingerprint=SketchDocument.sha256(("sketch_owned_import\n"+SketchDocument.canonical(document)).getBytes(StandardCharsets.UTF_8));
                // Publish the reconstruction proof before the atomic DB commit. If the
                // process stops after commit, its exact DB receipt remains authoritative.
                immutableJson(new File(receipts,op+".prepared.json"),new JSONObject().put("requestSha256",fingerprint).put("storeFingerprint",storeFingerprint).put("documentSha256",hashJson(document)).put("resultMetadata",metadata));
                JSONObject result;try(SketchProjectStore store=new SketchProjectStore(context)){result=store.importOwnedDocument(document,op);}
                mergeMetadata(result,metadata);
                immutableJson(new File(receipts,op+".completed.json"),new JSONObject().put("requestSha256",fingerprint).put("result",result));return result;
            }finally{OpenPipelineArtifacts.deleteTree(stage);}
        }
    }
    private static String restoreId(String operationId){return UUID.nameUUIDFromBytes(("videostudio.sketch.restore/"+operationId).getBytes(StandardCharsets.UTF_8)).toString();}
    private static String restoreFingerprint(String expected,String operationId,String name)throws Exception {return hashJson(new JSONObject().put("sha256",expected).put("operationId",operationId).put("name",name==null?JSONObject.NULL:name));}
    /** Durable result is independent of temporary/cloud source availability after commit. */
    private static JSONObject completedRestore(Context context,String expected,String op,String name)throws Exception {
        File receipts=owned(context,"sketch_archive_receipts"),intent=new File(receipts,op+".json");if(!intent.exists())return null;JSONObject admitted=json(intent,4096);SketchDocument.fields(admitted,"requestSha256,sketchId");String fingerprint=restoreFingerprint(expected,op,name),id=restoreId(op);if(!fingerprint.equals(admitted.getString("requestSha256"))||!id.equals(admitted.getString("sketchId")))throw new IllegalStateException("SKETCH_OPERATION_ID_CONFLICT: restore request differs");
        File completed=new File(receipts,op+".completed.json");
        if(!completed.exists()){
            File preparedFile=new File(receipts,op+".prepared.json");if(!preparedFile.exists())return null;JSONObject prepared=json(preparedFile,1024*1024);SketchDocument.fields(prepared,"requestSha256,storeFingerprint,documentSha256,resultMetadata");if(!fingerprint.equals(prepared.getString("requestSha256")))throw new IllegalStateException("SKETCH_ARCHIVE_RECEIPT_CHANGED");String storeFingerprint=sha(prepared.getString("storeFingerprint")),documentHash=sha(prepared.getString("documentSha256"));JSONObject metadata=prepared.getJSONObject("resultMetadata");validateRestoreMetadata(metadata,expected);
            JSONObject actual;try(SketchProjectStore store=new SketchProjectStore(context)){actual=store.operationReceipt(id,op,storeFingerprint);}if(actual==null)return null;
            if(!id.equals(actual.getString("sketchId"))||!documentHash.equals(actual.getString("documentSha256"))||actual.getLong("revision")!=1||!actual.getBoolean("persisted"))throw new IllegalStateException("SKETCH_ARCHIVE_RECEIPT_CHANGED");mergeMetadata(actual,metadata);immutableJson(completed,new JSONObject().put("requestSha256",fingerprint).put("result",actual));
        }
        JSONObject receipt=json(completed,1024*1024);SketchDocument.fields(receipt,"requestSha256,result");JSONObject result=receipt.getJSONObject("result");if(!fingerprint.equals(receipt.getString("requestSha256"))||!expected.equals(result.getString("archiveSha256"))||!id.equals(result.getString("sketchId"))||!result.getBoolean("persisted"))throw new IllegalStateException("SKETCH_ARCHIVE_RECEIPT_CHANGED");
        try(SketchProjectStore store=new SketchProjectStore(context)){if(store.revision(id)<1||!store.hasOperation(id,op))throw new IllegalStateException("SKETCH_ARCHIVE_RESTORE_RECEIPT_UNAVAILABLE");}return SketchDocument.copy(result);
    }
    private static JSONObject restoreMetadata(JSONObject inventory,String expected,int dependencies)throws Exception {return new JSONObject().put("ok",true).put("archiveSha256",expected).put("sourceSketchId",inventory.getString("sourceSketchId")).put("sourceRevision",inventory.getLong("sourceRevision")).put("sourceDocumentSha256",inventory.getString("documentSha256")).put("dependencyCount",dependencies).put("restoredAsNewSketch",true).put("sourceUnchanged",true).put("galleryAccess",false);}
    private static void validateRestoreMetadata(JSONObject metadata,String expected)throws Exception {SketchDocument.fields(metadata,"ok,archiveSha256,sourceSketchId,sourceRevision,sourceDocumentSha256,dependencyCount,restoredAsNewSketch,sourceUnchanged,galleryAccess");if(!expected.equals(sha(metadata.getString("archiveSha256"))))throw new IllegalStateException("SKETCH_ARCHIVE_RECEIPT_CHANGED");SketchDocument.id(metadata,"sourceSketchId");SketchDocument.integer(metadata,"sourceRevision",1,Long.MAX_VALUE-1);sha(metadata.getString("sourceDocumentSha256"));SketchDocument.integer(metadata,"dependencyCount",0,MAX_ENTRIES-2);for(String field:new String[]{"ok","restoredAsNewSketch","sourceUnchanged"}){SketchDocument.bool(metadata,field);if(!metadata.getBoolean(field))throw new IllegalStateException("SKETCH_ARCHIVE_RECEIPT_CHANGED");}SketchDocument.bool(metadata,"galleryAccess");if(metadata.getBoolean("galleryAccess"))throw new IllegalStateException("SKETCH_ARCHIVE_RECEIPT_CHANGED");}
    private static void mergeMetadata(JSONObject result,JSONObject metadata)throws Exception {Iterator<String> keys=metadata.keys();while(keys.hasNext()){String key=keys.next();result.put(key,metadata.get(key));}}
    static JSONObject freezeDependencies(Context context,JSONObject document,File ownedRoot)throws Exception {
        requirePrivate(context,ownedRoot);mkdir(ownedRoot);SketchDocument.validate(document);LinkedHashMap<String,File> dependencies=dependencies(context,document,null);JSONArray files=new JSONArray();long total=0;
        for(Map.Entry<String,File> e:dependencies.entrySet()){JSONObject row=record(e.getKey(),e.getValue());total=Math.addExact(total,row.getLong("bytes"));if(total>MAX_EXPANDED_BYTES-SketchDocument.MAX_BYTES-MAX_INVENTORY||files.length()+2>MAX_ENTRIES)throw budget("Editable dependencies exceed archive bounds");immutableCopy(e.getValue(),inside(ownedRoot,e.getKey()),row);files.put(row);}
        JSONObject inventory=new JSONObject().put("files",files).put("bytes",total);verifyFrozen(context,document,ownedRoot,inventory);return inventory;
    }
    static void verifyFrozen(Context context,JSONObject document,File root,JSONObject inventory)throws Exception {
        requirePrivate(context,root);SketchDocument.fields(inventory,"files,bytes");JSONArray rows=inventory.getJSONArray("files");if(rows.length()>MAX_ENTRIES-2)throw budget("Frozen dependency count exceeded");TreeMap<String,JSONObject> expected=new TreeMap<>();long total=0;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);SketchDocument.fields(row,"path,sha256,bytes");String path=assetPath(row.getString("path"));if(expected.put(path,row)!=null)throw SketchDocument.bad("Frozen dependency duplicate");sha(row.getString("sha256"));total=Math.addExact(total,SketchDocument.integer(row,"bytes",1,entryLimit("assets/"+path)));checkFile(inside(root,path),row);}
        if(total!=SketchDocument.integer(inventory,"bytes",0,MAX_EXPANDED_BYTES))throw SketchDocument.bad("Frozen dependency byte count changed");LinkedHashMap<String,File> actual=dependencies(context,document,root);if(!actual.keySet().equals(expected.keySet()))throw SketchDocument.bad("Editable archive contains missing or unreachable dependencies");
    }
    private static LinkedHashMap<String,File> dependencies(Context context,JSONObject document,File frozenRoot)throws Exception {
        TreeMap<String,File> ordered=new TreeMap<>();String id=document.getString("id");SketchPaintStore paint=null;try(SketchProjectStore sketches=new SketchProjectStore(context)){
            JSONArray layers=document.getJSONArray("layers");for(int i=0;i<layers.length();i++){JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");for(int k=0;k<objects.length();k++){JSONObject object=objects.getJSONObject(k);String kind=object.getString("kind");if("image".equals(kind)){JSONObject ref=object.getJSONObject("reference");String path="references/"+ref.getString("file");File source=frozenRoot==null?sketches.referenceFile(id,ref):inside(frozenRoot,assetPath(path));if(!ref.getString("sha256").equals(SketchProjectStore.fileHash(source)))throw SketchDocument.bad("Frozen reference hash changed");verifyPng(source,ref.getInt("width"),ref.getInt("height"));ordered.put(path,source);}else if("paint".equals(kind)){if(paint==null)paint=frozenRoot==null?new SketchPaintStore(context,id):new SketchPaintStore(context,id,frozenRoot);for(Map.Entry<String,File> dep:paint.dependencies(object.getJSONObject("paint")).entrySet()){assetPath(dep.getKey());File old=ordered.put(dep.getKey(),dep.getValue());if(old!=null&&!old.getCanonicalFile().equals(dep.getValue().getCanonicalFile()))throw SketchDocument.bad("Paint dependency ownership differs");}}}}
        }finally{if(paint!=null)paint.close();}
        long refs=0,paintBytes=0;for(Map.Entry<String,File> e:ordered.entrySet()){if(e.getKey().startsWith("references/"))refs=Math.addExact(refs,e.getValue().length());else paintBytes=Math.addExact(paintBytes,e.getValue().length());}if(refs>128L*1024*1024||paintBytes>512L*1024*1024)throw budget("Owned reference or paint quota exceeded");return new LinkedHashMap<>(ordered);
    }
    private static JSONObject inventoryFromArchive(JSONObject inventory)throws Exception {JSONArray rows=new JSONArray();long bytes=0;JSONArray files=inventory.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject row=files.getJSONObject(i);if("document.json".equals(row.getString("path")))continue;JSONObject copy=SketchDocument.copy(row);String path=row.getString("path");if(!path.startsWith("assets/"))throw SketchDocument.bad("Editable archive asset path missing");copy.put("path",assetPath(path.substring(7)));rows.put(copy);bytes=Math.addExact(bytes,row.getLong("bytes"));}return new JSONObject().put("files",rows).put("bytes",bytes);}
    /** Restore trust gate only: fully decode one checked PNG at a time before any DB write. */
    private static void verifyRestoredPixels(File assets,JSONObject inventory)throws Exception {
        Set<String> decodedHashes=new HashSet<>();JSONArray files=inventory.getJSONArray("files");
        for(int i=0;i<files.length();i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Sketch archive pixel validation cancelled");
            JSONObject row=files.getJSONObject(i);String path=row.getString("path");if(!path.endsWith(".png")||!decodedHashes.add(row.getString("sha256")))continue;File file=inside(assets,path);checkFile(file,row);
            BitmapFactory.Options header=new BitmapFactory.Options();header.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),header);final int width=header.outWidth,height=header.outHeight;
            if(!"image/png".equals(header.outMimeType)||width<1||height<1||width>2048||height>2048||path.startsWith("paint/tiles/")&&(width!=256||height!=256))throw SketchDocument.bad("SKETCH_ARCHIVE_PNG_INVALID: bounded PNG dimensions required");
            Bitmap decoded=null;
            try{
                decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),(decoder,info,source)->{
                    if(info.getSize().getWidth()!=width||info.getSize().getHeight()!=height)throw SketchDocument.bad("SKETCH_ARCHIVE_PNG_INVALID: decoder dimensions differ");
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));decoder.setOnPartialImageListener(error->false);
                });
                if(decoded==null||decoded.getWidth()!=width||decoded.getHeight()!=height)throw SketchDocument.bad("SKETCH_ARCHIVE_PNG_INVALID: complete bounded pixels required");
            }finally{if(decoded!=null)decoded.recycle();}
        }
    }
    private static JSONObject record(String path,File file)throws Exception {if(!file.isFile()||Files.isSymbolicLink(file.toPath()))throw SketchDocument.bad("Owned dependency missing");long bytes=file.length();if(bytes<1||bytes>entryLimit(path.startsWith("paint/")||path.startsWith("references/")?"assets/"+path:path))throw budget("Owned dependency size rejected");return new JSONObject().put("path",path).put("sha256",SketchProjectStore.fileHash(file)).put("bytes",bytes);}
    private static void checkFile(File file,JSONObject row)throws Exception {if(!file.isFile()||Files.isSymbolicLink(file.toPath())||file.length()!=row.getLong("bytes")||!row.getString("sha256").equals(SketchProjectStore.fileHash(file)))throw SketchDocument.bad("Editable dependency checksum changed");}
    private static void immutableCopy(File source,File target,JSONObject row)throws Exception {checkFile(source,row);mkdir(target.getParentFile());if(target.exists()){checkFile(target,row);return;}if(target.getParentFile().getUsableSpace()<row.getLong("bytes")+RESERVE)throw budget("Insufficient dependency storage reserve");File temp=File.createTempFile(".freeze_",".part",target.getParentFile());try{try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(temp)){if(copy(in,out,row.getLong("bytes"))!=row.getLong("bytes"))throw new IOException("Dependency copy incomplete");out.getFD().sync();}checkFile(temp,row);checkFile(source,row);if(target.exists())throw SketchDocument.bad("Immutable target appeared during copy");Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);}finally{temp.delete();}}
    private static void verifyPng(File file,int width,int height)throws Exception {if(width<1||height<1||width>2048||height>2048||file.length()>8L*1024*1024)throw SketchDocument.bad("Reference PNG budget rejected");byte[] signature=new byte[8];try(InputStream in=new FileInputStream(file)){if(in.read(signature)!=8||!Arrays.equals(signature,new byte[]{(byte)137,80,78,71,13,10,26,10}))throw SketchDocument.bad("Reference must be a PNG");}BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);if(bounds.outWidth!=width||bounds.outHeight!=height)throw SketchDocument.bad("Reference PNG dimensions differ");Bitmap decoded=BitmapFactory.decodeFile(file.getPath());if(decoded==null)throw SketchDocument.bad("Reference PNG cannot decode");try{if(decoded.getWidth()!=width||decoded.getHeight()!=height)throw SketchDocument.bad("Reference PNG decoded dimensions differ");}finally{decoded.recycle();}}
    private static String archivePath(String path){if("document.json".equals(path)||"archive.json".equals(path))return path;if(path!=null&&path.startsWith("assets/")){assetPath(path.substring(7));return path;}throw SketchDocument.bad("Unsupported editable archive path");}
    private static String assetPath(String path){if(path==null||!path.matches("(?:references/[a-f0-9]{64}\\.png|paint/[a-f0-9]{64}\\.json|paint/(?:tiles|sources)/[a-f0-9]{64}\\.png|paint/strokes/[a-f0-9]{64}\\.json)"))throw SketchDocument.bad("Unsafe owned archive dependency path");return path;}
    private static long entryLimit(String path){if("archive.json".equals(path))return MAX_INVENTORY;if("document.json".equals(path))return SketchDocument.MAX_BYTES;if(path.startsWith("assets/paint/tiles/"))return 384L*1024;if(path.startsWith("assets/paint/strokes/"))return 512L*1024;if(path.startsWith("assets/paint/")&&path.endsWith(".json"))return 64L*1024;if(path.startsWith("assets/references/")||path.startsWith("assets/paint/sources/"))return 8L*1024*1024;throw SketchDocument.bad("Unknown archive dependency bound");}
    private static String sha(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw SketchDocument.bad("SHA-256 required");return value;}
    private static File owned(Context context,String relative)throws Exception {File file=new File(context.getFilesDir(),relative);requirePrivate(context,file);return file;}
    private static void requirePrivate(Context context,File file)throws Exception {File root=context.getFilesDir().getCanonicalFile();if(file==null||!file.getCanonicalPath().startsWith(root.getPath()+File.separator))throw SketchDocument.bad("Private sketch directory required");File cursor=file;while(cursor!=null&&cursor.getAbsolutePath().startsWith(context.getFilesDir().getAbsolutePath())){if(Files.isSymbolicLink(cursor.toPath()))throw SketchDocument.bad("Sketch directory symlink rejected");cursor=cursor.getParentFile();}}
    private static File inside(File root,String relative)throws Exception {OpenPipelineArtifacts.path(relative);File target=new File(root,relative);File cursor=target;while(cursor!=null&&cursor.getAbsolutePath().startsWith(root.getAbsolutePath())){if(Files.isSymbolicLink(cursor.toPath()))throw SketchDocument.bad("Sketch dependency symlink rejected");cursor=cursor.getParentFile();}if(!target.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw SketchDocument.bad("Sketch dependency escape rejected");return target;}
    private static void mkdir(File file)throws IOException {if(!file.isDirectory()&&!file.mkdirs())throw new IOException("Sketch archive storage unavailable");}
    private static byte[] canonical(JSONObject object)throws Exception {return SketchDocument.canonical(object).getBytes(StandardCharsets.UTF_8);}
    private static String hashJson(JSONObject object)throws Exception {return SketchDocument.sha256(canonical(object));}
    private static void immutableJson(File file,JSONObject value)throws Exception {byte[] bytes=canonical(value);if(bytes.length>MAX_INVENTORY)throw budget("Sketch archive metadata exceeds limit");if(file.exists()){if(!file.isFile()||Files.isSymbolicLink(file.toPath())||!SketchDocument.sha256(bytes).equals(SketchProjectStore.fileHash(file)))throw new IllegalStateException("SKETCH_OPERATION_ID_CONFLICT: immutable archive request or metadata differs");return;}mkdir(file.getParentFile());File temporary=File.createTempFile(".archive_",".json",file.getParentFile());try{try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}if(file.exists())throw new IllegalStateException("Immutable sketch metadata appeared");Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE);}finally{temporary.delete();}}
    private static JSONObject json(File file,int max)throws Exception {try(InputStream in=new FileInputStream(file)){return json(in,max);}}
    private static JSONObject json(InputStream in,int max)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(in,bytes,max);
        return parseOwnedJson(bytes.toByteArray());
    }
    /** Shared checked parser for passive archive, paint snapshot and stroke bytes. */
    static JSONObject parseOwnedJson(byte[] bytes)throws Exception {
        if(bytes==null||bytes.length<1||bytes.length>MAX_INVENTORY)throw SketchDocument.bad("Archive JSON byte budget exceeded");
        String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        // JSONTokener recurses; reject excessive structure before it can allocate or overflow.
        int depth=0,containers=0,separators=0;boolean quoted=false,escaped=false;
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}
            if(c=='"'){quoted=true;continue;}
            if(c=='{'||c=='['){if(++depth>24||++containers>262144)throw SketchDocument.bad("Archive JSON nesting or container budget exceeded");}
            else if(c=='}'||c==']'){if(--depth<0)throw SketchDocument.bad("Archive JSON nesting is malformed");}
            else if(c==','&&++separators>1048576)throw SketchDocument.bad("Archive JSON element budget exceeded");
        }
        if(depth!=0||quoted)throw SketchDocument.bad("Archive JSON nesting or quotation is malformed");
        JSONTokener parser=new JSONTokener(raw);Object parsed=parser.nextValue();if(!(parsed instanceof JSONObject)||parser.nextClean()!=0)throw SketchDocument.bad("One archive JSON object required");return (JSONObject)parsed;
    }
    private static long copy(InputStream in,OutputStream out,long max)throws Exception {byte[] buffer=new byte[32768];long count=0;int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Sketch archive cancelled");count=Math.addExact(count,n);if(count>max)throw budget("Sketch archive entry byte limit exceeded");out.write(buffer,0,n);}return count;}
    private static void entry(ZipOutputStream zip,String path,byte[] bytes)throws IOException {ZipEntry entry=new ZipEntry(path);entry.setTime(0);zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry();}
    private static IllegalArgumentException budget(String message){return SketchDocument.bad("SKETCH_ARCHIVE_BUDGET: "+message);}
    private static final class LimitedOutput extends FilterOutputStream {long count;final long max;LimitedOutput(OutputStream out,long max){super(out);this.max=max;}void reserve(int bytes)throws IOException {if(bytes<0||bytes>max-count)throw new IOException("SKETCH_ARCHIVE_BUDGET: compressed archive exceeds 256 MiB");count+=bytes;}@Override public void write(int value)throws IOException {reserve(1);out.write(value);}@Override public void write(byte[] bytes,int offset,int length)throws IOException {reserve(length);out.write(bytes,offset,length);}}
    /** Java ZipFile omits Unix entry modes. Check its central directory before extracting. */
    private static void verifyZipAttributes(File file)throws Exception {
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){long length=in.length();int tailSize=(int)Math.min(length,65557);byte[] tail=new byte[tailSize];in.seek(length-tailSize);in.readFully(tail);int end=-1;for(int i=tail.length-22;i>=0;i--)if(u32(tail,i)==0x06054b50L&&i+22+u16(tail,i+20)==tail.length){end=i;break;}if(end<0||u16(tail,end+4)!=0||u16(tail,end+6)!=0||u16(tail,end+8)!=u16(tail,end+10))throw SketchDocument.bad("Unsupported multipart editable ZIP");int count=u16(tail,end+10);long bytes=u32(tail,end+12),offset=u32(tail,end+16),endPosition=length-tailSize+end;if(count<2||count>MAX_ENTRIES||count==65535||offset+bytes!=endPosition)throw SketchDocument.bad("Unsupported editable ZIP directory");in.seek(offset);Set<Long> localOffsets=new HashSet<>();for(int i=0;i<count;i++){byte[] central=new byte[46];in.readFully(central);if(u32(central,0)!=0x02014b50L)throw SketchDocument.bad("Invalid editable ZIP directory");int flags=u16(central,8),method=u16(central,10),mode=(int)(u32(central,38)>>>16)&0170000;if((flags&1)!=0||(method!=0&&method!=8)||(mode!=0&&mode!=0100000)||u16(central,34)!=0)throw SketchDocument.bad("Editable ZIP symlink, encryption or directory rejected");long packed=u32(central,20),unpacked=u32(central,24),local=u32(central,42);int nameLength=u16(central,28),extra=u16(central,30),comment=u16(central,32);if(packed==0xffffffffL||unpacked==0xffffffffL||local==0xffffffffL||nameLength<1||nameLength>180||!localOffsets.add(local))throw SketchDocument.bad("Unsupported editable ZIP64 or duplicate entry");byte[] name=new byte[nameLength];in.readFully(name);archivePath(new String(name,StandardCharsets.UTF_8));long next=in.getFilePointer()+extra+comment;if(next>offset+bytes)throw SketchDocument.bad("Invalid editable ZIP metadata length");in.seek(local);byte[] header=new byte[30];in.readFully(header);if(u32(header,0)!=0x04034b50L||u16(header,6)!=flags||u16(header,8)!=method||u16(header,26)!=nameLength)throw SketchDocument.bad("Editable ZIP local directory differs");byte[] localName=new byte[nameLength];in.readFully(localName);if(!Arrays.equals(name,localName)||local+30L+nameLength+u16(header,28)+packed>offset)throw SketchDocument.bad("Editable ZIP entry range differs");in.seek(next);}if(in.getFilePointer()!=offset+bytes)throw SketchDocument.bad("Unexpected editable ZIP directory bytes");}
    }
    private static int u16(byte[] bytes,int offset){return (bytes[offset]&255)|((bytes[offset+1]&255)<<8);}
    private static long u32(byte[] bytes,int offset){return ((long)u16(bytes,offset))|((long)u16(bytes,offset+2)<<16);}
}
