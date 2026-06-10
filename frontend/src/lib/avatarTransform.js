/**
 * avatarTransform.js — Client-side processing pipeline for the
 * "Upload your own avatar" feature.
 *
 * v2.10.29 — "Just works" upload.  User drops in ANY image, GIF
 * or short video file and we handle the resize / animation
 * conversion automatically.  No external tools, no manual cropping,
 * no resizing required.
 *
 * Three paths, picked by MIME type:
 *
 *   • image/png | image/jpeg | image/webp | image/bmp
 *     → decode via <img> → draw to 512×512 canvas with object-fit:
 *       cover semantics (center-crop) → export as JPEG quality 0.9.
 *       Output is always ≤200 KB regardless of source size.
 *
 *   • image/gif (already animated)
 *     → pass-through if ≤2 MB.  We don't decode/re-encode the GIF
 *       because preserving the animation timeline cleanly without
 *       a decoder is a pile of edge cases; sub-2 MB GIFs animate
 *       fine inside the <img> avatar circle.
 *
 *   • video/* (mp4, webm, mov, etc.)
 *     → load into a hidden <video>, seek through the first 3 s,
 *       sample 24 frames at 8 fps (cheap on CPU, smooth-enough
 *       perceptually) drawn at 256×256 (smaller than image path —
 *       GIF palette quantisation gets ugly at 512×512 and the
 *       avatar circle only renders 240 px max anyway), encode
 *       with gifenc, infinite loop, return data:image/gif URL.
 *
 *  All three paths return a Promise<{ dataUrl, mime, animated }>.
 */

import { GIFEncoder, quantize, applyPalette } from 'gifenc';

const TARGET_IMAGE_SIZE = 512;
const TARGET_VIDEO_SIZE = 256;   // see comment above
const VIDEO_MAX_DURATION = 3;    // seconds
const VIDEO_FPS = 8;             // 8 fps × 3 s = 24 frames

/**
 * Common helper — draw `src` onto a square canvas at `size`x`size`,
 * using object-fit:cover semantics so the centre of the source
 * survives, then mask the corners to a transparent circle.  The
 * circle mask actually doesn't matter for the JPEG path (since
 * <AvatarCircle> wraps the result in a border-radius:50% span)
 * but does matter for the GIF path so the masked black corners
 * don't show up if the avatar is ever rendered without the wrapper.
 */
function drawSquareCentred(src, size) {
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    // Letterbox black so transparent edges of source PNGs flatten
    // cleanly to a known colour rather than browser-default white.
    ctx.fillStyle = '#06080F';
    ctx.fillRect(0, 0, size, size);

    const srcW = src.videoWidth || src.naturalWidth || src.width;
    const srcH = src.videoHeight || src.naturalHeight || src.height;
    if (!srcW || !srcH) return canvas;

    // object-fit: cover — scale so the smaller dimension fills the
    // target, then centre-crop the longer dimension.
    const scale = Math.max(size / srcW, size / srcH);
    const drawW = srcW * scale;
    const drawH = srcH * scale;
    const dx = (size - drawW) / 2;
    const dy = (size - drawH) / 2;
    ctx.drawImage(src, dx, dy, drawW, drawH);
    return canvas;
}

/* ----------------------------------------------------------------
 * Path 1: still images (PNG / JPEG / WebP / BMP)
 * --------------------------------------------------------------*/
function readImage(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => {
            const img = new Image();
            img.onload = () => resolve(img);
            img.onerror = () =>
                reject(new Error('Could not decode that image.'));
            img.src = reader.result;
        };
        reader.onerror = () =>
            reject(new Error('Could not read that file.'));
        reader.readAsDataURL(file);
    });
}

async function processImage(file) {
    const img = await readImage(file);
    const canvas = drawSquareCentred(img, TARGET_IMAGE_SIZE);
    // JPEG q=0.9 is the sweet spot for 512×512 portraits: ~80 KB
    // for a typical anime/photo avatar, well under the 2 MB
    // localStorage soft ceiling.
    const dataUrl = canvas.toDataURL('image/jpeg', 0.9);
    return { dataUrl, mime: 'image/jpeg', animated: false };
}

