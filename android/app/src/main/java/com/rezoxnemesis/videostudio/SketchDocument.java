package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Bounded, normalized vector/cel document. All accepted edits are validated here. */
public final class SketchDocument {
    public static final String SCHEMA = "videostudio.sketch.v1";
    public static final int MAX_BYTES = 4 * 1024 * 1024, MAX_LAYERS = 32, MAX_IMAGES = 32, MAX_OBJECTS = 4096;
    public static final int MAX_POINTS = 131072, MAX_FRAMES = 1200, MAX_KEYS = 4096;
    private SketchDocument() {}

    public static JSONObject create(JSONObject args) throws Exception {
        JSONObject doc = new JSONObject().put("schema", SCHEMA).put("id", id(args, "sketchId"))
                .put("name", args.optString("name", "Untitled sketch")).put("revision", 1)
                .put("width", args.has("width") ? args.get("width") : 720)
                .put("height", args.has("height") ? args.get("height") : 1280)
                .put("fps", args.has("fps") ? args.get("fps") : 24)
                .put("frameCount", args.has("frameCount") ? args.get("frameCount") : 24)
                .put("background", args.optString("background", "#FFFFFFFF"))
                .put("layers", new JSONArray().put(new JSONObject().put("id", "layer_1")
                        .put("name", "Layer 1").put("visible", true).put("locked", false)
                        .put("opacity", 1).put("transform", transform()).put("keys", new JSONArray())
                        .put("objects", new JSONArray())));
        validate(doc);
        return doc;
    }

    public static JSONObject transform() throws Exception {
        return new JSONObject().put("x", 0).put("y", 0).put("scaleX", 1).put("scaleY", 1)
                .put("rotation", 0).put("anchorX", .5).put("anchorY", .5).put("opacity", 1);
    }

    public static JSONObject copy(JSONObject o) throws Exception { return new JSONObject(o.toString()); }

