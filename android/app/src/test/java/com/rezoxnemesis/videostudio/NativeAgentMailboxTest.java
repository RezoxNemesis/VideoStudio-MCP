package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
public class NativeAgentMailboxTest {
 static final String KEY="synthetic-owner-only-for-tests";
 JSONObject command()throws Exception{return new JSONObject().put("id",UUID.randomUUID().toString()).put("action","self_test").put("parameters",new JSONObject());}
 JSONObject packet(long now)throws Exception{return new JSONObject().put("schema","videostudio.agent.commands.v1").put("packetId",UUID.randomUUID().toString()).put("issuedAtMs",now).put("expiresAtMs",now+60000).put("commands",new JSONArray().put(command()));}
 @Test public void authenticatedRoundTripAndTamper()throws Exception{long now=System.currentTimeMillis();JSONObject p=packet(now);JSONObject e=NativeAgentMailboxCrypto.encodePacket(KEY,p);assertEquals(p.toString(),NativeAgentMailboxCrypto.decodePacket(KEY,e.toString(),now).toString());e.put("channel","0".repeat(32));assertThrows(Exception.class,()->NativeAgentMailboxCrypto.decodePacket(KEY,e.toString(),now));}
 @Test public void expiredAndFutureRejected()throws Exception{long now=System.currentTimeMillis();JSONObject expired=NativeAgentMailboxCrypto.encodePacket(KEY,packet(now-120000));assertThrows(Exception.class,()->NativeAgentMailboxCrypto.decodePacket(KEY,expired.toString(),now));JSONObject future=NativeAgentMailboxCrypto.encodePacket(KEY,packet(now+600000));assertThrows(Exception.class,()->NativeAgentMailboxCrypto.decodePacket(KEY,future.toString(),now));}
 @Test public void wholePacketPreflight()throws Exception{long now=System.currentTimeMillis();JSONObject p=packet(now);p.getJSONArray("commands").put(command().put("parameters", "invalid"));JSONObject e=NativeAgentMailboxCrypto.encodePacket(KEY,p);assertThrows(Exception.class,()->NativeAgentMailboxCrypto.decodePacket(KEY,e.toString(),now));}
 @Test public void repeatedIdsAndReservedFieldsRejected()throws Exception{long now=System.currentTimeMillis();JSONObject p=packet(now);p.getJSONArray("commands").put(p.getJSONArray("commands").getJSONObject(0));final JSONObject duplicate=p;assertThrows(Exception.class,()->NativeAgentMailboxCrypto.validate(duplicate,now));p=packet(now);p.getJSONArray("commands").getJSONObject(0).getJSONObject("parameters").put("_mcpCommandId","forged");final JSONObject bad=p;assertThrows(Exception.class,()->NativeAgentMailboxCrypto.validate(bad,now));}
 @Test public void receiptPreservesFullResultAndPages()throws Exception{String id=UUID.randomUUID().toString();JSONObject r=new JSONObject().put("status","completed").put("result",new JSONObject().put("data","x".repeat(200000)));JSONObject e=NativeAgentMailboxCrypto.encodeReceipt(KEY,id,r);assertEquals(r.toString(),NativeAgentMailboxCrypto.decodeReceipt(KEY,id,e).toString());JSONArray pages=NativeAgentMailboxCrypto.receiptPages(e);assertTrue(pages.length()>1);for(int i=0;i<pages.length();i++)assertTrue(pages.getJSONObject(i).getString("fragment").length()<=32768);}
 @Test public void durableOrderReplayConflictAndFairReceipts()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
 java.io.File dir=new java.io.File(context.getFilesDir(),"mailbox-test-"+UUID.randomUUID());
 NativeAgentMailboxStore store=new NativeAgentMailboxStore(dir,KEY);
 long now=System.currentTimeMillis();JSONObject p=packet(now);JSONObject first=p.getJSONArray("commands").getJSONObject(0),second=command();p.getJSONArray("commands").put(second);
 store.accept(NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),now);store.accept(NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),now);
 assertEquals(first.getString("id"),store.next().getString("id"));store.markDispatched(first.getString("id"));
 assertEquals(first.getString("id"),new NativeAgentMailboxStore(dir,KEY).next().getString("id"));
 JSONObject changed=packet(now);changed.getJSONArray("commands").put(0,new JSONObject(first.toString()).put("action","get_state"));
 assertThrows(Exception.class,()->store.accept(NativeAgentMailboxCrypto.encodePacket(KEY,changed).toString(),now));
 store.complete(first.getString("id"),new JSONObject().put("ok",true),"completed");assertEquals(second.getString("id"),store.next().getString("id"));
 store.complete(second.getString("id"),new JSONObject().put("ok",true),"completed");store.deferReceipt(first.getString("id"),now+60000);
 assertEquals(second.getString("id"),store.pendingReceipts(now).getString(0));
 }
 @Test public void deferredAcknowledgementNeverBecomesTerminalReceipt()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
 NativeAgentMailbox mailbox=new NativeAgentMailbox(context,KEY,()->false,c->{},c->true);
 JSONObject p=packet(System.currentTimeMillis()),cmd=p.getJSONArray("commands").getJSONObject(0);mailbox.accept(NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),System.currentTimeMillis());
 ControlService service=org.robolectric.Robolectric.buildService(ControlService.class).get();java.lang.reflect.Field f=ControlService.class.getDeclaredField("mailbox");f.setAccessible(true);f.set(service,mailbox);
 java.lang.reflect.Method ack=ControlService.class.getDeclaredMethod("acknowledge",JSONObject.class,JSONObject.class,String.class);ack.setAccessible(true);
 ack.invoke(service,cmd,new JSONObject().put("ok",true).put("queued",true).put("jobId","job"),"completed");assertNull(mailbox.receipt(cmd.getString("id")));
 ack.invoke(service,cmd,new JSONObject().put("ok",true).put("actual","terminal"),"completed");assertEquals("terminal",NativeAgentMailboxCrypto.decodeReceipt(KEY,cmd.getString("id"),mailbox.receipt(cmd.getString("id"))).getJSONObject("result").getString("actual"));mailbox.stop();
 }
 @Test public void nodeFixtureAuthenticatesOnAndroid()throws Exception{
 java.io.File fixture=new java.io.File("../scripts/fixtures/native-agent-mailbox-synthetic.json");if(!fixture.exists())fixture=new java.io.File("scripts/fixtures/native-agent-mailbox-synthetic.json");if(!fixture.exists())fixture=new java.io.File("../../scripts/fixtures/native-agent-mailbox-synthetic.json");
 JSONObject f=new JSONObject(new String(java.nio.file.Files.readAllBytes(fixture.toPath()),java.nio.charset.StandardCharsets.UTF_8));String key=f.getString("ownerKey");assertEquals(f.getString("channel"),NativeAgentMailboxCrypto.channel(key));assertEquals(f.getJSONObject("plaintext").toString(),NativeAgentMailboxCrypto.decodePacket(key,f.getJSONObject("packet").toString(),f.getLong("now")).toString());assertEquals(f.getJSONObject("receiptPlaintext").toString(),NativeAgentMailboxCrypto.decodeReceipt(key,f.getJSONObject("receipt").getString("commandId"),f.getJSONObject("receipt")).toString());
 }
 @Test public void oversizedResultDoesNotBlockLaterNativeCommands()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();java.io.File dir=new java.io.File(context.getFilesDir(),"oversize-"+UUID.randomUUID());NativeAgentMailboxStore store=new NativeAgentMailboxStore(dir,KEY);long now=System.currentTimeMillis();JSONObject p=packet(now),second=command(),first=p.getJSONArray("commands").getJSONObject(0);p.getJSONArray("commands").put(second);store.accept(NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),now);store.markDispatched(first.getString("id"));store.complete(first.getString("id"),new JSONObject().put("ok",true).put("payload","x".repeat(2200000)),"completed");assertEquals(second.getString("id"),store.next().getString("id"));JSONObject receipt=NativeAgentMailboxCrypto.decodeReceipt(KEY,first.getString("id"),store.receipt(first.getString("id")));assertEquals("completed",receipt.getString("status"));assertTrue(receipt.getJSONObject("result").getBoolean("doNotRetry"));assertEquals("RESULT_EXCEEDS_MAILBOX_LIMIT",receipt.getJSONObject("result").getString("error"));assertTrue(new java.io.File(dir,first.getString("id")+".private-result").length()>2200000);
 }
 @Test public void transientFirstPacketFailureCannotReorderLaterPacket()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();NativeAgentMailbox mailbox=new NativeAgentMailbox(context,KEY,()->false,c->{},c->true);long now=System.currentTimeMillis();JSONObject a=packet(now),b=packet(now);String aid=a.getString("packetId"),bid=b.getString("packetId");java.util.concurrent.atomic.AtomicBoolean failA=new java.util.concurrent.atomic.AtomicBoolean(true);
 String index=new JSONObject().put("schema","videostudio.agent.index.v1").put("packets",new JSONArray().put(aid).put(bid)).toString(),ae=NativeAgentMailboxCrypto.encodePacket(KEY,a).toString(),be=NativeAgentMailboxCrypto.encodePacket(KEY,b).toString();
 java.lang.reflect.Field factory=NativeAgentMailbox.class.getDeclaredField("connectionFactory");factory.setAccessible(true);factory.set(mailbox,(java.util.function.Function<String,java.net.HttpURLConnection>)(address)->new java.net.HttpURLConnection(null){public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}public int getResponseCode()throws java.io.IOException{if(address.contains(aid)&&failA.get())throw new java.io.IOException("offline");return 200;}public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream((java.net.URI.create(address).getPath().endsWith("index.json")?index:address.contains(aid)?ae:be).getBytes(java.nio.charset.StandardCharsets.UTF_8));}});
 java.lang.reflect.Field running=NativeAgentMailbox.class.getDeclaredField("running");running.setAccessible(true);running.set(mailbox,true);java.lang.reflect.Method poll=NativeAgentMailbox.class.getDeclaredMethod("pollIndex");poll.setAccessible(true);poll.invoke(mailbox);assertFalse(mailbox.owns(b.getJSONArray("commands").getJSONObject(0).getString("id")));failA.set(false);poll.invoke(mailbox);assertTrue(mailbox.owns(a.getJSONArray("commands").getJSONObject(0).getString("id")));assertTrue(mailbox.owns(b.getJSONArray("commands").getJSONObject(0).getString("id")));mailbox.stop();
 }
 @Test public void pauseAndStopSuppressPostedCallbacks()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();java.util.concurrent.atomic.AtomicBoolean paused=new java.util.concurrent.atomic.AtomicBoolean(true);java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();NativeAgentMailbox mailbox=new NativeAgentMailbox(context,KEY,paused::get,c->calls.incrementAndGet(),c->true);long now=System.currentTimeMillis();mailbox.accept(NativeAgentMailboxCrypto.encodePacket(KEY,packet(now)).toString(),now);java.lang.reflect.Field running=NativeAgentMailbox.class.getDeclaredField("running");running.setAccessible(true);running.set(mailbox,true);mailbox.dispatchPending();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(0,calls.get());paused.set(false);mailbox.dispatchPending();mailbox.stop();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(0,calls.get());
 }
 @Test public void failedReceiptWriteRetainsResultForRetryWithoutBlockingNextCommand()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();NativeAgentMailbox mailbox=new NativeAgentMailbox(context,KEY,()->false,c->{},c->true);long now=System.currentTimeMillis();JSONObject p=packet(now),first=p.getJSONArray("commands").getJSONObject(0),second=command();p.getJSONArray("commands").put(second);mailbox.accept(NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),now);java.io.File blocker=new java.io.File(context.getNoBackupFilesDir(),"agent-mailbox-v1/"+first.getString("id")+".receipt");assertTrue(blocker.mkdir());JSONObject result=new JSONObject().put("ok",true).put("actual","retained");mailbox.complete(first,result,"completed");java.lang.reflect.Field f=NativeAgentMailbox.class.getDeclaredField("store");f.setAccessible(true);assertEquals(second.getString("id"),((NativeAgentMailboxStore)f.get(mailbox)).next().getString("id"));assertTrue(blocker.delete());mailbox.complete(first,result,"completed");assertEquals("retained",NativeAgentMailboxCrypto.decodeReceipt(KEY,first.getString("id"),mailbox.receipt(first.getString("id"))).getJSONObject("result").getString("actual"));mailbox.stop();
 }
 @Test public void unavailableMailboxNeverRoutesItsRestoredAckIntoMcpOutbox()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();NativeAgentMailbox accepted=new NativeAgentMailbox(context,KEY,()->false,c->{},c->true);long now=System.currentTimeMillis();JSONObject p=packet(now),cmd=p.getJSONArray("commands").getJSONObject(0),result=new JSONObject().put("ok",true);accepted.accept(NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),now);new CommandJournal(context).finish(cmd,result,"completed");ControlService service=org.robolectric.Robolectric.buildService(ControlService.class).get();AppProtocol protocol=new AppProtocol(context,null);java.lang.reflect.Field pf=ControlService.class.getDeclaredField("protocol");pf.setAccessible(true);pf.set(service,protocol);java.lang.reflect.Method ack=ControlService.class.getDeclaredMethod("acknowledge",JSONObject.class,JSONObject.class,String.class);ack.setAccessible(true);ack.invoke(service,cmd,result,"completed");try(CommandOutbox outbox=new CommandOutbox(context)){assertFalse(outbox.contains(cmd.getString("id")));}protocol.stop();accepted.stop();
 }
 @Test public void acceptedQueuedCommandExpiresBeforeDispatchWithoutEffects()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();NativeAgentMailbox mailbox=new NativeAgentMailbox(context,KEY,()->false,c->calls.incrementAndGet(),c->true);long acceptedAt=System.currentTimeMillis()-120000;JSONObject p=packet(acceptedAt),cmd=p.getJSONArray("commands").getJSONObject(0);mailbox.accept(NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),acceptedAt);java.lang.reflect.Field running=NativeAgentMailbox.class.getDeclaredField("running");running.setAccessible(true);running.set(mailbox,true);mailbox.dispatchPending();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(0,calls.get());assertEquals("PACKET_EXPIRED_BEFORE_EXECUTION",NativeAgentMailboxCrypto.decodeReceipt(KEY,cmd.getString("id"),mailbox.receipt(cmd.getString("id"))).getJSONObject("result").getString("error"));mailbox.stop();
 }
 @Test public void expiredTransportCannotRelabelAlreadyStartedRecoveredJobAsUnexecuted()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();java.util.concurrent.atomic.AtomicInteger callbacks=new java.util.concurrent.atomic.AtomicInteger();NativeAgentMailbox[] ref=new NativeAgentMailbox[1];ref[0]=new NativeAgentMailbox(context,KEY,()->false,c->{if(ref[0].allowExecution(c))callbacks.incrementAndGet();},c->true);NativeAgentMailbox mailbox=ref[0];long acceptedAt=System.currentTimeMillis()-120000;JSONObject p=packet(acceptedAt),cmd=p.getJSONArray("commands").getJSONObject(0);mailbox.accept(NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),acceptedAt);java.lang.reflect.Field sf=NativeAgentMailbox.class.getDeclaredField("store");sf.setAccessible(true);((NativeAgentMailboxStore)sf.get(mailbox)).markDispatched(cmd.getString("id"));new CommandJournal(context).linkJob(cmd,"existing-native-job","",new JSONObject().put("ok",true).put("queued",true).put("jobId","existing-native-job"));java.lang.reflect.Field running=NativeAgentMailbox.class.getDeclaredField("running");running.setAccessible(true);running.set(mailbox,true);java.lang.reflect.Method recover=NativeAgentMailbox.class.getDeclaredMethod("recover");recover.setAccessible(true);recover.invoke(mailbox);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,callbacks.get());assertNull(mailbox.receipt(cmd.getString("id")));mailbox.stop();
 }
 @Test public void acceptedImmediateCommandsDrainWithoutWaitingForNetworkPollTick()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();java.util.concurrent.atomic.AtomicInteger callbacks=new java.util.concurrent.atomic.AtomicInteger();JSONObject result=new JSONObject().put("ok",true);NativeAgentMailbox[] ref=new NativeAgentMailbox[1];ref[0]=new NativeAgentMailbox(context,KEY,()->false,c->{callbacks.incrementAndGet();ref[0].complete(c,result,"completed");},c->true);NativeAgentMailbox mailbox=ref[0];long now=System.currentTimeMillis();JSONObject p=packet(now);p.getJSONArray("commands").put(command());mailbox.accept(NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),now);java.lang.reflect.Field running=NativeAgentMailbox.class.getDeclaredField("running");running.setAccessible(true);running.set(mailbox,true);mailbox.dispatchPending();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);while(callbacks.get()<2&&System.nanoTime()<deadline){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}assertEquals(2,callbacks.get());mailbox.stop();
 }
 @Test public void refreshedIndexEscapesCdnSnapshotDespiteIgnoredNoCacheHeader()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();NativeAgentMailbox mailbox=new NativeAgentMailbox(context,KEY,()->false,c->{},c->true);long now=System.currentTimeMillis();JSONObject p=packet(now);String packetId=p.getString("packetId"),commandId=p.getJSONArray("commands").getJSONObject(0).getString("id"),encrypted=NativeAgentMailboxCrypto.encodePacket(KEY,p).toString();
 java.util.concurrent.atomic.AtomicReference<String> originIndex=new java.util.concurrent.atomic.AtomicReference<>(new JSONObject().put("schema","videostudio.agent.index.v1").put("packets",new JSONArray()).toString());java.util.Map<String,String> edgeCache=new java.util.HashMap<>();
 java.lang.reflect.Field factory=NativeAgentMailbox.class.getDeclaredField("connectionFactory");factory.setAccessible(true);factory.set(mailbox,(java.util.function.Function<String,java.net.HttpURLConnection>)(address)->{
  java.net.URI request=java.net.URI.create(address);assertEquals("https",request.getScheme());assertEquals("raw.githubusercontent.com",request.getHost());assertFalse(address.contains(KEY));String body=edgeCache.computeIfAbsent(address,u->request.getPath().endsWith("index.json")?originIndex.get():encrypted);
  return new java.net.HttpURLConnection(null){public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}public int getResponseCode(){return 200;}public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));}};
 });
 java.lang.reflect.Field running=NativeAgentMailbox.class.getDeclaredField("running");running.setAccessible(true);running.set(mailbox,true);java.lang.reflect.Method poll=NativeAgentMailbox.class.getDeclaredMethod("pollIndex");poll.setAccessible(true);
 try{poll.invoke(mailbox);assertFalse(mailbox.owns(commandId));originIndex.set(new JSONObject().put("schema","videostudio.agent.index.v1").put("packets",new JSONArray().put(packetId)).toString());poll.invoke(mailbox);assertTrue("New commands must appear on the next poll even while an earlier full URL is cached",mailbox.owns(commandId));}finally{mailbox.stop();}
 }
 @Test public void packetRetryEscapesCachedNotFoundWithoutLosingIndexOrder()throws Exception{
 android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();NativeAgentMailbox mailbox=new NativeAgentMailbox(context,KEY,()->false,c->{},c->true);long now=System.currentTimeMillis();JSONObject p=packet(now);String packetId=p.getString("packetId"),commandId=p.getJSONArray("commands").getJSONObject(0).getString("id"),encrypted=NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),index=new JSONObject().put("schema","videostudio.agent.index.v1").put("packets",new JSONArray().put(packetId)).toString();java.util.concurrent.atomic.AtomicBoolean packetVisible=new java.util.concurrent.atomic.AtomicBoolean(false);java.util.Map<String,Integer> cachedStatus=new java.util.HashMap<>();
 java.lang.reflect.Field factory=NativeAgentMailbox.class.getDeclaredField("connectionFactory");factory.setAccessible(true);factory.set(mailbox,(java.util.function.Function<String,java.net.HttpURLConnection>)(address)->{
  boolean isIndex=java.net.URI.create(address).getPath().endsWith("index.json");int response=cachedStatus.computeIfAbsent(address,u->isIndex||packetVisible.get()?200:404);String body=isIndex?index:encrypted;
  return new java.net.HttpURLConnection(null){public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}public int getResponseCode(){return response;}public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));}};
 });
 java.lang.reflect.Field running=NativeAgentMailbox.class.getDeclaredField("running");running.setAccessible(true);running.set(mailbox,true);java.lang.reflect.Method poll=NativeAgentMailbox.class.getDeclaredMethod("pollIndex");poll.setAccessible(true);
 try{poll.invoke(mailbox);assertFalse(mailbox.owns(commandId));packetVisible.set(true);poll.invoke(mailbox);assertTrue("A cached CDN404 must not keep an immutable packet unavailable after it reaches origin",mailbox.owns(commandId));}finally{mailbox.stop();}
 }

 @Test public void oneSweepDeliversSeveralReceiptPagesWithoutThreeSecondGaps()throws Exception {
  NativeAgentMailbox mailbox=newMailbox();Carrier carrier=new Carrier(mailbox);
  JSONObject cmd=completed(mailbox,250000);String id=cmd.getString("id");
  try {
   flush(mailbox);
   assertTrue("An available carrier must receive more than one page per sweep",carrier.bodies.size()>1);
   assertEquals(receiptPageCount(mailbox,id),carrier.bodies.size());
   assertEquals(0,mailboxStore(mailbox).pendingReceipts(Long.MAX_VALUE).length());
   JSONObject health=health(mailbox);
   assertEquals(carrier.bodies.size(),health.getInt("lastSweepRequests"));
   assertEquals(receiptPageCount(mailbox,id),health.getInt("receiptNextPage"));
   assertEquals("encrypted receipt published",health.getString("lastReceiptState"));
   assertFalse(health.toString().contains(KEY));
   assertFalse(health.toString().contains("ciphertext"));
  } finally {mailbox.stop();}
 }

 @Test public void largeReceiptBatchHasRequestByteBoundsAndDoesNotStarveSmallReceipts()throws Exception {
  NativeAgentMailbox mailbox=newMailbox();Carrier carrier=new Carrier(mailbox);
  JSONObject large=completed(mailbox,1500000),small=completed(mailbox,0);
  try {
   flush(mailbox);
   assertEquals("Per-sweep request budget",16,carrier.bodies.size());
   assertTrue(carrier.bytes<=640*1024);
   assertEquals(large.getString("id"),carrier.page(0).getString("commandId"));
   assertEquals(small.getString("id"),carrier.page(1).getString("commandId"));
   assertEquals(15,mailboxStore(mailbox).receiptPage(large.getString("id")));
   assertEquals(1,mailboxStore(mailbox).receiptPage(small.getString("id")));
   assertEquals(1,mailboxStore(mailbox).pendingReceipts(Long.MAX_VALUE).length());
   assertEquals(carrier.bytes,health(mailbox).getLong("lastSweepBytes"));
  } finally {mailbox.stop();}
 }

 @Test public void carrierFailureRetriesExactFragmentAndReportsBoundedProgress()throws Exception {
  NativeAgentMailbox mailbox=newMailbox();Carrier carrier=new Carrier(mailbox);
  JSONObject cmd=completed(mailbox,90000);String id=cmd.getString("id");carrier.fail=true;
  try {
   flush(mailbox);
   assertEquals(1,carrier.bodies.size());assertEquals(0,mailboxStore(mailbox).receiptPage(id));
   JSONObject failed=health(mailbox);
   assertEquals("saved privately; carrier retry pending",failed.getString("lastReceiptState"));
   assertEquals(0,failed.getInt("receiptNextPage"));
   assertFalse(failed.getString("lastReceiptError").isEmpty());
   JSONObject first=carrier.page(0);carrier.fail=false;mailboxStore(mailbox).deferReceipt(id,0);
   flush(mailbox);
   assertEquals(first.toString(),carrier.page(1).toString());
   assertEquals(receiptPageCount(mailbox,id),mailboxStore(mailbox).receiptPage(id));
   assertEquals("",health(mailbox).getString("lastReceiptError"));
  } finally {mailbox.stop();}
 }

 @Test public void receiptSweepStopsStartingRequestsWhenItsTimeBudgetExpires()throws Exception {
  NativeAgentMailbox mailbox=newMailbox();Carrier carrier=new Carrier(mailbox);
  completed(mailbox,250000);carrier.advanceClock=true;
  try {
   flush(mailbox);
   assertEquals(1,carrier.bodies.size());
   JSONObject health=health(mailbox);
   assertEquals("time",health.getString("lastSweepLimit"));
   assertTrue(health.getLong("lastSweepDurationMs")>=6000);
   assertTrue(carrier.timeout>0&&carrier.timeout<=6000);
  } finally {mailbox.stop();}
 }

 @Test public void publishedPageCheckpointResumesAfterMailboxRecreation()throws Exception {
  NativeAgentMailbox first=newMailbox();Carrier sent=new Carrier(first);
  JSONObject cmd=completed(first,1500000);String id=cmd.getString("id");
  flush(first);assertEquals(16,sent.bodies.size());
  JSONObject expected=NativeAgentMailboxCrypto.receiptPages(first.receipt(id)).getJSONObject(16);first.stop();
  NativeAgentMailbox resumed=newMailbox();Carrier carrier=new Carrier(resumed);
  try {
   mailboxStore(resumed).deferReceipt(id,0);flush(resumed);
   assertEquals(expected.toString(),carrier.page(0).toString());
   assertEquals(32,mailboxStore(resumed).receiptPage(id));
  } finally {resumed.stop();}
 }

 @Test public void savedHealthDistinguishesGitHubPollSuccessFromReceiptDelivery()throws Exception {
  NativeAgentMailbox mailbox=newMailbox();Carrier carrier=new Carrier(mailbox);
  try {
   java.lang.reflect.Method tick=NativeAgentMailbox.class.getDeclaredMethod("tick");tick.setAccessible(true);tick.invoke(mailbox);
   JSONObject healthy=health(mailbox);
   assertTrue(healthy.getBoolean("pollFresh"));
   assertTrue(healthy.getLong("lastPollSuccessAt")>0);
   assertEquals("",healthy.getString("lastError"));
   carrier.fail=true;tick.invoke(mailbox);
   JSONObject failed=health(mailbox);
   assertFalse(failed.getBoolean("connected"));
   assertFalse(failed.getString("lastError").isEmpty());
   assertEquals(healthy.getLong("lastPollSuccessAt"),failed.getLong("lastPollSuccessAt"));
   java.lang.reflect.Method saved=NativeAgentMailbox.class.getMethod("savedHealth",android.content.Context.class);
   JSONObject persisted=(JSONObject)saved.invoke(null,org.robolectric.RuntimeEnvironment.getApplication());
   assertEquals(failed.getLong("lastPollAt"),persisted.getLong("lastPollAt"));
  } finally {mailbox.stop();}
 }

 @Test public void successfulPollCannotHideAnAdmittedPacketRecoveryFailure()throws Exception {
  NativeAgentMailbox mailbox=newMailbox();new Carrier(mailbox);
  long now=System.currentTimeMillis();JSONObject p=packet(now),cmd=p.getJSONArray("commands").getJSONObject(0);
  mailboxStore(mailbox).accept(NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),now);
  mailboxStore(mailbox).admitted(cmd.getString("id"));
  java.io.File encrypted=new java.io.File(org.robolectric.RuntimeEnvironment.getApplication().getNoBackupFilesDir(),"agent-mailbox-v1/"+p.getString("packetId")+".vscmd");
  assertTrue(encrypted.delete());
  try {
   java.lang.reflect.Method tick=NativeAgentMailbox.class.getDeclaredMethod("tick");tick.setAccessible(true);tick.invoke(mailbox);
   JSONObject health=health(mailbox);assertTrue(health.getBoolean("pollFresh"));
   assertEquals("Private mailbox recovery unavailable",health.getString("lastError"));
  } finally {mailbox.stop();}
 }

 private NativeAgentMailbox newMailbox()throws Exception {
  NativeAgentMailbox result=new NativeAgentMailbox(org.robolectric.RuntimeEnvironment.getApplication(),KEY,()->false,c->{},c->true);
  java.lang.reflect.Field running=NativeAgentMailbox.class.getDeclaredField("running");running.setAccessible(true);running.set(result,true);return result;
 }
 private JSONObject completed(NativeAgentMailbox mailbox,int size)throws Exception {
  long now=System.currentTimeMillis();JSONObject p=packet(now),cmd=p.getJSONArray("commands").getJSONObject(0);
  mailbox.accept(NativeAgentMailboxCrypto.encodePacket(KEY,p).toString(),now);
  mailbox.complete(cmd,new JSONObject().put("ok",true).put("payload","x".repeat(size)),"completed");return cmd;
 }
 private NativeAgentMailboxStore mailboxStore(NativeAgentMailbox mailbox)throws Exception {
  java.lang.reflect.Field field=NativeAgentMailbox.class.getDeclaredField("store");field.setAccessible(true);return (NativeAgentMailboxStore)field.get(mailbox);
 }
 private int receiptPageCount(NativeAgentMailbox mailbox,String id)throws Exception {return NativeAgentMailboxCrypto.receiptPages(mailbox.receipt(id)).length();}
 private void flush(NativeAgentMailbox mailbox)throws Exception {
  java.lang.reflect.Method flush=NativeAgentMailbox.class.getDeclaredMethod("flushReceipts");flush.setAccessible(true);flush.invoke(mailbox);
 }
 private JSONObject health(NativeAgentMailbox mailbox)throws Exception {
  try{return (JSONObject)NativeAgentMailbox.class.getMethod("health").invoke(mailbox);}
  catch(NoSuchMethodException missing){fail("Mailbox health must expose poll and receipt progress independently");return null;}
 }
 private static final class Carrier {
  final List<JSONObject> bodies=new ArrayList<>();long bytes;boolean fail,advanceClock;int timeout;
  Carrier(NativeAgentMailbox mailbox)throws Exception {
   java.lang.reflect.Field field=NativeAgentMailbox.class.getDeclaredField("connectionFactory");field.setAccessible(true);
   field.set(mailbox,(java.util.function.Function<String,java.net.HttpURLConnection>)(address)->new java.net.HttpURLConnection(null){
    final java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();JSONObject body;
    public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}
    public java.io.OutputStream getOutputStream(){return output;}
    public int getResponseCode()throws java.io.IOException {
     if(address.contains("/api/device/register")){
      try{body=new JSONObject(output.toString("UTF-8"));bodies.add(body);bytes+=output.size();timeout=getReadTimeout();}
      catch(Exception invalid){throw new java.io.IOException(invalid);}
      if(advanceClock)org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6));
     }
     if(fail)throw new java.io.IOException("synthetic private carrier failure");return 200;
    }
    public java.io.InputStream getInputStream()throws java.io.IOException {
     try{
      String reply=body==null?new JSONObject().put("schema","videostudio.agent.index.v1").put("packets",new JSONArray()).toString():new JSONObject().put("ok",true).put("device",new JSONObject().put("deviceId",body.getString("deviceId")).put("capabilities",body.getJSONObject("meta").getJSONArray("capabilities"))).toString();
      return new java.io.ByteArrayInputStream(reply.getBytes(java.nio.charset.StandardCharsets.UTF_8));
     }catch(Exception invalid){throw new java.io.IOException(invalid);}
    }
   });
  }
  JSONObject page(int index)throws Exception {return new JSONObject(bodies.get(index).getJSONObject("meta").getJSONArray("capabilities").getString(1));}
 }
}
