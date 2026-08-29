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
| Language | expressions, `var`/`let`/`const`, assignment, **compound assignment** (`+=` `-=` `*=` `/=` `%=`) and **`++`/`--`** (prefix and postfix), `if`/`else`, ternary `?:`, `while`, `for`, **`for...of`** and **`for...in`**, **`switch`** (with fallthrough), `break`/`continue`, blocks, **functions** (declarations, function expressions incl. named and immediate, parameters, `return`, recursion, mutual recursion, hoisting), **closures** (capture by reference), **arrays and objects** (literals, `.k`, `[k]`, `.length`, member assignment, nesting), **method calls** and `this`, **builtins** (`push`/`pop`/`filter`/`map`/`forEach`/`join`/`indexOf`/`includes`/`slice`; `charAt`/`indexOf`/`substring`/`toLowerCase`/`toUpperCase`/`split`/`includes`), **`throw`/`try`/`catch`/`finally`** with real `Error` objects and **`new Error(...)`**, `typeof` (including the spec's unresolvable reference, so `typeof window` is `undefined` rather than an error), short-circuit `&&`/`||`, comments |
| Not yet | prototypes and `instanceof`, IEEE-754 numbers (integers only), host objects beyond the eight below — see Gaps |
| Own tests | **251/251 inside wasm32** (`--target wasm32-browser`, instantiated under the real browser host, run in chunks) and on the restricted-ESM artifact |
| Differential | **237/238 agree with a real host V8** (1 recorded divergence), and **62/63 agree with quickjs-ng** — 35/36 language, 22/22 DOM, 5/5 events — through `browser`'s `test/runtime-differential.cljs`, against the engine this one would replace |
| Capabilities | **none** — `kotoba -M check` reports `:effects #{}`, DOM bridge included. A write is a VALUE the host replays; the authority never leaves the host |
| wasm32-browser | **35 KB, instantiates and runs** — this is the target that replaces the QuickJS blob |

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
- **A caller's bindings are still visible behind the capture.** Captured names
  win, which is what makes it lexical, but a name in neither the capture nor
  the caller is the only ReferenceError. The tail is deliberate: it is what
  lets a function reach a sibling declared later, and itself, neither of which
  a snapshot taken at declaration time can contain.
- **A wasm32 module may carry at most 256 typed literals** (`browser-host.mjs`
  rejects more with `too many typed ABI literals`), and the engine is close
  enough to that ceiling that it cannot also carry its 170 tests. Restricting
  the EXPORT list does not help, which is the opposite of how unreachable
  functions behave: measured 2026-08-29, a build that DEFINES 170 tests and
  exports 3 still exceeds the ceiling, while a build physically containing
  only those 3 passes. **Function bodies are dropped when unreachable; their
  literals are not.** `test/wasm-suite.cljs` therefore slices the source
  physically and runs five chunks — 180/180 inside wasm32. As the engine grows
  it will approach the ceiling on its own, and that is the thing to watch.
- **Cells are never freed.** The cell region only grows, because a cell id is
  its length at allocation and reuse would hand the same number out twice. A
  long-running program bounded by the 64 KiB string ceiling will reach it; a
  page script will not. Freeing needs a reachability pass this engine has no
  reason to run yet.
- **`new` is only the Error constructors.** `new Error("boom")` builds the
  same plain `{name, message}` object a caught error becomes; any other
  constructor is refused BY NAME rather than silently producing an empty
  object. There are no prototypes and no `instanceof`.
- **Numbers are i64, not IEEE-754 doubles.** This is the one recorded
  divergence from the host engine (`7 / 2` is 3, not 3.5). It is a gap in the
  *language*, measured 2026-08-29: `:f64` values pass through Kotoba, but
  arithmetic on them has no admitted lowering (`(+ x x)` on two `:f64` is
  rejected with `expected i64, got f64`), so doubles would have to be built in
  software on top of i64 (`bit-and`, `bit-or`, `bit-xor` and `quot` are
  admitted; shifts are not, and are `*`/`quot` by powers of two).
- **Source is treated as ASCII.** `string-length` counts code points while
  `string-substring` takes byte boundaries; they agree only for ASCII.

## The DOM bridge

A page script is only useful if it can change the page, and this engine has no
capabilities at all. Both are true at once because a write is not performed —
it is **returned**:

```
host: snapshot (element id -> textContent)  ──┐
                                              ▼
              eval-dom(source, snapshot) ──> effect log ──> host replays it
```

That is the shape `kotoba/app` already draws for this workspace
(`state + event -> next-state + inert effects`, ADR-2607201300 / ADR-2608261100).
It is why `kotoba -M check` still answers `:effects #{}` with `document` bound,
and why `browser.runtime/ecma262` declares `:imports #{}` — there is nothing
to import.

```js
document.getElementById('ws-proof').textContent = 'done';
```

```
11:textContent8:ws-proof4:done
```

The log is `<len>:<prop><len>:<id><len>:<value>`, appended in order, so the
host replays exactly what the script asked for, in the order it asked.

Attributes travel the same way. The snapshot gives each element a small region
of its own — `textContent`, then each attribute under `@name`, so an element
carrying an attribute literally called `textContent` cannot shadow the
property — and `setAttribute` is an effect whose value nests the name and the
new value:

```
12:setAttribute1:a11:5:class2:on
```

`getAttribute` answers `null` for an attribute the element does not carry,
which is what a real one does and what makes `if (el.getAttribute('x'))` a
real guard.

### What the guest can reach

`document` and `console` are **nodes too**, under ids no HTML id can spell —
`#document` and `#console`. That is not a trick to save code, it is what
removes the special cases: a write to `document.title` is the same rule as a
write to any element's property, so the assignment path has one rule rather
than three.

| | reads | writes |
|---|---|---|
| element | `textContent`, `getAttribute(n)` | `textContent`, `setAttribute(n, v)`, `addEventListener(type, fn)` |
| `document` | `title`, `getElementById(id)` | `title` |
| `console` | — | `log(…)` |

That is the whole surface. Anything else is `undefined`, which a page script
can test for — the same way it tests for a feature a browser does not have.

### Events

`addEventListener` is not a write. It hands the host a **function**, and a
guest with no capabilities cannot be called back. So the registration is what
comes back — element, event type, and a handler **number** — and firing is a
second entry point:

```js
var r = document.getElementById('out');
document.getElementById('btn').addEventListener('click', function () {
  r.textContent = 'fired';
});
```

```
eval-dom       -> 16:addEventListener3:btn10:5:click1:0
eval-dom-event(…, 0) -> 11:textContent3:out5:fired
```

`eval-dom-event` re-runs the script to rebuild the environment, clears the
log, and calls handler *n*, so only that handler's own effects come back.
Re-running is the price of holding no state between calls, and it is the
honest one: the host owns the state, so the guest has to be told everything it
needs each time. The handler still sees what it closed over — which is exactly
why the function itself stays inside the guest and only a number crosses.

- **Reads come from the snapshot, and a write does not change it.** A script
  that sets `textContent` and reads it back sees what the host injected. That
  is the honest answer for a guest that cannot see the host's document, and it
  is what `+=` composes against.
- `getElementById` of an unknown id answers `null`, as a real document does,
  so `if (el)` is a real guard.
- `%dom` and `%fx` hold the snapshot and the log. No JavaScript identifier can
  spell those names, so no page script can reach or shadow them.
- **The bridge and its events are measured against quickjs-ng**, not against a recorded
  string: `browser`'s `test/runtime-differential.cljs` gives the real engine a
  `document` shim whose setter writes the same log format, and compares the
  two logs.

## Why this repo is named this way

`ecma-international.org` reversed is `org-ecma-international`; the subject is
ECMA-262. The origin plane applies because the subject is someone else's
specification (root ADR-2608040100). The name is not a claim of conformance —
the Status table above is the claim.

## Running the tests inside wasm32

```bash
nbb test/wasm-suite.cljs        # five chunks of 40, each compiled and run as its own module
nbb test/wasm-suite.cljs 20     # smaller chunks
```

Exit 0 every test passed, 1 a test failed, **2 the harness could not answer**
(no compiler, no host) — which is not the same as passing. It picks the
compiler by RUNNING it rather than by looking for the file, because
`bin/kotoba` used to exit 194 with no output at all when its `node_modules`
could not resolve nbb.
