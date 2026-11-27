# 縮小（R8）の設定
# 設定に名前で保存している列挙型の名前は残す
-keepclassmembers enum com.example.clockwidget.** { *; }
# スタックトレースを読みやすく
-keepattributes SourceFile,LineNumberTable
