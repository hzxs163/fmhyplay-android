// Runs in every frame; frames pass messages to each other, so cross-origin embeds work too.
//   pause   = stop the page's own players
//   servers = list the page's server / source choices (buttons on screen, or the menu behind a "Servers" button)
//   click   = press one of the choices that were listed
//   episode = press an episode (and its season) in the page's own episode list
//   play    = start the page's player
// Autoplay off: a muted or looping video that starts by itself (a hero trailer, a card preview, a background clip) is stopped
// before it plays, so the page keeps its Play button and the TV does not spend its decoder on video nobody watches.
// It plays when the user just pressed OK or clicked, when the app starts the page's player (__apAllowPlay), and always on a
// watch / play page. Only the page itself and trailer hosts' frames: embedded players are left alone.
(() => {
  if (window.__apAuto) return;
  window.__apAuto = 1;
  if (window !== top && !/(^|\.)(youtube(-nocookie)?\.com|vimeo\.com|imdb\.com|dailymotion\.com)$/i.test(location.hostname)) return;
  let okAt = 0;
  const stopped = new Set();   // in a frame: videos held back before the page said whether the user's press made this frame
  const ok = () => { okAt = Date.now(); };
  window.__apAllowPlay = ok;
  // A frame made by a press on the page (its Trailer button) starts with no press of its own: it asks the page how long ago one was
  addEventListener('message', e => {
    const d = e.data;
    if (!d || typeof d !== 'object') return;
    if (d.fp === 'askedq' && e.source) e.source.postMessage({ fp: 'asked', age: Date.now() - okAt }, '*');
    else if (d.fp === 'asked' && e.source === parent && d.age < 5000) {   // this frame is what the press asked for: start what was held
      ok();
      for (const v of stopped) if (v.isConnected) v.play().catch(() => { });
      stopped.clear();
    }
  });
  if (window !== top) parent.postMessage({ fp: 'askedq' }, '*');
  addEventListener('keydown', e => { if (e.isTrusted && /^(Enter| |MediaPlayPause|MediaPlay)$/.test(e.key)) ok(); }, true);
  for (const t of ['pointerdown', 'click', 'touchstart']) addEventListener(t, e => { if (e.isTrusted) ok(); }, true);
  const watching = () => /\/(watch|play|player|embed|stream)\b|[?&](play|autoplay)=(1|true)\b/i.test(location.pathname + location.search) && window === top;
  const unasked = v => (v.muted || v.defaultMuted || v.loop) && Date.now() - okAt > 2000 && !watching();
  const stop = v => { v.autoplay = false; v.removeAttribute('autoplay'); v.pause(); if (window !== top) stopped.add(v); };
  document.addEventListener('play', e => { if (e.target instanceof HTMLMediaElement && unasked(e.target)) stop(e.target); }, true);
  const play = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    if (!unasked(this)) return play.apply(this, arguments);
    stop(this);
    return Promise.reject(new DOMException('Autoplay is off', 'NotAllowedError'));   // what a browser says when it blocks autoplay: sites show their Play button
  };
})();

