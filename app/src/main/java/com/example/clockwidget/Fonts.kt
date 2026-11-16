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
 * フォントの組み合わせ。layout は同じフォントを書いたウィジェットの見た目（res/layout/widget_*.xml）。
 * japanese が false のフォントは日本語の字がないので、日付を英語（11.16 MON）で出す。
 *
 * アプリに入れたフォント（res/font）は、ホーム画面側でウィジェットの文字に使えない
 * （ホーム画面は制限付きでアプリの見た目を読み込むため、フォントの指定が無視される）。
 * そのため bitmap のフォントは、時刻と日付を絵にして出し、1分ごとに描き直す。
 */
class FontDef(val name: String, val layout: Int, val time: FontRef, val date: FontRef, val japanese: Boolean) {
    val bitmap get() = layout == R.layout.widget_bitmap
}

object Fonts {
    private fun sys(name: String, layout: Int, family: String) = FontDef(name, layout, SysFont(family), SysFont(family), true)
    private fun res(name: String, font: Int, japanese: Boolean = false) =
        FontDef(name, R.layout.widget_bitmap, ResFont(font), ResFont(font), japanese)

    /** 設定には位置を保存するので、足すときは末尾に。 */
    val all = listOf(
        sys("標準", R.layout.widget_sans, "sans-serif"),
        sys("細め", R.layout.widget_light, "sans-serif-light"),
        sys("やや太め", R.layout.widget_medium, "sans-serif-medium"),
        sys("縦長", R.layout.widget_cond, "sans-serif-condensed"),
        sys("明朝風", R.layout.widget_serif, "serif"),
        sys("等幅", R.layout.widget_mono, "monospace"),
        sys("カジュアル", R.layout.widget_casual, "casual"),
        sys("筆記体", R.layout.widget_cursive, "cursive"),
        res("SF（Orbitron）", R.font.orbitron),
        res("SF（Audiowide）", R.font.audiowide),
        FontDef("ゲームウォッチ（液晶）", R.layout.widget_bitmap, ResFont(R.font.dseg7), ResFont(R.font.dseg14), false),
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
