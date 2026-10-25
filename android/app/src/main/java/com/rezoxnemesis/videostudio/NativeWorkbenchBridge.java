package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Closed native aliases for the existing generic app_execute relay. No permission is added. */
public final class NativeWorkbenchBridge {
    public static final String PREFIX="studio_",MINIMUM_NATIVE_VERSION="3.9.0";
    private static final int MAX_COMMAND_BYTES=2*1024*1024,MAX_DEPTH=64,MAX_VALUES=2*1024*1024;
    private static final Set<String> ACTIONS=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "precision_edit","apply_tool","set_studio_layers",
            "kinetic_benchmark_capabilities","kinetic_benchmark_plan","kinetic_benchmark_apply",
            "motion_group_capabilities","motion_group_plan","motion_group_edit",
            "camera2d_capabilities","camera2d_plan","camera2d_apply",
            "resolve_color_capabilities","resolve_grade_plan","resolve_grade_apply",
            "fairlight_fx_capabilities","fairlight_fx_plan","fairlight_fx_apply",
            "fusion_scene_capabilities","fusion_scene_plan","fusion_scene_apply","import_mailbox_media","project_asset_info","project_import_asset","get_native_result_page","get_native_delivery_status","compact_native_delivery",
            "sketch_create","sketch_list","sketch_get","sketch_inspect","sketch_review","sketch_quality_audit","sketch_edit","sketch_xsheet","sketch_stage_capabilities","sketch_stage_execute","sketch_stage_status","sketch_undo","sketch_redo",
            "sketch_animation_save","sketch_animation_get","sketch_animation_list","sketch_animation_bake","sketch_animation_place_in_book",
            "sketch_story_create","sketch_story_preview","sketch_story_audio_create","sketch_story_example",
            "sketch_correction_start","sketch_correction_get","sketch_correction_note","sketch_correction_propose","sketch_correction_preview","sketch_correction_apply","sketch_correction_options","sketch_correction_list","sketch_correction_close",
            "sketch_paint_journal","sketch_sample_color","sketch_archive","sketch_restore_archive",
            "sketch_import_reference","sketch_import_reference_layers","sketch_reference_layers_preview","sketch_layered_drawing_preview","sketch_import_frame_manifest","sketch_import_drawing_manifest","sketch_import_drawing_asset","sketch_import_animation","sketch_preview","sketch_render",
            "sketch_book_create","sketch_book_edit","sketch_book_list","sketch_book_get","sketch_book_open_page","sketch_book_timeline","sketch_book_render","sketch_live_begin","sketch_live_events","sketch_live_end","sketch_capabilities","sketch_component_save","sketch_component_list","sketch_component_apply",
            "local_drawing_models","local_drawing_install","local_drawing_generate","local_drawing_get",
            "open_pipeline_configure","open_pipeline_capabilities","open_pipeline_list",
            "open_pipeline_export_request","open_pipeline_submit","open_pipeline_status","open_pipeline_cancel",
            "open_pipeline_download_result","open_pipeline_import_result","open_pipeline_archive_artifact","open_pipeline_restore_artifact",
            "cinematic_3d_capabilities","cinematic_3d_install_builtin","cinematic_3d_import_bundle",
            "cinematic_3d_save_program","cinematic_3d_edit_program","cinematic_3d_get_program","cinematic_3d_list_programs",
            "cinematic_3d_plan","cinematic_3d_export_request","cinematic_3d_render"
    )));
    private NativeWorkbenchBridge() {}

    public static String canonicalAction(String action) {
        // Closed additive aliases let an older v3 relay forward newly shaped requests.
        if("studio_cinematic_3d_edit_program_v4103".equals(action))return "cinematic_3d_edit_program";
        if("studio_cinematic_3d_get_program_v4103".equals(action))return "cinematic_3d_get_program";
        if(action!=null&&action.startsWith(PREFIX)){
            String candidate=action.substring(PREFIX.length());
            if(ACTIONS.contains(candidate))return candidate;
        }
        return action;
    }
    /** Clone ordinary JSON deeply; retain credentials in memory for the existing protected facade. */
    public static JSONObject canonicalCommand(JSONObject command)throws Exception {
        if(command==null)throw new IllegalArgumentException("A native command is required");
        JSONObject copy=(JSONObject)copy(command,0,new IdentityHashMap<>(),new Budget());
        if(copy.toString().getBytes(StandardCharsets.UTF_8).length>MAX_COMMAND_BYTES)throw new IllegalArgumentException("Native command exceeds its relay metadata budget");
        Object action=copy.opt("action");if(action instanceof String)copy.put("action",canonicalAction((String)action));
        return copy;
    }
    public static JSONObject capabilities()throws Exception {
        JSONArray actions=new JSONArray(),aliases=new JSONArray();for(String action:ACTIONS){actions.put(action);aliases.put(PREFIX+action);}
        return new JSONObject().put("tool","app_execute").put("aliasPrefix",PREFIX)
                .put("minimumNativeVersion",MINIMUM_NATIVE_VERSION).put("canonicalActions",actions).put("aliases",aliases)
                .put("colourTraceMinimumNativeVersion","3.9.5").put("drawingManifestMinimumNativeVersion","3.9.5").put("advancedSketchMinimumNativeVersion","3.9.1").put("croppedSketchPreviewMinimumNativeVersion","3.9.3").put("flatParameters",true).put("permissionPolicy","existing-native-action-permissions-and-source-validation")
                .put("featureLengthSketchMinimumNativeVersion","4.7.0").put("sketchSequenceMaximumFrames",SketchSequenceLimits.MAX_FRAMES)
                .put("expandedReferenceImageBudgetMinimumNativeVersion","4.8.6").put("sketchMaximumImageObjects",SketchDocument.MAX_IMAGES)
                .put("referenceLayersPreviewMinimumNativeVersion","4.8.5").put("referenceLayersMinimumNativeVersion","4.8.5").put("sourceMaskMinimumNativeVersion","4.8.5")
                .put("layeredDrawingMinimumNativeVersion","4.9.2").put("authoredPrecisionPenMinimumNativeVersion","4.9.1")
                .put("paintMinimumNativeVersion","4.1.0").put("editableArchiveMinimumNativeVersion","4.1.0")
                .put("visualReviewMinimumNativeVersion","4.5.6").put("visualReviewAction","sketch_review")
                .put("characterAnimationMinimumNativeVersion","4.4.0").put("animationBookPlacementMinimumNativeVersion","4.8.0")
                .put("storyPlanMinimumNativeVersion","4.8.0").put("storyAudioMinimumNativeVersion","4.8.0")
                .put("projectAssetImport",ProjectAssetImport.capabilities())
                .put("studioEditing",StudioEditingCapabilities.capabilities())
                .put("kineticDirector",StudioKineticDirector.capabilities())
                .put("motionGroups",StudioMotionGroupOps.capabilities())
                .put("compositionCamera2D",StudioCamera2DDirector.capabilities())
                .put("resolveColorPage",StudioResolveColorEdits.capabilities())
                .put("fairlightAudio",StudioFairlightEdits.capabilities())
                .put("fusionSceneCompiler",StudioFusionSceneCompiler.capabilities())
                .put("sketchStillExport",SketchStillExport.capabilities())
                .put("sketchVisibleAgent",SketchAgentStageCommands.capabilities())
                .put("encryptedMediaImportMinimumNativeVersion","4.2.0").put("encryptedMediaImportMaximumBytes",256L*1024*1024)
                .put("drawingAssetImportMinimumNativeVersion","4.3.1").put("nativeResultPageMinimumNativeVersion","4.3.1")
                .put("incrementalKeyframeMinimumNativeVersion","4.3.0").put("cameraEditMinimumNativeVersion","4.3.0")
                .put("symmetryMinimumNativeVersion","4.3.0").put("stereoMixMinimumNativeVersion","4.3.0").put("captionStyleMinimumNativeVersion","4.3.0")
                .put("localNeuralDrawing",LocalDrawingCapabilities.get())
                .put("compatibilityAliases",CinematicCameraOrbit.capabilities().getJSONObject("compatibilityAliases"))
                .put("galleryAccess",false).put("nativeExecutionVerified",false);
    }
    private static final class Budget {int values;long characters;void add(long amount){characters+=amount;if(characters>MAX_COMMAND_BYTES)throw new IllegalArgumentException("Native command exceeds its relay metadata budget");}}
    private static Object copy(Object value,int depth,IdentityHashMap<Object,Boolean> active,Budget budget)throws Exception {
        if(depth>MAX_DEPTH||++budget.values>MAX_VALUES)throw new IllegalArgumentException("Native command exceeds its bounded JSON structure");
        if(value==null||value==JSONObject.NULL){budget.add(4);return JSONObject.NULL;}
        if(value instanceof String){budget.add(((String)value).length()+2L);return value;}
        if(value instanceof Boolean){budget.add(5);return value;}
        if(value instanceof Number){Class<?> type=value.getClass();if(!Arrays.asList(Byte.class,Short.class,Integer.class,Long.class,Float.class,Double.class,BigDecimal.class,BigInteger.class).contains(type)||!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Native command requires finite plain JSON numbers");budget.add(JSONObject.numberToString((Number)value).length());return value;}
        if(!(value instanceof JSONObject)&&!(value instanceof JSONArray))throw new IllegalArgumentException("Native command requires plain JSON values");
        if(active.put(value,true)!=null)throw new IllegalArgumentException("Native command cannot contain a JSON cycle");
        try {
            budget.add(2);
            if(value instanceof JSONObject){JSONObject source=(JSONObject)value,result=new JSONObject();Iterator<String> keys=source.keys();while(keys.hasNext()){String key=keys.next();budget.add(key.length()+4L);result.put(key,copy(source.get(key),depth+1,active,budget));}return result;}
            JSONArray source=(JSONArray)value,result=new JSONArray();for(int i=0;i<source.length();i++){budget.add(1);result.put(copy(source.get(i),depth+1,active,budget));}return result;
        }finally{active.remove(value);}
    }
}
