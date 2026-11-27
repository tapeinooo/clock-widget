package com.example.stagerecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.concurrent.thread

/** 自動の更新確認をした時刻（アプリが動いている間だけ覚える） */
private var lastAutoCheck = 0L

/** はじめの画面：録り始める・設定・録ったものの一覧。 */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var startBtn: MaterialButton
    private lateinit var overlayBtn: MaterialButton
    private lateinit var listBox: LinearLayout
    private lateinit var selectBtn: MaterialButton
    private lateinit var deleteSelectedBtn: MaterialButton
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateBannerText: TextView
    private lateinit var versionText: TextView
    private var available: Updater.Info? = null
    private var checking = false
    private var items: List<Rec> = emptyList()
    private var selecting = false
    private val selected = mutableSetOf<Uri>()
    private val videoOnly = mutableListOf<View>()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        // 通知の許可は無くても録れる
        if (neededPermissions().all { granted(it) || it == Manifest.permission.POST_NOTIFICATIONS }) startRecording()
        else Toast.makeText(this, "マイク" + (if (prefs.video) "とカメラ" else "") + "の許可が必要です", Toast.LENGTH_LONG).show()
    }

    private val deleteRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16f).toInt()
            setPadding(p, p, p, p)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        fun label(t: String, top: Float = 16f) = TextView(this).apply {
            text = t
            textSize = 15f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, dp(top).toInt(), 0, dp(4f).toInt())
        }.also { root.addView(it) }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
        })

        // 新しい版があるときだけ出す
        updateBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = dp(12f).toInt()
            setPadding(p, dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12f)
                setColor(0xFF1E3A5F.toInt())
            }
            visibility = View.GONE
        }
        updateBannerText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
        }
        updateBanner.addView(updateBannerText, LinearLayout.LayoutParams(0, -2, 1f))
        updateBanner.addView(MaterialButton(this).apply {
            text = "更新"
            setOnClickListener { available?.let { startUpdate(it) } }
        })
        root.addView(updateBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f).toInt() })

        startBtn = MaterialButton(this).apply {
            textSize = 20f
            setOnClickListener { onStartPressed() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(-1, dp(72f).toInt()).apply { topMargin = dp(16f).toInt() })

        // 録音か録画か
        label("録り方")
        root.addView(radio(listOf("録音（音声のみ）", "録画（映像＋音声）"), if (prefs.video) 1 else 0) {
            prefs.video = it == 1
            showVideoOptions()
            updateStart()
        })
        videoOnly += label("カメラ")
        videoOnly += radio(listOf("前面（自分を撮る）", "背面"), if (prefs.front) 0 else 1) { prefs.front = it == 0 }
            .also { root.addView(it) }
        videoOnly += label("画質")
        val sizeHint = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF999999.toInt())
        }
        fun showSizeHint() {
            sizeHint.text = if (prefs.saving) {
                // 映像＋音声（約0.13Mbps）で1時間あたり
                val mb = (prefs.savingBitRate() + 130_000) / 8.0 * 3600 / 1_000_000
                val est = if (mb >= 1000) "%.1fGB".format(mb / 1000) else "%.0fMB".format(mb)
                "1時間でおよそ $est（話す姿を撮るには十分。動きの多い場面は粗くなります）"
            } else {
                "端末まかせのきれいさ。1時間でおよそ " + when (prefs.quality) {
                    0 -> "1〜2GB"
                    1 -> "3〜4GB"
                    else -> "5〜8GB"
                }
            }
        }
        videoOnly += radio(listOf("480p", "720p", "1080p"), prefs.quality) { prefs.quality = it; showSizeHint() }
            .also { root.addView(it) }
        videoOnly += label("容量", 8f)
        videoOnly += radio(listOf("標準", "節約（小さく保存）"), if (prefs.saving) 1 else 0) {
            prefs.saving = it == 1
            showSizeHint()
        }.also { root.addView(it) }
        videoOnly += sizeHint.also { root.addView(it) }
        showSizeHint()
        videoOnly += MaterialSwitch(this).apply {
            text = "録っている間の画面に映像を映す"
            isChecked = prefs.showPreview
            setOnCheckedChangeListener { _, on -> prefs.showPreview = on }
        }.also { root.addView(it) }
        showVideoOptions()

        // 持ち時間
        val targetLabel = label("")
        fun showTarget() {
            targetLabel.text = if (prefs.targetMin == 0) "持ち時間: なし"
            else "持ち時間: ${prefs.targetMin}分（残りわずかで橙、超えたら赤）"
        }
        root.addView(SeekBar(this).apply {
            max = 120
            progress = prefs.targetMin
            setOnSeekBarChangeListener(onSeek { prefs.targetMin = it; showTarget() })
        })
        showTarget()

        // カメラのあたりの時間表示
        label("画面上部（カメラのあたり）の時間表示", 24f)
        root.addView(TextView(this).apply {
            text = "録っている間、他のアプリの上にも経過時間を出します。指で動かせます（位置は覚えます）。触ると録音中の画面に戻ります。"
            textSize = 13f
            setTextColor(0xFF999999.toInt())
        })
        overlayBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        root.addView(overlayBtn)
        val sizeLabel = label("", 8f)
        fun showSize() { sizeLabel.text = "文字の大きさ: ${prefs.overlaySp}" }
        root.addView(SeekBar(this).apply {
            max = 48 - 12
            progress = prefs.overlaySp - 12
            setOnSeekBarChangeListener(onSeek { prefs.overlaySp = it + 12; showSize() })
        })
        showSize()
        root.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "位置をカメラの真下に戻す"
            setOnClickListener {
                prefs.resetOverlayPos()
                Toast.makeText(this@MainActivity, "次に表示するときからカメラの真下に出ます", Toast.LENGTH_SHORT).show()
            }
        })

        // 一覧
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24f).toInt(), 0, 0)
        }
        head.addView(TextView(this).apply {
            text = "録ったもの"
            textSize = 15f
            setTextColor(0xFFBBBBBB.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        selectBtn = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "選んで削除"
            setOnClickListener { setSelecting(!selecting) }
        }
        head.addView(selectBtn)
        root.addView(head)
        root.addView(TextView(this).apply {
            text = "触ると切り出し編集。長押しで共有・名前の変更など。"
            textSize = 13f
            setTextColor(0xFF999999.toInt())
        })
        deleteSelectedBtn = MaterialButton(this).apply {
            setBackgroundColor(0xFFD32F2F.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            visibility = View.GONE
            setOnClickListener { confirmDelete(items.filter { it.uri in selected }) }
        }
        root.addView(deleteSelectedBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f).toInt() })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        // 版と更新
        label("アプリの版", 32f)
        versionText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(versionText)
        root.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "更新を確認"
            setOnClickListener { checkUpdate(manual = true) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f).toInt() })
        showVersion(null)

        setContentView(scroll)
        showProblems()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RecState.phase.combine(RecState.savedCount) { a, b -> a to b }.collect { updateStart(); refreshList() }
                }
                RecState.lastError.collect { msg ->
                    if (msg != null) {
                        RecState.lastError.value = null
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("始められませんでした")
                            .setMessage(msg)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayBtn.text = if (Settings.canDrawOverlays(this)) "表示の許可：済み" else "表示を許可する（他のアプリの上に重ねる）"
        updateStart()
        refreshList()
        // 開くたびに自動で確認（30分に1回まで）
        if (System.currentTimeMillis() - lastAutoCheck > 30 * 60_000L) checkUpdate(manual = false)
    }

    /** 前回落ちた・うまく動かなかった記録があれば見せる（コピーして送ってもらう用） */
    private fun showProblems() {
        val text = CrashLog.take(this) ?: return
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            val p = dp(16f).toInt()
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this)
            .setTitle("前回うまく動かなかったときの記録")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("コピー") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("記録", text))
                Toast.makeText(this, "コピーしました。チャットに貼って送ってください", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    // ---- 更新 ----

    private fun showVersion(latest: Updater.Info?) {
        val cur = "今の版: ${BuildConfig.VERSION_NAME}（ビルド ${Updater.current}）"
        versionText.text = when {
            latest == null -> cur
            latest.versionCode > Updater.current -> "$cur\n新しい版: ビルド ${latest.versionCode}"
            else -> "$cur\n最新です"
        }
        val newer = latest != null && latest.versionCode > Updater.current
        available = if (newer) latest else null
        updateBanner.visibility = if (newer) View.VISIBLE else View.GONE
        if (newer) updateBannerText.text = "新しい版があります（ビルド ${latest!!.versionCode}）"
    }

    private fun checkUpdate(manual: Boolean) {
        if (checking) return
        checking = true
        if (!manual) lastAutoCheck = System.currentTimeMillis()
        if (manual) versionText.text = "確認しています…"
        thread {
            val info = Updater.fetch()
            runOnUiThread {
                checking = false
                if (isDestroyed) return@runOnUiThread
                showVersion(info)
                if (!manual) return@runOnUiThread
                when {
                    info == null -> Toast.makeText(this, "確認できませんでした（通信を確かめてください）", Toast.LENGTH_LONG).show()
                    info.versionCode > Updater.current -> startUpdate(info)
                    else -> Toast.makeText(this, "最新です", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startUpdate(info: Updater.Info) {
        if (RecState.active) {
            Toast.makeText(this, "録っている間は更新できません。止めてからにしてください", Toast.LENGTH_LONG).show()
            return
        }
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("インストールの許可")
                .setMessage("このアプリから新しい版を入れるには、次の画面で「この提供元のアプリを許可」をオンにして、戻ってきてからもう一度「更新」を押してください。")
                .setPositiveButton("設定を開く") { _, _ -> Updater.openInstallPermission(this) }
                .setNegativeButton("やめる", null)
                .show()
            return
        }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val box = LinearLayout(this).apply {
            val p = dp(24f).toInt()
            setPadding(p, p, p, 0)
            addView(bar, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("新しい版を取得しています…")
            .setView(box)
            .setCancelable(false)
            .show()
        thread {
            val result = runCatching { Updater.download(this, info) { p -> runOnUiThread { bar.progress = p } } }
            runOnUiThread {
                dialog.dismiss()
                result.onSuccess { Updater.install(this, it) }
                    .onFailure { Toast.makeText(this, "取得できませんでした: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun radio(items: List<String>, checked: Int, onPick: (Int) -> Unit): RadioGroup =
        RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            items.forEachIndexed { i, t ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = t
                    textSize = 15f
                    isChecked = i == checked
                    setOnClickListener { onPick(i) }
                })
            }
        }

    private fun onSeek(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = f(p)
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    }

    private fun showVideoOptions() {
        videoOnly.forEach { it.visibility = if (prefs.video) View.VISIBLE else View.GONE }
    }

    private fun updateStart() {
        if (!::startBtn.isInitialized) return
        startBtn.text = when {
            RecState.active -> "● 録っている画面に戻る"
            prefs.video -> "録画を始める"
            else -> "録音を始める"
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun neededPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (prefs.video) add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun onStartPressed() {
        if (RecState.active) {
            startActivity(Intent(this, RecordActivity::class.java))
            return
        }
        val missing = neededPermissions().filterNot(::granted)
        if (missing.isEmpty()) startRecording() else askPermissions.launch(missing.toTypedArray())
    }

    private fun startRecording() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "カメラのあたりに時間を出すには「表示を許可する」から許可してください", Toast.LENGTH_LONG).show()
        }
        RecState.starting(prefs.video, prefs.targetMin)
        try {
            RecorderService.start(this)
        } catch (e: Exception) {
            RecState.idle()
            RecState.lastError.value = "録り始められませんでした\n\n${e.javaClass.simpleName}: ${e.message}"
            return
        }
        startActivity(Intent(this, RecordActivity::class.java))
    }

    // ---- 一覧 ----

    private fun refreshList() {
        if (!::listBox.isInitialized) return
        thread {
            val items = Library.list(this)
            runOnUiThread { showList(items) }
        }
    }

    private fun setSelecting(on: Boolean) {
        selecting = on
        selected.clear()
        selectBtn.text = if (on) "やめる" else "選んで削除"
        showList(items)
    }

    private fun updateDeleteSelected() {
        deleteSelectedBtn.visibility = if (selecting) View.VISIBLE else View.GONE
        deleteSelectedBtn.isEnabled = selected.isNotEmpty()
        deleteSelectedBtn.text = if (selected.isEmpty()) "削除するものを選んでください" else "選んだ${selected.size}件を削除"
    }

    private fun showList(list: List<Rec>) {
        items = list
        selected.retainAll(list.map { it.uri }.toSet())
        listBox.removeAllViews()
        updateDeleteSelected()
        if (list.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "まだありません"
                setTextColor(0xFF888888.toInt())
                setPadding(0, dp(8f).toInt(), 0, 0)
            })
            return
        }
        val fmt = DateFormat.getMediumDateFormat(this)
        val tf = DateFormat.getTimeFormat(this)
        for (r in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                val tv = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }
            val check = CheckBox(this).apply {
                isChecked = r.uri in selected
                visibility = if (selecting) View.VISIBLE else View.GONE
                isClickable = false // 行ごと触って切り替える
            }
            row.addView(check)
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = dp(10f).toInt()
                setPadding(0, p, 0, p)
            }
            texts.addView(TextView(this).apply {
                text = (if (r.isVideo) "🎥 " else "🎙 ") + r.name
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
            })
            val d = Date(r.dateMs)
            val dur = if (r.durationMs > 0) "　${fmtClock(r.durationMs)}" else ""
            texts.addView(TextView(this).apply {
                text = "${fmt.format(d)} ${tf.format(d)}$dur　${fmtSize(r.size)}"
                textSize = 13f
                setTextColor(0xFF999999.toInt())
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (!selecting) {
                row.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    text = "削除"
                    setTextColor(0xFFFF6B5E.toInt())
                    setOnClickListener { confirmDelete(listOf(r)) }
                })
            }
            row.setOnClickListener {
                if (selecting) {
                    if (!selected.add(r.uri)) selected.remove(r.uri)
                    check.isChecked = r.uri in selected
                    updateDeleteSelected()
                } else openEditor(r)
            }
            row.setOnLongClickListener {
                if (selecting) false else { showMenu(r); true }
            }
            listBox.addView(row)
        }
    }

    private fun openEditor(r: Rec) {
        startActivity(Intent(this, EditActivity::class.java).setData(r.uri).putExtra(EditActivity.EXTRA_VIDEO, r.isVideo))
    }

    private fun showMenu(r: Rec) {
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setItems(arrayOf("切り出し編集", "共有", "名前を変更", "削除")) { _, which ->
                when (which) {
                    0 -> openEditor(r)
                    1 -> share(r)
                    2 -> rename(r)
                    3 -> confirmDelete(listOf(r))
                }
            }
            .show()
    }

    private fun share(r: Rec) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (r.isVideo) "video/mp4" else Library.audioMime)
            .putExtra(Intent.EXTRA_STREAM, r.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "共有"))
    }

    private fun rename(r: Rec) {
        val edit = EditText(this).apply {
            setText(Library.baseName(r.name))
            setSelectAllOnFocus(true)
            gravity = Gravity.START
        }
        val box = LinearLayout(this).apply {
            val p = dp(20f).toInt()
            setPadding(p, dp(8f).toInt(), p, 0)
            addView(edit, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("名前を変更")
            .setView(box)
            .setPositiveButton("変更") { _, _ ->
                val n = edit.text.toString().trim().replace('/', '_')
                if (n.isNotEmpty() && !Library.rename(this, r.uri, n + Library.ext(r.name))) {
                    Toast.makeText(this, "変更できませんでした", Toast.LENGTH_SHORT).show()
                }
                refreshList()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun confirmDelete(list: List<Rec>) {
        if (list.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(if (list.size == 1) "削除しますか？" else "${list.size}件を削除しますか？")
            .setMessage(list.joinToString("\n") { it.name })
            .setPositiveButton("削除") { _, _ -> delete(list.map { it.uri }) }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun delete(uris: List<Uri>) {
        val refused = mutableListOf<Uri>()
        for (u in uris) {
            try {
                contentResolver.delete(u, null, null)
            } catch (_: SecurityException) {
                refused += u // 入れ直す前に録ったもの等
            } catch (_: Exception) {
            }
        }
        if (selecting) setSelecting(false)
        if (refused.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 30) {
                // 端末に確認してから消す
                val pi = MediaStore.createDeleteRequest(contentResolver, refused)
                deleteRequest.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                Toast.makeText(this, "${refused.size}件は削除できませんでした", Toast.LENGTH_SHORT).show()
            }
        }
        refreshList()
    }
}
