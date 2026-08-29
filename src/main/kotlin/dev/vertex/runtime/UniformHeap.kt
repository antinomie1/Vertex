package dev.vertex.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class UniformType(val alignment: Int, val bytes: Int) {
    FLOAT(4, 4), INT(4, 4), VEC2(8, 8), IVEC2(8, 8), VEC3(16, 12), IVEC3(16, 12),
    VEC4(16, 16), MAT3(16, 48), MAT4(16, 64),
}

data class UniformMember(val name: String, val type: UniformType, val count: Int, val offset: Int, val stride: Int)

data class UniformLayout(val members: Map<String, UniformMember>, val segmentBytes: Int) {
    fun member(name: String): UniformMember = members[name] ?: error("unknown uniform '$name'")
}

class UniformLayoutBuilder(private val minimumSegmentAlignment: Int) {
    init { require(minimumSegmentAlignment > 0 && minimumSegmentAlignment.countOneBits() == 1) }
    private val members = linkedMapOf<String, UniformMember>()
    private var cursor = 0

    fun add(name: String, type: UniformType, count: Int = 1): UniformLayoutBuilder = apply {
        require(name !in members && count > 0)
        val alignment = if (count == 1) type.alignment else maxOf(16, type.alignment)
        val stride = if (count == 1) type.bytes else align(type.bytes, 16)
        cursor = align(cursor, alignment)
        members[name] = UniformMember(name, type, count, cursor, stride)
        cursor += stride * count
    }

    fun build(): UniformLayout = UniformLayout(members.toMap(), align(cursor, minimumSegmentAlignment))

    private fun align(value: Int, alignment: Int) = (value + alignment - 1) and -alignment
}

/** One persistently allocated, slot-segmented UBO staging heap. */
class UniformHeap(val layout: UniformLayout, val slots: Int = 2) {
    init { require(slots > 0) }
    private val data = ByteBuffer.allocateDirect(Math.multiplyExact(layout.segmentBytes, slots))
        .order(ByteOrder.nativeOrder())

    fun segmentOffset(slot: Int): Int = checkedSlot(slot) * layout.segmentBytes

    fun putFloats(slot: Int, name: String, values: FloatArray) {
        val member = layout.member(name)
        require(member.type !in INTEGER_TYPES && values.size * Float.SIZE_BYTES <= member.stride * member.count)
        var offset = segmentOffset(slot) + member.offset
        values.forEach { data.putFloat(offset, it); offset += Float.SIZE_BYTES }
    }

    fun putFloat(slot: Int, name: String, value: Float) {
        val member = layout.member(name)
        require(member.type == UniformType.FLOAT)
        data.putFloat(segmentOffset(slot) + member.offset, value)
    }

    fun putVec2(slot: Int, name: String, x: Float, y: Float) {
        val member = layout.member(name)
        require(member.type == UniformType.VEC2)
        val offset = segmentOffset(slot) + member.offset
        data.putFloat(offset, x); data.putFloat(offset + Float.SIZE_BYTES, y)
    }

    fun putVec3(slot: Int, name: String, x: Float, y: Float, z: Float) {
        val member = layout.member(name)
        require(member.type == UniformType.VEC3)
        val offset = segmentOffset(slot) + member.offset
        data.putFloat(offset, x); data.putFloat(offset + Float.SIZE_BYTES, y); data.putFloat(offset + 2 * Float.SIZE_BYTES, z)
    }

    fun putInt(slot: Int, name: String, value: Int) {
        val member = layout.member(name)
        require(member.type == UniformType.INT && member.count == 1)
        data.putInt(segmentOffset(slot) + member.offset, value)
    }

    fun putInts(slot: Int, name: String, values: IntArray) {
        val member = layout.member(name)
        require(member.type == UniformType.IVEC2 && values.size * Int.SIZE_BYTES <= member.stride * member.count)
        var offset = segmentOffset(slot) + member.offset
        values.forEach { data.putInt(offset, it); offset += Int.SIZE_BYTES }
    }

    fun putIVec2(slot: Int, name: String, x: Int, y: Int) {
        val member = layout.member(name)
        require(member.type == UniformType.IVEC2)
        val offset = segmentOffset(slot) + member.offset
        data.putInt(offset, x); data.putInt(offset + Int.SIZE_BYTES, y)
    }

    fun putIVec3(slot: Int, name: String, x: Int, y: Int, z: Int) {
        val member = layout.member(name)
        require(member.type == UniformType.IVEC3)
        val offset = segmentOffset(slot) + member.offset
        data.putInt(offset, x); data.putInt(offset + 4, y); data.putInt(offset + 8, z)
    }

    fun putMat3(slot: Int, name: String, values: FloatArray) {
        val member = layout.member(name)
        require(member.type == UniformType.MAT3 && values.size == 9)
        val start = segmentOffset(slot) + member.offset
        for (column in 0..2) for (row in 0..2) data.putFloat(start + column * 16 + row * 4, values[column * 3 + row])
    }

    fun view(slot: Int): ByteBuffer = data.duplicate().apply {
        val start = segmentOffset(slot)
        position(start); limit(start + layout.segmentBytes)
    }.slice().asReadOnlyBuffer().order(data.order())

    private fun checkedSlot(slot: Int): Int {
        require(slot in 0 until slots) { "slot $slot outside 0..${slots - 1}" }
        return slot
    }

    private companion object {
        val INTEGER_TYPES = setOf(UniformType.INT, UniformType.IVEC2, UniformType.IVEC3)
    }
}