    public static void validate(JSONObject doc) throws Exception {
        fields(doc, "schema,id,name,revision,width,height,fps,frameCount,background,layers,paletteStyles,masterAssets,layeredDrawing");
        if (!SCHEMA.equals(doc.getString("schema"))) throw bad("Unknown sketch schema");
        id(doc, "id");
        text(doc, "name", 160);
        integer(doc, "revision", 1, Long.MAX_VALUE - 1);
        int width = (int) integer(doc, "width", 256, 2048), height = (int) integer(doc, "height", 256, 2048);
        if ((width & 1) != 0 || (height & 1) != 0 || (long) width * height > 2048L * 2048)
            throw bad("Raster must be even and at most 2048×2048 pixels");
        NativeFrameClock.requireFps((int) integer(doc, "fps", 24, 60));
        int frames = (int) integer(doc, "frameCount", 1, MAX_FRAMES);
        color(doc.getString("background"));
        java.util.Map<String,Integer> linkedStyles=SketchPaletteStyles.indexed(doc);
        java.util.Map<String,JSONObject> linkedMasters=SketchMasterAssets.indexed(doc);
        JSONArray layers = doc.getJSONArray("layers");
        if (layers.length() < 1 || layers.length() > MAX_LAYERS) throw bad("Use 1..32 sketch layers");
        Set<String> ids = new HashSet<>();
        Set<String> meshedPaintSources=new HashSet<>();
        int objects = 0, points = SketchMasterAssets.storedPoints(linkedMasters), keys = 0, images = 0, proceduralActors=0;
        int[] proceduralActorChanges=new int[frames+1];
        for (int i = 0; i < layers.length(); i++) {
            JSONObject layer = layers.getJSONObject(i);
            fields(layer, "id,name,visible,locked,opacity,transform,keys,objects,blendMode");
            unique(ids, id(layer, "id")); text(layer, "name", 160);
            if (layer.has("blendMode") && !java.util.Arrays.asList("normal","multiply","screen","add").contains(layer.getString("blendMode"))) throw bad("Unknown layer blend mode");
            bool(layer, "visible"); bool(layer, "locked"); number(layer, "opacity", 0, 1);
            validateTransform(layer.getJSONObject("transform"));
            keys += validateKeys(layer.getJSONArray("keys"), frames);
            JSONArray items = layer.getJSONArray("objects");
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.getJSONObject(j);
                fields(item, "id,kind,frame,exposure,color,styleId,width,fill,points,bounds,reference,paint,transform,keys,brush,commands,shapeKeys,paintMesh,gradient,referenceCrop,text,fontFamily,fontSize,fontStyle,textAlign,letterSpacing,hand,human,masterLink");
                unique(ids, id(item, "id"));
                String kind = item.getString("kind");
                if (!java.util.Arrays.asList("stroke","eraser","line","rect","ellipse","polygon","image","paint","path","text","hand","human").contains(kind))
                    throw bad("Unsupported sketch object kind");
                int start = (int) integer(item, "frame", 0, frames - 1);
                int exposure=(int)integer(item, "exposure", 1, frames - start);
                color(item.getString("color")); SketchPaletteStyles.validateLink(item,linkedStyles); number(item, "width", .0005, .2); bool(item, "fill");
                validateTransform(item.getJSONObject("transform"));
                keys += validateKeys(item.getJSONArray("keys"), frames);
                int[] shapeBudget=SketchShapeKeys.validate(item,frames);keys+=shapeBudget[0];points+=shapeBudget[1];
                int[] meshBudget=SketchPaintMesh.validate(item,frames);keys+=meshBudget[0];points+=meshBudget[1];if(item.has("paintMesh")){meshedPaintSources.add(item.getJSONObject("paint").getString("sha256"));if(meshedPaintSources.size()>32||(long)width*height>1048576)throw bad("Use at most32 distinct meshed paint sources, each at most1048576 native pixels");}
                if(item.has("paint")&&!"paint".equals(kind))throw bad("Paint snapshots require a paint cel");
                if("paint".equals(kind))fields(item,"id,kind,frame,exposure,color,width,fill,bounds,paint,transform,keys,paintMesh");
                if(item.has("hand")&&!"hand".equals(kind))throw bad("Hand programme requires a hand object");
                if("hand".equals(kind)){SketchHandAnimation.validate(doc,item);keys+=item.getJSONObject("hand").getJSONArray("poses").length();}
                if(item.has("human")&&!"human".equals(kind))throw bad("Human programme requires a human object");
                if("human".equals(kind)){SketchHumanAnimation.validate(doc,item);keys+=item.getJSONObject("human").getJSONArray("poses").length();}
                if("hand".equals(kind)||"human".equals(kind)){proceduralActorChanges[start]++;proceduralActorChanges[start+exposure]--;}
                if (item.has("brush")) { if (!"stroke".equals(kind) && !"eraser".equals(kind)) throw bad("Brush requires stroke/eraser"); SketchBrushes.validate(item.getJSONObject("brush")); }
                if (item.has("gradient")) validateGradient(item.getJSONObject("gradient"));
                if (item.has("referenceCrop")) { if (!"image".equals(kind)) throw bad("Reference crop requires an image"); validateBounds(item.getJSONObject("referenceCrop")); }
                if ("text".equals(kind)) SketchText.validate(item);
                SketchMasterAssets.validateInstance(item,linkedMasters);
                if ("path".equals(kind)) { points += validateCommands(item.getJSONArray("commands")); }
                else if ("stroke".equals(kind) || "eraser".equals(kind) || "line".equals(kind) || "polygon".equals(kind)) {
                    JSONArray pp = item.getJSONArray("points");
                    int min = "polygon".equals(kind) ? 3 : ("line".equals(kind) ? 2 : 1);
                    if (pp.length() < min || pp.length() > MAX_POINTS || ("line".equals(kind) && pp.length() != 2))
                        throw bad("Invalid sketch point count");
                    for (int k = 0; k < pp.length(); k++) {
                        JSONArray p = pp.getJSONArray(k);
                        if (p.length() != 3) throw bad("Points require [x,y,pressure]");
                        for (int v = 0; v < 3; v++) scalar(p.get(v), 0, 1, "Point coordinate/pressure");
                    }
                    points += pp.length();
                } else {
                    JSONObject b = item.getJSONObject("bounds");
                    fields(b, "x,y,width,height");
                    number(b, "x", 0, 1); number(b, "y", 0, 1);
                    number(b, "width", .0001, 1); number(b, "height", .0001, 1);
                    if (b.getDouble("x") + b.getDouble("width") > 1.000001 || b.getDouble("y") + b.getDouble("height") > 1.000001)
                        throw bad("Shape bounds must fit normalized canvas");
                    if ("image".equals(kind)) {
                        JSONObject ref = item.getJSONObject("reference");
                        fields(ref, "file,sha256,width,height,sourceProjectId,sourceAssetId,sourceWidth,sourceHeight,sourceOrientation,sourceSha256,sourceMask");
                        String hash = ref.getString("sha256");
                        if (!hash.matches("[a-f0-9]{64}") || !ref.getString("file").equals(hash + ".png"))
                            throw bad("Reference image must be an owned hash-bound PNG");
                        integer(ref, "width", 1, 2048); integer(ref, "height", 1, 2048);
                        if(ref.has("sourceWidth")||ref.has("sourceHeight")){
                            long sourceWidth=integer(ref,"sourceWidth",1,64_000_000),sourceHeight=integer(ref,"sourceHeight",1,64_000_000);
                            if(sourceWidth*sourceHeight>64_000_000L)throw bad("Reference source dimensions exceed decode budget");
                        }
                        if(ref.has("sourceOrientation"))integer(ref,"sourceOrientation",1,8);
                        if(ref.has("sourceSha256")&&!ref.getString("sourceSha256").matches("[a-f0-9]{64}"))throw bad("Reference source hash is invalid");
                        if(ref.has("sourceMask"))SketchSourceMask.validate(ref);
                        id(ref, "sourceProjectId"); id(ref, "sourceAssetId"); images++;
                    }
                    if("paint".equals(kind)){
                        if(b.getDouble("x")!=0||b.getDouble("y")!=0||b.getDouble("width")!=1||b.getDouble("height")!=1)throw bad("Paint cels require full-canvas bounds");
                        JSONObject paint=item.getJSONObject("paint");SketchPaintStore.validateDescriptor(paint);
                        if(paint.getInt("width")!=width||paint.getInt("height")!=height)throw bad("Paint snapshot dimensions must match the canvas");
                    }
                }
                objects++;
            }
        }
        // Cel splits increase stored segments without increasing simultaneous rendering work.
        for(int frame=0;frame<frames;frame++){
            proceduralActors+=proceduralActorChanges[frame];
            if(proceduralActors>16)throw bad("Use at most16 simultaneously exposed retained hand/human actors per frame");
        }
        if (objects > MAX_OBJECTS || points > MAX_POINTS || keys > MAX_KEYS || images > MAX_IMAGES)
            throw bad("Sketch budget exceeded (4096 objects, 131072 points, 4096 keys, 32 images)");
        SketchLayeredDrawing.validate(doc);
        if (doc.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw bad("Sketch document exceeds 4 MiB");
    }

