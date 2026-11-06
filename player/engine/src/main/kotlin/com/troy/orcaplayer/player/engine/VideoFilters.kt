package com.troy.orcaplayer.player.engine

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.SingleColorLut
import com.troy.orcaplayer.core.model.VideoFilter
import kotlin.math.pow

/** Media3 GPU effects for a [VideoFilter] (20.6). */
@OptIn(UnstableApi::class)
object VideoFilters {
    private const val LUT = 17

    fun effects(f: VideoFilter): List<Effect> {
        if (f.isNeutral) return emptyList()
        val v = f.clamped()
        val list = mutableListOf<Effect>()
        if (v.gamma != 0f) list += SingleColorLut.createFromCube(gammaCube(v.gammaExponent))
        if (v.brightness != 0f) list += Brightness(v.brightness * 0.5f)
        if (v.contrast != 0f) list += Contrast(v.contrast * 0.6f)
        if (v.hue != 0f || v.saturation != 0f) {
            list += HslAdjustment.Builder().adjustHue(v.hue).adjustSaturation(v.saturation * 100f).build()
        }
        if (v.sharpness > 0f) list += Sharpen(v.sharpness)
        return list
    }

    private fun gammaCube(exp: Double): Array<Array<IntArray>> {
        val table = IntArray(LUT) { i -> ((i.toDouble() / (LUT - 1)).pow(exp) * 255 + 0.5).toInt().coerceIn(0, 255) }
        return Array(LUT) { r -> Array(LUT) { g -> IntArray(LUT) { b -> (0xFF shl 24) or (table[r] shl 16) or (table[g] shl 8) or table[b] } } }
    }
}

/**
 * Contrast-adaptive sharpening (after AMD FidelityFX CAS, MIT licence): sharpens edges more where
 * local contrast is low, so it doesn't ring around strong edges.
 */
@OptIn(UnstableApi::class)
class Sharpen(private val amount: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = SharpenProgram(amount.coerceIn(0f, 1f), useHdr)
}

@OptIn(UnstableApi::class)
private class SharpenProgram(private val amount: Float, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram = try {
        GlProgram(VERTEX, FRAGMENT).also {
            it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var width = 1
    private var height = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth.coerceAtLeast(1)
        height = inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uTexel", floatArrayOf(1f / width, 1f / height))
            program.setFloatUniform("uAmount", amount)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uAmount;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c4 = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = c4.rgb;
  vec3 n = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -uTexel.y)).rgb;
  vec3 s = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexel.y)).rgb;
  vec3 e = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexel.x, 0.0)).rgb;
  vec3 w = texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexel.x, 0.0)).rgb;
  vec3 mn = min(c, min(min(n, s), min(e, w)));
  vec3 mx = max(c, max(max(n, s), max(e, w)));
  vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));
  vec3 k = amp * (-1.0 / mix(8.0, 5.0, uAmount));
  vec3 outc = (c + (n + s + e + w) * k) / (1.0 + 4.0 * k);
  gl_FragColor = vec4(clamp(outc, 0.0, 1.0), c4.a);
}
"""
    }
}
