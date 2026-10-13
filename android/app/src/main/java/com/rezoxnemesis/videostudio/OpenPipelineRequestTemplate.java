package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Inert, revision-bound human authoring handoff. Never executes or configures a runner. */
public final class OpenPipelineRequestTemplate {
    private OpenPipelineRequestTemplate() {}
    public static String readHandoff(Context context,String inline,String fileName,String expectedSha256)throws Exception {
        if(inline!=null){if(fileName!=null||expectedSha256!=null)throw new IllegalArgumentException("Choose one inert authoring handoff");if(inline.length()>OpenPipelineArtifacts.MAX_JSON||inline.getBytes(StandardCharsets.UTF_8).length>OpenPipelineArtifacts.MAX_JSON)throw new IllegalArgumentException("Authoring template exceeds its metadata limit");return inline;}
        if(fileName==null||expectedSha256==null||!fileName.endsWith(".json"))throw new IllegalArgumentException("A complete authoring template handoff is required");
        String uuid=fileName.substring(0,fileName.length()-5);if(!UUID.fromString(uuid).toString().equals(uuid))throw new IllegalArgumentException("Unsafe authoring template filename");OpenPipelineArtifacts.sha(expectedSha256);
        File root=new File(context.getFilesDir(),"open_pipeline_templates"),file=new File(root,fileName);
        if(java.nio.file.Files.isSymbolicLink(root.toPath())||java.nio.file.Files.isSymbolicLink(file.toPath())||!file.getCanonicalPath().equals(new File(root.getCanonicalFile(),fileName).getAbsolutePath())||!file.isFile()||file.length()<1||file.length()>OpenPipelineArtifacts.MAX_JSON)throw new IllegalArgumentException("Authoring template must be an owned bounded plain file");
        ByteArrayOutputStream output=new ByteArrayOutputStream();try(FileInputStream in=new FileInputStream(file)){OpenPipelineArtifacts.copy(in,output,OpenPipelineArtifacts.MAX_JSON);}byte[] bytes=output.toByteArray();if(!expectedSha256.equals(OpenPipelineArtifacts.hash(bytes)))throw new IllegalStateException("AUTHORING_TEMPLATE_CHANGED: file checksum differs");return new String(bytes,StandardCharsets.UTF_8);
    }
    public static JSONObject validate(String raw,ProjectStore.Project project)throws Exception {
        if(raw==null||raw.length()>OpenPipelineArtifacts.MAX_JSON)throw new IllegalArgumentException("Authoring request template exceeds its metadata limit");
        byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);if(bytes.length>OpenPipelineArtifacts.MAX_JSON)throw new IllegalArgumentException("Authoring request template exceeds its metadata limit");
        JSONObject template=OpenPipelineArtifacts.json(new ByteArrayInputStream(bytes));
        OpenPipelineArtifacts.fields(template,"projectId","sourceProjectUpdatedAt","backend","controls","assetIds");
        String id=OpenPipelineArtifacts.id(template.getString("projectId"));long revision=OpenPipelineArtifacts.integer(template,"sourceProjectUpdatedAt",0,Long.MAX_VALUE);
        if(project==null||!id.equals(project.id)||revision!=project.updatedAt)throw new IllegalStateException("SOURCE_PROJECT_CHANGED: reopen the cinematic authoring studio before rebuilding");
        if(!"blender".equals(template.getString("backend")))throw new IllegalArgumentException("Cinematic rebuild templates must use Blender");
        JSONObject controls=OpenPipelineOperations.validateControls("blender",template.getJSONObject("controls"));
        if(controls.optLong("renderFrames",0)!=0||controls.optJSONArray("conditioningFrames")!=null&&controls.getJSONArray("conditioningFrames").length()!=0)throw new IllegalArgumentException("Authoring handoff is a rebuild request; choose rendering explicitly after opening the workbench");
        JSONArray ids=template.getJSONArray("assetIds");if(ids.length()>64)throw new IllegalArgumentException("Too many rebuild source anchors");Set<String> selected=new LinkedHashSet<>();
        for(int i=0;i<ids.length();i++){String asset=OpenPipelineArtifacts.id(ids.getString(i));ProjectStore.Asset owned=project.asset(asset);if(!selected.add(asset)||owned==null||owned.mime==null||!owned.mime.startsWith("image/"))throw new IllegalArgumentException("Rebuild references must be unique image assets owned by this project");}
        Set<String> anchors=new HashSet<>();JSONArray shots=controls.getJSONObject("timeline").getJSONArray("shots");
        for(int i=0;i<shots.length();i++){JSONArray refs=shots.getJSONObject(i).optJSONArray("sourceAnchors");if(refs!=null)for(int j=0;j<refs.length();j++)anchors.add(refs.getJSONObject(j).getString("assetId"));}
        if(!selected.equals(anchors))throw new IllegalArgumentException("Rebuild selection must match the timeline's original source anchors");
        return template.put("controls",controls);
    }
    /** Bind authored anchors to the actual immutable bytes captured by native export. */
    static void verifySourceBindings(JSONObject controls,JSONArray sources)throws Exception {
        Map<String,String> hashes=new HashMap<>();for(int i=0;i<sources.length();i++){JSONObject source=sources.getJSONObject(i);hashes.put(source.getString("assetId"),source.getString("sha256"));}
        JSONArray shots=controls.getJSONObject("timeline").getJSONArray("shots");for(int i=0;i<shots.length();i++){JSONArray refs=shots.getJSONObject(i).optJSONArray("sourceAnchors");if(refs==null)continue;for(int j=0;j<refs.length();j++){JSONObject anchor=refs.getJSONObject(j);if(!anchor.getString("sha256").equals(hashes.get(anchor.getString("assetId"))))throw new IllegalStateException("SOURCE_ANCHOR_CHANGED: captured originals differ from the authored timeline");}}
    }
}
