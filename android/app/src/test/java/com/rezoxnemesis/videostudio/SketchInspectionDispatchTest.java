package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchInspectionDispatchTest {
 @Test public void actualBackgroundControllerCompletesInspectionAndPersistsItsReceipt() throws Exception {
  Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();
  SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId",UUID.randomUUID().toString()).put("frameCount",200));
  ControlService service=Robolectric.buildService(ControlService.class).get();CommandJournal journal=new CommandJournal(context);AppProtocol protocol=new AppProtocol(context,null);protocol.stop();ExecutorService worker=Executors.newSingleThreadExecutor();
  try {
   for(String name:new String[]{"commandJournal","protocol"}){Field field=ControlService.class.getDeclaredField(name);field.setAccessible(true);field.set(service,name.equals("protocol")?protocol:journal);}
   Field prefs=ControlService.class.getDeclaredField("prefs");prefs.setAccessible(true);prefs.set(service,context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE));
   JSONObject command=new JSONObject().put("id",UUID.randomUUID().toString()).put("seq",1).put("action","sketch_inspect").put("parameters",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("frameLimit",2));
   worker.submit(()->service.onCommand(command)).get(10,TimeUnit.SECONDS);
   JSONObject receipt=journal.terminal(command.getString("id"));assertNotNull(receipt);assertEquals(receipt.toString(),"completed",receipt.getString("status"));assertEquals(200,receipt.getJSONObject("result").getInt("frameCount"));assertFalse(receipt.getJSONObject("result").getBoolean("creativeQualityVerified"));
  } finally {protocol.stop();worker.shutdownNow();}
 }
}
