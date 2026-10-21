package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchCorrectionRecipesTest {
    private static final String ASSET="animation_recipes/corrections-v1.json";
    private static Set<String> keys(JSONObject value){Set<String> result=new HashSet<>();value.keys().forEachRemaining(result::add);return result;}
    private Context context(){return RuntimeEnvironment.getApplication();}
    private Class<?> helper(){
        try{return Class.forName("com.rezoxnemesis.videostudio.SketchCorrectionRecipes");}
        catch(ClassNotFoundException e){throw new AssertionError("Bundled native correction recipe helper is missing",e);}
    }
    private Object call(String name,Class<?>[] types,Object...args)throws Exception {
        try{return helper().getDeclaredMethod(name,types).invoke(null,args);}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}
    }
    private JSONObject options(JSONObject doc,String id)throws Exception {
        return (JSONObject)call("options",new Class<?>[]{Context.class,JSONObject.class,String.class},context(),doc,id);
    }
    private JSONArray operations(JSONObject doc,String recipe,String id)throws Exception {
        return (JSONArray)call("operations",new Class<?>[]{Context.class,JSONObject.class,String.class,String.class},context(),doc,recipe,id);
    }
    private JSONObject option(JSONObject options,String id)throws Exception {
        JSONArray all=options.getJSONArray("options");for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("id").equals(id))return all.getJSONObject(i);
        throw new AssertionError("Missing recipe "+id);
    }
    private JSONObject document()throws Exception {
        return SketchDocument.create(new JSONObject().put("sketchId","recipe_test").put("width",1280).put("height",720).put("frameCount",120).put("fps",30));
    }
    private JSONObject human(boolean anatomy)throws Exception {
        JSONObject doc=document();JSONArray poses=new JSONArray()
            .put(new JSONObject().put("frame",0).put("interpolation","bezier").put("curve",new JSONArray("[0.2,0.1,0.8,0.9]")).put("nearHandX",92).put("nearHandY",-210).put("nearHandZ",34).put("farHandZ",-22).put("gazeX",.4).put("mouth",.2).put("handOpen",.6))
            .put(new JSONObject().put("frame",90).put("interpolation","hold").put("farHandX",-72).put("farHandY",-195).put("gazeY",-.3).put("smile",.25));
        JSONObject settings=new JSONObject().put("size",.8);if(anatomy)settings.put("figureStyle","anatomy_sketch");
        JSONObject speech=new JSONObject().put("transitionMs",45).put("cues",new JSONArray().put(new JSONObject().put("startMs",100).put("endMs",850).put("shape","open").put("strength",.7)));
        SketchEditOps.apply(doc,new JSONObject().put("type","add_human_animation").put("layerId","layer_1").put("actorId","actor").put("settings",settings).put("poses",poses).put("speech",speech));
        JSONObject payload=SketchDocument.object(doc,"actor_rig").getJSONObject("human");payload.put("sourceFrameOffset",7).put("parts","all");
        payload.getJSONObject("settings").remove("faceStyle"); // Valid omitted default must remain omitted unless the recipe changes it.
        return doc;
    }
    private JSONObject hand()throws Exception {
        JSONObject doc=document();SketchEditOps.apply(doc,new JSONObject().put("type","add_hand_animation").put("layerId","layer_1").put("handId","hand").put("settings",new JSONObject().put("motion",.4)));return doc;
    }

    @Test public void catalogueIsBoundedMetadataAndIdentifiesExactPackagedBytes()throws Exception {
        JSONObject doc=human(false);String before=SketchDocument.canonical(doc);JSONObject result=options(doc,"actor_rig");
        assertEquals(Set.of("libraryVersion","librarySha256","objectId","options"),keys(result));assertEquals(1,result.getInt("libraryVersion"));assertEquals("actor_rig",result.getString("objectId"));
        try(InputStream in=context().getAssets().open(ASSET)){assertEquals(SketchDocument.sha256(in.readAllBytes()),result.getString("librarySha256"));}
        Set<String> ids=new HashSet<>();JSONArray choices=result.getJSONArray("options");assertEquals(12,choices.length());
        for(int i=0;i<choices.length();i++){
            JSONObject o=choices.getJSONObject(i);assertEquals(Set.of("id","label","available","reason","expectedChange","checks"),keys(o));assertTrue(ids.add(o.getString("id")));
            assertTrue(o.get("available") instanceof Boolean);assertTrue(o.getString("label").length()<=80);assertTrue(o.getString("reason").length()<=240);
            assertTrue(o.getString("expectedChange").length()<=500);assertTrue(o.getJSONArray("checks").length()>0&&o.getJSONArray("checks").length()<=8);
        }
        assertEquals(before,SketchDocument.canonical(doc));
    }

    @Test public void neutralArmsChangesExactlyFourChannelsAcrossEveryKeyAndPreservesPayload()throws Exception {
        JSONObject doc=human(false),source=SketchDocument.object(doc,"actor_rig").getJSONObject("human");String before=SketchDocument.canonical(doc);
        JSONArray ops=operations(doc,"neutral_arms","actor_rig");assertEquals(1,ops.length());JSONObject op=ops.getJSONObject(0);
        assertEquals(Set.of("type","objectId","patch"),keys(op));assertEquals("update_object",op.getString("type"));assertEquals(Set.of("human"),keys(op.getJSONObject("patch")));
        JSONObject changed=op.getJSONObject("patch").getJSONObject("human"),expected=SketchDocument.copy(source);
        for(int i=0;i<expected.getJSONArray("poses").length();i++){
            JSONObject pose=expected.getJSONArray("poses").getJSONObject(i);pose.put("nearElbowSwivel",90).put("farElbowSwivel",-90).put("nearWrist",-6).put("farWrist",6);
        }
        assertEquals(SketchDocument.canonical(expected),SketchDocument.canonical(changed));assertEquals(before,SketchDocument.canonical(doc));
        changed.getJSONObject("speech").put("transitionMs",99);assertEquals(before,SketchDocument.canonical(doc)); // No alias back into the source.
    }

    @Test public void incompatibleAndUnchangedRecipesAreDisabledAndCannotEmitOperations()throws Exception {
        JSONObject anatomy=human(true),choices=options(anatomy,"actor_rig");
        for(String id:new String[]{"tailored_clothing","angular_face","soft_face","soft_shading","cel_hands"}){
            assertFalse(id,option(choices,id).getBoolean("available"));assertFalse(option(choices,id).getString("reason").isEmpty());
            assertThrows(IllegalArgumentException.class,()->operations(anatomy,id,"actor_rig"));
        }
        assertTrue(option(choices,"graphite_palette").getBoolean("available"));assertFalse(option(options(human(false),"actor_rig"),"graphite_palette").getBoolean("available"));
        JSONObject legacy=human(false);assertFalse(SketchDocument.object(legacy,"actor_rig").getJSONObject("human").getJSONObject("settings").has("clothingStyle"));
        assertTrue("Omitted legacy clothing remains a valid tailored candidate",option(options(legacy,"actor_rig"),"tailored_clothing").getBoolean("available"));
        JSONObject h=hand();SketchDocument.object(h,"hand_rig").getJSONObject("hand").getJSONObject("settings").remove("renderStyle");
        assertFalse("An omitted native smooth default is already smooth",option(options(h,"hand_rig"),"smooth_hands").getBoolean("available"));
        JSONArray neutral=operations(anatomy,"neutral_arms","actor_rig");JSONObject payload=neutral.getJSONObject(0).getJSONObject("patch").getJSONObject("human");
        SketchDocument.object(anatomy,"actor_rig").put("human",payload);
        assertFalse(option(options(anatomy,"actor_rig"),"neutral_arms").getBoolean("available"));
        assertThrows(IllegalArgumentException.class,()->operations(anatomy,"neutral_arms","actor_rig"));
        assertThrows(IllegalArgumentException.class,()->operations(anatomy,"unknown_recipe","actor_rig"));
        assertThrows(IllegalArgumentException.class,()->options(anatomy,"missing"));
        anatomy.getJSONArray("layers").getJSONObject(0).put("locked",true);JSONArray locked=options(anatomy,"actor_rig").getJSONArray("options");
        for(int i=0;i<locked.length();i++)assertFalse(locked.getJSONObject(i).getBoolean("available"));
        assertThrows(IllegalArgumentException.class,()->operations(anatomy,"long_hair","actor_rig"));
    }

    @Test public void everyBundledRecipeResolvesAndThenDisablesItsCompletedChange()throws Exception {
        for(String recipe:new String[]{"neutral_arms","tailored_clothing","angular_face","soft_face","soft_shading","long_hair","natural_blinks","graphite_palette","cel_hands","smooth_hands","anatomical_hand_detail","contour_hand_detail"}){
            boolean hand=recipe.endsWith("hands")||recipe.endsWith("hand_detail");String kind=hand?"hand":"human",id=hand?"hand_rig":"actor_rig";
            JSONObject doc=hand?hand():human(recipe.equals("graphite_palette"));
            JSONObject settings=SketchDocument.object(doc,id).getJSONObject(kind).getJSONObject("settings");
            if(recipe.equals("smooth_hands"))settings.put("renderStyle","cel");
            if(recipe.equals("anatomical_hand_detail"))settings.put("detail","contour");
            String source=SketchDocument.canonical(doc);assertTrue(recipe,option(options(doc,id),recipe).getBoolean("available"));
            JSONArray ops=operations(doc,recipe,id);assertEquals(1,ops.length());assertEquals("update_object",ops.getJSONObject(0).getString("type"));
            assertEquals(Set.of(kind),keys(ops.getJSONObject(0).getJSONObject("patch")));assertEquals(source,SketchDocument.canonical(doc));
            JSONObject candidate=SketchDocument.copy(doc);SketchDocument.object(candidate,id).put(kind,ops.getJSONObject(0).getJSONObject("patch").getJSONObject(kind));
            assertFalse(recipe,option(options(candidate,id),recipe).getBoolean("available"));
            assertThrows(IllegalArgumentException.class,()->operations(candidate,recipe,id));
        }
    }

    @Test public void blinkDefaultsUseNativePhysicalTimeWithoutReplacingKeysSpeechOrExistingTracks()throws Exception {
        JSONObject doc=human(false),payload=SketchDocument.object(doc,"actor_rig").getJSONObject("human"),expected=SketchDocument.copy(payload);
        JSONArray ops=operations(doc,"natural_blinks","actor_rig");JSONObject changed=ops.getJSONObject(0).getJSONObject("patch").getJSONObject("human");
        expected.put("blinkTrack",SketchHumanBlink.defaults());assertEquals(SketchDocument.canonical(expected),SketchDocument.canonical(changed));
        JSONObject candidate=SketchDocument.copy(doc);SketchEditOps.apply(candidate,ops.getJSONObject(0));SketchDocument.validate(candidate);
        assertEquals(0,SketchHumanAnimation.pose(SketchDocument.object(doc,"actor_rig"),51).getDouble("blink"),0);
        assertTrue(SketchHumanAnimation.pose(SketchDocument.object(candidate,"actor_rig"),51).getDouble("blink")>.9);
        payload.put("blinkTrack",SketchHumanBlink.defaults().put("intervalMs",5200));String before=SketchDocument.canonical(doc);
        assertFalse(option(options(doc,"actor_rig"),"natural_blinks").getBoolean("available"));assertThrows(IllegalArgumentException.class,()->operations(doc,"natural_blinks","actor_rig"));assertEquals(before,SketchDocument.canonical(doc));
    }

    @Test public void actualNativeUpdateOperationsChangeRenderedPixelsWithoutChangingCanvasClockOrSource()throws Exception {
        for(String example:new String[]{"human","hand","arms"}){
            String kind=example.equals("hand")?"hand":"human";
            JSONObject doc=kind.equals("human")?human(false):hand();String id=kind.equals("human")?"actor_rig":"hand_rig",recipe=example.equals("arms")?"neutral_arms":kind.equals("human")?"long_hair":"cel_hands";
            String before=SketchDocument.canonical(doc);JSONArray ops=operations(doc,recipe,id);JSONObject candidate=SketchDocument.copy(doc);SketchEditOps.apply(candidate,ops.getJSONObject(0));SketchDocument.validate(candidate);
            assertEquals(before,SketchDocument.canonical(doc));for(String clock:new String[]{"width","height","fps","frameCount"})assertEquals(doc.get(clock),candidate.get(clock));
            JSONObject original=SketchDocument.object(doc,id).getJSONObject(kind),changed=SketchDocument.object(candidate,id).getJSONObject(kind);
            for(String keep:new String[]{"poses","durationFrames","fps","speech","sourceFrameOffset","parts"})if(original.has(keep)&&!(example.equals("arms")&&keep.equals("poses")))assertEquals(keep,SketchDocument.canonical(original.get(keep)),SketchDocument.canonical(changed.get(keep)));
            for(String keep:new String[]{"frame","exposure","transform","keys"})assertEquals(keep,SketchDocument.canonical(SketchDocument.object(doc,id).get(keep)),SketchDocument.canonical(SketchDocument.object(candidate,id).get(keep)));
            try(SketchFrameRenderer a=new SketchFrameRenderer(context(),doc);SketchFrameRenderer b=new SketchFrameRenderer(context(),candidate)){
                Bitmap old=a.render(0),next=b.render(0);try{assertFalse(recipe+" changes actual native ink",old.sameAs(next));evidence(example,doc,candidate,ops,old,next);}finally{old.recycle();next.recycle();}
            }
        }
    }

    @Test public void rejectsUntrustedSchemaKeysKindsVersionsAndUnboundedCatalogues()throws Exception {
        helper();JSONObject library;try(InputStream in=context().getAssets().open(ASSET)){library=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        Method parse=helper().getDeclaredMethod("parse",byte[].class);parse.setAccessible(true);
        for(int variant=0;variant<6;variant++){
            JSONObject bad=SketchDocument.copy(library);JSONObject recipe=bad.getJSONArray("recipes").getJSONObject(0);
            switch(variant){case 0:bad.put("libraryVersion",2);break;case 1:recipe.put("kind","javascript");break;case 2:recipe.put("eval","run()");break;case 3:recipe.getJSONObject("posePatch").put("fps",60);break;case 4:for(int i=bad.getJSONArray("recipes").length();i<33;i++)bad.getJSONArray("recipes").put(SketchDocument.copy(recipe).put("id","extra_"+i));break;default:recipe.put("id",bad.getJSONArray("recipes").getJSONObject(1).getString("id"));}
            InvocationTargetException e=assertThrows(InvocationTargetException.class,()->parse.invoke(null,bad.toString().getBytes(StandardCharsets.UTF_8)));assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }
    private void evidence(String name,JSONObject before,JSONObject after,JSONArray ops,Bitmap a,Bitmap b)throws Exception {
        String root=System.getProperty("sketch.recipes.evidence");if(root==null)return;File dir=new File(root,name);assertTrue(dir.isDirectory()||dir.mkdirs());
        for(Object[] item:new Object[][]{{"before.json",before},{"after.json",after},{"operations.json",ops}})try(FileOutputStream out=new FileOutputStream(new File(dir,(String)item[0]))){out.write(item[1].toString().getBytes(StandardCharsets.UTF_8));}
        for(Object[] item:new Object[][]{{"before.png",a},{"after.png",b}})try(FileOutputStream out=new FileOutputStream(new File(dir,(String)item[0]))){assertTrue(((Bitmap)item[1]).compress(Bitmap.CompressFormat.PNG,100,out));}
    }
}