(() => {
  if (window.__fpf) return;
  window.__fpf = 1;
  const fid = Math.random().toString(36).slice(2, 7);
  let items = [];                                     // {label, on, el | opener}
  const wait = ms => new Promise(r => setTimeout(r, ms));
  const label = e => (e.innerText || '').trim().replace(/\s+/g, ' ');
  const junk = /reorder|drag|close|cancel|dismiss|^back\b|search all|again|^[x×✕]$/i;
  const onTok = /^(is[-_])?(active|selected|current|checked|playing)$|[-_](active|selected|current)$/i;
  const optSel = 'button,[role=option],[role=menuitem],[role=radio],li,a';
  const leaf = e => !e.querySelector(optSel);

  const isOn = e => {
    for (let n = e, i = 0; n && i < 4; n = n.parentElement, i++) {
      if ([...n.classList].some(t => onTok.test(t))) return true;
      if (n.getAttribute('aria-selected') === 'true' || n.getAttribute('aria-checked') === 'true' || n.hasAttribute('aria-current')) return true;
    }
    return false;
  };
  const click = e => {
    if (window.__apAllowPlay) window.__apAllowPlay();   // the app pressing an episode, a server or Play: what starts next was asked for
    for (const t of ['pointerdown', 'mousedown', 'pointerup', 'mouseup'])
      e.dispatchEvent(new (t[0] === 'p' ? PointerEvent : MouseEvent)(t, { bubbles: true, cancelable: true, view: window }));
    e.click();
  };
  // Players hide their controls (and the Servers button with them) after a few seconds without a pointer
  const wake = async () => {
    const v = [...document.querySelectorAll('video')].sort((a, b) => b.clientWidth - a.clientWidth)[0];
    for (const t of [document, document.body, v, v && v.parentElement])
      if (t) for (const n of ['pointermove', 'mousemove']) t.dispatchEvent(new MouseEvent(n, { bubbles: true, clientX: 200, clientY: 200 }));
    await wait(450);
  };

  // 1) choices already on screen: a group of 2+ siblings that say server / source / mirror
  const named = /\b(servers?|sources?|mirrors?|providers?)\b/i;
  const meta = e => String(e.className && e.className.baseVal === undefined ? e.className : '') + ' ' + e.id + ' ' + [...e.attributes].filter(a => /^data-/.test(a.name)).map(a => a.name + a.value).join(' ');
  const onScreen = () => {
    const all = [...document.querySelectorAll('button,a,li,span,div,[role=tab],[role=button],[role=option]')].filter(e => {
      const t = label(e);
      if (t.length < 2 || t.length > 28) return false;
      const r = e.getBoundingClientRect();
      if (r.width < 10 || r.height < 10 || r.width > 420 || !leaf(e)) return false;
      return /^(server|source|mirror|provider|player|embed)\b/i.test(t) || named.test(meta(e));
    });
    const g = new Map();
    for (const e of all) { const p = e.parentElement; if (!g.has(p)) g.set(p, []); g.get(p).push(e); }
    let out = [];
    for (const v of g.values()) if (v.length >= 2) out = out.concat(v);
    return out.slice(0, 12).map(e => ({ label: label(e), on: isOn(e), el: e }));
  };

  // The current entry often just has a different class from the rest (Tailwind sites): mark the odd one out
  const markOdd = list => {
    const cls = list.map(x => String(x.el.className)), n = new Map();
    cls.forEach(c => n.set(c, (n.get(c) || 0) + 1));
    const odd = list.filter((x, i) => n.get(cls[i]) === 1);
    if (list.length >= 3 && odd.length === 1 && !list.some(x => x.on)) odd[0].on = true;
    return list;
  };

  // 1b) a "Server(s)" or "Quality" heading with entries under it, even inside a popup that is hidden right now
  //     (hidden entries can still be pressed from script)
  const headRe = /^(servers?|sources?|mirrors?|providers?)$/i, qualRe = /^(quality|qualities|resolution)$/i;
  // AUDIO (a language list: English, Hindi...) is its own group; SUBTITLES only marks where the audio list ends
  // (not a plain "Language" heading: that is often the site's own language switcher, whose links leave the page)
  const audioHeadRe = /^(audio|audio languages?|audio tracks?|dubs?)$/i, subsHeadRe = /^(subtitles?|captions?|cc|languages?)$/i;
  // Text of an entry from its leaf pieces, so "1080p" + "Full HD" reads "1080p Full HD" (works while hidden)
  const textOf = e => [...e.querySelectorAll('*')].filter(c => !c.children.length).map(c => (c.textContent || '').trim()).filter(Boolean).join(' ') || (e.textContent || '').trim();
  // Just the name of an entry ("Lavender" of "Lavender No stream found")
  const firstText = e => [...e.querySelectorAll('*')].filter(c => !c.children.length).map(c => (c.textContent || '').trim()).find(Boolean) || (e.textContent || '').trim();
  const headed = () => {
    const out = { s: [], q: [], a: [] };
    const heads = [...document.querySelectorAll('*')].filter(h => {
      if (h.children.length > 1 || h.closest(optSel)) return false;
      const t = (h.textContent || '').trim();
      return headRe.test(t) || qualRe.test(t) || audioHeadRe.test(t) || subsHeadRe.test(t);
    });
    for (const h of heads) {
      const t = (h.textContent || '').trim();
      const kind = headRe.test(t) ? 's' : qualRe.test(t) ? 'q' : audioHeadRe.test(t) ? 'a' : '';
      if (!kind || out[kind].length) continue;
      // the other lists' headings (not this heading's own wrapper or inner element, which match the same word)
      const others = heads.filter(o => o !== h && !o.contains(h) && !h.contains(o));
      const after = (a, b) => !!(a.compareDocumentPosition(b) & 4);   // b comes after a
      for (let c = h.parentElement, i = 0; c && i < 3; c = c.parentElement, i++) {
        // A box that also holds another list's heading (Quality and Server side by side, or one under the other): only what
        // sits between this heading and the next one. With just "Auto" under Quality (an adaptive stream) the whole box used
        // to be read, Auto plus every server, as qualities
        const shared = others.filter(o => c.contains(o)), next = shared.find(o => after(h, o));
        const mine = e => !shared.length || (after(h, e) && (!next || after(e, next)));
        // an audio box can hold its subtitle buttons too ("External subtitles", "Upload", "Subtitle style"): languages only, no links
        const opts = [...c.querySelectorAll(optSel)].filter(e => leaf(e) && !e.contains(h) && mine(e) && !(kind === 'a' && (e.matches('a[href]') || /subtitle|caption|upload|style|sync/i.test(textOf(e)))));
        const names = opts.map(textOf);
        if (opts.length >= 2 && names.every(n => n.length >= 1 && n.length <= 30 && !junk.test(n))) {
          out[kind] = markOdd(opts.slice(0, 24).map((e, j) => ({ label: names[j], on: isOn(e), el: e })));
          break;
        }
      }
    }
    return out;
  };

  // 1c) native <select> dropdowns: custom-styled players (anime sites especially) keep their real choices in <select>s behind the pretty ones.
  //     AUDIO / LANGUAGE (sub, dub) is its own group ('a'): the language is picked first, then the server (whose list changes with it)
  const capRe = /^(audio|languages?|lang|servers?|sources?|mirrors?|providers?|quality|qualities|resolution)\b/i;
  const audioWords = /^(sub(bed|s)?|dub(bed|s)?|raw|soft-?sub|hard-?sub|multi(-audio)?|original)$/i;
  const caps = root => [...root.querySelectorAll('*')].filter(e => e.children.length <= 1 && !e.closest('select,option') && (e.textContent || '').trim().length < 24 && capRe.test((e.textContent || '').trim()));
  const kindOf = hint => /audio|lang|dub/i.test(hint) ? 'a' : /server|source|mirror|provider/i.test(hint) ? 's' : /quality|resolution/i.test(hint) ? 'q' : '';
  const selects = () => {
    const out = { s: [], a: [], q: [] };
    const all = [...document.querySelectorAll('select')].filter(x => x.options.length >= 2 && x.options.length <= 40);
    for (const sel of all) {
      const names = [...sel.options].map(o => (o.textContent || '').trim());
      if (names.some(n => !n || n.length > 40)) continue;
      // episode ranges, page numbers, years: not a server or language. (The dash is escaped: ",-–" was a range that matched
      // nearly every letter, so dropdowns like Sub / Dub or Vidstream / Mp4upload were skipped as "numbers")
      if (names.every(n => /^[\d\s.,\-–]+$/.test(n))) continue;
      let kind = kindOf([sel.getAttribute('aria-label'), sel.name, sel.id].join(' '));
      const lab = !kind && sel.id && document.querySelector('label[for="' + CSS.escape(sel.id) + '"]');
      if (lab) kind = kindOf(lab.textContent || '');
      // captions in a row above the dropdowns (AUDIO, SERVER (6)): the n-th caption names the n-th dropdown
      for (let a = sel.parentElement, i = 0; !kind && a && i < 6; a = a.parentElement, i++) {
        const sels = [...a.querySelectorAll('select')].filter(x => x.options.length >= 2), cs = caps(a);
        if (sels.length >= 2 && cs.length === sels.length) kind = kindOf((cs[sels.indexOf(sel)].textContent || '').trim());
        else if (sels.length === 1 && cs.length && i < 3) kind = kindOf((cs.filter(c => c.compareDocumentPosition(sel) & 4).pop() || cs[0]).textContent || '');
      }
      if (!kind && names.every(n => audioWords.test(n))) kind = 'a';
      if (kind) out[kind] = out[kind].concat(names.map((n, i) => ({ label: n, on: sel.selectedIndex === i, sel, idx: i })));
    }
    return out;
  };

  // 1d) ARIA comboboxes (the phone layout of the same players): a button that says what it picks (aria-label "Language", "Server")
  //     and opens a listbox only when pressed: open it, read the options, close it again
  const shown = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  const visibleOptions = () => [...document.querySelectorAll('[role=option],[role=menuitem],[role=menuitemradio]')].filter(shown);
  const picked = e => e.getAttribute('aria-selected') === 'true' || e.getAttribute('aria-checked') === 'true' || e.getAttribute('data-state') === 'checked' || e.hasAttribute('data-selected') || /\b(selected|active|current)\b/i.test(String(e.className));
  const comboBtn = kind => [...document.querySelectorAll('[role=combobox],button[aria-haspopup=listbox],button[aria-haspopup=menu],button[aria-haspopup=true]')].find(b => shown(b) && kindOf(b.getAttribute('aria-label') || '') === kind);
  const closeList = async b => {
    for (const t of [document.activeElement, document, document.body]) if (t) t.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
    await wait(120);
    if (visibleOptions().length) { click(b); await wait(150); }
  };
  const combos = async () => {
    const out = { s: [], a: [], q: [] };
    for (const kind of ['a', 's']) {   // not quality: AnantPlayer picks it, and opening that menu shows on screen
      const b = comboBtn(kind);
      if (!b) continue;
      const before = new Set(visibleOptions());
      click(b);
      await wait(350);
      const opts = visibleOptions().filter(o => !before.has(o));
      const names = opts.map(textOf);
      const now = firstText(b);
      if (opts.length >= 2 && names.every(n => n.length >= 1 && n.length <= 40)) out[kind] = names.map((n, i) => ({ label: n, on: picked(opts[i]) || n === now, combo: b }));
      await closeList(b);
    }
    return out;
  };

  // 1e) Sub / Dub as buttons or tabs side by side (SUB | DUB)
  const audioToggles = () => {
    const all = [...document.querySelectorAll('button,[role=tab],[role=radio],a,li')].filter(e => leaf(e) && shown(e) && audioWords.test(label(e).replace(/\s*\(.*\)$/, '')));
    const g = new Map();
    for (const e of all) { const p = e.parentElement; if (!g.has(p)) g.set(p, []); g.get(p).push(e); }
    for (const v of g.values()) if (v.length >= 2) return v.map(e => ({ label: label(e), on: isOn(e) || e.getAttribute('aria-pressed') === 'true', el: e }));
    return [];
  };

  // A real list names its entries apart: a pair of "10"s (the player's skip buttons, drawn again) is not a server list
  const distinct = l => l.length >= 2 && new Set(l.map(x => x.label)).size === l.length && !l.every(x => /^([+-]\d+|\d+\s*s(ec)?)$/i.test(x.label));

  // 2) a button called Servers / Sources that opens a menu: open it, read the entries, close it again
  const openers = () => [...document.querySelectorAll('button,[role=button],a')].filter(b => {
    const t = (b.getAttribute('aria-label') || b.title || label(b)).trim();
    return /^(servers?|sources?|mirrors?|change (server|source))$/i.test(t);
  });
  const entries = added => added.filter(e => e.matches(optSel) && leaf(e)).filter(e => {
    const t = firstText(e);
    return t.length >= 1 && t.length <= 30 && !junk.test(t) && !junk.test(e.getAttribute('aria-label') || '');
  });
  const closeMenu = (added, op) => {
    const x = added.find(e => /close|dismiss|cancel/i.test(e.getAttribute('aria-label') || e.title || ''));
    click(x || op);
  };
  const viaMenu = async () => {
    let ops = openers();
    if (!ops.length) { await wake(); ops = openers(); }
    for (const op of ops) {
      const before = new Set(document.getElementsByTagName('*'));
      click(op);
      // the panel may slide in after the player redraws its own controls: look again a moment later
      let added = [], list = [];
      for (let i = 0; i < 3 && !distinct(list); i++) {
        await wait(i ? 500 : 700);
        added = [...document.getElementsByTagName('*')].filter(e => !before.has(e));
        list = entries(added).slice(0, 12).map(e => ({ label: label(e), on: isOn(e), menu: true }));
      }
      closeMenu(added, op);
      if (distinct(list)) return list;
    }
    return [];
  };
  // 2b) Servers hide one level deeper: a Settings button opens a menu with a "Source" / "Server" row, which opens the list
  const settingsBtns = () => [...document.querySelectorAll('button,[role=button]')].filter(b => /^(settings?|options?|more options|menu)$/i.test((b.getAttribute('aria-label') || b.title || label(b)).trim()));
  const sourceRow = added => added.find(e => e.matches(optSel) && /^(servers?|sources?)$/i.test(firstText(e)));
  // The site may already have the Settings menu open (some keep it open after a pick): then use it instead of clicking again
  const openRow = () => [...document.querySelectorAll(optSel)].find(e => /^(servers?|sources?)$/i.test(firstText(e)) && e.closest('[role=dialog],[role=menu]'));
  const sourceList = () => {
    const dlg = [...document.querySelectorAll('[role=dialog],[role=menu],[role=listbox]')].reverse().find(d => /server|source/i.test(d.getAttribute('aria-label') || ''));
    return entries([...(dlg || document.body).querySelectorAll('*')]);
  };
  const viaSettings = async () => {
    let ops = settingsBtns();
    if (!ops.length) { await wake(); ops = settingsBtns(); }
    for (const op of ops) {
      const before = new Set(document.getElementsByTagName('*'));
      let row = openRow(), added = [];
      if (!row) {
        click(op);
        await wait(700);
        added = [...document.getElementsByTagName('*')].filter(e => !before.has(e));
        row = sourceRow(added);
      }
      let list = [];
      if (row) { click(row); await wait(700); list = sourceList(); }
      const names = list.map(firstText), marks = list.map(isOn);
      closeMenu(added, op);
      if (list.length >= 2) return names.slice(0, 24).map((n, i) => ({ label: n, on: marks[i], settings: true }));
    }
    return [];
  };
  // Press a listed choice (a menu has to be opened again first, then the entry with that label is pressed)
  const choose = (sel, idx) => {
    // the way a user's pick reaches React and friends: set through the native setter, then fire input and change
    const set = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set;
    set.call(sel, sel.options[idx].value);
    sel.dispatchEvent(new Event('input', { bubbles: true }));
    sel.dispatchEvent(new Event('change', { bubbles: true }));
  };
  const press = async it => {
    if (it.combo) {
      const b = document.contains(it.combo) ? it.combo : comboBtn(it.kind);
      if (!b) return;
      click(b);
      await wait(450);
      const t = visibleOptions().find(o => textOf(o) === it.label);
      if (t) click(t); else await closeList(b);
      return;
    }
    if (it.sel) {
      if (!document.contains(it.sel)) { const fresh = selects()[it.kind].find(x => x.label === it.label); if (fresh) choose(fresh.sel, fresh.idx); }
      else choose(it.sel, it.idx);
      return;
    }
    if (it.el && document.contains(it.el)) { click(it.el); return; }
    // The page re-drew its list while it was paused: find the entry again by its name
    if (it.el) {
      const fresh = (it.kind === 'a' ? headed().a.concat(audioToggles()) : headed()[it.kind === 'q' ? 'q' : 's']).find(x => x.label === it.label);
      if (fresh) { click(fresh.el); return; }
    }
    if (it.settings) {
      let op = settingsBtns()[0];
      if (!op) { await wake(); op = settingsBtns()[0]; }
      if (!op) return;
      const before = new Set(document.getElementsByTagName('*'));
      let row = openRow();
      if (!row) {
        click(op);
        await wait(700);
        row = sourceRow([...document.getElementsByTagName('*')].filter(e => !before.has(e)));
      }
      if (!row) return;
      click(row);
      await wait(700);
      const t = sourceList().find(e => firstText(e) === it.label);
      if (t) click(t);
      return;
    }
    if (!it.menu) return;
    let op = openers()[0];
    if (!op) { await wake(); op = openers()[0]; }
    if (!op) return;
    click(op);
    await wait(700);
    const t = [...document.querySelectorAll(optSel)].find(e => leaf(e) && label(e) === it.label);
    if (t) click(t);
  };

  // Some sites hide their stream behind URLs with no .m3u8 in them: watch what the page downloads and
  // recognise a playlist by its first bytes, then tell the app
  const report = (url, dash) => {
    try {
      const u = new URL(url, location.href);
      window.AnantPlay && AnantPlay.stream(u.href, !!dash, location.href, u.origin !== location.origin ? location.origin : '');
    } catch (e) { }
  };
  const sniff = (url, head) => {
    if (/^\s*#EXTM3U/.test(head)) report(url, false);
    else if (/^\s*(<\?xml[^>]*>\s*)?<MPD[\s>]/.test(head)) report(url, true);
  };
  // The watch goes in before the page's scripts run: players often keep their own copy of fetch from the start.
  // A few sites check, as they start, that fetch and friends are the browser's own and refuse to run otherwise
  // (checked from a fresh frame): there it goes in once the start-up scripts have run. Which sites: the title-links source's
  // "@late" list (host names without the ending), which the app puts in window.__apLate before this script.
  const strict = (window.__apLate || []).some(h => (location.hostname.toLowerCase() + '.').includes('.' + h + '.') || location.hostname.toLowerCase().startsWith(h + '.'));
  const hook = () => {
  try {
    const xo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function open() {
      this.addEventListener('load', () => {
        try {
          const t = this.responseType;
          if (t === '' || t === 'text') sniff(this.responseURL, String(this.responseText).slice(0, 64));
          else if (t === 'arraybuffer' && this.response && this.response.byteLength < 4e6) sniff(this.responseURL, new TextDecoder().decode(new Uint8Array(this.response, 0, 32)));
        } catch (e) { }
      });
      return xo.apply(this, arguments);
    };
    const fo = window.fetch;
    window.fetch = function fetch() {
      const p = fo.apply(this, arguments);
      p.then(r => {
        const ct = r.headers.get('content-type') || '', len = +(r.headers.get('content-length') || 0);
        if (/video|audio|image|font|css|javascript/i.test(ct) || len > 4e6 || !r.body) return;
        const rd = r.clone().body.getReader();
        rd.read().then(c => { rd.cancel(); if (c.value) sniff(r.url, new TextDecoder().decode(c.value.slice(0, 32))); }).catch(() => { });
      }).catch(() => { });
      return p;
    };
  } catch (e) { }
  };
  if (strict && document.readyState === 'loading') addEventListener('DOMContentLoaded', hook, { once: true }); else hook();

  // An episode in the page's own list: the season first (a <select>, tab or button that says "Season 2" / "S2"), then the entry
  // that says 3 / "Ep 3" / "Episode 3 - Name" (or carries data-episode / data-number 3) among 3+ such entries under one parent
  const seasonRe = n => new RegExp('^(season|s)\\s*0*' + n + '(?![0-9])', 'i');
  const epAny = /^(e|ep|eps|episode)?\.?\s*\d{1,4}(?![0-9])/i;
  const epRe = n => new RegExp('^(e|ep|eps|episode)?\\.?\\s*0*' + n + '(?![0-9])(?!\\s*(min|mins|h|sec|views?|st|nd|rd|th)\\b)', 'i');
  const epNum = e => e.getAttribute('data-episode') || e.getAttribute('data-number') || e.getAttribute('data-ep');
  // A list that sits behind a button (a player's own Episodes menu, P-Stream): press "Episodes", and when the season shown is not
  // the one wanted, press the season's own button too (it opens the list of seasons)
  const shownBtn = re => [...document.querySelectorAll('button,[role=button],a')].find(b => {
    const r = b.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && re.test((label(b) || b.getAttribute('aria-label') || '').trim());
  });
  // a "Choose a season" dropdown (its label says the season shown: "Season 2 3 / 9")
  const seasonMenu = () => [...document.querySelectorAll('button[aria-haspopup],[role=combobox]')].find(b => {
    const r = b.getBoundingClientRect();
    return r.width > 0 && /season/i.test((b.getAttribute('aria-label') || '') + ' ' + label(b));
  });
  const seasonPick = async s => {
    const sel = [...document.querySelectorAll('select')].find(x => [...x.options].some(o => seasonRe(s).test(o.text.trim())));
    if (sel) {
      const i = [...sel.options].findIndex(o => seasonRe(s).test(o.text.trim()));
      if (sel.selectedIndex !== i) { choose(sel, i); await wait(1200); }
      return true;
    }
    const m = seasonMenu();
    if (m && seasonRe(s).test(label(m))) return true;   // already showing that season
    const b = [...document.querySelectorAll('button,a,li,[role=tab],[role=option],[role=button]')].find(x => leaf(x) && !x.matches('[aria-haspopup],[role=combobox]') && label(x).length < 20 && seasonRe(s).test(label(x)));
    if (b) { if (!isOn(b)) { click(b); await wait(1200); } return true; }
    return false;
  };
  const episodeHit = n => {
    // its own text, or a piece of it: "E3 · 53 min" under the episode's name
    const pieces = x => [label(x)].concat([...x.querySelectorAll('*')].filter(c => !c.children.length).map(c => (c.textContent || '').trim()).filter(t => t && t.length < 40));
    const all = [...document.querySelectorAll('a,button,li,article,[role=button],[role=option],[data-episode],[data-number],[data-ep]')].filter(x => {
      const t = label(x);
      return epNum(x) !== null || (t && t.length < 400 && pieces(x).some(p => epAny.test(p)));
    });
    // 3+ entries under one parent; a row that wraps each entry with its own icons (P-Stream) puts them a level or two further apart
    for (let depth = 1; depth <= 6; depth++) {   // the closest grouping wins
      const g = new Map();
      for (const x of all) {
        let p = x;
        for (let i = 0; i < depth && p; i++) p = p.parentElement;
        if (!g.has(p)) g.set(p, []);
        g.get(p).push(x);
      }
      for (const v of g.values()) {
        if (v.length < 3) continue;
        const hit = v.find(x => +epNum(x) === n) || v.find(x => pieces(x).some(p => epRe(n).test(p)));
        if (hit) return hit;
      }
    }
    return null;
  };
  const pickEpisode = async (s, n) => {
    let opened = false, seasonDone = s <= 0;   // the season is chosen once; after that only its episodes are waited for
    // a player draws its Episodes button once it has found a source, and its menu a moment after that: keep looking (~15 s)
    for (let misses = 0, tries = 0; misses < 36 && tries < 45; tries++) {
      let seasonOk = seasonDone || await seasonPick(s);
      if (!seasonOk) {
        // the season dropdown: its options may be bare numbers (1, 2, 3) once it is open
        const menu = seasonMenu() || shownBtn(/^season\s*\d+/i);
        if (menu) {
          click(menu);
          await wait(900);
          const o = visibleOptions().find(x => { const t = textOf(x).trim(); return t === String(s) || seasonRe(s).test(t); });
          if (o) { click(o); await wait(1200); seasonOk = true; } else seasonOk = await seasonPick(s);
        }
      }
      if (seasonOk) seasonDone = true;
      const hit = seasonOk ? episodeHit(n) : null;
      if (hit) { hit.scrollIntoView({ block: 'center' }); click(hit); return true; }
      if (opened) { await wait(700); continue; }
      const menu = shownBtn(/^(episodes?|episode list|all episodes|seasons?)$/i);
      if (!menu) { misses++; await wait(700); continue; }   // the player may not have drawn its controls yet
      click(menu);
      opened = true;
      await wait(1000);
    }
    return false;
  };

  // Start the page's player, as a tap on its Play would (after an episode or Sub / Dub was opened from the app)
  const playRe = /\b(vjs-big-play-button|jw-icon-display|plyr__control--overlaid|play-?(btn|button)|big-?play)\b/i;
  const startPlayer = () => {
    try { if (window.jwplayer && jwplayer().getState && jwplayer().getState() !== 'playing') { jwplayer().play(); return true; } } catch (x) { }
    const area = e => { const r = e.getBoundingClientRect(); return r.width * r.height; };
    const b = [...document.querySelectorAll('button,[role=button],div,span,a')].filter(e => {
      const r = e.getBoundingClientRect();
      if (r.width < 24 || r.height < 24 || r.bottom < 0 || r.top > innerHeight) return false;
      return /^play( video)?$/i.test((e.getAttribute('aria-label') || e.title || '').trim()) || playRe.test(String(e.className && e.className.baseVal === undefined ? e.className : ''));
    }).sort((x, y) => area(y) - area(x))[0];
    if (b) { click(b); return true; }
    // a title's page with its own Play / Watch now button (it leads to the player)
    const w = [...document.querySelectorAll('button,a,[role=button]')].filter(e => {
      const r = e.getBoundingClientRect();
      return r.width >= 40 && r.height >= 24 && r.bottom > 0 && r.top < innerHeight * 1.5 && /^(\u25B6\s*)?(play|play now|watch|watch now|watch movie|start watching|resume)$/i.test((e.innerText || '').trim());
    }).sort((x, y) => area(y) - area(x))[0];
    if (w) { click(w); return true; }
    const v = [...document.querySelectorAll('video')].find(x => x.paused && (x.currentSrc || x.src) && area(x) > 20000);
    if (v) { v.play().catch(() => { }); return true; }
    return false;
  };

  // While the stream plays in the app's player the page's own player stays stopped ({fp:'pause', hold}): playing underneath it
  // kept downloading, and back on the page it started again and was handed over again, in a loop. A press on the page (OK, a
  // tap) frees it, and so does the app when it switches a server or episode there (release, play).
  let hold = false;
  document.addEventListener('play', e => { if (hold && e.target instanceof HTMLMediaElement) e.target.pause(); }, true);
  const held = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    if (!hold) return held.apply(this, arguments);
    this.pause();
    return Promise.reject(new DOMException('Playing in AnantPlayer', 'NotAllowedError'));
  };
  const free = () => { if (!hold) return; hold = false; if (window === top) down({ fp: 'release' }); };
  addEventListener('keydown', e => { if (e.isTrusted && /^(Enter| |MediaPlayPause|MediaPlay)$/.test(e.key)) free(); }, true);
  for (const t of ['pointerdown', 'touchstart']) addEventListener(t, e => { if (e.isTrusted) free(); }, true);

  const up = m => window === top ? (window.AnantPlay && AnantPlay.servers(JSON.stringify(m.items), m.gen | 0)) : parent.postMessage(m, '*');
  const down = m => { for (let i = 0; i < frames.length; i++) try { frames[i].postMessage(m, '*'); } catch (x) { } };
  addEventListener('message', async e => {
    const d = e.data;
    if (!d || typeof d !== 'object' || !d.fp) return;
    if (d.fp === 'pause') { if (d.hold) hold = true; document.querySelectorAll('video,audio').forEach(m => m.pause()); down(d); }
    else if (d.fp === 'release') { hold = false; down(d); }
    else if (d.fp === 'servers') {
      // the top frame looks first, then tells its frames whether to open menus (it found no server list itself) or not
      if (window !== top) down(d);
      const deep = window === top || d.deep !== false;
      const h = headed();
      let ns = selects();
      if (ns.a.length < 2 && ns.s.length < 2 && !(h.a.length >= 2 && h.s.length >= 2) && deep) {   // no <select>s: the phone layout's comboboxes
        const c = await combos();
        ns = { s: ns.s.length >= 2 ? ns.s : c.s, a: ns.a.length >= 2 ? ns.a : c.a, q: ns.q.length ? ns.q : c.q };
      }
      // an AUDIO heading's own list (English, Hindi...) beats a guess from a menu that also holds the subtitle languages
      if (h.a.length >= 2) ns.a = h.a;
      else if (ns.a.length < 2) ns.a = audioToggles();
      // a dropdown that is labelled Server / Source is the most reliable list; guessing from the look of buttons comes after it
      let sv = ns.s.length >= 2 ? ns.s : markOdd(onScreen());
      if (!distinct(sv)) sv = h.s;
      if (!distinct(sv) && deep) sv = await viaMenu();
      if (!distinct(sv) && deep) sv = await viaSettings();
      if (!distinct(sv)) sv = [];
      const qs = h.q.length ? h.q : ns.q;
      items = sv.map(x => Object.assign(x, { kind: 's' })).concat(qs.map(x => Object.assign(x, { kind: 'q' })))
        .concat(ns.a.length >= 2 ? ns.a.map(x => Object.assign(x, { kind: 'a' })) : []);
      const tries = d.tries || 0;
      if (window === top && !tries) down({ fp: 'servers', gen: d.gen, deep: sv.length < 2 });   // the frames look once, right away
      // Nothing yet, and the player is in the page itself: one that builds its menus a moment after its stream starts (Bingr) is
      // looked at again, twice, before the app is told the scan is done and hands the stream over (it waits up to 3 s for this).
      // A page whose player is in a frame has nothing to wait for here
      if (window === top && !items.length && tries < 2 && document.querySelector('video')) { await wait(800); postMessage({ fp: 'servers', gen: d.gen, tries: tries + 1 }, '*'); return; }
      // the page always answers, also with nothing: the lists of an earlier scan must not outlive it (their entries are gone)
      if (items.length || window === top) up({ fp: 'reply', gen: d.gen, items: items.map((it, i) => ({ k: fid + ':' + i, label: it.label, on: it.on, kind: it.kind })) });
      if (window === top && window.AnantPlay) AnantPlay.scanDone();
    } else if (d.fp === 'episode') {
      down(d);
      if (await pickEpisode(d.s, d.e) && window.AnantPlay) AnantPlay.episodeFound();
    } else if (d.fp === 'play') {
      hold = false;
      down(d);
      if (window.__apAllowPlay) window.__apAllowPlay();
      startPlayer();
    } else if (d.fp === 'reply') up(d);
    else if (d.fp === 'click') {
      let it = items.find((_, j) => fid + ':' + j === d.k);
      // a Sub / Dub kept from an earlier look at this page: the list was read again since, so find it by its name
      if (String(d.k).startsWith(fid + ':') && d.kind === 'a' && (!it || it.label !== d.label)) {
        const b = comboBtn('a');
        it = selects().a.find(x => x.label === d.label) || headed().a.find(x => x.label === d.label) || audioToggles().find(x => x.label === d.label) || (b ? { combo: b, kind: 'a', label: d.label } : null);
      }
      if (it) press(it);
      down(d);
    }
  });
})();

