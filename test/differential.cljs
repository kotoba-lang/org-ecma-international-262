(ns differential
  "Differential test: the same JavaScript through this engine and through a
  REAL JavaScript engine (the host V8), compared.

  Unit tests can only check what someone thought to assert. This is the same
  instrument `kotoba-lang/cssom` points at a real Blink browser, aimed at a
  real JS engine instead: every case is evaluated twice and the two answers
  must agree.

  Run:  nbb test/differential.cljs [path/to/ecma262.mjs]

  The artifact is built by:
    kotoba -M compile src/ecma262.kotoba --target js \\
      --fuel 200000000 --output target/ecma262.mjs

  Exit codes: 0 all agree, 1 a disagreement, 2 the harness could not answer
  (no artifact) -- which is NOT the same as agreement and must not read as one."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def cases
  ["42"
   "1 + 2 - 3"
   "1 + 2 * 3"
   "(1 + 2) * 3"
   "((2 + 3) * (4 - 1))"
   "-7 + 2"
   "17 % 5"
   "20 / 4"
   "7 / 2"
   "0 - 0"
   "1000000 * 3"
   "'hello'"
   "'a' + 'b' + 'c'"
   "'n=' + 42"
   "'' + 7"
   "'apple' < 'banana'"
   "'b' < 'aa'"
   "'abc' == 'abc'"
   "'abc' === 'abc'"
   "true"
   "false"
   "3 > 2"
   "2 >= 2"
   "1 == 1"
   "1 != 2"
   "1 === 1"
   "1 !== 2"
   "0 || 5"
   "3 || 5"
   "1 && 7"
   "0 && 7"
   "!false"
   "!''"
   "!0"
   "!'x'"
   "typeof 1"
   "typeof 'a'"
   "typeof true"
   "typeof undefined"
   "var x = 10; x"
   "let a = 2; const b = 3; a * b"
   "var x = 1; x = 5; x"
   "var x = 1; x = x + 4; x"
   "var a = 1; var b = 2; var c = 3; a + b + c"
   "var w = 4; (w + 1) * w"
   "var x = 0; if (true) { x = 1; } x"
   "var x = 0; if (1 > 2) { x = 1; } else { x = 9; } x"
   "var x = 3; if (false) { x = 99; } x"
   "var x = 0; if (1 < 2) { if (2 < 3) { x = 7; } } x"
   "var i = 0; while (i < 5) { i = i + 1; } i"
   "var i = 9; while (false) { i = 0; } i"
   "var i = 1; var s = 0; while (i <= 4) { s = s + i; i = i + 1; } s"
   "var a = 0; { a = 6; } a"
   "var n = 0; var k = 0; while (k < 10) { if (k % 2 == 0) { n = n + k; } k = k + 1; } n"
   ";;; 5 ;;;"
   "// a comment\n1 + 1 // trailing\n"
   "var s = ''; var i = 0; while (i < 3) { s = s + 'x'; i = i + 1; } s"
   ;; functions
   "function f() { return 7; } f()"
   "function add(a, b) { return a + b; } add(3, 4)"
   "function d(x) { return x * 2; } d(d(3))"
   "function fact(n) { if (n <= 1) { return 1; } return n * fact(n - 1); } fact(5)"
   "function f(x) { if (x > 0) { return 1; } return 2; } f(5)"
   "twice(4); function twice(n) { return n + n; } twice(4)"
   "function ev(n) { if (n == 0) { return 1; } return od(n - 1); } function od(n) { if (n == 0) { return 0; } return ev(n - 1); } ev(6)"
   "function f(a) { return typeof a; } f()"
   "function f(a) { return a; } f(3, 9)"
   "function f() { return 1; } typeof f"
   "function f() { return; } typeof f()"
   "function greet(who) { return 'hi ' + who; } greet('you')"
   "var g = 6; function f() { return g + 1; } f()"
   "function f() { return 3; } f(); 42"
   "function sum(n) { var t = 0; var i = 1; while (i <= n) { t = t + i; i = i + 1; } return t; } sum(10)"
   "function outer(n) { return inner(n) + 1; } function inner(n) { return n * 10; } outer(2)"
   ;; arrays and objects
   "var a = [10, 20, 30]; a[1]"
   "var a = [1, 2, 3]; a[0] + a[2]"
   "var a = [1, 2, 3, 4]; a.length"
   "var a = [1, 2, 3]; a[1] = 9; a[1]"
   "var a = [1]; a[3] = 7; a.length"
   "var a = ['x', 'y']; a[0] + a[1]"
   "var a = [1, 2, 3, 4]; var i = 0; var t = 0; while (i < a.length) { t = t + a[i]; i = i + 1; } t"
   "var a = []; a.length"
   "var a = [[1, 2], [3, 4]]; a[1][0]"
   "var o = {a: 1, b: 2}; o.b"
   "var o = {x: 5}; o['x']"
   "var o = {x: 1}; o.x = 8; o.x"
   "var o = {}; o.fresh = 3; o.fresh"
   "var o = {a: 1}; typeof o.nope"
   "var o = {inner: {deep: 6}}; o.inner.deep"
   "var a = [{v: 1}, {v: 2}]; a[1].v"
   "var o = {items: [4, 5, 6]}; o.items[2]"
   "typeof {a: 1}"
   "typeof [1]"
   "'hello'.length"
   "'' + [1, 2, 3]"
   "'' + {a: 1}"
   "function pair(a, b) { return [a, b]; } pair(3, 4)[1]"
   "function f() { return 1; } var o = {g: f}; typeof o.g"
   ;; for
   "var t = 0; for (var i = 0; i < 5; i = i + 1) { t = t + 1; } t"
   "var t = 0; for (var i = 1; i <= 4; i = i + 1) { t = t + i; } t"
   "var t = 9; for (var i = 0; i < 0; i = i + 1) { t = 0; } t"
   "var a = [2, 4, 6]; var t = 0; for (var i = 0; i < a.length; i = i + 1) { t = t + a[i]; } t"
   "var t = 0; for (var i = 0; i < 3; i = i + 1) { for (var j = 0; j < 2; j = j + 1) { t = t + 1; } } t"
   "function first(a) { for (var i = 0; i < a.length; i = i + 1) { return a[i]; } return 0; } first([7, 8])"
   "var s = ''; for (var i = 0; i < 3; i = i + 1) { s = s + 'ab'; } s"
   ;; calls in postfix position
   "function f() { return 4; } var o = {m: f}; o.m()"
   "function add(a, b) { return a + b; } var o = {plus: add}; o.plus(2, 3)"
   "function f() { return 6; } var a = [f]; a[0]()"
   "function outer() { return inner; } function inner() { return 3; } outer()()"
   "function f() { return 11; } var o = {deep: {m: f}}; o.deep.m()"
   ;; closures
   "function makeAdder(n) { function add(x) { return n + x; } return add; } makeAdder(5)(3)"
   "function make() { var secret = 42; function peek() { return secret; } return peek; } var p = make(); p()"
   "function make() { var v = 1; function get() { return v; } return get; } var g = make(); var v = 99; g()"
   "var base = 10; function f() { return base + 1; } f()"
   "function outer() { function inner(k) { return k * 3; } return inner(4); } outer()"
   "function outer(n) { function ev(k) { if (k == 0) { return 1; } return od(k - 1); } function od(k) { if (k == 0) { return 0; } return ev(k - 1); } return ev(n); } outer(4)"
   "function make() { var c = 0; function inc() { c = c + 1; return c; } return inc; } var f = make(); f(); f()"
   "function twice(g, x) { return g(g(x)); } function inc(n) { return n + 1; } twice(inc, 5)"
   ;; this
   "function get() { return this.v; } var o = {v: 7, m: get}; o.m()"
   "function sum() { return this.a + this.b; } var o = {a: 2, b: 3, go: sum}; o.go()"
   "function f() { return typeof this; } f()"
   ;; builtin methods
   "[1, 2, 3].join('-')"
   "[1, 2, 3].join()"
   "[10, 20, 30].indexOf(20)"
   "[10, 20].indexOf(99)"
   "[1, 2, 3].includes(2)"
   "[1, 2, 3, 4].slice(1, 3).join('')"
   "'hello'.charAt(1)"
   "'hello'.indexOf('ll')"
   "'hello'.indexOf('zz')"
   "'hello'.substring(1, 3)"
   "'ABC'.toLowerCase()"
   "'a,b,c'.split(',').join('-')"
   "'a,b,c'.split(',').length"
   "'hello'.includes('ell')"
   "function mine() { return 42; } var o = {indexOf: mine}; o.indexOf()"
   "var a = [3, 1, 2]; a.slice(0, 2).length"
   ;; try / catch / throw
   "try { throw 'boom'; } catch (e) { e }"
   "try { throw 42; } catch (e) { e }"
   "try { throw 7; } catch (e) { typeof e }"
   "try { 5 } catch (e) { 9 }"
   "try { nope } catch (e) { typeof e }"
   "var x = 1; try { x() } catch (e) { 3 }"
   "var t = 0; try { throw 1; } catch (e) { t = 5; } t + 1"
   "function bad() { throw 8; } try { bad() } catch (e) { e }"
   "function safe() { try { throw 2; } catch (e) { return e + 1; } } safe()"
   "try { try { throw 1; } catch (a) { throw a + 1; } } catch (b) { b }"
   "try { throw {code: 4}; } catch (e) { e.code }"])

