const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/java/com/paperlight/reader/ui/reader/EpubReaderScreen.kt'),'utf8');
let script=source.split('): String = """')[1].split('""".trimIndent()')[0].replace(/\$\{when \(themeMode\)[\s\S]*?\}\}/g,"'white'").replaceAll("${'$'}",'$');
for(const [k,v] of Object.entries({fontScale:1,scrollMode:false,widthPx:360,heightPx:720,flip:true,chapterIndex:2,initialFraction:0,lastChapterIndex:8,entryDirection:0}))script=script.replaceAll('${'+k+'}',String(v)).replaceAll('$'+k,String(v));
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH,headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:360,height:720},hasTouch:true,isMobile:true});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.setContent('<meta name="viewport" content="width=device-width,initial-scale=1"><h1>Chapter One</h1>'+Array.from({length:60},(_,i)=>`<p>Paragraph ${i+1}. The evening light fell across the quiet library. Each page should follow your hand, revealing the words underneath.</p>`).join(''));
 await page.evaluate(()=>{window.reports=[];window.Paperlight={onPage:(...x)=>reports.push(x),onReaderTap(){},onChapterTurn(){},onSelection(){}};});
 await page.evaluate(script);
 await page.waitForTimeout(200);
 const cdp=await page.context().newCDPSession(page);
 async function touch(type,x){await cdp.send('Input.dispatchTouchEvent',{type,touchPoints:type==='touchEnd'||type==='touchCancel'?[]:[{x,y:370}]});}
 await touch('touchStart',300);await touch('touchMove',120);
 await page.waitForTimeout(40);
 const forward=await page.evaluate(()=>({clip:document.getElementById('paperlight-viewport').style.clipPath,layers:document.querySelectorAll('body > div').length}));
 assert.match(forward.clip,/polygon/);assert.equal(forward.layers,4);
 await page.screenshot({path:path.join(__dirname,'curl-forward.png')});
 await touch('touchEnd');await page.waitForTimeout(400);
 assert.equal(await page.evaluate(()=>reports.at(-1)[0]),1);
 await touch('touchStart',60);await touch('touchMove',240);await page.waitForTimeout(40);
 assert.equal(await page.evaluate(()=>document.getElementById('paperlight-viewport').style.clipPath),forward.clip,'backward halfway must exactly reverse forward halfway');
 await page.screenshot({path:path.join(__dirname,'curl-back.png')});
 await touch('touchEnd');await page.waitForTimeout(400);
 assert.equal(await page.evaluate(()=>reports.at(-1)[0]),0);
 await page.evaluate(()=>paperlightSetFlip(false));
 await touch('touchStart',300);await touch('touchMove',210);
 assert.equal(await page.evaluate(()=>document.getElementById('paperlight-viewport').scrollLeft),90);
 await touch('touchMove',140);
 assert.equal(await page.evaluate(()=>document.getElementById('paperlight-viewport').scrollLeft),160);
 await touch('touchCancel');
 assert.equal(await page.evaluate(()=>document.getElementById('paperlight-viewport').scrollLeft),0);
 for(const texture of ['grain','linen','none']){
   await page.evaluate(texture=>paperlightSetAppearance({size:1,lineHeight:1.65,margins:24,font:'serif',alignment:'original',texture}),texture);
   const bg=await page.evaluate(()=>getComputedStyle(document.getElementById('paperlight-viewport')).backgroundImage);
   assert.equal(bg==='none',texture==='none');
 }
 await page.evaluate(()=>paperlightTheme('dark'));
 assert.equal(await page.evaluate(()=>getComputedStyle(document.getElementById('paperlight-viewport')).backgroundColor),'rgb(29, 32, 31)');
 assert.deepEqual(errors,[]);
 await browser.close();console.log('PASS: Chromium touch dragging, layered curl, exact reverse geometry, commit/cancel, textures, theme.');
})().catch(e=>{console.error(e);process.exit(1)});
