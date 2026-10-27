(function () {
  'use strict';
  if (window.__clearTV) { window.__clearTV.enable(true); return; }
  var enabled = true, current = null, timer = 0, savedTab = [], grids = [], posters = [], style;
  var selector = 'a[href],button,input:not([type="hidden"]),select,textarea,[role="button"],[role="link"],[role="tab"],[tabindex],video[controls],[onclick][data-url],.playlists header dt,.popup-btn.close-pop,.module-tab-item.tab-item';
  function visible(el) {
    var r = el.getBoundingClientRect(), s = getComputedStyle(el);
    return r.width > 2 && r.height > 2 && s.display !== 'none' && s.visibility !== 'hidden' && !el.disabled && !el.closest('[inert],[aria-hidden="true"]');
  }
  function topScope() {
    var modals = document.querySelectorAll('dialog[open],[role="dialog"],[role="alertdialog"],[aria-modal="true"]'), scope = document, topZ = -Infinity;
    for (var i = 0; i < modals.length; i++) if (visible(modals[i])) {
      var z = parseInt(getComputedStyle(modals[i]).zIndex, 10) || 0;
      if (z >= topZ) { scope = modals[i]; topZ = z; }
    }
    return scope;
  }
  function hittable(el) {
    var r = el.getBoundingClientRect();
    if (r.bottom <= 0 || r.top >= innerHeight || r.right <= 0 || r.left >= innerWidth) return true;
    var x = Math.max(1, Math.min(innerWidth - 1, r.left + r.width / 2)), y = Math.max(1, Math.min(innerHeight - 1, r.top + r.height / 2));
    var hit = document.elementFromPoint(x, y); return hit === el || (hit && el.contains(hit));
  }
  function safeGrid(parent) {
    if (parent.closest('[aria-roledescription="carousel"],[role="listbox"]')) return false;
    var p = getComputedStyle(parent);
    if (p.transform !== 'none' || /^(absolute|fixed)$/.test(p.position) || /^(auto|scroll|hidden)$/.test(p.overflowX) || (p.scrollSnapType && p.scrollSnapType !== 'none')) return false;
    for (var i = 0; i < parent.children.length; i++) {
      var s = getComputedStyle(parent.children[i]);
      if (s.transform !== 'none' || /^(absolute|fixed)$/.test(s.position)) return false;
    }
    return true;
  }
  function protectedArea(el) { return !!el.closest('form,video,audio,[role="dialog"],[contenteditable="true"]'); }
  function editable(el) { return !!el && (/^(INPUT|TEXTAREA|SELECT|VIDEO)$/.test(el.tagName) || el.isContentEditable); }
  function targets() {
    var scope = topScope(), all = scope.querySelectorAll(selector), list = [];
    for (var i = 0; i < all.length && i < 5000 && list.length < 1200; i++) if (visible(all[i]) && hittable(all[i])) list.push(all[i]);
    return list;
  }
  function focus(el) {
    if (!el) return false;
    if (current) current.removeAttribute('data-cs-focus');
    current = el; el.setAttribute('data-cs-focus', '1');
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    var r = el.getBoundingClientRect();
    if (r.top < 20 || r.bottom > innerHeight - 35 || r.left < 0 || r.right > innerWidth) {
      try { el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'auto' }); }
      catch (e) { el.scrollIntoView(false); }
    }
    return true;
  }
  function scan() {
    if (!enabled) return;
    savedTab = savedTab.filter(function (item) { return document.documentElement.contains(item[0]); });
    grids = grids.filter(function (el) { return document.documentElement.contains(el); });
    posters = posters.filter(function (el) { return document.documentElement.contains(el); });
    var list = targets();
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!el.hasAttribute('data-cs-target')) {
        savedTab.push([el, el.getAttribute('tabindex')]);
        el.setAttribute('data-cs-target', '1');
        if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '0');
      }
      if (!protectedArea(el)) {
        var r = el.getBoundingClientRect();
        if (r.height < 42 && r.width < 240) el.setAttribute('data-cs-compact', '1');
        // Only reshape a clear, repeated poster list. Never rearrange a form,
        // embedded player, page navigation or the whole document.
        if (el.tagName === 'A' && el.querySelector('img')) {
          var item = el.parentElement && el.parentElement.tagName === 'LI' ? el.parentElement : el;
          var parent = item.parentElement;
          if (parent && parent.tagName !== 'BODY' && parent.tagName !== 'HTML' && !parent.hasAttribute('data-cs-grid')
              && !parent.closest('header,nav,footer,form,[role="dialog"]') && !parent.querySelector('video,audio,input,iframe,canvas')
              && parent.children.length >= 3 && parent.children.length <= 60 && safeGrid(parent)) {
            var posterCount = 0;
            for (var n = 0; n < parent.children.length; n++) if (parent.children[n].querySelector('a img') || (parent.children[n].tagName === 'A' && parent.children[n].querySelector('img'))) posterCount++;
            if (posterCount >= 3 && posterCount / parent.children.length >= .75) {
              parent.setAttribute('data-cs-grid', '1'); grids.push(parent);
            }
          }
          if (parent && parent.hasAttribute('data-cs-grid') && !el.hasAttribute('data-cs-poster')) {el.setAttribute('data-cs-poster', '1'); posters.push(el);}
        }
      }
    }
    if (!current || list.indexOf(current) < 0) {
      if (current) current.removeAttribute('data-cs-focus'); current = null;
      // Prefer what is actually on screen; do not jump to an offscreen menu or footer.
      for (var j = 0; j < list.length; j++) {
        var rect = list[j].getBoundingClientRect();
        if (rect.bottom > 0 && rect.top < innerHeight && rect.right > 0 && rect.left < innerWidth) { focus(list[j]); break; }
      }
    }
  }
  style = document.createElement('style'); style.id = 'clearstream-tv-style';
  style.textContent =
    'html.cs-tv{scroll-padding:24px!important}html.cs-tv body{font-size:18px;line-height:1.5}' +
    'html.cs-tv [data-cs-compact="1"]{min-height:42px!important;min-width:48px!important;font-size:18px!important;box-sizing:border-box!important}' +
    'html.cs-tv a[data-cs-compact="1"]{display:inline-flex!important;align-items:center!important;padding:6px 10px!important}' +
    'html.cs-tv [data-cs-grid="1"]{display:grid!important;grid-template-columns:repeat(auto-fill,minmax(160px,1fr))!important;gap:20px!important;width:auto!important;max-width:100%!important;overflow:visible!important;list-style:none!important}' +
    'html.cs-tv [data-cs-grid="1"]> *{width:auto!important;max-width:100%!important;min-width:0!important;margin:0!important;float:none!important}' +
    'html.cs-tv a[data-cs-poster="1"]{display:flex!important;flex-direction:column!important;gap:8px!important;font-size:18px!important}' +
    'html.cs-tv [data-cs-focus="1"]{outline:4px solid #4c9aff!important;outline-offset:3px!important;border-radius:6px!important;box-shadow:0 0 0 6px rgba(16,20,24,.65)!important;scroll-margin:32px!important}' +
    'html.cs-tv img{max-width:100%}';
  (document.head || document.documentElement).appendChild(style);
  var observer = new MutationObserver(function () { if (!timer) timer = setTimeout(function () { timer = 0; scan(); }, 180); });
  observer.observe(document.documentElement, { childList: true, subtree: true });
  document.addEventListener('focusin', function (event) {
    if (!enabled || !event.target.matches || !event.target.matches(selector)) return;
    if (current && current !== event.target) current.removeAttribute('data-cs-focus');
    current = event.target; current.setAttribute('data-cs-focus', '1');
  }, true);
  function enable(value) {
    enabled = !!value; if(enabled&&window.ClearStreamTVPlugins)window.ClearStreamTVPlugins.enable(true);document.documentElement.classList.toggle('cs-tv', enabled);
    if (enabled) { scan(); return; }
    if (current) current.removeAttribute('data-cs-focus'); current = null;
    for (var i = 0; i < savedTab.length; i++) {
      var el = savedTab[i][0]; if (savedTab[i][1] === null) el.removeAttribute('tabindex'); else el.setAttribute('tabindex', savedTab[i][1]);
      el.removeAttribute('data-cs-target'); el.removeAttribute('data-cs-compact'); el.removeAttribute('data-cs-focus');
    }
    savedTab = [];
    for (var g = 0; g < grids.length; g++) grids[g].removeAttribute('data-cs-grid');
    for (var p = 0; p < posters.length; p++) posters[p].removeAttribute('data-cs-poster');
    grids = []; posters = [];if(window.ClearStreamTVPlugins)window.ClearStreamTVPlugins.enable(false);
  }
  function move(key) {
    if (!enabled) return 'native';
    scan();
    if (!current) { window.scrollBy(0, key === 19 ? -innerHeight * .6 : innerHeight * .6); return key === 19 && window.scrollY <= 0 ? 'toolbar' : 'scroll'; }
    if (editable(document.activeElement)) {
      var active=document.activeElement,textField=active.tagName==='TEXTAREA'||active.tagName==='INPUT'&&/^(text|search|url|email|tel|password)$/.test(active.type||'text');
      if(!textField||(key!==19&&key!==20))return 'native';
      if(current&&current!==active)current.removeAttribute('data-cs-focus');
      current=active;current.setAttribute('data-cs-focus','1');active.blur();
    }
    if (key === 23 || key === 66) {
      if (!hittable(current)) { scan(); return 'focus'; }
      var clickRect = current.getBoundingClientRect();
      // Ask Android for a genuine WebView touch gesture, preserving media gesture requirements.
      return { action: 'click', x: Math.max(0, Math.min(1, (clickRect.left + clickRect.width / 2) / innerWidth)), y: Math.max(0, Math.min(1, (clickRect.top + clickRect.height / 2) / innerHeight)) };
    }
    var origin = current.getBoundingClientRect(), ox = origin.left + origin.width / 2, oy = origin.top + origin.height / 2;
    var list = targets(), best = null, bestScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      if (list[i] === current) continue;
      var r = list[i].getBoundingClientRect(), dx = r.left + r.width / 2 - ox, dy = r.top + r.height / 2 - oy;
      var primary = key === 19 ? -dy : key === 20 ? dy : key === 21 ? -dx : dx;
      var secondary = key === 19 || key === 20 ? Math.abs(dx) : Math.abs(dy);
      if (primary < 4) continue;
      var overlap = key === 19 || key === 20 ? r.left < origin.right && r.right > origin.left : r.top < origin.bottom && r.bottom > origin.top;
      var score = primary + secondary * (overlap ? .25 : 3) + (overlap ? 0 : 180);
      if (score < bestScore) { bestScore = score; best = list[i]; }
    }
    if (best) { focus(best); return 'focus'; }
    if (key === 19 && window.scrollY <= 0) return 'toolbar';
    if (key === 19 || key === 20) { window.scrollBy(0, key === 19 ? -innerHeight * .65 : innerHeight * .65); return 'scroll'; }
    return 'edge';
  }
  function snapshot(){
    var origin=current&&current.getBoundingClientRect(),cx=origin?origin.left+origin.width/2:innerWidth/2,cy=origin?origin.top+origin.height/2:0;
    function label(el){return String(el.getAttribute('aria-label')||(/^(INPUT|TEXTAREA)$/.test(el.tagName)?el.getAttribute('placeholder')||'网页输入框':el.innerText||el.textContent)||'').replace(/\s+/g,' ').trim().slice(0,100);}
    var list=targets().map(function(el){var r=el.getBoundingClientRect(),url;try{url=new URL(el.getAttribute('href')||'',location.href);}catch(_){}return {label:label(el),x:(r.left+r.width/2)/innerWidth,y:(r.top+r.height/2)/innerHeight,play:!!url&&el.hasAttribute('href')&&url.origin===location.origin&&/\/(?:vodplay|play|watch|episode)(?:\/|[-_])/.test(url.pathname),distance:Math.abs(r.top+r.height/2-cy)+Math.abs(r.left+r.width/2-cx)*.25};}).filter(function(item){return !!item.label;});
    list.sort(function(a,b){return a.distance-b.distance;});return {focus:current?label(current):'',x:cx/innerWidth,y:cy/innerHeight,targets:list.slice(0,64)};
  }
  window.__clearTV = { enable: enable, move: move, rescan: scan, snapshot:snapshot, version: 1 };
  enable(true);
})();
