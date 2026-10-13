package com.rezoxnemesis.videostudio;

import android.opengl.Matrix;
import com.google.android.filament.Box;
import com.google.android.filament.Camera;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.LightManager;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Scene;
import com.google.android.filament.TransformManager;
import com.google.android.filament.gltfio.Animator;
import com.google.android.filament.gltfio.FilamentAsset;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared full-scene sampling for native preview and export. Never rewrites world roots. */
public final class Cinematic3DSceneSampling {
    private Cinematic3DSceneSampling() {}

    public static int requireNamedIndex(String[] names,String exactName) {
        if(names==null||exactName==null||exactName.isEmpty())
            throw new IllegalArgumentException("One exact manifest animation name is required");
        int result=-1;
        for(int index=0;index<names.length;index++) if(exactName.equals(names[index])) {
            if(result!=-1)throw new IllegalArgumentException("The exact manifest animation is ambiguous");
            result=index;
        }
        if(result<0)throw new IllegalArgumentException("The exact manifest animation is absent");
        return result;
    }

    /** Only an explicit action library can bind new shot ids by an exact action. */
    public static JSONObject sceneForShot(JSONObject bundle,JSONObject shot) throws Exception {
        if(bundle==null||shot==null)throw new IllegalArgumentException("A bundle and shot are required");
        JSONObject manifest=bundle.optJSONObject("manifest");
        if(manifest==null)manifest=bundle;
        JSONArray scenes=manifest.getJSONArray("scenes");
        JSONObject generator=manifest.optJSONObject("generator");
        boolean library=generator!=null&&generator.optBoolean("actionLibrary",false);
        String shotId=shot.getString("id"),action=shot.getString("action");
        JSONObject match=null;
        for(int index=0;index<scenes.length();index++) {
            JSONObject candidate=scenes.getJSONObject(index);
            if(shotId.equals(candidate.getString("shotId"))) {
                if(match!=null)throw new IllegalArgumentException("Shot has ambiguous scene bindings");
                if(!action.equals(candidate.getString("action"))&&!library)
                    throw new IllegalArgumentException("Changed shot action requires a rebuilt scene binding");
                if(action.equals(candidate.getString("action")))match=candidate;
            }
        }
        if(match==null) {
            if(library) {
                for(int index=0;index<scenes.length();index++) {
                    JSONObject candidate=scenes.getJSONObject(index);
                    if(action.equals(candidate.getString("action"))) {
                        if(match!=null)throw new IllegalArgumentException("Action has ambiguous library scene bindings");
                        match=candidate;
                    }
                }
            }
        }
        if(match==null)throw new IllegalArgumentException("Shot has no exact manifest scene binding");
        return new JSONObject(match.toString()).put("shotId",shotId);
    }

    public static JSONObject frame(JSONObject shot,int shotFrame,int fps,JSONObject scene) throws Exception {
        if(scene==null||!shot.getString("id").equals(scene.getString("shotId"))||
                !shot.getString("action").equals(scene.getString("action")))
            throw new IllegalArgumentException("A matching exact scene binding is required");
        JSONObject sample=Cinematic3DTimeline.sampleShot(shot,shotFrame,fps);
        double length=scene.getDouble("clipDurationSeconds"),impact=scene.getDouble("impactPhase");
        if(!Double.isFinite(length)||length<=0||!Double.isFinite(impact)||impact<=0||impact>=1)
            throw new IllegalArgumentException("Invalid authored scene clock");
        double phase=sample.getDouble("phase");
        // The shared held phase is 0.5; imported clips can declare another contact time.
        sample.put("seconds",shotFrame/(double)NativeFrameClock.requireFps(fps));
        sample.put("clipSeconds",clipSeconds(phase,length,impact));
        sample.put("cameraSeconds",shotFrame/(double)shot.getInt("durationFrames")*length);
        sample.put("animationName",scene.getString("animationName"));
        sample.put("cameraName",scene.getString("cameraName"));
        sample.put("path",scene.getString("path"));
        sample.put("vfx",new JSONObject(shot.getJSONObject("vfx").toString()));
        JSONArray trailTimes=new JSONArray();
        // History is indexed by output frames, independently of authored clip FPS or retiming.
        JSONObject clockShot=new JSONObject().put("id",shot.getString("id")).put("action",shot.getString("action"))
                .put("durationFrames",shot.getInt("durationFrames")).put("hitStopFrames",shot.getInt("hitStopFrames"))
                .put("camera",shot.getJSONObject("camera")).put("vfx",shot.getJSONObject("vfx"));
        for(int age=0;age<=6;age++) {
            int previous=shotFrame-age;
            if(previous<0)trailTimes.put(JSONObject.NULL);
            else if(age==0)trailTimes.put(sample.getDouble("clipSeconds"));
            else trailTimes.put(clipSeconds(Cinematic3DTimeline.sampleShot(clockShot,previous,fps).getDouble("phase"),length,impact));
        }
        sample.put("trailClipSeconds",trailTimes);
        return sample;
    }

