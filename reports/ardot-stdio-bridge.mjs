#!/usr/bin/env node
/**
 * ardot-stdio-bridge — a stdio MCP server that forwards every JSON-RPC message
 * to Ardot's Streamable-HTTP endpoint with a freshly refreshed OAuth bearer.
 *
 * Why: `@deepseek-ai/dsh-mcp-client` cannot do OAuth and evaluates its static
 * `headers` only once, at plugin activation. Instead of baking a token, point a
 * `transport: stdio` row at THIS file: DSH then owns the process lifecycle
 * (spawn / reconnect / dispose), and the token is refreshed per request, so
 * DSH never sends a stale bearer and no port or background service is needed.
 *
 *   DSH (mcp-client, stdio) -> this process -> https://ardot.tencent.com/mcp
 *
 * Wire format: newline-delimited JSON-RPC on stdin/stdout (the MCP stdio
 * framing). Notifications get no reply. Every request gets exactly one reply.
 *
 * Reads/rotates C:/Users/bx233/.dsh/ardot-mcp/credentials.json.
 * Env: ARDOT_CREDENTIALS (override path), ARDOT_REFRESH_SKEW_MS.
 */
import { readFileSync, writeFileSync, renameSync, appendFileSync } from 'node:fs'
import { createHash } from 'node:crypto'

const ISSUER = 'https://ardot.tencent.com'
const RESOURCE = 'https://ardot.tencent.com/mcp'
const UPSTREAM = `${ISSUER}/mcp`
const STORE_DIR = 'C:/Users/bx233/.dsh/ardot-mcp'
const CREDENTIALS = process.env.ARDOT_CREDENTIALS ?? `${STORE_DIR}/credentials.json`
const TOKEN_FILE = `${STORE_DIR}/access-token.txt`
const REFRESH_SKEW_MS = Number(process.env.ARDOT_REFRESH_SKEW_MS ?? 5 * 60 * 1000)

/** Diagnostics go to stderr only — stdout carries the protocol. */
function log(message) {
  try { appendFileSync(`${STORE_DIR}/bridge.log`, `${new Date().toISOString()} ${message}\n`) } catch {}
  process.stderr.write(`[ardot-bridge] ${message}\n`)
}

function load() {
  try { return JSON.parse(readFileSync(CREDENTIALS, 'utf8')) } catch { return {} }
}

function save(store) {
  writeFileSync(`${CREDENTIALS}.tmp`, `${JSON.stringify(store, null, 2)}\n`)
  renameSync(`${CREDENTIALS}.tmp`, CREDENTIALS)
  if (typeof store.access_token === 'string' && store.access_token) {
    writeFileSync(`${TOKEN_FILE}.tmp`, `${store.access_token}\n`)
    renameSync(`${TOKEN_FILE}.tmp`, TOKEN_FILE)
  }
}

function stillFresh(store) {
  if (!store.access_token) return false
  const at = store.expires_at ? new Date(store.expires_at).getTime() : 0
  return at - Date.now() > REFRESH_SKEW_MS
}

async function refresh(store) {
  if (!store.refresh_token || !store.client_id) throw new Error('no refresh_token/client_id; run ardot-auth.mjs login')
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
    // Ardot rotates the refresh_token; persist the new one or the next refresh
    // fails and the user has to re-run login.
    refresh_token: json.refresh_token ?? store.refresh_token,
    expires_at: json.expires_in ? new Date(Date.now() + json.expires_in * 1000).toISOString() : null,
    updated_at: new Date().toISOString(),
  }
  delete next.pending
  save(next)
  log(`token refreshed, valid until ${next.expires_at}`)
  return next
}

let cache = null
let inflight = null

async function bearer() {
  if (cache && stillFresh(cache)) return cache.access_token
  const store = load()
  if (stillFresh(store)) { cache = store; return store.access_token }
  inflight ??= refresh(store).then((next) => { cache = next; return next.access_token }).finally(() => { inflight = null })
  return await inflight
}

/** Headers the transport owns; never forwarded verbatim. */
const STRIP = new Set(['authorization', 'host', 'content-length', 'connection', 'accept-encoding'])

let sessionId

async function forward(message) {
  const headers = new Headers({
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
  })
  if (sessionId) headers.set('mcp-session-id', sessionId)
  headers.set('Authorization', `Bearer ${await bearer()}`)

  const response = await fetch(UPSTREAM, {
    method: 'POST',
    headers,
    body: JSON.stringify(message),
    signal: AbortSignal.timeout(1_800_000),
  })
  const sid = response.headers.get('mcp-session-id')
  if (sid) sessionId = sid

  if (response.status === 401) {
    // A revoked/expired token that the clock still thought was valid: drop the
    // cache so the next attempt refreshes instead of replaying the dead token.
    cache = null
    throw new Error('upstream returned 401 (token rejected); cache cleared for refresh')
  }
  const text = await response.text()
  if (!response.ok) throw new Error(`upstream HTTP ${response.status}: ${text.slice(0, 300)}`)
  return { text, contentType: response.headers.get('content-type') ?? '' }
}

/** Decode a Streamable-HTTP body: SSE frames or a bare JSON document. */
function decode({ text, contentType }) {
  if (text.trim() === '') return []
  if (contentType.includes('text/event-stream') || text.startsWith('event:') || text.startsWith('data:')) {
    const out = []
    for (const frame of text.split(/\r?\n\r?\n/)) {
      const data = frame.split(/\r?\n/).filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n')
      if (data === '' || data === '[DONE]') continue
      try { out.push(JSON.parse(data)) } catch { log(`unparseable SSE frame: ${data.slice(0, 120)}`) }
    }
    return out
  }
  try {
    const value = JSON.parse(text)
    return Array.isArray(value) ? value : [value]
  } catch {
    log(`unparseable JSON body: ${text.slice(0, 200)}`)
    return []
  }
}

function emit(value) {
  process.stdout.write(`${JSON.stringify(value)}\n`)
}

async function handle(message) {
  const isRequest = typeof message.id === 'number' || typeof message.id === 'string'
  try {
    const replies = decode(await forward(message))
    for (const reply of replies) emit(reply)
    // A request must always be answered; a silently dropped reply would hang
    // the caller until its toolCallTimeoutMs.
    if (isRequest && replies.length === 0) {
      emit({ jsonrpc: '2.0', id: message.id, error: { code: -32603, message: 'upstream returned no reply' } })
    }
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error)
    log(`${message.method ?? 'response'} failed: ${detail}`)
    if (isRequest) emit({ jsonrpc: '2.0', id: message.id, error: { code: -32603, message: detail } })
  }
}

// Serialize forwards per process: the upstream session is shared state, and
// out-of-order initialize/initialized would break the handshake.
let chain = Promise.resolve()
let buffer = ''
process.stdin.setEncoding('utf8')
process.stdin.on('data', (chunk) => {
  buffer += chunk
  let index
  while ((index = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, index).trim()
    buffer = buffer.slice(index + 1)
    if (line === '') continue
    let message
    try { message = JSON.parse(line) } catch { log(`unparseable stdin line: ${line.slice(0, 120)}`); continue }
    chain = chain.then(() => handle(message))
  }
})
process.stdin.on('end', () => { chain = chain.then(() => process.exit(0)) })
process.on('SIGTERM', () => process.exit(0))
process.on('SIGINT', () => process.exit(0))

log(`started, upstream=${UPSTREAM}, credentials=${CREDENTIALS}`)
