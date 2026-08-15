package com.fabrice.vigie.camera

import java.io.File

/**
 * Pont caméra → logique : les callbacks sont enregistrés par VigieRuntime
 * (détection, flux, photos) et appelés depuis l'analyzer / le service.
 */
object CameraBridge {
    @Volatile var onMotionScore: ((Float) -> Unit)? = null
    @Volatile var onJpegFrame: ((ByteArray) -> Unit)? = null
    @Volatile var onBurstPhoto: ((File) -> Unit)? = null
    @Volatile var burstCaptureRequested: (() -> Unit)? = null
}
