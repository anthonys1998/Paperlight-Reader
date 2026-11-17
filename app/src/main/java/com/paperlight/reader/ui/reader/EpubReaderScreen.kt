package com.paperlight.reader.ui.reader

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.epub.EpubBook
import com.paperlight.reader.epub.EpubBookParser
import com.paperlight.reader.epub.EpubChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubReaderScreen(
    book: BookEntity,
    onBack: () -> Unit,
    onProgress: (Float) -> Unit
) {
    val context = LocalContext.current
    val mainView = LocalView.current
    val latestProgress by rememberUpdatedState(onProgress)
    val preferences = remember { context.getSharedPreferences("reader", 0) }
    var scrollMode by remember { mutableStateOf(preferences.getBoolean("scrollMode", false)) }
    var flip by remember { mutableStateOf(preferences.getBoolean("pageFlip", true)) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var reportedChapter by remember(book.id) { mutableIntStateOf(-1) }
    var fraction by remember(book.id) { mutableFloatStateOf(0f) }
    var parsed by remember(book.id) { mutableStateOf<EpubBook?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    var chapterIndex by remember(book.id) { mutableIntStateOf(0) }
    var page by remember(book.id) { mutableIntStateOf(0) }
    var pageCount by remember(book.id) { mutableIntStateOf(1) }
    var initialChapterFraction by remember(book.id) { mutableFloatStateOf(0f) }
    var isRestoringPosition by remember(book.id) { mutableStateOf(true) }
    var themeMode by remember { mutableIntStateOf(0) } // paper, white, dark
    var showContents by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    var fontScale by remember { mutableFloatStateOf(1f) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadedChapterPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(book.id) {
        runCatching {
            withContext(Dispatchers.IO) {
                EpubBookParser.open(File(book.localPath), File(context.filesDir, "epub/${book.id}"))
            }
        }.onSuccess { publication ->
            if (book.progress > 0f) {
                val scaled = book.progress * publication.chapters.size
                chapterIndex = scaled.toInt().coerceIn(0, publication.chapters.lastIndex)
                initialChapterFraction = (scaled - chapterIndex).coerceIn(0f, 1f)
            }
            parsed = publication
        }.onFailure { error = it.message ?: "Could not open EPUB" }
    }

    val publication = parsed
    if (publication == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (error == null) CircularProgressIndicator() else Text(error ?: "Unable to open book")
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBack) { Text("Back to library") }
        }
        return
    }

    val savedPosition = if (chapterIndex == publication.chapters.lastIndex && fraction >= 1f) 1f
        else (chapterIndex + fraction * .9999f) / publication.chapters.size
    LaunchedEffect(reportedChapter, chapterIndex, savedPosition) {
        if (reportedChapter == chapterIndex) latestProgress(savedPosition)
    }
    val displayProgress = if (reportedChapter == chapterIndex) savedPosition else book.progress
    val chapterTitles = remember(publication) {
        val titles = publication.tableOfContents.associate { it.chapterIndex to it.title }
        publication.chapters.mapIndexed { index, entry -> titles[index] ?: entry.title }
    }
    val chapter = publication.chapters[chapterIndex]
    val background = when (themeMode) { 1 -> Color.White; 2 -> Color(0xFF171717); else -> Color(0xFFF4EBD8) }
    val foreground = if (themeMode == 2) Color(0xFFE5E0D5) else Color(0xFF29251F)
    val themeName = listOf("Paper", "White", "Dark")[themeMode]


    Column(Modifier.fillMaxSize().background(background)) {
        Row(
            Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = foreground) }
            Column(Modifier.weight(1f)) {
                Text(publication.title, color = foreground, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(chapterTitles[chapterIndex], color = foreground.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            IconButton(onClick = { showContents = true }) {
                Icon(Icons.Rounded.List, contentDescription = "Table of contents", tint = foreground)
            }
            Box {
                IconButton(onClick = { showThemes = true }) {
                    Icon(Icons.Rounded.Brightness4, contentDescription = "Reading theme: $themeName", tint = foreground)
                }
                DropdownMenu(expanded = showThemes, onDismissRequest = { showThemes = false }) {
                    listOf("Paper", "White", "Dark").forEachIndexed { index, label ->
                        DropdownMenuItem(
                            text = { Text(if (themeMode == index) "✓ $label" else label) },
                            onClick = {
                                themeMode = index
                                webView?.evaluateJavascript("window.paperlightTheme('${label.lowercase()}')", null)
                                showThemes = false
                            }
                        )
                    }
                }
            }
            IconButton(onClick = { fontScale = (fontScale - .1f).coerceAtLeast(.7f); webView?.evaluateJavascript("window.paperlightFont($fontScale)", null) }) {
                Icon(Icons.Rounded.TextDecrease, contentDescription = "Decrease text size", tint = foreground)
            }
            IconButton(onClick = { fontScale = (fontScale + .1f).coerceAtMost(1.8f); webView?.evaluateJavascript("window.paperlightFont($fontScale)", null) }) {
                Icon(Icons.Rounded.TextIncrease, contentDescription = "Increase text size", tint = foreground)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            listOf(false to "Pages", true to "Scroll").forEach { (scroll, label) ->
                FilterChip(
                    selected = scrollMode == scroll,
                    onClick = {
                        if (scrollMode != scroll) {
                            scrollMode = scroll
                            preferences.edit().putBoolean("scrollMode", scroll).apply()
                            webView?.evaluateJavascript("window.paperlightSetScroll?.($scroll)", null)
                        }
                    },
                    label = { Text(label) }
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            if (!scrollMode) TextButton(
                onClick = {
                    flip = !flip
                    preferences.edit().putBoolean("pageFlip", flip).apply()
                    webView?.evaluateJavascript("window.paperlightSetFlip?.($flip)", null)
                }
            ) { Text(if (flip) "Transition: Page flip" else "Transition: Simple slide", color = foreground) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it }) {
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = false
                        settings.allowFileAccess = true // EPUB-relative images and stylesheets are local files.
                        settings.allowContentAccess = false
                        settings.allowFileAccessFromFileURLs = false
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.domStorageEnabled = false
                        settings.blockNetworkLoads = true
                        isHorizontalScrollBarEnabled = false
                        isVerticalScrollBarEnabled = false
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        webChromeClient = WebChromeClient()
                        addJavascriptInterface(object {
                            @JavascriptInterface fun onPage(current: Int, total: Int, offset: Float, sourceChapter: Int, scrolling: Boolean) {
                                mainView.post {
                                    if (sourceChapter != chapterIndex || scrolling != scrollMode) return@post
                                    page = current.coerceAtLeast(0)
                                    pageCount = total.coerceAtLeast(1)
                                    fraction = offset.coerceIn(0f, 1f)
                                    reportedChapter = sourceChapter
                                }
                            }
                            @JavascriptInterface fun onChapterTurn(sourceChapter: Int, direction: Int) {
                                mainView.post {
                                    if (sourceChapter != chapterIndex || direction !in listOf(-1, 1)) return@post
                                    val target = chapterIndex + direction
                                    if (target !in publication.chapters.indices) return@post
                                    initialChapterFraction = if (direction < 0) 1f else 0f
                                    isRestoringPosition = true
                                    reportedChapter = -1
                                    chapterIndex = target
                                }
                            }
                        }, "Paperlight")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                val fraction = if (isRestoringPosition) initialChapterFraction else 0f
                                view.evaluateJavascript(buildReaderScript(themeMode, fontScale, fraction, scrollMode, chapterIndex, view.width, view.height, flip = flip)) {
                                    isRestoringPosition = false
                                    initialChapterFraction = 0f
                                }
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                if (url.startsWith("file:", ignoreCase = true)) {
                                    val targetPath = runCatching { Uri.parse(url).path?.let { File(it).canonicalPath } }.getOrNull()
                                    val targetChapter = publication.chapters.indexOfFirst { it.file.canonicalPath == targetPath }
                                    if (targetChapter >= 0 && targetChapter != chapterIndex) {
                                        reportedChapter = -1
                                        chapterIndex = targetChapter
                                        page = 0
                                        pageCount = 1
                                        initialChapterFraction = 0f
                                        isRestoringPosition = false
                                        return true
                                    }
                                    return targetPath != publication.chapters[chapterIndex].file.canonicalPath
                                }
                                return true
                            }
                        }
                        webView = this
                    }
                },
                onRelease = { view ->
                    view.removeJavascriptInterface("Paperlight")
                    view.destroy()
                },
                update = { view ->
                    webView = view
                    if (viewport.width > 0 && viewport.height > 0 && loadedChapterPath != chapter.file.absolutePath) {
                        loadedChapterPath = chapter.file.absolutePath
                        view.post {
                            if (view.width > 0 && view.height > 0 && loadedChapterPath == chapter.file.absolutePath) {
                                loadChapter(view, chapter)
                            }
                        }
                    }
                }
            )

        }
        Row(
            Modifier.fillMaxWidth().background(background).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewNext(webView, -1) }) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous", tint = foreground)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!scrollMode && reportedChapter == chapterIndex) {
                    Text("Chapter page ${page + 1} / $pageCount", color = foreground,
                        style = MaterialTheme.typography.labelMedium)
                }
                Text("${(displayProgress * 100).toInt()}% of book", color = foreground,
                    style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(
                    progress = { displayProgress },
                    modifier = Modifier.fillMaxWidth(.82f).padding(top = 4.dp)
                )
            }
            IconButton(onClick = { viewNext(webView, 1) }) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next", tint = foreground)
            }
        }
    }
    if (showContents) {
        ChapterList(
            titles = chapterTitles,
            currentChapter = chapterIndex,
            background = background,
            foreground = foreground,
            onDismiss = { showContents = false },
            onSelect = { target ->
                showContents = false
                if (target != chapterIndex) {
                    reportedChapter = -1
                    initialChapterFraction = 0f
                    isRestoringPosition = true
                    chapterIndex = target
                }
            }
        )
    }
}

