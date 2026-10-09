package com.rezoxnemesis.videostudio;

import android.content.Context;
import java.io.InputStream;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared, introspectable wire schema for owner/agent editing. No arbitrary URI import. */
public final class EditorProtocol {
    public static final int SCHEMA_VERSION=6;
    private final JSONObject schema;
    private final ProjectStore store;
    private final EditorEngine editor;
    private final OwnerAccessPolicy access;
    public EditorProtocol(Context context,ProjectStore store){
        this.store=store;this.editor=new EditorEngine(store);this.access=new OwnerAccessPolicy(context,store);
        try(InputStream in=context.getAssets().open("editor-operations.json")){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            while((count=in.read(buffer))!=-1){if(bytes.size()+count>256*1024)throw new IllegalArgumentException("Editor schema is too large");bytes.write(buffer,0,count);}
            schema=new JSONObject(bytes.toString("UTF-8"));
        }catch(Exception error){throw new IllegalStateException("Editor schema is unavailable",error);}
    }
    public JSONObject describe(){try{return new JSONObject(schema.toString());}catch(Exception error){throw new IllegalStateException(error);}}
    public JSONObject execute(String action,JSONObject parameters)throws Exception{
        if(!access.allows(action,parameters))throw new SecurityException("Command is outside the current owner scope");
        String projectId=requiredString(parameters,"projectId",120);
        ProjectStore.Project current=store.get(projectId);if(current==null)throw new IllegalArgumentException("Project not found");
        if("project_query".equals(action)){
            current=ProjectStore.Project.fromJson(access.redactProject(current));
            String query=parameters.optString("query","graph");JSONObject result=receipt(current,"",query);
            if("graph".equals(query))result.put("project",current.toJson());
            else if("assets".equals(query))result.put("assets",current.toJson().getJSONArray("assets"));
            else if("timeline".equals(query)){result.put("tracks",current.toJson().getJSONArray("tracks"));result.put("clips",current.toJson().getJSONArray("clips"));result.put("markers",current.markers);}
            else if("snapshots".equals(query))result.put("snapshots",store.snapshots(projectId));
            else throw new IllegalArgumentException("Unknown project query");return result;
        }
        Object raw=parameters.opt("expectedRevision");
        if(!(raw instanceof Number)||!Double.isFinite(((Number)raw).doubleValue())||((Number)raw).doubleValue()!=((Number)raw).longValue()||((Number)raw).longValue()<1||((Number)raw).doubleValue()>9007199254740991d)
            throw new IllegalArgumentException("Expected project revision is required");
        long revision=((Number)raw).longValue();String commandId=requiredString(parameters,"commandId",120);
        if(commandId.length()<8)throw new IllegalArgumentException("Durable command ID requires at least 8 characters");
        if(parameters.toString().length()>65536)throw new IllegalArgumentException("Editor request exceeds 64 KiB");
        ProjectStore.Project result;
        if("editor_operation".equals(action)){
            String operation=parameters.getString("operation");JSONObject args=parameters.optJSONObject("args");if(args==null)args=new JSONObject();
            validateOperation(operation,args);result=editor.execute(projectId,revision,"agent",commandId,operation,args);
        }else if("editor_batch".equals(action)){
            JSONArray operations=parameters.getJSONArray("operations");
            if(operations.length()<1||operations.length()>100)throw new IllegalArgumentException("Batch requires 1–100 operations");
            for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);validateOperation(op.getString("operation"),op.getJSONObject("args"));}
            result=editor.executeBatch(projectId,revision,"agent",commandId,operations);
        }else if("editor_history".equals(action)){
            String operation=parameters.getString("operation");
            if("undo".equals(operation))result=store.undo(projectId,revision,commandId);
            else if("redo".equals(operation))result=store.redo(projectId,revision,commandId);
            else if("restore".equals(operation))result=store.restore(projectId,revision,requiredString(parameters,"snapshotId",120),commandId);
            else if("snapshot".equals(operation)){
                String id=store.snapshot(projectId,revision,parameters.optString("name","Snapshot r"+revision),commandId);
                JSONObject response=receipt(store.commandReceipt(projectId,commandId),commandId,operation);response.put("snapshotId",id);return response;
            }else throw new IllegalArgumentException("Unknown history operation");
        }else throw new IllegalArgumentException("Unknown shared editor action");
        JSONObject response=receipt(result,commandId,action);response.put("expectedRevision",revision);return response;
    }
    private JSONObject receipt(ProjectStore.Project p,String commandId,String operation)throws Exception{
        p=ProjectStore.Project.fromJson(access.redactProject(p));
        JSONObject out=new JSONObject();out.put("ok",true);out.put("projectId",p.id);out.put("revision",p.revision);out.put("commandId",commandId);
        out.put("schemaVersion",SCHEMA_VERSION);out.put("operation",operation);out.put("clipCount",p.clips.size());out.put("trackCount",p.tracks.size());out.put("durationMs",p.outputDurationMs());return out;
    }
    public void validateOperation(String operation,JSONObject args)throws Exception{
        JSONObject definition=schema.getJSONObject("operations").optJSONObject(operation);
        if(definition==null)throw new IllegalArgumentException("Unknown editor operation: "+operation);
        validateValue(definition,args,"arguments",0);
        if("set_property".equals(operation)||"set_keyframe".equals(operation))validateValue(schema.getJSONObject("properties").getJSONObject(args.getString("property")),args.get("value"),"property value",0);
    }
    private static String requiredString(JSONObject o,String key,int limit){
        Object value=o.opt(key);if(!(value instanceof String)||((String)value).trim().isEmpty()||((String)value).length()>limit)throw new IllegalArgumentException(key+" must be a bounded nonempty string");return (String)value;
    }
    private static void validateValue(JSONObject spec,Object value,String path,int depth)throws Exception{
        if(depth>12)throw new IllegalArgumentException("Editor arguments exceed nesting limit");String type=spec.optString("type");
        if("object".equals(type)){
            if(!(value instanceof JSONObject))throw new IllegalArgumentException(path+" must be an object");JSONObject o=(JSONObject)value,props=spec.optJSONObject("properties");if(props==null)props=new JSONObject();
            if(o.length()<spec.optInt("minProperties",0))throw new IllegalArgumentException(path+" is empty");JSONArray required=spec.optJSONArray("required");
            if(required!=null)for(int i=0;i<required.length();i++)if(!o.has(required.getString(i)))throw new IllegalArgumentException(path+" requires "+required.getString(i));
            Iterator<String> keys=o.keys();while(keys.hasNext()){String key=keys.next();JSONObject field=props.optJSONObject(key);if(field==null){if(!spec.optBoolean("additionalProperties",true))throw new IllegalArgumentException(path+" does not accept argument "+key);}else validateValue(field,o.get(key),path+"."+key,depth+1);}
        }else if("array".equals(type)){
            if(!(value instanceof JSONArray))throw new IllegalArgumentException(path+" must be an array");JSONArray a=(JSONArray)value;
            if(a.length()<spec.optInt("minItems",0)||a.length()>spec.optInt("maxItems",100))throw new IllegalArgumentException(path+" has invalid items");
            for(int i=0;i<a.length();i++)validateValue(spec.getJSONObject("items"),a.get(i),path+"[]",depth+1);
        }else if("string".equals(type)){
            if(!(value instanceof String)||((String)value).length()<spec.optInt("minLength",0)||((String)value).length()>spec.optInt("maxLength",5000))throw new IllegalArgumentException(path+" must be a bounded string");
            if(spec.has("pattern")&&!((String)value).matches(spec.getString("pattern")))throw new IllegalArgumentException(path+" has invalid format");
        }else if("integer".equals(type)||"number".equals(type)){
            if(!(value instanceof Number))throw new IllegalArgumentException(path+" must be numeric");double n=((Number)value).doubleValue();
            if(!Double.isFinite(n)||("integer".equals(type)&&(n!=Math.floor(n)||Math.abs(n)>9007199254740991d))||n<spec.optDouble("minimum",-Double.MAX_VALUE)||n>spec.optDouble("maximum",Double.MAX_VALUE))throw new IllegalArgumentException(path+" has invalid numeric value");
        }else if("boolean".equals(type)&&!(value instanceof Boolean))throw new IllegalArgumentException(path+" must be boolean");
        JSONArray choices=spec.optJSONArray("enum");if(choices!=null){boolean found=false;for(int i=0;i<choices.length();i++){Object allowed=choices.get(i);found|=allowed.equals(value)||(allowed instanceof Number&&value instanceof Number&&((Number)allowed).doubleValue()==((Number)value).doubleValue());}if(!found)throw new IllegalArgumentException(path+" has unsupported value");}
    }
}
