package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic pressure brushes shared by touch, agent preview and export. */
public final class SketchBrushes {
    public static final String[] PRESETS={"ink","pencil","marker","airbrush","charcoal","calligraphy","hairline","engraving","graphite"};
    private SketchBrushes() {}
    public static JSONObject style(String preset,double opacity,double hardness,boolean pressure,double angle) throws Exception {
        JSONObject b=new JSONObject().put("preset",preset).put("opacity",opacity).put("hardness",hardness).put("pressure",pressure).put("angle",angle);validate(b);return b;
    }
    public static void validate(JSONObject b) throws Exception {
        SketchDocument.fields(b,"preset,opacity,hardness,pressure,angle");
        if(!java.util.Arrays.asList(PRESETS).contains(b.getString("preset")))throw SketchDocument.bad("Unknown brush preset");
        if(b.has("opacity"))SketchDocument.number(b,"opacity",0,1);
        if(b.has("hardness"))SketchDocument.number(b,"hardness",0,1);
        if(b.has("pressure"))SketchDocument.bool(b,"pressure");
        if(b.has("angle"))SketchDocument.number(b,"angle",-180,180);
    }
    public static void draw(Canvas c,JSONArray points,int w,int h,float width,Paint source,JSONObject b) throws Exception {
        if(b==null){SketchFrameRenderer.drawPressureStroke(c,points,w,h,width,source);return;}
        Paint p=new Paint(source);String preset=b.getString("preset");
        Paint alpha=new Paint();alpha.setXfermode(source.getXfermode());p.setXfermode(null);alpha.setAlpha((int)Math.round(255*b.optDouble("opacity",1)));
        JSONArray first=points.getJSONArray(0);float fx=(float)first.getDouble(0)*w,fy=(float)first.getDouble(1)*h;RectF bounds=new RectF(fx,fy,fx,fy);for(int i=1;i<points.length();i++){JSONArray a=points.getJSONArray(i);float x=(float)a.getDouble(0)*w,y=(float)a.getDouble(1)*h;bounds.left=Math.min(bounds.left,x);bounds.right=Math.max(bounds.right,x);bounds.top=Math.min(bounds.top,y);bounds.bottom=Math.max(bounds.bottom,y);}bounds.inset(-width,-width);
        int save=c.saveLayer(bounds,alpha);boolean pressure=b.optBoolean("pressure",true);
        if("graphite".equals(preset)){
            drawGraphite(c,points,w,h,width,p,pressure,(float)b.optDouble("hardness",.6));
        }else if("hairline".equals(preset)||"engraving".equals(preset)){
            drawRefinedInk(c,points,w,h,width,p,pressure,"engraving".equals(preset));
        }else if("ink".equals(preset)||"marker".equals(preset)) {
            JSONArray pp=points;if(!pressure||"marker".equals(preset)){pp=new JSONArray();for(int i=0;i<points.length();i++){JSONArray a=points.getJSONArray(i);pp.put(new JSONArray().put(a.getDouble(0)).put(a.getDouble(1)).put(1));}}
            SketchFrameRenderer.drawPressureStroke(c,pp,w,h,width,p);
        }else{
            float spacing=Math.max(.8f,width*("airbrush".equals(preset)?.18f:.12f));
            int stamp=0;
            for(int i=0;i<points.length();i++){
                JSONArray end=points.getJSONArray(i),start=points.getJSONArray(Math.max(0,i-1));
                float ax=(float)start.getDouble(0)*w,ay=(float)start.getDouble(1)*h,bx=(float)end.getDouble(0)*w,by=(float)end.getDouble(1)*h;
                int steps=i==0?1:Math.max(1,(int)Math.ceil(Math.hypot(bx-ax,by-ay)/spacing));
                for(int j=i==0?0:1;j<=steps;j++){
                    float t=j/(float)steps,x=ax+(bx-ax)*t,y=ay+(by-ay)*t;
                    float force=pressure?(float)(start.getDouble(2)+(end.getDouble(2)-start.getDouble(2))*t):1;
                    float radius=width*(.15f+.85f*force)/2;
                    p.setStyle(Paint.Style.FILL);p.setShader(null);
                    if("airbrush".equals(preset)) {
                        int color=source.getColor(),transparent=color&0x00ffffff;float hardness=(float)b.optDouble("hardness",.25);
                        p.setShader(new RadialGradient(x,y,Math.max(.1f,radius),new int[]{color,color,transparent},new float[]{0,Math.min(.98f,hardness),1},Shader.TileMode.CLAMP));c.drawCircle(x,y,radius,p);
                    }else if("calligraphy".equals(preset)) {
                        int rotated=c.save();c.rotate((float)b.optDouble("angle",35),x,y);c.drawOval(new RectF(x-radius,y-radius*.28f,x+radius,y+radius*.28f),p);c.restoreToCount(rotated);
                    }else {
                        // Fixed-seed grain makes saved strokes identical on every render.
                        int seed=stamp*1103515245+12345;int grains="charcoal".equals(preset)?18:10;
                        for(int n=0;n<grains;n++){seed=seed*1664525+1013904223;float dx=((seed>>>8)&65535)/32767.5f-1;seed=seed*1664525+1013904223;float dy=((seed>>>8)&65535)/32767.5f-1;if(dx*dx+dy*dy<=1)c.drawCircle(x+dx*radius,y+dy*radius,Math.max(.35f,radius*("charcoal".equals(preset)?.14f:.08f)),p);}
                    }
                    stamp++;
                }
            }
        }
        c.restoreToCount(save);
    }

