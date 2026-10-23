package io.github.betterzslf.core.text

import io.github.betterzslf.edition.BuildEdition
import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.platform.RunMapBootstrapPolicy
import io.github.betterzslf.platform.RunMapCheckpoint
import io.github.betterzslf.platform.RunMapCoordinateSystem
import io.github.betterzslf.platform.RunMapModel
import io.github.betterzslf.platform.RunMapPath
import io.github.betterzslf.platform.RunMapProvider
import io.github.betterzslf.platform.RunMapWeb
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val ORIGINAL_EMPTY_DOCUMENT_ALIAS: String = RunMapBootstrapPolicy.emptyDocument

/** Expectations are independent literals copied from the pre-migration source snapshot, not AppText. */
class AppTextPlatformGoldenTest {
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
        editMode = true,
        dark = true,
    )

    @Test
    fun originalStaticPlatformDisplayCopy() {
        assertEquals("本地数据保存失败", AppText.Common.LOCAL_DATA_SAVE_FAILED)
        assertEquals("文件读取失败，请重新选择。", AppText.Common.PICKED_FILE_READ_FAILED_RESELECT)
        assertEquals("文件超过 8 MiB 限制，请选择较小文件。", AppText.Common.PICKED_FILE_SIZE_LIMIT_EXCEEDED_SELECT_SMALLER)
        assertEquals("文件选择器暂不可用。", AppText.Common.FILE_PICKER_TEMPORARILY_UNAVAILABLE)
        assertEquals("设备未提供蓝牙适配器。", AppText.Water.BLUETOOTH_ADAPTER_UNAVAILABLE)
        assertEquals("请先授予附近设备权限。", AppText.Water.NEARBY_DEVICES_PERMISSION_REQUIRED)
        assertEquals("请打开蓝牙。", AppText.Water.ENABLE_BLUETOOTH_REQUIRED)
        assertEquals("附近设备权限请求失败，请在设置中检查。", AppText.Water.NEARBY_DEVICES_PERMISSION_REQUEST_FAILED_CHECK_SETTINGS)
        assertEquals("请在系统设置中打开蓝牙。", AppText.Water.ENABLE_BLUETOOTH_IN_SYSTEM_SETTINGS)
        assertEquals("定位间隔应为 10–60 秒。", AppText.Run.LOCATION_SAMPLING_INTERVAL_OUT_OF_RANGE)
        assertEquals("请先授予精确定位权限。", AppText.Run.PRECISE_LOCATION_PERMISSION_REQUIRED)
        assertEquals("请打开设备定位与 GPS。", AppText.Run.ENABLE_DEVICE_LOCATION_AND_GPS)
        assertEquals("定位权限已变化，请重新检查。", AppText.Run.LOCATION_PERMISSION_CHANGED_RECHECK)
        assertEquals("请在应用前台重新开始定位。", AppText.Run.RESTART_LOCATION_TRACKING_IN_FOREGROUND)
        assertEquals("校园跑定位", AppText.Run.LOCATION_TRACKING_NOTIFICATION_CHANNEL_DISPLAY_NAME)
        assertEquals("定位启动失败，请在应用前台重试。", AppText.Run.LOCATION_START_FAILED_RETRY_IN_FOREGROUND)
        assertEquals("设备定位已关闭，记录暂停。", AppText.Run.DEVICE_LOCATION_DISABLED_RECORDING_PAUSED)
        assertEquals("校园跑正在记录位置", AppText.Run.LOCATION_TRACKING_NOTIFICATION_TITLE)
        assertEquals("高精度定位已开启；点按返回应用管理记录。", AppText.Run.HIGH_ACCURACY_LOCATION_ENABLED_RETURN_TO_MANAGE_RECORDING)
        assertEquals("停止定位", AppText.Run.STOP_LOCATION_TRACKING_ACTION)
        assertEquals("下载服务暂不可用，请稍后重试。", AppText.Updates.DOWNLOAD_SERVICE_UNAVAILABLE_RETRY_LATER)
        assertEquals("更新安装包信息无效，请重新检查发布版本。", AppText.Updates.INVALID_UPDATE_PACKAGE_INFORMATION_RECHECK_RELEASE)
        assertEquals("正在下载公开发布的安装包。", AppText.Updates.DOWNLOADING_PUBLIC_RELEASE_PACKAGE)
        assertEquals("更新准备已取消；已打开的系统安装页面仍由你确认。", AppText.Updates.UPDATE_PREPARATION_CANCELLED_SYSTEM_INSTALL_CONFIRMATION_REMAINS)
        assertEquals("更新下载器已关闭。", AppText.Updates.UPDATE_DOWNLOADER_CLOSED)
        assertEquals("better-zslf 更新", AppText.Updates.UPDATE_DOWNLOAD_NOTIFICATION_TITLE)
        assertEquals("正在下载应用公开发布的安装包", AppText.Updates.UPDATE_DOWNLOAD_NOTIFICATION_DESCRIPTION)
        assertEquals("正在校验安装包。", AppText.Updates.VERIFYING_INSTALL_PACKAGE)
        assertEquals("安装包校验完成，点击后仍需系统安装确认。", AppText.Updates.PACKAGE_VERIFIED_SYSTEM_INSTALL_CONFIRMATION_REQUIRED)
        assertEquals("更新下载或校验失败，请重新尝试。", AppText.Updates.UPDATE_DOWNLOAD_OR_VERIFICATION_FAILED_RETRY)
        assertEquals("更新下载等待超时，请重新尝试。", AppText.Updates.UPDATE_DOWNLOAD_WAIT_TIMEOUT_RETRY)
        assertEquals("本次更新下载已停止，请重新尝试。", AppText.Updates.CURRENT_UPDATE_DOWNLOAD_STOPPED_RETRY)
        assertEquals("安装包大小与发布信息不一致。", AppText.Updates.PACKAGE_SIZE_RELEASE_INFORMATION_MISMATCH)
        assertEquals("更新下载失败，请重新尝试。", AppText.Updates.UPDATE_DOWNLOAD_FAILED_RETRY)
        assertEquals("本次安装包文件无效。", AppText.Updates.CURRENT_INSTALL_PACKAGE_FILE_INVALID)
        assertEquals("安装包大小超过本次发布信息。", AppText.Updates.PACKAGE_SIZE_EXCEEDS_RELEASE_INFORMATION)
        assertEquals("安装包校验值与发布信息不一致，请重新下载。", AppText.Updates.PACKAGE_CHECKSUM_RELEASE_INFORMATION_MISMATCH_REDOWNLOAD)
        assertEquals("安装包格式无效。", AppText.Updates.INVALID_INSTALL_PACKAGE_FORMAT)
        assertEquals("安装包的应用或版本与本次更新不符。", AppText.Updates.PACKAGE_APP_OR_VERSION_UPDATE_MISMATCH)
        assertEquals("安装包签名与当前应用不一致，请停止安装。", AppText.Updates.PACKAGE_SIGNATURE_CURRENT_APP_MISMATCH_STOP_INSTALLATION)
        assertEquals("正在重新校验安装包。", AppText.Updates.REVERIFYING_INSTALL_PACKAGE)
        assertEquals("请回到应用前台后重新确认安装。", AppText.Updates.RETURN_TO_FOREGROUND_AND_RECONFIRM_INSTALLATION)
        assertEquals("已请求本应用的安装来源设置；返回后请再次明确点击安装。", AppText.Updates.INSTALL_SOURCE_SETTINGS_REQUESTED_EXPLICIT_INSTALL_RECONFIRMATION_REQUIRED)
        assertEquals("已向系统请求打开安装确认；尚未确认安装。", AppText.Updates.SYSTEM_INSTALL_CONFIRMATION_REQUESTED_INSTALL_UNCONFIRMED)
        assertEquals("系统安装页面暂不可用，请重新尝试。", AppText.Updates.SYSTEM_INSTALLER_UNAVAILABLE_RETRY)
        assertEquals("应用下载目录暂不可用。", AppText.Updates.APP_DOWNLOAD_DIRECTORY_UNAVAILABLE)
        assertEquals("应用更新缓存暂不可用。", AppText.Updates.APP_UPDATE_CACHE_UNAVAILABLE)
        assertEquals("本次更新临时目录无效。", AppText.Updates.CURRENT_UPDATE_TEMPORARY_DIRECTORY_INVALID)
        assertEquals("本次更新临时文件清理尚未完成。", AppText.Updates.CURRENT_UPDATE_TEMPORARY_FILE_CLEANUP_INCOMPLETE)
        assertEquals("本次更新临时目录清理尚未完成。", AppText.Updates.CURRENT_UPDATE_TEMPORARY_DIRECTORY_CLEANUP_INCOMPLETE)
        assertEquals("本次下载状态保存失败，请重新尝试。", AppText.Updates.CURRENT_DOWNLOAD_STATE_SAVE_FAILED_RETRY)
        assertEquals("请明确请求蓝牙权限后再开始扫描", AppText.Water.EXPLICIT_BLUETOOTH_PERMISSION_REQUEST_REQUIRED_BEFORE_SCAN)
        assertEquals("请在应用前台请求蓝牙权限", AppText.Water.REQUEST_BLUETOOTH_PERMISSION_IN_FOREGROUND)
        assertEquals("请在系统设置 → 蓝牙中打开蓝牙；若权限已关闭，请在本应用设置中允许蓝牙后返回并重新检查", AppText.Water.ENABLE_BLUETOOTH_AND_ALLOW_APP_PERMISSION_THEN_RECHECK)
        assertEquals("此设备不支持所需蓝牙功能", AppText.Water.REQUIRED_BLUETOOTH_FEATURES_UNSUPPORTED)
        assertEquals("蓝牙权限已关闭或受系统限制，请检查本应用的系统设置", AppText.Water.BLUETOOTH_PERMISSION_DISABLED_OR_RESTRICTED_CHECK_APP_SETTINGS)
        assertEquals("等待系统蓝牙授权；选择后可再次点击检查权限", AppText.Water.AWAITING_SYSTEM_BLUETOOTH_AUTHORIZATION_RECHECK_AFTER_SELECTION)
        assertEquals("蓝牙已关闭，请在系统设置 → 蓝牙中打开", AppText.Water.BLUETOOTH_DISABLED_ENABLE_IN_SYSTEM_SETTINGS)
        assertEquals("系统蓝牙正在重置，请稍后明确重新检查", AppText.Water.SYSTEM_BLUETOOTH_RESETTING_RECHECK_EXPLICITLY_LATER)
        assertEquals("系统尚未允许使用蓝牙，请检查权限", AppText.Water.SYSTEM_BLUETOOTH_ACCESS_NOT_GRANTED_CHECK_PERMISSION)
        assertEquals("正在等待系统蓝牙状态", AppText.Water.AWAITING_SYSTEM_BLUETOOTH_STATE)
        assertEquals("请返回当前页面后重新选择文件", AppText.Common.RETURN_TO_CURRENT_PAGE_AND_RESELECT_FILE)
        assertEquals("所选文件暂不可读，请重新选择", AppText.Common.SELECTED_FILE_UNREADABLE_RESELECT)
        assertEquals("文件超过 8 MiB 限制", AppText.Common.PICKED_FILE_SIZE_LIMIT_EXCEEDED)
        assertEquals("读取文件失败，请重新选择或下载到本机后重试", AppText.Common.PICKED_FILE_READ_FAILED_RESELECT_OR_DOWNLOAD_LOCALLY)
        assertEquals("定位记录器已关闭", AppText.Run.LOCATION_TRACKER_CLOSED)
        assertEquals("正在检查定位服务", AppText.Run.CHECKING_LOCATION_SERVICES)
        assertEquals("系统定位服务已关闭，请在设置中开启", AppText.Run.SYSTEM_LOCATION_SERVICES_DISABLED_ENABLE_IN_SETTINGS)
        assertEquals("请在应用前台明确开始或恢复记录", AppText.Run.EXPLICIT_RECORDING_START_OR_RESUME_IN_FOREGROUND_REQUIRED)
        assertEquals("当前应用未配置连续后台定位", AppText.Run.CONTINUOUS_BACKGROUND_LOCATION_NOT_CONFIGURED)
        assertEquals("请先允许使用期间的精确定位，再明确开始记录", AppText.Run.ALLOW_PRECISE_LOCATION_WHILE_USING_BEFORE_EXPLICIT_RECORDING)
        assertEquals("正在等待精确定位信号", AppText.Run.AWAITING_PRECISE_LOCATION_SIGNAL)
        assertEquals("定位样本处理阻塞，请返回前台后重新开始", AppText.Run.LOCATION_SAMPLE_PROCESSING_BLOCKED_RESTART_IN_FOREGROUND)
        assertEquals("定位信号暂不可用，正在等待新样本", AppText.Run.LOCATION_SIGNAL_UNAVAILABLE_AWAITING_NEW_SAMPLE)
        assertEquals("定位服务发生错误，请返回前台后明确重新开始", AppText.Run.LOCATION_SERVICE_ERROR_RESTART_EXPLICITLY_IN_FOREGROUND)
        assertEquals("精确定位权限已变化，请检查系统设置", AppText.Run.PRECISE_LOCATION_PERMISSION_CHANGED_CHECK_SYSTEM_SETTINGS)
        assertEquals("此平台请在发布页面查看更新，不下载 Android 安装包。", AppText.Updates.PLATFORM_RELEASE_PAGE_ONLY_NO_ANDROID_PACKAGE_DOWNLOAD)
        assertEquals("宿主测试目标未连接文件选择器。", AppText.Common.HOST_TEST_FILE_PICKER_NOT_CONNECTED)
        assertEquals("宿主测试目标未连接定位源。", AppText.Run.HOST_TEST_LOCATION_SOURCE_NOT_CONNECTED)
        assertEquals("宿主测试目标未连接蓝牙适配器。", AppText.Water.HOST_TEST_BLUETOOTH_ADAPTER_NOT_CONNECTED)
        assertEquals("本机测试环境不显示原生地图；可检查路线与坐标。", AppText.Run.HOST_TEST_NATIVE_MAP_UNAVAILABLE_INSPECT_ROUTE_AND_COORDINATES)
        assertEquals("路线地图", AppText.Run.ROUTE_MAP_ACCESSIBILITY_LABEL)
        assertEquals("地图暂不可用", AppText.Run.MAP_TEMPORARILY_UNAVAILABLE_DOCUMENT_TITLE)
    }

    @Test
    fun amapDocumentPreservesOriginalUtf8() {
        val document = RunMapWeb.document(publicFixture, "platform_golden_document_44")
        assertNull(document.issue)
        val html = assertNotNull(document.html)
        // Original UTF-8: 9281 bytes, SHA-256 80ffee5e183cce08eda42a0708410e0fa8a2fcac6616a6c5e03cff955a6e5f30.
        val expected = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; base-uri 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; form-action 'none'; media-src 'none'; script-src 'nonce-platform_golden_document_44' 'unsafe-eval' https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; style-src 'unsafe-inline' https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; img-src data: blob: https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; font-src data: https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; connect-src https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; worker-src blob:;">
<style>html,body,#map{width:100%;height:100%;margin:0;overflow:hidden}body{background:#eef2f5}.checkpoint-label{display:inline-block;background:#fff;color:#17212b;border:2px solid #34F18C;border-radius:8px;padding:4px 7px;font:13px sans-serif;white-space:pre-wrap;max-width:180px}.position-dot{width:12px;height:12px;background:#2688ff;border:2px solid white;border-radius:50%}</style>
</head><body><div id="map" role="img" aria-label="路线地图"></div>
<script id="run-map-data" type="application/json">{"documentId":"platform_golden_document_44","provider":"AMAP","frame":"GCJ02","apiKey":"public_fixture_key_44","editMode":true,"dark":true,"actual":[[[1.25,2.5],[3.75,4.5]]],"recommended":[[[1.25,2.5],[3.75,4.5]]],"imported":[[[3.75,4.5],[1.25,2.5]]],"current":[1.25,2.5],"center":[1.25,2.5],"zoom":15,"checkpoints":[{"id":"checkpoint_fixture","label":"public\u003c\u0026\u003e\u2028\u2029","point":[3.75,4.5]}]}</script>
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
  document.write("\u003cscript nonce=\"platform_golden_document_44\" src=\"https://webapi.amap.com/maps?v=1.4.15\u0026amp;key=public_fixture_key_44\"\u003e\u003c/script\u003e");
})();
</script></body></html>"""
        // Independent read-only standard snapshot: only editMode and drawing code differ.
        val expectedStandard = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; base-uri 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; form-action 'none'; media-src 'none'; script-src 'nonce-platform_golden_document_44' 'unsafe-eval' https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; style-src 'unsafe-inline' https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; img-src data: blob: https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; font-src data: https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; connect-src https://amap.com https://*.amap.com https://autonavi.com https://*.autonavi.com; worker-src blob:;">
<style>html,body,#map{width:100%;height:100%;margin:0;overflow:hidden}body{background:#eef2f5}.checkpoint-label{display:inline-block;background:#fff;color:#17212b;border:2px solid #34F18C;border-radius:8px;padding:4px 7px;font:13px sans-serif;white-space:pre-wrap;max-width:180px}.position-dot{width:12px;height:12px;background:#2688ff;border:2px solid white;border-radius:50%}</style>
</head><body><div id="map" role="img" aria-label="路线地图"></div>
<script id="run-map-data" type="application/json">{"documentId":"platform_golden_document_44","provider":"AMAP","frame":"GCJ02","apiKey":"public_fixture_key_44","editMode":false,"dark":true,"actual":[[[1.25,2.5],[3.75,4.5]]],"recommended":[[[1.25,2.5],[3.75,4.5]]],"imported":[[[3.75,4.5],[1.25,2.5]]],"current":[1.25,2.5],"center":[1.25,2.5],"zoom":15,"checkpoints":[{"id":"checkpoint_fixture","label":"public\u003c\u0026\u003e\u2028\u2029","point":[3.75,4.5]}]}</script>
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
        assertContentEquals((if (BuildEdition.isPro) expected else expectedStandard).encodeToByteArray(), html.encodeToByteArray())
        if (!BuildEdition.isPro) {
            assertEquals(false, html.contains("function clicked(event)"))
            assertEquals(false, html.contains("post('draw'"))
            assertEquals(false, html.contains("map.on('click',clicked);"))
            assertEquals(false, html.contains("map.addEventListener('click',clicked);"))
        }
    }

    @Test
    fun tianDiTuDocumentPreservesOriginalUtf8() {
        val document = RunMapWeb.document(publicFixture.copy(provider = RunMapProvider.TIAN_DI_TU), "platform_golden_document_44")
        assertNull(document.issue)
        val html = assertNotNull(document.html)
        // Original UTF-8: 9356 bytes, SHA-256 32b15e51d9dee508c257088f9cb57512289d1db3ed49e234afde54729c48983a.
        val expected = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; base-uri 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; form-action 'none'; media-src 'none'; script-src 'nonce-platform_golden_document_44' 'unsafe-eval' https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; style-src 'unsafe-inline' https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; img-src data: blob: https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; font-src data: https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; connect-src https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; worker-src blob:;">
<style>html,body,#map{width:100%;height:100%;margin:0;overflow:hidden}body{background:#eef2f5}.checkpoint-label{display:inline-block;background:#fff;color:#17212b;border:2px solid #34F18C;border-radius:8px;padding:4px 7px;font:13px sans-serif;white-space:pre-wrap;max-width:180px}.position-dot{width:12px;height:12px;background:#2688ff;border:2px solid white;border-radius:50%}</style>
</head><body><div id="map" role="img" aria-label="路线地图"></div>
<script id="run-map-data" type="application/json">{"documentId":"platform_golden_document_44","provider":"TIAN_DI_TU","frame":"WGS84","apiKey":"public_fixture_key_44","editMode":true,"dark":true,"actual":[[[1.25,2.5],[3.75,4.5]]],"recommended":[[[1.25,2.5],[3.75,4.5]]],"imported":[[[3.75,4.5],[1.25,2.5]]],"current":[1.25,2.5],"center":[1.25,2.5],"zoom":15,"checkpoints":[{"id":"checkpoint_fixture","label":"public\u003c\u0026\u003e\u2028\u2029","point":[3.75,4.5]}]}</script>
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
  document.write("\u003cscript nonce=\"platform_golden_document_44\" src=\"https://api.tianditu.gov.cn/api?v=4.0\u0026amp;tk=public_fixture_key_44\"\u003e\u003c/script\u003e");
})();
</script></body></html>"""
        // Independent read-only standard snapshot: only editMode and drawing code differ.
        val expectedStandard = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; base-uri 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; form-action 'none'; media-src 'none'; script-src 'nonce-platform_golden_document_44' 'unsafe-eval' https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; style-src 'unsafe-inline' https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; img-src data: blob: https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; font-src data: https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; connect-src https://tianditu.gov.cn https://*.tianditu.gov.cn https://tianditu.com https://*.tianditu.com; worker-src blob:;">
