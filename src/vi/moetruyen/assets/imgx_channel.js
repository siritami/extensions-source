(async () => {
const send = (obj) => window.__imgxKt.postMessage(JSON.stringify(obj));
try {
const CHAN = 'imgx-reader-channel-v1';
const PAGEKEY = 'IMGX-READER-PAGE-KEY-v1';
const PVER = 'imgx-page-access-proof-v3';
const enc = new TextEncoder();
const b64u = (a) => btoa(String.fromCharCode.apply(null, new Uint8Array(a))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, '');
const ub64 = (s) => {
const n = String(s).replace(/-/g, '+').replace(/_/g, '/');
const b = atob(n + '='.repeat((4 - n.length % 4) % 4));
const o = new Uint8Array(b.length);
for (let i = 0; i < b.length; i++) o[i] = b.charCodeAt(i);
return o;
};
const html = document.documentElement.innerHTML;
const bootUrl = /bootstrapUrl:\s*"([^"]+)"/.exec(html)[1];
const reqPath = /requestPath:\s*"([^"]+)"/.exec(html)[1];
const chapterId = Number(/chapterId:\s*(\d+)/.exec(html)[1]);
const initIdx = JSON.parse('[' + (/initialIndexes:\s*\[([^\]]*)\]/.exec(html) || [null, ''])[1] + ']');
const mediaMatch = /media:\s*(\[[\s\S]*?\])\s*,\s*\n\s*connection:/.exec(html);
const media = mediaMatch ? JSON.parse(mediaMatch[1]).filter((e) =>
e.storageKey && e.storageKey.startsWith('chapters/') &&
!e.storageKey.endsWith('/0.js') && !String(e.downloadUrl || '').endsWith('/0.js'),
) : [];
const pageIndexes = media.length ? media.map((e) => e.pageIndex) : initIdx;
const kp = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
const pubB64 = b64u(await crypto.subtle.exportKey('raw', kp.publicKey));
const proof = b64u(crypto.getRandomValues(32));
const jh = { Accept: 'application/json', 'Content-Type': 'application/json' };
const post = async (u, b) => (await fetch(u, {
method: 'POST', credentials: 'same-origin', cache: 'no-store',
headers: jh, body: JSON.stringify(b),
})).json();
let shared;
const open = async (sealed, prf) => {
const peer = await crypto.subtle.importKey('raw', ub64(sealed.publicKey), { name: 'ECDH', namedCurve: 'P-256' }, false, []);
const bits = await crypto.subtle.deriveBits({ name: 'ECDH', public: peer }, kp.privateKey, 256);
shared = bits;
const material = await crypto.subtle.importKey('raw', bits, 'HKDF', false, ['deriveKey']);
const key = await crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: enc.encode(prf), info: enc.encode(CHAN) }, material, { name: 'AES-GCM', length: 256 }, false, ['decrypt']);
const aad = enc.encode(JSON.stringify([CHAN, pubB64, sealed.publicKey, prf]));
const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: ub64(sealed.iv), additionalData: aad, tagLength: 128 }, key, ub64(sealed.ciphertext));
const list = JSON.parse(new TextDecoder().decode(plain));
return { list, material, sealed, prf };
};
const openGrantKeys = async (page, opened) => {
const ck = page.grant && page.grant.channelKeys;
if (!ck) return;
const material = await crypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveKey']);
const key = await crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: enc.encode(opened.prf), info: enc.encode(PAGEKEY) }, material, { name: 'AES-GCM', length: 256 }, false, ['decrypt']);
const g = page.grant;
const aad = enc.encode(JSON.stringify([PAGEKEY, [pubB64, opened.sealed.publicKey, opened.prf], page.pageIndex, page.storageKey, g.imageId, g.issuedAt, g.expiresAt, g.nonce, g.signature]));
const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: ub64(ck.iv), additionalData: aad, tagLength: 128 }, key, ub64(ck.ciphertext));
const extra = JSON.parse(new TextDecoder().decode(plain));
const merged = Object.assign({}, g);
delete merged.channelKeys;
for (const k of ['decodeKey', 'wrappedDecodeKey', 'wrappedContentKey', 'wrappedV4Key']) {
if (Object.prototype.hasOwnProperty.call(extra, k)) merged[k] = extra[k];
}
page.grant = merged;
};
const boot = await post(location.origin + bootUrl, {
readerPublicKey: pubB64, bootstrapProof: proof, initialPageIndexes: initIdx,
});
if (!boot.ok) throw new Error('bootstrap ' + (boot.code || boot.status || 'failed'));
const capOpened = await open(boot.sealedCapability, proof);
const cap = capOpened.list[0];
const granted = new Map();
if (boot.sealedInitialPages) {
const opened = await open(boot.sealedInitialPages, proof);
for (const p of opened.list) {
await openGrantKeys(p, opened);
granted.set(p.pageIndex, p);
}
}
const pubHashBytes = new Uint8Array(await crypto.subtle.digest('SHA-256', ub64(pubB64)));
let pubHash = '';
for (const b of pubHashBytes) pubHash += b.toString(16).padStart(2, '0');
const secretKey = await crypto.subtle.importKey('raw', ub64(cap.secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
let seq = 0;
const remaining = pageIndexes.filter((i) => !granted.has(i));
for (let s = 0; s < remaining.length; s += 10) {
const idx = remaining.slice(s, s + 10);
seq += 1;
const material = JSON.stringify([PVER, cap.readerInstanceId, chapterId, reqPath, '', idx, boot.serverTime, seq, pubHash]);
const sig = b64u(await crypto.subtle.sign({ name: 'HMAC', hash: 'SHA-256' }, secretKey, enc.encode(material)));
const acc = await post(location.origin + reqPath, {
pageIndexes: idx,
pageAccessProof: {
version: PVER, readerInstanceId: cap.readerInstanceId,
issuedAt: boot.serverTime, sequence: seq, proof: sig,
},
readerPublicKey: pubB64,
});
if (!acc.ok) throw new Error('page-access ' + (acc.code || acc.status || 'failed'));
const opened = await open(acc.sealedPages, sig);
for (const p of opened.list) {
await openGrantKeys(p, opened);
granted.set(p.pageIndex, p);
}
}
const pages = pageIndexes.map((i) => granted.get(i)).filter(Boolean);
send({ ok: true, pages: pages });
} catch (e) {
send({ ok: false, error: String((e && e.message) || e) });
}
})();