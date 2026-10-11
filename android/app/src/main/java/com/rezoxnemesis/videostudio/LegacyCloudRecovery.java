package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One-time, source-preserving migration from the old VideoStudio app's
 * explicitly selected cloud folder. Never opens another app's data directory,
 * enumerates MediaStore/Gallery, or deletes the installed legacy application.
 *
 * Only fully resolvable projects are committed to the fresh app's SQLite DB.
 * Incomplete archives stay untouched in Drive, with their missing inputs
 * reported so the owner can supply those media deliberately.
 */
public final class LegacyCloudRecovery {
    private static final String PREFS="videostudio_native_v1";
    private static final String TREE_KEY="drive_workspace_tree_uri";
    private static final int MAX_FILES=2400;
    private static final int MAX_PROJECTS=100;
    private static final long MAX_MANIFEST=4L*1024*1024;
    private static final long MAX_ARCHIVE_BYTES=3L*1024*1024*1024;
    private static final int BUFFER=128*1024;

    public interface Progress { void progress(int percent,String detail) throws Exception; }

    private final Context context;
    private final ContentResolver resolver;
    private final ProjectStore store;
    private final Uri tree;

    private static final class Entry {
        final Uri uri;
        final String name,relative;
        final long size;
        Entry(Uri uri,String name,String relative,long size){
            this.uri=uri;this.name=name;this.relative=relative;this.size=size;
        }
    }
    private static final class Archive {
        final String id;
        final Uri folder;
        final JSONObject manifest;
        Archive(String id,Uri folder,JSONObject manifest){
            this.id=id;this.folder=folder;this.manifest=manifest;
        }
    }

    public LegacyCloudRecovery(Context context,ProjectStore store) throws Exception {
        this.context=context.getApplicationContext();
        this.resolver=this.context.getContentResolver();
        this.store=store;
        SharedPreferences pref=this.context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String raw=pref.getString(TREE_KEY,"");
        if(raw==null||raw.trim().isEmpty())
            throw new IllegalStateException("Select the existing VideoStudio cloud folder in Control first.");
        this.tree=Uri.parse(raw);
        boolean allowed=false;
        for(android.content.UriPermission grant:resolver.getPersistedUriPermissions()) {
            if(tree.equals(grant.getUri())&&grant.isReadPermission()) allowed=true;
        }
        if(!allowed)
            throw new IllegalStateException("The linked cloud folder no longer grants read access.");
        if(!DocumentsContract.isTreeUri(tree))
            throw new IllegalStateException("VideoStudio requires a user-selected document-tree folder.");
    }

    private Uri root() {
        return DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
    }
    private static String safeName(String value) {
        return value==null?"":value.replaceAll("[\\\\/:*?\\\"<>|\\p{Cntrl}]+","_");
    }

    private ArrayList<Entry> children(Uri folder,String prefix) throws Exception {
        String parentId=DocumentsContract.getDocumentId(folder);
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId);
        String[] columns={
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        ArrayList<Entry> result=new ArrayList<>();
        try(Cursor cursor=resolver.query(children,columns,null,null,null)) {
            if(cursor==null)return result;
            while(cursor.moveToNext()) {
                if(result.size()>=MAX_FILES)
                    throw new IllegalStateException("Archive folder contains too many items");
                String id=cursor.getString(0);
                String name=cursor.getString(1);
                String mime=cursor.getString(2);
                long size=cursor.isNull(3)?0:Math.max(0,cursor.getLong(3));
                if(name==null||name.contains("/")||name.contains("\\")||name.contains(".."))
                    continue;
                Uri item=DocumentsContract.buildDocumentUriUsingTree(tree,id);
                String path=prefix.isEmpty()?name:prefix+"/"+name;
                result.add(new Entry(item, mime, path,size));
            }
        }
        return result;
    }

    private boolean directory(Entry entry) {
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(entry.name);
    }

    private Entry child(Uri parent,String name,boolean wantFolder) throws Exception {
        for(Entry entry:children(parent,"")) {
            if(name.equals(entry.relative) && directory(entry)==wantFolder) return entry;
        }
        return null;
    }

