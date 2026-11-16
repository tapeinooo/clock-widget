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
 */
class FontDef(val name: String, val layout: Int, val time: FontRef, val date: FontRef, val japanese: Boolean)

object Fonts {
    private fun sys(name: String, layout: Int, family: String) = FontDef(name, layout, SysFont(family), SysFont(family), true)
    private fun res(name: String, layout: Int, font: Int, japanese: Boolean = false) =
        FontDef(name, layout, ResFont(font), ResFont(font), japanese)

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
        res("SF（Orbitron）", R.layout.widget_orbitron, R.font.orbitron),
        res("SF（Audiowide）", R.layout.widget_audiowide, R.font.audiowide),
        FontDef("ゲームウォッチ（液晶）", R.layout.widget_gamewatch, ResFont(R.font.dseg7), ResFont(R.font.dseg14), false),
        res("デジタル（Share Tech Mono）", R.layout.widget_sharetech, R.font.sharetechmono),
        res("レトロ端末（VT323）", R.layout.widget_vt323, R.font.vt323),
        res("ドット（DotGothic16）", R.layout.widget_dotgothic, R.font.dotgothic16, japanese = true),
        res("8ビット（Press Start 2P）", R.layout.widget_pressstart, R.font.pressstart2p),
    )
}
