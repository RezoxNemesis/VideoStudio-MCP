package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.util.Random;
import java.util.Arrays;

/**
 * Paints the ACTUAL illustrated fighters into a locked, source-derived city.
 * Every output frame uses continuous 2D masked mesh deformations of their
 * own image textures, never procedural replacement stickmen or image zooms.
 *
 * Supports short paired, authored-keyframe action shots. A large camera jump
 * must be split into another shot; 2D meshes cannot reveal unseen body sides.
 */
public final class NativeStoryboardArtworkRenderer implements AutoCloseable {
    private final int width,height;
    private final Bitmap background;
    private final Rig2DProfile blackStart,blackEnd,whiteStart,whiteEnd;
    private final NativeSkinnedSprite2D blackTextureStart,blackTextureEnd;
    private final NativeSkinnedSprite2D whiteTextureStart,whiteTextureEnd;
    private final float[] blackTipStart,blackTipEnd,whiteTipStart,whiteTipEnd;
    private final StoryboardPoseMotion.Action action;
    private final double sourceAspect;
    private final StoryboardContactPlan contacts;
    private final StoryboardChoreography.Beat beat;
    private final boolean cameraEnabled,sourcePropsEnabled;
    private Bitmap appearanceFirst,appearanceLast;
    private final int[] rowFirst,rowLast;
    private int frameRate=24;
    private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG
            |Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);

    public NativeStoryboardArtworkRenderer(int width,int height,int[] city,
            Rig2DProfile blackStart,Rig2DProfile whiteStart,
            Rig2DProfile blackEnd,Rig2DProfile whiteEnd,
            NativeSkinnedSprite2D blackTextureStart,
            NativeSkinnedSprite2D whiteTextureStart,
            NativeSkinnedSprite2D blackTextureEnd,
            NativeSkinnedSprite2D whiteTextureEnd,
            float[] blackTipStart,float[] whiteTipStart,
            float[] blackTipEnd,float[] whiteTipEnd,
            StoryboardPoseMotion.Action action) {
        this(width,height,city,blackStart,whiteStart,blackEnd,whiteEnd,
                blackTextureStart,whiteTextureStart,blackTextureEnd,whiteTextureEnd,
                blackTipStart,whiteTipStart,blackTipEnd,whiteTipEnd,action,
                width/(double)height,true,true);
    }

    public NativeStoryboardArtworkRenderer(int width,int height,int[] city,
            Rig2DProfile blackStart,Rig2DProfile whiteStart,
            Rig2DProfile blackEnd,Rig2DProfile whiteEnd,
            NativeSkinnedSprite2D blackTextureStart,NativeSkinnedSprite2D whiteTextureStart,
            NativeSkinnedSprite2D blackTextureEnd,NativeSkinnedSprite2D whiteTextureEnd,
            float[] blackTipStart,float[] whiteTipStart,float[] blackTipEnd,float[] whiteTipEnd,
            StoryboardPoseMotion.Action action,double sourceAspect,
            boolean cameraEnabled,boolean requireSourceProps) {
        this(width,height,city,blackStart,whiteStart,blackEnd,whiteEnd,
                blackTextureStart,whiteTextureStart,blackTextureEnd,whiteTextureEnd,
                blackTipStart,whiteTipStart,blackTipEnd,whiteTipEnd,action,sourceAspect,null,null,cameraEnabled,requireSourceProps);
    }
    public NativeStoryboardArtworkRenderer(int width,int height,int[] city,
            Rig2DProfile blackStart,Rig2DProfile whiteStart,Rig2DProfile blackEnd,Rig2DProfile whiteEnd,
            NativeSkinnedSprite2D blackTextureStart,NativeSkinnedSprite2D whiteTextureStart,
            NativeSkinnedSprite2D blackTextureEnd,NativeSkinnedSprite2D whiteTextureEnd,
            float[] blackTipStart,float[] whiteTipStart,float[] blackTipEnd,float[] whiteTipEnd,
            StoryboardPoseMotion.Action action,double sourceAspect,StoryboardContactPlan contacts,
            boolean cameraEnabled,boolean requireSourceProps) {
        this(width,height,city,blackStart,whiteStart,blackEnd,whiteEnd,
                blackTextureStart,whiteTextureStart,blackTextureEnd,whiteTextureEnd,
                blackTipStart,whiteTipStart,blackTipEnd,whiteTipEnd,action,sourceAspect,contacts,null,cameraEnabled,requireSourceProps);
    }
    public NativeStoryboardArtworkRenderer(int width,int height,int[] city,
            Rig2DProfile blackStart,Rig2DProfile whiteStart,Rig2DProfile blackEnd,Rig2DProfile whiteEnd,
            NativeSkinnedSprite2D blackTextureStart,NativeSkinnedSprite2D whiteTextureStart,
            NativeSkinnedSprite2D blackTextureEnd,NativeSkinnedSprite2D whiteTextureEnd,
            float[] blackTipStart,float[] whiteTipStart,float[] blackTipEnd,float[] whiteTipEnd,
            StoryboardChoreography.Beat beat,double sourceAspect,StoryboardContactPlan contacts,
            boolean cameraEnabled,boolean requireSourceProps) {
        this(width,height,city,blackStart,whiteStart,blackEnd,whiteEnd,
                blackTextureStart,whiteTextureStart,blackTextureEnd,whiteTextureEnd,
                blackTipStart,whiteTipStart,blackTipEnd,whiteTipEnd,
                StoryboardChoreography.actionFor(beat,true),sourceAspect,contacts,beat,cameraEnabled,requireSourceProps);
    }
    private NativeStoryboardArtworkRenderer(int width,int height,int[] city,
            Rig2DProfile blackStart,Rig2DProfile whiteStart,Rig2DProfile blackEnd,Rig2DProfile whiteEnd,
            NativeSkinnedSprite2D blackTextureStart,NativeSkinnedSprite2D whiteTextureStart,
            NativeSkinnedSprite2D blackTextureEnd,NativeSkinnedSprite2D whiteTextureEnd,
            float[] blackTipStart,float[] whiteTipStart,float[] blackTipEnd,float[] whiteTipEnd,
            StoryboardPoseMotion.Action action,double sourceAspect,StoryboardContactPlan contacts,
            StoryboardChoreography.Beat beat,boolean cameraEnabled,boolean requireSourceProps) {
        if(width<256||height<256||width>1280||height>1280||(long)width*height>720L*1280L||
                city==null||city.length!=width*height)
            throw new IllegalArgumentException("Source artwork needs bounded dimensions with at most 720×1280 pixels");
        if(blackStart==null||blackEnd==null||whiteStart==null||whiteEnd==null
                ||blackTextureStart==null||blackTextureEnd==null
                ||whiteTextureStart==null||whiteTextureEnd==null||action==null)
            throw new IllegalArgumentException("Two authentic traced frames per character are mandatory");
        if(!Double.isFinite(sourceAspect)||Math.abs(sourceAspect-width/(double)height)>.000001
                ||Math.abs(blackTextureStart.sourceAspect()-sourceAspect)>.000001
                ||Math.abs(whiteTextureStart.sourceAspect()-sourceAspect)>.000001
                ||Math.abs(blackTextureEnd.sourceAspect()-sourceAspect)>.000001
                ||Math.abs(whiteTextureEnd.sourceAspect()-sourceAspect)>.000001)
            throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: source actors and world need one oriented aspect or explicit registration");
        if(contacts!=null){
            if(Math.abs(contacts.sourceAspect()-sourceAspect)>.000001)
                throw new IllegalArgumentException("Authored contacts use a different source aspect");
            try{contacts.requireRigBindings(blackStart,whiteStart,blackEnd,whiteEnd);}
            catch(Exception invalid){throw new IllegalArgumentException(invalid.getMessage(),invalid);}
        }
        if(beat!=null&&contacts==null)throw new IllegalArgumentException("Authored choreography requires an explicit contact plan");
        this.sourceAspect=sourceAspect;this.contacts=contacts;this.beat=beat;
        sourcePropsEnabled=blackTextureStart.hasSourceProp()&&whiteTextureStart.hasSourceProp()
                &&blackTextureEnd.hasSourceProp()&&whiteTextureEnd.hasSourceProp();
        if(requireSourceProps&&!sourcePropsEnabled)
            throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: both original swords need polygons, hilts and tips in both source anchors");
        if(sourcePropsEnabled){
            requireAuthoredTip(blackStart,blackTipStart);requireAuthoredTip(whiteStart,whiteTipStart);
            requireAuthoredTip(blackEnd,blackTipEnd);requireAuthoredTip(whiteEnd,whiteTipEnd);
        }
        this.cameraEnabled=cameraEnabled;
        rowFirst=new int[width];rowLast=new int[width];
        this.width=width;this.height=height;
        this.blackStart=freeze(blackStart);this.blackEnd=freeze(blackEnd);
        this.whiteStart=freeze(whiteStart);this.whiteEnd=freeze(whiteEnd);
        this.blackTextureStart=blackTextureStart;
        this.blackTextureEnd=blackTextureEnd;
        this.whiteTextureStart=whiteTextureStart;
        this.whiteTextureEnd=whiteTextureEnd;
        blackTextureStart.excludeSourceProps(Arrays.asList(this.blackStart.swordOutline,this.whiteStart.swordOutline));
        whiteTextureStart.excludeSourceProps(Arrays.asList(this.blackStart.swordOutline,this.whiteStart.swordOutline));
        blackTextureEnd.excludeSourceProps(Arrays.asList(this.blackEnd.swordOutline,this.whiteEnd.swordOutline));
        whiteTextureEnd.excludeSourceProps(Arrays.asList(this.blackEnd.swordOutline,this.whiteEnd.swordOutline));
        this.blackTipStart=blackTipStart.clone();
        this.blackTipEnd=blackTipEnd.clone();
        this.whiteTipStart=whiteTipStart.clone();
        this.whiteTipEnd=whiteTipEnd.clone();
        this.action=action;
        background=Bitmap.createBitmap(city,width,height,Bitmap.Config.ARGB_8888);
    }
    private static void requireAuthoredTip(Rig2DProfile profile,float[] tip){
        if(tip==null||tip.length!=2||profile.swordTip==null
                ||tip[0]!=profile.swordTip[0]||tip[1]!=profile.swordTip[1])
            throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: rendered source sword endpoints must match their frozen authored profiles");
    }
    private static Rig2DProfile freeze(Rig2DProfile profile){
        try{return Rig2DProfile.fromJson(profile.toJson());}
        catch(Exception invalid){throw new IllegalArgumentException("Cannot freeze source actor annotations",invalid);}
    }
    public void setFrameRate(int fps){frameRate=NativeFrameClock.requireFps(fps);}
    /** Pure admission check of only the source appearances that this exact output frame actually draws. */
    public static void requireRenderableFrame(Rig2DProfile blackFrom,Rig2DProfile whiteFrom,
            Rig2DProfile blackTo,Rig2DProfile whiteTo,StoryboardPoseMotion.Frame frame,
            double blackAppearancePhase,double whiteAppearancePhase,int width,int height,boolean requireSourceProps){
        if(frame==null||!Double.isFinite(blackAppearancePhase)||!Double.isFinite(whiteAppearancePhase)
                ||blackAppearancePhase<0||blackAppearancePhase>1||whiteAppearancePhase<0||whiteAppearancePhase>1)
            throw new IllegalArgumentException("Finite sampled native frame phases are required");
        Rig2DProfile[] sources={blackFrom,whiteFrom,blackTo,whiteTo};boolean props=true;
        for(Rig2DProfile source:sources){
            if(source==null)throw new IllegalArgumentException("Four source actor appearances are required");
            boolean present=source.swordTip!=null&&source.swordHilt!=null&&source.swordOutline.length>=3;
            if(present)NativeSourceProp2D.requireSourceGeometry(source,width,height);
            props&=present;
        }
        if(requireSourceProps&&!props)throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: both original source weapons must be complete");
        requireActorFrame(blackFrom,blackTo,frame.black,frame.blackBladeTip,blackAppearancePhase,width,height,props);
        requireActorFrame(whiteFrom,whiteTo,frame.white,frame.whiteBladeTip,whiteAppearancePhase,width,height,props);
    }
    private static void requireActorFrame(Rig2DProfile from,Rig2DProfile to,float[][] pose,float[] tip,
                                         double phase,int width,int height,boolean props){
        if(phase<.535)NativeSkinnedSprite2D.requireRenderablePose(from,pose,width,height);
        if(phase>.465)NativeSkinnedSprite2D.requireRenderablePose(to,pose,width,height);
        if(props)NativeSourceProp2D.requirePosedGeometry(pose[Rig2DProfile.SWORD_HAND],tip,width,height);
    }
    private boolean contactVisualsAuthored(){
        return sourcePropsEnabled&&(beat==null?(action==StoryboardPoseMotion.Action.IMPACT||action==StoryboardPoseMotion.Action.PARRY)
                :(beat==StoryboardChoreography.Beat.CLASH||beat==StoryboardChoreography.Beat.BLADE_LOCK));
    }

    /** Authored endpoints have exactly the reference motion landmarks. */
    public Bitmap render(double progress,int frameNo,int frameCount) {
        StoryboardPoseMotion.Frame motion=sampleMotion(progress);
        Bitmap out=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        boolean returned=false;
        try{
        Canvas canvas=new Canvas(out);
        brush.reset();brush.setAntiAlias(true);brush.setFilterBitmap(true);
        canvas.save();
        // Controlled contact impulse, never a global Ken Burns camera drift.
        if(cameraEnabled&&contactVisualsAuthored()&&motion.contact>.1) {
            float impulse=(float)(.006*motion.contact*Math.sin(frameNo*43.2/frameRate));
            canvas.translate(impulse*width,impulse*height*.5f);
        }
        // The same camera transform applies once to world, actors and original props.
        canvas.drawBitmap(background,0,0,brush);
        drawFootShadow(canvas,motion.white);
        drawFootShadow(canvas,motion.black);
        // White is the distant actor in this particular screenplay shot.
        // Only the precise traced sprite is composited (the source city is
        // NEVER embedded in a foreground bitmap).
        actor(canvas,whiteTextureStart,whiteTextureEnd,motion.white,motion.whiteBladeTip,
                beat==null?progress:StoryboardChoreography.phase(beat,false,progress));
        actor(canvas,blackTextureStart,blackTextureEnd,motion.black,motion.blackBladeTip,
                beat==null?progress:StoryboardChoreography.phase(beat,true,progress));
        if(contactVisualsAuthored()&&motion.contact>.05)impact(canvas,motion,frameNo,frameCount);
        canvas.restore();
        returned=true;
        return out;
        }finally{if(!returned)out.recycle();}
    }

    /** Solver predictions for independently labelled QA; actual pixels come from render(). */
    public StoryboardPoseMotion.Frame sampleMotion(double progress){
        if(beat!=null)return StoryboardPoseMotion.between(blackStart,whiteStart,blackEnd,whiteEnd,
                blackTipStart,whiteTipStart,blackTipEnd,whiteTipEnd,progress,beat,contacts);
        if(contacts!=null)return StoryboardPoseMotion.between(blackStart,whiteStart,blackEnd,whiteEnd,
                blackTipStart,whiteTipStart,blackTipEnd,whiteTipEnd,progress,action,contacts);
        return StoryboardPoseMotion.between(blackStart,whiteStart,blackEnd,whiteEnd,
                blackTipStart,whiteTipStart,blackTipEnd,whiteTipEnd,progress,action,sourceAspect);
    }

    private void actor(Canvas canvas,NativeSkinnedSprite2D first,
                       NativeSkinnedSprite2D last,float[][] pose,float[] tip,double t) {
        // Masked artwork is aligned to the SAME interpolated landmarks.
        // A short local appearance handoff avoids a hard texture pop without
        // ghosting two whole frames/cities over one another.
        if(t<=.465)drawAppearance(canvas,first,pose,tip);
        else if(t>=.535)drawAppearance(canvas,last,pose,tip);
        else{
            float u=(float)((t-.465)/.07);
            float smooth=u*u*(3-2*u);
            ensureAppearanceBuffers();
            appearanceFirst.eraseColor(Color.TRANSPARENT);appearanceLast.eraseColor(Color.TRANSPARENT);
            drawAppearance(new Canvas(appearanceFirst),first,pose,tip);
            drawAppearance(new Canvas(appearanceLast),last,pose,tip);
            // Weighted premultiplied appearances form ONE source layer. Sequential
            // source-over draws would expose the city beneath otherwise opaque material.
            for(int y=0;y<height;y++){
                appearanceFirst.getPixels(rowFirst,0,width,0,y,width,1);
                appearanceLast.getPixels(rowLast,0,width,0,y,width,1);
                for(int x=0;x<width;x++)rowFirst[x]=mixPremultiplied(rowFirst[x],rowLast[x],smooth);
                appearanceFirst.setPixels(rowFirst,0,width,0,y,width,1);
            }
            brush.reset();brush.setFilterBitmap(true);canvas.drawBitmap(appearanceFirst,0,0,brush);
        }
    }

    private void drawAppearance(Canvas canvas,NativeSkinnedSprite2D sprite,float[][] pose,float[] tip){
        sprite.render(canvas,pose,255);
        if(sourcePropsEnabled)sprite.renderSourceProp(canvas,pose[Rig2DProfile.SWORD_HAND],tip,255);
    }
    private void ensureAppearanceBuffers(){
        if(appearanceFirst==null)appearanceFirst=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        if(appearanceLast==null)appearanceLast=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
    }
    private static int mixPremultiplied(int first,int last,float amount){
        double a=Color.alpha(first)*(1-amount),b=Color.alpha(last)*amount,total=a+b;
        int alpha=(int)Math.round(total);if(alpha==0)return Color.TRANSPARENT;
        return Color.argb(alpha,(int)Math.round((Color.red(first)*a+Color.red(last)*b)/total),
                (int)Math.round((Color.green(first)*a+Color.green(last)*b)/total),
                (int)Math.round((Color.blue(first)*a+Color.blue(last)*b)/total));
    }

    private void drawFootShadow(Canvas canvas,float[][] pose) {
        float a=(pose[12][0]+pose[15][0])*.5f;
        float b=Math.max(pose[12][1],pose[15][1]);
        brush.reset();brush.setAntiAlias(true);brush.setColor(0x65030912);
        float r=width*.056f;
        canvas.drawOval(a*width-r,b*height-r*.10f,
                a*width+r,b*height+r*.10f,brush);
    }

    private void impact(Canvas canvas,StoryboardPoseMotion.Frame frame,
                        int frameNo,int frames) {
        if(frame.bladeWitness==null)return;
        float centerX=(float)((frame.bladeWitness.pointA.x+frame.bladeWitness.pointB.x)*.5/sourceAspect*width);
        float centerY=(float)((frame.bladeWitness.pointA.y+frame.bladeWitness.pointB.y)*.5*height);
        double energy=frame.contact;
        Random random=new Random(194038); // stable particles, never flashing random confetti
        brush.reset();brush.setAntiAlias(true);
        brush.setStrokeCap(Paint.Cap.ROUND);
        float elapsed=(float)frame.progress;
        for(int i=0;i<20;i++){
            float angle=(float)(Math.PI*2*i/20d);
            float spread=(float)(width*energy*
                    (.008+random.nextDouble()*.040));
            float x=centerX+(float)Math.cos(angle)*spread;
            float y=centerY+(float)Math.sin(angle)*spread;
            brush.setColor(i%4==0?0xfff7e6bf:0xfff8a861);
            brush.setStrokeWidth(i%3==0?2.4f:1.35f);
            canvas.drawLine(centerX,centerY,x,y,brush);
        }
        brush.setColor(0xfffaeac8);
        canvas.drawCircle(centerX,centerY,(float)(width*.009*energy),brush);
    }

    public Bitmap backgroundForQa(){
        return background.copy(Bitmap.Config.ARGB_8888,false);
    }
    public org.json.JSONObject sourceCoverageEvidence()throws Exception{
        return new org.json.JSONObject().put("origin","UNAVAILABLE")
                .put("reason","Hidden cloth, anatomy and occluded blade surfaces are not synthesized or independently measured")
                .put("excludedOpaqueSourcePixels",blackTextureStart.excludedOpaqueSourcePixels()+whiteTextureStart.excludedOpaqueSourcePixels()
                        +blackTextureEnd.excludedOpaqueSourcePixels()+whiteTextureEnd.excludedOpaqueSourcePixels())
                .put("hiddenMaterialSynthesized",false).put("dynamicLimbDepthInferred",false);
    }

    @Override public void close(){
        if(!background.isRecycled())background.recycle();
        if(appearanceFirst!=null&&!appearanceFirst.isRecycled())appearanceFirst.recycle();
        if(appearanceLast!=null&&!appearanceLast.isRecycled())appearanceLast.recycle();
        blackTextureStart.close();whiteTextureStart.close();
        blackTextureEnd.close();whiteTextureEnd.close();
    }
}
