package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Exact roll/slip/slide plans. Apply only inside ProjectStore.edit so revisions and history stay shared. */
final class ProjectAdvancedEdits {
    private ProjectAdvancedEdits() { }

    static void apply(ProjectStore.Project project, String operation, JSONObject settings) throws Exception {
        Plan plan=plan(project,operation,settings);
        if(plan.delta==0)return;
        for(String id:plan.affected){
            ProjectStore.Clip target=project.clip(id),edited=plan.after.clip(id);
            target.startMs=edited.startMs;target.inMs=edited.inMs;target.outMs=edited.outMs;
            target.programDurationMs=edited.programDurationMs;
            target.effects=new JSONObject(edited.effects.toString());
        }
    }

    /** Pure source inspection for owner feedback; never queries or opens media providers. */
    static JSONObject describe(ProjectStore.Project project,String operation,JSONObject settings) throws Exception {
        Plan plan=plan(project,operation,settings);JSONObject result=new JSONObject();
        result.put("operation",plan.operation);result.put("deltaMs",plan.delta);
        result.put("clipId",plan.selectedId);result.put("durationMs",plan.after.outputDurationMs());
        if(plan.boundary>=0){result.put("boundaryBeforeMs",plan.boundary);result.put("boundaryAfterMs",ProjectTimeline.safeAdd(plan.boundary,plan.delta));}
        JSONArray changes=new JSONArray();
        for(String id:plan.affected){
            ProjectStore.Clip before=project.clip(id),after=plan.after.clip(id);
            ProjectStore.Track track=project.track(before.trackId);ProjectStore.Asset asset=project.asset(before.assetId);
            JSONObject change=new JSONObject();change.put("clipId",id);change.put("trackId",before.trackId);
            change.put("name",asset==null?"Clip":asset.name);change.put("trackName",track==null?"Track":track.name);
            change.put("role",plan.left.contains(id)?"preceding":plan.right.contains(id)?"following":plan.operation.equals("slip")?"source":"target");
            change.put("startBeforeMs",before.startMs);change.put("startMs",after.startMs);
            change.put("endBeforeMs",before.endMs());change.put("endMs",after.endMs());
            change.put("inBeforeMs",before.inMs);change.put("inMs",after.inMs);
            change.put("outBeforeMs",before.outMs);change.put("outMs",after.outMs);
            changes.put(change);
        }
        result.put("changes",changes);return result;
    }

    private static final class Plan {
        ProjectStore.Project after;
        String operation,selectedId;
        long delta,boundary=-1;
        final Set<String> left=new LinkedHashSet<>(),right=new LinkedHashSet<>(),middle=new LinkedHashSet<>(),affected=new LinkedHashSet<>();
    }

