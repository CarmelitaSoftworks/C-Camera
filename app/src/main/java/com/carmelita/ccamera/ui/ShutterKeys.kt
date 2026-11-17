package com.carmelita.ccamera.ui

/** Puente entre los botones físicos (los recibe la Activity) y la pantalla de cámara. */
object ShutterKeys {
    @Volatile var onShutter: (() -> Unit)? = null
}