@Composable
private fun ChapterList(
    titles: List<String>,
    currentChapter: Int,
    background: Color,
    foreground: Color,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val nearbyChapter = (currentChapter - 3).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = nearbyChapter)
    val scope = rememberCoroutineScope()
    val divider = foreground.copy(alpha = .16f)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val drawerWidth = minOf(maxWidth * .85f, 440.dp)
            Box(
                Modifier.matchParentSize().clickable(
                    onClickLabel = "Close chapters",
                    onClick = onDismiss,
                )
            )
            Surface(
                modifier = Modifier.align(Alignment.CenterStart).width(drawerWidth).fillMaxHeight(),
                color = background,
                contentColor = foreground,
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Close chapters")
                        }
                        Text("Chapters", style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider(color = divider)
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        itemsIndexed(titles, key = { index, _ -> index }) { index, title ->
                            val current = index == currentChapter
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (current) foreground.copy(alpha = .08f) else background)
                                    .semantics { selected = current }
                                    .clickable(role = Role.Button) { onSelect(index) }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (current) {
                                    Icon(Icons.Rounded.Check, contentDescription = "Current chapter", modifier = Modifier.size(18.dp))
                                }
                            }
                            HorizontalDivider(color = divider)
                        }
                    }
                    HorizontalDivider(color = divider)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = "Show current chapter in list") {
                                scope.launch { listState.scrollToItem(nearbyChapter) }
                            }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            titles[currentChapter],
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("${currentChapter + 1}/${titles.size}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private fun loadChapter(webView: WebView, chapter: EpubChapter, html: String = chapterHtml(chapter)) {
    val base = chapter.file.parentFile?.let { Uri.fromFile(it).toString().trimEnd('/') + "/" } ?: "file:///"
    webView.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
}

