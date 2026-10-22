// Trusted synthetic test issuer, never imported into the Worker or customer site.
import {createHmac,randomUUID,createHash} from 'node:crypto';
import {canonical} from './pricing.mjs';
const hash=s=>createHash('sha256').update(s).digest('hex');
const seal=(value,key)=>{const payload=Buffer.from(JSON.stringify(value)).toString('base64url');return payload+'.'+createHmac('sha256',key).update(payload).digest('base64url');};
export const abuseFixtureBindings={CUSTOMER_ABUSE_MODE:'CHALLENGE_LOCAL',LOCAL_ABUSE_KEY:'synthetic-anonymous-session-key-not-production-4f',LOCAL_CHALLENGE_KEY:'synthetic-challenge-verifier-key-not-production-4f'};
export function anonymousSession({shop='proof-shop',id=randomUUID(),expires=Math.floor(Date.now()/1000)+3600}={}){return seal({kind:'anonymous-session',shop,id,expires},abuseFixtureBindings.LOCAL_ABUSE_KEY);}
export function challengeHeaders(body,{session=anonymousSession(),shop='proof-shop',...overrides}={}){
 const clean={...body};delete clean.capability;const sessionHash=hash(session),payloadHash=hash(canonical(clean)),now=Math.floor(Date.now()/1000);
 const cdata=hash(canonical({shop,session:sessionHash,submission:body.submissionId,payloadHash}));
 const token=seal({kind:'synthetic-challenge',shop,sessionHash,hostname:'proof.invalid',action:'order-submit',cdata,issued:now,expires:now+300,nonce:randomUUID(),...overrides},abuseFixtureBindings.LOCAL_CHALLENGE_KEY);
 return {'x-order-session':session,'x-order-challenge':token};
}
