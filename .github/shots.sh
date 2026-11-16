#!/bin/bash
# エミュレーターで、フォントごとのウィジェットの見た目を撮る（確認用）
set -x
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appwidget grantbind --package com.example.clockwidget
mkdir -p shots
shot() { # 名前 フォント 自動 幅 高さ
  adb shell am start -S -W -n com.example.clockwidget/.WidgetHostActivity --ei font $2 --ei auto $3 --ei w $4 --ei h $5
  sleep 4
  adb exec-out screencap -p > shots/$1.png
}
for f in $(seq 0 19); do shot font${f} $f 1 250 110; done
shot manual_sans 0 0 250 110
shot manual_dseg 10 0 250 110
shot wide_sans 0 1 300 60
shot wide_zen 19 1 300 60
