package io.github.betterzslf.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Independent literal expectations for reviewed manual-route copy, not AppText-derived values. */
class AppTextManualRouteGoldenTest {
    @Test fun controllerContractCopyIsExact() {
        assertEquals("地图点选草稿", AppText.ManualRoute.SOURCE_LABEL)
        assertEquals("手绘路线只在本页内存中预览，离开后不保存。不是 GPS 实测，不进入真实跑步记录，不会上传，也不代表学校认可。", AppText.ManualRoute.LOCAL_ONLY_WARNING)
        assertEquals("保留原始地图坐标，处理后路线使用 WGS84，不修改原始点。", AppText.ManualRoute.WORKING_COPY_WGS84_HINT)
        assertEquals("请在地图上选择至少两个不同的点，再使用此预览。", AppText.ManualRoute.AT_LEAST_TWO_POINTS_REQUIRED)
        assertEquals("这份草稿暂不可用，原有预览保持不变。", AppText.ManualRoute.INVALID_DRAFT)
    }

    @Test fun editorActionsHaveIndependentLabels() {
        assertEquals(listOf("打开手绘路线编辑器", "手绘路线（仅本页预览）", "取消绘制", "使用此预览", "撤销末点", "清空绘制"),
            listOf(AppText.ManualRoute.OPEN_EDITOR, AppText.ManualRoute.TITLE, AppText.ManualRoute.CANCEL,
                AppText.ManualRoute.SAVE_PREVIEW, AppText.ManualRoute.UNDO, AppText.ManualRoute.CLEAR))
    }

    @Test fun mapAndSinglePointHintsDoNotRepresentTheDefaultViewAsGps() {
        assertEquals("默认全国视野不是当前位置。请先缩放或平移地图，再点选路线。", AppText.ManualRoute.DEFAULT_VIEW_HINT)
        assertEquals("首点已保留；再选一个不同点后显示连线。", AppText.ManualRoute.FIRST_POINT_HINT)
        assertEquals("正在等待地图服务就绪；尚未接收绘制点。", AppText.ManualRoute.WAITING_FOR_MAP)
        assertEquals("地图已就绪，请点选路线；这些点不是 GPS 采样。", AppText.ManualRoute.MAP_READY)
    }

    @Test fun terminalAndEditErrorsRemainExplicit() {
        assertEquals("正在更新本机预览，请等待结果。", AppText.ManualRoute.SAVING_PREVIEW)
        assertEquals("本次替换未完成，请关闭后重新打开绘制；原有预览保持不变。", AppText.ManualRoute.SAVE_FAILED_REOPEN)
        assertEquals("账号、地图配置或本机预览已变化，请重新打开绘制。", AppText.ManualRoute.SOURCE_CHANGED)
        assertEquals("绘制点数已达到本地上限。请撤销或清空后继续。", AppText.ManualRoute.POINT_LIMIT)
        assertEquals("此点的坐标或坐标系与当前地图不符，未加入草稿。", AppText.ManualRoute.INVALID_POINT)
        assertEquals("相邻点相同，未重复加入。", AppText.ManualRoute.DUPLICATE_POINT)
        assertEquals("当前绘制草稿为空。", AppText.ManualRoute.EMPTY_DRAFT)
    }

    @Test fun frameLabelsAndPointSummaryBoundariesAreExact() {
        assertEquals("GCJ-02 · 高德地图", AppText.ManualRoute.GCJ02_LABEL)
        assertEquals("WGS84 · 天地图", AppText.ManualRoute.WGS84_LABEL)
        assertEquals("本次绘制 0 个点 · GCJ-02 · 高德地图", AppText.ManualRoute.draftSummary(0, "GCJ-02 · 高德地图"))
        assertEquals("本次绘制 1 个点 · WGS84 · 天地图", AppText.ManualRoute.draftSummary(1, "WGS84 · 天地图"))
        assertEquals("本次绘制 2 个点 · GCJ-02 · 高德地图", AppText.ManualRoute.draftSummary(2, "GCJ-02 · 高德地图"))
        assertEquals("本次绘制 2000 个点 · WGS84 · 天地图", AppText.ManualRoute.draftSummary(2000, "WGS84 · 天地图"))
    }

    @Test fun budgetUsesIntegerCountsAtEmptyFirstAndUpperBound() {
        assertEquals("0 / 2000 个点", AppText.ManualRoute.pointBudget(0, 2000))
        assertEquals("1 / 2000 个点", AppText.ManualRoute.pointBudget(1, 2000))
        assertEquals("2 / 2000 个点", AppText.ManualRoute.pointBudget(2, 2000))
        assertEquals("2000 / 2000 个点", AppText.ManualRoute.pointBudget(2000, 2000))
    }

    @Test fun negativeSummaryIsNotFormattedAsARealCount() {
        assertFailsWith<IllegalArgumentException> { AppText.ManualRoute.draftSummary(-1, "WGS84") }
    }

    @Test fun invalidBudgetDoesNotHideNegativeOverflowOrZeroMaximum() {
        assertFailsWith<IllegalArgumentException> { AppText.ManualRoute.pointBudget(-1, 2000) }
        assertFailsWith<IllegalArgumentException> { AppText.ManualRoute.pointBudget(2001, 2000) }
        assertFailsWith<IllegalArgumentException> { AppText.ManualRoute.pointBudget(0, 0) }
        assertFailsWith<IllegalArgumentException> { AppText.ManualRoute.pointBudget(0, -1) }
    }
}
