// ==UserScript==
// @name         Claude Chat Exporter (all chats, all organizations)
// @namespace    https://github.com/xyz-jphil
// @version      1.2
// @description  One click on claude.ai saves every conversation of every organization of the logged-in account into a folder you pick, one JSON file per chat. Resumable.
// @match        https://claude.ai/*
// @grant        none
// ==/UserScript==

(function () {
    'use strict';

    const PAGE = 100;          // conversations per list request
    const CONCURRENCY = 3;     // parallel conversation fetches
    const GAP_MS = 250;        // pause after each fetch, per worker
    let stopRequested = false;
    let running = false;

    const sleep = ms => new Promise(r => setTimeout(r, ms));

    // ---------- API ----------
    async function api(path, tries = 6) {
        for (let i = 0; i < tries; i++) {
            const res = await fetch(path, { credentials: 'include', headers: { 'Accept': 'application/json' } });
            if (res.ok) return res.json();
            if (res.status === 429 || res.status >= 500) {
                const wait = (Number(res.headers.get('retry-after')) || 0) * 1000 || Math.min(2000 * 2 ** i, 60000);
                log(`HTTP ${res.status} on ${path.slice(0, 70)}, waiting ${Math.round(wait / 1000)}s`);
                await sleep(wait);
                continue;
            }
            let why = '';
            try { why = (await res.json()).error.message || ''; } catch (e) { /* body not JSON */ }
            throw new Error(`HTTP ${res.status}${why ? ' - ' + why : ''}`);   // 403 here = locked by the plan, not retryable
        }
        throw new Error('gave up after retries: ' + path);
    }

    async function listConversations(orgId) {
        const seen = new Map();
        for (let offset = 0; ; offset += PAGE) {
            const page = await api(`/api/organizations/${orgId}/chat_conversations?limit=${PAGE}&offset=${offset}`);
            const before = seen.size;
            for (const c of page) seen.set(c.uuid, c);
            setStatus(`Listing conversations… ${seen.size}`);
            // stop on a short page, or if the server ignored offset and sent nothing new
            if (page.length < PAGE || seen.size === before) break;
        }
        return [...seen.values()];
    }

    const fetchConversation = (orgId, id) =>
        api(`/api/organizations/${orgId}/chat_conversations/${id}?tree=True&rendering_mode=messages&render_all_tools=true`);

    // ---------- naming ----------
    const safe = (s, max = 60) =>
        (s || '').replace(/[\\/:*?"<>|\x00-\x1f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max).trim() || 'untitled';
    const day = c => (c.created_at || '').slice(0, 10) || 'undated';
    const UUID_TAIL = /_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.json$/;

    // ---------- sinks: where files go ----------
    // Folder sink streams each file to disk as it arrives (Chromium: Chrome, Vivaldi, Edge).
    async function folderSink() {
        const root = await window.showDirectoryPicker({ mode: 'readwrite' });
        return {
            async org(label) {
                const dir = await root.getDirectoryHandle(label, { create: true });
                const have = new Set();
                for await (const [name] of dir.entries()) {
                    const m = UUID_TAIL.exec(name);
                    if (m) have.add(m[1]);
                }
                return {
                    have,
                    async write(name, text) {
                        const fh = await dir.getFileHandle(name, { create: true });
                        const w = await fh.createWritable();
                        await w.write(text);
                        await w.close();
                    },
                };
            },
        };
    }

    // Fallback (Firefox etc.): one browser download per file, no resume.
    function downloadSink() {
        return {
            async org(label) {
                return {
                    have: new Set(),
                    async write(name, text) {
                        const a = document.createElement('a');
                        a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
                        a.download = `${label}__${name}`;
                        document.body.appendChild(a);
                        a.click();
                        a.remove();
                        setTimeout(() => URL.revokeObjectURL(a.href), 30000);
                    },
                };
            },
        };
    }

    // ---------- export ----------
    async function exportOrg(org, sink, index, total) {
        const label = `${safe(org.name, 40)}_${org.uuid.slice(0, 8)}`;
        setStatus(`Organization ${index}/${total}: ${org.name}`);
        let convs = await listConversations(org.uuid);
        if (window.__ccxLimit) convs = convs.slice(0, window.__ccxLimit);   // test hook
        const out = await sink.org(label);
        const manifest = [];
        let done = 0, skipped = 0, failed = 0, next = 0;

        async function worker() {
            while (!stopRequested) {
                const i = next++;
                if (i >= convs.length) return;
                const c = convs[i];
                const file = `${day(c)}_${safe(c.name)}_${c.uuid}.json`;
                try {
                    if (out.have.has(c.uuid)) {
                        skipped++;
                    } else {
                        const full = await fetchConversation(org.uuid, c.uuid);
                        await out.write(file, JSON.stringify(full, null, 2));
                        await sleep(GAP_MS);
                    }
                    manifest.push({ uuid: c.uuid, name: c.name, created_at: c.created_at, updated_at: c.updated_at, file });
                } catch (e) {
                    failed++;
                    manifest.push({ uuid: c.uuid, name: c.name, error: String(e) });
                    log(`FAILED ${c.uuid}: ${e}`);
                }
                done++;
                setStatus(`Org ${index}/${total} "${org.name}": ${done}/${convs.length}` +
                    (skipped ? `  (${skipped} already saved)` : '') + (failed ? `  (${failed} failed)` : ''));
            }
        }
        await Promise.all(Array.from({ length: CONCURRENCY }, worker));

        await out.write('index.json', JSON.stringify(
            { organization: org, exported_at: new Date().toISOString(), conversations: manifest }, null, 2));
        log(`${label}: ${done - failed - skipped} saved, ${skipped} already there, ${failed} failed${stopRequested ? ' (stopped early)' : ''}`);
    }

    async function exportAll() {
        if (running) return;
        let sink;
        try {
            sink = window.__ccxSink || (window.showDirectoryPicker ? await folderSink() : downloadSink());   // __ccxSink: test hook
        } catch (e) { return; }   // picker cancelled
        running = true; stopRequested = false;
        btn.disabled = true; stopBtn.style.display = '';
        try {
            const orgs = await api('/api/organizations');
            log(`Found ${orgs.length} organization(s): ${orgs.map(o => o.name).join(', ')}`);
            const chatOrgs = orgs.filter(o => (o.capabilities || []).includes('chat'));
            log(`${chatOrgs.length} with chat access; skipping: ${orgs.filter(o => !chatOrgs.includes(o)).map(o => o.name).join(', ') || 'none'}`);
            for (let i = 0; i < chatOrgs.length && !stopRequested; i++) {
                try { await exportOrg(chatOrgs[i], sink, i + 1, chatOrgs.length); }
                catch (e) { log(`Organization "${chatOrgs[i].name}" failed: ${e}`); }
            }
            setStatus(stopRequested ? 'Stopped. Run again to resume.' : 'Done.');
        } catch (e) {
            setStatus('Error: ' + e.message);
            log(String(e.stack || e));
        } finally {
            running = false; btn.disabled = false; stopBtn.style.display = 'none';
        }
    }

    // ---------- UI ----------
    const box = document.createElement('div');
    box.id = 'claude-chat-exporter';
    box.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:99999;font:13px system-ui,sans-serif;' +
        'background:#fff;color:#222;border:1px solid #bbb;border-radius:8px;padding:8px 10px;max-width:340px;box-shadow:0 2px 8px #0003';
    const btn = document.createElement('button');
    btn.textContent = 'Export all chats…';
    btn.style.cssText = 'cursor:pointer;padding:4px 10px';
    btn.onclick = exportAll;
    const stopBtn = document.createElement('button');
    stopBtn.textContent = 'Stop';
    stopBtn.style.cssText = 'cursor:pointer;padding:4px 10px;margin-left:6px;display:none';
    stopBtn.onclick = () => { stopRequested = true; setStatus('Stopping after current items…'); };
    const status = document.createElement('div');
    status.style.cssText = 'margin-top:6px;white-space:pre-wrap';
    box.append(btn, stopBtn, status);

    function setStatus(t) { status.textContent = t; }
    function log(t) { console.log('[claude-chat-exporter]', t); }

    const mount = () => { if (document.body && !box.isConnected) document.body.appendChild(box); };
    mount();
    new MutationObserver(mount).observe(document.documentElement, { childList: true });
})();
