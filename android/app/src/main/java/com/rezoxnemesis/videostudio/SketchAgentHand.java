package com.rezoxnemesis.videostudio;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

/**
 * A live pointer attached to accepted native operations, never a simulated drawing.
 * Worker preparation captures exact prefix geometry and frame transforms; the UI
 * looks up only the cursor for the prefix it has actually displayed.
 */
public final class SketchAgentHand {
    private SketchAgentHand() {}

    /** Stationary glyph for the actual selected control; it carries no canvas coordinates. */
    public static Drawable toolIndicator(Context context,float sizeDp){
        if(context==null||!Float.isFinite(sizeDp)||sizeDp<16||sizeDp>32)
            throw new IllegalArgumentException("Tool hand size must be 16..32 dp");
        int pixels=Math.max(1,Math.min(256,Math.round(sizeDp*context.getResources().getDisplayMetrics().density)));
        return new ToolHandDrawable(pixels);
    }
    private static final class ToolHandDrawable extends Drawable {
        private final int dimension;
        private int alpha=255;
        private ColorFilter filter;
        private final State glyph=new State(0,0,0,0,true,false,"","","","",0);
        ToolHandDrawable(int dimension){this.dimension=dimension;setBounds(0,0,dimension,dimension);}
        @Override public void draw(Canvas canvas){
            Rect bounds=getBounds();if(bounds.isEmpty()||alpha==0)return;
            float size=Math.min(bounds.width(),bounds.height()),left=bounds.exactCenterX()-size*.5f,top=bounds.exactCenterY()-size*.5f;
            int save=canvas.save();
            try{
                canvas.clipRect(bounds);
                if(alpha<255||filter!=null){Paint composite=new Paint();composite.setAlpha(alpha);composite.setColorFilter(filter);canvas.saveLayer(new RectF(bounds),composite);}
                SketchAgentHand.draw(canvas,glyph,left+size*.14f,top+size*.82f,size*.9f);
            }finally{canvas.restoreToCount(save);}
        }
        @Override public void setAlpha(int value){alpha=Math.max(0,Math.min(255,value));invalidateSelf();}
        @Override public int getAlpha(){return alpha;}
        @Override public void setColorFilter(ColorFilter filter){this.filter=filter;invalidateSelf();}
        @Override public ColorFilter getColorFilter(){return filter;}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        @Override public int getIntrinsicWidth(){return dimension;}
        @Override public int getIntrinsicHeight(){return dimension;}
    }

    public static final class State {
        /** Full document normalized coordinates after object and layer transforms. */
        public final double x, y, pressure, sizePx;
        public final boolean visible, drawing;
        public final String objectId, toolLabel, caption, operationType;
        public final int color;
        private State(double x,double y,double pressure,double sizePx,boolean visible,boolean drawing,
                      String objectId,String toolLabel,String caption,String operationType,int color) {
            this.x=x;this.y=y;this.pressure=pressure;this.sizePx=sizePx;this.visible=visible;this.drawing=drawing;
            this.objectId=objectId;this.toolLabel=toolLabel;this.caption=caption;this.operationType=operationType;this.color=color;
        }
        public static State hidden() {return new State(0,0,0,0,false,false,"","","","",0);}
        /** Preserve the last displayed position while the next actual prefix is prepared. */
        public State paused(String caption) {
            return new State(x,y,pressure,sizePx,visible,false,objectId,toolLabel,
                    caption==null?this.caption:caption,operationType,color);
        }
    }

    private interface Cursor {double[] at(double fraction)throws Exception;}

