package com.example.clockwidget

import android.content.Context
import android.graphics.Typeface

/** 端末に入っているフォント（名前）か、アプリに入れたフォント（res/font）。 */
sealed class FontRef {
    abstract fun typeface(context: Context): Typeface
}

class SysFont(private val family: String) : FontRef() {
    override fun typeface(context: Context): Typeface = Typeface.create(family, Typeface.NORMAL)
}

class ResFont(private val res: Int) : FontRef() {
    override fun typeface(context: Context): Typeface = context.resources.getFont(res)
}

/**
 * フォントの組み合わせ（時刻と日付）。
 * japanese が false のフォントは日本語の字がないので、日付を英語（11.16 MON）で出す。
 * layout は同じフォントを書いたウィジェットの見た目（res/layout/widget_text_*.xml）。フォントごとに1つ（RemoteViews ではフォントを後から変えられないため）。
 */
class FontDef(val name: String, val time: FontRef, val date: FontRef, val japanese: Boolean, val layout: Int) {
    /** 日付を、ホーム画面が進める文字（TextClock）ではなく、こちらが書く文字（TextView）で出すか（日本語の字がないフォントは英語で書くため）。 */
    val dateIsText get() = !japanese
}

object Fonts {
    private fun sys(name: String, family: String, layout: Int) = FontDef(name, SysFont(family), SysFont(family), true, layout)
    private fun res(name: String, layout: Int, font: Int, japanese: Boolean = false) =
        FontDef(name, ResFont(font), ResFont(font), japanese, layout)

    /** 設定には位置を保存するので、足すときは末尾に。 */
    val all = listOf(
        sys("標準", "sans-serif", R.layout.widget_text_sans),
        sys("細め", "sans-serif-light", R.layout.widget_text_light),
        sys("やや太め", "sans-serif-medium", R.layout.widget_text_medium),
        sys("縦長", "sans-serif-condensed", R.layout.widget_text_cond),
        sys("明朝風", "serif", R.layout.widget_text_serif),
        sys("等幅", "monospace", R.layout.widget_text_mono),
        sys("カジュアル", "casual", R.layout.widget_text_casual),
        sys("筆記体", "cursive", R.layout.widget_text_cursive),
        res("SF（Orbitron）", R.layout.widget_text_orbitron, R.font.orbitron),
        res("SF（Audiowide）", R.layout.widget_text_audiowide, R.font.audiowide),
        FontDef("ゲームウォッチ（液晶）", ResFont(R.font.dseg7), ResFont(R.font.dseg14), false, R.layout.widget_text_dseg),
        res("デジタル（Share Tech Mono）", R.layout.widget_text_sharetech, R.font.sharetechmono),
        res("レトロ端末（VT323）", R.layout.widget_text_vt323, R.font.vt323),
        res("ドット（DotGothic16）", R.layout.widget_text_dotgothic, R.font.dotgothic16, japanese = true),
        res("8ビット（Press Start 2P）", R.layout.widget_text_pressstart, R.font.pressstart2p),
        res("横長（Michroma）", R.layout.widget_text_michroma, R.font.michroma),
        res("横長・太め（Krona One）", R.layout.widget_text_kronaone, R.font.kronaone),
        res("横長・極太（Syncopate）", R.layout.widget_text_syncopate, R.font.syncopate),
        res("超横長（Lexend Zetta）", R.layout.widget_text_lexendzetta, R.font.lexendzetta),
        res("横長・丸み（Zen Dots）", R.layout.widget_text_zendots, R.font.zendots),
    )
}
