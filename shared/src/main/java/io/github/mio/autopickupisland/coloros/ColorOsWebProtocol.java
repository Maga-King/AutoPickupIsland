package io.github.mio.autopickupisland.coloros;

import java.util.List;
import org.json.JSONArray;

/**
 * Read-only WebViewExtractHelper result types 0..5, from the user's ColorOS16 firmware.
 * Script literals copied byte-for-byte from baksmali; do not replace innerText with
 * accessibility text, normalize the route, deduplicate portal text, or inject JS observers.
 * Framework SHA256: 3B47A02BEC547B3218F8A9FF94E11BB443E985B9B980314A9E0BEDD82DF8AF78.
 * The OS4 cross-process transport is separate. Having these scripts is NOT that transport.
 * Type 6 refreshMiniProgram mutates navigation and is deliberately not exposed here.
 */
public final class ColorOsWebProtocol {
    private ColorOsWebProtocol() { }

    private static final String PAGE_ID = "javascript:(function() {  var arrays = new Array();  var index = 0;  arrays[index++] = window.location.href;  arrays[index++] = window.__route__;  arrays[index++] = window.__appId__;  try{    var appId = window.$HybridDataMineManager.envInfo.appId;    arrays[index++] = appId;  } catch(err) { arrays[index++] = \'\';}  return arrays;})();";

    private static final String DOCUMENT_HTML = "javascript:(function() {  var arrays = new Array();  var index = 0;  arrays[index++] = window.location.href;  arrays[index++] = document.documentElement.outerHTML;  return arrays;})();";

    private static final String CONTENT_BY_ROUTE = "    javascript:(function(isAlipayMini,isUseLabelPathChange,useRootPortal) {\n        var arrays = new Array();\n        var href = window.location.href;\n        arrays.push(href);\n        var route = \'\';\n        if (isAlipayMini) {\n            var hashIndex = href.indexOf(\'#\');\n            var questionMarkIndex = href.indexOf(\'?\');\n            if (hashIndex !== -1) {\n                if (questionMarkIndex !== -1 && questionMarkIndex > hashIndex) {\n                    route = href.substring(hashIndex + 1, questionMarkIndex);\n                } else {\n                    route = href.substring(hashIndex + 1);\n                }\n            }\n        } else {\n            route = window.__route__;\n        }\n        var targetRoutes = %s;\n        if (isUseLabelPathChange || targetRoutes.includes(route)) {\n            var text = document.body.innerText;\n            if (useRootPortal) {\n                var rootPortals = document.querySelectorAll(\'wx-root-portal-content\');\n                var wxText = Array.from(rootPortals).map(el => el.innerText.trim()).filter(text => text.length > 0).join(\'\\n\');\n                text = wxText + \'\\n\' + text;\n            }\n            arrays.push(text);\n        } else {\n            arrays.push(\'\');\n        }\n        arrays.push(route);\n        arrays.push(window.__appId__);\n        try {\n            var appId = window.$HybridDataMineManager.envInfo.appId;\n            arrays.push(appId);\n        } catch (err) {\n            arrays.push(\'\');\n        }\n        arrays.push(window.__queryString__);\n        return arrays;\n    })(%s,%s,%s);\n";

    private static final String LEGACY_LEAVES = "javascript:(function() {  let allNodes = document.querySelectorAll(\'*\');  let leafNodes = Array.from(allNodes).filter(node => {    if(node.firstElementChild) return false;    let rect = node.getBoundingClientRect();    return (rect.width > 0 && rect.height > 0);  });  let nodes = leafNodes;  var arrays = new Array();  var index = 0;  arrays[index++] = window.location.href;  arrays[index++] = document.body.innerText;  arrays[index++] = window.__route__;  arrays[index++] = window.__appId__;  try{    var appId = window.$HybridDataMineManager.envInfo.appId;    arrays[index++] = appId;  } catch(err) { arrays[index++] = \'\';}  try{    var bookUrl = window.HeytapReader.getBookUrl();    var bookImgUrl = window.HeytapReader.getBookImgUrl();    var novelInfo = window.HeytapReader.getNovelInfo();    var novelTitle = window.HeytapReader.getNovelTitle();    arrays[index++] = window.HeytapReader.getBookUrl();    arrays[index++] = window.HeytapReader.getBookImgUrl();    arrays[index++] = window.HeytapReader.getNovelInfo();    arrays[index++] = window.HeytapReader.getNovelTitle();  } catch(err) {    arrays[index++] = \'\';    arrays[index++] = \'\';    arrays[index++] = \'\';    arrays[index++] = \'\';  }  for (let i = 0; i < nodes.length; i++) {      var j = 0;      var array = new Array();      array[j++] = nodes[i].tagName;      array[j++] = nodes[i].textContent;      if (!nodes[i].src && nodes[i].style && nodes[i].style.cssText) {        array[j++] = \'style:\' + nodes[i].style.cssText;      } else {        array[j++] = nodes[i].src;      }      var rect = nodes[i].getBoundingClientRect();      array[j++] = Number.parseInt(rect.x);      array[j++] = Number.parseInt(rect.y);      array[j++] = Number.parseInt(rect.width);      array[j++] = Number.parseInt(rect.height);      arrays[index++] = array;  }  return arrays;})();";

