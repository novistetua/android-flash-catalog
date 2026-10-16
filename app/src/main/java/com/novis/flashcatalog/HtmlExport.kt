package com.novis.flashcatalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Одна HTML-страница со всеми фото и текстами карточек: открывается в любом браузере, без интернета и приложения. */
object HtmlExport {

    private const val HEAD = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Flash Catalog</title><link rel="icon" type="image/png" href="data:image/png;base64,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"><style>
:root{--bg:#f4f6f8;--fg:#222;--card:#fff;--ac:#1565c0;--mut:#667}
@media(prefers-color-scheme:dark){:root{--bg:#15191d;--fg:#e6e9ec;--card:#222830;--ac:#7fb2ff;--mut:#9aa}}
body{margin:0;background:var(--bg);color:var(--fg);font-family:system-ui,sans-serif}
header{background:#1565c0;color:#fff;padding:12px 16px;position:sticky;top:0;z-index:2}
header h1{margin:0 0 8px;font-size:19px}
#q{width:100%;box-sizing:border-box;padding:9px 12px;border:0;border-radius:8px;font-size:16px}
main{max-width:1000px;margin:0 auto;padding:12px;display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:12px}
.c{background:var(--card);border-radius:10px;overflow:hidden;cursor:pointer;box-shadow:0 1px 3px #0003}
.c img{width:100%;height:150px;object-fit:contain;background:#cfd8dc;display:block}
.c div{padding:8px 10px}.c b{display:block;word-break:break-all;font-size:14px}
.c span{display:block;color:var(--mut);font-size:12px;margin-top:3px;max-height:3.6em;overflow:hidden}
#v{position:fixed;inset:0;background:var(--bg);overflow:auto;display:none;z-index:5}
#v .in{max-width:900px;margin:0 auto;padding:12px}
#pg{display:flex;overflow-x:auto;scroll-snap-type:x mandatory;gap:8px}
#pg img{flex:0 0 100%;max-width:100%;max-height:70vh;object-fit:contain;scroll-snap-align:center;background:#cfd8dc;border-radius:8px}
#hint{color:var(--mut);font-size:13px;margin:4px 0}
#nm{font-size:21px;font-weight:700;margin:10px 0 6px;word-break:break-all;user-select:text}
#nt{white-space:pre-wrap;font-size:16px;user-select:text}
button{font-size:15px;padding:9px 14px;border:0;border-radius:8px;background:var(--ac);color:#fff;margin:10px 8px 0 0;cursor:pointer}
</style></head><body><header><h1 id="h">Flash Catalog</h1><input id="q" type="search" placeholder="Поиск по названию и описанию"></header>
<main id="m"></main>
<div id="v"><div class="in"><button id="bk">← Назад</button><button id="cp">Копировать текст</button>
<div id="pg"></div><div id="hint"></div><div id="nm"></div><div id="nt"></div></div></div>
<script>
var D=["""

    private const val TAIL = """];
var m=document.getElementById('m'),q=document.getElementById('q'),v=document.getElementById('v'),pg=document.getElementById('pg');
document.getElementById('h').textContent='Flash Catalog: карточек '+D.length;
function esc(s){return s.replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function draw(){var t=q.value.toLowerCase(),h='';D.forEach(function(c,i){
if(t&&(c.n+' '+c.t).toLowerCase().indexOf(t)<0)return;
h+='<div class="c" onclick="show('+i+')">'+(c.i[0]?'<img src="'+c.i[0]+'">':'')+'<div><b>'+esc(c.n)+'</b><span>'+esc(c.t||'(без описания)')+'</span></div></div>'});
m.innerHTML=h||'<p>Ничего не найдено</p>'}
function show(i){var c=D[i];pg.innerHTML=c.i.map(function(s){return '<img src="'+s+'">'}).join('');
document.getElementById('hint').textContent=c.i.length>1?'Фото: '+c.i.length+' (листай вбок)':'';
document.getElementById('nm').textContent=c.n;document.getElementById('nt').textContent=c.t||'(без описания)';
v.style.display='block';v.scrollTop=0;history.pushState({},'');}
function hide(){v.style.display='none'}
document.getElementById('bk').onclick=function(){history.back()};
window.onpopstate=hide;
document.getElementById('cp').onclick=function(){var s=document.getElementById('nm').textContent+'\n'+document.getElementById('nt').textContent;
if(navigator.clipboard&&navigator.clipboard.writeText)navigator.clipboard.writeText(s);else{var a=document.createElement('textarea');a.value=s;document.body.appendChild(a);a.select();document.execCommand('copy');a.remove()}};
q.oninput=draw;draw();
</script></body></html>"""

    private fun jsStr(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> {}
            '<' -> sb.append("\\u003c")
            ' ' -> sb.append("\\u2028")
            ' ' -> sb.append("\\u2029")
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    private fun jpegDataUri(b: Bitmap, background: Int?): String {
        val src = if (background != null && b.hasAlpha()) {
            val o = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(o)
            c.drawColor(background)
            c.drawBitmap(b, 0f, 0f, null)
            o
        } else b
        val bos = ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    /** Пишет страницу в поток. names = null: все карточки. */
    fun write(ctx: Context, names: List<String>?, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write(HEAD)
        val entries = Storage.list(ctx).filter { names == null || it.name in names }
        var first = true
        for (e in entries) {
            val imgs = ArrayList<String>()
            val main = e.cutout ?: e.photo
            if (main != null) Images.decode(ctx, main, 1280)?.let { imgs.add(jpegDataUri(it, 0xFFCFD8DC.toInt())) }
            for (x in Storage.listExtras(ctx, e.name)) Images.decode(ctx, x.uri, 1280)?.let { imgs.add(jpegDataUri(it, null)) }
            if (!first) w.write(",")
            first = false
            w.write("{\"n\":" + jsStr(e.name) + ",\"t\":" + jsStr(e.note) + ",\"i\":[" + imgs.joinToString(",") { "\"$it\"" } + "]}")
            w.write("\n")
            w.flush()
        }
        w.write(TAIL)
        w.flush()
    }

    fun fileName(names: List<String>?): String =
        if (names != null && names.size == 1) Storage.sanitize(names[0]) + ".html" else "FlashCatalog.html"

    /** Делает файл-страницу и открывает «Поделиться». */
    fun share(act: android.app.Activity, names: List<String>?) {
        android.widget.Toast.makeText(act, "Готовлю страницу…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val f = java.io.File(act.cacheDir, fileName(names))
                f.outputStream().use { write(act, names, it) }
                val uri = androidx.core.content.FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", f)
                act.runOnUiThread {
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/html")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    act.startActivity(android.content.Intent.createChooser(i, "Отправить страницу"))
                }
            } catch (e: Exception) {
                act.runOnUiThread { android.widget.Toast.makeText(act, "Не получилось: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
