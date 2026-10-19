// תוסף הסינון: אוכף את הרשימה הלבנה, חוסם פרסומות, ושולח כל תמונה לאפליקציה לבדיקת AI
// לפני שהיא מגיעה לדף (filterResponseData).
"use strict";

const port = browser.runtime.connectNative("wlf");
let rules = null;
let rulesReady;
const rulesPromise = new Promise(res => { rulesReady = res; });
let domainSet = new Set();
let adSet = new Set();
let seq = 0;
const pending = new Map();

// משטח אפור קטן — למקרה שהבדיקה נתקעה
const GRAY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAD0lEQVR4nGM4gQQYiOMAAM9hJYGWbiP1AAAAAElFTkSuQmCC";

port.onMessage.addListener(m => {
  if (m.type === "rules") {
    rules = m;
    domainSet = new Set(m.domains || []);
    adSet = new Set(m.ads || []);
    rulesReady();
  } else if (m.type === "verdict") {
    const cb = pending.get(m.id);
    if (cb) { pending.delete(m.id); cb(m); }
  }
});
port.postMessage({ type: "hello", home: browser.runtime.getURL("home.html") });

function matchSet(set, host) {
  let h = host;
  while (true) {
    if (set.has(h)) return true;
    const i = h.indexOf(".");
    if (i < 0) return false;
    h = h.slice(i + 1);
  }
}

function allowed(host) {
  if (rules.mode === "OPEN") return true;
  if (rules.mode === "BLOCK") return false;
  return matchSet(domainSet, host);
}

function b64ToBytes(b64) {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

function bytesToB64(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(s);
}

/** שולח לאפליקציה ומחכה לתשובה (עד 15 שניות — אחרת נחשב חסום) */
function ask(msg) {
  return new Promise(res => {
    const id = ++seq;
    msg.id = id;
    pending.set(id, res);
    port.postMessage(msg);
    setTimeout(() => {
      if (pending.has(id)) { pending.delete(id); res({ blocked: true, data: null, timeout: true }); }
    }, 15000);
  });
}

function filterImage(details) {
  const filter = browser.webRequest.filterResponseData(details.requestId);
  const chunks = [];
  let size = 0;
  filter.ondata = e => {
    const c = new Uint8Array(e.data);
    chunks.push(c);
    size += c.length;
  };
  filter.onstop = async () => {
    if (size === 0) { filter.close(); return; }
    const all = new Uint8Array(size);
    let o = 0;
    for (const c of chunks) { all.set(c, o); o += c.length; }
    try {
      const v = await ask({ type: "image", url: details.url, data: bytesToB64(all) });
      if (v.blocked) filter.write(b64ToBytes(v.data || GRAY_PNG));
      else filter.write(all);
    } catch (e) {
      filter.write(b64ToBytes(GRAY_PNG));
    }
    filter.close();
  };
  filter.onerror = () => {};
}

function decide(d) {
  let u;
  try { u = new URL(d.url); } catch (e) { return {}; }
  if (u.protocol !== "http:" && u.protocol !== "https:") return {};
  const host = u.hostname.toLowerCase().replace(/^www\./, "").replace(/\.$/, "");

  if (rules.blockAds && matchSet(adSet, host)) {
    return d.type === "main_frame"
      ? { redirectUrl: browser.runtime.getURL("blocked.html") + "#" + encodeURIComponent(host) }
      : { cancel: true };
  }
  if (!allowed(host)) {
    port.postMessage({ type: "blocked", host: host });
    return d.type === "main_frame"
      ? { redirectUrl: browser.runtime.getURL("blocked.html") + "#" + encodeURIComponent(host) }
      : { cancel: true };
  }
  if (rules.imageActive && (d.type === "image" || d.type === "imageset")) filterImage(d);
  return {};
}

browser.webRequest.onBeforeRequest.addListener(
  d => (rules ? decide(d) : rulesPromise.then(() => decide(d))),
  { urls: ["<all_urls>"] },
  ["blocking"]
);

// תמונות מוטמעות בדף (data: / blob:) — מגיעות מסקריפט התוכן
browser.runtime.onMessage.addListener(msg => {
  if (msg.t === "active") return (rules ? Promise.resolve(rules) : rulesPromise.then(() => rules)).then(r => ({ active: !!r.imageActive }));
  if (msg.t === "inline") return ask({ type: "inline", data: msg.data }).then(v => ({ blocked: !!v.blocked }));
  if (msg.t === "sites") return (rules ? Promise.resolve(rules) : rulesPromise.then(() => rules)).then(r => ({ sites: r.domains || [] }));
});
