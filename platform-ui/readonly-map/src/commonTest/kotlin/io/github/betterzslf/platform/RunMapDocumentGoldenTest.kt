package io.github.betterzslf.platform

import io.github.betterzslf.feature.run.GeoPoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val ORIGINAL_EMPTY_DOCUMENT_ALIAS: String = RunMapBootstrapPolicy.emptyDocument

/** Independent UTF-8 literals for the owned renderer; no CDN, native view, location, or device is used. */
class RunMapDocumentGoldenTest {
    private val p = GeoPoint(longitude = 1.25, latitude = 2.5)
    private val q = GeoPoint(longitude = 3.75, latitude = 4.5)
    private val publicFixture = RunMapModel(
        provider = RunMapProvider.AMAP,
        apiKey = "public_fixture_key_44",
        actualPath = RunMapPath(points = listOf(p, q), coordinateSystem = RunMapCoordinateSystem.WGS84),
        recommendedPath = RunMapPath(points = listOf(p, q), coordinateSystem = RunMapCoordinateSystem.WGS84),
        importedPath = RunMapPath(coordinateSystem = RunMapCoordinateSystem.WGS84, segments = listOf(listOf(q, p))),
        currentLocation = p,
        currentCoordinateSystem = RunMapCoordinateSystem.WGS84,
        checkpoints = listOf(
            RunMapCheckpoint(
                id = "checkpoint_fixture",
                point = q,
                label = "public<&>\u2028\u2029",
                coordinateSystem = RunMapCoordinateSystem.WGS84,
            ),
        ),
        dark = true,
    )

