package io.github.betterzslf.schedule.vendor

import io.github.betterzslf.platform.ScheduleVendor
import kotlin.test.*

/**
 * Vendor live-notification policy.
 *
 * The vendor islands reuse undocumented parameters and fail silently, so the only thing worth
 * pinning in a host test is the replacement rule: a private card may take over only for the
 * vendors we implement, and only when a renderer confirmed delivery. Every other outcome must
 * leave the standard Android 16 card in place.
 */
class VendorLivePolicyTest {

    @Test fun onlyXiaomiVivoAndMeizuAreClaimed() {
        assertEquals(
            setOf(ScheduleVendor.XIAOMI, ScheduleVendor.VIVO, ScheduleVendor.MEIZU),
            VendorLivePolicy.claimed,
        )
    }

    @Test fun nativeVendorsAreNeverTakenOver() {
        // OPPO, Samsung and Honor map Android's Live Updates onto their own islands; posting a
        // private card there would duplicate or downgrade a working standard one.
        for (vendor in listOf(ScheduleVendor.OPPO, ScheduleVendor.SAMSUNG, ScheduleVendor.HUAWEI,
            ScheduleVendor.ONEPLUS, ScheduleVendor.AOSP)) {
            assertFalse(VendorLivePolicy.mayClaim(vendor, rendererSupports = true, rendererDelivered = true), vendor.name)
        }
    }

    @Test fun vendorPathWinsOnlyWhenItBothSupportsAndDelivers() {
        for (vendor in VendorLivePolicy.claimed) {
            assertTrue(VendorLivePolicy.mayClaim(vendor, true, true), vendor.name)
            assertFalse(VendorLivePolicy.mayClaim(vendor, false, true), vendor.name)
            assertFalse(VendorLivePolicy.mayClaim(vendor, true, false), vendor.name)
            assertFalse(VendorLivePolicy.mayClaim(vendor, false, false), vendor.name)
        }
    }

    @Test fun brandStringsMapToTheirFamilies() {
        assertEquals(ScheduleVendor.XIAOMI, VendorLivePolicy.vendorOf("Xiaomi", "Xiaomi"))
        assertEquals(ScheduleVendor.XIAOMI, VendorLivePolicy.vendorOf("Redmi", "Xiaomi"))
        assertEquals(ScheduleVendor.XIAOMI, VendorLivePolicy.vendorOf("POCO", "Xiaomi"))
        assertEquals(ScheduleVendor.VIVO, VendorLivePolicy.vendorOf("vivo", "vivo"))
        assertEquals(ScheduleVendor.VIVO, VendorLivePolicy.vendorOf("iQOO", "vivo"))
        assertEquals(ScheduleVendor.MEIZU, VendorLivePolicy.vendorOf("Meizu", "Meizu"))
        assertEquals(ScheduleVendor.MEIZU, VendorLivePolicy.vendorOf("mblu", "Meizu"))
        assertEquals(ScheduleVendor.OPPO, VendorLivePolicy.vendorOf("OPPO", "OPPO"))
        assertEquals(ScheduleVendor.OPPO, VendorLivePolicy.vendorOf("realme", "realme"))
        assertEquals(ScheduleVendor.SAMSUNG, VendorLivePolicy.vendorOf("samsung", "samsung"))
        assertEquals(ScheduleVendor.HUAWEI, VendorLivePolicy.vendorOf("HONOR", "HUAWEI"))
    }

    @Test fun unknownBrandsFallBackInsteadOfGuessing() {
        // A wrong guess posts a private payload at a system that ignores it, losing the card.
        assertEquals(ScheduleVendor.AOSP, VendorLivePolicy.vendorOf("Google", "Google"))
        assertEquals(ScheduleVendor.AOSP, VendorLivePolicy.vendorOf("", ""))
        assertEquals(ScheduleVendor.AOSP, VendorLivePolicy.vendorOf("One", "Nothing"))
    }

    @Test fun matchingIsCaseInsensitive() {
        assertEquals(ScheduleVendor.MEIZU, VendorLivePolicy.vendorOf("MEIZU", "MEIZU"))
        assertEquals(ScheduleVendor.XIAOMI, VendorLivePolicy.vendorOf("XIAOMI", "XIAOMI"))
    }
}
