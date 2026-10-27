(function () {
  'use strict';
  var port = browser.runtime.connectNative('clearstream'),extensionVersion=browser.runtime.getManifest().version;
  var gestureTimer=0,gestureClick=null,restorePending=null,fullscreenStatus='idle',playbackStatus='idle',playAttempt=0;
  var cctv=null,adNetworkBlocked=0;
  function post(message) { try { port.postMessage(message); } catch (_) {} }
  function toggleFitted(){var fit=window.__clearPlayerLayout;if(!fit||!fit.snapshot().active)return false;return fit.requestToggle(function(){var bounds=fit.snapshot();post({kind:'action',action:'click',x:(bounds.x+bounds.width/2)/innerWidth,y:(bounds.y+bounds.height/2)/innerHeight});});}
  var lastUserGesture=0;
  ['click','pointerdown','keydown'].forEach(function(kind){document.addEventListener(kind,function(e){if(e.isTrusted)lastUserGesture=Date.now();},true);});
  document.addEventListener('pause',function(e){if(e.target&&e.target.tagName==='VIDEO'&&Date.now()-lastUserGesture<1000){playAttempt++;post({kind:'userPaused'});}},true);
  function playerVideo(){
    var all=document.querySelectorAll('video'),best=null,area=0;
    for(var i=0;i<Math.min(all.length,32);i++){
      var v=all[i],r=v.getBoundingClientRect(),s=getComputedStyle(v),ratio=v.videoWidth>0&&v.videoHeight>0?v.videoWidth/v.videoHeight:r.width/r.height;
      if(v.closest('.ad,.ads,.advertisement,[data-ad],[data-ad-slot],[hidden],[aria-hidden="true"]')||r.width<160||r.height<90||s.display==='none'||s.visibility==='hidden'||ratio>3.2||ratio<.35)continue;
      if(r.width*r.height>area){best=v;area=r.width*r.height;}
    }
    return best;
  }
  function cancelGesture(){if(gestureClick)document.removeEventListener('click',gestureClick,true);gestureClick=null;clearTimeout(gestureTimer);if(restorePending)restorePending();restorePending=null;}
  function videoGesture(video,operation){
    if(gestureClick)return; // One remote confirmation at a time.
    var originalControls=video.controls;
    // Gecko's hidden UA controls can consume the first click. Receive one
    // genuine page click instead; media APIs still require that trusted event.
    video.controls=false;restorePending=function(){video.controls=originalControls;};
    gestureClick=function(event){
      if(!event.isTrusted)return;
      event.preventDefault();event.stopImmediatePropagation();
      document.removeEventListener('click',gestureClick,true);gestureClick=null;clearTimeout(gestureTimer);restorePending=null;
      if(operation==='fullscreen'){
        fullscreenStatus='trusted-click';video.controls=true;
        function restore(){if(document.fullscreenElement===video)return;video.controls=originalControls;document.removeEventListener('fullscreenchange',restore);}
        document.addEventListener('fullscreenchange',restore);
        video.requestFullscreen().catch(function(){fullscreenStatus='request-rejected';restore();post({kind:'fullscreenFailed'});});
      }else{
        video.controls=originalControls;
        if(video.paused||video.ended){var attempt=++playAttempt;playbackStatus='trusted-play';video.play().catch(function(error){if(attempt!==playAttempt||error&&error.name==='AbortError'){playbackStatus='play-cancelled';return;}playbackStatus='play-rejected';post({kind:'playbackFailed'});});}
        else {playAttempt++;playbackStatus='trusted-pause';post({kind:'userPaused'});video.pause();}
      }
    };
    document.addEventListener('click',gestureClick,true);
    gestureTimer=setTimeout(function(){cancelGesture();if(operation==='fullscreen'){fullscreenStatus='click-timeout';post({kind:'fullscreenFailed'});}else{playbackStatus='click-timeout';post({kind:'playbackFailed'});}},2500);
    video.scrollIntoView({block:'center',behavior:'instant'});
    requestAnimationFrame(function(){if(!gestureClick)return;var r=video.getBoundingClientRect(),x=(r.left+r.width/2)/innerWidth,y=(r.top+r.height/2)/innerHeight;
      if(r.width>0&&r.height>0&&x>=0&&x<=1&&y>=0&&y<=1)post({kind:'action',action:'click',x:x,y:y});
      else{cancelGesture();post({kind:'noVideo'});}
    });
  }
  function videoState() {
    if(cctv){var official=cctv.sample();official.kind='state';official.extensionVersion=extensionVersion;return official;}
    var navigation=window.__clearTV&&window.__clearTV.snapshot?window.__clearTV.snapshot():{};
    var layout=window.__clearPlayerLayout?window.__clearPlayerLayout.snapshot():{active:false};
    var fit={adBlocked:adNetworkBlocked,adHidden:window.__clearAds?window.__clearAds.hidden():0,layoutFullscreen:layout.active,layoutWidth:layout.width||0,layoutHeight:layout.height||0,viewportWidth:innerWidth,viewportHeight:innerHeight,layoutInnerWidth:layout.innerWidth||0,layoutInnerHeight:layout.innerHeight||0,layoutMediaWidth:layout.mediaWidth||0,layoutMediaHeight:layout.mediaHeight||0,layoutMediaTime:layout.mediaTime||0};
    var v = playerVideo();
    if (!v) return Object.assign(fit, {kind:'state', video:false, extensionVersion:extensionVersion, frames:document.querySelectorAll('iframe').length,navigation:navigation});
    var r = v.getBoundingClientRect();
    return Object.assign(fit,{kind:'state', video:true, extensionVersion:extensionVersion,navigation:navigation,time:v.currentTime, width:v.videoWidth, height:v.videoHeight,
      ready:v.readyState, network:v.networkState, paused:v.paused, controls:v.controls, fullscreen:document.fullscreenElement===v, fullscreenStatus:fullscreenStatus, playbackStatus:playbackStatus, error:v.error?v.error.code:0,
      cx:(r.left+r.width/2)/innerWidth, cy:(r.top+r.height/2)/innerHeight,
      px:(r.left+24)/innerWidth, py:(r.bottom-24)/innerHeight});
  }
  port.onMessage.addListener(function (m) {
    if(m.command==='beginAdPick'){if(window.__clearAds)window.__clearAds.startPicker();return;}
    if(m.command==='cancelAdPick'){if(window.__clearAds)window.__clearAds.cancelPicker();return;}
    if(m.command==='pointAd'){if(window.__clearAds)window.__clearAds.point(m.x,m.y);return;}
    if(m.command==='pickAdAt'){var result=window.__clearAds?window.__clearAds.pickAt(m.x,m.y):{ok:false};post({kind:'adPicked',ok:result.ok,selector:result.selector||'',label:result.label||''});return;}

    if (m.command === 'pause') {if(window.ClearStreamTVPlugins)window.ClearStreamTVPlugins.foreground(false);if(window.__clearAds)window.__clearAds.cancelPicker();if(window.__clearPlayerLayout)window.__clearPlayerLayout.pause();cancelGesture();if(cctv)cctv.foreground(false);document.querySelectorAll('video,audio').forEach(function(v){v.pause();});return;}
    if (m.command === 'enable') {var ads=m.adProtection||{network:true,cosmetic:false,plugins:true};if(window.__clearAds)window.__clearAds.configure(ads);browser.runtime.sendMessage({type:'clearstream-ad-config',config:ads}).then(function(result){adNetworkBlocked=result.blocked||0;post({kind:'adConfigSaved'});}).catch(function(){});if(window.ClearStreamTVPlugins)window.ClearStreamTVPlugins.foreground(!!m.foreground&&ads.plugins!==false);if(window.__clearTV)window.__clearTV.enable(!!m.enabled&&!m.officialCctv);if(m.officialCctv&&window.__clearCctvFactory)cctv=window.__clearCctvFactory({enabled:true,foreground:!!m.foreground});return;}
    if (m.command === 'fitPlayer' || m.command === 'exitFitPlayer') {
      var fitted=window.__clearPlayerLayout?(m.command==='fitPlayer'?window.__clearPlayerLayout.enter():window.__clearPlayerLayout.exit()):{active:false};
      post({kind:'layoutFullscreen',active:!!fitted.active});post(videoState());return;
    }
    if (m.command === 'fullscreenVideo') {
      var fullVideo=playerVideo();if(!fullVideo){var fitted=window.__clearPlayerLayout?window.__clearPlayerLayout.enter():{active:false};if(fitted.active){post({kind:'layoutFullscreen',active:true});post(videoState());}else post({kind:'noVideo'});return;}
      fullscreenStatus='requested';videoGesture(fullVideo,'fullscreen');
      return;
    }
    if (m.command === 'toggleVideo') {if(toggleFitted())return;
      if(cctv){cctv.request('toggle');post(videoState());return;}
      var video=playerVideo();
      if(!video){
        var links=document.querySelectorAll('a[href]'),entry=null;
        for(var i=0;i<links.length&&i<2000;i++){
          var a=links[i],address;try{address=new URL(a.href);}catch(_){continue;}
          var r=a.getBoundingClientRect(),label=a.textContent.trim();
          var hit=document.elementFromPoint(Math.max(1,Math.min(innerWidth-1,r.left+r.width/2)),Math.max(1,Math.min(innerHeight-1,r.top+r.height/2)));
          var offscreen=r.bottom<=0||r.top>=innerHeight||r.right<=0||r.left>=innerWidth;
          if(address.origin===location.origin&&r.width>0&&r.height>0&&(offscreen||hit===a||a.contains(hit))&&(/\/(?:vod\/play|vodplay)\//.test(address.pathname)||/\/play\/\d+(?:-\d+-\d+)?\.html$/.test(address.pathname)||/^(立即播放|开始播放|播放正片|正片|国语中字|潮汕话中字|HD中字|第[一二三四五六七八九十0-9]+集)$/.test(label))){entry=a;break;}
        }
        if(entry){entry.scrollIntoView({block:'center'});requestAnimationFrame(function(){var r=entry.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2,hit=document.elementFromPoint(x,y);if(hit===entry||entry.contains(hit))post({kind:'action',action:'click',x:x/innerWidth,y:y/innerHeight});else post({kind:'noVideo'});});}
        else post({kind:'noVideo'});
        return;
      }
      if(video.readyState>0||video.currentSrc)videoGesture(video,'toggle');
      else{video.scrollIntoView({block:'center',behavior:'instant'});requestAnimationFrame(function(){var s=videoState();post({kind:'action',action:'click',x:s.cx,y:s.cy});});}
      return;
    }
    if ([19,20,21,22,23,66].indexOf(m.keyCode)<0)return;
    if((m.keyCode===23||m.keyCode===66)&&toggleFitted())return;
    if(cctv){if(m.keyCode===23||m.keyCode===66)cctv.request('toggle');post(videoState());return;}
    var result=window.__clearTV?window.__clearTV.move(m.keyCode):'native';
    if(result&&typeof result==='object'&&result.action==='click')post({kind:'action',action:'click',x:result.x,y:result.y});
    else if(result==='toolbar'||result==='native')post({kind:'action',action:result,keyCode:m.keyCode});
    post(videoState());
  });
  post(videoState());
  var timer=setInterval(function(){if(!document.hidden){browser.runtime.sendMessage({type:'clearstream-ad-stats'}).then(function(r){adNetworkBlocked=r.blocked||0;}).catch(function(){});post(videoState());}},750);
  window.addEventListener('pagehide',function(){cancelGesture();clearInterval(timer);port.disconnect();},{once:true});
})();
