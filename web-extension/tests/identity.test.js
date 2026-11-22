import test from 'node:test';
import assert from 'node:assert/strict';
import {browserIdentity} from '../identity.js';

test('pairing and background sync share a single browser ID on first use', async () => {
 const stored={};
 let writes=0;
 globalThis.chrome={storage:{local:{
  async get(){return {...stored}},
  async set(value){writes++;Object.assign(stored,value)}
 }}};
 const [pairing,sync]=await Promise.all([browserIdentity(),browserIdentity()]);
 assert.match(pairing.id,/^[a-f0-9]{32}$/);
 assert.equal(sync.id,pairing.id);
 assert.equal(stored.browserSyncId,pairing.id);
 assert.equal(writes,1);
});
