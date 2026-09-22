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

    const decodeBase64Url = (value) => {
        const normalized = String(value).replace(/-/g, "+").replace(/_/g, "/");
        const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
        return Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
    };
    const toBase64 = (bytes) => {
        const chunks = [];
        for (let offset = 0; offset < bytes.byteLength; offset += 0x8000) {
            chunks.push(String.fromCharCode(...bytes.subarray(offset, offset + 0x8000)));
        }
        return btoa(chunks.join(""));
    };
    const encodeBase64Url = (bytes) => toBase64(bytes)
        .replace(/\+/g, "-")
        .replace(/\//g, "_")
        .replace(/=+$/g, "");
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
    const decodeImgxV4 = async (payload, key, context) => {
        if (payload.byteLength <= 78 || hexPreview(payload, 4) !== "494d4758" || payload[4] !== 4) {
            throw new Error("IMGX v4 file invalid");
        }
        const header = payload.slice(0, 78);
        const derived = await hkdfSha256(
            key,
            header.slice(13, 45),
            textEncoder.encode("IMGX-v4.envelope"),
            64,
        );
        const envelopeKey = derived.slice(0, 32);
        const contentKey = derived.slice(32, 64);
        try {
            const storageKey = String(context.storageKey || "").replace(/^\/+/, "");
            const contextJson = textEncoder.encode(
                `["IMGX-v4","${context.imageId || ""}","${storageKey}"]`,
            );
            const envelopeAad = new Uint8Array(57 + contextJson.length);
            envelopeAad.set(header.slice(0, 57), 0);
            envelopeAad.set(contextJson, 57);
            const envelope = await aesGcmDecrypt(
                envelopeKey,
                header.slice(45, 57),
                envelopeAad,
                header.slice(57, 78),
            );
            const profile = envelope[0];
            const body = payload.slice(78);
            const contentAad = new Uint8Array(header.length + contextJson.length);
            contentAad.set(header, 0);
            contentAad.set(contextJson, header.length);
            post({ type: "log", message: `v4 profile=p0${profile} body=${body.byteLength}` });

            if (profile === 1) {
                return await aesGcmDecrypt(contentKey, body.slice(0, 12), contentAad, body.slice(12));
            }
            if (profile === 2) {
                const keyObj = await crypto.subtle.importKey("raw", contentKey, "ChaCha20-Poly1305", false, ["decrypt"]);
                return new Uint8Array(await crypto.subtle.decrypt(
                    { name: "ChaCha20-Poly1305", iv: body.slice(0, 12), additionalData: contentAad, tagLength: 128 },
                    keyObj,
                    body.slice(12),
                ));
            }
            if (profile === 6) {
                const p06Key = await hkdfSha256(
                    contentKey,
                    new Uint8Array(32),
                    textEncoder.encode("IMGX-v4.p06"),
                    64,
                );
                try {
                    return await aesCbcHmacDecrypt(p06Key, body, contentAad);
                } finally {
                    p06Key.fill(0);
                }
            }
            throw new Error(`IMGX v4 profile unsupported: p0${profile}`);
        } finally {
            envelopeKey.fill(0);
            contentKey.fill(0);
            derived.fill(0);
        }
    };
    const aesCbcHmacDecrypt = async (key, body, aad) => {
        if (key.byteLength !== 64 || body.byteLength <= 48) throw new Error("IMGX p06 payload invalid");
        const iv = body.slice(0, 16);
        const ct = body.slice(16, body.byteLength - 32);
        const mac = body.slice(body.byteLength - 32);
        const macKey = key.slice(0, 32);
        const encKey = key.slice(32, 64);
        const lenBlock = new Uint8Array(8);
        new DataView(lenBlock.buffer).setBigUint64(0, BigInt(aad.byteLength) * 8n);
        const macInput = new Uint8Array(aad.byteLength + iv.byteLength + ct.byteLength + 8);
        macInput.set(aad, 0);
        macInput.set(iv, aad.byteLength);
        macInput.set(ct, aad.byteLength + iv.byteLength);
        macInput.set(lenBlock, aad.byteLength + iv.byteLength + ct.byteLength);
        const macKeyObj = await crypto.subtle.importKey("raw", macKey, { name: "HMAC", hash: "SHA-512" }, false, ["sign"]);
        const fullMac = new Uint8Array(await crypto.subtle.sign("HMAC", macKeyObj, macInput));
        const expected = fullMac.slice(0, 32);
        for (let i = 0; i < 32; i++) {
            if (mac[i] !== expected[i]) throw new Error("IMGX p06 authentication failed");
        }
        const encKeyObj = await crypto.subtle.importKey("raw", encKey, "AES-CBC", false, ["decrypt"]);
        return new Uint8Array(await crypto.subtle.decrypt({ name: "AES-CBC", iv }, encKeyObj, ct));
    };
    const decodeProtectedPage = async (encrypted, grant, storageKey) => {
        const context = { imageId: grant.imageId, storageKey };
        const version = encrypted[4];
        if (version === 2) {
            return decodeImgxV2(encrypted, grant, storageKey);
        }
        if (version === 4) {
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

    const textEncoder = new TextEncoder();
    const generateEcdh = async () => {
        const pair = await crypto.subtle.generateKey(
            { name: "ECDH", namedCurve: "P-256" },
            true,
            ["deriveBits"],
        );
        const raw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
        return { privateKey: pair.privateKey, publicKey: encodeBase64Url(raw) };
    };
    const deriveSharedSecret = async (privateKey, peerPublicKey) => {
        const peer = await crypto.subtle.importKey(
            "raw",
            decodeBase64Url(peerPublicKey),
            { name: "ECDH", namedCurve: "P-256" },
            false,
            [],
        );
        const bits = await crypto.subtle.deriveBits(
            { name: "ECDH", public: peer },
            privateKey,
            256,
        );
        return new Uint8Array(bits);
    };
    const hkdfSha256 = async (ikm, salt, info, length) => {
        const key = await crypto.subtle.importKey("raw", ikm, "HKDF", false, ["deriveBits"]);
        const bits = await crypto.subtle.deriveBits(
            { name: "HKDF", hash: "SHA-256", salt, info },
            key,
            length * 8,
        );
        return new Uint8Array(bits);
    };
    const hmacSha256 = async (keyBytes, data) => {
        const key = await crypto.subtle.importKey(
            "raw",
            keyBytes,
            { name: "HMAC", hash: "SHA-256" },
            false,
            ["sign"],
        );
        return new Uint8Array(await crypto.subtle.sign("HMAC", key, data));
    };
    const aesGcmDecrypt = async (keyBytes, iv, aad, ciphertext) => {
        const key = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["decrypt"]);
        return new Uint8Array(await crypto.subtle.decrypt(
            { name: "AES-GCM", iv, additionalData: aad, tagLength: 128 },
            key,
            ciphertext,
        ));
    };
    const channelAad = (ownPublic, peerPublic, proof) => textEncoder.encode(
        `["imgx-reader-channel-v1","${ownPublic}","${peerPublic}","${proof}"]`,
    );
    const deriveChannelKey = (shared, proof) => hkdfSha256(
        shared,
        textEncoder.encode(proof),
        textEncoder.encode("imgx-reader-channel-v1"),
        32,
    );
    const publicKeyHash = async (publicKey) => {
        const digest = await crypto.subtle.digest("SHA-256", decodeBase64Url(publicKey));
        return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
    };
    const parseBootstrapConfig = (rootEl) => {
        const scripts = [...document.querySelectorAll("script:not([src])")].map((node) => node.textContent).join("\n")
            + "\n"
            + document.documentElement.outerHTML;
        const requestPath = (/requestPath:\s*"([^"]+)"/.exec(scripts) || [])[1];
        const chapterId = Number((/chapterId:\s*(\d+)/.exec(scripts) || [])[1]);
        const bootstrapUrl = (/bootstrapUrl:\s*"([^"]*)"/.exec(scripts) || [])[1] || "";
        const initialRaw = (/initialIndexes:\s*\[([^\]]*)\]/.exec(scripts) || [])[1] || "";
        const initialIndexes = initialRaw.split(",")
            .map((part) => part.trim())
            .filter((part) => part !== "")
            .map(Number)
            .filter(Number.isSafeInteger);
        if (requestPath && Number.isSafeInteger(chapterId)) {
            return { requestPath, chapterId, bootstrapUrl, initialIndexes };
        }
        const accessUrl = rootEl.getAttribute("data-reader-imgx-access-url");
        if (!accessUrl) throw new Error("IMGX access URL missing");
        const trackToken = rootEl.getAttribute("data-reader-view-track-token") || "";
        const attrChapterId = Number(trackToken.split(".")[0]);
        const totalPages = Number(rootEl.getAttribute("data-reader-total-pages") || "0");
        return {
            requestPath: accessUrl,
            chapterId: Number.isSafeInteger(attrChapterId) ? attrChapterId : chapterId,
            bootstrapUrl,
            initialIndexes: totalPages > 0 ? [totalPages - 1] : [],
        };
    };
    const buildProofMaterial = (config, readerInstanceId, pageIndexes, issuedAt, sequence, hash) => textEncoder.encode(
        `["imgx-page-access-proof-v3","${readerInstanceId}",${config.chapterId},"${config.requestPath}","",[${pageIndexes.join(",")}],${issuedAt},${sequence},"${hash}"]`,
    );
    const openChannelKeys = async (keyPair, sealed, shared, proof, page, ck) => {
        if (ck.version !== "IMGX-READER-PAGE-KEY-v1") {
            throw new Error(`IMGX page key version unsupported: ${ck.version}`);
        }
        const pageKey = await hkdfSha256(
            shared,
            textEncoder.encode(proof),
            textEncoder.encode("IMGX-READER-PAGE-KEY-v1"),
            32,
        );
        try {
            const grant = page.grant;
            const aad = textEncoder.encode(
                `["${ck.version}",["${keyPair.publicKey}","${sealed.publicKey}","${proof}"],${page.pageIndex},"${page.storageKey}","${grant.imageId || ""}",${grant.issuedAt},${grant.expiresAt},"${grant.nonce || ""}","${grant.signature || ""}"]`,
            );
            const plain = await aesGcmDecrypt(
                pageKey,
                decodeBase64Url(ck.iv),
                aad,
                decodeBase64Url(ck.ciphertext),
            );
            return JSON.parse(new TextDecoder().decode(plain));
        } finally {
            pageKey.fill(0);
        }
    };
    const openSealedPages = async (keyPair, sealed, proof) => {
        if (!sealed || sealed.version !== "imgx-reader-channel-v1") {
            throw new Error("IMGX sealed page channel invalid");
        }
        const shared = await deriveSharedSecret(keyPair.privateKey, sealed.publicKey);
        try {
            const channelKey = await deriveChannelKey(shared, proof);
            const plain = await aesGcmDecrypt(
                channelKey,
                decodeBase64Url(sealed.iv),
                channelAad(keyPair.publicKey, sealed.publicKey, proof),
                decodeBase64Url(sealed.ciphertext),
            );
            const pages = JSON.parse(new TextDecoder().decode(plain));
            const opened = [];
            for (const page of pages) {
                const grant = page.grant;
                const ck = grant && grant.channelKeys;
                if (!ck) {
                    opened.push(page);
                    continue;
                }
                const decrypted = await openChannelKeys(keyPair, sealed, shared, proof, page, ck);
                opened.push({
                    ...page,
                    grant: {
                        version: grant.version,
                        algorithm: grant.algorithm,
                        imageId: grant.imageId,
                        issuedAt: grant.issuedAt,
                        expiresAt: grant.expiresAt,
                        nonce: grant.nonce,
                        keyNonce: grant.keyNonce,
                        signature: grant.signature,
                        wrappedDecodeKey: decrypted.wrappedDecodeKey || grant.wrappedDecodeKey,
                        wrappedContentKey: decrypted.wrappedContentKey || grant.wrappedContentKey,
                        wrappedV4Key: decrypted.wrappedV4Key || grant.wrappedV4Key,
                        decodeKey: decrypted.decodeKey || grant.decodeKey,
                    },
                });
            }
            return opened;
        } finally {
            shared.fill(0);
        }
    };
    const fetchAllGrants = async (rootEl, pageIndexes) => {
        const config = parseBootstrapConfig(rootEl);
        post({
            type: "log",
            message: `bootstrap=${config.bootstrapUrl} path=${config.requestPath} chapterId=${config.chapterId} pages=${pageIndexes.length}`,
        });
        if (!config.bootstrapUrl) {
            throw new Error("IMGX bootstrapUrl missing");
        }
        const keyPair = await generateEcdh();
        const bootstrapProof = encodeBase64Url(crypto.getRandomValues(new Uint8Array(32)));
        const bootstrapResponse = await fetch(config.bootstrapUrl, {
            method: "POST",
            credentials: "include",
            headers: {
                "Accept": "application/json",
                "Content-Type": "application/json",
                "Sec-Fetch-Dest": "empty",
                "Sec-Fetch-Mode": "cors",
                "Sec-Fetch-Site": "same-origin",
            },
            body: JSON.stringify({
                readerPublicKey: keyPair.publicKey,
                bootstrapProof,
                initialPageIndexes: config.initialIndexes,
            }),
        });
        const bootstrapText = await bootstrapResponse.text();
        post({ type: "log", message: `bootstrap HTTP ${bootstrapResponse.status} body=${bootstrapText.slice(0, 240)}` });
        if (!bootstrapResponse.ok) {
            throw new Error(`IMGX bootstrap failed: HTTP ${bootstrapResponse.status}`);
        }
        const bootstrap = JSON.parse(bootstrapText);
        if (!bootstrap.ok || !bootstrap.sealedCapability) {
            throw new Error(`IMGX bootstrap failed: ${bootstrap.code || "unknown"}`);
        }
        const sharedCap = await deriveSharedSecret(keyPair.privateKey, bootstrap.sealedCapability.publicKey);
        let capability;
        try {
            const capKey = await deriveChannelKey(sharedCap, bootstrapProof);
            const capPlain = await aesGcmDecrypt(
                capKey,
                decodeBase64Url(bootstrap.sealedCapability.iv),
                channelAad(keyPair.publicKey, bootstrap.sealedCapability.publicKey, bootstrapProof),
                decodeBase64Url(bootstrap.sealedCapability.ciphertext),
            );
            const capList = JSON.parse(new TextDecoder().decode(capPlain));
            capability = Array.isArray(capList) ? capList[0] : capList;
        } finally {
            sharedCap.fill(0);
        }
        if (!capability || capability.readerInstanceId !== bootstrap.readerInstanceId) {
            throw new Error("IMGX reader instance mismatch");
        }

        const granted = new Map();
        if (bootstrap.sealedInitialPages) {
            const initialPages = await openSealedPages(keyPair, bootstrap.sealedInitialPages, bootstrapProof);
            for (const page of initialPages) granted.set(Number(page.pageIndex), page);
        }

        const remaining = pageIndexes.filter((index) => !granted.has(index));
        let sequence = 0;
        const hash = await publicKeyHash(keyPair.publicKey);
        for (let offset = 0; offset < remaining.length; offset += 10) {
            const batch = remaining.slice(offset, offset + 10);
            sequence += 1;
            const material = buildProofMaterial(
                config,
                capability.readerInstanceId,
                batch,
                bootstrap.serverTime,
                sequence,
                hash,
            );
            const signature = encodeBase64Url(await hmacSha256(decodeBase64Url(capability.secret), material));
            const accessResponse = await fetch(config.requestPath, {
                method: "POST",
                credentials: "include",
                headers: {
                    "Accept": "application/json",
                    "Content-Type": "application/json",
                    "Sec-Fetch-Dest": "empty",
                    "Sec-Fetch-Mode": "cors",
                    "Sec-Fetch-Site": "same-origin",
                },
                body: JSON.stringify({
                    pageIndexes: batch,
                    pageAccessProof: {
                        version: "imgx-page-access-proof-v3",
                        readerInstanceId: capability.readerInstanceId,
                        issuedAt: bootstrap.serverTime,
                        sequence,
                        proof: signature,
                    },
                    readerPublicKey: keyPair.publicKey,
                }),
            });
            const accessText = await accessResponse.text();
            post({ type: "log", message: `page-access indexes=${batch.join(",")} HTTP ${accessResponse.status}` });
            if (!accessResponse.ok) {
                throw new Error(`IMGX page-access failed: HTTP ${accessResponse.status}`);
            }
            const access = JSON.parse(accessText);
            if (!access.ok || !access.sealedPages) {
                throw new Error(`IMGX page access failed: ${access.code || "unknown"}`);
            }
            const opened = await openSealedPages(keyPair, access.sealedPages, signature);
            for (const page of opened) granted.set(Number(page.pageIndex), page);
        }

        return pageIndexes.map((index) => granted.get(index)).filter(Boolean);
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

        const pages = new Map();

        const grantedList = await fetchAllGrants(root, pageIndexes);
        post({ type: "log", message: `grants=${grantedList.length} media=${media.length}` });
        if (!grantedList.length) {
            throw new Error("IMGX grants missing after page-access");
        }
        grantedList.forEach((page) => {
            const expected = media.find((entry) => Number(entry.pageIndex) === Number(page.pageIndex));
            if (expected && page.storageKey && expected.storageKey && page.storageKey !== expected.storageKey) {
                throw new Error([
                    "IMGX grant mismatch",
                    `pageIndex=${page.pageIndex}`,
                    `expected=${expected.storageKey}`,
                    `actual=${page.storageKey}`,
                ].join("; "));
            }
            pages.set(Number(page.pageIndex), page);
        });
        for (const index of pageIndexes) {
            if (!pages.has(index)) {
                throw new Error(`IMGX grant missing for pageIndex=${index}`);
            }
        }

        const decoderUrl = "__IMGX_DECODER_URL__";
        if (decoderUrl && !decoderUrl.startsWith("__")) {
            try {
                await import(decoderUrl);
                post({ type: "log", message: "v4 decoder import ok (local still used)" });
            } catch (e) {
                post({ type: "log", message: `v4 decoder import skipped: ${e.message}` });
            }
        }

        const decodeOne = async (order) => {
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
                let imageBytes;
                try {
                    imageBytes = await decodeProtectedPage(encrypted, page.grant, page.storageKey);
                    const head = imageBytes.byteLength >= 12 ? hexPreview(imageBytes, 12) : "";
                    const isPng = head.startsWith("89504e47");
                    const isJpeg = head.startsWith("ffd8ff");
                    const isGif = head.startsWith("47494638");
                    const isWebp = head.startsWith("52494646") && head.slice(16, 24) === "57454250";
                    if (!(isPng || isJpeg || isGif || isWebp)) {
                        throw new Error(`IMGX decode output is not an image; magic=${head.slice(0, 8)}`);
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
                post({ type: "page", index: order, downloadUrl: page.downloadUrl, data: toBase64(imageBytes) });
                imageBytes.fill(0);
            } finally {
                encrypted.fill(0);
            }
        };
        const concurrency = 4;
        let cursor = 0;
        let firstError = null;
        const worker = async () => {
            while (cursor < pageIndexes.length && !firstError) {
                const order = cursor++;
                try {
                    await decodeOne(order);
                    post({ type: "log", message: `decoded page=${order + 1}/${pageIndexes.length}` });
                } catch (error) {
                    firstError = error;
                    return;
                }
            }
        };
        await Promise.all(Array.from({ length: Math.min(concurrency, pageIndexes.length) }, worker));
        if (firstError) throw firstError;
        post({ type: "done", count: pageIndexes.length });
    } catch (error) {
        post({
            type: "error",
            message: error?.message || String(error),
            stack: error?.stack || "none",
        });
    }
})();
