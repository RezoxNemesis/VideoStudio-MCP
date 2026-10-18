package com.rezoxnemesis.videostudio;

import org.json.*;
import java.util.*;

/** Native source-free box construction. Shared 3D corners bake to ordinary editable 2D cels. */
public final class SketchSolidOps {
    private SketchSolidOps(){}
    private static final String[] CHANNELS={"x","elevation","depth","pitch","yaw","roll","scaleX","scaleY","scaleZ"};
    private static final int[][] FACES={{0,3,2,1},{4,5,6,7},{0,4,7,3},{1,2,6,5},{3,7,6,2},{0,1,5,4}};
    private static final String[] COLORS={"#FFF2A94A","#FF39B9AD","#FF405C9C","#FFE76E5B","#FFFFD87A","#FF7452A3"};
    public static void apply(JSONObject doc,JSONObject op)throws Exception {
        SketchDocument.fields(op,"type,layerId,solidId,program");String prefix=SketchDocument.id(op,"solidId");if(prefix.length()>48)throw SketchDocument.bad("Solid ID must fit 48 characters");
        JSONObject layer=SketchDocument.layer(doc,SketchDocument.id(op,"layerId"));if(layer.getBoolean("locked"))throw SketchDocument.bad("Layer is locked");
        Model m=new Model(doc,op.getJSONObject("program"));JSONArray additions=new JSONArray();
        if(m.ground){
            JSONArray grid=new JSONArray();for(int n=0;n<=10;n++){grid.put(point("M",new double[]{.5,m.horizon}));grid.put(point("L",new double[]{n/10d,1}));}
            for(double z:new double[]{0,200,400,800,1600,3200}){double y=m.horizon+m.focal*m.height/(m.distance+z)/doc.getDouble("height");if(y>=0&&y<=1){grid.put(point("M",new double[]{0,y}));grid.put(point("L",new double[]{1,y}));}}
            JSONObject guide=path(prefix+"_ground",grid,m.ink,false,m.first,prefix+"_ink",1/doc.getDouble("height"));guide.put("exposure",m.last-m.first+1).getJSONObject("transform").put("opacity",.18);additions.put(guide);
        }
        int existing=0;JSONArray layers=doc.getJSONArray("layers");for(int l=0;l<layers.length();l++)existing+=layers.getJSONObject(l).getJSONArray("objects").length();
        for(int f=m.first;f<=m.last;f++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Solid construction cancelled");
            double[][] vertices=m.vertices(f);ArrayList<Integer> visible=new ArrayList<>();
            for(int face=0;face<6;face++)if(facing(vertices,FACES[face],m.height,m.distance)>1e-8)visible.add(face);
            visible.sort((a,b)->Double.compare(depth(vertices,FACES[b]),depth(vertices,FACES[a])));
            double[][] projected=new double[8][2];for(int v=0;v<8;v++){
                double w=m.distance+vertices[v][2];if(w<32)throw SketchDocument.bad("Solid crosses the camera near plane at frame "+f);
                projected[v][0]=.5+m.focal*vertices[v][0]/w/doc.getDouble("width");
                projected[v][1]=m.horizon-m.focal*(vertices[v][1]-m.height)/w/doc.getDouble("height");
                for(double coordinate:projected[v])if(!Double.isFinite(coordinate)||coordinate<0||coordinate>1)throw SketchDocument.bad("Solid leaves the canvas at frame "+f+"; adjust position, size or camera");
            }
            if(m.ground){
                double[] pose=m.pose(f);double w=m.distance+pose[2],cx=.5+m.focal*pose[0]/w/doc.getDouble("width"),cy=m.horizon+m.focal*m.height/w/doc.getDouble("height"),rx=m.focal*m.size[0]*pose[6]*.7*(1+pose[1]/600)/w/doc.getDouble("width"),ry=rx*doc.getDouble("width")/doc.getDouble("height")*.22;
                JSONArray shadow=new JSONArray();for(int n=0;n<24;n++){double angle=n*Math.PI/12;shadow.put(point(n==0?"M":"L",new double[]{Math.max(0,Math.min(1,cx+rx*Math.cos(angle))),Math.max(0,Math.min(1,cy+ry*Math.sin(angle)))}));}shadow.put(new JSONArray().put("Z"));
                JSONObject object=path(prefix+"_f"+f+"_shadow",shadow,m.ink,true,f,prefix+"_ink",m.stroke/doc.getDouble("height"));object.getJSONObject("transform").put("opacity",.20/(1+pose[1]/500));additions.put(object);
            }
            HashSet<String> edges=new HashSet<>();JSONArray ink=new JSONArray();
            for(int face:visible){
                JSONArray commands=new JSONArray();int[] indices=FACES[face];
                for(int n=0;n<4;n++)commands.put(point(n==0?"M":"L",projected[indices[n]]));commands.put(new JSONArray().put("Z"));
                additions.put(path(prefix+"_f"+f+"_face"+face,commands,m.colors[face],true,f,prefix+"_face"+face,m.stroke/doc.getDouble("height")));
                for(int n=0;n<4;n++){int a=indices[n],b=indices[(n+1)%4];String edge=Math.min(a,b)+":"+Math.max(a,b);if(edges.add(edge)){ink.put(point("M",projected[a]));ink.put(point("L",projected[b]));}}
            }
            if(ink.length()>0)additions.put(path(prefix+"_f"+f+"_ink",ink,m.ink,false,f,prefix+"_ink",m.stroke/doc.getDouble("height")));
            if(existing+additions.length()>SketchDocument.MAX_OBJECTS)throw SketchDocument.bad("Solid exceeds the 4096-object sketch budget; shorten the range or use a separate shot");
        }
        JSONArray palette=doc.optJSONArray("paletteStyles");palette=palette==null?new JSONArray():new JSONArray(palette.toString());
        for(int c=0;c<7;c++){String id=prefix+(c<6?"_face"+c:"_ink");for(int n=0;n<palette.length();n++)if(id.equals(palette.getJSONObject(n).getString("id")))throw SketchDocument.bad("Solid style already exists; choose a new solid ID");palette.put(new JSONObject().put("id",id).put("name",c<6?"Solid face "+(c+1):"Solid outline").put("color",c<6?m.colors[c]:m.ink));}
        // Validate a complete candidate before changing even the caller's in-memory document.
        JSONObject candidate=SketchDocument.copy(doc);candidate.put("paletteStyles",palette);JSONArray objects=SketchDocument.layer(candidate,layer.getString("id")).getJSONArray("objects");for(int n=0;n<additions.length();n++)objects.put(additions.getJSONObject(n));SketchDocument.validate(candidate);
        doc.put("paletteStyles",palette);layer.put("objects",objects);
    }
    private static JSONArray point(String command,double[] p)throws Exception{return new JSONArray().put(command).put(p[0]).put(p[1]);}
    private static JSONObject path(String id,JSONArray commands,String color,boolean fill,int frame,String style,double width)throws Exception{return new JSONObject().put("id",id).put("kind","path").put("frame",frame).put("exposure",1).put("color",color).put("styleId",style).put("width",Math.max(.0005,width)).put("fill",fill).put("commands",commands).put("transform",SketchDocument.transform()).put("keys",new JSONArray());}
    private static double depth(double[][] v,int[] face){double z=0;for(int i:face)z+=v[i][2];return z/4;}
    private static double facing(double[][] v,int[] face,double eyeY,double distance){
        double[] a=v[face[0]],b=v[face[1]],c=v[face[2]];double ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];
        return (uy*vz-uz*vy)*(-a[0])+(uz*vx-ux*vz)*(eyeY-a[1])+(ux*vy-uy*vx)*(-distance-a[2]);
    }
    static final class Model {
        final double[] size=new double[3];final double focal,distance,height,horizon,stroke;final String ink;final String[] colors=new String[6];final JSONArray keys;final int first,last;final boolean ground;
        Model(JSONObject doc,JSONObject program)throws Exception{
            SketchDocument.fields(program,"size,camera,keys,colors,ink,strokePx,ground");if(program.has("ground"))SketchDocument.bool(program,"ground");ground=program.optBoolean("ground",false);JSONArray dimensions=program.getJSONArray("size");if(dimensions.length()!=3)throw SketchDocument.bad("Solid size requires width, height and depth");for(int n=0;n<3;n++)size[n]=SketchDocument.scalar(dimensions.get(n),1,2048,"Solid dimension");
            JSONObject camera=program.getJSONObject("camera");SketchDocument.fields(camera,"focal,distance,height,horizon");focal=SketchDocument.number(camera,"focal",32,8192);distance=SketchDocument.number(camera,"distance",32,16384);height=SketchDocument.number(camera,"height",0,8192);horizon=SketchDocument.number(camera,"horizon",0,1);
            stroke=optional(program,"strokePx",3,.5,20);ink=program.optString("ink","#FF142637");SketchDocument.color(ink);JSONArray palette=program.optJSONArray("colors");if(program.has("colors")&&(palette==null||palette.length()!=6))throw SketchDocument.bad("Solid requires six face colours");for(int n=0;n<6;n++){colors[n]=palette==null?COLORS[n]:palette.getString(n);SketchDocument.color(colors[n]);}
            keys=program.getJSONArray("keys");if(keys.length()<1||keys.length()>64)throw SketchDocument.bad("Use 1..64 solid pose keys");int prior=-1;
            for(int n=0;n<keys.length();n++){
                JSONObject key=keys.getJSONObject(n);SketchDocument.fields(key,"frame,x,elevation,depth,pitch,yaw,roll,scaleX,scaleY,scaleZ,interpolation,curve,elevationInterpolation,depthInterpolation");int f=(int)SketchDocument.integer(key,"frame",0,doc.getInt("frameCount")-1);if(f<=prior)throw SketchDocument.bad("Solid key frames must strictly increase");prior=f;
                SketchDocument.number(key,"x",-8192,8192);SketchDocument.number(key,"elevation",0,8192);SketchDocument.number(key,"depth",-8192,8192);
                for(int ch=3;ch<9;ch++)optional(key,CHANNELS[ch],ch>=6?1:0,ch>=6?.05:-36000,ch>=6?8:36000);
                SketchEasing.validate(key);for(String field:new String[]{"elevationInterpolation","depthInterpolation"})if(key.has(field)&&(!SketchEasing.MODES.contains(key.getString(field))||"bezier".equals(key.getString(field))))throw SketchDocument.bad("Unsupported "+field);
            }
            first=keys.getJSONObject(0).getInt("frame");last=keys.getJSONObject(keys.length()-1).getInt("frame");
        }
        double[][] vertices(int frame)throws Exception {
            double[] values=pose(frame);double[][] v=local(values);double minY=Double.POSITIVE_INFINITY;for(double[] point:v)minY=Math.min(minY,point[1]);
            // Interpolate the authored key centres. Re-anchoring every airborne
            // rotated pose by its lowest corner creates artificial vertical bobbing.
            double centerY=Math.max(values[9],-minY);for(double[] point:v){point[0]+=values[0];point[1]+=centerY;point[2]+=values[2];}return v;
        }
        private double[][] local(double[] values){
            double px=Math.toRadians(values[3]),py=Math.toRadians(values[4]),pz=Math.toRadians(values[5]);double[][] v=new double[8][3];double minY=Double.POSITIVE_INFINITY;
            for(int i=0;i<8;i++){
                double x=((i==1||i==2||i==5||i==6)?1:-1)*size[0]*values[6]/2,y=((i==2||i==3||i==6||i==7)?1:-1)*size[1]*values[7]/2,z=(i>=4?1:-1)*size[2]*values[8]/2;
                double yy=y*Math.cos(px)-z*Math.sin(px),zz=y*Math.sin(px)+z*Math.cos(px);y=yy;z=zz;double xx=x*Math.cos(py)+z*Math.sin(py);zz=-x*Math.sin(py)+z*Math.cos(py);x=xx;z=zz;xx=x*Math.cos(pz)-y*Math.sin(pz);yy=x*Math.sin(pz)+y*Math.cos(pz);
                v[i]=new double[]{xx,yy,z};
            }
            return v;
        }
        private double support(JSONObject key){
            double[] values=new double[9];for(int c=0;c<9;c++)values[c]=key.optDouble(CHANNELS[c],c>=6?1:0);double low=Double.POSITIVE_INFINITY;for(double[] point:local(values))low=Math.min(low,point[1]);return -low;
        }
        double[] pose(int frame)throws Exception {
            JSONObject a=keys.getJSONObject(0),b=a;for(int n=1;n<keys.length();n++){b=keys.getJSONObject(n);if(frame<=b.getInt("frame"))break;a=b;}
            if(frame==b.getInt("frame"))a=b;
            double t=a==b?0:(frame-a.getInt("frame"))/(double)(b.getInt("frame")-a.getInt("frame"));double eased=SketchEasing.sample(a.optString("interpolation","linear"),a.optJSONArray("curve"),t);double[] values=new double[10];
            double vertical=a.has("elevationInterpolation")?SketchEasing.sample(a.getString("elevationInterpolation"),null,t):eased,depth=a.has("depthInterpolation")?SketchEasing.sample(a.getString("depthInterpolation"),null,t):eased;
            for(int c=0;c<9;c++){double start=a.optDouble(CHANNELS[c],c>=6?1:0),end=b.optDouble(CHANNELS[c],c>=6?1:0);double time=c==1?vertical:c==2?depth:eased;values[c]=start+(end-start)*time;}
            double firstCenter=a.getDouble("elevation")+support(a),lastCenter=b.getDouble("elevation")+support(b);values[9]=firstCenter+(lastCenter-firstCenter)*vertical;
            return values;
        }
        static double optional(JSONObject object,String key,double fallback,double min,double max)throws Exception{return object.has(key)?SketchDocument.number(object,key,min,max):fallback;}
    }
}
