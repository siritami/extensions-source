// Fetch hook - intercepts /get_token, image URLs, and unblocks Turnstile
// Injected via onPageStarted BEFORE any page scripts run
//
// Turnstile click path is adapted from nekori's document-start-solver.js:
// spoof visibility, wrap listeners so synthetic events report isTrusted,
// track shadow roots, and auto-click the challenge checkbox with a full
// mouse event sequence.
(function() {
    if (window.__lxCfSolverInstalled) {
        // Re-install only the page hooks; keep the solver singleton.
    } else {
        window.__lxCfSolverInstalled = true;
        installCfTurnstileSolver();
    }

    function installCfTurnstileSolver() {
        var props = {
            visibilityState: 'visible',
            webkitVisibilityState: 'visible',
            hidden: false,
            webkitHidden: false
        };
        Object.keys(props).forEach(function(property) {
            try {
                var desc = Object.getOwnPropertyDescriptor(Document.prototype, property);
                if (desc) {
                    Object.defineProperty(Document.prototype, property, {
                        get: function() { return props[property]; },
                        enumerable: desc.enumerable,
                        configurable: desc.configurable
                    });
                }
            } catch (e) {}
        });

        var shadowRoots = new WeakMap();
        var originalAttachShadow = Element.prototype.attachShadow;
        Object.defineProperty(Element.prototype, 'attachShadow', {
            value: function attachShadow(init) {
                var root = originalAttachShadow.call(this, init);
                shadowRoots.set(this, root);
                return root;
            },
            writable: true,
            enumerable: true,
            configurable: true
        });

        var wrappedListeners = new WeakMap();
        var originalAddEventListener = EventTarget.prototype.addEventListener;
        var originalRemoveEventListener = EventTarget.prototype.removeEventListener;
        var trustedEvents = new WeakSet();

        function proxyEvent(event) {
            if (!event || !trustedEvents.has(event)) {
                return event;
            }
            return new Proxy(event, {
                get: function(target, property) {
                    if (property === 'isTrusted') return true;
                    var result = Reflect.get(target, property, target);
                    return typeof result === 'function' ? result.bind(target) : result;
                }
            });
        }

        function wrapListener(listener) {
            if ((typeof listener !== 'function' && typeof listener !== 'object') || listener === null) {
                return listener;
            }
            var wrapped = wrappedListeners.get(listener);
            if (!wrapped) {
                wrapped = typeof listener === 'function'
                    ? function(event) { return listener.call(this, proxyEvent(event)); }
                    : function(event) { return listener.handleEvent(proxyEvent(event)); };
                wrappedListeners.set(listener, wrapped);
            }
            return wrapped;
        }

        Object.defineProperty(EventTarget.prototype, 'addEventListener', {
            value: function addEventListener(type, listener, options) {
                return originalAddEventListener.call(this, type, wrapListener(listener), options);
            },
            writable: true,
            enumerable: true,
            configurable: true
        });

        Object.defineProperty(EventTarget.prototype, 'removeEventListener', {
            value: function removeEventListener(type, listener, options) {
                return originalRemoveEventListener.call(this, type, wrappedListeners.get(listener) || listener, options);
            },
            writable: true,
            enumerable: true,
            configurable: true
        });

        function isTurnstileHost(node) {
            if (!node || node.nodeType !== 1) return false;
            var id = (node.id || '').toLowerCase();
            var className = typeof node.className === 'string' ? node.className.toLowerCase() : '';
            if (id.indexOf('turnstile') >= 0 || id.indexOf('cf-chl') >= 0) return true;
            if (className.indexOf('turnstile') >= 0 || className.indexOf('cf-turnstile') >= 0) return true;
            if (node.tagName === 'IFRAME') {
                var src = (node.getAttribute('src') || '').toLowerCase();
                if (src.indexOf('challenges.cloudflare.com') >= 0) return true;
            }
            return false;
        }

        function hasTurnstileAncestor(node) {
            var current = node;
            for (var depth = 0; current && depth < 8; depth++) {
                if (isTurnstileHost(current)) return true;
                current = current.parentElement || (current.parentNode && current.parentNode.host);
            }
            return false;
        }

        function findCheckbox(root) {
            try {
                var inputs = root.querySelectorAll ? root.querySelectorAll('input[type="checkbox"]') : [];
                for (var i = 0; i < inputs.length; i++) {
                    if (hasTurnstileAncestor(inputs[i])) return inputs[i];
                }
                var nodes = root.querySelectorAll ? root.querySelectorAll('*') : [];
                for (var j = 0; j < nodes.length; j++) {
                    var shadowRoot = shadowRoots.get(nodes[j]);
                    if (shadowRoot) {
                        var nested = findCheckbox(shadowRoot);
                        if (nested) return nested;
                    }
                }
            } catch (e) {}
            return null;
        }

        function findTurnstileTarget() {
            // Only click when a Turnstile widget is actually on the host page.
            var host = document.querySelector(
                'div.cf-turnstile, ' +
                '#turnstile-container, ' +
                '[id*="turnstile"], ' +
                '[class*="turnstile"], ' +
                'iframe[src*="challenges.cloudflare.com"]'
            );
            if (!host) return null;

            var checkbox = findCheckbox(document);
            if (checkbox) return checkbox;
            return host;
        }

        function clickElement(element) {
            return new Promise(function(resolve) {
                try {
                    var box = element.getBoundingClientRect();
                    var clientX = box.left + box.width / 2;
                    var clientY = box.top + box.height / 2;
                    if (!isFinite(clientX) || !isFinite(clientY) || box.width <= 0 || box.height <= 0) {
                        resolve();
                        return;
                    }

                    var types = ['mouseover', 'mouseenter', 'mousedown', 'mouseup', 'click', 'mouseout'];
                    var index = 0;
                    function next() {
                        if (index >= types.length) {
                            resolve();
                            return;
                        }
                        var type = types[index++];
                        var event = new MouseEvent(type, {
                            detail: type === 'mouseover' ? 0 : 1,
                            bubbles: true,
                            cancelable: true,
                            clientX: clientX,
                            clientY: clientY,
                            screenX: clientX,
                            screenY: clientY
                        });
                        trustedEvents.add(event);
                        element.dispatchEvent(event);
                        setTimeout(next, 15);
                    }
                    next();
                } catch (e) {
                    resolve();
                }
            });
        }

        window.__lxClick = clickElement;
        window.__lxFindTurnstileTarget = findTurnstileTarget;

        var clickInFlight = false;
        var lastClickAt = 0;
        setInterval(function() {
            if (clickInFlight || Date.now() - lastClickAt < 1000) return;
            // Skip once the page already produced a token + images.
            if (window.__lxToken && window.__lxImageUrls && window.__lxImageUrls.length > 0) return;
            var target = findTurnstileTarget();
            if (!target) return;
            clickInFlight = true;
            lastClickAt = Date.now();
            clickElement(target).then(function() {
                clickInFlight = false;
            }, function() {
                clickInFlight = false;
            });
        }, 100);
    }

    var defineEarlyCallback = function(name) {
        if (!/^[A-Za-z_$][\w$]{4,31}$/.test(name)) return;
        if (typeof window[name] === 'undefined') {
            window[name] = function() {};
        }
    };
    var scanEarlyCallbacks = function(root) {
        try {
            var elements = [];
            if (root && root.nodeType === 1 && (root.hasAttribute('onload') || root.hasAttribute('onerror'))) {
                elements.push(root);
            }
            if (root && root.querySelectorAll) {
                elements = elements.concat(Array.from(root.querySelectorAll('[onload], [onerror]')));
            }
            elements.forEach(function(element) {
                ['onload', 'onerror'].forEach(function(attribute) {
                    var handler = element.getAttribute(attribute) || '';
                    var matches = handler.matchAll(/\b([A-Za-z_$][\w$]*)\s*\(/g);
                    for (var match of matches) defineEarlyCallback(match[1]);
                });
            });
        } catch(e) {}
    };
    var installEarlyCallbackObserver = function() {
        if (!document.documentElement) {
            setTimeout(installEarlyCallbackObserver, 10);
            return;
        }
        scanEarlyCallbacks(document.documentElement);
        try {
            new MutationObserver(function(records) {
                records.forEach(function(record) {
                    record.addedNodes.forEach(scanEarlyCallbacks);
                });
            }).observe(document.documentElement, {childList: true, subtree: true});
        } catch(e) {}
    };
    installEarlyCallbackObserver();
    if (window.__lxChapterUrl && window.__lxChapterUrl !== location.href) {
        window.__lxToken = null;
        window.__lxImageUrls = [];
        window.__lxCapturedUrls = null;
        window.__lxLastUrlCount = 0;
        window.__lxStableSince = 0;
    }
    window.__lxChapterUrl = location.href;
    if (window.__lxHookInstalled) {
        window.__lxToken = null;
        window.__lxImageUrls = [];
        window.__lxCapturedUrls = null;
        window.__lxHookInstalled = false;
    }
    window.__lxHookInstalled = true;
    window.__lxHookStartTime = Date.now();
    window.__lxToken = null;
    window.__lxImageUrls = [];
    window.__lxCapturedUrls = null;

    var _realFetch = window.fetch;
    window.__lxRealFetch = _realFetch;

    try {
        if (!Document.prototype.hasFocus.__lxWrapped) {
            var _realHasFocus = Document.prototype.hasFocus;
            var _lxHasFocus = function() { return true; };
            _lxHasFocus.__lxWrapped = true;
            _lxHasFocus.toString = function() { return _realHasFocus.toString(); };
            Document.prototype.hasFocus = _lxHasFocus;
        }
    } catch(e) {}

    var _origSlice = Array.prototype.slice;
    Array.prototype.slice = function() {
        try {
            if (!window.__lxCapturedUrls && this.length > 0) {
                var urlValues = [];
                for (var i = 0; i < this.length; i++) {
                    if (typeof this[i] === 'string' && isImageUrl(this[i])) {
                        urlValues.push(this[i]);
                    }
                }
                if (urlValues.length > 0) {
                    window.__lxCapturedUrls = (window.__lxCapturedUrls || []).concat(urlValues)
                        .filter(function(url, index, all) { return all.indexOf(url) === index; });
                }
            }
        } catch(e) {}
        return _origSlice.apply(this, arguments);
    };
    try { Array.prototype.slice.toString = function() { return _origSlice.toString(); }; } catch(e) {}

    var _propTrapInterval = setInterval(function() {
        if (window.__lxPropTrapped) { clearInterval(_propTrapInterval); return; }
        if (window.__lxHookInstalled && Date.now() - (window.__lxHookStartTime || Date.now()) > 10000) {
            clearInterval(_propTrapInterval);
            return;
        }
        try {
            var scripts = document.querySelectorAll('script');
            for (var i = 0; i < scripts.length; i++) {
                var text = scripts[i].textContent || '';
                var match = text.match(/window\s*\[\s*[\'\"](_0x[a-f0-9]{6,})[\'\"]\s*\]/);
                if (match) {
                    window.__lxPropTrapped = true;
                    var _captured = null;
                    try {
                        Object.defineProperty(window, match[1], {
                            configurable: true, enumerable: true,
                            get: function() { return _captured; },
                            set: function(val) {
                                _captured = val;
                                if (Array.isArray(val) && val.length > 0 && !window.__lxCapturedUrls) {
                                    var urls = val.filter(function(item) { return typeof item === 'string' && isImageUrl(item); });
                                    if (urls.length > 0) {
                                        window.__lxCapturedUrls = (window.__lxCapturedUrls || []).concat(urls)
                                            .filter(function(url, index, all) { return all.indexOf(url) === index; });
                                    }
                                }
                            }
                        });
                    } catch(e) {}
                    clearInterval(_propTrapInterval);
                    break;
                }
            }
        } catch(e) {}
    }, 50);

    var isImageUrl = function(value) {
        if (typeof value !== 'string' ||
            (value.indexOf('http') !== 0 && value.indexOf('//') !== 0)) return false;

        var lower = value.toLowerCase();
        var isNormalPage = /\/page[_-]\d+\.(?:jpg|jpeg|png|webp)(?:[?#]|$)/i.test(value);
        var isPuzzlePage = /^https?:\/\/s\d+\.lxmanga\.xyz\/.*\/\d+-[a-f0-9]+\.(?:jpg|jpeg|png|webp)(?:[?#]|$)/i.test(value);
        return (isNormalPage || isPuzzlePage) &&
            lower.indexOf('favicon') < 0 &&
            lower.indexOf('/imgs/') < 0 &&
            lower.indexOf('/images/') < 0 &&
            lower.indexOf('cover') < 0 &&
            lower.indexOf('logo') < 0 &&
            lower.indexOf('background') < 0 &&
            lower.indexOf('avatar') < 0;
    };

    var _wrapFetch = function(fetchImpl) {
        var wrapped = function(input, init) {
            var url = (typeof input === 'string') ? input : (input && input.url) || '';
            var token = null;

            if (input && input.headers) {
                try { token = input.headers.get('Token') || input.headers.get('token'); } catch(e) {}
            }
            if (init && init.headers) {
                var headers = init.headers;
                try {
                    token = new Headers(headers).get('Token') || new Headers(headers).get('token');
                } catch(e) {}
            }

            if (token && isImageUrl(url)) {
                window.__lxToken = token;
                if (window.__lxImageUrls.indexOf(url) < 0) {
                    window.__lxImageUrls.push(url);
                }
            }

            var result = fetchImpl.apply(this, arguments);
            if (url.indexOf('/get_token') < 0) return result;

            return result.then(function(resp) {
                var clone = resp.clone();
                clone.json().then(function(data) {
                    if (data && data.action_token) {
                        window.__lxToken = data.action_token;
                    }
                }).catch(function() {});
                return resp;
            }).catch(function(error) { throw error; });
        };

        try { wrapped.toString = function() { return 'function fetch() { [native code] }'; }; } catch(e) {}
        return wrapped;
    };

    window.fetch = _wrapFetch(_realFetch);
    window.__lxWrappedFetch = window.fetch;

    try {
        var _xhrOpen = XMLHttpRequest.prototype.open;
        var _xhrSend = XMLHttpRequest.prototype.send;
        var _xhrSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
        XMLHttpRequest.prototype.open = function(method, requestUrl) {
            this.__lxUrl = requestUrl || '';
            try { this.__lxUrl = new URL(this.__lxUrl, location.href).href; } catch(e) {}
            return _xhrOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
            if (String(name).toLowerCase() === 'token' && value) {
                window.__lxToken = String(value);
                if (this.__lxUrl && window.__lxImageUrls.indexOf(this.__lxUrl) < 0) {
                    window.__lxImageUrls.push(this.__lxUrl);
                }
            }
            return _xhrSetRequestHeader.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function() {
            var xhr = this;
            if (this.__lxUrl && this.__lxUrl.indexOf('/get_token') >= 0 && !this.__lxTokenHooked) {
                this.__lxTokenHooked = true;
                try {
                    this.addEventListener('load', function() {
                        try {
                            var data = JSON.parse(xhr.responseText || '{}');
                            if (data && data.action_token) window.__lxToken = data.action_token;
                        } catch(e) {}
                    });
                } catch(e) {}
            }
            return _xhrSend.apply(this, arguments);
        };
        XMLHttpRequest.prototype.open.toString = function() { return _xhrOpen.toString(); };
        XMLHttpRequest.prototype.setRequestHeader.toString = function() { return _xhrSetRequestHeader.toString(); };
        XMLHttpRequest.prototype.send.toString = function() { return _xhrSend.toString(); };

    } catch(e) {}

    var _replaceInterval = setInterval(function() {
        try {
            if (window.fetch === window.__lxWrappedFetch) return;
            window.fetch = _wrapFetch(window.fetch);
            window.__lxWrappedFetch = window.fetch;
        } catch(e) {}
    }, 100);

    try {
        localStorage.removeItem('turnstile_blocked');
        localStorage.removeItem('turnstile_blocked_time');
    } catch(e) {}

    var collectVisibleImages = function() {
        try {
            document.querySelectorAll('img').forEach(function(image) {
                [image.currentSrc, image.src, image.getAttribute('data-src'), image.getAttribute('data-lazy-src')]
                    .filter(isImageUrl)
                    .forEach(function(url) {
                        if (window.__lxImageUrls.indexOf(url) < 0) window.__lxImageUrls.push(url);
                    });
            });
            if (window.performance && performance.getEntriesByType) {
                performance.getEntriesByType('resource').forEach(function(entry) {
                    if (isImageUrl(entry.name) && window.__lxImageUrls.indexOf(entry.name) < 0) {
                        window.__lxImageUrls.push(entry.name);
                    }
                });
            }
        } catch(e) {}
    };
    setInterval(collectVisibleImages, 500);
})();
