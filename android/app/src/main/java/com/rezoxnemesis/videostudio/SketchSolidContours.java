package com.rezoxnemesis.videostudio;

import org.json.*;
import java.util.*;

/** Shared mesh edges cancel inside a colour region; only its editable boundary is stored. */
final class SketchSolidContours {
    private SketchSolidContours(){}
    static JSONArray commands(double[][] projected,List<int[]> faces)throws Exception{
        LinkedHashMap<Long,int[]> boundary=new LinkedHashMap<>();
        for(int[] face:faces)for(int n=0;n<face.length;n++){
            int a=face[n],b=face[(n+1)%face.length];long key=((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);
            if(boundary.containsKey(key))boundary.remove(key);else boundary.put(key,new int[]{a,b});
        }
        LinkedHashMap<Integer,ArrayDeque<Integer>> outgoing=new LinkedHashMap<>();for(int[] edge:boundary.values())outgoing.computeIfAbsent(edge[0],k->new ArrayDeque<>()).add(edge[1]);
        JSONArray commands=new JSONArray();int remaining=boundary.size();
        while(remaining>0){
            int start=-1;for(Map.Entry<Integer,ArrayDeque<Integer>> entry:outgoing.entrySet())if(!entry.getValue().isEmpty()){start=entry.getKey();break;}
            if(start<0)throw SketchDocument.bad("Invalid native solid region boundary");int current=start;commands.put(point("M",projected[start]));
            do{
                ArrayDeque<Integer> next=outgoing.get(current);if(next==null||next.isEmpty())throw SketchDocument.bad("Open native solid region boundary");current=next.removeFirst();remaining--;
                if(current!=start)commands.put(point("L",projected[current]));
            }while(current!=start);
            commands.put(new JSONArray().put("Z"));
        }
        return commands;
    }
    private static JSONArray point(String command,double[] p)throws Exception{return new JSONArray().put(command).put(p[0]).put(p[1]);}
}
