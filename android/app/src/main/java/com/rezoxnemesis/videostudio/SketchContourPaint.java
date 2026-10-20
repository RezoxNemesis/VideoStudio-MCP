package com.rezoxnemesis.videostudio;

import android.graphics.Path;
import android.graphics.Region;
import org.json.*;
import java.nio.charset.StandardCharsets;

/** Source-free area paint from an existing closed authored pen contour. No reference reads. */
final class SketchContourPaint {
 private SketchContourPaint(){}
 static final String SCHEMA="videostudio.paint.contour.v1";
 private static String stable(JSONObject value)throws Exception{return SketchDocument.canonical(new JSONObject(value.toString()));}
 static String digest(JSONObject value)throws Exception{return SketchDocument.sha256(stable(value).getBytes(StandardCharsets.UTF_8));}
 static void dimensions(int w,int h){if(w<256||h<256||w>2048||h>2048||(long)w*h>1048576)throw SketchDocument.bad("Contour paint is limited to 2048 per axis and 1048576 native pixels; no downsampling");}
 private static void identity(JSONObject value)throws Exception{
  if(value.getJSONArray("keys").length()!=0||value.has("shapeKeys")||!stable(value.getJSONObject("transform")).equals(stable(SketchDocument.transform())))throw SketchDocument.bad("Contour paint requires static identity transforms; bake or remove transforms explicitly first");
 }
 private static void authored(JSONObject object)throws Exception{
  if(!"path".equals(object.optString("kind"))||object.optBoolean("fill",false)||object.has("reference")||object.has("masterLink")||object.has("styleId")||object.has("gradient"))throw SketchDocument.bad("Choose a source-free unfilled authored pen path");
  identity(object);JSONArray commands=object.getJSONArray("commands");if(commands.length()<3||commands.length()>4096)throw SketchDocument.bad("Contour paint requires 3..4096 path commands");SketchDocument.validateCommands(commands);
  boolean open=false,closed=false;for(int i=0;i<commands.length();i++){String c=commands.getJSONArray(i).getString(0);if("M".equals(c)){if(open)throw SketchDocument.bad("Every pen subpath must close before another starts");open=true;}else if("Z".equals(c)){if(!open)throw SketchDocument.bad("Contour close has no open pen subpath");open=false;closed=true;}else if(!open)throw SketchDocument.bad("Contour segment has no open pen subpath");}
  if(open||!closed)throw SketchDocument.bad("Close every pen subpath before filling it");
 }
 static JSONObject pin(JSONObject document,String contourId)throws Exception{
  int w=document.getInt("width"),h=document.getInt("height");dimensions(w,h);JSONObject object=SketchDocument.object(document,contourId),layer=SketchDocument.owner(document,contourId);authored(object);identity(layer);SketchEditOps.requireUnlocked(layer);
  if(!layer.getBoolean("visible")||layer.getDouble("opacity")!=1||!"normal".equals(layer.optString("blendMode","normal")))throw SketchDocument.bad("Use a visible opaque normal layer for contour paint");
  JSONObject pin=new JSONObject().put("schema",SCHEMA).put("width",w).put("height",h).put("layerId",layer.getString("id")).put("object",SketchDocument.copy(object));pin.put("sha256",digest(pin));return pin;
 }
 static Path path(JSONObject pin)throws Exception{
  SketchDocument.fields(pin,"schema,width,height,layerId,object,sha256");if(!SCHEMA.equals(pin.getString("schema")))throw SketchDocument.bad("Unknown contour paint pin");int w=(int)SketchDocument.integer(pin,"width",256,2048),h=(int)SketchDocument.integer(pin,"height",256,2048);dimensions(w,h);SketchDocument.id(pin,"layerId");SketchSourceMask.hashField(pin,"sha256");JSONObject body=SketchDocument.copy(pin);body.remove("sha256");if(!pin.getString("sha256").equals(digest(body)))throw SketchDocument.bad("Contour paint source hash changed");JSONObject object=pin.getJSONObject("object");authored(object);Path path=SketchFrameRenderer.path(object.getJSONArray("commands"),w,h);path.setFillType(Path.FillType.EVEN_ODD);Region area=new Region();area.setPath(path,new Region(0,0,w,h));if(area.isEmpty())throw SketchDocument.bad("Closed contour has no native paintable area");return path;
 }
 static void request(JSONObject op)throws Exception{
  SketchDocument.fields(op,"type,objectId,strokeId,contourObjectId,expectedContourSha256,color,frameIndex");if(!"paint_fill".equals(op.getString("type")))throw SketchDocument.bad("Expected paint_fill");for(String key:new String[]{"objectId","strokeId","contourObjectId"})SketchDocument.id(op,key);SketchSourceMask.hashField(op,"expectedContourSha256");if(!(op.get("color") instanceof String)||SketchDocument.color(op.getString("color"))>>>24==0)throw SketchDocument.bad("Contour fill requires a nontransparent #AARRGGBB color");if(op.has("frameIndex"))SketchDocument.integer(op,"frameIndex",0,1199);
 }
 static final class Selection {final JSONObject target,before,pin;final Path path;final boolean moveUnderInk;Selection(JSONObject target,JSONObject before,JSONObject pin,Path path,boolean move){this.target=target;this.before=before;this.pin=pin;this.path=path;this.moveUnderInk=move;}}
 static Selection select(JSONObject document,JSONObject op)throws Exception{
  request(op);JSONObject pin=pin(document,op.getString("contourObjectId"));if(!op.getString("expectedContourSha256").equals(pin.getString("sha256")))throw SketchDocument.bad("Contour changed; inspect its current source hash before filling");Path path=path(pin);JSONObject target=SketchDocument.object(document,op.getString("objectId")),layer=SketchDocument.owner(document,target.getString("id"));if(!"paint".equals(target.getString("kind"))||!layer.getString("id").equals(pin.getString("layerId")))throw SketchDocument.bad("Contour and target paint cel must belong to the same layer");identity(target);JSONObject b=target.getJSONObject("bounds");if(b.getDouble("x")!=0||b.getDouble("y")!=0||b.getDouble("width")!=1||b.getDouble("height")!=1)throw SketchDocument.bad("Contour paint requires a full-canvas native paint cel");int frame=op.has("frameIndex")?(int)SketchDocument.integer(op,"frameIndex",0,document.getInt("frameCount")-1):target.getInt("frame");if(!SketchFrameRenderer.exposed(target,frame)||!SketchFrameRenderer.exposed(pin.getJSONObject("object"),frame))throw SketchDocument.bad("Contour and paint cel must both be exposed at the selected frame");JSONObject before=SketchDocument.copy(target.getJSONObject("paint"));SketchPaintStore.validateDescriptor(before);if(before.getInt("width")!=document.getInt("width")||before.getInt("height")!=document.getInt("height"))throw SketchDocument.bad("Contour paint dimensions differ");JSONArray objects=layer.getJSONArray("objects");int firstInk=objects.length(),at=-1;for(int i=0;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);if(!"paint".equals(object.getString("kind")))firstInk=Math.min(firstInk,i);if(target.getString("id").equals(object.getString("id")))at=i;}return new Selection(target,before,pin,path,at>firstInk);
 }
 static String emptyHash(int w,int h)throws Exception{return digest(new JSONObject().put("schema","videostudio.sketch.paint.snapshot.v1").put("width",w).put("height",h).put("tileSize",256).put("strokeCount",0).put("tiles",new JSONArray()));}
 static void placeUnderInk(JSONObject document,JSONObject event)throws Exception{
  JSONObject record=event.getJSONObject("stroke");if(!record.getBoolean("moveUnderInk"))return;JSONObject before=record.getJSONObject("beforePaint");if(!emptyHash(before.getInt("width"),before.getInt("height")).equals(before.getString("sha256")))throw SketchDocument.bad("Only a fresh empty base cel can move under ink automatically");String id=event.getString("objectId");JSONObject target=SketchDocument.object(document,id),layer=SketchDocument.owner(document,id);JSONArray objects=layer.getJSONArray("objects"),ordered=new JSONArray().put(target);for(int i=0;i<objects.length();i++)if(!id.equals(objects.getJSONObject(i).getString("id")))ordered.put(objects.getJSONObject(i));layer.put("objects",ordered);
 }
}
