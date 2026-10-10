package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Creates an owned transparent canvas; the shared title effect draws the editable glyphs. */
final class EditorTextFactory {
    static final class Result {
        final ProjectStore.Project project;
        final String clipId;
        final boolean reused;
        Result(ProjectStore.Project project, String clipId) { this(project, clipId, false); }
        Result(ProjectStore.Project project, String clipId, boolean reused) { this.project = project; this.clipId = clipId; this.reused = reused; }
    }

    private EditorTextFactory() { }

    static Result create(Context context, ProjectStore store, String projectId, long expectedRevision,
                         long startMs, long durationMs, String text, JSONObject style) throws Exception {
        return create(context,store,projectId,expectedRevision,startMs,durationMs,text,style,null);
    }

    static synchronized Result create(Context context, ProjectStore store, String projectId, long expectedRevision,
                         long startMs, long durationMs, String text, JSONObject style, String stableRequestKey) throws Exception {
        String requestKey=stableRequestKey==null?"":stableRequestKey.trim();
        if(requestKey.length()>256)throw new IllegalArgumentException("Title request key is too long");
        String assetId=requestKey.isEmpty()?UUID.randomUUID().toString():stableId(projectId,requestKey,"asset");
        String clipId=requestKey.isEmpty()?UUID.randomUUID().toString():stableId(projectId,requestKey,"clip");
        ProjectStore.Project existing=store.get(projectId);
        if(existing==null)throw new IllegalArgumentException("Title project no longer exists");
        if(!requestKey.isEmpty()){
            ProjectStore.Clip previous=existing.clip(clipId);ProjectStore.Asset previousAsset=existing.asset(assetId);
            if(previous!=null&&previousAsset!=null&&assetId.equals(previous.assetId)){
                Uri uri=Uri.parse(previousAsset.uri==null?"":previousAsset.uri);File owned="file".equals(uri.getScheme())?new File(uri.getPath()==null?"":uri.getPath()):null;
                if(owned==null||!owned.isFile()||!owned.canRead()||owned.length()==0)throw new IllegalStateException("The existing title canvas is unavailable; restore its project workspace before retrying");
                return new Result(existing,clipId,true);
            }
            if(previous!=null||previousAsset!=null)throw new IllegalStateException("This title request was already applied; its owner-edited graph cannot be recreated");
        }
        String title = text == null ? "" : text.trim();
        if (title.isEmpty() || title.length() > 2000) throw new IllegalArgumentException("Enter 1 to 2000 title characters");
        if (startMs < 0 || durationMs < 100 || durationMs > 3600000) throw new IllegalArgumentException("Title duration must be 0.1 to 3600 seconds");
        ProjectTimeline.safeAdd(startMs, durationMs);
        JSONObject effects = style == null ? new JSONObject() : new JSONObject(style.toString());
        if (!NativeTextOverlay.supportsFont(effects.optString("fontFamily", "sans-serif-medium"))) throw new IllegalArgumentException("Unsupported title font");
        if (!NativeTextOverlay.supportsAnimation(effects.optString("textAnimation", "none"))) throw new IllegalArgumentException("Unsupported title animation");
        effects.put("titleOnly", true);
        Color.parseColor(effects.optString("textColor", "#FFFFFF"));
        double size=effects.optDouble("textSize",.06),y=effects.optDouble("textY",.80);
        if(!Double.isFinite(size)||size<.02||size>.15||!Double.isFinite(y)||y<.05||y>.95)throw new IllegalArgumentException("Title size must be 0.02 to 0.15 and vertical position 0.05 to 0.95");
        ProjectStore.Clip candidate=new ProjectStore.Clip();candidate.inMs=0;candidate.outMs=durationMs;candidate.title=title;candidate.effects=effects;
        List<String> unsupported=NativeVideoEffects.unsupported(candidate);
        if(!unsupported.isEmpty())throw new IllegalArgumentException("Unsupported title settings: " + String.join(", ",unsupported));
        File directory = new File(new CreativeWorkspace(context).projectRoot(projectId), "generated/title_canvases");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Could not create title storage");
        File file = new File(directory, assetId + ".png");
        boolean createdFile=file.createNewFile();
        if(!createdFile){
            if(requestKey.isEmpty())throw new IllegalStateException("Could not allocate a new title canvas");
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
            if(bounds.outWidth!=32||bounds.outHeight!=32||!"image/png".equals(bounds.outMimeType))throw new IllegalStateException("An incomplete title canvas must be recovered before retrying");
        }
        boolean committed = false;
        try {
            if(createdFile){
                Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
                try (FileOutputStream output = new FileOutputStream(file)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("Could not write title canvas");
                } finally { bitmap.recycle(); }
            }
            ProjectStore.Project project = store.edit(projectId, expectedRevision, "Add title", p -> {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Title creation was cancelled before commit");
                ProjectStore.Track destination = null;
                for (ProjectStore.Track track : p.renderTracks()) {
                    if (track.locked || !("text".equals(track.type) || "subtitle".equals(track.type))) continue;
                    boolean available = true;
                    for (ProjectStore.Clip clip : p.clipsOnTrack(track.id)) {
                        if (clip.startMs < startMs + durationMs && clip.endMs() > startMs) { available = false; break; }
                    }
                    if (available) { destination = track; break; }
                }
                if (destination == null) {
                    destination = new ProjectStore.Track(); destination.id = UUID.randomUUID().toString();
                    destination.type = "text"; destination.name = "Titles " + (p.tracks.size() + 1);
                    int order = 0; for (ProjectStore.Track track : p.tracks) order = Math.max(order, track.order + 1);
                    destination.order = order; p.tracks.add(destination);
                }
                ProjectStore.Asset asset = new ProjectStore.Asset();
                asset.id = assetId; asset.uri = Uri.fromFile(file).toString(); asset.name = "Title · " + title.substring(0, Math.min(40, title.length())).replace('\n', ' ');
                asset.mime = "image/png"; asset.width = 32; asset.height = 32; asset.sizeBytes = file.length();
                asset.seekable = true; asset.role = "title_canvas"; asset.generated = true; asset.hasAudio = false;
                asset.generationMetadata.put("kind", "transparent_title_canvas");
                asset.generationMetadata.put("editableGlyphRenderer", "native_text_overlay");
                asset.generationMetadata.put("title", title);
                asset.generationMetadata.put("titleStyle", new JSONObject(effects.toString()));
                asset.generationMetadata.put("defaultDurationMs", durationMs);
                if(!requestKey.isEmpty())asset.generationMetadata.put("sourceCommandId",requestKey);
                p.assets.add(asset);
                ProjectStore.Clip clip = new ProjectStore.Clip(); clip.id = clipId; clip.assetId = assetId;
                clip.trackId = destination.id; clip.startMs = startMs; clip.inMs = 0; clip.outMs = durationMs;
                clip.title = title; clip.effects = new JSONObject(effects.toString());
                p.clips.add(clip);
            });
            committed = true;
            return new Result(project, clipId);
        } finally { if (!committed && createdFile) file.delete(); }
    }

    private static String stableId(String projectId,String requestKey,String role) {
        return UUID.nameUUIDFromBytes((projectId+":title:"+requestKey+":"+role).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
