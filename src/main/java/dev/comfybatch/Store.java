package dev.comfybatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
@Component
public class Store {
 final JdbcTemplate db; final TransactionTemplate tx;
 public Store(JdbcTemplate db,TransactionTemplate tx){this.db=db;this.tx=tx;}
 public Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw new Missing();return rows.getFirst();}
 public Map<String,Object> task(String id){return one("select * from cb_task where id=?",id);}
 public static String str(Map<String,Object> m,String k){Object o=m.get(k);return o==null?null:o.toString();}
 public static long num(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
 public static class Missing extends RuntimeException {}
 public synchronized void transition(String id,State next,String reason){tx.executeWithoutResult(t->{var row=task(id);var old=State.valueOf(str(row,"state"));if(!old.permits(next))throw new IllegalStateException("Illegal transition "+old+" -> "+next);db.update("update cb_task set state=?,updated_at=? where id=?",next.name(),System.currentTimeMillis(),id);event(id,old.name(),next.name(),reason);});}
 public void event(String id,String from,String to,String reason){db.update("insert into cb_event(id,task_id,from_state,to_state,reason,happened_at) values(?,?,?,?,?,?)",UUID.randomUUID().toString(),id,from,to,reason,System.currentTimeMillis());}
 public synchronized void running(String id,int progress){var r=task(id);State state=State.valueOf(str(r,"state"));if(state.terminal())return;if(state==State.SUBMITTED)transition(id,State.RUNNING,"ComfyUI execution event");db.update("update cb_task set progress=?,updated_at=? where id=?",Math.max(0,Math.min(99,progress)),System.currentTimeMillis(),id);}
}
