export const meta = {
  name: 'settee-audit',
  description: 'Read-only audit of Settee: bugs and runtime/build performance, area by area, each finding challenged before it survives',
  whenToUse: 'Auditing Settee for bugs and performance savings. Options (all optional): areas, lenses, maxFindings, skeptics. Run with no args for the full audit (~100-150 agents); pick areas to keep it cheap.',
  phases: [
    { title: 'Find', detail: 'one reviewer per area and lens (bugs, performance), plus build & CI' },
    { title: 'Verify', detail: 'a skeptic tries to refute each finding; high-severity ones get two' },
    { title: 'Report', detail: 'dedupe and draft one issue per confirmed finding' },
  ],
}

// ---------------------------------------------------------------------------
// Options, passed as the Workflow's args (every field optional):
//   areas:       which areas to review, e.g. ['build-and-ci', 'playback'].
//                Keys: providers, catalogue, epg-and-library, playback, platform,
//                ui-shell, ui-screens, build-and-ci. Default: all of them.
//   lenses:      ['bugs'], ['performance'] or both (default). build-and-ci always
//                runs its one build lens when it is selected.
//   maxFindings: findings each reviewer keeps, 1-20. Default 15.
//   skeptics:    0 = skip verifying (quick first look, findings unchecked),
//                1 = one skeptic per finding, 2 = two per finding,
//                'auto' = two for high severity, one otherwise (default).
// Examples:
//   { areas: ['build-and-ci'] }                                   ~10-20 agents
//   { areas: ['playback', 'catalogue'], lenses: ['performance'], maxFindings: 8 }
//   { maxFindings: 8, skeptics: 1 }                               the lean full audit
// ---------------------------------------------------------------------------
const OPTS = (args && typeof args === 'object' && !Array.isArray(args)) ? args : {}

// ---------------------------------------------------------------------------
// Areas. Paths are relative to the repo root; main code is ~42k lines of Kotlin.
// ---------------------------------------------------------------------------
const SRC = 'app/src/main/java/app/opentv'
const AREAS = [
  {
    key: 'providers',
    paths: [`${SRC}/data/remote`, `${SRC}/data/parser`],
    docs: 'docs/ARCHITECTURE.md (Xtream parsing, Stalker, Stremio, M3U, XMLTV sections)',
    focus: 'provider clients and parsers: hand-parsed Xtream JSON, M3U/XMLTV streaming parse, HTTP error handling, memory use while parsing very large playlists/guides',
  },
  {
    key: 'catalogue',
    paths: [`${SRC}/data/db`, `${SRC}/data/model`, `${SRC}/data/repo/CatalogRepository.kt`, `${SRC}/data/repo/CatalogueRefresh.kt`,
      `${SRC}/data/repo/ChannelLineup.kt`, `${SRC}/data/repo/SourceRepository.kt`, `${SRC}/data/repo/Title*.kt`,
      `${SRC}/data/repo/Vod*.kt`, `${SRC}/data/repo/Version*.kt`, `${SRC}/data/repo/Series*.kt`, `${SRC}/data/repo/ShowEpisodes.kt`],
    docs: 'docs/ARCHITECTURE.md (catalogue refresh), CLAUDE.md "Data rules that are easy to break"',
    focus: 'Room DAOs and queries (missing indexes, full scans, IN-list bound-variable limit, N+1), catalogue merge-never-wipe, transactions, Flow re-emission storms',
  },
  {
    key: 'epg-and-library',
    paths: [`${SRC}/data/repo/Epg*.kt`, `${SRC}/data/repo/NowOn.kt`, `${SRC}/data/repo/ViewerLibrary.kt`, `${SRC}/data/repo/Favourites.kt`,
      `${SRC}/data/repo/Profile*.kt`, `${SRC}/data/repo/Synced*.kt`, `${SRC}/data/repo/LiveWatching.kt`, `${SRC}/data/repo/Recording*.kt`,
      `${SRC}/data/repo/Reminder*.kt`, `${SRC}/data/repo/Tmdb*.kt`, `${SRC}/data/repo` + ' (anything not covered above)'],
    docs: 'docs/ARCHITECTURE.md (EPG sync), CLAUDE.md "Watch state, favourites, profiles"',
    focus: 'EPG batching/pruning and UTC handling, watch-state rules (93% watched, newest-wins merges), per-profile scoping, TMDB passes',
  },
  {
    key: 'playback',
    paths: [`${SRC}/player`, `${SRC}/recording`, `${SRC}/reminders`, `${SRC}/ui/player`],
    docs: 'docs/ARCHITECTURE.md (playback), docs/ERRORS.md',
    focus: 'PlayerController lifecycle (one ExoPlayer per screen, 350 ms debounce, retry policy), leaks, buffer/memory settings for a 1 GB device, recording and reminder scheduling',
  },
  {
    key: 'platform',
    paths: [`${SRC}/core`, `${SRC}/sync`, `${SRC}/pairing`, `${SRC}/update`, `${SRC}/*.kt`],
    docs: 'docs/ARCHITECTURE.md, docs/adr/',
    focus: 'ServiceLocator wiring and startup cost (work done in Application.onCreate / first frame), WorkManager jobs, LAN/SMB sync, pairing server, updater, navigation and back handling',
  },
  {
    key: 'ui-shell',
    paths: [`${SRC}/ui/*.kt`, `${SRC}/ui/design`, `${SRC}/ui/channels`],
    docs: 'docs/LOOK.md, docs/PERFORMANCE.md',
    focus: 'Compose: unstable params and needless recomposition, work in composition, lazy list keys, image loading sizes, D-pad focus bugs, state lost across navigation; OpenTvViewModels.kt',
  },
  {
    key: 'ui-screens',
    paths: [`${SRC}/ui/vod`, `${SRC}/ui/settings`, `${SRC}/ui/options`, `${SRC}/ui/onboarding`, `${SRC}/ui/recordings`, `${SRC}/ui/setup`],
    docs: 'docs/LOOK.md, docs/PERFORMANCE.md',
    focus: 'Compose performance on large VOD grids, focus traversal bugs, state handling, collecting flows without lifecycle awareness',
  },
]

