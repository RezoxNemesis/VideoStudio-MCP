package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Native P01/P02 Earth implementation: provenance-check private imported NASA maps,
 * then render actual inverse-projected sphere proofs into VideoStudio's Media Bin.
 *
 * The 40-second encoder is deliberately NOT implemented here. A true textured
 * globe must pass these source/geography/terminator proofs first. No Gallery scan.
 */
public final class EarthNativeProof {
    private static final int CANVAS_W=1280, CANVAS_H=720;
    private static final int MAX_PREVIEW_W=640, MAX_PREVIEW_H=360;
    private static final long MAX_SOURCE_BYTES=28L*1024L*1024L;
    private static final String[] NAMES={
      "NASA_BlueMarble_200408_5400.jpg",
      "NASA_BlackMarble_2016_3600.jpg",
      "NASA_BlueMarble_Clouds_2048.jpg"
    };
    private static final int[][] DIMS={{5400,2700},{3600,1800},{2048,1024}};

    private EarthNativeProof() {}

    public static JSONObject execute(Context context,ProjectStore store,
                                     String action,JSONObject args)throws Exception {
        if (!"earth_assets_verify".equals(action)&&!"earth_projection_preview".equals(action))
            throw new IllegalArgumentException("Unsupported native Earth proof action");
        final String projectId=args.optString("projectId","");
        if (projectId.isEmpty())throw new IllegalArgumentException("Earth projectId required");
        ProjectStore.Project project=store.get(projectId);
        if (project==null)throw new IllegalArgumentException("Earth project not found");
        JSONObject[] files=new JSONObject[3];
        File[] owned=new File[3];
        JSONArray sources=new JSONArray();
        for(int i=0;i<3;i++){
            ProjectStore.Asset selected=null;
            for(ProjectStore.Asset asset:project.assets)if(NAMES[i].equals(asset.name)){
                if(selected!=null)throw new IllegalStateException("Ambiguous Earth source: "+NAMES[i]);
                selected=asset;
            }
            if(selected==null)throw new IllegalStateException("Missing imported Earth source: "+NAMES[i]);
            File file=ownedImport(context,selected);
            JSONObject report=probe(file,selected,DIMS[i]);
            sources.put(report);files[i]=report;owned[i]=file;
        }
        if ("earth_assets_verify".equals(action))return new JSONObject()
                .put("ok",true).put("projectId",projectId)
                .put("sources",sources).put("sourceCount",3)
                .put("allDecodedDimensionsMatch",true)
                .put("allSourcesSha256Recorded",true).put("galleryAccess",false)
                .put("cloudAlphaVerified",false)
                .put("note","Cloud mask needs independent pixel-level validation before full-film use");

        final int frame=args.getInt("frame");
        if(frame<0||frame>=1200)throw new IllegalArgumentException("Earth frame must be 0..1199");
        double cx=num(args,"cameraCx",0,CANVAS_W),cy=num(args,"cameraCy",0,CANVAS_H);
        double radius=num(args,"earthRadius",8,1600);
        double longitude=num(args,"centralLongitude",-720,720);
        double tilt=num(args,"axisTilt",-90,90);
        double sx=num(args,"sunViewX",-1,1),sy=num(args,"sunViewY",-1,1),sz=num(args,"sunViewZ",-1,1);
        double cloudShift=numDefault(args,"cloudOffset",0,-15,15);
        double cloudOpacity=numDefault(args,"cloudOpacity",.72,0,1);
        double nightWeight=numDefault(args,"nightStrength",.78,0,1);
        String pass=args.optString("pass","day");
        if(!"day".equals(pass)&&!"full".equals(pass))
            throw new IllegalArgumentException("Pass must be day or full; do not fake unverified clouds");
        int width=args.optInt("width",MAX_PREVIEW_W);
        if(width<256||width>MAX_PREVIEW_W||width%2!=0)
            throw new IllegalArgumentException("Preview width must be even and 256..640");
        int height=width*CANVAS_H/CANVAS_W;
        if(height<1||height>MAX_PREVIEW_H)
            throw new IllegalArgumentException("Preview aspect exceeds protected budget");

        Bitmap day=null,night=null,cloud=null;
        try{
            day=decode(owned[0],2);
            if("full".equals(pass)){night=decode(owned[1],2);cloud=decode(owned[2],1);}
            int[] surface=bitmapPixels(day);
            int[] lights=night==null?null:bitmapPixels(night);
            int[] weather=cloud==null?null:bitmapPixels(cloud);
            int[] output=new int[width*height];
            final double scale=CANVAS_W/(double)width;
            for(int y=0;y<height;y++){
                for(int x=0;x<width;x++){
                    final double px=(x+.5)*scale, py=(y+.5)*scale;
                    EarthSphereProjection.Point p=EarthSphereProjection.project(px,py,cx,cy,radius,tilt,longitude);
                    if(!p.visible){output[y*width+x]=starfield(x,y);continue;}
                    double cosine=EarthSphereProjection.sunCosine(p,sx,sy,sz);
                    double lit=Math.max(0,cosine), daylight=EarthSphereProjection.daylight(cosine);
                    int color=EarthSphereProjection.bilinear(surface,day.getWidth(),day.getHeight(),p.texU,p.texV);
                    double shade=.08+.92*Math.pow(lit,.55);
                    double rr=((color>>16)&255)*shade,gg=((color>>8)&255)*shade,bb=(color&255)*shade;
                    if(lights!=null){
                        int n=EarthSphereProjection.bilinear(lights,night.getWidth(),night.getHeight(),p.texU,p.texV);
                        double nightFactor=Math.pow(1-daylight,1.8)*nightWeight;
                        double nL=Math.max(0,(((n>>16)&255)+((n>>8)&255)+(n&255))/3.0-22);
                        // Night source contains a dark basemap: suppress unlit oceans.
                        if(nL>13){rr+=((n>>16)&255)*nightFactor*.9;gg+=((n>>8)&255)*nightFactor*.85;bb+=(n&255)*nightFactor*.8;}
                    }
                    if(weather!=null){
                        EarthSphereProjection.Point w=EarthSphereProjection.project(px,py,cx,cy,radius*1.012,tilt,longitude+cloudShift);
                        if(w.visible){
                            int wc=EarthSphereProjection.bilinear(weather,cloud.getWidth(),cloud.getHeight(),w.texU,w.texV);
                            int wr=(wc>>16)&255,wg=(wc>>8)&255,wb=wc&255;
                            double brightness=(wr+wg+wb)/3.0;
                            double chroma=(Math.max(wr,Math.max(wg,wb))-Math.min(wr,Math.min(wg,wb)))/255.0;
                            double alpha=EarthSphereProjection.clamp((brightness-38)/196.0,0,1)
                                    *EarthSphereProjection.clamp(1.3-chroma*1.7,0,1)
                                    *cloudOpacity*.75;
                            rr=rr*(1-alpha)+237*alpha;gg=gg*(1-alpha)+244*alpha;bb=bb*(1-alpha)+249*alpha;
                        }
                    }
                    double halo=Math.pow(1-p.viewZ,2.5)*.24;
                    rr=rr*(1-halo)+93*halo;gg=gg*(1-halo)+152*halo;bb=bb*(1-halo)+211*halo;
                    output[y*width+x]=0xff000000|(clampByte(rr)<<16)|(clampByte(gg)<<8)|clampByte(bb);
                }
            }
            Bitmap out=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            try{
                out.setPixels(output,0,width,0,0,width,height);
                File dir=new File(context.getFilesDir(),"earth-proofs"+File.separator+projectId);
                if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Earth proof directory unavailable");
                String filename=String.format(java.util.Locale.US,"Earth_%s_f%04d.png",pass,frame);
                File target=new File(dir,filename);
                File temp=File.createTempFile("earth-",".png",dir);
                try(FileOutputStream stream=new FileOutputStream(temp)){
                    if(!out.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IllegalStateException("Earth preview PNG encode failed");
                    stream.getFD().sync();
                }
                if(!temp.renameTo(target)){temp.delete();throw new IllegalStateException("Earth preview atomic publication failed");}
                ProjectStore.Asset asset=store.registerGeneratedAsset(project,Uri.fromFile(target),filename,"earth_projection_preview",false);
                return new JSONObject().put("ok",true).put("projectId",projectId)
                        .put("frame",frame).put("pass",pass).put("assetId",asset.id)
                        .put("width",width).put("height",height)
                        .put("sourceSha256Day",files[0].getString("sha256"))
                        .put("sourceSha256Night",files[1].getString("sha256"))
                        .put("sourceSha256Cloud",files[2].getString("sha256"))
                        .put("outputSha256",sha256(target))
                        .put("registeredInVideoStudioMediaBin",true)
                        .put("projection","orthographic-inverse-sphere-per-pixel")
                        .put("previewOnly",true).put("renderedOnDevice",true)
                        .put("galleryAccess",false).put("cloudMaskAcceptancePending",true);
            }finally{out.recycle();}
        }finally{
            if(day!=null)day.recycle();if(night!=null)night.recycle();if(cloud!=null)cloud.recycle();
        }
    }

    private static JSONObject probe(File f, ProjectStore.Asset a, int[] expected)throws Exception{
        if(!f.isFile()||f.length()<1024||f.length()>MAX_SOURCE_BYTES)
            throw new IllegalStateException("Earth source outside safe size budget");
        BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(f.getAbsolutePath(),b);
        if(b.outWidth!=expected[0]||b.outHeight!=expected[1])
            throw new IllegalStateException("Earth map dimension mismatch for "+a.name+": "+b.outWidth+"x"+b.outHeight);
        return new JSONObject().put("assetId",a.id).put("name",a.name)
                .put("mime",a.mime).put("width",b.outWidth).put("height",b.outHeight)
                .put("bytes",f.length()).put("sha256",sha256(f))
                .put("privateOwnedImport",true);
    }

    private static File ownedImport(Context context,ProjectStore.Asset asset)throws Exception{
        Uri uri=Uri.parse(asset.uri);
        if(!"file".equalsIgnoreCase(uri.getScheme())||uri.getPath()==null)
            throw new IllegalStateException("Earth maps must be VideoStudio-private imported files");
        File imports=new File(context.getFilesDir(),"imports").getCanonicalFile();
        File path=new File(uri.getPath()).getCanonicalFile();
        if(!path.getPath().startsWith(imports.getPath()+File.separator))
            throw new IllegalStateException("Earth asset outside app import boundary");
        return path;
    }
    private static String sha256(File f)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(f)){
            byte[] block=new byte[65536];int n;
            while((n=in.read(block))!=-1)hash.update(block,0,n);
        }
        StringBuilder s=new StringBuilder(64);
        for(byte b:hash.digest())s.append(String.format(java.util.Locale.US,"%02x",b&255));
        return s.toString();
    }
    private static double num(JSONObject args,String k,double min,double max)throws Exception{
        double value=args.getDouble(k);if(!Double.isFinite(value)||value<min||value>max)
            throw new IllegalArgumentException("Invalid Earth parameter: "+k);return value;
    }
    private static double numDefault(JSONObject args,String k,double defaultValue,double min,double max)throws Exception{
        double value=args.has(k)?args.getDouble(k):defaultValue;
        if(!Double.isFinite(value)||value<min||value>max)throw new IllegalArgumentException("Invalid Earth parameter: "+k);
        return value;
    }
    private static Bitmap decode(File f,int sample){
        BitmapFactory.Options opt=new BitmapFactory.Options();opt.inSampleSize=sample;
        opt.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap bitmap=BitmapFactory.decodeFile(f.getAbsolutePath(),opt);
        if(bitmap==null)throw new IllegalStateException("Earth source image could not decode");
        return bitmap;
    }
    private static int[] bitmapPixels(Bitmap b){int[] a=new int[b.getWidth()*b.getHeight()];b.getPixels(a,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());return a;}
    private static int clampByte(double v){return (int)EarthSphereProjection.clamp(Math.round(v),0,255);}
    private static int starfield(int x,int y){
        int h=(x*73856093)^(y*19349663)^0x4f1bbcdc;
        h^=h>>>13;h*=0x5bd1e995;h^=h>>>15;
        int base=((h&2047)==97)?126:((h&511)==91?49:4);
        return 0xff000000|(base<<16)|(base<<8)|Math.min(255,base+4);
    }
}
