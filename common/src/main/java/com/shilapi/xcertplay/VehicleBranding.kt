package com.shilapi.xcertplay

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R
import java.util.Locale

internal enum class VehicleBrandPreset(val storageValue: String) {
    LINCOLN("lincoln"),
    FORD("ford"),
    CUSTOM("custom");

    companion object {
        fun fromStorage(value: String?): VehicleBrandPreset? =
            entries.firstOrNull { it.storageValue == value }
    }
}

/** User-visible vehicle identity. This never changes the CarPlay pairing identity. */
internal object VehicleBranding {
    private const val APP_DISPLAY_NAME = "CorsairPlay"
    private val brandToken = Regex("Lincoln/Ford|林肯/福特|Lincoln|林肯")
    private val literalBrandResources = setOf(
        R.string.car_button_brand_lincoln,
        // This is a filesystem path, not the current vehicle's display name.
        R.string.reports_save_to_downloads_diplay,
    )

    fun preset(context: Context): VehicleBrandPreset {
        VehicleBrandPreset.fromStorage(AirPlayPersistence.loadVehicleBrandPreset(context))?.let {
            return it
        }
        val migrated = when {
            AirPlayPersistence.loadCustomAirPlayIconFile(context) != null -> VehicleBrandPreset.CUSTOM
            AirPlayPersistence.loadStoredOemLabel(context)
                ?.let { it != AirPlayPersistence.DEFAULT_OEM_LABEL } == true -> VehicleBrandPreset.CUSTOM
            else -> VehicleBrandPreset.LINCOLN
        }
        setPreset(context, migrated)
        return migrated
    }

    fun setPreset(context: Context, preset: VehicleBrandPreset) {
        AirPlayPersistence.saveVehicleBrandPreset(context, preset.storageValue)
    }

    fun displayName(context: Context): String = when (preset(context)) {
        VehicleBrandPreset.LINCOLN -> "Lincoln"
        VehicleBrandPreset.FORD -> "Ford"
        VehicleBrandPreset.CUSTOM -> AirPlayPersistence.loadOemLabel(context).trim()
            .ifBlank { AirPlayPersistence.DEFAULT_OEM_LABEL }
    }

    fun localize(resourceId: Int, text: String): String {
        if (resourceId in literalBrandResources || text.isBlank()) return text
        if (!text.contains("Lincoln") && !text.contains("林肯")) return text
        return brandToken.replace(text, APP_DISPLAY_NAME)
    }

    fun text(context: Context, resourceId: Int, vararg formatArgs: Any): String {
        val template = localize(resourceId, context.getString(resourceId))
        if (formatArgs.isEmpty()) return template
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return String.format(locale, template, *formatArgs)
    }

    fun logo(context: Context): Drawable =
        when (preset(context)) {
            VehicleBrandPreset.FORD -> requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_ford))
            VehicleBrandPreset.CUSTOM -> AirPlayPersistence.loadCustomAirPlayIconFile(context)
                ?.let { Drawable.createFromPath(it.absolutePath) }
                ?: requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_carplay))
            VehicleBrandPreset.LINCOLN -> requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_carplay))
        }

    fun packagedHomeIconResource(context: Context): Int =
        when (preset(context)) {
            VehicleBrandPreset.FORD -> R.raw.ic_car_home_ford
            VehicleBrandPreset.LINCOLN, VehicleBrandPreset.CUSTOM -> R.raw.ic_car_home
        }

    fun setLogo(view: ImageView) {
        view.setImageDrawable(logo(view.context))
    }
}
