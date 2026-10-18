package dev.halone.compositor

import dev.halone.glasses.api.DistortionGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistortionMeshTest {

    private val width = 1920
    private val height = 1080

    /** A grid that does nothing: every point already lands where it belongs. */
    private fun identityGrid(rows: Int = 4, cols: Int = 5): DistortionGrid {
        val points = FloatArray(rows * cols * 4)
        var i = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = c * width.toFloat() / (cols - 1)
                val y = r * height.toFloat() / (rows - 1)
                points[i++] = x; points[i++] = y; points[i++] = x; points[i++] = y
            }
        }
        return DistortionGrid(rows, cols, points)
    }

    /** Displaces every point outward by [push] pixels, like a real lens. */
    private fun pushedGrid(push: Float, rows: Int = 4, cols: Int = 5): DistortionGrid {
        val points = FloatArray(rows * cols * 4)
        var i = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = c * width.toFloat() / (cols - 1)
                val y = r * height.toFloat() / (rows - 1)
                val dx = if (x < width / 2f) -push else if (x > width / 2f) push else 0f
                val dy = if (y < height / 2f) -push else if (y > height / 2f) push else 0f
                points[i++] = x; points[i++] = y; points[i++] = x + dx; points[i++] = y + dy
            }
        }
        return DistortionGrid(rows, cols, points)
    }

    @Test
    fun `mesh has one vertex per grid point and two triangles per cell`() {
        val mesh = DistortionMesh.build(identityGrid(), width, height, overfill = 0f)
        assertEquals(4 * 5, mesh.vertexCount)
        assertEquals(4 * 5 * 2, mesh.positions.size)
        assertEquals((4 - 1) * (5 - 1) * 6, mesh.indexCount)
    }

    @Test
    fun `grid corners land on the corners of the display`() {
        val mesh = DistortionMesh.build(identityGrid(), width, height, overfill = 0f)
        // First grid point is the top-left of the image: NDC (-1, +1).
        assertEquals(-1f, mesh.positions[0], 1e-5f)
        assertEquals(1f, mesh.positions[1], 1e-5f)
        // Last is the bottom-right: NDC (+1, -1).
        val last = mesh.positions.size - 2
        assertEquals(1f, mesh.positions[last], 1e-5f)
        assertEquals(-1f, mesh.positions[last + 1], 1e-5f)
    }

    /**
     * The most useful property to pin: a grid that says the optics are perfect
     * must produce a mesh that samples straight through. If this ever fails,
     * the warp is introducing distortion rather than removing it.
     */
    @Test
    fun `an identity grid samples the texture one to one`() {
        val mesh = DistortionMesh.build(identityGrid(), width, height, overfill = 0f)
        for (v in 0 until mesh.vertexCount) {
            val ndcX = mesh.positions[v * 2]
            val ndcY = mesh.positions[v * 2 + 1]
            // NDC [-1,1] and texture [0,1] differ only by the usual mapping.
            assertEquals((ndcX + 1f) / 2f, mesh.texCoords[v * 2], 1e-5f)
            assertEquals((ndcY + 1f) / 2f, mesh.texCoords[v * 2 + 1], 1e-5f)
        }
    }

    /**
     * The direction matters and is easy to get backwards. A lens that pushes
     * light outward must be corrected by sampling further out, so the corner of
     * the display shows content from beyond the frame — which is what the
     * overfill exists to provide.
     */
    @Test
    fun `an outward-pushing lens makes the corners sample beyond the frame`() {
        val mesh = DistortionMesh.build(pushedGrid(push = 20f), width, height, overfill = 0f)
        // Top-left vertex: u below 0 and v above 1, both off the texture.
        assertTrue("u should fall below 0, was ${mesh.texCoords[0]}", mesh.texCoords[0] < 0f)
        assertTrue("v should rise above 1, was ${mesh.texCoords[1]}", mesh.texCoords[1] > 1f)
    }

    @Test
    fun `overfill brings the out-of-frame samples back inside the texture`() {
        val grid = pushedGrid(push = 20f)
        val needed = DistortionMesh.requiredOverfill(grid, width, height)
        // One factor covers both axes, so the tighter dimension sets it: 20 px
        // is a larger fraction of 1080 than of 1920.
        assertEquals(20f / height, needed, 1e-4f)

        val mesh = DistortionMesh.build(grid, width, height, overfill = needed)
        for (value in mesh.texCoords) {
            assertTrue("texture coordinate $value outside [0,1]", value >= -1e-4f && value <= 1f + 1e-4f)
        }
    }

    @Test
    fun `a grid that needs no overfill reports none`() {
        assertEquals(0f, DistortionMesh.requiredOverfill(identityGrid(), width, height), 1e-6f)
    }

    @Test
    fun `every index refers to a real vertex`() {
        val mesh = DistortionMesh.build(identityGrid(6, 7), width, height, overfill = 0.01f)
        for (index in mesh.indices) {
            assertTrue("index $index out of range", index >= 0 && index < mesh.vertexCount)
        }
    }

    /**
     * Triangles must wind consistently or back-face culling drops half of them.
     * Checked by signed area in NDC, which is what the rasteriser sees:
     * positive is counter-clockwise, which is GL's default front face.
     */
    @Test
    fun `all triangles wind counter-clockwise, which GL treats as front facing`() {
        val mesh = DistortionMesh.build(identityGrid(6, 7), width, height, overfill = 0f)
        var i = 0
        while (i < mesh.indexCount) {
            val a = mesh.indices[i].toInt(); val b = mesh.indices[i + 1].toInt()
            val c = mesh.indices[i + 2].toInt()
            val area = (mesh.positions[b * 2] - mesh.positions[a * 2]) *
                (mesh.positions[c * 2 + 1] - mesh.positions[a * 2 + 1]) -
                (mesh.positions[c * 2] - mesh.positions[a * 2]) *
                (mesh.positions[b * 2 + 1] - mesh.positions[a * 2 + 1])
            assertTrue("triangle at $i winds the wrong way (area $area)", area > 0f)
            i += 3
        }
    }

    /**
     * Reprojection needs the offscreen pass whether or not the unit gave up a
     * distortion grid, so there has to be a mesh that warps nothing.
     */
    @Test
    fun `an identity mesh covers the display corner to corner`() {
        val mesh = DistortionMesh.identity(0f)
        val xs = (0 until mesh.vertexCount).map { mesh.positions[it * 2] }
        val ys = (0 until mesh.vertexCount).map { mesh.positions[it * 2 + 1] }
        assertEquals(-1f, xs.min(), 1e-6f)
        assertEquals(1f, xs.max(), 1e-6f)
        assertEquals(-1f, ys.min(), 1e-6f)
        assertEquals(1f, ys.max(), 1e-6f)
        for (v in 0 until mesh.vertexCount) {
            assertEquals((mesh.positions[v * 2] + 1f) / 2f, mesh.texCoords[v * 2], 1e-6f)
            assertEquals((mesh.positions[v * 2 + 1] + 1f) / 2f, mesh.texCoords[v * 2 + 1], 1e-6f)
        }
    }

    @Test
    fun `an identity mesh insets its texture coordinates by the overfill`() {
        val mesh = DistortionMesh.identity(0.05f)
        val us = (0 until mesh.vertexCount).map { mesh.texCoords[it * 2] }
        val vs = (0 until mesh.vertexCount).map { mesh.texCoords[it * 2 + 1] }
        val inset = 0.05f / 1.1f
        assertEquals(inset, us.min(), 1e-6f)
        assertEquals(1f - inset, us.max(), 1e-6f)
        assertEquals(inset, vs.min(), 1e-6f)
        assertEquals(1f - inset, vs.max(), 1e-6f)
    }

    @Test
    fun `an identity mesh winds the same way as a warped one`() {
        val mesh = DistortionMesh.identity(0f, rows = 3, cols = 3)
        var i = 0
        while (i < mesh.indexCount) {
            val a = mesh.indices[i].toInt()
            val b = mesh.indices[i + 1].toInt()
            val c = mesh.indices[i + 2].toInt()
            val area = (mesh.positions[b * 2] - mesh.positions[a * 2]) *
                (mesh.positions[c * 2 + 1] - mesh.positions[a * 2 + 1]) -
                (mesh.positions[c * 2] - mesh.positions[a * 2]) *
                (mesh.positions[b * 2 + 1] - mesh.positions[a * 2 + 1])
            assertTrue("triangle at $i winds the wrong way (area $area)", area > 0f)
            i += 3
        }
    }
}
