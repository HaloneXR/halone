package dev.halone.compositor

import dev.halone.glasses.api.DistortionGrid

/**
 * Turns the factory distortion grid into a mesh that undoes the lens.
 *
 * The scene is rendered into an offscreen texture as an ideal pinhole image,
 * and then drawn to the display through this mesh. Each vertex sits at a
 * *display* position and samples the *ideal* image at the place whose light
 * actually arrives there, so the optics put it back where it belongs.
 *
 * On an Air 2 Pro this is worth up to 0.44° at the extreme corners and about
 * 0.07° across most of the frame — a straight edge bows by roughly 20 px
 * without it. Small, and the last calibrated correction the unit offers.
 *
 * ### Which way round the grid reads
 *
 * The blob stores `[idealX, idealY, actualX, actualY]` per grid point, all in
 * display pixels. Read as "a pixel drawn at `ideal` is seen in the direction an
 * ideal image would put `actual`", which makes the mesh
 * `position = ideal, texture coordinate = actual`.
 *
 * The reading is not arbitrary. `actual` runs from −15.4 to 1936.2 across a
 * 1920-wide frame, so the warp samples *outside* the rendered image — which is
 * only sensible if the rendered image is an ideal camera view that must be
 * overfilled, and is precisely why NRSDK exposes `NRHMDSetOverFillFactor`. The
 * opposite reading would have the display drawing outside itself, which means
 * nothing. See [requiredOverfill].
 */
class DistortionMesh private constructor(
    val rows: Int,
    val cols: Int,
    /** `x, y` in normalised device coordinates, two per vertex. */
    val positions: FloatArray,
    /** `u, v` into the rendered texture, two per vertex. */
    val texCoords: FloatArray,
    val indices: ShortArray,
) {
    val vertexCount: Int get() = rows * cols
    val indexCount: Int get() = indices.size

    companion object {
        /**
         * How far outside the rendered image the warp reaches, as a fraction of
         * frame size.
         *
         * Render with at least this much extra field of view or the edges
         * sample nothing and the frame ends in a black fringe that moves with
         * the head. 1.3% on the unit we have.
         */
        fun requiredOverfill(grid: DistortionGrid, widthPx: Int, heightPx: Int): Float {
            var worst = 0f
            for (row in 0 until grid.rows) {
                for (col in 0 until grid.cols) {
                    val x = grid.actualX(row, col)
                    val y = grid.actualY(row, col)
                    worst = maxOf(
                        worst,
                        -x / widthPx, (x - widthPx) / widthPx,
                        -y / heightPx, (y - heightPx) / heightPx,
                    )
                }
            }
            return maxOf(worst, 0f)
        }

        /**
         * A mesh that warps nothing: a flat grid whose texture coordinates
         * differ from its positions only by the overfill margin.
         *
         * The offscreen pass is also what reprojection warps through, and that
         * is wanted whether or not the unit gave up a distortion grid.
         */
        fun identity(overfill: Float, rows: Int = 2, cols: Int = 2): DistortionMesh {
            require(overfill >= 0f) { "overfill cannot be negative" }
            require(rows >= 2 && cols >= 2) { "a mesh needs at least one cell" }
            val positions = FloatArray(rows * cols * 2)
            val texCoords = FloatArray(rows * cols * 2)
            val span = 1f + 2f * overfill

            var v = 0
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val x = 2f * col / (cols - 1) - 1f
                    val y = 1f - 2f * row / (rows - 1)
                    positions[v] = x
                    positions[v + 1] = y
                    texCoords[v] = ((x + 1f) / 2f + overfill) / span
                    texCoords[v + 1] = ((y + 1f) / 2f + overfill) / span
                    v += 2
                }
            }
            return DistortionMesh(rows, cols, positions, texCoords, triangulate(rows, cols))
        }

        /**
         * @param overfill extra field of view the offscreen texture was
         *   rendered with, as a fraction of frame size per side. Must be at
         *   least [requiredOverfill] for the corners to have anything to sample.
         */
        fun build(
            grid: DistortionGrid,
            widthPx: Int,
            heightPx: Int,
            overfill: Float,
        ): DistortionMesh {
            require(overfill >= 0f) { "overfill cannot be negative" }
            val rows = grid.rows
            val cols = grid.cols
            val positions = FloatArray(rows * cols * 2)
            val texCoords = FloatArray(rows * cols * 2)

            // The overfilled texture spans this many display pixels per side.
            val spanX = widthPx * (1f + 2f * overfill)
            val spanY = heightPx * (1f + 2f * overfill)

            var v = 0
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    // Display position. Image y runs down, NDC y runs up.
                    positions[v] = 2f * grid.idealX(row, col) / widthPx - 1f
                    positions[v + 1] = 1f - 2f * grid.idealY(row, col) / heightPx

                    // Where to sample the ideal image. Same flip, plus the
                    // offset that the overfilled render introduced.
                    val u = (grid.actualX(row, col) + overfill * widthPx) / spanX
                    val t = (grid.actualY(row, col) + overfill * heightPx) / spanY
                    texCoords[v] = u
                    texCoords[v + 1] = 1f - t
                    v += 2
                }
            }

            return DistortionMesh(rows, cols, positions, texCoords, triangulate(rows, cols))
        }

        private fun triangulate(rows: Int, cols: Int): ShortArray {
            val indices = ShortArray((rows - 1) * (cols - 1) * 6)
            var i = 0
            for (row in 0 until rows - 1) {
                for (col in 0 until cols - 1) {
                    val topLeft = (row * cols + col).toShort()
                    val topRight = (row * cols + col + 1).toShort()
                    val bottomLeft = ((row + 1) * cols + col).toShort()
                    val bottomRight = ((row + 1) * cols + col + 1).toShort()
                    indices[i++] = topLeft
                    indices[i++] = bottomLeft
                    indices[i++] = topRight
                    indices[i++] = topRight
                    indices[i++] = bottomLeft
                    indices[i++] = bottomRight
                }
            }
            return indices
        }
    }
}
