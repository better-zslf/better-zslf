package io.github.betterzslf.feature.accountrecovery

import kotlin.test.Test
import kotlin.test.assertEquals

private const val ORIGINAL_RECOVERY_ALIAS: String = RECOVERY_WRITE_BACK_NOTICE

/** The old mixed golden's independent literal follows the actual internal production alias. */
class RecoveryTextGoldenTest {
    @Test
    fun writeBackNoticeKeepsItsIndependentOriginalLiteral() {
        assertEquals("请求可能已受理，请等待结果。返回页面不会撤销请求。", ORIGINAL_RECOVERY_ALIAS)
    }
}
