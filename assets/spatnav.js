// TV remote navigation for any website: arrows move a highlight between clickable things,
// OK clicks, and pages scroll when nothing is left in that direction. Runs in every frame;
// embedded players are entered with OK and left by pressing past their edge.
(() => {
  if (window.__fp) return;
  window.__fp = 1;

  const SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,video,iframe,label[for],' +
    '[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[role=checkbox],[onclick],[tabindex]:not([tabindex="-1"])';
  const TYPING = /^(text|search|email|url|tel|password|number|date|time|datetime-local|month|week)$/i;
  const KEYS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
  let cur = null;

  // What a thing IS (close, play, tile, ...) comes from roles.js; this file only decides what to do about it
  const Roles = window.__fpRoles || { roleOf: () => '', dialogs: () => [] };
  const role = el => Roles.roleOf(el);

  const style = () => {
    const s = document.createElement('style');
    s.textContent = '.__fp{outline:4px solid #FF3B5C!important;outline-offset:2px!important;' +
      'box-shadow:0 0 0 8px rgba(255,59,92,.35)!important;border-radius:6px;transition:outline-offset .1s}';
    (document.head || document.documentElement).appendChild(s);
  };
  document.readyState === 'loading' ? addEventListener('DOMContentLoaded', style) : style();

  const gap = (a1, a2, b1, b2) => Math.max(0, Math.max(a1, b1) - Math.min(a2, b2));
  const inView = r => r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;

  function clickable(el) {
    // Seek bars: OK on them would jump the video, arrows already seek
    if (el.matches('[role=slider],input[type=range]') || /progress|seek|scrub|timeline|slider/i.test((el.getAttribute('class') || '') + ' ' + el.id)) return false;
    const r = el.getBoundingClientRect();
    if (r.width > innerWidth * 0.4 && r.height < 48 && bigVideo()) return false;
    if (el.matches(SEL)) return !el.disabled;
    const c = getComputedStyle(el).cursor;
    return c === 'pointer' && (!el.parentElement || getComputedStyle(el.parentElement).cursor !== 'pointer');
  }

  // Invisible click layers stretched over the page/player are never targets (videos and players are)
  function shown(el, r) {
    if (r.width < 6 || r.height < 6) return false;
    if (r.width * r.height > innerWidth * innerHeight * 0.5 && !/^(VIDEO|IFRAME)$/.test(el.tagName)) return false;
    // Faded-out player controls count as hidden (opacity on any ancestor)
    if (el.checkVisibility) return el.checkVisibility({ opacityProperty: true, visibilityProperty: true });
    const s = getComputedStyle(el);
    return s.visibility !== 'hidden' && +s.opacity > 0.05;
  }

  // Where to search from: the highlighted element, or the screen edge if it scrolled away
  function origin(dir) {
    if (cur && document.contains(cur)) {
      const r = cur.getBoundingClientRect();
      if (inView(r)) return r;
    }
    const w = innerWidth, h = innerHeight;
    return { up: { left: 0, right: w, top: h, bottom: h }, down: { left: 0, right: w, top: 0, bottom: 0 },
      left: { left: w, right: w, top: 0, bottom: h }, right: { left: 0, right: 0, top: 0, bottom: h } }[dir];
  }

  // Things under a pop-up's backdrop are skipped, unless everything is covered (invisible click-jack layers)
  function blockedSet(all) {
    const covered = [], open = [];
    for (const el of all) {
      const r = el.getBoundingClientRect();
      if (r.bottom <= 0 || r.top >= innerHeight || r.right <= 0 || r.left >= innerWidth || !shown(el, r) || !clickable(el)) continue;
      const x = Math.min(Math.max(r.left + r.width / 2, 1), innerWidth - 2), y = Math.min(Math.max(r.top + r.height / 2, 1), innerHeight - 2);
      const hit = document.elementFromPoint(x, y);
      (hit && hit !== el && !el.contains(hit) && !hit.contains(el) ? covered : open).push(el);
    }
    return new Set(open.length ? covered : []);
  }

  function find(dir, from) {
    const f = from || origin(dir), w = innerWidth, h = innerHeight;
    const all = document.querySelectorAll('body *'), blocked = blockedSet(all);
    let best = null, bestScore = Infinity, bestP = 0;
    for (const el of all) {
      if (el === cur || blocked.has(el) || (cur && (el.contains(cur) || cur.contains(el)))) continue;
      const r = el.getBoundingClientRect();
      if (r.bottom < -h || r.top > 2 * h || r.right < -w || r.left > 2 * w) continue;
      let p, s;
      if (dir === 'down') { p = r.top - f.bottom; s = gap(r.left, r.right, f.left, f.right); }
      else if (dir === 'up') { p = f.top - r.bottom; s = gap(r.left, r.right, f.left, f.right); }
      else if (dir === 'right') { p = r.left - f.right; s = gap(r.top, r.bottom, f.top, f.bottom); }
      else { p = f.left - r.right; s = gap(r.top, r.bottom, f.top, f.bottom); }
      if (p < -2) continue;
      const score = p + 2 * s;
      if (score >= bestScore || !shown(el, r) || !clickable(el) || role(el) === 'dot') continue;
      best = el; bestScore = score; bestP = p;
    }
    // Far-away targets would skip content in between: scroll a step instead
    const limit = dir === 'up' || dir === 'down' ? h * 0.75 : w * 0.9;
    return best && bestP <= limit ? best : null;
  }

  function scrollable(el, dx, dy) {
    const root = el === document.scrollingElement || el === document.documentElement || el === document.body;
    if (!root) {
      const s = getComputedStyle(el);
      if (!/(auto|scroll|overlay)/.test(dy ? s.overflowY : s.overflowX)) return false;
    }
    if (dy > 0) return el.scrollTop + el.clientHeight < el.scrollHeight - 2;
    if (dy < 0) return el.scrollTop > 0;
    if (dx > 0) return el.scrollLeft + el.clientWidth < el.scrollWidth - 2;
    return el.scrollLeft > 0;
  }

  // Scrolls the nearest scrollable box (around the highlight, else the page, else the biggest box)
  function scroll(dir) {
    const dx = dir === 'right' ? 1 : dir === 'left' ? -1 : 0, dy = dir === 'down' ? 1 : dir === 'up' ? -1 : 0;
    let el = cur && document.contains(cur) ? cur.parentElement : null;
    while (el && !scrollable(el, dx, dy)) el = el.parentElement;
    const page = document.scrollingElement || document.documentElement;
    if (!el && scrollable(page, dx, dy)) el = page;
    if (!el) {
      let area = 0;
      for (const c of document.querySelectorAll('body *')) {
        if (!scrollable(c, dx, dy)) continue;
        const a = c.clientWidth * c.clientHeight;
        if (a > area) { area = a; el = c; }
      }
    }
    if (!el) return false;
    el.scrollBy({ left: dx * innerWidth * 0.6, top: dy * innerHeight * 0.6, behavior: 'smooth' });
    return true;
  }

  // In pointer mode the page gets no highlights at all (the app tells us through the bridge)
  const pointerMode = () => { try { return !!(window.AnantPlay && AnantPlay.pointer && AnantPlay.pointer()); } catch (e) { return false; } };
  const clear = () => {
    document.querySelectorAll('.__fp').forEach(e => e.classList.remove('__fp'));
    const a = document.activeElement;
    if (a && a !== document.body && a.blur) a.blur();
    cur = null;
  };
  // The app calls these when the mode flips
  window.__fpClear = clear;
  window.__fpResume = () => { cur = null; touched = false; settle(); };

  function highlight(el) {
    if (pointerMode()) return;
    if (cur) cur.classList.remove('__fp');
    cur = el;
    el.classList.add('__fp');
    // Keyboard focus follows the highlight, except a player frame keeps it here until OK enters it
    if (el.tagName === 'IFRAME') {
      if (document.activeElement === el) el.blur();
    } else {
      if (!el.matches('a[href],button,input,select,textarea,summary,[tabindex]')) el.setAttribute('tabindex', '-1');
      el.focus({ preventScroll: true });
    }
    const r = el.getBoundingClientRect();
    if (r.top < 40 || r.bottom > innerHeight - 40 || r.left < 0 || r.right > innerWidth)
      el.scrollIntoView({ block: r.height > innerHeight * 0.8 ? 'start' : 'center', inline: 'center', behavior: 'smooth' });
  }

  function move(dir) {
    const next = find(dir);
    if (next) return highlight(next);
    if (scroll(dir)) return;
    if (bigVideo()) {
      if (cur) cur.classList.remove('__fp');
      cur = null;
      return;
    }
    if (window.parent !== window) {
      if (cur) cur.classList.remove('__fp');
      cur = null;
      parent.postMessage({ __fp: 'exit', dir }, '*');
    }
  }

  function largest() {
    let best = null, area = 0;
    for (const el of document.querySelectorAll('body *')) {
      const r = el.getBoundingClientRect();
      if (!inView(r) || r.width * r.height <= area || !shown(el, r) || !clickable(el)) continue;
      best = el; area = r.width * r.height;
    }
    return best;
  }

  const video = () => {
    const vs = [...document.querySelectorAll('video')];
    return vs.find(v => !v.paused) || vs.sort((a, b) => b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight)[0];
  };

  // Players hide their controls after a few seconds without mouse movement; fake some
  function wake(v) {
    const r = v.getBoundingClientRect(), at = { bubbles: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
    for (const t of ['pointermove', 'mousemove']) v.dispatchEvent(t[0] === 'p' ? new PointerEvent(t, at) : new MouseEvent(t, at));
  }

  function toggle(v) {
    v.paused ? v.play() : v.pause();
  }

  function fullscreen(el) {
    if (document.fullscreenElement) return;
    const target = el.closest('[class*=player],[id*=player]') || el;
    (target.requestFullscreen || target.webkitRequestFullscreen || (() => {})).call(target);
  }

  // Full-screen video: OK play/pause, left/right seek 10s, up/down wake the player's controls
  function media(key) {
    const v = video();
    if (!v) return false;
    if (key === 'Enter' || key === 'MediaPlayPause' || key === ' ') toggle(v);
    else if (key === 'ArrowLeft' || key === 'MediaRewind') v.currentTime = Math.max(0, v.currentTime - 10);
    else if (key === 'ArrowRight' || key === 'MediaFastForward') v.currentTime += 10;
    else if (key === 'ArrowUp' || key === 'ArrowDown') wake(v);
    else return false;
    return true;
  }

  function activate(el) {
    if (el.tagName === 'VIDEO') { toggle(el); fullscreen(el); return; }
    if (el.tagName === 'IFRAME') { el.focus(); el.contentWindow.postMessage({ __fp: 'enter' }, '*'); return; }
    if (/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) && !/^(checkbox|radio|submit|button|reset|image)$/i.test(el.type)) {
      el.focus();
      return;
    }
    const a = el.closest('a[href]');
    if (a && window.AnantPlay) AnantPlay.clicked(a.href);
    if (!el.matches('a,button,input,summary,label')) {
      const r = el.getBoundingClientRect(), at = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
      for (const t of ['pointerdown', 'mousedown', 'pointerup', 'mouseup'])
        el.dispatchEvent(t[0] === 'p' ? new PointerEvent(t, at) : new MouseEvent(t, at));
    }
    el.click();
  }

  // Site pop-ups grab the highlight first, on their Close (or Accept) button. Top frame only, so ad frames can't steal focus.
  const seen = new WeakSet();
  let before = null, scanTimer = 0, lastScan = 0;

  function covered(el) {
    const r = el.getBoundingClientRect();
    if (!r.width || !document.contains(el)) return true;
    const x = Math.min(Math.max(r.left + r.width / 2, 1), innerWidth - 2), y = Math.min(Math.max(r.top + r.height / 2, 1), innerHeight - 2);
    const hit = document.elementFromPoint(x, y);
    return !!hit && hit !== el && !el.contains(hit) && !hit.contains(el);
  }

  // Clickable things inside a pop-up that the user can actually see and reach
  function reachable(root) {
    const out = [];
    for (const el of root.querySelectorAll('*')) {
      const r = el.getBoundingClientRect();
      if (inView(r) && shown(el, r) && clickable(el) && !covered(el)) out.push(el);
    }
    return out;
  }

  // The button that gets rid of a pop-up: the ✕ nearest its top-right corner, else "Not now"-style closers, else Accept
  function dismisser(root, items) {
    const rr = root.getBoundingClientRect();
    const corner = el => { const r = el.getBoundingClientRect(); return (rr.right - r.right) + (r.top - rr.top); };
    const closes = items.filter(el => role(el) === 'close').sort((a, b) => corner(a) - corner(b));
    return closes[0] || items.find(el => role(el) === 'accept') || null;
  }

  function popups() {
    const found = new Map(), w = innerWidth, h = innerHeight, v = bigVideo();
    for (const root of Roles.dialogs()) {
      if (root.querySelector('video') || (v && root.contains(v))) continue;      // a player's own layer is not a pop-up
      const items = reachable(root);
      if (!items.length) continue;
      const rr = root.getBoundingClientRect();
      const explicit = root.matches('[role=dialog],[role=alertdialog],[aria-modal=true]');
      const middle = rr.left < w / 2 && rr.right > w / 2 && rr.top < h / 2 && rr.bottom > h / 2;
      // Real pop-ups say so, have a way out, or sit in the middle of the screen; floating menus and docks don't
      if (explicit || middle || dismisser(root, items)) found.set(root, items);
    }
    return found;
  }

  // Back closes an open pop-up before it leaves the page (the app asks this first)
  window.__fpBack = () => {
    for (const [root, items] of popups()) {
      const t = dismisser(root, items);
      if (t) { t.click(); return true; }
    }
    return false;
  };

  // Focus the top pop-up when a new one appears, or when the highlighted thing got covered or removed
  function scanPopups() {
    scanTimer = 0;
    if (pointerMode()) return;
    lastScan = Date.now();
    const roots = popups();
    const lost = cur && (inView(cur.getBoundingClientRect()) ? covered(cur) : !document.contains(cur));
    const choices = [...roots].filter(([root]) => lost || (!seen.has(root) && !(cur && root.contains(cur))));
    if (!choices.length) {
      // Pages render in stages: until the user presses something, keep moving to Play/Watch once it shows up
      if (!cur || lost || (!touched && autoPicked && role(cur) !== 'play')) settle();
      return;
    }
    const [root, els] = choices.pop();
    for (const [r] of roots) seen.add(r);
    const pick = dismisser(root, els) || els.find(el => role(el) === 'play') ||
      els.sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top)[0];
    if (touched && cur && !lost && !root.contains(cur)) before = cur;
    highlight(pick);
    autoPicked = false;
  }

  if (window.top === window) {
    // Pop-ups often slide in with CSS animations that cause no DOM changes, so also look again
    // a moment after the page goes quiet
    let settleTimer = 0;
    const queue = () => {
      if (!scanTimer) scanTimer = setTimeout(scanPopups, Math.max(300, 1000 - (Date.now() - lastScan)));
      clearTimeout(settleTimer);
      settleTimer = setTimeout(scanPopups, 1500);
    };
    const watch = () => new MutationObserver(queue).observe(document.body,
      { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'open', 'hidden', 'aria-hidden'] });
    document.body ? watch() : addEventListener('DOMContentLoaded', watch);
    addEventListener('load', queue);
  }

  // Always show a highlight, like a TV app: where we were before a pop-up, else Play, else the first poster, else the first thing
  let touched = false, autoPicked = false;
  function settle() {
    if (pointerMode()) return;
    if (cur) cur.classList.remove('__fp');
    if (before && document.contains(before) && before.getBoundingClientRect().width) {
      const b = before;
      before = null;
      return highlight(b);
    }
    cur = null;
    if (bigVideo()) return;
    const all = [];
    for (const el of document.querySelectorAll('body *')) {
      const r = el.getBoundingClientRect();
      if (inView(r) && shown(el, r) && clickable(el) && !covered(el)) all.push(el);
    }
    const reading = (a, b) => { const x = a.getBoundingClientRect(), y = b.getBoundingClientRect(); return Math.abs(x.top - y.top) > 40 ? x.top - y.top : x.left - y.left; };
    const calm = el => !['dot', 'tab', 'menu', 'search', 'close', 'back'].includes(role(el));
    const pick = all.find(el => role(el) === 'play') || all.find(el => role(el) === 'enter') || all.filter(el => role(el) === 'tile').sort(reading)[0] || all.find(calm) || all[0];
    if (pick) highlight(pick);
    autoPicked = true;
  }

  // Player pages start on the control in the middle of the video (usually the big Play button)
  function bigVideo() {
    return [...document.querySelectorAll('video')].find(v => {
      const r = v.getBoundingClientRect();
      return r.width * r.height > innerWidth * innerHeight * 0.3;
    });
  }

  function nearest(v, els) {
    const r = v.getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    const d = el => { const b = el.getBoundingClientRect(); return Math.hypot(b.left + b.width / 2 - cx, b.top + b.height / 2 - cy); };
    return els.slice().sort((a, b) => d(a) - d(b))[0];
  }

  // True if the highlight had to move (so this key press only shows it, never clicks it)
  function recover() {
    if (cur && document.contains(cur) && shown(cur, cur.getBoundingClientRect())) return false;
    settle();
    return true;
  }

  // ---- Clean video mode ----
  // With anything painted over a 4K video the TV can't take its efficient video path and the picture stutters (measured on a
  // TCL MT5896: every frame dropped with the site's controls over it, none with the video alone). So while a big video plays
  // and nobody has pressed a key for a few seconds, hide whatever overlaps it (subtitles stay); any input brings it all back.
  const clean = (() => {
    let hidden = [], last = Date.now(), timer = 0;
    const on = () => { try { return !!(window.AnantPlay && AnantPlay.cleanVideo && AnantPlay.cleanVideo()); } catch (e) { return false; } };
    const SUBTITLE = /subtitle|caption|\bcue|jassub|octopus|ass-?canvas|lyric/i;
    // A stalled video still counts as playing: stalls are what the overlays cause, so un-hiding on a stall would keep it stuck
    const playing = () => { const v = bigVideo(); return v && !v.paused && !v.ended ? v : null; };

    function hide(v) {
      const vr = v.getBoundingClientRect(), keep = e => e === v || e.contains(v) || v.contains(e);
      for (const e of document.querySelectorAll('body *')) {
        if (keep(e)) continue;
        const r = e.getBoundingClientRect();
        if (r.width < 1 || r.height < 1 || r.right <= vr.left || r.left >= vr.right || r.bottom <= vr.top || r.top >= vr.bottom) continue;
        const s = getComputedStyle(e);
        if (s.visibility === 'hidden' || s.display === 'none') continue;
        if (SUBTITLE.test((e.className && e.className.baseVal === undefined ? e.className : '') + ' ' + e.id) || e.closest('[class*=subtitle i],[class*=caption i],[id*=subtitle i]')) continue;
        hidden.push([e, e.style.getPropertyValue('visibility'), e.style.getPropertyPriority('visibility')]);
        e.style.setProperty('visibility', 'hidden', 'important');
      }
    }

    function reveal() {
      for (const [e, v, p] of hidden) { if (v) e.style.setProperty('visibility', v, p); else e.style.removeProperty('visibility'); }
      hidden = [];
    }

    const touch = ev => { if (ev && ev.isTrusted === false) return; last = Date.now(); if (hidden.length) reveal(); };
    for (const t of ['keydown', 'mousemove', 'pointerdown', 'touchstart', 'wheel']) addEventListener(t, touch, true);

    timer = setInterval(() => {
      const v = playing();
      if (v) try { window.AnantPlay && AnantPlay.videoPlaying && AnantPlay.videoPlaying(); } catch (e) { }   // the app hides its cursor while one plays
      if (!on() || !v) { if (hidden.length) reveal(); return; }
      if (!hidden.length && Date.now() - last > 4000 && !popups().size) hide(v);
    }, 1000);
    return { reveal };
  })();

  addEventListener('keydown', e => {
    if (KEYS[e.key]) touched = true;
    const big = bigVideo();
    if (big && cur && (KEYS[e.key] || e.key === 'Enter')) wake(big);
    const moved = recover();
    // Player mode (nothing highlighted on a player page): OK play/pause, left/right seek,
    // up/down wake the controls and step onto the nearest one in that direction
    const v = !cur && bigVideo();
    if (v && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
      e.preventDefault(); e.stopImmediatePropagation();
      media(e.key);
      const r = v.getBoundingClientRect(), mid = { left: r.left + r.width / 2, right: r.left + r.width / 2, top: r.top + r.height / 2, bottom: r.top + r.height / 2 };
      setTimeout(() => { const n = find(KEYS[e.key], mid); if (n) highlight(n); }, 250);
      return;
    }
    if (v && media(e.key)) {
      e.preventDefault(); e.stopImmediatePropagation();
      return;
    }
    if (moved && (e.key === 'Enter' || KEYS[e.key])) {
      e.preventDefault(); e.stopImmediatePropagation();
      return;
    }
    const fs = document.fullscreenElement;
    if (fs && fs.tagName === 'IFRAME') {
      fs.contentWindow.postMessage({ __fp: 'key', key: e.key }, '*');
      e.preventDefault(); e.stopImmediatePropagation();
      return;
    }
    if (fs || e.key.startsWith('Media')) {
      if (media(e.key)) { e.preventDefault(); e.stopImmediatePropagation(); }
      return;
    }
    const a = document.activeElement;
    const typing = a && (a.isContentEditable || a.tagName === 'TEXTAREA' || (a.tagName === 'INPUT' && TYPING.test(a.type)));
    const dir = KEYS[e.key];
    if (dir) {
      if (typing && (dir === 'left' || dir === 'right')) return;
      e.preventDefault(); e.stopImmediatePropagation();
      if (typing) a.blur();
      move(dir);
    } else if (e.key === 'Enter' && cur && !typing && document.contains(cur)) {
      e.preventDefault(); e.stopImmediatePropagation();
      activate(cur);
    }
  }, true);

  addEventListener('message', e => {
    const d = e.data;
    if (!d || typeof d !== 'object' || !d.__fp) return;
    if (d.__fp === 'enter') {
      window.focus();
      const el = largest();
      if (el) highlight(el);
    } else if (d.__fp === 'key') {
      media(d.key);
    } else if (d.__fp === 'exit') {
      const frame = [...document.querySelectorAll('iframe')].find(f => f.contentWindow === e.source);
      if (frame) { cur = frame; frame.blur(); move(d.dir); }
    }
  });
})();
