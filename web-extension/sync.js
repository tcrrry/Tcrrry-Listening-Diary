const encoder=new TextEncoder(),decoder=new TextDecoder(),aad=encoder.encode('ListeningDiary/LAN/v1');
function from64(value){const raw=atob(value.replace(/-/g,'+').replace(/_/g,'/'));return Uint8Array.from(raw,c=>c.charCodeAt(0))}
function to64(bytes){let s='';for(const b of bytes)s+=String.fromCharCode(b);return btoa(s)}
function url64(bytes){return to64(bytes).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'')}
function concat(...parts){const out=new Uint8Array(parts.reduce((n,x)=>n+x.length,0));let at=0;for(const part of parts){out.set(part,at);at+=part.length}return out}
export function privateHost(ip){const p=ip.split('.');if(p.length!==4||p.some(x=>!/^\d{1,3}$/.test(x)||Number(x)>255))return false;const a=p.map(Number);return a[0]===10||(a[0]===192&&a[1]===168)||(a[0]===172&&a[1]>=16&&a[1]<=31)||a[0]===127}
async function seal(key,value){const iv=crypto.getRandomValues(new Uint8Array(12));const cipher=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:aad,tagLength:128},key,encoder.encode(JSON.stringify(value))));return concat(iv,cipher)}
async function open(key,bytes){if(bytes.length<28)throw Error('响应无效');return JSON.parse(decoder.decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:bytes.slice(0,12),additionalData:aad,tagLength:128},key,bytes.slice(12))))}
async function post(ip,path,body,fetcher){const response=await fetcher(`http://${ip}:38989/${path}`,{method:'POST',headers:{'Content-Type':'text/plain'},body,signal:AbortSignal.timeout(4000),cache:'no-store',credentials:'omit'});if(!response.ok)throw Error('日记未接受请求');return response.text()}
export async function pairGroup(ip,code,fetcher=fetch){
 if(!privateHost(ip)||!/^\d{6}$/.test(code))throw Error('请输入局域网地址和六位配对码');
 const pair=await crypto.subtle.generateKey({name:'ECDH',namedCurve:'P-256'},true,['deriveBits']);const pub=new Uint8Array(await crypto.subtle.exportKey('spki',pair.publicKey));
 const begin=JSON.parse(await post(ip,'pair-begin',JSON.stringify({public:to64(pub)}),fetcher));if(!/^[a-f0-9]{32}$/.test(begin.session))throw Error('配对响应无效');
 const peer=await crypto.subtle.importKey('spki',from64(begin.public),{name:'ECDH',namedCurve:'P-256'},false,[]);const shared=new Uint8Array(await crypto.subtle.deriveBits({name:'ECDH',public:peer},pair.privateKey,256));const raw=new Uint8Array(await crypto.subtle.digest('SHA-256',concat(encoder.encode('ListeningDiary/browser-pair/v1/'),shared)));const key=await crypto.subtle.importKey('raw',raw,'AES-GCM',false,['encrypt','decrypt']);
 await post(ip,'pair-proof',JSON.stringify({session:begin.session,sealed:to64(await seal(key,{type:'browser-pair-proof-v1',code}))}),fetcher);
 const result=JSON.parse(await post(ip,'pair-result',JSON.stringify({session:begin.session}),fetcher));if(!result.sealed)throw Error('配对未完成');const data=await open(key,from64(result.sealed));if(data.type!=='browser-pair-result-v1'||from64(data.group).length!==32)throw Error('配对响应无效');return {key:data.group,addresses:Array.isArray(data.addresses)?data.addresses.filter(privateHost):[]};
}
export async function sendBatch(ip,token,tracks,spans,fetcher=fetch,identity=null){
 if(!privateHost(ip))throw Error('请输入局域网 IPv4 地址');const raw=from64(token);if(raw.length!==32)throw Error('配对密钥无效');const key=await crypto.subtle.importKey('raw',raw,'AES-GCM',false,['encrypt','decrypt']);const id=crypto.randomUUID().replaceAll('-','');const bytes=await seal(key,{type:'browser-import-v1',id,at:Date.now(),tracks,spans,...(identity?{client:identity}:{})});
 const ack=await open(key,from64(await post(ip,'diary-sync',to64(bytes),fetcher)));if(ack.type!=='browser-ack-v1'||ack.id!==id||ack.count!==spans.length)throw Error('同步确认不匹配');return ack;
}
