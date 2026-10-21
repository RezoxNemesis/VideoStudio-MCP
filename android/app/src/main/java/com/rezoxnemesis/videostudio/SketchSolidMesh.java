package com.rezoxnemesis.videostudio;

import java.util.*;

/** Closed convex meshes in normalized local coordinates; no imported geometry. */
final class SketchSolidMesh {
    static final List<String> SHAPES=List.of("box","sphere","cylinder","cone","pyramid");
    final double[][] vertices;
    final int[][] faces;
    final int[] materials;
    final List<int[]> edges=new ArrayList<>(); // vertex A/B, adjacent face A/B
    final String shape;

    private SketchSolidMesh(String shape,double[][] vertices,int[][] faces,int[] materials){
        this.shape=shape;this.vertices=vertices;this.faces=faces;this.materials=materials;
        LinkedHashMap<String,int[]> map=new LinkedHashMap<>();
        for(int f=0;f<faces.length;f++)for(int n=0;n<faces[f].length;n++){
            int a=faces[f][n],b=faces[f][(n+1)%faces[f].length];String key=Math.min(a,b)+":"+Math.max(a,b);int[] edge=map.get(key);
            if(edge==null)map.put(key,new int[]{a,b,f,-1});else{if(edge[3]!=-1)throw new IllegalArgumentException("Non-manifold solid");edge[3]=f;}
        }
        for(int[] edge:map.values())if(edge[3]<0)throw new IllegalArgumentException("Open solid surface");
        edges.addAll(map.values());
    }
    static SketchSolidMesh create(String shape){return create(shape,false);}
    static SketchSolidMesh create(String shape,boolean fineSphere){
        if("box".equals(shape))return new SketchSolidMesh(shape,new double[][]{{-.5,-.5,-.5},{.5,-.5,-.5},{.5,.5,-.5},{-.5,.5,-.5},{-.5,-.5,.5},{.5,-.5,.5},{.5,.5,.5},{-.5,.5,.5}},new int[][]{{0,3,2,1},{4,5,6,7},{0,4,7,3},{1,2,6,5},{3,7,6,2},{0,1,5,4}},new int[]{0,1,2,3,4,5});
        if("pyramid".equals(shape)){
            ArrayList<double[]> points=new ArrayList<>(Arrays.asList(new double[][]{{-.5,-.5,-.5},{.5,-.5,-.5},{.5,-.5,.5},{-.5,-.5,.5},{0,.5,0}}));ArrayList<int[]> faces=new ArrayList<>();ArrayList<Integer> materials=new ArrayList<>();add(points,faces,materials,new int[]{0,1,2,3},5);for(int n=0;n<4;n++)add(points,faces,materials,new int[]{n,4,(n+1)%4},n);return built(shape,points,faces,materials);
        }
        if("cone".equals(shape))return cone();
        if("cylinder".equals(shape))return cylinder();
        if(!"sphere".equals(shape))throw SketchDocument.bad("Unsupported native solid shape");
        int segments=fineSphere?48:24,rings=fineSphere?16:10;ArrayList<double[]> points=new ArrayList<>();ArrayList<int[]> faces=new ArrayList<>();ArrayList<Integer> materials=new ArrayList<>();points.add(new double[]{0,.5,0});
        for(int ring=1;ring<rings;ring++){double phi=Math.PI*ring/rings;for(int n=0;n<segments;n++){double theta=2*Math.PI*n/segments;points.add(new double[]{.5*Math.sin(phi)*Math.sin(theta),.5*Math.cos(phi),.5*Math.sin(phi)*Math.cos(theta)});}}
        int bottom=points.size();points.add(new double[]{0,-.5,0});
        for(int n=0;n<segments;n++){
            int next=(n+1)%segments;add(points,faces,materials,new int[]{0,1+n,1+next},n/(segments/6));
            for(int ring=0;ring<rings-2;ring++)add(points,faces,materials,new int[]{1+ring*segments+n,1+(ring+1)*segments+n,1+(ring+1)*segments+next,1+ring*segments+next},n/(segments/6));
            add(points,faces,materials,new int[]{bottom,1+(rings-2)*segments+next,1+(rings-2)*segments+n},n/(segments/6));
        }
        return built(shape,points,faces,materials);
    }
    private static SketchSolidMesh cone(){
        int segments=24;ArrayList<double[]> points=new ArrayList<>();ArrayList<int[]> faces=new ArrayList<>();ArrayList<Integer> materials=new ArrayList<>();
        for(double y:new double[]{-.5,-.14,.06})for(int n=0;n<segments;n++){double theta=2*Math.PI*n/segments,r=(.5-y)/2;points.add(new double[]{r*Math.sin(theta),y,r*Math.cos(theta)});}int apex=points.size();points.add(new double[]{0,.5,0});
        for(int n=0;n<segments;n++){int next=(n+1)%segments;for(int ring=0;ring<2;ring++)add(points,faces,materials,new int[]{ring*segments+n,ring*segments+next,(ring+1)*segments+next,(ring+1)*segments+n},n<2?3:ring==1?1:0);add(points,faces,materials,new int[]{48+n,48+next,apex},n<2?3:0);}
        int[] cap=new int[segments];for(int n=0;n<segments;n++)cap[n]=n;add(points,faces,materials,cap,5);return built("cone",points,faces,materials);
    }
    private static SketchSolidMesh cylinder(){
        int segments=24;ArrayList<double[]> points=new ArrayList<>();ArrayList<int[]> faces=new ArrayList<>();ArrayList<Integer> materials=new ArrayList<>();
        for(int ring=0;ring<2;ring++)for(int n=0;n<segments;n++){double theta=2*Math.PI*n/segments;points.add(new double[]{.5*Math.sin(theta),ring==0?-.5:.5,.5*Math.cos(theta)});}points.add(new double[]{0,-.5,0});points.add(new double[]{0,.5,0});
        for(int n=0;n<segments;n++){int next=(n+1)%segments;add(points,faces,materials,new int[]{n,next,segments+next,segments+n},n/(segments/6));add(points,faces,materials,new int[]{48,n,next},n<2?0:5);add(points,faces,materials,new int[]{49,segments+next,segments+n},n<2?0:4);}
        return built("cylinder",points,faces,materials);
    }
    private static void add(List<double[]> points,List<int[]> faces,List<Integer> materials,int[] face,int material){
        double[] a=points.get(face[0]),b=points.get(face[1]),c=points.get(face[2]);double[] normal=cross(a,b,c);double dot=0;
        for(int index:face){double[] v=points.get(index);for(int n=0;n<3;n++)dot+=v[n]*normal[n];}
        if(dot<0)for(int l=0,r=face.length-1;l<r;l++,r--){int tmp=face[l];face[l]=face[r];face[r]=tmp;}
        faces.add(face);materials.add(material);
    }
    private static SketchSolidMesh built(String shape,List<double[]> points,List<int[]> faces,List<Integer> materials){int[] groups=new int[materials.size()];for(int i=0;i<groups.length;i++)groups[i]=materials.get(i);return new SketchSolidMesh(shape,points.toArray(new double[0][]),faces.toArray(new int[0][]),groups);}
    static double[] cross(double[] a,double[] b,double[] c){double ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];return new double[]{uy*vz-uz*vy,uz*vx-ux*vz,ux*vy-uy*vx};}
}