    private static Plan plan(ProjectStore.Project project,String operation,JSONObject settings) throws Exception {
        if(project==null||settings==null)throw new IllegalArgumentException("Project and edit settings are required");
        String op=operation==null?"":operation.trim().toLowerCase(java.util.Locale.US);
        if(!op.equals("roll")&&!op.equals("slip")&&!op.equals("slide"))throw new IllegalArgumentException("Choose roll, slip or slide");
        String selectedId=settings.getString("clipId");ProjectStore.Clip selected=project.clip(selectedId);
        if(selected==null)throw new IllegalArgumentException("Selected clip is no longer in the project");
        long delta=integer(settings,"deltaMs");ProjectTimeline.validate(project);
        Plan plan=new Plan();plan.operation=op;plan.selectedId=selectedId;plan.delta=delta;plan.after=ProjectStore.copy(project);
        if(op.equals("slip")){
            addGroup(project,selected,plan.middle);
            for(String id:plan.middle){
                ProjectStore.Clip peer=project.clip(id);ProjectStore.Asset asset=project.asset(peer.assetId);
                if(asset==null||asset.mime==null||!(asset.mime.startsWith("audio/")||asset.mime.startsWith("video/")))throw new IllegalArgumentException("Slip requires temporal audio or video on every linked peer; still images and title canvases have no alternate source frames");
                sourceRange(project,peer);
            }
        }
        else if(op.equals("roll")){
            String edge=settings.optString("edge","end");
            if(!edge.equals("start")&&!edge.equals("end"))throw new IllegalArgumentException("Roll edge must be start or end");
            ProjectStore.Clip left=edge.equals("start")?adjacent(project,selected.trackId,selected.startMs,false):selected;
            ProjectStore.Clip right=edge.equals("end")?adjacent(project,selected.trackId,selected.endMs(),true):selected;
            if(left==null||right==null)throw new IllegalArgumentException("Roll requires two clips touching this boundary on the selected track");
            plan.boundary=left.endMs();addGroup(project,left,plan.left);addGroup(project,right,plan.right);
            expandRollBoundary(project,plan);
        }else{
            addGroup(project,selected,plan.middle);
            if(adjacent(project,selected.trackId,selected.startMs,false)==null||adjacent(project,selected.trackId,selected.endMs(),true)==null)
                throw new IllegalArgumentException("Slide requires touching preceding and following clips on the selected track");
            for(String id:plan.middle){
                ProjectStore.Clip peer=project.clip(id);
                ProjectStore.Clip left=adjacent(project,peer.trackId,peer.startMs,false),right=adjacent(project,peer.trackId,peer.endMs(),true);
                if(left!=null)addGroup(project,left,plan.left);if(right!=null)addGroup(project,right,plan.right);
            }
            requireSlideTopology(project,plan,selected.startMs,selected.endMs());
        }
        requireDisjoint(plan.left,plan.right);requireDisjoint(plan.left,plan.middle);requireDisjoint(plan.right,plan.middle);
        plan.affected.addAll(plan.left);plan.affected.addAll(plan.middle);plan.affected.addAll(plan.right);
        List<ProjectStore.Clip> lockedCheck=new ArrayList<>();for(String id:plan.affected)lockedCheck.add(project.clip(id));
        ProjectLinkedEdits.requireUnlocked(project,lockedCheck);
        if(delta!=0){
            for(String id:plan.left)resizeTail(project,plan.after,id,delta);
            for(String id:plan.right)resizeHead(project,plan.after,id,delta);
            for(String id:plan.middle){
                ProjectStore.Clip old=project.clip(id),edited=plan.after.clip(id);
                if(op.equals("slip"))slip(project,old,edited,delta);
                else edited.startMs=ProjectTimeline.safeAdd(old.startMs,delta);
            }
        }
        ProjectTimeline.validate(plan.after);ProjectTimeline.validateLocks(project,plan.after);
        if(plan.after.outputDurationMs()!=project.outputDurationMs())throw new IllegalArgumentException("This edit must preserve the program's outer duration");
        return plan;
    }

    private static void expandRollBoundary(ProjectStore.Project project,Plan plan) {
        boolean changed;
        do{
            int count=plan.left.size()+plan.right.size();
            for(String id:new ArrayList<>(plan.left)){
                ProjectStore.Clip clip=project.clip(id);
                if(clip.endMs()!=plan.boundary)throw new IllegalArgumentException("Linked preceding clips do not share this roll boundary");
                ProjectStore.Clip next=adjacent(project,clip.trackId,plan.boundary,true);if(next!=null)addGroup(project,next,plan.right);
            }
            for(String id:new ArrayList<>(plan.right)){
                ProjectStore.Clip clip=project.clip(id);
                if(clip.startMs!=plan.boundary)throw new IllegalArgumentException("Linked following clips do not share this roll boundary");
                ProjectStore.Clip previous=adjacent(project,clip.trackId,plan.boundary,false);if(previous!=null)addGroup(project,previous,plan.left);
            }
            requireDisjoint(plan.left,plan.right);changed=count!=plan.left.size()+plan.right.size();
        }while(changed);
    }

    private static void requireSlideTopology(ProjectStore.Project project,Plan plan,long start,long end) {
        for(String id:plan.left){
            ProjectStore.Clip clip=project.clip(id);if(clip.endMs()!=start)throw new IllegalArgumentException("A linked preceding clip is not adjacent to the slide target");
            ProjectStore.Clip next=adjacent(project,clip.trackId,start,true);
            if(next!=null&&!plan.middle.contains(next.id))throw new IllegalArgumentException("Slide reaches an unrelated middle clip on "+project.track(clip.trackId).name+"; link the middle counterparts or unlink the neighboring group first");
        }
        for(String id:plan.right){
            ProjectStore.Clip clip=project.clip(id);if(clip.startMs!=end)throw new IllegalArgumentException("A linked following clip is not adjacent to the slide target");
            ProjectStore.Clip previous=adjacent(project,clip.trackId,end,false);
            if(previous!=null&&!plan.middle.contains(previous.id))throw new IllegalArgumentException("Slide reaches an unrelated middle clip on "+project.track(clip.trackId).name+"; link the middle counterparts or unlink the neighboring group first");
        }
    }

