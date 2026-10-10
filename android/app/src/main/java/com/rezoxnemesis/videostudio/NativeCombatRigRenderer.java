package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import java.util.Random;

/**
 * Cinematic fixed-world renderer. The apocalyptic background is drawn ONCE and
 * reused byte-for-byte for every generated frame. Two independent IK skeletons,
 * swords, recoil, and contact-local particle effects are drawn at each unique
 * timestamp. No whole-image zoom, still-image tween, or camera shake.
 */
public final class NativeCombatRigRenderer {
    private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path=new Path();
    private final int width,height;
    private final Bitmap world;
    private static final int DARK=Color.rgb(16,17,23);
    private static final int SUN=Color.rgb(255,141,53);

    public NativeCombatRigRenderer(int width,int height) {
        if(width<320||height<540||width>720||height>1280
                ||width%2!=0||height%2!=0) throw new IllegalArgumentException("Unsupported native rig canvas size");
        this.width=width;this.height=height;
        world=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(world);
        drawWorld(c);
    }

    public Bitmap render(CombatRigSolver.Frame frame,double seconds,double durationSeconds){
        Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(image);
        resetPaint();
        canvas.drawBitmap(world,0,0,brush);
        drawContactDust(canvas,frame);
        drawWarrior(canvas,frame.black,frame,seconds,durationSeconds);
        drawWarrior(canvas,frame.white,frame,seconds,durationSeconds);
        drawImpact(canvas,frame);
        return image;
    }

    public Bitmap staticBackgroundCopy(){return world.copy(Bitmap.Config.ARGB_8888,false);}
    public void recycle(){if(!world.isRecycled())world.recycle();}

