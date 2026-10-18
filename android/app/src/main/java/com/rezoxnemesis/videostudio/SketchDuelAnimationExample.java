package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Two independently articulated actors, three genuinely different prepared views and
 * written reaction/dialogue. Extends existing locally owned ORIGINAL editable vector
 * character exercise, not a neural inference of unseen perspective.
 *
 * Rook's rest geometry is deliberately re-authored as a mirrored, recoloured cutout
 * from the original prepared actor. This is a reproducible film-making training scene,
 * not a claim that a new film-quality character was drawn from a text prompt.
 */
final class SketchDuelAnimationExample {
    static final int FRAMES=100, FPS=24;
    private static final int WIDTH=1280, HEIGHT=720;
    private static final double WIDE_OFFSET=.05, MEDIUM_OFFSET=.19, POV_OFFSET=.14;
    private static final String BASE="sketch-animation/";
    private static final String BASE_SOURCE="rooftop-relay-source.json";
    private static final String BASE_PROGRAM="rooftop-relay-program.json";
    private SketchDuelAnimationExample(){}

    static JSONObject build(Context context)throws Exception{
        JSONObject source=read(context,BASE_SOURCE,SketchDocument.MAX_BYTES);
        JSONObject program=read(context,BASE_PROGRAM,SketchAnimationCompiler.MAX_PROGRAM_BYTES);
        source.put("id","rooftop_duel_prepared").put("name","Rooftop Standoff · Vale and Rook")
                .put("revision",1);
        program.put("name","Rooftop Standoff · two-character action with three views");
        // A two-human scene would otherwise duplicate thousands of near-identical
        // per-object affine keys. Bound interpolation error to 2 source pixels;
        // the native report records measured maximum error for artistic review.
        program.put("keyframeTolerancePixels",2.0);
        JSONArray original=source.getJSONArray("layers"),layers=new JSONArray();
        JSONArray oldRigs=program.getJSONArray("rigs"),rigs=new JSONArray();
        JSONObject wide=null,medium=null;
        for(int i=0;i<oldRigs.length();i++){
            JSONObject rig=oldRigs.getJSONObject(i);
            if("drone_rig".equals(rig.getString("id")))continue;
            if("wide_rig".equals(rig.getString("id")))wide=rig;
            if("medium_rig".equals(rig.getString("id")))medium=rig;
            rigs.put(rig);
        }
        if(wide==null||medium==null)throw SketchDocument.bad("The local character artwork foundation is missing");
        program.put("rigs",rigs);
        JSONObject wideArt=SketchDocument.layer(source,"wide_courier");
        JSONObject mediumArt=SketchDocument.layer(source,"medium_courier");
        JSONObject rookWide=mirroredLayer(wideArt,"rook_wide","Rook · coloured wide 3/4","rook_",0,36,WIDE_OFFSET,1,0,true);
        JSONObject rookPov=mirroredLayer(mediumArt,"rook_pov","Rook · independently prepared POV bust","rook_pov_",36,24,POV_OFFSET,.86,-.16,false);
        JSONObject rookMedium=mirroredLayer(mediumArt,"rook_medium","Rook · medium counter-angle","rook_",60,40,MEDIUM_OFFSET,1,0,true);
        // A recurring facial scar and gold band make the second character
        // consistently identifiable in all three artwork views.
        scar(rookWide,"rook_wide_scar","rook_wide_band",.662,.464,0,36);
        scar(rookPov,"rook_pov_scar","rook_pov_band",.650,.37,36,24);
        scar(rookMedium,"rook_medium_scar","rook_medium_band",.700,.625,60,40);

        for(int i=0;i<original.length();i++){
            JSONObject layer=original.getJSONObject(i);String id=layer.getString("id");
            if("drone".equals(id))continue; // no disguised second actor
            if("contact_shadows".equals(id)) {
                JSONArray shadows=new JSONArray(),objects=layer.getJSONArray("objects");
                for(int n=0;n<objects.length();n++){
                    JSONObject item=objects.getJSONObject(n);
                    if(!"drone_shadow".equals(item.getString("id")))shadows.put(item);
                }
                layer.put("objects",shadows);
                layer.getJSONArray("objects").put(SketchStoryFilmGenerator.oval("rook_shadow","#66121C2A",0,36,.56,.825,.23,.033));
            }
            if("drawn_action_effects".equals(id)) {
                JSONArray effects=new JSONArray(),old=layer.getJSONArray("objects");
                for(int n=0;n<old.length();n++){
                    JSONObject effect=old.getJSONObject(n);
                    if(!"incoming_bolt".equals(effect.getString("id"))&&!"bolt_core".equals(effect.getString("id")))
                        effects.put(effect);
                }
                layer.put("objects",effects);
            }
            if("cinema_frame".equals(id)){
                for(int n=0;n<layer.getJSONArray("objects").length();n++){
                    JSONObject object=layer.getJSONArray("objects").getJSONObject(n);
                    if("film_title".equals(object.getString("id")))object.put("text","ROOFTOP STANDOFF");
                    if("film_credit".equals(object.getString("id")))object.put("text","VALE  /  ROOK  ·  ORIGINAL VECTOR EXERCISE");
                }
            }
            layers.put(layer);
            if("wide_courier".equals(id))layers.put(rookWide);
            if("wide_courier".equals(id))layers.put(rookPov);
            if("medium_courier".equals(id))layers.put(rookMedium);
        }
        source.put("layers",layers);
        // Preserve the original deliberate camera selection: only the
        // environment layer is depth-transformed. Both human character layers
        // remain in the same staging/world view or their baton contact would
        // appear visually displaced even when rig-space joint checks pass.
        JSONArray cameraLayers=program.optJSONArray("cameraLayerIds");
        if(cameraLayers!=null){
            JSONArray next=new JSONArray();
            for(int i=0;i<cameraLayers.length();i++){
                String id=cameraLayers.getString(i);
                if(!"drone".equals(id))next.put(id);
            }
            program.put("cameraLayerIds",next);
        }
        rigs.put(mirrorRig(wide,"rook_wide_rig","rook_",WIDE_OFFSET,1,0,0));
        rigs.put(mirrorRig(medium,"rook_pov_rig","rook_pov_",POV_OFFSET,.86,-.16,1));
        rigs.put(mirrorRig(medium,"rook_medium_rig","rook_",MEDIUM_OFFSET,1,0,2));
        // The three independently prepared views use the same wine jacket,
        // gold scarf, hair/skin tones, and headband identifiers.
        addBoundFace(rigs,"rook_wide_rig","rook_wide_scar","rook_wide_band");
        addBoundFace(rigs,"rook_pov_rig","rook_pov_scar","rook_pov_band");
        addBoundFace(rigs,"rook_medium_rig","rook_medium_scar","rook_medium_band");

        // Dialogue is deliberately short enough to act in the small 4.17s scene.
        // Audio-driven lip sync remains a separate acceptance task.
        JSONObject dialogueLayer=SketchDocument.layer(source,"dialogue");
        dialogueLayer.getJSONArray("objects").put(
                SketchStoryFilmGenerator.rect("rook_speech_panel","#EE293347",62,16,.065,.70,.36,.165));
        dialogueLayer.getJSONArray("objects").put(
                label("rook_speaker","ROOK","#FFF1C994",62,16,.088,.719,.20,.040,.037));
        program.getJSONArray("dialogues").getJSONObject(0).put("start",79).put("end",99).put("text","Not today.");
        program.getJSONArray("dialogues").put(new JSONObject()
                .put("start",64).put("end",77).put("text","Yield.").put("layerId","dialogue")
                .put("bounds",rect(.078,.771,.342,.072))
                .put("color","#FFFFE3B0").put("fontSize",.047));
        JSONArray hidden=new JSONArray()
                .put("rook_medium_mouth").put("rook_mouth_open").put("rook_mouth_rounded");
        program.put("hiddenArtwork",hidden);
        JSONArray mouths=program.getJSONArray("mouthCels");
        mouths.put(range("rook_medium_mouth",60,64));
        mouths.put(range("rook_mouth_open",65,69));
        mouths.put(range("rook_mouth_rounded",70,73));
        mouths.put(range("rook_mouth_open",74,77));
        mouths.put(range("rook_medium_mouth",78,99));

        // The native compiler will enforce this two-actor contact against actual
        // prepared-space joint positions. A decorative spark alone cannot pass.
        program.put("interactionCues",new JSONArray().put(new JSONObject()
                .put("frame",16).put("rigA","wide_rig").put("jointA","wrist")
                .put("rigB","rook_wide_rig").put("jointB","wrist")
                .put("maxGapPx",80).put("description","Vale and Rook baton contact")));

        // OMEGA M3: bounded arm targeting at the existing authored wrist cue.
        // Native rig solver independently checks contact and planted feet.
        JSONObject contactCorrection=SketchAnimationContactDirector.refinePairedWristCue(
                source,program,16,"wide_rig","rook_wide_rig","wrist",6.0);
        program=contactCorrection.getJSONObject("program");
        program.getJSONArray("interactionCues").getJSONObject(0).put("maxGapPx",8.0);

        // M3.1 edits five pre-existing brace/recoil/settle poses, preserving
        // the exact frame-16 joint contact and all original source artwork.
        JSONObject impactResponse=SketchAnimationImpactDynamics.shape(source,program);
        program=impactResponse.getJSONObject("program");

        // M3.2: physically aim the EXISTING editable weapon vector paths at
        // each other. Wrist-only closeness is not a weapon collision.
        JSONObject weaponStage=SketchAnimationWeaponContact.aim(source,program);
        source=weaponStage.getJSONObject("source");

        // New native source artwork pass: rotate the gripped batons at their
        // painted hand-grip pivots, crossing the shafts inside both weapons.
        JSONObject combatStage=SketchAnimationCombatStaging.cross(source,program);
        source=combatStage.getJSONObject("source");

        // Impact rays/flash must show the actual interior vector intersection.
        JSONObject a=joint(SketchAnimationCompiler.sample(source,program,16),"wide_rig","wrist");
        JSONObject b=joint(SketchAnimationCompiler.sample(source,program,16),"rook_wide_rig","wrist");
        double ax=a.getDouble("x"),ay=a.getDouble("y"),bx=b.getDouble("x"),by=b.getDouble("y");
        double distance=Math.hypot((ax-bx)*WIDTH,(ay-by)*HEIGHT);
        if(distance>6.00001)throw SketchDocument.bad("Two-character authored wrist contact misses by "+Math.round(distance)+" physical pixels");
        double wx=(ax+bx)*.5,wy=(ay+by)*.5;
        JSONObject weaponReport=weaponStage.getJSONObject("report");
        JSONObject combatReport=combatStage.getJSONObject("report");
        double mx=combatReport.getDouble("impactX"),my=combatReport.getDouble("impactY");
        JSONObject effects=SketchDocument.layer(source,"drawn_action_effects");
        for(int n=0;n<effects.getJSONArray("objects").length();n++){
            JSONObject effect=effects.getJSONArray("objects").getJSONObject(n);
            String name=effect.getString("id");
            if(name.startsWith("impact_ray"))shift(effect,mx-.59,my-.608);
            if("impact_flash".equals(name)){
                JSONObject bb=effect.getJSONObject("bounds");
                bb.put("x",mx-bb.getDouble("width")*.5).put("y",my-bb.getDouble("height")*.5);
            }
        }
        SketchDocument.validate(source);
        // The native compiler enforces all screen/rig/IK/budget contracts. Never
        // claim source-only geometry is a correctly renderable motion sequence.
        JSONObject compiled=SketchAnimationCompiler.compile(source,program,"duel_validation_only");
        JSONObject baked=SketchAnimationCombatStaging.bakedCross(compiled.getJSONObject("document"),16);
        if(baked.getDouble("shaftCrossAngleDeg")<35 ||
                baked.getDouble("intersectionFractionA")<.33 ||
                baked.getDouble("intersectionFractionB")<.33 ||
                Math.hypot((baked.getDouble("impactX")-mx)*WIDTH,
                           (baked.getDouble("impactY")-my)*HEIGHT)>7)
            throw SketchDocument.bad("Compiled editable baton shafts lost the authored interior crossing");
        JSONObject report=compiled.getJSONObject("report");
        if(report.getInt("boundObjectCount")<180||report.getInt("animatedJointCount")<12)
            throw SketchDocument.bad("Duel lacks independently animated prepared character parts");
        if(!report.getBoolean("independentArticulation"))
            throw SketchDocument.bad("Duel animated only a global camera, not independent characters");
        return new JSONObject().put("source",source).put("program",program)
                .put("report",new JSONObject().put("schema","videostudio.duel-proof.v1")
                        .put("actors",new JSONArray().put("Vale").put("Rook"))
                        .put("preparedViews",new JSONArray().put("wide duel").put("foreground POV / Rook upper body").put("recovery counter-angle"))
                        .put("heroRig","wide_rig").put("rivalRig","rook_wide_rig")
                        .put("batonWristDistancePx",distance).put("batonContactFrame",16)
                        .put("contactDirector",contactCorrection.getJSONObject("report"))
                        .put("impactDynamics",impactResponse.getJSONObject("report"))
                        .put("weaponContact",weaponReport)
                        .put("combatStaging",combatReport.put("compiled",baked))
                        .put("contactMidpoint",new JSONArray().put(wx).put(wy))
                        .put("weaponContactPoint",new JSONArray().put(mx).put(my))
                        .put("frameCount",FRAMES).put("fps",FPS)
                        .put("independentArticulation",true)
                        .put("vectorDerivation","Mirrored and recoloured original prepared artist-cutout for second actor; not inferred unseen anatomy")
                        .put("audioRecorded",false).put("phoneRendered",false)
                        .put("professionalArtQualityVerified",false));
    }

