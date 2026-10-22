// Local artifact only. No account, deploy command, cloud credentials or network publishing.
import {mkdir,readFile,writeFile,copyFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {startWeb} from './web-server.mjs';
import {root} from './runtime.mjs';
const out=path.join(root,'phase4j-local-customer-build');await mkdir(out,{recursive:true});
const files={'index.html':'web/index.html','styles.css':'web/styles.css','app.mjs':'web/app.mjs','pricing.mjs':'pricing.mjs','web-model.mjs':'web-model.mjs','submission-client.mjs':'submission-client.mjs'};
for(const [dest,source] of Object.entries(files)){if(dest.endsWith('.mjs'))execFileSync(process.execPath,['--check',path.join(root,source)]);await copyFile(path.join(root,source),path.join(out,dest));}
const app=await startWeb({port:4319});try{await writeFile(path.join(out,'catalog.json'),app.snapshot.plain);await writeFile(path.join(out,'catalog.json.gz'),app.snapshot.gzip);const response=await fetch(app.origin+'/api/public-config');await writeFile(path.join(out,'public-config.local.json'),JSON.stringify(await response.json()));
 const manifest={localOnly:true,productionReady:false,catalogVariants:app.snapshot.count,catalogGzipBytes:app.snapshot.gzip.length,files:{}};
 for(const file of [...Object.keys(files),'catalog.json','catalog.json.gz','public-config.local.json']){const bytes=await readFile(path.join(out,file));manifest.files[file]={bytes:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex')};}
 await writeFile(path.join(out,'manifest.json'),JSON.stringify(manifest,null,2));await writeFile(path.join(root,'evidence','phase4j-web-build.json'),JSON.stringify(manifest,null,2));console.log(JSON.stringify(manifest,null,2));
}finally{await app.close();}
