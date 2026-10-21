package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Bounded bundled recipe data, interpreted as native retained-object patches.
 * Never evaluates code or applies edits. The correction coordinator owns detached
 * candidate validation, source binding, preview, policy, history and atomic apply.
 */
final class SketchCorrectionRecipes {
    private static final String ASSET="animation_recipes/corrections-v1.json";
    private static final int VERSION=1,MAX_RECIPES=32,MAX_BYTES=64*1024;
    private static final Set<String> HUMAN_SETTINGS=Set.of("clothingStyle","faceStyle","shading","hairStyle","skinColor","hairColor","trouserColor");
    private static final Set<String> HAND_SETTINGS=Set.of("renderStyle","detail");
    private static final Set<String> ARM_CHANNELS=Set.of("nearElbowSwivel","farElbowSwivel","nearWrist","farWrist");
    private static final Set<String> LEGACY_ONLY=Set.of("clothingStyle","faceStyle","shading");
    private SketchCorrectionRecipes() {}

    static JSONObject options(Context context,JSONObject doc,String objectId)throws Exception {
        identity(objectId);Library library=load(context);JSONObject object=SketchDocument.object(doc,objectId),owner=SketchDocument.owner(doc,objectId);
        JSONArray options=new JSONArray();
        for(int i=0;i<library.recipes.length();i++){
            JSONObject recipe=library.recipes.getJSONObject(i);String reason=unavailable(object,owner,recipe);
            options.put(new JSONObject().put("id",recipe.getString("id")).put("label",recipe.getString("label"))
                .put("available",reason.isEmpty()).put("reason",reason.isEmpty()?"Available":reason)
                .put("expectedChange",recipe.getString("expectedChange"))
                .put("checks",new JSONArray(recipe.getJSONArray("checks").toString())));
        }
        return new JSONObject().put("libraryVersion",VERSION).put("librarySha256",library.sha256).put("objectId",objectId).put("options",options);
    }

    static JSONArray operations(Context context,JSONObject doc,String recipeId,String objectId)throws Exception {
        identity(objectId);identity(recipeId);Library library=load(context);JSONObject recipe=null;
        for(int i=0;i<library.recipes.length();i++)if(library.recipes.getJSONObject(i).getString("id").equals(recipeId)){recipe=library.recipes.getJSONObject(i);break;}
        if(recipe==null)throw SketchDocument.bad("Unknown correction recipe");
        JSONObject object=SketchDocument.object(doc,objectId);String reason=unavailable(object,SketchDocument.owner(doc,objectId),recipe);
        if(!reason.isEmpty())throw SketchDocument.bad("Correction recipe unavailable: "+reason);
        String kind=recipe.getString("kind");JSONObject payload=SketchDocument.copy(object.getJSONObject(kind));
        if(recipe.has("settingsPatch"))putAll(payload.getJSONObject("settings"),recipe.getJSONObject("settingsPatch"));
        if(recipe.has("posePatch")){
            JSONArray poses=payload.getJSONArray("poses");
            for(int i=0;i<poses.length();i++)putAll(poses.getJSONObject(i),recipe.getJSONObject("posePatch"));
        }
        if(recipe.optBoolean("blinkDefaults",false)&&!payload.has("blinkTrack"))payload.put("blinkTrack",SketchHumanBlink.defaults());
        return new JSONArray().put(new JSONObject().put("type","update_object").put("objectId",objectId)
            .put("patch",new JSONObject().put(kind,payload)));
    }

    private static String unavailable(JSONObject object,JSONObject owner,JSONObject recipe)throws Exception {
        if(owner.getBoolean("locked"))return "Target layer is locked";
        String kind=recipe.getString("kind");
        if(!kind.equals(object.getString("kind")))return "Requires a retained "+kind+" object";
        JSONObject payload=object.getJSONObject(kind),settings=payload.getJSONObject("settings");
        JSONObject effective=kind.equals("human")?SketchHumanFigure.settings(settings):SketchHandAnimation.settings(settings);
        if(kind.equals("human")){
            String style=effective.optString("figureStyle","legacy");boolean compatible=false;JSONArray styles=recipe.getJSONArray("figureStyles");
            for(int i=0;i<styles.length();i++)if(styles.getString(i).equals(style))compatible=true;
            if(!compatible)return style.equals("anatomy_sketch")?"This control does not affect the anatomy sketch renderer":"Requires anatomy sketch figure style";
        }
        boolean changed=false;
        if(recipe.has("settingsPatch")){
            JSONObject patch=recipe.getJSONObject("settingsPatch");Iterator<String> keys=patch.keys();
            while(keys.hasNext()){
                String key=keys.next();Object current=key.equals("clothingStyle")?effective.optString("clothingStyle","legacy"):effective.get(key);
                if(!same(current,patch.get(key),key.endsWith("Color")))changed=true;
            }
        }
        if(recipe.has("posePatch")){
            JSONObject patch=recipe.getJSONObject("posePatch"),defaults=SketchHumanFigure.poseDefaults();JSONArray poses=payload.getJSONArray("poses");
            if(poses.length()<1||poses.length()>240)throw SketchDocument.bad("Correction requires1..240 retained human pose keys");
            for(int i=0;i<poses.length();i++){
                JSONObject pose=poses.getJSONObject(i);Iterator<String> keys=patch.keys();
                while(keys.hasNext()){String key=keys.next();if(!same(pose.has(key)?pose.get(key):defaults.get(key),patch.get(key),false))changed=true;}
            }
        }
        if(recipe.optBoolean("blinkDefaults",false)){
            if(!payload.has("blinkTrack"))changed=true;
            else if(!changed)return "An existing native blink track is preserved";
        }
        return changed?"":"Already matches this recipe; no native change";
    }

