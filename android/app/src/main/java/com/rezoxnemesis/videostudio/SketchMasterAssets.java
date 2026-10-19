package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 1: document-owned, versioned vector masters and linked path instances.
 *
 * The renderer still receives ordinary editable paths. The master is the source of
 * geometric truth; local transforms, keys, timing and explicit appearance overrides
 * remain on each instance. One sketch_edit is one atomic SQLite/history transaction.
 *
 * This first slice deliberately excludes cross-project links, bitmaps, paint and
 * procedural human rigs. These need separate dependency and asset lifecycle work.
 */
final class SketchMasterAssets {
    static final int MAX_MASTERS = 64;
    private static final String[] APPEARANCE = {"commands","color","fill","width","gradient"};
    private SketchMasterAssets() {}

    static Map<String,JSONObject> indexed(JSONObject doc) throws Exception {
        if (!doc.has("masterAssets")) return Collections.emptyMap();
        JSONArray masters = doc.optJSONArray("masterAssets");
        if (masters == null || masters.length() > MAX_MASTERS)
            throw SketchDocument.bad("masterAssets must be an array with at most 64 masters");
        Map<String,JSONObject> result = new LinkedHashMap<>();
        for (int i=0;i<masters.length();i++) {
            JSONObject master=masters.getJSONObject(i);
            SketchDocument.fields(master,"id,name,version,kind,artwork");
            String id=SketchDocument.id(master,"id");
            SketchDocument.text(master,"name",100);
            SketchDocument.integer(master,"version",1,Long.MAX_VALUE-2);
            if (!"path".equals(master.getString("kind")))
                throw SketchDocument.bad("Phase 1 masters support original editable paths only");
            appearance(master.getJSONObject("artwork"),true);
            if (result.put(id,master)!=null)
                throw SketchDocument.bad("Duplicate vector master identity: "+id);
        }
        return result;
    }

    static int storedPoints(Map<String,JSONObject> masters) throws Exception {
        int total=0;
        for (JSONObject master:masters.values())
            total+=SketchDocument.validateCommands(master.getJSONObject("artwork").getJSONArray("commands"));
        return total;
    }

    static void validateInstance(JSONObject instance,Map<String,JSONObject> masters) throws Exception {
        if (!instance.has("masterLink")) return;
        JSONObject link=instance.getJSONObject("masterLink");
        SketchDocument.fields(link,"id,version,overrides");
        String id=SketchDocument.id(link,"id");
        JSONObject master=masters.get(id);
        if (master==null) throw SketchDocument.bad("Linked artwork has an unknown master: "+id);
        if (!"path".equals(instance.optString("kind")))
            throw SketchDocument.bad("A linked master currently requires an editable path");
        if (instance.has("styleId")||instance.has("shapeKeys")||instance.has("brush"))
            throw SketchDocument.bad("A linked path cannot have independent geometry or palette programmes");
        if (SketchDocument.integer(link,"version",1,Long.MAX_VALUE-2)!=master.getLong("version"))
            throw SketchDocument.bad("Linked master version mismatch");
        JSONObject overrides=link.getJSONObject("overrides");
        overrides(overrides);
        JSONObject expected=resolved(master,overrides);
        for (String field:APPEARANCE)
            if (!sameField(instance,expected,field))
                throw SketchDocument.bad("Linked artwork changed outside master_patch/master_override: "+field);
    }

    private static void appearance(JSONObject art,boolean all) throws Exception {
        SketchDocument.fields(art,all?"commands,color,fill,width,gradient":"commands,color,fill,width,gradient");
        if (all&&(!art.has("commands")||!art.has("color")||!art.has("fill")||!art.has("width")))
            throw SketchDocument.bad("A master requires path commands, color, fill and stroke width");
        if (art.has("commands")) SketchDocument.validateCommands(art.getJSONArray("commands"));
        if (art.has("color")) SketchDocument.color(art.getString("color"));
        if (art.has("fill")) SketchDocument.bool(art,"fill");
        if (art.has("width")) SketchDocument.number(art,"width",.0005,.2);
        if (art.has("gradient")) {
            if (art.isNull("gradient")) {if(all)throw SketchDocument.bad("Master gradient must be removed, not null");}
            else SketchDocument.validateGradient(art.getJSONObject("gradient"));
        }
    }

