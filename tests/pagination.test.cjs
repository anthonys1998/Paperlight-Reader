// Runs the production embedded script with a deterministic WebView DOM stand-in.
// This tests navigation/state, not CSS layout or Android rendering.
const {readFileSync}=require('node:fs');
const {join}=require('node:path');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const source=readFileSync(join(__dirname,'../app/src/main/java/com/paperlight/reader/ui/reader/EpubReaderScreen.kt'),'utf8');
function reader({flip=true, overflow=1452, initialFraction=0, chapterIndex=4, lastChapterIndex=8, entryDirection=0, scrolling=false, reduced=false, reduceMotionSetting=false, chapterText=[], sectionLeft=null, largeChapter=false, appearance=null}={}){
  let script=source.split('): String = """')[1].split('""".trimIndent()')[0];
  script=script.replace(/\$\{when \(themeMode\)[\s\S]*?\}\}/g,"'white'")
    .replaceAll("${'$'}",'$');
  for(const [key,value] of Object.entries({fontScale:1,scrollMode:scrolling,widthPx:360,heightPx:600,flip,reduceMotion:reduceMotionSetting,chapterIndex,initialFraction,lastChapterIndex,entryDirection}))
    script=script.replaceAll('${'+key+'}',String(value)).replaceAll('$'+key,String(value));
  const events={},docEvents={},reports=[],turns=[],animations=[],effects=[],timeline=[],taps=[],selections=[],misses=[];
  let now=0, selected=null;
  const elements=[];
  function element(){
    const listeners={};
    const el={style:{setProperty(name,value){this[name]=value;},removeProperty(name){delete this[name];}},clientWidth:360,clientHeight:600,naturalScrollWidth:360+overflow,scrollHeight:4200,scrollLeft:0,scrollTop:0,
      children:[],dataset:{},remove(){},removeAttribute(){},querySelectorAll(){return [];},getElementsByTagName(){return {length:largeChapter?1500:0};},textContent:'',cloneNode(){assert.equal(largeChapter,false,'large chapters must not clone the document');return element();},setAttribute(){},contains(node){return nodes.includes(node)||node===section;},getBoundingClientRect(){return {left:0,top:0};},
      get scrollWidth(){
        const spacer=this.children.find(child=>child.style.display==='block');
        return Math.max(this.naturalScrollWidth,spacer?parseFloat(spacer.style.left)+1:0);
      },
      appendChild(child){this.children.push(child);},addEventListener(k,f){listeners[k]=f;},
      scrollTo({left,top}){this.scrollLeft=Math.max(0,Math.min(left,this.scrollWidth-this.clientWidth));this.scrollTop=top;},
      animate(frames,options){animations.push(this.id);effects.push({frames,options});timeline.push('animate');if(this.id==='paperlight-content')this.scrollLeft=0;return {finished:Promise.resolve(),cancel(){}};}};
    elements.push(el);return el;
  }
  const body=element();body.firstChild=null;
  const nodes=chapterText.map(([data,left],i)=>({data,left,length:data.length,nodeType:3,
    parentElement:{closest(selector){return selector.startsWith('script')?null:i;}}}));
  const section=sectionLeft===null?null:{getClientRects(){return [{left:sectionLeft,top:200,width:20,height:20}];}};
  const highlights=new Map();
  function range(){
    return {selectNodeContents(node){this.startContainer=node;this.startOffset=0;this.endContainer=node;this.endOffset=node.length;},
      getBoundingClientRect(){return this.getClientRects()[0]||{left:0,right:0,top:0,bottom:0,width:0,height:0};},
      setStart(node,offset){this.startContainer=node;this.startOffset=offset;},
      setEnd(node,offset){this.endContainer=node;this.endOffset=offset;},
      getClientRects(){
        if(largeChapter){
          const left=elements.find(x=>x.id==='paperlight-viewport').scrollLeft||0;
          const rectangles=[];
          const end=this.endOffset??this.startOffset+1;
          for(let offset=this.startOffset;offset<end;){
            const line=Math.floor(offset/20),take=Math.min(end-offset,20-offset%20);
            const x=Math.floor(line/10)*360+24+(offset%20)*8-left;
            const y=24+(line%10)*24;
            rectangles.push({left:x,right:x+take*8,top:y,bottom:y+20,width:take*8,height:20});
            offset+=take;
          }
          return rectangles;
        }
        return [{left:this.startContainer.left-(elements.find(x=>x.id==='paperlight-viewport').scrollLeft||0),
          top:240,width:12,height:20}];
      }};
  }
  const document={body,createRange:range,createTreeWalker(){
    let index=-1;
    return {nextNode(){return ++index<nodes.length;},get currentNode(){return nodes[index];}};
  },addEventListener(k,f){docEvents[k]=f;},head:element(),documentElement:element(),getElementById(id){return id==="section 2"?section:null;},getElementsByName(){return [];},createElement:element};
  class Highlight { constructor(...ranges){this.ranges=ranges;} }
  const CSS={highlights};
  const window={paperlightInitialAppearance:appearance,getComputedStyle:()=>({cssText:"",backgroundImage:"none"}),CSS,Highlight,getSelection:()=>selected,innerWidth:360,innerHeight:600,devicePixelRatio:1,matchMedia:()=>({matches:reduced}),addEventListener(k,f){events[k]=f;}};
  const result=vm.runInNewContext(script,{document,window,CSS,Highlight,NodeFilter:{SHOW_TEXT:4,FILTER_REJECT:2,FILTER_ACCEPT:1}, performance:{now:()=>now},Paperlight:{onReaderTap(...x){taps.push(x);},onSelection(...x){selections.push(x);},onSearchMiss(...x){misses.push(x);},onPage(...x){reports.push(x);},onChapterTurn(...x){turns.push(x);timeline.push('chapter');}},setTimeout:(fn,delay)=>setTimeout(fn,delay).unref(),clearTimeout});
  function gesture({time,x=70,y=50,dx=0,dy=0,duration=50,link=false,drag=false}){
    now=time;
    events.touchstart({touches:[{clientX:x,clientY:y}]});
    now+=duration;
    if(drag) events.touchmove({touches:[{clientX:x+dx,clientY:y+dy}]});
    events.touchend({touches:[],changedTouches:[{clientX:x+dx,clientY:y+dy}],
      target:{closest:()=>link}});
  }
  function select(first,start,last,end,quote){
    const r=range();r.setStart(nodes[first],start);r.setEnd(nodes[last],end);
    selected={rangeCount:1,isCollapsed:false,getRangeAt:()=>r,toString:()=>quote};
    docEvents.selectionchange();
  }
  return {elements,window,events,docEvents,reports,turns,animations,effects,timeline,result,taps,gesture,select,selections,misses,highlights,viewport:elements.find(x=>x.id==='paperlight-viewport')};
}
(async()=>{
  for(const flip of [true,false]){
    const r=reader({flip});
    assert.equal(r.reports.at(-1)[1],5);
    for(let i=1;i<5;i++){
      await r.window.paperlightMove(1);
      assert.equal(r.reports.at(-1)[0],i);
      assert.equal(r.viewport.scrollLeft,i*360);
      // Model an unsolicited native scroll reset: page state must survive it.
      r.viewport.scrollLeft=0;
    }
    await r.window.paperlightMove(1);
    assert.deepEqual(r.turns,[[4,1]]);
    await r.window.paperlightMove(-1);
    assert.equal(r.reports.at(-1)[0],3);
    assert.equal(r.viewport.scrollLeft,1080);
    assert.ok(r.animations.every(id=>id==='paperlight-viewport'));
    r.window.paperlightFont(1.2);
    assert.equal(r.reports.at(-1)[0],3);
    r.window.paperlightSetScroll(true);
    assert.equal(r.viewport.scrollTop,2700);
    r.window.paperlightSetScroll(false);
    assert.equal(r.viewport.scrollLeft,1080);
    r.window.innerWidth=400;r.viewport.clientWidth=400;r.viewport.naturalScrollWidth=2012;
    r.events.resize();
    assert.equal(r.viewport.scrollLeft,1200);
    assert.equal(r.reports.at(-1)[0],3);
    for(let i=0;i<3;i++)await r.window.paperlightMove(-1);
    await r.window.paperlightMove(-1);
    assert.deepEqual(r.turns.at(-1),[4,-1]);
  }
  // The last column ends 24px short of the viewport boundary. A clamped
  // final offset would shift the text right by 24px and clip the right margin.
  for(const flip of [true,false]){
    const last=reader({flip,overflow:1416});
    for(let i=0;i<4;i++)await last.window.paperlightMove(1);
    assert.equal(last.viewport.scrollLeft,1440);
    assert.deepEqual(last.reports.at(-1).slice(0,3),[4,5,1]);
    await last.window.paperlightMove(1);
    assert.deepEqual(last.turns,[[4,1]]);
    const restored=reader({flip,overflow:1416,initialFraction:1});
    assert.equal(restored.viewport.scrollLeft,1440);
    restored.window.paperlightSetScroll(true);
    assert.equal(restored.viewport.children.at(-1).style.display,'none');
  }
  for(const direction of [-1,1]){
    for(const flip of [true,false]){
      const edge=reader({initialFraction:direction===1?1:0,flip});
      await edge.window.paperlightMove(direction);
      assert.deepEqual(edge.turns,[[4,direction]]);
      // The Android view animates the outgoing snapshot after the new chapter renders.
      assert.equal(edge.viewport.style['clip-path'],undefined);
      assert.equal(edge.viewport.style.transform,undefined);
      const destination=reader({initialFraction:direction===1?0:1,entryDirection:direction,flip});
      assert.equal(destination.viewport.scrollLeft,direction===1?0:1440);
      destination.window.paperlightRestore(.5);
      assert.equal(destination.viewport.scrollLeft,720);
      assert.equal(destination.reports.at(-1)[2],.5);
    }
  }
  const bookEnd=reader({initialFraction:1,chapterIndex:8});
  await bookEnd.window.paperlightMove(1);
  assert.deepEqual(bookEnd.timeline,[]);
  for(const options of [{reduced:true,initialFraction:1},{scrolling:true,initialFraction:1}]){
    const edge=reader(options);
    await edge.window.paperlightMove(1);
    assert.deepEqual(edge.timeline,['chapter']);
  }
  const accessibilityReduced=reader({reduceMotionSetting:true});
  await accessibilityReduced.window.paperlightMove(1);
  assert.deepEqual(accessibilityReduced.timeline,[],'the accessibility setting should skip page-turn animation');
  assert.equal(accessibilityReduced.reports.at(-1)[2],.25);
  const gestures=reader();
  gestures.gesture({time:100});
  gestures.gesture({time:280,x:75});
  gestures.gesture({time:900,duration:600});
  gestures.gesture({time:1700,dy:60});
  gestures.gesture({time:1900,link:true});
  assert.deepEqual(gestures.taps,[[4,1],[4,2]]);
  gestures.gesture({time:2200,dx:-80});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(gestures.viewport.scrollLeft,360);
  assert.equal(gestures.taps.length,2);
  const scroll=reader({scrolling:true});
  scroll.gesture({time:100});
  scroll.gesture({time:260});
  assert.deepEqual(scroll.taps,[[4,1],[4,2]]);
  const text=reader({chapterText:[['Alpha [one]',25],['Alpha [two]',745]]});
  text.select(0,6,0,11,'[one]');
  assert.deepEqual(text.selections,[[4,6,11,'[one]']]);
  text.gesture({time:1000});
  assert.deepEqual(text.taps,[]);
  text.window.paperlightHighlights([[6,11]]);
  assert.equal(text.highlights.get('paperlight-note').ranges[0].startOffset,6);
  text.window.paperlightHighlights([[6,11,'pink'],[11,16,'green']]);
  assert.equal(text.highlights.get('paperlight-pink').ranges[0].startOffset,6);
  assert.equal(text.highlights.get('paperlight-green').ranges.length,1);
  assert.equal(text.highlights.get('paperlight-note').ranges.length,0);
  text.window.paperlightFind('[two]',0);
  assert.equal(text.viewport.scrollLeft,720);
  assert.equal(text.highlights.get('paperlight-search').ranges[0].endOffset,11);
  text.window.paperlightShowRange(6,11);
  assert.equal(text.viewport.scrollLeft,0);
  text.window.paperlightFind('Alpha',1);
  assert.equal(text.viewport.scrollLeft,720);
  text.window.paperlightFind('missing',0);
  assert.deepEqual(text.misses,[[4]]);
  const drag=reader();
  drag.events.touchstart({touches:[{clientX:130,clientY:50}]});
  drag.events.touchmove({touches:[{clientX:85,clientY:51}]});
  assert.match(drag.viewport.style['clip-path'],/polygon/);
  drag.events.touchcancel();
  assert.equal(drag.viewport.style['clip-path'],undefined);
  drag.gesture({time:100,x:160,dx:-100,drag:true});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(drag.viewport.scrollLeft,360);
  const reverse=reader({initialFraction:.5});
  reverse.events.touchstart({touches:[{clientX:130,clientY:50}]});
  reverse.events.touchmove({touches:[{clientX:210,clientY:50}]});
  assert.match(reverse.viewport.style['clip-path'],/polygon/);
  assert.equal(reverse.viewport.scrollLeft,360);
  reverse.events.touchcancel();
  assert.equal(reverse.viewport.scrollLeft,720);
  reverse.gesture({time:200,x:130,dx:100,drag:true});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(reverse.viewport.scrollLeft,360);
  const slide=reader({flip:false,initialFraction:.5});
  slide.events.touchstart({touches:[{clientX:210,clientY:50}]});
  slide.events.touchmove({touches:[{clientX:130,clientY:50}]});
  assert.equal(slide.viewport.scrollLeft,800);
  slide.events.touchcancel();
  assert.equal(slide.viewport.scrollLeft,720);
  slide.gesture({time:100,x:210,dx:-100,drag:true});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(slide.viewport.scrollLeft,1080);
  const anchored=reader({sectionLeft:740});
  anchored.docEvents.click({
    target:{closest:()=>({getAttribute:()=>"#section%202"})},preventDefault(){},
  });
  assert.equal(anchored.viewport.scrollLeft,720);
  anchored.window.paperlightSetAppearance({size:1.4,lineHeight:1.9,margins:30,font:'sans-serif',alignment:'start'});
  assert.equal(anchored.viewport.scrollLeft,720);
  const savedAppearance={size:1.2,lineHeight:1.9,margins:30,font:'sans-serif',alignment:'start',texture:'linen'};
  const configured=reader({appearance:savedAppearance});
  assert.ok(configured.elements.some(x=>String(x.textContent).includes('line-height:1.9!important')),'first layout uses saved typography');
  configured.events.touchstart({touches:[{clientX:300,clientY:50}]});
  configured.events.touchmove({touches:[{clientX:180,clientY:50}]});
  const activeFold=configured.viewport.style['clip-path'];
  configured.window.paperlightSetAppearance({...savedAppearance});
  assert.equal(configured.viewport.style['clip-path'],activeFold,'unchanged settings must not cancel/reflow a turn');
  const large=reader({largeChapter:true,initialFraction:.5,
    chapterText:[[Array.from({length:500},(_,i)=>'word'+i).join(' '),0]]});
  large.events.touchstart({touches:[{clientX:300,clientY:50}]});
  large.events.touchmove({touches:[{clientX:180,clientY:50}]});
  assert.match(large.viewport.style['clip-path'],/polygon/,'large MOBI-like sections must retain a curl');
  assert.ok(large.elements.some(x=>x.dataset.paperlightSheet==='visible'),'large curl uses a visible-page sheet');
  const paintedRuns=large.elements.filter(x=>x.style.left!==undefined && x.textContent);
  assert.ok(paintedRuns.length>0 && paintedRuns.length<200,'the sheet paints only the visible text runs');
  assert.equal(large.viewport.scrollLeft,720,'building a sheet must preserve the real reading position');
  large.events.touchcancel();
  assert.equal(large.viewport.scrollLeft,720);
  await large.window.paperlightMove(-1);
  assert.equal(large.viewport.scrollLeft,360);
  console.log('PASS: forward/backward, chapter edges, native reset, animation isolation, font, mode, resize, final-page alignment, final-page restore, chapter exit/entry, book bounds, reduced motion, bookmark restore, reader tap gestures, text ranges, search jumps, finger-tracked slide, forward and reverse curl, section anchors, typography reflow, and bounded large-chapter curl');
})().catch(e=>{console.error(e);process.exitCode=1;});
