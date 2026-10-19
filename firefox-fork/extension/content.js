// תמונות שמוטמעות בקוד הדף (data: / blob:) לא עוברות דרך בקשות רשת.
// מסתירים אותן לרגע, שולחים לבדיקה, ומציגים או מחליפים במשטח אפור.
(function () {
  if (window.__wlf) return;
  window.__wlf = 1;
  var G = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4"><rect width="4" height="4" fill="#c8c8c8"/></svg>');
  var seq = 0, pending = {};

  function inline(s) {
    if (!s || s === G) return false;
    return (s.indexOf('data:image') === 0 && s.indexOf('data:image/svg') !== 0) || s.indexOf('blob:') === 0;
  }
  function block(img) {
    try {
      if (!img.style.width && img.width) img.style.width = img.width + 'px';
      if (!img.style.height && img.height) img.style.height = img.height + 'px';
      img.removeAttribute('srcset');
      img.src = G;
      img.style.visibility = '';
    } catch (e) {}
  }
  function done(id, blocked) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (p.bg) {
      p.el.__wlfBg = p.src;
      if (blocked) { p.el.style.backgroundImage = 'none'; p.el.style.backgroundColor = '#c8c8c8'; }
      else p.el.style.backgroundImage = p.orig;
      return;
    }
    var img = p.img;
    if ((img.currentSrc || img.src) !== p.src) { img.style.visibility = ''; chk(img); return; }
    img.__wlfOk = !blocked ? p.src : null;
    if (blocked) block(img); else img.style.visibility = '';
  }
  function toData(src) {
    if (src.indexOf('blob:') !== 0) return Promise.resolve(src);
    return fetch(src).then(function (r) { return r.blob(); }).then(function (b) {
      return new Promise(function (res) { var fr = new FileReader(); fr.onload = function () { res(fr.result); }; fr.readAsDataURL(b); });
    });
  }
  function sendData(id, src) {
    setTimeout(function () { if (pending[id]) done(id, true); }, 10000);
    toData(src).then(function (d) {
      return browser.runtime.sendMessage({ t: 'inline', data: d });
    }).then(function (r) { done(id, !!(r && r.blocked)); }).catch(function () { done(id, true); });
  }
  function chk(img) {
    try {
      var s = img.currentSrc || img.src || '';
      if (!inline(s) || img.__wlfOk === s) return;
      for (var k in pending) if (pending[k].img === img && pending[k].src === s) return;
      var w = img.naturalWidth || 0, h = img.naturalHeight || 0;
      if (img.complete && w > 0 && h > 0 && (w < 40 || h < 40)) return;
      var id = 'i' + (++seq);
      pending[id] = { img: img, src: s };
      img.style.visibility = 'hidden';
      sendData(id, s);
    } catch (e) {}
  }
  function bg(el) {
    try {
      var b = el.style && el.style.backgroundImage;
      if (!b || b === 'none') return;
      var m = b.match(/url\(["']?(data:image\/(?!svg)[^"')]+|blob:[^"')]+)/);
      if (!m || el.__wlfBg === m[1]) return;
      for (var k in pending) if (pending[k].el === el) return;
      var r = el.getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && (r.width < 64 || r.height < 64)) return;
      var id = 'b' + (++seq);
      pending[id] = { bg: true, el: el, src: m[1], orig: b };
      el.style.backgroundImage = 'none';
      sendData(id, m[1]);
    } catch (e) {}
  }
  function scan(root) {
    if (!root || root.nodeType !== 1 && root.nodeType !== 9) return;
    if (root.tagName === 'IMG') chk(root); else bg(root);
    var imgs = root.querySelectorAll ? root.querySelectorAll('img') : [];
    for (var i = 0; i < imgs.length; i++) chk(imgs[i]);
    var styled = root.querySelectorAll ? root.querySelectorAll('[style*="background"]') : [];
    for (var j = 0; j < styled.length; j++) bg(styled[j]);
  }
  function start() {
    scan(document);
    new MutationObserver(function (ms) {
      for (var i = 0; i < ms.length; i++) {
        var m = ms[i];
        if (m.type === 'attributes') { if (m.target.tagName === 'IMG') { if (m.attributeName !== 'style') chk(m.target); } else bg(m.target); }
        else for (var k = 0; k < m.addedNodes.length; k++) scan(m.addedNodes[k]);
      }
    }).observe(document.documentElement || document, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'srcset', 'style'] });
  }
  start();
})();
