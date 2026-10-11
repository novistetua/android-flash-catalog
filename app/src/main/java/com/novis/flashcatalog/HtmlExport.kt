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
<title>Flash Catalog</title><style>
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
