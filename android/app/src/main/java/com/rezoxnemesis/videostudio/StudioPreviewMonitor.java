package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.ui.PlayerView;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persistent source/program monitor. A still image never waits for a render job. */
public final class StudioPreviewMonitor {
    private final Context context;
    private final LiveEditPlayer sourcePlayer;
    private final FrameLayout root;
    private final FrameLayout sourceHost;
    private final ImageView image;
    private final TextView status;
    private final PlayerView programView;
    private final CompositionPlayer program;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decode = Executors.newSingleThreadExecutor();
    private volatile long generation;
    private String boundSource = "";
    private String boundClip = "";
    private String programProject = "";
    private long programRevision = -1;
    private boolean programMode;
    private volatile boolean released;
    private String composingProject="";
    private long composingRevision=-1,pendingSeek,sourceRevision;
    private boolean pendingPlay;
    private Bitmap bitmap;

    public StudioPreviewMonitor(Context context, LiveEditPlayer sourcePlayer) {
        this.context=context;this.sourcePlayer=sourcePlayer;
        root=new FrameLayout(context);root.setBackgroundColor(Color.rgb(5,7,12));
        sourceHost=new FrameLayout(context);root.addView(sourceHost,fill());sourcePlayer.attach(sourceHost);
        program=new CompositionPlayer.Builder(context).build();programView=new PlayerView(context);
        programView.setPlayer(program);programView.setUseController(false);root.addView(programView,fill());programView.setVisibility(View.GONE);
        image=new ImageView(context);image.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(image,fill());image.setVisibility(View.GONE);
        status=new TextView(context);status.setTextColor(Color.rgb(190,202,220));status.setGravity(Gravity.CENTER);
        status.setPadding(24,24,24,24);status.setText("Import media to begin editing");root.addView(status,fill());
        program.addListener(new Player.Listener(){
            @Override public void onPlayerError(PlaybackException error){showError("Program preview: "+error.getMessage());}
            @Override public void onRenderedFirstFrame(){if(programMode)status.setVisibility(View.GONE);}
        });
        sourcePlayer.setErrorListener(this::showError);
    }
    private static FrameLayout.LayoutParams fill(){return new FrameLayout.LayoutParams(-1,-1);}
    public void attach(ViewGroup host){
        if(root.getParent() instanceof ViewGroup)((ViewGroup)root.getParent()).removeView(root);
        host.addView(root,new ViewGroup.LayoutParams(-1,-1));
    }
    public static String sourceKind(ProjectStore.Asset asset){
        if(asset==null || asset.mime==null)return "missing";
        if(asset.mime.startsWith("image/"))return "image";
        if(asset.mime.startsWith("video/"))return "video";
        if(asset.mime.startsWith("audio/"))return "audio";
        return "unsupported";
    }
    public void showSource(ProjectStore.Project p, ProjectStore.Asset asset, ProjectStore.Clip clip, long sourceMs, boolean play){
        if(released)return;
        program.pause();programMode=false;sourceRevision=p.revision;composingRevision=-1;programView.setVisibility(View.GONE);sourceHost.setVisibility(View.VISIBLE);
        if(asset==null){showError("This clip's source media is missing. Relink it in Media.");return;}
        String kind=sourceKind(asset), clipId=clip==null ? "" : clip.id;
        String uri=ProxyManager.previewUri(p,asset);
        if("image".equals(kind)){
            sourcePlayer.pause();image.setVisibility(View.VISIBLE);sourceHost.setVisibility(View.GONE);
            if(uri.equals(boundSource) && clipId.equals(boundClip) && bitmap!=null){status.setVisibility(View.GONE);return;}
            boundSource=uri;boundClip=clipId;long request=++generation;
            status.setText("Loading image…");status.setVisibility(View.VISIBLE);
            decode.execute(()->{
                try{
                    Bitmap result=decodeImage(context,Uri.parse(uri),1600);
                    main.post(()->{
                        if(released || generation!=request){if(result!=null)result.recycle();return;}
                        if(result==null){showError("This image could not be decoded. Check its format and storage access.");return;}
                        Bitmap old=bitmap;bitmap=result;image.setImageBitmap(result);status.setVisibility(View.GONE);
                        if(old!=null && old!=result)old.recycle();
                    });
                }catch(Exception error){main.post(()->{if(!released && generation==request)showError("Image unavailable: "+error.getMessage());});}
            });
        }else if("video".equals(kind) || "audio".equals(kind)){
            generation++;image.setVisibility(View.GONE);status.setVisibility("audio".equals(kind)?View.VISIBLE:View.GONE);
            if("audio".equals(kind))status.setText("♫  "+asset.name+"\nAudio source");
            if(uri.equals(boundSource) && clipId.equals(boundClip) && sourcePlayer.hasMedia()){
                sourcePlayer.seekTo(sourceMs);sourcePlayer.setPlaying(play);return;
            }
            boundSource=uri;boundClip=clipId;sourcePlayer.setContextIds("",clipId);
            sourcePlayer.play(Uri.parse(uri),sourceMs,clip==null?1:clip.speed,play);
        }else showError("This asset needs a compatible preview decoder: "+asset.mime);
    }
    public void showProgram(ProjectStore.Project p,long timeMs,boolean play){
        if(released)return;
        sourcePlayer.pause();image.setVisibility(View.GONE);sourceHost.setVisibility(View.GONE);
        programView.setVisibility(View.VISIBLE);programMode=true;
        pendingSeek=Math.max(0,timeMs);pendingPlay=play;
        if(p.id.equals(programProject)&&p.revision==programRevision){generation++;status.setVisibility(View.GONE);program.seekTo(pendingSeek);program.setPlayWhenReady(play);return;}
        if(p.id.equals(composingProject)&&p.revision==composingRevision)return;
        long request=++generation;composingProject=p.id;composingRevision=p.revision;
        ProjectStore.Project snapshot=ProjectStore.Project.fromJson(p.toJson());
        program.pause();status.setText("Preparing program preview · r"+p.revision);status.setVisibility(View.VISIBLE);
        decode.execute(()->{
            if(released||generation!=request)return;
            try{
                androidx.media3.transformer.Composition composition=new TimelineCompositionFactory(context).build(snapshot,snapshot.settings.optString("aspect","16:9"),"540p",true);
                main.post(()->{
                    if(released||generation!=request||!programMode)return;
                    try{program.setComposition(composition);programProject=snapshot.id;programRevision=snapshot.revision;composingRevision=-1;
                        program.prepare();program.seekTo(pendingSeek);program.setPlayWhenReady(pendingPlay);
                    }catch(Exception error){composingRevision=-1;showError("Program preview could not start: "+error.getMessage());}
                });
            }catch(Exception error){main.post(()->{if(!released&&generation==request){composingRevision=-1;showError("Program preview could not start: "+error.getMessage());}});}
        });
    }
    public long shownRevision(){return programMode?programRevision:sourceRevision;}
    public boolean isProgram(){return programMode;}
    public boolean isPlaying(){return programMode?program.isPlaying():sourcePlayer.isPlaying();}
    public long position(){return programMode?program.getCurrentPosition():sourcePlayer.currentPositionMs();}
    public void pause(){pendingPlay=false;program.pause();sourcePlayer.pause();}
    public boolean isPreparingToPlay(){return programMode&&composingRevision>=0&&pendingPlay;}
    public void setPlaying(boolean playing){if(programMode)program.setPlayWhenReady(playing);else sourcePlayer.setPlaying(playing);}
    public void seek(long timeMs){if(programMode)program.seekTo(Math.max(0,timeMs));else sourcePlayer.seekTo(timeMs);}
    public void showError(String detail){status.setText(detail);status.setVisibility(View.VISIBLE);}
    public void release(){released=true;generation++;decode.shutdownNow();programView.setPlayer(null);program.release();image.setImageDrawable(null);if(bitmap!=null)bitmap.recycle();}

