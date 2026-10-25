package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Exact authored Bézier node coordinates shared by artist and private ChatGPT.
 *
 * Read: one bounded page of source path handles, in the same normalized sketch
 * coordinates used by the human Nodes tool. Write: one precise coordinate pair,
 * never an untrusted replacement of the entire path or exported bitmap.
 *
 * All mutations still run under sketch_edit's existing revision, original
 * drawing policy, SQLite journaling, undo and layer-lock guards.
 */
final class SketchPathNodeOps {
    static final int MAX_NODE_PAGE=128;
    private SketchPathNodeOps(){}

    static JSONObject capabilities() throws Exception {
        return new JSONObject()
                .put("minimumNativeVersion","4.10.39")
                .put("readAction","sketch_path_nodes")
                .put("writeAction","sketch_edit")
                .put("operationType","move_path_node")
                .put("maximumNodesPerRead",MAX_NODE_PAGE)
                .put("coordinates","Normalized original path [0,1], not screen pixels")
                .put("commandKinds",new JSONArray(new String[]{"M","L","Q","C","Z"}))
                .put("shapeKeysArePreserved",true)
                .put("exactRevisionRequiredForMutation",true)
                .put("sourceMasterUnchanged",true)
                .put("galleryAccess",false)
                .put("frameAnimationQualityCertified",false);
    }

    /** Revision-pinned paged source geometry; no film frames or Gallery access. */
    static JSONObject inspect(JSONObject document,JSONObject query) throws Exception {
        SketchDocument.fields(query,
                "sketchId,expectedRevision,objectId,frameIndex,nodeOffset,nodeLimit");
        String sketchId=SketchDocument.id(query,"sketchId"),
                objectId=SketchDocument.id(query,"objectId");
        if(!sketchId.equals(document.getString("id")))
            throw SketchDocument.bad("Unknown selected Sketch Studio document");
        if(query.has("expectedRevision")&&SketchDocument.integer(query,"expectedRevision",
                1,Long.MAX_VALUE-1)!=document.getLong("revision"))
            throw new IllegalStateException("SKETCH_SOURCE_CHANGED: reload the source before inspecting handles");
        JSONObject source=path(document,objectId);
        int frame=(int)SketchDocument.integer(query,"frameIndex",0,document.getInt("frameCount")-1);
        int offset=query.has("nodeOffset")?(int)SketchDocument.integer(query,
                "nodeOffset",0,SketchDocument.MAX_POINTS):0;
        int limit=query.has("nodeLimit")?(int)SketchDocument.integer(query,
                "nodeLimit",1,MAX_NODE_PAGE):48;
        JSONArray path=SketchShapeKeys.commandsAt(source,frame);
        JSONArray nodes=new JSONArray();
        int total=0;
        for(int commandIndex=0;commandIndex<path.length();commandIndex++){
            JSONArray command=path.getJSONArray(commandIndex);
            String kind=command.getString(0);
            for(int pairIndex=0;pairIndex<(command.length()-1)/2;pairIndex++){
                if(total>=offset&&nodes.length()<limit){
                    nodes.put(new JSONObject()
                            .put("commandIndex",commandIndex)
                            .put("pairIndex",pairIndex)
                            .put("kind",kind)
                            .put("role",role(kind,pairIndex))
                            .put("x",command.getDouble(1+2*pairIndex))
                            .put("y",command.getDouble(2+2*pairIndex)));
                }
                total++;
            }
        }
        if(offset>total)
            throw SketchDocument.bad("Node page offset exceeds the existing path handles");
        JSONObject layer=SketchDocument.owner(document,objectId);
        return new JSONObject()
                .put("schema","videostudio.sketch-path-nodes.v1")
                .put("sketchId",sketchId)
                .put("sourceRevision",document.getLong("revision"))
                .put("objectId",objectId)
                .put("layerId",layer.getString("id"))
                .put("layerLocked",layer.getBoolean("locked"))
                .put("frameIndex",frame)
                .put("animated",SketchShapeKeys.animated(source))
                .put("nodeOffset",offset)
                .put("returnedNodes",nodes.length())
                .put("totalNodes",total)
                .put("nextNodeOffset",offset+nodes.length()<total?offset+nodes.length():JSONObject.NULL)
                .put("nodes",nodes)
                .put("readOnly",true)
                .put("sourceBytesCopied",false);
    }

