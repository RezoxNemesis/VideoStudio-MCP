package com.rezoxnemesis.videostudio;
import org.json.*;
import java.util.Locale;

/** Artist-authored Bezier ink stored as ordinary editable Sketch Studio objects. */
final class JungleCelInk {
    final JSONObject layer;final double ox,oy,scale,angle;int serial;
    JungleCelInk(JSONObject layer){this(layer,0,0,1,0);}
    JungleCelInk(JSONObject l,double x,double y,double s,double a){layer=l;ox=x;oy=y;scale=s;angle=Math.toRadians(a);}
    JungleCelInk at(double x,double y,double s,double a){double c=Math.cos(angle),n=Math.sin(angle);return new JungleCelInk(layer,ox+scale*(x*c-y*n),oy+scale*(x*n+y*c),scale*s,Math.toDegrees(angle)+a);}
    double x(double x,double y){return clamp((ox+scale*(x*Math.cos(angle)-y*Math.sin(angle)))/1280);}
    double y(double x,double y){return clamp((oy+scale*(x*Math.sin(angle)+y*Math.cos(angle)))/720);}
    static double clamp(double n){return Math.max(0,Math.min(1,n));}
    static String color(String c){return c.length()==7?"#FF"+c.substring(1):c;}
    Shape ink(String fill,String outline,double weight){return new Shape(fill,outline,weight);}
    void poly(String fill,String stroke,double weight,double...xy)throws Exception {Shape s=ink(fill,stroke,weight).m(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)s.l(xy[i],xy[i+1]);s.z().end();}
    void ellipse(String fill,String stroke,double weight,double x,double y,double rx,double ry)throws Exception {double k=.55228475;ink(fill,stroke,weight).m(x-rx,y).c(x-rx,y-k*ry,x-k*rx,y-ry,x,y-ry).c(x+k*rx,y-ry,x+rx,y-k*ry,x+rx,y).c(x+rx,y+k*ry,x+k*rx,y+ry,x,y+ry).c(x-k*rx,y+ry,x-rx,y+k*ry,x-rx,y).z().end();}
    void line(String c,double weight,double...xy)throws Exception {Shape s=ink(null,c,weight).m(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)s.l(xy[i],xy[i+1]);s.end();}
    void text(String text,String c,double cx,double cy,double size)throws Exception {
        text(text,c,cx,cy,size,1178);
    }
    void text(String text,String c,double cx,double cy,double size,double width)throws Exception {
        double left=clamp(x(cx,cy)-width*Math.abs(scale)/2560),top=y(cx,cy),span=Math.min(1-left,width*Math.abs(scale)/1280);
        JSONObject o=base("text",color(c),true,1).put("text",text).put("fontFamily","sans-serif-condensed").put("fontStyle","bold").put("textAlign","center").put("fontSize",size/720)
            .put("bounds",new JSONObject().put("x",left).put("y",top).put("width",span).put("height",Math.min(.12,1-top)));layer.getJSONArray("objects").put(o);
    }
    JSONObject base(String kind,String c,boolean fill,double weight)throws Exception {return new JSONObject().put("id",layer.getString("id")+"_p"+layer.getJSONArray("objects").length()).put("kind",kind).put("frame",0).put("exposure",1).put("color",c).put("fill",fill).put("width",Math.max(.0005,Math.min(.2,weight*Math.abs(scale)/720))).put("transform",SketchDocument.transform()).put("keys",new JSONArray());}
    final class Shape {
        final String fill,outline;final double weight;final JSONArray commands=new JSONArray();
        Shape(String fill,String outline,double weight){this.fill=fill;this.outline=outline;this.weight=weight;}
        Shape m(double a,double b)throws Exception {commands.put(new JSONArray().put("M").put(x(a,b)).put(y(a,b)));return this;}
        Shape l(double a,double b)throws Exception {commands.put(new JSONArray().put("L").put(x(a,b)).put(y(a,b)));return this;}
        Shape q(double a,double b,double c,double d)throws Exception {commands.put(new JSONArray().put("Q").put(x(a,b)).put(y(a,b)).put(x(c,d)).put(y(c,d)));return this;}
        Shape c(double a,double b,double c,double d,double e,double f)throws Exception {commands.put(new JSONArray().put("C").put(x(a,b)).put(y(a,b)).put(x(c,d)).put(y(c,d)).put(x(e,f)).put(y(e,f)));return this;}
        Shape z(){commands.put(new JSONArray().put("Z"));return this;}
        void end()throws Exception {if(fill!=null)layer.getJSONArray("objects").put(base("path",color(fill),true,weight).put("commands",commands));if(outline!=null)layer.getJSONArray("objects").put(base("path",color(outline),false,weight).put("commands",commands));}
    }
}
