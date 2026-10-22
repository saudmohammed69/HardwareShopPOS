// Loopback-only review server. No customer mutations or backend forwarding.
import http from 'node:http';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
export async function serve(directory,port=4324){
 const root=path.resolve(directory),headers={};
 for(const line of (await readFile(path.join(root,'_headers'),'utf8')).split('\n')){
  const match=line.match(/^  ([\w-]+): (.*)$/);if(match)headers[match[1]]=match[2];
 }
 const server=http.createServer(async(req,res)=>{
  if(req.headers.host!==`127.0.0.1:${port}`){res.writeHead(403);res.end();return;}
  if(!['GET','HEAD'].includes(req.method)){res.writeHead(405,{...headers,Allow:'GET, HEAD'});res.end();return;}
  const pathname=new URL(req.url,`http://127.0.0.1:${port}`).pathname;
  const file=pathname==='/'?'index.html':pathname.slice(1);
  if(!/^[a-z0-9_.-]+$/.test(file)){res.writeHead(404,headers);res.end();return;}
  try{const bytes=await readFile(path.join(root,file)),type={'.html':'text/html; charset=utf-8','.mjs':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json'}[path.extname(file)]??'text/plain';res.writeHead(200,{...headers,'Content-Type':type});res.end(req.method==='HEAD'?undefined:bytes);}
  catch{res.writeHead(404,headers);res.end('Not available. Ordering is unavailable.');}
 });
 await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve);});
 return {origin:`http://127.0.0.1:${port}`,close:()=>new Promise(resolve=>server.close(resolve))};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const instance=await serve(process.argv[2]??path.join(path.dirname(fileURLToPath(import.meta.url)),'build'));
 console.log('Local static review: '+instance.origin);
 for(const sig of ['SIGINT','SIGTERM'])process.on(sig,async()=>{await instance.close();process.exit(0);});
}