    /** Pure preflight for one exact editable original knot/control point. */
    static JSONObject plan(JSONObject document,JSONObject op) throws Exception {
        if(document==null||op==null)throw SketchDocument.bad("Source sketch and node edit are required");
        SketchDocument.fields(op,"type,objectId,frameIndex,commandIndex,pairIndex,x,y");
        if(!"move_path_node".equals(op.optString("type")))
            throw SketchDocument.bad("Use move_path_node for the exact native knot edit");
        String id=SketchDocument.id(op,"objectId");
        JSONObject object=path(document,id);
        JSONObject layer=SketchDocument.owner(document,id);
        SketchEditOps.requireUnlocked(layer);
        if(!layer.getBoolean("visible"))
            throw SketchDocument.bad("Reveal the authored layer before moving a visible node");
        if(object.has("masterLink"))
            throw SketchDocument.bad("Linked master handles require an explicit master edit or detach");
        int frame=(int)SketchDocument.integer(op,"frameIndex",0,document.getInt("frameCount")-1);
        if(!SketchFrameRenderer.exposed(object,frame))
            throw SketchDocument.bad("Node is not exposed at requested frame; select an actual source cel");
        JSONArray sampled=SketchShapeKeys.commandsAt(object,frame);
        int commandIndex=(int)SketchDocument.integer(op,"commandIndex",0,sampled.length()-1);
        JSONArray command=sampled.getJSONArray(commandIndex);
        int pairs=(command.length()-1)/2;
        if(pairs==0)throw SketchDocument.bad("Z closes the path and has no editable coordinate");
        int pairIndex=(int)SketchDocument.integer(op,"pairIndex",0,pairs-1);
        double x=SketchDocument.number(op,"x",0,1),
                y=SketchDocument.number(op,"y",0,1);
        int xSlot=1+2*pairIndex,ySlot=xSlot+1;
        double oldX=command.getDouble(xSlot),oldY=command.getDouble(ySlot);
        if(Math.abs(oldX-x)<1e-8&&Math.abs(oldY-y)<1e-8)
            throw SketchDocument.bad("This path handle already has the requested coordinates");
        return new JSONObject()
                .put("objectId",id)
                .put("layerId",layer.getString("id"))
                .put("frameIndex",frame)
                .put("commandIndex",commandIndex)
                .put("pairIndex",pairIndex)
                .put("kind",command.getString(0))
                .put("role",role(command.getString(0),pairIndex))
                .put("sourceX",oldX).put("sourceY",oldY)
                .put("targetX",x).put("targetY",y)
                .put("createsOrReplacesShapeKey",SketchShapeKeys.animated(object))
                .put("editsBasePathForAllHeldFrames",!SketchShapeKeys.animated(object))
                .put("readOnly",true).put("galleryAccess",false);
    }

    /** Perform only through a sketch_edit transaction; no standalone persistence. */
    static void apply(JSONObject document,JSONObject op) throws Exception {
        JSONObject plan=plan(document,op);
        JSONObject object=SketchDocument.object(document,plan.getString("objectId"));
        int frame=plan.getInt("frameIndex");
        JSONArray newCommands=SketchShapeKeys.commandsAt(object,frame);
        JSONArray command=newCommands.getJSONArray(plan.getInt("commandIndex"));
        int x=1+plan.getInt("pairIndex")*2;
        command.put(x,plan.getDouble("targetX"));
        command.put(x+1,plan.getDouble("targetY"));
        SketchDocument.validateCommands(newCommands);
        if(SketchShapeKeys.animated(object)){
            // The native shape-key editor retains the existing interpolation
            // and 1-to-1 command topology for every original animated frame.
            JSONObject edit=SketchShapeKeys.editOperation(object,frame,newCommands);
            SketchShapeKeys.apply(object,edit,document.getInt("frameCount"));
        }else{
            object.put("commands",newCommands);
        }
    }

    private static JSONObject path(JSONObject document,String id) throws Exception {
        JSONObject source=SketchDocument.object(document,id);
        if(!"path".equals(source.optString("kind")))
            throw SketchDocument.bad("Only an original editable Bézier path has these nodes");
        return source;
    }

    private static String role(String kind,int pairIndex){
        if("C".equals(kind))return pairIndex==2?"end":"control"+(pairIndex+1);
        if("Q".equals(kind))return pairIndex==1?"end":"control";
        return "anchor";
    }
}
