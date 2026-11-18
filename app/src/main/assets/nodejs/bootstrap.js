"use strict"

const fs = require("fs")
const fsPromises = require("fs/promises")
const http = require("http")
const path = require("path")
const crypto = require("crypto")
const { fileURLToPath } = require("url")

const [
  ,
  ,
  indexPath,
  configPath,
  dataRoot,
  bridgePortText,
  token,
  runtimePath,
] = process.argv
const bridgePort = Number(bridgePortText)
const bridgeTokenHeader = "X-CatVod-Token"
let server = null

const resolvedIndexPath = path.resolve(indexPath)
const resolvedConfigPath = path.resolve(configPath || path.join(path.dirname(resolvedIndexPath), "index.config.js"))
const resolvedDataRoot = path.resolve(dataRoot)
const bundleRoot = path.dirname(resolvedIndexPath)
const configRoot = path.dirname(resolvedConfigPath)
const runtimeRoot = __dirname
const bundleTempDir = path.join(bundleRoot, "Temp")
const redirectRoot = path.join(bundleTempDir, "_redirect")

const originalMkdirSync = fs.mkdirSync.bind(fs)
const originalExistsSync = fs.existsSync.bind(fs)
originalMkdirSync(resolvedDataRoot, { recursive: true })
originalMkdirSync(redirectRoot, { recursive: true })

process.env.CATVOD_DISABLE_AUTOSTART = "1"
process.env.HOME = resolvedDataRoot
process.env.NODE_PATH = resolvedDataRoot
process.chdir(resolvedDataRoot)

const SYSTEM_READONLY_PREFIXES = ["/proc", "/dev", "/sys", "/apex", "/system", "/vendor"]

function isInsideDir(parentDir, targetPath) {
  const rel = path.relative(parentDir, targetPath)
  return rel === "" || (!rel.startsWith("..") && !path.isAbsolute(rel))
}

function isSystemReadOnlyPath(resolvedPath) {
  const normalized = resolvedPath.replace(/\\/g, "/")
  return SYSTEM_READONLY_PREFIXES.some(
    (prefix) => normalized === prefix || normalized.startsWith(`${prefix}/`),
  )
}

function toStringPath(input) {
  if (typeof input === "string") return input
  if (Buffer.isBuffer(input)) return input.toString("utf8")
  if (input instanceof URL && input.protocol === "file:") {
    try {
      return fileURLToPath(input)
    } catch (_) {
      return null
    }
  }
  return null
}

function mapRedirectPath(resolvedTarget) {
  const cleaned = resolvedTarget
    .replace(/^[A-Za-z]:[\\/]+/, "")
    .replace(/^[/\\]+/, "")
  const safeRelative = cleaned.replace(/\.\.(?=[/\\]|$)/g, "_up_")
  return path.join(redirectRoot, safeRelative || "root")
}

function resolveSandboxPath(rawInput, isWrite) {
  const str = toStringPath(rawInput)
  if (!str) return rawInput
  const resolved = path.resolve(str)

  if (isInsideDir(resolvedDataRoot, resolved) || isInsideDir(bundleTempDir, resolved)) {
    return rawInput
  }

  if (!isWrite) {
    if (
      isInsideDir(bundleRoot, resolved) ||
      isInsideDir(configRoot, resolved) ||
      isInsideDir(runtimeRoot, resolved) ||
      isSystemReadOnlyPath(resolved)
    ) {
      return rawInput
    }
  }

  const mapped = mapRedirectPath(resolved)
  if (isWrite) {
    try {
      originalMkdirSync(path.dirname(mapped), { recursive: true })
    } catch (_) {}
  }
  return mapped
}

