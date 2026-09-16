(async () => {
    const bridge = window.MoeTruyenBridge;
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
    const injectionFlag = "moetruyenExtensionReader";
    if (documentRoot.dataset[injectionFlag]) return;
    documentRoot.dataset[injectionFlag] = "1";
    const decodeBase64Url = (value) => Uint8Array.from(
        atob(value.replace(/-/g, "+").replace(/_/g, "/")),
        (char) => char.charCodeAt(0),
    );
    const openSealedPages = async (channelKeyPair, sealed, proof) => {
        if (!sealed || sealed.version !== "imgx-reader-channel-v1") {
            throw new Error("IMGX sealed page channel invalid");
        }
        if (typeof proof !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(proof)) {
            throw new Error("IMGX channel proof invalid");
        }
        const serverPublicBytes = decodeBase64Url(sealed.publicKey);
        if (serverPublicBytes.byteLength !== 65 || serverPublicBytes[0] !== 4) {
            throw new Error("IMGX channel public key invalid");
        }
        const serverPublicKey = await crypto.subtle.importKey(
            "raw",
            serverPublicBytes,
            { name: "ECDH", namedCurve: "P-256" },
            false,
            [],
        );
        const sharedSecret = new Uint8Array(await crypto.subtle.deriveBits(
            { name: "ECDH", public: serverPublicKey },
            channelKeyPair.privateKey,
            256,
        ));
        try {
            const hkdfBaseKey = await crypto.subtle.importKey(
                "raw",
                sharedSecret,
                "HKDF",
                false,
                ["deriveKey"],
            );
            const channelKey = await crypto.subtle.deriveKey(
                {
                    name: "HKDF",
                    hash: "SHA-256",
                    salt: new TextEncoder().encode(proof),
                    info: new TextEncoder().encode("imgx-reader-channel-v1"),
                },
                hkdfBaseKey,
                { name: "AES-GCM", length: 256 },
                false,
                ["decrypt"],
            );
            const iv = decodeBase64Url(sealed.iv);
            const ciphertext = decodeBase64Url(sealed.ciphertext);
            const publicKeyBytes = new Uint8Array(await crypto.subtle.exportKey(
                "raw",
                channelKeyPair.publicKey,
            ));
            const additionalData = new TextEncoder().encode(JSON.stringify([
                "imgx-reader-channel-v1",
                toBase64Url(publicKeyBytes),
                sealed.publicKey,
                proof,
            ]));
            const plaintext = new Uint8Array(await crypto.subtle.decrypt(
                { name: "AES-GCM", iv, additionalData, tagLength: 128 },
                channelKey,
                ciphertext,
            ));
            try {
                const pages = JSON.parse(new TextDecoder().decode(plaintext));
                if (!Array.isArray(pages)) throw new Error("IMGX sealed pages invalid");
                return pages;
            } finally {
                plaintext.fill(0);
                ciphertext.fill(0);
                iv.fill(0);
            }
        } finally {
            sharedSecret.fill(0);
        }
    };
    const toBase64Url = (bytes) => btoa(String.fromCharCode(...bytes))
        .replace(/\+/g, "-")
        .replace(/\//g, "_")
        .replace(/=+$/, "");
    const toBase64 = (bytes) => {
        const chunks = [];
        for (let offset = 0; offset < bytes.byteLength; offset += 0x8000) {
            chunks.push(String.fromCharCode(...bytes.subarray(offset, offset + 0x8000)));
        }
        return btoa(chunks.join(""));
    };
    const hexPreview = (bytes, length = 16) => [...bytes.slice(0, length)]
        .map((byte) => byte.toString(16).padStart(2, "0"))
        .join("");
    const webpDimensions = (bytes) => {
        if (bytes.byteLength < 30 || hexPreview(bytes, 4) !== "52494646" ||
            hexPreview(bytes.slice(8), 4) !== "57454250" ||
            hexPreview(bytes.slice(12), 4) !== "56503858") {
            return "unknown";
        }
        const width = 1 + bytes[24] + (bytes[25] << 8) + (bytes[26] << 16);
        const height = 1 + bytes[27] + (bytes[28] << 8) + (bytes[29] << 16);
        return `${width}x${height}`;
    };
    const imgxDimensions = (bytes) => bytes.byteLength >= 13
        ? `${new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(5)}x${new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(9)}`
        : "unknown";
    const digest = async (bytes) => [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))]
        .map((byte) => byte.toString(16).padStart(2, "0"))
        .join("");
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
    const unwrapContentKey = (grant, storageKey) => {
        const wrapped = decodeBase64Url(grant.wrappedContentKey);
        if (wrapped.byteLength !== 32) throw new Error("IMGX content grant invalid");
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
    const readUint32 = (bytes, offset) => new DataView(
        bytes.buffer,
        bytes.byteOffset,
        bytes.byteLength,
    ).getUint32(offset);
    const decodeImgxV3 = async (encrypted, grant, storageKey) => {
        if (encrypted.byteLength < 41 || encrypted[4] !== 3) {
            throw new Error("IMGX v3 payload invalid");
        }
        const width = readUint32(encrypted, 5);
        const height = readUint32(encrypted, 9);
        if (!width || !height) throw new Error("IMGX v3 dimensions invalid");
        const key = unwrapContentKey(grant, storageKey);
        const iv = encrypted.slice(13, 25);
        const ciphertext = encrypted.slice(25);
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
            iv.fill(0);
            ciphertext.fill(0);
        }
    };

    try {
        const root = await waitFor(() => document.querySelector("[data-reader-lazy-pages]"));
        if (!root) throw new Error("IMGX reader metadata missing");

        const media = JSON.parse(decodeURIComponent(root.dataset.readerImgxMedia || "%5B%5D"))
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

        const runtime = (await waitFor(() => globalThis.__IMGX_RUNTIME__))?.take();
        if (!runtime) throw new Error("IMGX reader runtime unavailable");
        const pages = new Map();

        for (let offset = 0; offset < pageIndexes.length; offset += 10) {
            const indexes = pageIndexes.slice(offset, offset + 10);
            const batch = await runtime.requestPageAccess(indexes);
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

        const decoderUrl = "__IMGX_DECODER_URL__";
        const { decodeImgxV4 } = await import(decoderUrl);
        const decodedFingerprints = [];

        for (let order = 0; order < pageIndexes.length; order++) {
            const page = pages.get(pageIndexes[order]);
            if (!page?.downloadUrl || (!page?.grant?.wrappedV4Key && !page?.grant?.wrappedContentKey)) {
                throw new Error(`IMGX grant missing for page ${order + 1}`);
            }
            const encryptedResponse = await fetch(page.downloadUrl);
            const encrypted = new Uint8Array(await encryptedResponse.arrayBuffer());
            if (!encryptedResponse.ok) {
                throw new Error(`IMGX page ${order + 1} HTTP ${encryptedResponse.status}`);
            }
            const payloadVersion = encrypted[4];
            const key = payloadVersion === 4 ? unwrapV4Key(page.grant, page.storageKey) : null;
            const expectedUrl = page.downloadUrl.replace(/[?#].*$/, "");
            if (expectedUrl.endsWith(`/media/${page.storageKey}`) === false && expectedUrl.endsWith(page.storageKey) === false) {
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
                    webp = payloadVersion === 3
                        ? await decodeImgxV3(encrypted, page.grant, page.storageKey)
                        : await decodeImgxV4(encrypted, key, {
                            imageId: page.grant.imageId,
                            storageKey: page.storageKey,
                        });
                    const magic = webp.byteLength >= 12 ? hexPreview(webp, 4) : "";
                    if (magic !== "52494646" || hexPreview(webp.slice(8), 4) !== "57454250") {
                        throw new Error(`IMGX v3 authentication failed; output is not WebP; magic=${magic}`);
                    }
                } catch (error) {
                    throw new Error([
                        `IMGX decode failed page=${order + 1}`,
                        `storageKey=${page.storageKey}`,
                        `status=${encryptedResponse.status}`,
                        `bytes=${encrypted.byteLength}`,
                        `head=${hexPreview(encrypted)}`,
                        `error=${error?.message || String(error)}`,
                        `stack=${error?.stack || "none"}`,
                    ].join("; "));
                }
                decodedFingerprints.push({
                    page: order + 1,
                    storageKey: page.storageKey,
                    pageIndex: page.pageIndex,
                    encryptedDimensions: imgxDimensions(encrypted),
                    expected: `${page.width || "?"}x${page.height || "?"}`,
                    bytes: webp.byteLength,
                    dimensions: webpDimensions(webp),
                    head: hexPreview(webp),
                    sha256: await digest(webp),
                });
                post({ type: "page", index: order, data: toBase64(webp) });
                webp.fill(0);
            } finally {
                encrypted.fill(0);
                key?.fill(0);
            }
        }
        const uniqueFingerprints = new Set(decodedFingerprints.map((entry) => entry.sha256));
        post({
            type: "diagnostic",
            message: uniqueFingerprints.size === 1
                ? "IMGX decoded pages are identical; possible site lock/banner response"
                : "IMGX decoded page fingerprints collected",
            pages: decodedFingerprints.length,
            unique: uniqueFingerprints.size,
            first: decodedFingerprints[0],
            second: decodedFingerprints[1],
            last: decodedFingerprints.at(-1),
        });
        post({ type: "done", count: pageIndexes.length });
    } catch (error) {
        post({
            type: "error",
            message: error?.message || String(error),
            stack: error?.stack || "none",
        });
    }
})();
