package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Locale;

/** Shared native raster brush planning and tile painting. */
public final class SketchPaintBrush {
    private static final int TILE_SIZE = 256, MAX_DABS = 8192;
    private static final long MAX_PIXEL_VISITS = 32000000L;
    private SketchPaintBrush() {}
    public interface Source { int sample(double paintX, double paintY) throws Exception; }

    private static final class Dab {
        final double x, y, radius, pressure;
        final int left, top, right, bottom;
        Dab(double x, double y, double radius, double pressure, int left, int top, int right, int bottom) {
            this.x=x; this.y=y; this.radius=radius; this.pressure=pressure;
            this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
    }

    public static final class Plan {
        public final JSONObject stroke;
        public final int dabCount;
        /** Grid indexes, never pixel offsets. This set is detached from caller input. */
        public final BitSet tileIndexes;
        final int width, height;
        private final Dab[] dabs;
        private final String mode;
        private final int color, seed;
        private final double opacity, flow, hardness, cos, sin;
        private final double lastX, lastY, lastPressure;
        private final boolean flat, grain, alphaLock, maximumCoverage;
        private Plan(JSONObject stroke, int width, int height, ArrayList<Dab> dabs,
                     BitSet tiles, String mode, int color, int seed, double opacity,
                     double flow, double hardness, double angle, boolean flat,
                     boolean grain, boolean alphaLock, boolean maximumCoverage, double[] lastPoint) {
            this.stroke=stroke; this.width=width; this.height=height;
            this.dabs=dabs.toArray(new Dab[0]); this.dabCount=this.dabs.length;
            this.tileIndexes=(BitSet)tiles.clone(); this.mode=mode; this.color=color;
            this.seed=seed; this.opacity=opacity; this.flow=flow; this.hardness=hardness;
            this.cos=Math.cos(Math.toRadians(angle)); this.sin=Math.sin(Math.toRadians(angle));
            this.flat=flat; this.grain=grain; this.alphaLock=alphaLock;this.maximumCoverage=maximumCoverage;
            this.lastX=lastPoint[0]/width; this.lastY=lastPoint[1]/height; this.lastPressure=lastPoint[2];
        }
        /** O(1) native cursor metadata for the visible ordered dab prefix. */
        public JSONObject cursorAt(double fraction) throws Exception {
            if(!Double.isFinite(fraction)||fraction<0||fraction>1)throw bad("Paint cursor fraction must be 0..1");
            if(fraction==1)return new JSONObject().put("x",lastX).put("y",lastY).put("pressure",lastPressure);
            Dab dab=dabs[fraction==0?0:Math.max(0,(int)Math.ceil(fraction*dabCount)-1)];
            return new JSONObject().put("x",dab.x/width).put("y",dab.y/height).put("pressure",fraction==0?0:dab.pressure);
        }
    }

