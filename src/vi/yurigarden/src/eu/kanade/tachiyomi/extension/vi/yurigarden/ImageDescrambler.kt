package eu.kanade.tachiyomi.extension.vi.yurigarden

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import app.cash.quickjs.QuickJs
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import java.io.ByteArrayOutputStream

class ImageDescrambler : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val fragment = response.request.url.fragment ?: return response
        if (!fragment.contains("KEY=")) return response

        val key = fragment.substringAfter("KEY=")
        val bitmap = BitmapFactory.decodeStream(response.body.byteStream())
            ?: return response

        val descrambled = unscrambleImage(bitmap, key)

        val output = ByteArrayOutputStream()
        descrambled.compress(Bitmap.CompressFormat.JPEG, 90, output)

        val responseBody = output.toByteArray()
            .toResponseBody(MEDIA_TYPE)

        return response.newBuilder()
            .body(responseBody)
            .build()
    }

    private fun unscrambleImage(bitmap: Bitmap, key: String): Bitmap {
        val js = """
            (function(Q0,Q1,Q2){"use strict";const A=(()=>{const L=[49,50,51,52,53,54,55,56,57,65,66,67,68,69,70,71,72,74,75,76,77,78,80,81,82,83,84,85,86,87,88,89,90,97,98,99,100,101,102,103,104,105,106,107,109,110,111,112,113,114,115,116,117,118,119,120,121,122];return L.map(c=>String.fromCharCode(c)).join("")})();const F=(()=>{let f=[1];for(let i=1;i<=10;i++)f[i]=f[i-1]*i;return f})();const _I=(E,P)=>{let n=[...Array(P).keys()],r=[];for(let a=P-1;a>=0;a--){let i=F[a],s=Math.floor(E/i);E%=i;r.push(n.splice(s,1)[0])}return r};const _S=str=>{let t=0;for(let ch of str){let r=A.indexOf(ch);if(r<0)throw Error("Invalid Base58 char");t=t*58+r}return t};const _U=(enc,p)=>{if(!/^H[1-9A-HJ-NP-Za-km-z]+$/.test(enc))throw Error("Bad Base58");let t=enc.slice(1,-1),n=enc.slice(-1),r=_S(t);if(A[r%58]!==n)throw Error("Checksum mismatch");return _I(r,p)};const _P=(h,p)=>{let n=Math.floor(h/p),r=h%p,a=[];for(let i=0;i<p;i++)a.push(n+(i<r?1:0));return a};const _D=e=>{let t=Array(e.length);e.forEach((v,i)=>t[v]=i);return t};const _X=(K,H,P)=>{let e=_U(K.slice(4),P),s=_D(e),u=_P(H-4*(P-1),P),m=e.map(i=>u[i]),pts=[0];for(let i=0;i<m.length;i++)pts[i+1]=pts[i]+m[i];let f=[];for(let i=0;i<m.length;i++)f.push({y:i?pts[i]+4*i:0,h:m[i]});return s.map(i=>f[i])};return JSON.stringify(_X(Q0,Q1,Q2))})("$key",${bitmap.height},10);
        """.trimIndent()

        val result = QuickJs.create().use { it.evaluate(js) as String }
        val arr = JSONArray(result)

        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        var dy = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sy = o.getInt("y")
            val h = o.getInt("h")
            val src = Rect(0, sy, bitmap.width, sy + h)
            val dst = Rect(0, dy, bitmap.width, dy + h)
            canvas.drawBitmap(bitmap, src, dst, null)
            dy += h
        }
        return out
    }

    companion object {
        private val MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}
