package dev.reelkit

import kotlinx.coroutines.CoroutineDispatcher

/** `Dispatchers.IO` is not exposed to common code, so each platform supplies it. */
internal expect val IoDispatcher: CoroutineDispatcher
