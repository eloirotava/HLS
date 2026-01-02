package com.eloirotava.hlsstreamer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.arthenica.ffmpegkit.FFmpegKit // O pacote interno geralmente mantem o nome original
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    // --- CONFIGURAÇÃO ---
    // COLOQUE SUA CHAVE AQUI! Mantenha o formato da URL.
    private val YOUTUBE_URL = "https://a.upload.youtube.com/http_upload_hls?cid=0yhk-4u11-wexp-z4ak-c8pv&copy=0&file="
    
    // Resolução (720p é um bom equilíbrio para teste)
    private val WIDTH = 1280
    private val HEIGHT = 720
    private val FPS = 30

    private lateinit var btnStart: Button
    private lateinit var textureView: TextureView
    
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

        btnStart.setOnClickListener {
            if (isStreaming) stopStream() else startStream()
        }
        
        // Pede permissões ao abrir
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun startStream() {
        if (YOUTUBE_URL.contains("SUA-CHAVE")) {
            Toast.makeText(this, "ERRO: Configure a chave no código!", Toast.LENGTH_LONG).show()
            return
        }

        startBackgroundThread()
        isStreaming = true
        btnStart.text = "PARAR STREAM"

        // 1. Cria o Pipe
        pipePath = FFmpegKitConfig.registerNewFFmpegPipe(this)
        
        // 2. Inicia FFmpeg (Comando otimizado para H.265 via Hardware)
        val cmd = "-f rawvideo -vcodec rawvideo -pix_fmt nv21 -s ${WIDTH}x${HEIGHT} -r $FPS " +
                "-i $pipePath " +
                "-c:v hevc_mediacodec -b:v 2M -g 60 -keyint_min 60 " + // Hardware Encoding H.265
                "-c:a aac -ar 44100 -b:a 128k " + 
                "-f hls -hls_time 2 -hls_list_size 4 -http_persistent 1 -method PUT $YOUTUBE_URL"

        Log.d("HLS", "Iniciando FFmpeg: $cmd")
        
        FFmpegKit.executeAsync(cmd) { session ->
            Log.d("HLS", "FFmpeg terminou com código: ${session.returnCode}")
            runOnUiThread { 
                if (isStreaming) stopStream() // Só para se morrer sozinho
            }
        }

        // 3. Abre a Câmera
        openCamera()
    }

    private fun stopStream() {
        isStreaming = false
        runOnUiThread { btnStart.text = "INICIAR STREAM HLS" }
        
        try { captureSession?.stopRepeating(); captureSession?.abortCaptures() } catch (e: Exception){}
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        
        // Mata o FFmpeg
        FFmpegKit.cancel()
        
        stopBackgroundThread()
        Toast.makeText(this, "Stream Parado", Toast.LENGTH_SHORT).show()
    }

    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0] // Traseira
            
            // Configura o ImageReader para receber os frames
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
                // CORREÇÃO AQUI: Passando cameraId e garantindo o Handler
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCameraPreview()
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close() }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close() }
                }, backgroundHandler)
            }
        } catch (e: Exception) { e.printStackTrace() }
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
            // Log.e("HLS", "Erro pipe: ${e.message}")
        }
    }

    private fun startBackgroundThread() {
        val thread = HandlerThread("CameraBackground")
        thread.start()
        backgroundThread = thread
        // CORREÇÃO AQUI: Usando o looper do objeto thread criado localmente (seguro)
        backgroundHandler = Handler(thread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (e: InterruptedException) { e.printStackTrace() }
        backgroundThread = null
        backgroundHandler = null
    }
}