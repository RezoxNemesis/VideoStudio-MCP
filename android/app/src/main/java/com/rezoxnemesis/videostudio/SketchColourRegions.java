package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** Native colour quantisation and complete connected-region boundaries, including holes.
 * Produces editable geometry, never embeds the source pixels in the drawing. */
final class SketchColourRegions {
    private SketchColourRegions() {}
    static JSONArray objects(Bitmap bitmap,JSONObject bounds,Matrix mapping,int canvasWidth,int canvasHeight,
                             String prefix,int frame,int exposure,int levels,int availableObjects) throws Exception {
        int w=bitmap.getWidth(),h=bitmap.getHeight(),size=w*h,stride=w+1;
        int[] colours=new int[size],queue=new int[size],vertices=new int[size*4];boolean[] visited=new boolean[size];byte[] edges=new byte[(w+1)*(h+1)];
        bitmap.getPixels(colours,0,w,0,0,w,h);for(int n=0;n<size;n++){int c=colours[n];colours[n]=Color.alpha(c)==0?0:Color.argb(Color.alpha(c),quant(Color.red(c),levels),quant(Color.green(c),levels),quant(Color.blue(c),levels));}
        JSONArray result=new JSONArray();int totalPoints=0;
        for(int seed=0;seed<size;seed++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(visited[seed]||colours[seed]==0)continue;
            int colour=colours[seed],head=0,tail=1;queue[0]=seed;visited[seed]=true;
            while(head<tail){int pixel=queue[head++],x=pixel%w,y=pixel/w;
                if(x>0)tail=visit(pixel-1,colour,colours,visited,queue,tail);
                if(x+1<w)tail=visit(pixel+1,colour,colours,visited,queue,tail);
                if(y>0)tail=visit(pixel-w,colour,colours,visited,queue,tail);
                if(y+1<h)tail=visit(pixel+w,colour,colours,visited,queue,tail);
            }
            if(result.length()>=availableObjects)throw SketchDocument.bad("Colour trace exceeds object budget; reduce trace resolution or colour levels");
            int touched=0;
            for(int n=0;n<tail;n++){int pixel=queue[n],x=pixel%w,y=pixel/w;
                // Clockwise outer edges; inner contours naturally run in reverse.
                if(y==0||colours[pixel-w]!=colour)touched=edge(edges,vertices,touched,y*stride+x,0);
                if(x==w-1||colours[pixel+1]!=colour)touched=edge(edges,vertices,touched,y*stride+x+1,1);
                if(y==h-1||colours[pixel+w]!=colour)touched=edge(edges,vertices,touched,(y+1)*stride+x+1,2);
                if(x==0||colours[pixel-1]!=colour)touched=edge(edges,vertices,touched,(y+1)*stride+x,3);
            }
            JSONArray commands=new JSONArray();
            for(int n=0;n<touched;n++){int start=vertices[n];while(edges[start]!=0){ArrayList<int[]> contour=new ArrayList<>();int vertex=start,prior=-1,steps=0;
                do{
                    if(++steps>size*4)throw SketchDocument.bad("Colour contour did not close");
                    appendCorner(contour,vertex%stride,vertex/stride);
                    int direction=choose(edges[vertex]&15,prior);if(direction<0)throw SketchDocument.bad("Colour contour has an open boundary");edges[vertex]=(byte)(edges[vertex]&~(1<<direction));
                    vertex+=direction==0?1:direction==1?stride:direction==2?-1:-stride;prior=direction;
                }while(vertex!=start);
                for(int k=0;k<contour.size();k++){int[] point=contour.get(k);float[] mapped={(float)(bounds.getDouble("x")+point[0]/(double)w*bounds.getDouble("width"))*canvasWidth,(float)(bounds.getDouble("y")+point[1]/(double)h*bounds.getDouble("height"))*canvasHeight};mapping.mapPoints(mapped);double x=mapped[0]/(double)canvasWidth,y=mapped[1]/(double)canvasHeight;if(x<-.000001||x>1.000001||y<-.000001||y>1.000001)throw SketchDocument.bad("Colour trace mapping leaves the canvas; adjust the destination layer transform");commands.put(new JSONArray().put(k==0?"M":"L").put(Math.max(0,Math.min(1,x))).put(Math.max(0,Math.min(1,y))));totalPoints++;}
                commands.put(new JSONArray().put("Z"));if(commands.length()>4096||totalPoints>SketchDocument.MAX_POINTS)throw SketchDocument.bad("Colour trace exceeds point budget; reduce trace resolution");
            }}
            result.put(SketchEditOps.completeObject(new JSONObject().put("id",prefix+"_"+result.length()).put("kind","path").put("commands",commands).put("fill",true).put("color",String.format(java.util.Locale.ROOT,"#%08X",colour)).put("width",.001).put("frame",frame).put("exposure",exposure)));
        }
        if(result.length()==0)throw SketchDocument.bad("Reference contains no visible colour regions");return result;
    }
    private static int quant(int value,int levels){return (int)Math.round(Math.round(value*(levels-1)/255d)*255d/(levels-1));}
    private static int visit(int at,int colour,int[] colours,boolean[] seen,int[] queue,int tail){if(!seen[at]&&colours[at]==colour){seen[at]=true;queue[tail++]=at;}return tail;}
    private static int edge(byte[] edges,int[] vertices,int count,int vertex,int direction){if(edges[vertex]==0)vertices[count++]=vertex;edges[vertex]|=1<<direction;return count;}
    private static int choose(int mask,int prior){if(prior<0){for(int d=0;d<4;d++)if((mask&(1<<d))!=0)return d;}else for(int turn:new int[]{1,0,3,2}){int d=(prior+turn)%4;if((mask&(1<<d))!=0)return d;}return -1;}
    private static void appendCorner(ArrayList<int[]> contour,int x,int y){int n=contour.size();if(n>=2){int[] a=contour.get(n-2),b=contour.get(n-1);if((b[0]-a[0])*(y-b[1])==(b[1]-a[1])*(x-b[0])){b[0]=x;b[1]=y;return;}}contour.add(new int[]{x,y});}
}
