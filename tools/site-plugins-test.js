const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const file=path.join(__dirname,'../app/src/main/assets/site-plugins.js');
const source=fs.existsSync(file)?fs.readFileSync(file,'utf8'):'/* no API */';
(async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})});
  try{
    const page=await browser.newPage();await page.route('https://plugin.test/**',r=>r.fulfill({contentType:'text/html',body:'<div id="banner" style="display:block">示例广告</div><button id="skip" type="button" onclick="this.dataset.clicks=String(Number(this.dataset.clicks||0)+1)">跳过</button>'}));await page.goto('https://plugin.test/');await page.addScriptTag({content:source});
    assert.equal(await page.evaluate(()=>!!window.ClearStreamTVPlugins),true,'A usable developer plugin API must exist');
    await page.evaluate(()=>{ClearStreamTVPlugins.register({id:'wrong-host',hosts:['other.test'],mount(){throw Error('must not run')}});ClearStreamTVPlugins.register({id:'broken',hosts:['plugin.test'],mount(){throw Error('fixture failure')}});ClearStreamTVPlugins.register({id:'demo',hosts:['plugin.test'],mount(ctx){ctx.hide('#banner');ctx.observe(()=>ctx.clickOnce('#skip'));}});ClearStreamTVPlugins.enable(true);});
    assert.equal(await page.locator('#banner').isVisible(),false);assert.equal(await page.locator('#skip').getAttribute('data-clicks'),'1');
    assert.deepEqual(await page.evaluate(()=>ClearStreamTVPlugins.status().active),['demo']);assert.deepEqual(await page.evaluate(()=>ClearStreamTVPlugins.status().errors),['broken']);
    await page.evaluate(()=>document.body.appendChild(document.createElement('p')));await page.waitForTimeout(120);assert.equal(await page.locator('#skip').getAttribute('data-clicks'),'1','Do not repeatedly click the same skip button');
    await page.evaluate(()=>ClearStreamTVPlugins.foreground(false));assert.equal(await page.locator('#banner').isVisible(),true);assert.deepEqual(await page.evaluate(()=>ClearStreamTVPlugins.status().active),[]);
    await page.evaluate(()=>ClearStreamTVPlugins.foreground(true));assert.equal(await page.locator('#banner').isVisible(),false);await page.evaluate(()=>ClearStreamTVPlugins.enable(false));assert.equal(await page.locator('#banner').isVisible(),true);
    console.log('PASS exact host scope, error isolation, reversible styles, one-time click and foreground lifecycle');
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
