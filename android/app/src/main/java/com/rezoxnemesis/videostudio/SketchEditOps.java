package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** The only document operation facade, used unchanged by touch and MCP. */
public final class SketchEditOps {
    private SketchEditOps() {}

    public static JSONObject execute(Context context,String action,JSONObject args) throws Exception {
        return executeWithSession(context,action,args,null);
    }
    /** Trusted importer boundary; the session pin is never accepted from public JSON. */
    static JSONObject executeLiveEdit(Context context,JSONObject args,String expectedSessionId)throws Exception {
        SketchDocument.id(new JSONObject().put("sessionId",expectedSessionId),"sessionId");
        return executeWithSession(context,"sketch_edit",args,expectedSessionId);
    }
    private static JSONObject executeWithSession(Context context,String action,JSONObject args,String expectedSessionId)throws Exception {
        JSONObject result=executeInternal(context,action,args,expectedSessionId);result.put("ok",true);
        // A hidden guide is preparatory data, not a request to change the
        // foreground drawing. Actual edits and live sessions still follow.
        boolean hiddenGuideImport="sketch_import_reference".equals(action)&&!args.optBoolean("guideVisible",true);
        if(result.has("sketchId")&&!hiddenGuideImport&&!java.util.Arrays.asList("sketch_get","sketch_preview","sketch_review","sketch_quality_audit","sketch_list","sketch_live_events","sketch_inspect","sketch_xsheet","sketch_paint_journal","sketch_sample_color").contains(action)){
            android.content.SharedPreferences.Editor signal=context.getSharedPreferences("sketch_live_workspace",Context.MODE_PRIVATE).edit().putString("sketchId",result.getString("sketchId")).putLong("signal",System.nanoTime());
            // Edits do not carry a page argument. Resolve the actual persisted
            // membership so following a late cel never jumps back to page zero.
            JSONObject membership;try(SketchBookStore books=new SketchBookStore(context)){membership=books.pageForSketch(result.getString("sketchId"));}
            signal.putString("bookId",membership==null?"":membership.getString("bookId"));
            signal.putInt("page",membership==null?0:membership.getInt("page"));signal.apply();
        }
        return result;
    }
    private static JSONObject executeInternal(Context context, String action, JSONObject args,String expectedSessionId) throws Exception {
        if (args == null || args.toString().getBytes(StandardCharsets.UTF_8).length > 512 * 1024)
            throw SketchDocument.bad("Request exceeds 512 KiB; split the edit into revision-bound batches");
        if("sketch_capabilities".equals(action)){SketchDocument.fields(args,"");return SketchCapabilities.get();}
        if("sketch_live_begin".equals(action))return SketchLiveDrawing.begin(context,args);
        if("sketch_live_end".equals(action)){SketchDocument.fields(args,"sketchId,sessionId,expectedRevision");try(SketchProjectStore store=new SketchProjectStore(context)){return store.endLiveSession(SketchDocument.id(args,"sketchId"),SketchDocument.id(args,"sessionId"),SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1));}}
        if(action.startsWith("sketch_component_"))return SketchComponentLibrary.execute(context,action,args);
        if(action.startsWith("sketch_book_"))return SketchBookStore.execute(context,action,args);
        try (SketchProjectStore store = new SketchProjectStore(context)) {
            if ("sketch_list".equals(action)) { SketchDocument.fields(args, ""); return new JSONObject().put("sketches", store.list()); }
            String id = SketchDocument.id(args, "sketchId");
            SketchDrawingPolicy.requireAction(store.activeDrawingSession(id),action,args);
            if("sketch_paint_journal".equals(action)||"sketch_sample_color".equals(action))return SketchPaintOps.query(context,store,action,args);
            if("sketch_quality_audit".equals(action)){
                SketchDocument.fields(args,"sketchId,expectedRevision,frameOffset,frameLimit,frameStep,regions,motionRequired,minChangedFraction,minNewCels");
                JSONObject doc=store.get(id);
                return SketchQualityAudit.inspect(context,doc,args);
            }
            if("sketch_inspect".equals(action)){
                SketchDocument.fields(args,"sketchId,expectedRevision,frameOffset,frameLimit,characterLayerIds");JSONObject doc=store.get(id);if(args.has("expectedRevision"))SketchProjectStore.requireRevision(doc,SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1));return SketchInspection.inspect(doc,args);
            }
            if("sketch_xsheet".equals(action)){
                SketchDocument.fields(args,"sketchId,expectedRevision,frameOffset,frameLimit");
                JSONObject doc=store.get(id);
                if(args.has("expectedRevision"))SketchProjectStore.requireRevision(
                        doc,SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1));
                int offset=args.has("frameOffset")?(int)SketchDocument.integer(
                        args,"frameOffset",0,doc.getInt("frameCount")-1):0;
                int limit=args.has("frameLimit")?(int)SketchDocument.integer(
                        args,"frameLimit",1,SketchXsheetProjection.MAX_ROWS):12;
                return SketchXsheetProjection.project(doc,offset,limit);
            }
            if("sketch_live_events".equals(action)){
                SketchDocument.fields(args,"sketchId,afterSequence,limit,operationOffset,operationLimit,eventSequence");
                store.get(id);
                long after=SketchDocument.integer(args,"afterSequence",0,Long.MAX_VALUE-1);
                int offset=args.has("operationOffset")?(int)SketchDocument.integer(args,"operationOffset",0,32768):0;
                int maximum=args.has("operationLimit")?(int)SketchDocument.integer(args,"operationLimit",1,64):64;
                long pinned=args.has("eventSequence")?SketchDocument.integer(args,"eventSequence",1,Long.MAX_VALUE-1):0;
                if(offset>0&&pinned==0)throw SketchDocument.bad("A live operation offset requires its eventSequence cursor");
                int requestedLimit=args.has("limit")?(int)SketchDocument.integer(args,"limit",1,8):4;
                JSONObject batch=store.liveEvents(id,after,requestedLimit);
                if(!args.has("operationOffset")&&!args.has("operationLimit")&&pinned==0&&batch.toString().getBytes(StandardCharsets.UTF_8).length<=500*1024)return batch.put("operationPagination",false);
                JSONArray events=batch.getJSONArray("events");
                if(pinned>0&&(events.length()==0||events.getJSONObject(0).getLong("sequence")!=pinned))throw new IllegalStateException("SKETCH_EVENT_CURSOR_EXPIRED: requested live event is no longer retained; inspect the current drawing before restarting");
                if(events.length()==0)return batch;
                JSONObject event=events.getJSONObject(0);JSONArray all=event.getJSONArray("operations"),page=new JSONArray();
                if(offset>all.length())throw SketchDocument.bad("Live operation cursor is outside the event");
                int bytes=0,next=offset;
                while(next<all.length()&&page.length()<maximum){
                    JSONObject operation=all.getJSONObject(next);int size=operation.toString().getBytes(StandardCharsets.UTF_8).length;
                    if(size>500*1024)throw new IllegalStateException("SKETCH_EVENT_OPERATION_BUDGET: inspect the object's geometry using sketch_get pages");
                    if(bytes+size>500*1024&&page.length()>0)break;
                    page.put(operation);bytes+=size;next++;
                }
                batch.put("events",new JSONArray().put(event));
                event.put("operations",page).put("operationOffset",offset).put("operationCount",all.length()).put("nextOperationOffset",next).put("moreOperations",next<all.length());
                JSONObject cursor=new JSONObject().put("afterSequence",next<all.length()?after:event.getLong("sequence")).put("operationOffset",next<all.length()?next:0);
                if(next<all.length())cursor.put("eventSequence",event.getLong("sequence"));
                batch.put("operationPagination",true).put("nextCursor",cursor);
                if(batch.toString().getBytes(StandardCharsets.UTF_8).length>512*1024-32)throw new IllegalStateException("SKETCH_EVENT_OPERATION_BUDGET: request a smaller operation page");
                return batch;
            }
            if ("sketch_get".equals(action)) {
                SketchDocument.fields(args, "sketchId,expectedRevision,layerId,objectId,pointOffset,pointLimit,commandOffset,commandLimit,objectOffset,objectLimit,shapeFrame"); JSONObject doc = store.get(id);
                if(args.has("shapeFrame")&&!args.has("objectId"))throw SketchDocument.bad("Choose an object for sampled shape geometry");
                if (args.has("expectedRevision")) SketchProjectStore.requireRevision(doc, SketchDocument.integer(args, "expectedRevision", 1, Long.MAX_VALUE - 1));
                JSONObject result = new JSONObject().put("sketchId", id).put("revision", doc.getLong("revision"))
                        .put("documentSha256", SketchDocument.sha256(SketchDocument.canonical(doc).getBytes(StandardCharsets.UTF_8)));
                if (args.has("objectId")) {
                    JSONObject object = SketchDocument.copy(SketchDocument.object(doc, SketchDocument.id(args, "objectId")));
                    if(args.has("shapeFrame")){
                        if(!"path".equals(object.getString("kind")))throw SketchDocument.bad("Sampled shape geometry requires a path");
                        int shapeFrame=(int)SketchDocument.integer(args,"shapeFrame",0,doc.getInt("frameCount")-1);
                        object.put("commands",SketchShapeKeys.commandsAt(object,shapeFrame));result.put("shapeFrame",shapeFrame);
                    }
                    if(object.has("shapeKeys"))object.put("shapeKeys",SketchShapeKeys.metadata(object));
                    if (args.has("layerId") && !SketchDocument.owner(doc, object.getString("id")).getString("id").equals(SketchDocument.id(args, "layerId")))
                        throw SketchDocument.bad("Object is not owned by the selected layer");
                    result.put("layerId", SketchDocument.owner(doc, object.getString("id")).getString("id"));
                    if (object.has("points")) {
                        JSONArray points = object.getJSONArray("points");
                        int offset = args.has("pointOffset") ? (int) SketchDocument.integer(args, "pointOffset", 0, points.length()) : 0;
                        int limit = args.has("pointLimit") ? (int) SketchDocument.integer(args, "pointLimit", 1, 512) : 512;
                        JSONArray page = new JSONArray(); for (int i = offset; i < Math.min(points.length(), offset + limit); i++) page.put(points.getJSONArray(i));
                        object.put("points", page); result.put("pointCount", points.length()).put("pointOffset", offset).put("morePoints", offset + page.length() < points.length());
                    }
                    if(object.has("commands")){JSONArray commands=object.getJSONArray("commands");int offset=args.has("commandOffset")?(int)SketchDocument.integer(args,"commandOffset",0,commands.length()):0,max=args.has("commandLimit")?(int)SketchDocument.integer(args,"commandLimit",1,512):512;JSONArray page=new JSONArray();for(int n=offset;n<Math.min(commands.length(),offset+max);n++)page.put(commands.getJSONArray(n));object.put("commands",page);result.put("commandCount",commands.length()).put("commandOffset",offset).put("moreCommands",offset+page.length()<commands.length());}
                    result.put("object", object);
                } else if (args.has("layerId")) {
                    JSONObject layer = SketchDocument.copy(SketchDocument.layer(doc, SketchDocument.id(args, "layerId")));
                    JSONArray objects = layer.getJSONArray("objects");
                    int offset = args.has("objectOffset") ? (int) SketchDocument.integer(args, "objectOffset", 0, objects.length()) : 0;
                    int limit = args.has("objectLimit") ? (int) SketchDocument.integer(args, "objectLimit", 1, 128) : 128;
                    JSONArray page = new JSONArray();
                    for (int i = offset; i < Math.min(objects.length(), offset + limit); i++) {
                        JSONObject object = objects.getJSONObject(i); page.put(new JSONObject().put("id", object.getString("id")).put("kind", object.getString("kind"))
                                .put("frame", object.getInt("frame")).put("exposure", object.getInt("exposure"))
                                .put("pointCount", object.has("points") ? object.getJSONArray("points").length() : 0));
                    }
                    layer.put("objects", page); result.put("layer", layer).put("objectCount", objects.length()).put("objectOffset", offset).put("moreObjects", offset + page.length() < objects.length());
                } else {
                    JSONObject header = SketchDocument.copy(doc); JSONArray summaries = new JSONArray(); JSONArray layers = doc.getJSONArray("layers");
                    for (int i = 0; i < layers.length(); i++) {
                        JSONObject layer = SketchDocument.copy(layers.getJSONObject(i)); int count = layer.getJSONArray("objects").length(); layer.remove("objects");
                        JSONArray keyFrames = new JSONArray(); JSONArray keys = layer.getJSONArray("keys");
                        for (int k = 0; k < keys.length(); k++) keyFrames.put(keys.getJSONObject(k).getInt("frame")); layer.remove("keys");
                        summaries.put(layer.put("objectCount", count).put("keyFrames", keyFrames));
                    }
                    header.put("layers", summaries); result.put("document", header).put("geometryPaged", true);
                }
                if (result.toString().getBytes(StandardCharsets.UTF_8).length > 512 * 1024) throw new IllegalStateException("SKETCH_QUERY_BUDGET: request a smaller geometry page");
                return result;
            }
            if ("sketch_review".equals(action)){
                SketchDocument.fields(args,"sketchId,expectedRevision,frameIndex,crop,focus,detail");
                JSONObject doc=store.get(id);
                if(args.has("expectedRevision"))SketchProjectStore.requireRevision(
                        doc,SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1));
                return SketchVisualReview.inspect(context,doc,args);
            }
            if ("sketch_preview".equals(action)) {
                SketchDocument.fields(args, "sketchId,expectedRevision,frameIndex,maxDimension,crop");
                long observed=store.revision(id);int requestedFrame=args.has("frameIndex")?(int)SketchDocument.integer(args,"frameIndex",0,SketchDocument.MAX_FRAMES-1):0;
                int requestedDimension=args.has("maxDimension")?(int)SketchDocument.integer(args,"maxDimension",128,720):720;
                JSONObject requestedCrop=args.optJSONObject("crop");if(args.has("crop")){requestedCrop=args.getJSONObject("crop");SketchDocument.validateBounds(requestedCrop);}
                if(observed>0&&args.has("expectedRevision")&&SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1)!=observed)throw new IllegalStateException("SKETCH_REVISION_CONFLICT: source drawing changed");
                String cacheKey=SketchPreviewCache.key(context,id,observed,requestedFrame,requestedDimension,requestedCrop);JSONObject cached=SketchPreviewCache.get(store,id,cacheKey);if(cached!=null)return cached;
                JSONObject doc = store.get(id);
                if (args.has("expectedRevision")) SketchProjectStore.requireRevision(doc, SketchDocument.integer(args, "expectedRevision", 1, Long.MAX_VALUE - 1));
                int frame = args.has("frameIndex") ? (int) SketchDocument.integer(args, "frameIndex", 0, doc.getInt("frameCount") - 1) : 0;
                int dimension = args.has("maxDimension") ? (int) SketchDocument.integer(args, "maxDimension", 128, 720) : 720;
                int canvasWidth=doc.getInt("width"),canvasHeight=doc.getInt("height");
                int left=0,top=0,right=canvasWidth,bottom=canvasHeight;
                if(args.has("crop")){
                    JSONObject crop=args.getJSONObject("crop");SketchDocument.validateBounds(crop);
                    left=(int)Math.floor(crop.getDouble("x")*canvasWidth);top=(int)Math.floor(crop.getDouble("y")*canvasHeight);
                    right=Math.min(canvasWidth,Math.max(left+1,(int)Math.ceil((crop.getDouble("x")+crop.getDouble("width"))*canvasWidth)));
                    bottom=Math.min(canvasHeight,Math.max(top+1,(int)Math.ceil((crop.getDouble("y")+crop.getDouble("height"))*canvasHeight)));
                }
                try (SketchFrameRenderer renderer = new SketchFrameRenderer(context, doc)) {
                    android.graphics.Bitmap bitmap;
                    if(args.has("crop")){
                        double zoom=dimension/(double)Math.max(right-left,bottom-top);
                        bitmap=android.graphics.Bitmap.createBitmap(Math.max(1,(int)Math.round((right-left)*zoom)),Math.max(1,(int)Math.round((bottom-top)*zoom)),android.graphics.Bitmap.Config.ARGB_8888);
                        try{
                            android.graphics.Canvas viewport=new android.graphics.Canvas(bitmap);viewport.scale((float)zoom,(float)zoom);viewport.translate(-left,-top);renderer.draw(viewport,frame,true);
                        }catch(Exception error){bitmap.recycle();throw error;}
                    }else bitmap=renderer.render(frame,true,dimension,null);
                    try {
                        byte[] bytes;
                        while (true) {
                            double ratio = Math.min(1, dimension / (double) Math.max(bitmap.getWidth(), bitmap.getHeight()));
                            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bitmap,
                                    Math.max(1, (int) Math.round(bitmap.getWidth() * ratio)), Math.max(1, (int) Math.round(bitmap.getHeight() * ratio)), true);
                            if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled; }
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Sketch preview encoding failed");
                            bytes = out.toByteArray(); if (bytes.length <= 1024 * 1024) break;
                            dimension = Math.max(128, dimension * 3 / 4);
                            if (Math.max(bitmap.getWidth(), bitmap.getHeight()) <= 128) throw new IllegalStateException("SKETCH_PREVIEW_BUDGET: PNG exceeds 1 MiB");
                        }
                        JSONObject result=new JSONObject().put("previewCached",false).put("sketchId", id).put("revision", doc.getLong("revision")).put("frameIndex", frame)
                                .put("canvasWidth",canvasWidth).put("canvasHeight",canvasHeight)
                                .put("sourcePixelBounds",new JSONObject().put("x",left).put("y",top).put("width",right-left).put("height",bottom-top))
                                .put("documentSha256", SketchDocument.sha256(SketchDocument.canonical(doc).getBytes(StandardCharsets.UTF_8)))
                                .put("renderer", "android-bitmap-canvas").put("sketchPreviewImage", new JSONObject().put("mimeType", "image/png")
                                        .put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                                        .put("width", bitmap.getWidth()).put("height", bitmap.getHeight()).put("sha256", SketchDocument.sha256(bytes)));
                        SketchPreviewCache.put(SketchPreviewCache.key(context,id,doc.getLong("revision"),frame,requestedDimension,requestedCrop),result,doc);return result;
                    } finally { bitmap.recycle(); }
                }
            }
            String op = SketchDocument.id(args, "operationId");
            String fingerprint = SketchDocument.sha256((action + "\n" + SketchDocument.canonical(args)).getBytes(StandardCharsets.UTF_8));
            if ("sketch_create".equals(action)) {
                SketchDocument.fields(args, "sketchId,operationId,name,width,height,fps,frameCount,background"); return store.create(args, fingerprint);
            }
            long revision = SketchDocument.integer(args, "expectedRevision", 1, Long.MAX_VALUE - 2);
            switch (action) {
                case "sketch_edit":
                    SketchDocument.fields(args, "sketchId,operationId,expectedRevision,operations");
                    JSONArray operations = args.getJSONArray("operations");
                    if (operations.length() < 1 || operations.length() > 64) throw SketchDocument.bad("Use 1..64 operations in an atomic batch");
                    SketchPaintOps.validatePublicOperations(operations);
                    if(SketchPaintOps.containsPaint(operations)||SketchPaintOps.containsDependencyCopies(operations)){
                        if(expectedSessionId!=null)throw SketchDocument.bad("Session-bound drawing imports accept vector operations only");
                        return SketchPaintOps.executeEdit(context,store,id,op,revision,fingerprint,operations);
                    }
                    JSONArray visual=new JSONArray();boolean additions=true;for(int n=0;n<operations.length();n++)if(!"add_object".equals(operations.getJSONObject(n).optString("type")))additions=false;final boolean appendOnly=additions;
                    return store.mutate(id, op, revision, fingerprint, doc -> {
                        if(expectedSessionId!=null){
                            JSONObject active=store.activeDrawingSession(id);
                            if(active==null||!expectedSessionId.equals(active.getString("sessionId")))
                                throw new IllegalStateException("SKETCH_LIVE_SESSION_ENDED: drawing import stopped after session takeover");
                        }
                        SketchDrawingPolicy.requireOperations(store.activeDrawingSession(id),operations);
                        // For pure additions, a valid final document implies every growing subset
                        // is valid. Store.mutate validates once before commit/history/live events.
                        for (int i=0;i<operations.length();i++){JSONObject operation=operations.getJSONObject(i);
                            if(appendOnly){appendObject(doc,operation);visual.put(operation);continue;}
                            if("symmetry_stroke".equals(operation.optString("type"))){JSONArray expanded=SketchSymmetry.apply(doc,operation);for(int j=0;j<expanded.length();j++)visual.put(SketchDocument.copy(expanded.getJSONObject(j)));}
                            else if("trace_reference".equals(operation.optString("type"))){JSONObject layer=SketchDocument.layer(doc,operation.getString("layerId"));int before=layer.getJSONArray("objects").length();SketchReferenceTrace.apply(store,doc,operation);for(int j=before;j<layer.getJSONArray("objects").length();j++)visual.put(new JSONObject().put("type","add_object").put("layerId",layer.getString("id")).put("object",layer.getJSONArray("objects").getJSONObject(j)));}
                            else{apply(doc,operation);visual.put(operation);}
                        }
                        return doc;
                    },visual);
                case "sketch_undo": case "sketch_redo":
                    SketchDocument.fields(args, "sketchId,operationId,expectedRevision");
                    return store.historyMove(id, op, revision, fingerprint, "sketch_redo".equals(action));
                case "sketch_import_reference":
                    SketchDocument.fields(args, "sketchId,operationId,expectedRevision,projectId,sourceProjectUpdatedAt,assetId,layerId,objectId,frame,exposure,bounds,guideVisible");
                    if(args.has("guideVisible"))SketchDocument.bool(args,"guideVisible");
                    ProjectStore projects = new ProjectStore(context);
                    JSONArray importedVisual=new JSONArray();
                    return projects.guardedProject(SketchDocument.id(args, "projectId"), SketchDocument.integer(args, "sourceProjectUpdatedAt", 0, Long.MAX_VALUE), project ->
                        store.mutate(id, op, revision, fingerprint, doc -> {
                            SketchDrawingPolicy.requireAction(store.activeDrawingSession(id),action,args);
                            JSONObject layer = SketchDocument.layer(doc, SketchDocument.id(args, "layerId")); requireUnlocked(layer);
                            ProjectStore.Asset asset = project.asset(SketchDocument.id(args, "assetId"));
                            // Validate the future image geometry and document before copying any bytes.
                            JSONObject referencePlaceholder = new JSONObject().put("sha256", new String(new char[64]).replace('\0', '0'))
                                    .put("file", new String(new char[64]).replace('\0', '0') + ".png").put("width", 1).put("height", 1)
                                    .put("sourceProjectId", project.id).put("sourceAssetId", SketchDocument.id(args, "assetId"));
                            JSONObject object = completeObject(new JSONObject().put("id", SketchDocument.id(args, "objectId")).put("kind", "image")
                                    .put("frame", args.has("frame") ? args.get("frame") : 0)
                                    .put("exposure", args.has("exposure") ? args.get("exposure") : doc.getInt("frameCount"))
                                    .put("bounds", args.has("bounds") ? args.getJSONObject("bounds") : new JSONObject().put("x", 0).put("y", 0).put("width", 1).put("height", 1))
                                    .put("transform",SketchDocument.transform().put("opacity",args.optBoolean("guideVisible",true)?1:0))
                                    .put("reference", referencePlaceholder));
                            layer.getJSONArray("objects").put(object); SketchDocument.validate(doc);
                            JSONObject reference=store.importReference(id, project, asset);object.put("reference", reference);
                            if(!args.has("bounds")){double imageRatio=reference.getDouble("width")/reference.getDouble("height"),canvasRatio=doc.getDouble("width")/doc.getDouble("height");double bw=imageRatio>canvasRatio?1:imageRatio/canvasRatio,bh=imageRatio>canvasRatio?canvasRatio/imageRatio:1;object.put("bounds",new JSONObject().put("x",(1-bw)/2).put("y",(1-bh)/2).put("width",bw).put("height",bh));}
                            importedVisual.put(new JSONObject().put("type","add_object").put("layerId",layer.getString("id")).put("object",SketchDocument.copy(object)));
                            return doc;
                        },importedVisual));
                default: throw SketchDocument.bad("Unsupported sketch action: " + action);
            }
        }
    }

    private static void appendObject(JSONObject doc,JSONObject op)throws Exception {
        SketchDocument.fields(op,"type,layerId,object");JSONObject layer=SketchDocument.layer(doc,SketchDocument.id(op,"layerId"));requireUnlocked(layer);layer.getJSONArray("objects").put(completeObject(op.getJSONObject("object")));
    }
    static void apply(JSONObject doc, JSONObject op) throws Exception {
        String type = op.getString("type");
        switch (type) {
            case "add_layer": {
                SketchDocument.fields(op, "type,layer"); JSONObject layer = SketchDocument.copy(op.getJSONObject("layer"));
                SketchDocument.fields(layer, "id,name,visible,locked,opacity,transform,keys,objects,blendMode");
                if (!layer.has("name")) layer.put("name", "Layer"); if (!layer.has("visible")) layer.put("visible", true);
                if (!layer.has("locked")) layer.put("locked", false); if (!layer.has("opacity")) layer.put("opacity", 1);
                if (!layer.has("transform")) layer.put("transform", SketchDocument.transform()); if (!layer.has("keys")) layer.put("keys", new JSONArray());
                if (!layer.has("objects")) layer.put("objects", new JSONArray());
                doc.getJSONArray("layers").put(layer); break;
            }
            case "update_layer": {
                SketchDocument.fields(op, "type,layerId,patch"); JSONObject layer = SketchDocument.layer(doc, SketchDocument.id(op, "layerId"));
                JSONObject patch = op.getJSONObject("patch"); SketchDocument.fields(patch, "name,visible,locked,opacity,blendMode"); merge(layer, patch); break;
            }
            case "delete_layer": {
                SketchDocument.fields(op, "type,layerId"); String id = SketchDocument.id(op, "layerId"); requireUnlocked(SketchDocument.layer(doc, id));
                remove(doc.getJSONArray("layers"), id); break;
            }
            case "reorder_layer": {
                SketchDocument.fields(op, "type,layerId,index"); JSONArray layers = doc.getJSONArray("layers");
                int index = (int) SketchDocument.integer(op, "index", 0, layers.length() - 1);
                JSONObject selected = SketchDocument.layer(doc, SketchDocument.id(op, "layerId")); requireUnlocked(selected);
                remove(layers, selected.getString("id")); JSONArray reordered = new JSONArray();
                for (int i = 0, j = 0; i <= layers.length(); i++) reordered.put(i == index ? selected : layers.getJSONObject(j++));
                doc.put("layers", reordered); break;
            }
            case "consolidate_path_runs": {
                SketchTraceConsolidator.apply(doc,op);break;
            }
            case "add_object": {
                appendObject(doc,op);break;
            }
            case "add_eye_animation": {
                SketchEyeAnimation.apply(doc,op);break;
            }
            case "add_mouth_animation": {
                SketchMouthAnimation.apply(doc,op);break;
            }
            case "add_face_animation": {
                SketchFaceAnimation.apply(doc,op);break;
            }
            case "add_motion_study": {
                SketchMotionStudies.apply(doc,op);break;
            }
            case "add_solid_collision": {
                SketchSolidCollision.apply(doc,op);break;
            }
            case "add_solid": {
                SketchSolidOps.apply(doc,op);break;
            }
            case "symmetry_stroke": {
                SketchSymmetry.apply(doc,op);break;
            }
            case "paint_stroke": {
                SketchPaintOps.applyPreparedStroke(doc,op);break;
            }
            case "update_object": {
                SketchDocument.fields(op, "type,objectId,patch"); String id = SketchDocument.id(op, "objectId"); requireUnlocked(SketchDocument.owner(doc, id));
                JSONObject patch = op.getJSONObject("patch"); SketchDocument.fields(patch, "color,styleId,width,fill,points,bounds,frame,exposure,brush,commands,shapeKeys,gradient,referenceCrop,text,fontFamily,fontSize,fontStyle,textAlign,letterSpacing");
                JSONObject target=SketchDocument.object(doc,id);
                // An explicit direct recolor is a deliberate opt-out of the linked palette.
                if(patch.has("color")&&!patch.has("styleId"))target.remove("styleId");
                merge(target, patch);
                if(patch.has("shapeKeys"))target.put("shapeKeys",new JSONArray(patch.getJSONArray("shapeKeys").toString()));
                if(patch.has("gradient")&&patch.isNull("gradient"))target.remove("gradient");
                break;
            }
            case "palette_upsert": {
                SketchDocument.fields(op,"type,styleId,name,color");
                SketchPaletteStyles.upsert(doc,SketchDocument.id(op,"styleId"),
                        op.getString("name"),op.getString("color"));break;
            }
            case "palette_assign": {
                SketchDocument.fields(op,"type,objectId,styleId");
                String objectId=SketchDocument.id(op,"objectId");
                SketchEditOps.requireUnlocked(SketchDocument.owner(doc,objectId));
                SketchPaletteStyles.assign(doc,objectId,SketchDocument.id(op,"styleId"));break;
            }
            case "palette_link_matching": {
                SketchDocument.fields(op,"type,styleId,originalColor");
                SketchPaletteStyles.linkMatching(doc,SketchDocument.id(op,"styleId"),
                        op.getString("originalColor"));break;
            }
            case "palette_unlink": {
                SketchDocument.fields(op,"type,objectId");
                String objectId=SketchDocument.id(op,"objectId");
                SketchEditOps.requireUnlocked(SketchDocument.owner(doc,objectId));
                SketchPaletteStyles.unlink(doc,objectId);break;
            }
            case "palette_delete": {
                SketchDocument.fields(op,"type,styleId");
                SketchPaletteStyles.deleteUnused(doc,SketchDocument.id(op,"styleId"));break;
            }
            case "append_points": {
                SketchDocument.fields(op,"type,objectId,points");String id=SketchDocument.id(op,"objectId");requireUnlocked(SketchDocument.owner(doc,id));JSONObject object=SketchDocument.object(doc,id);
                if(!"stroke".equals(object.getString("kind"))&&!"eraser".equals(object.getString("kind")))throw SketchDocument.bad("Incremental points require a stroke");JSONArray added=op.getJSONArray("points");if(added.length()<1||added.length()>2048)throw SketchDocument.bad("Append 1..2048 points per live step");JSONArray points=object.getJSONArray("points");for(int i=0;i<added.length();i++)points.put(added.getJSONArray(i));break;
            }
            case "delete_object": {
                SketchDocument.fields(op, "type,objectId"); String id = SketchDocument.id(op, "objectId"); JSONObject layer = SketchDocument.owner(doc, id);
                requireUnlocked(layer); remove(layer.getJSONArray("objects"), id); break;
            }
            case "set_canvas": {
                SketchDocument.fields(op, "type,width,height,background,name");
                for (String key : new String[]{"width","height","background","name"}) if (op.has(key)) doc.put(key, op.get(key)); break;
            }
            case "set_timing": {
                SketchDocument.fields(op, "type,fps,frameCount");
                if (op.has("fps")) doc.put("fps", op.get("fps")); if (op.has("frameCount")) doc.put("frameCount", op.get("frameCount")); break;
            }
            case "set_exposure": {
                SketchDocument.fields(op, "type,objectId,frame,exposure"); String id = SketchDocument.id(op, "objectId"); requireUnlocked(SketchDocument.owner(doc, id));
                SketchDocument.object(doc, id).put("frame", op.get("frame")).put("exposure", op.get("exposure")); break;
            }
            case "set_transform": {
                SketchDocument.fields(op, "type,layerId,objectId,transform"); JSONObject item = target(doc, op);
                JSONObject transform = SketchDocument.copy(item.getJSONObject("transform"));
                SketchDocument.fields(op.getJSONObject("transform"), "x,y,scaleX,scaleY,rotation,anchorX,anchorY,opacity");
                merge(transform, op.getJSONObject("transform")); item.put("transform", transform); break;
            }
            case "set_reference_visibility": {
                SketchDocument.fields(op,"type,objectId,visible");SketchDocument.bool(op,"visible");String id=SketchDocument.id(op,"objectId");
                JSONObject reference=SketchDocument.object(doc,id);if(!"image".equals(reference.getString("kind")))throw SketchDocument.bad("Reference visibility requires an owned image guide");
                requireUnlocked(SketchDocument.owner(doc,id));boolean visible=op.getBoolean("visible");
                referenceOpacity(reference.getJSONObject("transform"),visible);
                JSONArray keys=reference.getJSONArray("keys");for(int n=0;n<keys.length();n++)referenceOpacity(keys.getJSONObject(n).getJSONObject("transform"),visible);
                break;
            }
            case "set_shape_key": {
                JSONObject item=target(doc,op);SketchShapeKeys.apply(item,op,doc.getInt("frameCount"));break;
            }
            case "set_keyframe": {
                SketchDocument.fields(op, "type,layerId,objectId,frame,transform,interpolation,curve,remove"); JSONObject item = target(doc, op);
                int frame = (int) SketchDocument.integer(op, "frame", 0, doc.getInt("frameCount") - 1);
                boolean delete = op.has("remove") && op.getBoolean("remove"); JSONArray existing = item.getJSONArray("keys"), out = new JSONArray();
                JSONObject key = null;
                if (!delete) {
                    JSONObject transform = SketchDocument.transformAt(item, frame);
                    SketchDocument.fields(op.getJSONObject("transform"), "x,y,scaleX,scaleY,rotation,anchorX,anchorY,opacity");
                    merge(transform, op.getJSONObject("transform"));
                    key = new JSONObject().put("frame", frame).put("transform", transform).put("interpolation", op.optString("interpolation", "linear"));
                    if(op.has("curve"))key.put("curve",new JSONArray(op.getJSONArray("curve").toString()));
                }
                boolean inserted = false;
                for (int i = 0; i < existing.length(); i++) {
                    JSONObject previous = existing.getJSONObject(i); int priorFrame = previous.getInt("frame");
                    if (!inserted && priorFrame >= frame) { if (key != null) out.put(key); inserted = true; }
                    if (priorFrame != frame) out.put(previous);
                }
                if (!inserted && key != null) out.put(key); item.put("keys", out); break;
            }
            case "duplicate_cel": case "clear_cel": case "insert_frame": case "delete_frame": SketchCelOps.apply(doc, op); break;
            default: throw SketchDocument.bad("Unsupported sketch edit operation: " + type);
        }
        // Bound every intermediate state as well as the final batch, before persistence.
        SketchDocument.validate(doc);
    }

    private static void referenceOpacity(JSONObject transform,boolean visible)throws Exception{
        double opacity=transform.getDouble("opacity");transform.put("opacity",visible?(opacity>0?opacity:.35):0);
    }
    public static JSONObject completeObject(JSONObject source) throws Exception {
        JSONObject object = SketchDocument.copy(source);
        if (!object.has("frame")) object.put("frame", 0); if (!object.has("exposure")) object.put("exposure", 1);
        if (!object.has("color")) object.put("color", "#FF161C2B"); if (!object.has("width")) object.put("width", .008);
        if (!object.has("fill")) object.put("fill", false); if (!object.has("transform")) object.put("transform", SketchDocument.transform());
        if (!object.has("keys")) object.put("keys", new JSONArray()); return object;
    }
    private static JSONObject target(JSONObject doc, JSONObject op) throws Exception {
        if (op.has("objectId") == op.has("layerId")) throw SketchDocument.bad("Choose exactly one objectId or layerId");
        if (op.has("objectId")) { String id = SketchDocument.id(op, "objectId"); requireUnlocked(SketchDocument.owner(doc, id)); return SketchDocument.object(doc, id); }
        JSONObject layer = SketchDocument.layer(doc, SketchDocument.id(op, "layerId")); requireUnlocked(layer); return layer;
    }
    private static void merge(JSONObject target, JSONObject source) throws Exception { Iterator<String> keys = source.keys(); while (keys.hasNext()) { String key = keys.next(); target.put(key, source.get(key)); } }
    private static void remove(JSONArray array, String id) throws Exception {
        for (int i = 0; i < array.length(); i++) if (id.equals(array.getJSONObject(i).getString("id"))) { array.remove(i); return; }
        throw SketchDocument.bad("Unknown ID: " + id);
    }
    static void requireUnlocked(JSONObject layer) throws Exception { if (layer.getBoolean("locked")) throw new IllegalStateException("SKETCH_LAYER_LOCKED"); }
}
