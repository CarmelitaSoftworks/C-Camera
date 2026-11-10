package com.carmelita.ccamera.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Última ubicación conocida para el EXIF (sin pedir una nueva: es instantáneo y no gasta batería).
 * Sólo si el usuario activó "Guardar ubicación" y concedió el permiso.
 */
object LocationHelper {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** La ubicación más reciente de los proveedores disponibles, si tiene menos de [maxAgeMs]. */
    @Suppress("MissingPermission")
    fun lastKnownLocation(context: Context, maxAgeMs: Long = 10 * 60_000L): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val best: Location = lm.getProviders(true)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return if (System.currentTimeMillis() - best.time > maxAgeMs) null else best
    }

    fun lastKnown(context: Context, maxAgeMs: Long = 10 * 60_000L): ExifWriter.Gps? {
        val best = lastKnownLocation(context, maxAgeMs) ?: return null
        return ExifWriter.Gps(best.latitude, best.longitude, if (best.hasAltitude()) best.altitude else null)
    }
}