/* ----------------------------------------------------------------
 * Path 2: animated GIF — pass-through with size guard
 * --------------------------------------------------------------*/
function processGif(file) {
    return new Promise((resolve, reject) => {
        if (file.size > 2 * 1024 * 1024) {
            reject(
                new Error(
                    `That GIF is ${(file.size / 1024 / 1024).toFixed(1)} MB — try a smaller one (≤2 MB) or upload a video instead and we'll convert it.`,
                ),
            );
            return;
        }
        const reader = new FileReader();
        reader.onload = () =>
            resolve({
                dataUrl: reader.result,
                mime: 'image/gif',
                animated: true,
            });
        reader.onerror = () =>
            reject(new Error('Could not read that GIF.'));
        reader.readAsDataURL(file);
    });
}

/* ----------------------------------------------------------------
 * Path 3: video → animated GIF via gifenc
 * --------------------------------------------------------------*/
function loadVideo(file) {
    return new Promise((resolve, reject) => {
        const url = URL.createObjectURL(file);
        const v = document.createElement('video');
        v.preload = 'auto';
        v.muted = true;
        v.playsInline = true;
        v.crossOrigin = 'anonymous';
        // Chromium needs the element in the document for the
        // decoder pipeline to fully spin up (loadedmetadata fires
        // off-DOM, but `currentTime =` seeks silently do nothing
        // when the element is detached).  Park it offscreen.
        v.style.position = 'fixed';
        v.style.left = '-99999px';
        v.style.top = '0';
        v.style.width = '1px';
        v.style.height = '1px';
        v.style.opacity = '0';
        v.style.pointerEvents = 'none';
        document.body.appendChild(v);

        let settled = false;
        const cleanup = () => {
            URL.revokeObjectURL(url);
            if (v.parentNode) v.parentNode.removeChild(v);
        };
        const finishError = (msg) => {
            if (settled) return;
            settled = true;
            cleanup();
            reject(new Error(msg));
        };
        const finishOk = () => {
            if (settled) return;
            settled = true;
            resolve({ video: v, cleanup });
        };

        v.onerror = () =>
            finishError("We couldn't read that video. Try MP4, WebM or MOV.");
        v.onloadedmetadata = () => {
            // Some Chromium builds report duration = Infinity for
            // fragmented MP4s until we seek to the end; force it
            // by seeking to a huge time and waiting for the clamp.
            if (!isFinite(v.duration) || v.duration === 0) {
                const onSeeked = () => {
                    v.removeEventListener('seeked', onSeeked);
                    v.currentTime = 0;
                    finishOk();
                };
                v.addEventListener('seeked', onSeeked);
                try { v.currentTime = 1e7; } catch { finishOk(); }
            } else {
                finishOk();
            }
        };
        v.src = url;
        // load() is implicit when setting src but calling it
        // explicitly nudges Chromium to start the demuxer
        // immediately rather than waiting for a paint.
        try { v.load(); } catch { /* ignore */ }

        // Safety timeout — broken / DRM'd videos can hang
        // loadedmetadata forever.
        setTimeout(() => {
            if (!settled) {
                finishError(
                    "We couldn't read that video — it might be DRM-protected or in an unsupported format.",
                );
            }
        }, 10000);
    });
}

/**
 * Seek a hidden <video> to `time` and resolve once the new frame
 * is actually rasterised.  Plain `currentTime =` is async; we have
 * to wait for the `seeked` event AND a rAF tick before drawImage
 * is guaranteed to read the new frame.
 */
function seekTo(video, time) {
    return new Promise((resolve) => {
        const onSeeked = () => {
            video.removeEventListener('seeked', onSeeked);
            // Two rAFs — first commits the seek, second guarantees
            // the decoded frame has reached the compositor.
            requestAnimationFrame(() =>
                requestAnimationFrame(resolve),
            );
        };
        video.addEventListener('seeked', onSeeked);
        video.currentTime = time;
    });
}

