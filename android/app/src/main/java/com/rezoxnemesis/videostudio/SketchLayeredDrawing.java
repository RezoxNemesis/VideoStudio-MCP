package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Persistent source-separated authored drawing stages. Reviews record an explicit human/agent judgment. */
public final class SketchLayeredDrawing {
    public static final String SCHEMA="videostudio.layered-drawing.v1";
    public static final String[] STAGES={"construction","refinement","details","clean_line","separation"};
    private SketchLayeredDrawing(){}
    private static IllegalStateException denied(String reason){return new IllegalStateException("SKETCH_LAYERED_DRAWING_REQUIRED: "+reason);}
    private static int stage(String value){for(int i=0;i<STAGES.length;i++)if(STAGES[i].equals(value))return i;throw denied("Unknown drawing stage");}
    private static JSONObject meta(JSONObject doc)throws Exception{return doc.getJSONObject("layeredDrawing");}
    private static boolean enabled(JSONObject doc){return doc.has("layeredDrawing");}
    /** Normalize only workflow hashes; ordinary canonical JSON and old request fingerprints are unchanged. */
    private static String stable(JSONObject value)throws Exception{return SketchDocument.canonical(new JSONObject(value.toString()));}
    private static String hash(JSONObject value)throws Exception{return SketchDocument.sha256(stable(value).getBytes(StandardCharsets.UTF_8));}
    private static String hashField(JSONObject value,String field)throws Exception{return SketchSourceMask.hashField(value,field);}
    private static boolean contains(JSONArray values,String value)throws Exception{for(int i=0;i<values.length();i++)if(value.equals(values.getString(i)))return true;return false;}
    private static JSONObject clock(JSONObject doc)throws Exception{JSONObject c=new JSONObject();for(String key:new String[]{"width","height","fps","frameCount"})c.put(key,doc.get(key));return c;}
    private static JSONObject guideSnapshot(JSONObject doc,JSONObject m)throws Exception{return SketchDocument.copy(SketchDocument.layer(doc,m.getString("referenceLayerId")));}
    private static void frozen(JSONObject doc,JSONObject m)throws Exception{
        JSONObject layer=SketchDocument.layer(doc,m.getString("referenceLayerId")),image=SketchDocument.object(doc,m.getString("referenceObjectId"));
        if(!layer.getBoolean("locked")||layer.getBoolean("visible")||layer.getJSONArray("objects").length()!=1||!image.getString("id").equals(layer.getJSONArray("objects").getJSONObject(0).getString("id"))||!"image".equals(image.getString("kind")))throw denied("The dedicated reference guide must remain hidden and locked");
        if(image.getJSONObject("transform").getDouble("opacity")!=0)throw denied("Reference pixels cannot be revealed");
        JSONArray keys=image.getJSONArray("keys");for(int i=0;i<keys.length();i++)if(keys.getJSONObject(i).getJSONObject("transform").getDouble("opacity")!=0)throw denied("Reference keyframes cannot reveal source pixels");
        JSONObject ref=image.getJSONObject("reference");if(!m.getString("referenceSha256").equals(ref.getString("sha256"))||!m.getString("referenceSourceSha256").equals(ref.getString("sourceSha256"))||!m.getString("referenceSnapshotSha256").equals(hash(guideSnapshot(doc,m))))throw denied("The frozen reference object, layer or source identity changed");
        if(!stable(clock(doc)).equals(stable(m.getJSONObject("sourceClock"))))throw denied("The source canvas and frame clock are frozen");
    }
    public static JSONArray activeLayerIds(JSONObject doc)throws Exception{return enabled(doc)?new JSONArray(meta(doc).getJSONObject("stageLayers").getJSONArray(meta(doc).getString("stage")).toString()):new JSONArray();}
    public static String stageContentSha256(JSONObject doc,String name)throws Exception{
        stage(name);JSONObject m=meta(doc);JSONArray ids=m.getJSONObject("stageLayers").getJSONArray(name),content=new JSONArray();
        JSONArray ordered=doc.getJSONArray("layers");for(int i=0;i<ordered.length();i++){JSONObject row=ordered.getJSONObject(i);if(!contains(ids,row.getString("id")))continue;JSONObject layer=SketchDocument.copy(row);layer.remove("visible");layer.remove("locked");layer.remove("name");content.put(layer);}
        return hash(new JSONObject().put("stage",name).put("layers",content));
    }
    private static boolean accepted(JSONObject doc,String name)throws Exception{
        JSONArray reviews=meta(doc).getJSONArray("reviews");String digest=stageContentSha256(doc,name);
        for(int i=reviews.length()-1;i>=0;i--){JSONObject r=reviews.getJSONObject(i);if(name.equals(r.getString("stage")))return "ACCEPTABLE".equals(r.getString("status"))&&digest.equals(r.getString("contentSha256"));}return false;
    }
    static boolean currentReviewAccepted(JSONObject doc,String stage)throws Exception{return accepted(doc,stage);}
    public static JSONObject summary(JSONObject doc)throws Exception{
        if(!enabled(doc))return new JSONObject().put("enabled",false);JSONObject m=meta(doc),out=SketchDocument.copy(m);JSONArray states=new JSONArray();
        for(String name:STAGES)states.put(new JSONObject().put("stage",name).put("contentSha256",stageContentSha256(doc,name)).put("acceptable",accepted(doc,name)));
        return out.put("enabled",true).put("status",reviewed(doc)?"REVIEWED":"IN_PROGRESS").put("activeLayerIds",activeLayerIds(doc)).put("stageReviews",states).put("artisticQualityCertified",false);
    }
    private static boolean reviewed(JSONObject doc)throws Exception{if(!"REVIEWED".equals(meta(doc).getString("status")))return false;for(String name:STAGES)if(!accepted(doc,name))return false;return true;}
    /** Detached animation input contains reviewed clean authored parts only; original checkpoints stay protected. */
    public static JSONObject animationSource(JSONObject doc)throws Exception{
        if(!enabled(doc))return SketchDocument.copy(doc);validate(doc);if(!reviewed(doc))throw denied("Finish every current stage review before animating clean parts");
        JSONObject result=SketchDocument.copy(doc);result.remove("layeredDrawing");JSONArray layers=new JSONArray(),ids=meta(doc).getJSONObject("stageLayers").getJSONArray("clean_line");JSONArray ordered=doc.getJSONArray("layers");for(int i=0;i<ordered.length();i++)if(contains(ids,ordered.getJSONObject(i).getString("id")))layers.put(SketchDocument.copy(ordered.getJSONObject(i)));result.put("layers",layers);SketchDocument.validate(result);return result;
    }
    public static JSONObject referenceDocument(JSONObject doc)throws Exception{
        if(!enabled(doc))throw denied("Begin a layered drawing first");validate(doc);JSONObject result=SketchDocument.copy(doc),m=meta(doc),layer=SketchDocument.copy(SketchDocument.layer(doc,m.getString("referenceLayerId")));result.remove("layeredDrawing");
        layer.put("visible",true).put("locked",false).put("opacity",1);layer.getJSONObject("transform").put("opacity",1);JSONArray layerKeys=layer.getJSONArray("keys");for(int i=0;i<layerKeys.length();i++)layerKeys.getJSONObject(i).getJSONObject("transform").put("opacity",1);
        JSONObject image=layer.getJSONArray("objects").getJSONObject(0);image.getJSONObject("transform").put("opacity",1);JSONArray keys=image.getJSONArray("keys");for(int i=0;i<keys.length();i++)keys.getJSONObject(i).getJSONObject("transform").put("opacity",1);result.put("layers",new JSONArray().put(layer));SketchDocument.validate(result);return result;
    }
    public static void apply(JSONObject doc,JSONObject op)throws Exception{
        String type=op.getString("type");if("layered_drawing_begin".equals(type)){begin(doc,op);return;}
        if(!enabled(doc))throw denied("Begin a layered drawing before changing its stage");JSONObject m=meta(doc);
        if("layered_drawing_stage".equals(type)){
            SketchDocument.fields(op,"type,stage,promote");String next=op.getString("stage");int from=stage(m.getString("stage")),to=stage(next);
            if(to>from&&(to!=from+1||!accepted(doc,STAGES[from])))throw denied("Advance one stage after an explicit current ACCEPTABLE review");m.put("stage",next).put("status","IN_PROGRESS");
            JSONArray ids=m.getJSONObject("stageLayers").getJSONArray(next);
            // Keep the immediately preceding drawing for detail work, while hiding
            // construction after refinement and every preliminary pass for clean parts.
            for(int pass=0;pass<4;pass++){JSONArray passIds=m.getJSONObject("stageLayers").getJSONArray(STAGES[pass]);boolean visible=to<3?(pass==to||to>0&&pass==to-1):pass==3;for(int i=0;i<passIds.length();i++)SketchDocument.layer(doc,passIds.getString(i)).put("visible",visible);}
            for(int i=0;i<ids.length();i++)SketchDocument.layer(doc,ids.getString(i)).put("locked",false);if(op.has("promote"))SketchAuthoredStagePromotion.apply(doc,op);return;
        }
        if("layered_drawing_review".equals(type)){
            SketchDocument.fields(op,"type,stage,reviewedRevision,status,note,frameIndex");String name=op.getString("stage");stage(name);if(!name.equals(m.getString("stage")))throw denied("Review the active drawing stage");
            long revision=SketchDocument.integer(op,"reviewedRevision",1,Long.MAX_VALUE-1);if(revision!=doc.getLong("revision"))throw denied("Review must bind the exact current authored revision");
            String status=op.getString("status");if(!"ACCEPTABLE".equals(status)&&!"NEEDS_REWORK".equals(status))throw denied("Review status must be ACCEPTABLE or NEEDS_REWORK");SketchDocument.text(op,"note",1000);
            int frame=op.has("frameIndex")?(int)SketchDocument.integer(op,"frameIndex",0,doc.getInt("frameCount")-1):0;
            if(!stageHasArt(doc,name,false,frame))throw denied("Review requires visible authored marks; empty cels and erasers alone are insufficient");
            JSONArray reviews=m.getJSONArray("reviews");if(reviews.length()>=32){int removable=-1;for(int i=0;i<reviews.length()&&removable<0;i++){String prior=reviews.getJSONObject(i).getString("stage");if(name.equals(prior)){removable=i;break;}for(int j=i+1;j<reviews.length();j++)if(prior.equals(reviews.getJSONObject(j).getString("stage"))){removable=i;break;}}if(removable<0)throw denied("Stage review history cannot release its latest checkpoints");reviews.remove(removable);}
            reviews.put(new JSONObject().put("stage",name).put("reviewedRevision",revision).put("frameIndex",frame).put("status",status).put("note",op.getString("note")).put("contentSha256",stageContentSha256(doc,name)));m.put("status","IN_PROGRESS");return;
        }
        if("layered_drawing_finish".equals(type)){
            SketchDocument.fields(op,"type");if(!"separation".equals(m.getString("stage")))throw denied("Finish after the separation stage");for(String name:STAGES)if(!accepted(doc,name))throw denied("Every stage needs a fresh explicit ACCEPTABLE review");
            if(!stageHasArt(doc,"clean_line",true,reviewFrame(doc,"separation")))throw denied("Every named clean part requires visible authored marks");
            for(String name:new String[]{"construction","refinement","details"}){JSONArray ids=m.getJSONObject("stageLayers").getJSONArray(name);for(int i=0;i<ids.length();i++)SketchDocument.layer(doc,ids.getString(i)).put("visible",false);}
            JSONArray ids=m.getJSONObject("stageLayers").getJSONArray("clean_line");for(int i=0;i<ids.length();i++)SketchDocument.layer(doc,ids.getString(i)).put("visible",true).put("locked",false);m.put("status","REVIEWED");return;
        }
        throw denied("Unknown layered drawing operation");
    }
    private static void begin(JSONObject doc,JSONObject op)throws Exception{
        SketchDocument.fields(op,"type,referenceObjectId,prefix,parts");if(enabled(doc))throw denied("This document already has a persistent layered workflow");String objectId=SketchDocument.id(op,"referenceObjectId"),prefix=SketchDocument.id(op,"prefix");if(prefix.length()>32)throw denied("Use a prefix of at most 32 characters");
        JSONObject image=SketchDocument.object(doc,objectId),guide=SketchDocument.owner(doc,objectId);if(!"image".equals(image.getString("kind"))||guide.getJSONArray("objects").length()!=1||image.getJSONObject("reference").has("sourceMask"))throw denied("Choose the original reference image on its own dedicated layer");
        JSONObject ref=image.getJSONObject("reference");hashField(ref,"sha256");hashField(ref,"sourceSha256");JSONArray old=doc.getJSONArray("layers");Set<String> used=new HashSet<>();
        for(int i=0;i<old.length();i++){JSONObject layer=old.getJSONObject(i);used.add(layer.getString("id"));JSONArray objects=layer.getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject object=objects.getJSONObject(j);used.add(object.getString("id"));if(!objectId.equals(object.getString("id")))throw denied("Begin on a document containing only the reference image and empty layers");}}
        JSONArray parts=op.getJSONArray("parts");if(parts.length()<2||parts.length()>12)throw denied("Name 2..12 separately authored clean parts");Set<String> partIds=new HashSet<>();JSONArray named=new JSONArray(),clean=new JSONArray();JSONObject layers=new JSONObject();
        for(String name:new String[]{"construction","refinement","details"}){String id=prefix+"_"+name;addLayer(doc,id,name.replace('_',' '),used);layers.put(name,new JSONArray().put(id));}
        for(int i=0;i<parts.length();i++){JSONObject p=parts.getJSONObject(i);SketchDocument.fields(p,"id,name");String id=SketchDocument.id(p,"id");if(id.length()>48)throw denied("Part IDs are limited to 48 characters");SketchDocument.text(p,"name",160);if(!partIds.add(id))throw denied("Part IDs must be distinct");String layerId=prefix+"_"+id;if(layerId.length()>96)throw denied("Part layer ID exceeds 96 characters");addLayer(doc,layerId,p.getString("name"),used);named.put(new JSONObject().put("id",id).put("name",p.getString("name")).put("layerId",layerId));clean.put(layerId);}
        layers.put("clean_line",clean).put("separation",new JSONArray(clean.toString()));guide.put("locked",true).put("visible",false);image.getJSONObject("transform").put("opacity",0);JSONArray keys=image.getJSONArray("keys");for(int i=0;i<keys.length();i++)keys.getJSONObject(i).getJSONObject("transform").put("opacity",0);
        JSONObject m=new JSONObject().put("schema",SCHEMA).put("stage","construction").put("status","IN_PROGRESS").put("referenceObjectId",objectId).put("referenceLayerId",guide.getString("id")).put("referenceSha256",ref.getString("sha256")).put("referenceSourceSha256",ref.getString("sourceSha256")).put("sourceClock",clock(doc)).put("stageLayers",layers).put("parts",named).put("reviews",new JSONArray());doc.put("layeredDrawing",m);m.put("referenceSnapshotSha256",hash(guideSnapshot(doc,m)));
    }
    private static void addLayer(JSONObject doc,String id,String name,Set<String> used)throws Exception{if(!used.add(id))throw denied("A planned layer ID already exists");doc.getJSONArray("layers").put(new JSONObject().put("id",id).put("name",name).put("visible",true).put("locked",false).put("opacity",1).put("transform",SketchDocument.transform()).put("keys",new JSONArray()).put("objects",new JSONArray()));}
    private static void authored(JSONObject object,boolean nativePaint)throws Exception{
        String kind=object.optString("kind");if(!Arrays.asList("stroke","eraser","path").contains(kind)&&!(nativePaint&&"paint".equals(kind)))throw denied("Author strokes, erasers, unfilled pen paths or native paint cels; copied or prefab shapes are unavailable");
        if(object.optBoolean("fill",false)||object.has("reference")||object.has("masterLink")||object.has("hand")||object.has("human")||object.has("styleId")||object.has("gradient"))throw denied("Only source-free unfilled authored marks are allowed");
    }
    /** Structural admission only. Paint ancestry is checked on the native authoring worker. */
    static JSONArray authoredCelSources(JSONObject doc,JSONObject op)throws Exception {
        if(!enabled(doc))throw denied("Protected authored cel reuse requires a layered drawing");validate(doc);
        SketchDocument.fields(op,"type,layerId,fromFrame,toFrame,idPrefix");
        for(String field:new String[]{"layerId","fromFrame","toFrame","idPrefix"})if(!op.has(field))throw denied("Authored cel reuse requires "+field);
        int from=(int)SketchDocument.integer(op,"fromFrame",0,doc.getInt("frameCount")-1),to=(int)SketchDocument.integer(op,"toFrame",0,SketchDocument.MAX_FRAMES-1);
        if(to>=doc.getInt("frameCount"))throw denied("Copy inside the frozen drawing clock; its frame count cannot be extended");
        String layerId=SketchDocument.id(op,"layerId");
        if(!contains(activeLayerIds(doc),layerId))throw denied("Copy authored cels on an active stage layer only");
        JSONObject layer=SketchDocument.layer(doc,layerId);SketchEditOps.requireUnlocked(layer);
        JSONObject sourceTransform=SketchDocument.transformAt(layer,from),targetTransform=SketchDocument.transformAt(layer,to);
        if(!layer.getBoolean("visible")||layer.getDouble("opacity")<=0||sourceTransform.getDouble("opacity")<=0||targetTransform.getDouble("opacity")<=0)throw denied("Copy a visible authored stage layer");
        if(!stable(sourceTransform).equals(stable(targetTransform)))throw denied("Match source and destination layer transforms before reusing authored cels");
        JSONArray objects=layer.getJSONArray("objects"),sources=new JSONArray();
        for(int i=0;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);if(!SketchFrameRenderer.exposed(object,from))continue;authored(object,true);sources.put(object);}
        if(sources.length()==0)throw denied("Choose a cel containing authored marks");return sources;
    }
    public static void requireOperation(JSONObject doc,JSONObject op)throws Exception{
        String type=op.optString("type");if("layered_drawing_begin".equals(type)){if(enabled(doc))throw denied("A workflow is already active");return;}if(!enabled(doc))return;
        if(type.startsWith("layered_drawing_"))return;JSONArray active=activeLayerIds(doc);String layerId=null;
        if("add_object".equals(type)){authored(op.getJSONObject("object"),false);layerId=op.getString("layerId");}
        else if("duplicate_cel".equals(type)){authoredCelSources(doc,op);layerId=op.getString("layerId");}
        else if("precision_contour".equals(type)){if(op.optBoolean("fill",false)||op.optBoolean("pressureOutline",false))throw denied("Use an unfilled precision pen contour");layerId=op.getString("layerId");}
        else if("add_paint_cel".equals(type))layerId=op.getString("layerId");
        else if("paint_fill".equals(type)){
            JSONObject record=op.optJSONObject("stroke"),request=record==null?op:record.getJSONObject("request");SketchContourPaint.request(request);String contour=request.getString("contourObjectId");layerId=SketchDocument.owner(doc,contour).getString("id");authored(SketchDocument.object(doc,contour),true);if(!contains(active,layerId))throw denied("Fill an authored contour on the active stage layer");try{String paintLayer=SketchDocument.owner(doc,request.getString("objectId")).getString("id");if(!layerId.equals(paintLayer))throw denied("Fill the native paint cel on the same authored layer");}catch(IllegalArgumentException absent){if(!absent.getMessage().contains("Unknown sketch object"))throw absent;}
        }
        else if("paint_stroke".equals(type)){
            JSONObject record=op.optJSONObject("stroke"),request=record==null?op:record.getJSONObject("request");JSONObject brush=request.optJSONObject("brush");if(request.has("cloneSource")||record!=null&&record.has("source")||brush==null||!Arrays.asList("paint","erase").contains(brush.optString("mode")))throw denied("Only native paint and erase brushes are allowed; clone copies reference pixels");try{layerId=SketchDocument.owner(doc,op.getString("objectId")).getString("id");}catch(IllegalArgumentException absent){if(absent.getMessage().contains("Unknown sketch object"))return;throw absent;}
        }else if("set_exposure_cel".equals(type)){
            SketchDocument.fields(op,"type,layerId,frameIndex,exposureFrames");
            layerId=SketchDocument.id(op,"layerId");
            JSONObject target=SketchDocument.layer(doc,layerId);
            int source=(int)SketchDocument.integer(op,"frameIndex",0,doc.getInt("frameCount")-1);
            JSONArray strokes=target.getJSONArray("objects");
            boolean authoredCel=false;
            for(int i=0;i<strokes.length();i++){
                JSONObject object=strokes.getJSONObject(i);
                if(object.getInt("frame")==source){authored(object,true);authoredCel=true;}
            }
            if(!authoredCel)throw denied("Choose the original authored drawing-start cell");
        }else if("move_path_node".equals(type)){
            SketchDocument.fields(op,"type,objectId,frameIndex,commandIndex,pairIndex,x,y");
            JSONObject object=SketchDocument.object(doc,SketchDocument.id(op,"objectId"));
            layerId=SketchDocument.owner(doc,object.getString("id")).getString("id");
            authored(object,true);
        }else if(Arrays.asList("append_points","update_object","delete_object","set_exposure","reorder_object").contains(type)){
            String id=op.getString("objectId");JSONObject object=SketchDocument.object(doc,id);layerId=SketchDocument.owner(doc,id).getString("id");authored(object,true);
            if("update_object".equals(type)){JSONObject candidate=SketchDocument.copy(object),patch=op.getJSONObject("patch");Iterator<String> keys=patch.keys();while(keys.hasNext()){String key=keys.next();candidate.put(key,patch.get(key));}authored(candidate,true);}
        }else if("set_layer_group_motion".equals(type)){
            JSONArray selected=op.getJSONArray("layerIds");for(int i=0;i<selected.length();i++)if(!contains(active,selected.getString(i)))throw denied("Group motion must select active stage layers only");return;
        }else if("set_transform".equals(type)||"set_keyframe".equals(type)){layerId=op.has("objectId")?SketchDocument.owner(doc,op.getString("objectId")).getString("id"):op.getString("layerId");}
        else if("reorder_layer".equals(type)){
            String current=meta(doc).getString("stage");if(!"clean_line".equals(current)&&!"separation".equals(current))throw denied("Reorder named clean parts during clean line or separation");
            layerId=op.getString("layerId");JSONArray ordered=doc.getJSONArray("layers");int target=(int)SketchDocument.integer(op,"index",0,ordered.length()-1),first=-1,last=-1,count=0;
            for(int i=0;i<ordered.length();i++)if(contains(active,ordered.getJSONObject(i).getString("id"))){if(first<0)first=i;last=i;count++;}
            if(last-first+1!=count||target<first||target>last)throw denied("Keep clean parts within their own layer block; reference and preliminary layers stay protected");
        }
        else if("update_layer".equals(type)){layerId=op.getString("layerId");JSONObject patch=op.getJSONObject("patch");Iterator<String> fields=patch.keys();while(fields.hasNext())if(!"name".equals(fields.next()))throw denied("Stage visibility and reference locks are managed by the workflow");}
        else throw denied("This persistent drawing accepts authored marks and explicit stage/review operations only: "+type);
        if(!contains(active,layerId))throw denied("Draw on an active stage layer; revisit earlier stages explicitly");
    }
    private static String emptyPaintHash(JSONObject doc)throws Exception{return hash(new JSONObject().put("schema","videostudio.sketch.paint.snapshot.v1").put("width",doc.getInt("width")).put("height",doc.getInt("height")).put("tileSize",SketchPaintStore.TILE_SIZE).put("strokeCount",0).put("tiles",new JSONArray()));}
    private static boolean layerHasArt(JSONObject doc,JSONObject layer,int frame)throws Exception{
        if(!layer.getBoolean("visible")||layer.getDouble("opacity")<=0||SketchDocument.transformAt(layer,frame).getDouble("opacity")<=0)return false;JSONArray objects=layer.getJSONArray("objects");
        for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);String kind=o.getString("kind");if("eraser".equals(kind)||(frame<o.getInt("frame")||frame>=o.getInt("frame")+o.getInt("exposure"))||SketchDocument.transformAt(o,frame).getDouble("opacity")<=0)continue;if("paint".equals(kind)){if(!emptyPaintHash(doc).equals(o.getJSONObject("paint").getString("sha256")))return true;}else if((SketchDocument.color(o.getString("color"))>>>24)>0)return true;}return false;
    }
    private static boolean stageHasArt(JSONObject doc,String name,boolean every,int frame)throws Exception{JSONArray ids=meta(doc).getJSONObject("stageLayers").getJSONArray(name);boolean found=false;for(int i=0;i<ids.length();i++){boolean art=layerHasArt(doc,SketchDocument.layer(doc,ids.getString(i)),frame);if(every&&!art)return false;found|=art;}return found;}
    public static void validate(JSONObject doc)throws Exception{
        if(!enabled(doc))return;JSONObject m=meta(doc);SketchDocument.fields(m,"schema,stage,status,referenceObjectId,referenceLayerId,referenceSha256,referenceSourceSha256,referenceSnapshotSha256,sourceClock,stageLayers,parts,reviews");if(!SCHEMA.equals(m.getString("schema")))throw denied("Unknown layered drawing schema");stage(m.getString("stage"));if(!Arrays.asList("IN_PROGRESS","REVIEWED").contains(m.getString("status")))throw denied("Unknown layered drawing status");for(String key:new String[]{"referenceObjectId","referenceLayerId"})SketchDocument.id(m,key);for(String key:new String[]{"referenceSha256","referenceSourceSha256","referenceSnapshotSha256"})hashField(m,key);SketchDocument.fields(m.getJSONObject("sourceClock"),"width,height,fps,frameCount");frozen(doc,m);
        JSONObject sl=m.getJSONObject("stageLayers");SketchDocument.fields(sl,"construction,refinement,details,clean_line,separation");Set<String> stageIds=new HashSet<>();
        for(String name:STAGES){JSONArray ids=sl.getJSONArray(name);if(ids.length()<("clean_line".equals(name)||"separation".equals(name)?2:1)||ids.length()>("clean_line".equals(name)||"separation".equals(name)?12:1))throw denied("Stage layer topology changed");Set<String> local=new HashSet<>();for(int i=0;i<ids.length();i++){String id=ids.getString(i);SketchDocument.id(new JSONObject().put("id",id),"id");if(!local.add(id)||id.equals(m.getString("referenceLayerId")))throw denied("Stage layers require distinct owned IDs");SketchDocument.layer(doc,id);if(!"separation".equals(name)&&!stageIds.add(id))throw denied("Independent stages cannot share layers");}}
        if(!sl.getJSONArray("clean_line").toString().equals(sl.getJSONArray("separation").toString()))throw denied("Separation retains the exact clean part layers");JSONArray parts=m.getJSONArray("parts"),clean=sl.getJSONArray("clean_line");if(parts.length()!=clean.length())throw denied("Named parts changed");Set<String> partIds=new HashSet<>();for(int i=0;i<parts.length();i++){JSONObject p=parts.getJSONObject(i);SketchDocument.fields(p,"id,name,layerId");if(!partIds.add(SketchDocument.id(p,"id")))throw denied("Duplicate part");SketchDocument.text(p,"name",160);if(!clean.getString(i).equals(SketchDocument.id(p,"layerId")))throw denied("Part layer mapping changed");}
        JSONArray layers=doc.getJSONArray("layers");for(int i=0;i<layers.length();i++){JSONObject layer=layers.getJSONObject(i);if(layer.getString("id").equals(m.getString("referenceLayerId")))continue;JSONArray objects=layer.getJSONArray("objects");if(objects.length()>0&&!stageIds.contains(layer.getString("id")))throw denied("Authored marks must remain in named stage layers");for(int j=0;j<objects.length();j++)authored(objects.getJSONObject(j),true);}
        JSONArray reviews=m.getJSONArray("reviews");if(reviews.length()>32)throw denied("Stage review history exceeds 32 entries");for(int i=0;i<reviews.length();i++){JSONObject r=reviews.getJSONObject(i);SketchDocument.fields(r,"stage,reviewedRevision,frameIndex,status,note,contentSha256");stage(r.getString("stage"));SketchDocument.integer(r,"reviewedRevision",1,Long.MAX_VALUE-1);SketchDocument.integer(r,"frameIndex",0,doc.getInt("frameCount")-1);if(!Arrays.asList("ACCEPTABLE","NEEDS_REWORK").contains(r.getString("status")))throw denied("Unknown review status");SketchDocument.text(r,"note",1000);hashField(r,"contentSha256");}
        if("REVIEWED".equals(m.getString("status"))&&!reviewed(doc))throw denied("Reviewed content changed; return to an in-progress stage");
    }
    /** Compare the complete prospective document with replayed authorized native events. */
    public static void requireTransition(JSONObject current,JSONObject next,JSONArray liveOps)throws Exception{
        if(!enabled(current)&&!enabled(next))return;if(liveOps==null||liveOps.length()==0)throw denied("Every workflow change requires recorded native operations");
        for(int i=0;i<liveOps.length();i++)if(liveOps.getJSONObject(i).optString("type").startsWith("layered_drawing_")&&liveOps.length()!=1)throw denied("Stage controls and reviews require their own source-revision-bound transaction");
        JSONObject replay=SketchDocument.copy(current);
        for(int i=0;i<liveOps.length();i++){JSONObject op=liveOps.getJSONObject(i);
            if(enabled(replay)&&"add_object".equals(op.optString("type"))&&"paint".equals(op.getJSONObject("object").optString("kind"))){JSONObject object=op.getJSONObject("object");authored(object,true);if(!contains(activeLayerIds(replay),op.getString("layerId"))||!emptyPaintHash(replay).equals(object.getJSONObject("paint").getString("sha256")))throw denied("Paint cels must start with the native empty snapshot");SketchDocument.layer(replay,op.getString("layerId")).getJSONArray("objects").put(SketchEditOps.completeObject(object));}
            else SketchEditOps.apply(replay,op);
        }
        updateStatus(replay);updateStatus(next);JSONObject a=SketchDocument.copy(replay),b=SketchDocument.copy(next);a.remove("revision");b.remove("revision");if(!stable(a).equals(stable(b)))throw denied("Unrecorded document replacement is not an authored drawing step");if(enabled(current)&&!enabled(next))throw denied("A persistent drawing workflow cannot be removed by editing");validate(next);
    }
    static void afterOperation(JSONObject doc)throws Exception{updateStatus(doc);}
    private static void updateStatus(JSONObject doc)throws Exception{if(enabled(doc)&&"REVIEWED".equals(meta(doc).getString("status"))&&!reviewed(doc))meta(doc).put("status","IN_PROGRESS");}
    /** Undo/redo restores a genuine prior checkpoint while preserving the immutable guide and workflow. */
    public static void requireHistoryTransition(JSONObject current,JSONObject next)throws Exception{
        if(!enabled(current)&&!enabled(next))return;
        if(enabled(current)&&enabled(next)){if(!meta(current).getString("referenceSnapshotSha256").equals(meta(next).getString("referenceSnapshotSha256"))||!stable(meta(current).getJSONObject("stageLayers")).equals(stable(meta(next).getJSONObject("stageLayers"))))throw denied("History cannot replace the frozen reference or stage layers");validate(next);return;}
        // This helper is used ONLY after the store has loaded a genuine historical snapshot.
        JSONObject guarded=enabled(current)?current:next,plain=enabled(current)?next:current,m=meta(guarded);JSONArray layers=plain.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");for(int j=0;j<objects.length();j++)if(!m.getString("referenceObjectId").equals(objects.getJSONObject(j).getString("id")))throw denied("Undo across begin requires the stored reference-only baseline");}
        JSONObject baseline=SketchDocument.copy(plain),guide=SketchDocument.layer(baseline,m.getString("referenceLayerId")),image=SketchDocument.object(baseline,m.getString("referenceObjectId"));guide.put("visible",false).put("locked",true);image.getJSONObject("transform").put("opacity",0);JSONArray keys=image.getJSONArray("keys");for(int i=0;i<keys.length();i++)keys.getJSONObject(i).getJSONObject("transform").put("opacity",0);
        if(!m.getString("referenceSnapshotSha256").equals(hash(guide))||!stable(clock(plain)).equals(stable(m.getJSONObject("sourceClock"))))throw denied("Historical baseline does not match the frozen source");validate(guarded);
    }
    public static void requireReviewPixels(Context context,JSONObject doc,JSONArray operations)throws Exception{
        if(!enabled(doc)||operations==null)return;boolean required=false;for(int i=0;i<operations.length();i++)required|=Arrays.asList("layered_drawing_review","layered_drawing_finish").contains(operations.getJSONObject(i).optString("type"));if(!required)return;if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw denied("Pixel review must run on the authoring worker");for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);String type=op.optString("type");if("layered_drawing_review".equals(type))requirePixels(context,doc,op.getString("stage"),false,op.optInt("frameIndex",0));else if("layered_drawing_finish".equals(type))requirePixels(context,doc,"clean_line",true,reviewFrame(doc,"separation"));}
    }
    private static int reviewFrame(JSONObject doc,String name)throws Exception{JSONArray reviews=meta(doc).getJSONArray("reviews");for(int i=reviews.length()-1;i>=0;i--)if(name.equals(reviews.getJSONObject(i).getString("stage")))return reviews.getJSONObject(i).getInt("frameIndex");throw denied("No stage review frame is available");}
    private static void requirePixels(Context context,JSONObject doc,String name,boolean every,int frame)throws Exception{
        JSONArray ids=meta(doc).getJSONObject("stageLayers").getJSONArray(name);boolean any=false;
        for(int i=0;i<ids.length();i++){JSONObject copy=SketchDocument.copy(doc);copy.remove("layeredDrawing");copy.put("background","#00000000").put("layers",new JSONArray().put(SketchDocument.copy(SketchDocument.layer(doc,ids.getString(i)))));boolean art=false;
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,copy)){Bitmap bitmap=renderer.render(frame,false);try{int[] row=new int[bitmap.getWidth()];for(int y=0;y<bitmap.getHeight()&&!art;y++){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Layered drawing review cancelled");bitmap.getPixels(row,0,row.length,0,y,row.length,1);for(int pixel:row)if((pixel>>>24)!=0){art=true;break;}}}finally{bitmap.recycle();}}
            if(every&&!art)throw denied("Every clean part needs actual visible authored pixels");any|=art;
        }if(!any)throw denied("Review requires actual visible authored pixels, not empty or erased content");
    }
}