    private static boolean same(Object a,Object b,boolean color){
        if(a instanceof Number&&b instanceof Number)return Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue())==0;
        if(color&&a instanceof String&&b instanceof String)return ((String)a).equalsIgnoreCase((String)b);
        return a.equals(b);
    }
    private static void putAll(JSONObject target,JSONObject patch)throws Exception {
        Iterator<String> keys=patch.keys();while(keys.hasNext()){String key=keys.next();target.put(key,patch.get(key));}
    }
    private static void identity(String id)throws Exception {SketchDocument.id(new JSONObject().put("id",id),"id");}

    private static Library load(Context context)throws Exception {
        if(context==null)throw SketchDocument.bad("Bundled recipes require an Android context");
        try(InputStream in=context.getAssets().open(ASSET);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;
            while((count=in.read(buffer))!=-1){if(out.size()+count>MAX_BYTES)throw SketchDocument.bad("Correction recipe asset exceeds64KiB");out.write(buffer,0,count);}
            return parse(out.toByteArray());
        }
    }
    private static final class Library {
        final JSONArray recipes;final String sha256;
        Library(JSONArray recipes,String sha256){this.recipes=recipes;this.sha256=sha256;}
    }

    /** Validate the complete catalogue before any recipe is offered or resolved. */
    private static Library parse(byte[] bytes)throws Exception {
        if(bytes.length==0||bytes.length>MAX_BYTES)throw SketchDocument.bad("Invalid correction recipe asset size");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        JSONObject root=new JSONObject(text);SketchDocument.fields(root,"libraryVersion,recipes");SketchDocument.integer(root,"libraryVersion",VERSION,VERSION);
        JSONArray recipes=root.getJSONArray("recipes");if(recipes.length()<1||recipes.length()>MAX_RECIPES)throw SketchDocument.bad("Use1..32 bundled correction recipes");
        Set<String> ids=new HashSet<>();
        for(int i=0;i<recipes.length();i++){
            JSONObject recipe=recipes.getJSONObject(i);SketchDocument.fields(recipe,"id,label,kind,figureStyles,settingsPatch,posePatch,blinkDefaults,expectedChange,checks");
            String id=SketchDocument.id(recipe,"id");if(id.length()>64||!ids.add(id))throw SketchDocument.bad("Correction recipe IDs must be unique and bounded");
            SketchDocument.text(recipe,"label",80);SketchDocument.text(recipe,"expectedChange",500);
            Object rawKind=recipe.get("kind");if(!(rawKind instanceof String)||!Set.of("human","hand").contains(rawKind))throw SketchDocument.bad("Unknown correction recipe object kind");
            String kind=(String)rawKind;JSONArray checks=recipe.getJSONArray("checks");
            if(checks.length()<1||checks.length()>8)throw SketchDocument.bad("Use1..8 correction recipe checks");
            for(int j=0;j<checks.length();j++)SketchDocument.text(new JSONObject().put("check",checks.get(j)),"check",200);
            if(kind.equals("human")){
                JSONArray styles=recipe.getJSONArray("figureStyles");Set<String> seen=new HashSet<>();
                if(styles.length()<1||styles.length()>2)throw SketchDocument.bad("Invalid correction figure styles");
                for(int j=0;j<styles.length();j++){
                    Object style=styles.get(j);if(!(style instanceof String)||!Set.of("legacy","anatomy_sketch").contains(style)||!seen.add((String)style))throw SketchDocument.bad("Unknown or duplicate correction figure style");
                }
            }else if(recipe.has("figureStyles")||recipe.has("posePatch")||recipe.has("blinkDefaults"))throw SketchDocument.bad("Hand recipes support native hand settings only");
            boolean patch=false;
            if(recipe.has("settingsPatch")){
                JSONObject settings=recipe.getJSONObject("settingsPatch");Set<String> allowed=kind.equals("human")?HUMAN_SETTINGS:HAND_SETTINGS;knownKeys(settings,allowed);patch=true;
                if(kind.equals("human")){
                    JSONArray styles=recipe.getJSONArray("figureStyles");
                    for(int j=0;j<styles.length();j++){
                        String style=styles.getString(j);
                        if(style.equals("anatomy_sketch"))for(String key:LEGACY_ONLY)if(settings.has(key))throw SketchDocument.bad("Legacy face, shading and clothing controls cannot affect anatomy sketch recipes");
                        SketchHumanFigure.settings(SketchDocument.copy(settings).put("figureStyle",style));
                    }
                }else SketchHandAnimation.settings(settings);
            }
            if(recipe.has("posePatch")){
                JSONObject pose=recipe.getJSONObject("posePatch");knownKeys(pose,ARM_CHANNELS);SketchHumanFigure.validatePose(pose);patch=true;
            }
            if(recipe.has("blinkDefaults")){
                if(!Boolean.TRUE.equals(recipe.get("blinkDefaults")))throw SketchDocument.bad("Recipe blink defaults must be true");patch=true;
            }
            if(!patch)throw SketchDocument.bad("Correction recipe must declare a bounded native patch");
        }
        return new Library(recipes,SketchDocument.sha256(bytes));
    }
    private static void knownKeys(JSONObject patch,Set<String> allowed)throws Exception {
        if(patch.length()<1||patch.length()>allowed.size())throw SketchDocument.bad("Invalid correction patch size");
        Iterator<String> keys=patch.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw SketchDocument.bad("Unknown correction patch key");
    }
}
