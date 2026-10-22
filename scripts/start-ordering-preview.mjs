// Developer-only loopback preview. Never included in the static Pages artifact.
// Reuse the existing ordering implementation and synthetic backend; no real POS data.
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
const here=path.dirname(fileURLToPath(import.meta.url));
const proofRoot=path.resolve(process.env.HARDWARESHOPPOS_PROOF_ROOT??path.join(here,'../../HardwareShopPOS/tools/website-proof'));
const {startWeb}=await import(pathToFileURL(path.join(proofRoot,'web-server.mjs')).href);
const app=await startWeb({port:4319,largeCatalog:false});
console.log('Full ordering preview: '+app.origin+' (synthetic products/orders only)');
let closing=false;
async function close(){if(closing)return;closing=true;await app.close();process.exitCode=0;}
process.once('SIGINT',()=>close().catch(()=>{process.exitCode=1;}));
process.once('SIGTERM',()=>close().catch(()=>{process.exitCode=1;}));
