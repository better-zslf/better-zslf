package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.run.GeoPoint
import kotlinx.serialization.json.*

/** HTML contains a public provider key and coordinates; never include either in diagnostics. */
data class RunMapDocument(val html: String?, val issue: RunMapFailure?) {
    override fun toString(): String = "RunMapDocument(html=${html != null}, issue=$issue)"
}

fun runMapProviderFor(webType: String?): RunMapProvider? = when (webType?.trim()) {
    "1" -> RunMapProvider.AMAP
    "2" -> RunMapProvider.TIAN_DI_TU
    else -> null
}

fun runMapFailureMessage(reason: RunMapFailure): String = when (reason) {
    RunMapFailure.CONFIGURATION -> AppText.Run.MAP_PROVIDER_AND_PUBLIC_KEY_CONFIGURATION_REQUIRED
    RunMapFailure.UNCONFIRMED_COORDINATES -> AppText.Run.MAP_COORDINATE_FRAME_UNCONFIRMED_CHECK_TRACK_SOURCE
    RunMapFailure.TOO_MANY_POINTS -> AppText.Run.MAP_DATA_DISPLAY_LIMIT_EXCEEDED_REDUCE_TRACK
    RunMapFailure.NETWORK -> AppText.Run.MAP_RESOURCE_LOAD_TIMEOUT_CHECK_NETWORK
    RunMapFailure.SDK -> AppText.Run.MAP_SERVICE_NOT_READY_RETRY_LATER
    RunMapFailure.UNAVAILABLE -> AppText.Run.MAP_DISPLAY_UNAVAILABLE_IN_CURRENT_ENVIRONMENT
}

/** Owned map document only. Events select/edit UI; none is a GPS sample or an API action. */
object RunMapWeb {
    const val baseUrl = "https://map.better-zslf.invalid/"
    private const val MAX_POINTS = 20_000
    private const val MAX_MARKERS = 256
    private val tokenPattern = Regex("[A-Za-z0-9_-]{1,128}")
    private val keyPattern = Regex("[A-Za-z0-9._-]{1,256}")
    private val json = Json { isLenient = false; allowSpecialFloatingPointValues = false }