    private int X(double x){return (int)Math.round(x*width);}
    private int Y(double y){return (int)Math.round(y*height);}
    private void resetPaint(){
        brush.reset();
        brush.setAntiAlias(true);
        brush.setFilterBitmap(true);
        brush.setStyle(Paint.Style.FILL);
    }
    private void fill(Canvas c,int color){
        resetPaint();brush.setColor(color);c.drawRect(0,0,width,height,brush);
    }
    private void poly(Canvas c,int color,float...points){
        resetPaint();brush.setColor(color);
        path.reset();
        path.moveTo(points[0],points[1]);
        for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);
        path.close();
        c.drawPath(path,brush);
    }
    private void line(Canvas c,double x0,double y0,double x1,double y1,int color,float size){
        resetPaint();brush.setColor(color);
        brush.setStrokeWidth(size);brush.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(X(x0),Y(y0),X(x1),Y(y1),brush);
    }
    private void circle(Canvas c,double x,double y,double r,int color){
        resetPaint();brush.setColor(color);c.drawCircle(X(x),Y(y),(float)(r*width),brush);
    }
    private void drawWorld(Canvas c){
        fill(c,Color.rgb(17,16,23));
        resetPaint();
        brush.setShader(new LinearGradient(0,0,0,Y(.76),new int[]{
                Color.rgb(16,18,27),Color.rgb(74,29,27),
                Color.rgb(208,67,34),Color.rgb(255,139,48),
                Color.rgb(82,40,35)},new float[]{0,.26f,.51f,.76f,1},Shader.TileMode.CLAMP));
        c.drawRect(0,0,width,Y(.85),brush);
        brush.setShader(null);
        brush.setShader(new RadialGradient(X(.69),Y(.51),Math.max(1,width*.33f),
                new int[]{0xffffd690,0x99f7954d,0x33f04c24,0x00e85019},
                new float[]{0,.13f,.42f,1},Shader.TileMode.CLAMP));
        c.drawCircle(X(.69),Y(.51),width*.34f,brush);
        brush.setShader(null);

        Random rng=new Random(745103);
        for(int i=0;i<50;i++){
            double x=rng.nextDouble(), y=.08+rng.nextDouble()*.47;
            double rx=.09+rng.nextDouble()*.20,ry=.026+rng.nextDouble()*.055;
            resetPaint();
            brush.setColor(Color.argb(40+rng.nextInt(45),30,20,29));
            c.drawOval((float)X(x-rx),(float)Y(y-ry),(float)X(x+rx),(float)Y(y+ry),brush);
        }
        for(int layer=0;layer<3;layer++){
            for(int j=0;j<19;j++){
                double depth=layer/2.0;
                double x=(j+.15+rng.nextDouble()*.5)/19.0;
                double buildingWidth=(.013+rng.nextDouble()*.029)*(1+depth*.4);
                double top=.36+rng.nextDouble()*.21-depth*.09;
                double bottom=.75;
                int color=layer==0?0x88463536:layer==1?0xcc27252a:0xff15161b;
                drawBuilding(c,x,top,buildingWidth,bottom,color,rng);
            }
        }
        // Static broken elevated bridge, held in place across all frames.
        poly(c,0xff292326, X(0),Y(.38),X(.17),Y(.45),X(.46),Y(.57),
                X(.67),Y(.62),X(.69),Y(.636),X(.44),Y(.595),X(.16),Y(.48),X(0),Y(.42));
        for(int j=0;j<13;j++){
            double x=j*.047;
            double y=.40+x*.33;
            line(c,x,y,x+.004,.77,0x9933242a,width*.008f);
        }
        // Ground and reflection layer — built once, never animated.
        resetPaint();
        brush.setShader(new LinearGradient(0,Y(.74),0,height,
                new int[]{0xff271d21,0xff271c22,0xff12151c},
                null,Shader.TileMode.CLAMP));
        c.drawRect(0,Y(.74),width,height,brush);
        brush.setShader(null);

        for(int i=0;i<360;i++){
            double x=rng.nextDouble(),y=.744+rng.nextDouble()*.256;
            double perspective=(y-.744)/.256;
            double length=.006+rng.nextDouble()*(.07*perspective+.012);
            int color=i%5==0?0x66f0a056:i%4==0?0x554a4348:0x66322f32;
            line(c,x,y,Math.min(1,x+length),y,color,
                    Math.max(1,width*.002f));
        }
        for(int i=0;i<145;i++){
            double x=rng.nextDouble(),y=.72+rng.nextDouble()*.28;
            double r=.0015+rng.nextDouble()*.006*((y-.72)/.28+ .25);
            circle(c,x,y,r,(i%5==0)?0xff59413b:0xff28272a);
        }
        // No caption, watermark, decorative border, or collage separators.
    }
    private void drawBuilding(Canvas c,double x,double top,double w,double bottom,int color,Random rng){
        double left=x-w*.5;
        poly(c,color,X(left),Y(bottom),X(left),Y(top+.03),
                X(left+w*.16),Y(top),X(left+w*.33),Y(top+.015),
                X(left+w*.58),Y(top-.012),X(left+w*.75),Y(top+.007),
                X(left+w),Y(top+.038),X(left+w),Y(bottom));
        int window=(color==0xff15161b)?0xff3f312d:0x77452929;
        for(int row=0;row<9;row++){
            double y=top+.08+row*.031;
            if(y>=bottom-.02)break;
            for(int col=0;col<3;col++){
                if(rng.nextDouble()>.62)continue;
                double xx=left+w*(.14+col*.28);
                line(c,xx,y,xx+w*.07,y,window,Math.max(1,width*.0015f));
            }
        }
    }

    private void drawWarrior(Canvas c,CombatRigSolver.Warrior w,
                             CombatRigSolver.Frame f,double sec,double duration) {
        int main=w.black?0xff070b0f:0xfff3efe6;
        int rim=w.black?0xffbb572e:0xfff9b783;
        float thickness=width*(w.black?.0175f:.0165f);
        // Ground shadow changes only beneath the moving warrior.
        resetPaint();brush.setColor(0x690c0b11);
        c.drawOval(X(w.pelvis.x-.091),Y(.844),X(w.pelvis.x+.091),Y(.858),brush);

        jointLine(c,w.trailingHip,w.trailingKnee,main,rim,thickness);
        jointLine(c,w.trailingKnee,w.trailingFoot,main,rim,thickness);
        jointLine(c,w.leadingHip,w.leadingKnee,main,rim,thickness);
        jointLine(c,w.leadingKnee,w.leadingFoot,main,rim,thickness);
        line(c,w.leadingFoot.x-.018,w.leadingFoot.y,w.leadingFoot.x+.018,
                w.leadingFoot.y,main,thickness*.64f);
        line(c,w.trailingFoot.x-.018,w.trailingFoot.y,w.trailingFoot.x+.018,
                w.trailingFoot.y,main,thickness*.64f);
        jointLine(c,w.pelvis,w.chest,main,rim,thickness*1.55f);
        jointLine(c,w.chest,w.neck,main,rim,thickness*1.3f);
        jointLine(c,w.freeShoulder,w.freeElbow,main,rim,thickness);
        jointLine(c,w.freeElbow,w.freeHand,main,rim,thickness);
        jointLine(c,w.swordShoulder,w.swordElbow,main,rim,thickness*1.15f);
        jointLine(c,w.swordElbow,w.swordHand,main,rim,thickness);
        circle(c,w.freeHand.x,w.freeHand.y,.011,main);
        circle(c,w.swordHand.x,w.swordHand.y,.011,main);
        // Individual head, rim glow, and real pose-derived tilt.
        circle(c,w.head.x+.002,w.head.y,.029,rim);
        circle(c,w.head.x,w.head.y,.028,main);
        drawSword(c,w,f,sec,duration);
    }

    private void jointLine(Canvas c,CombatRigSolver.Vec a,CombatRigSolver.Vec b,
                           int core,int edge,float thickness){
        line(c,a.x+.003,a.y,b.x+.003,b.y,edge,thickness*1.12f);
        line(c,a.x,a.y,b.x,b.y,core,thickness);
    }
    private void drawSword(Canvas c,CombatRigSolver.Warrior w,
                           CombatRigSolver.Frame f,double seconds,double duration){
        int metal=w.black?0xffd3c7b8:0xfff8f6f0;
        int edge=w.black?0xffaeb6ba:0xffefe2ca;
        // Subtle local motion trails only at actual high blade velocity.
        double dt=Math.min(.022,duration/90.0);
        CombatRigSolver.Warrior old=w.black?
                CombatRigSolver.at(Math.max(0,seconds-dt),duration).black:
                CombatRigSolver.at(Math.max(0,seconds-dt),duration).white;
        if(old.swordTip.distance(w.swordTip)>.008) {
            line(c,old.swordHand.x,old.swordHand.y,old.swordTip.x,old.swordTip.y,
                    w.black?0x338b9eb1:0x33fff2d2,width*.007f);
        }
        line(c,w.swordHand.x,w.swordHand.y,w.swordTip.x,w.swordTip.y,edge,width*.010f);
        line(c,w.swordHand.x,w.swordHand.y,w.swordTip.x,w.swordTip.y,metal,width*.0045f);
        double dx=w.swordTip.x-w.swordHand.x,dy=w.swordTip.y-w.swordHand.y;
        double norm=Math.max(.001,Math.hypot(dx,dy));
        double px=-dy/norm,py=dx/norm;
        line(c,w.swordHand.x-px*.022,w.swordHand.y-py*.022,
                w.swordHand.x+px*.022,w.swordHand.y+py*.022,
                w.black?0xff81512f:0xffc3a77d,width*.010f);
    }
    private void drawImpact(Canvas c,CombatRigSolver.Frame f){
        double amount=f.impactIntensity;
        if(amount<.065) return;
        double x=(f.black.swordTip.x+f.white.swordTip.x)*.5;
        double y=(f.black.swordTip.y+f.white.swordTip.y)*.5;
        Random r=new Random(93834);
        for(int i=0;i<32;i++){
            double a=(i+r.nextDouble()*.25)*Math.PI*2/32.0;
            double reach=amount*(.018+r.nextDouble()*.065);
            double xx=x+Math.cos(a)*reach;
            double yy=y+Math.sin(a)*reach;
            line(c,x,y,xx,yy,0xfff9b460,(float)(1+r.nextDouble()*width*.005));
        }
        circle(c,x,y,.014*amount,0xfffff1c5);
    }
    private void drawContactDust(Canvas c,CombatRigSolver.Frame f){
        double dust=f.dustIntensity;
        Random r=new Random(348791);
        for(int i=0;i<54;i++){
            boolean black=i%2==0;
            CombatRigSolver.Warrior actor=black?f.black:f.white;
            double center=actor.leadingFoot.x,ground=.838;
            double spread=(r.nextDouble()-.5)*.15*dust;
            double height=Math.sin(r.nextDouble()*Math.PI)*.045*dust;
            double radius=.001+r.nextDouble()*.0035*dust;
            circle(c,center+spread,ground-height,radius,0x995a504a);
        }
    }
}
