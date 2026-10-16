package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Read-only OpenToonz-style exposure sheet projected from the SAME objects and
 * exposure intervals as the native SketchFrameRenderer. No cels are duplicated,
 * invented, retimed, saved or made visible while inspecting this view.
 *
 * A column is a prepared layer, each cell references an immutable set of object
 * IDs ("drawing"); repeated drawings are explicit HOLD cells, absent drawings
 * are BLANK. Transform key markers remain distinct: held drawings can still be
 * animated through per-object or per-layer motion keys.
 */
final class SketchXsheetProjection {
    static final int MAX_ROWS=48, MAX_RESULT_BYTES=500*1024;
    private SketchXsheetProjection(){}

    static JSONObject project(JSONObject source,int offset,int requestedRows)throws Exception {
        SketchDocument.validate(source);
        int frames=source.getInt("frameCount");
        if(offset<0||offset>=frames||requestedRows<1||requestedRows>MAX_ROWS)
            throw SketchDocument.bad("Xsheet requires a valid frame and 1..48 visible rows");
        int last=Math.min(frames,offset+requestedRows);
        JSONArray columns=new JSONArray(),layers=source.getJSONArray("layers");
        int drawings=0,draws=0,holds=0,blanks=0,transformKeys=0;
        for(int layerIndex=0;layerIndex<layers.length();layerIndex++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Xsheet inspection cancelled");
            JSONObject layer=layers.getJSONObject(layerIndex);
            String id=layer.getString("id");
            JSONArray objects=layer.getJSONArray("objects");
            Set<Integer> layerKeys=framesWithKeys(layer);
            Map<String,Set<Integer>> objectKeys=new HashMap<>();
            for(int i=0;i<objects.length();i++){
                JSONObject item=objects.getJSONObject(i);
                objectKeys.put(item.getString("id"),framesWithKeys(item));
            }
            // A window beginning during a held exposure MUST still show HOLD,
            // not a false new drawing on the first row of each page.
            String previous=offset>0?fingerprint(id,visibleAt(objects,offset-1)):"";
            JSONArray cells=new JSONArray(),dictionary=new JSONArray();
            Map<String,String> known=new LinkedHashMap<>();
            for(int frame=offset;frame<last;frame++){
                ArrayList<String> exposed=visibleAt(objects,frame);
                String active=exposed.isEmpty()?"":fingerprint(id,exposed);
                String state=active.isEmpty()?"blank":active.equals(previous)?"hold":"drawing";
                if("blank".equals(state))blanks++;else if("hold".equals(state))holds++;else draws++;
                JSONObject cell=new JSONObject().put("frame",frame).put("state",state)
                        .put("drawingId",active.isEmpty()?JSONObject.NULL:active);
                boolean keyed=layerKeys.contains(frame);
                if(!keyed)for(String objectId:exposed)if(objectKeys.get(objectId).contains(frame)){
                    keyed=true;break;
                }
                if(keyed){cell.put("transformKey",true);transformKeys++;}
                cells.put(cell);
                if(!active.isEmpty()&&!known.containsKey(active)){
                    known.put(active,active);
                    JSONArray ids=new JSONArray();for(String objectId:exposed)ids.put(objectId);
                    dictionary.put(new JSONObject().put("drawingId",active).put("objectIds",ids));
                    drawings++;
                }
                previous=active;
            }
            columns.put(new JSONObject().put("layerId",id).put("name",layer.getString("name"))
                    .put("visible",layer.getBoolean("visible")).put("locked",layer.getBoolean("locked"))
                    .put("cells",cells).put("drawings",dictionary));
        }
        JSONObject result=new JSONObject()
                .put("schema","videostudio.sketch-xsheet.v1")
                .put("sketchId",source.getString("id"))
                .put("sourceRevision",source.getLong("revision"))
                .put("fps",source.getInt("fps")).put("frameCount",frames)
                .put("frameOffset",offset).put("returnedFrames",last-offset)
                .put("nextFrameOffset",last<frames?last:JSONObject.NULL)
                .put("levelCount",layers.length()).put("columns",columns)
                .put("dictionaryDrawings",drawings)
                .put("drawingCells",draws).put("holdCells",holds).put("blankCells",blanks)
                .put("transformKeyCells",transformKeys)
                .put("readOnly",true)
                .put("renderingParity","exposure intervals match SketchFrameRenderer.exposed; hidden levels remain authored columns");
        if(result.toString().getBytes(StandardCharsets.UTF_8).length>MAX_RESULT_BYTES)
            throw SketchDocument.bad("Xsheet query exceeds 500 KiB; request fewer frame rows");
        return result;
    }

    private static ArrayList<String> visibleAt(JSONArray objects,int frame)throws Exception {
        ArrayList<String> exposed=new ArrayList<>();
        for(int i=0;i<objects.length();i++){
            JSONObject object=objects.getJSONObject(i);
            if(SketchFrameRenderer.exposed(object,frame))exposed.add(object.getString("id"));
        }
        return exposed;
    }
    private static String fingerprint(String level,ArrayList<String> exposed)throws Exception {
        // A length-prefixed canonical identity prevents ambiguous joined IDs.
        StringBuilder input=new StringBuilder(level.length()+exposed.size()*24);
        input.append(level.length()).append(':').append(level);
        for(String part:exposed)input.append('|').append(part.length()).append(':').append(part);
        return "cel_"+SketchDocument.sha256(input.toString().getBytes(StandardCharsets.UTF_8)).substring(0,20);
    }
    private static Set<Integer> framesWithKeys(JSONObject object)throws Exception {
        Set<Integer> frames=new HashSet<>();
        JSONArray keys=object.getJSONArray("keys");
        for(int i=0;i<keys.length();i++)frames.add(keys.getJSONObject(i).getInt("frame"));
        return frames;
    }
}
