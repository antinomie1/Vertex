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
}
