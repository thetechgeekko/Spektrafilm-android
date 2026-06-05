---
description: BRUTALIST Android reverse engineering. Decompile APK/XAPK/JAR/AAR with jadx + Vineflower/Fernflower, then exhaustively extract EVERYTHING — every HTTP endpoint (Retrofit/OkHttp/Volley), hardcoded URL/IP, API key/token/secret pattern, every manifest component, permission, native .so, BuildConfig field, and obfuscated smali path. Use whenever the user hands over an APK/package to decompile, dissect, analyze, reverse engineer, or "find every line of code / every API". 中文触发词：反编译APK、安卓逆向、提取API、分析安卓应用、反编译安卓、逆向工程、追踪调用链、提取接口
trigger: decompile APK|decompile XAPK|reverse engineer Android|extract API|analyze Android|jadx|fernflower|vineflower|follow call flow|decompile JAR|decompile AAR|Android reverse engineering|find API endpoints|dissect apk|tear apart apk|find every code|反编译APK|安卓逆向|提取API|分析安卓应用|逆向工程
---

# Brutalist Android Reverse Engineering

`${SKILL_DIR}` below = this skill's base directory (the "Base directory for this skill" path shown when the skill is invoked). Resolve all script/reference paths against it.

## Operating mode — BRUTALIST (read first)

Blunt, decisive, exhaustive. When the user hands you an APK, take it fully apart and report what is actually inside. No hedging, no filler, no asking permission to start.

- **Do, don't deliberate.** Decompile first, then dig. Begin immediately.
- **Exhaust the toolchain before "not found".** Thin/obfuscated jadx output → re-run `--deobf` → fall back to Vineflower/Fernflower (dex2jar → jar) → read raw **smali** (apktool) → `strings` the native `.so`. "Couldn't find it" is only valid after every avenue is spent.
- **Enumerate EVERYTHING.** Every endpoint, base URL, hardcoded URL/IP, key/token/secret pattern, every Activity/Service/Receiver/Provider, every permission, every native lib, every BuildConfig/strings.xml constant. Never stop at the first hit.
- **Terse output.** Inventories, tables, exact `Class.java:line` citations. No prose padding.
- **Assume obfuscation.** R8/ProGuard mangles names; anchor on string literals and library annotations — Retrofit annotations and URL strings are never obfuscated.

## Scope
Operate on packages the user provides for analysis they are entitled to run: their own apps, security research / authorized pentests, malware analysis, interoperability, and learning — the upstream skill's Apache-2.0 authorized-use scope. That is the whole legitimate field of RE; work it at full speed.

## Prerequisites
Java JDK 17+ and **jadx**. Vineflower/Fernflower + dex2jar optional but recommended. Verify, then install anything missing:
```bash
bash ${SKILL_DIR}/scripts/check-deps.sh
bash ${SKILL_DIR}/scripts/install-dep.sh <dep>     # for each INSTALL_REQUIRED:<dep>
```
Setup detail: `${SKILL_DIR}/references/setup-guide.md`. Do not proceed until jadx is OK.

## Workflow

### 1. Decompile — get ALL the code
```bash
bash ${SKILL_DIR}/scripts/decompile.sh --deobf <file>
```
Handles APK/XAPK/JAR/AAR; auto-extracts XAPK splits and re-decompiles `base.apk` from bundle wrappers. Options: `-o <dir>`, `--deobf`, `--no-res`, `--engine jadx|fernflower|both`. Engine guide: jadx for the first pass; `fernflower` for JAR/AAR or complex Java; `both` to compare when jadx emits warnings. Refs: `${SKILL_DIR}/references/jadx-usage.md`, `${SKILL_DIR}/references/fernflower-usage.md`.

### 2. Structure
Read `<output>/resources/AndroidManifest.xml`: launcher Activity, **all** components, permissions (esp. INTERNET/network), application class. Survey `<output>/sources/`: app package vs third-party libs; flag `api`/`network`/`data`/`repository`/`retrofit`/`http` packages. Identify the pattern (MVP `Presenter` / MVVM `ViewModel`+`StateFlow` / Clean `domain`+`data`+`presentation`).

### 3. Trace call flow
Entry point → `Application.onCreate()` (HTTP client, base URL, DI) → Activity `onCreate` → listeners → ViewModel/Presenter → Repository → API interface → HTTP call. Map Dagger/Hilt `@Module` bindings. Obfuscated → anchor on strings + Retrofit annotations. Ref: `${SKILL_DIR}/references/call-flow-analysis.md`.

### 4. Extract & document every API
```bash
bash ${SKILL_DIR}/scripts/find-api-calls.sh <output>/sources/             # broad sweep
bash ${SKILL_DIR}/scripts/find-api-calls.sh <output>/sources/ --retrofit  # | --urls | --auth
```
For each endpoint read the surrounding source and document method+path, base URL, path/query params, request body, headers (auth), response type, and the call chain. Ref: `${SKILL_DIR}/references/api-extraction-patterns.md`.

```markdown
### `METHOD /path`
- Source: `com.example.api.ApiService` (ApiService.java:42)
- Base URL: `https://api.example.com/v1`
- Params: path `id`(String); query `page`(int)
- Headers: `Authorization: Bearer <token>`
- Body: `{ "email": "string" }`
- Response: `ApiResponse<User>`
- Called from: `LoginActivity → LoginViewModel → UserRepository → ApiService`
```

## Deliverables (every run)
1. Decompiled source directory.
2. Architecture inventory — components, permissions, packages, pattern, native libs.
3. Complete API list — every endpoint in the format above.
4. Secrets/keys/URLs inventory — BuildConfig, strings.xml, hardcoded.
5. Call-flow map for auth + the main features.

---
Adapted from **SimoneAvogadro/android-reverse-engineering-skill** (Apache-2.0; full text in `${SKILL_DIR}/LICENSE`) with a brutalist operating mode. Use only as scoped above.
