import test from 'node:test';import assert from 'node:assert/strict';import {pairGroup} from '../sync.js';
const enc=new TextEncoder(),dec=new TextDecoder(),aad=enc.encode('ListeningDiary/LAN/v1');const concat=(...p)=>Buffer.concat(p.map(x=>Buffer.from(x)));
test('six digit browser pairing derives upload key and learns addresses',async()=>{
 let sessionKey;const group=crypto.getRandomValues(new Uint8Array(32)),id='a'.repeat(32);
 const fetcher=async(url,opt)=>{const body=JSON.parse(opt.body);let value;
  if(url.endsWith('/pair-begin')){const client=Buffer.from(body.public,'base64'),pair=await crypto.subtle.generateKey({name:'ECDH',namedCurve:'P-256'},true,['deriveBits']);const pub=await crypto.subtle.importKey('spki',client,{name:'ECDH',namedCurve:'P-256'},false,[]);const shared=await crypto.subtle.deriveBits({name:'ECDH',public:pub},pair.privateKey,256);const digest=await crypto.subtle.digest('SHA-256',concat(enc.encode('ListeningDiary/browser-pair/v1/'),shared));sessionKey=await crypto.subtle.importKey('raw',digest,'AES-GCM',false,['encrypt','decrypt']);value={session:id,public:Buffer.from(await crypto.subtle.exportKey('spki',pair.publicKey)).toString('base64')};}
  else if(url.endsWith('/pair-proof')){const b=Buffer.from(body.sealed,'base64');const proof=JSON.parse(dec.decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:b.subarray(0,12),additionalData:aad},sessionKey,b.subarray(12))));assert.equal(proof.code,'123456');value={pending:true}}
  else {const iv=crypto.getRandomValues(new Uint8Array(12)),data=enc.encode(JSON.stringify({type:'browser-pair-result-v1',group:Buffer.from(group).toString('base64url'),addresses:['10.0.0.10','8.8.8.8']})),cipher=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:aad},sessionKey,data);value={sealed:concat(iv,cipher).toString('base64')}}
  return {ok:true,text:async()=>JSON.stringify(value)};
 };
 const result=await pairGroup('10.0.0.10','123456',fetcher);assert.equal(result.key,Buffer.from(group).toString('base64url'));assert.deepEqual(result.addresses,['10.0.0.10']);
});
