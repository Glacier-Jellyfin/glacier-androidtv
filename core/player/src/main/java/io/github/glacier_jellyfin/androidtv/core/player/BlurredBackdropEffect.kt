package io.github.glacier_jellyfin.androidtv.core.player

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Puts a portrait video in the middle of a 16:9 frame of the same height, over a dimmed and blurred copy of itself. The
 * copy is the video scaled up evenly until it covers the frame (so it is cropped, not stretched) and is blurred on a
 * texture of about 1/8 of the frame instead of at full size, which is what makes this cheap: crop and shrink into the small
 * texture, blur it in two passes, then one linear upscaled read per pixel of the frame, and the sharp video on top.
 */
@OptIn(UnstableApi::class)
class BlurredBackdropEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = try {
        BlurredBackdropProgram()
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException.from(e)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/** The sizes of [BlurredBackdropEffect], as plain functions. */
object BackdropGeometry {
    const val FRAME_ASPECT = 16f / 9f
    const val DIM = 0.55f
    const val BLUR_SIGMA = 4f
    const val BLUR_RADIUS = 8
    private const val BLUR_DIVISOR = 8
    private const val MIN_BLUR_WIDTH = 192
    private const val MAX_BLUR_WIDTH = 320

    /** One blur tap: where to read, in texels from the centre, and its weight. */
    data class Tap(val offset: Float, val weight: Float)

    /** A rectangle of the input in texture coordinates. */
    data class Window(
        val u0: Float,
        val v0: Float,
        val du: Float,
        val dv: Float,
    )

    /** The half size of a rectangle in the frame, as a fraction of the frame (1 = the whole of it). */
    data class Extent(val halfWidth: Float, val halfHeight: Float)

    /** Frame of the input's height, 16:9 (wider if the input already is), width made even. */
    fun outputSize(inputWidth: Int, inputHeight: Int): Pair<Int, Int> {
        val aspect = max(FRAME_ASPECT, inputWidth.toFloat() / inputHeight)
        val width = (inputHeight * aspect / 2f).roundToInt() * 2
        return width to inputHeight
    }

    /** The small texture the blur works on: about 1/8 of the frame, between 192 and 320 wide, same aspect. */
    fun blurSize(outputWidth: Int, outputHeight: Int): Pair<Int, Int> {
        val width = (outputWidth / BLUR_DIVISOR).coerceIn(MIN_BLUR_WIDTH, MAX_BLUR_WIDTH)
        val height = (width.toFloat() * outputHeight / outputWidth).roundToInt().coerceAtLeast(2)
        return width to height
    }

    /** The part of the input that covers the frame when scaled evenly: the input's width fills a 16:9 frame from a portrait. */
    fun coverWindow(
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): Window {
        val scale = max(outputWidth.toFloat() / inputWidth, outputHeight.toFloat() / inputHeight)
        val du = min(1f, outputWidth / (inputWidth * scale))
        val dv = min(1f, outputHeight / (inputHeight * scale))
        return Window((1f - du) / 2f, (1f - dv) / 2f, du, dv)
    }

    /** The sharp input in the frame: full height, its own aspect, in the middle (never bigger than the frame). */
    fun sharpExtent(
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): Extent {
        val inputAspect = inputWidth.toFloat() / inputHeight
        val outputAspect = outputWidth.toFloat() / outputHeight
        return Extent(min(1f, inputAspect / outputAspect), min(1f, outputAspect / inputAspect))
    }

    /**
     * The Gaussian of [sigma] over [radius] texels (an even number) as the centre weight and pairs of neighbours merged into
     * one linearly filtered read each; the taps are mirrored around the centre, so the weights sum to 1 with the centre
     * counted once and each tap twice.
     */
    fun blurKernel(sigma: Float, radius: Int): Pair<Float, List<Tap>> {
        val raw = FloatArray(radius + 1) { exp(-(it * it) / (2f * sigma * sigma)) }
        var total = raw[0]
        for (i in 1..radius) total += 2f * raw[i]
        val weights = FloatArray(radius + 1) { raw[it] / total }
        val taps = (1..radius step 2).map { near ->
            val pair = weights[near] + weights[near + 1]
            Tap((near * weights[near] + (near + 1) * weights[near + 1]) / pair, pair)
        }
        return weights[0] to taps
    }
}

@OptIn(UnstableApi::class)
private class BlurredBackdropProgram : BaseGlShaderProgram(false, 1) {
    private val downsample = GlProgram(VERTEX, DOWNSAMPLE)
    private val blur = GlProgram(VERTEX, blurShader())
    private val copy = GlProgram(VERTEX, COPY_FRAGMENT)
    private val quad = GlUtil.getNormalizedCoordinateBounds()

    private var outputWidth = 0
    private var outputHeight = 0
    private var blurWidth = 0
    private var blurHeight = 0
    private var window = BackdropGeometry.Window(0f, 0f, 1f, 1f)
    private var sharp = BackdropGeometry.Extent(1f, 1f)
    private val textures = IntArray(2)
    private val framebuffers = IntArray(2)
    private var allocated = false

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        try {
            val (outW, outH) = BackdropGeometry.outputSize(inputWidth, inputHeight)
            outputWidth = outW
            outputHeight = outH
            window = BackdropGeometry.coverWindow(inputWidth, inputHeight, outW, outH)
            sharp = BackdropGeometry.sharpExtent(inputWidth, inputHeight, outW, outH)
            val (blurW, blurH) = BackdropGeometry.blurSize(outW, outH)
            if (!allocated || blurW != blurWidth || blurH != blurHeight) {
                freeTextures()
                blurWidth = blurW
                blurHeight = blurH
                for (i in textures.indices) {
                    textures[i] = GlUtil.createTexture(blurW, blurH, false)
                    framebuffers[i] = GlUtil.createFboForTexture(textures[i])
                }
                allocated = true
            }
            return Size(outW, outH)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException.from(e)
        }
    }