    private static void overrides(JSONObject overrides)throws Exception {
        SketchDocument.fields(overrides,"color,width,fill,gradient");
        appearance(overrides,false);
    }
    private static boolean sameField(JSONObject a,JSONObject b,String key)throws Exception {
        return a.has(key)==b.has(key)
            && (!a.has(key)||SketchDocument.canonical(a.get(key)).equals(SketchDocument.canonical(b.get(key))));
    }
    private static JSONObject snapshot(JSONObject source)throws Exception {
        if (!"path".equals(source.getString("kind"))||source.has("shapeKeys")
                ||source.has("styleId")||source.has("brush")||source.has("masterLink"))
            throw SketchDocument.bad("Choose an original vector path with no existing master, shape animation or palette link");
        JSONObject art=new JSONObject();
        for(String field:APPEARANCE)
            if(source.has(field)) art.put(field,source.get(field));
        appearance(art,true);
        return SketchDocument.copy(art);
    }

    private static JSONObject resolved(JSONObject master,JSONObject overrides)throws Exception {
        JSONObject out=SketchDocument.copy(master.getJSONObject("artwork"));
        for(String field:new String[]{"color","width","fill","gradient"}){
            if(!overrides.has(field))continue;
            if(overrides.isNull(field))out.remove(field);
            else out.put(field,overrides.get(field));
        }
        return out;
    }

    private static void sync(JSONObject target,JSONObject master,JSONObject overrides)throws Exception {
        JSONObject art=resolved(master,overrides);
        for(String field:APPEARANCE) {
            if (art.has(field)) target.put(field,SketchDocument.copy(new JSONObject().put("v",art.get(field))).get("v"));
            else target.remove(field);
        }
        target.put("masterLink",new JSONObject().put("id",master.getString("id"))
                .put("version",master.getLong("version"))
                .put("overrides",SketchDocument.copy(overrides)));
    }
    private static JSONObject requireMaster(JSONObject doc,String id)throws Exception {
        JSONObject master=indexed(doc).get(id);
        if(master==null)throw SketchDocument.bad("Unknown linked master: "+id);
        return master;
    }
    private static JSONObject requireLink(JSONObject doc,String objectId)throws Exception {
        JSONObject target=SketchDocument.object(doc,objectId);
        if(!target.has("masterLink"))throw SketchDocument.bad("Choose a linked master instance");
        return target;
    }
    private static void unlocked(JSONObject doc,String objectId)throws Exception {
        SketchEditOps.requireUnlocked(SketchDocument.owner(doc,objectId));
    }

