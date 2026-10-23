// ===========================================
// MIXAMO BATCH DOWNLOADER SCRIPT (UPDATED)
// ===========================================
// Instructions:
// 1. Login to Mixamo.com
// 2. Open Developer Tools (F12) -> Console.
// 3. IMPORTANT: If you see a "Forbidden" error, it means we couldn't find the token automatically.
//    In that case, go to the "Network" tab, click on any request (like 'products'), look at "Request Headers",
//    copy the value of "Authorization" (starts with "Bearer ..."), and paste it below where it says MANUAL_TOKEN.
// ===========================================

(async function () {
    console.clear();
    console.log("%c🚀 Starting Mixamo Downloader...", "color: #00ff88; font-weight: bold; font-size: 14px;");

    // --- CONFIGURATION ---
    const CONFIG = {
        startPage: 1,
        endPage: 5,           // Change this to download more pages
        format: 'fbx7',       // fbx7, fbx6, dae, bvh, etc.
        skin: 'false',        // 'true' = with skin, 'false' = without skin
        fps: 30,
        reduceKeyframes: 'false',
        concurrency: 3,
        manuallySetToken: ''  // PASTE TOKEN HERE IF AUTO-DETECT FAILS (e.g. 'Bearer eyJ...')
    };

    // --- HELPERS ---
    const sleep = (ms) => new Promise(r => setTimeout(r, ms));

    // Get Auth Token
    function getAuthToken() {
        // 1. Check manual override
        if (CONFIG.manuallySetToken) return CONFIG.manuallySetToken.replace('Bearer ', '');

        // 2. Try LocalStorage (standard key)
        try {
            const token = localStorage.getItem('access_token');
            if (token) return token;
        } catch (e) { }

        // 3. Try searching cookie (sometimes stored there)
        const match = document.cookie.match(/access_token=([^;]+)/);
        if (match) return match[1];

        return '';
    }

    // Get Active Character ID
    function getCharacterId() {
        try {
            const persisted = localStorage.getItem('mixamo_client_state');
            if (persisted) {
                const data = JSON.parse(persisted);
                if (data?.characters?.selected) return data.characters.selected;
            }
        } catch (e) { }
        return '1e194a62-9860-449c-9f88-41071475727e'; // Fallback Y-Bot
    }

    // --- STATE ---
    let bearerToken = getAuthToken();
    let characterId = getCharacterId();

    if (!bearerToken) {
        // Prompt user if token is missing
        const userInput = prompt("⚠️ Could not auto-detect Access Token.\n\nPlease go to the Network tab, find a request to 'mixamo.com/api', copy the 'Authorization' header (Bearer ...), and paste it here:");
        if (userInput) {
            bearerToken = userInput.replace('Bearer ', '').trim();
        } else {
            console.error("❌ No token provided. Script aborted.");
            return;
        }
    }

    console.log(`%c🔑 Token found/provided.`, "color: #00d4ff");
    console.log(`Using Character ID: ${characterId}`);

    // --- API ---

    // 1. Fetch List of Animations
    async function fetchPage(page) {
        console.log(`%c📄 Fetching Page ${page}...`, "color: #00d4ff");
        const url = `https://www.mixamo.com/api/v1/products?page=${page}&limit=96&order=relevance&type=Motion%2CMotionPack`;

        const res = await fetch(url, {
            headers: {
                'Authorization': `Bearer ${bearerToken}`,
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest' // Sometimes helps avoiding 403
            }
        });

        if (!res.ok) throw new Error(`Failed to fetch page ${page}: ${res.status} ${res.statusText}`);
        return await res.json();
    }

    // 2. Request Animation Export
    async function requestExport(animId, name) {
        const exportUrl = `https://www.mixamo.com/api/v1/animations/${animId}/export`;

        const payload = {
            character_id: characterId,
            product_name: name,
            type: "Motion",
            preferences: {
                format: CONFIG.format,
                skin: CONFIG.skin,
                fps: String(CONFIG.fps),
                reduce_keyframes: CONFIG.reduceKeyframes
            }
        };

        const res = await fetch(exportUrl, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${bearerToken}`,
                'Content-Type': 'application/json',
                'Accept': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: JSON.stringify(payload)
        });

        if (!res.ok) {
            if (res.status === 429) {
                console.warn(`⏳ Rate limited on ${name}. Waiting 5s...`);
                await sleep(5000);
                return requestExport(animId, name);
            }
            throw new Error(`Export failed for ${name}: ${res.status}`);
        }

        return await res.json();
    }

    // 3. Monitor Job Status
    async function monitorJob(jobId, name) {
        const monitorUrl = `https://www.mixamo.com/api/v1/products/monitor/${jobId}`;

        for (let i = 0; i < 60; i++) {
            await sleep(2000);
            const res = await fetch(monitorUrl, {
                headers: { 'Authorization': `Bearer ${bearerToken}` }
            });

            if (!res.ok) continue;
            const data = await res.json();

            if (data.status === 'completed') return data.job_result;
            if (data.status === 'failed') throw new Error(`Job failed for ${name}`);
        }
        throw new Error(`Timeout waiting for ${name}`);
    }

    // 4. Download File
    function downloadFile(url, filename) {
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        console.log(`%c✅ Downloaded: ${filename}`, "color: #00ff00");
    }

    // --- WORKER ---
    async function processAnimation(anim) {
        const name = anim.name || anim.description || 'animation';
        const filename = `${name.replace(/[^a-z0-9]/gi, '_')}.fbx`;

        try {
            const jobData = await requestExport(anim.id, name);
            if (!jobData.job_id) throw new Error('No Job ID returned');
            const downloadUrl = await monitorJob(jobData.job_id, name);
            downloadFile(downloadUrl, filename);
        } catch (err) {
            console.error(`❌ Error processing ${name}:`, err.message);
        }
    }

    // --- MAIN LOOP ---
    try {
        const queue = [];

        for (let p = CONFIG.startPage; p <= CONFIG.endPage; p++) {
            const data = await fetchPage(p);
            const animations = data.results || data.data || [];

            if (animations.length === 0) {
                console.log("No more animations found.");
                break;
            }

            console.log(`Found ${animations.length} animations on page ${p}. Adding to queue...`);

            for (const anim of animations) {
                while (queue.length >= CONFIG.concurrency) {
                    await Promise.race(queue);
                }

                const p = processAnimation(anim).then(() => {
                    queue.splice(queue.indexOf(p), 1);
                });
                queue.push(p);
                await sleep(500);
            }
        }
        await Promise.all(queue);
        console.log("%c🎉 All downloads complete!", "color: #00ff88; font-size: 16px; font-weight: bold;");

    } catch (err) {
        console.error("Critical Error:", err);
    }

})();
