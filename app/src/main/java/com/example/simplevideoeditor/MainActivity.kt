package com.example.simplevideoeditor

import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.ArrayDeque
import java.util.Date
import java.text.SimpleDateFormat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(11, 18, 32)
    private val card = Color.rgb(21, 31, 51)
    private val field = Color.rgb(13, 23, 40)
    private val accent = Color.rgb(79, 140, 255)
    private val white = Color.rgb(241, 245, 249)
    private val muted = Color.rgb(148, 163, 184)

    data class Video(
        val name: String,
        val uri: Uri,
        val sizeBytes: Long,
        val duration: Double
    )

    data class Segment(
        val source: Spinner,
        val start: EditText,
        val end: EditText,
        val container: LinearLayout,
        var selectedSourceUri: Uri? = null
    )

    private val videos = mutableListOf<Video>()
    private val segments = mutableListOf<Segment>()
    private val activityLog = ArrayDeque<String>()
    private val maxLogEntries = 300

    private lateinit var videoLabel: TextView
    private lateinit var segmentContainer: LinearLayout
    private lateinit var formatGroup: RadioGroup
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var exportButton: Button

    private val executor = Executors.newSingleThreadExecutor()

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            importVideos(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        recordLog("Aplicación iniciada")
        buildInterface()
        addSegment()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
            insets
        }

        val scroll = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(25), dp(20), dp(20))
        }

        scroll.addView(content)

        val brand = label(
            "CLIPFORGE  /  VIDEO EDITOR",
            12,
            accent,
            true
        )

        content.addView(brand)

        content.addView(
            label("Recorta y exporta", 29, white, true)
        )

        content.addView(
            label(
                "Combina fragmentos de uno o varios vídeos.",
                14,
                muted
            )
        )

        space(content, 24)

        // VIDEOS FUENTE

        val videoCard = createCard(content, "01  ·  Vídeos fuente")

        val addVideoButton = button("＋ Añadir vídeos") {
            picker.launch(arrayOf("video/*"))
        }

        videoCard.addView(addVideoButton)

        videoLabel = label(
            "Ningún vídeo seleccionado",
            13,
            muted
        )

        videoCard.addView(videoLabel)

        // FRAGMENTOS

        val segmentCard = createCard(
            content,
            "02  ·  Orden de los fragmentos"
        )

        segmentCard.addView(
            label(
                "Selecciona el vídeo y establece el inicio y el final de cada corte.",
                12,
                muted
            )
        )

        space(segmentCard, 12)

        val compactWidth = resources.configuration.screenWidthDp < 400
        val segmentButtons = LinearLayout(this).apply {
            orientation = if (compactWidth) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }

        val segmentButtonParams = if (compactWidth) {
            LinearLayout.LayoutParams(-1, -2)
        } else {
            LinearLayout.LayoutParams(0, -2, 1f)
        }
        segmentButtons.addView(
            button("＋ Añadir recorte") {
                addSegment()
            },
            segmentButtonParams
        )

        val removeButtonParams = if (compactWidth) {
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        } else {
            LinearLayout.LayoutParams(0, -2, 1f)
        }
        segmentButtons.addView(
            button("Quitar último") {
                removeSegment()
            },
            removeButtonParams
        )

        segmentCard.addView(segmentButtons)

        segmentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        segmentCard.addView(segmentContainer)

        // FORMATOS

        val formatCard = createCard(
            content,
            "03  ·  Formato de salida"
        )

        formatGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        val formats = listOf(
            "reels" to "Reels / TikTok / Shorts (9:16)",
            "reels_standard" to "Vertical estándar (9:16)",
            "story" to "Historias / Estado (fondo difuminado)",
            "horizontal" to "Normal (relación original)"
        )

        formats.forEachIndexed { index, item ->

            val radio = RadioButton(this).apply {
                id = 100 + index
                text = item.second
                textSize = 14f
                setTextColor(white)
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                setPadding(dp(5), dp(12), dp(5), dp(12))
            }

            formatGroup.addView(radio)

            if (index == 0) {
                formatGroup.check(radio.id)
            }
        }

        formatGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId)?.text
            if (selected != null) recordLog("Formato seleccionado: $selected")
        }

        formatCard.addView(formatGroup)

        // PIE DE PANTALLA

        status = label(
            "Listo para editar",
            13,
            muted
        )

        progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            progressTintList =
                android.content.res.ColorStateList.valueOf(accent)
        }

        exportButton = button("EXPORTAR VÍDEO") {
            startExport()
        }
        val shareLogButton = button("COMPARTIR REGISTRO") {
            shareActivityLog()
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setBackgroundColor(card)
        }

        footer.addView(status)

        footer.addView(progress)

        footer.addView(exportButton)
        footer.addView(shareLogButton)
        footer.addView(label("El registro incluye campos y nombres de vídeos.", 11, muted))

        root.addView(footer)

        setContentView(root)
    }

    // --------------------------------------------------
    // INTERFAZ
    // --------------------------------------------------

    private fun label(
        text: String,
        size: Int,
        color: Int,
        bold: Boolean = false
    ): TextView {

        return TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)

            if (bold) {
                typeface = Typeface.DEFAULT_BOLD
            }

            setPadding(0, dp(5), 0, dp(5))
        }
    }

    private fun button(
        text: String,
        action: () -> Unit
    ): Button {

        return Button(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(accent)
            )
            setOnClickListener {
                recordLog("Pulsación de botón: $text")
                action()
            }
        }
    }

    private fun space(
        parent: LinearLayout,
        height: Int
    ) {
        parent.addView(
            View(this),
            LinearLayout.LayoutParams(1, dp(height))
        )
    }

    private fun createCard(
        parent: LinearLayout,
        title: String
    ): LinearLayout {

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(card)
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }

        val params = LinearLayout.LayoutParams(
            -1,
            -2
        )

        params.bottomMargin = dp(15)

        parent.addView(container, params)

        container.addView(
            label(title, 17, white, true)
        )

        return container
    }

    private fun createInput(
        hint: String,
        value: String
    ): EditText {

        return EditText(this).apply {
            setSingleLine(true)
            setText(value)
            this.hint = hint
            textSize = 14f
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(field)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    recordLog("Campo '$hint' cambiado a '${s?.toString().orEmpty()}'")
                }
            })
        }
    }

    @Synchronized
    private fun recordLog(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            .format(Date())
        synchronized(activityLog) {
            activityLog.addLast("[$timestamp] $message")
            while (activityLog.size > maxLogEntries) {
                activityLog.removeFirst()
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 0) return "desconocido"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = -1
        do {
            size /= 1024.0
            unitIndex++
        } while (size >= 1024.0 && unitIndex < units.lastIndex)
        return String.format(Locale.getDefault(), "%.2f %s", size, units[unitIndex])
    }

    private fun shareActivityLog() {
        try {
            val logDirectory = File(cacheDir, "logs")
            if (!logDirectory.exists() && !logDirectory.mkdirs()) {
                throw Exception("No se pudo crear el archivo del registro.")
            }

            val fileName = "clipforge_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
            val logFile = File(logDirectory, fileName)
            val eventLines = synchronized(activityLog) { activityLog.toList() }
            logFile.writeText(buildString {
                appendLine("Registro de actividad de ClipForge")
                appendLine("Generado: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())}")
                appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android API: ${Build.VERSION.SDK_INT}")
                appendLine("Versión de la app: ${packageManager.getPackageInfo(packageName, 0).versionName}")
                appendLine()
                appendLine("FORMULARIO ACTUAL")
                appendLine("Vídeos de entrada:")
                if (videos.isEmpty()) {
                    appendLine("(ninguno)")
                } else {
                    videos.forEach { video ->
                        appendLine("- ${video.name} (${formatFileSize(video.sizeBytes)})")
                    }
                }
                segments.forEachIndexed { index, segment ->
                    val selectedVideo = videos.getOrNull(segment.source.selectedItemPosition)?.name ?: "sin seleccionar"
                    appendLine("Recorte ${index + 1}: vídeo=$selectedVideo; inicio=${segment.start.text}; fin=${segment.end.text}")
                }
                val selectedFormat = formatGroup.findViewById<RadioButton>(formatGroup.checkedRadioButtonId)?.text ?: "sin seleccionar"
                appendLine("Formato: $selectedFormat")
                appendLine()
                appendLine("ÚLTIMAS ACCIONES (máximo $maxLogEntries)")
                if (eventLines.isEmpty()) appendLine("(sin acciones registradas)")
                eventLines.forEach { appendLine(it) }
            })

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", logFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Registro de actividad de ClipForge")
                putExtra(Intent.EXTRA_TEXT, "Adjunto el registro de actividad y el estado actual del formulario.")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, logFile.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Compartir registro"))
        } catch (e: Exception) {
            recordLog("Error al compartir registro: ${e.javaClass.simpleName}: ${e.message}")
            showError(e.message ?: "No se pudo preparar el registro.")
        }
    }

    // --------------------------------------------------
    // SELECCIÓN DE VÍDEOS
    // --------------------------------------------------

    private fun importVideos(uris: List<Uri>) {
        recordLog("Importación iniciada: ${uris.size} archivo(s) seleccionado(s)")

        executor.execute {

            val imported = mutableListOf<Video>()
            val importErrors = mutableListOf<String>()

            for (uri in uris) {
                var selectedName = "archivo seleccionado"

                try {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) {
                        // Keep using the temporary read permission granted by the picker.
                    }
                    val name = getFileName(uri)
                    selectedName = name
                    val sizeBytes = getFileSize(uri)
                    val retriever = MediaMetadataRetriever()
                    val durationMs = try {
                        retriever.setDataSource(this@MainActivity, uri)
                        retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull() ?: 0L
                    } finally {
                        retriever.release()
                    }

                    if (durationMs > 0) {
                        imported.add(
                            Video(
                                name,
                                uri,
                                sizeBytes,
                                durationMs / 1000.0
                            )
                        )
                    } else {
                        importErrors.add("$name: Android no pudo leer la duración del vídeo")
                    }

                } catch (e: Exception) {
                    importErrors.add("Error al leer $selectedName: ${e.message ?: e.javaClass.simpleName}")
                }
            }

            runOnUiThread {

                videos.addAll(imported)

                videoLabel.text = if (importErrors.isEmpty()) {
                    "${videos.size} vídeo(s) añadido(s)"
                } else {
                    "${imported.size} importado(s); ${importErrors.size} con error"
                }

                imported.forEach {
                    recordLog("Vídeo de entrada añadido: ${it.name}; tamaño=${formatFileSize(it.sizeBytes)}")
                }
                importErrors.forEach { recordLog("Error de importación: $it") }

                refreshSpinners()

                status.text = if (importErrors.isEmpty()) "Vídeos preparados" else "Revisa los vídeos no importados"
                if (importErrors.isNotEmpty()) {
                    showError(importErrors.joinToString("\n"))
                }
            }
        }
    }

    private fun getFileSize(uri: Uri): Long {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    return cursor.getLong(sizeColumn)
                }
            }
        }
        return -1L
    }

    private fun getFileName(uri: Uri): String {

        var name = "video.mp4"

        contentResolver.query(
            uri,
            null,
            null,
            null,
            null
        )?.use { cursor ->

            if (cursor.moveToFirst()) {

                val index = cursor.getColumnIndex(
                    OpenableColumns.DISPLAY_NAME
                )

                if (index >= 0) {
                    name = cursor.getString(index)
                }
            }
        }

        return name
    }

    // --------------------------------------------------
    // GESTIÓN DE FRAGMENTOS
    // --------------------------------------------------

    private fun addSegment() {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(12), dp(5), dp(12))
        }

        val number = segments.size + 1

        row.addView(
            label("RECORTE $number", 12, accent, true)
        )

        val spinner = Spinner(this)

        val start = createInput(
            "Inicio (MM:SS)",
            "00:00"
        )

        val end = createInput(
            "Fin (MM:SS)",
            ""
        )

        row.addView(spinner)

        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        times.addView(
            start,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        times.addView(
            end,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        row.addView(times)

        segmentContainer.addView(row)

        val segment = Segment(
            spinner,
            start,
            end,
            row
        )

        segments.add(segment)

        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {}

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {

                    if (position < videos.size) {

                        val video = videos[position]
                        if (segment.selectedSourceUri != video.uri) {
                            segment.selectedSourceUri = video.uri
                            recordLog("Vídeo seleccionado para recorte: ${video.name}")
                            start.setText("00:00")
                            end.setText(formatTime(video.duration))
                        }
                    }
                }
            }

        refreshSpinners()
    }

    private fun removeSegment() {

        if (segments.size <= 1) return

        val last = segments.removeAt(segments.lastIndex)

        segmentContainer.removeView(last.container)

    }

    private fun refreshSpinners() {

        val names = videos.map {
            it.name
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            names
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        segments.forEach {
            val selectedUri = it.selectedSourceUri
                ?: videos.getOrNull(it.source.selectedItemPosition)?.uri
            val selectedPosition = selectedUri?.let { uri ->
                videos.indexOfFirst { video -> video.uri == uri }
            } ?: 0
            it.source.adapter = adapter

            if (videos.isNotEmpty()) {
                it.source.setSelection(selectedPosition.coerceAtLeast(0))
            }
        }
    }

    // --------------------------------------------------
    // TIEMPOS
    // --------------------------------------------------

    private fun parseTime(value: String): Double {

        val clean = value.trim().replace(",", ".")

        if (clean.isEmpty()) {
            throw Exception("Introduce un tiempo.")
        }

        val parts = clean.split(":")

        val result = when (parts.size) {

            1 -> clean.toDouble()

            2 -> {
                parts[0].toDouble() * 60 +
                        parts[1].toDouble()
            }

            3 -> {
                parts[0].toDouble() * 3600 +
                        parts[1].toDouble() * 60 +
                        parts[2].toDouble()
            }

            else -> throw Exception("Formato de tiempo incorrecto.")
        }

        if (result < 0) {
            throw Exception("El tiempo no puede ser negativo.")
        }

        if (parts.size >= 2) {
            if (parts.last().toDouble() >= 60) {
                throw Exception("Los segundos deben ser menores de 60.")
            }
        }

        if (parts.size == 3 && parts[1].toDouble() >= 60) {
            throw Exception("Los minutos deben ser menores de 60.")
        }

        return result
    }

    private fun formatTime(seconds: Double): String {

        val total = seconds.toInt()

        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60

        return if (hours > 0) {
            String.format(
                Locale.US,
                "%02d:%02d:%02d",
                hours,
                minutes,
                secs
            )
        } else {
            String.format(
                Locale.US,
                "%02d:%02d",
                minutes,
                secs
            )
        }
    }

    // --------------------------------------------------
    // EXPORTACIÓN
    // --------------------------------------------------

    private fun startExport() {
        recordLog("Exportación solicitada")

        if (videos.isEmpty()) {
            showError("Añade al menos un vídeo.")
            return
        }

        val selectedSegments = mutableListOf<Triple<Video, Double, Double>>()

        try {

            segments.forEachIndexed { index, segment ->

                val position = segment.source.selectedItemPosition

                if (position !in videos.indices) {
                    throw Exception("Selecciona un vídeo en el recorte ${index + 1}.")
                }

                val video = videos[position]

                val start = parseTime(
                    segment.start.text.toString()
                )

                val end = parseTime(
                    segment.end.text.toString()
                )

                if (end <= start) {
                    throw Exception(
                        "El final debe ser posterior al inicio en el recorte ${index + 1}."
                    )
                }

                if (end > video.duration + 0.05) {
                    throw Exception(
                        "El recorte ${index + 1} supera la duración del vídeo."
                    )
                }

                selectedSegments.add(
                    Triple(video, start, end)
                )
            }

        } catch (e: Exception) {
            showError(e.message ?: "Error en los tiempos.")
            return
        }

        val selectedFormat = when (formatGroup.checkedRadioButtonId) {
            100 -> "reels"
            101 -> "reels_standard"
            102 -> "story"
            else -> "horizontal"
        }
        recordLog("Exportación iniciada: ${selectedSegments.size} recorte(s), formato=$selectedFormat")

        exportButton.isEnabled = false
        progress.progress = 0
        status.text = "Preparando exportación..."

        executor.execute {

            try {

                val tempFolder = File(
                    cacheDir,
                    "clipforge_${System.currentTimeMillis()}"
                )

                tempFolder.mkdirs()

                val parts = mutableListOf<File>()

                val total = selectedSegments.size

                selectedSegments.forEachIndexed { index, segment ->

                    val video = segment.first
                    val start = segment.second
                    val end = segment.third

                    val part = File(
                        tempFolder,
                        "part_${index + 1}.mp4"
                    )

                    val command = buildSegmentCommand(
                        FFmpegKitConfig.getSafParameterForRead(this@MainActivity, video.uri),
                        part,
                        start,
                        end,
                        selectedFormat
                    )

                    runFFmpeg(command)

                    parts.add(part)

                    runOnUiThread {
                        progress.progress =
                            ((index + 1) * 85) / total

                        status.text =
                            "Procesando fragmento ${index + 1}/$total"
                    }
                }

                val output = File(
                    tempFolder,
                    "clipforge_export.mp4"
                )

                if (parts.size == 1) {

                    parts[0].copyTo(output, true)

                } else {

                    val manifest = File(
                        tempFolder,
                        "concat.txt"
                    )

                    manifest.writeText(
                        parts.joinToString("\n") {
                            "file '${it.absolutePath.replace("'", "'\\''")}'"
                        }
                    )

                    val concatCommand = arrayOf(
                        "-hide_banner", "-y", "-f", "concat", "-safe", "0",
                        "-i", manifest.absolutePath,
                        "-c", "copy", "-movflags", "+faststart",
                        output.absolutePath
                    )

                    runFFmpeg(concatCommand)
                }

                val outputSize = output.length()
                val savedUri = saveToGallery(output)
                recordLog("Vídeo de salida guardado; tamaño=${formatFileSize(outputSize)}")

                tempFolder.deleteRecursively()

                runOnUiThread {

                    progress.progress = 100
                    exportButton.isEnabled = true
                    status.text = "¡Exportación terminada!"
                    recordLog("Exportación completada y guardada en la galería; tamaño=${formatFileSize(outputSize)}")

                    Toast.makeText(
                        this,
                        "Vídeo guardado en la galería",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                recordLog("Error de exportación: ${e.message ?: e.javaClass.simpleName}")

                runOnUiThread {

                    exportButton.isEnabled = true

                    status.text = "Error en la exportación"

                    showError(
                        e.message ?: "No se pudo exportar el vídeo."
                    )
                }
            }
        }
    }

    // --------------------------------------------------
    // COMANDOS FFMPEG
    // --------------------------------------------------

    private fun buildSegmentCommand(
        source: String,
        output: File,
        start: Double,
        end: Double,
        mode: String
    ): Array<String> {

        val duration = end - start

        val common = listOf(
            "-hide_banner", "-y",
            "-ss", start.toString(),
            "-i", source,
            "-t", duration.toString()
        )

        val codecs = listOf(
            "-c:v", "mpeg4",
            "-q:v", "4",
            "-c:a", "aac",
            "-b:a", "192k",
            "-movflags", "+faststart"
        )

        val filters = when (mode) {

            "reels", "reels_standard" -> {

                listOf(
                    "-vf",
                    "scale=1080:1920:force_original_aspect_ratio=increase," +
                        "crop=1080:1920,setsar=1"
                )
            }

            "story" -> {

                listOf(
                    "-filter_complex",
                    "[0:v]split=2[bg][fg];" +
                        "[bg]scale=1080:1920:force_original_aspect_ratio=increase," +
                        "crop=1080:1920,gblur=sigma=28," +
                        "eq=brightness=-0.18[back];" +
                        "[fg]scale=1080:1920:force_original_aspect_ratio=decrease[front];" +
                        "[back][front]overlay=(W-w)/2:(H-h)/2,setsar=1[v]",
                    "-map", "[v]", "-map", "0:a?"
                )
            }

            else -> listOf("-map", "0:v:0", "-map", "0:a?")
        }

        return (common + filters + codecs + output.absolutePath).toTypedArray()
    }

    private fun runFFmpeg(command: Array<String>) {

        val session = FFmpegKit.executeWithArguments(command)

        if (!ReturnCode.isSuccess(session.returnCode)) {
            val details: String = session.getAllLogsAsString(1_000)
                .takeLast(2_500)
                .trim()

            throw Exception(
                details.takeIf { it.isNotEmpty() }
                    ?: session.failStackTrace
                    ?: "FFmpeg no pudo procesar el vídeo."
            )
        }
    }

    // --------------------------------------------------
    // GUARDAR EN GALERÍA
    // --------------------------------------------------

    private fun saveToGallery(file: File): Uri {

        val values = android.content.ContentValues().apply {

            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "ClipForge_${System.currentTimeMillis()}.mp4"
            )

            put(
                MediaStore.Video.Media.MIME_TYPE,
                "video/mp4"
            )

            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "Movies/ClipForge"
            )

            put(
                MediaStore.Video.Media.IS_PENDING,
                1
            )
        }

        val uri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw Exception("No se pudo crear el archivo de vídeo.")

        try {

            contentResolver.openOutputStream(uri)?.use { output ->

                FileInputStream(file).use { input ->
                    input.copyTo(output)
                }

            } ?: throw Exception("No se pudo guardar el vídeo.")

            values.clear()

            values.put(
                MediaStore.Video.Media.IS_PENDING,
                0
            )

            contentResolver.update(
                uri,
                values,
                null,
                null
            )

            return uri

        } catch (e: Exception) {

            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    // --------------------------------------------------
    // ERRORES
    // --------------------------------------------------

    private fun showError(message: String) {

        android.app.AlertDialog.Builder(this)
            .setTitle("ClipForge")
            .setMessage(message)
            .setPositiveButton("Aceptar", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