    public static Bitmap decodeImage(Context context,Uri uri,int maxDimension) throws Exception{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){if(in==null)return null;BitmapFactory.decodeStream(in,null,bounds);}
        if(bounds.outWidth<=0 || bounds.outHeight<=0)return null;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>Math.max(64,maxDimension))options.inSampleSize*=2;
        Bitmap decoded;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){if(in==null)return null;decoded=BitmapFactory.decodeStream(in,null,options);}
        if(decoded==null)return null;
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in!=null)orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
        }catch(Exception ignored){ }
        Matrix matrix=new Matrix();
        if(orientation==ExifInterface.ORIENTATION_ROTATE_90)matrix.postRotate(90);
        else if(orientation==ExifInterface.ORIENTATION_ROTATE_180)matrix.postRotate(180);
        else if(orientation==ExifInterface.ORIENTATION_ROTATE_270)matrix.postRotate(270);
        else if(orientation==ExifInterface.ORIENTATION_FLIP_HORIZONTAL)matrix.postScale(-1,1);
        else if(orientation==ExifInterface.ORIENTATION_FLIP_VERTICAL)matrix.postScale(1,-1);
        else if(orientation==ExifInterface.ORIENTATION_TRANSPOSE){matrix.postScale(-1,1);matrix.postRotate(90);}
        else if(orientation==ExifInterface.ORIENTATION_TRANSVERSE){matrix.postScale(-1,1);matrix.postRotate(270);}
        if(!matrix.isIdentity()){
            Bitmap oriented=Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),matrix,true);
            if(oriented!=decoded)decoded.recycle();decoded=oriented;
        }
        return decoded;
    }
}
