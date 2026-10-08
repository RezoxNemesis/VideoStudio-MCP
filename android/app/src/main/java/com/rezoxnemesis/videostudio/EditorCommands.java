package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Transactional operations shared by the human editor and MCP. */
public final class EditorCommands {
    private EditorCommands() {}
    public static JSONObject execute(ProjectStore store, String projectId, JSONObject args) throws Exception {
        final JSONObject result = new JSONObject();
        store.mutate(projectId, project -> {
            String operation=args.optString("operation");
            if(args.has("expectedUpdatedAt") && args.optLong("expectedUpdatedAt")!=project.updatedAt)
                throw new IllegalStateException("Project changed. Refresh before applying this edit.");
            ProjectStore.Clip clip=null;
            for(ProjectStore.Clip c:project.clips) if(c.id.equals(args.optString("clipId"))) clip=c;
            String before=timeline(project).toString();
            if(operation.equals("undo") || operation.equals("redo")) {
                store.restoreTimelineHistory(project,operation.equals("redo"));
            } else if(operation.equals("rename_asset") || operation.equals("delete_asset") || operation.equals("replace_asset")) {
                ProjectStore.Asset asset=project.asset(args.optString("assetId"));
                if(asset==null) throw new IllegalArgumentException("Asset no longer exists");
                if(operation.equals("rename_asset")) {
                    String name=args.optString("name").trim(); if(name.isEmpty() || name.length()>200) throw new IllegalArgumentException("Enter a name of 1–200 characters");
                    asset.name=name;
                } else if(operation.equals("replace_asset")) {
                    ProjectStore.Asset replacement=project.asset(args.optString("replacementAssetId"));
                    if(replacement==null || !replacement.mime.equals(asset.mime)) throw new IllegalArgumentException("Choose an imported replacement of the same media type");
                    for(ProjectStore.Clip c:project.clips) if(c.assetId.equals(asset.id)) {
                        c.assetId=replacement.id;
                        if(!replacement.mime.startsWith("image/")) c.outMs=Math.min(c.outMs,replacement.durationMs);
                        if(c.outMs<=c.inMs) throw new IllegalArgumentException("Replacement is shorter than the clip's trim range");
                    }
                } else {
                    project.clips.removeIf(c->c.assetId.equals(asset.id)); project.assets.remove(asset);
                    // Never delete the user's source file or provider document.
                }
            } else if(operation.equals("add")) {
                ProjectStore.Asset asset=project.asset(args.optString("assetId"));
                if(asset==null) throw new IllegalArgumentException("Asset no longer exists");
                if(!asset.mime.startsWith("image/")&&!asset.mime.startsWith("video/")&&!asset.mime.startsWith("audio/")) throw new IllegalArgumentException("Asset is not playable media");
                ProjectStore.Clip c=new ProjectStore.Clip();c.id=UUID.randomUUID().toString();c.assetId=asset.id;c.outMs=asset.mime.startsWith("image/")?3000:asset.durationMs;
                if(c.outMs<=0) throw new IllegalArgumentException("Media duration is unavailable");
                c.track=args.optInt("track",asset.mime.startsWith("audio/")?2:0);c.timelineStartMs=Math.max(0,args.optLong("startMs",0));
                project.clips.add(c);result.put("clipId",c.id);
            } else {
                if(clip==null) throw new IllegalArgumentException("Select a clip first");
                int index=project.clips.indexOf(clip);
                switch(operation) {
                    case "remove": project.clips.remove(clip);break;
                    case "move": project.clips.remove(clip); project.clips.add(Math.max(0,Math.min(project.clips.size(),args.optInt("index"))),clip);break;
                    case "track": clip.track=Math.max(0,Math.min(3,args.optInt("track")));clip.timelineStartMs=Math.max(0,args.optLong("startMs"));break;
                    case "duplicate": ProjectStore.Clip copy=ProjectStore.Clip.fromJson(clip.toJson());copy.id=UUID.randomUUID().toString();project.clips.add(index+1,copy);break;
                    case "split": long at=args.optLong("atMs");if(at<=clip.inMs||at>=clip.outMs) throw new IllegalArgumentException("Move the playhead inside the clip");ProjectStore.Clip second=ProjectStore.Clip.fromJson(clip.toJson());second.id=UUID.randomUUID().toString();second.inMs=at;second.timelineStartMs=clip.timelineStartMs+(long)((at-clip.inMs)/clip.speed);clip.outMs=at;project.clips.add(index+1,second);break;
                    case "trim": long in=args.optLong("inMs"),out=args.optLong("outMs");ProjectStore.Asset a=project.asset(clip.assetId);if(in<0||out<=in||out-in>1200000||(!a.mime.startsWith("image/")&&out>a.durationMs)) throw new IllegalArgumentException("Trim exceeds source bounds");clip.inMs=in;clip.outMs=out;break;
                    case "speed": clip.speed=(float)number(args,"value",.25,4);break;
                    case "volume": clip.volume=(float)number(args,"value",0,2);break;
                    case "text": clip.title=args.optString("text");if(clip.title.length()>500) throw new IllegalArgumentException("Text is too long");break;
                    case "transition": String t=args.optString("value");if(!CreatorCatalog.TRANSITIONS.contains(t)) throw new IllegalArgumentException("Unknown transition");clip.transition=t;break;
                    case "effects": JSONObject fx=args.optJSONObject("effects");if(fx==null||fx.toString().length()>20000)throw new IllegalArgumentException("Invalid effects");validateEffects(fx);java.util.Iterator<String> keys=fx.keys();while(keys.hasNext()){String key=keys.next();clip.effects.put(key,fx.get(key));}break;
                    default: throw new IllegalArgumentException("Unknown editor operation");
                }
            }
            if(!operation.equals("undo")&&!operation.equals("redo")&&!operation.equals("rename_asset"))store.recordTimelineHistory(project.id,before,timeline(project).toString());
            result.put("ok",true).put("projectId",project.id).put("operation",operation);
        });
        result.put("project",store.get(projectId).toJson());return result;
    }
    private static void validateEffects(JSONObject fx)throws Exception {
        JSONArray crop=fx.optJSONArray("cropBounds");if(crop!=null){if(crop.length()!=4)throw new IllegalArgumentException("Crop needs four coordinates");double[] v=new double[4];for(int i=0;i<4;i++){v[i]=crop.optDouble(i,Double.NaN);if(!Double.isFinite(v[i])||v[i]<0||v[i]>1)throw new IllegalArgumentException("Crop coordinates must be 0–1");}if(v[0]>=v[2]||v[1]>=v[3])throw new IllegalArgumentException("Crop rectangle is empty");}
        JSONArray frames=fx.optJSONArray("editorKeyframes");if(frames!=null){if(frames.length()<2||frames.length()>120)throw new IllegalArgumentException("Enter 2–120 keyframes");double previous=-1;for(int i=0;i<frames.length();i++){JSONObject frame=frames.getJSONObject(i);double time=number(frame,"t",0,1);if(time<=previous)throw new IllegalArgumentException("Keyframes must be in increasing time order");previous=time;for(String key:new String[]{"x","y"})if(frame.has(key))number(frame,key,-2,2);if(frame.has("scale"))number(frame,"scale",.1,4);if(frame.has("rotation"))number(frame,"rotation",-360,360);}if(frames.getJSONObject(0).getDouble("t")!=0||frames.getJSONObject(frames.length()-1).getDouble("t")!=1)throw new IllegalArgumentException("Keyframes must cover 0–1 of the clip");}
        if(fx.has("mask")&&!java.util.Arrays.asList("none","circle","rounded_rect","green_screen").contains(fx.getString("mask")))throw new IllegalArgumentException("Unknown mask shape");
    }
    static JSONArray timeline(ProjectStore.Project p) {JSONArray a=new JSONArray();for(ProjectStore.Clip c:p.clips)a.put(c.toJson());return a;}
    static double number(JSONObject p,String key,double min,double max) {double n=p.optDouble(key,Double.NaN);if(!Double.isFinite(n)||n<min||n>max)throw new IllegalArgumentException(key+" must be between "+min+" and "+max);return n;}
}