(def known-divergences
  "Cases where this engine and a real one genuinely disagree, each with the
  reason. The set is asserted EXACTLY, so a divergence that appears or
  disappears fails the run instead of being absorbed silently."
  {"7 / 2" "JS numbers are IEEE-754 doubles; this engine's numbers are i64, so division truncates"
   "try { nope } catch (e) { typeof e }"
   "an INTERNAL error (ReferenceError, TypeError, SyntaxError) is caught as its MESSAGE STRING here. A real engine throws an Error OBJECT, which this engine has no constructor for. A thrown value keeps its own type -- `throw 42` catches a number -- so only the internal ones differ."
   "function f() { return typeof this; } f()"
   "a plain call has no receiver here, so `this` is undefined. A real engine in sloppy mode substitutes the GLOBAL object, which this engine does not have at all -- there is no globalThis to name."
   "function make() { var c = 0; function inc() { c = c + 1; return c; } return inc; } var f = make(); f(); f()"
   "closures capture BY VALUE: environments here are immutable strings, so inc updates its own copy and the outer c does not move. A read-only capture (makeAdder) agrees; a stateful counter does not."})

(defn host-eval
  "The real engine's answer, as the text this engine would print.

  `new Function(\"return eval(...)\")` rather than a bare `eval`, for two
  reasons: eval returns the COMPLETION value, which is what this engine's
  entry points report, and the wrapper gives each case its own function scope
  so a `var` in one case cannot leak into the next."
  [src]
  (try
    (let [f (js/Function. (str "return eval(" (js/JSON.stringify src) ")"))
          v (f)]
      (cond (nil? v) "undefined"
            (undefined? v) "undefined"
            (string? v) v
            (boolean? v) (if v "true" "false")
            (number? v) (if (js/Number.isInteger v) (str v) (str "NON-INTEGER:" v))
            :else (str "OTHER:" v)))
    (catch :default e (str "THROW:" (.-name e)))))

