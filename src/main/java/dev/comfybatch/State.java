package dev.comfybatch;
public enum State {
 PENDING,SUBMITTED,RUNNING,SUCCEEDED,FAILED,CANCELLED;
 public boolean terminal(){return this==SUCCEEDED||this==FAILED||this==CANCELLED;}
 public boolean permits(State next){
  if(terminal())return false;
  return next==CANCELLED||next==FAILED||(this==PENDING&&next==SUBMITTED)||(this==SUBMITTED&&(next==RUNNING||next==SUCCEEDED))||(this==RUNNING&&next==SUCCEEDED);
 }
}
