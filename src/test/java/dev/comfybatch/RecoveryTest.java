package dev.comfybatch;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import okhttp3.mockwebserver.*;
import okhttp3.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.comfybatch.Store.*;
@SpringBootTest @ActiveProfiles("test")
class RecoveryTest {
 @Autowired Store s;@Autowired JobService jobs;
 @BeforeEach void reset(){for(String t:List.of("cb_result","cb_attempt","cb_event","cb_task","cb_job","cb_template"))s.db.update("delete from "+t);}
 String task(){String t=jobs.template(ServiceTest.template()).get("id").toString();String j=jobs.submit(new JobService.Submit(t,Map.of(),1,List.of(42L)),null).get("id").toString();return str(s.one("select id from cb_task where job_id=?",j),"id");}
 @Test void restartUsesHistoryWithoutResubmitting()throws Exception{
  String id=task();s.transition(id,State.SUBMITTED,"before restart");s.transition(id,State.RUNNING,"before restart");s.db.update("update cb_task set prompt_id='saved',attempt=1,started_at=? where id=?",System.currentTimeMillis(),id);
  try(var server=new MockWebServer()){
   var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"png",bytes);
   server.enqueue(new MockResponse().setResponseCode(503)); // WS down: history fallback
   server.enqueue(new MockResponse().setBody("{\"saved\":{\"status\":{\"status_str\":\"success\"},\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}"));
   server.enqueue(new MockResponse().setBody(new okio.Buffer().write(bytes.toByteArray())));server.start();
   var w=new Worker(s,new ComfyClient(server.url("/").toString()),1,60,1,50,"target/test-storage",false);w.run(id);w.stop();
   assertEquals("SUCCEEDED",str(s.task(id),"state"));assertEquals(1,num(s.task(id),"attempt"));assertEquals(1,s.db.queryForObject("select count(*) from cb_result where task_id=?",Integer.class,id));assertEquals(2,num(s.one("select * from cb_result where task_id=?",id),"width"));
   for(int i=0;i<3;i++)assertNotEquals("/prompt",server.takeRequest(1,TimeUnit.SECONDS).getPath());
  }
 }
 @Test void missingPromptSchedulesRetry()throws Exception{
  String id=task();s.transition(id,State.SUBMITTED,"before crash");s.db.update("update cb_task set prompt_id='missing',attempt=1,started_at=? where id=?",System.currentTimeMillis(),id);
  try(var server=new MockWebServer()){server.enqueue(new MockResponse().setResponseCode(503));server.enqueue(new MockResponse().setBody("{}"));server.enqueue(new MockResponse().setBody("{\"queue_running\":[],\"queue_pending\":[]}"));server.enqueue(new MockResponse().setBody("{\"queue_running\":[],\"queue_pending\":[]}"));server.start();var w=new Worker(s,new ComfyClient(server.url("/").toString()),1,60,1,50,"target/test-storage",false);w.run(id);w.stop();assertNull(str(s.task(id),"prompt_id"));assertTrue(str(s.task(id),"error").contains("absent"));}
 }
 @Test void queueCancelDoesNotInterruptUnrelatedPrompt()throws Exception{
  try(var server=new MockWebServer()){server.enqueue(new MockResponse().setBody("{\"queue_running\":[[1,\"other\"]],\"queue_pending\":[[2,\"mine\"]]}"));server.enqueue(new MockResponse().setBody("{}"));server.start();new ComfyClient(server.url("/").toString()).cancel("mine");assertEquals("/queue",server.takeRequest().getPath());var request=server.takeRequest();assertEquals("/queue",request.getPath());assertEquals("{\"delete\":[\"mine\"]}",request.getBody().readUtf8());}
 }
 @Test void clientReadsRealWebSocketMessages()throws Exception{
  try(var server=new MockWebServer()){
   server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onOpen(WebSocket ws,Response r){ws.send("{\"type\":\"progress\",\"data\":{\"prompt_id\":\"p\",\"value\":1,\"max\":2}}");}}));server.start();
   var received=new LinkedBlockingQueue<com.fasterxml.jackson.databind.JsonNode>();var ws=new ComfyClient(server.url("/").toString()).connect("client",received::add);var m=received.poll(3,TimeUnit.SECONDS);assertNotNull(m);assertEquals("progress",m.path("type").asText());ws.abort();
  }
 }

 @Test void timeoutInterruptsBeforeRetry()throws Exception{
  String id=task();s.transition(id,State.SUBMITTED,"test");s.db.update("update cb_task set prompt_id='slow',attempt=1,started_at=? where id=?",System.currentTimeMillis()-5000,id);
  try(var server=new MockWebServer()){
   server.enqueue(new MockResponse().setResponseCode(503));
   server.enqueue(new MockResponse().setBody("{}"));
   String queue="{\"queue_running\":[[1,\"slow\"]],\"queue_pending\":[]}";
   server.enqueue(new MockResponse().setBody(queue));
   server.enqueue(new MockResponse().setBody("{}"));
   server.enqueue(new MockResponse().setBody(queue));
   server.enqueue(new MockResponse().setBody("{}"));
   server.enqueue(new MockResponse().setBody("{\"queue_running\":[],\"queue_pending\":[]}"));server.start();
   var w=new Worker(s,new ComfyClient(server.url("/").toString()),1,1,1,50,"target/test-storage",false);w.run(id);w.stop();
   assertNull(str(s.task(id),"prompt_id"));assertTrue(str(s.task(id),"error").contains("timeout"));
   boolean interrupted=false;for(int i=0;i<7;i++)if("/interrupt".equals(server.takeRequest(1,TimeUnit.SECONDS).getPath()))interrupted=true;assertTrue(interrupted);
  }
 }
 @Test void networkFailurePreservesPromptForReconciliation()throws Exception{
  String id=task();s.transition(id,State.SUBMITTED,"test");s.db.update("update cb_task set prompt_id='existing',attempt=1,started_at=? where id=?",System.currentTimeMillis(),id);
  try(var server=new MockWebServer()){
   server.enqueue(new MockResponse().setResponseCode(503));server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));server.start();
   var w=new Worker(s,new ComfyClient(server.url("/").toString()),1,60,1,50,"target/test-storage",false);w.run(id);w.stop();assertEquals("existing",str(s.task(id),"prompt_id"));assertEquals(1,num(s.task(id),"attempt"));assertEquals("SUBMITTED",str(s.task(id),"state"));
  }
 }
}
