(function install(w) {
  'use strict';
  if (w.__clearPlayerLayout) return;
  var d=w.document, target=null, kind='', changes=[], remembered=new WeakMap(), child=null, childLoad=null, childState={}, scrollX=0, scrollY=0;
  var eventName='clearstream-player-layout-v1';
  var pendingToggle=null,cancelGesture=null;
  function set(el,key,value){
    var saved=remembered.get(el);if(!saved){saved={};remembered.set(el,saved);}
    if(!Object.prototype.hasOwnProperty.call(saved,key)){saved[key]=true;changes.push([el,key,el.style.getPropertyValue(key),el.style.getPropertyPriority(key)]);}
    el.style.setProperty(key,value,'important');
  }
  function visible(el){var r=el.getBoundingClientRect(),s=w.getComputedStyle(el);return r.width>=120&&r.height>=70&&s.display!=='none'&&s.visibility!=='hidden'&&!el.closest('.ad,.ads,.advertisement,[data-ad],[data-ad-slot],[hidden],[aria-hidden="true"]');}
  function pick(){
    var best=null,score=-1,list=d.querySelectorAll('video,iframe');
    for(var i=0;i<list.length&&i<200;i++){
      var el=list[i];if(!visible(el))continue;var r=el.getBoundingClientRect(),value=r.width*r.height;
      if(el.tagName==='VIDEO')value+=el.readyState>=2&&!el.paused?2e9:1e9;
      if(value>score){best=el;score=value;}
    }
    return best;
  }
  function tellChild(action){
    if(!child)return;
    try{install(child.contentWindow);childState=child.contentWindow.__clearPlayerLayout[action]();return;}catch(ignore){}
    try{child.contentWindow.postMessage({type:eventName,action:action},'*');}catch(ignore){}
  }
  function snapshot(){
    if(target&&!d.documentElement.contains(target))exit();
    var r=target?target.getBoundingClientRect():{left:0,top:0,width:0,height:0};
    if(child)tellChild('snapshot');var media=target?(target.tagName==='VIDEO'?target:target.querySelector('video')):null;
    return {active:!!target,kind:kind,x:r.left,y:r.top,width:r.width,height:r.height,viewportWidth:w.innerWidth,viewportHeight:w.innerHeight,
      innerWidth:childState.width||0,innerHeight:childState.height||0,mediaWidth:media?media.videoWidth:childState.mediaWidth||0,mediaHeight:media?media.videoHeight:childState.mediaHeight||0,mediaTime:media?media.currentTime:childState.mediaTime||0};
  }
  function exit(){
    pendingToggle=null;if(cancelGesture)cancelGesture();
    tellChild('exit');if(child&&childLoad)child.removeEventListener('load',childLoad);child=null;childLoad=null;childState={};
    for(var i=changes.length-1;i>=0;i--){var c=changes[i];if(c[2])c[0].style.setProperty(c[1],c[2],c[3]);else c[0].style.removeProperty(c[1]);}
    changes=[];remembered=new WeakMap();target=null;kind='';
    try{w.scrollTo(scrollX,scrollY);w.dispatchEvent(new w.Event('resize'));}catch(ignore){}
    return snapshot();
  }
  function enter(){
    if(target)return snapshot();var media=pick();if(!media)return snapshot();
    scrollX=w.scrollX;scrollY=w.scrollY;kind=media.tagName==='IFRAME'?'iframe':'video';target=media;
    if(kind==='video'){
      var wrapper=media.closest('#player,.player,.video-player,.dplayer,.art-video-player,.jwplayer,.xgplayer,[data-player]');
      if(wrapper&&wrapper!==d.body&&wrapper!==d.documentElement&&wrapper.querySelectorAll('video').length===1)target=wrapper;
    }
    var node=target;
    while(node&&node!==d.documentElement){
      var parent=node.parentElement;if(!parent)break;
      for(var i=0;i<parent.children.length;i++)if(parent.children[i]!==node)set(parent.children[i],'display','none');
      for(var key in {'transform':0,'filter':0,'perspective':0,'contain':0,'clip-path':0})set(parent,key,'none');
      set(parent,'overflow','visible');set(parent,'position','static');set(parent,'z-index','auto');node=parent;
    }
    set(d.documentElement,'overflow','hidden');set(d.documentElement,'background','#000');set(d.body,'overflow','hidden');set(d.body,'margin','0');
    var props={'position':'fixed','inset':'0','left':'0','top':'0','right':'0','bottom':'0','width':'100vw','height':'100vh','min-width':'0','min-height':'0','max-width':'none','max-height':'none','margin':'0','padding':'0','border':'0','box-sizing':'border-box','z-index':'2147483000','background':'#000','display':'block','visibility':'visible','transform':'none','clip-path':'none','overflow':'hidden'};
    Object.keys(props).forEach(function(key){set(target,key,props[key]);});
    if(kind==='video'){
      set(media,'width','100%');set(media,'height','100%');set(media,'max-width','none');set(media,'max-height','none');set(media,'object-fit','contain');
      if(target!==media){set(media,'position','absolute');set(media,'inset','0');set(media,'margin','0');}
    }else{
      child=media;childLoad=function(){tellChild('enter');};child.addEventListener('load',childLoad);tellChild('enter');
    }
    try{w.dispatchEvent(new w.Event('resize'));}catch(ignore){}
    return snapshot();
  }
  function requestToggle(ready){
    if(!target)return false;
    if(child){
      try{install(child.contentWindow);return child.contentWindow.__clearPlayerLayout.requestToggle(ready);}catch(ignore){}
      pendingToggle=ready;child.contentWindow.postMessage({type:eventName,action:'prepare-toggle'},'*');return true;
    }
    var media=target.tagName==='VIDEO'?target:target.querySelector('video');if(!media)return false;
    if(cancelGesture)cancelGesture();var controls=media.controls,timer=0;media.controls=false;
    function cancel(){d.removeEventListener('click',clicked,true);clearTimeout(timer);media.controls=controls;cancelGesture=null;}
    function clicked(event){if(!event.isTrusted||(event.target!==media&&!media.contains(event.target)))return;event.preventDefault();event.stopImmediatePropagation();cancel();if(media.paused){var promise=media.play();if(promise&&promise.catch)promise.catch(function(){});}else media.pause();}
    cancelGesture=cancel;d.addEventListener('click',clicked,true);timer=setTimeout(cancel,2500);if(typeof ready==='function')ready();return true;
  }
  function pause(){pendingToggle=null;if(cancelGesture)cancelGesture();tellChild('pause');d.querySelectorAll('video,audio').forEach(function(v){v.pause();});}
  w.__clearPlayerLayout={enter:enter,exit:exit,snapshot:snapshot,pause:pause,requestToggle:requestToggle,cancelToggle:function(){pendingToggle=null;if(cancelGesture)cancelGesture();},version:1};
  w.addEventListener('message',function(event){
    if(child&&event.source===child.contentWindow&&event.data&&event.data.type===eventName&&event.data.action==='toggle-ready'){var fn=pendingToggle;pendingToggle=null;if(typeof fn==='function')fn();return;}
    if(child&&event.source===child.contentWindow&&event.data&&event.data.type===eventName&&event.data.action==='state'){
      var raw=event.data.state||{},safe={};['width','height','mediaWidth','mediaHeight','mediaTime'].forEach(function(k){var n=Number(raw[k]);safe[k]=isFinite(n)&&n>=0?n:0;});childState=safe;return;
    }
    // Only the actual parent may coordinate this frame; messages contain no code or media URL.
    if(w.parent===w||event.source!==w.parent||!event.data||event.data.type!==eventName)return;
    if(event.data.action==='prepare-toggle'){requestToggle(function(){w.parent.postMessage({type:eventName,action:'toggle-ready'},'*');});return;}
    if(event.data.action==='enter')enter();else if(event.data.action==='exit')exit();else if(event.data.action==='pause'){pause();return;}else if(event.data.action!=='snapshot')return;
    w.parent.postMessage({type:eventName,action:'state',state:snapshot()},'*');
  });
})(window);