const BUILD_AREA = {
  key: 'build-and-ci',
  paths: ['build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml', 'app/build.gradle.kts',
    'app/proguard-rules.pro', '.github/workflows/', 'tools/check/', 'app/src/main/baseline-prof.txt or generated baseline profile'],
  docs: 'docs/PERFORMANCE.md, docs/RELEASING.md, docs/TESTING.md',
}

const DEVICE = 'The primary device is a Fire TV Stick (AFTSS, Fire OS 7 / API 28, 1 GB RAM, weak CPU). Performance claims should be judged against that device.'

const RULES = `Rules:
- READ-ONLY. Do not edit, create or delete files, do not commit, do not touch GitHub.
- Never run Gradle (./gradlew or tools/check/check.py): the Mac is shared and a Gradle run can swap it for hours. Reading code, git log/blame and grep only.
- Report only what you can point at in the code: file, line, and the concrete scenario that goes wrong. No style nits, no "consider adding tests", no speculative refactors.
- Respect decisions the docs record as deliberate (hand-rolled ServiceLocator, hand-parsed Xtream JSON, no backend, etc.) unless the code contradicts the doc's own reasoning.`

// ---------------------------------------------------------------------------
// Schemas
// ---------------------------------------------------------------------------
const FINDINGS = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string', description: 'one line, what goes wrong' },
          kind: { type: 'string', enum: ['bug', 'runtime-performance', 'build-performance', 'ci-performance'] },
          severity: { type: 'string', enum: ['high', 'medium', 'low'] },
          file: { type: 'string' },
          line: { type: 'integer' },
          evidence: { type: 'string', description: 'the code involved and why it is wrong or slow' },
          scenario: { type: 'string', description: 'concrete inputs/state -> wrong result, crash, jank, memory or time cost' },
          impact: { type: 'string', description: 'what a viewer or the developer notices; estimated size of the saving for performance items' },
          fix: { type: 'string', description: 'the smallest fix that would work' },
        },
        required: ['title', 'kind', 'severity', 'file', 'line', 'evidence', 'scenario', 'impact', 'fix'],
      },
    },
    not_reviewed: { type: 'array', items: { type: 'string' }, description: 'files or topics in your area you did not get to' },
  },
  required: ['findings', 'not_reviewed'],
}

const VERDICT = {
  type: 'object',
  properties: {
    refuted: { type: 'boolean' },
    reason: { type: 'string' },
    severity: { type: 'string', enum: ['high', 'medium', 'low'], description: 'your own severity if not refuted' },
  },
  required: ['refuted', 'reason', 'severity'],
}

