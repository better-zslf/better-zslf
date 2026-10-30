package io.github.betterzslf.schedule.vendor

import io.github.betterzslf.platform.ScheduleVendor

/**
 * Pure decision rules for the vendor live path, kept free of `Context` so they can be unit tested
 * on the host: the Android-facing dispatcher only wires these answers to real renderers.
 *
 * The whole point of this file is that a wrong answer is invisible on a device -- an island that
 * refuses the payload simply renders nothing. So the "when may a vendor card replace the standard
 * one" question is answered by a function with tests instead of by device observation.
 */
internal object VendorLivePolicy {

    /** Vendors whose islands ignore Android 16 Live Updates and need a private notification. */
    val claimed: Set<ScheduleVendor> = setOf(
        ScheduleVendor.XIAOMI,
        ScheduleVendor.VIVO,
        ScheduleVendor.MEIZU,
    )

    /**
     * May this vendor's private card stand in for the standard live notification?
     *
     * Both conditions are required. The vendor must be one we implemented (OPPO/Samsung/Honor are
     * served by the native API, so a private card there would duplicate or replace a working one),
     * and a renderer must have said it can deliver -- otherwise the user would lose the card
     * entirely rather than merely losing the island styling.
     */
    fun mayClaim(vendor: ScheduleVendor, rendererSupports: Boolean, rendererDelivered: Boolean): Boolean =
        vendor in claimed && rendererSupports && rendererDelivered

    /**
     * Map a manufacturer/brand string onto the vendor family. Unrelated brands fall through to
     * [ScheduleVendor.AOSP] rather than a best guess, because guessing wrong here means posting a
     * private payload to a system that will ignore it.
     */
    fun vendorOf(brand: String, manufacturer: String): ScheduleVendor {
        val id = (brand + " " + manufacturer).lowercase()
        return when {
            id.contains("xiaomi") || id.contains("redmi") || id.contains("poco") -> ScheduleVendor.XIAOMI
            id.contains("vivo") || id.contains("iqoo") -> ScheduleVendor.VIVO
            id.contains("meizu") || id.contains("mblu") -> ScheduleVendor.MEIZU
            id.contains("oppo") || id.contains("realme") || id.contains("oneplus") -> ScheduleVendor.OPPO
            id.contains("samsung") -> ScheduleVendor.SAMSUNG
            id.contains("huawei") || id.contains("honor") -> ScheduleVendor.HUAWEI
            else -> ScheduleVendor.AOSP
        }
    }
}
