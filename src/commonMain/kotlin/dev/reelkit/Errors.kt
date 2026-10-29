package dev.reelkit

public sealed class ReelException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The string is not an Instagram post/reel URL (share links like /share/... are not supported). */
    public class InvalidUrl(url: String) : ReelException("Not a supported Instagram post/reel URL: $url")

    /** Instagram demands a login (private post, or anonymous rate limit hit). */
    public class LoginRequired(message: String) : ReelException(message)

    /** Instagram answered but there is no usable media (deleted, restricted, or no video). */
    public class MediaNotFound(message: String) : ReelException(message)

    /** Instagram's response no longer matches what the parser expects. Update the remote config or the library. */
    public class ParseFailure(message: String) : ReelException(message)

    public class HttpError(public val code: Int, url: String) : ReelException("HTTP $code for $url")

    public class Network(cause: Throwable) : ReelException("Network error: ${cause.message}", cause)
}