function installFsSandbox() {
  const readSingleMethods = [
    "access", "accessSync",
    "createReadStream",
    "exists", "existsSync",
    "lstat", "lstatSync",
    "open", "openSync",
    "opendir", "opendirSync",
    "readdir", "readdirSync",
    "readFile", "readFileSync",
    "readlink", "readlinkSync",
    "realpath", "realpathSync",
    "stat", "statSync",
    "watch", "watchFile", "unwatchFile",
  ]

  const writeSingleMethods = [
    "appendFile", "appendFileSync",
    "chmod", "chmodSync",
    "chown", "chownSync",
    "createWriteStream",
    "lchmod", "lchmodSync",
    "lchown", "lchownSync",
    "mkdir", "mkdirSync",
    "mkdtemp", "mkdtempSync",
    "rm", "rmSync",
    "rmdir", "rmdirSync",
    "truncate", "truncateSync",
    "unlink", "unlinkSync",
    "utimes", "utimesSync",
    "writeFile", "writeFileSync",
  ]

  function wrapSingleArg(target, method, isWrite) {
    const orig = target[method]
    if (typeof orig !== "function") return
    target[method] = function sandboxedFsSingle(p, ...rest) {
      let writeMode = isWrite
      if (!isWrite && (method === "open" || method === "openSync")) {
        const flags = typeof rest[0] === "string" || typeof rest[0] === "number" ? rest[0] : ""
        if (typeof flags === "string" && /[wa+]/.test(flags)) writeMode = true
      }
      const mapped = resolveSandboxPath(p, writeMode)
      return orig.call(this, mapped, ...rest)
    }
  }

  for (const m of readSingleMethods) wrapSingleArg(fs, m, false)
  for (const m of writeSingleMethods) wrapSingleArg(fs, m, true)
  if (fs.realpath && typeof fs.realpath.native === "function") {
    const origNative = fs.realpath.native
    fs.realpath.native = function (p, ...rest) {
      return origNative.call(this, resolveSandboxPath(p, false), ...rest)
    }
  }
  if (fs.realpathSync && typeof fs.realpathSync.native === "function") {
    const origNativeSync = fs.realpathSync.native
    fs.realpathSync.native = function (p, ...rest) {
      return origNativeSync.call(this, resolveSandboxPath(p, false), ...rest)
    }
  }

  for (const m of ["copyFile", "copyFileSync", "cp", "cpSync", "link", "linkSync", "rename", "renameSync", "symlink", "symlinkSync"]) {
    const orig = fs[m]
    if (typeof orig !== "function") continue
    fs[m] = function sandboxedFsPair(src, dest, ...rest) {
      const mappedSrc = resolveSandboxPath(src, m.startsWith("rename"))
      const mappedDest = resolveSandboxPath(dest, true)
      return orig.call(this, mappedSrc, mappedDest, ...rest)
    }
  }

  const promiseReadMethods = [
    "access", "lstat", "open", "opendir", "readdir", "readFile", "readlink", "realpath", "stat",
  ]
  const promiseWriteMethods = [
    "appendFile", "chmod", "chown", "lchmod", "lchown", "mkdir", "mkdtemp", "rm", "rmdir", "truncate", "unlink", "utimes", "writeFile",
  ]
  for (const m of promiseReadMethods) wrapSingleArg(fsPromises, m, false)
  for (const m of promiseWriteMethods) wrapSingleArg(fsPromises, m, true)
  for (const m of ["copyFile", "cp", "link", "rename", "symlink"]) {
    const orig = fsPromises[m]
    if (typeof orig !== "function") continue
    fsPromises[m] = function sandboxedFsPromisesPair(src, dest, ...rest) {
      const mappedSrc = resolveSandboxPath(src, m === "rename")
      const mappedDest = resolveSandboxPath(dest, true)
      return orig.call(this, mappedSrc, mappedDest, ...rest)
    }
  }
}

installFsSandbox()

const originalHttpRequest = http.request
const originalCreateServer = http.createServer

function isBridgeRequest(input, options = {}) {
  let host
  let port
  let requestPath
  let method = options.method
  if (typeof input === "string" || input instanceof URL) {
    const target = new URL(input)
    host = target.hostname
    port = target.port || (target.protocol === "https:" ? "443" : "80")
    requestPath = target.pathname
  } else if (input && typeof input === "object") {
    host = input.hostname || input.host
    port = input.port
    requestPath = input.path || input.pathname
    method = input.method
  }
  return (
    String(method || "GET").toUpperCase() === "POST" &&
    (host === "127.0.0.1" || host === "localhost") &&
    Number(port) === bridgePort &&
    String(requestPath || "").split("?", 1)[0] === "/msg"
  )
}

