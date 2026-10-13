package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Synthetic material markers for raster regressions, never authentic Test 1 annotations. */
final class SourceArtworkRasterFixture implements AutoCloseable {
    static final int W=256,H=448;
    static final int BLACK_MATERIAL=0xffa04020,WHITE_MATERIAL=0xffe3ebf4;
    static final int BLACK_SWORD=0xff2cf09b,WHITE_SWORD=0xff50dbea;
    static final int MARKER=0xffed2fdc;
    static final int WORLD_MARKER=0xffe428ed;
    final Context context;
    final ProjectStore.Asset from,to;
    final Rig2DProfile blackFrom,whiteFrom,blackTo,whiteTo;
    final int[] city;
    private final File firstFile,lastFile;

    SourceArtworkRasterFixture(Context context,boolean contacting) throws Exception {
        this(context,contacting,BLACK_MATERIAL);
    }
    SourceArtworkRasterFixture(Context context,boolean contacting,int secondBlackMaterial) throws Exception {
        this(context,contacting,secondBlackMaterial,null);
    }
    SourceArtworkRasterFixture(Context context,boolean contacting,int secondBlackMaterial,float[] whiteTipOverride) throws Exception {
        this.context=context;city=city();
        float[][] black=joints(true),white=joints(false);
        float[] blackTip={.61f,.54f};
        float[] whiteTip=whiteTipOverride!=null?whiteTipOverride.clone():contacting?blackTip.clone():new float[]{.99f,.08f};
        int[] artwork=city.clone();
        paint(artwork,outline(true),BLACK_MATERIAL);
        paint(artwork,outline(false),WHITE_MATERIAL);
        paint(artwork,swordOutline(black[Rig2DProfile.SWORD_HAND],blackTip),BLACK_SWORD);
        paint(artwork,swordOutline(white[Rig2DProfile.SWORD_HAND],whiteTip),WHITE_SWORD);
        // A small patch inside the first actor identifies material, independently of joint proxies.
        for(int y=173;y<186;y++)for(int x=96;x<109;x++)artwork[y*W+x]=MARKER;
        int[] second=artwork.clone();
        for(int index=0;index<second.length;index++)if(second[index]==BLACK_MATERIAL)second[index]=secondBlackMaterial;
        firstFile=write(context,artwork);lastFile=write(context,second);
        from=asset(firstFile);to=asset(lastFile);
        blackFrom=profile(from,true,black,blackTip);
        whiteFrom=profile(from,false,white,whiteTip);
        blackTo=profile(to,true,black,blackTip);
        whiteTo=profile(to,false,white,whiteTip);
    }

    NativeStoryboardArtworkRenderer renderer(StoryboardPoseMotion.Action action) throws Exception {
        return renderer(action,true);
    }
    NativeStoryboardArtworkRenderer renderer(StoryboardPoseMotion.Action action,boolean cameraEnabled) throws Exception {
        NativeSkinnedSprite2D b0=null,w0=null,b1=null,w1=null;
        try {
            b0=new NativeSkinnedSprite2D(context,from,blackFrom,W,H);
            w0=new NativeSkinnedSprite2D(context,from,whiteFrom,W,H);
            b1=new NativeSkinnedSprite2D(context,to,blackTo,W,H);
            w1=new NativeSkinnedSprite2D(context,to,whiteTo,W,H);
            return new NativeStoryboardArtworkRenderer(W,H,city,
                    blackFrom,whiteFrom,blackTo,whiteTo,b0,w0,b1,w1,
                    blackFrom.swordTip,whiteFrom.swordTip,blackTo.swordTip,whiteTo.swordTip,action,
                    W/(double)H,cameraEnabled,true);
        }catch(Exception|Error failure){
            if(b0!=null)b0.close();if(w0!=null)w0.close();
            if(b1!=null)b1.close();if(w1!=null)w1.close();throw failure;
        }
    }

    private static Rig2DProfile profile(ProjectStore.Asset asset,boolean black,float[][] joints,float[] tip) throws Exception {
        Rig2DProfile old=Rig2DProfile.create("fixture-project",asset.id,black,false,joints,outline(black),tip);
        JSONObject json=old.toJson();
        // Existing fromJson API accepts this input today. No new production API is needed for RED.
        json.put("swordHilt",pairs(new float[][]{joints[Rig2DProfile.SWORD_HAND]}).getJSONArray(0));
        json.put("swordOutline",pairs(swordOutline(joints[Rig2DProfile.SWORD_HAND],tip)));
        return Rig2DProfile.fromJson(json);
    }

