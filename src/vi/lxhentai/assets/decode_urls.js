// Poll script - retrieves token + image URLs captured by fetch_hook
// Runs in evaluateJs every second until both token and URLs are ready
(function() {
    try {
        var debugErrors = window.__lxDebugErrors || [];
        var debugErrorHandler = function(event) {
            var message = event && (event.message || event.error && event.error.message) || String(event || 'Unknown error');
            if (debugErrors.indexOf(message) < 0) debugErrors.push(message);
            window.__lxDebugErrors = debugErrors.slice(-10);
        };
        if (!window.__lxDebugHandlerInstalled) {
            window.addEventListener('error', debugErrorHandler, true);
            window.addEventListener('unhandledrejection', function(event) {
                debugErrorHandler(event.reason || event);
            });
            window.__lxDebugHandlerInstalled = true;
        }

        var getDebugState = function() {
            var containers = document.querySelectorAll('#image-container').length;
            var canvases = document.querySelectorAll('canvas').length;
            var nonBlankCanvases = 0;
            try {
                document.querySelectorAll('canvas').forEach(function(canvas) {
                    if (!canvas.width || !canvas.height) return;
                    var sample = canvas.getContext('2d').getImageData(
                        0,
                        0,
                        Math.min(canvas.width, 10),
                        Math.min(canvas.height, 10)
                    ).data;
                    if (Array.from(sample).some(function(value) { return value !== 0; })) {
                        nonBlankCanvases++;
                    }
                });
            } catch (error) {
                debugErrorHandler(error);
            }

            return {
                url: location.href,
                title: document.title,
                token: Boolean(window.__lxToken || document.querySelector('meta[name="action_token"]')),
                capturedUrls: window.__lxCapturedUrls && window.__lxCapturedUrls.length || 0,
                imageUrls: window.__lxImageUrls && window.__lxImageUrls.length || 0,
                readerScript: Array.from(document.scripts).some(function(script) {
                    return /\/js\/read\.js(?:\?|$)/i.test(script.src || '');
                }),
                readerContainers: containers,
                canvases: canvases,
                nonBlankCanvases: nonBlankCanvases,
                debugErrors: (window.__lxDebugErrors || []).slice(-10)
            };
        };

        var stateKey = '__lx_retry_' + location.pathname;
        var readRetryState = function() {
            try {
                return JSON.parse(localStorage.getItem(stateKey) || '{}');
            } catch(e) {
                return window.__lxRetryState || {};
            }
        };
        var writeRetryState = function(state) {
            window.__lxRetryState = state;
            try { localStorage.setItem(stateKey, JSON.stringify(state)); } catch(e) {}
        };
        var retryState = readRetryState();
        var visibleDialogs = Array.from(document.querySelectorAll('.swal2-container'))
            .filter(function(dialog) {
                return getComputedStyle(dialog).display !== 'none' &&
                    dialog.getAttribute('aria-hidden') !== 'true';
            });
        var turnstileCount = document.querySelectorAll('[id*="turnstile"], iframe[src*="challenges.cloudflare.com"]').length;

        var verificationActive = Boolean(window.__lxCaptchaShown || window.getTokenRequestInProgress);
        if (!window.__lxPollStarted) window.__lxPollStarted = Date.now();
        if (!window.__lxPollCount) window.__lxPollCount = 0;
        window.__lxPollCount++;

        var isImageUrl = function(value) {
            if (typeof value !== 'string' ||
                (value.indexOf('http') !== 0 && value.indexOf('//') !== 0)) return false;

            var lower = value.toLowerCase();
            if (!/\.(?:jpe?g|png|webp)(?:[?#]|$)/i.test(lower)) return false;
            if (lower.indexOf('avatar') >= 0 ||
                lower.indexOf('favicon') >= 0 ||
                lower.indexOf('/imgs/') >= 0 ||
                lower.indexOf('/images/default') >= 0 ||
                lower.indexOf('/images/avatars') >= 0 ||
                lower.indexOf('/images/pets') >= 0 ||
                lower.indexOf('/emoji/') >= 0 ||
                lower.indexOf('rank') >= 0 ||
                lower.indexOf('cover') >= 0 ||
                lower.indexOf('logo') >= 0 ||
                lower.indexOf('background') >= 0) return false;
            return /lxmanga\.(?:xyz|space|me)/i.test(lower);
        };

        if (!window.__lxCapturedUrls || window.__lxCapturedUrls.length === 0) {
            var kgzScripts = Array.from(document.querySelectorAll('script'))
                .filter(function(script) {
                    var text = script.textContent || '';
                    return !script.src && text.length < 50000 &&
                        (text.indexOf('KGZ1') >= 0 || (text.indexOf('concat(') >= 0 && text.indexOf('TextDecoder') >= 0));
                });

            if (kgzScripts.length > 0) {
                kgzScripts.forEach(function(script) {
                    try {
                        (0, eval)(script.textContent || '');
                    } catch(e) {}
                });
            }

            try {
                Object.keys(window).forEach(function(key) {
                    if (!/^_0x[a-f0-9]+$/i.test(key) || !Array.isArray(window[key])) return;

                    var captured = window[key].filter(function(url) {
                        return typeof url === 'string' && isImageUrl(url);
                    });
                    if (captured.length > 0) {
                        window.__lxCapturedUrls = (window.__lxCapturedUrls || []).concat(captured)
                            .filter(function(url, index, all) { return all.indexOf(url) === index; });
                    }
                });
            } catch(e) {}
        }
        var verificationStarted = window.__lxVerificationStarted || 0;
        if (verificationActive && !verificationStarted) {
            verificationStarted = Date.now();
            window.__lxVerificationStarted = verificationStarted;
        }
        var failedDialog = visibleDialogs.find(function(dialog) {
            return /xác minh thất bại|verification failed|quá lâu không phản hồi/i.test(dialog.textContent || '');
        });
        if (failedDialog) {
            var reloadButton = Array.from(failedDialog.querySelectorAll('button')).find(function(button) {
                return /tải lại|reload|retry/i.test(button.textContent || '') && !button.disabled;
            });
            var reloadCount = retryState.dialogReloads || 0;
            if (reloadButton && reloadCount < 2) {
                retryState.dialogReloads = reloadCount + 1;
                writeRetryState(retryState);
                reloadButton.click();
                return JSON.stringify({token: '', urls: [], reloading: true});
            }
        }

        if (!window._lxClicked) {
            var activeDialog = visibleDialogs
                .find(function(dialog) {
                    return dialog.querySelector('.swal2-popup');
                });
            var turnstileResponse = document.querySelector(
                'input[name="cf-turnstile-response"], input[id*="turnstile"][id$="_response"], input[id*="cf-chl-widget"][id$="_response"]'
            );
            var hasTurnstileResponse = turnstileResponse && turnstileResponse.value;
            var currentToken = window.__lxToken || (document.querySelector('meta[name="action_token"]') && document.querySelector('meta[name="action_token"]').getAttribute('content'));
            var canConfirm = hasTurnstileResponse || Boolean(currentToken);
            var btns = activeDialog ? activeDialog.querySelectorAll('.swal2-confirm') : [];
            for (var bi = 0; bi < btns.length; bi++) {
                var b = btns[bi];
                if (b && !b.disabled && canConfirm) {
                    var txt = (b.textContent || '').toLowerCase();
                    var isVerificationButton = txt.indexOf('ok') >= 0 ||
                        txt.indexOf('tiếp tục') >= 0 ||
                        txt.indexOf('continue') >= 0 ||
                        txt.indexOf('đọc') >= 0 ||
                        txt.indexOf('xem') >= 0 ||
                        (currentToken && btns.length === 1);
                    if (isVerificationButton) {
                        b.click();
                        window._lxClicked = true;
                        window.__lxClickedAt = Date.now();
                        break;
                    }
                }
            }
        }

        if (window._lxClicked && activeDialog && currentToken &&
            Date.now() - (window.__lxClickedAt || 0) > 2500) {
            window._lxClicked = false;
        }

        if (verificationActive && !hasTurnstileResponse && verificationStarted &&
            Date.now() - verificationStarted > 12000) {
            var retryCount = retryState.verificationReloads || 0;
            if (retryCount < 2) {
                retryState.verificationReloads = retryCount + 1;
                writeRetryState(retryState);
                window.__lxVerificationStarted = 0;
                location.reload();
                return JSON.stringify({token: '', urls: [], reloading: true});
            }
        }

        if (!window._lxDone) {
            window._lxDone = true;
            try {
                ['pointerdown', 'touchstart', 'wheel', 'keydown'].forEach(function(t) {
                    document.dispatchEvent(new Event(t, {bubbles: true}));
                });
                window.dispatchEvent(new Event('scroll'));
            } catch(e) {}
        }

        var urls = [];
        if (window.__lxCapturedUrls && window.__lxCapturedUrls.length > 0) {
            urls = window.__lxCapturedUrls;
        }
        if (window.__lxImageUrls && window.__lxImageUrls.length > 0) {
            urls = urls.concat(window.__lxImageUrls);
        }

        urls = urls.filter(function(url, index) {
            return url && urls.indexOf(url) === index && isImageUrl(url);
        }).sort(function(a, b) {
            var pageA = parseInt((a.match(/(?:page[_-]|\/)(\d+)(?:-|\.)/i) || [])[1] || '0', 10);
            var pageB = parseInt((b.match(/(?:page[_-]|\/)(\d+)(?:-|\.)/i) || [])[1] || '0', 10);
            return pageA - pageB;
        });

        var token = window.__lxToken || null;
        if (!token) {
            var tokenMeta = document.querySelector('meta[name="action_token"]');
            token = tokenMeta && tokenMeta.getAttribute('content') || null;
        }
        var currentCount = urls.length;
        if (currentCount !== window.__lxLastUrlCount) {
            window.__lxLastUrlCount = currentCount;
            window.__lxStableSince = Date.now();
        }
        var stableLongEnough = window.__lxStableSince && Date.now() - window.__lxStableSince >= 2500;
        var containerCount = document.querySelectorAll('#image-container').length;
        var hasAllCaptured = Boolean(window.__lxCapturedUrls && window.__lxCapturedUrls.length > 0);
        var containerCountSatisfied = containerCount > 0 && urls.length >= containerCount;
        var isReady = hasAllCaptured || containerCountSatisfied || stableLongEnough;

        if (!token && urls.length > 0 && stableLongEnough && !verificationActive &&
            visibleDialogs.length === 0 && !window.__lxManualTokenTried) {
            window.__lxManualTokenTried = true;

            var csrfMeta = document.querySelector('meta[name="csrf-token"]');
            var csrfToken = csrfMeta ? csrfMeta.getAttribute('content') : '';
            if (!csrfToken) {
                var xsrfMatch = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
                if (xsrfMatch) {
                    try { csrfToken = decodeURIComponent(xsrfMatch[1]); } catch(e) {}
                }
            }

            var fetchFn = window.fetch || window.__lxRealFetch;
            var headers = {
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': 'application/json'
            };
            if (csrfToken) {
                headers['X-CSRF-TOKEN'] = csrfToken;
            }

            try {
                fetchFn('/get_token', {
                    method: 'GET',
                    credentials: 'same-origin',
                    headers: headers
                }).then(function(resp) {
                    return resp.json();
                }).then(function(data) {
                    if (data && data.action_token) {
                        window.__lxToken = data.action_token;
                    } else if (data && (data.require_verification || data.is_bot)) {
                        window.__lxCaptchaShown = true;
                        window.getTokenRequestInProgress = true;
                        window.__lxGetTokenResponse = data;

                        var sitekeyMatch = document.documentElement.innerHTML.match(/sitekey['":\s]+([A-Za-z0-9_-]+)/i);
                        var sitekey = sitekeyMatch ? sitekeyMatch[1] : '0x4AAAAAABmIZvltdaZbP-9a';

                        var container = document.querySelector('#turnstile-container, [id*="cf-chl-widget"]');
                        if (!container) {
                            container = document.createElement('div');
                            container.id = '__lx-turnstile-container';
                            container.style.cssText = 'position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);z-index:999999;background:#fff;padding:20px;border-radius:8px;box-shadow:0 4px 20px rgba(0,0,0,0.3);text-align:center;font-family:sans-serif;min-width:300px;';
                            container.innerHTML = '<p style="margin:0 0 10px;font-size:14px;">Đang xác minh...</p><div id="__lx-turnstile-widget"></div>';
                            document.body.appendChild(container);
                        }

                        var widgetTarget = container.querySelector('#__lx-turnstile-widget') || container;
                        try {
                            if (typeof turnstile !== 'undefined' && turnstile.render) {
                                turnstile.render(widgetTarget, {
                                    sitekey: sitekey,
                                    callback: function(response) {
                                        window.__lxTurnstileResponse = response;
                                        var postFetch = window.__lxRealFetch || window.fetch;
                                        var postHeaders = { 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/json', 'Accept': 'application/json' };
                                        var postCsrf = document.querySelector('meta[name="csrf-token"]');
                                        if (postCsrf) postHeaders['X-CSRF-TOKEN'] = postCsrf.getAttribute('content');
                                        var postBody = JSON.stringify({ 'cf-turnstile-response': response });
                                        postFetch('/get_token', {
                                            method: 'POST',
                                            credentials: 'same-origin',
                                            headers: postHeaders,
                                            body: postBody
                                        }).then(function(resp) {
                                            return resp.json();
                                        }).then(function(postData) {
                                            if (postData && postData.action_token) {
                                                window.__lxToken = postData.action_token;
                                                window.getTokenRequestInProgress = false;
                                            } else {
                                                var formBody = 'cf-turnstile-response=' + encodeURIComponent(response);
                                                postFetch('/get_token', {
                                                    method: 'POST',
                                                    credentials: 'same-origin',
                                                    headers: { 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json' },
                                                    body: formBody
                                                }).then(function(r2) { return r2.json(); }).then(function(d2) {
                                                    if (d2 && d2.action_token) {
                                                        window.__lxToken = d2.action_token;
                                                        window.getTokenRequestInProgress = false;
                                                    }
                                                }).catch(function() {});
                                            }
                                        }).catch(function() {});
                                    }
                                });
                            } else {
                                var ts = document.createElement('script');
                                ts.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
                                ts.onload = function() {
                                    if (typeof turnstile !== 'undefined' && turnstile.render) {
                                        turnstile.render(widgetTarget, {
                                            sitekey: sitekey,
                                            callback: function(response) {
                                                window.__lxTurnstileResponse = response;
                                            }
                                        });
                                    }
                                };
                                document.head.appendChild(ts);
                            }
                        } catch(e3) {}
                    }
                }).catch(function() {});
            } catch(e) {}
        }

        if (!token && urls.length > 0 && !verificationActive && visibleDialogs.length === 0 &&
            window.__lxManualTokenTried && !window.__lxTokenReloadTried &&
            Date.now() - window.__lxPollStarted > 15000 &&
            location.hash.indexOf('_lxretry') < 0) {
            window.__lxTokenReloadTried = true;
            location.hash = (location.hash || '') + '_lxretry';
            location.reload();
            return JSON.stringify({token: '', urls: [], reloading: true});
        }

        if (token && urls.length > 0 && isReady) {
            window.__lxVerificationStarted = 0;
            window.__lxVerificationReloads = 0;
            try { localStorage.removeItem(stateKey); } catch(e) {}
            return JSON.stringify({token: token, urls: urls});
        }

        var debugState = getDebugState();
        if (debugState.debugErrors.length > 0 || (debugState.readerContainers > 0 && debugState.capturedUrls === 0)) {
            console.error('[LxHentai] decode_urls debug:', JSON.stringify(debugState));
        }
        return JSON.stringify({token: token || '', urls: urls || [], ready: false, debug: debugState});
    } catch(e) {
        var fatalDebug = {
            error: String(e),
            stack: e && e.stack || '',
            url: location.href,
            token: Boolean(window.__lxToken || document.querySelector('meta[name="action_token"]')),
            capturedUrls: window.__lxCapturedUrls && window.__lxCapturedUrls.length || 0,
            imageUrls: window.__lxImageUrls && window.__lxImageUrls.length || 0,
            readerContainers: document.querySelectorAll('#image-container').length,
            canvases: document.querySelectorAll('canvas').length,
            debugErrors: (window.__lxDebugErrors || []).slice(-10)
        };
        console.error('[LxHentai] decode_urls fatal:', JSON.stringify(fatalDebug));
        return JSON.stringify({token: '', urls: [], error: String(e), debug: fatalDebug});
    }
})();