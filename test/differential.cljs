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
   "var s = ''; var i = 0; while (i < 3) { s = s + 'x'; i = i + 1; } s"])

(def known-divergences
  "Cases where this engine and a real one genuinely disagree, each with the
  reason. The set is asserted EXACTLY, so a divergence that appears or
  disappears fails the run instead of being absorbed silently."
  {"7 / 2" "JS numbers are IEEE-754 doubles; this engine's numbers are i64, so division truncates"})

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