    /** Detached geometry and tool state; lookup performs no file I/O or document scan. */
    public static final class Track {
        private final Cursor cursor;
        private final float[] matrix;
        private final int width,height,color;
        private final double sizePx;
        private final boolean drawing,marksVisible,pressureControlsPaint;
        private final String objectId,toolLabel,caption,operationType;
        private Track(Cursor cursor,float[] matrix,int width,int height,int color,double sizePx,
                      boolean drawing,boolean marksVisible,boolean pressureControlsPaint,String objectId,String toolLabel,
                      String caption,String operationType) {
            this.cursor=cursor;this.matrix=matrix;this.width=width;this.height=height;this.color=color;this.sizePx=sizePx;
            this.drawing=drawing;this.marksVisible=marksVisible;this.objectId=objectId;this.toolLabel=toolLabel;
            this.pressureControlsPaint=pressureControlsPaint;
            this.caption=caption;this.operationType=operationType;
        }
        public State at(double fraction) {
            if(!Double.isFinite(fraction)||fraction<0||fraction>1)return State.hidden();
            if(cursor==null)return new State(0,0,0,sizePx,false,false,objectId,toolLabel,caption,operationType,color);
            try {
                double[] local=cursor.at(fraction);
                double px=local[0]*width,py=local[1]*height;
                double x=(matrix[0]*px+matrix[1]*py+matrix[2])/width;
                double y=(matrix[3]*px+matrix[4]*py+matrix[5])/height;
                if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(local[2]))return State.hidden();
                return new State(x,y,local[2],sizePx,true,
                        drawing&&marksVisible&&fraction>0&&(!pressureControlsPaint||local[2]>0),objectId,toolLabel,caption,operationType,color);
            }catch(Exception unexpected){return State.hidden();}
        }
        public State cursorAt(double fraction) {return at(fraction);}
        public static Track hidden() {return new Track(null,identity(),1,1,0,0,false,false,false,"","","","");}
    }

    /** Caller supplies the accepted event and its presented document on a worker. */
    public static Track prepare(JSONObject document,JSONObject visualOperation,int frameIndex)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())
            throw new IllegalStateException("Agent hand geometry preparation requires a background worker");
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Agent hand preparation cancelled");
        if(document==null||visualOperation==null)return Track.hidden();
        try {
            int width=document.getInt("width"),height=document.getInt("height");
            if(width<1||height<1||frameIndex<0||frameIndex>=document.getInt("frameCount"))return Track.hidden();
            String type=visualOperation.optString("type","");
            if(!supported(type))return Track.hidden();
            JSONObject submitted=visualOperation.optJSONObject("object");
            String id=submitted!=null?submitted.optString("id",""):visualOperation.optString("objectId","");
            JSONObject object=null,layer=null;JSONArray layers=document.getJSONArray("layers");
            if(!id.isEmpty())for(int i=0;i<layers.length()&&object==null;i++){
                JSONObject candidate=layers.getJSONObject(i);JSONArray objects=candidate.getJSONArray("objects");
                for(int j=0;j<objects.length();j++)if(id.equals(objects.getJSONObject(j).optString("id"))){
                    object=objects.getJSONObject(j);layer=candidate;break;
                }
            }
            if(layer==null&&visualOperation.has("layerId"))for(int i=0;i<layers.length();i++){
                JSONObject candidate=layers.getJSONObject(i);
                if(visualOperation.getString("layerId").equals(candidate.optString("id"))){layer=candidate;break;}
            }
            String kind=object==null?"":object.optString("kind","");
            String tool=tool(type,kind,object,visualOperation),caption=caption(type,tool,visualOperation);
            int color=object==null?0xFF65E9B2:SketchDocument.color(object.optString("color","#FF65E9B2"));
            double size=object==null?0:object.optDouble("width",0)*Math.min(width,height);
            JSONObject objectTransform=object==null?SketchDocument.transform():SketchDocument.transformAt(object,frameIndex);
            JSONObject layerTransform=layer==null?SketchDocument.transform():SketchDocument.transformAt(layer,frameIndex);
            Matrix combined=new Matrix();combined.setConcat(SketchFrameRenderer.matrix(layerTransform,width,height),
                    SketchFrameRenderer.matrix(objectTransform,width,height));
            float[] transform=new float[9];combined.getValues(transform);
            boolean marks=object!=null&&layer!=null&&layer.optBoolean("visible",true)&&
                    layer.optDouble("opacity",1)>0&&layerTransform.optDouble("opacity",1)>0&&
                    objectTransform.optDouble("opacity",1)>0;
            Cursor cursor=null;boolean drawing=false,pressureControlsPaint=false;
            if(object!=null&&"paint_stroke".equals(type)) {
                JSONObject request=visualOperation.getJSONObject("stroke").getJSONObject("request");
                if(!id.equals(request.getString("objectId")))return Track.hidden();
                SketchPaintBrush.Plan plan=SketchPaintBrush.plan(request,width,height);
                JSONObject brush=plan.stroke.getJSONObject("brush");
                tool=paintTool(brush.getString("mode"));color=SketchDocument.color(brush.getString("color"));
                size=brush.getDouble("sizePx");caption=tool+" · "+number(size)+" px"+
                        ("paint".equals(brush.getString("mode"))?" · "+brush.getString("color"):"");
                marks=marks&&brush.optDouble("opacity",1)>0&&brush.optDouble("flow",1)>0;
                if("paint".equals(brush.getString("mode")))marks=marks&&(color>>>24)>0;
                pressureControlsPaint=brush.optBoolean("pressure",true);
                cursor=fraction->{JSONObject position=plan.cursorAt(fraction);return new double[]{position.getDouble("x"),position.getDouble("y"),position.getDouble("pressure")};};
                drawing=true;
            }else if(object!=null&&isDrawing(type,visualOperation)&&isVector(kind)){
                if("stroke".equals(kind)||"eraser".equals(kind)||"line".equals(kind)) {
                    JSONArray submittedPoints=object.getJSONArray("points");
                    int first=0,last=submittedPoints.length();
                    if("line".equals(kind))last=Math.min(2,last);
                    if("append_points".equals(type))first=Math.max(0,last-visualOperation.getJSONArray("points").length()-1);
                    cursor=points(submittedPoints,first,last);drawing=cursor!=null;
                }else{
                    Path path=geometry(object,kind,width,height);cursor=path(path,width,height);drawing=cursor!=null;
                }
                if(object.has("brush"))marks=marks&&object.getJSONObject("brush").optDouble("opacity",1)>0;
                if(!"eraser".equals(kind))marks=marks&&(color>>>24)>0;
            }else if(object!=null){
                double[] selected=center(object,width,height);if(selected!=null)cursor=fraction->selected.clone();
            }else if(layer!=null){cursor=fraction->new double[]{.5,.5,0};}
            if(drawing&&!SketchFrameRenderer.exposed(object,frameIndex))cursor=null;
            return new Track(cursor,transform,width,height,color,size,drawing,marks,pressureControlsPaint,id,tool,caption,type);
        }catch(org.json.JSONException|IllegalArgumentException malformed){return Track.hidden();}
    }

    private static boolean supported(String type){
        switch(type){case "paint_fill":case "precision_contour":case "add_object":case "paint_stroke":case "append_points":case "update_object":case "delete_object":
            case "add_layer":case "update_layer":case "delete_layer":case "reorder_layer":case "reorder_object":case "set_canvas":case "set_timing":
            case "set_transform":case "set_keyframe":case "set_exposure":case "set_reference_visibility":
            case "duplicate_cel":case "clear_cel":case "insert_frame":case "delete_frame":case "add_paint_cel":return true;
            default:return false;}
    }

    private static boolean isVector(String kind){
        return "stroke".equals(kind)||"eraser".equals(kind)||"line".equals(kind)||"path".equals(kind)||
                "polygon".equals(kind)||"rect".equals(kind)||"ellipse".equals(kind);
    }
    private static boolean isDrawing(String type,JSONObject operation){
        if("precision_contour".equals(type)||"add_object".equals(type)||"append_points".equals(type))return true;
        if(!"update_object".equals(type))return false;
        JSONObject patch=operation.optJSONObject("patch");
        return patch!=null&&(patch.has("points")||patch.has("commands")||patch.has("bounds"));
    }
    private static Cursor points(JSONArray points,int first,int last)throws Exception {
        if(first<0||last>points.length()||first>=last)return null;
        int count=last-first;double[][] positions=new double[count][3];double[] distances=new double[count];
        for(int i=0;i<count;i++){
            JSONArray point=points.getJSONArray(first+i);if(point.length()!=3)return null;
            for(int j=0;j<3;j++){double value=point.getDouble(j);if(!Double.isFinite(value))return null;positions[i][j]=value;}
            if(i>0)distances[i]=distances[i-1]+Math.hypot(positions[i][0]-positions[i-1][0],positions[i][1]-positions[i-1][1]);
        }
        return fraction->{
            if(fraction==0)return new double[]{positions[0][0],positions[0][1],0};
            if(fraction==1||distances[count-1]==0)return positions[count-1].clone();
            double distance=distances[count-1]*fraction;int low=1,high=count-1;
            while(low<high){int middle=(low+high)>>>1;if(distances[middle]<distance)low=middle+1;else high=middle;}
            int index=low;double span=distances[index]-distances[index-1];
            double part=span==0?1:(distance-distances[index-1])/span;double[] result=new double[3];
            for(int j=0;j<3;j++)result[j]=positions[index-1][j]+(positions[index][j]-positions[index-1][j])*part;
            return result;
        };
    }

    private static Path geometry(JSONObject object,String kind,int width,int height)throws Exception {
        if("path".equals(kind))return SketchFrameRenderer.path(object.getJSONArray("commands"),width,height);
        Path path=new Path();
        if("polygon".equals(kind)){
            JSONArray points=object.getJSONArray("points");
            for(int i=0;i<points.length();i++){JSONArray point=points.getJSONArray(i);float x=(float)point.getDouble(0)*width,y=(float)point.getDouble(1)*height;
                if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}
            path.close();
        }else{
            RectF bounds=SketchFrameRenderer.bounds(object.getJSONObject("bounds"),width,height);
            if("rect".equals(kind))path.addRect(bounds,Path.Direction.CW);else path.addOval(bounds,Path.Direction.CW);
        }
        return path;
    }

    /** Native curve lookup uses the same pixel arc lengths as the renderer's prefix. */
    private static Cursor path(Path path,int width,int height)throws Exception {
        ArrayList<PathMeasure> measures=new ArrayList<>();ArrayList<Double> lengths=new ArrayList<>();
        PathMeasure complete=new PathMeasure(path,false);double total=0;
        do{
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Agent path preparation cancelled");
            float length=complete.getLength();
            if(length>0){Path contour=new Path();complete.getSegment(0,length,contour,true);
                PathMeasure measure=new PathMeasure(contour,false);measures.add(measure);total+=length;lengths.add(total);}
        }while(complete.nextContour());
        if(measures.isEmpty())return null;
        final double distance=total;
        return new Cursor(){
            public synchronized double[] at(double fraction){
                double selected=distance*fraction;int low=0,high=lengths.size()-1;
                while(low<high){int middle=(low+high)>>>1;if(lengths.get(middle)<selected)low=middle+1;else high=middle;}
                int index=low;float local=(float)(selected-(index==0?0:lengths.get(index-1)));
                PathMeasure measure=measures.get(index);local=Math.max(0,Math.min(local,measure.getLength()));
                float[] position=new float[2];if(!measure.getPosTan(local,position,null))return new double[]{Double.NaN,Double.NaN,0};
                return new double[]{position[0]/(double)width,position[1]/(double)height,fraction==0?0:1};
            }
        };
    }

    private static double[] center(JSONObject object,int width,int height)throws Exception{
        JSONObject bounds=object.optJSONObject("bounds");
        if(bounds!=null)return new double[]{bounds.getDouble("x")+bounds.getDouble("width")*.5,bounds.getDouble("y")+bounds.getDouble("height")*.5,0};
        JSONArray points=object.optJSONArray("points");
        if(points==null&&"path".equals(object.optString("kind"))){
            Path shape=SketchFrameRenderer.path(object.getJSONArray("commands"),width,height);RectF extent=new RectF();
            shape.computeBounds(extent,true);if(shape.isEmpty())return null;
            return new double[]{extent.centerX()/width,extent.centerY()/height,0};
        }
        if(points==null||points.length()==0)return null;
        double left=Double.POSITIVE_INFINITY,top=left,right=Double.NEGATIVE_INFINITY,bottom=right;
        for(int i=0;i<points.length();i++){JSONArray point=points.getJSONArray(i);double x=point.getDouble(0),y=point.getDouble(1);
            left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
        return new double[]{(left+right)*.5,(top+bottom)*.5,0};
    }
    private static String paintTool(String mode){return "erase".equals(mode)?"Eraser":"clone".equals(mode)?"Clone brush":"Brush";}
    private static String tool(String type,String kind,JSONObject object,JSONObject operation){
        if("paint_fill".equals(type))return "Contour fill";
        if("precision_contour".equals(type))return "Precision pen";
        if("paint_stroke".equals(type))return "Brush";
        if("set_reference_visibility".equals(type))return "Reference";
        if("set_transform".equals(type))return "Transform";
        if("set_keyframe".equals(type))return "Keyframe";
        if("set_exposure".equals(type))return "Exposure";
        if(type.endsWith("layer"))return "Layers";
        if("set_timing".equals(type)||"insert_frame".equals(type)||"delete_frame".equals(type))return "Timing";
        if("set_canvas".equals(type))return "Canvas";
        if("delete_object".equals(type))return "Delete";
        if("duplicate_cel".equals(type)||"clear_cel".equals(type)||"add_paint_cel".equals(type))return "Cel tools";
        if("image".equals(kind))return "Reference";
        JSONObject patch=operation.optJSONObject("patch");
        if("update_object".equals(type)&&patch!=null){if(patch.has("brush"))return "Brush settings";if(patch.has("color"))return "Colour";}
        if("eraser".equals(kind))return "Eraser";
        if("stroke".equals(kind)){
            JSONObject brush=object==null?null:object.optJSONObject("brush");String preset=brush==null?"ink":brush.optString("preset","ink");
            if(preset.isEmpty())preset="ink";
            return preset.substring(0,1).toUpperCase(Locale.ROOT)+preset.substring(1);
        }
        switch(kind){case "line":return "Line";case "path":return "Path";case "polygon":return "Polygon";
            case "rect":return "Rectangle";case "ellipse":return "Ellipse";case "text":return "Text";case "paint":return "Paint cel";default:return "Workspace";}
    }
    private static String caption(String type,String tool,JSONObject operation){
        if("set_reference_visibility".equals(type))return operation.optBoolean("visible",true)?"Show reference":"Hide reference";
        if("precision_contour".equals(type))return "Draw precision pen";
        if("add_object".equals(type))return "Reference".equals(tool)?"Place reference":"Text".equals(tool)?"Add text":"Draw "+tool.toLowerCase(Locale.ROOT);
        if("append_points".equals(type))return "Continue "+tool.toLowerCase(Locale.ROOT);
        if("set_transform".equals(type))return "Apply transform";
        if("set_keyframe".equals(type))return operation.optBoolean("remove",false)?"Remove keyframe":"Set keyframe";
        if("update_object".equals(type))return "Update "+tool.toLowerCase(Locale.ROOT);
        return type.replace('_',' ');
    }
    private static String number(double value){return value==Math.rint(value)?Long.toString((long)value):String.format(Locale.ROOT,"%.1f",value);}
    private static float[] identity(){return new float[]{1,0,0,0,1,0,0,0,1};}

    /**
     * Draw a compact glove and pencil after the canvas transform is restored.
     * screenX/screenY are the actual mapped tip, not a guessed target or UI tap.
     * This overlay is never rendered into a saved drawing or export.
     */
    public static void draw(Canvas canvas,State state,float screenX,float screenY,float sizePx) {
        if(canvas==null||state==null||!state.visible||!Float.isFinite(screenX)||!Float.isFinite(screenY)||
                !Float.isFinite(sizePx)||sizePx<=0)return;
        float size=Math.min(256,sizePx);int save=canvas.save();
        try{
            canvas.translate(screenX,screenY);canvas.scale(size/28f,size/28f);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(state.drawing?0xCC65E9B2:0x99FFFFFF);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.4f);
            canvas.drawCircle(0,0,state.drawing?3.4f:2.8f,paint);
            Path glove=new Path();glove.moveTo(8,-12);glove.cubicTo(8,-17,11,-20,13,-17);
            glove.lineTo(15,-20);glove.cubicTo(16,-22,19,-20,18,-17);glove.lineTo(20,-18);
            glove.cubicTo(23,-20,24,-16,22,-13);glove.lineTo(24,-12);glove.cubicTo(27,-12,25,-8,24,-6);
            glove.lineTo(20,1);glove.lineTo(12,0);glove.cubicTo(10,-3,7,-3,5,-6);
            glove.cubicTo(3,-9,5,-12,8,-10);glove.close();
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xFFF4F7F6);canvas.drawPath(glove,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setColor(0xFF142E2A);paint.setStrokeWidth(1.5f);canvas.drawPath(glove,paint);
            canvas.drawLine(12,-13,11,-8,paint);canvas.drawLine(17,-14,15,-8,paint);canvas.drawLine(21,-12,18,-7,paint);
            if(state.drawing){
                paint.setStrokeWidth(3.2f);paint.setColor(0xFF142E2A);canvas.drawLine(1,-1,11,-15,paint);
                paint.setStrokeWidth(1.6f);paint.setColor(0xFF65E9B2);canvas.drawLine(3,-4,10,-14,paint);
                paint.setStrokeWidth(1.4f);paint.setColor(0xFFFFFFFF);canvas.drawLine(0,0,2,-3,paint);
            }else{
                paint.setStrokeWidth(3.8f);paint.setColor(0xFF142E2A);canvas.drawLine(0,0,8,-10,paint);
                paint.setStrokeWidth(2.1f);paint.setColor(0xFFF4F7F6);canvas.drawLine(1,-1,8,-10,paint);
            }
        }finally{canvas.restoreToCount(save);}
    }
}
