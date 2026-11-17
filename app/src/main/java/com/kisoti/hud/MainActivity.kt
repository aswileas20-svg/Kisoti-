package com.kisoti.hud

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var btnDownload: Button
    private lateinit var btnBluetooth: Button
    private lateinit var btnListen: Button

    private var recognizer: OfflineRecognizer? = null
    private var isListening = false
    private var listenJob: Job? = null
    private var btOutputStream: OutputStream? = null

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTranscript = findViewById(R.id.tvTranscript)
        btnDownload = findViewById(R.id.btnDownload)
        btnBluetooth = findViewById(R.id.btnBluetooth)
        btnListen = findViewById(R.id.btnListen)

        requestAppPermissions()
        checkModelReady()

        btnDownload.setOnClickListener { downloadSwahiliModel() }
        btnBluetooth.setOnClickListener { selectBluetoothGlasses() }
        btnListen.setOnClickListener { toggleListening() }
    }

    private fun requestAppPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }

    private fun getModelDir(): File = File(filesDir, "swahili_model").apply { mkdirs() }

    private fun checkModelReady() {
        val dir = getModelDir()
        val enc = File(dir, "encoder.onnx")
        val dec = File(dir, "decoder.onnx")
        val tok = File(dir, "tokens.txt")

        if (enc.exists() && dec.exists() && tok.exists()) {
            tvStatus.text = "Hali: Model ipo tayari (Offline)"
            initRecognizer(enc.absolutePath, dec.absolutePath, tok.absolutePath)
        } else {
            tvStatus.text = "Hali: Bonyeza kitufe 1 kupakua model ya Kiswahili"
        }
    }

    private fun downloadSwahiliModel() {
        btnDownload.isEnabled = false
        tvStatus.text = "Hali: Inapakua model ya Kiswahili... (Subiri kidogo)"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val baseUrl = "https://huggingface.co"
                val dir = getModelDir()
                downloadFile("$baseUrl/small-encoder.int8.onnx", File(dir, "encoder.onnx"))
                downloadFile("$baseUrl/small-decoder.int8.onnx", File(dir, "decoder.onnx"))
                downloadFile("$baseUrl/small-tokens.txt", File(dir, "tokens.txt"))

                withContext(Dispatchers.Main) {
                    tvStatus.text = "Hali: Model imepakuliwa! Inawasha..."
                    checkModelReady()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Kosa la kupakua: ${e.message}"
                    btnDownload.isEnabled = true
                }
            }
        }
    }

    private fun downloadFile(urlStr: String, dest: File) {
        URL(urlStr).openStream().use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun initRecognizer(encPath: String, decPath: String, tokPath: String) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = encPath,
                    decoder = decPath,
                    language = "sw",
                    task = "transcribe"
                )
                val modelConfig = OfflineModelConfig(
                    whisper = whisperConfig,
                    tokens = tokPath,
                    numThreads = 2
                )
                val config = OfflineRecognizerConfig(modelConfig = modelConfig)
                recognizer = OfflineRecognizer(config = config)

                withContext(Dispatchers.Main) {
                    tvStatus.text = "Hali: Kisoti Iko Tayari Kufanya Kazi Offline!"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Kosa la AI Model: ${e.message}"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectBluetoothGlasses() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val paired: Set<BluetoothDevice> = adapter.bondedDevices
        if (paired.isEmpty()) {
            Toast.makeText(this, "Oanisha (Pair) miwani kwenye Settings za simu kwanza", Toast.LENGTH_LONG).show()
            return
        }
        val names = paired.map { it.name ?: it.address }.toTypedArray()
        val devices = paired.toList()

        AlertDialog.Builder(this)
            .setTitle("Chagua Miwani ya HUD")
            .setItems(names) { _, which ->
                connectToGlasses(devices[which])
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToGlasses(device: BluetoothDevice) {
        tvStatus.text = "Hali: Inaunganisha na ${device.name}..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket.connect()
                btOutputStream = socket.outputStream
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Hali: Imeunganishwa na ${device.name}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Imeshindwa kuunganisha Bluetooth: ${e.message}"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleListening() {
        if (recognizer == null) {
            Toast.makeText(this, "Pakua model ya Kiswahili kwanza!", Toast.LENGTH_SHORT).show()
            return
        }
        if (isListening) {
            isListening = false
            listenJob?.cancel()
            btnListen.text = "3. Anza Kusikiliza Mazungumzo"
            tvStatus.text = "Hali: Imesimama"
        } else {
            isListening = true
            btnListen.text = "Simamisha Kusikiliza"
            tvStatus.text = "Hali: Inasikiliza mazungumzo..."
            startAudioCapture()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        val sampleRate = 16000
        val chunkSamples = sampleRate * 3 // Vipande vya sekunde 3 kwa kasi
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        listenJob = CoroutineScope(Dispatchers.IO).launch {
            recorder.startRecording()
            val shortBuffer = ShortArray(chunkSamples)

            while (isActive && isListening) {
                var readTotal = 0
                while (readTotal < chunkSamples && isListening) {
                    val read = recorder.read(shortBuffer, readTotal, chunkSamples - readTotal)
                    if (read > 0) readTotal += read
                }

                if (readTotal > 0) {
                    val floatSamples = FloatArray(readTotal) { shortBuffer[it] / 32768.0f }
                    val stream = recognizer?.createStream()
                    stream?.acceptWaveform(floatSamples, sampleRate)
                    recognizer?.decode(stream)
                    val text = recognizer?.getResult(stream)?.text?.trim() ?: ""
                    stream?.release()

                    if (text.isNotEmpty()) {
                        sendToHud(text)
                        withContext(Dispatchers.Main) {
                            tvTranscript.append("\n• $text")
                        }
                    }
                }
            }
            recorder.stop()
            recorder.release()
        }
    }

    private fun sendToHud(message: String) {
        try {
            btOutputStream?.write("$message\n".toByteArray(Charsets.UTF_8))
            btOutputStream?.flush()
        } catch (_: Exception) {
        }
    }
}
