package com.xnotes.gl

import android.opengl.GLES30

/**
 * A placed image: one textured quad, its corners computed on the CPU in doubles and handed over in
 * clip space.
 *
 * Images take this path rather than the geometry store's because they carry a texture rather than a
 * colour, and because an image is always four corners of an axis-aligned rectangle. Working the
 * corners out in double precision and passing clip-space coordinates sidesteps the chunk-and-offset
 * scheme entirely, and is exact however far from the origin the image sits.
 */
class ImageShader(contextGen: Int) {

    private val program = GlProgram.build(VERTEX_SRC, FRAGMENT_SRC, contextGen)

    val contextGen: Int get() = program.contextGen

    fun release() = program.release()

    /**
     * Draw [texture] over the clip-space quad given by its four corners, in the order top-left,
     * top-right, bottom-left, bottom-right. [rotationSteps] turns the texture a quarter turn at a
     * time, so an image's orientation costs nothing but a different set of texture coordinates, and
     * [part] narrows them to the share of the image a sliced picture shows.
     */
    fun draw(corners: FloatArray, texture: Int, rotationSteps: Int, alpha: Double = 1.0, part: com.xnotes.core.geometry.Rect? = null) {
        if (texture == 0) return
        program.use()
        program.set("uP0", corners[0], corners[1])
        program.set("uP1", corners[2], corners[3])
        program.set("uP2", corners[4], corners[5])
        program.set("uP3", corners[6], corners[7])
        program.set("uRotation", ((rotationSteps % 4) + 4) % 4)
        program.set("uPartFrom", (part?.left ?: 0.0).toFloat(), (part?.top ?: 0.0).toFloat())
        program.set("uPartTo", (part?.right ?: 1.0).toFloat(), (part?.bottom ?: 1.0).toFloat())
        program.set("uAlpha", alpha.coerceIn(0.0, 1.0).toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        program.set("uTexture", 0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        private val VERTEX_SRC = """#version 300 es
            uniform vec2 uP0;
            uniform vec2 uP1;
            uniform vec2 uP2;
            uniform vec2 uP3;
            uniform int uRotation;
            uniform vec2 uPartFrom;
            uniform vec2 uPartTo;
            out vec2 vUv;
            void main() {
                vec2 p = uP0;
                vec2 uv = vec2(0.0, 0.0);
                if (gl_VertexID == 1) { p = uP1; uv = vec2(1.0, 0.0); }
                else if (gl_VertexID == 2) { p = uP2; uv = vec2(0.0, 1.0); }
                else if (gl_VertexID == 3) { p = uP3; uv = vec2(1.0, 1.0); }
                // A quarter-turn orientation is a rotation of the texture coordinates about the
                // middle, so the image itself needs no re-decoding to be turned.
                vec2 c = uv - vec2(0.5);
                if (uRotation == 1) c = vec2(-c.y, c.x);
                else if (uRotation == 2) c = -c;
                else if (uRotation == 3) c = vec2(c.y, -c.x);
                // A sliced picture shows only its part of the image, so the corners sample just that.
                vUv = mix(uPartFrom, uPartTo, c + vec2(0.5));
                gl_Position = vec4(p, 0.0, 1.0);
            }
        """.trimIndent()

        private val FRAGMENT_SRC = """#version 300 es
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                vec4 c = texture(uTexture, vUv);
                fragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """.trimIndent()
    }
}
