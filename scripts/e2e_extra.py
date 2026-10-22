import e2e as e
import os,time,json,urllib.request,urllib.error
os.environ['CONCURRENCY']='2'
e.OUT=e.ROOT/'reports/evidence/extra';e.OUT.mkdir(exist_ok=True);results={}
try:
 e.start()
 for path,expected in [('/api/jobs',401),('/v3/api-docs',200)]:
  try:
   with urllib.request.urlopen(e.BASE+path) as r:status=r.status;body=r.read()
  except urllib.error.HTTPError as ex:status=ex.code;body=ex.read()
  assert status==expected
  (e.OUT/('auth.json' if expected==401 else 'openapi.json')).write_bytes(body)
  results[path]={'status':status,'result':'PASS'}
 template=json.loads((e.ROOT/'examples/template.json').read_text());t=e.api('POST','/api/templates',template)['id'];job,_=e.submit(t,2,[501,502],{'steps':80})
 deadline=time.monotonic()+60
 while time.monotonic()<deadline:
  detail=e.api('GET','/api/jobs/'+job['id']);tasks=detail['tasks']
  if len([x for x in tasks if x['state']=='RUNNING'])==1 and len([x for x in tasks if x['state']=='SUBMITTED'])==1:break
  time.sleep(.5)
 else:raise AssertionError('Did not see remote pending task')
 pending=next(t for t in tasks if t['state']=='SUBMITTED');running=next(t for t in tasks if t['state']=='RUNNING')
 with urllib.request.urlopen('http://127.0.0.1:8188/queue') as r:before=json.load(r)
 assert any(item[1]==pending['prompt_id'] for item in before['queue_pending'])
 (e.OUT/'comfy-queue-before.json').write_text(json.dumps(before,indent=2))
 e.api('POST','/api/tasks/'+pending['id']+'/cancel',{})
 for _ in range(60):
  cancelled=e.api('GET','/api/tasks/'+pending['id'])
  if cancelled['state']=='CANCELLED':break
  time.sleep(.5)
 assert cancelled['state']=='CANCELLED'
 with urllib.request.urlopen('http://127.0.0.1:8188/queue') as r:after=json.load(r)
 assert not any(item[1]==pending['prompt_id'] for item in after['queue_pending'])
 (e.OUT/'comfy-queue-after.json').write_text(json.dumps(after,indent=2));results['remote_pending_delete']='PASS'
 e.api('POST','/api/tasks/'+running['id']+'/cancel',{})
 for _ in range(60):
  cancelled=e.api('GET','/api/tasks/'+running['id'])
  if cancelled['state']=='CANCELLED':break
  time.sleep(.5)
 assert cancelled['state']=='CANCELLED';results['running_interrupt']='PASS'
 e.api('GET','/api/jobs?page=0&size=1&state=CANCELLED');results['filtered_pagination']='PASS';results['job_id']=job['id']
finally:
 e.stop();(e.OUT/'summary.json').write_text(json.dumps(results,indent=2));print(json.dumps(results))
