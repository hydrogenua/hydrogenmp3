package com.vibemusic.android.playback

import java.util.Collections

/**
 * Длины файлов стримов, известные из ответов источников (contentLength).
 * Нужны [BoundedRangeDataSource], чтобы превращать открытые Range-запросы
 * в bounded — GVS отвечает 403 на «bytes=0-» без точной границы.
 */
object StreamLengths {
    private val map = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > 16
        },
    )

    fun put(url: String, contentLength: Long) {
        if (contentLength > 0) map[url] = contentLength
    }

    fun get(url: String): Long? = map[url]
}
