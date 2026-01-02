package com.eloirotava.hlsstreamer

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    // =========================================================================
    // CONFIGURAÇÃO
    // =========================================================================
    // Cole APENAS a chave HLS aqui (o código depois do 'cid=')
    private val YOUTUBE_CID = "5jj0-eeq5-5a66-wwws-3rkk" 
    
    // Configurações de Vídeo
    private val WIDTH = 1280
    private val HEIGHT = 720
    private val FPS = 30
    private val VIDEO_BITRATE = 2000000 // 2Mbps

    // Configurações de Áudio
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val AUDIO_BITRATE = "128k"

    // =========================================================================

    private lateinit var btnStart: Button
    private lateinit var textureView: TextureView
    private lateinit var tvLog: TextView
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    
    // Encoder de Vídeo (Hardware)
    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    
    // Gravador de Áudio
    private var audioRecord: AudioRecord? = null
    
    // Pipes e Controle
    private var videoPipePath: String? = null
    private var videoPipeStream: FileOutputStream? = null
    private var audioPipePath: String? = null
    private var audioPipeStream: FileOutputStream? = null
    
    private var isStreaming = false
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        textureView = findViewById(R.id.textureView)
        btnStart = findViewById(R.id.btnStart)
        tvLog = findViewById(R.id.tvLog)

        FFmpegKitConfig.enableLogCallback { log ->
            runOnUiThread { tvLog.append(log.message + "\n") }
        }

        btnStart.setOnClickListener {
            if (isStreaming) stopStream() else startStream()
        }
        
        // Solicita permissões (Câmera e Áudio)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun startStream() {
        if (YOUTUBE_CID.contains("COLE-SUA")) {
            logToScreen("ERRO: Configure o CID na linha 27!")
            return
        }

        startBackgroundThread()
        isStreaming = true
        btnStart.text = "PARAR STREAM"
        tvLog.text = "Iniciando A/V Stream...\n"

        // 1. Cria Pipes (Um para vídeo, um para áudio)
        videoPipePath = FFmpegKitConfig.registerNewFFmpegPipe(this)
        videoPipeStream = FileOutputStream(videoPipePath)
        
        audioPipePath = FFmpegKitConfig.registerNewFFmpegPipe(this)
        audioPipeStream = FileOutputStream(audioPipePath)

        // 2. Prepara URLs
        val cleanCid = YOUTUBE_CID.trim()
        val baseUrl = "http://a.upload.youtube.com/http_upload_hls?cid=$cleanCid&copy=0&file="
        val segmentUrl = "${baseUrl}seq%d.ts"
        val playlistUrl = "${baseUrl}master.m3u8"

        // 3. Inicia Captura (Hardware Video + Mic Audio)
        startVideoCodec()
        startAudioCapture()

        // 4. Inicia FFmpeg
        // Explicação do comando:
        // -f h264 -i videoPipe: Lê vídeo bruto H.264 do hardware
        // -f s16le -ar 44100 -ac 1 -i audioPipe: Lê áudio bruto PCM do mic
        // -c:v copy: Não gasta CPU com vídeo (já vem pronto)
        // -c:a aac: Codifica o áudio para AAC (leve)
        // -map 0:v -map 1:a: Garante que pega vídeo do input 0 e áudio do input 1
        val cmd = "-f h264 -i $videoPipePath " + 
                "-f s16le -ar 44100 -ac 1 -i $audioPipePath " +
                "-c:v copy " +
                "-c:a aac -b:a $AUDIO_BITRATE " +
                "-map 0:v -map 1:a " +
                "-f hls -hls_time 2 -hls_list_size 4 " +
                "-method PUT -http_persistent 1 " +
                "-http_opts tls_verify=0 " + 
                "-hls_segment_filename \"$segmentUrl\" \"$playlistUrl\""

        logToScreen("Iniciando FFmpeg...")
        FFmpegKit.executeAsync(cmd) { session ->
            val returnCode = session.returnCode
            runOnUiThread { 
                logToScreen("FFmpeg Fim: $returnCode")
                stopStream() 
            }
        }

        // 5. Liga a câmera na Surface
        openCamera()
    }

    private fun startVideoCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe a cada 1s

            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoCodec?.createInputSurface()
            videoCodec?.start()

            // Thread dedicada para tirar dados do Codec e jogar no Pipe
            thread(start = true) {
                val bufferInfo = MediaCodec.BufferInfo()
                while (isStreaming) {
                    try {
                        val codec = videoCodec ?: break
                        val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000)
                        
                        if (outputBufferId >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputBufferId)
                            if (outputBuffer != null) {
                                val outData = ByteArray(bufferInfo.size)
                                outputBuffer.get(outData)
                                videoPipeStream?.write(outData)
                            }
                            codec.releaseOutputBuffer(outputBufferId, false)
                        }
                    } catch (e: Exception) {
                        // Ignora erros ao fechar
                    }
                }
            }
        } catch (e: Exception) {
            logToScreen("Erro Video Codec: ${e.message}")
        }
    }

    private fun startAudioCapture() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            logToScreen("Sem permissão de áudio!")
            return
        }

        thread(start = true) {
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val bufferSize = minBufferSize.coerceAtLeast(4096)
                
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                audioRecord?.startRecording()

                val buffer = ByteArray(2048) // Buffer pequeno para menor latência
                while (isStreaming) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        audioPipeStream?.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { logToScreen("Erro Audio: ${e.message}") }
            }
        }
    }

    private fun stopStream() {
        isStreaming = false
        runOnUiThread { btnStart.text = "INICIAR STREAM" }
        
        // 1. Para Câmera
        try { captureSession?.stopRepeating(); captureSession?.abortCaptures() } catch (e: Exception){}
        captureSession?.close()
        cameraDevice?.close()
        
        // 2. Para Codec de Vídeo
        try {
            videoCodec?.stop()
            videoCodec?.release()
        } catch(e: Exception){}
        videoCodec = null
        
        // 3. Para Áudio
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch(e: Exception){}
        audioRecord = null
        
        // 4. Fecha Pipes
        try { videoPipeStream?.close() } catch(e: Exception){}
        try { audioPipeStream?.close() } catch(e: Exception){}
        
        // 5. Cancela FFmpeg
        FFmpegKit.cancel()
        stopBackgroundThread()
    }

    private fun logToScreen(msg: String) {
        tvLog.append("$msg\n")
    }

    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0] 
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCameraPreview()
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close() }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close() }
                }, backgroundHandler)
            }
        } catch (e: Exception) { logToScreen("Cam: ${e.message}") }
    }

    private fun createCameraPreview() {
        try {
            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(WIDTH, HEIGHT)
            val previewSurface = Surface(texture)
            
            // Envia para Tela E para o Encoder (Surface)
            val targets = mutableListOf(previewSurface)
            inputSurface?.let { targets.add(it) }

            cameraDevice?.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    request.addTarget(previewSurface)
                    inputSurface?.let { request.addTarget(it) }
                    
                    request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    session.setRepeatingRequest(request.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startBackgroundThread() {
        val thread = HandlerThread("CameraBackground")
        thread.start()
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (e: InterruptedException) { }
        backgroundThread = null
        backgroundHandler = null
    }
}