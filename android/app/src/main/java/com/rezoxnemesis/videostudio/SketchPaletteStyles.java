package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional per-document named color styles for editable vector, shape and text
 * cels. The original per-object "#AARRGGBB" remains a valid offline fallback
 * and old documents without paletteStyles render byte-for-byte as before.
 *
 * Style edits update a single small palette entry, NOT thousands of object
 * records. Paint and imported image pixels are deliberately not recolored.
 */
final class SketchPaletteStyles {
    static final int MAX_STYLES=128;
    private SketchPaletteStyles(){}

    static Map<String,Integer> indexed(JSONObject document)throws Exception{
        JSONArray palette=document.optJSONArray("paletteStyles");
        if(document.has("paletteStyles")&&palette==null)
            throw SketchDocument.bad("paletteStyles must be an array");
        if(palette==null)return Collections.emptyMap();
        if(palette.length()>MAX_STYLES)throw SketchDocument.bad("Use at most 128 linked palette styles");
        Map<String,Integer> result=new LinkedHashMap<>();
        for(int i=0;i<palette.length();i++){
            JSONObject style=palette.getJSONObject(i);
            SketchDocument.fields(style,"id,name,color");
            String id=SketchDocument.id(style,"id");
            SketchDocument.text(style,"name",80);
            int color=SketchDocument.color(style.getString("color"));
            if(result.put(id,color)!=null)
                throw SketchDocument.bad("Duplicate linked palette style ID: "+id);
        }
        return result;
    }

    static void validateLink(JSONObject item,Map<String,Integer> styles)throws Exception{
        if(!item.has("styleId"))return;
        String id=SketchDocument.id(item,"styleId");
        String kind=item.getString("kind");
        if("image".equals(kind)||"paint".equals(kind)||"eraser".equals(kind)||item.has("gradient"))
            throw SketchDocument.bad("Linked palette styles require non-gradient vector shapes or text, not bitmap/paint/eraser content");
        if(!styles.containsKey(id))throw SketchDocument.bad("Unknown linked palette style: "+id);
    }

    static int resolvedColor(JSONObject object,Map<String,Integer> styles)throws Exception {
        if(!object.has("styleId"))return SketchDocument.color(object.getString("color"));
        String id=object.getString("styleId");
        Integer color=styles.get(id);
        if(color==null)throw SketchDocument.bad("Unknown linked palette style: "+id);
        return color;
    }

    static String colorString(int color){
        return String.format(java.util.Locale.ROOT,"#%08X",color);
    }

    static void upsert(JSONObject document,String id,String name,String color)throws Exception{
        id=SketchDocument.id(new JSONObject().put("id",id),"id");
        name=name==null?"":name.trim();
        if(name.isEmpty()||name.length()>80)throw SketchDocument.bad("Palette style names require 1..80 characters");
        SketchDocument.color(color);
        JSONArray styles=document.optJSONArray("paletteStyles");
        if(styles==null){styles=new JSONArray();document.put("paletteStyles",styles);}
        for(int i=0;i<styles.length();i++){
            JSONObject old=styles.getJSONObject(i);
            if(id.equals(old.getString("id"))){
                old.put("name",name).put("color",color);
                return;
            }
        }
        if(styles.length()>=MAX_STYLES)throw SketchDocument.bad("Linked palette has reached its 128-style limit");
        styles.put(new JSONObject().put("id",id).put("name",name).put("color",color));
    }

    static void assign(JSONObject document,String objectId,String styleId)throws Exception{
        JSONObject object=SketchDocument.object(document,objectId);
        Map<String,Integer> styles=indexed(document);
        Integer color=styles.get(styleId);
        if(color==null)throw SketchDocument.bad("Create a linked palette style before assigning it");
        if("image".equals(object.getString("kind"))||
                "paint".equals(object.getString("kind"))||
                "eraser".equals(object.getString("kind"))||
                object.has("gradient"))
            throw SketchDocument.bad("Only flat-color vector shapes and text support linked palette styles");
        // Preserve the chosen current pigment as an independent fallback value.
        object.put("color",colorString(color)).put("styleId",styleId);
    }

    static void unlink(JSONObject document,String objectId)throws Exception{
        JSONObject object=SketchDocument.object(document,objectId);
        if(!object.has("styleId"))return;
        int current=resolvedColor(object,indexed(document));
        object.put("color",colorString(current));
        object.remove("styleId");
    }

    /** Batch link only *unlinked* flat-color vector objects currently using a
     * specific original pigment. Locked matching layers abort the whole operation.
     * This is the opt-in bridge from thousands of legacy colors to stable styles. */
    static int linkMatching(JSONObject document,String styleId,String originalColor)throws Exception{
        SketchDocument.color(originalColor);
        Map<String,Integer> styles=indexed(document);
        if(!styles.containsKey(styleId))throw SketchDocument.bad("Create the linked palette style first");
        JSONArray layers=document.getJSONArray("layers");
        int count=0;
        for(int i=0;i<layers.length();i++){
            JSONObject layer=layers.getJSONObject(i);
            JSONArray objects=layer.getJSONArray("objects");
            for(int j=0;j<objects.length();j++){
                JSONObject item=objects.getJSONObject(j);
                String kind=item.getString("kind");
                if(item.has("styleId")||item.has("gradient")||
                        "paint".equals(kind)||"image".equals(kind)||"eraser".equals(kind))
                    continue;
                if(!originalColor.equalsIgnoreCase(item.getString("color")))continue;
                SketchEditOps.requireUnlocked(layer);
                count++;
            }
        }
        if(count==0)throw SketchDocument.bad("No unlinked vector artwork uses that pigment");
        for(int i=0;i<layers.length();i++){
            JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");
            for(int j=0;j<objects.length();j++){
                JSONObject item=objects.getJSONObject(j);
                String kind=item.getString("kind");
                if(item.has("styleId")||item.has("gradient")||
                        "paint".equals(kind)||"image".equals(kind)||"eraser".equals(kind))
                    continue;
                if(originalColor.equalsIgnoreCase(item.getString("color")))
                    assign(document,item.getString("id"),styleId);
            }
        }
        return count;
    }

    static void deleteUnused(JSONObject document,String styleId)throws Exception{
        JSONArray layers=document.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){
            JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");
            for(int j=0;j<objects.length();j++)
                if(styleId.equals(objects.getJSONObject(j).optString("styleId","")))
                    throw SketchDocument.bad("Unlink every artwork reference before deleting a palette style");
        }
        JSONArray old=document.optJSONArray("paletteStyles");
        if(old==null)throw SketchDocument.bad("Linked palette style not found");
        JSONArray next=new JSONArray();boolean found=false;
        for(int i=0;i<old.length();i++){
            JSONObject entry=old.getJSONObject(i);
            if(styleId.equals(entry.getString("id"))){found=true;continue;}
            next.put(entry);
        }
        if(!found)throw SketchDocument.bad("Linked palette style not found");
        document.put("paletteStyles",next);
    }
}