    fun document(model: RunMapModel, documentId: String): RunMapDocument {
        val issue = issue(model, documentId)
        if (issue != null) return RunMapDocument(null, issue)
        val provider = requireNotNull(model.provider)
        val data = safeJson(payload(model, documentId).toString())
        val domains = when (provider) {
            RunMapProvider.AMAP -> "https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com"
            RunMapProvider.TIAN_DI_TU -> "https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com"
        }
        // Legacy AMap 1.4.15/TDT 4.0 module loaders need Function/eval. Model JSON is NEVER eval'd.
        // Network sources remain provider-specific; frames, forms, media and arbitrary base URLs are disabled.
        val csp = "default-src 'none'; base-uri 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; " +
            "form-action 'none'; media-src 'none'; script-src 'nonce-$documentId' 'unsafe-eval' $domains; " +
            "style-src 'unsafe-inline' $domains; img-src data: blob: $domains; font-src data: $domains; " +
            "connect-src $domains; worker-src blob:;"
        val sdkUrl = when (provider) {
            RunMapProvider.AMAP -> "https://webapi.amap.com/maps?v=1.4.15&amp;key=${model.apiKey}"
            RunMapProvider.TIAN_DI_TU -> "https://api.tianditu.gov.cn/api?v=4.0&amp;tk=${model.apiKey}"
        }
        // Only this fixed, validated SDK tag is parser-written, preserving legacy synchronous SDK loading.
        // Labels never use HTML parsing: their DOM nodes are populated with textContent below.
        val sdkTag = safeJson(JsonPrimitive("<script nonce=\"$documentId\" src=\"$sdkUrl\"></script>").toString())
        val html = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta http-equiv="Content-Security-Policy" content="$csp">
<style>html,body,#map{width:100%;height:100%;margin:0;overflow:hidden}body{background:#eef2f5}.checkpoint-label{display:inline-block;background:#fff;color:#17212b;border:2px solid #34F18C;border-radius:8px;padding:4px 7px;font:13px sans-serif;white-space:pre-wrap;max-width:180px}.position-dot{width:12px;height:12px;background:#2688ff;border:2px solid white;border-radius:50%}</style>
</head><body><div id="map" role="img" aria-label="${AppText.Run.ROUTE_MAP_ACCESSIBILITY_LABEL}"></div>
<script id="run-map-data" type="application/json">$data</script>
<script nonce="$documentId">
(function(){'use strict';
  let state=JSON.parse(document.getElementById('run-map-data').textContent);
  const documentId=state.documentId, provider=state.provider, frame=state.frame;
  let map=null, overlays=[], ready=false, failed=false, timer=null, poll=null;
  function post(type,extra){
    const value=Object.assign({documentId:documentId,type:type},extra||{});
    const message=JSON.stringify(value);
    try{
      if(window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.runMap){window.webkit.messageHandlers.runMap.postMessage(message);}
      else if(window.runMap&&typeof window.runMap.postMessage==='function'){window.runMap.postMessage(message);}
    }catch(_){/* No URLs, coordinates or keys in logs. */}
  }
  function fail(reason){if(failed){return;}failed=true;clearTimeout(timer);clearInterval(poll);post('failed',{reason:reason});}
  function denyCallback(_success,error){if(typeof error==='function'){setTimeout(function(){error({code:1,message:'Permission denied'});},0);}return -1;}
  function denyMedia(){return Promise.reject(new DOMException('Permission denied','NotAllowedError'));}
  function marked(value){try{Object.defineProperty(value,'__runMapDenied',{value:true});}catch(_){}return value;}
  const geo=Object.freeze(marked({getCurrentPosition:denyCallback,watchPosition:denyCallback,clearWatch:function(){}}));
  marked(denyCallback);marked(denyMedia);
  function lock(object,name,value){
    if(!object){return true;}
    try{
      const old=Object.getOwnPropertyDescriptor(object,name);
      if(old&&!old.configurable){return !!(old.value&&old.value.__runMapDenied);}
      Object.defineProperty(object,name,{value:value,writable:false,configurable:false});return true;
    }catch(_){return false;}
  }
  function permissions(){
    const proto=Object.getPrototypeOf(navigator);
    let ok=lock(proto,'geolocation',geo)&&lock(navigator,'geolocation',geo);
    ['getUserMedia','webkitGetUserMedia','mozGetUserMedia','msGetUserMedia'].forEach(function(name){ok=lock(proto,name,denyCallback)&&lock(navigator,name,denyCallback)&&ok;});
    const media=navigator.mediaDevices;
    if(media){const mp=Object.getPrototypeOf(media);ok=lock(mp,'getUserMedia',denyMedia)&&lock(media,'getUserMedia',denyMedia)&&ok;ok=lock(mp,'getDisplayMedia',denyMedia)&&lock(media,'getDisplayMedia',denyMedia)&&ok;}
    return ok;
  }
  function labelNode(text){const node=document.createElement('span');node.className='checkpoint-label';node.textContent=text;return node;}
  function point(value){return provider==='AMAP'?new AMap.LngLat(value[0],value[1]):new T.LngLat(value[0],value[1]);}
  function add(overlay){if(provider==='AMAP'){map.add(overlay);}else{map.addOverLay(overlay);}overlays.push(overlay);}
  function selected(id){post('checkpoint',{id:id});}
  function line(segment,color){
    if(segment.length<2){return;}
    const points=segment.map(point);
    add(provider==='AMAP'?new AMap.Polyline({path:points,strokeColor:color,strokeWeight:8,strokeOpacity:1}):new T.Polyline(points,{color:color,weight:8,opacity:1}));
  }
  function render(){
    if(!map){return;}
    overlays.forEach(function(overlay){try{if(provider==='AMAP'){map.remove(overlay);}else{map.removeOverLay(overlay);}}catch(_){}});overlays=[];
    state.recommended.forEach(function(segment){line(segment,'#34F18C');});
    state.actual.forEach(function(segment){line(segment,'#2688FF');});
    state.imported.forEach(function(segment){line(segment,'#F39B24');});
    state.checkpoints.forEach(function(checkpoint){
      const node=labelNode(checkpoint.label), position=point(checkpoint.point);
      if(provider==='AMAP'){
        const marker=new AMap.Marker({position:position,content:node});marker.on('click',function(){selected(checkpoint.id);});add(marker);
      }else{
        const marker=new T.Marker(position);marker.addEventListener('click',function(){selected(checkpoint.id);});add(marker);
        // T.Label takes markup; outerHTML comes ONLY from a fresh span filled via textContent.
        const label=new T.Label({position:position,text:node.outerHTML});label.addEventListener('click',function(){selected(checkpoint.id);});add(label);
      }
    });
    if(state.current){
      if(provider==='AMAP'){const node=document.createElement('span');node.className='position-dot';add(new AMap.Marker({position:point(state.current),content:node}));}
      else{add(new T.Marker(point(state.current)));}
    }
    document.body.style.background=state.dark?'#171b22':'#eef2f5';
  }
  function onReady(){if(ready||failed){return;}ready=true;clearTimeout(timer);clearInterval(poll);post('ready');}
  function clicked(event){
    if(!state.editMode||failed){return;}
    const value=event&&event.lnglat;if(!value){return;}
    const longitude=typeof value.getLng==='function'?value.getLng():value.lng;
    const latitude=typeof value.getLat==='function'?value.getLat():value.lat;
    if(Number.isFinite(longitude)&&Number.isFinite(latitude)&&longitude>=-180&&longitude<=180&&latitude>=-90&&latitude<=90){post('draw',{longitude:longitude,latitude:latitude,frame:frame});}
  }
  function initialize(){
    if(map||failed){return;}
    if(provider==='AMAP'&&(!window.AMap||!AMap.Map)){return;}
    if(provider==='TIAN_DI_TU'&&(!window.T||!T.Map)){return;}
    try{
      const center=point(state.center);
      if(provider==='AMAP'){
        map=new AMap.Map('map',{center:center,zoom:state.zoom,resizeEnable:true});map.on('complete',onReady);map.on('click',clicked);
      }else{
        const suffix='&SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&FORMAT=tiles&TILEMATRIXSET=w&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk='+encodeURIComponent(state.apiKey);
        const vector=new T.TileLayer('https://t0.tianditu.gov.cn/vec_w/wmts?LAYER=vec'+suffix,{minZoom:1,maxZoom:18});
        const labels=new T.TileLayer('https://t0.tianditu.gov.cn/cva_w/wmts?LAYER=cva'+suffix,{minZoom:1,maxZoom:18});
        map=new T.Map('map',{layers:[vector,labels]});map.addEventListener('load',onReady);map.addEventListener('click',clicked);map.centerAndZoom(center,state.zoom);
      }
      render();
    }catch(_){fail('SDK');}
  }
  function apply(next){
    if(!next||next.documentId!==documentId||next.provider!==provider||next.frame!==frame||next.apiKey!==state.apiKey||failed){return false;}
    state=next;
    try{render();return true;}catch(_){fail('SDK');return false;}
  }
  Object.defineProperty(window,'__runMapApply',{value:apply,writable:false,configurable:false});
  window.addEventListener('error',function(event){if(event&&typeof event.preventDefault==='function'){event.preventDefault();}fail(event&&event.target&&event.target.tagName==='SCRIPT'?'NETWORK':'SDK');},true);
  window.addEventListener('unhandledrejection',function(event){if(event&&typeof event.preventDefault==='function'){event.preventDefault();}fail('SDK');});
  window.addEventListener('securitypolicyviolation',function(){fail('SDK');});
  if(!permissions()){fail('SDK');return;}
  timer=setTimeout(function(){if(!ready){fail('NETWORK');}},15000);
  poll=setInterval(initialize,100);window.addEventListener('load',initialize);
  // Fixed provider tag only, AFTER immutable permission guards, while the parser is active.
  document.write($sdkTag);
})();
</script></body></html>"""
        return RunMapDocument(html, null)
    }

    fun updateScript(model: RunMapModel, documentId: String): String? {
        if (issue(model, documentId) != null) return null
        return "(function(){if(typeof window.__runMapApply==='function'){window.__runMapApply(${safeJson(payload(model, documentId).toString())});}})();"
    }

    fun parseEvent(message: String, model: RunMapModel, documentId: String): RunMapEvent? {
        if (message.length > 4096 || issue(model, documentId) != null) return null
        val fieldCount = flatObjectFields(message) ?: return null
        val root = runCatching { json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return null
        if (root.size != fieldCount || root.string("documentId") != documentId) return null
        return when (root.string("type")) {
            "ready" -> if (root.keys == setOf("documentId", "type")) RunMapEvent.Ready else null
            "failed" -> {
                if (root.keys != setOf("documentId", "type", "reason")) return null
                when (root.string("reason")) {
                    "NETWORK" -> RunMapEvent.Failed(RunMapFailure.NETWORK)
                    "SDK" -> RunMapEvent.Failed(RunMapFailure.SDK)
                    else -> null
                }
            }
            "checkpoint" -> {
                if (root.keys != setOf("documentId", "type", "id")) return null
                val id = root.string("id") ?: return null
                if (model.checkpoints.any { it.id == id }) RunMapEvent.CheckpointSelected(id) else null
            }
            "draw" -> {
                if (!model.editMode || root.keys != setOf("documentId", "type", "longitude", "latitude", "frame")) return null
                val frame = frame(requireNotNull(model.provider))
                if (root.string("frame") != frame.name) return null
                val longitude = root.number("longitude") ?: return null
                val latitude = root.number("latitude") ?: return null
                val point = runCatching { GeoPoint(longitude, latitude) }.getOrNull() ?: return null
                RunMapEvent.DrawPoint(point, frame)
            }
            else -> null
        }
    }

    fun isAllowedNavigation(url: String): Boolean = url == baseUrl || url == "about:blank"

    fun isAllowedRequest(url: String, provider: RunMapProvider?): Boolean {
        if (provider == null || url.length > 8192 || url.any { it <= ' ' || it == '\\' || it.code == 127 }) return false
        if (url == baseUrl) return true
        if (Regex("data:image/(?:png|gif|jpeg|webp);base64,[A-Za-z0-9+/=]+", RegexOption.IGNORE_CASE).matches(url)) return true
        if (Regex("blob:(?:https://map\\.better-zslf\\.invalid/|null/)[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(url)) return true
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val authority = url.substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || authority.any { it.code > 127 || it == '@' || it == '%' }) return false
        val parts = authority.split(':')
        if (parts.size > 2 || (parts.size == 2 && parts[1] != "443")) return false
        val host = parts[0].lowercase()
        if (!Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*").matches(host)) return false
        val domains = when (provider) {
            RunMapProvider.AMAP -> listOf("amap.com", "autonavi.com")
            RunMapProvider.TIAN_DI_TU -> listOf("tianditu.gov.cn", "tianditu.com")
        }
        return domains.any { host == it || host.endsWith(".$it") }
    }

    private fun frame(provider: RunMapProvider): RunMapCoordinateSystem = when (provider) {
        RunMapProvider.AMAP -> RunMapCoordinateSystem.GCJ02
        RunMapProvider.TIAN_DI_TU -> RunMapCoordinateSystem.WGS84
    }

    private fun issue(model: RunMapModel, documentId: String): RunMapFailure? {
        if (!tokenPattern.matches(documentId) || model.provider == null || !keyPattern.matches(model.apiKey.orEmpty())) return RunMapFailure.CONFIGURATION
        if (model.checkpoints.size > MAX_MARKERS) return RunMapFailure.TOO_MANY_POINTS
        var count = model.checkpoints.size + if (model.currentLocation != null) 1 else 0
        for (path in listOf(model.actualPath, model.recommendedPath, model.importedPath)) {
            if (path.segments.size > MAX_POINTS) return RunMapFailure.TOO_MANY_POINTS
            val segments = if (path.segments.isEmpty()) listOf(path.points) else path.segments
            for (segment in segments) {
                if (segment.size > MAX_POINTS - count) return RunMapFailure.TOO_MANY_POINTS
                count += segment.size
                if (segment.isNotEmpty() && path.coordinateSystem == RunMapCoordinateSystem.UNKNOWN) return RunMapFailure.UNCONFIRMED_COORDINATES
            }
        }
        if (model.currentLocation != null && model.currentCoordinateSystem == RunMapCoordinateSystem.UNKNOWN) return RunMapFailure.UNCONFIRMED_COORDINATES
        if (model.checkpoints.any { it.coordinateSystem == RunMapCoordinateSystem.UNKNOWN }) return RunMapFailure.UNCONFIRMED_COORDINATES
        if (model.checkpoints.any { it.id.isEmpty() || it.id.length > 128 || it.id.any { c -> c.code < 32 } || it.label.length > 80 } ||
            model.checkpoints.map { it.id }.toSet().size != model.checkpoints.size) return RunMapFailure.CONFIGURATION
        return null
    }

    private fun payload(model: RunMapModel, documentId: String): JsonObject {
        val provider = requireNotNull(model.provider)
        val destination = frame(provider)
        fun convert(point: GeoPoint, source: RunMapCoordinateSystem): GeoPoint = requireNotNull(RunMapCoordinates.convert(point, source, destination))
        fun point(point: GeoPoint): JsonArray = JsonArray(listOf(JsonPrimitive(point.longitude), JsonPrimitive(point.latitude)))
        fun path(path: RunMapPath): JsonArray = JsonArray(path.allSegments().map { segment -> JsonArray(segment.map { point(convert(it, path.coordinateSystem)) }) })
        val current = model.currentLocation?.let { convert(it, model.currentCoordinateSystem) }
        val first = current ?: listOf(model.recommendedPath, model.actualPath, model.importedPath).firstNotNullOfOrNull { path ->
            path.allSegments().firstOrNull()?.firstOrNull()?.let { convert(it, path.coordinateSystem) }
        } ?: model.checkpoints.firstOrNull()?.let { convert(it.point, it.coordinateSystem) }
        return buildJsonObject {
            put("documentId", documentId); put("provider", provider.name); put("frame", destination.name)
            put("apiKey", model.apiKey); put("editMode", model.editMode); put("dark", model.dark)
            put("actual", path(model.actualPath)); put("recommended", path(model.recommendedPath)); put("imported", path(model.importedPath))
            put("current", current?.let(::point) ?: JsonNull); put("center", point(first ?: GeoPoint(105.0, 35.0))); put("zoom", if (first == null) 4 else 15)
            putJsonArray("checkpoints") { model.checkpoints.forEach { checkpoint -> add(buildJsonObject {
                put("id", checkpoint.id); put("label", checkpoint.label); put("point", point(convert(checkpoint.point, checkpoint.coordinateSystem)))
            }) } }
        }
    }

    private fun safeJson(value: String): String = value.replace("<", "\\u003c").replace(">", "\\u003e")
        .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    /** Events are flat objects. Reject nested structures before parsing, bounding stack use and duplicate fields. */
    private fun flatObjectFields(message: String): Int? {
        val value = message.trim()
        if (!value.startsWith('{') || !value.endsWith('}')) return null
        var quoted = false; var escaped = false; var depth = 0; var fields = 0
        for (char in value) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> { depth++; if (depth != 1) return null }
                '}' -> { depth--; if (depth < 0) return null }
                '[', ']' -> return null
                ':' -> fields++
            }
        }
        return if (!quoted && depth == 0) fields else null
    }
}
