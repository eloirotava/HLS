package com.eloirotava.hlsstreamer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

// Callback para avisar quando mudou a posição
interface OnCropChangeListener {
    fun onCropChanged(xPercent: Float, yPercent: Float, wPercent: Float, hPercent: Float)
}

class DraggableCropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var listener: OnCropChangeListener? = null

    // Tamanho do retângulo de seleção (fixo em 16:9 para este exemplo, simulando um "Zoom")
    // Você pode diminuir esses valores para dar mais "Zoom"
    private val cropWidthPercent = 0.6f 
    private val cropHeightPercent = 0.6f * (9f / 16f) * (4f / 3f) // Ajuste de aspecto 

    // Posição atual (0.0 a 1.0)
    private var centerX = 0.5f
    private var centerY = 0.5f

    private val borderPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#80000000") // Fundo escuro semitransparente
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        // Calcula tamanho do retângulo em pixels da tela
        val rectW = viewW * cropWidthPercent
        val rectH = rectW * (9f/16f) // Força proporção 16:9 (formato do YouTube)

        // Calcula posição do topo/esquerda baseado no centro
        val left = (viewW * centerX) - (rectW / 2)
        val top = (viewH * centerY) - (rectH / 2)
        
        rect.set(left, top, left + rectW, top + rectH)

        // Desenha o "escuro" em volta
        canvas.drawRect(0f, 0f, viewW, top, bgPaint) // Topo
        canvas.drawRect(0f, top + rectH, viewW, viewH, bgPaint) // Base
        canvas.drawRect(0f, top, left, top + rectH, bgPaint) // Esquerda
        canvas.drawRect(left + rectW, top, viewW, top + rectH, bgPaint) // Direita

        // Desenha a borda verde
        canvas.drawRect(rect, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {
                // Atualiza o centro para onde o dedo está
                centerX = event.x / width
                centerY = event.y / height
                
                // Limites para não sair da tela
                centerX = centerX.coerceIn(0.1f, 0.9f)
                centerY = centerY.coerceIn(0.1f, 0.9f)

                invalidate() // Redesenha
                
                // Avisa a MainActivity para atualizar o filtro
                listener?.onCropChanged(
                    centerX - (cropWidthPercent / 2), // X inicial (percentual)
                    centerY, // Y (aproximado, refinamos no Main)
                    cropWidthPercent, 
                    0f // Altura calculada dinamicamente
                )
                return true
            }
        }
        return true
    }
}