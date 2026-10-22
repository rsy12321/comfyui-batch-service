package dev.comfybatch;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.comfybatch.Store.*;
@SpringBootTest @ActiveProfiles("test")
class ServiceTest {
 @Autowired Store s;@Autowired JobService jobs;@Autowired Worker worker;
 @BeforeEach void reset(){for(String t:List.of("cb_result","cb_attempt","cb_event","cb_task","cb_job","cb_template"))s.db.update("delete from "+t);}
 static JobService.Template template(){var w=Json.read("{\"1\":{\"class_type\":\"TestNode\",\"inputs\":{\"prompt\":\"p\",\"negative_prompt\":\"n\",\"seed\":1,\"width\":512,\"height\":512,\"steps\":20}}}");var m=new HashMap<String,JobService.Mapping>();for(String k:List.of("prompt","negative_prompt","seed","width","height","steps"))m.put(k,new JobService.Mapping("1",k));return new JobService.Template("test",w,m);}
 String job(){String template=jobs.template(template()).get("id").toString();return jobs.submit(new JobService.Submit(template,Map.of(),1,List.of(11L)),null).get("id").toString();}
 String task(){return str(s.one("select id from cb_task where job_id=?",job()),"id");}
 @Test void stateMachine(){assertTrue(State.PENDING.permits(State.SUBMITTED));assertFalse(State.PENDING.permits(State.SUCCEEDED));assertTrue(State.SUBMITTED.permits(State.RUNNING));assertTrue(State.RUNNING.permits(State.SUCCEEDED));for(State terminal:List.of(State.SUCCEEDED,State.FAILED,State.CANCELLED))for(State n:State.values())assertFalse(terminal.permits(n));String id=task();assertThrows(IllegalStateException.class,()->s.transition(id,State.SUCCEEDED,"bad"));s.transition(id,State.SUBMITTED,"submitted");s.transition(id,State.RUNNING,"running");s.transition(id,State.SUCCEEDED,"done");assertEquals(4,s.db.queryForObject("select count(*) from cb_event where task_id=?",Integer.class,id));}
 @Test void idempotencyAndConflict(){String t=jobs.template(template()).get("id").toString();var r=new JobService.Submit(t,Map.of(),5,List.of(1L,2L,3L,4L,5L));var first=jobs.submit(r,"same");var repeat=jobs.submit(r,"same");assertEquals(first.get("id"),repeat.get("id"));assertEquals(true,repeat.get("replayed"));assertEquals(5,s.db.queryForObject("select count(*) from cb_task",Integer.class));assertThrows(ResponseStatusException.class,()->jobs.submit(new JobService.Submit(t,Map.of(),1,List.of(3L)),"same"));}
 @Test void retriesAreThreeAfterInitial(){String id=task();s.transition(id,State.SUBMITTED,"test");for(int attempt=1;attempt<=4;attempt++){s.db.update("update cb_task set attempt=? where id=?",attempt,id);worker.failure(id,new Exception("node_errors: invalid node"));assertEquals(attempt==4?"FAILED":"SUBMITTED",str(s.task(id),"state"));}assertEquals(1000,Worker.backoff(1000,1));assertEquals(2000,Worker.backoff(1000,2));assertEquals(4000,Worker.backoff(1000,3));assertTrue(str(s.task(id),"error").contains("node_errors"));}
 @Test void wsParsesOnlyOwnPrompt(){String id=task();s.transition(id,State.SUBMITTED,"test");s.db.update("update cb_task set prompt_id='p' where id=?",id);worker.onMessage(id,Json.read("{\"type\":\"progress\",\"data\":{\"prompt_id\":\"foreign\",\"value\":8,\"max\":10}}"));assertEquals(0,num(s.task(id),"progress"));worker.onMessage(id,Json.read("{\"type\":\"executing\",\"data\":{\"prompt_id\":\"p\",\"node\":\"1\"}}"));assertEquals("RUNNING",str(s.task(id),"state"));worker.onMessage(id,Json.read("{\"type\":\"progress\",\"data\":{\"prompt_id\":\"p\",\"value\":5,\"max\":20}}"));assertEquals(25,num(s.task(id),"progress"));worker.onMessage(id,Json.read("{\"type\":\"executed\",\"data\":{\"prompt_id\":\"p\"}}"));assertEquals(99,num(s.task(id),"progress"));}
 @Test void localCancelIsTerminal(){String id=task();worker.cancel(id);assertEquals("CANCELLED",str(s.task(id),"state"));worker.cancel(id);worker.failure(id,new Exception("late"));assertEquals("CANCELLED",str(s.task(id),"state"));}
 @Test void validationRollsBackWholeBatch(){String t=jobs.template(template()).get("id").toString();assertThrows(ResponseStatusException.class,()->jobs.submit(new JobService.Submit(t,Map.of("width",Json.read("63")),2,null),null));assertEquals(0,s.db.queryForObject("select count(*) from cb_job",Integer.class));}
 @Test void mappingAndSeedsAreApplied(){String j=job();var r=s.one("select * from cb_task where job_id=?",j);assertEquals(11,Json.read(str(r,"workflow")).path("1").path("inputs").path("seed").asLong());}
}
