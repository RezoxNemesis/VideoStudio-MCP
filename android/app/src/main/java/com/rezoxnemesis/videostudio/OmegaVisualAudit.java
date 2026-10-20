package com.rezoxnemesis.videostudio;

/**
 * OMEGA Phase 6 observable motion signal, not an artistic certification.
 * Separates pixel changes inside a user-declared subject region from changes
 * outside it; cannot by itself identify anatomy, parallax or true acting.
 */
final class OmegaVisualAudit {
    private OmegaVisualAudit() {}
    static final class Rect {
        final int left,top,right,bottom;
        Rect(int left,int top,int right,int bottom,int width,int height) {
            if(width<2||height<2||width>2048||height>2048||
               left<0||top<0||left>=right||top>=bottom||right>width||bottom>height)
                throw new IllegalArgumentException("Invalid subject region");
            this.left=left;this.top=top;this.right=right;this.bottom=bottom;
        }
    }
    static final class Evidence {
        final int subjectPixels,backgroundPixels,subjectChanged,backgroundChanged;
        Evidence(int subjectPixels,int backgroundPixels,int subjectChanged,int backgroundChanged) {
            this.subjectPixels=subjectPixels;this.backgroundPixels=backgroundPixels;
            this.subjectChanged=subjectChanged;this.backgroundChanged=backgroundChanged;
        }
        double subjectChangeRatio(){return subjectPixels==0?0:subjectChanged/(double)subjectPixels;}
        double backgroundChangeRatio(){return backgroundPixels==0?0:backgroundChanged/(double)backgroundPixels;}
        boolean backgroundDominated(){return backgroundPixels>0&&
            backgroundChangeRatio()>.15&&subjectChangeRatio()<.02;}
        boolean staticSubject(){return subjectChanged==0;}
    }
    static Evidence measure(int[] before,int[] after,int width,int height,Rect subject,int threshold){
        if(width<2||height<2||width>2048||height>2048||
           before==null||after==null||before.length!=width*height||
           after.length!=width*height||subject==null||
           subject.right>width||subject.bottom>height||threshold<0||threshold>765)
            throw new IllegalArgumentException("Invalid visual evidence input");
        int inside=0,outside=0,moveInside=0,moveOutside=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int i=y*width+x;
            int a=before[i],b=after[i];
            int delta=Math.abs(((a>>16)&255)-((b>>16)&255))
                +Math.abs(((a>>8)&255)-((b>>8)&255))
                +Math.abs((a&255)-(b&255));
            boolean isInside=x>=subject.left&&x<subject.right&&y>=subject.top&&y<subject.bottom;
            if(isInside){inside++;if(delta>threshold)moveInside++;}
            else{outside++;if(delta>threshold)moveOutside++;}
        }
        return new Evidence(inside,outside,moveInside,moveOutside);
    }
}