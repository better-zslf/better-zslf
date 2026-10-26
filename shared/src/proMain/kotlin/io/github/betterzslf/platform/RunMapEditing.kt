package io.github.betterzslf.platform

import io.github.betterzslf.edition.EditionAccess
import io.github.betterzslf.feature.run.GeoPoint
import kotlinx.serialization.json.*

/** Only the owned Pro editor may interpret map clicks; never a native location source. */
internal object RunMapEditing {
    const val script: String = """  function clicked(event){
    if(!state.editMode||failed){return;}
    const value=event&&event.lnglat;if(!value){return;}
    const longitude=typeof value.getLng==='function'?value.getLng():value.lng;
    const latitude=typeof value.getLat==='function'?value.getLat():value.lat;
    if(Number.isFinite(longitude)&&Number.isFinite(latitude)&&longitude>=-180&&longitude<=180&&latitude>=-90&&latitude<=90){post('draw',{longitude:longitude,latitude:latitude,frame:frame});}
  }"""
    const val amapBinding: String = "map.on('click',clicked);"
    const val tiandituBinding: String = "map.addEventListener('click',clicked);"
    fun enabled(model: RunMapModel): Boolean = EditionAccess.routeEditorsEnabled && model.editMode
    fun parseEvent(root: JsonObject, model: RunMapModel, frame: RunMapCoordinateSystem): RunMapEvent? {
        if (!enabled(model) || root.keys != setOf("documentId", "type", "longitude", "latitude", "frame")) return null
        val incomingFrame = (root["frame"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (incomingFrame != frame.name) return null
        fun number(key: String): Double? = (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        val longitude = number("longitude") ?: return null
        val latitude = number("latitude") ?: return null
        val point = runCatching { GeoPoint(longitude, latitude) }.getOrNull() ?: return null
        return RunMapEvent.DrawPoint(point, frame)
    }
}
