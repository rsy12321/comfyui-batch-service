"""Real MySQL + ComfyUI acceptance. Owns only the Java process it starts."""
import json,pathlib,subprocess,time,urllib.request,urllib.error,hashlib,sys,os,signal,uuid
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'reports/evidence/e2e';OUT.mkdir(parents=True,exist_ok=True)
config={}
for line in (ROOT/'.env').read_text().splitlines():
 if line.strip() and not line.strip().startswith('#') and '=' in line:
  k,v=line.split('=',1);config[k.strip()]=v.strip().strip('\"\'')
KEY=config['API_KEY'];BASE='http://127.0.0.1:8080';RUN=uuid.uuid4().hex[:12]
proc=None;seq=0;summary={};job_ids=[]
def api(method,path,body=None,key=None):
 global seq
 headers={'X-API-Key':KEY,'Content-Type':'application/json'}
 if key:headers['Idempotency-Key']=key
 req=urllib.request.Request(BASE+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
 try:
  with urllib.request.urlopen(req,timeout=25) as r:status=r.status;data=r.read();ctype=r.headers.get('Content-Type','')
 except urllib.error.HTTPError as e:status=e.code;data=e.read();ctype=e.headers.get('Content-Type','')
 response=json.loads(data) if 'json' in ctype else {'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
 seq+=1
 (OUT/f'{seq:04d}.json').write_text(json.dumps({'method':method,'path':path,'request':body,'idempotency_key':key,'status':status,'response':response},ensure_ascii=False,indent=2))
 if status>=400:raise AssertionError(f'{method} {path}: HTTP {status}')
 return data if 'image/' in ctype else response

def start():
 global proc
 f=(OUT/'service.log').open('ab');proc=subprocess.Popen([str(ROOT/'scripts/run.sh')],cwd=ROOT,stdout=f,stderr=subprocess.STDOUT);f.close()
 for _ in range(90):
  if proc.poll() is not None:raise RuntimeError('Java process exited; inspect service.log')
  try:api('GET','/api/templates');return
  except (OSError,urllib.error.URLError):time.sleep(1)
 raise TimeoutError('Java startup')

def stop(hard=False):
 global proc
 if proc and proc.poll() is None:
  proc.kill() if hard else proc.terminate()
  try:proc.wait(timeout=20)
  except subprocess.TimeoutExpired:proc.kill();proc.wait()
 proc=None

def wait_job(j,target=None):
 deadline=time.monotonic()+600
 while time.monotonic()<deadline:
  row=api('GET','/api/jobs/'+j)
  if target and any(t['state']==target for t in row['tasks']):return row
  if row['state'] in ['SUCCEEDED','FAILED','CANCELLED']:return row
  time.sleep(1)
 raise TimeoutError('Job '+j)

def submit(t,count,seeds,params=None,key=None):
 r={'templateId':t,'parameters':params or {},'count':count,'seeds':seeds};res=api('POST','/api/jobs',r,key);job_ids.append(res['id']);return res,r

def run():
 start()
 template=json.loads((ROOT/'examples/template.json').read_text());t=api('POST','/api/templates',template)['id']
 first,request=submit(t,5,[101,102,103,104,105],{'prompt':'a ceramic teapot on a wooden table, studio photograph','negative_prompt':'blurry','width':512,'height':512,'steps':20},'e2e-'+RUN)
 row=wait_job(first['id']);assert row['state']=='SUCCEEDED',row['state'];manifest=[]
 for task in row['tasks']:
  detail=api('GET','/api/tasks/'+task['id']);assert detail['state']=='SUCCEEDED';assert len(detail['results'])==1
  result=detail['results'][0];data=api('GET','/api/results/'+result['id']+'/file');assert hashlib.sha256(data).hexdigest()==result['sha256'];assert data.startswith(b'\x89PNG\r\n\x1a\n')
  assert result['seed']==task['seed'];assert (ROOT/'storage'/result['path']).is_file();manifest.append(result)
 (OUT/'png-manifest.json').write_text(json.dumps(manifest,indent=2));summary['a_batch_5']='PASS'
 repeated=api('POST','/api/jobs',request,'e2e-'+RUN);assert repeated['id']==first['id'] and repeated['replayed'];summary['b_idempotency']='PASS'
 broken=json.loads(json.dumps(template));broken['name']='Deliberately invalid KSampler node';broken['workflow']['3']['inputs']['sampler_name']='not_a_real_sampler'
 invalid_t=api('POST','/api/templates',broken)['id'];bad,_=submit(invalid_t,1,[201]);failed=wait_job(bad['id']);assert failed['state']=='FAILED';detail=api('GET','/api/tasks/'+failed['tasks'][0]['id']);assert detail['attempt']==4 and len(detail['attempts'])==4;assert 'node_errors' in detail['error'];summary['c_three_retries']='PASS'
 restart,_=submit(t,1,[301],{'steps':80,'width':768,'height':768});running=wait_job(restart['id'],'RUNNING');assert running['tasks'][0]['state']=='RUNNING';pid=running['tasks'][0]['prompt_id'];stop(True);start();done=wait_job(restart['id']);assert done['state']=='SUCCEEDED';recovered=api('GET','/api/tasks/'+done['tasks'][0]['id']);assert recovered['prompt_id']==pid and recovered['attempt']==1;summary['d_restart']='PASS'
 cancellation,_=submit(t,2,[401,402],{'steps':60});running=wait_job(cancellation['id'],'RUNNING');pending=next(x for x in running['tasks'] if x['state']=='PENDING');api('POST','/api/tasks/'+pending['id']+'/cancel',{});cancelled=api('GET','/api/tasks/'+pending['id']);assert cancelled['state']=='CANCELLED' and cancelled['attempt']==0;wait_job(cancellation['id']);summary['e_cancel_pending']='PASS'
 sys.path.insert(0,str(ROOT/'.tools/mysql-probe'));import pymysql
 conn=pymysql.connect(host=config['DB_HOST'],port=int(config['DB_PORT']),user=config['DB_USER'],password=config['DB_PASSWORD'],database=config['DB_NAME'],cursorclass=pymysql.cursors.DictCursor)
 with conn.cursor() as cur:
  unique=list(dict.fromkeys(job_ids));slots=','.join(['%s']*len(unique));cur.execute('select id,job_id,state,seed,attempt,progress,prompt_id from cb_task where job_id in ('+slots+') order by created_at,ordinal_no',unique);tasks=cur.fetchall();cur.execute('select r.* from cb_result r join cb_task t on t.id=r.task_id where t.job_id in ('+slots+')',unique);results=cur.fetchall();cur.execute('select count(*) as count from cb_job where idempotency_key=%s',('e2e-'+RUN,));assert cur.fetchone()['count']==1
 conn.close();(OUT/'database-records.json').write_text(json.dumps({'tasks':tasks,'results':results},indent=2));summary['database_cross_check']='PASS'
 summary['job_ids']=unique
if __name__ == "__main__":
 try:
  run()
 except Exception as e:
  summary['failure']={'type':type(e).__name__,'message':str(e).replace(KEY,'[REDACTED]')};raise
 finally:
  stop();(OUT/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary))
