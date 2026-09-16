(async () => {
    const injectionFlag = "moetruyenExtensionReader";
    if (document.documentElement.dataset[injectionFlag]) return;
    document.documentElement.dataset[injectionFlag] = "1";

    const bridge = window.MoeTruyenBridge;
    const post = (value) => bridge.post(JSON.stringify(value));
    const base64Url = (bytes) => btoa(String.fromCharCode(...bytes))
        .replace(/\+/g, "-")
        .replace(/\//g, "_")
        .replace(/=+$/, "");
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
    const request = async (url, body) => {
        const response = await fetch(url, {
            method: "POST",
            credentials: "same-origin",
            cache: "no-store",
            headers: { Accept: "application/json", "Content-Type": "application/json" },
            body: JSON.stringify(body),
        });
        const payload = await response.json();
        if (!response.ok || payload.ok !== true) {
            throw new Error(payload.code || `HTTP ${response.status}`);
        }
        return payload;
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

        const inlineCode = [...document.scripts].map((script) => script.textContent || "").join("\n");
        const bootstrapUrl = inlineCode.match(/bootstrapUrl:\s*["']([^"']+)["']/)?.[1];
        const chapterId = Number(inlineCode.match(/chapterId:\s*(\d+)/)?.[1]);
        const requestPath = root.dataset.readerImgxAccessUrl;
        if (!bootstrapUrl || !Number.isSafeInteger(chapterId) || !requestPath) {
            throw new Error("IMGX reader capability missing");
        }

        const readerCryptoUrl = new URL("/imgx-reader.js", location.href).href;
        const readerCrypto = await import(readerCryptoUrl);
        const channel = await readerCrypto.createImgxReaderChannel();
        const bootstrapProof = base64Url(crypto.getRandomValues(new Uint8Array(32)));
        const initialIndexes = JSON.parse(decodeURIComponent(root.dataset.readerImgxInitialPages || "%5B%5D"))
            .map((page) => page.pageIndex);
        const bootstrap = await request(bootstrapUrl, {
            readerPublicKey: channel.publicKey,
            bootstrapProof,
            initialPageIndexes: initialIndexes,
        });
        const [material] = await channel.open(bootstrap.sealedCapability, bootstrapProof);
        const initialPages = await channel.open(bootstrap.sealedInitialPages, bootstrapProof);
        const pages = new Map(initialPages.map((page) => [page.pageIndex, page]));

        const secret = decodeBase64Url(material.secret);
        const signingKey = await crypto.subtle.importKey(
            "raw",
            secret,
            { name: "HMAC", hash: "SHA-256" },
            false,
            ["sign"],
        );
        secret.fill(0);
        const publicKeyBytes = decodeBase64Url(channel.publicKey);
        const publicKeyHash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", publicKeyBytes))]
            .map((byte) => byte.toString(16).padStart(2, "0"))
            .join("");
        let sequence = 0;

        for (let offset = 0; offset < pageIndexes.length; offset += 10) {
            const indexes = pageIndexes.slice(offset, offset + 10).filter((index) => !pages.has(index));
            if (!indexes.length) continue;
            const proof = {
                version: readerCrypto.IMGX_PAGE_ACCESS_PROOF_VERSION,
                readerInstanceId: material.readerInstanceId,
                issuedAt: bootstrap.serverTime,
                sequence: ++sequence,
            };
            const proofPayload = readerCrypto.buildImgxPageAccessClientProofPayload({
                ...proof,
                chapterId,
                requestPath,
                pageIndexes: indexes,
                publicKeyHash,
            });
            proof.proof = base64Url(new Uint8Array(await crypto.subtle.sign(
                "HMAC",
                signingKey,
                new TextEncoder().encode(proofPayload),
            )));
            const access = await request(requestPath, {
                pageIndexes: indexes,
                pageAccessProof: proof,
                readerPublicKey: channel.publicKey,
            });
            const batch = await channel.open(access.sealedPages, proof.proof);
            batch.forEach((page) => pages.set(page.pageIndex, page));
        }

        const readerScriptUrl = [...document.scripts]
            .map((script) => script.src)
            .find((url) => url.includes("/reader.js"));
        if (!readerScriptUrl) throw new Error("IMGX reader script missing");
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
