package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Compact a locally traced image into FEWER genuine editable vector paths.
 *
 * This never reads Gallery/media and never places a source image on the Canvas.
 * It coalesces pre-existing filled, opaque, identical-style, closed path regions
 * into a compound path with multiple M...Z subpaths. Native touch/MCP edits,
 * pixel rendering and undo history remain the same SketchDocument system.
 *
 * Only nearby regions in the same bounded source-order window can coalesce:
 * source paint order is protected conservatively, but anti-alias seams may differ.
 * Never treat the resulting vector as a certified pixel-perfect trace without
 * rendering and visually reviewing the before/after frames on the device.
 */
final class SketchTraceConsolidator {
    static final int MAX_WINDOW=96;
    static final int MAX_MERGED_SUBPATHS=32;

    private SketchTraceConsolidator(){}

    static void apply(JSONObject document,JSONObject operation) throws Exception {
        SketchDocument.fields(operation,"type,layerId,window,maxMergeSize,idPrefix");
        String layerId=SketchDocument.id(operation,"layerId");
        JSONObject layer=SketchDocument.layer(document,layerId);
        SketchEditOps.requireUnlocked(layer);
        int window=operation.has("window")
                ?(int)SketchDocument.integer(operation,"window",4,MAX_WINDOW):32;
        int maxMerge=operation.has("maxMergeSize")
                ?(int)SketchDocument.integer(operation,"maxMergeSize",2,MAX_MERGED_SUBPATHS):16;
        String prefix=operation.optString("idPrefix","rr_trace_");
        if(prefix.length()<4||prefix.length()>90||prefix.contains("/")||prefix.contains("\\"))
            throw SketchDocument.bad("A valid owned traced-vector ID prefix is required");
        JSONArray before=layer.getJSONArray("objects");
        if(before.length()==0)
            throw SketchDocument.bad("There are no paths to consolidate");

        JSONArray output=new JSONArray();int merged=0;
        for(int start=0;start<before.length();start+=window){
            int stop=Math.min(before.length(),start+window);
            Map<String,Group> groups=new HashMap<>();
            for(int i=start;i<stop;i++){
                JSONObject candidate=before.getJSONObject(i);
                String key=key(candidate,prefix);
                Group group=key==null?null:groups.get(key);
                if(group!=null&&group.members<maxMerge
                        &&preservesSourcePaintOrder(before,group.sourceIndex,i,candidate)){
                    JSONArray old=group.leader.getJSONArray("commands");
                    JSONArray next=candidate.getJSONArray("commands");
                    if(old.length()+next.length()<=4096){
                        // A closed subpath must remain disjoint from the next M.
                        for(int k=0;k<next.length();k++)
                            old.put(new JSONArray(next.getJSONArray(k).toString()));
                        group.members++;
                        merged++;
                        continue;
                    }
                }
                JSONObject retained=SketchDocument.copy(candidate);
                output.put(retained);
                if(key!=null)groups.put(key,new Group(retained,i));
            }
        }
        if(merged==0)throw SketchDocument.bad(
                "No safe identical-style traced paths could be compacted");
        layer.put("objects",output);
    }

    private static String key(JSONObject object,String prefix)throws Exception {
        if(!object.getString("id").startsWith(prefix)
                ||!"path".equals(object.optString("kind"))
                ||!object.optBoolean("fill")
                ||object.has("gradient")||object.has("styleId")
                ||object.has("brush")||object.has("referenceCrop")
                ||object.optJSONArray("keys")==null
                ||object.getJSONArray("keys").length()>0)return null;
        String color=object.getString("color");
        if(!color.matches("#FF[0-9A-Fa-f]{6}"))return null;
        JSONArray commands=object.getJSONArray("commands");
        if(commands.length()<3
                ||!"M".equals(commands.getJSONArray(0).getString(0))
                ||!"Z".equals(commands.getJSONArray(commands.length()-1).getString(0)))
            return null;
        JSONObject t=object.getJSONObject("transform");
        if(Math.abs(t.getDouble("x"))>1e-9||Math.abs(t.getDouble("y"))>1e-9
                ||Math.abs(t.getDouble("scaleX")-1)>1e-9
                ||Math.abs(t.getDouble("scaleY")-1)>1e-9
                ||Math.abs(t.getDouble("rotation"))>1e-9
                ||Math.abs(t.getDouble("opacity")-1)>1e-9)return null;
        return color+"|"+object.getDouble("width")+"|"
                +object.getInt("frame")+"|"+object.getInt("exposure")
                +"|"+t.getDouble("anchorX")+"|"+t.getDouble("anchorY");
    }


