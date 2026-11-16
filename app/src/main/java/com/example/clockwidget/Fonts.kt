package com.example.clockwidget

import android.graphics.Typeface

class FontDef(val name: String, val family: String, val layout: Int)

object Fonts {
    val all = listOf(
        FontDef("標準", "sans-serif", R.layout.widget_sans),
        FontDef("細め", "sans-serif-light", R.layout.widget_light),
        FontDef("やや太め", "sans-serif-medium", R.layout.widget_medium),
        FontDef("縦長", "sans-serif-condensed", R.layout.widget_cond),
        FontDef("明朝風", "serif", R.layout.widget_serif),
        FontDef("等幅", "monospace", R.layout.widget_mono),
        FontDef("カジュアル", "casual", R.layout.widget_casual),
        FontDef("筆記体", "cursive", R.layout.widget_cursive),
    )

    fun typeface(f: FontDef): Typeface = Typeface.create(f.family, Typeface.NORMAL)
}
