import pathlib,sys,json
root=pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'.tools/mysql-probe'))
import pymysql
v={}
for line in (root/'.env').read_text().splitlines():
 line=line.strip()
 if line and not line.startswith('#') and '=' in line:
  k,val=line.split('=',1);v[k.strip()]=val.strip().strip('\"\'')
result={'config_keys_present':all(k in v for k in ['DB_HOST','DB_PORT','DB_NAME','DB_USER','DB_PASSWORD'])}
try:
 c=pymysql.connect(host=v['DB_HOST'],port=int(v['DB_PORT']),user=v['DB_USER'],password=v['DB_PASSWORD'],database=v['DB_NAME'],connect_timeout=8)
 with c.cursor() as cur:
  cur.execute('SELECT VERSION(), 1');version,value=cur.fetchone();result.update(status='PASS',version=version,select_1=value)
  cur.execute('SHOW TABLES');result['tables']=[r[0] for r in cur.fetchall()]
 c.close()
except Exception as e:result.update(status='FAIL',error_type=type(e).__name__,error_code=e.args[0] if e.args and isinstance(e.args[0],int) else None)
(root/'reports/evidence/mysql-probe.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