    private static void resizeTail(ProjectStore.Project project,ProjectStore.Project next,String id,long delta) throws Exception {
        ProjectStore.Clip old=project.clip(id),edited=next.clip(id);long duration=ProjectTimeline.safeAdd(old.outputDurationMs(),delta);
        if(duration<=0)throw new IllegalArgumentException("The preceding clip would collapse; choose a smaller offset");
        edited.outMs=ProjectTimeline.safeAdd(old.inMs,handleSourceSpan(old,duration,old.effects.optLong("animationOffsetMs",0)));edited.programDurationMs=duration;
        if(edited.outMs==old.outMs)throw new IllegalArgumentException("This offset is below a linked clip's source millisecond precision; choose a larger offset");
        sourceRange(project,edited);ProjectLinkedEdits.animationWindow(edited,old.effects.optLong("animationDurationMs",old.outputDurationMs()),old.effects.optLong("animationOffsetMs",0));
        if(edited.startMs!=old.startMs)throw new IllegalArgumentException("The preceding clip's outer start must remain fixed");
    }

    private static void resizeHead(ProjectStore.Project project,ProjectStore.Project next,String id,long delta) throws Exception {
        ProjectStore.Clip old=project.clip(id),edited=next.clip(id);long duration=ProjectTimeline.safeAdd(old.outputDurationMs(),Math.negateExact(delta));
        if(duration<=0)throw new IllegalArgumentException("The following clip would collapse; choose a smaller offset");
        long offset=ProjectTimeline.safeAdd(old.effects.optLong("animationOffsetMs",0),delta);
        edited.inMs=Math.subtractExact(old.outMs,handleSourceSpan(old,duration,offset));edited.startMs=ProjectTimeline.safeAdd(old.startMs,delta);edited.programDurationMs=duration;
        if(edited.inMs==old.inMs)throw new IllegalArgumentException("This offset is below a linked clip's source millisecond precision; choose a larger offset");
        sourceRange(project,edited);ProjectLinkedEdits.animationWindow(edited,old.effects.optLong("animationDurationMs",old.outputDurationMs()),offset);
        if(edited.endMs()!=old.endMs())throw new IllegalArgumentException("The following clip's outer end must remain fixed");
    }

    private static void slip(ProjectStore.Project project,ProjectStore.Clip old,ProjectStore.Clip edited,long delta) throws Exception {
        ProjectStore.Asset asset=project.asset(old.assetId);
        if(asset==null||asset.mime==null||asset.mime.startsWith("image/"))throw new IllegalArgumentException("Slip requires temporal audio or video on every linked peer; still images and title canvases have no alternate source frames");
        long shift=sourceDelta(old.effectiveSpeed(),delta);edited.inMs=ProjectTimeline.safeAdd(old.inMs,shift);edited.outMs=ProjectTimeline.safeAdd(old.outMs,shift);
        edited.programDurationMs=old.outputDurationMs();sourceRange(project,edited);
        // Slip replaces footage under an unchanged program window. Fades and authored
        // automation stay on that window rather than following the new source head.
        if(edited.startMs!=old.startMs||edited.endMs()!=old.endMs())throw new IllegalArgumentException("Slip must preserve the clip's program position and span");
    }

    private static long sourceDelta(float rate,long delta) {
        double amount=delta*(double)rate;
        if(!Double.isFinite(amount)||amount<=Long.MIN_VALUE||amount>=Long.MAX_VALUE)throw new IllegalArgumentException("Source offset is too large");
        // Symmetric rounding lets an equal opposite slip restore the source
        // window, including rates that put the offset at half a millisecond.
        long rounded=amount<0?-Math.round(-amount):Math.round(amount);
        if(delta!=0&&rounded==0)throw new IllegalArgumentException("This offset is below a linked clip's source millisecond precision; choose a larger offset");
        return rounded;
    }

