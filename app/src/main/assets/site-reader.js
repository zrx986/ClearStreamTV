(function () {
  'use strict';
  // Data reader only: no network calls, media URLs, click simulation or native bridge.
  var result = {url: location.href, title: String(document.title || '').slice(0,100), items: [], form: null};
  function clean(value, max) { return String(value || '').replace(/\s+/g,' ').trim().slice(0,max); }
  function address(value) {
    if (!value || String(value).length > 8192) return null;
    try { var u = new URL(value, document.baseURI); return /^https?:$/.test(u.protocol) && !u.username && !u.password ? u.href : null; } catch (_) { return null; }
  }
  function visible(el) {
    if (!el || el.closest('[hidden],[aria-hidden="true"],template')) return false;
    for (var n=el,depth=0;n && depth<12;n=n.parentElement,depth++) {
      var s=getComputedStyle(n); if (s.display==='none' || s.visibility==='hidden' || s.opacity==='0') return false;
    }
    return el.getClientRects().length>0;
  }
  function decorative(image) { return !!image && (/^(decoration|decorative|mascot|装饰|装饰图|装饰图片)$/i.test(String(image.getAttribute('alt') || '').trim()) || image.getAttribute('role')==='presentation' || !!image.closest('[aria-hidden="true"]')); }
  function posterIn(node) {var images=node.querySelectorAll('img');for(var i=0;i<Math.min(images.length,24);i++)if(!decorative(images[i]))return images[i];return null;}
  function floating(el) {for(var n=el,depth=0;n&&depth<8;n=n.parentElement,depth++)if(getComputedStyle(n).position==='fixed')return true;return false;}
  Array.prototype.slice.call(document.querySelectorAll('video,audio'),0,20).forEach(function (m) { try { m.pause(); m.autoplay=false; } catch (_) {} });
  // Read past ordinary address announcements without clicking/accepting them.
  // Authentication, verification and unknown dialogs still block native reading.
  function addressNotice(el) {
    if(el.querySelector('input,select,textarea,form,iframe,video,[contenteditable="true"]'))return false;
    var text=clean(el.textContent,1600);
    return /(?:收藏.*发布页|收藏.*最新地址|最新发布页|防止走丢)/.test(text) && !/(?:验证码|人机验证|安全验证|请登录|密码|付费|会员|同意协议)/.test(text);
  }
  var dialogs=document.querySelectorAll('dialog[open],[role="dialog"],[role="alertdialog"],[aria-modal="true"]');
  for (var d=0;d<Math.min(dialogs.length,8);d++) if (visible(dialogs[d])&&!addressNotice(dialogs[d])) { result.notice='页面有待处理弹窗，请在原网页操作'; return result; }
  // Read only literal public metadata. Never eval scripts, call APIs, or use
  // these objects to invent content links or extract a media/playback URL.
  var metadata=Object.create(null), metadataCount=0, visited=0;
  function walkMetadata(value, depth) {
    if(!value || typeof value!=='object' || depth>32 || ++visited>12000 || metadataCount>=200)return;
    if(!Array.isArray(value) && /^\d{1,12}$/.test(String(value.vodId || '')) && typeof value.vodName==='string' && typeof value.vodPic==='string') {
      var picture=address(value.vodPic), id=String(value.vodId);
      if(picture && picture.length<=2048 && !metadata[id]) {metadata[id]={title:clean(value.vodName,100),image:picture,summary:clean(String(value.vodBlurb || '').replace(/<[^>]*>/g,' '),600)};metadataCount++;}
    }
    if(Array.isArray(value)) {for(var i=0;i<Math.min(value.length,400) && visited<=12000;i++)walkMetadata(value[i],depth+1);}
    else for(var key in value)if(Object.prototype.hasOwnProperty.call(value,key) && visited<=12000)walkMetadata(value[key],depth+1);
  }
  var scripts=document.querySelectorAll('script'), scriptBudget=0;
  for(var si=0;si<Math.min(scripts.length,96);si++) {
    var source=scripts[si].textContent || '';
    if(source.length>128*1024 || source.indexOf('vodPic')<0)continue;
    scriptBudget+=source.length;if(scriptBudget>512*1024)break;
    var literal=source.match(/^\s*self\.__next_f\.push\((\[[\s\S]*\])\)\s*;?\s*$/);
    if(!literal)continue;
    try {var record=JSON.parse(literal[1]);if(Array.isArray(record) && record[0]===1 && typeof record[1]==='string' && /^[0-9a-f]+:/i.test(record[1]))walkMetadata(JSON.parse(record[1].slice(record[1].indexOf(':')+1)),0);}catch(_){}
  }
  var forms=document.querySelectorAll('form');
  for (var f=0;f<Math.min(forms.length,16);f++) {
    var form=forms[f], method=String(form.getAttribute('method') || 'get').toLowerCase();
    if (method!=='get' || form.getAttribute('onsubmit') || !visible(form)) continue;
    if(form.elements.length>40)continue;
    var controls=Array.prototype.slice.call(form.elements,0,40), query=null, fixed=[], bad=controls.some(function(input){
      if(input.matches(':disabled'))return false;
      var type=String(input.type || '').toLowerCase();
      return /^(password|file|image)$/.test(type) || ((type==='submit' || input.tagName==='BUTTON') && (input.name || input.hasAttribute('formaction') || input.hasAttribute('formmethod')));
    });
    if(bad)continue;
    for (var i=0;i<controls.length;i++) {
      var input=controls[i], type=String(input.type || '').toLowerCase();
      if (!input.name || input.matches(':disabled') || input.readOnly || !/^(search|text)$/.test(type) || !visible(input)) continue;
      if (type==='search' || /^(q|wd|keyword|keywords|search|searchword|word|k)$/i.test(input.name) || /搜索|search/i.test(input.placeholder || '')) { query=input; break; }
    }
    if (bad || !query || !address(form.action)) continue;
    controls.forEach(function (input) {
      var type=String(input.type || '').toLowerCase();
      if (!input.name || input===query || input.matches(':disabled') || /^(submit|button|reset|file|password)$/.test(type) || (/^(checkbox|radio)$/.test(type) && !input.checked)) return;
      if (input.tagName==='SELECT') Array.prototype.forEach.call(input.options,function (o) { if(o.selected && !o.matches(':disabled')) fixed.push([input.name,String(o.value)]); });
      else fixed.push([String(input.name),String(input.value || '')]);
    });
    if (fixed.length<=32) { result.form={action:form.action,method:method,query:String(query.name),fixed:fixed}; break; }
  }
  var links=document.querySelectorAll('a[href]'), seen=Object.create(null), budget=0;
  for (var j=0;j<Math.min(links.length,800) && result.items.length<120;j++) {
    var a=links[j];
    if (!visible(a) || floating(a) || a.closest('header,nav,footer,[role="navigation"],[role="banner"],[role="dialog"],[role="alertdialog"],[aria-modal="true"],.ad,.ads,.advertisement,[data-ad]')) continue;
    var url=address(a.getAttribute('href'));
    if (!url || seen[url]) continue;
    var parsed=new URL(url), path=parsed.pathname;
    if (/\.(png|jpe?g|gif|webp|svg|m3u8|mp4|mpd|ts|txt|json|zip|apk)$/i.test(path) || /^\/(account|usercenter|profile|login|register|privacy|terms|about|legal)(\/|$)/i.test(path) || /\/(?:genre|category|categories|type|vodtype|vodshow)(?:\/|[-_])/i.test(path)) continue;
    var image=posterIn(a), title=clean(a.getAttribute('title') || a.getAttribute('aria-label') || (image && image.getAttribute('alt')) || a.textContent,100);
    if (title.length<2) continue;
    var thumbnailDetail=!!image && a.classList.contains('thumbnail') && /^\/(?:dm|dy|dsj|zy|dj)\/\d+\.html$/i.test(path) && parsed.origin===location.origin;
    var content=parsed.origin===location.origin && (thumbnailDetail || /\/(?:x\/)?(?:cover|page|detail|video|videos|play|watch|movie|film|vod(?:detail|play)?|b|v)(?:\/|[-_.])/i.test(path));
    var videoId=parsed.origin===location.origin && (parsed.searchParams.get('vid') || parsed.searchParams.get('videoid') || parsed.searchParams.get('cid'));
    if (!content && !videoId && /^(登录|注册|下载|开通会员|个人中心|隐私政策|用户协议|版权声明|广告|login|sign in|privacy)$/i.test(title)) continue;
    if (!content && !videoId && !a.closest('article,[class*="video-card"],[class*="video-item"],[class*="movie-item"],[class*="module-item"],[class*="poster"],[class*="result-item"],[class*="vodlist"]')) continue;
    var container=a.closest('article,[class*="video-card"],[class*="video-item"],[class*="movie-item"],[class*="module-item"],[class*="result-item"]') || (thumbnailDetail && a.closest('li')) || a.parentElement;
    if(!image && container)image=posterIn(container);
    var poster=image && (address(image.getAttribute('data-src')) || address(image.getAttribute('data-original')) || address(image.currentSrc) || address(image.getAttribute('src')));
    if (poster && poster.length>2048) poster=null;
    var description=container && container.querySelector('[data-description],.description,.desc,.intro,.summary,p');
    var summary=clean(a.getAttribute('data-description') || (description && (description.getAttribute('data-description') || description.textContent)),600);
    var contentId=path.match(/^\/(?:detail|vod\/(?:detail|play))\/(\d{1,12})(?:\/|$)/i), extra=contentId && parsed.origin===location.origin && metadata[contentId[1]];
    if(extra && extra.title===title) {poster=extra.image;if(!summary)summary=extra.summary;}
    budget+=url.length+title.length+summary.length+(poster ? poster.length:0);
    if (budget>48000) break;
    var referrerPolicy=image && String(image.getAttribute('referrerpolicy') || '').toLowerCase()==='no-referrer'?'no-referrer':'';
    seen[url]=true;result.items.push({title:title,url:url,image:poster || '',summary:summary,referrerPolicy:referrerPolicy});
  }
  return result;
})()