<style>html,body,#map{width:100%;height:100%;margin:0;overflow:hidden}body{background:#eef2f5}.checkpoint-label{display:inline-block;background:#fff;color:#17212b;border:2px solid #34F18C;border-radius:8px;padding:4px 7px;font:13px sans-serif;white-space:pre-wrap;max-width:180px}.position-dot{width:12px;height:12px;background:#2688ff;border:2px solid white;border-radius:50%}</style>
</head><body><div id="map" role="img" aria-label="路线地图"></div>
<script id="run-map-data" type="application/json">{"documentId":"platform_golden_document_44","provider":"TIAN_DI_TU","frame":"WGS84","apiKey":"public_fixture_key_44","editMode":false,"dark":true,"actual":[[[1.25,2.5],[3.75,4.5]]],"recommended":[[[1.25,2.5],[3.75,4.5]]],"imported":[[[3.75,4.5],[1.25,2.5]]],"current":[1.25,2.5],"center":[1.25,2.5],"zoom":15,"checkpoints":[{"id":"checkpoint_fixture","label":"public\u003c\u0026\u003e\u2028\u2029","point":[3.75,4.5]}]}</script>
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
        assertContentEquals((if (BuildEdition.isPro) expected else expectedStandard).encodeToByteArray(), html.encodeToByteArray())
        if (!BuildEdition.isPro) {
            assertEquals(false, html.contains("function clicked(event)"))
            assertEquals(false, html.contains("post('draw'"))
            assertEquals(false, html.contains("map.on('click',clicked);"))
            assertEquals(false, html.contains("map.addEventListener('click',clicked);"))
        }
    }

    @Test
    fun bootstrapDocumentPreservesOriginalLiteralAndUtf8() {
        // Original UTF-8: 139 bytes, SHA-256 af4e5d3d3a20675a68612da77faef4f9188e4707e08f900830fdc5c7c498637f.
        val expected = """<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'none'"><title>地图暂不可用</title>"""
        assertEquals(expected, RunMapBootstrapPolicy.emptyDocument)
        assertContentEquals(expected.encodeToByteArray(), ORIGINAL_EMPTY_DOCUMENT_ALIAS.encodeToByteArray())
    }
}
