package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Project-private 2D rig storage, included in the existing Drive archive. */
public final class Rig2DProjectStore {
    private final Context context;
    private final ProjectStore projects;
    public Rig2DProjectStore(Context context,ProjectStore projects) {
        if(context==null||projects==null)throw new IllegalArgumentException("Store is required");
        this.context=context.getApplicationContext();
        this.projects=projects;
    }

    private File directory(String projectId) {
        if(projectId==null||!projectId.matches("[A-Za-z0-9_-]{8,128}"))
            throw new IllegalArgumentException("Invalid rig project ID");
        if(projects.get(projectId)==null)
            throw new IllegalArgumentException("Rig project does not exist");
        File dir=new File(context.getFilesDir(),
                "creative_workspace/projects/"+projectId+"/rigs");
        if(!dir.exists()&&!dir.mkdirs())
            throw new IllegalStateException("Unable to create rig project workspace");
        return dir;
    }

    public synchronized File save(Rig2DProfile rig) throws Exception {
        ProjectStore.Project p=projects.get(rig.projectId);
        if(p==null||p.asset(rig.sourceAssetId)==null)
            throw new IllegalArgumentException("Rig image must already be imported into VideoStudio");
        ProjectStore.Asset asset=p.asset(rig.sourceAssetId);
        if(!asset.mime.startsWith("image/"))
            throw new IllegalArgumentException("Rigging requires an image source");
        File dir=directory(rig.projectId);
        File target=new File(dir,rig.id+".json");
        File temp=new File(dir,rig.id+".tmp");
        byte[] data=rig.toJson().toString(2).getBytes(StandardCharsets.UTF_8);
        if(data.length>65536)throw new IllegalArgumentException("Rig profile too large");
        try(FileOutputStream out=new FileOutputStream(temp)) {
            out.write(data);
            out.getFD().sync();
        }
        if(!temp.renameTo(target)){
            // Only atomically replace a known profile on providers without atomic replace.
            if(target.exists()&&!target.delete())throw new IllegalStateException("Cannot replace rig");
            if(!temp.renameTo(target))throw new IllegalStateException("Cannot commit rig profile");
        }
        return target;
    }

    public synchronized Rig2DProfile load(String projectId,String rigId) throws Exception {
        if(rigId==null||!rigId.matches("[A-Za-z0-9_-]{8,128}"))
            throw new IllegalArgumentException("Invalid rig ID");
        File file=new File(directory(projectId),rigId+".json");
        if(!file.isFile()||file.length()<10||file.length()>65536)
            throw new IllegalArgumentException("Rig profile not found");
        byte[] bytes=new byte[(int)file.length()];
        try(FileInputStream in=new FileInputStream(file)) {
            int total=0,step;
            while(total<bytes.length&&(step=in.read(bytes,total,bytes.length-total))>0)total+=step;
            if(total!=bytes.length)throw new IllegalStateException("Incomplete rig profile");
        }
        Rig2DProfile rig=Rig2DProfile.fromJson(
                new JSONObject(new String(bytes,StandardCharsets.UTF_8)));
        if(!projectId.equals(rig.projectId)||!rigId.equals(rig.id))
            throw new IllegalArgumentException("Rig/profile identity mismatch");
        ProjectStore.Project p=projects.get(projectId);
        if(p==null||p.asset(rig.sourceAssetId)==null)
            throw new IllegalArgumentException("Rig source asset no longer exists");
        return rig;
    }

    /** Lists ONLY rig definitions inside this project-private folder. */
    public synchronized List<Rig2DProfile> list(String projectId) {
        ArrayList<Rig2DProfile> result=new ArrayList<>();
        File[] files=directory(projectId).listFiles();
        if(files==null)return result;
        for(File f:files){
            if(!f.isFile()||!f.getName().endsWith(".json"))continue;
            String rigId=f.getName().substring(0,f.getName().length()-5);
            try{ result.add(load(projectId,rigId)); }catch(Exception ignored){}
            if(result.size()>=100)break;
        }
        return result;
    }
}