    /** Continuous pressure core with fine, repeatable grain. Older pencil marks keep their renderer. */
    private static void drawGraphite(Canvas canvas,JSONArray points,int w,int h,float width,
                                      Paint source,boolean pressure,float hardness)throws Exception{
        JSONArray corePoints=points;
        if(!pressure){corePoints=new JSONArray();for(int i=0;i<points.length();i++){
            JSONArray point=points.getJSONArray(i);corePoints.put(new JSONArray()
                    .put(point.getDouble(0)).put(point.getDouble(1)).put(1));}}
        Paint core=new Paint(source);core.setShader(null);
        core.setAlpha(Math.round(source.getAlpha()*(.30f+.45f*hardness)));
        SketchFrameRenderer.drawPressureStroke(canvas,corePoints,w,h,width*.92f,core);
        Paint grain=new Paint(source);grain.setShader(null);grain.setStyle(Paint.Style.FILL);
        grain.setAlpha(Math.round(source.getAlpha()*(.08f+.12f*hardness)));
        float spacing=Math.max(.5f,width*.14f);int stamp=0;
        for(int i=0;i<corePoints.length();i++){
            JSONArray end=corePoints.getJSONArray(i),start=corePoints.getJSONArray(Math.max(0,i-1));
            float ax=(float)start.getDouble(0)*w,ay=(float)start.getDouble(1)*h;
            float bx=(float)end.getDouble(0)*w,by=(float)end.getDouble(1)*h;
            int steps=i==0?0:Math.max(1,(int)Math.ceil(Math.hypot(bx-ax,by-ay)/spacing));
            for(int j=i==0?0:1;j<=steps;j++){
                float t=steps==0?0:j/(float)steps,x=ax+(bx-ax)*t,y=ay+(by-ay)*t;
                float force=(float)(start.getDouble(2)+(end.getDouble(2)-start.getDouble(2))*t);
                float radius=width*(.15f+.85f*force)/2;
                int seed=stamp++*1103515245+12345;
                for(int n=0;n<4;n++){
                    seed=seed*1664525+1013904223;float dx=((seed>>>8)&65535)/32767.5f-1;
                    seed=seed*1664525+1013904223;float dy=((seed>>>8)&65535)/32767.5f-1;
                    if(dx*dx+dy*dy<=1)canvas.drawCircle(x+dx*radius,y+dy*radius,
                            Math.max(.08f,radius*.07f),grain);
                }
            }
        }
    }

    /**
     * Crisp pressure-tapered hair and fashion engraving with deterministic
     * thin secondary incision. Real style is preserved in original editable
     * strokes, not a decorative photo filter or imported bitmap.
     */
    private static void drawRefinedInk(Canvas canvas,JSONArray source,int w,int h,
                                       float strokeWidth,Paint paint,boolean pressure,
                                       boolean engraving)throws Exception{
        if(source.length()==0)return;
        JSONArray tapered=new JSONArray();
        int last=source.length()-1;
        for(int i=0;i<source.length();i++){
            JSONArray point=source.getJSONArray(i);
            double t=last==0?.5:i/(double)last;
            double endTaper=.10+.90*Math.min(1,Math.min(t,1-t)*5.0);
            double raw=pressure?point.getDouble(2):1;
            double force=Math.max(.015,Math.min(1,raw*endTaper));
            tapered.put(new JSONArray().put(point.getDouble(0))
                    .put(point.getDouble(1)).put(force));
        }
        Paint primary=new Paint(paint);
        primary.setShader(null);
        primary.setStrokeCap(Paint.Cap.ROUND);
        float base=strokeWidth*(engraving?.86f:.46f);
        SketchFrameRenderer.drawPressureStroke(canvas,tapered,w,h,base,primary);
        if(!engraving||source.length()<2)return;
        Paint cut=new Paint(paint);
        cut.setShader(null);
        cut.setStyle(Paint.Style.STROKE);
        cut.setStrokeCap(Paint.Cap.ROUND);
        cut.setStrokeJoin(Paint.Join.ROUND);
        cut.setStrokeWidth(Math.max(.25f,base*.105f));
        cut.setAlpha(Math.max(1,Math.round(paint.getAlpha()*.47f)));
        Path fine=new Path();
        for(int i=0;i<source.length();i++){
            JSONArray cur=source.getJSONArray(i);
            JSONArray left=source.getJSONArray(Math.max(0,i-1));
            JSONArray right=source.getJSONArray(Math.min(last,i+1));
            float x=(float)cur.getDouble(0)*w,y=(float)cur.getDouble(1)*h;
            float dx=(float)(right.getDouble(0)-left.getDouble(0))*w;
            float dy=(float)(right.getDouble(1)-left.getDouble(1))*h;
            float magnitude=(float)Math.hypot(dx,dy);
            float offset=Math.min(2.2f,base*.32f);
            if(magnitude>.00001f){x-=dy/magnitude*offset;y+=dx/magnitude*offset;}
            if(i==0)fine.moveTo(x,y);else fine.lineTo(x,y);
        }
        canvas.drawPath(fine,cut);
    }
}