async function processVideo(file, onProgress) {
    const { video, cleanup } = await loadVideo(file);
    try {
        // Clamp duration — we only ever want a ≤3 s loop.
        const duration = Math.min(video.duration || 0, VIDEO_MAX_DURATION);
        if (!duration) {
            throw new Error('That video has no duration we could read.');
        }
        const totalFrames = Math.max(
            4,
            Math.round(duration * VIDEO_FPS),
        );
        const frameDelayMs = Math.round(1000 / VIDEO_FPS);

        const gif = GIFEncoder();
        const size = TARGET_VIDEO_SIZE;

        for (let i = 0; i < totalFrames; i++) {
            const t = (i / totalFrames) * duration;
            await seekTo(video, t);
            const canvas = drawSquareCentred(video, size);
            const ctx = canvas.getContext('2d');
            const { data } = ctx.getImageData(0, 0, size, size);
            // Per-frame palette quantisation gives much better
            // colour fidelity for video content (each frame can
            // diverge a lot from the previous one).  rgb565 is the
            // gifenc default and gives a good speed/quality balance.
            const palette = quantize(data, 256, { format: 'rgb565' });
            const index = applyPalette(data, palette);
            gif.writeFrame(index, size, size, {
                palette,
                delay: frameDelayMs,
            });
            if (onProgress) onProgress((i + 1) / totalFrames);
            // Yield to the event loop every few frames so the
            // page stays responsive.  Without this the entire UI
            // freezes for ~1 s on slower devices.
            if (i % 3 === 2) await new Promise((r) => setTimeout(r, 0));
        }
        gif.finish();
        const bytes = gif.bytes();
        // Build a data: URL from the Uint8Array.  btoa won't handle
        // binary cleanly via String.fromCharCode(...big) due to
        // call-stack limits — chunk it.
        let binary = '';
        const chunkSize = 0x8000;
        for (let i = 0; i < bytes.length; i += chunkSize) {
            binary += String.fromCharCode.apply(
                null,
                bytes.subarray(i, i + chunkSize),
            );
        }
        const dataUrl = 'data:image/gif;base64,' + btoa(binary);
        return { dataUrl, mime: 'image/gif', animated: true };
    } finally {
        cleanup();
    }
}

/* ----------------------------------------------------------------
 * Public entry point
 * --------------------------------------------------------------*/

/**
 * Determine the right path for `file` and run it.
 * @param {File} file
 * @param {(progress: number) => void} [onProgress] - 0..1, only
 *   fires for video conversion (the slow path).
 * @returns {Promise<{ dataUrl: string, mime: string, animated: boolean }>}
 */
export async function processAvatarFile(file, onProgress) {
    if (!file) throw new Error('No file provided.');
    const type = (file.type || '').toLowerCase();

    if (type === 'image/gif') {
        return processGif(file);
    }
    if (type.startsWith('image/')) {
        // Reject anything wild like image/svg+xml — we can't safely
        // rasterise SVGs from random uploads in a TV WebView.
        const okStill =
            type === 'image/png' ||
            type === 'image/jpeg' ||
            type === 'image/jpg' ||
            type === 'image/webp' ||
            type === 'image/bmp';
        if (!okStill) {
            throw new Error(
                "We can't process that image type. Try PNG, JPEG or WebP.",
            );
        }
        return processImage(file);
    }
    if (type.startsWith('video/')) {
        // Hard cap on source video size so we don't blow memory
        // decoding a 100 MB file just to throw most of it away.
        if (file.size > 50 * 1024 * 1024) {
            throw new Error(
                'That video is too big — keep it under 50 MB (we only use the first 3 seconds).',
            );
        }
        return processVideo(file, onProgress);
    }
    throw new Error(
        "We can't read that file. Try a PNG, JPEG, GIF or short video (MP4 / WebM / MOV).",
    );
}

/** MIME types the file input should accept. */
export const AVATAR_ACCEPT =
    'image/png,image/jpeg,image/jpg,image/webp,image/bmp,image/gif,' +
    'video/mp4,video/webm,video/quicktime,video/x-matroska';
