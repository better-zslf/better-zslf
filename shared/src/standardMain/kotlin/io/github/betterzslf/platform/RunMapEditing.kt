package io.github.betterzslf.platform

import kotlinx.serialization.json.JsonObject

/** Standard maps are read-only. No map-click drawing script or parser is compiled. */
internal object RunMapEditing {
    const val script: String = ""
    const val amapBinding: String = ""
    const val tiandituBinding: String = ""
    fun enabled(model: RunMapModel): Boolean = false
    fun parseEvent(root: JsonObject, model: RunMapModel, frame: RunMapCoordinateSystem): RunMapEvent? = null
}