    private static final String LEGACY_NODES = "javascript:(function() {  let allNodes = document.querySelectorAll(\'*:not(script)\');  let nodes = allNodes;  var arrays = new Array();  var index = 0;  arrays[index++] = window.location.href;  arrays[index++] = document.body.innerText;  arrays[index++] = window.__route__;  arrays[index++] = window.__appId__;  try{    var appId = window.$HybridDataMineManager.envInfo.appId;    arrays[index++] = appId;  } catch(err) { arrays[index++] = \'\';}  try{    var bookUrl = window.HeytapReader.getBookUrl();    var bookImgUrl = window.HeytapReader.getBookImgUrl();    var novelInfo = window.HeytapReader.getNovelInfo();    var novelTitle = window.HeytapReader.getNovelTitle();    arrays[index++] = window.HeytapReader.getBookUrl();    arrays[index++] = window.HeytapReader.getBookImgUrl();    arrays[index++] = window.HeytapReader.getNovelInfo();    arrays[index++] = window.HeytapReader.getNovelTitle();  } catch(err) {    arrays[index++] = \'\';    arrays[index++] = \'\';    arrays[index++] = \'\';    arrays[index++] = \'\';  }  for (let i = 0; i < nodes.length; i++) {      var j = 0;      var array = new Array();      array[j++] = nodes[i].tagName;      let child = nodes[i].firstChild;      let text = \'\';      while (child) {          if (child.nodeType === 3) {              text += child.textContent;          }          child = child.nextSibling;      }      array[j++] = text;      if (!nodes[i].src && nodes[i].style && nodes[i].style.cssText) {        array[j++] = \'style:\' + nodes[i].style.cssText;      } else {        array[j++] = nodes[i].src;      }      var rect = nodes[i].getBoundingClientRect();      array[j++] = Number.parseInt(rect.x);      array[j++] = Number.parseInt(rect.y);      array[j++] = Number.parseInt(rect.width);      array[j++] = Number.parseInt(rect.height);      arrays[index++] = array;  }  return arrays;})();";

    private static final String SELECT_LEAVES = "  let allNodes = document.querySelectorAll(\'*\');  let leafNodes = Array.from(allNodes).filter(node => {    if(node.firstElementChild) return false;    let rect = node.getBoundingClientRect();    return (rect.width > 0 && rect.height > 0);  });  let nodes = leafNodes;";

    private static final String SELECT_NODES = "  let nodes = document.querySelectorAll(\'*:not(script)\');";

    private static final String LEAF_TEXT = "  nodeMap[\'text\'] = node.textContent;";

    private static final String DIRECT_TEXT = "  let child = node.firstChild;  let text = \'\';  while (child) {    if (child.nodeType === 3) {      text += child.textContent;    }    child = child.nextSibling;  }  nodeMap[\'text\'] = text;";

    private static final String MINI_PREFIX = "javascript:(function() {";

    private static final String MINI_MIDDLE = "  var result = {};  result[\'href\'] = window.location.href;  result[\'innerText\'] = document.body.innerText;  result[\'wechat_route\'] = window.__route__;  result[\'wechat_appId\'] = window.__appId__;  result[\'wechat_query\'] = window.__queryString__;  try{    var appId = window.$HybridDataMineManager.envInfo.appId;    result[\'alipay_appId\'] = appId;  } catch(err) {}  try{    var bookUrl = window.HeytapReader.getBookUrl();    var bookImgUrl = window.HeytapReader.getBookImgUrl();    var novelInfo = window.HeytapReader.getNovelInfo();    var novelTitle = window.HeytapReader.getNovelTitle();    result[\'browser_bookUrl\'] = bookUrl;    result[\'browser_bookImgUrl\'] = bookImgUrl;    result[\'browser_novelInfo\'] = novelInfo;    result[\'browser_novelTitle\'] = novelTitle;  } catch(err) {}  var nodesArray = new Array();  for (let i = 0; i < nodes.length; i++) {      var node = nodes[i];      var nodeMap = {};      nodeMap[\'tagName\'] = node.tagName;";

    private static final String MINI_SUFFIX = "      if (!node.src && node.style && node.style.cssText) {        nodeMap[\'src\'] = \'style:\' + node.style.cssText;      } else {        nodeMap[\'src\'] = node.src;      }      var rect = node.getBoundingClientRect();      nodeMap[\'x\'] = Number.parseInt(rect.x);      nodeMap[\'y\'] = Number.parseInt(rect.y);      nodeMap[\'w\'] = Number.parseInt(rect.width);      nodeMap[\'h\'] = Number.parseInt(rect.height);      nodesArray[i] = nodeMap;  }  result[\'nodes\'] = nodesArray;  return result;})();";

    public static String pageId() { return PAGE_ID; }
    public static String documentHtml() { return DOCUMENT_HTML; }

    // Original getMiniProgramContentByRoute: JSONArray(Collection), then formatted
    // with routes / isAlipayMini / isUseLabelPathChange / useRootPortal in that order.
    // Null route list is the framework process() default (empty list).
    public static String contentByRoute(List<String> routes, boolean alipay,
                                        boolean useLabelPathChange, boolean rootPortal) {
        return CONTENT_BY_ROUTE.formatted(new JSONArray(routes == null ? List.of() : routes).toString(),
                alipay, useLabelPathChange, rootPortal);
    }

    public static String legacyNodes(boolean leaf) { return leaf ? LEGACY_LEAVES : LEGACY_NODES; }

    public static String miniProgramNodes(boolean leaf) {
        return MINI_PREFIX + (leaf ? SELECT_LEAVES : SELECT_NODES) + MINI_MIDDLE
                + (leaf ? LEAF_TEXT : DIRECT_TEXT) + MINI_SUFFIX;
    }
}