    @Test
    fun amapDocumentPreservesReviewedUtf8() {
        val document = RunMapWeb.document(publicFixture, "platform_golden_document_44")
        assertNull(document.issue)
        val html = assertNotNull(document.html)
        // Reviewed UTF-8: 8727 bytes, SHA-256 5fc2f04bcda40b2579f36ecf6170d44a5feabf2540190118f232051b4f88143c.
        val expected = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; base-uri 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; form-action 'none'; media-src 'none'; script-src 'nonce-platform_golden_document_44' 'unsafe-eval' https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; style-src 'unsafe-inline' https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; img-src data: blob: https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; font-src data: https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; connect-src https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; worker-src blob:;">
<style>html,body,#map{width:100%;height:100%;margin:0;overflow:hidden}body{background:#eef2f5}.checkpoint-label{display:inline-block;background:#fff;color:#17212b;border:2px solid #34F18C;border-radius:8px;padding:4px 7px;font:13px sans-serif;white-space:pre-wrap;max-width:180px}.position-dot{width:12px;height:12px;background:#2688ff;border:2px solid white;border-radius:50%}</style>
</head><body><div id="map" role="img" aria-label="路线地图"></div>
<script id="run-map-data" type="application/json">{"documentId":"platform_golden_document_44","provider":"AMAP","frame":"GCJ02","apiKey":"public_fixture_key_44","dark":true,"actual":[[[1.25,2.5],[3.75,4.5]]],"recommended":[[[1.25,2.5],[3.75,4.5]]],"imported":[[[3.75,4.5],[1.25,2.5]]],"current":[1.25,2.5],"center":[1.25,2.5],"zoom":15,"checkpoints":[{"id":"checkpoint_fixture","label":"public\u003c\u0026\u003e\u2028\u2029","point":[3.75,4.5]}]}</script>
<script nonce="platform_golden_document_44">
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
  function initialize(){
    if(map||failed){return;}
    if(provider==='AMAP'&&(!window.AMap||!AMap.Map)){return;}
    if(provider==='TIAN_DI_TU'&&(!window.T||!T.Map)){return;}
    try{
      const center=point(state.center);
      if(provider==='AMAP'){
        map=new AMap.Map('map',{center:center,zoom:state.zoom,resizeEnable:true});map.on('complete',onReady);
      }else{
        const suffix='&SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&FORMAT=tiles&TILEMATRIXSET=w&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk='+encodeURIComponent(state.apiKey);
        const vector=new T.TileLayer('https://t0.tianditu.gov.cn/vec_w/wmts?LAYER=vec'+suffix,{minZoom:1,maxZoom:18});
        const labels=new T.TileLayer('https://t0.tianditu.gov.cn/cva_w/wmts?LAYER=cva'+suffix,{minZoom:1,maxZoom:18});
        map=new T.Map('map',{layers:[vector,labels]});map.addEventListener('load',onReady);map.centerAndZoom(center,state.zoom);
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
  document.write("\u003cscript nonce=\"platform_golden_document_44\" src=\"https://webapi.amap.com/maps?v=1.4.15\u0026amp;key=public_fixture_key_44\"\u003e\u003c/script\u003e");
})();
</script></body></html>"""
        assertContentEquals(expected.encodeToByteArray(), html.encodeToByteArray())
        for (drawing in listOf("editMode", "function clicked(event)", "post('draw'", "map.on('click',clicked);", "map.addEventListener('click',clicked);")) assertFalse(html.contains(drawing))
    }

    @Test
    fun tianDiTuDocumentPreservesReviewedUtf8() {
        val document = RunMapWeb.document(publicFixture.copy(provider = RunMapProvider.TIAN_DI_TU), "platform_golden_document_44")
        assertNull(document.issue)
        val html = assertNotNull(document.html)
        // Reviewed UTF-8: 8802 bytes, SHA-256 cc8bde11f4c28f34ba3d164ace82168c9d6092efce9b0548a1b4a779f71d0203.
        val expected = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; base-uri 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; form-action 'none'; media-src 'none'; script-src 'nonce-platform_golden_document_44' 'unsafe-eval' https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; style-src 'unsafe-inline' https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; img-src data: blob: https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; font-src data: https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; connect-src https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; worker-src blob:;">
<style>html,body,#map{width:100%;height:100%;margin:0;overflow:hidden}body{background:#eef2f5}.checkpoint-label{display:inline-block;background:#fff;color:#17212b;border:2px solid #34F18C;border-radius:8px;padding:4px 7px;font:13px sans-serif;white-space:pre-wrap;max-width:180px}.position-dot{width:12px;height:12px;background:#2688ff;border:2px solid white;border-radius:50%}</style>
</head><body><div id="map" role="img" aria-label="路线地图"></div>
<script id="run-map-data" type="application/json">{"documentId":"platform_golden_document_44","provider":"TIAN_DI_TU","frame":"WGS84","apiKey":"public_fixture_key_44","dark":true,"actual":[[[1.25,2.5],[3.75,4.5]]],"recommended":[[[1.25,2.5],[3.75,4.5]]],"imported":[[[3.75,4.5],[1.25,2.5]]],"current":[1.25,2.5],"center":[1.25,2.5],"zoom":15,"checkpoints":[{"id":"checkpoint_fixture","label":"public\u003c\u0026\u003e\u2028\u2029","point":[3.75,4.5]}]}</script>
<script nonce="platform_golden_document_44">
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
  function initialize(){
    if(map||failed){return;}
    if(provider==='AMAP'&&(!window.AMap||!AMap.Map)){return;}
    if(provider==='TIAN_DI_TU'&&(!window.T||!T.Map)){return;}
    try{
      const center=point(state.center);
      if(provider==='AMAP'){
        map=new AMap.Map('map',{center:center,zoom:state.zoom,resizeEnable:true});map.on('complete',onReady);
      }else{
        const suffix='&SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&FORMAT=tiles&TILEMATRIXSET=w&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk='+encodeURIComponent(state.apiKey);
        const vector=new T.TileLayer('https://t0.tianditu.gov.cn/vec_w/wmts?LAYER=vec'+suffix,{minZoom:1,maxZoom:18});
        const labels=new T.TileLayer('https://t0.tianditu.gov.cn/cva_w/wmts?LAYER=cva'+suffix,{minZoom:1,maxZoom:18});
        map=new T.Map('map',{layers:[vector,labels]});map.addEventListener('load',onReady);map.centerAndZoom(center,state.zoom);
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
  document.write("\u003cscript nonce=\"platform_golden_document_44\" src=\"https://api.tianditu.gov.cn/api?v=4.0\u0026amp;tk=public_fixture_key_44\"\u003e\u003c/script\u003e");
})();
</script></body></html>"""
        assertContentEquals(expected.encodeToByteArray(), html.encodeToByteArray())
        for (drawing in listOf("editMode", "function clicked(event)", "post('draw'", "map.on('click',clicked);", "map.addEventListener('click',clicked);")) assertFalse(html.contains(drawing))
    }

    @Test
    fun bootstrapDocumentPreservesOriginalLiteralAndUtf8() {
        // Original UTF-8: 139 bytes, SHA-256 af4e5d3d3a20675a68612da77faef4f9188e4707e08f900830fdc5c7c498637f.
        val expected = """<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'none'"><title>地图暂不可用</title>"""
        assertEquals(expected, RunMapBootstrapPolicy.emptyDocument)
        assertContentEquals(expected.encodeToByteArray(), ORIGINAL_EMPTY_DOCUMENT_ALIAS.encodeToByteArray())
    }
}
