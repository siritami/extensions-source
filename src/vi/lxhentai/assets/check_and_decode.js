(function(){
    try {
        var b = document.querySelector('.swal2-confirm');
        if (b && !b.disabled && b.textContent.indexOf('tiếp tục') >= 0) b.click();
        var t = window.actionToken;
        if (!t || typeof t !== 'string' || t.length === 0) return JSON.stringify({token:'',urls:[]});
        var scripts = document.querySelectorAll('script:not([src])');
        var target = null;
        for (var i = 0; i < scripts.length; i++) {
            var txt = scripts[i].textContent;
            if (txt.indexOf('["KGZ1') >= 0 || txt.indexOf('=\["KGZ1') >= 0) {
                target = txt;
                break;
            }
        }
        if (!target) return JSON.stringify({token:t,urls:[]});
        var b64Match = target.match(/=\[((?:"[A-Za-z0-9+/=]{20,}",?\s*)+)\]/);
        if (!b64Match) return JSON.stringify({token:t,urls:[]});
        var parts = b64Match[1].match(/"([^"]+)"/g);
        if (!parts) return JSON.stringify({token:t,urls:[]});
        var joined = parts.map(function(s){return s.replace(/"/g,'');}).join('');
        var raw = atob(joined);
        var layer1;
        try { layer1 = decodeURIComponent(escape(raw)); } catch(e2) { layer1 = raw; }
        var key2Match = layer1.match(/var _\w+='([0-9a-f]{20,})'/);
        if (!key2Match) return JSON.stringify({token:t,urls:[]});
        var key2 = key2Match[1];
        var arrRe = /var _\w+=\[((?:-?\d+,?)*)\]/g;
        var combined = [];
        var m;
        while ((m = arrRe.exec(layer1)) !== null) {
            var nums = m[1].split(',').filter(function(s){return s.length>0;}).map(Number);
            combined = combined.concat(nums);
        }
        if (combined.length === 0) return JSON.stringify({token:t,urls:[]});
        var decoded = '';
        for (var i = 0; i < combined.length; i++) {
            decoded += String.fromCharCode((combined[i] ^ key2.charCodeAt(i % key2.length)) & 0xFF);
        }
        var key3Match = decoded.match(/var _\w+="([0-9a-f]{20,})"/);
        if (!key3Match) return JSON.stringify({token:t,urls:[]});
        var key3 = key3Match[1];
        var jsonB64Match = decoded.match(/var _\w+="([A-Za-z0-9+/=]{50,})"/);
        if (!jsonB64Match) return JSON.stringify({token:t,urls:[]});
        var jsonArr = JSON.parse(atob(jsonB64Match[1]));
        var urls = [];
        for (var j = 0; j < jsonArr.length; j++) {
            var item;
            try { item = decodeURIComponent(escape(atob(jsonArr[j]))); }
            catch(e3) { item = atob(jsonArr[j]); }
            var url = '';
            for (var k = 0; k < item.length; k++) {
                url += String.fromCharCode((item.charCodeAt(k) ^ key3.charCodeAt(k % key3.length)) & 0xFF);
            }
            if (url.indexOf('http') === 0 && urls.indexOf(url) < 0) urls.push(url);
        }
        return JSON.stringify({token:t,urls:urls});
    } catch(e) { return JSON.stringify({token:'',urls:[]}); }
})()