const REPORT = {
  type: 'object',
  properties: {
    issues: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string', description: 'GitHub issue title in plain words' },
          kind: { type: 'string' },
          severity: { type: 'string' },
          area: { type: 'string' },
          body: { type: 'string', description: 'Markdown: what happens, where (file:line), evidence, suggested fix, how to verify' },
          merged_from: { type: 'array', items: { type: 'string' } },
        },
        required: ['title', 'kind', 'severity', 'area', 'body', 'merged_from'],
      },
    },
    summary: { type: 'string', description: 'a short overview: biggest risks and biggest savings' },
  },
  required: ['issues', 'summary'],
}

// ---------------------------------------------------------------------------
// Finder prompts
// ---------------------------------------------------------------------------
const ALL_AREA_KEYS = [...AREAS.map(a => a.key), BUILD_AREA.key]
const selectedAreas = Array.isArray(OPTS.areas) && OPTS.areas.length ? OPTS.areas : ALL_AREA_KEYS
const unknownAreas = selectedAreas.filter(k => !ALL_AREA_KEYS.includes(k))
if (unknownAreas.length) {
  throw new Error(`Unknown area(s): ${unknownAreas.join(', ')}. Known areas: ${ALL_AREA_KEYS.join(', ')}`)
}
const LENSES = Array.isArray(OPTS.lenses) && OPTS.lenses.length ? OPTS.lenses : ['bugs', 'performance']
const unknownLenses = LENSES.filter(l => l !== 'bugs' && l !== 'performance')
if (unknownLenses.length) throw new Error(`Unknown lens(es): ${unknownLenses.join(', ')}. Use bugs and/or performance.`)
const MAX_PER_FINDER = Number.isInteger(OPTS.maxFindings) ? Math.min(20, Math.max(1, OPTS.maxFindings)) : 15
const SKEPTICS = [0, 1, 2, 'auto'].includes(OPTS.skeptics) ? OPTS.skeptics : 'auto'
const skepticsFor = f => SKEPTICS === 'auto' ? (f.severity === 'high' ? 2 : 1) : SKEPTICS

function finderPrompt(area, lens) {
  const lensText = lens === 'bugs'
    ? `Hunt for CORRECTNESS bugs: crashes, wrong results, data loss (especially anything that breaks the data rules in CLAUDE.md), race conditions and coroutine/cancellation mistakes, lifecycle leaks, time-zone errors, error paths that hide failures, D-pad focus traps.`
    : `Hunt for RUNTIME PERFORMANCE costs: main-thread I/O or parsing, slow or unindexed queries, needless recomposition, allocation in hot paths, oversized bitmaps, memory pressure on 1 GB, work at startup that could be lazy, redundant network calls, polling, wakeups and battery cost.`
  return `You are auditing the Settee Android TV app (repo root is your working directory).

Area: ${area.key}
Paths: ${area.paths.join(', ')}
Background to read first: ${area.docs}
Area focus: ${area.focus}

${lensText}

${DEVICE}

${RULES}

Read every file in your area. Return at most ${MAX_PER_FINDER} findings, most severe first; if you had more, keep the worst and list the rest by title in not_reviewed. List anything you did not read in not_reviewed.`
}

function buildPrompt() {
  return `You are auditing the BUILD AND CI PIPELINE of the Settee Android app (repo root is your working directory).

Paths: ${BUILD_AREA.paths.join(', ')}
Background to read first: ${BUILD_AREA.docs}

Look for:
- Gradle: configuration cache, build cache, parallel execution, JVM args, unnecessary plugins or tasks on every build, KSP/Room configuration, test task configuration (forking, max heap, Robolectric), dependency bloat that slows builds or bloats the APK.
- Release build: R8/minification and resource shrinking, baseline profile wiring, APK size.
- CI (.github/workflows): caching (Gradle, Android SDK, Python deps), job duplication (e.g. compiling the app separately in several jobs), concurrency/cancel-in-progress, path filters, runner choice, steps that could run in parallel.
- Use \`gh run list --workflow build.yml --limit 20 --json databaseId,conclusion,createdAt,updatedAt\` and \`gh run view <id> --json jobs\` to see real job durations, and base savings estimates on them.

${RULES}

Return at most ${MAX_PER_FINDER} findings, biggest saving first, kind build-performance or ci-performance (or bug for broken configuration).`
}

