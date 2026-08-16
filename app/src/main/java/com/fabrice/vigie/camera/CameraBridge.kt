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
    /** Vrai si au moins un client est connecté au flux → on peut encoder les frames. */
    @Volatile var isStreamActive: (() -> Boolean)? = null
    // Vidéo (déclenchable à distance via le serveur)
    @Volatile var videoStartRequested: (() -> Boolean)? = null
    @Volatile var videoStopRequested: (() -> String?)? = null
    @Volatile var videoListProvider: (() -> List<Pair<String, Long>>)? = null
    @Volatile var videoFileProvider: ((String) -> File?)? = null
    // Photos des événements (visualisation / suppression à distance)
    @Volatile var photosListProvider: (() -> List<Triple<String, String, Long>>)? = null
    @Volatile var photoFileProvider: ((String, String) -> File?)? = null
    @Volatile var photoDeleteRequested: ((String, String) -> Boolean)? = null
    // Contrôles caméra (zoom / flash à distance)
    @Volatile var torchRequested: ((Boolean) -> Boolean)? = null
    @Volatile var zoomRequested: ((Float) -> Boolean)? = null
    @Volatile var zoomResetRequested: (() -> Boolean)? = null
    // Statut pour la page web (batterie, résolution, état)
    @Volatile var statusProvider: (() -> String)? = null
}