    static void apply(JSONObject doc,JSONObject op)throws Exception {
        String type=op.getString("type");
        switch(type) {
            case "master_create": {
                SketchDocument.fields(op,"type,masterId,name,sourceObjectId");
                String id=SketchDocument.id(op,"masterId");
                if(indexed(doc).containsKey(id))throw SketchDocument.bad("Master identity already exists");
                String object=SketchDocument.id(op,"sourceObjectId");
                unlocked(doc,object);
                JSONObject art=snapshot(SketchDocument.object(doc,object));
                String name=op.getString("name");
                if(name.isEmpty()||name.length()>100)throw SketchDocument.bad("Master name must be 1..100 characters");
                JSONArray list=doc.optJSONArray("masterAssets");
                if(list==null){list=new JSONArray();doc.put("masterAssets",list);}
                if(list.length()>=MAX_MASTERS)throw SketchDocument.bad("Master budget exceeded");
                JSONObject master=new JSONObject().put("id",id).put("name",name)
                    .put("kind","path").put("version",1).put("artwork",art);
                list.put(master);
                sync(SketchDocument.object(doc,object),master,new JSONObject());
                break;
            }
            case "master_instance": {
                SketchDocument.fields(op,"type,masterId,layerId,objectId,frame,exposure,transform,keys,overrides");
                String id=SketchDocument.id(op,"masterId"),layerId=SketchDocument.id(op,"layerId");
                JSONObject master=requireMaster(doc,id);
                JSONObject layer=SketchDocument.layer(doc,layerId);
                SketchEditOps.requireUnlocked(layer);
                String newId=SketchDocument.id(op,"objectId");
                // Reject duplicate object/layer identities, even if no object geometry overlaps.
                try {SketchDocument.object(doc,newId);throw SketchDocument.bad("Duplicate instance object ID");}
                catch(IllegalArgumentException missing){
                    if(!missing.getMessage().startsWith("INVALID_SKETCH: Unknown sketch object:"))throw missing;
                }
                SketchDocument.layer(doc,layerId);
                int frame=op.has("frame")?(int)SketchDocument.integer(op,"frame",0,doc.getInt("frameCount")-1):0;
                int exposure=op.has("exposure")?(int)SketchDocument.integer(op,"exposure",1,doc.getInt("frameCount")-frame):doc.getInt("frameCount")-frame;
                JSONObject obj=new JSONObject().put("id",newId).put("kind","path").put("frame",frame)
                    .put("exposure",exposure).put("transform",op.has("transform")?SketchDocument.copy(op.getJSONObject("transform")):SketchDocument.transform())
                    .put("keys",op.has("keys")?new JSONArray(op.getJSONArray("keys").toString()):new JSONArray());
                JSONObject overrides=op.has("overrides")?SketchDocument.copy(op.getJSONObject("overrides")):new JSONObject();
                overrides(overrides);sync(obj,master,overrides);
                layer.getJSONArray("objects").put(obj);
                break;
            }
            case "master_patch": {
                SketchDocument.fields(op,"type,masterId,expectedVersion,patch");
                String id=SketchDocument.id(op,"masterId");
                JSONObject master=requireMaster(doc,id);
                long old=SketchDocument.integer(op,"expectedVersion",1,Long.MAX_VALUE-2);
                if(old!=master.getLong("version"))throw new IllegalStateException("SKETCH_MASTER_VERSION_CONFLICT");
                JSONObject patch=op.getJSONObject("patch");
                appearance(patch,false);
                if(patch.length()==0)throw SketchDocument.bad("Master patch must change an appearance property");
                JSONObject artwork=master.getJSONObject("artwork");
                for(String field:APPEARANCE)if(patch.has(field)){
                    if(patch.isNull(field)){if(!"gradient".equals(field))throw SketchDocument.bad("Only gradient can be removed");artwork.remove(field);}
                    else artwork.put(field,patch.get(field));
                }
                appearance(artwork,true);
                // Locked instances make the whole master transaction fail; no silent drift.
                for(int i=0;i<doc.getJSONArray("layers").length();i++){
                    JSONObject layer=doc.getJSONArray("layers").getJSONObject(i);
                    JSONArray objects=layer.getJSONArray("objects");
                    for(int j=0;j<objects.length();j++){
                        JSONObject target=objects.getJSONObject(j),link=target.optJSONObject("masterLink");
                        if(link!=null&&id.equals(link.optString("id"))){
                            SketchEditOps.requireUnlocked(layer);
                        }
                    }
                }
                master.put("version",old+1);
                for(int i=0;i<doc.getJSONArray("layers").length();i++){
                    JSONArray objects=doc.getJSONArray("layers").getJSONObject(i).getJSONArray("objects");
                    for(int j=0;j<objects.length();j++){
                        JSONObject target=objects.getJSONObject(j),link=target.optJSONObject("masterLink");
                        if(link!=null&&id.equals(link.optString("id")))
                            sync(target,master,link.getJSONObject("overrides"));
                    }
                }
                break;
            }
            case "master_override": {
                SketchDocument.fields(op,"type,objectId,patch");
                String id=SketchDocument.id(op,"objectId");unlocked(doc,id);
                JSONObject target=requireLink(doc,id),link=target.getJSONObject("masterLink");
                JSONObject overrides=SketchDocument.copy(link.getJSONObject("overrides"));
                JSONObject patch=op.getJSONObject("patch");overrides(patch);
                if(patch.length()==0)throw SketchDocument.bad("Empty local override");
                for(String field:new String[]{"color","width","fill","gradient"})
                    if(patch.has(field))overrides.put(field,patch.get(field));
                sync(target,requireMaster(doc,link.getString("id")),overrides);
                break;
            }
            case "master_clear_override": {
                SketchDocument.fields(op,"type,objectId,field");
                String id=SketchDocument.id(op,"objectId");unlocked(doc,id);
                JSONObject target=requireLink(doc,id),link=target.getJSONObject("masterLink");
                String field=op.getString("field");
                if(!("color".equals(field)||"width".equals(field)||"fill".equals(field)||"gradient".equals(field)))
                    throw SketchDocument.bad("Choose one clearable appearance property");
                JSONObject overrides=SketchDocument.copy(link.getJSONObject("overrides"));
                overrides.remove(field);
                sync(target,requireMaster(doc,link.getString("id")),overrides);
                break;
            }
            case "master_detach": {
                SketchDocument.fields(op,"type,objectId");
                String id=SketchDocument.id(op,"objectId");unlocked(doc,id);
                requireLink(doc,id).remove("masterLink");
                break;
            }
            default: throw SketchDocument.bad("Unknown linked master operation: "+type);
        }
    }

