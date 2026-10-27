(function(){
  'use strict';
  if(window.ClearStreamTVPlugins)return;
  var registry=[],active=[],errors=[],enabled=false,foreground=!document.hidden;
  function context(id){
    var cleanups=[],clicked=new WeakSet(),hidden=new WeakMap();
    function cleanup(){for(var i=cleanups.length-1;i>=0;i--)try{cleanups[i]();}catch(ignore){}}
    return {document:document,host:location.hostname,cleanup:cleanup,
      onCleanup:function(fn){if(typeof fn==='function')cleanups.push(fn);},
      addStyle:function(css){var node=document.createElement('style');node.textContent=String(css);document.documentElement.appendChild(node);cleanups.push(function(){node.remove();});},
      hide:function(selector){document.querySelectorAll(selector).forEach(function(el){if(hidden.has(el))return;hidden.set(el,true);var value=el.style.getPropertyValue('display'),priority=el.style.getPropertyPriority('display');el.style.setProperty('display','none','important');cleanups.push(function(){if(value)el.style.setProperty('display',value,priority);else el.style.removeProperty('display');});});},
      focusable:function(selector){document.querySelectorAll(selector).forEach(function(el){var old=el.getAttribute('tabindex');if(old!==null)return;el.setAttribute('tabindex','0');cleanups.push(function(){el.removeAttribute('tabindex');});});if(window.__clearTV)window.__clearTV.rescan();},
      clickOnce:function(selector){if(!enabled||!foreground||document.hidden)return false;var el=document.querySelector(selector);if(!el||clicked.has(el)||el.disabled)return false;var r=el.getBoundingClientRect(),style=getComputedStyle(el);if(r.width<2||r.height<2||style.display==='none'||style.visibility==='hidden')return false;clicked.add(el);el.click();return true;},
      observe:function(fn){var timer=0;function run(){timer=0;if(!enabled||!foreground)return;try{fn();}catch(ignore){if(errors.indexOf(id)<0)errors.push(id);}}var observer=new MutationObserver(function(){if(!timer)timer=setTimeout(run,80);});observer.observe(document.documentElement,{childList:true,subtree:true});cleanups.push(function(){observer.disconnect();clearTimeout(timer);});run();}
    };
  }
  function stop(){active.forEach(function(p){p.ctx.cleanup();});active=[];}
  function start(){
    if(!enabled||!foreground||document.hidden)return;
    registry.forEach(function(p){if(p.hosts.indexOf(location.hostname.toLowerCase())<0||active.some(function(a){return a.id===p.id;}))return;var ctx=context(p.id);try{var dispose=p.mount(ctx);if(typeof dispose==='function')ctx.onCleanup(dispose);active.push({id:p.id,ctx:ctx});}catch(ignore){ctx.cleanup();if(errors.indexOf(p.id)<0)errors.push(p.id);}});
  }
  window.ClearStreamTVPlugins={apiVersion:1,
    register:function(plugin){if(!plugin||!/^[a-z0-9][a-z0-9.-]{0,79}$/.test(plugin.id)||!Array.isArray(plugin.hosts)||!plugin.hosts.length||plugin.hosts.some(function(h){return typeof h!=='string'||!/^[a-z0-9.-]+$/.test(h);})||typeof plugin.mount!=='function'||registry.some(function(p){return p.id===plugin.id;}))throw Error('Invalid or duplicate site plugin');registry.push(plugin);start();},
    enable:function(value){enabled=!!value;if(enabled)start();else stop();},
    foreground:function(value){foreground=!!value;if(foreground)start();else stop();},
    status:function(){return {enabled:enabled,foreground:foreground,registered:registry.map(function(p){return p.id;}),active:active.map(function(p){return p.id;}),errors:errors.slice()};}
  };
  document.addEventListener('visibilitychange',function(){if(document.hidden)stop();else start();});
})();
