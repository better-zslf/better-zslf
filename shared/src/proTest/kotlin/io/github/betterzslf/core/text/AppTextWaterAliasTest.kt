package io.github.betterzslf.core.text

import io.github.betterzslf.feature.water.WaterFailureGuard
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ORIGINAL_WATER_ALIAS: String = WaterFailureGuard.LOCKOUT_NOTICE

class AppTextWaterAliasTest {
    @Test fun existingWaterConstAliasKeepsItsExactCopy() {
        assertEquals("多次失败可能锁定水控器，需在通电状态下等待约 1 小时。认证失败后已断开，不会自动重试。", ORIGINAL_WATER_ALIAS)
    }
}