    /**
     * An opaque later region must not be moved beneath a region which used to
     * be painted before it. Test ORIGINAL source-order objects, not compacted
     * leaders: a previously merged path may otherwise be hidden in an earlier
     * compound object, and future merges could reverse its paint order.
     *
     * Normalized bounding rectangles include stroke safety margins and Bezier
     * controls. Unknown or animated intervening geometry acts as a barrier.
     * False negatives only reduce compaction; no uncertain paint is reordered.
     */
    private static boolean preservesSourcePaintOrder(JSONArray source,int leaderIndex,
            int candidateIndex,JSONObject candidate)throws Exception {
        Bounds moved=bounds(candidate);
        if(moved==null)return false;
        for(int i=leaderIndex+1;i<candidateIndex;i++){
            Bounds crossed=bounds(source.getJSONObject(i));
            if(crossed==null||moved.overlaps(crossed))return false;
        }
        return true;
    }

    private static Bounds bounds(JSONObject object)throws Exception {
        if(!"path".equals(object.optString("kind"))
                ||object.optJSONArray("keys")==null
                ||object.getJSONArray("keys").length()!=0
                ||object.has("referenceCrop"))return null;
        JSONObject transform=object.optJSONObject("transform");
        if(transform==null
                ||Math.abs(transform.getDouble("x"))>1e-9
                ||Math.abs(transform.getDouble("y"))>1e-9
                ||Math.abs(transform.getDouble("scaleX")-1)>1e-9
                ||Math.abs(transform.getDouble("scaleY")-1)>1e-9
                ||Math.abs(transform.getDouble("rotation"))>1e-9
                ||Math.abs(transform.getDouble("opacity")-1)>1e-9)return null;
        JSONArray commands=object.optJSONArray("commands");
        if(commands==null||commands.length()<2)return null;
        double left=Double.POSITIVE_INFINITY,top=Double.POSITIVE_INFINITY;
        double right=Double.NEGATIVE_INFINITY,bottom=Double.NEGATIVE_INFINITY;
        for(int i=0;i<commands.length();i++){
            JSONArray command=commands.getJSONArray(i);
            String kind=command.getString(0);
            if("Z".equals(kind))continue;
            if(!("M".equals(kind)||"L".equals(kind)
                    ||"Q".equals(kind)||"C".equals(kind)))return null;
            for(int p=1;p+1<command.length();p+=2){
                double x=command.getDouble(p),y=command.getDouble(p+1);
                if(!Double.isFinite(x)||!Double.isFinite(y))return null;
                left=Math.min(left,x);right=Math.max(right,x);
                top=Math.min(top,y);bottom=Math.max(bottom,y);
            }
        }
        if(!Double.isFinite(left)||!Double.isFinite(right))return null;
        // Conservative expansion accounts for native stroke widths / AA seams.
        double margin=Math.max(.001,object.optDouble("width",.001));
        if(!Double.isFinite(margin)||margin<0)return null;
        return new Bounds(left-margin,top-margin,right+margin,bottom+margin);
    }

    private static final class Bounds {
        final double left,top,right,bottom;
        Bounds(double left,double top,double right,double bottom) {
            this.left=left;this.top=top;this.right=right;this.bottom=bottom;
        }
        boolean overlaps(Bounds other) {
            return left<=other.right&&other.left<=right
                    &&top<=other.bottom&&other.top<=bottom;
        }
    }

    private static final class Group {
        final JSONObject leader;
        final int sourceIndex;
        int members=1;
        Group(JSONObject leader,int sourceIndex){
            this.leader=leader;this.sourceIndex=sourceIndex;
        }
    }
}
