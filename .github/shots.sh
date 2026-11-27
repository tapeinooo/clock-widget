#!/bin/bash
# エミュレーターで、フォントごとのウィジェットの見た目を撮る（確認用）
set -x
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd locale set-app-locales com.example.clockwidget --locales ja-JP || true
adb shell appwidget grantbind --package com.example.clockwidget
mkdir -p shots
shot() { # 名前 フォント 自動 幅 高さ
  adb shell am start -S -W -n com.example.clockwidget/.WidgetHostActivity --ei font $2 --ei auto $3 --ei w $4 --ei h $5
  sleep 4
  adb exec-out screencap -p > shots/$1.png
}
for f in 0 8 9 10 11 12 13 14 15 16 17 18 19; do shot font${f} $f 1 250 110; done
shot manual_sans 0 0 250 110
shot manual_dseg 10 0 250 110
shot wide_sans 0 1 300 60
shot tall_serif 4 1 160 160
shot tall_mono 5 1 250 110
# 時刻が進むか：そのまま70秒待って撮り直す（文字・絵）
shot tick_text_a 0 1 250 110
sleep 70
adb exec-out screencap -p > shots/tick_text_b.png
shot tick_bitmap_a 10 1 250 110
sleep 70
adb exec-out screencap -p > shots/tick_bitmap_b.png
adb shell dumpsys activity services com.example.clockwidget > shots/services.txt
sleep 70
adb exec-out screencap -p > shots/tick_b.png
