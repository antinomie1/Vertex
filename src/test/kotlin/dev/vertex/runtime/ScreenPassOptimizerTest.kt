package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenPassOptimizerTest {
    @Test fun `eliminates only exact identity copies`() {
        val copy = "void main() { /* DRAWBUFFERS:0 */ gl_FragData[0] = texture2D(colortex0, texcoord); }"
        assertTrue(ScreenPassOptimizer.isIdentityCopy(copy, listOf(0), listOf("colortex0")))
        assertFalse(ScreenPassOptimizer.isIdentityCopy(copy.replace("texcoord", "texcoord * 0.5"), listOf(0), listOf("colortex0")))
        assertFalse(ScreenPassOptimizer.isIdentityCopy(copy, listOf(1), listOf("colortex0")))
    }

    @Test fun `accepts modern texture syntax and gcolor alias`() {
        assertTrue(ScreenPassOptimizer.isIdentityCopy(
            "void main() { gl_FragColor = texture(gcolor, texcoord); }", listOf(0), listOf("gcolor"),
        ))
    }

    @Test fun `keeps identity-looking shaders with extra statements`() {
        assertFalse(ScreenPassOptimizer.isIdentityCopy(
            "void main() { float x = 1.0; gl_FragColor = texture(colortex0, texcoord); }",
            listOf(0), listOf("colortex0"),
        ))
    }
}
