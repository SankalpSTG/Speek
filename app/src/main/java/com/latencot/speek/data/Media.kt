package com.latencot.speek.data

/** What a file is, from its name: enough to pick a preview and an app to open it. */
object Media {
    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
    private val videos = setOf("mp4", "webm", "mkv", "mov", "3gp")
    private val audio = setOf("m4a", "mp3", "ogg", "oga", "opus", "wav", "aac", "flac")

    fun name(path: String): String = path.substringAfterLast('/').substringBefore('?').ifEmpty { path }

    fun ext(path: String): String = name(path).substringAfterLast('.', "").lowercase()

    fun isImage(path: String) = ext(path) in images
    fun isVideo(path: String) = ext(path) in videos
    fun isAudio(path: String) = ext(path) in audio
    fun isUrl(path: String) = path.startsWith("http://") || path.startsWith("https://")
}
