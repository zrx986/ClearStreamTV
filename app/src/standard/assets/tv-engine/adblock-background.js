(function(){
  'use strict';var defaults=ClearStreamAdRules,sites={},tabs={},counts={};
  function host(url){try{return new URL(url).hostname.toLowerCase().replace(/\.$/,'');}catch(ignore){return '';}}
  function block(details,config){
    if(!config.network||details.type==='main_frame'||details.type==='media')return false;
    var url;try{url=new URL(details.url);}catch(ignore){return false;}
    if(/\.(m3u8|mpd|mp4|m4s|ts|aac|m4a|webm|mp3)$/i.test(url.pathname)||/\/ssai\/|\/ondemand\/hls\//i.test(url.pathname))return false;
    var h=host(details.url);return (config.domains||[]).some(function(d){return h===d||h.endsWith('.'+d);});
  }
  browser.storage.local.get('siteAdConfigs').then(function(data){sites=Object.assign({},data.siteAdConfigs||{},sites);}).catch(function(){});
  browser.webRequest.onBeforeRequest.addListener(function(details){
    if(details.type==='main_frame')tabs[details.tabId]=host(details.url);
    var site=tabs[details.tabId]||host(details.documentUrl||details.originUrl||''),config=sites[site]||{network:true,domains:defaults.domains};
    if(block(details,config)){counts[details.tabId]=(counts[details.tabId]||0)+1;return {cancel:true};}return {};
  },{urls:['http://*/*','https://*/*']},['blocking']);
  browser.runtime.onMessage.addListener(function(message,sender){
    if(!sender.tab)return;var tab=sender.tab.id,site=tabs[tab]||host(sender.tab.url||sender.url||'');
    if(message.type==='clearstream-ad-config'){sites[site]=message.config;browser.storage.local.set({siteAdConfigs:sites}).catch(function(){});}
    if(message.type==='clearstream-ad-config'||message.type==='clearstream-ad-stats')return Promise.resolve({blocked:counts[tab]||0});
  });
  if(browser.tabs&&browser.tabs.onRemoved)browser.tabs.onRemoved.addListener(function(id){delete tabs[id];delete counts[id];});
})();
