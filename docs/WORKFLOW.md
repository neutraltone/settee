# How Settee is built

Most of Settee is written with [Claude Code](https://claude.com/claude-code) and a handful of agent
skills, under the rules in `CLAUDE.md`. This page lists those skills, how to install them, and the
process they slot into, so a contribution can be made the same way. None of it is required: every
step below can be done by hand, and CI checks the result the same either way.

## Skills

The skills aren't in this repository. Each contributor installs them for their own user, so they
work in every checkout and update separately. The only agent tooling committed here is what's
specific to Settee: `CLAUDE.md`, `docs/agents/` (how the skills find this repo's issue tracker,
labels and domain docs) and the `settee-audit` workflow in `.claude/workflows/`.

| Skills | What they're for here | Install |
|---|---|---|
| [Matt Pocock's skills](https://github.com/mattpocock/skills) | The engineering process: turning an idea into a spec and tickets, triage, test-first fixes, debugging, code review, PR descriptions, the glossary and ADRs | In Claude Code: `/plugin install mattpocock-skills@claude-plugins-official` |
| [Impeccable](https://github.com/pbakaus/impeccable) | Design work: critique, polish, harden, layout and the rest, reading `PRODUCT.md`, `DESIGN.md` and `docs/LOOK.md` | `npx skills add pbakaus/impeccable -g` |
| [Emil Kowalski's skills](https://github.com/emilkowalski/skills) | Motion: whether something should move, which curve and duration, how moves are sequenced, and taking out motion that doesn't earn its place | `npx skills add emilkowalski/skills -g` |

`npx skills` is the [skills CLI](https://github.com/vercel-labs/skills); it asks which skills from a
package to install and which agents to install them for. `-g` installs them for your user rather
than into this checkout, which keeps them out of git. From Emil Kowalski's package, the ones that
apply to a Compose TV app are `animate`, `choreograph`, `review-animations`, `improve-animations`,
`find-animation-opportunities`, `remove-animations` and `animation-vocabulary`; the rest are for the
web, React Native or Swift.

Where a skill and this repo disagree, the repo wins. In particular:

- **`docs/LOOK.md` is the authority on the look.** `DESIGN.md` summarises it for design tools, and
  `PRODUCT.md` says who Settee is for. Impeccable reads all three.
- **Every motion has to hold 60 fps on a Fire TV Stick** (`docs/PERFORMANCE.md`), whatever an
  animation skill suggests. If it can't, it doesn't move.
- **The Material 3 and web advice in general-purpose skills doesn't apply.** Settee builds from
  `ui/design` and Compose foundation, D-pad first.

## The process

1. **Ideas become issues.** Larger work starts as a spec (`/wayfinder`, `/to-spec`, then
   `/to-tickets`), with `/grilling` to stress-test it first. Every bug and feature is an issue on
   `neutraltone/settee`. New issues stay `needs-triage` until the owner triages them
   (`docs/agents/triage-labels.md`).
2. **The board sets the order.** [Settee's project board](https://github.com/users/neutraltone/projects/2)
   ranks the work: P0 is the main sequence, P1 the rest, P2 undecided ideas; within a priority, the
   top card is next.
3. **One ticket, one branch, one PR.** Work in its own git worktree, so several pieces can be in
   flight at once. Commits and the PR title follow `type(#issue): description`, worded for a viewer
   when it's `feat`, `fix` or `perf` (see `CONTRIBUTING.md`). `git config core.hooksPath .githooks`
   checks each commit message.
4. **Fix test-first where it can be tested** (`/tdd`), and find a bug's cause before patching it
   (`/diagnosing-bugs`).
5. **Check little locally, let CI check the rest.** Run the test class you're working on and
   `tools/check/check.py quick` (ktlint and detekt). The full suite, Lint and gitleaks run on the PR.
   Run Gradle through `tools/check/check.py`, which explains failures and stops frozen test runs.
6. **Review as soon as the fix is green, without waiting to be asked.** Run
   `/mattpocock-skills:code-review` against `origin/main`. Not the built-in `/code-review`, a bug hunt
   that shares the name, and not local `main`, which lags behind in a worktree. It reports Standards
   and Spec separately; the owner picks which findings to fix, and only those get fixed. Stopping at
   the commit is the step most often missed.
7. **Then open the PR with Auto-fix on.** Commit, rebase on `origin/main`, push, write the description
   with `/pr`, open the PR and turn on Auto-fix, so CI failures, merge conflicts and review comments
   come back to the session. A screen change adds a `captureScreen` line to that screen's tests, and
   the Screenshots workflow puts a before and after table in the PR.
8. **Merge by squash.** The PR title becomes the commit on `main`, and release-please turns `feat`,
   `fix` and `perf` titles into the changelog and the app's What's new (`docs/RELEASING.md`).

For design work, run `/impeccable critique` on the screen first, agree what to change, then use the
command it points to (`polish`, `harden`, `layout`, …). For a broad bug and performance sweep, the
`settee-audit` workflow audits the app area by area and challenges each finding before reporting it.