    /** Validate and bound the complete operation before any raster tile can be changed. */
    public static Plan plan(JSONObject operation, int width, int height) throws Exception {
        checkCancelled();
        if(operation==null || width<1 || height<1 || width>2048 || height>2048
                || (long)width*height>4194304L) throw bad("Invalid paint canvas dimensions");
        SketchDocument.fields(operation,"type,objectId,strokeId,points,brush,alphaLock,clip,cloneSource,frameIndex");
        if(!"paint_stroke".equals(string(operation,"type"))) throw bad("Expected paint_stroke");
        String objectId=SketchDocument.id(operation,"objectId"), strokeId=SketchDocument.id(operation,"strokeId");
        Integer frameIndex=operation.has("frameIndex")?(int)SketchDocument.integer(operation,"frameIndex",0,1199):null;
        JSONObject brush=object(operation,"brush");
        SketchDocument.fields(brush,"mode,color,sizePx,opacity,flow,hardness,spacing,pressure,texture,seed,angle,tip,coverage");
        String mode=choice(brush,"mode","paint","erase","clone");
        String colorText=string(brush,"color").toUpperCase(Locale.ROOT);
        int color=SketchDocument.color(colorText);
        double size=SketchDocument.number(brush,"sizePx",.5,256);
        double opacity=number(brush,"opacity",1,0,1), flow=number(brush,"flow",1,0,1);
        double hardness=number(brush,"hardness",1,0,1), spacing=number(brush,"spacing",.2,.05,1);
        double angle=number(brush,"angle",0,-180,180);
        boolean pressure=bool(brush,"pressure",true), alphaLock=bool(operation,"alphaLock",false);
        String texture=brush.has("texture")?choice(brush,"texture","smooth","grain"):"smooth";
        String tip=brush.has("tip")?choice(brush,"tip","round","flat"):"round";
        int seed=brush.has("seed")?(int)SketchDocument.integer(brush,"seed",0,Integer.MAX_VALUE):0;
        String coverage=brush.has("coverage")?choice(brush,"coverage","maximum","accumulate"):null;
        if("erase".equals(mode) && alphaLock) throw bad("Erase cannot use alpha lock");
        if(operation.has("cloneSource") && !"clone".equals(mode)) throw bad("Only clone mode accepts cloneSource");
        JSONObject clone=null;
        if("clone".equals(mode)) {clone=cloneSource(object(operation,"cloneSource"));if(SketchCloneSourceSampling.filtered(clone))SketchCloneSourceSampling.requireTarget(width,height);}

        double clipX=0,clipY=0,clipWidth=1,clipHeight=1;
        JSONObject clip=null;
        if(operation.has("clip")) {
            JSONObject input=object(operation,"clip"); SketchDocument.fields(input,"x,y,width,height");
            clipX=SketchDocument.number(input,"x",0,1); clipY=SketchDocument.number(input,"y",0,1);
            clipWidth=SketchDocument.number(input,"width",Double.MIN_VALUE,1);
            clipHeight=SketchDocument.number(input,"height",Double.MIN_VALUE,1);
            if(clipX+clipWidth>1 || clipY+clipHeight>1) throw bad("Paint clip must fit the canvas");
            clip=new JSONObject().put("x",clipX).put("y",clipY).put("width",clipWidth).put("height",clipHeight);
        }
        int clipLeft=Math.max(0,(int)Math.ceil(clipX*width-.5));
        int clipTop=Math.max(0,(int)Math.ceil(clipY*height-.5));
        int clipRight=Math.min(width,(int)Math.ceil((clipX+clipWidth)*width-.5));
        int clipBottom=Math.min(height,(int)Math.ceil((clipY+clipHeight)*height-.5));

        JSONArray points=array(operation,"points");
        if(points.length()<1 || points.length()>2048) throw bad("Paint stroke requires 1..2048 pressure points");
        double[][] positions=new double[points.length()][3]; JSONArray normalizedPoints=new JSONArray();
        for(int i=0;i<points.length();i++) {
            checkCancelled(); Object value=points.get(i);
            if(!(value instanceof JSONArray) || ((JSONArray)value).length()!=3)
                throw bad("Each paint point requires x, y and pressure");
            JSONArray point=(JSONArray)value;
            double x=SketchDocument.scalar(point.get(0),0,1,"point x");
            double y=SketchDocument.scalar(point.get(1),0,1,"point y");
            double p=SketchDocument.scalar(point.get(2),0,1,"point pressure");
            positions[i][0]=x*width; positions[i][1]=y*height; positions[i][2]=p;
            normalizedPoints.put(new JSONArray().put(x).put(y).put(p));
        }
        JSONObject normalizedBrush=new JSONObject().put("mode",mode).put("color",colorText).put("sizePx",size)
                .put("opacity",opacity).put("flow",flow).put("hardness",hardness).put("spacing",spacing)
                .put("pressure",pressure).put("texture",texture).put("seed",seed).put("angle",angle).put("tip",tip);
        // An omitted field must keep historical journal/request identities exactly.
        if(coverage!=null)normalizedBrush.put("coverage",coverage);
        JSONObject normalized=new JSONObject().put("type","paint_stroke").put("objectId",objectId)
                .put("strokeId",strokeId).put("points",normalizedPoints).put("brush",normalizedBrush).put("alphaLock",alphaLock);
        if(frameIndex!=null) normalized.put("frameIndex",frameIndex);
        if(clip!=null) normalized.put("clip",clip);
        if(clone!=null) normalized.put("cloneSource",clone);

        boolean flat="flat".equals(tip); double cos=Math.cos(Math.toRadians(angle)),sin=Math.sin(Math.toRadians(angle));
        ArrayList<Dab> dabs=new ArrayList<>(); BitSet tiles=new BitSet(); long visits=0;
        visits=addDab(dabs,tiles,visits,positions[0][0],positions[0][1],positions[0][2],size,pressure,
                flat,cos,sin,width,clipLeft,clipTop,clipRight,clipBottom);
        for(int i=1;i<positions.length;i++) {
            checkCancelled(); double[] a=positions[i-1],b=positions[i];
            double distance=Math.hypot(b[0]-a[0],b[1]-a[1]);
            if(distance==0 && a[2]==b[2]) continue;
            if(pressure && a[2]==0 && b[2]==0) continue;
            double step=size*spacing*(pressure?Math.max(.05,Math.min(a[2],b[2])):1);
            long segments=Math.max(1,(long)Math.ceil(distance/step));
            if(segments>MAX_DABS-dabs.size()) throw bad("Paint stroke exceeds 8192 dabs");
            for(int j=1;j<=segments;j++) {
                double t=j/(double)segments;
                visits=addDab(dabs,tiles,visits,a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t,
                        size,pressure,flat,cos,sin,width,clipLeft,clipTop,clipRight,clipBottom);
            }
        }
        return new Plan(normalized,width,height,dabs,tiles,mode,color,seed,opacity,flow,hardness,angle,flat,"grain".equals(texture),alphaLock,"maximum".equals(coverage),positions[positions.length-1]);
    }

