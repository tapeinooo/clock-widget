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

/** はじめの画面：録り始める・設定・録ったものの一覧。 */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var startBtn: MaterialButton
    private lateinit var overlayBtn: MaterialButton
    private lateinit var listBox: LinearLayout
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
        videoOnly += radio(listOf("720p", "1080p"), if (prefs.fullHd) 1 else 0) { prefs.fullHd = it == 1 }
            .also { root.addView(it) }
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
        label("録ったもの（触ると切り出し編集・長押しでその他）", 24f)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        setContentView(scroll)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecState.phase.combine(RecState.savedCount) { a, b -> a to b }.collect { updateStart(); refreshList() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayBtn.text = if (Settings.canDrawOverlays(this)) "表示の許可：済み" else "表示を許可する（他のアプリの上に重ねる）"
        updateStart()
        refreshList()
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
        RecorderService.start(this)
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

    private fun showList(items: List<Rec>) {
        listBox.removeAllViews()
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "まだありません"
                setTextColor(0xFF888888.toInt())
                setPadding(0, dp(8f).toInt(), 0, 0)
            })
            return
        }
        val fmt = DateFormat.getMediumDateFormat(this)
        val tf = DateFormat.getTimeFormat(this)
        for (r in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = dp(10f).toInt()
                setPadding(0, p, 0, p)
                isClickable = true
                isFocusable = true
                val tv = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { openEditor(r) }
                setOnLongClickListener { showMenu(r); true }
            }
            row.addView(TextView(this).apply {
                text = (if (r.isVideo) "🎥 " else "🎙 ") + r.name
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
            })
            val d = Date(r.dateMs)
            val dur = if (r.durationMs > 0) "　${fmtClock(r.durationMs)}" else ""
            row.addView(TextView(this).apply {
                text = "${fmt.format(d)} ${tf.format(d)}$dur　${fmtSize(r.size)}"
                textSize = 13f
                setTextColor(0xFF999999.toInt())
            })
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
                    3 -> confirmDelete(r)
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

    private fun confirmDelete(r: Rec) {
        AlertDialog.Builder(this)
            .setTitle("削除しますか？")
            .setMessage(r.name)
            .setPositiveButton("削除") { _, _ -> delete(r.uri) }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun delete(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
            refreshList()
        } catch (e: SecurityException) {
            // 入れ直す前に録ったもの等は、端末に確認してから消す
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                deleteRequest.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                Toast.makeText(this, "削除できませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
