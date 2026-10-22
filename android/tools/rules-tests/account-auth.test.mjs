import {test} from 'node:test';
import assert from 'node:assert/strict';
import {initializeApp,deleteApp} from 'firebase/app';
import {getAuth,connectAuthEmulator,signInAnonymously,linkWithCredential,EmailAuthProvider,signInWithEmailAndPassword,getIdTokenResult} from 'firebase/auth';
import {getFirestore,connectFirestoreEmulator,doc,writeBatch,getDoc,setDoc,updateDoc,terminate,onSnapshot,runTransaction} from 'firebase/firestore';

assert.equal(process.env.FIRESTORE_EMULATOR_HOST,'127.0.0.1:61907');
assert.equal(process.env.FIREBASE_AUTH_EMULATOR_HOST,'127.0.0.1:61908');
const projectId='demo-hardware-shop-sync';
test('printer realtime listener receives an owner job and a confirmed retry without sending duplicate copies',async()=> {
  const apps=[],stores=[],listeners=[];
  function phone(name) {
    const app=initializeApp({projectId,apiKey:'demo-only-key'},name);apps.push(app);
    const auth=getAuth(app);connectAuthEmulator(auth,'http://127.0.0.1:61908',{disableWarnings:true});
    const db=getFirestore(app);connectFirestoreEmulator(db,'127.0.0.1',61907);stores.push(db);
    return {auth,db};
  }
  function waitFor(db,path,status) {
    return new Promise((resolve,reject)=> {
      const timeout=setTimeout(()=>reject(new Error(`No server ${status} snapshot`)),15000);
      const unsubscribe=onSnapshot(doc(db,path),{includeMetadataChanges:true},snapshot=> {
        if(!snapshot.metadata.fromCache && snapshot.data()?.status===status) {
          clearTimeout(timeout);resolve(snapshot.data());
        }
      },error=>{clearTimeout(timeout);reject(error)});
      listeners.push(()=>{clearTimeout(timeout);unsubscribe()});
    });
  }
  try {
    const owner=phone('realtime-owner'),printer=phone('realtime-printer');
    const anonymous=await signInAnonymously(owner.auth),uid=anonymous.user.uid;
    const email=`realtime-${Date.now()}@example.test`,password='DemoOnlyPassword42';
    await linkWithCredential(anonymous.user,EmailAuthProvider.credential(email,password));
    await getIdTokenResult(owner.auth.currentUser,true);
    const shop=`account_${uid}`,batch=writeBatch(owner.db);
    batch.set(doc(owner.db,`shops/${shop}`),{ownerUid:uid});
    batch.set(doc(owner.db,`shops/${shop}/members/${uid}`),{enabled:true,role:'OWNER',printer:true});
    batch.set(doc(owner.db,`cloudAccounts/${uid}`),{shopId:shop});await batch.commit();
    await signInWithEmailAndPassword(printer.auth,email,password);
    await setDoc(doc(printer.db,`shops/${shop}/config/device`),{deviceId:'physical-shop-phone'});
    const path=`shops/${shop}/printJobs/realtime-job`,received=waitFor(printer.db,path,'PENDING');
    const receipt=Buffer.from('TEST RECEIPT\n').toString('base64');
    await setDoc(doc(owner.db,path),{jobId:'realtime-job',status:'PENDING',receiptPayload:receipt,deviceId:'physical-shop-phone'});
    assert.equal((await received).receiptPayload,receipt);
    const job=doc(printer.db,path);
    await runTransaction(printer.db,async tx=> {
      const target=await tx.get(doc(printer.db,`shops/${shop}/config/device`));
      const current=await tx.get(job);
      assert.equal(target.data().deviceId,'physical-shop-phone');
      assert.equal(current.data().status,'PENDING');
      tx.update(job,{status:'RECEIVED',claimToken:'first-attempt',leaseUntil:Date.now()+45000});
    });
    await updateDoc(job,{status:'PRINTING'});
    // Simulated interrupted transport: no automatic second write is allowed.
    await updateDoc(job,{status:'FAILED',lastError:'Outcome unknown; check paper'});
    const retryReceived=waitFor(printer.db,path,'PENDING');
    const copy=Buffer.from('COPY\nTEST RECEIPT\n').toString('base64');
    await updateDoc(doc(owner.db,path),{status:'PENDING',claimToken:'',leaseUntil:0,lastError:null,receiptPayload:copy,retryCount:1});
    assert.equal((await retryReceived).receiptPayload,copy);
    await updateDoc(job,{status:'RECEIVED',claimToken:'confirmed-copy',leaseUntil:Date.now()+45000});
    // Competing callbacks must acquire the print fence only once.
    const grants=await Promise.all([0,1].map(()=>runTransaction(printer.db,async tx=> {
      const current=await tx.get(job);
      if(current.data().status!=='RECEIVED' || current.data().claimToken!=='confirmed-copy') return false;
      tx.update(job,{status:'PRINTING'});return true;
    })));
    assert.equal(grants.filter(Boolean).length,1);
    const acknowledged=waitFor(owner.db,path,'PRINTED');
    await updateDoc(job,{status:'PRINTED'});await acknowledged;
    assert.equal((await getDoc(job)).data().status,'PRINTED');
  } finally {
    listeners.forEach(stop=>stop());
    await Promise.all(stores.map(terminate));await Promise.all(apps.map(deleteApp));
  }
});
test('real emulator email login preserves anonymous UID and restores the same shop on a second phone',async()=> {
  const apps=[],stores=[];
  function phone(name) {
    const app=initializeApp({projectId,apiKey:'demo-only-key'},name);apps.push(app);
    const auth=getAuth(app);connectAuthEmulator(auth,'http://127.0.0.1:61908',{disableWarnings:true});
    const db=getFirestore(app);connectFirestoreEmulator(db,'127.0.0.1',61907);stores.push(db);
    return {auth,db};
  }
  try {
    const owner=phone('owner-phone'),printer=phone('printer-phone');
    const anonymous=await signInAnonymously(owner.auth),uid=anonymous.user.uid;
    const email=`shop-${Date.now()}@example.test`,password='DemoOnlyPassword42';
    await linkWithCredential(anonymous.user,EmailAuthProvider.credential(email,password));
    assert.equal(owner.auth.currentUser.uid,uid);
    const token=await getIdTokenResult(owner.auth.currentUser,true);
    assert.equal(token.signInProvider,'password');
    const shop=`account_${uid}`,batch=writeBatch(owner.db);
    batch.set(doc(owner.db,`shops/${shop}`),{ownerUid:uid});
    batch.set(doc(owner.db,`shops/${shop}/members/${uid}`),{enabled:true,role:'OWNER',printer:true});
    batch.set(doc(owner.db,`cloudAccounts/${uid}`),{shopId:shop});
    await batch.commit();
    await signInWithEmailAndPassword(printer.auth,email,password);
    assert.equal(printer.auth.currentUser.uid,uid);
    assert.equal((await getDoc(doc(printer.db,`cloudAccounts/${uid}`))).data().shopId,shop);
    await setDoc(doc(printer.db,`shops/${shop}/config/device`),{deviceId:'worker-phone'});
    await setDoc(doc(owner.db,`shops/${shop}/printJobs/email-roundtrip`),
      {jobId:'email-roundtrip',status:'PENDING',receiptPayload:'demo-receipt',deviceId:'worker-phone'});
    const job=doc(printer.db,`shops/${shop}/printJobs/email-roundtrip`);
    assert.equal((await getDoc(job)).data().status,'PENDING');
    await updateDoc(job,{status:'RECEIVED',claimToken:'demo-lease',leaseUntil:1000});
    await updateDoc(job,{status:'PRINTING'});
    await updateDoc(job,{status:'PRINTED'});
    assert.equal((await getDoc(doc(owner.db,`shops/${shop}/printJobs/email-roundtrip`))).data().status,'PRINTED');
  } finally {
    await Promise.all(stores.map(terminate));
    await Promise.all(apps.map(deleteApp));
  }
});