    private static long addDab(ArrayList<Dab> dabs,BitSet tiles,long visits,double x,double y,double p,
                               double size,boolean pressure,boolean flat,double cos,double sin,int width,
                               int clipLeft,int clipTop,int clipRight,int clipBottom) throws Exception {
        checkCancelled(); if(dabs.size()>=MAX_DABS) throw bad("Paint stroke exceeds 8192 dabs");
        double radius=size*(pressure?p:1)*.5;
        double horizontal=flat?Math.abs(cos)*radius+Math.abs(sin)*radius*.22:radius;
        double vertical=flat?Math.abs(sin)*radius+Math.abs(cos)*radius*.22:radius;
        int left=Math.max(clipLeft,(int)Math.floor(x-horizontal-1)),top=Math.max(clipTop,(int)Math.floor(y-vertical-1));
        int right=Math.min(clipRight,(int)Math.ceil(x+horizontal+1)),bottom=Math.min(clipBottom,(int)Math.ceil(y+vertical+1));
        if(radius<=0 || left>=right || top>=bottom) {left=top=right=bottom=0;}
        else {
            visits+=(long)(right-left)*(bottom-top);
            if(visits>MAX_PIXEL_VISITS) throw bad("Paint stroke exceeds 32000000 pixel visits");
            int columns=(width+TILE_SIZE-1)/TILE_SIZE;
            for(int ty=top/TILE_SIZE;ty<=(bottom-1)/TILE_SIZE;ty++)
                for(int tx=left/TILE_SIZE;tx<=(right-1)/TILE_SIZE;tx++) tiles.set(ty*columns+tx);
        }
        dabs.add(new Dab(x,y,radius,p,left,top,right,bottom)); return visits;
    }

