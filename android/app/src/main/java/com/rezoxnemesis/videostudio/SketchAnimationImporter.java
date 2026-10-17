package com.rezoxnemesis.videostudio;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Direct native raster animation import. Original layers/strokes are not inferred. */
final class SketchAnimationImporter {
    interface Progress {void imported(int frames,int total)throws Exception;}
    private SketchAnimationImporter() {}
    static JSONObject importAnimation(Context context,ProjectStore projects,JSONObject args,Progress progress)throws Exception {
        synchronized(SketchAnimationImporter.class){return run(context,projects,args,progress);}
    }
    private static JSONObject run(Context c,ProjectStore projects,JSONObject args,Progress progress)throws Exception {
        SketchDocument.fields(args,"projectId,sourceProjectUpdatedAt,assetId,expectedSha256,bookId,operationId,name,fps,frameStart,frameCount,width,height");
        String projectId=SketchDocument.id(args,"projectId"),assetId=SketchDocument.id(args,"assetId"),bookId=SketchDocument.id(args,"bookId"),op=SketchDocument.id(args,"operationId");
        if(bookId.length()>72)throw SketchDocument.bad("Book ID is limited to 72 characters");
        long projectRevision=SketchDocument.integer(args,"sourceProjectUpdatedAt",0,Long.MAX_VALUE);String expected=args.getString("expectedSha256");if(!expected.matches("[a-f0-9]{64}"))throw SketchDocument.bad("Exact source SHA-256 required");
        int fps=NativeFrameClock.requireFps(args.has("fps")?(int)SketchDocument.integer(args,"fps",24,60):24),start=args.has("frameStart")?(int)SketchDocument.integer(args,"frameStart",0,SketchSequenceLimits.MAX_FRAMES-1):0;
        String fingerprint=SketchDocument.sha256(SketchDocument.canonical(args).getBytes(StandardCharsets.UTF_8));
        File root=new File(c.getFilesDir(),"sketch_animation_imports/"+bookId+"/"+op),source=new File(root,"source.media"),inputReceipt=new File(root,"input.json"),journal=new File(root,"progress.json"),complete=new File(root,"completed.json");
        if(!root.getCanonicalPath().equals(c.getFilesDir().getCanonicalPath()+"/sketch_animation_imports/"+bookId+"/"+op))throw SketchDocument.bad("Owned animation import path rejected");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Animation import storage unavailable");
        JSONObject input;
        if(inputReceipt.isFile()){
            input=SketchLongBookExport.read(inputReceipt,65536);if(!fingerprint.equals(input.getString("requestSha256")))throw new IllegalStateException("SKETCH_OPERATION_ID_CONFLICT");
            if(!expected.equals(SketchProjectStore.fileHash(source)))throw new IllegalStateException("SKETCH_ANIMATION_SOURCE_CHANGED");
        }else{
            final String[] registration={null},name={null};
            File original=projects.guardedProject(projectId,projectRevision,p->{ProjectStore.Asset a=p.asset(assetId);if(a==null)throw SketchDocument.bad("Choose a registered source animation");registration[0]=SketchDocument.canonical(a.toJson());name[0]=a.name;return privateAsset(c,a);});
            String type=name[0]!=null&&name[0].toLowerCase(Locale.ROOT).endsWith(".zip")?"png-zip":name[0]!=null&&name[0].toLowerCase(Locale.ROOT).matches(".*\\.(mp4|webm|mkv|mov)")?"video":null;
            if(type==null)throw SketchDocument.bad("Choose a numbered PNG ZIP or video imported into VideoStudio");
            File staged=new File(root,"source.part");try{
                try(InputStream in=new FileInputStream(original);FileOutputStream out=new FileOutputStream(staged)){byte[] buffer=new byte[65536];int n;long bytes=0;while((n=in.read(buffer))!=-1){SketchLongBookExport.cancelled();bytes+=n;space(c,n);if(bytes>SketchSequenceLimits.SNAPSHOT_BYTES)throw SketchDocument.bad("Source animation exceeds 8 GiB; import a scene range");out.write(buffer,0,n);}out.getFD().sync();}
                if(!expected.equals(SketchProjectStore.fileHash(staged)))throw new IllegalStateException("SKETCH_ANIMATION_SHA256_MISMATCH");
                projects.guardedProject(projectId,projectRevision,p->{ProjectStore.Asset a=p.asset(assetId);if(a==null||!registration[0].equals(SketchDocument.canonical(a.toJson())))throw new IllegalStateException("SOURCE_ASSET_CHANGED");return null;});
                if(source.exists()&&!source.delete()||!staged.renameTo(source))throw new IOException("Animation source publication failed");
                input=new JSONObject().put("requestSha256",fingerprint).put("type",type);SketchLongBookExport.write(inputReceipt,input);
            }finally{staged.delete();}
        }
        if(complete.isFile())return SketchLongBookExport.read(complete,65536);
        try(Frames frames="png-zip".equals(input.getString("type"))?new PngZip(source,new File(root,"zip-index.db")):new Video(source,fps)){
            int count=args.has("frameCount")?(int)SketchDocument.integer(args,"frameCount",1,SketchSequenceLimits.MAX_CELS):frames.count()-start;
            if(count<1||(long)start+count>frames.count()||count>SketchSequenceLimits.MAX_CELS)throw SketchDocument.bad("Requested frames fall outside source animation");
            int width=args.has("width")?(int)SketchDocument.integer(args,"width",256,2048):evenCanvas(frames.width()),height=args.has("height")?(int)SketchDocument.integer(args,"height",256,2048):evenCanvas(frames.height());
            JSONObject create=new JSONObject().put("bookId",bookId).put("operationId","import_"+fingerprint.substring(0,32)).put("name",args.optString("name","Imported 2D animation")).put("pageCount",count).put("width",width).put("height",height).put("fps",fps).put("background","#00000000");
            SketchEditOps.execute(c,"sketch_book_create",create);
            JSONObject state=journal.isFile()?SketchLongBookExport.read(journal,65536):new JSONObject().put("nextFrame",0).put("ownedBytes",0);int next=state.getInt("nextFrame");long owned=state.getLong("ownedBytes");
            if(next<0||next>count)throw new IllegalStateException("SKETCH_IMPORT_PROGRESS_CHANGED");
            for(int f=next;f<count;f++){
                SketchLongBookExport.cancelled();space(c,16L*width*height);Bitmap bitmap=frames.frame(start+f);String id=bookId+"_p"+f;
                try{
                    JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId",id).put("name","Imported cel "+(f+1)).put("width",width).put("height",height).put("fps",fps).put("frameCount",1).put("background","#00000000"));
                    File references=new File(c.getFilesDir(),"sketch_studio/"+id+"/references");if(!references.isDirectory()&&!references.mkdirs())throw new IOException("Cel reference storage unavailable");File png=File.createTempFile("frame-",".part",references);
                    try{
                        try(FileOutputStream out=new FileOutputStream(png)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Animation cel PNG encoding failed");out.getFD().sync();}
                        if(png.length()>8L*1024*1024)throw SketchDocument.bad("Imported cel exceeds 8 MiB");String sha=SketchProjectStore.fileHash(png);File target=new File(references,sha+".png");
                        if(target.exists()){if(!sha.equals(SketchProjectStore.fileHash(target)))throw new IllegalStateException("SKETCH_REFERENCE_CHANGED");}else if(!png.renameTo(target))throw new IOException("Animation cel publication failed");
                        owned+=target.length();if(owned>SketchSequenceLimits.SNAPSHOT_BYTES)throw new IllegalStateException("SKETCH_ANIMATION_STORAGE: import a smaller scene range");
                        JSONObject reference=new JSONObject().put("file",target.getName()).put("sha256",sha).put("width",bitmap.getWidth()).put("height",bitmap.getHeight()).put("sourceProjectId",projectId).put("sourceAssetId",assetId).put("sourceSha256",expected);
                        double scale=Math.min(width/(double)bitmap.getWidth(),height/(double)bitmap.getHeight()),w=bitmap.getWidth()*scale/width,h=bitmap.getHeight()*scale/height;
                        JSONObject image=new JSONObject().put("id","imported_frame").put("kind","image").put("frame",0).put("exposure",1).put("color","#FFFFFFFF").put("width",.001).put("fill",true).put("transform",SketchDocument.transform()).put("keys",new JSONArray()).put("reference",reference).put("bounds",new JSONObject().put("x",(1-w)/2).put("y",(1-h)/2).put("width",w).put("height",h));
                        doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").put(image);
                        try(SketchProjectStore sketches=new SketchProjectStore(c)){sketches.importOwnedDocument(doc,"animation_import");}
                        try(SketchBookStore books=new SketchBookStore(c)){books.attachImportedPage(bookId,f,id);}
                    }finally{png.delete();}
                }finally{bitmap.recycle();}
                state.put("nextFrame",f+1).put("ownedBytes",owned);SketchLongBookExport.write(journal,state);progress.imported(f+1,count);
            }
            JSONObject result=new JSONObject().put("ok",true).put("bookId",bookId).put("importedFrames",count).put("fps",fps).put("frameStart",start).put("durationUs",NativeFrameClock.presentationTimeUs(count,fps)).put("sourceSha256",expected).put("sourceProjectId",projectId).put("sourceAssetId",assetId).put("sourceType",input.getString("type")).put("ownedBytes",owned).put("rasterCels",true).put("originalLayersRecovered",false).put("originalSourceUnchanged",true).put("galleryAccess",false);
            SketchLongBookExport.write(complete,result);return result;
        }
    }
    static File privateAsset(Context c,ProjectStore.Asset asset)throws Exception {
        Uri uri=Uri.parse(asset.uri);if(!"file".equals(uri.getScheme())||uri.getPath()==null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getAuthority()!=null&&!uri.getAuthority().isEmpty())throw SketchDocument.bad("Choose an app-private imported animation asset");File original=new File(uri.getPath()),file=original.getCanonicalFile();
        if(!file.getPath().startsWith(c.getFilesDir().getCanonicalPath()+File.separator)||Files.isSymbolicLink(original.toPath())||!file.isFile()||file.length()<1||file.length()>SketchSequenceLimits.SNAPSHOT_BYTES||file.length()!=asset.sizeBytes)throw SketchDocument.bad("Animation source ownership or size changed");return file;
    }
    private static void space(Context c,long next){if(c.getFilesDir().getUsableSpace()<SketchSequenceLimits.STORAGE_RESERVE+next)throw new IllegalStateException("SKETCH_ANIMATION_STORAGE: insufficient free space");}
    private static int evenCanvas(int edge){return Math.max(256,Math.min(2048,(edge+1)/2*2));}
    private interface Frames extends AutoCloseable {int count();int width();int height();Bitmap frame(int i)throws Exception;void close();}
    private static final class PngZip implements Frames {
        final ZipFile zip;final SQLiteDatabase index;int count,width,height;String prefix;
        PngZip(File source,File db)throws Exception {
            zip=new ZipFile(source);SQLiteDatabase opened=null;
            try{
                if(db.exists()&&!SQLiteDatabase.deleteDatabase(db))throw new IOException("PNG index cannot be rebuilt");opened=SQLiteDatabase.openOrCreateDatabase(db,null);index=opened;index.execSQL("CREATE TABLE frames(n INTEGER PRIMARY KEY,name TEXT UNIQUE,ordinal INTEGER)");
                index.beginTransaction();try{
                    Enumeration<? extends ZipEntry> entries=zip.entries();int seen=0;
                    while(entries.hasMoreElements()){
                        SketchLongBookExport.cancelled();ZipEntry entry=entries.nextElement();String name=entry.getName();if(++seen>2*SketchSequenceLimits.MAX_CELS)throw SketchDocument.bad("PNG ZIP entry budget exceeded");
                        if(name.length()>256||name.startsWith("/")||name.contains("\\")||Arrays.asList(name.split("/")).contains("..")||name.contains("//"))throw SketchDocument.bad("Unsafe PNG ZIP path");if(entry.isDirectory())continue;
                        Matcher m=Pattern.compile("^(.*?)([0-9]{1,9})\\.png$",Pattern.CASE_INSENSITIVE).matcher(name);if(!m.matches()||entry.getSize()<1||entry.getSize()>8L*1024*1024)throw SketchDocument.bad("Use numbered PNG files with a shared name prefix, up to 8 MiB each");
                        if(prefix==null)prefix=m.group(1);else if(!prefix.equals(m.group(1)))throw SketchDocument.bad("PNG sequence must share one file prefix");
                        ContentValues row=new ContentValues();row.put("n",Long.parseLong(m.group(2)));row.put("name",name);if(index.insert("frames",null,row)<0)throw SketchDocument.bad("Duplicate PNG frame number");if(++count>SketchSequenceLimits.MAX_CELS)throw SketchDocument.bad("PNG sequence exceeds frame capacity");
                    }index.setTransactionSuccessful();
                }finally{index.endTransaction();}
                int ordinal=0;try(Cursor ordered=index.rawQuery("SELECT n FROM frames ORDER BY n",null)){while(ordered.moveToNext()){ContentValues rank=new ContentValues();rank.put("ordinal",ordinal++);index.update("frames",rank,"n=?",new String[]{String.valueOf(ordered.getLong(0))});}}index.execSQL("CREATE UNIQUE INDEX frames_ordinal ON frames(ordinal)");
                if(count==0)throw SketchDocument.bad("PNG sequence is empty");try(BitmapOwner first=new BitmapOwner(frame(0))){width=first.bitmap.getWidth();height=first.bitmap.getHeight();}
            }catch(Exception error){if(opened!=null)opened.close();zip.close();throw error;}
        }
        public int count(){return count;}public int width(){return width;}public int height(){return height;}
        public Bitmap frame(int i)throws Exception {
            String name;try(Cursor c=index.rawQuery("SELECT name FROM frames WHERE ordinal=?",new String[]{String.valueOf(i)})){if(!c.moveToFirst())throw SketchDocument.bad("PNG frame outside sequence");name=c.getString(0);}
            ZipEntry entry=zip.getEntry(name);byte[] bytes;try(InputStream in=zip.getInputStream(entry);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[32768];int n,total=0;while((n=in.read(b))!=-1){SketchLongBookExport.cancelled();total+=n;if(total>8*1024*1024)throw SketchDocument.bad("PNG entry exceeds byte budget");out.write(b,0,n);}bytes=out.toByteArray();}
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(options.outWidth<1||options.outHeight<1||(long)options.outWidth*options.outHeight>64_000_000L||!"image/png".equals(options.outMimeType))throw SketchDocument.bad("Invalid PNG cel dimensions");int originalWidth=options.outWidth,originalHeight=options.outHeight;options.inSampleSize=1;while(Math.max(originalWidth,originalHeight)/options.inSampleSize>2048)options.inSampleSize*=2;options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)throw SketchDocument.bad("PNG cel cannot be decoded");if(width>0&&(bitmap.getWidth()!=width||bitmap.getHeight()!=height)){bitmap.recycle();throw SketchDocument.bad("PNG sequence cel dimensions must match");}return bitmap;
        }
        public void close(){index.close();try{zip.close();}catch(IOException ignored){}}
    }
    private static final class Video implements Frames {
        final MediaMetadataRetriever retriever=new MediaMetadataRetriever();final int fps,count,width,height;
        Video(File source,int fps)throws Exception {this.fps=fps;try{retriever.setDataSource(source.getPath());long duration=Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));long samples=(duration*fps+999)/1000;if(duration<1||samples>SketchSequenceLimits.MAX_FRAMES)throw SketchDocument.bad("Video exceeds sequence duration capacity");count=(int)samples;int w=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),h=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));if(w<1||h<1||w>16384||h>16384)throw SketchDocument.bad("Video dimensions invalid");double scale=Math.min(1,2048d/Math.max(w,h));width=Math.max(1,(int)Math.round(w*scale));height=Math.max(1,(int)Math.round(h*scale));}catch(Exception error){retriever.release();throw error;}}
        public int count(){return count;}public int width(){return width;}public int height(){return height;}
        public Bitmap frame(int i)throws Exception {Bitmap b=retriever.getScaledFrameAtTime(NativeFrameClock.presentationTimeUs(i,fps),MediaMetadataRetriever.OPTION_CLOSEST,width,height);if(b==null)throw new IOException("Native video frame unavailable at sample "+i);return b;}
        public void close(){try{retriever.release();}catch(IOException ignored){}}
    }
    private static final class BitmapOwner implements AutoCloseable {final Bitmap bitmap;BitmapOwner(Bitmap b){bitmap=b;}public void close(){bitmap.recycle();}}
}
