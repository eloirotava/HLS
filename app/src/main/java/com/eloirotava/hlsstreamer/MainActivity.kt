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

class MainActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {

    private val RTMP_URL = "rtmp://a.rtmp.youtube.com/live2/14r1-3asd-4ze4-uyhh-4m9f"

    private lateinit var openGlView: OpenGlView
    private lateinit var btnStart: Button
    private lateinit var tvLog: TextView

    private var rtmpCamera2: RtmpCamera2? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        tvLog = findViewById(R.id.tvLog)
        btnStart = findViewById(R.id.btnStart)

        // 1. Adiciona o Callback para saber quando a tela está pronta
        openGlView.holder.addCallback(this)

        // 2. Inicializa a biblioteca com proteção
        try {
            rtmpCamera2 = RtmpCamera2(openGlView, this)
        } catch (e: Exception) {
            log("Erro Fatal ao iniciar lib: ${e.message}")
        }

        btnStart.setOnClickListener {
            if (rtmpCamera2?.isStreaming == true) {
                stopStream()
            } else {
                startStream()
            }
        }
        
        checkPermissions()
    }

    // --- CICLO DE VIDA DA SUPERFÍCIE (O Segredo para não crashar) ---
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        // A tela existe! Agora é seguro ligar a câmera (se tiver permissão)
        if (hasPermissions()) {
            startPreviewSafe()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        // Se a câmera já estiver ligada, reinicia o preview para ajustar o tamanho
        if (rtmpCamera2?.isOnPreview == true) {
            rtmpCamera2?.stopPreview()
            startPreviewSafe()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Se a tela for destruída (minimizou), desliga tudo para não dar erro
        if (rtmpCamera2?.isStreaming == true) rtmpCamera2?.stopStream()
        if (rtmpCamera2?.isOnPreview == true) rtmpCamera2?.stopPreview()
    }

    // --- FUNÇÕES SEGURAS ---

    private fun startPreviewSafe() {
        try {
            if (rtmpCamera2?.isOnPreview == false) {
                log("Ligando Câmera...")
                rtmpCamera2?.startPreview()
            }
        } catch (e: Exception) {
            log("ERRO ao ligar Câmera: ${e.message}")
        }
    }

    private fun checkPermissions() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, perms, 1)
        }
    }
    
    private fun hasPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            // Permissão concedida agora. Tenta ligar se a tela já existir.
            if (openGlView.holder.surface.isValid) {
                startPreviewSafe()
            }
        }
    }

    private fun startStream() {
        if (rtmpCamera2?.isStreaming == false) {
            if (rtmpCamera2!!.prepareVideo(1280, 720, 30, 2500 * 1024, 0)) {
                rtmpCamera2!!.prepareAudio(128 * 1024, 44100, true, false, false)
                rtmpCamera2!!.startStream(RTMP_URL)
                btnStart.text = "CONECTANDO..."
            } else {
                log("720p falhou. Tentando 480p...")
                if (rtmpCamera2!!.prepareVideo(640, 480, 30, 1200 * 1024, 0)) {
                     rtmpCamera2!!.prepareAudio(128 * 1024, 44100, true, false, false)
                     rtmpCamera2!!.startStream(RTMP_URL)
                } else {
                    log("Erro: Câmera não suportada.")
                }
            }
        }
    }

    private fun stopStream() {
        if (rtmpCamera2?.isStreaming == true) {
            rtmpCamera2!!.stopStream()
            btnStart.text = "INICIAR LIVE"
            log("Parado.")
        }
    }

    // --- CALLBACKS ---

    override fun onConnectionStarted(url: String) {
        runOnUiThread { log("Conectando: $url") }
    }

    override fun onConnectionSuccess() {
        runOnUiThread {
            log("✅ AO VIVO! (YouTube)")
            btnStart.text = "PARAR LIVE"
            Toast.makeText(this, "Conectado!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            log("❌ ERRO: $reason")
            if (rtmpCamera2?.isStreaming == true) rtmpCamera2?.stopStream()
            btnStart.text = "TENTAR NOVAMENTE"
            
            // Dica de Debug
            if (reason.contains("Endpoint")) log("DICA: Verifique a URL ou Internet.")
        }
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        runOnUiThread {
            log("Desconectado.")
            btnStart.text = "INICIAR LIVE"
        }
    }

    override fun onAuthError() { runOnUiThread { log("Erro Auth.") } }
    override fun onAuthSuccess() { runOnUiThread { log("Auth OK.") } }

    private fun log(msg: String) {
        tvLog.append("$msg\n")
    }
}