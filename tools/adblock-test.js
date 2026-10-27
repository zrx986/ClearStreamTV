const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {chromium}=require('playwright');
const assets=path.join(__dirname,'../app/src/main/assets');
(async()=>{
  const source=fs.readFileSync(path.join(assets,'adblock-dom.js'),'utf8'),rules=JSON.parse(fs.readFileSync(path.join(assets,'adblock-rules.json'),'utf8'));
  const browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})});
  try{
    const page=await browser.newPage({viewport:{width:1280,height:720}});await page.setContent('<div class="adsbygoogle">常见广告</div><a id="promo" href="#unwanted" style="display:block;width:200px;height:100px">自选广告区域</a><video controls style="width:320px;height:180px"></video>');await page.addScriptTag({content:source});
    await page.evaluate(r=>__clearAds.configure({cosmetic:true,selectors:r.selectors}),rules);assert.equal(await page.locator('.adsbygoogle').isVisible(),false);assert.equal(await page.locator('video').isVisible(),true);
    await page.evaluate(()=>__clearAds.startPicker());const box=await page.locator('#promo').boundingBox();const x=(box.x+box.width/2)/1280,y=(box.y+box.height/2)/720;
    await page.mouse.click(x*1280,y*720);assert.equal(await page.evaluate(()=>location.hash),'','Selecting must never follow the advertisement link');
    const result=await page.evaluate(({x,y})=>__clearAds.pickAt(x,y),{x,y});assert.equal(result.ok,true);assert.equal(result.selector,'[id="promo"]');
    await page.evaluate(selector=>__clearAds.configure({cosmetic:true,selectors:[selector]}),result.selector);assert.equal(await page.locator('#promo').isVisible(),false);assert.equal(await page.locator('video').isVisible(),true);
    await page.evaluate(()=>__clearAds.configure({cosmetic:false}));assert.equal(await page.locator('#promo').isVisible(),true);assert.equal(await page.locator('.adsbygoogle').isVisible(),true);
    console.log('PASS cosmetic blocking, point selection, no ad navigation, stable rule and undo');
  }finally{await browser.close();}
  let beforeRequest,onMessage;const sandbox={ClearStreamAdRules:rules,URL,Promise,browser:{storage:{local:{get:async()=>({}),set:async()=>{}}},webRequest:{onBeforeRequest:{addListener:fn=>beforeRequest=fn}},runtime:{onMessage:{addListener:fn=>onMessage=fn}},tabs:{onRemoved:{addListener(){}}}}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../app/src/standard/assets/tv-engine/adblock-background.js'),'utf8'),sandbox);
  beforeRequest({tabId:1,type:'main_frame',url:'https://site.example/'});
  assert.equal(beforeRequest({tabId:1,type:'image',url:'https://ad.doubleclick.net/pixel.gif'}).cancel,true);
  assert.equal(beforeRequest({tabId:1,type:'media',url:'https://ad.doubleclick.net/video.mp4'}).cancel,undefined);
  await onMessage({type:'clearstream-ad-config',config:{network:false,domains:rules.domains}},{tab:{id:1,url:'https://site.example/'}});
  assert.equal(beforeRequest({tabId:1,type:'image',url:'https://ad.doubleclick.net/pixel.gif'}).cancel,undefined);
  console.log('PASS background request interception, media preservation and per-site off switch');
})().catch(e=>{console.error(e);process.exitCode=1;});
