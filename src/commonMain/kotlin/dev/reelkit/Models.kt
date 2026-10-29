package dev.reelkit

/** One downloadable item: a reel/video, or an image inside a carousel. */
public data class MediaItem(
    val videoUrl: String?,
    val imageUrl: String?,
    val width: Int?,
    val height: Int?,
) {
    public val isVideo: Boolean get() = videoUrl != null
}

public data class ReelInfo(
    val shortcode: String,
    val mediaId: String,
    val items: List<MediaItem>,
    val caption: String? = null,
    val username: String? = null,
) {
    /** Direct URL of the first video, which for a reel is the reel itself. */
    public val videoUrl: String? get() = items.firstOrNull { it.videoUrl != null }?.videoUrl
    public val thumbnailUrl: String? get() = items.firstOrNull()?.imageUrl
}
