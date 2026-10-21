package com.rezoxnemesis.videostudio;

import org.json.*;
import java.util.*;

/** Native source-free box construction. Shared 3D corners bake to ordinary editable 2D cels. */
public final class SketchSolidOps {
    private SketchSolidOps(){}
    private static final String[] CHANNELS={"x","elevation","depth","pitch","yaw","roll","scaleX","scaleY","scaleZ"};

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
        double[] previousPose=null;int previousStart=-1;int existing=0;JSONArray layers=doc.getJSONArray("layers");for(int l=0;l<layers.length();l++)existing+=layers.getJSONObject(l).getJSONArray("objects").length();
        for(int f=m.first;f<=m.last;f++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Solid construction cancelled");
            double[] currentPose=m.pose(f);
            if(m.mergeHolds&&previousPose!=null&&Arrays.equals(previousPose,currentPose)){for(int n=previousStart;n<additions.length();n++){JSONObject object=additions.getJSONObject(n);object.put("exposure",object.getInt("exposure")+1);}continue;}
            previousPose=currentPose;previousStart=additions.length();double[][] vertices=m.vertices(f);ArrayList<Integer> visible=new ArrayList<>();
            for(int face=0;face<m.mesh.faces.length;face++)if(facing(vertices,m.mesh.faces[face],m.height,m.distance)>1e-8)visible.add(face);
            visible.sort((a,b)->Double.compare(depth(vertices,m.mesh.faces[b]),depth(vertices,m.mesh.faces[a])));
            double[][] projected=new double[vertices.length][2];for(int v=0;v<vertices.length;v++){
                double w=m.distance+vertices[v][2];if(w<32)throw SketchDocument.bad("Solid crosses the camera near plane at frame "+f);
                projected[v][0]=.5+m.focal*vertices[v][0]/w/doc.getDouble("width");
                projected[v][1]=m.horizon-m.focal*(vertices[v][1]-m.height)/w/doc.getDouble("height");
                for(double coordinate:projected[v])if(!Double.isFinite(coordinate)||coordinate<0||coordinate>1)throw SketchDocument.bad("Solid leaves the canvas at frame "+f+"; adjust position, size or camera");
            }
            if(!"box".equals(m.mesh.shape))for(double[] point:projected)for(int axis=0;axis<2;axis++)point[axis]=Math.rint(point[axis]*1e6)/1e6;
            if(m.ground){
                // Key elevation is clearance only at authored keys. Between them,
                // rotation/scale changes support while the centre follows its own arc.
                // Use the same ground-clamped geometry as the visible body.
                double clearance=Double.POSITIVE_INFINITY;for(double[] vertex:vertices)clearance=Math.min(clearance,vertex[1]);clearance=Math.max(0,clearance);
                double[] pose=m.pose(f);double w=m.distance+pose[2],cx=.5+m.focal*(pose[0]+pose[10])/w/doc.getDouble("width"),cy=m.horizon+m.focal*m.height/w/doc.getDouble("height"),rx=m.focal*m.size[0]*pose[6]*.7*(1+clearance/600)/w/doc.getDouble("width"),ry=rx*doc.getDouble("width")/doc.getDouble("height")*.22;
                JSONArray shadow=new JSONArray();for(int n=0;n<24;n++){double angle=n*Math.PI/12;shadow.put(point(n==0?"M":"L",new double[]{Math.max(0,Math.min(1,cx+rx*Math.cos(angle))),Math.max(0,Math.min(1,cy+(m.softShadow?rx*doc.getDouble("width")/doc.getDouble("height"):ry)*Math.sin(angle)))}));}shadow.put(new JSONArray().put("Z"));
                JSONObject object=path(prefix+"_f"+f+"_shadow",shadow,m.ink,true,f,prefix+"_ink",m.stroke/doc.getDouble("height"));object.getJSONObject("transform").put("opacity",.20/(1+clearance/500));
                if(m.softShadow){object.remove("styleId");double sx=Math.max(0,Math.min(1,cx)),sy=Math.max(0,Math.min(1,cy));String clear="#00"+m.ink.substring(3);object.put("gradient",new JSONObject().put("type","radial").put("cx",sx).put("cy",sy).put("radius",Math.max(.0001,Math.min(2,rx*doc.getDouble("width")/Math.min(doc.getDouble("width"),doc.getDouble("height"))))).put("colors",new JSONArray().put(m.ink).put(clear)).put("stops",new JSONArray("[0,1]")));object.getJSONObject("transform").put("anchorX",sx).put("anchorY",sy).put("scaleY",.22);}
                additions.put(object);
            }
            if(!"box".equals(m.mesh.shape))additions.put(path(prefix+"_f"+f+"_body",hull(projected),m.colors[0],true,f,prefix+"_face0",m.stroke/doc.getDouble("height")));
            boolean smooth=m.lighting&&"sphere".equals(m.mesh.shape);LinkedHashMap<Integer,JSONArray> surfaces=new LinkedHashMap<>();LinkedHashMap<Integer,List<int[]>> regions=new LinkedHashMap<>();
            for(int face:visible){
                int material=m.mesh.materials[face],group=m.lighting&&!smooth?material*3+lightTone(vertices,m.mesh.faces[face]):material;int[] indices=m.mesh.faces[face];if(m.fineSphere){regions.computeIfAbsent(group,k->new ArrayList<>()).add(indices);continue;}JSONArray commands=surfaces.computeIfAbsent(group,k->new JSONArray());
                for(int n=0;n<indices.length;n++)commands.put(point(n==0?"M":"L",projected[indices[n]]));commands.put(new JSONArray().put("Z"));
            }
            for(Map.Entry<Integer,List<int[]>> region:regions.entrySet())surfaces.put(region.getKey(),SketchSolidContours.commands(projected,region.getValue()));
            for(Map.Entry<Integer,JSONArray> surface:surfaces.entrySet()){
                int group=surface.getKey(),material=m.lighting&&!smooth?group/3:group,tone=m.lighting&&!smooth?group%3:2;String suffix=m.lighting&&!smooth?"_shade"+group:"_face"+material,style=prefix+"_face"+material+(m.lighting&&!smooth?"_shade"+tone:"");JSONObject object=path(prefix+"_f"+f+suffix,surface.getValue(),m.lighting&&!smooth?shade(m.colors[material],tone):m.colors[material],true,f,style,m.stroke/doc.getDouble("height"));
                if(smooth){double minX=1,maxX=0,minY=1,maxY=0;for(double[] p:projected){minX=Math.min(minX,p[0]);maxX=Math.max(maxX,p[0]);minY=Math.min(minY,p[1]);maxY=Math.max(maxY,p[1]);}double cx=minX+(maxX-minX)*.35,cy=minY+(maxY-minY)*.28,radius=Math.max((maxX-minX)*doc.getDouble("width"),(maxY-minY)*doc.getDouble("height"))*.78/Math.min(doc.getDouble("width"),doc.getDouble("height"));object.remove("styleId");object.put("gradient",new JSONObject().put("type","radial").put("cx",cx).put("cy",cy).put("radius",Math.max(.0001,Math.min(2,radius))).put("colors",new JSONArray().put(m.colors[material]).put(shade(m.colors[material],2)).put(shade(m.colors[material],0))).put("stops",new JSONArray("[0,0.32,1]")));}
                additions.put(object);
            }
            HashSet<Integer> shown=new HashSet<>(visible);JSONArray ink=new JSONArray();
            for(int[] edge:m.mesh.edges){boolean a=shown.contains(edge[2]),b=shown.contains(edge[3]);if(!(a||b))continue;
                boolean crease="box".equals(m.mesh.shape)||!"sphere".equals(m.mesh.shape)&&crease(vertices,m.mesh.faces[edge[2]],m.mesh.faces[edge[3]]);
                if(a!=b||crease){ink.put(point("M",projected[edge[0]]));ink.put(point("L",projected[edge[1]]));}
            }
            if(ink.length()>0)additions.put(path(prefix+"_f"+f+"_ink",ink,m.ink,false,f,prefix+"_ink",m.stroke/doc.getDouble("height")));
            if(existing+additions.length()>SketchDocument.MAX_OBJECTS)throw SketchDocument.bad("Solid exceeds the 4096-object sketch budget; shorten the range or use a separate shot");
        }
        JSONArray palette=doc.optJSONArray("paletteStyles");palette=palette==null?new JSONArray():new JSONArray(palette.toString());
        for(int c=0;c<7;c++){String id=prefix+(c<6?"_face"+c:"_ink");for(int n=0;n<palette.length();n++)if(id.equals(palette.getJSONObject(n).getString("id")))throw SketchDocument.bad("Solid style already exists; choose a new solid ID");palette.put(new JSONObject().put("id",id).put("name",c<6?"Solid face "+(c+1):"Solid outline").put("color",c<6?m.colors[c]:m.ink));}
        if(m.lighting&&!"sphere".equals(m.mesh.shape))for(int face=0;face<6;face++)for(int tone=0;tone<3;tone++)palette.put(new JSONObject().put("id",prefix+"_face"+face+"_shade"+tone).put("name","Solid face "+(face+1)+" light band "+(tone+1)).put("color",shade(m.colors[face],tone)));
        // Validate a complete candidate before changing even the caller's in-memory document.
        JSONObject candidate=SketchDocument.copy(doc);candidate.put("paletteStyles",palette);JSONArray objects=SketchDocument.layer(candidate,layer.getString("id")).getJSONArray("objects");for(int n=0;n<additions.length();n++)objects.put(additions.getJSONObject(n));SketchDocument.validate(candidate);
        doc.put("paletteStyles",palette);layer.put("objects",objects);
    }
    private static int lightTone(double[][] v,int[] face){double[] n=SketchSolidMesh.cross(v[face[0]],v[face[1]],v[face[2]]);double length=Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);double light=(-.4*n[0]+.8*n[1]-.6*n[2])/(length*Math.sqrt(1.16));return light>=2d/3?2:light>=1d/3?1:0;}
    private static String shade(String color,int tone){int argb=(int)Long.parseLong(color.substring(1),16);double factor=new double[]{.58,.78,1}[tone];int out=argb&0xff000000;for(int shift:new int[]{16,8,0})out|=((int)Math.round(((argb>>>shift)&255)*factor))<<shift;return String.format(Locale.US,"#%08X",out);}
    private static JSONArray hull(double[][] points)throws Exception{
        ArrayList<double[]> sorted=new ArrayList<>(Arrays.asList(points));sorted.sort((a,b)->a[0]==b[0]?Double.compare(a[1],b[1]):Double.compare(a[0],b[0]));ArrayList<double[]> hull=new ArrayList<>();
        for(double[] p:sorted){while(hull.size()>1&&turn(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=0)hull.remove(hull.size()-1);hull.add(p);}int lower=hull.size();
        for(int n=sorted.size()-2;n>=0;n--){double[] p=sorted.get(n);while(hull.size()>lower&&turn(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=0)hull.remove(hull.size()-1);hull.add(p);}if(hull.size()>1)hull.remove(hull.size()-1);
        JSONArray commands=new JSONArray();for(int n=0;n<hull.size();n++)commands.put(point(n==0?"M":"L",hull.get(n)));return commands.put(new JSONArray().put("Z"));
    }
    private static double turn(double[] a,double[] b,double[] c){return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);}
    private static boolean crease(double[][] v,int[] a,int[] b){double[] x=SketchSolidMesh.cross(v[a[0]],v[a[1]],v[a[2]]),y=SketchSolidMesh.cross(v[b[0]],v[b[1]],v[b[2]]);double dot=0,lx=0,ly=0;for(int n=0;n<3;n++){dot+=x[n]*y[n];lx+=x[n]*x[n];ly+=y[n]*y[n];}return dot/Math.sqrt(lx*ly)<.8;}
    private static JSONArray point(String command,double[] p)throws Exception{return new JSONArray().put(command).put(p[0]).put(p[1]);}
    private static JSONObject path(String id,JSONArray commands,String color,boolean fill,int frame,String style,double width)throws Exception{return new JSONObject().put("id",id).put("kind","path").put("frame",frame).put("exposure",1).put("color",color).put("styleId",style).put("width",Math.max(.0005,width)).put("fill",fill).put("commands",commands).put("transform",SketchDocument.transform()).put("keys",new JSONArray());}
    private static double depth(double[][] v,int[] face){double z=0;for(int i:face)z+=v[i][2];return z/face.length;}
    private static double facing(double[][] v,int[] face,double eyeY,double distance){
        double[] a=v[face[0]],b=v[face[1]],c=v[face[2]];double ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];
        return (uy*vz-uz*vy)*(-a[0])+(uz*vx-ux*vz)*(eyeY-a[1])+(ux*vy-uy*vx)*(-distance-a[2]);
    }
    static final class Model {
        final SketchSolidMesh mesh;final double rollingRadius;final double[] size=new double[3];final double focal,distance,height,horizon,stroke;final String ink;final String[] colors=new String[6];final JSONArray keys;final int first,last;final boolean fineSphere,ground,preserveVolume,worldScale,groundLock,softShadow,lighting,rockingPivot,holdLast,mergeHolds;
        Model(JSONObject doc,JSONObject program)throws Exception{
            SketchDocument.fields(program,"size,camera,keys,colors,ink,strokePx,ground,preserveVolume,scaleSpace,shape,meshDetail,rollingRadius,groundLock,softShadow,lighting,rockingPivot,holdLast,mergeHolds");if(program.has("ground"))SketchDocument.bool(program,"ground");ground=program.optBoolean("ground",false);for(String flag:new String[]{"groundLock","softShadow","lighting","rockingPivot","holdLast","mergeHolds"})if(program.has(flag))SketchDocument.bool(program,flag);groundLock=program.optBoolean("groundLock");softShadow=program.optBoolean("softShadow");lighting=program.optBoolean("lighting");rockingPivot=program.optBoolean("rockingPivot");holdLast=program.optBoolean("holdLast");mergeHolds=program.optBoolean("mergeHolds");String detail=program.optString("meshDetail","standard");if(!Set.of("standard","fine").contains(detail))throw SketchDocument.bad("Unsupported solid mesh detail");fineSphere="fine".equals(detail)&&"sphere".equals(program.optString("shape","box"));mesh=SketchSolidMesh.create(program.optString("shape","box"),fineSphere);rollingRadius=optional(program,"rollingRadius",0,1,2048);if(rockingPivot&&(!groundLock||rollingRadius>0||!Set.of("box","pyramid").contains(mesh.shape)))throw SketchDocument.bad("Rocking pivot requires ground lock, box/pyramid and no distance rolling");if(program.has("preserveVolume"))SketchDocument.bool(program,"preserveVolume");preserveVolume=program.optBoolean("preserveVolume",false);String scaleSpace=program.optString("scaleSpace","local");if(!Set.of("local","world").contains(scaleSpace))throw SketchDocument.bad("Unsupported solid scale space");worldScale="world".equals(scaleSpace);JSONArray dimensions=program.getJSONArray("size");if(dimensions.length()!=3)throw SketchDocument.bad("Solid size requires width, height and depth");for(int n=0;n<3;n++)size[n]=SketchDocument.scalar(dimensions.get(n),1,2048,"Solid dimension");
            JSONObject camera=program.getJSONObject("camera");SketchDocument.fields(camera,"focal,distance,height,horizon");focal=SketchDocument.number(camera,"focal",32,8192);distance=SketchDocument.number(camera,"distance",32,16384);height=SketchDocument.number(camera,"height",0,8192);horizon=SketchDocument.number(camera,"horizon",0,1);
            stroke=optional(program,"strokePx",3,.5,20);ink=program.optString("ink","#FF142637");SketchDocument.color(ink);JSONArray palette=program.optJSONArray("colors");if(program.has("colors")&&(palette==null||palette.length()!=6))throw SketchDocument.bad("Solid requires six face colours");for(int n=0;n<6;n++){colors[n]=palette==null?COLORS[n]:palette.getString(n);SketchDocument.color(colors[n]);}
            keys=program.getJSONArray("keys");if(keys.length()<1||keys.length()>64)throw SketchDocument.bad("Use 1..64 solid pose keys");int prior=-1;
            for(int n=0;n<keys.length();n++){
                JSONObject key=keys.getJSONObject(n);SketchDocument.fields(key,"frame,x,elevation,depth,pitch,yaw,roll,scaleX,scaleY,scaleZ,interpolation,curve,elevationInterpolation,depthInterpolation,scaleInterpolation,flightHeight");int f=(int)SketchDocument.integer(key,"frame",0,doc.getInt("frameCount")-1);if(f<=prior)throw SketchDocument.bad("Solid key frames must strictly increase");prior=f;
                SketchDocument.number(key,"x",-8192,8192);SketchDocument.number(key,"elevation",0,8192);SketchDocument.number(key,"depth",-8192,8192);
                for(int ch=3;ch<9;ch++)optional(key,CHANNELS[ch],ch>=6?1:0,ch>=6?.05:-36000,ch>=6?8:36000);
                SketchEasing.validate(key);optional(key,"flightHeight",0,0,8192);if(groundLock&&(key.getDouble("elevation")!=0||key.optDouble("flightHeight",0)!=0))throw SketchDocument.bad("Ground lock conflicts with flight or elevation keys");for(String field:new String[]{"elevationInterpolation","depthInterpolation","scaleInterpolation"})if(key.has(field)&&(!SketchEasing.MODES.contains(key.getString(field))||"bezier".equals(key.getString(field))))throw SketchDocument.bad("Unsupported "+field);
            }
            first=keys.getJSONObject(0).getInt("frame");last=holdLast?doc.getInt("frameCount")-1:keys.getJSONObject(keys.length()-1).getInt("frame");
        }
        double[][] vertices(int frame)throws Exception {
            double[] values=pose(frame);double[][] v=local(values);double minY=Double.POSITIVE_INFINITY;for(double[] point:v)minY=Math.min(minY,point[1]);
            // Interpolate the authored key centres. Re-anchoring every airborne
            // rotated pose by its lowest corner creates artificial vertical bobbing.
            double centerY=groundLock?-minY:Math.max(values[9],-minY);for(double[] point:v){point[0]+=values[0]+values[10];point[1]+=centerY;point[2]+=values[2];}return v;
        }
        private double[][] local(double[] values){
            double px=Math.toRadians(values[3]),py=Math.toRadians(values[4]),pz=Math.toRadians(values[5]);double[][] v=new double[mesh.vertices.length][3];double minY=Double.POSITIVE_INFINITY;
            for(int i=0;i<mesh.vertices.length;i++){
                double x=mesh.vertices[i][0]*size[0]*(worldScale?1:values[6]),y=mesh.vertices[i][1]*size[1]*(worldScale?1:values[7]),z=mesh.vertices[i][2]*size[2]*(worldScale?1:values[8]);
                double yy=y*Math.cos(px)-z*Math.sin(px),zz=y*Math.sin(px)+z*Math.cos(px);y=yy;z=zz;double xx=x*Math.cos(py)+z*Math.sin(py);zz=-x*Math.sin(py)+z*Math.cos(py);x=xx;z=zz;xx=x*Math.cos(pz)-y*Math.sin(pz);yy=x*Math.sin(pz)+y*Math.cos(pz);
                v[i]=new double[]{xx*(worldScale?values[6]:1),yy*(worldScale?values[7]:1),z*(worldScale?values[8]:1)};
            }
            return v;
        }
        private double support(JSONObject key)throws Exception{
            double[] values=new double[9];for(int c=0;c<9;c++)values[c]=key.optDouble(CHANNELS[c],c>=6?1:0);roll(values);double low=Double.POSITIVE_INFINITY;for(double[] point:local(values))low=Math.min(low,point[1]);return -low;
        }
        private void roll(double[] values)throws Exception{if(rollingRadius>0)values[5]-=(values[0]-keys.getJSONObject(0).getDouble("x"))/rollingRadius*180/Math.PI;}
        double[] pose(int frame)throws Exception {
            JSONObject a=keys.getJSONObject(0),b=a;for(int n=1;n<keys.length();n++){b=keys.getJSONObject(n);if(frame<=b.getInt("frame"))break;a=b;}
            if(frame==b.getInt("frame"))a=b;
            double t=a==b?0:(frame-a.getInt("frame"))/(double)(b.getInt("frame")-a.getInt("frame"));double eased=SketchEasing.sample(a.optString("interpolation","linear"),a.optJSONArray("curve"),t);double[] values=new double[11];
            double vertical=a.has("elevationInterpolation")?SketchEasing.sample(a.getString("elevationInterpolation"),null,t):eased,depth=a.has("depthInterpolation")?SketchEasing.sample(a.getString("depthInterpolation"),null,t):eased;
            for(int c=0;c<9;c++){double start=a.optDouble(CHANNELS[c],c>=6?1:0),end=b.optDouble(CHANNELS[c],c>=6?1:0);double scale=a.has("scaleInterpolation")?SketchEasing.sample(a.getString("scaleInterpolation"),null,t):eased;double time=c>=6?scale:c==1?vertical:c==2?depth:eased;values[c]=start+(end-start)*time;}
            if(preserveVolume){double scale=a.has("scaleInterpolation")?SketchEasing.sample(a.getString("scaleInterpolation"),null,t):eased;double av=a.optDouble("scaleX",1)*a.optDouble("scaleY",1)*a.optDouble("scaleZ",1),bv=b.optDouble("scaleX",1)*b.optDouble("scaleY",1)*b.optDouble("scaleZ",1),target=av+(bv-av)*scale,factor=Math.sqrt(target/(values[6]*values[7]*values[8]));values[6]*=factor;values[8]*=factor;}
            roll(values);double flight=4*a.optDouble("flightHeight",0)*t*(1-t);values[1]+=flight;
            double firstCenter=a.getDouble("elevation")+support(a),lastCenter=b.getDouble("elevation")+support(b);values[9]=firstCenter+(lastCenter-firstCenter)*vertical+flight;
            if(rockingPivot){double[][] current=local(values);int low=0;for(int n=1;n<current.length;n++)if(current[n][1]<current[low][1])low=n;double[] neutral=values.clone();neutral[5]=0;values[10]=local(neutral)[low][0]-current[low][0];}
            return values;
        }
        static double optional(JSONObject object,String key,double fallback,double min,double max)throws Exception{return object.has(key)?SketchDocument.number(object,key,min,max):fallback;}
    }
}
