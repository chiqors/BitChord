package com.music.bitchord.desktop.mpv

import com.music.bitchord.desktop.DesktopTrackLog

internal object Logger {
 fun d(tag: String, message: String) = DesktopTrackLog.log("$tag: $message")
 fun w(tag: String, message: String) = d(tag, message)
 fun e(tag: String, message: String) = d(tag, message)
}
