const {chromium}=require('playwright');
const fs=require('node:fs');
const path=require('node:path');
const http=require('node:http');
const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../app/src/main/assets');
const output=path.join(__dirname,'output');fs.mkdirSync(output,{recursive:true});
const sample={id:'test-book',title:'The Art of Paying Attention',pages:124,words:28650,total:900,position:265,completed:false,ocr:0,skipped:0};
const context=[{index:264,page:36,text:'There is a certain quiet pleasure in noticing what has been there all along.'},{index:265,page:36,text:'The world is full of small, beautiful things, waiting for us to slow down enough to see them.'},{index:266,page:36,text:'A familiar street can become an entirely new place when we pay attention.'}];
async function run(){
 const server=http.createServer((req,res)=>{const target=path.join(root,req.url==='/'?'index.html':req.url);if(!target.startsWith(root)){res.writeHead(403);res.end();return;}fs.readFile(target,(err,data)=>{if(err){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',target.endsWith('.css')?'text/css':target.endsWith('.js')?'application/javascript':'text/html');res.end(data);});});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({executablePath:'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',headless:true});
 let checks=0;const errors=[];
 try{
  for(const width of [360,412,768,1440]){
   const page=await browser.newPage({viewport:{width,height:width>850?1000:900},deviceScaleFactor:1,reducedMotion:'reduce'});
   page.on('pageerror',e=>errors.push(e.message));
   await page.addInitScript(()=>{window.nativeCalls=[];window.currentNativeState={};window.addEventListener('folio-state',e=>Object.assign(window.currentNativeState,e.detail));window.FolioNative={command:(action,raw)=>window.nativeCalls.push({action,...JSON.parse(raw)})};});
   await page.goto(`http://127.0.0.1:${server.address().port}`);
   await page.screenshot({path:path.join(output,`empty-${width}.png`),fullPage:true});
   await page.getByRole('button',{name:'Import PDF',exact:true}).click();
   assert.ok(await page.evaluate(()=>nativeCalls.some(c=>c.action==='import')));checks++;
   await page.evaluate(({sample,context})=>window.dispatchEvent(new CustomEvent('folio-state',{detail:{library:[sample,{...sample,id:'second',title:'A Journey Through the Quiet Mountains',position:0},{...sample,id:'third',title:'Collected Thoughts',position:900}],player:{book:sample,index:265,total:900,page:36,rate:1.15,pitch:1,pauseMs:500,playing:false,ready:true,context,bookmarks:[],engineLabel:'Speech Recognition & Synthesis from Google'},stats:{millis:12720000,words:34782,today:720000,finished:1,streak:4,days:[{day:'2026-10-09',millis:0},{day:'2026-10-10',millis:1200000},{day:'2026-10-11',millis:600000},{day:'2026-10-12',millis:1800000},{day:'2026-10-13',millis:900000},{day:'2026-10-14',millis:1500000},{day:'2026-10-15',millis:720000}]}}})),{sample,context});
   await page.screenshot({path:path.join(output,`library-${width}.png`),fullPage:true});
   assert.equal(await page.locator('.book-card').count(),3);checks++;
   await page.locator('#library-search').fill('MOUNTAINS');assert.equal(await page.locator('.book-card').count(),1);checks++;
   await page.locator('#library-search').fill('');
   for(const view of ['library','player','stats','settings']){
    await page.evaluate(v=>location.hash=v,view);await page.waitForTimeout(80);
    const overflow=await page.evaluate(()=>({doc:document.documentElement.scrollWidth,win:innerWidth}));assert.ok(overflow.doc<=overflow.win,`${view} overflows at ${width}: ${JSON.stringify(overflow)}`);checks++;
    await page.screenshot({path:path.join(output,`${view}-${width}.png`),fullPage:true});
   }
   await page.evaluate(()=>location.hash='player');await page.waitForTimeout(60);
   await page.locator('.player-options [data-action="pace"]').click();await page.locator('#sheet-pause').fill('1700');await page.locator('#sheet-pause').dispatchEvent('change');assert.ok(await page.evaluate(()=>nativeCalls.some(c=>c.action==='settings'&&c.pauseMs===1700)));checks++;
   await page.screenshot({path:path.join(output,`pace-${width}.png`)});
   await page.getByRole('button',{name:'That feels right'}).click();
   await page.locator('.player-options [data-action="sleep"]').click();await page.getByRole('button',{name:'30 min',exact:true}).click();assert.ok(await page.evaluate(()=>nativeCalls.some(c=>c.action==='sleep'&&c.minutes===30)));checks++;
   await page.locator('.player-options [data-action="bookmark"]').click();assert.ok(await page.evaluate(()=>nativeCalls.some(c=>c.action==='bookmark')));checks++;
   // A real native response can arrive well after the former 100 ms UI timeout.
   await page.getByRole('button',{name:'View bookmarks'}).click();
   await page.waitForTimeout(180);
   await page.evaluate(()=>window.dispatchEvent(new CustomEvent('folio-state',{detail:{player:{...currentNativeState.player,bookmarks:[{index:265,page:36,label:'Saved passage'}]}}})));
   assert.equal(await page.locator('#sheet .result-button').count(),1);checks++;
   await page.getByRole('button',{name:'Remove bookmark',exact:true}).click();
   await page.waitForTimeout(180);
   await page.evaluate(()=>window.dispatchEvent(new CustomEvent('folio-state',{detail:{player:{...currentNativeState.player,bookmarks:[]}}})));
   assert.equal(await page.locator('#sheet .result-button').count(),0);checks++;
   await page.getByRole('button',{name:'Close dialog'}).click();
   await page.evaluate(()=>window.dispatchEvent(new CustomEvent('folio-state',{detail:{player:{...currentNativeState.player,sleepSeconds:300}}})));
   assert.ok(await page.locator('.player-options [data-action="sleep"]').evaluate(e=>e.classList.contains('active')));checks++;
   await page.evaluate(()=>window.dispatchEvent(new CustomEvent('folio-state',{detail:{player:{...currentNativeState.player,sleepSeconds:0}}})));
   assert.ok(await page.locator('.player-options [data-action="sleep"]').evaluate(e=>!e.classList.contains('active')));checks++;
   await page.getByRole('button',{name:'Search this book'}).click();await page.locator('#book-search').fill('beautiful');await page.waitForTimeout(250);
   await page.evaluate(()=>window.dispatchEvent(new CustomEvent('folio-search',{detail:{query:'beautiful',results:[{index:265,page:36,text:'A beautiful passage.'}]}})));
   await page.locator('.result-button').click();assert.ok(await page.evaluate(()=>nativeCalls.some(c=>c.action==='seek'&&c.index===265)));checks++;
   await page.locator('.theme-toggle').click();assert.ok(await page.locator('body').evaluate(e=>e.classList.contains('dark')));checks++;
   await page.screenshot({path:path.join(output,`dark-player-${width}.png`),fullPage:true});
   await page.locator('.player-options [data-action="voice"]').click();
   await page.evaluate(()=>window.dispatchEvent(new CustomEvent('folio-state',{detail:{settings:{rate:1.15,pitch:1.2,pauseMs:500,theme:'dark',ocr:true,goal:30,engine:'com.google.android.tts',engines:[{name:'com.google.android.tts',label:'Google speech engine'}],language:'en-US',voice:'',activeVoice:'offline-good',voices:[{name:'offline-good',label:'English (United States) \u00b7 Voice 1',language:'en-US',network:false,installed:true,quality:'Enhanced quality'},{name:'online',label:'Network voice',language:'en-US',network:true,installed:true,quality:'Enhanced quality'},{name:'missing',label:'English (United States) \u00b7 Voice 2',language:'en-US',network:false,installed:false,quality:'Standard quality'}]}}})));
   assert.equal(await page.locator('#voice option[value="online"]').count(),0);assert.ok(await page.locator('#voice option[value="missing"]').isDisabled());checks++;
   await page.getByRole('button',{name:'Hear sample',exact:true}).click();assert.ok(await page.evaluate(()=>nativeCalls.some(c=>c.action==='previewVoice')));checks++;
   await page.evaluate(()=>window.dispatchEvent(new CustomEvent('folio-state',{detail:{player:{...currentNativeState.player,previewing:true}}})));
   await page.getByRole('button',{name:'Stop sample',exact:true}).click();assert.ok(await page.evaluate(()=>nativeCalls.some(c=>c.action==='stopPreview')));checks++;
   await page.getByRole('button',{name:'Normal pitch & pace',exact:true}).click();assert.ok(await page.evaluate(()=>nativeCalls.some(c=>c.action==='settings'&&c.pitch===1&&c.rate===1)));checks++;
   await page.evaluate(()=>window.dispatchEvent(new CustomEvent('folio-state',{detail:{settings:{...currentNativeState.settings,theme:'system'}}})));
   await page.emulateMedia({colorScheme:'light'});await page.waitForTimeout(50);
   assert.ok(await page.locator('body').evaluate(e=>!e.classList.contains('dark')));checks++;
   await page.emulateMedia({colorScheme:'dark'});await page.waitForTimeout(50);
   assert.ok(await page.locator('body').evaluate(e=>e.classList.contains('dark')));checks++;
   await page.screenshot({path:path.join(output,`offline-voices-${width}.png`),fullPage:true});
   await page.evaluate(()=>location.hash='player');await page.waitForTimeout(50);
   // Untrusted document strings remain inert, including notification-like HTML.
   await page.evaluate(({sample})=>{sample.title='<img src=x onerror="window.injected=true">';window.dispatchEvent(new CustomEvent('folio-state',{detail:{library:[sample],player:{book:sample,index:0,total:900,page:1,rate:1,pitch:1,pauseMs:400,playing:false,context:[{index:0,page:1,text:'<script>window.injected=true</script>'}],bookmarks:[]}}}));},{sample});
   assert.equal(await page.evaluate(()=>window.injected),undefined);assert.equal(await page.locator('#player img').count(),0);checks++;
   await page.close();
  }
  assert.deepEqual(errors,[]);checks++;
  fs.writeFileSync(path.join(output,'ui-results.json'),JSON.stringify({checks,errors,widths:[360,412,768,1440],note:'Browser UI tests use a mocked native bridge; they do not validate Android speech.'},null,2));
  console.log(`${checks} UI checks passed at 360, 412, 768 and 1440 px.`);
 } finally {await browser.close();server.close();}
}
run().catch(e=>{console.error(e);process.exitCode=1;});
