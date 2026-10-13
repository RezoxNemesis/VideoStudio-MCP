package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/** Copy-on-edit form helpers. These produce drafts, never bind a draft to an old baked scene. */
final class Cinematic3DDraftForms {
    static final String[] ACTORS = {"black","white"};
    static final String[] PRESETS = {"athletic","slender","stocky","chibi","robot"};
    static final String[] STYLES = {"grounded","anime","martial","cartoony"};
    private Cinematic3DDraftForms() {}
    static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    static JSONObject defaultActor() throws Exception { return new JSONObject().put("preset","athletic"); }
    static JSONObject actor(JSONObject timeline, String name) throws Exception {
        requireActor(name); return timeline.has("actors") ? copy(timeline.getJSONObject("actors").getJSONObject(name)) : defaultActor();
    }
    static JSONObject editActor(JSONObject timeline, String actor, JSONObject morphology) throws Exception {
        requireActor(actor); JSONObject next=copy(timeline), actors=next.has("actors")?next.getJSONObject("actors"):
                new JSONObject().put("black",defaultActor()).put("white",defaultActor());
        actors.put(actor,copy(morphology));next.put("actors",actors);return Cinematic3DTimeline.validate(next);
    }
    static JSONObject editStyle(JSONObject timeline,int shot,String preset,double intensity,boolean remove) throws Exception {
        JSONObject next=copy(timeline), target=next.getJSONArray("shots").getJSONObject(shot);
        if(remove)target.remove("motionStyle");else target.put("motionStyle",new JSONObject().put("preset",preset).put("intensity",intensity));
        return Cinematic3DTimeline.validate(next);
    }
    static JSONArray vector(double... numbers) throws Exception {
        JSONArray out=new JSONArray();for(double value:numbers){if(!Double.isFinite(value))throw new IllegalArgumentException("Finite pose coordinates required");out.put(value);}return out;
    }
    static JSONArray quaternion(double w,double x,double y,double z) throws Exception {
        double[] q={w,x,y,z};double max=0;for(double value:q){if(!Double.isFinite(value))throw new IllegalArgumentException("Finite wxyz quaternion required");max=Math.max(max,Math.abs(value));}
        if(max==0)throw new IllegalArgumentException("A zero quaternion cannot define a joint rotation");
        double norm=0;for(double value:q)norm+=(value/max)*(value/max);norm=Math.sqrt(norm);
        return vector((w/max)/norm,(x/max)/norm,(y/max)/norm,(z/max)/norm);
    }
    /** Euler XYZ: apply local X, then Y, then Z; returned order is w,x,y,z. */
    static JSONArray eulerXYZ(double x,double y,double z) throws Exception {
        for(double value:new double[]{x,y,z})if(!Double.isFinite(value)||Math.abs(value)>3600)throw new IllegalArgumentException("Euler angles must be within ±3600 degrees");
        double cx=Math.cos(Math.toRadians(x)/2),sx=Math.sin(Math.toRadians(x)/2),cy=Math.cos(Math.toRadians(y)/2),sy=Math.sin(Math.toRadians(y)/2),cz=Math.cos(Math.toRadians(z)/2),sz=Math.sin(Math.toRadians(z)/2);
        return quaternion(cx*cy*cz+sx*sy*sz,sx*cy*cz-cx*sy*sz,cx*sy*cz+sx*cy*sz,cx*cy*sz-sx*sy*cz);
    }
    static JSONObject poseBase(JSONObject timeline,int shot,double phase) throws Exception {
        phase(phase);JSONObject target=timeline.getJSONArray("shots").getJSONObject(shot);JSONArray keys=target.optJSONArray("poseKeyframes");JSONObject previous=null;
        if(keys!=null&&keys.length()>0){
            for(int i=0;i<keys.length();i++){JSONObject key=keys.getJSONObject(i);if(key.getDouble("phase")<=phase)previous=key;else break;}
            if(previous==null)previous=keys.getJSONObject(0);return copy(previous).put("phase",phase);
        }
        JSONObject actors=new JSONObject();for(String name:ACTORS){JSONArray rotations=new JSONArray();for(int i=0;i<16;i++)rotations.put(vector(1,0,0,0));
            actors.put(name,new JSONObject().put("root",vector("black".equals(name)?-1.15:1.15,0,0)).put("rotations",rotations));}
        return new JSONObject().put("phase",phase).put("actors",actors);
    }
    static JSONObject editPose(JSONObject timeline,int shot,String actor,int joint,double phase,JSONArray rotation,JSONArray root) throws Exception {
        requireActor(actor);if(joint<0||joint>=16)throw new IllegalArgumentException("Choose one of sixteen named local joints");phase(phase);
        if(rotation.length()!=4)throw new IllegalArgumentException("Exactly four wxyz components required");boundedRoot(root);
        JSONArray q=quaternion(rotation.getDouble(0),rotation.getDouble(1),rotation.getDouble(2),rotation.getDouble(3));
        JSONObject next=copy(timeline),key=poseBase(next,shot,phase),pose=key.getJSONObject("actors").getJSONObject(actor);
        pose.put("root",new JSONArray(root.toString()));pose.getJSONArray("rotations").put(joint,q);
        JSONObject target=next.getJSONArray("shots").getJSONObject(shot);JSONArray previous=target.optJSONArray("poseKeyframes"),out=new JSONArray();boolean inserted=false;
        if(previous!=null)for(int i=0;i<previous.length();i++){JSONObject existing=previous.getJSONObject(i);double p=existing.getDouble("phase");
            if(!inserted&&p>=phase){out.put(key);inserted=true;}if(Double.compare(p,phase)!=0)out.put(existing);}
        if(!inserted)out.put(key);target.put("poseKeyframes",out);return Cinematic3DTimeline.validate(next);
    }
    static JSONObject removePose(JSONObject timeline,int shot,double phase) throws Exception {
        phase(phase);JSONObject next=copy(timeline),target=next.getJSONArray("shots").getJSONObject(shot);JSONArray previous=target.optJSONArray("poseKeyframes"),out=new JSONArray();
        if(previous!=null)for(int i=0;i<previous.length();i++)if(Double.compare(previous.getJSONObject(i).getDouble("phase"),phase)!=0)out.put(previous.getJSONObject(i));
        if(out.length()==0)target.remove("poseKeyframes");else target.put("poseKeyframes",out);return Cinematic3DTimeline.validate(next);
    }
    static JSONObject editContact(JSONObject timeline,int shot,int existingIndex,String actor,String foot,double start,double end,JSONArray anchor,boolean remove) throws Exception {
        requireActor(actor);if(!"leading".equals(foot)&&!"trailing".equals(foot))throw new IllegalArgumentException("Choose leading or trailing ankle");
        phase(start);phase(end);if(!remove&&end<=start)throw new IllegalArgumentException("Contact ends after its start phase");boundedRoot(anchor);
        JSONObject next=copy(timeline),target=next.getJSONArray("shots").getJSONObject(shot);JSONArray previous=target.optJSONArray("footContacts");ArrayList<JSONObject> contacts=new ArrayList<>();
        if(previous!=null)for(int i=0;i<previous.length();i++)if(i!=existingIndex)contacts.add(previous.getJSONObject(i));
        if(!remove)contacts.add(new JSONObject().put("actor",actor).put("foot",foot).put("startPhase",start).put("endPhase",end).put("anchor",new JSONArray(anchor.toString())));
        contacts.sort((a,b)->{int value=a.optString("actor").compareTo(b.optString("actor"));if(value==0)value=a.optString("foot").compareTo(b.optString("foot"));return value==0?Double.compare(a.optDouble("startPhase"),b.optDouble("startPhase")):value;});
        JSONArray out=new JSONArray();for(JSONObject contact:contacts)out.put(contact);if(out.length()==0)target.remove("footContacts");else target.put("footContacts",out);
        return Cinematic3DTimeline.validate(next);
    }
    static JSONObject rebuildTemplate(ProjectStore.Project project,long expectedRevision,JSONObject timeline) throws Exception {
        if(project==null||project.updatedAt!=expectedRevision)throw new IllegalStateException("SOURCE_PROJECT_CHANGED: reopen the source before creating the rebuild request");
        JSONObject draft=Cinematic3DTimeline.validate(timeline);LinkedHashSet<String> ids=new LinkedHashSet<>();JSONArray shots=draft.getJSONArray("shots");
        for(int i=0;i<shots.length();i++){JSONArray references=shots.getJSONObject(i).optJSONArray("sourceAnchors");if(references==null)continue;
            for(int j=0;j<references.length();j++){String id=references.getJSONObject(j).getString("assetId");ProjectStore.Asset asset=project.asset(id);
                if(asset==null||asset.mime==null||!asset.mime.startsWith("image/"))throw new IllegalArgumentException("Every source anchor requires an image owned by this project");ids.add(id);}}
        JSONArray assets=new JSONArray();for(String id:ids)assets.put(id);
        if(assets.length()>64)throw new IllegalArgumentException("Blender rebuild requests support at most 64 unique source images");
        JSONObject request=new JSONObject().put("projectId",project.id).put("sourceProjectUpdatedAt",expectedRevision).put("backend","blender")
                .put("controls",new JSONObject().put("timeline",draft).put("renderFrames",0).put("conditioningFrames",new JSONArray())).put("assetIds",assets);
        if(request.toString().getBytes(StandardCharsets.UTF_8).length>512*1024)throw new IllegalArgumentException("Rebuild request exceeds the 512 KiB authoring budget");return request;
    }
    private static void requireActor(String actor){if(!"black".equals(actor)&&!"white".equals(actor))throw new IllegalArgumentException("Choose the black or white actor");}
    private static void phase(double phase){if(!Double.isFinite(phase)||phase<0||phase>1)throw new IllegalArgumentException("Shot phase must be within 0..1");}
    private static void boundedRoot(JSONArray root) throws Exception {if(root.length()!=3)throw new IllegalArgumentException("Exactly three world coordinates required");for(int i=0;i<3;i++){double value=root.getDouble(i);if(!Double.isFinite(value)||Math.abs(value)>20)throw new IllegalArgumentException("World coordinates must be within ±20 metres");}}
}
