package dev.comfybatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static dev.comfybatch.Store.*;
@Service
public class JobService {
 final Store s;
 public JobService(Store s){this.s=s;}
 public record Mapping(String nodeId,String inputKey){}
 public record Template(String name,JsonNode workflow,Map<String,Mapping> mappings){}
 public record Submit(String templateId,Map<String,JsonNode> parameters,int count,List<Long> seeds){}
 public Map<String,Object> template(Template t){
  if(t.name()==null||t.name().isBlank()||t.name().length()>200||t.workflow()==null||!t.workflow().isObject()||t.mappings()==null)throw bad("Invalid template");
  for(String k:List.of("prompt","negative_prompt","seed","width","height","steps"))if(!t.mappings().containsKey(k))throw bad("Missing mapping: "+k);
  t.mappings().forEach((k,m)->{if(m==null||m.nodeId()==null||m.inputKey()==null||!t.workflow().path(m.nodeId()).path("inputs").has(m.inputKey()))throw bad("Invalid mapping: "+k);});
  String id=UUID.randomUUID().toString();s.db.update("insert into cb_template values(?,?,?,?,?)",id,t.name(),Json.write(t.workflow()),Json.write(t.mappings()),System.currentTimeMillis());return Map.of("id",id);
 }
 public synchronized Map<String,Object> submit(Submit r,String key){
  if(r.count()<1||r.count()>100||r.parameters()==null||r.templateId()==null)throw bad("count must be 1..100 and parameters required");
  if(key!=null&&(key.isBlank()||key.length()>128))throw bad("Invalid Idempotency-Key");
  if(r.seeds()!=null&&(r.seeds().size()!=r.count()||r.seeds().stream().anyMatch(x->x==null||x<0)))throw bad("seeds must contain count nonnegative longs");
  String hash=Json.hashRequest(r);
  return s.tx.execute(tx->{
   if(key!=null){var rows=s.db.queryForList("select * from cb_job where idempotency_key=?",key);if(!rows.isEmpty()){var old=rows.getFirst();if(!hash.equals(str(old,"request_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Key reused for a different request");return Map.<String,Object>of("id",str(old,"id"),"replayed",true);}}
   var t=s.one("select * from cb_template where id=?",r.templateId());var mappings=Json.read(str(t,"mappings"));
   for(var k:r.parameters().keySet())if(!mappings.has(k))throw bad("Unknown parameter: "+k);
   var base=Json.read(str(t,"workflow"));r.parameters().forEach((k,v)->{validate(k,v);var m=mappings.get(k);((ObjectNode)base.path(m.path("nodeId").asText()).path("inputs")).set(m.path("inputKey").asText(),v);});
   String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();s.db.update("insert into cb_job values(?,?,?,?,?)",id,r.templateId(),key,hash,now);
   for(int i=0;i<r.count();i++){
    long seed=r.seeds()!=null?r.seeds().get(i):(r.parameters().containsKey("seed")?Math.addExact(r.parameters().get("seed").longValue(),i):java.util.concurrent.ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
    var workflow=base.deepCopy();var m=mappings.get("seed");((ObjectNode)workflow.path(m.path("nodeId").asText()).path("inputs")).put(m.path("inputKey").asText(),seed);
    String task=UUID.randomUUID().toString();s.db.update("insert into cb_task(id,job_id,ordinal_no,state,workflow,seed,created_at,updated_at,next_run_at) values(?,?,?,?,?,?,?,?,?)",task,id,i,"PENDING",Json.write(workflow),seed,now,now,now);s.event(task,null,"PENDING","Batch created");
   }
   return Map.<String,Object>of("id",id,"replayed",false);
  });
 }
 static void validate(String k,JsonNode v){
  if(v==null||v.isNull())throw bad("Null parameter");
  if(Set.of("prompt","negative_prompt").contains(k)&&(!v.isTextual()||v.textValue().length()>10000))throw bad("Invalid text");
  if(Set.of("width","height","steps","seed").contains(k)&&(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0))throw bad("Invalid numeric parameter");
  if(Set.of("width","height").contains(k)&&(v.longValue()<64||v.longValue()>2048||v.longValue()%8!=0))throw bad("Dimensions must be multiples of 8 in 64..2048");
  if(k.equals("steps")&&(v.longValue()<1||v.longValue()>100))throw bad("steps must be 1..100");
 }
 static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
 public Map<String,Object> detail(String id){var j=new LinkedHashMap<>(s.one("select id,template_id,created_at from cb_job where id=?",id));var tasks=s.db.queryForList("select id,ordinal_no,state,seed,progress,attempt,prompt_id,error,cancel_requested,created_at,updated_at from cb_task where job_id=? order by ordinal_no",id);j.put("tasks",tasks);j.put("state",aggregate(tasks));return j;}
 static String aggregate(List<Map<String,Object>> tasks){var states=tasks.stream().map(x->str(x,"state")).toList();if(states.stream().allMatch("SUCCEEDED"::equals))return "SUCCEEDED";if(states.stream().anyMatch(x->x.equals("RUNNING")||x.equals("SUBMITTED")))return "RUNNING";if(states.contains("PENDING"))return "PENDING";return states.contains("FAILED")?"FAILED":"CANCELLED";}
}
