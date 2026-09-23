(async () => {
    const bridge = window.__IMGX_BRIDGE__;
    let stage = "init";
    const post = (value) => bridge.post(JSON.stringify(value));
    const log = (message, level = "d") => post({ type: "log", level, message: `[${stage}] ${message}` });
    const fail = (error) => {
        const message = error?.message || String(error);
        post({
            type: "error",
            stage,
            message,
            stack: error?.stack || "none",
        });
    };
    const waitFor = async (predicate, timeout = 20000, label = "value") => {
        const deadline = performance.now() + timeout;
        while (performance.now() < deadline) {
            const value = predicate();
            if (value) {
                log(`wait ok: ${label}`);
                return value;
            }
            await new Promise((resolve) => setTimeout(resolve, 50));
        }
        log(`wait timeout: ${label} after ${timeout}ms`, "e");
        return null;
    };

    try {
        stage = "dom";
        const documentRoot = await waitFor(() => document.documentElement, 10000, "documentElement");
        if (!documentRoot) {
            throw new Error("IMGX document root unavailable");
        }
        if (documentRoot.dataset.moetruyenExtensionReader) {
            log("already injected, skip");
            return;
        }
        documentRoot.dataset.moetruyenExtensionReader = "1";
        log(`injected href=${location.href}`);

        stage = "runtime";
        const runtime = await waitFor(
            () => globalThis.__IMGX_RUNTIME__,
            20000,
            "__IMGX_RUNTIME__",
        );
        if (!runtime || typeof runtime.renderPage !== "function") {
            throw new Error("IMGX reader runtime unavailable");
        }
        log(`runtime methods=${Object.getOwnPropertyNames(runtime).join(",")}`);

        stage = "meta";
        const pagesRoot = await waitFor(
            () => document.querySelector("[data-reader-lazy-pages]"),
            15000,
            "[data-reader-lazy-pages]",
        );
        if (!pagesRoot) {
            throw new Error("IMGX reader metadata missing");
        }

        const declaredTotal = Number(pagesRoot.getAttribute("data-reader-total-pages") || 0);
        const accessUrl = pagesRoot.getAttribute("data-reader-imgx-access-url") || "";
        const renderMode = pagesRoot.getAttribute("data-reader-imgx-render-mode") || "";
        const shellCount = document.querySelectorAll(".page-protected-shell[data-page-index]").length;
        const imageCount = [...document.querySelectorAll("img.page-media")].filter((image) => {
            return !image.closest("noscript");
        }).length;
        const pageCount = [declaredTotal, shellCount, imageCount].find((value) => Number.isSafeInteger(value) && value > 0);
        log(
            `meta declaredTotal=${declaredTotal} shellCount=${shellCount} imageCount=${imageCount} ` +
            `pageCount=${pageCount} accessUrl=${accessUrl} renderMode=${renderMode}`,
        );
        if (!pageCount) {
            throw new Error("IMGX page count missing");
        }

        stage = "ready";
        // Wait until the site itself has prepared at least one page, so page-access is live.
        const siteReady = await waitFor(
            () => document.querySelector(".page-protected-shell.is-loaded, .page-protected-shell.is-loading, img.page-media.is-loaded"),
            25000,
            "site first page ready",
        );
        if (!siteReady) {
            log("site never marked a page ready; continuing anyway", "e");
        } else {
            const loaded = document.querySelectorAll(".page-protected-shell.is-loaded").length;
            log(`site ready loadedShells=${loaded}`);
        }

        const encodeCanvas = (canvas) => {
            const webp = canvas.toDataURL("image/webp", 0.92);
            if (webp.startsWith("data:image/webp,")) {
                return { data: webp.slice(webp.indexOf(",") + 1), mime: "image/webp" };
            }
            const png = canvas.toDataURL("image/png");
            return { data: png.slice(png.indexOf(",") + 1), mime: "image/png" };
        };

        stage = "render";
        for (let index = 0; index < pageCount; index++) {
            stage = `render:${index}`;
            let rendered = null;
            let lastError = null;
            for (let attempt = 0; attempt < 4 && !rendered; attempt++) {
                try {
                    if (typeof runtime.releasePage === "function") {
                        runtime.releasePage(index);
                        await new Promise((resolve) => setTimeout(resolve, 80));
                    }
                    log(`renderPage attempt=${attempt + 1} index=${index}`);
                    rendered = await runtime.renderPage(index);
                    log(`renderPage ok index=${index} ctor=${rendered?.constructor?.name} w=${rendered?.width} h=${rendered?.height}`);
                } catch (error) {
                    lastError = error;
                    log(`renderPage fail index=${index} attempt=${attempt + 1} err=${error?.message || error}`, "e");
                    await new Promise((resolve) => setTimeout(resolve, 300 * (attempt + 1)));
                }
            }
            if (!rendered) {
                throw lastError || new Error(`IMGX page ${index + 1} missing`);
            }

            try {
                const encoded = encodeCanvas(rendered);
                post({ type: "page", index, data: encoded.data, mime: encoded.mime });
                log(`encoded index=${index} mime=${encoded.mime} b64len=${encoded.data.length}`);
            } catch (error) {
                const message = error?.message || String(error);
                if (message.includes("protected canvas extraction is blocked")) {
                    throw new Error(
                        "IMGX blocked canvas extraction (protected reader). " +
                        "The site now refuses automated image export.",
                    );
                }
                throw error;
            }
        }

        stage = "done";
        log(`done count=${pageCount}`);
        post({ type: "done", count: pageCount });
    } catch (error) {
        fail(error);
    }
})();