// Taps, like Brave's pop-up and overlay layers (top frame only, so ad frames can't report themselves):
//   - the link that was tapped goes to the app, which then knows a pop-up to anywhere else is an ad, and opens this link instead
//   - an invisible layer stretched over most of the page (a click-jack for pop-unders) is made click-through before the tap lands
(() => {
  if (window !== top || window.__fpTaps) return;
  window.__fpTaps = 1;
  const invisible = el => {
    const s = getComputedStyle(el);
    if (+s.opacity < 0.1 || s.visibility === 'hidden') return true;
    const clear = c => /rgba\(.*,\s*0\)$|transparent/.test(c);
    return clear(s.backgroundColor) && s.backgroundImage === 'none' && !(el.innerText || '').trim() && !el.querySelector('img,svg,picture,canvas,video,iframe');
  };
  const layer = el => {
    for (let n = el, i = 0; n && n !== document.body && i < 4; n = n.parentElement, i++) {
      const s = getComputedStyle(n);
      if (s.position !== 'fixed' && s.position !== 'absolute') continue;
      const r = n.getBoundingClientRect();
      if (r.width * r.height < innerWidth * innerHeight * 0.6 || !invisible(n)) continue;
      // A player's own click-catcher sits next to its video: leave those alone
      if (n.parentElement && n.parentElement.querySelector('video,iframe')) return null;
      return n;
    }
    return null;
  };
  const down = e => {
    const l = e.target instanceof Element && layer(e.target);
    if (!l) return;
    l.style.setProperty('pointer-events', 'none', 'important');
    e.stopImmediatePropagation();
  };
  addEventListener('pointerdown', down, true);
  addEventListener('touchstart', down, true);
  addEventListener('click', e => {
    const a = e.target instanceof Element && e.target.closest('a[href]');
    if (a && window.AnantPlay && /^https?:/.test(a.href)) AnantPlay.clicked(a.href);
  }, true);
})();

