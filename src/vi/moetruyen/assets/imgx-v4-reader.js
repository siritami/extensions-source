(async () => {
    const bridge = window.__IMGX_BRIDGE__;
    const post = (value) => bridge.post(JSON.stringify(value));
    const waitFor = async (predicate, timeout = 15000) => {
        const deadline = performance.now() + timeout;
        while (performance.now() < deadline) {
            const value = predicate();
            if (value) return value;
            await new Promise((resolve) => setTimeout(resolve, 0));
        }
        return null;
    };
    const documentRoot = await waitFor(() => document.documentElement);
    if (!documentRoot) {
        post({ type: "error", message: "IMGX document root unavailable" });
        return;
    }
    if (documentRoot.dataset.moetruyenExtensionReader) return;
    documentRoot.dataset.moetruyenExtensionReader = "1";

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
    const hexPreview = (bytes, length = 16) => [...bytes.subarray(0, length)]
        .map((byte) => byte.toString(16).padStart(2, "0"))
        .join("");
    const readUint32 = (bytes, offset) => new DataView(
        bytes.buffer,
        bytes.byteOffset,
        bytes.byteLength,
    ).getUint32(offset);
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
    const unwrapGrantKey = (grant, storageKey, fieldName) => {
        const wrapped = decodeBase64Url(grant[fieldName]);
        if (wrapped.byteLength !== 32) throw new Error(`IMGX ${fieldName} invalid`);
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
        let hash = fnv1a(new TextEncoder().encode(grantString));
        for (let index = 0; index < wrapped.byteLength; index++) {
            if (index % 4 === 0) {
                hash = xorshift32((hash + index + 2654435769) >>> 0);
            }
            wrapped[index] ^= (hash >>> ((index % 4) * 8)) & 0xff;
        }
        return wrapped;
    };
    const decodeImgxV3 = async (encrypted, grant, storageKey) => {
        if (encrypted.byteLength < 41 || encrypted[4] !== 3) {
            throw new Error("IMGX v3 payload invalid");
        }
        const width = readUint32(encrypted, 5);
        const height = readUint32(encrypted, 9);
        if (!width || !height) throw new Error("IMGX v3 dimensions invalid");
        const key = unwrapGrantKey(grant, storageKey, "wrappedContentKey");
        const iv = encrypted.subarray(13, 25);
        const ciphertext = encrypted.subarray(25);
        const aad = new TextEncoder().encode([
            "IMGX-v3",
            String(grant.imageId || "").trim(),
            String(storageKey || "").replace(/^\/+/, ""),
            width,
            height,
        ].join("."));
        try {
            const cryptoKey = await crypto.subtle.importKey("raw", key, "AES-GCM", false, ["decrypt"]);
            return new Uint8Array(await crypto.subtle.decrypt(
                { name: "AES-GCM", iv, additionalData: aad, tagLength: 128 },
                cryptoKey,
                ciphertext,
            ));
        } finally {
            key.fill(0);
        }
    };
    const extractImx4FromWebp = (bytes) => {
        if (bytes.byteLength < 12 || hexPreview(bytes, 4) !== "52494646" || hexPreview(bytes.subarray(8), 4) !== "57454250") {
            return null;
        }
        const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        if (view.getUint32(4, true) + 8 !== bytes.byteLength) {
            throw new Error("IMGX WebP container invalid");
        }
        let payload = null;
        let chunks = 0;
        for (let offset = 12; offset < bytes.byteLength;) {
            if (++chunks > 1024 || offset + 8 > bytes.byteLength) {
                throw new Error("IMGX WebP chunks invalid");
            }
            const size = view.getUint32(offset + 4, true);
            const dataEnd = offset + 8 + size;
            const paddedEnd = dataEnd + (size & 1);
            if (paddedEnd > bytes.byteLength) {
                throw new Error("IMGX WebP chunk truncated");
            }
            if (hexPreview(bytes.subarray(offset, offset + 4)) === "494d5834") {
                if (
                    payload ||
                    size <= 78 ||
                    hexPreview(bytes.subarray(offset + 8, offset + 12)) !== "494d4758" ||
                    bytes[offset + 12] !== 4
                ) {
                    throw new Error("IMGX protected chunk invalid");
                }
                payload = bytes.slice(offset + 8, dataEnd);
            }
            offset = paddedEnd;
        }
        return payload;
    };
    const unwrapDecodeKey = (grant, storageKey) => {
        if (grant.wrappedDecodeKey) {
            return unwrapGrantKey(grant, storageKey, "wrappedDecodeKey");
        }
        if (grant.decodeKey) {
            return decodeBase64Url(grant.decodeKey);
        }
        throw new Error("IMGX decode key missing");
    };
    const seedFromKey = (key) => {
        const seed = readUint32(key, 0) >>> 0;
        return seed === 0 ? 2654435769 : seed;
    };
    const unshuffleBytes = (data, key) => {
        const indices = new Uint32Array(data.length);
        let seed = seedFromKey(key);
        for (let i = data.length - 1; i >= 1; i--) {
            seed = xorshift32(seed);
            indices[i] = seed % (i + 1);
        }
        for (let i = 1; i < data.length; i++) {
            const j = indices[i];
            if (i !== j) {
                const tmp = data[i];
                data[i] = data[j];
                data[j] = tmp;
            }
        }
    };
    const xorDecryptBytes = (data, key) => {
        for (let i = 0; i < data.length; i++) {
            data[i] ^= key[i % key.length];
        }
    };
    const decodeImgxV2 = (encrypted, grant, storageKey) => {
        if (encrypted.byteLength <= 13 || encrypted[4] !== 2) {
            throw new Error("IMGX v2 payload invalid");
        }
        const payload = encrypted.slice(13);
        const key = unwrapDecodeKey(grant, storageKey);
        try {
            unshuffleBytes(payload, key);
            xorDecryptBytes(payload, key);
            return payload;
        } finally {
            key.fill(0);
        }
    };
    const decodeProtectedPage = async (encrypted, grant, storageKey, decodeImgxV4) => {
        const context = { imageId: grant.imageId, storageKey };
        const version = encrypted[4];
        if (version === 2) {
            return decodeImgxV2(encrypted, grant, storageKey);
        }
        if (version === 4) {
            if (typeof decodeImgxV4 !== "function") {
                throw new Error("IMGX v4 decoder unavailable");
            }
            const key = unwrapGrantKey(grant, storageKey, "wrappedV4Key");
            try {
                return await decodeImgxV4(encrypted, key, context);
            } finally {
                key.fill(0);
            }
        }
        if (version !== 3) {
            throw new Error(`IMGX version unsupported: ${version}`);
        }
        const intermediate = await decodeImgxV3(encrypted, grant, storageKey);
        const imx4 = extractImx4FromWebp(intermediate);
        if (!imx4) {
            return intermediate;
        }
        try {
            const key = unwrapGrantKey(grant, storageKey, "wrappedV4Key");
            try {
                return await decodeImgxV4(imx4, key, context);
            } finally {
                key.fill(0);
            }
        } finally {
            intermediate.fill(0);
        }
    };

    try {
        const root = await waitFor(() => document.querySelector("[data-reader-lazy-pages]"));
        if (!root) throw new Error("IMGX reader metadata missing");

        const injectedMedia = __IMGX_MEDIA_JSON__;
        const attributeMedia = (() => {
            const raw = root.dataset.readerImgxMedia;
            if (!raw) return [];
            try {
                return JSON.parse(decodeURIComponent(raw));
            } catch (_) {
                try {
                    return JSON.parse(raw);
                } catch (_) {
                    return [];
                }
            }
        })();
        const media = (Array.isArray(injectedMedia) && injectedMedia.length ? injectedMedia : attributeMedia)
            .filter((page) => {
                const storageKey = String(page.storageKey || "");
                const downloadUrl = String(page.downloadUrl || "");
                return Number.isSafeInteger(Number(page.pageIndex)) &&
                    storageKey.startsWith("chapters/") &&
                    !storageKey.endsWith("/0.js") &&
                    !downloadUrl.endsWith("/0.js");
            })
            .sort((left, right) => Number(left.pageIndex) - Number(right.pageIndex));
        const pageIndexes = media
            .map((page) => Number(page.pageIndex))
            .filter(Number.isSafeInteger);
        if (!pageIndexes.length) throw new Error("IMGX page indexes missing");

        const injectedInitial = __IMGX_INITIAL_INDEXES_JSON__;
        const attributeInitial = (() => {
            const raw = root.dataset.readerImgxInitialPages;
            if (!raw) return [];
            try {
                return JSON.parse(decodeURIComponent(raw));
            } catch (_) {
                try {
                    return JSON.parse(raw);
                } catch (_) {
                    return [];
                }
            }
        })();
        const initialPages = Array.isArray(injectedInitial) && injectedInitial.length
            ? injectedInitial.map((value) => ({ pageIndex: value }))
            : attributeInitial;
        const initialIndexes = initialPages
            .map((page) => Number(typeof page === "number" ? page : page.pageIndex))
            .filter(Number.isSafeInteger);

        const runtimeHost = await waitFor(() => globalThis.__IMGX_RUNTIME__);
        const runtime = runtimeHost
            ? (typeof runtimeHost.take === "function" ? runtimeHost.take() : runtimeHost)
            : null;
        const pages = new Map();

        const openGrants = async (indexes) => {
            if (!indexes.length) return [];
            if (typeof runtime?.requestPageAccess === "function") {
                return runtime.requestPageAccess(indexes);
            }
            const accessUrl = root.getAttribute("data-reader-imgx-access-url");
            if (!accessUrl) {
                throw new Error("IMGX reader runtime locked: requestPageAccess unavailable");
            }
            const response = await fetch(accessUrl, {
                method: "POST",
                credentials: "include",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ pageIndexes: indexes }),
            });
            const bodyText = await response.text();
            post({ type: "log", message: `page-access HTTP ${response.status} body=${bodyText.slice(0, 400)}` });
            if (!response.ok) {
                throw new Error(`IMGX page-access failed: HTTP ${response.status}`);
            }
            const payload = JSON.parse(bodyText);
            const granted = Array.isArray(payload)
                ? payload
                : (payload?.pages || payload?.sealedPages || payload?.data || []);
            if (!Array.isArray(granted)) {
                throw new Error("IMGX page-access returned no grants");
            }
            return granted;
        };

        if (initialIndexes.length) {
            await openGrants(initialIndexes);
        }
        for (let offset = 0; offset < pageIndexes.length; offset += 10) {
            const indexes = pageIndexes.slice(offset, offset + 10);
            const batch = await openGrants(indexes);
            batch.forEach((page) => {
                const expected = media.find((entry) => Number(entry.pageIndex) === Number(page.pageIndex));
                if (!expected || page.storageKey !== expected.storageKey) {
                    throw new Error([
                        "IMGX grant mismatch",
                        `pageIndex=${page.pageIndex}`,
                        `expected=${expected?.storageKey || "missing"}`,
                        `actual=${page.storageKey || "missing"}`,
                    ].join("; "));
                }
                pages.set(Number(page.pageIndex), page);
            });
        }

        let decodeImgxV4 = null;
        const decoderUrl = "__IMGX_DECODER_URL__";
        if (decoderUrl && !decoderUrl.startsWith("__")) {
            try {
                ({ decodeImgxV4 } = await import(decoderUrl));
            } catch (e) {
                post({ type: "log", message: `v4 decoder import failed: ${e.message}` });
            }
        }

        for (let order = 0; order < pageIndexes.length; order++) {
            const page = pages.get(pageIndexes[order]);
            if (
                !page?.downloadUrl ||
                !(page.grant?.wrappedV4Key || page.grant?.wrappedContentKey ||
                    page.grant?.wrappedDecodeKey || page.grant?.decodeKey)
            ) {
                throw new Error(`IMGX grant missing for page ${order + 1}`);
            }
            const encryptedResponse = await fetch(page.downloadUrl);
            const encrypted = new Uint8Array(await encryptedResponse.arrayBuffer());
            if (!encryptedResponse.ok) {
                throw new Error(`IMGX page ${order + 1} HTTP ${encryptedResponse.status}`);
            }
            const expectedUrl = page.downloadUrl.replace(/[?#].*$/, "");
            if (!expectedUrl.endsWith(`/media/${page.storageKey}`) && !expectedUrl.endsWith(page.storageKey)) {
                throw new Error([
                    `IMGX grant/payload mismatch page=${order + 1}`,
                    `storageKey=${page.storageKey}`,
                    `downloadUrl=${page.downloadUrl}`,
                    `imageId=${page.grant.imageId}`,
                ].join("; "));
            }
            try {
                let webp;
                try {
                    webp = await decodeProtectedPage(encrypted, page.grant, page.storageKey, decodeImgxV4);
                    const magic = webp.byteLength >= 12 ? hexPreview(webp, 4) : "";
                    if (magic !== "52494646" || hexPreview(webp.subarray(8), 4) !== "57454250") {
                        throw new Error(`IMGX decode output is not WebP; magic=${magic}`);
                    }
                } catch (error) {
                    throw new Error([
                        `IMGX decode failed page=${order + 1}`,
                        `storageKey=${page.storageKey}`,
                        `status=${encryptedResponse.status}`,
                        `bytes=${encrypted.byteLength}`,
                        `error=${error?.message || String(error)}`,
                    ].join("; "));
                }
                post({ type: "page", index: order, downloadUrl: page.downloadUrl, data: toBase64(webp) });
                webp.fill(0);
            } finally {
                encrypted.fill(0);
            }
        }
        post({ type: "done", count: pageIndexes.length });
    } catch (error) {
        post({
            type: "error",
            message: error?.message || String(error),
            stack: error?.stack || "none",
        });
    }
})();
