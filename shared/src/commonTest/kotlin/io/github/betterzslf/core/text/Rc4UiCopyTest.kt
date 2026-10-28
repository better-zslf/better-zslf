package io.github.betterzslf.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class Rc4UiCopyTest {
    @Test fun htmlLifecycleHasDistinctHumanReadableFeedback() {
        assertEquals("正在加载正文…", AppText.Content.HTML_BODY_LOADING)
        assertEquals("正文加载失败，请重试。", AppText.Content.HTML_BODY_LOAD_FAILED)
        assertEquals("正文加载超时，请重试。", AppText.Content.HTML_BODY_LOAD_TIMEOUT)
        assertEquals("当前平台暂不支持正文网页显示。", AppText.Content.HTML_BODY_UNAVAILABLE)
        assertEquals("重新加载正文", AppText.Content.RELOAD_HTML_BODY)
    }

    @Test fun campusStatusIdentifiesRawValuesWithoutInventingAnOperatorMapping() {
        assertEquals("账号状态：dianxin（服务端原值）", AppText.CampusNetwork.typeStatus("dianxin"))
        assertEquals("账号状态：new-status（服务端原值）", AppText.CampusNetwork.typeStatus("new-status"))
    }

    @Test fun waterActionHintDoesNotRepeatAConnectionStatus() {
        assertEquals("需先连接设备", AppText.Water.CONNECT_DEVICE_BEFORE_START_HINT)
        assertFalse(AppText.Water.CONNECT_DEVICE_BEFORE_START_HINT.contains("尚未连接"))
    }
}
