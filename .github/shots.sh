#!/bin/bash
# エミュレーターで、フォントごとのウィジェットの見た目を撮る（確認用）
set -x
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appwidget grantbind --package com.example.clockwidget
mkdir -p shots
for f in 0 4 8 10 13 15 18; do
  for a in 0 1; do
    adb shell am start -S -W -n com.example.clockwidget/.WidgetHostActivity --ei font $f --ei auto $a --ei w 250 --ei h 110
    sleep 4
    adb exec-out screencap -p > shots/font${f}_auto${a}.png
  done
done
