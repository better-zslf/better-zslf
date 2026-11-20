#!/usr/bin/env node
/**
 * ardot-oauth-proxy — local Streamable-HTTP fan-through that injects a fresh
 * Ardot OAuth bearer token on every request.
 *
 * Why this exists: `@deepseek-ai/dsh-mcp-client` has no OAuth support and its
 * `headers` are evaluated ONCE at plugin activation (cordis-loader
 * `interpolate()` runs on the `internal/config` hook). So a token baked into a
 * `!!js` header goes stale after `expires_in`. This proxy re-reads/refreshes the
 * token per request, so DSH never holds a stale bearer.
 *
 *   DSH (mcp-client, streamable-http)  ->  http://127.0.0.1:48214/mcp
 *                                          |  (this process)
 *                                          v  https://ardot.tencent.com/mcp
 *
 * Usage:  node ardot-oauth-proxy.mjs [--port 48214]
 *
 * Reads  C:/Users/bx233/.dsh/ardot-mcp/credentials.json
 * Refreshes via the same RFC 6749 refresh_token grant ardot-auth.mjs uses and
 * writes the rotated credential + access-token.txt back atomically.
 */
import { createServer } from 'node:http'
import { readFileSync, writeFileSync, renameSync } from 'node:fs'

const ISSUER = 'https://ardot.tencent.com'
const RESOURCE = 'https://ardot.tencent.com/mcp'
const UPSTREAM = `${ISSUER}/mcp`
const STORE_DIR = 'C:/Users/bx233/.dsh/ardot-mcp'
const CREDENTIALS = `${STORE_DIR}/credentials.json`
const TOKEN_FILE = `${STORE_DIR}/access-token.txt`
const PORT = Number(process.env.ARDOT_PROXY_PORT ?? 48214)
/** Refresh when the token expires within this window. */
const REFRESH_SKEW_MS = 5 * 60 * 1000

let cache = null
let inflight = null

function load() {
  try { return JSON.parse(readFileSync(CREDENTIALS, 'utf8')) } catch { return {} }
}

function save(store) {
  const tmp = `${CREDENTIALS}.tmp`
  writeFileSync(tmp, `${JSON.stringify(store, null, 2)}\n`)
  renameSync(tmp, CREDENTIALS)
  if (typeof store.access_token === 'string' && store.access_token) {
    writeFileSync(`${TOKEN_FILE}.tmp`, `${store.access_token}\n`)
    renameSync(`${TOKEN_FILE}.tmp`, TOKEN_FILE)
  }
}

function fresh(store) {
  if (!store.access_token) return false
  const at = store.expires_at ? new Date(store.expires_at).getTime() : 0
  return at - Date.now() > REFRESH_SKEW_MS
}

async function refresh(store) {
  if (!store.refresh_token || !store.client_id) {
    throw new Error('no refresh_token/client_id; run ardot-auth.mjs login')
  }
  const response = await fetch(`${ISSUER}/oauth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'refresh_token',
      refresh_token: store.refresh_token,
      client_id: store.client_id,
      resource: RESOURCE,
    }).toString(),
  })
  const text = await response.text()
  if (!response.ok) throw new Error(`refresh failed HTTP ${response.status}: ${text.slice(0, 200)}`)
  const json = JSON.parse(text)
  if (!json.access_token) throw new Error(`refresh returned no access_token: ${text.slice(0, 200)}`)
  const next = {
    ...store,
    scope: json.scope ?? store.scope,
    token_type: json.token_type ?? 'Bearer',
    access_token: json.access_token,
    refresh_token: json.refresh_token ?? store.refresh_token,
    expires_at: json.expires_in ? new Date(Date.now() + json.expires_in * 1000).toISOString() : null,
    updated_at: new Date().toISOString(),
  }
  delete next.pending
  save(next)
  console.log(`[proxy] token refreshed, valid until ${next.expires_at}`)
  return next
}

/** Return a token that is valid now, refreshing/seeding from disk as needed. */
async function token() {
  if (cache && fresh(cache)) return cache.access_token
  const store = load()
  if (fresh(store)) { cache = store; return store.access_token }
  // Another request may already be refreshing; serialize so the rotated
  // refresh_token is never spent twice (Ardot rotates it).
  inflight ??= refresh(store).then((next) => { cache = next; return next.access_token })
    .finally(() => { inflight = null })
  return await inflight
}

/** Headers the transport owns; never copied back to the client. */
const HOP_BY_HOP = new Set([
  'authorization', 'connection', 'keep-alive', 'transfer-encoding',
  'upgrade', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer',
  'host', 'content-length',
])

const server = createServer(async (request, response) => {
  const started = Date.now()
  const label = `${request.method} ${request.url}`
  try {
    // Only the MCP endpoint exists; anything else leaks nothing.
    if (!request.url?.startsWith('/mcp')) {
      response.writeHead(404, { 'Content-Type': 'text/plain' }).end('not found')
      return
    }
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const body = chunks.length > 0 ? Buffer.concat(chunks) : undefined

    const headers = new Headers()
    for (const [key, value] of Object.entries(request.headers)) {
      if (value === undefined || HOP_BY_HOP.has(key.toLowerCase())) continue
      if (Array.isArray(value)) for (const item of value) headers.append(key, item)
      else headers.set(key, value)
    }
    headers.set('Authorization', `Bearer ${await token()}`)

    const upstream = await fetch(UPSTREAM, { method: request.method, headers, body, signal: AbortSignal.timeout(600_000) })

    // Copy status + headers (drop hop-by-hop), then stream the body through so
    // SSE progress events keep flowing instead of buffering.
    const out = new Headers()
    for (const [key, value] of upstream.headers) {
      if (HOP_BY_HOP.has(key.toLowerCase())) continue
      out.set(key, value)
    }
    response.writeHead(upstream.status, Object.fromEntries(out))
    console.log(`[proxy] ${label} -> ${upstream.status} (${Date.now() - started}ms)`)
    if (upstream.body === null) { response.end(); return }
    for await (const chunk of upstream.body) response.write(chunk)
    response.end()
  } catch (error) {
    console.error(`[proxy] ${label} FAILED: ${error instanceof Error ? error.message : String(error)}`)
    if (!response.headersSent) response.writeHead(502, { 'Content-Type': 'text/plain' })
    response.end(`proxy error: ${error instanceof Error ? error.message : String(error)}`)
  }
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[proxy] listening http://127.0.0.1:${PORT}/mcp -> ${UPSTREAM}`)
  console.log(`[proxy] credentials: ${CREDENTIALS}`)
})
