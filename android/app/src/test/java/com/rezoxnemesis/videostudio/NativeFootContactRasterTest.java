package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
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
import static org.junit.Assert.*;

/** Source material pixels, independently measured from the contact solver's coordinate trace. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativeFootContactRasterTest {
    private static final int FOOT_MATERIAL=0xfffdfb15;
    @Test public void actualPlantedFootMaterialStaysWithinFivePercentOfLegLength()throws Exception{
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),false)){
            float[][] end=SourceArtworkRasterFixture.copy(fixture.blackTo.joints);
            for(int i=0;i<end.length;i++){
                if(i<10)end[i][0]+=.04f;
                else if(i==10||i==13)end[i][0]+=.012f;
                else if(i==11||i==14)end[i][0]+=.006f;
            }
            Rig2DProfile blackTo=new Rig2DProfile(fixture.blackTo.id,fixture.blackTo.projectId,fixture.blackTo.sourceAssetId,
                    true,false,end,fixture.blackTo.outline,fixture.blackTo.swordTip,fixture.blackTo.swordHilt,fixture.blackTo.swordOutline);
            JSONObject contacts=new JSONObject().put("black",new JSONArray()
                    .put(plant("leadingFoot",fixture.blackFrom.joints[12]))
                    .put(plant("trailingFoot",fixture.blackFrom.joints[15]))).put("white",new JSONArray());
            StoryboardContactPlan plan=StoryboardContactPlan.parse(contacts,fixture.blackFrom,fixture.whiteFrom,
                    blackTo,fixture.whiteTo,SourceArtworkRasterFixture.W,SourceArtworkRasterFixture.H);
            Bitmap decoded=NativeStoryboardSourceImage.read(fixture.context,fixture.from,SourceArtworkRasterFixture.W,SourceArtworkRasterFixture.H);
            Bitmap marked;
            try{marked=decoded.copy(Bitmap.Config.ARGB_8888,true);}finally{decoded.recycle();}
            if(marked==null)throw new IllegalStateException("Cannot allocate mutable synthetic foot marker image");
            File image=File.createTempFile("planted-foot-material-",".png",fixture.context.getFilesDir());
            Bitmap first=null,middle=null,last=null;
            try{
                float[] anchor=fixture.blackFrom.joints[12];
                int centerX=(int)(anchor[0]*marked.getWidth()),centerY=(int)(anchor[1]*marked.getHeight());
                for(int y=centerY-3;y<=centerY+3;y++)for(int x=centerX-3;x<=centerX+3;x++)marked.setPixel(x,y,FOOT_MATERIAL);
                try(FileOutputStream out=new FileOutputStream(image)){assertTrue(marked.compress(Bitmap.CompressFormat.PNG,100,out));}
                ProjectStore.Asset source=ProjectStore.Asset.fromJson(fixture.from.toJson());source.uri=Uri.fromFile(image).toString();
                try(NativeSkinnedSprite2D sprite=new NativeSkinnedSprite2D(fixture.context,source,fixture.blackFrom,marked.getWidth(),marked.getHeight())){
                    double legLength=length(fixture.blackFrom.joints[10],fixture.blackFrom.joints[11])
                            +length(fixture.blackFrom.joints[11],fixture.blackFrom.joints[12]);
                    double[] initial=null;
                    for(int index=0;index<=10;index++){
                        StoryboardPoseMotion.Frame motion=StoryboardPoseMotion.between(fixture.blackFrom,fixture.whiteFrom,blackTo,fixture.whiteTo,
                                fixture.blackFrom.swordTip,fixture.whiteFrom.swordTip,blackTo.swordTip,fixture.whiteTo.swordTip,
                                index/10d,StoryboardPoseMotion.Action.ADVANCE,plan);
                        Bitmap frame=Bitmap.createBitmap(marked.getWidth(),marked.getHeight(),Bitmap.Config.ARGB_8888);
                        try{
                            sprite.render(new Canvas(frame),motion.black,255);
                            SourceArtworkRasterFixture.writeEvidence("measured_planted_foot_phase_"+index,frame);
                            double[] measured=SourceArtworkRasterFixture.centroid(frame,FOOT_MATERIAL,false);
                            if(initial==null)initial=measured;
                            assertTrue("Actual foot pixels drifted at phase "+index/10d,
                                    Math.hypot(measured[0]-initial[0],measured[1]-initial[1])<=legLength*.05);
                            if(index==0){first=frame;frame=null;}else if(index==5){middle=frame;frame=null;}else if(index==10){last=frame;frame=null;}
                        }finally{if(frame!=null)frame.recycle();}
                    }
                    SourceArtworkRasterFixture.writeEvidence("measured_planted_foot_material",first,middle,last);
                }
            }finally{
                marked.recycle();if(first!=null)first.recycle();if(middle!=null)middle.recycle();if(last!=null)last.recycle();image.delete();
            }
        }
    }
    private static JSONObject plant(String name,float[] anchor)throws Exception{
        return new JSONObject().put("foot",name).put("start",0).put("end",1).put("anchor",new JSONArray().put(anchor[0]).put(anchor[1]));
    }
    private static double length(float[] a,float[] b){return Math.hypot((a[0]-b[0])*SourceArtworkRasterFixture.W,(a[1]-b[1])*SourceArtworkRasterFixture.H);}
}
