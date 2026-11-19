package io.github.betterzslf.ui.run

/** Finite read-list/navigation intents. Product navigation and all action authority stay in the binder. */
enum class RunListUiEvent { BACK, REFRESH, MORE, LOCAL_TRACKER, SELECT_TASKS }
enum class RunTaskRowAction { DETAIL, RECORDS, RECORD_REAL }

data class RunListControls(val canGoBack: Boolean = false, val canRefresh: Boolean = true, val canMore: Boolean = false) {
    fun allows(event: RunListUiEvent): Boolean = when (event) {
        RunListUiEvent.BACK -> canGoBack
        RunListUiEvent.REFRESH -> canRefresh
        RunListUiEvent.MORE -> canMore
        RunListUiEvent.LOCAL_TRACKER, RunListUiEvent.SELECT_TASKS -> true
    }
}
