package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen source evidence. Image locations stay in the native project's asset registry. */
public final class StoryboardSourceManifest {
    public static final String FORMAT="videostudio.storyboard-sources.v1";
    public static final int MAX_SOURCES=160,MAX_MANIFEST_BYTES=256*1024;
    public static final long MAX_IMAGE_BYTES=32L*1024*1024,MAX_TOTAL_BYTES=512L*1024*1024;
    private static final int BUFFER_BYTES=64*1024;
    private StoryboardSourceManifest(){ }

    /** The read-only encoded file exists only during this callback; decoded results may outlive it. */
    public interface VerifiedSource<T>{
        T read(File encodedSnapshot,JSONObject sourceEntry)throws Exception;
        /** Release an owned result if private-file cleanup prevents it being delivered. */
        default void discard(T value)throws Exception{
            if(value instanceof AutoCloseable)((AutoCloseable)value).close();
        }
    }

    /** Worker-only measurement of a registered asset, without an existing annotation attestation. */
    public static JSONObject captureSource(Context context,ProjectStore.Asset registeredAsset)throws Exception{
        return withFrozenSource(context,registeredAsset,(file,entry)->entry);
    }

    /** Explicitly unbound legacy/first-authoring read. This does not attest existing geometry. */
    public static <T>T withFrozenSource(Context context,ProjectStore.Asset registeredAsset,VerifiedSource<T> reader)throws Exception{
        return withProbe(context,registeredAsset,MAX_IMAGE_BYTES,reader);
    }

    /** Resolve an existing project-owned image and author against the exact bytes displayed. */
    public static <T>T withOwnedSourceSnapshot(Context context,ProjectStore.Project project,String assetId,VerifiedSource<T> reader)throws Exception{
        return withFrozenSource(context,ownedImage(project,assetId),reader);
    }

    /** Match the full accepted source record before decoding the same privately frozen bytes. */
    public static <T>T withVerifiedSource(Context context,ProjectStore.Asset registeredAsset,JSONObject expectedEntry,VerifiedSource<T> reader)throws Exception{
        ProjectStore.Asset asset=sourceSnapshot(registeredAsset);JSONObject expected=checkedEntry(asset,expectedEntry);
        if(reader==null)throw new IllegalArgumentException("A native source reader is required");
        return withProbe(context,asset,MAX_IMAGE_BYTES,new VerifiedSource<T>(){
            @Override public T read(File file,JSONObject actual)throws Exception{
                if(!canonical(expected).equals(canonical(actual)))throw new IllegalArgumentException("Frozen storyboard source changed: "+asset.id);
                return reader.read(file,actual);
            }
            @Override public void discard(T value)throws Exception{reader.discard(value);}
        });
    }

