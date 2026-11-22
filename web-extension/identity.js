let identityRequest;
export function browserIdentity(){
 return identityRequest ??= loadIdentity().catch(error=>{
  identityRequest=undefined;
  throw error;
 });
}
async function loadIdentity(){
 const data=await chrome.storage.local.get(['browserSyncId','browserSyncName']);let id=data.browserSyncId;
 if(typeof id!=='string'||!/^[a-f0-9]{32}$/.test(id)){id=crypto.randomUUID().replaceAll('-','');await chrome.storage.local.set({browserSyncId:id})}
 const name=typeof data.browserSyncName==='string'&&data.browserSyncName.trim()?data.browserSyncName.trim().slice(0,48):'网页端 · Chrome';return {id,name};
}
