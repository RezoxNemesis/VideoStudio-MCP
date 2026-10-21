package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native Sketch Studio pen to editable Bézier-path bridge.
 *
 * User-authored stylus points are reduced with a measured pixel deviation
 * bound by OMEGA's actual fitter on SketchEditorSession's worker. We never
 * invent anatomy, hidden views or replacement source pixels.
 *
 * An ordinary sketch_edit operation (and hence the existing revision check,
 * drawing policy, atomic history, undo and same renderer) owns the commit.
 */
final class SketchPrecisionContour {
    private SketchPrecisionContour(){}

    static void apply(JSONObject doc,JSONObject op) throws Exception {
        if(doc==null||op==null)throw new IllegalArgumentException("Sketch and precise pen operation required");
        SketchDocument.fields(op,
                "type,layerId,objectId,points,closed,tolerancePx,tension,width,fill,color,frame,exposure,pressureOutline,brush");
        if(!"precision_contour".equals(op.getString("type")))
            throw new IllegalArgumentException("Only authored precision contours are supported");
        String layerId=SketchDocument.id(op,"layerId");
        String objectId=SketchDocument.id(op,"objectId");
        JSONObject layer=SketchDocument.layer(doc,layerId);
        SketchEditOps.requireUnlocked(layer);
        int width=doc.getInt("width"),height=doc.getInt("height");
        int frame=op.has("frame")?(int)SketchDocument.integer(op,"frame",0,doc.getInt("frameCount")-1):0;
        int exposure=op.has("exposure")?(int)SketchDocument.integer(
                op,"exposure",1,doc.getInt("frameCount")-frame):1;
        double tolerance=op.has("tolerancePx")?
                SketchDocument.number(op,"tolerancePx",.25,12):1.8;
        double tension=op.has("tension")?
                SketchDocument.number(op,"tension",0,1):.9;
        double stroke=op.has("width")?
                SketchDocument.number(op,"width",.0005,.2):.008;
        String color=op.optString("color","#FF161C2B");
        SketchDocument.color(color);
        boolean closed=flag(op,"closed",false);
        boolean outline=flag(op,"pressureOutline",false);
        boolean fill=flag(op,"fill",false);
        if(outline&&closed)
            throw new IllegalArgumentException("Pressure outline needs an open stylus contour");
        JSONObject brush=null;
        if(op.has("brush")){
            if(!(op.get("brush") instanceof JSONObject))throw SketchDocument.bad("Fitted brush settings must be an object");
            brush=SketchDocument.copy(op.getJSONObject("brush"));SketchBrushes.validate(brush);
            if(closed||outline||fill)throw SketchDocument.bad("Fitted pressure brushes require an open unfilled authored centerline");
            SketchFittedBrush.active();
        }
        JSONArray raw=op.getJSONArray("points");
        if(raw.length()<(closed?3:2)||raw.length()>OmegaBezierFitter.MAX_SAMPLES)
            throw new IllegalArgumentException("Use 2..4096 authored stylus points per contour");
        List<OmegaBezierFitter.Point> points=new ArrayList<>(raw.length());
        for(int i=0;i<raw.length();i++){
            JSONArray point=raw.getJSONArray(i);
            if(point.length()<2||point.length()>4)
                throw new IllegalArgumentException("A pen point needs x,y[,pressure[,corner]]");
            double x=coordinate(point,0),y=coordinate(point,1);
            double pressure=point.length()>=3?coordinate(point,2):1;
            boolean corner=false;
            if(point.length()==4){
                Object value=point.get(3);
                if(!(value instanceof Boolean))
                    throw new IllegalArgumentException("Explicit pen corner must be boolean");
                corner=(Boolean)value;
            }
            points.add(new OmegaBezierFitter.Point(x,y,pressure,corner));
        }
        OmegaBezierFitter.StrokeFit result=OmegaBezierFitter.fitStroke(
                points,width,height,tolerance,closed,tension);
        if(brush!=null){
            JSONArray centerline=SketchFittedBrush.points(result.contour,points,width,height);
            JSONObject artwork=new JSONObject().put("id",objectId).put("kind","stroke")
                    .put("points",centerline).put("brush",brush).put("color",color)
                    .put("width",stroke).put("fill",false).put("frame",frame).put("exposure",exposure);
            SketchFittedBrush.active();
            SketchEditOps.appendObject(doc,new JSONObject().put("type","add_object").put("layerId",layerId).put("object",artwork));
            return;
        }
        List<OmegaBezierFitter.Command> commands=outline?
                OmegaBezierFitter.pressureOutline(result.contour,stroke,width,height):
                OmegaBezierFitter.nativeCommands(result.contour);
        JSONArray serialized=new JSONArray();
        for(OmegaBezierFitter.Command command:commands){
            JSONArray row=new JSONArray().put(command.kind);
            for(double scalar:command.values())row.put(scalar);
            serialized.put(row);
        }
        JSONObject artwork=new JSONObject()
                .put("id",objectId).put("kind","path")
                .put("color",color).put("width",stroke)
                .put("fill",outline||fill)
                .put("frame",frame).put("exposure",exposure)
                .put("commands",serialized);
        // No external image insertion, no bitmap tracing, no layer bypass.
        SketchEditOps.appendObject(doc,new JSONObject()
                .put("type","add_object").put("layerId",layerId).put("object",artwork));
    }

    private static double coordinate(JSONArray point,int index)throws Exception{
        Object value=point.get(index);
        if(!(value instanceof Number))
            throw new IllegalArgumentException("Pen coordinates and pressure must be numeric");
        double d=((Number)value).doubleValue();
        if(!Double.isFinite(d)||d<0||d>1)
            throw new IllegalArgumentException("Pen coordinates and pressure must be in [0,1]");
        return d;
    }
    private static boolean flag(JSONObject object,String name,boolean def)throws Exception{
        if(!object.has(name))return def;
        Object value=object.get(name);
        if(!(value instanceof Boolean))
            throw new IllegalArgumentException(name+" must be boolean");
        return (Boolean)value;
    }
}
