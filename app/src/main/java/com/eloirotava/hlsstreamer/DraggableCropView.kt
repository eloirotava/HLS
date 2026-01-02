package com.eloirotava.hlsstreamer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

interface OnCropChangeListener {
    fun onCropChanged(xPercent: Float, yPercent: Float, wPercent: Float, hPercent: Float)
}

class DraggableCropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var listener: OnCropChangeListener? = null

    // CÁLCULO DA PROPORÇÃO (CRUCIAL!)
    // Entrada: 1440 (L) x 1080 (A)
    // Saída:   1280 (L) x  720 (A)
    
    // Quanto da largura da tela o recorte ocupa? 1280 / 1440 = 0.8888...
    private val widthRatio = 0.8888f
    
    // Quanto da altura da tela o recorte ocupa? 720 / 1080 = 0.6666...
    private val heightRatio = 0.6666f

    // Posição do Centro (0.0 a 1.0)
    private var centerX = 0.5f
    private var centerY = 0.5f

    private val borderPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        
        if (viewW == 0f || viewH == 0f) return

        // Calcula o tamanho do retângulo verde na tela do celular
        // Agora ele terá formato 16:9 mesmo numa tela 4:3
        val rectW = viewW * widthRatio
        val rectH = viewH * heightRatio

        // Proteção de limites para o retângulo não sair da tela
        val minX = widthRatio / 2
        val maxX = 1f - minX
        val minY = heightRatio / 2
        val maxY = 1f - minY
        
        centerX = centerX.coerceIn(minX, maxX)
        centerY = centerY.coerceIn(minY, maxY)

        // Desenha
        val left = (viewW * centerX) - (rectW / 2)
        val top = (viewH * centerY) - (rectH / 2)
        
        rect.set(left, top, left + rectW, top + rectH)

        // Máscara Escura
        canvas.drawRect(0f, 0f, viewW, top, bgPaint) // Cima
        canvas.drawRect(0f, top + rectH, viewW, viewH, bgPaint) // Baixo
        canvas.drawRect(0f, top, left, top + rectH, bgPaint) // Esquerda
        canvas.drawRect(left + rectW, top, viewW, top + rectH, bgPaint) // Direita

        // Borda Verde
        canvas.drawRect(rect, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {
                // Onde o dedo tocou?
                centerX = event.x / width
                centerY = event.y / height
                
                // Recalcula limites
                val minX = widthRatio / 2
                val maxX = 1f - minX
                val minY = heightRatio / 2
                val maxY = 1f - minY
                
                centerX = centerX.coerceIn(minX, maxX)
                centerY = centerY.coerceIn(minY, maxY)

                invalidate() // Redesenha
                
                // Avisa a MainActivity a posição do canto Superior-Esquerdo (Top-Left)
                // É isso que o CropFilter espera
                listener?.onCropChanged(
                    centerX - (widthRatio / 2),
                    centerY - (heightRatio / 2),
                    widthRatio, 
                    heightRatio
                )
                return true
            }
        }
        return true
    }
}