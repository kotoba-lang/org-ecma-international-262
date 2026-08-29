# org-ecma-international-262

**A JavaScript (ECMA-262 subset) interpreter written in Kotoba and compiled by
amu.** It exists to remove `kotoba-lang/browser`'s dependency on the QuickJS
WASM binary: the same host slot, a different guest.

JavaScript source is **runtime input** to this program — a string. amu never
sees it. amu compiles *this* program, and the compiled program then reads and
evaluates the JavaScript. That is exactly the shape QuickJS already has (a C
program compiled to Wasm by emcc); the C becomes Kotoba and emcc becomes amu,
and `browser.runtime`'s `:runtime/eval` seam does not move.

```
page <script> text ──runtime input──┐
                                    ▼
  src/ecma262.kotoba ──amu compile──> .wasm / .mjs ──> the slot QuickJS holds
```

## Status, measured

| | |
|---|---|
| Language | expressions, `var`/`let`/`const`, assignment, `if`/`else`, `while`, blocks, `typeof`, comments, string/number/boolean/undefined/null |
| Not yet | functions, objects, arrays, `for`, `try`, closures, prototypes — see Gaps |
| Own tests | 48, run on **:jvm-kir** (reference KIR) and **restricted ESM** by `kotoba -M test` |
| Differential | **56/57 agree with a real host V8**, 1 recorded divergence (`test/differential.cljs`) |
| Capabilities | **none** — `kotoba -M check` reports `:effects #{}`. A pure interpreter asks the host for nothing |
| wasm32-browser | compiles (16 KB) but the emitted module **does not instantiate** — an amu backend defect, see Gaps |

## Build and test

```bash
kotoba -M check src/ecma262.kotoba                       # types + effects
kotoba -M test  src/ecma262.kotoba                       # 48 tests per target

kotoba -M compile src/ecma262.kotoba --target js \
  --fuel 200000000 --output target/ecma262.mjs
nbb test/differential.cljs                               # vs the host engine
```

`--fuel` is not optional. The default budget is **512 operations for the life
of an instance**, which a parser spends before it reaches the first statement;
only the two simplest tests pass at that setting.

## Gaps, with the condition that removes each

- **wasm32-browser emits an invalid module.** `kotoba -M compile --target
  wasm32-browser` reports `:ok true`, and the artifact fails to compile with
  `function index #694 is out of bounds` in function #78. Small programs are
  fine (a 60-line probe instantiates and runs), so this is triggered by scale
  or shape, not by usage. **This is the one gap that blocks the whole point of
  the repo** and is filed against amu. Until it lifts, the reference KIR and
  restricted-ESM targets are where this engine actually runs.
- **No AST.** The engine parses and evaluates in one pass and re-reads a loop
  body from its source index each iteration. That is not how an interpreter
  wants to be written; it is forced by two measured limits — a typed ADT value
  is capped at **64 nodes / depth 12** (`kotoba-kir`, enforced at runtime, no
  CLI knob), and `[:vector [:ref :js/node]]` is rejected as outside the safe
  profile. A record with four `[:option [:ref :js/node]]` slots costs ~8 nodes,
  so an AST tops out around six nodes: `(1 + 2) * 3` already exceeds it.
  **Remove this design when either limit moves.**
- **The environment is a string.** Same cause: a record holding both a value
  and a collection blows the same 64-node budget, and a typed map holds 31
  entries. Entries are length-prefixed and prepended, so lookup is a scan.
- **Numbers are i64, not IEEE-754 doubles.** This is the one recorded
  divergence from the host engine (`7 / 2` is 3, not 3.5).
- **Source is treated as ASCII.** `string-length` counts code points while
  `string-substring` takes byte boundaries; they agree only for ASCII.

## Why this repo is named this way

`ecma-international.org` reversed is `org-ecma-international`; the subject is
ECMA-262. The origin plane applies because the subject is someone else's
specification (root ADR-2608040100). The name is not a claim of conformance —
the Status table above is the claim.
