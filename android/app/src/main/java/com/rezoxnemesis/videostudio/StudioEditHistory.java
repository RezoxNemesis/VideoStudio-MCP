package com.rezoxnemesis.videostudio;

import java.util.ArrayDeque;

/** In-memory undo/redo for user timeline edits. Never mutates source asset bytes. */
public final class StudioEditHistory {
    private static final int MAX_STEPS=25;
    private final ArrayDeque<String> undo=new ArrayDeque<>();
    private final ArrayDeque<String> redo=new ArrayDeque<>();
    private String projectId="";
    public synchronized void reset(String id) {
        String next=id==null?"":id;
        if(!projectId.equals(next)){projectId=next;undo.clear();redo.clear();}
    }
    public synchronized void checkpoint(ProjectStore.Project p){
        if(p==null)return;
        reset(p.id);
        undo.addLast(p.toJson().toString());
        while(undo.size()>MAX_STEPS)undo.removeFirst();
        redo.clear();
    }
    public synchronized boolean canUndo(){return !undo.isEmpty();}
    public synchronized boolean canRedo(){return !redo.isEmpty();}
    public synchronized ProjectStore.Project undo(ProjectStore.Project current){
        if(current==null)return null;
        reset(current.id);
        if(undo.isEmpty())return null;
        redo.addLast(current.toJson().toString());
        return read(undo.removeLast(),current);
    }
    public synchronized ProjectStore.Project redo(ProjectStore.Project current){
        if(current==null)return null;
        reset(current.id);
        if(redo.isEmpty())return null;
        undo.addLast(current.toJson().toString());
        return read(redo.removeLast(),current);
    }
    private static ProjectStore.Project read(String json,ProjectStore.Project current){
        try{
            ProjectStore.Project restored=ProjectStore.Project.fromJson(new org.json.JSONObject(json));
            if(!current.id.equals(restored.id))
                throw new IllegalStateException("Cross-project history cannot be restored");
            return restored;
        }catch(Exception e){throw new IllegalStateException("Cannot restore editor history",e);}
    }
}