    private byte[] readSmall(Uri uri,long limit) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=resolver.openInputStream(uri)){
            if(in==null)throw new IllegalArgumentException("Cloud manifest was unavailable");
            byte[] buffer=new byte[8192];
            int n;
            while((n=in.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(out.size()+n>limit)throw new IllegalArgumentException("Unreasonably large cloud manifest");
                out.write(buffer,0,n);
            }
        }
        return out.toByteArray();
    }

    private LinkedHashMap<String,Archive> archives() throws Exception {
        LinkedHashMap<String,Archive> found=new LinkedHashMap<>();
        Entry rootProjects=child(root(),"Projects",true);
        if(rootProjects==null)throw new IllegalStateException(
                "No Projects archive in this folder. Select your old VideoStudio cloud workspace.");
        for(Entry item:children(rootProjects.uri,"")){
            String name=item.relative;
            if(!directory(item)||!LegacyMigrationRules.validId(name))continue;
            Entry manifestFile=child(item.uri,"project.json",false);
            if(manifestFile==null)continue;
            JSONObject manifest;
            try {
                manifest=new JSONObject(new String(readSmall(manifestFile.uri,MAX_MANIFEST),
                        java.nio.charset.StandardCharsets.UTF_8));
            } catch(Exception invalid) {continue;}
            if(!name.equals(manifest.optString("id","")))continue;
            if(manifest.optJSONArray("assets")==null||manifest.optJSONArray("clips")==null)continue;
            Archive previous=found.get(name);
            if(previous==null||manifest.optLong("updatedAt",0)>
                    previous.manifest.optLong("updatedAt",0)) {
                found.put(name,new Archive(name,item.uri,manifest));
            }
            if(found.size()>MAX_PROJECTS)throw new IllegalStateException(
                    "More than 100 old projects; use a smaller selected archive folder.");
        }
        return found;
    }

    private void collect(Uri folder,String prefix,Map<String,Entry> into,int depth) throws Exception {
        if(depth>16)throw new IllegalArgumentException("Cloud project nesting too deep");
        for(Entry entry:children(folder,prefix)) {
            if(into.size()>MAX_FILES)
                throw new IllegalStateException("Cloud project exceeds 2400 files");
            if(directory(entry))collect(entry.uri,entry.relative,into,depth+1);
            else into.put(entry.relative,entry);
        }
    }

    private Map<String,Entry> stagedSources() throws Exception {
        Map<String,Entry> map=new LinkedHashMap<>();
        // Only these user-selected VideoStudio media staging directories are
        // examined. This cannot browse the device Gallery or unrelated Drive.
        for(Entry folder:children(root(),"")) {
            if(!directory(folder))continue;
            String n=folder.relative.toLowerCase(Locale.US);
            if(!(n.endsWith("_sourceframes")||n.equals("sourceframes")
                    ||n.equals("originals")))continue;
            for(Entry item:children(folder.uri,"")){
                if(directory(item))continue;
                String stem=LegacyMigrationRules.sourceStem(item.relative);
                if(!stem.isEmpty()&&!map.containsKey(stem))map.put(stem,item);
            }
        }
        return map;
    }

    private static File childFile(File root,String relative) throws Exception {
        if(relative==null||relative.isEmpty()||relative.startsWith("/")
                ||relative.contains("..")||relative.contains("\\")) {
            throw new IllegalArgumentException("Unsafe archive output name");
        }
        File target=new File(root,relative.replace('/',File.separatorChar));
        if(!target.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))
            throw new IllegalArgumentException("Archive path escapes the private project");
        return target;
    }

    private long copyCloud(Entry from,File to) throws Exception {
        if(!to.getParentFile().isDirectory()&&!to.getParentFile().mkdirs())
            throw new IllegalStateException("Cannot prepare private migration directory");
        File partial=new File(to.getParentFile(),to.getName()+".partial");
        long written=0;
        try(InputStream in=resolver.openInputStream(from.uri);
            OutputStream out=new FileOutputStream(partial)) {
            if(in==null)throw new IllegalStateException("Archived media is unavailable");
            byte[] b=new byte[BUFFER];int count;
            while((count=in.read(b))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                written+=count;
                if(written>MAX_ARCHIVE_BYTES)
                    throw new IllegalArgumentException("Single archived media file too large");
                out.write(b,0,count);
            }
            out.flush();
        } catch(Exception e) {
            partial.delete();throw e;
        }
        if(written<=0 || (from.size>0 && written!=from.size)) {
            partial.delete();
            throw new IllegalStateException("Archived source size mismatch");
        }
        if(!partial.renameTo(to)) {
            partial.delete();
            throw new IllegalStateException("Cannot finalize recovered media");
        }
        return written;
    }

    public JSONObject restoreAll(Progress observer) throws Exception {
        if(observer==null)observer=(pct,detail)->{};
        LinkedHashMap<String,Archive> all=archives();
        Map<String,Entry> staged=stagedSources();
        JSONArray reports=new JSONArray();
        int imported=0,skipped=0,already=0,ordinal=0;
        observer.progress(1,"Found "+all.size()+" source-owned archive(s)");
        for(Archive archive:all.values()) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            ordinal++;
            if(store.get(archive.id)!=null){
                already++;continue;
            }
            JSONObject report=new JSONObject()
                    .put("id",archive.id)
                    .put("name",archive.manifest.optString("name","Archived project"));
            try {
                String name=archive.manifest.optString("name","Archived project");
                ProjectStore.Project importedProject=ProjectStore.Project.fromJson(archive.manifest);
                if(importedProject.assets.size()>MAX_FILES)
                    throw new IllegalStateException("Project contains too many assets");
                Map<String,Entry> entries=new LinkedHashMap<>();
                collect(archive.folder,"",entries,0);
                Map<String,Entry> resolution=new LinkedHashMap<>();
                Map<String,String> relative=new LinkedHashMap<>();
                JSONArray missing=new JSONArray();
                for(ProjectStore.Asset a:importedProject.assets){
                    if(!LegacyMigrationRules.validId(a.id)||
                            !LegacyMigrationRules.isKnownMediaName(a.name)) {
                        missing.put("Invalid source identity "+a.id);continue;
                    }
                    String rel=LegacyMigrationRules.archivedRelativePath(a.uri,archive.id);
                    Entry media=rel.isEmpty()?null:entries.get(rel);
                    if(media==null && "final_render".equals(a.role)) {
                        rel="Exports/"+a.name;
                        media=entries.get(rel);
                    }
                    if(media==null && LegacyMigrationRules.isOldImportsFile(a.uri)) {
                        // Newer VideoStudio cloud archives store every
                        // explicitly imported source under Originals/asset-id.
                        Entry archivedOriginal=entries.get("Originals/"+a.id+"_"+a.name);
                        rel="source_assets/"+a.id;
                        media=archivedOriginal!=null?archivedOriginal
                                :staged.get(LegacyMigrationRules.sourceStem(a.name));
                    }
                    if(media==null) {
                        missing.put(a.name);continue;
                    }
                    resolution.put(a.id,media);
                    relative.put(a.id,rel);
                }
                if(missing.length()>0){
                    skipped++;
                    report.put("status","missing_source_media");
                    report.put("missing",missing);
                    reports.put(report);
                    continue;
                }
                File projectsDir=new File(context.getFilesDir(),"creative_workspace/projects");
                if(!projectsDir.isDirectory()&&!projectsDir.mkdirs())
                    throw new IllegalStateException("Cannot prepare private recovery workspace");
                File output=new File(projectsDir,archive.id);
                if(output.exists())throw new IllegalStateException(
                        "Recovery destination already exists; no overwriting is permitted");
                File staging=new File(projectsDir,".import_"+archive.id+"_"+System.currentTimeMillis());
                if(!staging.mkdirs())throw new IllegalStateException("Cannot stage project recovery");
                long estimated=0;
                for(Entry entry:entries.values())estimated+=entry.size;
                for(Entry entry:resolution.values())estimated+=entry.size;
                if(estimated>MAX_ARCHIVE_BYTES)throw new IllegalStateException(
                        "Archive exceeds the safe migration size limit");
                StatFs storage=new StatFs(projectsDir.getAbsolutePath());
                if(storage.getAvailableBytes()<estimated+64L*1024*1024)
                    throw new IllegalStateException("Not enough free space to migrate project");
                long written=0;
                for(Entry entry:entries.values()){
                    if("project.json".equals(entry.relative))continue;
                    written+=copyCloud(entry,childFile(staging,entry.relative));
                }
                for(ProjectStore.Asset asset:importedProject.assets){
                    String rel=relative.get(asset.id);
                    Entry original=resolution.get(asset.id);
                    if(rel.startsWith("source_assets/"))
                        written+=copyCloud(original,childFile(staging,rel));
                    File restored=childFile(staging,rel);
                    if(!restored.isFile()||restored.length()<1)
                        throw new IllegalStateException("Recovered source is incomplete: "+asset.name);
                    asset.uri=Uri.fromFile(childFile(output,rel)).toString();
                    asset.sizeBytes=restored.length();
                    asset.persistedReadAccess=true;
                    asset.seekable=true;
                }
                for(ProjectStore.Clip clip:importedProject.clips){
                    if(importedProject.asset(clip.assetId)==null)
                        throw new IllegalStateException("Archive clip references a missing asset");
                }
                // An old MediaStore URI belongs to the old package. The
                // archived final_render asset now points to our private file.
                importedProject.latestExportUri="";
                importedProject.latestExportAt=0;
                if(!staging.renameTo(output))
                    throw new IllegalStateException("Could not commit the complete project");
                // Commit the database only AFTER all media files are verified.
                store.save(importedProject);
                imported++;
                report.put("status","imported")
                    .put("clips",importedProject.clips.size())
                    .put("assets",importedProject.assets.size())
                    .put("bytes",written)
                    .put("legacyAppUntouched",true);
                reports.put(report);
            } catch(Exception e){
                skipped++;
                report.put("status","skipped");
                report.put("reason",e.getMessage()==null?"Recovery failed":e.getMessage());
                reports.put(report);
            }
            observer.progress(2+(int)(95d*ordinal/Math.max(1,all.size())),
                    "Checked "+ordinal+"/"+all.size()+" old projects");
        }
        JSONObject result=new JSONObject()
                .put("ok",true).put("found",all.size())
                .put("imported",imported).put("alreadyPresent",already)
                .put("skipped",skipped).put("projects",reports)
                .put("originalLegacyAppUnchanged",true)
                .put("galleryAccess",false);
        observer.progress(100,"Migration checked; "+imported+" restored, "+skipped+" need attention");
        return result;
    }
}
