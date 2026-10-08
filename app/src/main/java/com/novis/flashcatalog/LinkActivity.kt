package com.novis.flashcatalog

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Принимает ссылку fcat://r?p=… со страницы-посредника и передаёт данные в экран обмена. */
class LinkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = intent?.data?.getQueryParameter("p")
        if (p != null && Pack.unwrapQr(p) != null) {
            startActivity(Intent(this, ExchangeActivity::class.java).putExtra(ExchangeActivity.EXTRA_PAYLOAD, p))
        }
        finish()
    }
}
