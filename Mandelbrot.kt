object Mandelbrot {
    inline fun iterateInAreaNormalizedParallel(
        n: Int,
        crossinline fn: (x: Float, y: Float) -> Unit
    ) {
        val d = n.toFloat()
        IntStream.range(0, n)
            .parallel()
            .forEach { x ->
                for (y in 0..<n) fn(x / d, y / d)
            }
    }

    class MandelbrotDrawer(val size: Int) {
        fun calcCounts(threshold: Int): AtomicIntegerArray {
            val counts = AtomicIntegerArray(size * size)
            measureTime {
                iterateInAreaNormalizedParallel(size) { norX, norY ->
                    var x = 0f
                    var y = 0f

                    val cX = norX * 2 - 1.5f
                    val cY = norY * 2 - 1f

                    for (i in 0..<threshold) {
                        val nextX = x * x - y * y + cX
                        val nextY = 2 * x * y + cY
                        x = nextX
                        y = nextY

                        val norIterX = x / 2 + 0.75f
                        val norIterY = y / 2 + 0.5f

                        val ix = (norIterX * size).toInt()
                        val iy = (norIterY * size).toInt()

                        val index = ix + iy * size

                        if (x.isNaN() || y.isNaN() || index !in 0..<counts.length()) break
                        counts.incrementAndGet(index)
                    }
                }
            }.let { println("time = $it") }
            return counts
        }

        class CountToColor(threshold: Int) {
            val color = Color(0f, 0f, 0f, 1f)
            val d = 360f / threshold
            operator fun invoke(count: Int): Int = Color.rgba8888(color.fromHsv(180 + count * d, 1f, 1f))
        }

        inline fun countsToPixels(crossinline getCount: (index: Int) -> Int, threshold: Int, parallel: Boolean): IntArray {
            val pixels = IntArray(size * size)
            measureTime {
                if (!parallel) {
                    val countToColor = CountToColor(threshold)
                    for (index in pixels.indices) pixels[index] = countToColor(getCount(index))
                } else {
                    IntStream.range(0, size)
                        .parallel()
                        .forEach { x ->
                            val countToColor = CountToColor(threshold)
                            for (y in 0..<size) {
                                val index = x + y * size
                                pixels[index] = countToColor(getCount(index))
                            }
                        }
                }
            }.let { println("time = $it") }
            return pixels
        }

        fun pixelsToPixmap(pixels: IntArray): Pixmap {
            val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
            for (iy in 0..<size) for (ix in 0..<size) pixmap.drawPixel(ix, iy, pixels[ix + iy * size])
            return pixmap
        }

        fun drawMandelbrot(threshold: Int, parallelMap: Boolean) = pixelsToPixmap(countsToPixels(calcCounts(threshold)::get, threshold, parallelMap))
    }

    fun draw() = MandelbrotDrawer(1600).drawMandelbrot(100, true)
}