(defn -main []
  (let [artifact (or (first *command-line-args*) "target/ecma262.mjs")
        abs (path/resolve artifact)]
    (when-not (fs/existsSync abs)
      (println (str "REFUSING to report agreement: no artifact at " abs))
      (println "  build it with: kotoba -M compile src/ecma262.kotoba --target js --fuel 200000000 --output target/ecma262.mjs")
      (js/process.exit 2))
    (-> (js/import (str "file://" abs))
        (.then
         (fn [m]
           (let [inst ((.-instantiateKotoba m) #js {})
                 ev (aget inst "eval-string")
                 ok (aget inst "eval-ok")]
             (loop [[c & more] cases agree 0 diffs []]
               (if (nil? c)
                 (let [found (set (map first diffs))
                       expected (set (keys known-divergences))
                       new-diffs (remove #(contains? expected (first %)) diffs)
                       gone (remove found expected)]
                   (println (str "SCANNED\t" (count cases)))
                   (println (str agree "/" (count cases) " agree with the host engine, "
                                 (count expected) " known divergence(s)"))
                   (doseq [[src reason] known-divergences]
                     (println (str "  known: " (pr-str src) " -- " reason)))
                   (when (seq new-diffs)
                     (println "NEW DISAGREEMENTS:")
                     (doseq [[src want got] new-diffs]
                       (println (str "  " (pr-str src)
                                     "\n      host: " (pr-str want)
                                     "\n      ours: " (pr-str got)))))
                   (when (seq gone)
                     (println (str "RECORDED DIVERGENCES THAT NO LONGER DIVERGE (remove them): "
                                   (pr-str (vec gone)))))
                   (js/process.exit (if (seq (concat new-diffs gone)) 1 0)))
                 (let [want (host-eval c)
                       got (if (= 0 (ok c)) "ERROR" (ev c))
                       same (= want got)]
                   (recur more (if same (inc agree) agree)
                          (if same diffs (conj diffs [c want got])))))))))
        (.catch (fn [e]
                  (println (str "REFUSING to report agreement: " (.-message e)))
                  (js/process.exit 2))))))

(-main)
