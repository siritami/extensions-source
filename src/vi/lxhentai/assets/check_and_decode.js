(function(){
    try {
        // 1. Hook fetch to capture token from /get_token POST response
        if (!window._lxHooked) {
            window._lxHooked = true;
            window._lxToken = null;
            var origFetch = window.fetch.bind(window);
            window.fetch = function(input, init) {
                var url = (typeof input === 'string') ? input : (input && input.url) || '';
                if (url.indexOf('get_token') >= 0) {
                    return origFetch(input, init).then(function(resp) {
                        var clone = resp.clone();
                        clone.json().then(function(data) {
                            window._lxToken = data.action_token || null;
                        }).catch(function(){});
                        return resp;
                    });
                }
                return origFetch(input, init);
            };
        }

        // 2. Click Turnstile confirm button
        if (!window._lxClicked) {
            var b = document.querySelector('.swal2-confirm');
            if (b && !b.disabled && b.textContent.indexOf('tiếp tục') >= 0) {
                b.click();
                window._lxClicked = true;
            }
        }

        // 3. Dispatch events to bypass automation gate (v25 hard mode)
        if (!window._lxDone) {
            window._lxDone = true;
            try {
                ['pointerdown', 'touchstart', 'wheel', 'keydown'].forEach(function(t) {
                    document.dispatchEvent(new Event(t, {bubbles: true}));
                });
                window.dispatchEvent(new Event('scroll'));
            } catch(e) {}
        }

        // 4. Decode URLs from first KGZ1 script (cached)
        if (!window._lxCachedUrls) {
            window._lxCachedUrls = [];
            try {
                var scripts = document.querySelectorAll('script:not([src])');
                for (var i = 0; i < scripts.length; i++) {
                    var txt = scripts[i].textContent;
                    if (txt.indexOf('["KGZ1') >= 0 || txt.indexOf('=\["KGZ1') >= 0) {
                        var arrayMatch = txt.match(/=\[((?:"[A-Za-z0-9+/=]{20,}",?\s*)+)\]/);
                        if (!arrayMatch) continue;
                        var parts = arrayMatch[1].match(/"([^"]+)"/g);
                        if (!parts) continue;
                        var joined = parts.map(function(s){return s.replace(/"/g,'');}).join('');
                        var raw = atob(joined);
                        var layer1;
                        try { layer1 = decodeURIComponent(escape(raw)); } catch(e) { layer1 = raw; }

                        var key2Match = layer1.match(/var _\w+='([0-9a-f]{20,})'/);
                        if (!key2Match) continue;
                        var key2 = key2Match[1];

                        var arrRe = /var _\w+=\[((?:-?\d+,?)*)\]/g;
                        var combined = [];
                        var m;
                        while ((m = arrRe.exec(layer1)) !== null) {
                            var nums = m[1].split(',').filter(function(s){return s.length>0;}).map(Number);
                            combined = combined.concat(nums);
                        }
                        if (combined.length === 0) continue;

                        var decoded = '';
                        for (var j = 0; j < combined.length; j++) {
                            decoded += String.fromCharCode((combined[j] ^ key2.charCodeAt(j % key2.length)) & 0xFF);
                        }

                        var key3Match = decoded.match(/var _\w+="([0-9a-f]{20,})"/);
                        if (!key3Match) continue;
                        var key3 = key3Match[1];

                        var jsonB64Match = decoded.match(/var _\w+="([A-Za-z0-9+/=]{50,})"/);
                        if (!jsonB64Match) continue;

                        var jsonArr = JSON.parse(atob(jsonB64Match[1]));
                        for (var k = 0; k < jsonArr.length; k++) {
                            var item;
                            try { item = decodeURIComponent(escape(atob(jsonArr[k]))); }
                            catch(e) { item = atob(jsonArr[k]); }
                            var url = '';
                            for (var p = 0; p < item.length; p++) {
                                url += String.fromCharCode((item.charCodeAt(p) ^ key3.charCodeAt(p % key3.length)) & 0xFF);
                            }
                            if (url.indexOf('http') === 0 && window._lxCachedUrls.indexOf(url) < 0) {
                                window._lxCachedUrls.push(url);
                            }
                        }
                        break;
                    }
                }
            } catch(e) {}
        }

        // 5. Return when both token and URLs are ready
        var t = window._lxToken;
        var urls = window._lxCachedUrls;
        if (t && urls && urls.length > 0) {
            return JSON.stringify({token: t, urls: urls});
        }
        return JSON.stringify({token: t || '', urls: urls || []});
    } catch(e) { return JSON.stringify({token:'',urls:[]}); }
})()
