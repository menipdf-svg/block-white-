// תוסף סינון התמונות של פיירפוקס המסונן.
// כל תמונה נתפסת לפני שהיא מגיעה לדף (filterResponseData), מוקטנת לדוגמה קטנה,
// ונשלחת דרך פיירפוקס לאפליקציית המסנן — שם המודלים מחליטים. לפי התשובה הדף מקבל
// את המקור או משטח אפור באותו גודל.
"use strict";

const port = browser.runtime.connectNative("wlf");
let seq = 0;
const pending = new Map();
const MIN_SIDE = 48;
const SAMPLE_SIDE = 640;
const MAX_OUT_SIDE = 1600;

port.onMessage.addListener(m => {
  if (m.type === "verdict") {
    const cb = pending.get(m.id);
    if (cb) { pending.delete(m.id); cb(m); }
  }
});

function bytesToB64(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(s);
}

/** שולח לאפליקציה ומחכה לתשובה. בלי תשובה תוך 15 שניות — נחשב חסום */
function ask(msg) {
  return new Promise(res => {
    const id = ++seq;
    msg.id = id;
    pending.set(id, res);
    port.postMessage(msg);
    setTimeout(() => {
      if (pending.has(id)) { pending.delete(id); res({ blocked: true, timeout: true }); }
    }, 15000);
  });
}

/** משטח אפור עם סימן "חסום", באותו גודל כמו התמונה המקורית */
async function blockedImage(w, h) {
  const scale = Math.min(1, MAX_OUT_SIDE / Math.max(w, h));
  const cw = Math.max(1, Math.round(w * scale));
  const ch = Math.max(1, Math.round(h * scale));
  const c = new OffscreenCanvas(cw, ch);
  const g = c.getContext("2d");
  g.fillStyle = "#c8c8c8";
  g.fillRect(0, 0, cw, ch);
  const size = Math.min(cw, ch) * 0.28;
  if (size >= 14) {
    g.strokeStyle = "#969696";
    g.lineWidth = Math.max(2, size * 0.12);
    const cx = cw / 2, cy = ch / 2, r = size / 2, d = r * 0.7071;
    g.beginPath(); g.arc(cx, cy, r, 0, Math.PI * 2); g.stroke();
    g.beginPath(); g.moveTo(cx - d, cy - d); g.lineTo(cx + d, cy + d); g.stroke();
  }
  const blob = await c.convertToBlob({ type: "image/png" });
  return new Uint8Array(await blob.arrayBuffer());
}

/** מקטין את התמונה לדוגמה לבדיקה. null = לא תמונה שאפשר לפענח (SVG, פורמט לא נתמך...) */
async function sample(bytes) {
  let bmp;
  try {
    bmp = await createImageBitmap(new Blob([bytes]));
  } catch (e) {
    return null;
  }
  const w = bmp.width, h = bmp.height;
  if (w < MIN_SIDE || h < MIN_SIDE) { bmp.close(); return { w, h, small: true }; }
  const scale = Math.min(1, SAMPLE_SIDE / Math.max(w, h));
  const c = new OffscreenCanvas(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
  c.getContext("2d").drawImage(bmp, 0, 0, c.width, c.height);
  bmp.close();
  const blob = await c.convertToBlob({ type: "image/jpeg", quality: 0.9 });
  return { w, h, data: bytesToB64(new Uint8Array(await blob.arrayBuffer())) };
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
      const s = await sample(all);
      if (!s || s.small) {
        filter.write(all);
      } else {
        const v = await ask({ type: "image", url: details.url, data: s.data, w: s.w, h: s.h });
        filter.write(v.blocked ? await blockedImage(s.w, s.h) : all);
      }
    } catch (e) {
      filter.write(await blockedImage(320, 240));
    }
    filter.close();
  };
  filter.onerror = () => {};
}

browser.webRequest.onBeforeRequest.addListener(
  d => { filterImage(d); return {}; },
  { urls: ["<all_urls>"], types: ["image", "imageset"] },
  ["blocking"]
);

// תמונות מוטמעות בדף (data: / blob:) — מגיעות מסקריפט התוכן
browser.runtime.onMessage.addListener(async msg => {
  if (msg.t !== "inline") return undefined;
  try {
    const bytes = Uint8Array.from(atob(msg.data.slice(msg.data.indexOf(",") + 1)), c => c.charCodeAt(0));
    const s = await sample(bytes);
    if (!s) return { blocked: true };
    if (s.small) return { blocked: false };
    const v = await ask({ type: "image", url: "inline", data: s.data, w: s.w, h: s.h });
    return { blocked: !!v.blocked };
  } catch (e) {
    return { blocked: true };
  }
});
