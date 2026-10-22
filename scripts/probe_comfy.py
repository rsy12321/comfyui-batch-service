import ast, asyncio, json, pathlib, hashlib, time
import aiohttp
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'reports/evidence'
async def main():
 src=pathlib.Path('/path/to/ComfyUI/script_examples/websockets_api_example.py').read_text()
 tree=ast.parse(src)
 prompt=json.loads(next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='prompt_text' for t in n.targets)))
 prompt['4']['inputs']['ckpt_name']='sd_xl_base_1.0.safetensors'
 request={'prompt':prompt,'client_id':'comfybatch-step0'}
 (OUT/'step0-prompt-request.json').write_text(json.dumps(request,indent=2))
 async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=900)) as s:
  async with s.ws_connect('http://127.0.0.1:8188/ws?clientId=comfybatch-step0') as ws:
   async with s.post('http://127.0.0.1:8188/prompt',json=request) as r:
    response=await r.json();(OUT/'step0-prompt-response.json').write_text(json.dumps({'http_status':r.status,'body':response},indent=2));r.raise_for_status()
   pid=response['prompt_id'];deadline=time.monotonic()+900
   with (OUT/'step0-ws.jsonl').open('w') as log:
    while time.monotonic()<deadline:
     msg=await ws.receive(timeout=900)
     if msg.type==aiohttp.WSMsgType.TEXT:
      log.write(msg.data+'\n');log.flush();m=json.loads(msg.data)
      if m.get('data',{}).get('prompt_id')==pid and (m['type']=='execution_error' or (m['type']=='executing' and m['data'].get('node') is None)):break
   async with s.get('http://127.0.0.1:8188/history/'+pid) as r:
    history=await r.json();(OUT/'step0-history.json').write_text(json.dumps(history,indent=2));r.raise_for_status()
   manifest=[]
   for node in history[pid]['outputs'].values():
    for im in node.get('images',[]):
     async with s.get('http://127.0.0.1:8188/view',params=im) as r:
      data=await r.read();r.raise_for_status();p=OUT/('step0-'+im['filename']);p.write_bytes(data)
      manifest.append({'file':str(p.relative_to(ROOT)),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'http_status':r.status})
   (OUT/'step0-result.json').write_text(json.dumps({'status':history[pid].get('status'),'files':manifest},indent=2));print(json.dumps({'status':history[pid].get('status',{}).get('status_str'),'image_count':len(manifest)}))
asyncio.run(main())
