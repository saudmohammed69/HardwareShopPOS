// Customer admission only. This never authenticates staff or reads stock.
import {fail,hash,canonical,uuid} from './pricing.mjs';
const encoder=new TextEncoder();
const decode=s=>Uint8Array.from(atob(s.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
async function macKey(secret){if(typeof secret!=='string'||secret.length<32)fail(503,'Order verification unavailable; keep your cart');return crypto.subtle.importKey('raw',encoder.encode(secret),{name:'HMAC',hash:'SHA-256'},false,['sign','verify']);}
async function unpack(token,secret){
 if(typeof token!=='string'||token.length>4096||!/^[-_A-Za-z0-9]+\.[-_A-Za-z0-9]+$/.test(token))fail(403,'Order verification required; keep your cart');
 const [encoded,signature]=token.split('.');
 try{if(!await crypto.subtle.verify('HMAC',await macKey(secret),decode(signature),encoder.encode(encoded)))throw Error();return JSON.parse(new TextDecoder().decode(decode(encoded)));}
 catch{fail(403,'Order verification required; keep your cart');}
}
async function bucket(secret,value){return Array.from(new Uint8Array(await crypto.subtle.sign('HMAC',await macKey(secret),encoder.encode(value))),b=>b.toString(16).padStart(2,'0')).join('');}
export const challengeBinding=(shop,session,submission,payloadHash)=>hash(canonical({shop,session,submission,payloadHash}));
export async function verifyCustomerAdmission(request,env,shop,body,payloadHash){
 const mode=env.CUSTOMER_ABUSE_MODE;
 if(!['CHALLENGE_LOCAL','TURNSTILE'].includes(mode))fail(503,'Order verification is not configured; keep your cart');
 // Only the private loopback gateway can attest an IP in local tests. Public
 // X-Forwarded-For, CF-Connecting-IP and client-selected proof IP are ignored.
 if(env.LOCAL_PROOF_ONLY!=='true'||request.headers.get('x-local-gateway-key')!==env.LOCAL_FIXTURE_KEY)fail(403,'Trusted order gateway required');
 const ip=request.headers.get('x-proof-client-ip');if(!ip||ip.length>100)fail(403,'Trusted client address required');
 const sessionToken=request.headers.get('x-order-session'),session=await unpack(sessionToken,env.LOCAL_ABUSE_KEY),now=Math.floor(Date.now()/1000);
 uuid(session.id);
 if(session.kind!=='anonymous-session'||session.shop!==shop||!Number.isInteger(session.expires)||session.expires<=now||session.expires>now+3600)fail(403,'Customer session expired; keep your cart');
 const sessionHash=await hash(sessionToken),binding=await challengeBinding(shop,sessionHash,body.submissionId,payloadHash);
 const token=request.headers.get('x-order-challenge');if(!token||token.length>4096)fail(403,'Complete order verification; keep your cart');
 const tokenHash=await hash(token),verificationId=[tokenHash.slice(0,8),tokenHash.slice(8,12),'4'+tokenHash.slice(13,16),'a'+tokenHash.slice(17,20),tokenHash.slice(20,32)].join('-');
 let challenge;
 if(mode==='CHALLENGE_LOCAL'){
  // Synthetic verifier exists only in this explicitly local proof. No real bot
  // classification or provider delivery is claimed from fixture-signed tokens.
  challenge=await unpack(token,env.LOCAL_CHALLENGE_KEY);
  if(challenge.kind!=='synthetic-challenge'||challenge.shop!==shop||challenge.sessionHash!==sessionHash)fail(403,'Invalid order verification');
 }else{
  if(!env.TURNSTILE_SECRET||!env.TURNSTILE_HOSTNAME)fail(503,'Secure challenge provider unavailable; keep your cart');
  let result;
  try{const response=await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({secret:env.TURNSTILE_SECRET,response:token,idempotency_key:verificationId}),signal:AbortSignal.timeout(8000)});if(!response.ok)throw Error();result=await response.json();}
  catch{fail(503,'Order verification interrupted; keep your cart and retry the same submission');}
  const issued=Date.parse(result.challenge_ts)/1000;
  challenge={...result,issued,expires:issued+300};
  if(result.success!==true)fail(403,'Order verification failed; keep your cart');
 }
 if(challenge.hostname!==(mode==='CHALLENGE_LOCAL'?'proof.invalid':env.TURNSTILE_HOSTNAME)||challenge.action!=='order-submit'||challenge.cdata!==binding||!Number.isFinite(challenge.issued)||challenge.issued>now+5||challenge.expires<=now||challenge.expires>challenge.issued+300||now-challenge.issued>300)fail(403,'Expired or mismatched order verification; keep your cart');
 return {sessionBucket:await bucket(env.LOCAL_RATE_KEY,shop+'|session|'+session.id),
  ipBucket:await bucket(env.LOCAL_RATE_KEY,shop+'|ip-day|'+Math.floor(now/86400)+'|'+ip),
  tokenHash,expires:challenge.expires,window:Math.floor(now/600),sessionExpires:session.expires};
}
export function customerAdmissionStatements(db,shop,id,a){
 return [
  db.statement('INSERT INTO customer_challenge_uses(shop,token_hash,order_id,expires_at) SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM orders WHERE id=?)',[shop,a.tokenHash,id,a.expires,id]),
  db.statement('INSERT INTO customer_session_admission(shop,bucket,window,count,expires_at) SELECT ?,?,?,1,? WHERE EXISTS(SELECT 1 FROM orders WHERE id=?) ON CONFLICT(shop,bucket) DO UPDATE SET count=CASE WHEN customer_session_admission.window=excluded.window THEN customer_session_admission.count+1 ELSE 1 END,window=excluded.window,expires_at=excluded.expires_at',[shop,a.sessionBucket,a.window,a.sessionExpires,id]),
  db.statement('INSERT INTO customer_ip_admission(shop,bucket,window,count,expires_at) SELECT ?,?,?,1,? WHERE EXISTS(SELECT 1 FROM orders WHERE id=?) ON CONFLICT(shop,bucket) DO UPDATE SET count=CASE WHEN customer_ip_admission.window=excluded.window THEN customer_ip_admission.count+1 ELSE 1 END,window=excluded.window,expires_at=excluded.expires_at',[shop,a.ipBucket,a.window,(Math.floor(a.window/144)+2)*86400,id])
 ];
}