    /** Paint into temporary tile pixels, committing only after all source reads succeed. */
    public static void apply(Bitmap target,Bitmap before,int tileX,int tileY,Plan plan,Source source,double fraction) throws Exception {
        checkCancelled();
        if(plan==null || target==null || target.isRecycled() || !target.isMutable()
                || target.getConfig()!=Bitmap.Config.ARGB_8888 || target.getWidth()!=TILE_SIZE || target.getHeight()!=TILE_SIZE)
            throw bad("Paint requires a mutable 256px ARGB tile");
        if(tileX<0 || tileY<0 || tileX>=(plan.width+TILE_SIZE-1)/TILE_SIZE || tileY>=(plan.height+TILE_SIZE-1)/TILE_SIZE)
            throw bad("Paint tile is outside the canvas");
        if(before!=null && (before.isRecycled() || before.getConfig()!=Bitmap.Config.ARGB_8888
                || before.getWidth()!=TILE_SIZE || before.getHeight()!=TILE_SIZE)) throw bad("Invalid paint baseline tile");
        if(!Double.isFinite(fraction) || fraction<0 || fraction>1) throw bad("Paint prefix must be 0..1");
        if("clone".equals(plan.mode) && source==null) throw bad("Clone requires a native source sampler");
        if(fraction==0 || plan.opacity==0 || plan.flow==0 || (plan.alphaLock && before==null)) return;
        int[] original=new int[TILE_SIZE*TILE_SIZE]; target.getPixels(original,0,TILE_SIZE,0,0,TILE_SIZE,TILE_SIZE);
        // Legacy flow accumulates. Explicit maximum retains the strongest dab's
        // feather instead; opacity caps either whole stroke against its baseline.
        float[] mask=new float[original.length];
        int[] baseline=null;
        if(plan.alphaLock) {baseline=new int[original.length];before.getPixels(baseline,0,TILE_SIZE,0,0,TILE_SIZE,TILE_SIZE);}
        int originX=tileX*TILE_SIZE,originY=tileY*TILE_SIZE;
        double progress=fraction*plan.dabCount; int complete=(int)Math.floor(progress),count=(int)Math.ceil(progress);
        for(int i=0;i<count;i++) {
            checkCancelled(); Dab dab=plan.dabs[i]; if(dab.radius<=0) continue;
            double prefix=i<complete?1:progress-complete;
            int left=Math.max(originX,dab.left),top=Math.max(originY,dab.top);
            int right=Math.min(originX+TILE_SIZE,dab.right),bottom=Math.min(originY+TILE_SIZE,dab.bottom);
            for(int y=top;y<bottom;y++) {
                checkCancelled();
                for(int x=left;x<right;x++) {
                    int offset=(y-originY)*TILE_SIZE+x-originX;
                    if(baseline!=null && (baseline[offset]>>>24)==0) continue;
                    double coverage=coverage(plan,dab,x+.5,y+.5);
                    if(coverage<=0) continue;
                    if(plan.grain) coverage*=grain(plan.seed,x,y,i);
                    double amount=coverage*plan.flow*prefix;
                    if(amount<=0) continue;
                    mask[offset]=plan.maximumCoverage?(float)Math.max(mask[offset],clamp(amount))
                            :(float)clamp(mask[offset]+(1-mask[offset])*amount);
                }
            }
        }
        int[] pixels=original.clone();
        for(int y=0;y<TILE_SIZE;y++) {
            checkCancelled();
            for(int x=0;x<TILE_SIZE;x++) {
                int offset=y*TILE_SIZE+x;double amount=mask[offset]*plan.opacity;
                if(amount<=0)continue;
                if("erase".equals(plan.mode))pixels[offset]=erase(original[offset],amount);
                else {
                    int paint="clone".equals(plan.mode)?source.sample(originX+x+.5,originY+y+.5):plan.color;
                    pixels[offset]=over(original[offset],paint,amount,baseline==null?-1:baseline[offset]>>>24);
                }
            }
        }
        checkCancelled(); target.setPixels(pixels,0,TILE_SIZE,0,0,TILE_SIZE,TILE_SIZE);
    }

    private static double coverage(Plan plan,Dab dab,double x,double y) {
        double dx=x-dab.x,dy=y-dab.y,edge,soft;
        if(plan.flat) {
            double localX=dx*plan.cos+dy*plan.sin,localY=-dx*plan.sin+dy*plan.cos;
            double insideX=dab.radius-Math.abs(localX),insideY=dab.radius*.22-Math.abs(localY);
            edge=clamp(Math.min(insideX,insideY)+.5);
            soft=plan.hardness==1?1:clamp(Math.min(insideX/(dab.radius*(1-plan.hardness)),insideY/(dab.radius*.22*(1-plan.hardness))));
        } else {
            double inside=dab.radius-Math.hypot(dx,dy);
            edge=clamp(inside+.5);
            soft=plan.hardness==1?1:clamp(inside/(dab.radius*(1-plan.hardness)));
        }
        return edge*soft;
    }

