package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class CommandJournalConcurrencyTest {
    @Test public void validatedTerminalRetryRecapturesTheImmutableHandoff()throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        CommandJournal journal=new CommandJournal(context);
        JSONObject cmd=new JSONObject().put("id","completed-handoff-001").put("action","export_project").put("parameters",new JSONObject().put("quality","720p"));
        journal.bindProject(cmd,"project");journal.finish(cmd,new JSONObject().put("ok",true).put("playable",true),"completed");
        assertEquals("A completion racing handoff must retain its payload",cmd.toString(),journal.capturedCommand(cmd.getString("id")).toString());
        CommandJournal restarted=new CommandJournal(context);restarted.bindProject(new JSONObject(cmd.toString()),"project");
        assertEquals(cmd.toString(),restarted.capturedCommand(cmd.getString("id")).toString());assertNotNull(restarted.terminal(cmd.getString("id")));
        JSONObject changed=new JSONObject(cmd.toString());changed.getJSONObject("parameters").put("quality","1080p");
        try{restarted.bindProject(changed,"project");fail("Recapture must validate the original request");}catch(IllegalArgumentException expected){}
        assertEquals(cmd.toString(),restarted.capturedCommand(cmd.getString("id")).toString());
    }
    @Test public void independentForegroundAndServiceJournalsRetainEveryConcurrentHandoff()throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).edit().clear().commit();
        int count=16;ArrayList<CommandJournal> journals=new ArrayList<>();for(int i=0;i<count;i++)journals.add(new CommandJournal(context));
        ExecutorService pool=Executors.newFixedThreadPool(count);CyclicBarrier ready=new CyclicBarrier(count);ArrayList<Future<?>> writes=new ArrayList<>();
        try{
            for(int i=0;i<count;i++){final int index=i;writes.add(pool.submit(()->{
                ready.await(5,TimeUnit.SECONDS);
                JSONObject cmd=new JSONObject().put("id","handoff-"+index).put("action","export_project").put("parameters",new JSONObject());
                journals.get(index).bindProject(cmd,"project-"+index);return null;
            }));}
            for(Future<?> write:writes)write.get(10,TimeUnit.SECONDS);
            CommandJournal saved=new CommandJournal(context);
            for(int i=0;i<count;i++){
                assertNotNull("Captured command must survive simultaneous journal writes: "+i,saved.capturedCommand("handoff-"+i));
                assertEquals("project-"+i,saved.boundProject("handoff-"+i));
            }
        }finally{pool.shutdownNow();}
    }
}
