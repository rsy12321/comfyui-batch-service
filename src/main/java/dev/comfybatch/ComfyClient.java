package dev.comfybatch;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
@Component
public class ComfyClient {
 final String base; final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 public ComfyClient(@Value("${app.comfy-url}") String base){this.base=base.replaceAll("/$","");}
 JsonNode request(String method,String path,Object body)throws Exception{
  var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15));
  if(body==null)b.GET();else b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(Json.write(body)));
  var r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()/100!=2)throw new RemoteError("HTTP "+r.statusCode()+" "+r.body());return Json.read(r.body());
 }
 public JsonNode submit(JsonNode workflow,String prompt,String client)throws Exception{return request("POST","/prompt",Map.of("prompt",workflow,"prompt_id",prompt,"client_id",client));}
 public JsonNode history(String prompt)throws Exception{return request("GET","/history/"+prompt,null).path(prompt);}
 public JsonNode queue()throws Exception{return request("GET","/queue",null);}
 public boolean queued(JsonNode queue,String prompt,String which){for(var item:queue.path(which))if(prompt.equals(item.path(1).asText()))return true;return false;}
 public void cancel(String prompt)throws Exception{
  var q=queue();if(queued(q,prompt,"queue_running"))request("POST","/interrupt",Map.of());
  else if(queued(q,prompt,"queue_pending"))request("POST","/queue",Map.of("delete",List.of(prompt)));
 }
 public byte[] image(JsonNode image)throws Exception{
  var params=new ArrayList<String>();for(String k:List.of("filename","subfolder","type"))params.add(k+"="+URLEncoder.encode(image.path(k).asText(),StandardCharsets.UTF_8));
  var req=HttpRequest.newBuilder(URI.create(base+"/view?"+String.join("&",params))).timeout(Duration.ofSeconds(30)).GET().build();
  var r=http.send(req,HttpResponse.BodyHandlers.ofByteArray());if(r.statusCode()!=200)throw new RemoteError("view HTTP "+r.statusCode());return r.body();
 }
 public WebSocket connect(String client,Consumer<JsonNode> callback)throws Exception{
  var uri=URI.create(base.replaceFirst("^http","ws")+"/ws?clientId="+client);
  return http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(uri,new WebSocket.Listener(){
   final StringBuilder buffer=new StringBuilder();
   public void onOpen(WebSocket ws){ws.request(1);}
   public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){buffer.append(data);if(last){try{callback.accept(Json.read(buffer.toString()));}catch(RuntimeException ignored){}buffer.setLength(0);}ws.request(1);return null;}
   public CompletionStage<?> onBinary(WebSocket ws,java.nio.ByteBuffer data,boolean last){ws.request(1);return null;}
  }).get(6,TimeUnit.SECONDS);
 }
 public static class RemoteError extends Exception { public RemoteError(String message){super(message);} }
}
