package io.github.betterzslf.ui

import kotlin.test.*

class DormPhoneSourceTest {

    @Test fun onlyThePartBeforeTheAtSignIsKept() {
        // 掌上莲峰 returns accounts like "13800138000@163.gd"; the dialer takes just the phone.
        assertEquals("13800138000", dormPhonePrefixOf("13800138000@163.gd"))
        assertEquals("13800138000", dormPhonePrefixOf("13800138000"))
        assertEquals("13800138000", dormPhonePrefixOf("  13800138000@163.gd  "))
        assertEquals("17308221000", dormPhonePrefixOf("17308221000@zsdzkd"))
    }

    @Test fun anythingThatIsNotAnElevenDigitMobileYieldsNothing() {
        // A prefix we cannot trust must send the page to manual entry, never to a dial attempt.
        assertNull(dormPhonePrefixOf(""))
        assertNull(dormPhonePrefixOf("@163.gd"))
        assertNull(dormPhonePrefixOf("1380013800@163.gd"), "10 digits")
        assertNull(dormPhonePrefixOf("138001380001@163.gd"), "12 digits")
        assertNull(dormPhonePrefixOf("1380013800a@163.gd"))
        assertNull(dormPhonePrefixOf("学号2026010401004@x"))
    }

    @Test fun maskingNeverShowsTheMiddleDigits() {
        assertEquals("138****8000", maskDormPhone("13800138000"))
        // Anything unexpected is returned as-is rather than being mangled into a fake mask.
        assertEquals("123", maskDormPhone("123"))
        assertEquals("", maskDormPhone(""))
        // The masked form must never contain the original middle four digits.
        val masked = maskDormPhone("13800138000")
        assertFalse(masked.contains("0013"))
    }
}