    private static float[][] joints(boolean black){
        float[][] values=Rig2DProfileTest.joints();
        for(float[] point:values)point[0]=(black?.25f:.80f)+(point[0]-.42f)*(black?.8f:.6f);
        return values;
    }
    private static float[][] outline(boolean black){
        return black?new float[][]{{.07f,.16f},{.46f,.16f},{.46f,.96f},{.07f,.96f}}
                :new float[][]{{.64f,.16f},{.96f,.16f},{.96f,.96f},{.64f,.96f}};
    }
    private static float[][] swordOutline(float[] hilt,float[] tip){
        double dx=(tip[0]-hilt[0])*W,dy=(tip[1]-hilt[1])*H,length=Math.hypot(dx,dy);
        float nx=(float)(-dy/length*2.4/W),ny=(float)(dx/length*2.4/H);
        return new float[][]{{hilt[0]+nx,hilt[1]+ny},{tip[0]+nx,tip[1]+ny},
                {tip[0]-nx,tip[1]-ny},{hilt[0]-nx,hilt[1]-ny}};
    }
    private static JSONArray pairs(float[][] points) throws Exception {
        JSONArray out=new JSONArray();for(float[] point:points)out.put(new JSONArray().put(point[0]).put(point[1]));return out;
    }
    private static int[] city(){
        int[] colors=new int[W*H];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++)colors[y*W+x]=Color.rgb(20+x%31,100+y%27,160+x%41);
        for(int y=20;y<53;y++)for(int x=70;x<77;x++)colors[y*W+x]=WORLD_MARKER;
        return colors;
    }
    private static void paint(int[] image,float[][] polygon,int color){
        boolean[] included=StoryboardCleanPlate.mask(W,H,polygon);
        for(int index=0;index<image.length;index++)if(included[index])image[index]=color;
    }
    private static File write(Context context,int[] colors) throws Exception {
        File file=new File(context.getFilesDir(),"source_material_"+UUID.randomUUID()+".png");
        Bitmap image=Bitmap.createBitmap(colors,W,H,Bitmap.Config.ARGB_8888);
        try(FileOutputStream out=new FileOutputStream(file)){
            Assert.assertTrue("Synthetic source PNG must encode",image.compress(Bitmap.CompressFormat.PNG,100,out));
        }finally{image.recycle();}
        return file;
    }
    private static ProjectStore.Asset asset(File file){
        ProjectStore.Asset value=new ProjectStore.Asset();value.id=UUID.randomUUID().toString();
        value.uri=Uri.fromFile(file).toString();value.name=file.getName();value.mime="image/png";return value;
    }
    static float[][] copy(float[][] points){float[][] out=new float[points.length][];for(int i=0;i<points.length;i++)out[i]=points[i].clone();return out;}
    static void writeEvidence(String name,Bitmap... frames) throws Exception {
        File directory=new File("build/storyboard-qa/source-renderer");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("Cannot create native raster evidence directory");
        Bitmap sheet=Bitmap.createBitmap(W*frames.length,H,Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas=new Canvas(sheet);
            for(int index=0;index<frames.length;index++)canvas.drawBitmap(frames[index],index*W,0,null);
            try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){
                Assert.assertTrue("Native raster evidence must encode",sheet.compress(Bitmap.CompressFormat.PNG,100,out));
            }
            JSONObject metadata=new JSONObject().put("width",W).put("height",H).put("rasterCount",frames.length)
                    .put("scope","synthetic native Canvas raster regression; not Test 1 acting approval")
                    .put("test",name);
            try(FileOutputStream out=new FileOutputStream(new File(directory,name+".json"))){
                out.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }finally{sheet.recycle();}
    }
    static double[] centroid(Bitmap image,int sought,boolean actor){
        long count=0;double sumX=0,sumY=0;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
            int color=image.getPixel(x,y),r=Color.red(color),g=Color.green(color),b=Color.blue(color);
            boolean included=actor?r>80&&g<100&&b<120&&r>g*1.25:
                    sought==MARKER?r>180&&g<90&&b>150:
                    sought==WORLD_MARKER?y<70&&r>180&&g<80&&b>180:color==sought;
            if(Color.alpha(color)>200&&included){sumX+=x;sumY+=y;count++;}
        }
        Assert.assertTrue("The known source material must be visible in actual pixels",count>10);
        return new double[]{sumX/count,sumY/count};
    }
    @Override public void close(){firstFile.delete();lastFile.delete();}
}
