package com.rezoxnemesis.videostudio;
import java.util.List;
import java.util.ArrayList;
public final class NarrationChunks {
    private NarrationChunks(){}
    /** Lossless UTF-16 chunks; Android's limit is measured in Java characters. */
    public static List<String> split(String text,int limit){
        if(text==null||text.trim().isEmpty())throw new IllegalArgumentException("Narration text is required");
        if(limit<2)throw new IllegalArgumentException("Speech chunk limit must allow a Unicode pair");
        ArrayList<String> result=new ArrayList<>();
        for(int start=0;start<text.length();){
            int end=(int)Math.min(text.length(),(long)start+limit);
            if(end<text.length()){
                if(Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;
                int boundary=-1;
                for(int i=end-1;i>=start;i--){
                    char c=text.charAt(i);
                    if(Character.isWhitespace(c)){boundary=i+1;break;}
                }
                if(boundary>start)end=boundary;
            }
            result.add(text.substring(start,end));start=end;
        }
        return result;
    }
}
