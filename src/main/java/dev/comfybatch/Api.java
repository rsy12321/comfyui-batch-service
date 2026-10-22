package dev.comfybatch;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
import java.util.*;
import static dev.comfybatch.Store.*;
@RestController @RequestMapping("/api")
public class Api {
 final Store s;final JobService jobs;final Worker worker;
 public Api(Store s,JobService jobs,Worker worker){this.s=s;this.jobs=jobs;this.worker=worker;}
 @PostMapping("/templates") @ResponseStatus(HttpStatus.CREATED) public Object template(@RequestBody JobService.Template t){return jobs.template(t);}
 @GetMapping("/templates") public Object templates(){return s.db.queryForList("select id,name,created_at from cb_template order by created_at desc");}
 @PostMapping("/jobs") @ResponseStatus(HttpStatus.ACCEPTED) public Object submit(@RequestBody JobService.Submit r,@RequestHeader(value="Idempotency-Key",required=false) String key){return jobs.submit(r,key);}
 @GetMapping("/jobs/{id}") public Object detail(@PathVariable String id){return jobs.detail(id);}
 @GetMapping("/jobs") public Object list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) State state){
  if(page<0||page>100000||size<1||size>100)throw JobService.bad("Invalid pagination");
  String filter=state==null?"":" where exists(select 1 from cb_task t where t.job_id=j.id and t.state=?)";
  var args=new ArrayList<Object>();if(state!=null)args.add(state.name());long total=s.db.queryForObject("select count(*) from cb_job j"+filter,Long.class,args.toArray());args.add(size);args.add((long)page*size);
  var items=s.db.queryForList("select j.id from cb_job j"+filter+" order by j.created_at desc,j.id limit ? offset ?",args.toArray()).stream().map(x->jobs.detail(str(x,"id"))).toList();return Map.of("page",page,"size",size,"total",total,"items",items);
 }
 @GetMapping("/tasks/{id}") public Object task(@PathVariable String id){var row=new LinkedHashMap<>(s.task(id));row.remove("workflow");row.put("events",s.db.queryForList("select * from cb_event where task_id=? order by happened_at,id",id));row.put("attempts",s.db.queryForList("select * from cb_attempt where task_id=? order by attempt_no",id));row.put("results",s.db.queryForList("select * from cb_result where task_id=?",id));return row;}
 @PostMapping("/tasks/{id}/cancel") @ResponseStatus(HttpStatus.ACCEPTED) public Object cancel(@PathVariable String id){worker.cancel(id);return task(id);}
 @PostMapping("/jobs/{id}/cancel") @ResponseStatus(HttpStatus.ACCEPTED) public Object cancelJob(@PathVariable String id){s.one("select id from cb_job where id=?",id);for(var t:s.db.queryForList("select id from cb_task where job_id=?",id))worker.cancel(str(t,"id"));return jobs.detail(id);}
 @GetMapping("/results/{id}/file") public ResponseEntity<FileSystemResource> download(@PathVariable String id){var r=s.one("select * from cb_result where id=?",id);var p=worker.storage.resolve(str(r,"path")).normalize();if(!p.startsWith(worker.storage)||!java.nio.file.Files.isRegularFile(p))throw new Store.Missing();return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("Content-Disposition","attachment; filename=\""+id+".png\"").body(new FileSystemResource(p));}
 @ExceptionHandler(Store.Missing.class) @ResponseStatus(HttpStatus.NOT_FOUND) public Object missing(){return Map.of("error","Not found");}
 @ExceptionHandler({IllegalArgumentException.class,ArithmeticException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST) public Object bad(){return Map.of("error","Invalid request");}
}
