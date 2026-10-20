package com.rezoxnemesis.videostudio;

/**
 * OMEGA Phase 4 pure kinematic core. Enforces fixed upper/lower bone lengths
 * and explicit elbow bend side; native deformable overlays are future adapters.
 */
final class OmegaTwoBoneIK {
    private OmegaTwoBoneIK() {}
    static final class Joint {
        final double x,y;
        Joint(double x,double y) {
            if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>100||Math.abs(y)>100)
                throw new IllegalArgumentException(!Double.isFinite(x)||!Double.isFinite(y)?"Nonfinite rig point":"Invalid rig point");
            this.x=x;this.y=y;
        }
        double distanceTo(Joint other) {return Math.hypot(x-other.x,y-other.y);}
    }
    static final class Solution {
        final Joint root,elbow,tip,requestedTip;
        final boolean reachable;
        Solution(Joint root,Joint elbow,Joint tip,Joint requested,boolean reachable){
            this.root=root;this.elbow=elbow;this.tip=tip;this.requestedTip=requested;
            this.reachable=reachable;
        }
    }
    static Solution solve(Joint root,Joint target,double upperLength,double lowerLength,int bendSide) {
        if(root==null||target==null||!Double.isFinite(upperLength)||!Double.isFinite(lowerLength)
            ||upperLength<=.0001||lowerLength<=.0001||upperLength>10||lowerLength>10
            ||(bendSide!=1&&bendSide!=-1))
            throw new IllegalArgumentException("Unsafe rig constraints");
        double dx=target.x-root.x,dy=target.y-root.y,original=Math.hypot(dx,dy);
        double minDist=Math.abs(upperLength-lowerLength),maxDist=upperLength+lowerLength;
        // For coincident equal-length endpoints, select deterministic +X direction.
        double dist=Math.min(maxDist,Math.max(Math.max(1e-9,minDist),original));
        double ux=original>1e-12?dx/original:1;
        double uy=original>1e-12?dy/original:0;
        double along=(upperLength*upperLength-lowerLength*lowerLength+dist*dist)/(2*dist);
        along=Math.max(-upperLength,Math.min(upperLength,along));
        double height=Math.sqrt(Math.max(0,upperLength*upperLength-along*along));
        Joint elbow=new Joint(root.x+ux*along-bendSide*uy*height,
                              root.y+uy*along+bendSide*ux*height);
        Joint tip=new Joint(root.x+ux*dist,root.y+uy*dist);
        boolean reached=original>=minDist-1e-9 && original<=maxDist+1e-9;
        return new Solution(root,elbow,tip,target,reached);
    }

    // ====== OMEGA Phase 4: normalized scene rig, pixel-space skinning and contact ======
    static final int MAX_BONES = 64, MAX_MESHES = 128, MAX_VERTICES = 8192;
    private static final double EPS = 1e-8, WEIGHT_EPS = 1e-7, CONTACT_EPS_PX = .05;

    private static double[] solvePixels(double rx,double ry,double tx,double ty,double upper,double lower,int side) {
        double dx=tx-rx,dy=ty-ry,original=Math.hypot(dx,dy);
        double min=Math.abs(upper-lower),max=upper+lower;
        double distance=Math.min(max,Math.max(Math.max(1e-9,min),original));
        double ux=original>1e-12?dx/original:1,uy=original>1e-12?dy/original:0;
        double along=(upper*upper-lower*lower+distance*distance)/(2*distance);
        along=Math.max(-upper,Math.min(upper,along));
        double height=Math.sqrt(Math.max(0,upper*upper-along*along));
        return new double[]{rx+ux*along-side*uy*height,ry+uy*along+side*ux*height,
                rx+ux*distance,ry+uy*distance,original>=min-1e-8 && original<=max+1e-8?1:0};
    }
    static final class Bone {
        final String id, parentId;
        final Joint restStart, restTip;
        Bone(String id,String parentId,Joint restStart,Joint restTip) {
            this.id=safeId(id); this.parentId=parentId==null?null:safeId(parentId);
            this.restStart=scene(restStart); this.restTip=scene(restTip);
        }
    }
    static final class Influence {
        final String boneId;
        final double weight;
        Influence(String boneId,double weight) {
            this.boneId=safeId(boneId); finite(weight,"skin weight");
            if(weight<=0 || weight>1)throw bad("Skin weights must be positive and <= 1");
            this.weight=weight;
        }
    }
    static final class SkinVertex {
        final Joint rest;
        private final Influence[] influences;
        SkinVertex(Joint rest,Influence... influences) {
            this.rest=scene(rest);
            if(influences==null || influences.length==0 || influences.length>4)throw bad("Use 1..4 skin influences");
            this.influences=influences.clone();
            double sum=0;java.util.Set<String> ids=new java.util.HashSet<>();
            for(Influence i:this.influences){if(i==null || !ids.add(i.boneId))throw bad("Duplicate/null skin influence");sum+=i.weight;}
            if(Math.abs(sum-1)>WEIGHT_EPS)throw bad("Skin weights must sum to one");
        }
        Influence[] influences(){return influences.clone();}
    }
    /** Optional triangle indices protect authored winding; untriangulated strokes remain editable. */
    static final class Skin {
        final String layerId, objectId;
        final boolean locked;
        private final SkinVertex[] vertices;
        private final int[] triangles;
        Skin(String layerId,String objectId,boolean locked,SkinVertex[] vertices,int[] triangles){
            this.layerId=safeId(layerId);this.objectId=safeId(objectId);this.locked=locked;
            if(vertices==null || vertices.length<2 || vertices.length>4096)throw bad("Skin needs 2..4096 controls");
            this.vertices=vertices.clone();
            for(SkinVertex v:this.vertices)if(v==null)throw bad("Null skin control");
            this.triangles=triangles==null?new int[0]:triangles.clone();
            if(this.triangles.length%3!=0 || this.triangles.length>16384*3)throw bad("Invalid triangle index budget");
            for(int index:this.triangles)if(index<0 || index>=vertices.length)throw bad("Triangle index outside skin");
        }
        SkinVertex[] vertices(){return vertices.clone();}
        int[] triangles(){return triangles.clone();}
    }
    /** Explicitly authored view. No inferred or mirrored hidden anatomy. */
    static final class View {
        final String id;
        final int width,height;
        private final Bone[] bones;
        private final Skin[] skins;
        private final int[] parents;
        View(String id,Bone[] bones,Skin[] skins,int width,int height){
            this.id=safeId(id);this.width=width;this.height=height;
            if(bones==null || bones.length<1 || bones.length>MAX_BONES)throw bad("Use 1..64 authored bones per view");
            this.bones=bones.clone();this.skins=skins==null?new Skin[0]:skins.clone();
            if(this.skins.length>MAX_MESHES)throw bad("Too many skins in view");
            this.parents=new int[bones.length];java.util.Map<String,Integer> index=new java.util.HashMap<>();
            for(int i=0;i<bones.length;i++){
                Bone b=bones[i];if(b==null || index.putIfAbsent(b.id,i)!=null)throw bad("Duplicate/null bone ID");
                Integer parent=b.parentId==null?null:index.get(b.parentId);
                if(b.parentId!=null && (parent==null || parent>=i))throw bad("Bone parents must precede children");
                parents[i]=parent==null?-1:parent;
                if(parent!=null && pixels(b.restStart,bones[parent].restTip,width,height)>.05)
                    throw bad("Authored bone chain is disconnected");
                if(pixels(b.restStart,b.restTip,width,height)<=.1)throw bad("Bone must have a visible explicit length");
            }
            java.util.Set<String> objects=new java.util.HashSet<>();int vertexCount=0;
            for(Skin skin:this.skins){
                if(skin==null || !objects.add(skin.layerId+"/"+skin.objectId))throw bad("Duplicate/null layer-object skin");
                vertexCount+=skin.vertices.length;
                for(SkinVertex v:skin.vertices)for(Influence f:v.influences)if(!index.containsKey(f.boneId))throw bad("Unknown weighted bone");
                for(int k=0;k<skin.triangles.length;k+=3){
                    int a=skin.triangles[k],b=skin.triangles[k+1],c=skin.triangles[k+2];
                    double triangleArea=area(skin.vertices[a].rest,skin.vertices[b].rest,skin.vertices[c].rest,width,height);
                    if(Math.abs(triangleArea)<.01)throw bad("Degenerate rest triangle");
                }
            }
            if(vertexCount>MAX_VERTICES)throw bad("Too many deformed control points");
        }
        Bone[] bones(){return bones.clone();}
        Skin[] skins(){return skins.clone();}
        int find(String id){for(int i=0;i<bones.length;i++)if(bones[i].id.equals(id))return i;throw bad("Unknown bone: "+id);}
    }
    static final class Rig {
        final String sourceSketchId,sourceSha256;
        final long sourceRevision;
        final int width,height,fps,frameCount;
        private final View[] views;
        Rig(String sourceSketchId,long sourceRevision,String sourceSha256,int width,int height,int fps,int frameCount,View...views){
            this.sourceSketchId=safeId(sourceSketchId);
            if(sourceSha256==null || !sourceSha256.matches("[a-f0-9]{64}"))throw bad("A native source SHA-256 is mandatory");
            this.sourceSha256=sourceSha256;
            if(sourceRevision<1 || sourceRevision==Long.MAX_VALUE || width<256 || width>2048 || height<256 || height>2048
                    || width%2!=0 || height%2!=0 || (long)width*height>2048L*2048L
                    || frameCount<1 || frameCount>1200 || views==null || views.length<1 || views.length>8)
                throw bad("Rig must match native document limits");
            this.sourceRevision=sourceRevision;this.width=width;this.height=height;
            this.fps=NativeFrameClock.requireFps(fps);this.frameCount=frameCount;
            this.views=views.clone();java.util.Set<String> used=new java.util.HashSet<>();
            for(View v:this.views)if(v==null || v.width!=width || v.height!=height || !used.add(v.id))throw bad("Duplicate/mismatched authored view");
        }
        View view(String viewId){for(View v:views)if(v.id.equals(viewId))return v;throw bad("Missing explicitly authored view: "+viewId);}
        String[] viewIds(){String[] out=new String[views.length];for(int i=0;i<views.length;i++)out[i]=views[i].id;return out;}
    }
    static final class Target {
        static final String FOOT_PLANT="FOOT_PLANT", PROP_GRIP="PROP_GRIP", AIM="AIM";
        final String upperId,lowerId,kind,anchorId;
        final Joint sceneTarget;
        final int bendSide;
        Target(String upperId,String lowerId,String kind,String anchorId,Joint sceneTarget,int bendSide){
            this.upperId=safeId(upperId);this.lowerId=safeId(lowerId);this.anchorId=safeId(anchorId);
            if(!FOOT_PLANT.equals(kind) && !PROP_GRIP.equals(kind) && !AIM.equals(kind))throw bad("Unknown explicit contact type");
            if(bendSide!=1 && bendSide!=-1)throw bad("Bend must be +1 or -1");
            this.kind=kind;this.sceneTarget=scene(sceneTarget);this.bendSide=bendSide;
        }
    }
    static final class FrameRequest {
        final String viewId;
        final int frame;
        final long expectedRevision;
        final Joint translation;
        private final java.util.Map<String,Double> localDegrees;
        private final Target[] targets;
        FrameRequest(String viewId,int frame,long expectedRevision,Joint translation,java.util.Map<String,Double> rotations,Target...targets){
            this.viewId=safeId(viewId);this.frame=frame;this.expectedRevision=expectedRevision;
            this.translation=translation==null?new Joint(0,0):translation;
            if(Math.abs(this.translation.x)>2 || Math.abs(this.translation.y)>2)throw bad("Root translation is too large");
            java.util.Map<String,Double> angles=new java.util.LinkedHashMap<>();
            if(rotations!=null)for(java.util.Map.Entry<String,Double> e:rotations.entrySet()){
                String id=safeId(e.getKey());if(e.getValue()==null)throw bad("Null rig angle");
                double degrees=e.getValue();finite(degrees,"bone angle");
                if(Math.abs(degrees)>3600)throw bad("Excessive joint rotation");
                angles.put(id,degrees);
            }
            localDegrees=java.util.Collections.unmodifiableMap(angles);
            this.targets=targets==null?new Target[0]:targets.clone();
            if(this.targets.length>16)throw bad("Too many simultaneous contacts");
            for(Target target:this.targets)if(target==null)throw bad("Null contact");
        }
        Target[] targets(){return targets.clone();}
    }
    static final class BonePose {
        final String id;
        final Joint start,tip;
        final double lengthPixels,angleRadians;
        BonePose(String id,Joint start,Joint tip,double lengthPixels,double angleRadians){
            this.id=id;this.start=start;this.tip=tip;this.lengthPixels=lengthPixels;this.angleRadians=angleRadians;
        }
    }
    static final class DeformedSkin {
        final String layerId,objectId;
        private final Joint[] vertices;
        DeformedSkin(String layerId,String objectId,Joint[] vertices){
            this.layerId=layerId;this.objectId=objectId;this.vertices=vertices.clone();
        }
        Joint[] vertices(){return vertices.clone();}
    }
    static final class ContactReport {
        final String kind,anchorId,upperId,lowerId;
        final Joint requested,actual;
        final boolean reachable;
        final double residualPixels;
        ContactReport(Target t,Joint actual,boolean reachable,double residualPixels){
            this.kind=t.kind;this.anchorId=t.anchorId;this.upperId=t.upperId;this.lowerId=t.lowerId;
            this.requested=t.sceneTarget;this.actual=actual;this.reachable=reachable;this.residualPixels=residualPixels;
        }
    }
    static final class FrameResult {
        final String sourceSketchId,viewId;
        final int frame;
        final long sourceRevision,presentationTimeUs;
        private final BonePose[] bones;
        private final DeformedSkin[] skins;
        private final ContactReport[] contacts;
        FrameResult(Rig r,FrameRequest request,BonePose[] bones,DeformedSkin[] skins,ContactReport[] contacts){
            sourceSketchId=r.sourceSketchId;viewId=request.viewId;frame=request.frame;sourceRevision=r.sourceRevision;
            presentationTimeUs=NativeFrameClock.presentationTimeUs(frame,r.fps);
            this.bones=bones.clone();this.skins=skins.clone();this.contacts=contacts.clone();
        }
        BonePose[] bones(){return bones.clone();}
        DeformedSkin[] skins(){return skins.clone();}
        ContactReport[] contacts(){return contacts.clone();}
        BonePose bone(String id){for(BonePose b:bones)if(b.id.equals(id))return b;throw bad("Missing posed bone");}
    }
    /** Every sample is independent, explicit and pinned to the authoritative document revision. */
    static FrameResult sample(Rig rig,FrameRequest request){
        if(rig==null || request==null || request.frame<0 || request.frame>=rig.frameCount
                || request.expectedRevision!=rig.sourceRevision)throw bad("Stale or invalid frame/revision pin");
        View view=rig.view(request.viewId);Bone[] bones=view.bones;
        for(String id:request.localDegrees.keySet())view.find(id);
        int n=bones.length;double[] sx=new double[n],sy=new double[n],ex=new double[n],ey=new double[n],angle=new double[n],restAngle=new double[n],length=new double[n];
        for(int i=0;i<n;i++){
            Bone b=bones[i];int p=view.parents[i];
            restAngle[i]=Math.atan2((b.restTip.y-b.restStart.y)*rig.height,(b.restTip.x-b.restStart.x)*rig.width);
            length[i]=pixels(b.restStart,b.restTip,rig.width,rig.height);
            double local=Math.toRadians(request.localDegrees.getOrDefault(b.id,0d));
            angle[i]=restAngle[i]+(p<0?0:angle[p]-restAngle[p])+local;
            sx[i]=p<0?(b.restStart.x+request.translation.x)*rig.width:ex[p];
            sy[i]=p<0?(b.restStart.y+request.translation.y)*rig.height:ey[p];
            ex[i]=sx[i]+length[i]*Math.cos(angle[i]);ey[i]=sy[i]+length[i]*Math.sin(angle[i]);
        }
        java.util.Set<Integer> owned=new java.util.HashSet<>();java.util.List<ContactReport> evidence=new java.util.ArrayList<>();
        for(Target t:request.targets){
            int upper=view.find(t.upperId),lower=view.find(t.lowerId);
            if(view.parents[lower]!=upper)throw bad("IK target requires direct upper/lower chain");
            for(int used:owned)if(descendant(view,upper,used) || descendant(view,used,upper))
                throw bad("Overlapping or ancestral contact chains");
            owned.add(upper);
            double[] solved=solvePixels(sx[upper],sy[upper],t.sceneTarget.x*rig.width,t.sceneTarget.y*rig.height,
                    length[upper],length[lower],t.bendSide);
            boolean reachable=solved[4]==1;
            double residual=Math.hypot(solved[2]-t.sceneTarget.x*rig.width,solved[3]-t.sceneTarget.y*rig.height);
            if(!Target.AIM.equals(t.kind) && (!reachable || residual>CONTACT_EPS_PX))
                throw bad("UNREACHABLE_"+t.kind+" at frame "+request.frame+" for "+t.anchorId);
            ex[upper]=solved[0];ey[upper]=solved[1];angle[upper]=Math.atan2(ey[upper]-sy[upper],ex[upper]-sx[upper]);
            sx[lower]=ex[upper];sy[lower]=ey[upper];ex[lower]=solved[2];ey[lower]=solved[3];
            angle[lower]=Math.atan2(ey[lower]-sy[lower],ex[lower]-sx[lower]);
            // Descendants preserve local rotations; unrelated sibling roots are untouched.
            for(int i=upper+1;i<n;i++){
                if(i==lower || !descendant(view,i,upper))continue;
                int p=view.parents[i];
                sx[i]=ex[p];sy[i]=ey[p];
                angle[i]=restAngle[i]+angle[p]-restAngle[p]+Math.toRadians(request.localDegrees.getOrDefault(bones[i].id,0d));
                ex[i]=sx[i]+length[i]*Math.cos(angle[i]);ey[i]=sy[i]+length[i]*Math.sin(angle[i]);
            }
            evidence.add(new ContactReport(t,new Joint(solved[2]/rig.width,solved[3]/rig.height),reachable,residual));
        }
        BonePose[] posed=new BonePose[n];
        for(int i=0;i<n;i++){
            Joint start=scene(new Joint(sx[i]/rig.width,sy[i]/rig.height));
            Joint tip=scene(new Joint(ex[i]/rig.width,ey[i]/rig.height));
            if(Math.abs(Math.hypot(ex[i]-sx[i],ey[i]-sy[i])-length[i])>1e-5)
                throw bad("Internal bone length drift");
            posed[i]=new BonePose(bones[i].id,start,tip,length[i],angle[i]);
        }
        DeformedSkin[] deformed=new DeformedSkin[view.skins.length];
        for(int m=0;m<view.skins.length;m++){
            Skin skin=view.skins[m];if(skin.locked)throw bad("LOCKED_LAYER: "+skin.layerId);
            Joint[] points=new Joint[skin.vertices.length];
            for(int k=0;k<points.length;k++){
                SkinVertex v=skin.vertices[k];double px=0,py=0;
                for(Influence inf:v.influences){
                    int b=view.find(inf.boneId);Bone bone=bones[b];
                    double ox=(v.rest.x-bone.restStart.x)*rig.width,oy=(v.rest.y-bone.restStart.y)*rig.height;
                    double radians=angle[b]-restAngle[b],c=Math.cos(radians),s=Math.sin(radians);
                    px+=inf.weight*(sx[b]+ox*c-oy*s);py+=inf.weight*(sy[b]+ox*s+oy*c);
                }
                points[k]=scene(new Joint(px/rig.width,py/rig.height));
            }
            for(int k=0;k<skin.triangles.length;k+=3){
                int a=skin.triangles[k],b=skin.triangles[k+1],c=skin.triangles[k+2];
                double before=area(skin.vertices[a].rest,skin.vertices[b].rest,skin.vertices[c].rest,rig.width,rig.height);
                double after=area(points[a],points[b],points[c],rig.width,rig.height);
                if(Math.abs(after)<.01 || Math.signum(before)!=Math.signum(after))
                    throw bad("INVERTED_OR_COLLAPSED_SKIN_TRIANGLE: "+skin.objectId);
            }
            deformed[m]=new DeformedSkin(skin.layerId,skin.objectId,points);
        }
        return new FrameResult(rig,request,posed,deformed,evidence.toArray(new ContactReport[0]));
    }
    /**
     * Read-only native bridge. Validates the actual SketchDocument and provides revision-pinned
     * editable vector deltas. Existing SketchEditOps must commit via reviewed integration.
     */
    static org.json.JSONObject previewNativeChanges(org.json.JSONObject source,Rig rig,FrameRequest request)throws Exception{
        if(source==null || rig==null)throw bad("Missing native source");
        SketchDocument.validate(source);
        if(!rig.sourceSketchId.equals(source.getString("id")) || source.getLong("revision")!=rig.sourceRevision
                || !rig.sourceSha256.equals(SketchDocument.sha256(SketchDocument.canonical(source).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                || source.getInt("width")!=rig.width || source.getInt("height")!=rig.height
                || source.getInt("fps")!=rig.fps || source.getInt("frameCount")!=rig.frameCount)
            throw bad("Native source geometry, clock or revision mismatch");
        FrameResult frame=sample(rig,request);org.json.JSONArray patches=new org.json.JSONArray();
        View authoredView=rig.view(request.viewId);
        DeformedSkin[] sampledMeshes=frame.skins();
        for(int meshIndex=0;meshIndex<sampledMeshes.length;meshIndex++){
            DeformedSkin mesh=sampledMeshes[meshIndex];Skin authored=authoredView.skins[meshIndex];
            org.json.JSONObject owner=SketchDocument.owner(source,mesh.objectId);
            if(!mesh.layerId.equals(owner.getString("id")) || owner.getBoolean("locked"))
                throw bad("LOCKED_OR_MOVED_LAYER: "+mesh.layerId);
            org.json.JSONObject object=SketchDocument.object(source,mesh.objectId);
            if(request.frame<object.getInt("frame") || request.frame>=object.getInt("frame")+object.getInt("exposure"))
                throw bad("Mesh is not exposed at the pinned frame");
            if(object.has("masterLink") || object.has("shapeKeys") || object.getJSONArray("keys").length()!=0
                    || owner.getJSONArray("keys").length()!=0
                    || !identity(object.getJSONObject("transform")) || !identity(owner.getJSONObject("transform")))
                throw bad("NATIVE_TRANSFORM_OR_MASTER_REQUIRES_INTEGRATION_ADAPTER");
            String kind=object.getString("kind");org.json.JSONObject patch=new org.json.JSONObject()
                    .put("layerId",mesh.layerId).put("objectId",mesh.objectId)
                    .put("frame",request.frame).put("kind",kind);
            Joint[] coords=mesh.vertices();
            if("path".equals(kind)){
                org.json.JSONArray commands=object.getJSONArray("commands"),replacement=new org.json.JSONArray();int cursor=0;
                for(int i=0;i<commands.length();i++){
                    org.json.JSONArray command=commands.getJSONArray(i),rewritten=new org.json.JSONArray().put(command.getString(0));
                    for(int q=1;q<command.length();q+=2){
                        if(cursor>=coords.length)throw bad("SKIN_PATH_TOPOLOGY_MISMATCH");
                        if(Math.hypot((command.getDouble(q)-authored.vertices[cursor].rest.x)*rig.width,
                                (command.getDouble(q+1)-authored.vertices[cursor].rest.y)*rig.height)>.05)
                            throw bad("SKIN_REST_GEOMETRY_MISMATCH");
                        rewritten.put(coords[cursor].x).put(coords[cursor].y);cursor++;
                    }
                    replacement.put(rewritten);
                }
                if(cursor!=coords.length)throw bad("SKIN_PATH_TOPOLOGY_MISMATCH");
                patch.put("commands",replacement);
            }else if("line".equals(kind)||"stroke".equals(kind)||"eraser".equals(kind)||"polygon".equals(kind)){
                org.json.JSONArray existing=object.getJSONArray("points"),points=new org.json.JSONArray();
                if(existing.length()!=coords.length)throw bad("SKIN_POINT_TOPOLOGY_MISMATCH");
                for(int i=0;i<coords.length;i++){
                    org.json.JSONArray original=existing.getJSONArray(i);
                    if(Math.hypot((original.getDouble(0)-authored.vertices[i].rest.x)*rig.width,
                            (original.getDouble(1)-authored.vertices[i].rest.y)*rig.height)>.05)
                        throw bad("SKIN_REST_GEOMETRY_MISMATCH");
                    points.put(new org.json.JSONArray().put(coords[i].x).put(coords[i].y).put(original.getDouble(2)));
                }
                patch.put("points",points);
            }else throw bad("Native preview requires editable vector geometry");
            patches.put(patch);
        }
        return new org.json.JSONObject().put("sourceSketchId",rig.sourceSketchId).put("expectedRevision",rig.sourceRevision)
                .put("sourceSha256",rig.sourceSha256)
                .put("frame",frame.frame).put("presentationTimeUs",frame.presentationTimeUs).put("viewId",frame.viewId)
                .put("patches",patches).put("committed",false).put("artisticQualityVerified",false);
    }
    private static boolean identity(org.json.JSONObject t)throws Exception{
        return t.getDouble("x")==0 && t.getDouble("y")==0 && t.getDouble("scaleX")==1
                && t.getDouble("scaleY")==1 && t.getDouble("rotation")==0;
    }
    private static boolean descendant(View v,int child,int ancestor){
        for(int i=child;i>=0;i=v.parents[i])if(i==ancestor)return true;
        return false;
    }
    private static double pixels(Joint a,Joint b,int w,int h){return Math.hypot((a.x-b.x)*w,(a.y-b.y)*h);}
    private static double area(Joint a,Joint b,Joint c,int w,int h){
        return ((b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x))*w*h;
    }
    private static Joint scene(Joint joint){
        if(joint==null || joint.x < -EPS || joint.y < -EPS || joint.x > 1+EPS || joint.y > 1+EPS)
            throw bad("Out-of-canvas normalized geometry; never silently clip");
        // Only round floating-point boundary noise, never accept a meaningfully clipped pose.
        return (joint.x<0 || joint.x>1 || joint.y<0 || joint.y>1)
                ?new Joint(Math.max(0,Math.min(1,joint.x)),Math.max(0,Math.min(1,joint.y))):joint;
    }
    private static String safeId(String id){
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,96}"))throw bad("Invalid stable rig/asset ID");
        return id;
    }
    private static double finite(double number,String field){
        if(!Double.isFinite(number))throw bad("Nonfinite "+field);
        return number;
    }
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}


}