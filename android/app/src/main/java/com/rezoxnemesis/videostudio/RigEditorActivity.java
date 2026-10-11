package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Mobile-first, touch-only manual rig authoring. No automatic Gallery browse,
 * no implicit human-pose estimate and no PC or Android Studio required.
 *
 * One-time per character: tap skeleton landmarks, then draw an actor cutout.
 * The native 2D animator reuses this rig in any compatible action sequence.
 */
public final class RigEditorActivity extends Activity {
    private static final int BACKGROUND=0xff090d16,ACCENT=0xff58d9f2;
    private ProjectStore projectStore;
    private Rig2DProjectStore rigStore;
    private ProjectStore.Project project;
    private ProjectStore.Asset selected;
    private Bitmap picture;
    private RigCanvasView drawing;
    private TextView instructions,sourceName;
    private boolean black=true,outlineMode=false,transparent=false;
    private final List<float[]> joints=new ArrayList<>();
    private final List<float[]> outline=new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        projectStore=new ProjectStore(this);
        rigStore=new Rig2DProjectStore(this,projectStore);
        String projectId=getIntent().getStringExtra("projectId");
        project=projectStore.get(projectId);
        if(project==null){finish();return;}
        String assetId=getIntent().getStringExtra("assetId");
        selected=project.asset(assetId==null?"":assetId);
        if(selected==null||!selected.mime.startsWith("image/")) {
            for(ProjectStore.Asset a:project.assets)
                if(a.mime!=null&&a.mime.startsWith("image/")){selected=a;break;}
        }
        if(selected==null) {
            Toast.makeText(this,"Import a source image into VideoStudio first",
                    Toast.LENGTH_LONG).show();
            finish();return;
        }
        setupUi();
        selectImage(selected);
    }

    private Button button(String text,Runnable action){
        Button b=new Button(this);
        b.setText(text);b.setTextSize(12);b.setTextColor(Color.WHITE);b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff253047));
        b.setOnClickListener(v->action.run());
        return b;
    }
    private TextView label(String text,int size){
        TextView v=new TextView(this);
        v.setText(text);v.setTextSize(size);v.setTextColor(Color.WHITE);
        v.setPadding(14,10,14,7);return v;
    }
    private void setupUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BACKGROUND);
        TextView title=label("2D Character Rig Builder",21);
        root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView help=label("Tap all 16 joints on ONE actor, then trace its body outline. Do not include the sword in the outline. Save and repeat for the other fighter.",12);
        help.setTextColor(0xffadbacf);root.addView(help);
        sourceName=label("",13);
        sourceName.setTextColor(ACCENT);root.addView(sourceName);

        LinearLayout selectors=new LinearLayout(this);
        selectors.addView(button("Choose image",this::chooseImage),new LinearLayout.LayoutParams(0,48,1));
        selectors.addView(button("Black",()->resetActor(true)),new LinearLayout.LayoutParams(0,48,1));
        selectors.addView(button("White",()->resetActor(false)),new LinearLayout.LayoutParams(0,48,1));
        root.addView(selectors,new LinearLayout.LayoutParams(-1,48));
        instructions=label("",13);instructions.setTextColor(ACCENT);
        root.addView(instructions);
        drawing=new RigCanvasView();
        root.addView(drawing,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout row1=new LinearLayout(this);
        row1.addView(button("Tap joints",()->{
            outlineMode=false;updateInstruction();drawing.invalidate();
        }),new LinearLayout.LayoutParams(0,50,1));
        row1.addView(button("Trace outline",()->{
            outlineMode=true;updateInstruction();drawing.invalidate();
        }),new LinearLayout.LayoutParams(0,50,1));
        row1.addView(button("Undo",()->{
            List<float[]> list=outlineMode?outline:joints;
            if(!list.isEmpty())list.remove(list.size()-1);
            updateInstruction();drawing.invalidate();
        }),new LinearLayout.LayoutParams(0,50,1));
        root.addView(row1);
        LinearLayout row2=new LinearLayout(this);
        row2.addView(button("Transparent PNG",()->{
            transparent=!transparent;updateInstruction();
            Toast.makeText(this,transparent
                ?"Already-transparent character image selected. A mask is optional."
                :"Trace the actor outline after marking joints.",Toast.LENGTH_SHORT).show();
        }),new LinearLayout.LayoutParams(0,50,1));
        row2.addView(button("Save Actor Rig",this::saveRig),new LinearLayout.LayoutParams(0,50,1));
        row2.addView(button("Done",this::finish),new LinearLayout.LayoutParams(0,50,1));
        root.addView(row2);
        setContentView(root);
        updateInstruction();
    }

    private void chooseImage(){
        List<ProjectStore.Asset> choices=new ArrayList<>();
        for(ProjectStore.Asset a:project.assets)
            if(a.mime!=null&&a.mime.startsWith("image/"))choices.add(a);
        String[] labels=new String[choices.size()];
        for(int i=0;i<choices.size();i++)labels[i]=choices.get(i).name;
        new AlertDialog.Builder(this).setTitle("VideoStudio imported source images")
                .setItems(labels,(dialog,index)->selectImage(choices.get(index)))
                .setNegativeButton("Cancel",null).show();
    }
    private void selectImage(ProjectStore.Asset a){
        if(a==null)return;
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();
            bounds.inJustDecodeBounds=true;
            Uri uri=Uri.parse(a.uri);
            try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new IllegalStateException("Image not found");
                BitmapFactory.decodeStream(in,null,bounds);
            }
            if(bounds.outWidth<1||bounds.outHeight<1||
                    bounds.outWidth>8192||bounds.outHeight>8192)
                throw new IllegalArgumentException("Unsupported image dimensions");
            BitmapFactory.Options opts=new BitmapFactory.Options();
            opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
            opts.inSampleSize=1;
            while(bounds.outWidth/opts.inSampleSize>960||
                    bounds.outHeight/opts.inSampleSize>1720)
                opts.inSampleSize*=2;
            Bitmap b;
            try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new IllegalStateException("Source disappeared");
                b=BitmapFactory.decodeStream(in,null,opts);
            }
            if(b==null)throw new IllegalStateException("Could not decode source image");
            if(picture!=null&&!picture.isRecycled())picture.recycle();
            picture=b;selected=a;
            joints.clear();outline.clear();outlineMode=false;transparent=false;
            sourceName.setText("Source: "+a.name);
            drawing.invalidate();updateInstruction();
        }catch(Exception error){
            Toast.makeText(this,"Image unavailable: "+error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
    private void resetActor(boolean isBlack) {
        black=isBlack;joints.clear();outline.clear();
        outlineMode=false;transparent=false;
        updateInstruction();drawing.invalidate();
    }
    private void updateInstruction(){
        String actor=black?"BLACK":"WHITE";
        String instruction;
        if(outlineMode){
            instruction="Mask: tap around "+actor+"'s body ("
                    +outline.size()+" points). Leave out the sword.";
        }else if(joints.size()<Rig2DProfile.JOINTS.length){
            instruction=actor+" • Joint "+(joints.size()+1)+"/16: "
                    +Rig2DProfile.JOINTS[joints.size()];
        }else{
            instruction=actor+" • All joints marked. Tap Trace outline, "+
                    "or enable Transparent PNG, then Save.";
        }
        if(transparent)instruction+=" [transparent source]";
        instructions.setText(instruction);
    }
    private void saveRig(){
        if(joints.size()!=Rig2DProfile.JOINTS.length||
                (!transparent&&outline.size()<3)){
            Toast.makeText(this,"Mark all joints and the actor outline first",
                    Toast.LENGTH_LONG).show();return;
        }
        try{
            float[][] bonePoints=joints.toArray(new float[0][2]);
            float[][] polygon=outline.toArray(new float[0][2]);
            Rig2DProfile profile=Rig2DProfile.create(
                    project.id,selected.id,black,transparent,bonePoints,polygon);
            rigStore.save(profile);
            Toast.makeText(this,(black?"Black":"White")+" rig saved! You can reuse it.",
                    Toast.LENGTH_LONG).show();
            new AlertDialog.Builder(this).setTitle("Reusable actor saved")
                .setMessage("This character rig is stored inside the VideoStudio project "+
                        "and included in Drive archive. Mark the other fighter or return to Tools "+
                        "to render a source-textured 2D duel.")
                .setPositiveButton("Other fighter",(dialog,which)->resetActor(!black))
                .setNegativeButton("Done",(dialog,which)->finish()).show();
        }catch(Exception error){
            Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onDestroy(){
        super.onDestroy();
        if(picture!=null&&!picture.isRecycled())picture.recycle();
    }

    private final class RigCanvasView extends View {
        private final Paint imagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final Paint overlay=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dest=new RectF();
        RigCanvasView(){super(RigEditorActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);c.drawColor(0xff080b12);
            if(picture==null)return;
            float scale=Math.min(getWidth()/(float)picture.getWidth(),
                    getHeight()/(float)picture.getHeight());
            float w=picture.getWidth()*scale,h=picture.getHeight()*scale;
            float left=(getWidth()-w)*.5f,top=(getHeight()-h)*.5f;
            dest.set(left,top,left+w,top+h);
            c.drawBitmap(picture,null,dest,imagePaint);
            overlay.setStrokeWidth(2.5f);
            overlay.setStyle(Paint.Style.STROKE);
            overlay.setColor(black?0xffffa665:0xff53d9ff);
            for(int[] b:Rig2DProfile.BONES){
                if(b[0]>=joints.size()||b[1]>=joints.size())continue;
                float[] a=joints.get(b[0]),d=joints.get(b[1]);
                c.drawLine(left+a[0]*w,top+a[1]*h,left+d[0]*w,top+d[1]*h,overlay);
            }
            overlay.setStyle(Paint.Style.FILL);
            for(int i=0;i<joints.size();i++){
                float[] point=joints.get(i);
                float x=left+point[0]*w,y=top+point[1]*h;
                c.drawCircle(x,y,6,overlay);
                overlay.setTextSize(12);
                c.drawText(String.valueOf(i+1),x+8,y-7,overlay);
            }
            if(!outline.isEmpty()){
                Path shape=new Path();
                for(int i=0;i<outline.size();i++){
                    float[] p=outline.get(i);
                    float x=left+p[0]*w,y=top+p[1]*h;
                    if(i==0)shape.moveTo(x,y);else shape.lineTo(x,y);
                }
                if(outline.size()>=3)shape.close();
                overlay.setStyle(Paint.Style.FILL);
                overlay.setColor(black?0x44ff9754:0x4454aaff);
                c.drawPath(shape,overlay);
                overlay.setStyle(Paint.Style.STROKE);
                overlay.setStrokeWidth(2.5f);
                overlay.setColor(black?0xffffa665:0xff58ddff);
                c.drawPath(shape,overlay);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getAction()!=MotionEvent.ACTION_DOWN)return true;
            if(picture==null||!dest.contains(event.getX(),event.getY()))return true;
            float x=Math.max(0,Math.min(1,(event.getX()-dest.left)/dest.width()));
            float y=Math.max(0,Math.min(1,(event.getY()-dest.top)/dest.height()));
            if(outlineMode){
                if(outline.size()<64)outline.add(new float[]{x,y});
            }else if(joints.size()<Rig2DProfile.JOINTS.length){
                joints.add(new float[]{x,y});
            }
            updateInstruction();invalidate();
            return true;
        }
    }
}