    /** SHA-only compatibility read; cinematic callers should supply their complete accepted entry. */
    public static <T>T withVerifiedSource(Context context,ProjectStore.Asset registeredAsset,String expectedSha256,VerifiedSource<T> reader)throws Exception{
        if(expectedSha256==null||!expectedSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("An accepted lowercase source SHA-256 is required");
        if(reader==null)throw new IllegalArgumentException("A native source reader is required");
        return withProbe(context,registeredAsset,MAX_IMAGE_BYTES,new VerifiedSource<T>(){
            @Override public T read(File file,JSONObject actual)throws Exception{
                if(!expectedSha256.equals(actual.getString("sourceSha256")))throw new IllegalArgumentException("Frozen storyboard source changed: "+actual.getString("assetId"));
                return reader.read(file,actual);
            }
            @Override public void discard(T value)throws Exception{reader.discard(value);}
        });
    }

    /** A saved manifest supplies both ownership/order admission and the exact image-byte attestation. */
    public static <T>T withVerifiedSource(Context context,ProjectStore.Project project,JSONObject manifest,String assetId,VerifiedSource<T> reader)throws Exception{
        JSONObject accepted=validateForPlanning(project,manifest);ProjectStore.Asset asset=ownedImage(project,assetId);
        JSONArray library=accepted.getJSONArray("library");
        for(int index=0;index<library.length();index++){
            JSONObject entry=library.getJSONObject(index);
            if(asset.id.equals(entry.getString("assetId")))return withVerifiedSource(context,asset,entry,reader);
        }
        throw new IllegalArgumentException("Source image is absent from the frozen manifest");
    }

    /** Worker-only: stream each registered image; no original or project is changed. */
    public static JSONObject capture(Context context,ProjectStore.Project project,JSONObject selection)throws Exception{
        if(context==null)throw new IllegalArgumentException("Native source context is required");
        List<ProjectStore.Asset> images=imageLibrary(project);Selection order=selection(project,selection,images);
        JSONArray library=new JSONArray();long total=0;
        for(ProjectStore.Asset image:images){
            interrupted();JSONObject entry=probe(context,image,MAX_TOTAL_BYTES-total);
            total+=entry.getLong("bytes");library.put(entry);
        }
        JSONObject manifest=new JSONObject().put("format",FORMAT).put("version",1).put("sourceProjectId",project.id)
                .put("originalImageCount",images.size()).put("library",library).put("orderOrigin",order.origin)
                .put("orderedAssetIds",strings(order.ids)).put("exactByteAliases",aliases(library));
        if(order.groups!=null)manifest.put("anchorGroups",order.groups);
        manifest.put("manifestSha256",hash(canonical(manifest).getBytes(StandardCharsets.UTF_8)));
        budget(manifest);return manifest;
    }

    /** Verify the saved order and registered bytes before allocation, audit, render or recovery cleanup. */
    public static void verifyOwnedSources(Context context,ProjectStore.Project project,JSONObject supplied)throws Exception{
        JSONObject expected=validateForPlanning(project,supplied);
        JSONObject options=new JSONObject();String origin=expected.getString("orderOrigin");
        if("explicit_asset_ids".equals(origin))options.put("assetIds",expected.getJSONArray("orderedAssetIds"));
        else if("declared_anchor_groups".equals(origin))options.put("anchorGroups",expected.getJSONArray("anchorGroups"));
        JSONObject actual=capture(context,project,options);
        if(!expected.getString("manifestSha256").equals(actual.getString("manifestSha256")))
            throw new IllegalArgumentException("Frozen storyboard sources or their order changed; originals and earlier outputs are retained");
    }

    /** Pure structural/metadata check. Native callers still verify the actual owned bytes. */
    public static JSONObject validateForPlanning(ProjectStore.Project project,JSONObject supplied)throws Exception{
        List<ProjectStore.Asset> images=imageLibrary(project);
        if(supplied==null)throw new IllegalArgumentException("A frozen storyboard source manifest is required");
        fields(supplied,"format","version","sourceProjectId","originalImageCount","library","orderOrigin","orderedAssetIds","anchorGroups","exactByteAliases","manifestSha256");
        if(!FORMAT.equals(text(supplied,"format",80))||integer(supplied,"version",1,1)!=1||!project.id.equals(identity(supplied.opt("sourceProjectId"))))
            throw new IllegalArgumentException("Source manifest belongs to another project or format");
        JSONArray raw=array(supplied,"library");
        if(raw.length()!=images.size()||integer(supplied,"originalImageCount",1,MAX_SOURCES)!=raw.length())
            throw new IllegalArgumentException("The original storyboard image library changed");
        JSONArray library=new JSONArray();long total=0;
        for(int index=0;index<raw.length();index++){
            JSONObject entry=checkedEntry(images.get(index),object(raw,index));long bytes=entry.getLong("bytes");total+=bytes;
            if(total>MAX_TOTAL_BYTES)throw new IllegalArgumentException("Source manifest exceeds its aggregate image byte budget");
            library.put(entry);
        }
        String origin=text(supplied,"orderOrigin",40);JSONObject options=new JSONObject();
        if("explicit_asset_ids".equals(origin)){
            if(supplied.has("anchorGroups"))throw new IllegalArgumentException("Explicit source order cannot also have variant groups");
            options.put("assetIds",array(supplied,"orderedAssetIds"));
        }else if("declared_anchor_groups".equals(origin))options.put("anchorGroups",array(supplied,"anchorGroups"));
        else if(!"persisted_clip_order".equals(origin))throw new IllegalArgumentException("Unknown source manifest order origin");
        else if(supplied.has("anchorGroups"))throw new IllegalArgumentException("Timeline order cannot also have variant groups");
        Selection selected=selection(project,options,images);
        JSONArray ordered=array(supplied,"orderedAssetIds");checkIds(ordered);
        if(!canonical(strings(selected.ids)).equals(canonical(ordered)))throw new IllegalArgumentException("Frozen storyboard order no longer matches its declared source selection");
        JSONArray givenAliases=array(supplied,"exactByteAliases");
        if(givenAliases.length()>MAX_SOURCES)throw new IllegalArgumentException("Too many source aliases");
        for(int index=0;index<givenAliases.length();index++){
            JSONObject alias=object(givenAliases,index);fields(alias,"assetId","canonicalAssetId","sourceSha256");
            identity(alias.opt("assetId"));identity(alias.opt("canonicalAssetId"));digest(alias,"sourceSha256");
        }
        JSONArray actualAliases=aliases(library);
        if(!canonical(givenAliases).equals(canonical(actualAliases)))throw new IllegalArgumentException("Exact-byte alias evidence is inconsistent");
        JSONObject checked=new JSONObject().put("format",FORMAT).put("version",1).put("sourceProjectId",project.id)
                .put("originalImageCount",images.size()).put("library",library).put("orderOrigin",origin)
                .put("orderedAssetIds",strings(selected.ids)).put("exactByteAliases",actualAliases);
        if(selected.groups!=null)checked.put("anchorGroups",selected.groups);
        String expected=digest(supplied,"manifestSha256");
        if(!hash(canonical(checked).getBytes(StandardCharsets.UTF_8)).equals(expected))throw new IllegalArgumentException("Source manifest checksum is invalid");
        checked.put("manifestSha256",expected);budget(checked);return checked;
    }

    public static List<String> resolveAssetIds(ProjectStore.Project project,JSONObject parameters)throws Exception{
        return new ArrayList<>(selection(project,parameters,imageLibrary(project)).ids);
    }
    /** Explicit retry selections cannot contradict the accepted artifact or replace its variant declaration. */
    static void validateSelectionAgainstManifest(ProjectStore.Project project,JSONObject parameters,JSONObject accepted)throws Exception{
        if(parameters==null||(!parameters.has("assetIds")&&!parameters.has("anchorGroups")))return;
        Selection declared=selection(project,parameters,imageLibrary(project));
        if(!declared.origin.equals(accepted.getString("orderOrigin"))
                ||!canonical(strings(declared.ids)).equals(canonical(accepted.getJSONArray("orderedAssetIds")))
                ||(declared.groups!=null&&!canonical(declared.groups).equals(canonical(accepted.getJSONArray("anchorGroups")))))
            throw new IllegalArgumentException("Requested source selection differs from the frozen storyboard manifest");
    }
    public static List<String> manifestAssetIds(JSONObject manifest)throws Exception{
        JSONArray ids=array(manifest,"orderedAssetIds");checkIds(ids);List<String> result=new ArrayList<>();
        for(int index=0;index<ids.length();index++)result.add(ids.getString(index));return result;
    }
    public static int declaredVariantCount(ProjectStore.Project project,JSONObject parameters)throws Exception{
        if(parameters==null||!parameters.has("anchorGroups"))return 0;
        Selection order=selection(project,new JSONObject().put("anchorGroups",array(parameters,"anchorGroups")),imageLibrary(project));
        Set<String> representatives=new HashSet<>(order.ids);return imageLibrary(project).size()-representatives.size();
    }
    /** Coded/oriented dimensions are metadata only; a renderer must register the same source UV mapping. */
    public static int[] orientedDimensions(JSONObject manifest,String assetId)throws Exception{
        JSONArray library=array(manifest,"library");
        for(int index=0;index<library.length();index++){
            JSONObject entry=object(library,index);if(assetId.equals(entry.optString("assetId")))
                return new int[]{(int)integer(entry,"orientedWidth",16,8192),(int)integer(entry,"orientedHeight",16,8192)};
        }
        throw new IllegalArgumentException("Source image is absent from the frozen manifest");
    }

    private static final class Selection{
        final String origin;final List<String> ids;final JSONArray groups;
        Selection(String origin,List<String> ids,JSONArray groups){this.origin=origin;this.ids=ids;this.groups=groups;}
    }
    private static Selection selection(ProjectStore.Project project,JSONObject input,List<ProjectStore.Asset> images)throws Exception{
        JSONObject options=input==null?new JSONObject():input;
        if(options.has("assetIds")&&options.has("anchorGroups"))throw new IllegalArgumentException("Use literal assetIds or declared anchorGroups, not both");
        Set<String> known=new HashSet<>();for(ProjectStore.Asset image:images)known.add(image.id);List<String> ids=new ArrayList<>();
        JSONArray groups=null;String origin;
        if(options.has("anchorGroups")){
            JSONArray raw=array(options,"anchorGroups");if(raw.length()<2||raw.length()>MAX_SOURCES)throw new IllegalArgumentException("Declare 2 to 160 ordered anchor groups");
            groups=new JSONArray();Map<String,String> owners=new HashMap<>();
            for(int index=0;index<raw.length();index++){
                JSONObject group=object(raw,index);fields(group,"representativeAssetId","assetIds");String representative=identity(group.opt("representativeAssetId"));
                JSONArray members=array(group,"assetIds");if(members.length()<1||members.length()>MAX_SOURCES)throw new IllegalArgumentException("Variant group requires bounded original image IDs");
                Set<String> unique=new HashSet<>();List<String> normalized=new ArrayList<>();
                for(int item=0;item<members.length();item++){
                    String id=identity(members.get(item));if(!known.contains(id)||!unique.add(id))throw new IllegalArgumentException("Variant group references an unknown or repeated original image");
                    String old=owners.put(id,representative);if(old!=null&&!old.equals(representative))throw new IllegalArgumentException("Original image belongs to conflicting variant groups");normalized.add(id);
                }
                if(!unique.contains(representative))throw new IllegalArgumentException("Variant representative must belong to its source group");
                Collections.sort(normalized);groups.put(new JSONObject().put("representativeAssetId",representative).put("assetIds",strings(normalized)));ids.add(representative);
            }
            if(!owners.keySet().equals(known))throw new IllegalArgumentException("Declared variant groups must preserve and cover every original image");
            origin="declared_anchor_groups";
        }else if(options.has("assetIds")){
            JSONArray explicit=array(options,"assetIds");checkIds(explicit);
            for(int index=0;index<explicit.length();index++){String id=identity(explicit.get(index));if(!known.contains(id))throw new IllegalArgumentException("Unknown explicit storyboard source at index "+index);ids.add(id);}
            origin="explicit_asset_ids";
        }else{
            for(ProjectStore.Clip clip:project.clips){
                if(clip==null)throw new IllegalArgumentException("Storyboard timeline contains an invalid clip");
                ProjectStore.Asset asset=project.asset(clip.assetId);if(asset==null)throw new IllegalArgumentException("Storyboard timeline references missing media");
                if(StoryboardBatchPlanner.isImage(asset))ids.add(asset.id);
                if(ids.size()>MAX_SOURCES)throw new IllegalArgumentException("Storyboard order exceeds 160 anchor occurrences");
            }
            origin="persisted_clip_order";
        }
        if(ids.size()<2||ids.size()>MAX_SOURCES)throw new IllegalArgumentException("Need 2 to 160 ordered imported anchor occurrences");
        return new Selection(origin,ids,groups);
    }
    private static List<ProjectStore.Asset> imageLibrary(ProjectStore.Project project){
        if(project==null)throw new IllegalArgumentException("An explicitly selected source project is required");identity(project.id);
        List<ProjectStore.Asset> result=new ArrayList<>();Set<String> allIds=new HashSet<>();
        for(ProjectStore.Asset asset:project.assets){
            if(asset==null||!allIds.add(identity(asset.id)))throw new IllegalArgumentException("Project media identities are invalid or duplicated");
            if(asset.mime!=null&&asset.mime.startsWith("image/")){
                sourceSnapshot(asset);
                result.add(asset);if(result.size()>MAX_SOURCES)throw new IllegalArgumentException("Source image library exceeds 160 originals");
            }
        }
        if(result.isEmpty())throw new IllegalArgumentException("Source project contains no imported images");result.sort(Comparator.comparing(asset->asset.id));return result;
    }
    private static JSONObject probe(Context context,ProjectStore.Asset asset,long remaining)throws Exception{
        return withProbe(context,asset,remaining,(file,entry)->entry);
    }
    private static ProjectStore.Asset ownedImage(ProjectStore.Project project,String assetId){
        String id=identity(assetId);for(ProjectStore.Asset asset:imageLibrary(project))if(id.equals(asset.id))return asset;
        throw new IllegalArgumentException("Source image is not registered in the selected project");
    }
    private static ProjectStore.Asset sourceSnapshot(ProjectStore.Asset supplied){
        if(supplied==null)throw new IllegalArgumentException("A registered native image is required");identity(supplied.id);
        if(!StoryboardBatchPlanner.isImage(supplied)||supplied.uri==null||supplied.uri.isEmpty())throw new IllegalArgumentException("Registered source image has no native URI");
        if(supplied.mime.length()>100||!supplied.mime.matches("image/[A-Za-z0-9.+_-]+"))throw new IllegalArgumentException("Invalid source image MIME type");
        if((supplied.name!=null&&supplied.name.length()>4096)||(supplied.role!=null&&supplied.role.length()>100))throw new IllegalArgumentException("Source image label exceeds its metadata budget");
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=supplied.id;asset.uri=supplied.uri;asset.mime=supplied.mime;
        asset.name=supplied.name;asset.role=supplied.role;asset.generated=supplied.generated;asset.sizeBytes=supplied.sizeBytes;return asset;
    }
    private static JSONObject checkedEntry(ProjectStore.Asset image,JSONObject entry)throws Exception{
        if(entry==null)throw new IllegalArgumentException("An accepted source entry is required");
        fields(entry,"assetId","mime","metadataSha256","sourceSha256","bytes","codedWidth","codedHeight","exifOrientation","orientedWidth","orientedHeight");
        String id=identity(entry.opt("assetId"));
        if(!image.id.equals(id)||!image.mime.equals(text(entry,"mime",100))||!metadataHash(image).equals(digest(entry,"metadataSha256")))
            throw new IllegalArgumentException("Registered storyboard source metadata changed: "+id);
        long bytes=integer(entry,"bytes",1,MAX_IMAGE_BYTES);
        int width=(int)integer(entry,"codedWidth",16,8192),height=(int)integer(entry,"codedHeight",16,8192),orientation=(int)integer(entry,"exifOrientation",1,8);
        int orientedWidth=(int)integer(entry,"orientedWidth",16,8192),orientedHeight=(int)integer(entry,"orientedHeight",16,8192);
        if(orientedWidth!=(orientation>=5?height:width)||orientedHeight!=(orientation>=5?width:height))throw new IllegalArgumentException("Source orientation and dimensions disagree");
        return new JSONObject().put("assetId",id).put("mime",image.mime).put("metadataSha256",digest(entry,"metadataSha256"))
                .put("sourceSha256",digest(entry,"sourceSha256")).put("bytes",bytes).put("codedWidth",width).put("codedHeight",height)
                .put("exifOrientation",orientation).put("orientedWidth",orientedWidth).put("orientedHeight",orientedHeight);
    }
    private static <T>T withProbe(Context context,ProjectStore.Asset supplied,long remaining,VerifiedSource<T> reader)throws Exception{
        if(context==null||reader==null)throw new IllegalArgumentException("A native source context and reader are required");
        ProjectStore.Asset asset=sourceSnapshot(supplied);interrupted();
        Uri uri=Uri.parse(asset.uri);if(!"content".equals(uri.getScheme())&&!"file".equals(uri.getScheme()))throw new IllegalArgumentException("Source must be a registered native file or document URI");
        if(asset.sizeBytes>MAX_IMAGE_BYTES||remaining<1)throw new IllegalArgumentException("Source image exceeds its encoded byte budget");
        File root=new File(context.getCacheDir(),"storyboard_source_probes");if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("Private source probe storage unavailable");
        File temporary=File.createTempFile("source-",".probe",root);
        T result=null;boolean completed=false;Throwable primaryFailure=null;
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;byte[] buffer=new byte[BUFFER_BYTES];
            try(InputStream input=context.getContentResolver().openInputStream(uri);FileOutputStream output=new FileOutputStream(temporary)){
                if(input==null)throw new IllegalArgumentException("Registered source image is unavailable: "+asset.id);
                int emptyReads=0;
                while(true){
                    interrupted();int count=input.read(buffer);if(count<0)break;
                    if(count==0){if(++emptyReads>16)throw new IllegalArgumentException("Source provider did not make progress");continue;}emptyReads=0;
                    if(count>MAX_IMAGE_BYTES-bytes||count>remaining-bytes)throw new IllegalArgumentException("Source image exceeds its encoded byte budget");
                    output.write(buffer,0,count);digest.update(buffer,0,count);bytes+=count;
                }
            }catch(InterruptedException cancelled){throw cancelled;}
            catch(Exception error){throw new IllegalArgumentException("Registered source image is unavailable or outside its byte budget: "+asset.id,error);}
            interrupted();if(bytes<1)throw new IllegalArgumentException("Source image is empty: "+asset.id);
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(temporary.getAbsolutePath(),bounds);
            if(bounds.outWidth<16||bounds.outHeight<16||bounds.outWidth>8192||bounds.outHeight>8192)throw new IllegalArgumentException("Source image dimensions or format are invalid: "+asset.id);
            int orientation;
            if("image/bmp".equals(bounds.outMimeType)||"image/gif".equals(bounds.outMimeType))orientation=1;
            else{
                ExifInterface exif=new ExifInterface(temporary.getAbsolutePath());orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
                if(orientation==ExifInterface.ORIENTATION_UNDEFINED)orientation=ExifInterface.ORIENTATION_NORMAL;
                if(orientation<1||orientation>8)throw new IllegalArgumentException("Source image EXIF orientation is invalid");
            }
            JSONObject entry=new JSONObject().put("assetId",asset.id).put("mime",asset.mime).put("metadataSha256",metadataHash(asset)).put("sourceSha256",hex(digest.digest())).put("bytes",bytes)
                    .put("codedWidth",bounds.outWidth).put("codedHeight",bounds.outHeight).put("exifOrientation",orientation)
                    .put("orientedWidth",orientation>=5?bounds.outHeight:bounds.outWidth).put("orientedHeight",orientation>=5?bounds.outWidth:bounds.outHeight);
            if(!temporary.setReadOnly())throw new IllegalStateException("Private source snapshot could not be protected");
            interrupted();result=reader.read(temporary,entry);completed=true;return result;
        }catch(Exception|Error failure){primaryFailure=failure;throw failure;}
        finally{
            if(temporary.exists()&&!temporary.delete()){
                IllegalStateException cleanup=new IllegalStateException("Private source probe could not be released");
                if(completed)try{reader.discard(result);}catch(Exception|Error discardFailure){cleanup.addSuppressed(discardFailure);}
                if(primaryFailure!=null)primaryFailure.addSuppressed(cleanup);else throw cleanup;
            }
        }
    }
    private static String metadataHash(ProjectStore.Asset asset)throws Exception{
        String name=asset.name==null?"":asset.name;if(name.length()>4096)throw new IllegalArgumentException("Source label exceeds its metadata budget");
        JSONObject metadata=new JSONObject().put("assetId",asset.id).put("mime",asset.mime).put("name",name).put("role",asset.role==null?"":asset.role).put("generated",asset.generated);
        return hash(canonical(metadata).getBytes(StandardCharsets.UTF_8));
    }
    private static JSONArray aliases(JSONArray library)throws Exception{
        Map<String,String> first=new LinkedHashMap<>();JSONArray result=new JSONArray();
        for(int index=0;index<library.length();index++){
            JSONObject entry=object(library,index);String sha=entry.getString("sourceSha256"),id=entry.getString("assetId"),canonical=first.get(sha);
            if(canonical==null)first.put(sha,id);else result.put(new JSONObject().put("assetId",id).put("canonicalAssetId",canonical).put("sourceSha256",sha));
        }
        return result;
    }
    private static JSONArray strings(List<String> values){JSONArray result=new JSONArray();for(String value:values)result.put(value);return result;}
    private static void checkIds(JSONArray ids)throws Exception{if(ids.length()<2||ids.length()>MAX_SOURCES)throw new IllegalArgumentException("Use 2 to 160 ordered source IDs");for(int index=0;index<ids.length();index++)identity(ids.get(index));}
    private static String identity(Object raw){if(!(raw instanceof String)||!((String)raw).matches("[A-Za-z0-9_.:-]{1,180}"))throw new IllegalArgumentException("A bounded native project or asset identity is required");return(String)raw;}
    private static String text(JSONObject object,String key,int max){Object raw=object.opt(key);if(!(raw instanceof String)||((String)raw).length()>max)throw new IllegalArgumentException("Invalid source manifest text: "+key);return(String)raw;}
    private static String digest(JSONObject object,String key){String value=text(object,key,64);if(!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid source SHA-256: "+key);return value;}
    private static long integer(JSONObject object,String key,long min,long max){Object raw=object.opt(key);if(!(raw instanceof Number))throw new IllegalArgumentException("Invalid source manifest number: "+key);double value=((Number)raw).doubleValue();if(!Double.isFinite(value)||value<min||value>max||value!=Math.rint(value))throw new IllegalArgumentException("Source manifest number is outside bounds: "+key);return(long)value;}
    private static JSONArray array(JSONObject object,String key){Object raw=object.opt(key);if(!(raw instanceof JSONArray))throw new IllegalArgumentException(key+" must be a bounded array");return(JSONArray)raw;}
    private static JSONObject object(JSONArray array,int index){Object raw=array.opt(index);if(!(raw instanceof JSONObject))throw new IllegalArgumentException("Source manifest record must be an object");return(JSONObject)raw;}
    private static void fields(JSONObject object,String... allowed){Set<String> names=new HashSet<>(Arrays.asList(allowed));Iterator<String> keys=object.keys();while(keys.hasNext())if(!names.contains(keys.next()))throw new IllegalArgumentException("Unknown source manifest field");}
    private static void budget(JSONObject manifest)throws Exception{String encoded=canonical(manifest);if(encoded.length()>MAX_MANIFEST_BYTES||encoded.getBytes(StandardCharsets.UTF_8).length>MAX_MANIFEST_BYTES)throw new IllegalArgumentException("Source manifest exceeds 256 KiB");}
    private static String canonical(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();Iterator<String> names=object.keys();while(names.hasNext())keys.add(names.next());Collections.sort(keys);StringBuilder result=new StringBuilder("{");for(String key:keys){if(result.length()>1)result.append(',');result.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return result.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder result=new StringBuilder("[");for(int index=0;index<array.length();index++){if(index>0)result.append(',');result.append(canonical(array.get(index)));}return result.append(']').toString();}
        if(value instanceof String)return JSONObject.quote((String)value);if(value instanceof Number)return JSONObject.numberToString((Number)value);if(value instanceof Boolean)return value.toString();throw new IllegalArgumentException("Source manifest contains unsupported values");
    }
    private static String hash(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){char[] digits="0123456789abcdef".toCharArray(),result=new char[bytes.length*2];for(int index=0;index<bytes.length;index++){result[index*2]=digits[(bytes[index]>>>4)&15];result[index*2+1]=digits[bytes[index]&15];}return new String(result);}
    private static void interrupted()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Source manifest capture cancelled");}
}
