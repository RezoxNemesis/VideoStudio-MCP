package com.rezoxnemesis.videostudio;

import org.json.*;
import android.graphics.*;
import java.util.*;

/** Original joint-attached adult anatomy study, retained as ordinary editable Bezier ink.
 * This is a 2D drawing style. The joint model supplies projection and attachment, not a 3D render.
 */
final class SketchHumanAnatomy {
    private static final String INK="#FF454449",DETAIL="#B05D5959",FAINT="#706F6865",SHADE="#FFDBD7D1";
    private final JSONObject layer,s;private final SketchHumanFigure.Skeleton p;private final JungleCelInk ink;
    private final String actor,skin;private String part;private int serial;
    private SketchAnatomyHead.DepthOccluder neckOccluder;
    private SketchHumanAnatomy(JSONObject layer,String actor,JSONObject s,SketchHumanFigure.Skeleton p)throws Exception{
        this.layer=layer;this.actor=actor;this.s=s;this.p=p;skin=s.getString("skinColor");ink=new JungleCelInk(layer).at(640,360,1/16d,0);
    }
    static void draw(JSONObject layer,String actor,JSONObject s,SketchHumanFigure.Skeleton p)throws Exception{
        new SketchHumanAnatomy(layer,actor,s,p).draw();
    }
    private void draw()throws Exception{
        boolean leftNear=p.camera.clothDepth(p.leftHip)>p.camera.clothDepth(p.rightHip);
        boolean near=p.camera.clothDepth(p.nearShoulder)>=p.camera.clothDepth(p.farShoulder);
        leg(!leftNear);arm(!near);leg(leftNear);torso();shorts();arm(near);skinRim();
        SketchAnatomyHead.draw(layer,actor,s,p,neckOccluder);
        if(s.optBoolean("constructionGuides",false))guides();
    }
    private void begin(String name){part=name;serial=layer.optJSONArray("objects").length();}
    private void finish()throws Exception{JSONArray a=layer.getJSONArray("objects");for(int i=serial;i<a.length();i++)a.getJSONObject(i).put("id",actor+"_anatomy_"+part+"_"+(i-serial));}
    private double[] project(double x,double y,double z){return p.camera.point(new double[]{x,y,z});}
    private double[] body(double x,double y,double z){
        double a=Math.toRadians(p.lean),c=Math.cos(a),n=Math.sin(a);double[] axis=p.camera.poseAxis();double dot=axis[0]*x+axis[1]*y+axis[2]*z;
        return new double[]{p.hip[0]+x*c+(axis[1]*z-axis[2]*y)*n+axis[0]*dot*(1-c),p.hip[1]+y*c+(axis[2]*x-axis[0]*z)*n+axis[1]*dot*(1-c),p.hip[2]+z*c+(axis[0]*y-axis[1]*x)*n+axis[2]*dot*(1-c)};
    }
    private interface Coordinates{double[] at(double x,double y,double z);}
    private final class Curve{
        final JungleCelInk.Shape shape;final Coordinates coordinates;
        Curve(String fill,String stroke,double weight,Coordinates c){shape=ink.ink(fill,stroke,weight*16);coordinates=c;}
        double[] at(double x,double y,double z){double[] a=coordinates.at(x,y,z);return p.camera.point(a);}
        Curve m(double x,double y,double z)throws Exception{double[]a=at(x,y,z);shape.m(a[0],a[1]);return this;}
        Curve l(double x,double y,double z)throws Exception{double[]a=at(x,y,z);shape.l(a[0],a[1]);return this;}
        Curve q(double x,double y,double z,double X,double Y,double Z)throws Exception{double[]a=at(x,y,z),b=at(X,Y,Z);shape.q(a[0],a[1],b[0],b[1]);return this;}
        Curve c(double x,double y,double z,double X,double Y,double Z,double u,double v,double w)throws Exception{double[]a=at(x,y,z),b=at(X,Y,Z),d=at(u,v,w);shape.c(a[0],a[1],b[0],b[1],d[0],d[1]);return this;}
        Curve z(){shape.z();return this;}void end()throws Exception{shape.end();}
    }
    private Curve torsoPath(String fill,String stroke,double w){return new Curve(fill,stroke,w,this::body);}
    private void torso()throws Exception{
        begin("torso");double b=s.getString("build").equals("broad")?1.14:1;
        // Acromion -> deltoid -> rib cage -> waist -> iliac crest; the neck is continuous.
        Coordinates trunk=(x,y,z)->body(x*b,y,z);
        surface(new double[][]{{-151,9,7,8},{-139,11,9,10},{-132,17,11,12},{-128,30,13,14},{-124,37,14,15},{-120,43,15,17},{-111,38,21,20},{-99,35,24,21},{-83,31,22,19},{-64,27,19,17},{-44,24,16,15},{-28,25,16,16},{-13,28,18,17},{3,28,18,17},{13,19,15,14}},trunk,skin);
        if(p.camera.back){
            for(int side:new int[]{-1,1}){
                new Curve(null,DETAIL,.52,trunk).m(side*6,-137,-7).q(side*13,-123,-13,side*32,-114,-12).c(side*29,-103,-18,side*20,-91,-20,side*15,-85,-17).q(side*11,-100,-16,side*14,-114,-14).end();
                new Curve(null,FAINT,.42,trunk).m(side*32,-105,-15).q(side*30,-78,-17,side*18,-49,-12).q(side*9,-30,-8,side*8,-14,-7).end();
                hatch(trunk,side*13,-98,-17,side,8);
            }
            new Curve(null,DETAIL,.44,trunk).m(0,-137,-9).c(-2,-111,-17,2,-70,-15,0,-22,-8).end();
        }else{
            // Sternocleidomastoid and clavicles connect the head to the shoulder girdle.
            for(int side:new int[]{-1,1}){
                new Curve(null,DETAIL,.50,trunk).m(side*7,-144,8).q(side*7,-135,12,side*2,-129,16).q(side*13,-132,15,side*29,-124,9).end();
                new Curve(null,FAINT,.39,trunk).m(side*32,-117,12).c(side*25,-121,18,side*14,-121,23,side*4,-118,24).end();
                new Curve(null,DETAIL,.44,trunk).m(side*4,-104,23).c(side*12,-102,24,side*25,-103,19,side*30,-109,15).end();
                new Curve(null,FAINT,.40,trunk).m(side*30,-99,16).q(side*26,-88,18,side*21,-83,20).m(side*27,-89,15).q(side*23,-77,16,side*18,-74,18).end();
                new Curve(null,DETAIL,.42,trunk).m(side*25,-61,10).c(side*22,-42,12,side*15,-31,15,side*10,-22,17).end();
                // Rectus planes curve into a linea alba, rather than separate circular 'abs'.
                for(int row=0;row<3;row++){double y=-90+row*19;new Curve(null,FAINT,.39,trunk).m(side*2,y,22-row*2).q(side*9,y-2,23-row*2,side*13,y,20-row*2).m(side*14,y+4,20-row*2).q(side*14,y+8,20-row*2,side*11,y+11,18-row*2).end();}
                hatch(trunk,side*29,-111,18,side,5);hatch(trunk,side*23,-65,12,side,6);
            }
            new Curve(null,FAINT,.38,trunk).m(0,-120,23).q(-1,-111,25,0,-103,24).m(0,-96,23).q(-1,-68,18,0,-46,17).end();
            new Curve(null,DETAIL,.45,trunk).m(-1.2,-43,17).q(1,-40,18,1.2,-43,17).end();
        }
        finish();
    }
    private void hatch(Coordinates c,double x,double y,double z,int side,int count)throws Exception{
        for(int i=0;i<count;i++)new Curve(null,"#5577726D",.35,c).m(x-side*i*.8,y+i*2,z).l(x-side*(4+i*.45),y+i*2+5,z).end();
    }
    private void shorts()throws Exception{
        begin("shorts");String cloth=s.getString("trouserColor");
        torsoPath(cloth,INK,.64).m(-27,-17,1).q(0,-10,20,27,-17,1).q(31,6,0,33,25,0)
            .q(22,30,12,11,26,14).l(0,9,19).l(-11,26,14).q(-22,30,12,-33,25,0).q(-31,4,0,-27,-17,1).z().end();
        torsoPath(null,"#FF777575",.42).m(-26,-13,4).q(0,-7,22,26,-13,4).m(0,-8,22).l(0,8,21).m(-22,-7,14).q(-18,5,19,-27,10,12).m(22,-7,14).q(18,5,19,27,10,12).m(-13,19,14).l(-10,23,15).m(13,19,14).l(10,23,15).end();finish();
    }
    private static double[] plus(double[]a,double[]b){return new double[]{a[0]+b[0],a[1]+b[1],a[2]+b[2]};}
    private static double[] minus(double[]a,double[]b){return new double[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]};}
    private static double[] scale(double[]a,double n){return new double[]{a[0]*n,a[1]*n,a[2]*n};}
    private static double length(double[]a){return Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]);}
    /** Screen-normal surface coordinates stay perpendicular to the actual bone. */
    private Coordinates segment(double[]a,double[]b){
        double[] delta=minus(b,a);double len=length(delta);double[] axis=scale(delta,1/len),normal=p.camera.normal();
        double[] across={axis[1]*normal[2]-axis[2]*normal[1],axis[2]*normal[0]-axis[0]*normal[2],axis[0]*normal[1]-axis[1]*normal[0]};
        double n=length(across);if(n<1e-6)across=new double[]{1,0,0};else across=scale(across,1/n);final double[]x=across;
        return (u,v,w)->plus(a,plus(scale(x,u),plus(scale(axis,v),scale(normal,w))));
    }
    private void limb(String name,double[]a,double[]b,double[]c,double r0,double r1,double r2,boolean arm)throws Exception{
        begin(name);double[]A=p.camera.point(a),B=p.camera.point(b),C=p.camera.point(c);
        double[]d=minus(b,a),e=minus(c,b);double l=length(d),n=length(e);
        Coordinates upper=segment(a,b),lower=segment(b,c);
        if(arm){
            // The deltoid wraps the upper humerus and inserts below the shoulder.
            // A narrow limb root stays within this rounded cap; no flat cap or disk rim.
            double broad=s.getString("build").equals("broad")?1.08:1;
            surface(new double[][]{{-5,2,3,3},{0,8.8*broad,9,9},{7,12*broad,12,11},{15,12.2*broad,12,11},{24,10.2*broad,10,9},{34,7.6,7,7},{40,5,5,5}},upper,skin);
        }
        // One continuous contour around both segments. No outlined joint disks.
        double[]u=upper.at(r0,0,0),v=upper.at(r1,l,0),w=lower.at(r1,0,0),t=lower.at(r2,n,0);
        double[]U=upper.at(-r0,0,0),V=upper.at(-r1,l,0),W=lower.at(-r1,0,0),T=lower.at(-r2,n,0);
        Curve path=new Curve(skin,null,.62,(x,y,z)->new double[]{x,y,z});path.m(u[0],u[1],u[2]);
        double[]c1=upper.at(r0*1.16,l*.25,1),c2=upper.at(r1*1.2,l*.75,1);path.c(c1[0],c1[1],c1[2],c2[0],c2[1],c2[2],v[0],v[1],v[2]);
        path.q(b[0]+(v[0]+w[0]-2*b[0])*.55,b[1]+(v[1]+w[1]-2*b[1])*.55,b[2]+(v[2]+w[2]-2*b[2])*.55,w[0],w[1],w[2]);
        c1=lower.at(r1*1.2,n*.20,1);c2=lower.at(r2*1.5,n*.75,1);path.c(c1[0],c1[1],c1[2],c2[0],c2[1],c2[2],t[0],t[1],t[2]).l(T[0],T[1],T[2]);
        c1=lower.at(-r2*1.5,n*.70,1);c2=lower.at(-r1*1.35,n*.25,1);path.c(c1[0],c1[1],c1[2],c2[0],c2[1],c2[2],W[0],W[1],W[2]);
        path.q(b[0]+(V[0]+W[0]-2*b[0])*.55,b[1]+(V[1]+W[1]-2*b[1])*.55,b[2]+(V[2]+W[2]-2*b[2])*.55,V[0],V[1],V[2]);
        c1=upper.at(-r1*1.05,l*.72,1);c2=upper.at(-r0*1.2,l*.2,1);path.c(c1[0],c1[1],c1[2],c2[0],c2[1],c2[2],U[0],U[1],U[2]).q(a[0],a[1]-r0*.6,a[2],u[0],u[1],u[2]).z().end();
        if(arm){double side=name.startsWith("near")?1:-1;
            new Curve(null,DETAIL,.44,upper).m(side*7,7,4).c(side*11,17,5,side*7,29,5,side*1.5,37,4).end();
            double[]foldStart=body(side*(s.getString("build").equals("broad")?35:31),-109,16),foldControl=body(side*(s.getString("build").equals("broad")?40:35),-105,12),foldEnd=upper.at(-side*5,23,4);
            new Curve(null,FAINT,.42,(x,y,z)->new double[]{x,y,z}).m(foldStart[0],foldStart[1],foldStart[2]).q(foldControl[0],foldControl[1],foldControl[2],foldEnd[0],foldEnd[1],foldEnd[2]).end();
        }
        new Curve(null,DETAIL,.43,upper).m(-r0*.3,l*.18,2).c(-r0*.7,l*.36,2,-r1*.65,l*.65,2,-r1*.15,l*.83,2).end();
        new Curve(null,FAINT,.40,upper).m(r0*.5,l*.30,2).q(r0*.65,l*.50,2,r1*.15,l*.80,2).end();
        new Curve(null,DETAIL,.44,lower).m(-r1*.45,n*.12,2).c(-r1*.85,n*.30,2,-r2*.4,n*.63,2,-r2*.2,n*.91,2).end();
        new Curve(null,FAINT,.4,lower).m(r1*.4,n*.15,2).q(r1*.8,n*.36,2,r2*.1,n*.67,2).end();
        if(!arm){new Curve(null,DETAIL,.45,lower).m(-r1*.48,1,3).q(0,5,4,r1*.6,1,3).m(-r1*.5,6,3).q(0,10,5,r1*.4,6,3).end();}
        else new Curve(null,DETAIL,.42,lower).m(-r1*.5,2,3).q(0,5,3,r1*.4,2,3).end();
        hatch(upper,r0*.65,l*.3,2,1,arm?6:9);hatch(lower,-r1*.55,n*.22,2,-1,arm?5:8);finish();
    }
    private void leg(boolean left)throws Exception{
        String name=left?"left":"right";double[]hip=left?p.leftHip:p.rightHip,knee=left?p.leftKnee:p.rightKnee,ankle=left?p.leftAnkle:p.rightAnkle;
        limb(name+"_leg",hip,knee,ankle,15.8,8.2,4.7,false);foot(name,left?p.leftFoot:p.rightFoot,left);
    }
    private void foot(String name,double[]f,boolean left)throws Exception{
        begin(name+"_foot");Coordinates local=(x,y,z)->plus(f,new double[]{x,y,z});double side=left?-1:1;
        // Heel/instep and toe fan occupy the ground plane; soles stay on the supplied floor.
        surface(new double[][]{{-8,3.8,2,3},{-3,5,5,3},{1,5,12,3},{8,6.5,8,3},{17,9,4,3},{25,10,2,3},{30,8,1,3},{32,3,1,3}},(x,y,z)->plus(f,new double[]{x,-3-z,y}),skin,f[1]);
        if(p.camera.normal()[2]>-.1)for(int i=0;i<4;i++){double x=-6+i*3.6;new Curve(null,DETAIL,.38,local).m(x,-.8,29).q(x+.6,-3,27,x-.3,-4,23).end();
            new Curve(null,FAINT,.35,local).m(x-.5,-2,28).q(x+.6,-3,26,x+1.7,-2,28).end();}
        new Curve(null,DETAIL,.43,local).m(-3,-12,1).q(-1,-5,10,-4,-3,18).m(3,-11,1).q(4,-5,9,5,-2,20).end();finish();
    }
    private void arm(boolean near)throws Exception{
        String name=near?"near":"far";double[]shoulder=near?p.nearShoulder:p.farShoulder,elbow=near?p.nearElbow:p.farElbow,wrist=near?p.nearHand:p.farHand;
        limb(name+"_arm",shoulder,elbow,wrist,8.6,6.2,3.9,true);hand(name,elbow,wrist,near);
    }
    private void hand(String name,double[]elbow,double[]wrist,boolean near)throws Exception{
        begin(name+"_hand");double[]a=p.camera.point(elbow),b=p.camera.point(wrist);double angle=Math.atan2(b[1]-a[1],b[0]-a[0])-Math.PI/2+Math.toRadians(p.controls.getDouble(near?"nearWrist":"farWrist"));
        double c=Math.cos(angle),n=Math.sin(angle),open=p.controls.getDouble("handOpen");double side=near?-1:1;
        JungleCelInk d=ink.at(b[0],b[1],1,Math.toDegrees(angle));
        // Knuckle fan and five distinct digits; tapered fingertips with nail and flexion marks.
        JungleCelInk.Shape palm=d.ink(skin,null,.55*16).m(-3.9,-2).q(0,-3,3.9,-2).q(4.9,3,5.3,8)
            .q(7,11,7.5,16).q(7.3,18,6,17).l(3.5,12);
        double[]lengths={11,14,13,10};double[]roots={3.5,1.3,-1.1,-3.5};
        for(int i=0;i<4;i++){double x=roots[i],tip=12+lengths[i]*(.25+.75*open);palm.l(x+1,tip-2).q(x+1,tip,x,tip).q(x-1.1,tip,x-1,tip-2).l(x-1,12);}
        palm.q(-5.2,11,-5.2,7).q(-4.7,2,-3.9,-2).z().end();
        d.ink(null,DETAIL,.36*16).m(-3,8).q(0,5,3.2,8).m(-3,11).q(-1,14,2,12).m(3,4).q(1,7,3.4,11).end();
        for(int i=0;i<4;i++){double x=roots[i],tip=12+lengths[i]*(.25+.75*open);d.ink(null,FAINT,.35*16).m(x-.6,tip-3).q(x,tip-3.6,x+.6,tip-3).m(x-.6,15).l(x+.6,15).end();}
        if(side<0){
            JSONArray objects=layer.getJSONArray("objects");
            for(int i=serial;i<objects.length();i++){JSONArray commands=objects.getJSONObject(i).getJSONArray("commands");
                for(int j=0;j<commands.length();j++){JSONArray command=commands.getJSONArray(j);for(int k=1;k<command.length();k+=2){
                    double x=(command.getDouble(k)*1280-640)*16-b[0],y=(command.getDouble(k+1)*720-360)*16-b[1];
                    double localX=-(x*c+y*n),localY=-x*n+y*c;
                    command.put(k,(640+(b[0]+localX*c-localY*n)/16)/1280);command.put(k+1,(360+(b[1]+localX*n+localY*c)/16)/720);
                }}
            }
        }
        finish();
    }
    /** Cross-sections supply a real side silhouette. Internal mesh edges never become ink. */
    private void surface(double[][] rows,Coordinates coordinates,String fill)throws Exception{surface(rows,coordinates,fill,Double.POSITIVE_INFINITY);}
    private void surface(double[][] rows,Coordinates coordinates,String fill,double floor)throws Exception{
        List<double[]> points=new ArrayList<>();List<int[]> triangles=new ArrayList<>();int n=48;
        points.add(coordinates.at(0,rows[0][0],0));
        for(double[]r:rows)for(int j=0;j<n;j++){double a=j*2*Math.PI/n,c=Math.cos(a);points.add(coordinates.at(r[1]*Math.sin(a),r[0],c*(c>=0?r[2]:r[3])));}
        for(int j=0;j<n;j++)triangles.add(new int[]{0,1+(j+1)%n,1+j});
        for(int row=0;row<rows.length-1;row++)for(int j=0;j<n;j++){int a=1+row*n+j,b=1+row*n+(j+1)%n;triangles.add(new int[]{a,b,a+n});triangles.add(new int[]{b,b+n,a+n});}
        int end=points.size(),last=1+(rows.length-1)*n;points.add(coordinates.at(0,rows[rows.length-1][0],0));
        for(int j=0;j<n;j++)triangles.add(new int[]{last+j,last+(j+1)%n,end});
        // Reuse the very same constructed torso surface for head/neck depth visibility.
        // No second set of neck measurements or raster sampling is involved.
        if("torso".equals(part))neckOccluder=new SketchAnatomyHead.DepthOccluder(points,triangles,p.camera);
        Map<Long,int[]>edges=new LinkedHashMap<>();double[]view=p.camera.normal();
        for(int[]t:triangles){double[]a=points.get(t[0]),b=minus(points.get(t[1]),a),c=minus(points.get(t[2]),a);double facing=(b[1]*c[2]-b[2]*c[1])*view[0]+(b[2]*c[0]-b[0]*c[2])*view[1]+(b[0]*c[1]-b[1]*c[0])*view[2];if(facing<=1e-9)continue;
            for(int j=0;j<3;j++){int u=t[j],v=t[(j+1)%3];long key=((long)Math.min(u,v)<<32)|Math.max(u,v);if(edges.containsKey(key))edges.remove(key);else edges.put(key,new int[]{u,v});}}
        Map<Integer,ArrayDeque<Integer>> next=new LinkedHashMap<>();for(int[]e:edges.values())next.computeIfAbsent(e[0],k->new ArrayDeque<>()).add(e[1]);
        while(!next.isEmpty()){
            int start=next.keySet().iterator().next(),v=start;List<double[]>loop=new ArrayList<>();
            do{loop.add(points.get(v));ArrayDeque<Integer>out=next.get(v);if(out==null||out.isEmpty())throw SketchDocument.bad("Open anatomy surface boundary");int to=out.removeFirst();if(out.isEmpty())next.remove(v);v=to;if(loop.size()>points.size())throw SketchDocument.bad("Unbounded anatomy surface");}while(v!=start);
            if(loop.size()<3)continue;Curve line=new Curve(fill,null,.6,(x,y,z)->new double[]{x,Math.min(floor,y),z});double[]first=loop.get(0);line.m(first[0],first[1],first[2]);
            for(int i=0;i<loop.size();i++){double[]a=loop.get((i+loop.size()-1)%loop.size()),b=loop.get(i),c=loop.get((i+1)%loop.size()),d=loop.get((i+2)%loop.size());double[]u=plus(b,scale(minus(c,a),.11)),w=minus(c,scale(minus(d,b),.11));line.c(u[0],u[1],u[2],w[0],w[1],w[2],c[0],c[1],c[2]);}line.z().end();
        }
    }
    private static Path modelPath(JSONObject o)throws Exception{
        Path path=SketchFrameRenderer.path(o.getJSONArray("commands"),20480,11520);Matrix m=new Matrix();m.setTranslate(-10240,-5760);path.transform(m);return path;
    }
    /** A shared native silhouette removes artificial arm/ankle cap seams. Keep individual
     * filled surfaces and internal anatomical marks editable. The neutral shorts occlude
     * their covered skin boundary. Native Boolean failure aborts rather than emitting gaps. */
    private void skinRim()throws Exception{
        JSONArray objects=layer.getJSONArray("objects");Path skinPath=new Path(),shortsPath=new Path();boolean any=false;
        for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);if(!o.optBoolean("fill"))continue;
            if(skin.equals(o.optString("color"))){Path path=modelPath(o);if(!any){skinPath.set(path);any=true;}else {
                // Skia fails on the valid rear/high225° union. Preserve both
                // original operands and retry only that failure with the same
                // native vector backend used by bounded hand intersections.
                Path trial=new Path(skinPath);
                if(trial.op(path,Path.Op.UNION))skinPath.set(trial);
                else SketchHandGeometry.retryVectorBoolean(skinPath,path,Path.Op.UNION);
            }}
            if(o.getString("id").startsWith(actor+"_anatomy_shorts_"))shortsPath.addPath(modelPath(o));
        }
        if(!any)throw SketchDocument.bad("Anatomy surface is empty");Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(.65f);pen.setStrokeJoin(Paint.Join.ROUND);pen.setStrokeCap(Paint.Cap.ROUND);Path rim=new Path();pen.getFillPath(skinPath,rim);
        if(!shortsPath.isEmpty()&&!rim.op(shortsPath,Path.Op.DIFFERENCE))throw SketchDocument.bad("Native anatomy garment occlusion failed");
        begin("contour");JungleCelInk.Shape line=ink.ink(INK,null,16);
        for(float[] points:windingContours(rim)){
            for(int i=0;i<points.length;i+=2){if(i==0)line.m(points[i],points[i+1]);else line.l(points[i],points[i+1]);}line.z();
        }
        line.end();finish();
    }
    /** Boolean/stroked native paths may use EVEN_ODD. Ordinary editable ink uses
     * nonzero winding, so preserve holes by alternating nested ring direction.
     * Merely changing the native fill type would fill those holes before export. */
    static List<float[]> windingContours(Path path)throws Exception{
        if(path.isInverseFillType())throw SketchDocument.bad("Inverse anatomy contours are unsupported");
        List<float[]> rings=new ArrayList<>();PathMeasure measure=new PathMeasure(path,false);
        do{
            Path contour=new Path();if(!measure.getSegment(0,measure.getLength(),contour,true))continue;
            float[] samples=contour.approximate(.015f);if(samples.length<9)continue;
            int count=samples.length/3;
            if(samples[1]==samples[samples.length-2]&&samples[2]==samples[samples.length-1])count--;
            if(count<3)continue;float[] ring=new float[count*2];
            for(int i=0;i<count;i++){ring[i*2]=samples[i*3+1];ring[i*2+1]=samples[i*3+2];}
            rings.add(ring);
        }while(measure.nextContour());
        if(path.getFillType()!=Path.FillType.EVEN_ODD)return rings;
        for(float[] ring:rings){
            int depth=0;for(float[] parent:rings)if(parent!=ring&&encloses(parent,ring))depth++;
            double area=0;for(int i=0,j=ring.length-2;i<ring.length;j=i,i+=2)area+=(double)ring[j]*ring[i+1]-(double)ring[i]*ring[j+1];
            if((area>0)!=(depth%2==0))for(int a=0,b=ring.length-2;a<b;a+=2,b-=2){float x=ring[a],y=ring[a+1];ring[a]=ring[b];ring[a+1]=ring[b+1];ring[b]=x;ring[b+1]=y;}
        }
        return rings;
    }
    private static boolean encloses(float[] parent,float[] child){
        // Native Boolean contours can touch. Skip coincident boundary samples
        // rather than confusing a shared endpoint with an enclosing ring.
        for(int k=0;k<child.length;k+=2){double x=child[k],y=child[k+1];boolean inside=false,boundary=false;
            for(int i=0,j=parent.length-2;i<parent.length;j=i,i+=2){double ax=parent[j],ay=parent[j+1],bx=parent[i],by=parent[i+1];
                double cross=(x-ax)*(by-ay)-(y-ay)*(bx-ax);
                if(Math.abs(cross)<1e-7&&x>=Math.min(ax,bx)-1e-7&&x<=Math.max(ax,bx)+1e-7&&y>=Math.min(ay,by)-1e-7&&y<=Math.max(ay,by)+1e-7){boundary=true;break;}
                if((ay>y)!=(by>y)&&x<(bx-ax)*(y-ay)/(by-ay)+ax)inside=!inside;
            }
            if(!boundary)return inside;
        }
        return false;
    }
    private void guides()throws Exception{
        begin("guides");String blue="#885381AB";
        torsoPath(null,blue,.45).m(0,-153,0).l(0,15,0).m(-45,-120,0).l(45,-120,0).m(-34,-80,0).q(0,-63,23,34,-80,0).m(-29,-12,0).q(0,2,17,29,-12,0).end();
        for(double[][]chain:new double[][][]{ {p.nearShoulder,p.nearElbow,p.nearHand},{p.farShoulder,p.farElbow,p.farHand},{p.leftHip,p.leftKnee,p.leftAnkle},{p.rightHip,p.rightKnee,p.rightAnkle}}){for(int i=0;i<2;i++){double[]a=p.camera.point(chain[i]),b=p.camera.point(chain[i+1]);ink.line(blue,.45*16,a[0],a[1],b[0],b[1]);}}
        finish();
    }
}