http.request = function authenticatedBridgeRequest(...args) {
  const input = args[0]
  const options = args[1] && typeof args[1] === "object" ? args[1] : {}
  if (!isBridgeRequest(input, options))
    return originalHttpRequest.apply(this, args)
  if (typeof input === "string" || input instanceof URL) {
    const secured = {
      ...options,
      headers: { ...(options.headers || {}), [bridgeTokenHeader]: token },
    }
    if (args[1] && typeof args[1] === "object") args[1] = secured
    else args.splice(1, 0, secured)
  } else {
    args[0] = {
      ...input,
      headers: { ...(input.headers || {}), [bridgeTokenHeader]: token },
    }
  }
  return originalHttpRequest.apply(this, args)
}

function send(action, opt = {}) {
  const body = JSON.stringify({ action, opt })
  const request = http.request({
    host: "127.0.0.1",
    port: bridgePort,
    path: "/msg",
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Content-Length": Buffer.byteLength(body),
      [bridgeTokenHeader]: token,
    },
    timeout: 3000,
  })
  request.on("error", () => {})
  request.on("timeout", () => request.destroy())
  request.end(body)
}

function reportStarted() {
  const address = server && server.address()
  if (!address || typeof address !== "object") return
  const host =
    !address.address ||
    address.address === "0.0.0.0" ||
    address.address === "::"
      ? "127.0.0.1"
      : address.address
  send("serverStarted", {
    address: `http://${host}:${address.port}`,
    token,
    pid: process.pid,
    version: process.version,
    runtimePath: runtimePath || "",
    arch: process.arch,
  })
}

function serveHealth(req, res) {
  req.resume()
  const actual = Buffer.from(String(req.headers["x-catvod-token"] || ""))
  const expected = Buffer.from(token || "")
  if (
    req.method !== "GET" ||
    !expected.length ||
    actual.length !== expected.length ||
    !crypto.timingSafeEqual(actual, expected)
  ) {
    res.writeHead(403)
    return res.end()
  }
  res.setHeader("Content-Type", "application/json")
  res.end(
    JSON.stringify({
      value: 6 * 7,
      pid: process.pid,
      version: process.version,
    }),
  )
}

http.createServer = function (...args) {
  const s = originalCreateServer.apply(this, args)
  if (!server) {
    server = s
    s.once("listening", reportStarted)
  }
  return s
}

globalThis.catDartServerPort = () => bridgePort
globalThis.catServerFactory = (handler) => {
  const s = originalCreateServer.call(http, (req, res) => {
    if (req.url.split("?", 1)[0] !== "/__catvod_health")
      return handler(req, res)
    return serveHealth(req, res)
  })
  server = s
  s.once("listening", reportStarted)
  return s
}

function clearNonNativeModuleCache() {
  for (const cacheKey of Object.keys(require.cache)) {
    if (cacheKey !== __filename && !cacheKey.endsWith(".node")) {
      delete require.cache[cacheKey]
    }
  }
}

async function main() {
  clearNonNativeModuleCache()

  let config = {}
  if (originalExistsSync(resolvedConfigPath)) {
    try {
      const configModule = require(resolvedConfigPath)
      config = (configModule && (configModule.default || configModule)) || {}
    } catch (err) {
      console.warn(
        "[NodeRuntime] Failed to require index.config.js, fallback to {}:",
        (err && err.message) || err,
      )
      config = {}
    }
  } else {
    console.warn("[NodeRuntime] index.config.js missing, fallback to {}")
  }

  const indexModule = require(resolvedIndexPath)
  const runtime = indexModule && (indexModule.default || indexModule)
  if (!runtime || typeof runtime.start !== "function")
    throw new Error("Node bundle does not export start()")
  await runtime.start(config)
}

main().catch((error) => {
  send("nodeError", {
    message: String((error && (error.stack || error.message)) || error),
    token,
  })
  setTimeout(() => process.exit(1), 100)
})

process.on("uncaughtException", (err) => {
  console.error(
    "[NodeRuntime] uncaughtException:",
    (err && (err.stack || err.message)) || err,
  )
})

const IGNORED_REJECTION_PATTERN = /WebAssembly|undici|llhttp/i
process.on("unhandledRejection", (reason) => {
  const msg = (reason && (reason.stack || reason.message)) || String(reason || "")
  if (IGNORED_REJECTION_PATTERN.test(msg)) return
  console.error("[NodeRuntime] unhandledRejection:", msg)
})