    override fun shouldClearTextureBuffer(): Boolean = false

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val target = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, target, 0)
            GLES20.glDisable(GLES20.GL_BLEND)
            GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, inputTexId, GLES20.GL_LINEAR)

            // 1. The part of the input that covers the frame, shrunk into the small texture.
            GlUtil.focusFramebufferUsingCurrentContext(framebuffers[0], blurWidth, blurHeight)
            downsample.use()
            downsample.setSamplerTexIdUniform("uTex", inputTexId, 0)
            downsample.setFloatsUniform("uStep", floatArrayOf(window.du / blurWidth, window.dv / blurHeight))
            draw(downsample, FULL, floatArrayOf(window.u0, window.v0, window.du, window.dv))

            // 2. Blur it across, then down.
            blurPass(textures[0], framebuffers[1], 1f / blurWidth, 0f)
            blurPass(textures[1], framebuffers[0], 0f, 1f / blurHeight)

            // 3. The frame: the blurred copy upscaled with linear filtering and dimmed, the sharp input in the middle.
            GlUtil.focusFramebufferUsingCurrentContext(target[0], outputWidth, outputHeight)
            copy.use()
            copy.setSamplerTexIdUniform("uTex", textures[0], 0)
            copy.setFloatUniform("uFactor", BackdropGeometry.DIM)
            draw(copy, FULL, WHOLE)
            copy.setSamplerTexIdUniform("uTex", inputTexId, 0)
            copy.setFloatUniform("uFactor", 1f)
            draw(copy, floatArrayOf(0f, 0f, sharp.halfWidth, sharp.halfHeight), WHOLE)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException.from(e, presentationTimeUs)
        }
    }

    private fun blurPass(
        sourceTexture: Int,
        targetFramebuffer: Int,
        stepX: Float,
        stepY: Float,
    ) {
        GlUtil.focusFramebufferUsingCurrentContext(targetFramebuffer, blurWidth, blurHeight)
        blur.use()
        blur.setSamplerTexIdUniform("uTex", sourceTexture, 0)
        blur.setFloatsUniform("uStep", floatArrayOf(stepX, stepY))
        draw(blur, FULL, WHOLE)
    }

    private fun draw(
        program: GlProgram,
        rect: FloatArray,
        uv: FloatArray,
    ) {
        program.setFloatsUniform("uRect", floatArrayOf(rect[0], rect[1], rect[2], rect[3]))
        program.setFloatsUniform("uUv", uv)
        program.setBufferAttribute("aFramePosition", quad, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    }

    private fun freeTextures() {
        if (!allocated) return
        for (i in textures.indices) {
            GlUtil.deleteFbo(framebuffers[i])
            GlUtil.deleteTexture(textures[i])
        }
        allocated = false
    }

    override fun release() {
        super.release()
        try {
            freeTextures()
            downsample.delete()
            blur.delete()
            copy.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException.from(e)
        }
    }

    private companion object {
        const val QUAD_VERTICES = 4
        val FULL = floatArrayOf(0f, 0f, 1f, 1f)
        val WHOLE = floatArrayOf(0f, 0f, 1f, 1f)

        // uRect: centre x, y and half width, height in the frame (clip space); uUv: first u, v and the size of the window.
        const val VERTEX = """
            attribute vec4 aFramePosition;
            uniform vec4 uRect;
            uniform vec4 uUv;
            varying vec2 vTex;
            void main() {
              gl_Position = vec4(uRect.xy + aFramePosition.xy * uRect.zw, 0.0, 1.0);
              vTex = uUv.xy + (aFramePosition.xy * 0.5 + 0.5) * uUv.zw;
            }
        """

        const val COPY_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uFactor;
            varying vec2 vTex;
            void main() {
              gl_FragColor = vec4(texture2D(uTex, vTex).rgb * uFactor, 1.0);
            }
        """

        // 4 x 4 linear reads spread over one texel of the small texture, so the shrinking averages instead of skipping.
        const val DOWNSAMPLE = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uStep;
            varying vec2 vTex;
            void main() {
              vec3 sum = vec3(0.0);
              for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                  vec2 at = (vec2(float(x), float(y)) - 1.5) * 0.25 * uStep;
                  sum += texture2D(uTex, vTex + at).rgb;
                }
              }
              gl_FragColor = vec4(sum / 16.0, 1.0);
            }
        """

        fun blurShader(): String {
            val (centre, taps) = BackdropGeometry.blurKernel(BackdropGeometry.BLUR_SIGMA, BackdropGeometry.BLUR_RADIUS)
            val reads = taps.joinToString("\n") {
                val offset = String.format(Locale.US, "%.6f", it.offset)
                val weight = String.format(Locale.US, "%.6f", it.weight)
                "  sum += (texture2D(uTex, vTex + uStep * $offset).rgb + " +
                    "texture2D(uTex, vTex - uStep * $offset).rgb) * $weight;"
            }
            return """
                precision mediump float;
                uniform sampler2D uTex;
                uniform vec2 uStep;
                varying vec2 vTex;
                void main() {
                  vec3 sum = texture2D(uTex, vTex).rgb * ${String.format(Locale.US, "%.6f", centre)};
                $reads
                  gl_FragColor = vec4(sum, 1.0);
                }
            """
        }
    }
}