    static JSONObject create(Context context)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())
            throw new IllegalStateException("Build and save action films on an IO worker");
        JSONObject staged=build(context);
        String id=UUID.randomUUID().toString().replace("-","");
        String sketch="duel_art_"+id,animation="duel_scene_"+id;
        JSONObject source=staged.getJSONObject("source"),program=staged.getJSONObject("program");
        source.put("id",sketch).put("revision",1);SketchDocument.validate(source);
        try(SketchProjectStore store=new SketchProjectStore(context)){
            store.importOwnedDocument(source,"duel_art_import_"+id);
        }
        return SketchAnimationOps.execute(context,"sketch_animation_save",new JSONObject()
                .put("animationId",animation).put("sketchId",sketch).put("sourceRevision",1)
                .put("expectedRevision",0).put("operationId","duel_scene_save_"+id)
                .put("program",program));
    }

    private static JSONObject read(Context context,String file,int maximum)throws Exception{
        try(InputStream input=context.getAssets().open(BASE+file)){
            byte[] raw=input.readNBytes(maximum+1);
            if(raw.length>maximum)throw SketchDocument.bad("Example exceeds prepared artwork budget");
            return new JSONObject(new String(raw,StandardCharsets.UTF_8));
        }
    }
    private static JSONObject blankLayer(String id,String name)throws Exception{
        return new JSONObject().put("id",id).put("name",name).put("visible",true)
                .put("locked",false).put("opacity",1).put("transform",SketchDocument.transform())
                .put("keys",new JSONArray()).put("objects",new JSONArray());
    }
    private static JSONObject mirroredLayer(JSONObject original,String id,String name,String prefix,int start,int span,
                                            double xOffset,double yScale,double yOffset,boolean includeMouth)throws Exception{
        JSONObject layer=blankLayer(id,name);
        JSONArray objects=original.getJSONArray("objects"),output=layer.getJSONArray("objects");
        for(int i=0;i<objects.length();i++){
            JSONObject item=objects.getJSONObject(i);
            if(!includeMouth&&item.getString("id").contains("mouth"))continue;
            JSONObject clone=SketchDocument.copy(item);
            clone.put("id",prefix+item.getString("id")).put("frame",start).put("exposure",span)
                    .put("keys",new JSONArray());
            recolour(clone);
            transformGeometry(clone,xOffset,yScale,yOffset);
            output.put(clone);
        }
        return layer;
    }
    private static void recolour(JSONObject object)throws Exception{
        object.put("color",palette(object.getString("color")));
        JSONObject gradient=object.optJSONObject("gradient");
        if(gradient!=null){
            gradient.put("startColor",palette(gradient.getString("startColor")));
            gradient.put("endColor",palette(gradient.getString("endColor")));
        }
    }
    private static String palette(String input){
        switch(input.toUpperCase(Locale.ROOT)){
            case "#FF248F99":return "#FF924A67";  // wine/dark magenta jacket
            case "#FF12606F":return "#FF532840";
            case "#FF63D7CF":return "#FFE3A99C";
            case "#FF263950":return "#FF272D45";
            case "#FF172A41":return "#FF1F2437";
            case "#FF334D66":return "#FF343751";
            case "#FF567082":return "#FF6D6680";
            case "#FFF26455":return "#FFDFA13D";  // golden scarf
            case "#FFFFAC7E":return "#FFF8D789";
            case "#FFE9AE82":return "#FFC89478";
            case "#FFC27E65":return "#FF98694F";
            case "#FFC17C65":return "#FFB07760";
            case "#FF192A3C":return "#FF34283A";
            case "#FF426777":return "#FF956979";
            case "#FFB5FFE1":return "#FFFFD7AC";
            default:return input;
        }
    }
    private static void transformGeometry(JSONObject object,double xOffset,double yScale,double yOffset)throws Exception{
        JSONObject bounds=object.optJSONObject("bounds");
        if(bounds!=null){
            bounds.put("x",mapX(bounds.getDouble("x")+bounds.getDouble("width"),xOffset))
                    .put("y",mapY(bounds.getDouble("y"),yScale,yOffset))
                    .put("height",bounds.getDouble("height")*yScale);
        }
        JSONArray points=object.optJSONArray("points");
        if(points!=null)for(int i=0;i<points.length();i++){
            JSONArray p=points.getJSONArray(i);
            p.put(0,mapX(p.getDouble(0),xOffset)).put(1,mapY(p.getDouble(1),yScale,yOffset));
        }
        JSONArray commands=object.optJSONArray("commands");
        if(commands!=null)for(int i=0;i<commands.length();i++){
            JSONArray command=commands.getJSONArray(i);
            for(int j=1;j<command.length();j+=2){
                command.put(j,mapX(command.getDouble(j),xOffset))
                        .put(j+1,mapY(command.getDouble(j+1),yScale,yOffset));
            }
        }
        JSONObject gradient=object.optJSONObject("gradient");
        if(gradient!=null){
            gradient.put("x0",mapX(gradient.getDouble("x0"),0));
            gradient.put("x1",mapX(gradient.getDouble("x1"),0));
        }
        JSONObject transform=object.getJSONObject("transform");
        transform.put("x",-transform.getDouble("x")).put("y",transform.getDouble("y")*yScale)
                .put("anchorX",mapX(transform.getDouble("anchorX"),xOffset))
                .put("anchorY",mapY(transform.getDouble("anchorY"),yScale,yOffset))
                .put("rotation",-transform.getDouble("rotation"));
    }
    private static double mapX(double x,double offset){
        double value=1-x+offset;
        if(value<0||value>1)throw SketchDocument.bad("Mirrored actor leaves X canvas: "+value);
        return value;
    }
    private static double mapY(double y,double scale,double offset){
        double value=y*scale+offset;
        if(value<0||value>1)throw SketchDocument.bad("Prepared actor leaves Y canvas: "+value);
        return value;
    }
    private static JSONObject mirrorRig(JSONObject original,String id,String prefix,double xOffset,double yScale,double yOffset,int kind)throws Exception{
        JSONObject out=SketchDocument.copy(original).put("id",id);
        JSONArray joints=out.getJSONArray("joints");
        for(int i=0;i<joints.length();i++){
            JSONObject j=joints.getJSONObject(i);
            j.put("x",mapX(j.getDouble("x"),xOffset))
                    .put("y",mapY(j.getDouble("y"),yScale,yOffset));
        }
        JSONArray bindings=out.getJSONArray("bindings"),preparedBindings=new JSONArray();
        for(int i=0;i<bindings.length();i++){
            JSONObject binding=bindings.getJSONObject(i);
            if(kind==1&&binding.getString("objectId").contains("mouth"))continue;
            binding.put("objectId",prefix+binding.getString("objectId"));
            preparedBindings.put(binding);
        }
        out.put("bindings",preparedBindings);
        if(kind==1){ // independently drawn foreground POV reaction, not the wide picture magnified
            out.put("ik",new JSONArray());
            JSONArray poses=new JSONArray();
            int[] times={36,42,48,54,59};
            for(int i=0;i<times.length;i++){
                JSONObject angles=new JSONObject().put("chest",new int[]{-5,8,23,-12,-3}[i])
                        .put("head",new int[]{3,-5,-13,8,2}[i])
                        .put("front_upper",new int[]{-20,-42,17,46,8}[i])
                        .put("front_fore",new int[]{9,30,-35,-16,3}[i])
                        .put("back_upper",new int[]{-15,-7,22,10,0}[i])
                        .put("scarf",new int[]{9,23,-27,14,2}[i]);
                poses.put(new JSONObject().put("frame",times[i]).put("interpolation","smooth")
                        .put("rootX",new double[]{0,-.01,.008,.015,0}[i])
                        .put("rootY",new double[]{0,.006,-.012,.014,0}[i]).put("rotations",angles));
            }
            out.put("poses",poses);
        }else{
            JSONArray poses=out.getJSONArray("poses");
            for(int i=0;i<poses.length();i++){
                JSONObject pose=poses.getJSONObject(i),rotations=pose.optJSONObject("rotations");
                if(rotations!=null){
                    java.util.Iterator<String> fields=rotations.keys();
                    while(fields.hasNext()){
                        String key=fields.next();
                        double angle=rotations.getDouble(key);
                        // The second actor does not simply mirror animation timing:
                        // in the wide shot Rook drives the contact; afterwards she recovers.
                        if(kind==0&&("front_upper".equals(key)||"front_fore".equals(key)))angle*=1.15;
                        if(kind==2&&"chest".equals(key)&&pose.getInt("frame")==72)angle=21;
                        if(kind==2&&"head".equals(key)&&pose.getInt("frame")==72)angle=-13;
                        rotations.put(key,-angle);
                    }
                }
                pose.put("rootX",0);
                if(kind==0)pose.put("rootY",Math.min(.008,pose.optDouble("rootY",0)));
            }
            JSONArray ik=out.optJSONArray("ik");
            if(ik!=null)for(int i=0;i<ik.length();i++){
                JSONObject c=ik.getJSONObject(i);
                c.put("x",mapX(c.getDouble("x"),xOffset))
                        .put("y",mapY(c.getDouble("y"),yScale,yOffset))
                        .put("bend",-c.optInt("bend",1));
                if(c.has("rotation"))c.put("rotation",-c.getDouble("rotation"));
            }
        }
        return out;
    }
    private static void addBoundFace(JSONArray rigs,String rigId,String scarId,String bandId)throws Exception{
        for(int i=0;i<rigs.length();i++){
            JSONObject rig=rigs.getJSONObject(i);
            if(rigId.equals(rig.getString("id"))){
                rig.getJSONArray("bindings").put(new JSONObject().put("objectId",scarId).put("jointId","head"));
                rig.getJSONArray("bindings").put(new JSONObject().put("objectId",bandId).put("jointId","head"));
                return;
            }
        }
        throw SketchDocument.bad("New actor rig not found");
    }
    private static void scar(JSONObject layer,String scarId,String bandId,double x,double y,int frame,int duration)throws Exception{
        layer.getJSONArray("objects").put(SketchStoryFilmGenerator.line(scarId,"#FF563043",frame,duration,x,y,x+.012,y+.028,.0038));
        layer.getJSONArray("objects").put(SketchStoryFilmGenerator.line(bandId,"#FFFFCD82",frame,duration,x-.022,y-.045,x+.038,y-.051,.006));
    }
    private static JSONObject rect(double x,double y,double w,double h)throws Exception{
        return new JSONObject().put("x",x).put("y",y).put("width",w).put("height",h);
    }
    private static JSONObject range(String id,int start,int end)throws Exception{
        return new JSONObject().put("objectId",id).put("start",start).put("end",end);
    }
    private static JSONObject label(String id,String caption,String ink,int frame,int duration,
                                    double x,double y,double w,double h,double font)throws Exception{
        return new JSONObject().put("id",id).put("kind","text").put("frame",frame).put("exposure",duration)
                .put("color",ink).put("width",.001).put("fill",true)
                .put("bounds",rect(x,y,w,h)).put("transform",SketchDocument.transform())
                .put("keys",new JSONArray()).put("text",caption).put("fontFamily","sans-serif")
                .put("fontStyle","bold").put("fontSize",font).put("textAlign","left");
    }
    private static JSONObject joint(JSONObject sample,String rigId,String name)throws Exception{
        JSONArray positions=sample.getJSONArray("joints");
        for(int i=0;i<positions.length();i++){
            JSONObject j=positions.getJSONObject(i);
            if(rigId.equals(j.getString("rigId"))&&name.equals(j.getString("jointId")))return j;
        }
        throw SketchDocument.bad("Expected distinct human joint "+rigId+":"+name);
    }
    private static void shift(JSONObject item,double dx,double dy)throws Exception{
        JSONArray commands=item.optJSONArray("commands");
        if(commands!=null)for(int i=0;i<commands.length();i++){
            JSONArray cmd=commands.getJSONArray(i);
            for(int j=1;j<cmd.length();j+=2){
                double x=cmd.getDouble(j)+dx,y=cmd.getDouble(j+1)+dy;
                if(x<0||x>1||y<0||y>1)throw SketchDocument.bad("Impact animation exceeds canvas");
                cmd.put(j,x).put(j+1,y);
            }
        }
    }
}
