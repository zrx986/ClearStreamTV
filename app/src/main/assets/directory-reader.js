(function () {
  'use strict';
  var items = [], seen = {}, scope = document.querySelector('.sites-list'), category = '网站', recognized = false;
  function clean(value, limit) { return String(value || '').replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, limit); }
  function host(value) { return value.toLowerCase().replace(/^www\./, ''); }
  var base;
  try { base = new URL(document.baseURI); } catch (ignored) { return {items: []}; }
  function add(raw, name, group, summary) {
    raw = clean(raw, 2049);var address;
    if (!raw || raw.length > 2048 || items.length >= 240) return false;
    try { address = new URL(raw, document.baseURI); } catch (ignored) { return false; }
    if (!/^https?:$/.test(address.protocol) || !address.hostname || address.username || address.password || host(address.hostname) === host(base.hostname)) return false;
    if (/\.(?:apk|exe|msi|dmg|zip|rar|7z)$/i.test(address.pathname)) return false;
    name = clean(name, 80);group = clean(group, 40) || '网站';
    if (!name || /友情链接|广告|推广|赞助/.test(group) || seen[address.href]) return false;
    seen[address.href] = true;items.push({name:name, url:address.href, category:group, summary:clean(summary,220)});return true;
  }
  var nodes = scope ? scope.querySelectorAll('h4,.xe-card .box2[data-url]') : [];
  for (var i = 0; i < nodes.length && i < 3000 && items.length < 240; i++) {
    var node = nodes[i];
    if (node.tagName === 'H4') { category = clean(node.textContent, 40) || '网站'; continue; }
    if (/友情链接|广告|推广|赞助/.test(category)) continue;
    var title = node.querySelector('.site-name strong,strong,.site-name'), image = node.querySelector('img');
    var name = clean(title ? title.textContent : image ? image.getAttribute('alt') : '', 80);
    var description = node.querySelector('p');
    if(add(node.getAttribute('data-url'),name,category,description ? description.textContent : ''))recognized=true;
  }
  // Modern directory cards publish a site heading, description and their target
  // URL as text. A repeated, multi-host group is required; ordinary film cards
  // and isolated links with a /movie/ path are not directory evidence.
  var groups = [], links = document.querySelectorAll('a[href]');
  function floating(el) { for(var n=el,depth=0;n&&depth<8;n=n.parentElement,depth++)if(getComputedStyle(n).position==='fixed')return true;return false; }
  function visible(el) {if(!el.getClientRects().length)return false;for(var n=el,depth=0;n&&depth<12;n=n.parentElement,depth++){var style=getComputedStyle(n);if(style.display==='none'||style.visibility==='hidden'||style.opacity==='0')return false;}return true;}
  function displayedTarget(node,address) {
    var printed=String(node.textContent || '').match(/https?:\/\/[^\s<>"']+/g) || [];
    for(var j=0;j<Math.min(printed.length,8);j++)try{var value=new URL(printed[j]);if(value.hostname===address.hostname&&value.port===address.port&&value.pathname.replace(/\/$/,'')===address.pathname.replace(/\/$/,''))return true;}catch(ignored){}
    return false;
  }
  for(var li=0;li<Math.min(links.length,1600);li++) {
    var anchor=links[li], heading=anchor.querySelector('h2,h3,h4,.site-name'), paragraph=anchor.querySelector('p,.description,.desc');
    if(!heading||!paragraph||!visible(anchor)||anchor.hasAttribute('download')||anchor.closest('header,nav,footer,[role="navigation"],.ad,.ads,.advertisement,[data-ad],[hidden],[aria-hidden="true"]')||floating(anchor))continue;
    var target;
    try{target=new URL(anchor.getAttribute('href'),document.baseURI);}catch(ignored){continue;}
    if(!/^https?:$/.test(target.protocol)||!target.hostname||target.username||target.password||host(target.hostname)===host(base.hostname)||!displayedTarget(anchor,target))continue;
    var parent=anchor.parentElement, group=null;
    for(var gi=0;gi<groups.length;gi++)if(groups[gi].parent===parent){group=groups[gi];break;}
    if(!group){group={parent:parent,cards:[],hosts:{}};groups.push(group);}
    group.cards.push({url:target.href,name:heading.textContent,summary:paragraph.textContent,category:anchor.getAttribute('data-category') || '网站'});group.hosts[target.hostname]=true;
  }
  for(var g=0;g<groups.length;g++) {
    var modern=groups[g];if(modern.cards.length<3||Object.keys(modern.hosts).length<2)continue;
    for(var c=0;c<modern.cards.length&&items.length<240;c++){var card=modern.cards[c];if(add(card.url,card.name,card.category,card.summary))recognized=true;}
  }
  return {directory:recognized, items:items};
})()
