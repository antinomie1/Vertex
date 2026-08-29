package dev.vertex.runtime

/** OptiFine-compatible fixed sampler slots from DESIGN.md section 4.3. */
object SamplerBindingTable {
    private val fixed = buildMap {
        put("tex", 0)
        put("texture", 0)
        put("gtexture", 0)
        put("lightmap", 1)
        put("normals", 2)
        put("specular", 3)
        put("shadow", 4)
        put("watershadow", 4)
        put("shadowtex0", 4)
        put("shadowtex1", 5)
        put("depthtex0", 6)
        put("shadowcolor", 7)
        put("shadowcolor0", 7)
        put("shadowcolor1", 8)
        for (i in 0..7) put("colortex$i", 8 + i)
        put("noisetex", 15)
        for (i in 8..15) put("colortex$i", 16 + i - 8)
    }

    fun slot(name: String): Int? = fixed[name]

    fun plan(samplerNames: Iterable<String>): Map<String, Int> =
        samplerNames.associateWith { name ->
            slot(name) ?: throw IllegalArgumentException("unsupported sampler '$name'")
        }

    fun validate(plan: Map<String, Int>, reflected: Map<String, Int>) {
        val missing = plan.keys - reflected.keys
        val unexpected = reflected.keys - plan.keys
        val mismatched = plan.keys.intersect(reflected.keys)
            .filter { plan.getValue(it) != reflected.getValue(it) }
        require(missing.isEmpty() && unexpected.isEmpty() && mismatched.isEmpty()) {
            buildString {
                append("descriptor reflection mismatch")
                if (missing.isNotEmpty()) append("; missing=$missing")
                if (unexpected.isNotEmpty()) append("; unexpected=$unexpected")
                if (mismatched.isNotEmpty()) append("; wrongSlots=")
                    .append(mismatched.associateWith { plan.getValue(it) to reflected.getValue(it) })
            }
        }
    }
}