    static JSONObject preview(JSONObject doc,JSONObject args)throws Exception {
        SketchDocument.fields(args,"sketchId,expectedRevision,masterId,patch");
        long pinned=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-2);
        if(doc.getLong("revision")!=pinned)throw new IllegalStateException("SKETCH_REVISION_CONFLICT");
        String id=SketchDocument.id(args,"masterId");
        JSONObject master=requireMaster(doc,id),patch=args.optJSONObject("patch");
        if(args.has("patch")&&patch==null)throw SketchDocument.bad("Master preview patch must be an object");
        if(patch!=null)appearance(patch,false);
        JSONArray affected=new JSONArray(),overridden=new JSONArray(),locked=new JSONArray();
        int durationFrames=0;
        JSONArray layers=doc.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){
            JSONObject layer=layers.getJSONObject(i);
            JSONArray objects=layer.getJSONArray("objects");
            for(int j=0;j<objects.length();j++){
                JSONObject object=objects.getJSONObject(j),link=object.optJSONObject("masterLink");
                if(link==null||!id.equals(link.optString("id")))continue;
                affected.put(object.getString("id"));
                durationFrames+=object.getInt("exposure");
                if(link.getJSONObject("overrides").length()>0)overridden.put(object.getString("id"));
                if(layer.getBoolean("locked"))locked.put(layer.getString("id"));
            }
        }
        return new JSONObject().put("ok",true).put("readOnly",true)
            .put("sketchId",doc.getString("id")).put("sourceRevision",pinned)
            .put("masterId",id).put("masterVersion",master.getLong("version"))
            .put("instanceCount",affected.length()).put("instanceExposureFrames",durationFrames)
            .put("affectedObjectIds",affected).put("localOverrideIds",overridden)
            .put("lockedLayerIds",locked).put("ready",locked.length()==0)
            .put("onApply","master_patch is a revision-pinned atomic sketch_edit; transform and timing keys are preserved")
            .put("qualityCertified",false).put("exportCompleted",false);
    }

    static JSONObject capability()throws Exception {
        return new JSONObject().put("minimumNativeVersion","future-merge-required")
            .put("readAction","sketch_master_preview")
            .put("writeAction","sketch_edit")
            .put("operations",new JSONArray(new String[]{"master_create","master_instance","master_patch","master_override","master_clear_override","master_detach"}))
            .put("firstSlice","Editable vector path masters; 64 per sketch; one SQLite/history transaction per edit")
            .put("links","Master geometry/color/gradient is copied into ordinary native vector render objects, with a stored versioned link; updating a master propagates across all linked instances in the same sketch")
            .put("independentAnimation","Each instance keeps its own transform keys, frame/exposure and local color/width/fill/gradient overrides")
            .put("crossSketchPropagation",false).put("proceduralCharacterSupport",false)
            .put("automaticProfessionalDrawing",false).put("installedOnPhone",false)
            .put("galleryAccess",false).put("visualQualityCertified",false);
    }
}
