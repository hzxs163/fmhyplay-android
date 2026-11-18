((q, year, pickOnly) => {
  // Opens the title's own page on a site we have no address pattern for: a link on the page that names the title
  // (a card on the home page, or a search result) is pressed; else the site's search box is found (opening it first if it
  // hides behind a search icon), the title typed and Enter pressed, and the results are watched for the title.
  const norm = s => (s || '').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/&/g, ' and ').replace(/[^\p{L}\p{N}\p{M}]+/gu, ' ').trim();
  const want = norm(q);
  // (letters of every script are kept: a title in Hindi or Korean used to come out empty, and then any link with no Latin text "matched")
  if (!want) return;
  // Score a link: 3 = its name is the title, 2 = the title and a year ("Anora (2024)"), +1 = the right year is there too.
  // ponytail: names only; a remake with the same name and no year shown can win, a wrong show is still one tap away from Back
  const pick = () => {
    let best = null, top = 0;
    for (const a of document.querySelectorAll('a[href]')) {
      const r = a.getBoundingClientRect();
      if (r.width < 20 || r.height < 14 || a.href === location.href || /^(javascript:|#)/.test(a.getAttribute('href')) || /\/search\b|[?&](q|s|query|keyword)=/i.test(a.href)) continue;
      const texts = [a.innerText, a.title, a.getAttribute('aria-label')].concat([...a.querySelectorAll('img')].map(i => i.alt));
      for (const t of texts) {
        if (!t) continue;
        const first = norm(String(t).split('\n')[0]), all = norm(t);
        let s = first === want ? 3 : first.startsWith(want + ' ') && /^\d{4}$/.test(first.slice(want.length).trim()) ? 2 : 0;
        if (s && year && all.includes(year)) s++;
        if (s > top) { top = s; best = a; }
      }
    }
    return top >= 2 ? best : null;
  };
  // Some sites ignore a click from a script (they listen for a real tap): then go to the link's address
  const open = a => {
    if (window.AnantPlay && AnantPlay.picked) AnantPlay.picked();
    const before = location.href;
    a.click();
    setTimeout(() => { if (location.href === before) location.href = a.href; }, 1200);
  };
  const watch = () => {
    let k = 0;
    const t = setInterval(() => {
      const a = pick();
      if (a) { clearInterval(t); open(a); }
      else if (++k > 80) clearInterval(t);   // 40 s: some sites take a while to answer a search
    }, 500);
  };
  if (pickOnly) { watch(); return; }
  if (window.__fpQ === q) return;
  window.__fpQ = q;
  const visible = e => { const r = e.getBoundingClientRect(), s = getComputedStyle(e); return r.width > 30 && r.height > 12 && s.visibility != 'hidden' && s.display != 'none'; };
  const label = e => [e.placeholder, e.name, e.id, e.getAttribute('aria-label'), e.title, typeof e.className == 'string' ? e.className : (e.className && e.className.baseVal) || ''].join(' ');
  const field = () => [...document.querySelectorAll('input')].find(i => !/^(hidden|checkbox|radio|button|submit|file|password|email|number|date)$/.test(i.type) && visible(i) && (i.type == 'search' || /search|find|title|movie|show|query|\bq\b/i.test(label(i))));
  const type = i => {
    i.focus();
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(i, q);
    i.dispatchEvent(new Event('input', {bubbles: true}));
    i.dispatchEvent(new Event('change', {bubbles: true}));
    ['keydown', 'keypress', 'keyup'].forEach(t => i.dispatchEvent(new KeyboardEvent(t, {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true})));
    if (i.form && i.form.requestSubmit) try { i.form.requestSubmit(); } catch (e) { }
    if (window.AnantPlay && AnantPlay.searched) AnantPlay.searched();
  };
  const now = pick();   // already on the home page (Trending, Popular): no need to search
  if (now) { if (window.AnantPlay) AnantPlay.searched(); open(now); return; }
  watch();   // live results on the same page; a results page that loads anew gets this script again (pick only)
  let n = 0, opened = false;
  const timer = setInterval(() => {
    const i = field();
    if (i) { clearInterval(timer); type(i); return; }
    if (n++ > 2 && !opened) {
      // The search icon is often a bare svg (class "lucide-search") inside a button: click the button around it
      const b = [...document.querySelectorAll('button,a,[role=button],svg,div,span')].find(e => /search/i.test(label(e)) && e.children.length < 6 && (() => { const t = e.closest('button,a,[role=button]') || e, r = t.getBoundingClientRect(); return r.width > 14 && r.height > 14; })());
      if (b) { opened = true; (b.closest('button,a,[role=button]') || b).click(); }
    }
    if (n > 40) { clearInterval(timer); window.__fpQ = null; }
  }, 300);
})
