package com.ehviewer.core.files

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import okio.Path

actual inline fun <T> Path.read(f: Source.() -> T) = SystemFileSystem.source(this).buffered().use(f)

actual inline fun <T> Path.write(f: Sink.() -> T) = SystemFileSystem.sink(this).buffered().use(f)
