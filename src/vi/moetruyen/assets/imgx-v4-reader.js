(async () => {
    const bridge = window.__IMGX_BRIDGE__;
    const post = (value) => bridge.post(JSON.stringify(value));
    const waitFor = async (predicate, timeout = 20000) => {
        const deadline = performance.now() + timeout;
        while (performance.now() < deadline) {
            const value = predicate();
            if (value) return value;
            await new Promise((resolve) => setTimeout(resolve, 0));
        }
        return null;
    };

    try {
        const documentRoot = await waitFor(() => document.documentElement);
        if (!documentRoot) {
            post({ type: "error", message: "IMGX document root unavailable" });
            return;
        }
        if (documentRoot.dataset.moetruyenExtensionReader) return;
        documentRoot.dataset.moetruyenExtensionReader = "1";

        const runtime = await waitFor(() => globalThis.__IMGX_RUNTIME__);
        if (!runtime || typeof runtime.renderPage !== "function") {
            throw new Error("IMGX reader runtime unavailable");
        }

        const pagesRoot = await waitFor(() => document.querySelector("[data-reader-lazy-pages]"));
        if (!pagesRoot) {
            throw new Error("IMGX reader metadata missing");
        }

        const declaredTotal = Number(pagesRoot.getAttribute("data-reader-total-pages") || 0);
        const shellCount = document.querySelectorAll(".page-protected-shell[data-page-index]").length;
        const imageCount = [...document.querySelectorAll("img.page-media")].filter((image) => {
            return !image.closest("noscript");
        }).length;
        const pageCount = [declaredTotal, shellCount, imageCount].find((value) => Number.isSafeInteger(value) && value > 0);
        if (!pageCount) {
            throw new Error("IMGX page count missing");
        }

        const encodeCanvas = (canvas) => {
            const webp = canvas.toDataURL("image/webp", 0.92);
            if (webp.startsWith("data:image/webp,")) {
                return { data: webp.slice(webp.indexOf(",") + 1), mime: "image/webp" };
            }
            const png = canvas.toDataURL("image/png");
            return { data: png.slice(png.indexOf(",") + 1), mime: "image/png" };
        };

        for (let index = 0; index < pageCount; index++) {
            try {
                if (typeof runtime.releasePage === "function") {
                    runtime.releasePage(index);
                    await new Promise((resolve) => setTimeout(resolve, 50));
                }
                const rendered = await runtime.renderPage(index);
                if (!rendered || typeof rendered.toDataURL !== "function") {
                    throw new Error(`IMGX page ${index + 1} render returned no canvas`);
                }
                const encoded = encodeCanvas(rendered);
                post({ type: "page", index, data: encoded.data, mime: encoded.mime });
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

        post({ type: "done", count: pageCount });
    } catch (error) {
        post({
            type: "error",
            message: error?.message || String(error),
            stack: error?.stack || "none",
        });
    }
})();
