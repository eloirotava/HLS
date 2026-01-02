package com.eloirotava.hlsstreamer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    // --- IMPORTANTE: SUA CHAVE AQUI ---
    // Mantenha o formato exato. Se o YouTube deu "https://a.upload.../cid=XYZ", cole aqui.
    private val YOUTUBE_URL = "https://a.upload.youtube.com/http_upload_hls?cid=0yhk-4u11-wexp-z4ak-c8pv&copy=0&file="
    
    // Configurações
    private val WIDTH = 1280
    private val HEIGHT = 720
    private val FPS = 30

    private lateinit var btnStart: Button
    private lateinit var textureView: TextureView
    private lateinit var tvLog: TextView // Novo campo de log
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    
    private var pipePath: String? = null
    private var pipeStream: FileOutputStream? = null
    private var isStreaming = false

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        btnStart = findViewById(R.id.btnStart)
        tvLog = findViewById(R.id.tvLog) // Vincula o log

        // Ativa logs do FFmpeg na tela
        FFmpegKitConfig.enableLogCallback { log ->
            runOnUiThread {
                tvLog.append(log.message + "\n")
                // Rola para o final (opcional, simplificado aqui)
            }
        }

        btnStart.setOnClickListener {
            if (isStreaming) stopStream() else startStream()
        }
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun startStream() {
        if (YOUTUBE_URL.contains("SUA-CHAVE")) {
            logToScreen("ERRO: Configure a chave no código!")
            return
        }

        startBackgroundThread()
        isStreaming = true
        btnStart.text = "PARAR STREAM"
        tvLog.text = "Iniciando...\n"

        // 1. Cria o Pipe
        pipePath = FFmpegKitConfig.registerNewFFmpegPipe(this)
        logToScreen("Pipe criado: $pipePath")
        
        // 2. Comando FFmpeg (MODO SOFTWARE SEGURO - H.264)
        // Usamos 'libx264' e 'ultrafast' para garantir que funcione primeiro.
        // Se funcionar, depois mudamos para 'hevc_mediacodec'.
        val cmd = "-f rawvideo -vcodec rawvideo -pix_fmt nv21 -s ${WIDTH}x${HEIGHT} -r $FPS " +
                "-i $pipePath " + 
                "-c:v libx264 -preset ultrafast -b:v 2000k -g 60 -keyint_min 60 " +
                "-c:a aac -b:a 128k -ar 44100 " + 
                "-f hls -method PUT -http_persistent 1 $YOUTUBE_URL"

        logToScreen("Comando: $cmd")
        
        FFmpegKit.executeAsync(cmd) { session ->
            val returnCode = session.returnCode
            val output = session.allLogsAsString
            runOnUiThread { 
                logToScreen("FFmpeg encerrou: $returnCode")
                if (!returnCode.isValueSuccess) {
                    logToScreen("ERRO FATAL. Verifique a URL e a internet.")
                }
                stopStream() 
            }
        }

        // 3. Abre a Câmera
        openCamera()
    }

    private fun stopStream() {
        isStreaming = false
        runOnUiThread { btnStart.text = "INICIAR STREAM" }
        
        try { captureSession?.stopRepeating(); captureSession?.abortCaptures() } catch (e: Exception){}
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        
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
            
            imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    if (isStreaming && pipePath != null) {
                        saveYUVToPipe(image)
                    }
                    image.close()
                }
            }, backgroundHandler)

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
        } catch (e: Exception) { 
            logToScreen("Erro Camera: ${e.message}")
        }
    }

    private fun createCameraPreview() {
        try {
            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(WIDTH, HEIGHT)
            val previewSurface = Surface(texture)
            val readerSurface = imageReader!!.surface

            cameraDevice?.createCaptureSession(listOf(previewSurface, readerSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    request.addTarget(previewSurface)
                    request.addTarget(readerSurface)
                    request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    session.setRepeatingRequest(request.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveYUVToPipe(image: android.media.Image) {
        try {
            if (pipeStream == null) {
                pipeStream = FileOutputStream(pipePath)
            }
            
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize) 
            
            pipeStream?.write(nv21)
        } catch (e: Exception) {
            // Ignora erros de buffer cheio para não travar
        }
    }

    private fun startBackgroundThread() {
        val thread = HandlerThread("CameraBackground")
        thread.start()
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (e: InterruptedException) { e.printStackTrace() }
        backgroundThread = null
        backgroundHandler = null
    }
}