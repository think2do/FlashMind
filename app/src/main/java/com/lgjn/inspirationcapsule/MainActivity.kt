package com.lgjn.inspirationcapsule

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.lgjn.inspirationcapsule.adapter.InspirationAdapter
import com.lgjn.inspirationcapsule.data.Inspiration
import com.lgjn.inspirationcapsule.databinding.ActivityMainBinding
import com.lgjn.inspirationcapsule.service.FloatingWindowService
import com.lgjn.inspirationcapsule.service.RecordingWidgetService
import com.lgjn.inspirationcapsule.service.VolumeKeyAccessibilityService
import com.lgjn.inspirationcapsule.viewmodel.InspirationViewModel
import com.lgjn.inspirationcapsule.viewmodel.ProcessingState
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: InspirationViewModel by viewModels()
    private lateinit var adapter: InspirationAdapter

    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var isRecording = false

    /** 录音最长时长兜底：60 秒自动停止，防止用户误触后忘记关闭 */
    private val recordingTimeoutHandler = Handler(Looper.getMainLooper())
    private val recordingTimeoutRunnable = Runnable {
        if (isRecording) {
            stopRecording()
            binding.tvRecordHint.text = "点击录音"
            binding.recordingWave.visibility = View.GONE
            binding.btnRecord.alpha = 1f
            Toast.makeText(this, "录音已自动停止（超过1分钟）", Toast.LENGTH_LONG).show()
        }
    }

    /** 监听来自 RecordingWidgetService 的保存成功广播，实时刷新列表 */
    private val inspirationSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModel.loadInspirations()
            binding.viewPager.postDelayed({ binding.viewPager.currentItem = 0 }, 300)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.values.all { it })
            Toast.makeText(this, "需要录音权限才能使用此功能", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupRecordButton()
        setupManualAddButton()
        setupAccessibilityBanner()
        observeViewModel()
        checkPermissions()
    }

    /**
     * 每次 Activity 重新可见时刷新灵感列表，
     * 确保小组件录音/文案输入后保存的数据能即时显示。
     */
    override fun onResume() {
        super.onResume()
        viewModel.loadInspirations()
        // 注册广播：接收 RecordingWidgetService 保存成功通知，实时刷新列表
        val filter = IntentFilter(RecordingWidgetService.ACTION_INSPIRATION_SAVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(inspirationSavedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(inspirationSavedReceiver, filter)
        }
        // 每次回到前台时更新无障碍引导横幅的可见性
        binding.btnEnableAccessibility.visibility =
            if (isAccessibilityServiceEnabled()) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(inspirationSavedReceiver) } catch (_: Exception) {}
    }

    // ──────────────────── ViewPager ────────────────────

    private fun setupViewPager() {
        adapter = InspirationAdapter(
            onLongClick = { inspiration -> showEditContentDialog(inspiration) },
            onFloatClick = { inspiration -> launchFloatingWindow(inspiration) },
            onDeleteClick = { inspiration -> showDeleteDialog(inspiration) },
            onItemClick = { pos ->
                // 点击侧边卡片时直接聚焦
                if (binding.viewPager.currentItem != pos) {
                    binding.viewPager.setCurrentItem(pos, true)
                }
            }
        )

        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 3

        val pageMarginPx = resources.getDimensionPixelOffset(R.dimen.page_margin)
        val pagerWidth = resources.getDimensionPixelOffset(R.dimen.pager_width)
        val screenWidth = resources.displayMetrics.widthPixels
        val offsetPx = screenWidth - pageMarginPx - pagerWidth

        // 使用固定相机距离（与屏幕密度挂钩），避免 elevation+rotationY 渲染冲突
        val cameraDistPx = resources.displayMetrics.density * 8000f

        binding.viewPager.setPageTransformer { page, position ->
            val absPos = Math.abs(position)

            page.cameraDistance = cameraDistPx

            // 3D Y 轴旋转：18° 既有立体感，又不会让侧边卡片变成细线
            page.rotationY = position * -18f

            // 缩放：距中心越远越小
            val scale = (1f - absPos * 0.14f).coerceAtLeast(0.70f)
            page.scaleX = scale
            page.scaleY = scale

            // 透明度：中心完全不透明，向两侧递减
            page.alpha = when {
                absPos >= 2.2f -> 0f
                else -> (1f - absPos * 0.35f).coerceAtLeast(0f)
            }

            // Z 轴层级：中心卡片永远在最顶层
            page.translationZ = (1f - absPos) * 24f

            // 水平折叠压缩，使相邻卡片更紧凑
            page.translationX = -position * offsetPx
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { updateDots(position) }
        })
    }

    private fun updateDots(selected: Int) {
        val count = adapter.itemCount
        binding.dotsContainer.removeAllViews()
        for (i in 0 until count) {
            val params = android.widget.LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(if (i == selected) R.dimen.dot_selected else R.dimen.dot_normal),
                resources.getDimensionPixelSize(if (i == selected) R.dimen.dot_selected else R.dimen.dot_normal)
            ).also { it.setMargins(6, 0, 6, 0) }
            binding.dotsContainer.addView(View(this).apply {
                layoutParams = params
                setBackgroundResource(if (i == selected) R.drawable.dot_selected else R.drawable.dot_normal)
            })
        }
    }

    // ──────────────────── 录音（点击切换模式） ────────────────────

    private fun setupRecordButton() {
        binding.btnRecordSection.setOnClickListener {
            if (!isRecording) {
                if (hasRecordPermission()) {
                    startRecording()
                    binding.tvRecordHint.text = "再次点击停止录音"
                    binding.recordingWave.visibility = View.VISIBLE
                    binding.btnRecord.alpha = 0.7f
                } else {
                    requestPermissions()
                }
            } else {
                stopRecording()
                binding.tvRecordHint.text = "点击录音"
                binding.recordingWave.visibility = View.GONE
                binding.btnRecord.alpha = 1f
            }
        }
    }

    private fun startRecording() {
        if (isRecording) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(cacheDir, "recordings").also { it.mkdirs() }
        currentAudioFile = File(dir, "rec_$stamp.m4a")

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(currentAudioFile!!.absolutePath)
            try {
                prepare(); start(); isRecording = true
                // 1 分钟兜底：超时自动停止
                recordingTimeoutHandler.postDelayed(recordingTimeoutRunnable, 60_000L)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "录音启动失败", Toast.LENGTH_SHORT).show()
                release(); mediaRecorder = null
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        recordingTimeoutHandler.removeCallbacks(recordingTimeoutRunnable)
        isRecording = false
        mediaRecorder?.apply { try { stop() } catch (_: Exception) {}; release() }
        mediaRecorder = null
        currentAudioFile?.let { file ->
            if (file.exists() && file.length() > 0) viewModel.processAudioFile(file)
            else Toast.makeText(this, "录音文件无效", Toast.LENGTH_SHORT).show()
        }
    }

    // ──────────────────── 按钮 ────────────────────

    private fun setupManualAddButton() {
        binding.btnManualSection.setOnClickListener { showTextInputDialog() }
    }

    // ──────────────────── 无障碍服务引导 ────────────────────

    private fun setupAccessibilityBanner() {
        binding.btnEnableAccessibility.setOnClickListener {
            showAccessibilityGuideDialog()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = ComponentName(this, VolumeKeyAccessibilityService::class.java)
            .flattenToString()
        return enabled.split(":").any { it.equals(target, ignoreCase = true) }
    }

    private fun showAccessibilityGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("开启三击录音")
            .setMessage(
                "开启后，在亮屏或锁屏状态下连续三击「音量上键」即可快速录音，" +
                "无需解锁手机。\n\n" +
                "① 点击「去开启」跳转无障碍设置\n" +
                "② 找到「灵感胶囊」并开启\n\n" +
                "⚠ 小米手机还需在「应用 → 灵感胶囊 → 电池」中选择「无限制」，" +
                "并开启「自启动」，否则后台可能失效。\n\n" +
                "注：本服务仅监听音量键按下事件，不读取屏幕内容，不上传任何数据。"
            )
            .setPositiveButton("去开启") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("暂不") { d, _ -> d.dismiss() }
            .create()
            .show()
    }

    // ──────────────────── ViewModel 观察 ────────────────────

    private fun observeViewModel() {
        viewModel.inspirations.observe(this) { list ->
            adapter.updateData(list)
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.viewPager.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            binding.dotsContainer.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            if (list.isNotEmpty()) updateDots(binding.viewPager.currentItem)
        }

        viewModel.processingState.observe(this) { state ->
            when (state) {
                is ProcessingState.Transcribing -> {
                    binding.processingHint.visibility = View.VISIBLE
                    binding.tvLoading.text = "正在转写录音…"
                    binding.btnRecordSection.isEnabled = false
                    binding.btnManualSection.isEnabled = false
                }
                is ProcessingState.Processing -> {
                    binding.processingHint.visibility = View.VISIBLE
                    binding.tvLoading.text = "AI 正在生成文案…"
                    binding.btnRecordSection.isEnabled = false
                    binding.btnManualSection.isEnabled = false
                }
                is ProcessingState.Success -> {
                    binding.processingHint.visibility = View.GONE
                    binding.btnRecordSection.isEnabled = true
                    binding.btnManualSection.isEnabled = true
                    Toast.makeText(this, "灵感已保存！", Toast.LENGTH_SHORT).show()
                    viewModel.resetProcessingState()
                    binding.viewPager.postDelayed({ binding.viewPager.currentItem = 0 }, 300)
                }
                is ProcessingState.Error -> {
                    binding.processingHint.visibility = View.GONE
                    binding.btnRecordSection.isEnabled = true
                    binding.btnManualSection.isEnabled = true
                    // 重置录音按钮状态（防止录音时出错导致按钮状态卡住）
                    if (isRecording) {
                        isRecording = false
                        binding.tvRecordHint.text = "点击录音"
                        binding.recordingWave.visibility = View.GONE
                        binding.btnRecord.alpha = 1f
                    }
                    showApiErrorDialog(state.message)
                    viewModel.resetProcessingState()
                }
                is ProcessingState.Idle -> {
                    binding.processingHint.visibility = View.GONE
                    binding.btnRecordSection.isEnabled = true
                    binding.btnManualSection.isEnabled = true
                }
            }
        }
    }

    // ──────────────────── 弹窗 ────────────────────

    /** API 错误弹窗：显示完整的 Dify 错误信息，方便排查变量名等配置问题 */
    private fun showApiErrorDialog(errorMessage: String) {
        val scrollView = ScrollView(this)
        val tv = TextView(this).apply {
            text = errorMessage
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(48, 24, 48, 24)
            setLineSpacing(4f, 1.3f)
        }
        scrollView.addView(tv)

        AlertDialog.Builder(this)
            .setTitle("AI请求失败")
            .setView(scrollView)
            .setPositiveButton("知道了", null)
            .create()
            .also { dialog ->
                dialog.window?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(0xEE2D1B6B.toInt())
                )
                dialog.show()
            }
    }

    /**
     * 文案输入弹窗：同时提供「直接保存」和「✨ AI提炼」两个提交路径
     */
    private fun showTextInputDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null)
        val etText = view.findViewById<EditText>(R.id.dialogEditText)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<TextView>(R.id.dialogBtnCancel).setOnClickListener {
            dialog.dismiss()
        }

        view.findViewById<TextView>(R.id.dialogBtnSaveDirect).setOnClickListener {
            val text = etText.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.addManualInspiration(text)
            Toast.makeText(this, "灵感已保存！", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            binding.viewPager.postDelayed({ binding.viewPager.currentItem = 0 }, 300)
        }

        view.findViewById<TextView>(R.id.dialogBtnAI).setOnClickListener {
            val text = etText.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.processTextInput(text)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    /** 编辑已有灵感（直接保存，不经过AI） */
    private fun showEditContentDialog(inspiration: Inspiration) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_inspiration_edit, null)
        val etText = view.findViewById<EditText>(R.id.dialogEditText)

        view.findViewById<TextView>(R.id.dialogTitle).text = "编辑灵感"
        etText.setText(inspiration.content)
        etText.hint = "在这里修改你的灵感…"
        etText.setSelection(inspiration.content.length)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<TextView>(R.id.dialogBtnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.dialogBtnConfirm).setOnClickListener {
            val text = etText.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.updateContent(inspiration.id, text)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    /** 通用确认弹窗 */
    private fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String = "确认",
        onConfirm: () -> Unit
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)

        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).text = message
        view.findViewById<TextView>(R.id.dialogBtnConfirm).text = confirmText

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<TextView>(R.id.dialogBtnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.dialogBtnConfirm).setOnClickListener {
            onConfirm()
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.82).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showDeleteDialog(inspiration: Inspiration) {
        showConfirmDialog("删除灵感", "确定要删除这条灵感吗？", "删除") {
            viewModel.deleteInspiration(inspiration.id)
        }
    }

    // ──────────────────── 悬浮窗 ────────────────────

    private fun launchFloatingWindow(inspiration: Inspiration) {
        if (!Settings.canDrawOverlays(this)) {
            showConfirmDialog(
                "需要悬浮窗权限",
                "灵感胶囊需要悬浮窗权限才能将灵感显示在屏幕上",
                "去设置"
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
            return
        }
        startService(Intent(this, FloatingWindowService::class.java).apply {
            putExtra("inspiration_id", inspiration.id)
            putExtra("inspiration_content", inspiration.content)
        })
        Toast.makeText(this, "灵感已悬浮到屏幕", Toast.LENGTH_SHORT).show()
    }

    // ──────────────────── 权限 ────────────────────

    private fun hasRecordPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        val missing = arrayOf(Manifest.permission.RECORD_AUDIO).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingTimeoutHandler.removeCallbacks(recordingTimeoutRunnable)
        if (isRecording) {
            mediaRecorder?.apply { try { stop() } catch (_: Exception) {} }
        }
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
