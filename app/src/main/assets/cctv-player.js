(function () {
  'use strict';
  // Shared by WebView and the Gecko content script. This only lays out the
  // original official player and clicks its existing controls; it never reads
  // media addresses or replaces any authentication / protection logic.
  window.__clearCctvFactory = function (options) {
    if (!options || !options.enabled || location.protocol !== 'https:' || location.hostname !== 'tv.cctv.com' || !/^\/live\/(?:cctv(?:[1-8]|10|11|12|13|15|16|17|5plus|jilu|child|europe|america))\/(?:index\.shtml)?$/.test(location.pathname)) return null;
    if (window.__clearCctv) { window.__clearCctv.foreground(!!options.foreground); return window.__clearCctv; }
    var active = !!options.foreground, attempted = false, userPaused = false, pending = null, player = null, attachedAt = Date.now(), lastClick = 0;
    function video() { return player && (player.querySelector('#h5player_player') || player.querySelector('video')); }
    function pause() { pending = null; if (player) player.querySelectorAll('video,audio').forEach(function (v) { v.pause(); }); }
    function style(node, key, value) { if (node.style.getPropertyValue(key) !== value || node.style.getPropertyPriority(key) !== 'important') node.style.setProperty(key, value, 'important'); }
    function layout() {
      var found = document.getElementById('player');
      if (!found) return;
      player = found;
      style(document.documentElement, 'overflow', 'hidden');
      style(document.body, 'overflow', 'hidden');
      style(document.body, 'margin', '0');
      style(document.body, 'background', '#000');
      // Keep every node in its original place. Remove ancestor stacking and
      // clipping while hiding siblings, including late inserted QR/ad blocks.
      var child = player;
      for (var ancestor = child.parentElement; ancestor; child = ancestor, ancestor = ancestor.parentElement) {
        for (var i = 0; i < ancestor.children.length; i++) {
          var sibling = ancestor.children[i];
          if (sibling === child || sibling.tagName === 'SCRIPT' || sibling.tagName === 'STYLE' || sibling.tagName === 'LINK') continue;
          style(sibling, 'visibility', 'hidden'); style(sibling, 'pointer-events', 'none');
          sibling.querySelectorAll('a,button,input,select,textarea,[tabindex]').forEach(function (e) { e.tabIndex = -1; });
        }
        style(ancestor, 'transform', 'none'); style(ancestor, 'filter', 'none'); style(ancestor, 'perspective', 'none');
        style(ancestor, 'contain', 'none'); style(ancestor, 'overflow', 'visible'); style(ancestor, 'position', 'static');
        style(ancestor, 'opacity', '1'); style(ancestor, 'clip-path', 'none');
      }
      style(document.documentElement, 'overflow', 'hidden'); style(document.body, 'overflow', 'hidden');
      style(player, 'position', 'fixed'); style(player, 'left', '0'); style(player, 'top', '0'); style(player, 'margin', '0');
      style(player, 'width', '100vw'); style(player, 'height', '100vh'); style(player, 'max-width', 'none'); style(player, 'max-height', 'none');
      style(player, 'visibility', 'visible'); style(player, 'z-index', '2147483646'); style(player, 'background', '#000');
      var v = video();
      if (v) { style(v, 'width', '100%'); style(v, 'height', '100%'); style(v, 'object-fit', 'contain'); }
      if (document.activeElement && document.activeElement !== document.body && !player.contains(document.activeElement)) document.activeElement.blur();
      window.scrollTo(0, 0);
    }
    function clickControl() {
      if (!active || document.hidden || !player || pending || Date.now() - lastClick < 500) return;
      // Actual CCTV control ID, including the website's spelling of plause.
      var control = player.querySelector('#play_or_plause_player') || player.querySelector('.vjs-big-play-button');
      var target = control || player, r = target.getBoundingClientRect();
      // CCTV hides its control bar during playback. The original player also
      // handles a trusted centre click, so a hidden button must not eat OK.
      if (control && (r.width <= 0 || r.height <= 0 || getComputedStyle(control).visibility === 'hidden' || getComputedStyle(control).display === 'none')) { target=player; r=player.getBoundingClientRect(); }
      var x = (r.left + r.width / 2) / innerWidth, y = (r.top + r.height / 2) / innerHeight;
      if (r.width > 0 && r.height > 0 && x > 0 && x < 1 && y > 0 && y < 1) {
        // The native host supplies a real foreground touch. No click listener
        // consumes the official player's event and no play() is substituted.
        pending = {x:x,y:y}; lastClick = Date.now();
      }
    }
    function request(operation) {
      if (!active || document.hidden) return;
      layout(); var v = video(); attempted = true;
      if (operation === 'pause') { userPaused = true; pause(); return; }
      if (operation === 'play' && v && !v.paused && !v.ended) return;
      userPaused = !!(v && !v.paused && !v.ended && operation !== 'play');
      clickControl();
    }
    function sample() {
      layout(); var v = video();
      if ((!active || document.hidden) && v && !v.paused) pause();
      if (active && !document.hidden && !attempted && !userPaused && Date.now() - attachedAt > 900 && v) {
        if (!v.paused && !v.ended) attempted = true;
        else if (v.readyState > 0 || player.querySelector('#play_or_plause_player,.vjs-big-play-button')) { clickControl(); attempted=!!pending; }
      }
      var out = {video:!!v, officialCctv:true, cctvStatus:userPaused?'paused':'loading'};
      if (v) {
        var r = v.getBoundingClientRect();
        out.time=v.currentTime; out.width=v.videoWidth; out.height=v.videoHeight; out.ready=v.readyState; out.network=v.networkState;
        out.paused=v.paused; out.controls=v.controls; out.error=v.error?v.error.code:0; out.fullscreen=!!document.fullscreenElement;
        out.cx=(r.left+r.width/2)/innerWidth; out.cy=(r.top+r.height/2)/innerHeight;
        if (v.error) out.cctvStatus='error'; else if (!v.paused && v.readyState>=2) out.cctvStatus='playing';
      }
      if (pending) { if (active && !document.hidden) out.cctvClick=pending; pending=null; }
      return out;
    }
    document.addEventListener('play', function (event) { if ((!active || document.hidden) && event.target && /^(VIDEO|AUDIO)$/.test(event.target.tagName)) event.target.pause(); }, true);
    document.addEventListener('click', function (event) {
      var target = event.target && event.target.closest && event.target.closest('a[href]');
      if (target && (!player || !player.contains(target) || target.getAttribute('href').charAt(0) !== '#')) { event.preventDefault(); event.stopImmediatePropagation(); }
    }, true);
    document.addEventListener('focusin', function (event) { if (player && event.target !== document.body && !player.contains(event.target) && event.target.blur) event.target.blur(); }, true);
    document.addEventListener('visibilitychange', function () { if (document.hidden) pause(); });
    window.addEventListener('pagehide', function () { active=false; pause(); }, {once:true});
    window.__clearCctv = {sample:sample, request:request, foreground:function (value) { active=!!value; if (!active) { userPaused=true; pause(); } }, pause:pause};
    layout(); return window.__clearCctv;
  };
})();
