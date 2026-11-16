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
 *
 * 端末のフォントは layout（同じフォントを書いた res/layout/widget_text_*.xml）を持ち、ホーム画面が自分で時刻を進める。
 * アプリに入れたフォントは、ホーム画面がウィジェットの文字に使えない（制限付きで見た目を読み込むため、指定が無視される）ので、
 * layout を持たず、時刻と日付を絵に描いて出す。
 */
class FontDef(val name: String, val time: FontRef, val date: FontRef, val japanese: Boolean, val layout: Int = 0) {
    val system get() = layout != 0
}

object Fonts {
    private fun sys(name: String, family: String, layout: Int) = FontDef(name, SysFont(family), SysFont(family), true, layout)
    private fun res(name: String, font: Int, japanese: Boolean = false) =
        FontDef(name, ResFont(font), ResFont(font), japanese)

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
        res("SF（Orbitron）", R.font.orbitron),
        res("SF（Audiowide）", R.font.audiowide),
        FontDef("ゲームウォッチ（液晶）", ResFont(R.font.dseg7), ResFont(R.font.dseg14), false),
        res("デジタル（Share Tech Mono）", R.font.sharetechmono),
        res("レトロ端末（VT323）", R.font.vt323),
        res("ドット（DotGothic16）", R.font.dotgothic16, japanese = true),
        res("8ビット（Press Start 2P）", R.font.pressstart2p),
        res("横長（Michroma）", R.font.michroma),
        res("横長・太め（Krona One）", R.font.kronaone),
        res("横長・極太（Syncopate）", R.font.syncopate),
        res("超横長（Lexend Zetta）", R.font.lexendzetta),
        res("横長・丸み（Zen Dots）", R.font.zendots),
    )
}