    static void validateBounds(JSONObject b) throws Exception {
        fields(b,"x,y,width,height"); number(b,"x",0,1); number(b,"y",0,1); number(b,"width",.0001,1); number(b,"height",.0001,1);
        if(b.getDouble("x")+b.getDouble("width")>1.000001||b.getDouble("y")+b.getDouble("height")>1.000001)throw bad("Crop must fit normalized reference");
    }
    static int validateCommands(JSONArray commands) throws Exception {
        if(commands.length()<2||commands.length()>4096)throw bad("Path requires 2..4096 commands");int points=0;
        for(int i=0;i<commands.length();i++){JSONArray command=commands.getJSONArray(i);String kind=command.getString(0);int size="C".equals(kind)?7:"Q".equals(kind)?5:("M".equals(kind)||"L".equals(kind))?3:"Z".equals(kind)?1:0;
            if(size==0||command.length()!=size||i==0&&!"M".equals(kind))throw bad("Path must start with M and use M/L/Q/C/Z commands");
            for(int j=1;j<size;j++)scalar(command.get(j),0,1,"Path coordinate");points+=(size-1)/2;
        }return points;
    }
    static void validateGradient(JSONObject g) throws Exception {
        SketchGradients.validate(g);
    }

    public static void validateTransform(JSONObject t) throws Exception {
        fields(t, "x,y,scaleX,scaleY,rotation,anchorX,anchorY,opacity");
        number(t, "x", -4, 4); number(t, "y", -4, 4);
        number(t, "scaleX", .01, 8); number(t, "scaleY", .01, 8); number(t, "rotation", -3600, 3600);
        number(t, "anchorX", 0, 1); number(t, "anchorY", 0, 1); number(t, "opacity", 0, 1);
    }

