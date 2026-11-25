package com.promulticam.app

import android.opengl.GLES11Ext
import android.opengl.GLES20
import org.webrtc.RendererCommon
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/* Draws camera pictures with a colour style applied (and an alpha for fades) */
class LookDrawer : RendererCommon.GlDrawer {
    @Volatile var cm: FloatArray = ID.copyOf()
    @Volatile var co: FloatArray = floatArrayOf(0f, 0f, 0f, 0f)
    var alpha = 1f
    fun setLook(f: String) { val (m, o) = Looks.matrix(f); cm = m; co = o }

    private class Prog(val id: Int, val pos: Int, val tc: Int, val texMat: Int, val cm: Int, val co: Int, val al: Int, val s: IntArray)
    private var oes: Prog? = null; private var rgb: Prog? = null; private var yuv: Prog? = null

    override fun drawOes(tex: Int, texMatrix: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) {
        val p = oes ?: build(FRAG_OES, arrayOf("tex")).also { oes = it }
        use(p, texMatrix, vx, vy, vw, vh)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex); GLES20.glUniform1i(p.s[0], 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }
    override fun drawRgb(tex: Int, texMatrix: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) {
        val p = rgb ?: build(FRAG_RGB, arrayOf("tex")).also { rgb = it }
        use(p, texMatrix, vx, vy, vw, vh)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex); GLES20.glUniform1i(p.s[0], 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }
    override fun drawYuv(tex: IntArray, texMatrix: FloatArray, fw: Int, fh: Int, vx: Int, vy: Int, vw: Int, vh: Int) {
        val p = yuv ?: build(FRAG_YUV, arrayOf("y_tex", "u_tex", "v_tex")).also { yuv = it }
        use(p, texMatrix, vx, vy, vw, vh)
        for (i in 0..2) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]); GLES20.glUniform1i(p.s[i], i) }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        for (i in 0..2) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0) }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }
    override fun release() { listOfNotNull(oes, rgb, yuv).forEach { GLES20.glDeleteProgram(it.id) }; oes = null; rgb = null; yuv = null }

    private fun use(p: Prog, texMatrix: FloatArray, vx: Int, vy: Int, vw: Int, vh: Int) {
        GLES20.glViewport(vx, vy, vw, vh)
        GLES20.glUseProgram(p.id)
        GLES20.glEnableVertexAttribArray(p.pos); GLES20.glVertexAttribPointer(p.pos, 2, GLES20.GL_FLOAT, false, 0, POS)
        GLES20.glEnableVertexAttribArray(p.tc); GLES20.glVertexAttribPointer(p.tc, 2, GLES20.GL_FLOAT, false, 0, TC)
        GLES20.glUniformMatrix4fv(p.texMat, 1, false, texMatrix, 0)
        GLES20.glUniformMatrix4fv(p.cm, 1, false, cm, 0)
        GLES20.glUniform4fv(p.co, 1, co, 0)
        GLES20.glUniform1f(p.al, alpha)
    }

    private fun build(frag: String, samplers: Array<String>): Prog {
        val vs = shader(GLES20.GL_VERTEX_SHADER, VERT); val fs = shader(GLES20.GL_FRAGMENT_SHADER, frag)
        val id = GLES20.glCreateProgram(); GLES20.glAttachShader(id, vs); GLES20.glAttachShader(id, fs); GLES20.glLinkProgram(id)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return Prog(id, GLES20.glGetAttribLocation(id, "in_pos"), GLES20.glGetAttribLocation(id, "in_tc"),
            GLES20.glGetUniformLocation(id, "tex_mat"), GLES20.glGetUniformLocation(id, "cm"), GLES20.glGetUniformLocation(id, "co"),
            GLES20.glGetUniformLocation(id, "al"), IntArray(samplers.size) { GLES20.glGetUniformLocation(id, samplers[it]) })
    }
    private fun shader(type: Int, src: String): Int { val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s }

    companion object {
        val ID = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        private fun fb(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
        private val POS = fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val TC = fb(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        private const val VERT = """
attribute vec4 in_pos; attribute vec4 in_tc; uniform mat4 tex_mat; varying vec2 tc;
void main(){ gl_Position = in_pos; tc = (tex_mat * in_tc).xy; }"""
        private const val LOOK = """
uniform mat4 cm; uniform vec4 co; uniform float al;
vec4 look(vec3 c){ vec3 r = clamp((cm * vec4(c, 1.0)).rgb + co.rgb, 0.0, 1.0); return vec4(r * al, al); }"""
        private const val FRAG_OES = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 tc; uniform samplerExternalOES tex;" + LOOK +
            "\nvoid main(){ gl_FragColor = look(texture2D(tex, tc).rgb); }"
        private const val FRAG_RGB = "precision mediump float; varying vec2 tc; uniform sampler2D tex;" + LOOK +
            "\nvoid main(){ gl_FragColor = look(texture2D(tex, tc).rgb); }"
        private const val FRAG_YUV = "precision mediump float; varying vec2 tc; uniform sampler2D y_tex; uniform sampler2D u_tex; uniform sampler2D v_tex;" + LOOK +
            "\nvoid main(){ float y = texture2D(y_tex, tc).r; float u = texture2D(u_tex, tc).r - 0.5; float v = texture2D(v_tex, tc).r - 0.5;" +
            " gl_FragColor = look(vec3(y + 1.403 * v, y - 0.344 * u - 0.714 * v, y + 1.77 * u)); }"
    }
}