    private static long handleSourceSpan(ProjectStore.Clip clip,long duration,long offset) {
        // Use one authored phase for both boundaries. A closed sequence of head
        // and tail edits then restores the same source frames without rounding drift.
        long oldOffset=clip.effects.optLong("animationOffsetMs",0);
        long residual=Math.subtractExact(clip.outMs-clip.inMs,canonicalSourceSpan(oldOffset,clip.outputDurationMs(),clip.speed));
        long baseline=Math.max(1,canonicalSourceSpan(offset,duration,clip.speed));
        long desired=Math.max(1,ProjectTimeline.safeAdd(canonicalSourceSpan(offset,duration,clip.speed),residual));
        if(withinDurationTolerance(desired,duration,clip.speed))return desired;
        // Legacy exact spans may sit at the validation tolerance's edge. Project
        // their inherited residual to the nearest legal source millisecond.
        long low=Math.min(baseline,desired),high=Math.max(baseline,desired);
        boolean desiredAbove=desired>baseline;
        while(high-low>1){
            long middle=low+(high-low)/2;
            if(withinDurationTolerance(middle,duration,clip.speed)) {if(desiredAbove)low=middle;else high=middle;}
            else {if(desiredAbove)high=middle;else low=middle;}
        }
        return desiredAbove?low:high;
    }

    private static long canonicalSourceSpan(long offset,long duration,float rate) {
        return Math.subtractExact(roundedSourcePosition(ProjectTimeline.safeAdd(offset,duration),rate),roundedSourcePosition(offset,rate));
    }

    private static boolean withinDurationTolerance(long sourceSpan,long duration,float rate) {
        long nominal=Math.round(sourceSpan/(double)rate),tolerance=Math.max(2L,(long)Math.ceil(2d/rate));
        return Math.abs(nominal-duration)<=tolerance;
    }

    private static long roundedSourcePosition(long position,float rate) {
        double amount=position*(double)rate;
        if(!Double.isFinite(amount)||amount<=Long.MIN_VALUE||amount>=Long.MAX_VALUE)throw new IllegalArgumentException("Source span is too large");
        return Math.round(amount);
    }

    private static void sourceRange(ProjectStore.Project project,ProjectStore.Clip clip) {
        ProjectStore.Asset asset=project.asset(clip.assetId);
        if(asset==null||clip.inMs<0||clip.outMs<=clip.inMs)throw new IllegalArgumentException("The edit exceeds a clip's source head or would collapse its source span");
        if(asset.mime==null||!asset.mime.startsWith("image/")){
            if(asset.durationMs<=0)throw new IllegalArgumentException("The source duration is unknown; inspect or relink this media before editing its handles");
            if(clip.outMs>asset.durationMs)throw new IllegalArgumentException("The edit exceeds available source footage for "+asset.name);
        }
    }

    private static ProjectStore.Clip adjacent(ProjectStore.Project project,String trackId,long boundary,boolean following) {
        for(ProjectStore.Clip clip:project.clips)if(trackId.equals(clip.trackId)&&(following?clip.startMs==boundary:clip.endMs()==boundary))return clip;
        return null;
    }
    private static void addGroup(ProjectStore.Project project,ProjectStore.Clip clip,Set<String> destination) {
        for(ProjectStore.Clip peer:ProjectLinkedEdits.members(project,clip))destination.add(peer.id);
    }
    private static void requireDisjoint(Set<String> first,Set<String> second) {
        for(String id:first)if(second.contains(id))throw new IllegalArgumentException("This edit's linked groups overlap; unlink or align the participating groups first");
    }
    private static long integer(JSONObject settings,String key) throws Exception {
        Object value=settings.get(key);
        if(!(value instanceof Number))throw new IllegalArgumentException(key+" must be an integer in program milliseconds");
        double number=((Number)value).doubleValue();long integer=((Number)value).longValue();
        if(!Double.isFinite(number)||number!=integer||number<=Long.MIN_VALUE||number>=Long.MAX_VALUE)throw new IllegalArgumentException(key+" must be a finite integer in program milliseconds");
        return integer;
    }
}