// Nag pop-ups (top frame): "Join our Discord", "What's new in v1.5", donate, "disable your ad blocker", new domain notices.
// A dialog that says one of those is closed with its own Close / Got it / Later button, or taken away when it has none.
// Only for the first half minute of a page: a dialog the user opens later is theirs.
(() => {
  if (window !== top || window.__fpNag) return;
  window.__fpNag = 1;
  const nag = /discord|telegram|reddit|join (us|our)|what'?s new|good news|issues (are|have been) (fixed|resolved)|is back\b|changelog|release notes|announcement|new update|v\d+\.\d+|donat|support us|patreon|ko-?fi|ad ?block|new domain|bookmark|official (site|domain)|mirror/i;
  const closeWord = /^(got it!?|ok(ay)?|close|dismiss|(maybe )?later|no,? thanks|not now|skip|continue( to site)?|i understand|understood|x|×|✕|✖)$/i;
  const visible = e => { const r = e.getBoundingClientRect(), s = getComputedStyle(e); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && +s.opacity > 0.05; };
  const dialogs = () => [...document.querySelectorAll('[role=dialog],[role=alertdialog],[aria-modal=true],dialog[open],[class*=modal i],[class*=popup i],[class*=dialog i],[class*=announce i],[class*=changelog i]')].filter(d => {
    // never the page itself: <body class="modal-open"> (Bootstrap, while any pop-up is open) matched "modal", and with no
    // Close button to press the whole page was taken away
    if (d === document.body || d === document.documentElement || d.querySelectorAll('*').length > 400) return false;
    if (!visible(d) || d.closest('video') || d.querySelector('video,iframe')) return false;
    const r = d.getBoundingClientRect();
    return r.width * r.height > innerWidth * innerHeight * 0.12 && nag.test((d.innerText || '').slice(0, 2000));
  });
  const sweep = () => {
    // a first-visit picker that only wants a confirmation (a language choice): "Continue to <site>"
    const go = [...document.querySelectorAll('button')].find(b => /^continue to \S+$/i.test((b.innerText || '').trim()) && visible(b));
    if (go) go.click();
    for (const d of dialogs()) {
      const b = [...d.querySelectorAll('button,[role=button],a')].find(x => closeWord.test(((x.innerText || '').trim() || x.getAttribute('aria-label') || x.title || '').trim()) && !/discord|telegram|reddit/i.test(x.href || ''));
      if (b) { b.click(); continue; }
      const box = d.closest('[role=dialog],[aria-modal=true],dialog') || d;
      // nothing to press: take it away, with the dimmed layer behind it, and let the page scroll again
      const back = box.parentElement && getComputedStyle(box.parentElement).position === 'fixed' ? box.parentElement : box;
      back.remove();
      for (const el of [document.documentElement, document.body]) if (el && getComputedStyle(el).overflow === 'hidden') el.style.setProperty('overflow', 'auto', 'important');
    }
  };
  let n = 0;
  const t = setInterval(() => { try { sweep(); } catch (e) { } if (++n > 30) clearInterval(t); }, 1000);
})();