    private static double clipSeconds(double phase,double length,double impact) {
        return (phase<=0.5?phase*2*impact:impact+(phase-0.5)*2*(1-impact))*length;
    }

    /** Producer extras explicitly declare converted glTF local anchors; original Blender extras stay inert. */
    public static double[][] bladeEndpoints(double[] world,JSONObject extras) throws Exception {
        if(world==null||world.length!=16||extras==null)
            throw new IllegalArgumentException("An authored glTF world matrix and converted blade anchors are required");
        for(double value:world)if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite blade world matrix");
        if(Math.abs(world[3])>1e-7||Math.abs(world[7])>1e-7||Math.abs(world[11])>1e-7||Math.abs(world[15]-1)>1e-7)
            throw new IllegalArgumentException("Blade world matrix must be affine");
        double[][] result=new double[2][3];
        String[] keys={"gltf_blade_start","gltf_blade_end"};
        double[] depths={-.16,-1.06};
        for(int endpoint=0;endpoint<2;endpoint++) {
            JSONArray point=extras.optJSONArray(keys[endpoint]);
            if(point==null||point.length()!=3)throw new IllegalArgumentException("Converted glTF blade anchor is missing");
            for(int axis=0;axis<3;axis++) {
                Object value=point.get(axis);
                double expected=axis==2?depths[endpoint]:0;
                if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||
                        Math.abs(((Number)value).doubleValue()-expected)>1e-7)
                    throw new IllegalArgumentException("Blade anchors must use the declared negative-Z glTF katana template");
            }
            for(int axis=0;axis<3;axis++) {
                result[endpoint][axis]=world[axis]*point.getDouble(0)+world[4+axis]*point.getDouble(1)+
                        world[8+axis]*point.getDouble(2)+world[12+axis];
                if(!Double.isFinite(result[endpoint][axis]))throw new IllegalArgumentException("Blade world anchor overflow");
            }
        }
        return result;
    }

    public static Camera requireCamera(FilamentAsset asset,Engine engine,String exactName) {
        if(asset==null||engine==null||exactName==null||exactName.isEmpty())
            throw new IllegalArgumentException("An exact authored scene camera is required");
        Camera match=null;
        for(int entity:asset.getCameraEntities()) if(exactName.equals(asset.getName(entity))) {
            if(match!=null)throw new IllegalArgumentException("The exact manifest camera is ambiguous");
            match=engine.getCameraComponent(entity);
        }
        if(match==null)throw new IllegalArgumentException("The exact manifest camera is absent");
        return match;
    }

    public static void requireClipDuration(double actual,double declared) {
        if(!Double.isFinite(actual)||actual<=0||!Double.isFinite(declared)||declared<=0||
                Math.abs(actual-declared)>Math.max(.05,declared*.02))
            throw new IllegalArgumentException("The exact scene clip duration differs from its manifest");
    }

    public static int requireAuthoredScene(FilamentAsset asset,Animator animator,JSONObject scene) throws Exception {
        if(asset==null||asset.getInstance()==null||asset.getInstance().getSkinCount()<2)
            throw new IllegalArgumentException("A cinematic scene requires both genuinely weighted actors");
        if(animator==null)throw new IllegalArgumentException("The full scene has no authored animation");
        String[] names=new String[animator.getAnimationCount()];
        for(int i=0;i<names.length;i++)names[i]=animator.getAnimationName(i);
        int index=requireNamedIndex(names,scene.getString("animationName"));
        requireClipDuration(animator.getAnimationDuration(index),scene.getDouble("clipDurationSeconds"));
        return index;
    }

    public static void apply(Engine engine,FilamentAsset asset,Animator animator,int animationIndex,
                             Camera camera,JSONObject frame,int width,int height) throws Exception {
        if(engine==null||asset==null||animator==null||camera==null||frame==null||width<=0||height<=0)
            throw new IllegalArgumentException("A loaded full scene and sampled frame are required");
        animator.applyAnimation(animationIndex,(float)frame.getDouble("clipSeconds"));
        animator.updateBoneMatrices();
        // Actor, sword and portable VFX channels retain their authored hierarchy and scale.
        // Timeline camera controls use the unpaused shared sample, including top-down up.
        JSONObject vfx=frame.getJSONObject("vfx");
        setVisible(engine,asset,"C3D_Trail_Black",vfx.getBoolean("slashTrail"));
        setVisible(engine,asset,"C3D_Trail_White",vfx.getBoolean("slashTrail"));
        setVisible(engine,asset,"C3D_Spark_",vfx.getBoolean("sparkBurst"));
        setVisible(engine,asset,"C3D_Rubble_",vfx.getBoolean("rubble"));
        if(vfx.getBoolean("slashTrail"))applyOutputTrails(engine,asset,animator,animationIndex,frame);
        JSONObject motion=frame.getJSONObject("camera");
        JSONArray position=motion.getJSONArray("position"),target=motion.getJSONArray("target");
        JSONArray up=motion.optJSONArray("up");
        camera.setProjection(motion.getDouble("fovDegrees"),width/(double)height,.05,250,Camera.Fov.VERTICAL);
        camera.lookAt(position.getDouble(0),position.getDouble(1),position.getDouble(2),
                target.getDouble(0),target.getDouble(1),target.getDouble(2),
                up==null?0:up.getDouble(0),up==null?1:up.getDouble(1),up==null?0:up.getDouble(2));
    }

    private static JSONObject nodeExtras(FilamentAsset asset,int entity) throws Exception {
        String extras=asset.getExtras(entity);
        if(extras==null||extras.isEmpty())return new JSONObject();
        if(extras.length()>4096)throw new IllegalArgumentException("Portable trail node extras exceed the template bound");
        return new JSONObject(extras);
    }

    private static int exactEntity(FilamentAsset asset,String name) {
        int[] entities=asset.getEntitiesByName(name);
        if(entities.length!=1)throw new IllegalArgumentException("One exact portable trail node is required: "+name);
        return entities[0];
    }

    /** Only explicitly marked unit cubes are retimed; arbitrary imported VFX keep authored geometry. */
    private static void applyOutputTrails(Engine engine,FilamentAsset asset,Animator animator,int animation,
                                         JSONObject frame) throws Exception {
        String[] labels={"Black","White"};
        int[][] trailEntities=new int[2][6];
        boolean marked=false;
        for(int actor=0;actor<2;actor++)for(int age=0;age<6;age++) {
            int[] entities=asset.getEntitiesByName("C3D_Trail_"+labels[actor]+"_"+age);
            if(entities.length==1&&"unit_cube_v1".equals(nodeExtras(asset,entities[0]).optString("cinematic_trail_template"))) {
                trailEntities[actor][age]=entities[0]; marked=true;
            }
        }
        if(!marked)return;
        TransformManager transforms=engine.getTransformManager();
        RenderableManager renderables=engine.getRenderableManager();
        for(int actor=0;actor<2;actor++)for(int age=0;age<6;age++) {
            int entity=trailEntities[actor][age];
            if(entity==0)throw new IllegalArgumentException("The portable six-frame trail template is incomplete");
            int renderable=renderables.getInstance(entity);
            if(renderable==0)throw new IllegalArgumentException("The marked portable trail needs actual cube geometry");
            Box bounds=renderables.getAxisAlignedBoundingBox(renderable,new Box());
            float[] center=bounds.getCenter(),half=bounds.getHalfExtent();
            for(int axis=0;axis<3;axis++)if(Math.abs(center[axis])>1e-4||Math.abs(half[axis]-.5)>1e-4)
                throw new IllegalArgumentException("Marked portable trail geometry differs from its unit cube template");
        }
        int[] swords=new int[2]; JSONObject[] anchors=new JSONObject[2];
        for(int actor=0;actor<2;actor++) {
            swords[actor]=exactEntity(asset,labels[actor]+"Katana");
            anchors[actor]=nodeExtras(asset,swords[actor]);
            if(transforms.getInstance(swords[actor])==0)throw new IllegalArgumentException("Portable katana has no authored transform");
        }
        JSONArray times=frame.getJSONArray("trailClipSeconds");
        if(times.length()!=7||times.isNull(0))throw new IllegalArgumentException("Exactly current and six prior output samples are required");
        double[][][][] endpoints=new double[2][7][][];
        double current=frame.getDouble("clipSeconds");
        try {
            for(int sample=0;sample<7;sample++)if(!times.isNull(sample)) {
                animator.applyAnimation(animation,(float)times.getDouble(sample));
                animator.updateBoneMatrices();
                for(int actor=0;actor<2;actor++)endpoints[actor][sample]=bladeEndpoints(
                        transforms.getWorldTransform(transforms.getInstance(swords[actor]),new double[16]),anchors[actor]);
            }
        } finally {
            animator.applyAnimation(animation,(float)current);
            animator.updateBoneMatrices();
        }
        transforms.openLocalTransformTransaction();
        try {
            for(int actor=0;actor<2;actor++)for(int age=0;age<6;age++) {
                int entity=trailEntities[actor][age],renderable=renderables.getInstance(entity);
                if(endpoints[actor][age]==null||endpoints[actor][age+1]==null) {
                    renderables.setLayerMask(renderable,0xff,0); continue;
                }
                double[] world=Cinematic3DTrailGeometry.matrix(endpoints[actor][age][0],endpoints[actor][age][1],
                        endpoints[actor][age+1][0],endpoints[actor][age+1][1],age);
                boolean visible=world[0]!=0||world[1]!=0||world[2]!=0;
                renderables.setLayerMask(renderable,0xff,visible?0xff:0);
                if(visible)setWorldTransform(transforms,transforms.getInstance(entity),world);
            }
        } finally { transforms.commitLocalTransformTransaction(); }
    }

    private static void setWorldTransform(TransformManager transforms,int instance,double[] matrix) {
        float[] world=new float[16];
        for(int i=0;i<16;i++) {
            world[i]=(float)matrix[i];
            if(!Float.isFinite(world[i]))throw new IllegalArgumentException("Portable trail world transform overflow");
        }
        int parent=transforms.getParent(instance),parentInstance=transforms.getInstance(parent);
        if(parentInstance==0) { transforms.setTransform(instance,world); return; }
        float[] inverse=new float[16],local=new float[16];
        if(!Matrix.invertM(inverse,0,transforms.getWorldTransform(parentInstance,new float[16]),0))
            throw new IllegalArgumentException("Portable trail parent transform is singular");
        Matrix.multiplyMM(local,0,inverse,0,world,0);
        for(float value:local)if(!Float.isFinite(value))throw new IllegalArgumentException("Portable trail local transform overflow");
        transforms.setTransform(instance,local);
    }

    private static void setVisible(Engine engine,FilamentAsset asset,String prefix,boolean visible) {
        RenderableManager renderables=engine.getRenderableManager();
        for(int entity:asset.getEntitiesByPrefix(prefix)) {
            int instance=renderables.getInstance(entity);
            if(instance!=0)renderables.setLayerMask(instance,0xff,visible?0xff:0);
        }
    }

    /** Preview and export share this bounded light when the GLB has no portable lights. */
    public static int createFallbackLight(Engine engine,Scene scene,FilamentAsset asset) {
        if(asset.getLightEntities().length>0)return 0;
        int entity=EntityManager.get().create();
        try {
            new LightManager.Builder(LightManager.Type.SUN).color(1f,.85f,.7f).intensity(110000)
                    .direction(.3f,-.7f,-.5f).castShadows(true).build(engine,entity);
            scene.addEntity(entity);
            return entity;
        } catch(RuntimeException failure) {
            engine.destroyEntity(entity);
            EntityManager.get().destroy(entity);
            throw failure;
        }
    }
}
