browser.runtime.sendMessage({ t: "sites" }).then(r => {
  const box = document.getElementById("sites");
  const sites = (r && r.sites || []).slice().sort();
  if (!sites.length) { box.textContent = "אין עדיין אתרים ברשימה הלבנה."; return; }
  for (const d of sites) {
    const a = document.createElement("a");
    a.href = "https://" + d;
    a.textContent = d;
    box.appendChild(a);
  }
});