// ---------------------------------------------------------------------------
// Verify prompt
// ---------------------------------------------------------------------------
function verifyPrompt(f, area, n) {
  return `A reviewer claims this about the Settee Android TV app (repo root is your working directory). Your job is to REFUTE it if you can. Skeptic #${n}.

Title: ${f.title}
Kind: ${f.kind}   Severity claimed: ${f.severity}   Area: ${area}
Location: ${f.file}:${f.line}
Evidence: ${f.evidence}
Scenario: ${f.scenario}
Impact: ${f.impact}

Open the code and check:
- Does the code really do what is claimed? Check callers, guards elsewhere, and tests in app/src/test.
- Can the scenario actually happen in this app (on a Fire TV Stick, with real provider data)?
- Is it a deliberate decision recorded in docs/ARCHITECTURE.md, docs/adr/ or CLAUDE.md?
- For performance claims: is the cost real and big enough to matter, or is it a micro-optimisation?

${RULES}

Set refuted=true if the claim is wrong, cannot happen, is deliberate, or is too small to matter. If you are unsure, refuted=true.`
}

// ---------------------------------------------------------------------------
// Run
// ---------------------------------------------------------------------------
const jobs = [
  ...AREAS.filter(a => selectedAreas.includes(a.key)).flatMap(a =>
    LENSES.map(lens => ({ area: a.key, lens, prompt: finderPrompt(a, lens) }))),
  ...(selectedAreas.includes(BUILD_AREA.key) ? [{ area: BUILD_AREA.key, lens: 'build', prompt: buildPrompt() }] : []),
]

log(`Areas: ${selectedAreas.join(', ')} · lenses: ${LENSES.join(', ')} · ${jobs.length} reviewers · up to ${MAX_PER_FINDER} findings each · skeptics: ${SKEPTICS}`)
if (SKEPTICS === 0) log('skeptics: 0, so findings are NOT verified; treat them as leads, not confirmed bugs')

const notReviewed = []

const verified = await pipeline(
  jobs,
  job => agent(job.prompt, { label: `find:${job.area}:${job.lens}`, phase: 'Find', schema: FINDINGS }),
  (res, job) => {
    if (!res) { notReviewed.push(`${job.area}/${job.lens}: finder failed`); return [] }
    res.not_reviewed.forEach(x => notReviewed.push(`${job.area}/${job.lens}: ${x}`))
    log(`${job.area}/${job.lens}: ${res.findings.length} findings`)
    return parallel(res.findings.map(f => async () => {
      const skeptics = skepticsFor(f)
      if (skeptics === 0) return { ...f, area: job.area, lens: job.lens, verified: false }
      const votes = (await parallel(Array.from({ length: skeptics }, (_, i) => () =>
        agent(verifyPrompt(f, job.area, i + 1), {
          label: `verify:${job.area}:${f.file.split('/').pop()}:${f.line}`,
          phase: 'Verify',
          schema: VERDICT,
          effort: 'medium',
        })))).filter(Boolean)
      const upheld = votes.filter(v => !v.refuted)
      // A high-severity finding needs both skeptics to fail to refute it.
      const survives = votes.length > 0 && upheld.length === votes.length
      return survives
        ? { ...f, area: job.area, lens: job.lens, verified: true, verifiedSeverity: upheld.map(v => v.severity), verifierNotes: upheld.map(v => v.reason) }
        : null
    }))
  },
)

const confirmed = verified.flat().filter(Boolean)
log(SKEPTICS === 0 ? `${confirmed.length} unverified findings` : `${confirmed.length} findings survived verification`)
if (notReviewed.length) log(`${notReviewed.length} items reported as not reviewed (returned in notReviewed)`)

if (!confirmed.length) return { confirmed: [], issues: [], notReviewed }

phase('Report')
const report = await agent(`You are turning audit findings (verified ones are marked verified: true; unverified ones must say so in their body) for the Settee Android TV app into draft GitHub issues. Do NOT create the issues; return them.

Merge findings that describe the same root cause (list the merged titles in merged_from). Order by severity, then by size of saving. Write titles in plain words; follow the repo's style (no jargon a viewer would not follow for bugs a viewer notices). Each body: what happens, where (file:line links relative to repo root), evidence, suggested fix, how to verify (for performance items, which measurement in docs/PERFORMANCE.md or which CI timing shows the saving).

Findings (JSON):
${JSON.stringify(confirmed, null, 1)}`, { phase: 'Report', schema: REPORT })

return { summary: report?.summary, issues: report?.issues ?? [], confirmed, notReviewed }
