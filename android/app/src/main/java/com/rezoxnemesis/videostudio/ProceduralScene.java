package com.rezoxnemesis.videostudio;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;

/** Original scene renderer: animated 2D objects and perspective-projected 3D triangle meshes.
 * No remote generation, screenshots, title cards, or manifest-only capabilities.
 * This renderer is procedural geometry, not photorealistic neural synthesis.
 */
public final class ProceduralScene {
    private final JSONObject scene;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private static final String TYPES = "|circle|rectangle|ellipse|line|polygon|cube|pyramid|mesh|";
    private static final double[][] CUBE = {{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}};
    private static final int[][] CUBE_FACES = {{0,2,1},{0,3,2},{4,5,6},{4,6,7},{0,1,5},{0,5,4},{2,3,7},{2,7,6},{1,2,6},{1,6,5},{0,4,7},{0,7,3}};
    private static final double[][] PYRAMID = {{-1,-1,-1},{1,-1,-1},{1,-1,1},{-1,-1,1},{0,1,0}};
    private static final int[][] PYRAMID_FACES = {{0,1,4},{1,2,4},{2,3,4},{3,0,4},{0,2,1},{0,3,2}};

    public ProceduralScene(JSONObject input) throws Exception {
        validate(input);
        scene = new JSONObject(input.toString());
    }
    public static void validate(JSONObject scene) throws Exception {
        if (scene == null) throw new IllegalArgumentException("sceneGraph is required");
        if (scene.optInt("version", 1) != 1) throw new IllegalArgumentException("Unsupported procedural scene version");
        JSONArray objects = scene.optJSONArray("objects");
        if (objects == null || objects.length() == 0 || objects.length() > 128) throw new IllegalArgumentException("Scene requires 1..128 objects");
        int triangleBudget = 0;
        for (int i=0; i<objects.length(); i++) {
            JSONObject o = objects.getJSONObject(i);
            if (!TYPES.contains("|" + o.optString("type", "") + "|")) throw new IllegalArgumentException("Unknown scene object type");
            for (String field : new String[]{"x","y","z","toX","toY","toZ","size","width","height","rotation","spin","orbit","frequency","amplitude"}) {
                if (o.has(field) && (!Double.isFinite(o.getDouble(field)) || Math.abs(o.getDouble(field)) > 10000)) throw new IllegalArgumentException("Invalid scene parameter: " + field);
            }
            if ("cube".equals(o.optString("type"))) triangleBudget += 12;
            if ("pyramid".equals(o.optString("type"))) triangleBudget += 6;
            if (o.has("vertices")) {
                JSONArray v = o.getJSONArray("vertices");
                if (v.length()<3 || v.length()>512) throw new IllegalArgumentException("Mesh/polygon requires 3..512 vertices");
                for(int j=0;j<v.length();j++) {
                    JSONArray point=v.getJSONArray(j);
                    if(point.length()<2 || point.length()>3) throw new IllegalArgumentException("Invalid vertex dimension");
                    for(int k=0;k<point.length();k++) if(!Double.isFinite(point.getDouble(k)) || Math.abs(point.getDouble(k))>100) throw new IllegalArgumentException("Invalid vertex");
                }
            }
            if ("mesh".equals(o.optString("type"))) {
                JSONArray v = o.getJSONArray("vertices"), f = o.getJSONArray("faces");
                triangleBudget += f.length();
                if(f.length()>1024) throw new IllegalArgumentException("Mesh exceeds 1024 triangles");
                for(int j=0;j<f.length();j++) {
                    JSONArray tri=f.getJSONArray(j);
                    if(tri.length()!=3) throw new IllegalArgumentException("Mesh faces must be triangles");
                    for(int k=0;k<3;k++) if(tri.getInt(k)<0 || tri.getInt(k)>=v.length()) throw new IllegalArgumentException("Mesh index out of bounds");
                }
            }
        }
        if (triangleBudget > 4096) throw new IllegalArgumentException("Scene exceeds software renderer triangle budget; split into separate shots");
    }
    private static final class Face {
        double[][] vertices; double depth; int color;
        Face(double[][] v, int c) { vertices=v; color=c; depth=(v[0][2]+v[1][2]+v[2][2])/3; }
    }
    public void draw(Canvas canvas, double seconds, double duration) {
        int w=canvas.getWidth(), h=canvas.getHeight();
        canvas.drawColor(color(scene.optString("background", "#071224")));
        double p=SceneMath.ease(seconds/Math.max(.001,duration));
        JSONArray objects=scene.optJSONArray("objects");
        ArrayList<Face> faces=new ArrayList<>();
        for(int i=0;i<objects.length();i++) {
            JSONObject o=objects.optJSONObject(i);
            String type=o.optString("type");
            if("cube".equals(type) || "pyramid".equals(type) || "mesh".equals(type)) buildMesh(faces,o,p,seconds);
            else draw2d(canvas,o,p,seconds,w,h);
        }
        faces.sort(Comparator.comparingDouble((Face f)->f.depth).reversed());
        double fov=Math.max(20,Math.min(100,scene.optDouble("fov",50)));
        for(Face face:faces) {
            double[][] projected=new double[3][]; boolean visible=true;
            for(int i=0;i<3;i++) { double[] v=face.vertices[i]; projected[i]=SceneMath.project(v[0],v[1],v[2],fov,w/(double)h); if(projected[i]==null) visible=false; }
            if(!visible) continue;
            double light=SceneMath.diffuse(face.vertices[0],face.vertices[1],face.vertices[2]);
            paint.setColor(shade(face.color,light)); paint.setStyle(Paint.Style.FILL); path.reset();
            for(int i=0;i<3;i++) { float x=(float)(projected[i][0]*w), y=(float)(projected[i][1]*h); if(i==0) path.moveTo(x,y); else path.lineTo(x,y); }
            path.close(); canvas.drawPath(path,paint);
        }
    }
    private void buildMesh(ArrayList<Face> out,JSONObject o,double p,double t) {
        double[][] v; int[][] f;
        String type=o.optString("type");
        if("mesh".equals(type)) {
            JSONArray va=o.optJSONArray("vertices"), fa=o.optJSONArray("faces");
            v=new double[va.length()][3]; f=new int[fa.length()][3];
            for(int i=0;i<v.length;i++) for(int j=0;j<3;j++) v[i][j]=va.optJSONArray(i).optDouble(j,0);
            for(int i=0;i<f.length;i++) for(int j=0;j<3;j++) f[i][j]=fa.optJSONArray(i).optInt(j);
        } else { v="pyramid".equals(type)?PYRAMID:CUBE; f="pyramid".equals(type)?PYRAMID_FACES:CUBE_FACES; }
        double x=track(o,"x",0,p),y=track(o,"y",0,p),z=track(o,"z",4,p),size=Math.max(.01,Math.min(10,o.optDouble("size",.7)));
        double ry=Math.toRadians(o.optDouble("rotation",0)+o.optDouble("spin",25)*t);
        double orbit=Math.toRadians(scene.optDouble("cameraOrbit",0))*t;
        double[][] transformed=new double[v.length][];
        for(int i=0;i<v.length;i++) {
            double[] point=SceneMath.rotate(v[i][0]*size,v[i][1]*size,v[i][2]*size,.25,ry,0);
            point[0]+=x; point[1]+=y; point[2]+=z-4;
            point=SceneMath.rotate(point[0],point[1],point[2],0,orbit,0); point[2]+=4;
            transformed[i]=point;
        }
        int c=color(o.optString("color","#59d9e8"));
        for(int[] tri:f) out.add(new Face(new double[][]{transformed[tri[0]],transformed[tri[1]],transformed[tri[2]]},c));
    }
    private void draw2d(Canvas c,JSONObject o,double p,double t,int w,int h) {
        double x=track(o,"x",.5,p), y=track(o,"y",.5,p);
        double phase=o.optDouble("phase",0),freq=o.optDouble("frequency",.25),amp=o.optDouble("amplitude",0);
        x+=amp*Math.sin(t*freq*Math.PI*2+phase); y+=amp*.4*Math.cos(t*freq*Math.PI*2+phase);
        if(o.optBoolean("wrap",false)) { x=x-Math.floor(x); y=y-Math.floor(y); }
        float width=(float)Math.max(.001,Math.min(4,o.optDouble("width",.1)))*w, height=(float)Math.max(.001,Math.min(4,o.optDouble("height",.1)))*h;
        c.save(); c.translate((float)x*w,(float)y*h); c.rotate((float)(o.optDouble("rotation",0)+o.optDouble("spin",0)*t));
        paint.setStyle(Paint.Style.FILL); paint.setColor(color(o.optString("color","#ffffff")));
        switch(o.optString("type")) {
            case "circle": c.drawCircle(0,0,width/2,paint); break;
            case "ellipse": c.drawOval(-width/2,-height/2,width/2,height/2,paint); break;
            case "rectangle": c.drawRoundRect(-width/2,-height/2,width/2,height/2,Math.min(width,height)*.1f,Math.min(width,height)*.1f,paint); break;
            case "line": paint.setStrokeWidth(Math.max(1,width)); c.drawLine(0,0,0,height,paint); break;
            case "polygon":
                JSONArray points=o.optJSONArray("vertices"); if(points==null) break;
                path.reset(); for(int i=0;i<points.length();i++) { JSONArray v=points.optJSONArray(i); float vx=(float)v.optDouble(0)*width,vy=(float)v.optDouble(1)*height; if(i==0) path.moveTo(vx,vy); else path.lineTo(vx,vy); }
                path.close(); c.drawPath(path,paint); break;
        }
        c.restore();
    }
    private static double track(JSONObject o,String name,double defaultValue,double p) { double start=o.optDouble(name,defaultValue); return SceneMath.lerp(start,o.optDouble("to"+name.substring(0,1).toUpperCase()+name.substring(1),start),p); }
    private static int color(String s) { try { return Color.parseColor(s); } catch(Exception e) { return Color.WHITE; } }
    private static int shade(int c,double l) { return Color.argb(Color.alpha(c),(int)(Color.red(c)*l),(int)(Color.green(c)*l),(int)(Color.blue(c)*l)); }
}
