package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

/** Native product geometry/Canvas checks; no replacement drawing implementation. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchHandCelGeometryTest {
    private static final String[] VIEWS={"front","left","right","back","high","low"},HANDS={"right","left"};
    private static final int BACKGROUND=0xFFF4E4D1;
    // Exact raw Ink/path/gradient and native pixel digests captured from d76 before editing.
    private static final String[][] LEGACY={
        {"827cea7158fe51923342e05e8e612ae3b8e3c7f828d1ebb357b2eeaccbef626d","b474d454f12b3e761961952fa357326a48d7e225197bed186f1fd9175c8bc26e"},
        {"a7d8c2f94a80a9f97246f17a5c91f3836f31054e7e04080deb5610c787e87121","3c13740e7c1a088f408880be64fa5c8dccf31d00884c36d41252099eb0e7eca8"},
        {"4ad611c1699db848f22f99ea287711437d5b61e24bfa8a3bd21a6753df46ada3","74031c4ee24da4825e06cffa20a293fd6787a6d409a1ebd2ebf124eb952aadea"},
        {"c395245e8f2b180fed0d4e019f06d8aaf1f8396ed2ab548231e93116d7582fc1","c8b797d040d026c2645c42d63247da9bda7d5f552daf205483b4a33f2ad361de"},
        {"bcea0bc6337264f1b9542edea938deb34787790810c4755e1491f530be4a097e","881bb98beb674e8ec4340da22d3c27a94e57dacfdf82e76dd1204252668fc6ad"},
        {"4fa9e3b9ed885218dd5d0ac97d75c3482f61670d2d11470c87e06f65da790276","ef8e7ac4af4383b1673bc8d046806d51aa3eb8d02ed978baab355d37c5a2b6a7"},
        {"701a47ca9b24010d0cd96bb966039584e58fcf7ac40d360007614a973ff99b09","55cc578af119d15b04c7c3cfcfae2b04edae79f140e7631dbaa697f4e46bc13c"},
        {"c012e9905856ebf6e52e777b37c0348b57a5c669c73f623f5186201b8f3c857c","94d7a6d7960b06729360b97e9d3b688e17734b538893d74e26be3a2686fc0aa1"},
        {"2c8bc530f40878878fa8eb62f4f1d0f0b066e86de6d1b67baf760715e460c6f6","8ef9c00b7269ca8bcc8ddf896c154a10444602c8bee5a3434e85cf37a8343a48"},
        {"480ad1aea93ffc3e9a1c38595adeb6a3c02bbc3804e3692ea33d70a006a738b5","00041834b269121096f97b0b150cc187191eb2013df8145cfcc21b9709f6dd99"},
        {"03c516409c388fbe5d78e8985a9091efb9fd63a867c31a94fd25a5d0a75d5bfb","88c4548f2683b31c4f5758433419f96e4e5168568cf46b3f676eab1c19a5e3b4"},
        {"490a0cb99cc7fc67179cb88f22c62835b7b15354a1730d31ebe46aa0ae327150","b49299c4c33f418f3556521976e25e34d679e28839f1826f1af0ca45a135b8d7"},
        {"71463f93fe2f797c44c42111af618659b5b67f342d2e3d14c01dfbc3622bc43f","3f735ae232e2bedb32f9c38e1a8d0143c5a591cf61f39b98dfc25650505f9078"},
        {"d3e4d4fa0d96333f7f09528514c3bdafde47ee7c6eeefa46a8fc375023f5c910","3fcc56bb0e17eca739caed78e30130a25e44dd4594590b12220a93980285a9cc"},
        {"6e20c228808252a5bc07e6edf37cb4d8acef175275066ccdcae0f73bb590c037","71f7ea9b6b46cc236c7fa6f7f2b64ba013fbb31885c1669bdf8786d5d540f2ff"},
        {"9529c9e188008de7b9f798c6deecdb7ab204df5cff04c4d68a7d7aba0af96194","bb67c1b98acd7c6bba9212452f1aa22430e075b0938a054dddb81be20646dd5a"},
        {"4564442d79faaae535f547af42d0fb7ed4aa81221cd31f5153bdedcffc8b3496","7f9ba3e55dbf7abe8908589d1a51038a5a2bb6cac01d63acc3c766de8221c588"},
        {"ee9a128c2bd8fed47e2b67f736a65fc3366f5e6d5e2b69d08b3b54d77bd2dfea","682ce0cb0bc5df5c2b97455fe008e024ce2de38139e97df6628da94b67d8a31b"},
        {"f5398d6a68e266afcaa56533465623edd8f481f53b2155e57b3c29f163d34a00","3ce58e9bbc8d9d384e772e579e36c05d1dc19664471af79d126be806431d3a9a"},
        {"7e84372fbd3cbad7a7340fafc78351e2ce83bceaa0500310fa79120d36e2e479","fea91669504c3b0052a5c171cca0124c9e4ef2fd83680b0d35c905c3e87e42fe"},
        {"e179b9da0f27facf23174c58f6a256388a5edcdf9bf3f0014bb3ca5ccfb512c7","3c264bbb35d387eb8b7c823d63c9bb17219df60517ac844b4f3f5763f462c9a4"},
        {"66e60ead1eef23379367a4279585623dd1835fbdeb9db0a1ec086a36a022e67a","51368da2ba0c32ba115c0d91fcf83a2a3c4494e646052532b99cb2a3e4bc59ef"},
        {"a187d9c5cc8105569b8d0e9ef7b1f2880595aff1256ee3950305b7ce6550364a","778620f9eda507752535e6098c7c014b674abe8213c2cfed747789aae93ad39a"},
        {"486b607f5e37558ce535138f819b51e89d9a9d9fe428af5431991ef83c4eab33","6057084cf0f260400965f3abece287734c59ea427a5a18e06f1bd5727e4c1d6f"},
    };

    @Test public void defaultAndExplicitSmoothRetainEveryLegacyInkAndPixel()throws Exception {
        int at=0;for(String hand:HANDS)for(String view:VIEWS)for(int mode:new int[]{0,1}){
            JSONObject s=settings(null,hand,view);double[] pose=pose(mode);String expectedRaw=LEGACY[at][0],expectedPixel=LEGACY[at++][1];
            assertEquals("Legacy raw native paths/gradients "+hand+" "+view+" "+mode,expectedRaw,inkHash(SketchHandGeometry.draw(s,pose,1280,720)));
            assertEquals("Explicit smooth must be the same algorithm",expectedRaw,inkHash(SketchHandGeometry.draw(new JSONObject(s.toString()).put("renderStyle","smooth"),pose,1280,720)));
            Bitmap b=render(s,pose,1280,720);try{assertEquals("Legacy actual pixels "+hand+" "+view+" "+mode,expectedPixel,pixelHash(b));}finally{b.recycle();}
        }
    }
    @Test public void celEmitsBroadSolidSkinGroupsInsteadOfPerTriangleGradients()throws Exception {
        for(String hand:HANDS)for(String view:VIEWS)for(int mode:new int[]{0,1}){
            JSONObject s=settings("cel",hand,view);List<SketchHandGeometry.Ink> ink=SketchHandGeometry.draw(s,pose(mode),1280,720);
            assertTrue("Cel must merge visible faces rather than paint a triangle mesh: "+hand+" "+view+" count="+ink.size(),ink.size()<=7);
            assertTrue(ink.get(0).fill);assertEquals(s.getString("skinColor"),ink.get(0).color);assertFalse(ink.get(ink.size()-1).fill);
            Set<String> skinColors=new HashSet<>();
            for(SketchHandGeometry.Ink mark:ink){assertNull("Cel uses native solid tone regions",mark.gradient);if(mark.fill&&!mark.color.equals(s.getString("nailColor"))&&!mark.color.equals(SketchCharacterArt.tone(s.getString("skinColor"),.67)))skinColors.add(mark.color);}
            assertTrue("At most three intentional skin tones",skinColors.size()<=3);
        }
    }
    @Test public void nailsAndCreasesAreStrictlyInsideVisibleSkinAndDoNotChangeItsOutline()throws Exception {
        for(String hand:HANDS)for(String view:VIEWS)for(int mode:new int[]{0,1}){
            JSONObject s=settings("cel",hand,view);List<SketchHandGeometry.Ink> marks=SketchHandGeometry.draw(s,pose(mode),1280,720);Path skin=marks.get(0).path;
            Path outline=marks.get(marks.size()-1).path;Path difference=new Path();assertTrue(difference.op(skin,outline,Path.Op.XOR));assertTrue("Only actual skin defines the outer contour",difference.isEmpty());
            for(int i=1;i<marks.size()-1;i++)inside(marks.get(i).path,skin,"All native tone/detail ink must stay inside visible skin: "+hand+" "+view+" "+mode+" mark="+i+" color="+marks.get(i).color);
            // Removing nail/crease detail cannot change skin geometry or silhouette.
            List<SketchHandGeometry.Ink> contour=SketchHandGeometry.draw(new JSONObject(s.toString()).put("detail","contour"),pose(mode),1280,720);
            assertTrue(difference.op(skin,contour.get(0).path,Path.Op.XOR));assertTrue("Details must not create floating silhouette fragments",difference.isEmpty());
        }
    }
    @Test public void unoccludedNailsAreRoundedInsetSurfacesAndPalmViewHidesThem()throws Exception {
        for(String hand:HANDS){JSONObject s=settings("cel",hand,"back");Path nails=nails(SketchHandGeometry.draw(s,pose(0),1280,720),s);assertFalse("Back of open hand shows nails",nails.isEmpty());
            List<Path> shapes=contours(nails);assertEquals("Five independent seated nails",5,shapes.size());
            for(Path nail:shapes){float[] points=nail.approximate(.05f);assertTrue("Rounded nail has a curved perimeter rather than four corners",points.length>=8*3);RectF b=new RectF();nail.computeBounds(b,true);Path corner=new Path(),overlap=new Path();float x=b.left+b.width()*.06f,y=b.top+b.height()*.06f;corner.addCircle(x,y,.08f,Path.Direction.CW);assertTrue(overlap.op(corner,nail,Path.Op.INTERSECT));assertTrue("Nail's bounding-box corner is rounded away",overlap.isEmpty());}
            JSONObject palm=settings("cel",hand,"front");assertTrue("Open palmar view must not expose dorsal nails",nails(SketchHandGeometry.draw(palm,pose(0),1280,720),palm).isEmpty());
        }
    }
    @Test public void palmPixelsReadAsBroadRegionsWithoutAVisibleTriangleGrid()throws Exception {
        JSONObject s=settings("cel","right","front").put("detail","contour");Bitmap b=render(s,pose(0),1280,720);try{
            Set<Integer> palette=Set.of(Color.parseColor(s.getString("skinColor")),Color.parseColor(SketchCharacterArt.tone(s.getString("skinColor"),.83)),Color.parseColor(SketchCharacterArt.tone(s.getString("skinColor"),1.07)));
            int solid=0,total=0;for(int y=306;y<364;y+=2)for(int x=600;x<678;x+=2){total++;if(palette.contains(b.getPixel(x,y)))solid++;}
            assertTrue("At least 95% of interior palm pixels are broad flat tones, not mesh/gradient seams: "+solid+"/"+total,solid>=total*.95);
        }finally{b.recycle();}
    }
    @Test public void openPalmHasNoDetachedTinyShadowPatchesAtThePadJoins()throws Exception {
        JSONObject s=settings("cel","right","front").put("detail","contour");Bitmap b=render(s,pose(0),1280,720);try{
            int shadow=Color.parseColor(SketchCharacterArt.tone(s.getString("skinColor"),.83));Set<Integer> remaining=new HashSet<>();
            for(int y=280;y<410;y++)for(int x=600;x<692;x++)if(b.getPixel(x,y)==shadow)remaining.add(y*1280+x);
            while(!remaining.isEmpty()){int seed=remaining.iterator().next();remaining.remove(seed);ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(seed);int area=0;
                while(!queue.isEmpty()){int p=queue.removeFirst();area++;for(int q:new int[]{p-1,p+1,p-1280,p+1280})if(remaining.remove(q))queue.add(q);}
                assertFalse("A detached palm shadow patch is an implementation seam, not broad cel lighting: area="+area+" seed="+(seed%1280)+","+(seed/1280)+" owner="+visibleOwner(s,pose(0),seed%1280,seed/1280),area>=5&&area<=100);
            }
        }finally{b.recycle();}
    }
    @Test public void palmAndWristRootShareOneContinuousPosedLightingField()throws Exception {
        java.lang.reflect.Method surfaces=SketchHandGeometry.class.getDeclaredMethod("celSurfaces",JSONObject.class,double[].class);surfaces.setAccessible(true);
        for(double[] wrist:new double[][]{{0,0},{25,30},{-25,-30}}){double[] p=pose(0);p[7]=wrist[0];p[8]=wrist[1];
            @SuppressWarnings("unchecked") List<SketchHandGeometry.Face> faces=(List<SketchHandGeometry.Face>)surfaces.invoke(null,settings("cel","right","front"),p);
            int palm=0,arm=0,thumb=0;for(SketchHandGeometry.Face face:faces)if(face.material==0&&(face.owner==0||face.owner==5||face.owner==6))for(int i=0;i<face.p.length;i++){
                SketchHandGeometry.V world=face.p[i];if(world.y>.4)continue;
                // Inverse yaw then inverse pitch, independent of component normals.
                double yaw=Math.toRadians(-p[8]),pitch=Math.toRadians(-p[7]);double x=world.x*Math.cos(yaw)+world.z*Math.sin(yaw),z=-world.x*Math.sin(yaw)+world.z*Math.cos(yaw);
                double y=world.y*Math.cos(pitch)-z*Math.sin(pitch);z=world.y*Math.sin(pitch)+z*Math.cos(pitch);
                if(face.owner==5&&y<-.30)continue;
                SketchHandGeometry.V local=new SketchHandGeometry.V(x/.70,(y+.6)*.02-.10,z/.26).unit(),expected=SketchHandGeometry.rotate(local,p[7],p[8]);
                assertEquals("Shared positional lighting x owner="+face.owner,expected.x,face.normals[i].x,1e-9);assertEquals(expected.y,face.normals[i].y,1e-9);assertEquals(expected.z,face.normals[i].z,1e-9);
                if(face.owner==0)palm++;else if(face.owner==6)arm++;else thumb++;
            }assertTrue(palm>100);assertTrue(arm>100);assertTrue(thumb>20);
        }
    }
    @Test public void exposedProximalFingerSkinSharesThePosedPalmLightingField()throws Exception {
        for(String hand:HANDS)for(String view:new String[]{"low","high"})for(int mode:new int[]{0,1}){
            double[] p=pose(mode);SketchHandGeometry.V[][] chain=SketchHandGeometry.joints(p);int[] checked=new int[4];
            for(SketchHandGeometry.Face face:celFaces(settings("cel",hand,view),p))if(face.material==0&&face.owner>=1&&face.owner<=4)for(int i=0;i<face.p.length;i++){
                SketchHandGeometry.V local=SketchHandGeometry.rotate(face.p[i],view.equals("high")?-48:48,0);if(hand.equals("left"))local=new SketchHandGeometry.V(-local.x,local.y,local.z);
                local=SketchHandGeometry.rotate(SketchHandGeometry.rotate(local,0,-p[8]),-p[7],0);int d=face.owner-1;
                if(local.sub(chain[d][0]).dot(chain[d][1].sub(chain[d][0]).unit())>-.005)continue;
                SketchHandGeometry.V expected=new SketchHandGeometry.V(local.x/.70,(local.y+.6)*.02-.10,local.z/.26).unit();expected=SketchHandGeometry.rotate(expected,p[7],p[8]);if(hand.equals("left"))expected=new SketchHandGeometry.V(-expected.x,expected.y,expected.z);expected=SketchHandGeometry.rotate(expected,view.equals("high")?48:-48,0);
                assertEquals("Buried proximal roots must not introduce cap/mesh light patches: "+hand+" "+view+" owner="+face.owner,expected.x,face.normals[i].x,1e-9);assertEquals(expected.y,face.normals[i].y,1e-9);assertEquals(expected.z,face.normals[i].z,1e-9);checked[d]++;
            }for(int d=0;d<4;d++)assertTrue("Exercise each proximal digit",checked[d]>20);
        }
    }
    @Test public void foldedNailsCannotPaintThroughTheirOwnNearerFingerSegment()throws Exception {
        java.lang.reflect.Method method=SketchHandGeometry.class.getDeclaredMethod("celSurfaces",JSONObject.class,double[].class);method.setAccessible(true);int checked=0;
        search: for(String view:VIEWS)for(double thumbCurl:new double[]{1,.8,.6,.3})for(double opposition:new double[]{.25,.5,.75,1,0})for(double curl:new double[]{.55,.7,.85,1})for(double[] wrist:new double[][]{{0,0},{25,25},{25,-25},{-25,25},{-25,-25}}){JSONObject s=settings("cel","right",view);double[] p={curl,curl,curl,curl,thumbCurl,opposition,.2,wrist[0],wrist[1]};
            @SuppressWarnings("unchecked") List<SketchHandGeometry.Face> faces=(List<SketchHandGeometry.Face>)method.invoke(null,s,p);
            Path drawn=null;
            for(SketchHandGeometry.Face nail:faces)if(nail.material==1&&nail.p[1].sub(nail.p[0]).cross(nail.p[2].sub(nail.p[0])).z>0){SketchHandGeometry.V q=nail.p[0].add(nail.p[1]).add(nail.p[2]).mul(1d/3);double skin=-Double.MAX_VALUE,otherNail=-Double.MAX_VALUE;int owner=-1;
                for(SketchHandGeometry.Face f:faces){double z=rayDepth(f,q.x,q.y);if(f.material==0&&z>skin){skin=z;owner=f.owner;}if(f.material==1)otherNail=Math.max(otherNail,z);}
                if(owner==nail.owner&&skin>q.z+.008&&skin>otherNail+.008){checked++;if(drawn==null)drawn=nails(SketchHandGeometry.draw(s,p,1280,720),s);Path sample=new Path();sample.addCircle((float)(640+q.x*112),(float)(396+q.y*112),.025f,Path.Direction.CW);assertTrue(sample.op(drawn,Path.Op.INTERSECT));assertTrue("Folded nail occluded by its own nearer segment: "+view+" curl="+curl+" thumb="+thumbCurl+","+opposition+" wrist="+Arrays.toString(wrist)+" owner="+owner+" depth gap="+(skin-q.z),sample.isEmpty());if(checked>=8)break search;}
            }
        }System.out.println("Independently ray-proven same-finger hidden nail samples="+checked);assertTrue("Exercise independently ray-proven same-finger occlusion samples: "+checked,checked>0);
    }
    private static double rayDepth(SketchHandGeometry.Face f,double x,double y){
        double nearest=-Double.MAX_VALUE;for(int t=1;t<f.p.length-1;t++){SketchHandGeometry.V a=f.p[0],b=f.p[t],c=f.p[t+1];double det=(b.y-c.y)*(a.x-c.x)+(c.x-b.x)*(a.y-c.y);if(Math.abs(det)<1e-12)continue;double u=((b.y-c.y)*(x-c.x)+(c.x-b.x)*(y-c.y))/det,v=((c.y-a.y)*(x-c.x)+(a.x-c.x)*(y-c.y))/det;if(u>=-1e-10&&v>=-1e-10&&u+v<=1+1e-10)nearest=Math.max(nearest,u*a.z+v*b.z+(1-u-v)*c.z);}return nearest;
    }
    @Test public void nativeTipAndHeelCoverageAgreesWithIndependentUnculledRays()throws Exception {
        for(String hand:HANDS)for(String view:new String[]{"low","high"}){
            JSONObject s=settings("cel",hand,view);double[] p=pose(0);List<SketchHandGeometry.Face> faces=celFaces(s,p);Bitmap b=render(s,p,1280,720);int checked=0,holes=0;String first="";
            List<SketchHandGeometry.V> centers=new ArrayList<>();for(SketchHandGeometry.V[] chain:SketchHandGeometry.joints(p))centers.add(project(chain[3],hand,view));centers.add(project(new SketchHandGeometry.V(-.25,.06,.15),hand,view));
            try{for(int region=0;region<centers.size();region++){SketchHandGeometry.V center=centers.get(region);int radius=region==5?22:16;
                for(int y=(int)(396+112*center.y)-radius;y<=(int)(396+112*center.y)+radius;y++)for(int x=(int)(640+112*center.x)-radius;x<=(int)(640+112*center.x)+radius;x++){
                    double px=(x+.5-640)/112,py=(y+.5-396)/112;
                    if(!rayHitsSkin(faces,px,py)||!rayHitsSkin(faces,px-.006,py)||!rayHitsSkin(faces,px+.006,py)||!rayHitsSkin(faces,px,py-.006)||!rayHitsSkin(faces,px,py+.006))continue;
                    checked++;if(b.getPixel(x,y)==BACKGROUND){holes++;if(first.isEmpty())first=x+","+y+" region="+region;}
                }
            }assertTrue("Exercise actual tip/heel interiors",checked>200);assertEquals("Unculled skin rays must not expose background through native cel caps: "+hand+" "+view+" first="+first,0,holes);}finally{b.recycle();}
        }
    }
    @Test public void profileKnuckleAndWristEdgesHaveNoAbruptLocalRootLedges()throws Exception {
        for(String hand:HANDS)for(String view:new String[]{"left","right"})for(int mode:new int[]{0,1}){Bitmap b=render(settings("cel",hand,view),pose(mode),1280,720);try{
            for(int zone=0;zone<2;zone++)for(int side=0;side<2;side++){
                // A curled fingertip ends in this row range on the opposite
                // side. Both shoulders are exposed/tested in OPEN; GRASP keeps
                // the established dorsal and wrist regression windows.
                if(mode==1&&zone==0&&side!=(view.equals("left")?1:0))continue;
                int previous=-1;for(int y=zone==0?245:392;y<=(zone==0?262:415);y++){
                int edge=-1;for(int x=590;x<690;x++)if(b.getPixel(x,y)!=BACKGROUND){if(side==1)edge=Math.max(edge,x);else if(edge<0)edge=x;}
                if(previous>=0&&edge>=0)assertTrue("Both palmar/dorsal root edges must blend: "+hand+" "+view+" mode="+mode+" zone="+zone+" side="+side+" y="+y+" edge="+edge+" previous="+previous,Math.abs(edge-previous)<=3);
                previous=edge;
            }}
        }finally{b.recycle();}}
    }
    @Test public void palmCreaseInteriorsStayAboveEveryCrossedSupportingFacet()throws Exception {
        List<SketchHandGeometry.Face> faces=celFaces(settings("cel","right","front"),pose(0));int checked=0;
        for(SketchHandGeometry.Face f:faces)if(f.material==2&&f.owner==0)for(int t=1;t<f.p.length-1;t++)for(int i=0;i<=8;i++)for(int j=0;j<=8-i;j++){
            double u=i/8d,v=j/8d;SketchHandGeometry.V p=f.p[0].mul(1-u-v).add(f.p[t].mul(u)).add(f.p[t+1].mul(v));boolean back=p.z<0;double support=back?Double.MAX_VALUE:-Double.MAX_VALUE;
            for(SketchHandGeometry.Face skin:faces)if(skin.material==0&&skin.owner==0){double z=back?rayFarDepth(skin,p.x,p.y):rayDepth(skin,p.x,p.y);support=back?Math.min(support,z):Math.max(support,z);}
            if(Math.abs(support)==Double.MAX_VALUE)continue;checked++;double distance=(p.z-support)*(back?-1:1);
            assertTrue("Whole crease triangle must be seated above the actual skin, including facet crossings at "+p.x+","+p.y+": "+distance,distance>=.0015&&distance<=.006);
        }assertTrue("Exercise crease triangle interiors, not just corners",checked>10000);
    }
    @Test public void palmCreasesAreSeatedOnActualCelSkinInsteadOfAnalyticPadEstimates()throws Exception {
        List<SketchHandGeometry.Face> faces=celFaces(settings("cel","right","front"),pose(0));int checked=0;
        for(SketchHandGeometry.Face f:faces)if(f.material==2&&f.owner==0)for(SketchHandGeometry.V v:f.p){double support=v.z>0?-Double.MAX_VALUE:Double.MAX_VALUE;
            for(SketchHandGeometry.Face skin:faces)if(skin.material==0&&skin.owner==0){double z=rayDepth(skin,v.x,v.y);if(z>-Double.MAX_VALUE)support=v.z>0?Math.max(support,z):Math.min(support,rayFarDepth(skin,v.x,v.y));}
            if(Math.abs(support)==Double.MAX_VALUE)continue;checked++;double distance=Math.abs(v.z-support);assertTrue("Cel crease must sit within .006 model units of actual supporting skin, distance="+distance,distance>=.0015&&distance<=.006);
        }assertTrue("Check front and dorsal crease mesh vertices",checked>200);
    }
    private static double rayFarDepth(SketchHandGeometry.Face f,double x,double y){
        double far=Double.MAX_VALUE;for(int t=1;t<f.p.length-1;t++){SketchHandGeometry.V a=f.p[0],b=f.p[t],c=f.p[t+1];double det=(b.y-c.y)*(a.x-c.x)+(c.x-b.x)*(a.y-c.y);if(Math.abs(det)<1e-12)continue;double u=((b.y-c.y)*(x-c.x)+(c.x-b.x)*(y-c.y))/det,v=((c.y-a.y)*(x-c.x)+(a.x-c.x)*(y-c.y))/det;if(u>=-1e-10&&v>=-1e-10&&u+v<=1+1e-10)far=Math.min(far,u*a.z+v*b.z+(1-u-v)*c.z);}return far;
    }
    @SuppressWarnings("unchecked") private static List<SketchHandGeometry.Face> celFaces(JSONObject s,double[] p)throws Exception {java.lang.reflect.Method m=SketchHandGeometry.class.getDeclaredMethod("celSurfaces",JSONObject.class,double[].class);m.setAccessible(true);return (List<SketchHandGeometry.Face>)m.invoke(null,s,p);}
    private static boolean rayHitsSkin(List<SketchHandGeometry.Face> faces,double x,double y){for(SketchHandGeometry.Face f:faces)if(f.material==0&&rayDepth(f,x,y)>-Double.MAX_VALUE)return true;return false;}
    private static SketchHandGeometry.V project(SketchHandGeometry.V v,String hand,String view){if(hand.equals("left"))v=new SketchHandGeometry.V(-v.x,v.y,v.z);return SketchHandGeometry.rotate(v,view.equals("high")?48:-48,0);}
    @Test public void rayVisibleCreaseInteriorsRetainTheirNativeInk()throws Exception {
        for(String hand:HANDS)for(String view:new String[]{"high","low"}){
            int mode=view.equals("high")?1:0;JSONObject s=settings("cel",hand,view);double[] p=pose(mode);List<SketchHandGeometry.Face> faces=celFaces(s,p);Path marks=new Path();
            for(SketchHandGeometry.Ink ink:SketchHandGeometry.draw(s,p,1280,720))if(ink.fill&&ink.color.equals(SketchCharacterArt.tone(s.getString("skinColor"),.67)))marks.set(ink.path);
            int checked=0,missing=0;String first="";
            for(SketchHandGeometry.Face f:faces)if(f.material==2&&f.owner==0&&f.p[1].sub(f.p[0]).cross(f.p[2].sub(f.p[0])).z>1e-8)for(int t=1;t<f.p.length-1;t++){
                SketchHandGeometry.V q=f.p[0].add(f.p[t]).add(f.p[t+1]).mul(1d/3);double nearest=-Double.MAX_VALUE;
                for(SketchHandGeometry.Face skin:faces)if(skin.material==0)nearest=Math.max(nearest,rayDepth(skin,q.x,q.y));
                if(q.z-nearest<.0003)continue;boolean interior=true;
                for(double[] offset:new double[][]{{-.001,0},{.001,0},{0,-.001},{0,.001}}){double px=q.x+offset[0],py=q.y+offset[1],ownerDepth=-Double.MAX_VALUE,otherDepth=-Double.MAX_VALUE;
                    for(SketchHandGeometry.Face skin:faces)if(skin.material==0){double z=rayDepth(skin,px,py);if(skin.owner==0)ownerDepth=Math.max(ownerDepth,z);else otherDepth=Math.max(otherDepth,z);}
                    if(ownerDepth<=otherDepth)interior=false;
                }if(!interior)continue;double x=640+112*q.x,y=396+112*q.y;
                // Exercise the main palm lines. Isolated endpoint specks outside
                // these windows are deliberately removed by the separate policy.
                double anatomicalX=hand.equals("left")?1280-x:x;if(anatomicalX<590||anatomicalX>700||y<(view.equals("high")?310:330)||y>(view.equals("high")?346:398))continue;
                // Exclude the numerical skin-only contour guard, independently.
                if(!rayHitsSkin(faces,q.x-.001,q.y)||!rayHitsSkin(faces,q.x+.001,q.y)||!rayHitsSkin(faces,q.x,q.y-.001)||!rayHitsSkin(faces,q.x,q.y+.001))continue;
                Path probe=new Path();probe.addCircle((float)x,(float)y,.01f,Path.Direction.CW);assertTrue(probe.op(marks,Path.Op.INTERSECT));checked++;if(probe.isEmpty()){missing++;if(first.isEmpty()){first=x+","+y;
                    Class<?> tri=Class.forName("com.rezoxnemesis.videostudio.SketchHandGeometry$CelTriangle");java.lang.reflect.Constructor<?> ctor=tri.getDeclaredConstructors()[0];ctor.setAccessible(true);List<Object> triangles=new ArrayList<>();for(SketchHandGeometry.Face skin:faces)if(skin.material==0)for(int k=1;k<skin.p.length-1;k++)triangles.add(ctor.newInstance(skin.p[0],skin.p[k],skin.p[k+1]));
                    java.lang.reflect.Method visible=SketchHandGeometry.class.getDeclaredMethod("celVisibleDetail",SketchHandGeometry.Face.class,List.class,double.class,double.class,double.class);visible.setAccessible(true);Path raw=(Path)visible.invoke(null,f,triangles,112d,640d,396d);Path at=new Path();at.addCircle((float)x,(float)y,.01f,Path.Direction.CW);assertTrue(at.op(raw,Path.Op.INTERSECT));System.out.println("Missing diagnostic rawVisible="+!at.isEmpty()+" depthMargin="+(q.z-nearest)+" area="+f.p[t].sub(f.p[0]).cross(f.p[t+1].sub(f.p[0])).length());
                }}
            }
            System.out.println("Ray-visible crease "+hand+" "+view+" samples="+checked+" missing="+missing+" first="+first);assertTrue(checked>50);assertEquals("Visible seated crease interiors must retain ink: "+hand+" "+view+" first="+first,0,missing);
        }
    }
    @Test public void clippedCreasesDoNotLeaveIsolatedSubpixelSpecks()throws Exception {
        int large=0;for(String hand:HANDS)for(String view:new String[]{"high","low"})for(int size:new int[]{720,2048}){
            JSONObject s=settings("cel",hand,view);double unit=size/720d*112;List<SketchHandGeometry.Ink> ink=SketchHandGeometry.draw(s,pose(view.equals("high")?1:0),size==720?1280:2048,size);
            for(SketchHandGeometry.Ink mark:ink)if(mark.fill&&mark.color.equals(SketchCharacterArt.tone(s.getString("skinColor"),.67))){PathMeasure measure=new PathMeasure(mark.path,false);do{
                Path part=new Path();measure.getSegment(0,measure.getLength(),part,true);part.close();RectF box=new RectF();part.computeBounds(box,true);
                Path painted=new Path(part);assertTrue(painted.op(mark.path,Path.Op.INTERSECT));if(painted.isEmpty())continue;
                assertFalse("Tiny depth-clipped crease island: "+hand+" "+view+" size="+size+" bounds="+box,Math.max(box.width(),box.height())<=unit*.028&&measure.getLength()<=unit*.065);large++;
            }while(measure.nextContour());}
        }assertTrue("Main creases must remain present across views/resolutions",large>40);
    }
    @Test public void frontGraspRetainedAndEditableHaveNoDetachedCreaseFlecks()throws Exception {
        int bad=0,visibleMain=0;String first="";
        for(String hand:HANDS)for(int size:new int[]{720,2048}){int width=size==720?1280:2048;double referenceScale=size/720d;JSONObject settings=settings("cel",hand,"front");double[] pose=pose(1);String color=SketchCharacterArt.tone(settings.getString("skinColor"),.67);List<Path> paths=new ArrayList<>();Path retainedPath=new Path();
            for(SketchHandGeometry.Ink ink:SketchHandGeometry.draw(settings,pose,width,size))if(ink.fill&&ink.color.equals(color))retainedPath.set(ink.path);paths.add(retainedPath);
            JSONObject doc=frameDocument(settings,pose,width,size);Path editablePath=new Path();JSONArray objects=doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");for(int i=0;i<objects.length();i++){JSONObject object=objects.getJSONObject(i);if(object.getBoolean("fill")&&object.getString("color").equals(color))assertTrue(editablePath.op(SketchFrameRenderer.path(object.getJSONArray("commands"),width,size),Path.Op.UNION));}paths.add(editablePath);
            Bitmap retained=render(settings,pose,width,size);try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){Bitmap editable=renderer.render(0);try{
                String evidence=System.getProperty("videostudio.handcel.fleckEvidence");if(evidence!=null){String label=hand+"-front-grasp-"+size;save(retained,new File(evidence,label+"-retained.png"));save(editable,new File(evidence,label+"-editable.png"));}
                for(int kind=0;kind<2;kind++){Path path=paths.get(kind);PathMeasure measure=new PathMeasure(path,false);int main=0;do{Path part=new Path();measure.getSegment(0,measure.getLength(),part,true);part.close();RectF box=new RectF();part.computeBounds(box,true);Path painted=new Path(part);assertTrue(painted.op(path,Path.Op.INTERSECT));if(painted.isEmpty())continue;
                    // Independent fixture limits bracket the measured v6 fleck:
                    // span 2.961px, perimeter 7.013px at the 720px reference size.
                    double span=Math.max(box.width(),box.height())/referenceScale,perimeter=measure.getLength()/referenceScale;
                    if(span<3.1&&perimeter<7.2){bad++;String label=hand+" size="+size+" kind="+(kind==0?"retained":"editable")+" span="+span+" perimeter="+perimeter+" bounds="+box;if(first.isEmpty())first=label;System.out.println("Detached front crease "+label);}else main++;
                }while(measure.nextContour());assertTrue("Keep the main front/grasp crease strokes: "+hand+" size="+size+" kind="+kind,main>=5);visibleMain+=main;}
            }finally{editable.recycle();}}finally{retained.recycle();}
        }assertTrue("Exercise both hands, sizes and actual render paths",visibleMain>=40);assertEquals("No isolated front-grasp crease flecks: "+first,0,bad);
    }
    @Test public void creaseDeclutteringPreservesLargeStrokesHolesAndOccludedGaps()throws Exception {
        java.lang.reflect.Method clean=SketchHandGeometry.class.getDeclaredMethod("celCreasesWithoutSpecks",Path.class,double.class);clean.setAccessible(true);
        for(float scale:new float[]{1,2048f/720}){Path expected=new Path();expected.addRect(0,0,100,.8f,Path.Direction.CW);Path cut=new Path();cut.addRect(49,-1,51,2,Path.Direction.CW);cut.addRect(20,.2f,20.5f,.6f,Path.Direction.CW);assertTrue(expected.op(cut,Path.Op.DIFFERENCE));expected.addRect(105,4,110,4.8f,Path.Direction.CW);Path source=new Path(expected);source.addRect(120,10,121,10.5f,Path.Direction.CW);source.addRect(125,10,125.5f,13,Path.Direction.CW);
            Matrix matrix=new Matrix();matrix.setScale(scale,scale);source.transform(matrix);expected.transform(matrix);Path result=(Path)clean.invoke(null,source,112d*scale);Path difference=new Path();assertTrue(difference.op(result,expected,Path.Op.XOR));assertTrue("Remove only the tiny painted island; preserve large strokes, hole and occluded gap",difference.isEmpty());
        }
    }
    @Test public void distalPalmRepairRetainsCentralPalmPadsAndAllContactGeometry()throws Exception {
        // Captured from the frozen v4 native celSurfaces, not from drawCel or
        // the new taper. Includes all forearm/finger skin and every central
        // palm/pad vertex (y >= -1); both open and grasp have 13,656 samples.
        String[] frozen={"f1c856b5512812ec403d84446d95ee7a79effd8d9fd007d0f73af32b9eb742ac","c3954b712fef1756e313202df31fff2d6d51cd684780e1855babc41a6c4faeeb"};
        for(int mode=0;mode<2;mode++){ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);int checked=0;
            for(SketchHandGeometry.Face f:celFaces(settings("cel","right","front"),pose(mode)))if(f.material==0)for(SketchHandGeometry.V v:f.p)if(f.owner!=0||v.y>=-1){out.writeInt(f.owner);out.writeDouble(v.x);out.writeDouble(v.y);out.writeDouble(v.z);checked++;}
            assertEquals("Central palm/pads and all finger/forearm contact vertices",13656,checked);assertEquals("Exact frozen-v4 geometry, mode="+mode,frozen[mode],hash(bytes.toByteArray()));
        }
    }
    @Test public void celRetainsExactJointLengthsAndEveryPoseControlRemainsLive()throws Exception {
        double[] p=pose(1);SketchHandGeometry.V[][] before=SketchHandGeometry.joints(p);
        for(String hand:HANDS)for(String view:VIEWS)SketchHandGeometry.draw(settings("cel",hand,view),p,1280,720);
        SketchHandGeometry.V[][] after=SketchHandGeometry.joints(p);
        for(int d=0;d<5;d++)for(int j=0;j<4;j++){assertEquals(before[d][j].x,after[d][j].x,0);assertEquals(before[d][j].y,after[d][j].y,0);assertEquals(before[d][j].z,after[d][j].z,0);if(j>0)assertEquals(SketchHandGeometry.LENGTHS[d][j-1],after[d][j].sub(after[d][j-1]).length(),1e-12);}
        JSONObject s=settings("cel","right","front");String original=inkHash(SketchHandGeometry.draw(s,p,1280,720));
        for(int channel=0;channel<9;channel++){double[] moved=p.clone();moved[channel]=channel<7?(p[channel]>.5?0:1):20;assertNotEquals("Native control "+SketchHandAnimation.CHANNELS[channel]+" must remain live",original,inkHash(SketchHandGeometry.draw(s,moved,1280,720)));}
    }
    @Test public void editableCelFrameUsesActualNativePathsAndMatchesRetainedInteriorPixels()throws Exception {
        int totalCrease=0,totalNailInteriors=0;
        for(String hand:HANDS)for(String view:VIEWS)for(int mode:new int[]{0,1}){
        String label=hand+"-"+view+"-"+(mode==0?"open":"grasp");JSONObject s=settings("cel",hand,view);double[] p=pose(mode);JSONObject doc=frameDocument(s,p,1280,720);SketchDocument.validate(doc);assertTrue(doc.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").length()<=7);
        Path creaseCoverage=new Path();String creaseColor=SketchCharacterArt.tone(s.getString("skinColor"),.67);for(SketchHandGeometry.Ink mark:SketchHandGeometry.draw(s,p,1280,720))if(mark.fill&&mark.color.equals(creaseColor))creaseCoverage.set(mark.path);
        Bitmap retained=render(s,p,1280,720);try(SketchFrameRenderer renderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),doc)){Bitmap editable=renderer.render(0);try{
            String debug=System.getProperty("videostudio.handcel.exportDebug");if(debug!=null){save(retained,new File(debug,label+"-retained.png"));save(editable,new File(debug,label+"-editable.png"));try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(debug,label+"-document.json")),java.nio.charset.StandardCharsets.UTF_8)){writer.write(doc.toString());}}
            int[] original=new int[1280*720],converted=new int[1280*720],underlay=new int[1280*720];retained.getPixels(original,0,1280,0,0,1280,720);editable.getPixels(converted,0,1280,0,0,1280,720);
            JSONObject withoutCreases=SketchDocument.copy(doc);JSONArray plain=withoutCreases.getJSONArray("layers").getJSONObject(0).getJSONArray("objects");for(int i=plain.length()-1;i>=0;i--)if(plain.getJSONObject(i).getBoolean("fill")&&plain.getJSONObject(i).getString("color").equals(creaseColor))plain.remove(i);
            try(SketchFrameRenderer plainRenderer=new SketchFrameRenderer(RuntimeEnvironment.getApplication(),withoutCreases)){Bitmap plainBitmap=plainRenderer.render(0);try{plainBitmap.getPixels(underlay,0,1280,0,0,1280,720);}finally{plainBitmap.recycle();}}
            int changed=0,foreground=0,deep=0,deepChanged=0,creaseSamples=0,nailInteriors=0;int crease=Color.parseColor(SketchCharacterArt.tone(s.getString("skinColor"),.67)),nail=Color.parseColor(s.getString("nailColor"));
            for(int y=2;y<718;y++)for(int x=2;x<1278;x++){int index=y*1280+x,a=original[index],b=converted[index];if(a!=BACKGROUND||b!=BACKGROUND){foreground++;if(a!=b)changed++;}
                if(a!=BACKGROUND||a!=b){boolean interior=true;for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)if(original[index+dy*1280+dx]!=a)interior=false;if(interior){if(a!=BACKGROUND)deep++;if(a==nail)nailInteriors++;if(a!=b)deepChanged++;}}
                // Isolate the editable crease contribution from the same actual
                // renderer with that mark removed. This measures line contrast,
                // rather than requiring identical antialiasing at a one-pixel line.
                if(a==crease&&containsPixel(creaseCoverage,x,y)){creaseSamples++;boolean found=false;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int at=index+dy*1280+dx;double expected=luma(underlay[at])-luma(crease),actual=luma(underlay[at])-luma(converted[at]);if(expected>6&&actual>=expected*.5)found=true;}assertTrue("Editable crease retains half its local contrast within one pixel: "+label+" at "+x+","+y,found);}
            }
            System.out.println("Editable comparison "+label+" changed="+changed+"/"+foreground+" deep="+deepChanged+"/"+deep+" nailInteriors="+nailInteriors+" creaseSamples="+creaseSamples);totalCrease+=creaseSamples;totalNailInteriors+=nailInteriors;
            assertTrue("Exercise at least 1000 solid interior samples, including thin profile views: "+label,deep>1000);assertEquals("No deep-interior palette or skin/background coverage changes: "+label,0,deepChanged);
            // Preserve the original 7% front-grasp limit; the additional views
            // must all satisfy the stronger zero deep-interior-error contract.
            if(hand.equals("right")&&view.equals("front")&&mode==1)assertTrue("Editable path simplification is confined to subpixel boundaries: "+changed+"/"+foreground,changed<foreground*.07);
        }finally{editable.recycle();}}finally{retained.recycle();}
        }assertTrue("Exercise thin crease coverage",totalCrease>10);assertTrue("Exercise solid nail interiors",totalNailInteriors>30);
    }
    private static boolean containsPixel(Path path,int x,int y){Path probe=new Path();probe.addCircle(x+.5f,y+.5f,.01f,Path.Direction.CW);assertTrue(probe.op(path,Path.Op.INTERSECT));return !probe.isEmpty();}
    private static double luma(int color){return Color.red(color)*.2126+Color.green(color)*.7152+Color.blue(color)*.0722;}
    @Test public void nativeSixViewOpenGraspScreenshotsIncludeNormalAndEnlargedRenders()throws Exception {
        String root=System.getProperty("videostudio.handcel.output");if(root==null)return;
        for(String hand:HANDS)for(String view:VIEWS)for(int mode:new int[]{0,1})for(int size:new int[]{720,2048}){
            if(!(hand+"-"+view+"-"+(mode==0?"open":"grasp")).matches(System.getProperty("videostudio.handcel.filter",".*")))continue;
            JSONObject s=settings("cel",hand,view);Bitmap b=render(s,pose(mode),size==720?1280:2048,size);try{save(b,new File(root,hand+"-"+view+"-"+(mode==0?"open":"grasp")+(size==720?"":"-large")+".png"));}finally{b.recycle();}
        }
        for(int size:new int[]{720,2048}){Bitmap b=render(settings(null,"right","front"),pose(1),size==720?1280:2048,size);try{save(b,new File(root,"legacy-right-front-grasp"+(size==720?"":"-large")+".png"));}finally{b.recycle();}}
    }

    static JSONObject settings(String style,String hand,String view)throws Exception {JSONObject s=SketchHandAnimation.defaults().put("view",view).put("handedness",hand);if(style==null)s.remove("renderStyle");else s.put("renderStyle",style);return s;}
    private static int visibleOwner(JSONObject s,double[] p,int x,int y)throws Exception {
        java.lang.reflect.Method method=SketchHandGeometry.class.getDeclaredMethod("celSurfaces",JSONObject.class,double[].class);method.setAccessible(true);
        @SuppressWarnings("unchecked") List<SketchHandGeometry.Face> faces=(List<SketchHandGeometry.Face>)method.invoke(null,s,p);
        faces.sort((a,b)->Double.compare(Arrays.stream(b.p).mapToDouble(v->v.z).average().getAsDouble(),Arrays.stream(a.p).mapToDouble(v->v.z).average().getAsDouble()));
        Path probe=new Path();probe.addCircle(x+.5f,y+.5f,.01f,Path.Direction.CW);
        for(SketchHandGeometry.Face f:faces)if(f.material==0&&f.p[1].sub(f.p[0]).cross(f.p[2].sub(f.p[0])).z>0){Path path=new Path();for(int i=0;i<f.p.length;i++){float px=(float)(640+112*f.p[i].x),py=(float)(396+112*f.p[i].y);if(i==0)path.moveTo(px,py);else path.lineTo(px,py);}path.close();assertTrue(path.op(probe,Path.Op.INTERSECT));if(!path.isEmpty())return f.owner;}return -1;
    }
    static double[] pose(int mode){return mode==0?new double[]{0,0,0,0,0,0,.5,0,0}:new double[]{.9,.9,.9,.9,.63,.675,.25,5,0};}
    private static JSONObject rig(JSONObject settings,double[] p)throws Exception {JSONObject pose=new JSONObject().put("frame",0);for(int i=0;i<p.length;i++)pose.put(SketchHandAnimation.CHANNELS[i],p[i]);return SketchEditOps.completeObject(new JSONObject().put("id","hand_rig").put("kind","hand").put("width",.0012).put("frame",0).put("exposure",1).put("hand",new JSONObject().put("settings",settings).put("poses",new JSONArray().put(pose)).put("durationFrames",96).put("fps",24)));}
    private static JSONObject frameDocument(JSONObject s,double[] p,int w,int h)throws Exception {JSONObject doc=SketchDocument.create(new JSONObject().put("sketchId","hand_cel_fixture").put("width",w).put("height",h).put("frameCount",1).put("fps",24).put("background","#FFF4E4D1"));doc.getJSONArray("layers").getJSONObject(0).put("objects",SketchHandAnimation.vectorFrame(rig(s,p),w,h,0));return doc;}
    private static Bitmap render(JSONObject s,double[] p,int w,int h)throws Exception {Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.eraseColor(BACKGROUND);SketchHandAnimation.draw(new Canvas(b),rig(s,p),w,h,0,1);return b;}
    private static void save(Bitmap b,File f)throws Exception {assertTrue(f.getParentFile().isDirectory()||f.getParentFile().mkdirs());try(FileOutputStream out=new FileOutputStream(f)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}}
    private static Path nails(List<SketchHandGeometry.Ink> ink,JSONObject s)throws Exception {Path p=new Path();for(SketchHandGeometry.Ink mark:ink)if(mark.fill&&mark.color.equals(s.getString("nailColor")))assertTrue(p.op(mark.path,Path.Op.UNION));return p;}
    private static List<Path> contours(Path p){List<Path> out=new ArrayList<>();float[] a=p.approximate(.02f);Path path=null;for(int i=0;i<a.length;i+=3){if(i==0||a[i]==a[i-3]){if(path!=null){path.close();out.add(path);}path=new Path();path.moveTo(a[i+1],a[i+2]);}else path.lineTo(a[i+1],a[i+2]);}if(path!=null){path.close();out.add(path);}return out;}
    private static void inside(Path child,Path parent,String reason){Path outside=new Path();assertTrue(outside.op(child,parent,Path.Op.DIFFERENCE));RectF b=new RectF();outside.computeBounds(b,true);assertTrue(reason+" outside="+b,outside.isEmpty());}
    private static String inkHash(List<SketchHandGeometry.Ink> ink)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);for(SketchHandGeometry.Ink mark:ink){out.writeUTF(mark.color);out.writeBoolean(mark.fill);out.writeUTF(mark.gradient==null?"":SketchDocument.canonical(mark.gradient));float[] p=mark.path.approximate(.01f);out.writeInt(p.length);for(float f:p)out.writeFloat(f);}return hash(bytes.toByteArray());}
    private static String pixelHash(Bitmap b)throws Exception {int[] p=new int[b.getWidth()*b.getHeight()];b.getPixels(p,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());ByteBuffer bytes=ByteBuffer.allocate(p.length*4);for(int n:p)bytes.putInt(n);return hash(bytes.array());}
    private static String hash(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
