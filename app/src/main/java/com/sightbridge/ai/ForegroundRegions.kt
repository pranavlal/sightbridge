package com.sightbridge.ai

/** Converts coherent learned foreground components into position cues, not object names. */
object ForegroundRegions {
    fun extract(scores: FloatArray, width: Int, height: Int): List<ObjectBox> {
        require(width > 0 && height > 0 && scores.size == width * height)
        val visited = BooleanArray(scores.size)
        val queue = IntArray(scores.size)
        val components = mutableListOf<Pair<ObjectBox, Int>>()
        for (start in scores.indices) {
            if (visited[start] || !scores[start].isFinite() || scores[start] < .7f) continue
            var head = 0
            var tail = 1
            queue[0] = start
            visited[start] = true
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            var strong = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
                if (scores[index] >= .85f) strong++
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (!visited[next] && scores[next].isFinite() && scores[next] >= .7f) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }
            val boxPixels = (right - left + 1) * (bottom - top + 1)
            if (tail < scores.size * .005f || tail < boxPixels * .3f || strong < tail * .2f) continue
            val box = ObjectBox(left.toFloat() / width, top.toFloat() / height,
                (right + 1f) / width, (bottom + 1f) / height)
            if (box.area <= .95f) components.add(box to tail)
        }
        return components.sortedByDescending { it.second }.take(3).map { it.first }
    }
}
