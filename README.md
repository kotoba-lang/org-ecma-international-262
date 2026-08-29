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
| Language | expressions, `var`/`let`/`const`, assignment, `if`/`else`, `while`, blocks, **functions** (declaration, parameters, `return`, recursion, mutual recursion, hoisting), **arrays and objects** (literals, `.k`, `[k]`, `.length`, member assignment, nesting), `typeof`, comments, string/number/boolean/undefined/null/function/object |
| Not yet | `for`, `try`, closures, prototypes, methods (`o.f()`), array builtins — see Gaps |
| Own tests | **89/89 inside wasm32** (`--target wasm32-browser`, instantiated under the real browser host) and on the restricted-ESM artifact |
| Differential | **96/97 agree with a real host V8**, 1 recorded divergence (`test/differential.cljs`) |
| Capabilities | **none** — `kotoba -M check` reports `:effects #{}`. A pure interpreter asks the host for nothing |
| wasm32-browser | **26 KB, instantiates and runs** — this is the target that replaces the QuickJS blob |

## Build and test

```bash
kotoba -M check src/ecma262.kotoba                       # types + effects

kotoba -M compile src/ecma262.kotoba --target js \
  --fuel 200000000 --output target/ecma262.mjs
nbb test/differential.cljs                               # vs the host engine
```

```bash
kotoba -M compile src/ecma262.kotoba --target wasm32-browser \
  --fuel 200000000 --output target/ecma262.wasm
```

The wasm artifact is instantiated through `amu/runtime/browser-host.mjs`; its
`test-*` exports are zero-arity and return 1 for pass, so the suite can be run
directly against the module. All 48 pass there.

`kotoba -M test` runs every `test-*` export on three targets in one pass, which
is the right harness for this repo, but it does not complete on this engine
yet, so the `:jvm-kir` figure is **unmeasured here** -- it was measured only
for the small probes that led to this design.

`--fuel` is not optional. The default budget is **512 operations for the life
of an instance**, which a parser spends before it reaches the first statement;
only the two simplest tests pass at that setting.

## Gaps, with the condition that removes each

- ~~**wasm32-browser emits an invalid module.**~~ **Fixed 2026-08-29** in
  `kotoba-lang/kotoba-wasm` (`a739f37`). The emitter wrote call operands as a
  single byte, but a Wasm index is ULEB128, so any module with more than 128
  functions was invalid -- a call to function 182 (`0xB6`) decoded as
  `function index #694 is out of bounds`, which is 54 + (5 << 7): `0xB6`'s low
  seven bits plus the following opcode. This engine is what first crossed 128
  functions. `kotoba -M compile` reported `:ok true` throughout, which is the
  expensive shape of the bug: the compiler said the build was fine.
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
- **A member assignment's base must be a plain identifier.** Values are
  persistent, so `a[0] = 5` builds a new array and rebinds the NAME; a deeper
  path (`o.a.b = 1`) would have to rebuild every container along it. `o.a.b`
  READS fine — only assignment is restricted.
- **Objects and arrays share the environment's own entry format.** An object
  is a scope whose names happen to be properties, and an array is an object
  keyed by decimal index with its length carried in front. Entries are
  length-prefixed, so nesting one inside another needs no escaping. Lookup is
  a scan, and a property write prepends rather than rewrites.
- **Functions have no closures.** The callee's environment is seeded from the
  caller's, which is dynamic scope. Declarations, recursion, mutual recursion,
  hoisting and globals all work; a function returned from another function and
  called later would see the wrong bindings. This is the first thing a closure
  slice has to replace, and it is why `call-fn` says so in its own body.
- **Numbers are i64, not IEEE-754 doubles.** This is the one recorded
  divergence from the host engine (`7 / 2` is 3, not 3.5).
- **Source is treated as ASCII.** `string-length` counts code points while
  `string-substring` takes byte boundaries; they agree only for ASCII.

## Why this repo is named this way

`ecma-international.org` reversed is `org-ecma-international`; the subject is
ECMA-262. The origin plane applies because the subject is someone else's
specification (root ADR-2608040100). The name is not a claim of conformance —
the Status table above is the claim.
