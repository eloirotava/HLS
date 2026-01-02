package com.eloirotava.hlsstreamer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import com.pedro.encoder.input.gl.render.filters.CropFilterRender

class MainActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback, OnCropChangeListener {

    private val RTMP_URL = "rtmp://a.rtmp.youtube.com/live2/14r1-3asd-4ze4-uyhh-4m9f"

    private lateinit var openGlView: OpenGlView
    private lateinit var cropView: DraggableCropView
    private lateinit var btnStart: Button
    private lateinit var tvLog: TextView

    private var rtmpCamera2: RtmpCamera2? = null
    
    private val cropFilter = CropFilterRender()

    // --- VOLTAMOS PARA 4:3 (A resolução que funcionou) ---
    private val CAM_W = 1440
    private val CAM_H = 1080

    // Saída continua 720p (16:9)
    private val CROP_W = 1280f
    private val CROP_H = 720f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        cropView = findViewById(R.id.cropView)
        tvLog = findViewById(R.id.tvLog)
        btnStart = findViewById(R.id.btnStart)

        openGlView.holder.addCallback(this)
        cropView.listener = this 

        rtmpCamera2 = RtmpCamera2(openGlView, this)
        
        btnStart.setOnClickListener {
            if (rtmpCamera2?.isStreaming == true) stopStream() else startStream()
        }
        
        checkPermissions()
    }

    override fun onCropChanged(xPercent: Float, yPercent: Float, wPercent: Float, hPercent: Float) {
        // Mapeia o toque (0..1) para a resolução da câmera (1440x1080)
        var realX = xPercent * CAM_W
        var realY = yPercent * CAM_H

        if (realX < 0) realX = 0f
        if (realX + CROP_W > CAM_W) realX = (CAM_W - CROP_W).toFloat()
        
        if (realY < 0) realY = 0f
        if (realY + CROP_H > CAM_H) realY = (CAM_H - CROP_H).toFloat()

        cropFilter.setCropArea(realX, realY, CROP_W, CROP_H)
    }

    private fun startStream() {
        if (rtmpCamera2?.isStreaming == false) {
            
            // CONFIGURAÇÃO QUE FUNCIONAVA:
            // Bitrate: 1500kbps (Seguro)
            // Profile: Automático (Tiramos o High Profile forçado)
            // Rotação: 0 (Pois o app já está em Landscape no Manifest)
            
            val bitrate = 1500 * 1024
            
            if (rtmpCamera2!!.prepareVideo(1280, 720, 30, bitrate, 2, 0)) {
                // Áudio ESTÉREO (true) - Importante, pois o Mono falhou na última
                rtmpCamera2!!.prepareAudio(64 * 1024, 44100, true, false, false)
                
                rtmpCamera2!!.startStream(RTMP_URL)
                btnStart.text = "CONECTANDO..."
            } else {
                log("Erro: 720p falhou.")
            }
        }
    }

    private fun startPreview() {
        log("Iniciando Câmera 4:3...")
        // Abre a câmera em 1440x1080 (Isso garante imagem no S22)
        rtmpCamera2?.startPreview(CAM_W, CAM_H)
        
        // Configura o filtro imediatamente
        val startX = (CAM_W - CROP_W) / 2
        val startY = (CAM_H - CROP_H) / 2
        cropFilter.setCropArea(startX, startY, CROP_W, CROP_H)
        rtmpCamera2?.glInterface?.setFilter(cropFilter)
    }

    private fun stopStream() {
        rtmpCamera2?.stopStream()
        btnStart.text = "INICIAR LIVE"
        log("Parado.")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (hasPermissions()) startPreview()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (rtmpCamera2?.isOnPreview == true) {
            rtmpCamera2?.stopPreview()
            startPreview()
        }
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (rtmpCamera2?.isStreaming == true) rtmpCamera2?.stopStream()
        if (rtmpCamera2?.isOnPreview == true) rtmpCamera2?.stopPreview()
    }

    private fun checkPermissions() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, 
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }
    }
    
    private fun hasPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startPreview()
        }
    }

    override fun onConnectionStarted(url: String) { runOnUiThread { log("Conectando...") } }
    override fun onConnectionSuccess() { runOnUiThread { log("✅ AO VIVO! (Modo Seguro)"); btnStart.text = "PARAR" } }
    override fun onConnectionFailed(reason: String) { runOnUiThread { log("❌ Falha: $reason"); btnStart.text = "TENTAR" } }
    override fun onDisconnect() { runOnUiThread { log("Desconectado."); btnStart.text = "INICIAR" } }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onNewBitrate(bitrate: Long) {}

    private fun log(msg: String) { tvLog.append("$msg\n") }
}