package com.rezoxnemesis.videostudio;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Owned, immutable artwork components; the same editable layers are placed by touch and MCP. */
final class SketchComponentLibrary implements AutoCloseable {
    static final String[] CATEGORIES={"character","pose","turn","expression","mouth","hand","prop","background","effect","construction"};
    private final Context context;private final Db helper;private final SQLiteDatabase db;
    private static final class Db extends SQLiteOpenHelper {
        Db(Context c){super(c,"sketch_components_v1.db",null,1);}
        public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE components(id TEXT PRIMARY KEY,request_sha TEXT NOT NULL,document TEXT NOT NULL,document_sha TEXT NOT NULL,category TEXT NOT NULL,metadata TEXT NOT NULL)");}
        public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Component migration required");}
    }
    private SketchComponentLibrary(Context c){context=c.getApplicationContext();helper=new Db(context);db=helper.getWritableDatabase();}
    public void close(){helper.close();}
    static synchronized JSONObject execute(Context context,String action,JSONObject args)throws Exception {
        try(SketchComponentLibrary library=new SketchComponentLibrary(context)){
            if("sketch_component_list".equals(action))return library.list(args);
            if("sketch_component_save".equals(action))return library.save(args);
            if("sketch_component_apply".equals(action))return library.apply(args);
            throw SketchDocument.bad("Unknown component action");
        }
    }
    private JSONObject list(JSONObject args)throws Exception {
        SketchDocument.fields(args,"category,offset,limit");String category=args.optString("category","");if(!category.isEmpty()&&!Arrays.asList(CATEGORIES).contains(category))throw SketchDocument.bad("Unknown component category");int offset=args.has("offset")?(int)SketchDocument.integer(args,"offset",0,1000000):0,limit=args.has("limit")?(int)SketchDocument.integer(args,"limit",1,128):64;
        JSONArray items=new JSONArray();boolean more=false;try(Cursor c=db.rawQuery("SELECT metadata FROM components"+(category.isEmpty()?"":" WHERE category=?")+" ORDER BY rowid DESC LIMIT ? OFFSET ?",category.isEmpty()?new String[]{String.valueOf(limit+1),String.valueOf(offset)}:new String[]{category,String.valueOf(limit+1),String.valueOf(offset)})){while(c.moveToNext()){if(items.length()==limit){more=true;break;}items.put(new JSONObject(c.getString(0)));}}
        return new JSONObject().put("components",items).put("nextOffset",offset+items.length()).put("more",more).put("categories",new JSONArray(CATEGORIES)).put("includesAutomaticProfessionalDrawings",false);
    }
    private JSONObject save(JSONObject args)throws Exception {
        SketchDocument.fields(args,"componentId,sketchId,expectedRevision,operationId,name,category,frameIndex,layerIds");String id=SketchDocument.id(args,"componentId"),sourceId=SketchDocument.id(args,"sketchId");SketchDocument.id(args,"operationId");SketchDocument.text(args,"name",160);String category=args.getString("category");if(!Arrays.asList(CATEGORIES).contains(category))throw SketchDocument.bad("Unknown component category");
        String fingerprint=SketchDocument.sha256(SketchDocument.canonical(args).getBytes(StandardCharsets.UTF_8));try(Cursor old=db.rawQuery("SELECT request_sha,metadata FROM components WHERE id=?",new String[]{id})){if(old.moveToFirst()){if(!fingerprint.equals(old.getString(0)))throw new IllegalStateException("SKETCH_COMPONENT_ALREADY_EXISTS: use a new component ID");return new JSONObject(old.getString(1));}}
        try(SketchProjectStore sketches=new SketchProjectStore(context)){
            JSONObject source=sketches.get(sourceId);SketchProjectStore.requireRevision(source,SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1));int frame=(int)SketchDocument.integer(args,"frameIndex",0,source.getInt("frameCount")-1);
            Set<String> selected=new HashSet<>();if(args.has("layerIds")){JSONArray ids=args.getJSONArray("layerIds");if(ids.length()<1||ids.length()>32)throw SketchDocument.bad("Select 1..32 component layers");for(int i=0;i<ids.length();i++){String layer=ids.getString(i);SketchDocument.layer(source,layer);if(!selected.add(layer))throw SketchDocument.bad("Duplicate component layer");}}
            JSONObject doc=SketchDocument.copy(source);doc.put("id","lib_"+fingerprint.substring(0,48)).put("name",args.getString("name")).put("frameCount",1).put("revision",1);doc.remove("paletteStyles");doc.remove("masterAssets");JSONArray layers=new JSONArray();Map<String,Integer> palette=SketchPaletteStyles.indexed(source);int objects=0;
            for(int i=0;i<source.getJSONArray("layers").length();i++){
                JSONObject original=source.getJSONArray("layers").getJSONObject(i);if(!selected.isEmpty()&&!selected.contains(original.getString("id"))||selected.isEmpty()&&!original.getBoolean("visible"))continue;JSONObject layer=SketchDocument.copy(original);layer.put("transform",SketchDocument.transformAt(original,frame)).put("keys",new JSONArray());JSONArray items=new JSONArray();
                for(int j=0;j<original.getJSONArray("objects").length();j++){
                    JSONObject object=original.getJSONArray("objects").getJSONObject(j);if(!SketchFrameRenderer.exposed(object,frame))continue;JSONObject cel="human".equals(object.getString("kind"))?SketchHumanAnimation.sampled(object,frame):"hand".equals(object.getString("kind"))?SketchHandAnimation.sampled(object,frame):SketchDocument.copy(object);cel.remove("masterLink");cel.put("frame",0).put("exposure",1).put("transform",SketchDocument.transformAt(object,frame)).put("keys",new JSONArray());if(cel.has("shapeKeys")){cel.put("commands",SketchShapeKeys.commandsAt(object,frame));cel.remove("shapeKeys");}if(cel.has("styleId")){cel.put("color",SketchPaletteStyles.colorString(SketchPaletteStyles.resolvedColor(object,palette)));cel.remove("styleId");}items.put(cel);objects++;
                }
                if(items.length()>0){layer.put("objects",items);layers.put(layer);}
            }
            if(layers.length()==0||objects==0)throw SketchDocument.bad("Selected frame has no component artwork");doc.put("layers",layers);SketchDocument.validate(doc);
            JSONObject selectedSource=SketchDocument.copy(doc).put("id",sourceId);sketches.copyOwnedDependencies(selectedSource,doc.getString("id"));
            String json=SketchDocument.canonical(doc),hash=SketchDocument.sha256(json.getBytes(StandardCharsets.UTF_8));JSONObject metadata=new JSONObject().put("ok",true).put("componentId",id).put("name",args.getString("name")).put("category",category).put("sourceSketchId",sourceId).put("sourceRevision",source.getLong("revision")).put("sourceFrame",frame).put("documentSha256",hash).put("layers",layers.length()).put("objects",objects).put("width",doc.getInt("width")).put("height",doc.getInt("height"));
            ContentValues row=new ContentValues();row.put("id",id);row.put("request_sha",fingerprint);row.put("document",json);row.put("document_sha",hash);row.put("category",category);row.put("metadata",metadata.toString());db.insertOrThrow("components",null,row);return metadata;
        }
    }
    private JSONObject apply(JSONObject args)throws Exception {
        SketchDocument.fields(args,"componentId,sketchId,expectedRevision,operationId,frameIndex,exposure");String component=SketchDocument.id(args,"componentId"),id=SketchDocument.id(args,"sketchId"),op=SketchDocument.id(args,"operationId");long revision=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1);
        String json=SQLiteLargeText.read(db,"SELECT document FROM components WHERE id=?",component),hash=SQLiteLargeText.read(db,"SELECT document_sha FROM components WHERE id=?",component);if(json==null)throw SketchDocument.bad("Unknown component");if(!hash.equals(SketchDocument.sha256(json.getBytes(StandardCharsets.UTF_8))))throw new IllegalStateException("SKETCH_COMPONENT_CHANGED");
        JSONObject doc=new JSONObject(json);SketchDocument.validate(doc);String fingerprint=SketchDocument.sha256(("component_apply\n"+SketchDocument.canonical(args)+"\n"+hash).getBytes(StandardCharsets.UTF_8));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            boolean replay=store.hasOperation(id,op);JSONObject target=store.get(id);if(!replay){SketchProjectStore.requireRevision(target,revision);requireCopyAllowed(store,id);}
            int frame=(int)SketchDocument.integer(args,"frameIndex",0,target.getInt("frameCount")-1),exposure=args.has("exposure")?(int)SketchDocument.integer(args,"exposure",1,target.getInt("frameCount")-frame):1;String prefix="cmp_"+fingerprint.substring(0,24);JSONArray layers=doc.getJSONArray("layers");int ordinal=0;
            for(int i=0;i<layers.length();i++){JSONObject layer=layers.getJSONObject(i);layer.put("id",prefix+"_l"+i);JSONArray objects=layer.getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject object=objects.getJSONObject(j);object.put("id",prefix+"_o"+(ordinal++)).put("frame",frame).put("exposure",exposure);if("paint".equals(object.getString("kind"))&&(doc.getInt("width")!=target.getInt("width")||doc.getInt("height")!=target.getInt("height")))throw SketchDocument.bad("Painted components require matching canvas dimensions");}}
            if(!replay){if(target.getJSONArray("layers").length()+layers.length()>SketchDocument.MAX_LAYERS)throw SketchDocument.bad("Component exceeds the sketch layer budget");store.copyOwnedDependencies(doc,id);}
            JSONObject inherited=SketchDocument.copy(doc);return store.mutatePrepared(id,op,revision,fingerprint,drawing->{requireCopyAllowed(store,id);JSONArray into=drawing.getJSONArray("layers");for(int i=0;i<layers.length();i++)into.put(SketchDocument.copy(layers.getJSONObject(i)));return drawing;},null,null,inherited);
        }
    }
    private static void requireCopyAllowed(SketchProjectStore store,String id)throws Exception {if(SketchDrawingPolicy.requiresOriginal(store.activeDrawingSession(id)))throw new IllegalStateException("SKETCH_ORIGINAL_DRAWING_REQUIRED: library placements are copied artwork");}
}
