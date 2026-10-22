package dev.comfybatch;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.file.*;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.*;
import static dev.comfybatch.Store.*;
@Component
public class Worker {
 final Store s;final ComfyClient comfy;final int concurrency;final long timeout,retryBase,poll;final Path storage;final boolean enabled;
 final Set<String> active=ConcurrentHashMap.newKeySet();final ExecutorService pool=Executors.newVirtualThreadPerTaskExecutor();volatile boolean closing;
 public Worker(Store s,ComfyClient comfy,@Value("${app.concurrency}") int concurrency,@Value("${app.timeout-seconds}") long timeout,@Value("${app.retry-base-ms}") long retryBase,@Value("${app.poll-ms}") long poll,@Value("${app.storage}") String storage,@Value("${app.worker-enabled}") boolean enabled){
  if(concurrency<1||concurrency>16||timeout<1||retryBase<1||poll<50)throw new IllegalArgumentException("Invalid worker configuration");
  this.s=s;this.comfy=comfy;this.concurrency=concurrency;this.timeout=timeout*1000;this.retryBase=retryBase;this.poll=poll;this.storage=Path.of(storage).toAbsolutePath().normalize();this.enabled=enabled;
 }
 @Scheduled(fixedDelay=300)
 public void tick(){if(!enabled||closing)return;for(var r:s.db.queryForList("select id from cb_task where state in ('PENDING','SUBMITTED','RUNNING') and next_run_at<=? order by created_at,ordinal_no",System.currentTimeMillis())){if(active.size()>=concurrency)break;String id=str(r,"id");if(active.add(id))pool.submit(()->{try{run(id);}finally{active.remove(id);}});}}
 void run(String id){WebSocket ws=null;String prompt=null;try{
  var row=s.task(id);if(State.valueOf(str(row,"state")).terminal())return;
  prompt=str(row,"prompt_id");String client=id;
  try{ws=comfy.connect(client,m->onMessage(id,m));}catch(Exception ignored){/* history polling is authoritative, even while WS is available */}
  if(prompt!=null){
   var history=comfy.history(prompt);if(!history.isMissingNode()){complete(id,prompt,history);return;}
   var q=comfy.queue();
   if(!comfy.queued(q,prompt,"queue_running")&&!comfy.queued(q,prompt,"queue_pending")){
    // A crash before the HTTP request is indistinguishable from an accepted but lost prompt.
    throw new ComfyClient.RemoteError("Recovery: prompt absent from history and queue; retry with recorded new attempt");
   }
   s.event(id,str(row,"state"),str(row,"state"),"Recovered existing ComfyUI prompt "+prompt);
  } else {
   synchronized(s){row=s.task(id);if(State.valueOf(str(row,"state")).terminal())return;if(Boolean.TRUE.equals(row.get("cancel_requested"))){s.transition(id,State.CANCELLED,"Cancelled before submit");return;}
    prompt=UUID.randomUUID().toString();int attempt=(int)num(row,"attempt")+1;long now=System.currentTimeMillis();String p=prompt;
    s.tx.executeWithoutResult(tx->{s.db.update("update cb_task set prompt_id=?,attempt=?,started_at=?,progress=0 where id=?",p,attempt,now,id);s.db.update("insert into cb_attempt(id,task_id,attempt_no,prompt_id,started_at) values(?,?,?,?,?)",UUID.randomUUID().toString(),id,attempt,p,now);if("PENDING".equals(str(s.task(id),"state")))s.transition(id,State.SUBMITTED,"Submission intent persisted before HTTP");});
    // Serialize local cancellation with submission, avoiding a queue deletion before acceptance.
    var response=comfy.submit(Json.read(str(row,"workflow")),prompt,client);
    if(!prompt.equals(response.path("prompt_id").asText()))throw new ComfyClient.RemoteError("ComfyUI did not preserve prompt_id");
   }
  }
  while(!closing){
   row=s.task(id);if(State.valueOf(str(row,"state")).terminal())return;
   var history=comfy.history(prompt);if(!history.isMissingNode()){complete(id,prompt,history);return;}
   if(Boolean.TRUE.equals(row.get("cancel_requested"))){comfy.cancel(prompt);var q=comfy.queue();if(!comfy.queued(q,prompt,"queue_running")&&!comfy.queued(q,prompt,"queue_pending")){s.transition(id,State.CANCELLED,"Cancellation confirmed by queue");return;}}
   if(System.currentTimeMillis()-num(row,"started_at")>timeout){comfy.cancel(prompt);throw new ComfyClient.RemoteError("Task attempt timeout");}
   Thread.sleep(poll);
  }
 }catch(InterruptedException e){Thread.currentThread().interrupt();}
 catch(Exception e){if(!closing){
   // Transport failures must not cause duplicate generation while the old prompt is live.
   if(prompt!=null && !(e instanceof ComfyClient.RemoteError && e.getMessage().startsWith("HTTP 4"))){
    try{var q=comfy.queue();if(comfy.queued(q,prompt,"queue_running")||comfy.queued(q,prompt,"queue_pending")){defer(id,"ComfyUI prompt still queued/running; continue reconciliation");return;}}
    catch(Exception unreachable){defer(id,"ComfyUI unavailable; preserve prompt_id for recovery");return;}
   }
   failure(id,e);
  }}
 finally{if(ws!=null)ws.abort();}}
 void onMessage(String id,JsonNode m){var d=m.path("data");String expected=str(s.task(id),"prompt_id");if(expected==null||!expected.equals(d.path("prompt_id").asText()))return;
  switch(m.path("type").asText()){
   case "progress" -> {int max=d.path("max").asInt();if(max>0)s.running(id,(int)(100L*d.path("value").asInt()/max));}
   case "executing" -> {if(!d.path("node").isNull())s.running(id,0);}
   case "executed" -> s.running(id,99);
   default -> {}
  }
 }
 void complete(String id,String prompt,JsonNode history)throws Exception{
  var row=s.task(id);if(State.valueOf(str(row,"state")).terminal())return;
  String state=history.path("status").path("status_str").asText();
  if(!state.equals("success")){
   if(Boolean.TRUE.equals(row.get("cancel_requested"))){s.transition(id,State.CANCELLED,"ComfyUI interrupted; "+history.path("status"));return;}
   throw new ComfyClient.RemoteError("Execution failed: "+history.path("status"));
  }
  var results=new ArrayList<Map<String,Object>>();int index=0;
  for(var output:history.path("outputs"))for(var im:output.path("images")){
   byte[] data=comfy.image(im);if(data.length<8||data[0]!=(byte)137||data[1]!=80||data[2]!=78||data[3]!=71)throw new ComfyClient.RemoteError("Output is not PNG");
   var image=ImageIO.read(new ByteArrayInputStream(data));if(image==null)throw new ComfyClient.RemoteError("Unreadable PNG");
   String relative=id+"/"+prompt+"-"+(index++)+".png";Path path=storage.resolve(relative);Files.createDirectories(path.getParent());Path temp=path.resolveSibling(path.getFileName()+".part");Files.write(temp,data);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);
   results.add(Map.of("path",relative,"sha",Json.hash(data),"width",image.getWidth(),"height",image.getHeight()));
  }
  if(results.isEmpty())throw new ComfyClient.RemoteError("No output images in successful history");
  synchronized(s){if(State.valueOf(str(s.task(id),"state")).terminal())return;s.tx.executeWithoutResult(tx->{
   for(var result:results)if(s.db.queryForObject("select count(*) from cb_result where path=?",Integer.class,result.get("path"))==0)s.db.update("insert into cb_result values(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,result.get("path"),result.get("sha"),result.get("width"),result.get("height"),num(row,"seed"));
   s.db.update("update cb_task set progress=100,error=null where id=?",id);s.db.update("update cb_attempt set ended_at=? where prompt_id=?",System.currentTimeMillis(),prompt);s.transition(id,State.SUCCEEDED,"History success; PNG downloaded and hashed");
  });}
 }
 void defer(String id,String reason){synchronized(s){var row=s.task(id);if(State.valueOf(str(row,"state")).terminal())return;s.db.update("update cb_task set next_run_at=?,error=? where id=?",System.currentTimeMillis()+Math.max(1000,poll),reason,id);s.event(id,str(row,"state"),str(row,"state"),reason);}}
 static long backoff(long base,int attempt){return base*(1L<<Math.min(10,Math.max(0,attempt-1)));}
 void failure(String id,Exception error){synchronized(s){var row=s.task(id);if(State.valueOf(str(row,"state")).terminal())return;
  String message=error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),"unknown");if(message.length()>30000)message=message.substring(0,30000);
  int attempt=(int)num(row,"attempt");String msg=message;long now=System.currentTimeMillis();
  s.tx.executeWithoutResult(tx->{s.db.update("update cb_attempt set error=?,ended_at=? where task_id=? and attempt_no=?",msg,now,id,attempt);s.db.update("update cb_task set error=?,updated_at=? where id=?",msg,now,id);
   if(Boolean.TRUE.equals(row.get("cancel_requested"))){s.event(id,str(row,"state"),str(row,"state"),"Cancellation pending: "+msg);s.db.update("update cb_task set next_run_at=? where id=?",now+backoff(retryBase,1),id);}
   else if(attempt>=4){s.transition(id,State.FAILED,"Initial attempt and 3 retries exhausted: "+msg);}
   else{s.db.update("update cb_task set prompt_id=null,next_run_at=? where id=?",now+backoff(retryBase,Math.max(1,attempt)),id);s.event(id,str(row,"state"),str(row,"state"),"Retry scheduled: "+msg);}
  });
 }}
 public void cancel(String id){synchronized(s){var row=s.task(id);if(State.valueOf(str(row,"state")).terminal())return;s.db.update("update cb_task set cancel_requested=true,next_run_at=? where id=?",System.currentTimeMillis(),id);if(str(row,"prompt_id")==null)s.transition(id,State.CANCELLED,"Cancelled local pending task");else s.event(id,str(row,"state"),str(row,"state"),"Cancellation requested");}}
 @PreDestroy public void stop(){closing=true;pool.shutdownNow();}
}