    private static int validateKeys(JSONArray keys, int frames) throws Exception {
        int previous = -1;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject key = keys.getJSONObject(i);
            fields(key, "frame,transform,interpolation,curve");
            int frame = (int) integer(key, "frame", 0, frames - 1);
            if (frame <= previous) throw bad("Transform keys require unique ascending frames");
            previous = frame;
            validateTransform(key.getJSONObject("transform"));
            key.getString("interpolation");SketchEasing.validate(key);
        }
        return keys.length();
    }

    /** Before the first key use the base transform; between keys honor the left key's hold/linear mode. */
    public static JSONObject transformAt(JSONObject item, int frame) throws Exception {
        JSONObject base = item.getJSONObject("transform"); JSONArray keys = item.getJSONArray("keys");
        JSONObject left = null, right = null;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject key = keys.getJSONObject(i);
            if (key.getInt("frame") <= frame) left = key; else { right = key; break; }
        }
        if (left == null) return copy(base);
        JSONObject a = left.getJSONObject("transform");
        if (right == null || "hold".equals(left.getString("interpolation"))) return copy(a);
        JSONObject b = right.getJSONObject("transform");
        double f = (frame - left.getInt("frame")) / (double) (right.getInt("frame") - left.getInt("frame"));
        f=SketchEasing.sample(left.getString("interpolation"),left.optJSONArray("curve"),f);
        JSONObject out = new JSONObject();
        for (String key : new String[]{"x","y","scaleX","scaleY","rotation","anchorX","anchorY","opacity"})
            out.put(key, a.getDouble(key) + (b.getDouble(key) - a.getDouble(key)) * f);
        return out;
    }

    public static JSONObject layer(JSONObject doc, String id) throws Exception {
        JSONArray layers = doc.getJSONArray("layers");
        for (int i = 0; i < layers.length(); i++) if (layers.getJSONObject(i).getString("id").equals(id)) return layers.getJSONObject(i);
        throw bad("Unknown sketch layer: " + id);
    }
    public static JSONObject object(JSONObject doc, String id) throws Exception {
        JSONArray layers = doc.getJSONArray("layers");
        for (int i = 0; i < layers.length(); i++) {
            JSONArray items = layers.getJSONObject(i).getJSONArray("objects");
            for (int j = 0; j < items.length(); j++) if (items.getJSONObject(j).getString("id").equals(id)) return items.getJSONObject(j);
        }
        throw bad("Unknown sketch object: " + id);
    }
    public static JSONObject owner(JSONObject doc, String objectId) throws Exception {
        JSONArray layers = doc.getJSONArray("layers");
        for (int i = 0; i < layers.length(); i++) {
            JSONObject layer = layers.getJSONObject(i); JSONArray items = layer.getJSONArray("objects");
            for (int j = 0; j < items.length(); j++) if (objectId.equals(items.getJSONObject(j).getString("id"))) return layer;
        }
        throw bad("Unknown sketch object: " + objectId);
    }
    public static String id(JSONObject o, String field) throws Exception {
        Object value = o.get(field);
        if (!(value instanceof String) || !((String) value).matches("[A-Za-z0-9_-]{1,96}")) throw bad(field + " requires a stable safe ID");
        return (String) value;
    }
    static void text(JSONObject o, String field, int max) throws Exception {
        Object value = o.get(field);
        if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > max) throw bad("Invalid " + field);
    }
    static void bool(JSONObject o, String field) throws Exception {
        if (!(o.get(field) instanceof Boolean)) throw bad(field + " must be boolean");
    }
    public static long integer(JSONObject o, String field, long min, long max) throws Exception {
        Object v = o.get(field);
        if (!(v instanceof Number)) throw bad(field + " must be an integer");
        double d = ((Number) v).doubleValue(); long n = ((Number) v).longValue();
        if (!Double.isFinite(d) || d != n || n < min || n > max) throw bad(field + " is outside its integer bound");
        return n;
    }
    static double number(JSONObject o, String field, double min, double max) throws Exception { return scalar(o.get(field), min, max, field); }
    static double scalar(Object v, double min, double max, String label) {
        if (!(v instanceof Number)) throw bad(label + " must be numeric");
        double d = ((Number) v).doubleValue();
        if (!Double.isFinite(d) || d < min || d > max) throw bad(label + " is outside its bound");
        return d;
    }
    public static int color(String s) {
        if (s == null || !s.matches("#[a-fA-F0-9]{8}")) throw bad("Color requires #AARRGGBB");
        return (int) Long.parseLong(s.substring(1), 16);
    }
    static void fields(JSONObject o, String allowed) {
        Set<String> set = new HashSet<>(java.util.Arrays.asList(allowed.split(",")));
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) { String key = keys.next(); if (!set.contains(key)) throw bad("Unknown sketch field: " + key); }
    }
    private static void unique(Set<String> ids, String id) { if (!ids.add(id)) throw bad("Duplicate layer/object ID: " + id); }
    public static String sha256(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    /** Canonical request fingerprint: JSON key insertion order is not an operation change. */
    public static String canonical(Object o) throws Exception {
        if (o instanceof JSONObject) {
            JSONObject object = (JSONObject) o; java.util.ArrayList<String> keys = new java.util.ArrayList<>();
            object.keys().forEachRemaining(keys::add); java.util.Collections.sort(keys);
            StringBuilder s = new StringBuilder("{");
            for (String key : keys) { if (s.length() > 1) s.append(','); s.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))); }
            return s.append('}').toString();
        }
        if (o instanceof JSONArray) {
            JSONArray array = (JSONArray) o; StringBuilder s = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) { if (i > 0) s.append(','); s.append(canonical(array.get(i))); } return s.append(']').toString();
        }
        return o instanceof String ? JSONObject.quote((String) o) : String.valueOf(o);
    }
    static IllegalArgumentException bad(String reason) { return new IllegalArgumentException("INVALID_SKETCH: " + reason); }
}
