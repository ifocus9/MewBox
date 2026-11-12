package com.mewbox.tv.ui.web.fm

/**
 * 注入到 WebView 的完整 `window.fongmi` 与 `window.fm` JS SDK 脚本（对齐 MewPlayer HomeWebController.getSdk）。
 */
object FmJsSdk {

    const val BRIDGE_NAME = "fongmiBridge"

    fun buildScript(viewport: WebHomeViewport): String {
        val viewportJson = viewport.toJson().toString()
        return """
            (function() {
                if (window.fm && window.fongmi && window.fongmi.__initialized) {
                    if (document && document.documentElement) {
                        document.documentElement.classList.add('fm-native');
                    }
                    try { window.dispatchEvent(new CustomEvent('fmsdk')); } catch (_) {}
                    return;
                }
                if (document && document.documentElement) {
                    document.documentElement.classList.add('fm-native');
                }
                window.fongmiClient = { mode: 'mobile', isLeanback: false };

                var CHUNK_SIZE = 262144;
                var seq = 0;
                var pending = Object.create(null);
                var eventListeners = Object.create(null);

                function readNativeResult(id) {
                    var bridge = window.$BRIDGE_NAME;
                    if (!bridge || typeof bridge.resultLength !== 'function') return null;
                    try {
                        var total = bridge.resultLength(id);
                        if (!(total > 0)) return null;
                        var parts = [];
                        var offset = 0;
                        while (offset < total) {
                            var chunk = bridge.resultChunk(id, offset, CHUNK_SIZE);
                            if (!chunk) break;
                            parts.push(chunk);
                            offset += chunk.length;
                        }
                        bridge.clearResult(id);
                        return JSON.parse(parts.join(''));
                    } catch (e) {
                        try { bridge.clearResult(id); } catch (_) {}
                        return null;
                    }
                }

                function hydrate(data) {
                    if (!data || !data.__fmResultId) return data;
                    var loaded = readNativeResult(data.__fmResultId);
                    return loaded !== null ? loaded : data;
                }

                function invoke(method, payload) {
                    return new Promise(function(resolve, reject) {
                        var bridge = window.$BRIDGE_NAME;
                        if (!bridge || typeof bridge.invoke !== 'function') {
                            reject(new Error('fongmiBridge is not available'));
                            return;
                        }
                        var id = 'fm_' + Date.now() + '_' + (++seq);
                        pending[id] = { resolve: resolve, reject: reject };
                        var payloadJson = '{}';
                        try {
                            payloadJson = payload === undefined || payload === null ? '{}' : JSON.stringify(payload);
                        } catch (e) {
                            delete pending[id];
                            reject(e);
                            return;
                        }
                        try {
                            bridge.invoke(String(method || ''), payloadJson, id);
                        } catch (err) {
                            delete pending[id];
                            reject(err);
                        }
                    });
                }

                function handleCallback(id, inlinePayload) {
                    var entry = pending[id];
                    if (!entry) return;
                    delete pending[id];
                    var envelope = inlinePayload;
                    if (!envelope || typeof envelope !== 'object') {
                        envelope = readNativeResult(id);
                    }
                    if (!envelope || typeof envelope !== 'object') {
                        entry.reject(new Error('Empty bridge response'));
                        return;
                    }
                    if (envelope.ok) {
                        entry.resolve(hydrate(envelope.data));
                    } else {
                        var err = new Error(envelope.error || 'Bridge call failed');
                        err.code = envelope.code || -1;
                        entry.reject(err);
                    }
                }

                window.fongmiNative = {
                    resolve: function(id, data) {
                        var entry = pending[id];
                        if (!entry) return;
                        delete pending[id];
                        entry.resolve(hydrate(data));
                    },
                    reject: function(id, error) {
                        var entry = pending[id];
                        if (!entry) return;
                        delete pending[id];
                        entry.reject(new Error(error || ''));
                    }
                };

                if (!window.__fmUrlHook && window.history) {
                    window.__fmUrlHook = true;
                    var emitUrlChange = function() {
                        try {
                            window.dispatchEvent(new CustomEvent('fmurlchange', { detail: { url: location.href } }));
                        } catch (_) {}
                    };
                    var rawPush = history.pushState;
                    var rawReplace = history.replaceState;
                    if (typeof rawPush === 'function') {
                        history.pushState = function() {
                            var r = rawPush.apply(this, arguments);
                            emitUrlChange();
                            return r;
                        };
                    }
                    if (typeof rawReplace === 'function') {
                        history.replaceState = function() {
                            var r = rawReplace.apply(this, arguments);
                            emitUrlChange();
                            return r;
                        };
                    }
                    window.addEventListener('popstate', emitUrlChange);
                }

                function normalizeUrlParams(urlOrOptions, extraOptions) {
                    if (typeof urlOrOptions === 'string') {
                        var merged = Object.assign({}, extraOptions || {});
                        merged.url = urlOrOptions;
                        return merged;
                    }
                    return Object.assign({}, urlOrOptions || {});
                }

                var fongmi = {
                    __initialized: true,
                    version: '1.0.0',
                    _viewport: $viewportJson,
                    __onCallback: handleCallback,
                    invoke: invoke,
                    call: invoke,

                    getViewport: function() {
                        return Object.assign({}, fongmi._viewport || {});
                    },

                    app: {
                        search: function(keyword, options) {
                            var params = typeof keyword === 'object' && keyword !== null
                                ? keyword
                                : Object.assign({}, options || {}, { keyword: String(keyword || '') });
                            return invoke('app.search', params);
                        },
                        openVod: function() {
                            return invoke('app.openVod', {});
                        },
                        openLive: function() {
                            return invoke('app.openLive', {});
                        },
                        openSetting: function() {
                            return invoke('app.openSetting', {});
                        },
                        history: function() {
                            return invoke('app.history', {});
                        },
                        toast: function(message, duration) {
                            var msgText = (message === undefined || message === null) ? '' : String(message);
                            var params = typeof message === 'object' && message !== null
                                ? message
                                : { message: msgText, duration: duration || 'short' };
                            return invoke('app.toast', params);
                        },
                        clipboardGet: function() {
                            return invoke('app.clipboardGet', {});
                        },
                        clipboardSet: function(text) {
                            var clipText = (text === undefined || text === null) ? '' : String(text);
                            var params = typeof text === 'object' && text !== null
                                ? text
                                : { text: clipText };
                            return invoke('app.clipboardSet', params);
                        },
                        openBrowser: function(url) {
                            var params = typeof url === 'object' && url !== null
                                ? url
                                : { url: String(url || '') };
                            return invoke('app.openBrowser', params);
                        },
                        openWebView: function(urlOrOptions, options) {
                            return invoke('app.openWebView', normalizeUrlParams(urlOrOptions, options));
                        }
                    },

                    net: {
                        request: function(urlOrOptions, options) {
                            return invoke('net.request', normalizeUrlParams(urlOrOptions, options));
                        },
                        get: function(url, headersOrOptions) {
                            var opts = {};
                            if (headersOrOptions && typeof headersOrOptions === 'object') {
                                if (headersOrOptions.headers || headersOrOptions.responseType || headersOrOptions.timeout || headersOrOptions.credentials) {
                                    opts = Object.assign({}, headersOrOptions);
                                } else {
                                    opts = { headers: headersOrOptions };
                                }
                            }
                            opts.url = url;
                            opts.method = 'GET';
                            return invoke('net.request', opts);
                        },
                        post: function(url, body, headersOrOptions) {
                            var opts = {};
                            if (headersOrOptions && typeof headersOrOptions === 'object') {
                                if (headersOrOptions.headers || headersOrOptions.responseType || headersOrOptions.timeout || headersOrOptions.credentials) {
                                    opts = Object.assign({}, headersOrOptions);
                                } else {
                                    opts = { headers: headersOrOptions };
                                }
                            }
                            opts.url = url;
                            opts.method = 'POST';
                            opts.body = body;
                            return invoke('net.request', opts);
                        },
                        cookieGet: function(url) {
                            var params = typeof url === 'object' && url !== null ? url : { url: String(url || location.href) };
                            return invoke('net.cookieGet', params);
                        },
                        cookieSet: function(url, cookie) {
                            var params = typeof url === 'object' && url !== null ? url : { url: String(url || location.href), cookie: String(cookie || '') };
                            return invoke('net.cookieSet', params);
                        },
                        cookieRemove: function(url, name) {
                            var params = typeof url === 'object' && url !== null ? url : { url: String(url || location.href), name: String(name || '') };
                            return invoke('net.cookieRemove', params);
                        },
                        cookieClear: function() {
                            return invoke('net.cookieClear', {});
                        },
                        cookieFlush: function() {
                            return invoke('net.cookieFlush', {});
                        },
                        resourceUrl: function(urlOrOptions, options) {
                            var params = normalizeUrlParams(urlOrOptions, options);
                            var bridge = window.$BRIDGE_NAME;
                            if (bridge && typeof bridge.resourceUrl === 'function') {
                                try {
                                    var directUrl = bridge.resourceUrl(String(params.url || ''), JSON.stringify(params));
                                    if (directUrl) return directUrl;
                                } catch (_) {}
                            }
                            if (bridge && typeof bridge.buildResourceUrlSync === 'function') {
                                try {
                                    var syncUrl = bridge.buildResourceUrlSync(JSON.stringify(params));
                                    if (syncUrl) return syncUrl;
                                } catch (_) {}
                            }
                            return invoke('net.resourceUrl', params);
                        }
                    },

                    player: {
                        playUrl: function(urlOrOptions, titleOrOptions, options) {
                            if (typeof urlOrOptions === 'object' && urlOrOptions !== null) {
                                return invoke('player.playUrl', urlOrOptions);
                            }
                            var payload = {};
                            if (typeof titleOrOptions === 'string') {
                                payload = Object.assign({}, options || {}, { url: String(urlOrOptions || ''), title: titleOrOptions });
                            } else if (titleOrOptions && typeof titleOrOptions === 'object') {
                                payload = Object.assign({}, titleOrOptions, { url: String(urlOrOptions || '') });
                            } else {
                                payload = Object.assign({}, options || {}, { url: String(urlOrOptions || '') });
                            }
                            return invoke('player.playUrl', payload);
                        },
                        playVod: function(siteKeyOrOptions, vodId, title, pic, options) {
                            if (typeof siteKeyOrOptions === 'object' && siteKeyOrOptions !== null) {
                                return invoke('player.playVod', siteKeyOrOptions);
                            }
                            var payload = Object.assign({}, options || {}, {
                                siteKey: String(siteKeyOrOptions || ''),
                                vodId: String(vodId || ''),
                                title: title ? String(title) : '',
                                pic: pic ? String(pic) : ''
                            });
                            return invoke('player.playVod', payload);
                        },
                        playVodInline: function(payload) {
                            var opts = Object.assign({}, payload || {});
                            var resolver = opts.resolveEpisode || opts.onResolveEpisode || opts.resolver ||
                                (opts.vod && (opts.vod.resolveEpisode || opts.vod.onResolveEpisode || opts.vod.resolver));
                            if (typeof resolver === 'function') {
                                window.__fmWebHomeInlineResolver = resolver;
                                opts.hasResolver = true;
                            }
                            return invoke('player.playVodInline', opts);
                        },
                        preloadArtwork: function(pic, wallPic) {
                            var params = typeof pic === 'object' && pic !== null
                                ? pic
                                : { pic: String(pic || ''), wallPic: String(wallPic || '') };
                            return invoke('player.preloadArtwork', params);
                        },
                        control: function(action) {
                            var params = typeof action === 'object' && action !== null
                                ? action
                                : { action: String(action || '') };
                            return invoke('player.control', params);
                        },
                        status: function() {
                            return invoke('player.status', {});
                        },
                        detail: function(options) {
                            return invoke('player.detail', options || {});
                        },
                        search: function(keywordOrOptions, options) {
                            return fongmi.app.search(keywordOrOptions, options);
                        },
                        stop: function() {
                            return invoke('player.stop', {});
                        }
                    },

                    pan: {
                        check: function(items) {
                            var payload = Array.isArray(items)
                                ? { items: items }
                                : (items && typeof items === 'object' && Array.isArray(items.items) ? items : { items: items ? [items] : [] });
                            return invoke('pan.check', payload);
                        },
                        play: function(payload) {
                            var params = typeof payload === 'string'
                                ? { url: payload }
                                : (payload || {});
                            return invoke('pan.play', params);
                        },
                        login: function(typeOrOptions, options) {
                            var params = typeof typeOrOptions === 'string'
                                ? Object.assign({ type: typeOrOptions }, options || {})
                                : (typeOrOptions || {});
                            return invoke('pan.login', params);
                        },
                        webview: function(urlOrOptions, options) {
                            return invoke('pan.webview', normalizeUrlParams(urlOrOptions, options));
                        },
                        cookie: function(typeOrUrl) {
                            var params = typeof typeOrUrl === 'string'
                                ? { type: typeOrUrl, url: typeOrUrl }
                                : (typeOrUrl || {});
                            return invoke('pan.cookie', params);
                        },
                        getCookie: function(typeOrUrl) {
                            var params = typeof typeOrUrl === 'string'
                                ? { type: typeOrUrl, url: typeOrUrl }
                                : (typeOrUrl || {});
                            return invoke('pan.getCookie', params);
                        },
                        setCookie: function(typeOrUrl, cookie) {
                            var params = typeof typeOrUrl === 'object' && typeOrUrl !== null
                                ? typeOrUrl
                                : { type: String(typeOrUrl || ''), url: String(typeOrUrl || ''), cookie: String(cookie || '') };
                            return invoke('pan.setCookie', params);
                        },
                        clearCookie: function(typeOrUrl) {
                            var params = typeof typeOrUrl === 'string'
                                ? { type: typeOrUrl, url: typeOrUrl }
                                : (typeOrUrl || {});
                            return invoke('pan.clearCookie', params);
                        }
                    },

                    cache: {
                        get: function(key, rule) {
                            var params = typeof key === 'object' && key !== null
                                ? key
                                : { key: String(key || ''), rule: rule !== undefined && rule !== null ? String(rule) : '' };
                            return invoke('cache.get', params);
                        },
                        set: function(key, value, rule) {
                            var params = typeof key === 'object' && key !== null
                                ? key
                                : {
                                    key: String(key || ''),
                                    value: value === undefined || value === null ? '' : (typeof value === 'string' ? value : JSON.stringify(value)),
                                    rule: typeof rule === 'string' ? rule : '',
                                    ttl: typeof rule === 'number' ? rule : 0
                                };
                            return invoke('cache.set', params);
                        },
                        del: function(key, rule) {
                            var params = typeof key === 'object' && key !== null
                                ? key
                                : { key: String(key || ''), rule: rule !== undefined && rule !== null ? String(rule) : '' };
                            return invoke('cache.del', params);
                        },
                        remove: function(key, rule) {
                            var params = typeof key === 'object' && key !== null
                                ? key
                                : { key: String(key || ''), rule: rule !== undefined && rule !== null ? String(rule) : '' };
                            return invoke('cache.remove', params);
                        },
                        clear: function(prefix) {
                            var params = typeof prefix === 'object' && prefix !== null
                                ? prefix
                                : { prefix: String(prefix || '') };
                            return invoke('cache.clear', params);
                        },
                        keys: function(prefix) {
                            var params = typeof prefix === 'object' && prefix !== null
                                ? prefix
                                : { prefix: String(prefix || '') };
                            return invoke('cache.keys', params);
                        }
                    },

                    device: {
                        info: function() {
                            return invoke('device.info', {});
                        }
                    },

                    site: {
                        info: function() {
                            return invoke('site.info', {});
                        }
                    },

                    config: {
                        info: function() {
                            return invoke('config.info', {});
                        }
                    },

                    ext: {
                        info: function() {
                            return invoke('ext.info', {});
                        },
                        log: function(message, data) {
                            var logText = (message === undefined || message === null) ? '' : String(message);
                            return invoke('ext.log', { message: logText, data: data });
                        },
                        toast: function(message) {
                            var toastText = (message === undefined || message === null) ? '' : String(message);
                            var params = typeof message === 'object' && message !== null
                                ? message
                                : { message: toastText };
                            return invoke('ext.toast', params);
                        },
                        capabilities: function() {
                            return invoke('ext.capabilities', {});
                        },
                        invoke: function(name, payload) {
                            return invoke('ext.invoke', { name: String(name || ''), payload: payload });
                        },
                        on: function(event, fn) {
                            if (!event || typeof fn !== 'function') return;
                            if (!eventListeners[event]) eventListeners[event] = [];
                            eventListeners[event].push(fn);
                        },
                        off: function(event, fn) {
                            if (!event || !eventListeners[event]) return;
                            if (!fn) {
                                delete eventListeners[event];
                                return;
                            }
                            eventListeners[event] = eventListeners[event].filter(function(item) { return item !== fn; });
                        },
                        emit: function(event, detail) {
                            var list = eventListeners[event];
                            if (Array.isArray(list)) {
                                list.slice().forEach(function(fn) {
                                    try { fn(detail); } catch (_) {}
                                });
                            }
                            return invoke('ext.emit', { event: String(event || ''), detail: detail });
                        }
                    },

                    ui: {
                        setToolbar: function(visible) {
                            return invoke('ui.setToolbar', { visible: visible !== false });
                        },
                        setChrome: function(options) {
                            return invoke('ui.setChrome', options || {});
                        },
                        restoreChrome: function() {
                            return invoke('ui.restoreChrome', {});
                        },
                        getChrome: function() {
                            return invoke('ui.getChrome', {});
                        },
                        getViewport: function() {
                            return invoke('ui.getViewport', {});
                        }
                    },

                    navigation: {
                        back: function() {
                            return invoke('navigation.back', {});
                        },
                        reload: function() {
                            return invoke('navigation.reload', {});
                        },
                        setBackHandler: function(fn) {
                            if (typeof fn === 'function') {
                                window.__fmBackHandler = fn;
                                return invoke('navigation.setBackHandler', { enabled: true });
                            } else {
                                window.__fmBackHandler = null;
                                return invoke('navigation.clearBackHandler', {});
                            }
                        },
                        clearBackHandler: function() {
                            window.__fmBackHandler = null;
                            return invoke('navigation.clearBackHandler', {});
                        }
                    }
                };

                var fmDevice = function() { return fongmi.device.info(); };
                fmDevice.info = fongmi.device.info;

                var fmSite = function() { return fongmi.site.info(); };
                fmSite.info = fongmi.site.info;

                var fmConfig = function() { return fongmi.config.info(); };
                fmConfig.info = fongmi.config.info;

                var fm = Object.assign({}, fongmi, {
                    req: fongmi.net.request,
                    get: fongmi.net.get,
                    post: fongmi.net.post,
                    res: fongmi.net.resourceUrl,
                    play: fongmi.player.playUrl,
                    playUrl: fongmi.player.playUrl,
                    vod: fongmi.player.playVod,
                    playVod: fongmi.player.playVod,
                    vodInline: fongmi.player.playVodInline,
                    playVodInline: fongmi.player.playVodInline,
                    preloadArtwork: fongmi.player.preloadArtwork,
                    ctrl: fongmi.player.control,
                    stat: fongmi.player.status,
                    detail: fongmi.player.detail,
                    search: fongmi.app.search,
                    openVod: fongmi.app.openVod,
                    openLive: fongmi.app.openLive,
                    openSetting: fongmi.app.openSetting,
                    history: fongmi.app.history,
                    stop: fongmi.player.stop,
                    toast: fongmi.app.toast,
                    openBrowser: fongmi.app.openBrowser,
                    openWebView: fongmi.app.openWebView,
                    pan: fongmi.pan,
                    check: fongmi.pan.check,
                    panLogin: fongmi.pan.login,
                    panWebView: fongmi.pan.webview,
                    panCookie: fongmi.pan.cookie,
                    cache: fongmi.cache,
                    cacheGet: fongmi.cache.get,
                    cacheSet: fongmi.cache.set,
                    cacheRemove: fongmi.cache.remove,
                    cacheClear: fongmi.cache.clear,
                    ext: fongmi.ext,
                    ui: fongmi.ui,
                    device: fmDevice,
                    site: fmSite,
                    config: fmConfig,
                    back: fongmi.navigation.back,
                    reload: fongmi.navigation.reload,
                    setChrome: fongmi.ui.setChrome,
                    getChrome: fongmi.ui.getChrome,
                    setBackHandler: fongmi.navigation.setBackHandler,
                    clearBackHandler: fongmi.navigation.clearBackHandler,
                    viewport: fongmi.getViewport
                });

                window.fongmi = fongmi;
                window.fm = fm;

                try {
                    var vp = fongmi._viewport;
                    var root = document.documentElement;
                    if (root && root.style && vp) {
                        root.style.setProperty('--fm-safe-top', (vp.safeTop || 0) + 'px');
                        root.style.setProperty('--fm-safe-right', (vp.safeRight || 0) + 'px');
                        root.style.setProperty('--fm-safe-bottom', (vp.safeBottom || 0) + 'px');
                        root.style.setProperty('--fm-safe-left', (vp.safeLeft || 0) + 'px');
                        root.style.setProperty('--fm-bottom-ui-inset', (vp.bottomUiInset || 0) + 'px');
                        root.style.setProperty('--fm-safe-bottom-total', (vp.safeBottomTotal || 0) + 'px');
                    }
                } catch (_) {}

                try {
                    window.dispatchEvent(new CustomEvent('fmsdk'));
                    window.dispatchEvent(new CustomEvent('fongmiready', { detail: fongmi }));
                    window.dispatchEvent(new CustomEvent('fmready', { detail: fm }));
                } catch (_) {}
            })();
        """.trimIndent()
    }
}