private fun chapterHtml(chapter: EpubChapter): String {
    // Publication scripting is not supported in this milestone. Strip scripts and inline
    // event handlers before loading so an imported book cannot execute arbitrary JS.
    val html = chapter.file.readText(Charsets.UTF_8)
        .trimStart('﻿', ' ', '\t', '\r', '\n')
        .replaceFirst(Regex("""^<\?xml[^>]*>\s*""", RegexOption.IGNORE_CASE), "")
        .replaceFirst(Regex("""^<!doctype[^>]*>\s*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""<meta\b(?=[^>]*\bname\s*=\s*(?:"viewport"|'viewport'|viewport\b))[^>]*>""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<(iframe|object|embed)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("(href|src)\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\2", RegexOption.IGNORE_CASE), "$1=\"#\"")
    val viewport = """<meta name="viewport" content="width=device-width, initial-scale=1">"""
    val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
    val document = if (head == null) viewport + html else
        html.substring(0, head.range.last + 1) + viewport + html.substring(head.range.last + 1)
    return "<!doctype html>" + document
}

private fun viewNext(view: WebView?, direction: Int) {
    view?.evaluateJavascript("window.paperlightMove($direction)", null)
}

private fun buildReaderScript(themeMode: Int, fontScale: Float, initialFraction: Float, scrollMode: Boolean, chapterIndex: Int, widthPx: Int, heightPx: Int, flip: Boolean = false): String = """
(function(){
 const palettes={paper:{bg:'#F4EBD8',fg:'#29251F',link:'#405D85'},white:{bg:'#FFFFFF',fg:'#222222',link:'#315D91'},dark:{bg:'#171717',fg:'#E5E0D5',link:'#B7C9E8'}};
 let mode=${when (themeMode) { 1 -> "'white'"; 2 -> "'dark'"; else -> "'paper'" }};
 if(document.getElementById('paperlight-style')) return null;
 const style=document.createElement('style'); style.id='paperlight-style';
 style.textContent=`html{margin:0!important;padding:0!important;overflow:hidden!important;background:${'$'}{palettes[mode].bg}!important;}
 body{box-sizing:border-box!important;margin:24px!important;padding:0!important;overflow:visible!important;color:${'$'}{palettes[mode].fg}!important;background:transparent!important;font-size:${fontScale}em!important;line-height:1.65!important;font-family:serif!important;}
 p,li{orphans:2;widows:2;} img,svg,video{max-width:100%!important;max-height:90%!important;object-fit:contain!important;break-inside:avoid!important;} h1,h2,h3,h4{break-after:avoid!important;} a{color:${'$'}{palettes[mode].link}!important;}`;
 document.head.appendChild(style);
 let scrolling=$scrollMode;
 // Navigation owns the page index; animated scroll geometry must never own it.
 let currentPage=0;
 let pageTotal=1;
 let pageStride=1;
 let turning=false;
 let layoutWidth=0,layoutHeight=0;
 let activeAnimation=null;
 let motionGeneration=0;
 function cancelMotion(){
   motionGeneration++;
   if(activeAnimation) activeAnimation.cancel();
   activeAnimation=null;
   turning=false;
 }
 // Give the viewport and text columns separate boxes so publisher body styles
 // cannot change the size of either the scroll area or the page fragments.
 const viewport=document.createElement('div');
 const content=document.createElement('div');
 const endSpacer=document.createElement('div');
 endSpacer.setAttribute('aria-hidden','true');
 endSpacer.style.cssText='display:none;position:absolute;top:0;width:1px;height:1px;pointer-events:none';
 viewport.id='paperlight-viewport';
 content.id='paperlight-content';
 while(document.body.firstChild) content.appendChild(document.body.firstChild);
 viewport.appendChild(content);
 viewport.appendChild(endSpacer);
 document.body.appendChild(viewport);
 const layout=document.createElement('style');
 document.head.appendChild(layout);
 function configureMode(){
   const ratio=window.devicePixelRatio||1;
   const width=window.innerWidth||Math.round($widthPx/ratio);
   const height=window.innerHeight>48?window.innerHeight:Math.round($heightPx/ratio);
   layoutWidth=width; layoutHeight=height;
   const margin=Math.min(24,Math.floor(Math.min(width,height)/4));
   const contentWidth=Math.max(1,width-2*margin);
   const contentHeight=Math.max(1,height-2*margin);
   layout.textContent=`
     html > body{margin:0!important;padding:0!important;display:block!important;}
     #paperlight-viewport{
       position:fixed!important;inset:0 auto auto 0!important;
       width:${'$'}{width}px!important;height:${'$'}{height}px!important;
       overflow-x:hidden!important;overflow-y:${'$'}{scrolling?'auto':'hidden'}!important;
       scrollbar-width:none;overscroll-behavior:none;
       overflow-anchor:none!important;scroll-behavior:auto!important;
       background:${'$'}{palettes[mode].bg};
     }
     #paperlight-content{
       box-sizing:content-box!important;
       width:${'$'}{contentWidth}px!important;
       height:${'$'}{scrolling?'auto':contentHeight+'px'}!important;
       margin:${'$'}{margin}px!important;
       columns:${'$'}{scrolling?'auto':contentWidth+'px 1'}!important;
       column-gap:${'$'}{2*margin}px!important;
       column-fill:auto!important;overflow-wrap:anywhere!important;
     }
     #paperlight-content img,#paperlight-content svg,#paperlight-content video{
       max-height:${'$'}{contentHeight}px!important;
     }
   `;
   measurePages();
 }
 function measurePages(){
   endSpacer.style.display='none';
   if(scrolling) return;
   pageStride=Math.max(1,viewport.clientWidth);
   const overflow=Math.max(0,viewport.scrollWidth-viewport.clientWidth);
   pageTotal=Math.round(overflow/pageStride)+1;
   // Column overflow can end inside the final page's right margin.
   // Extend scrollable space to the page boundary without changing text layout.
   if(pageTotal>1){
     endSpacer.style.left=(pageTotal*pageStride-1)+'px';
     endSpacer.style.display='block';
   }
   currentPage=Math.min(currentPage,pageTotal-1);
 }
 function step(){return Math.max(1,scrolling?viewport.clientHeight*.9:pageStride);}
 function maxOffset(){
   return Math.max(0,scrolling?viewport.scrollHeight-viewport.clientHeight:(pageTotal-1)*pageStride);
 }
 function position(){return scrolling?viewport.scrollTop:viewport.scrollLeft;}
 function fraction(){
   if(!scrolling) return page()/Math.max(1,total()-1);
   return Math.min(1,Math.max(0,position()/Math.max(1,maxOffset())));
 }
 function total(){return scrolling?Math.round(maxOffset()/step())+1:pageTotal;}
 function page(){return scrolling?Math.min(total()-1,Math.round(position()/step())):currentPage;}
 function moveTo(offset,behavior){
   viewport.scrollTo({left:scrolling?0:offset,top:scrolling?offset:0,behavior});
 }
 function report(){if(turning) return; Paperlight.onPage(page(),total(),fraction(),$chapterIndex,scrolling);}
 function restore(progress){
   currentPage=Math.max(0,Math.min(total()-1,Math.round(progress*(total()-1))));
   moveTo(scrolling?progress*maxOffset():currentPage*step(),'instant');
   report();
 }
 window.paperlightSetScroll=function(next){
   const progress=fraction();
   cancelMotion();
   scrolling=next;
   configureMode();
   restore(progress);
 };
 configureMode();
 let flip=$flip;
 window.paperlightSetFlip=function(enabled){flip=enabled;};
 window.paperlightMove=async function(direction){
   if(turning) return;
   const nextPage=page()+direction;
   // A partial trailing gutter can extend past the final column without
   // adding another page. Use the same page boundaries as the counter.
   const atEdge=scrolling
     ? (direction>0?position()>=maxOffset()-1:position()<=1)
     : (nextPage<0||nextPage>=total());
   if(atEdge){Paperlight.onChapterTurn($chapterIndex,direction);return;}
   const target=scrolling?position()+direction*step():nextPage*step();
   if(scrolling){moveTo(target,'smooth');return;}
   currentPage=nextPage;
   const generation=++motionGeneration;
   if(window.matchMedia('(prefers-reduced-motion: reduce)').matches){
     moveTo(target,'instant');report();return;
   }
   turning=true;
   // Animate the fixed-size viewport, never the multi-column chapter. Rotating
   // the chapter changes its overflow bounds and can clamp scrollLeft to zero.
   const origin=direction>0?'left center':'right center';
   async function animate(frames,options){
     if(!viewport.animate) return;
     const animation=viewport.animate(frames,options);
     activeAnimation=animation;
     await animation.finished.catch(()=>{});
     if(activeAnimation===animation) activeAnimation=null;
   }
   try {
     if(flip){
       await animate([
         {transform:'none',transformOrigin:origin,filter:'brightness(1)'},
         {transform:'perspective(950px) rotateY('+direction*18+'deg)',transformOrigin:origin,filter:'brightness(.82)'}
       ],{duration:110,easing:'ease-in'});
       if(generation!==motionGeneration) return;
     }
     moveTo(target,'instant');
     await animate(flip?[
       {transform:'perspective(950px) rotateY('+(-direction*18)+'deg)',transformOrigin:origin,filter:'brightness(.82)'},
       {transform:'none',transformOrigin:origin,filter:'brightness(1)'}
     ]:[
       {transform:'translateX('+direction*16+'px)',opacity:.7},
       {transform:'none',opacity:1}
     ],{duration:160,easing:'ease-out'});
   } finally {
     if(generation===motionGeneration){
       // Reassert the logical destination after the visual effect completes.
       moveTo(currentPage*step(),'instant');
       turning=false;
       report();
     }
   }
 };
 let touchStart;
 window.addEventListener('touchstart',event=>{
   touchStart=event.touches.length===1 ? event.touches[0] : null;
 },{passive:true});
 window.addEventListener('touchend',event=>{
   if(!touchStart||scrolling||event.touches.length||window.getSelection()?.toString()) return;
   const touch=event.changedTouches[0];
   const dx=touch.clientX-touchStart.clientX;
   const dy=touch.clientY-touchStart.clientY;
   touchStart=null;
   if(Math.abs(dx)>45&&Math.abs(dx)>Math.abs(dy)*1.4) window.paperlightMove(dx<0?1:-1);
 },{passive:true});
 window.paperlightTheme=function(next){mode=palettes[next]?next:'paper';viewport.style.background=palettes[mode].bg;document.documentElement.style.setProperty('background',palettes[mode].bg,'important');document.body.style.setProperty('color',palettes[mode].fg,'important');style.textContent=style.textContent.replace(/a\{color:[^}]+\}/,`a{color:${'$'}{palettes[mode].link}!important;}`);};
 window.paperlightFont=function(scale){
   const progress=fraction();
   cancelMotion();
   document.body.style.setProperty('font-size',scale+'em','important');
   measurePages();
   restore(progress);
 };
 let reportTimer;
 window.addEventListener('resize',()=>{
   if(window.innerWidth===layoutWidth&&window.innerHeight===layoutHeight) return;
   const progress=fraction();
   cancelMotion();
   configureMode();
   restore(progress);
 });
 viewport.addEventListener('scroll',()=>{
   clearTimeout(reportTimer);
   reportTimer=setTimeout(report,120);
 });
 restore($initialFraction);
})();
""".trimIndent()
