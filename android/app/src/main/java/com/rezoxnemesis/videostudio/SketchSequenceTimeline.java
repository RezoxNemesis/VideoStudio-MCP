package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.util.Arrays;

/** Small integer timing index; never retains drawing documents or decoded bitmaps. */
final class SketchSequenceTimeline {
    private final int start;
    private final int[] firstFrame;
    SketchSequenceTimeline(JSONObject book,int start,int count) throws Exception {
        if(start<0||count<1||(long)start+count>book.getInt("pageCount"))throw SketchDocument.bad("Page range outside sequence");
        this.start=start;firstFrame=new int[count+1];JSONObject holds=book.optJSONObject("exposures");
        for(int i=0;i<count;i++){
            long next=(long)firstFrame[i]+SketchTimelineTools.exposure(holds,start+i);
            if(next>SketchSequenceLimits.MAX_FRAMES)throw SketchDocument.bad("Sequence exceeds 1,000,000 output frames; export a page range");
            firstFrame[i+1]=(int)next;
        }
    }
    int frames(){return firstFrame[firstFrame.length-1];}
    int firstFrame(int page){if(page<start||page>=start+firstFrame.length-1)throw SketchDocument.bad("Page outside sequence");return firstFrame[page-start];}
    int page(int frame){
        if(frame<0||frame>=frames())throw SketchDocument.bad("Frame outside sequence");
        int found=Arrays.binarySearch(firstFrame,frame);return start+(found>=0?found:-found-2);
    }
}
