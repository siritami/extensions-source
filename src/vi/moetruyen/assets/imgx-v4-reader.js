(async () => {
    const injectionFlag = "moetruyenExtensionReader";
    if (document.documentElement.dataset[injectionFlag]) return;
    document.documentElement.dataset[injectionFlag] = "1";

    const bridge = window.MoeTruyenBridge;
    const post = (value) => bridge.post(JSON.stringify(value));
    const decodeBase64Url = (value) => Uint8Array.from(
        atob(value.replace(/-/g, "+").replace(/_/g, "/")),
        (char) => char.charCodeAt(0),
    );
    const toBase64 = (bytes) => {
        const chunks = [];
        for (let offset = 0; offset < bytes.byteLength; offset += 0x8000) {
            chunks.push(String.fromCharCode(...bytes.subarray(offset, offset + 0x8000)));
        }
        return btoa(chunks.join(""));
    };
    const fnv1a = (bytes) => {
        let hash = 2166136261;
        for (const byte of bytes) {
            hash ^= byte;
            hash = Math.imul(hash, 16777619) >>> 0;
        }
        return hash || 2654435769;
    };
    const xorshift32 = (input) => {
        let value = input >>> 0;
        value ^= value << 13;
        value ^= value >>> 17;
        value ^= value << 5;
        return value >>> 0;
    };
    const deriveWrapKey = (input, length = 32) => {
        const output = new Uint8Array(length);
        let hash = fnv1a(new TextEncoder().encode(input));
        for (let index = 0; index < length; index++) {
            if (index % 4 === 0) {
                hash = xorshift32((hash + index + 2654435769) >>> 0);
            }
            output[index] = (hash >>> ((index % 4) * 8)) & 0xff;
        }
        return output;
    };
    const unwrapV4Key = (grant, storageKey) => {
        const wrapped = decodeBase64Url(grant.wrappedV4Key);
        if (wrapped.byteLength !== 32) throw new Error("IMGX v4 grant invalid");
        const grantString = [
            "IMGX-GRANT-WRAP-v1",
            grant.version,
            grant.algorithm,
            grant.imageId,
            grant.issuedAt,
            grant.expiresAt,
            grant.nonce,
            grant.keyNonce,
            grant.signature,
            String(storageKey || "").replace(/^\/+/, ""),
        ].map((value) => value == null ? "" : String(value)).join(".");
        const wrapKey = deriveWrapKey(grantString, wrapped.byteLength);
        for (let index = 0; index < wrapped.byteLength; index++) wrapped[index] ^= wrapKey[index];
        wrapKey.fill(0);
        return wrapped;
    };

    try {
        const root = document.querySelector("[data-reader-lazy-pages]");
        if (!root) throw new Error("IMGX reader metadata missing");

        const media = JSON.parse(decodeURIComponent(root.dataset.readerImgxMedia || "%5B%5D"))
            .filter((page) => page.storageKey !== "0.js" && !page.downloadUrl?.endsWith("/0.js"))
            .sort((left, right) => left.pageNumber - right.pageNumber);
        const pageIndexes = media
            .map((page) => Number(page.pageIndex))
            .filter(Number.isSafeInteger);
        if (!pageIndexes.length) throw new Error("IMGX page indexes missing");

        const runtime = globalThis.__IMGX_RUNTIME__?.take();
        if (!runtime) throw new Error("IMGX reader runtime unavailable");
        const pages = new Map();

        for (let offset = 0; offset < pageIndexes.length; offset += 10) {
            const indexes = pageIndexes.slice(offset, offset + 10);
            const batch = await runtime.requestPageAccess(indexes);
            batch.forEach((page) => pages.set(page.pageIndex, page));
        }

        const readerScriptUrl = new URL("/reader.js", location.href).href;
        const readerScript = await (await fetch(readerScriptUrl)).text();
        const v4Path = readerScript.match(/\.\.\/chunks\/(v4-[A-Za-z0-9_-]+\.js)/)?.[1];
        if (!v4Path) throw new Error("IMGX v4 decoder missing");
        const decoderUrl = new URL(`/chunks/${v4Path}`, location.href).href;
        const { decodeImgxV4 } = await import(decoderUrl);

        for (let order = 0; order < pageIndexes.length; order++) {
            const page = pages.get(pageIndexes[order]);
            if (!page?.downloadUrl || !page?.grant?.wrappedV4Key) {
                throw new Error(`IMGX grant missing for page ${order + 1}`);
            }
            const encrypted = new Uint8Array(await (await fetch(page.downloadUrl)).arrayBuffer());
            const key = unwrapV4Key(page.grant, page.storageKey);
            try {
                const webp = await decodeImgxV4(encrypted, key, {
                    imageId: page.grant.imageId,
                    storageKey: page.storageKey,
                });
                post({ type: "page", index: order, data: toBase64(webp) });
                webp.fill(0);
            } finally {
                encrypted.fill(0);
                key.fill(0);
            }
        }
        post({ type: "done", count: pageIndexes.length });
    } catch (error) {
        post({ type: "error", message: error?.message || String(error) });
    }
})();
