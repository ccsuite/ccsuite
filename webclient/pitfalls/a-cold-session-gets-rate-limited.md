# A cold session gets "Rate limited" on the chat endpoint (found 2026-11-06)

## Symptom

Requests to the claude.ai chat completion endpoint (`POST /api/organizations/<org>/chat_conversations/<id>/completion`) from `webclient` came back `HTTP 429 {"error":{"message":"Rate limited. Please try again later.","type":"rate_limit_error"}}`, on a free account that was not exhausted: the same account answered at the same moment in a real browser tab and through ClewdR. The pattern was erratic, not a size limit: roughly every other request failed, whatever the prompt size (500 to 45,000 characters), shape (inline `prompt` or an attachment), Referer, or spacing (4 s or 70 s). Once the proxy's own circuit breaker had seen three failures it refused locally (`HALF_OPEN`), which made it look like a lasting ban. It was not.

## Cause

The client sent only `Cookie: sessionKey=...`, cold, on every request. A browser and ClewdR first call `GET /api/bootstrap`, which hands back the session's Cloudflare cookies (`__cf_bm`, `_cfuvid`), and send them along. Without them the edge throttles the request.

A second, smaller fault: the libcurl-impersonate profile's default headers are shaped like a page navigation (`Upgrade-Insecure-Requests`, `Sec-Fetch-User`, `Cache-Control: max-age=0`, navigation `Sec-Fetch-*`), but these are `fetch()`-style JSON calls. They now go out as `Sec-Fetch-Dest: empty`, `Sec-Fetch-Mode: cors`, `Sec-Fetch-Site: same-origin`, with the three navigation-only headers removed.

## Fix (webclient `WebHttp`)

- `SessionCookies` (interceptor): before each new chat (`POST .../chat_conversations`) it calls `GET /api/bootstrap`, keeps the `Set-Cookie` pairs it returns, and sends them with the chat's requests; it also absorbs cookies from later responses and re-bootstraps after 20 minutes. A jar reused across chats is not enough: a fresh bootstrap at the start of each chat is what made every request pass (6 of 6, at 500, 5,000 and 44,827 characters, twice; before: about half failed).
- `FetchStyleHeaders` (interceptor): the fetch-style headers above.
- Both apply only when libcurl-impersonate is in use (`WebHttp.curl()`); with plain OkHttp nothing changes.

## How it was found, so it can be done again

- Do not guess from the 429. Replay the identical request from a real browser tab through cdpgate (`fetch` inside the page): HTTP 200 proved the request and the account were fine.
- Send the same request through a second independent client (ClewdR with the same cookie): 200. That puts the fault in our client.
- Ask a fingerprint echo (`tls.peet.ws/api/all`) through `WebHttp.client()`: the TLS and HTTP/2 fingerprint was a proper Chrome 152, but the headers were navigation-shaped.
- Then change one thing at a time in a small probe (`JavaClientProbe` in webproxy's tests) and repeat each case several times: a single success or failure means nothing here, the failures are intermittent.
- Chats should be created temporary (`is_temporary: true`) while testing so the account's chat list does not fill up; `webproxy` now defaults to temporary chats.

## Second finding (2026-11-06, PRP 74): a follow-up in a reused chat needs a fresh bootstrap too

Symptom: with ONE `CCAPIClient` (one cookie jar) the first message of a chat answered and every follow-up in the same chat (with `parent_message_uuid`) was refused with the same `HTTP 429 Rate limited`, every time (12 of 12). The probe that built a new HTTP client per call (`FreeGauge.turn`, so a bootstrap before every message) answered every follow-up (12 of 12), which looked like "the proxy path is different".

Cause: `SessionCookies` bootstrapped at the start of a chat only, and then reused that jar. The web wants fresh cookies for every message of a chat. Not absorbing the cookies of the replies made no difference; a bootstrap right before each message did (9 of 9, then 12 of 12 with the fix).

Fix: `SessionCookies` counts the messages sent with a jar; the second and later message of a jar is preceded by a bootstrap (the first one after a chat start is not, so a new chat still costs one). Test: `SessionCookiesTest` (fails on the old interceptor, passes on the new one); live: `ReuseProbe` in webproxy's tests, `proxy` mode, before 0 of 12 follow-ups answered, after 12 of 12.
