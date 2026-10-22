@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package fr.anon.app

import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: EditorModel by viewModels()
    private val ink = Color.rgb(16, 18, 15)
    private val panel = Color.rgb(29, 33, 27)
    private val lime = Color.rgb(217, 252, 118)
    private val muted = Color.rgb(157, 166, 149)
    private lateinit var root: LinearLayout
    private lateinit var stage: FrameLayout
    private lateinit var empty: LinearLayout
    private lateinit var canvas: PhotoCanvas
    private lateinit var playerView: PlayerView
    private lateinit var sourceLabel: TextView
    private lateinit var modeLabel: TextView
    private lateinit var hint: TextView
    private lateinit var faceRow: LinearLayout
    private lateinit var photoActions: LinearLayout
    private lateinit var export: Button
    private lateinit var importer: Button
    private lateinit var shuffle: Button
    private lateinit var share: Button
    private lateinit var openOutput: Button
    private lateinit var manual: Button
    private lateinit var blur: SeekBar
    private lateinit var blurLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var cancel: Button
    private lateinit var result: TextView
    private var player: ExoPlayer? = null
    private var playerKey: String? = null
    private var facesKey = ""
    private var lastError: String? = null
    private var previewError: String? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            releasePlayer(); previewError = null
            model.import(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val shared = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            if (shared != null) model.import(shared)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(ink); isFillViewport = true; clipToPadding = false }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(24)) }
        scroll.addView(root)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)

        val header = row()
        header.addView(label("ANON", 30, Color.WHITE, true).apply { letterSpacing = .13f }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(label("●  HORS LIGNE", 10, lime, true).apply { gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0); background = rounded(panel, 20) }, LinearLayout.LayoutParams(-2, dp(30)))
        root.addView(header)
        root.addView(label("Ton image. Leur anonymat.", 24, Color.WHITE, true), space(-1, -2, 20))
        root.addView(label("Un faux visage, puis du flou. Tout reste sur ton téléphone.", 13, muted), space(-1, -2, 7))

        val toolbar = row()
        modeLabel = label("01  /  IMPORTER", 11, lime, true).apply { letterSpacing = .1f }
        toolbar.addView(modeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        importer = button("＋ Importer", false) { picker.launch(arrayOf("image/*", "video/*")) }
        toolbar.addView(importer, LinearLayout.LayoutParams(-2, dp(42)))
        root.addView(toolbar, space(-1, -2, 24))

        stage = FrameLayout(this).apply { background = rounded(Color.rgb(8, 10, 8), 18); clipToOutline = true }
        root.addView(stage, space(-1, dp(300), 12))
        empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(20), dp(24), dp(20)) }
        empty.addView(label("[  ◉  ]", 42, lime, true).apply { gravity = Gravity.CENTER }, space(-1, -2, 0))
        empty.addView(label("Rends les visages anonymes.", 19, Color.WHITE, true).apply { gravity = Gravity.CENTER }, space(-1, -2, 20))
        empty.addView(label("Photo : choisis les visages.\nVidéo : tous les visages, automatiquement.", 13, muted).apply { gravity = Gravity.CENTER; setLineSpacing(dp(5).toFloat(), 1f) }, space(-1, -2, 12))
        empty.addView(button("Choisir une photo ou vidéo", true) { picker.launch(arrayOf("image/*", "video/*")) }, space(-1, dp(48), 22))
        stage.addView(empty, FrameLayout.LayoutParams(-1, -1))
        canvas = PhotoCanvas(this).apply {
            contentDescription = "Aperçu photo. Touche les cadres pour choisir les visages à anonymiser."
            onFaceTap = model::toggle; onRegion = model::addRegion
        }
        stage.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        playerView = PlayerView(this).apply { setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); setShutterBackgroundColor(ink) }
        stage.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        sourceLabel = label("MP4, MOV, JPG, PNG…", 11, muted)
        root.addView(sourceLabel, space(-1, -2, 10))
        hint = label("", 13, muted)
        root.addView(hint, space(-1, -2, 20))
        val faceScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        faceRow = row()
        faceScroll.addView(faceRow)
        root.addView(faceScroll, space(-1, -2, 12))

        photoActions = row()
        photoActions.addView(button("Tous", false) { model.selectAll(true) }, weighted())
        photoActions.addView(button("Aucun", false) { model.selectAll(false) }, weighted())
        manual = button("＋ Zone", false) { model.manual(!model.state.value.manual) }
        photoActions.addView(manual, weighted())
        root.addView(photoActions, space(-1, dp(42), 10))

        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(12)); background = rounded(panel, 16) }
        val settingTop = row()
        settingTop.addView(label("INTENSITÉ DU FLOU", 10, muted, true).apply { letterSpacing = .1f }, LinearLayout.LayoutParams(0, -2, 1f))
        blurLabel = label("65 %", 14, lime, true)
        settingTop.addView(blurLabel)
        settings.addView(settingTop)
        blur = SeekBar(this).apply {
            max = 80; progress = 45
            progressTintList = ColorStateList.valueOf(lime); thumbTintList = ColorStateList.valueOf(lime)
            setPadding(0, dp(10), 0, 0)
            contentDescription = "Intensité du flou"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) { blurLabel.text = "${value + 20} %" }
                override fun onStartTrackingTouch(seekBar: SeekBar) { player?.pause() }
                override fun onStopTrackingTouch(seekBar: SeekBar) { model.strength(seekBar.progress + 20) }
            })
        }
        settings.addView(blur, space(-1, dp(48), 4))
        shuffle = button("↻ Changer les faux visages", false) { model.shuffle() }
        settings.addView(shuffle, space(-1, dp(42), 8))
        root.addView(settings, space(-1, -2, 22))

        progressText = label("", 13, Color.WHITE)
        root.addView(progressText, space(-1, -2, 16))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = ColorStateList.valueOf(lime) }
        root.addView(progress, space(-1, dp(8), 8))
        cancel = button("Annuler le traitement", false) { model.cancel() }
        root.addView(cancel, space(-1, dp(42), 8))
        export = button("Exporter dans la galerie  ↗", true) { player?.pause(); model.export() }
        root.addView(export, space(-1, dp(54), 20))
        result = label("", 13, lime)
        root.addView(result, space(-1, -2, 12))
        val resultActions = row()
        openOutput = button("Voir le résultat", false) { viewResult() }
        share = button("Partager", false) { shareResult() }
        resultActions.addView(openOutput, weighted()); resultActions.addView(share, weighted())
        root.addView(resultActions, space(-1, dp(44), 8))
        root.addView(label("Vérifie le résultat avant de partager : un visage petit, masqué ou de profil peut échapper à la détection.", 11, muted), space(-1, -2, 20))
    }

    private fun render(s: EditorState) {
        val loaded = s.source != null
        val busy = s.busy != null
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        empty.visibility = if (loaded) View.GONE else View.VISIBLE
        canvas.visibility = if (loaded && !s.video) View.VISIBLE else View.GONE
        playerView.visibility = if (loaded && s.video) View.VISIBLE else View.GONE
        canvas.isEnabled = !busy
        canvas.bitmap = s.preview ?: if (s.rendering) null else s.photo
        canvas.faces = s.faces; canvas.selected = s.selected; canvas.manual = s.manual; canvas.invalidate()
        modeLabel.text = when { !loaded -> "01  /  IMPORTER"; s.video -> "02  /  TOUS LES VISAGES"; else -> "02  /  SÉLECTIONNER" }
        sourceLabel.text = if (loaded) s.name + if (s.video) "  •  ${s.durationMs / 1000} s" else "  •  ${s.photo?.width} × ${s.photo?.height}" else "MP4, MOV, JPG, PNG…"
        hint.text = when {
            !loaded -> ""
            s.video -> "Tous les visages détectés sont remplacés et floutés. L’export analyse chaque image."
            s.rendering -> "Actualisation de l’aperçu…"
            s.manual -> "Dessine un rectangle autour du visage à anonymiser."
            s.faces.isEmpty() -> "Aucun visage détecté. Ajoute une zone manuellement."
            else -> "${s.selected.size} / ${s.faces.size} visages anonymisés. Touche un cadre pour changer."
        }
        hint.visibility = if (loaded) View.VISIBLE else View.GONE
        photoActions.visibility = if (loaded && !s.video) View.VISIBLE else View.GONE
        for (i in 0 until photoActions.childCount) photoActions.getChildAt(i).isEnabled = !busy
        manual.text = if (s.manual) "Dessine…" else "＋ Zone"
        manual.backgroundTintList = ColorStateList.valueOf(if (s.manual) Color.rgb(64, 83, 35) else panel)
        if (!blur.isPressed) blur.progress = s.strength - 20
        blurLabel.text = "${s.strength} %"
        blur.isEnabled = loaded && !busy; shuffle.isEnabled = loaded && !busy
        importer.isEnabled = !busy
        export.isEnabled = loaded && !busy && !s.rendering && (s.video || s.selected.isNotEmpty())
        export.alpha = if (export.isEnabled) 1f else .4f
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progressText.visibility = if (busy) View.VISIBLE else View.GONE
        progressText.text = "${s.busy.orEmpty()}${if (s.progress > 0) " ${s.progress} %" else ""}"
        progress.isIndeterminate = busy && s.progress == 0
        progress.progress = s.progress
        cancel.visibility = if (busy && s.video) View.VISIBLE else View.GONE
        val done = s.output != null
        result.visibility = if (done) View.VISIBLE else View.GONE
        result.text = "✓ Enregistré dans la galerie / ANON"
        share.visibility = if (done) View.VISIBLE else View.GONE
        openOutput.visibility = if (done) View.VISIBLE else View.GONE
        updateFaces(s, busy)
        if (s.video && loaded && !busy && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) ensurePlayer(s)
        else if (busy || !s.video) releasePlayer()
        if (s.error != null && s.error != lastError) {
            lastError = s.error
            android.app.AlertDialog.Builder(this).setTitle("Traitement interrompu").setMessage(s.error)
                .setPositiveButton("Compris") { _, _ -> lastError = null; model.dismissError() }.show()
        }
    }

    private fun updateFaces(s: EditorState, busy: Boolean) {
        val key = "${s.source}|${s.faces}|${s.selected}|$busy"
        faceRow.visibility = if (s.video || s.faces.isEmpty()) View.GONE else View.VISIBLE
        if (key == facesKey) return
        facesKey = key; faceRow.removeAllViews()
        for ((index, face) in s.faces.withIndex()) {
            val chosen = face.id in s.selected
            faceRow.addView(button("${if (chosen) "●" else "○"} Visage ${index + 1}", chosen) { model.toggle(face.id) }
                .apply { isEnabled = !busy }, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(8) })
        }
    }

    private fun ensurePlayer(s: EditorState) {
        val key = "${s.source}|${s.strength}|${s.seed}"
        if (playerKey == key) return
        val position = if (playerKey?.substringBefore('|') == s.source.toString()) player?.currentPosition ?: 0 else 0
        releasePlayer()
        playerKey = key
        player = ExoPlayer.Builder(this).build().also { p ->
            p.setVideoEffects(listOf(ReplacementEffect(applicationContext, s.strength, s.seed, preview = true)))
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (previewError != error.message) {
                        previewError = error.message
                        Toast.makeText(this@MainActivity, "Aperçu indisponible : essaie l’export ou un autre format.", Toast.LENGTH_LONG).show()
                    }
                }
            })
            playerView.player = p
            p.setMediaItem(MediaItem.fromUri(requireNotNull(s.source))); p.prepare(); p.seekTo(position)
            p.playWhenReady = false
        }
    }

    private fun releasePlayer() { playerView.player = null; player?.release(); player = null; playerKey = null }
    override fun onResume() { super.onResume(); if (::root.isInitialized) render(model.state.value) }
    override fun onStop() { releasePlayer(); super.onStop() }
    override fun onDestroy() { releasePlayer(); super.onDestroy() }
    private fun shareResult() {
        val s = model.state.value; val uri = s.output ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (s.video) "video/mp4" else "image/png"
            putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri("ANON", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Partager le résultat"))
    }
    private fun viewResult() {
        val s = model.state.value; val uri = s.output ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (s.video) "video/mp4" else "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this, "Le fichier est dans ta galerie, dossier ANON.", Toast.LENGTH_LONG).show() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun button(text: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f; minWidth = 0; minHeight = 0
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (primary) ink else Color.WHITE)
        background = rounded(if (primary) lime else panel, 12)
        stateListAnimator = null
        setOnClickListener { action() }
    }
    private fun space(width: Int, height: Int, top: Int) = LinearLayout.LayoutParams(width, height).apply { topMargin = dp(top) }
    private fun weighted() = LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(6) }
}