    private static double grain(int seed,int x,int y,int dab) {
        int hash=x*374761393+y*668265263+seed*1442695041+dab*1274126177;
        hash=(hash^(hash>>>13))*1274126177;hash^=hash>>>16;
        return .25+.75*((hash&0xffffffffL)/4294967295.0);
    }
    private static int erase(int destination,double amount) {
        int alpha=(int)Math.round((destination>>>24)*(1-clamp(amount)));
        return alpha==0?0:(alpha<<24)|(destination&0x00ffffff);
    }
    private static int over(int destination,int source,double amount,int lockedAlpha) {
        double sourceAlpha=(source>>>24)/255.0*clamp(amount); if(sourceAlpha<=0) return destination;
        double destinationAlpha=(destination>>>24)/255.0;
        double alpha=sourceAlpha+destinationAlpha*(1-sourceAlpha);
        int r,g,b;
        if(lockedAlpha>=0) {
            r=channel((source>>>16)&255,(destination>>>16)&255,sourceAlpha);
            g=channel((source>>>8)&255,(destination>>>8)&255,sourceAlpha);
            b=channel(source&255,destination&255,sourceAlpha);
        } else {
            r=(int)Math.round((((source>>>16)&255)*sourceAlpha+((destination>>>16)&255)*destinationAlpha*(1-sourceAlpha))/alpha);
            g=(int)Math.round((((source>>>8)&255)*sourceAlpha+((destination>>>8)&255)*destinationAlpha*(1-sourceAlpha))/alpha);
            b=(int)Math.round(((source&255)*sourceAlpha+(destination&255)*destinationAlpha*(1-sourceAlpha))/alpha);
        }
        int outAlpha=lockedAlpha>=0?lockedAlpha:(int)Math.round(alpha*255);
        return outAlpha==0?0:(outAlpha<<24)|(r<<16)|(g<<8)|b;
    }
    private static int channel(int source,int destination,double alpha) {return (int)Math.round(source*alpha+destination*(1-alpha));}
    private static double clamp(double value) {return Math.max(0,Math.min(1,value));}
    private static void checkCancelled() throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Paint cancelled");}
    private static IllegalArgumentException bad(String reason) {return SketchDocument.bad(reason);}
    private static String string(JSONObject o,String field) throws Exception {
        Object value=o.get(field);if(!(value instanceof String))throw bad(field+" must be text");return (String)value;
    }
    private static String choice(JSONObject o,String field,String... choices) throws Exception {
        String value=string(o,field);for(String candidate:choices)if(candidate.equals(value))return value;throw bad("Unknown "+field);
    }
    private static JSONObject object(JSONObject o,String field) throws Exception {
        Object value=o.get(field);if(!(value instanceof JSONObject))throw bad(field+" must be an object");return (JSONObject)value;
    }
    private static JSONArray array(JSONObject o,String field) throws Exception {
        Object value=o.get(field);if(!(value instanceof JSONArray))throw bad(field+" must be an array");return (JSONArray)value;
    }
    private static double number(JSONObject o,String field,double fallback,double min,double max) throws Exception {
        return o.has(field)?SketchDocument.number(o,field,min,max):fallback;
    }
    private static boolean bool(JSONObject o,String field,boolean fallback) throws Exception {
        if(!o.has(field))return fallback;SketchDocument.bool(o,field);return o.getBoolean(field);
    }
    private static JSONObject cloneSource(JSONObject source) throws Exception {
        SketchDocument.fields(source,"objectId,sha256,offsetX,offsetY,scale,rotation,mapping,sampling");
        String id=SketchDocument.id(source,"objectId"),sha=string(source,"sha256");
        if(!sha.matches("[a-f0-9]{64}"))throw bad("Clone source requires its owned SHA-256");
        JSONObject normalized=new JSONObject().put("objectId",id).put("sha256",sha);
        if(source.has("sampling"))normalized.put("sampling",SketchCloneSourceSampling.choice(source));
        if(source.has("mapping")) {
            if(source.has("offsetX") || source.has("offsetY") || source.has("scale") || source.has("rotation"))
                throw bad("Clone affine mapping cannot be combined with offset, scale or rotation");
            JSONArray input=array(source,"mapping");if(input.length()!=6)throw bad("Clone affine mapping requires six numbers");
            double[] coefficients=new double[6];JSONArray mapping=new JSONArray();
            for(int i=0;i<6;i++){coefficients[i]=SketchDocument.scalar(input.get(i),i<4?-8:-4,i<4?8:4,"clone mapping");mapping.put(coefficients[i]);}
            if(coefficients[0]*coefficients[3]-coefficients[1]*coefficients[2]==0)throw bad("Clone affine mapping is singular");
            normalized.put("mapping",mapping);
        } else {
            normalized.put("offsetX",number(source,"offsetX",0,-1,1)).put("offsetY",number(source,"offsetY",0,-1,1))
                    .put("scale",number(source,"scale",1,.01,8)).put("rotation",number(source,"rotation",0,-180,180));
        }
        return normalized;
    }
}
