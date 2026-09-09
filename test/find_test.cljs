;; test/find_test.cljs — build the command and compare it with the system
;; find, as a SORTED SET rather than a sequence.
;;
;; The order cannot be matched and that is not this implementation's doing.
;; /usr/bin/find emits entries in readdir order, which is neither sorted nor
;; stable across filesystems; wire 34 answers its listing sorted by bytes and
;; has no request form that asks for readdir order. Measured 2026-09-10 on a
;; directory holding `a`, `c` and `f1.txt`, find emitted `f1.txt` BEFORE `a`.
;;
;; So both outputs are sorted before comparison, and the README says the same.
;; On the fixture below the two sequences happen to agree exactly -- that is a
;; coincidence of how the files were created, not a property, and it is not
;; asserted.
;;
;;   AMU_HOME=<amu checkout> nbb test/find_test.cljs
;;
;; Exit 0 when every case matches, 1 on a difference, 2 when it could not run.
(ns find-test
  (:require [clojure.string :as str] ["fs" :as fs] ["path" :as path] ["os" :as os]))

(def cp (js/require "node:child_process"))

(defn- run [cmd args]
  (let [r (.spawnSync cp cmd (clj->js args) #js {:encoding "buffer"})]
    {:status (.-status r) :out (.-stdout r) :err (.-stderr r)}))

(defn- refuse [message]
  (println (pr-str {:ok false :phase :setup :message message}))
  (.exit js/process 2))

(def amu-home (.-AMU_HOME js/process.env))
(def system-find "/usr/bin/find")

;; The tree. `f1.txt` sits beside directories on purpose: it is what showed
;; that find's order is readdir order and not sorted.
(def tree
  {"f1.txt" "" "a/f2.txt" "" "a/b/f3.log" "" "a/b/deep.txt" ""
   "c/f4.txt" "" "x/g.log" "" "x/y/z/deep2.txt" ""
   ;; An EMPTY directory: it is a path find reports and has no entries to
   ;; recurse into, which is the case a walk that only pushes non-empty
   ;; directories gets wrong.
   "empty-dir/.keep" nil})

(def cases [[] ["-type" "f"]])

(when-not amu-home (refuse "set AMU_HOME to an amu checkout"))
(let [amu (.join path amu-home "bin" "amu")
      packager (.join path amu-home "scripts" "package-command.cljs")]
  (when-not (.existsSync fs amu) (refuse (str "no amu at " amu)))
  (when-not (.existsSync fs system-find) (refuse (str "no " system-find)))
  (let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "org-ieee-find-"))
        data (.join path tmp "data")
        src (.resolve path (.cwd js/process) "find" "core.kotoba")
        policy (.join path tmp "policy.edn")
        kexe (.join path tmp "find.kexe")
        blob (.join path tmp "find.bin")
        exe (.join path tmp "find")]
    (.mkdirSync fs data)
    (doseq [[rel content] tree]
      (let [full (.join path data rel)]
        (.mkdirSync fs (.dirname path full) #js {:recursive true})
        (when content (.writeFileSync fs full content "utf8"))))
    (.writeFileSync fs policy
                    "{:allow #{[:cap/call 34] [:cap/call 37] [:cap/call 38] [:cap/call 39]}}"
                    "utf8")
    (let [c (run "node" [amu "compile" src "--target" "aarch64-macos" "--jvm-free"
                         "--policy" policy "--output" kexe])]
      (when (not= 0 (:status c)) (refuse (str "compile failed: " (str (:err c)) (str (:out c))))))
    (let [e (run "node" [amu "extract-native" kexe "--symbol" "main" "--output" blob])
          _ (when (not= 0 (:status e)) (refuse (str "extract failed: " (str (:err e)))))
          offset (second (re-find #":offset (\d+)" (str (:out e))))]
      (when-not offset (refuse "no :offset in the extract report"))
      (let [p (run "nbb" [packager "--code" blob "--offset" offset "--isa" "aarch64"
                          "--allow" "34,37,38,39"
                          "--browse-scope" (.realpathSync fs data)
                          "--fuel" "50000000" "--pairs" "200000"
                          "--string-pool" "8000000" "--output" exe])]
        (when (not= 0 (:status p)) (refuse (str "package failed: " (str (:err p)))))))
    (let [root (.realpathSync fs data)
          lines (fn [b] (sort (remove str/blank? (str/split (.toString b "utf8") #"\n"))))
          results
          (for [flags cases]
            (let [k (run exe (into [root] flags))
                  s (run system-find (into [root] flags))
                  ours (lines (:out k))
                  theirs (lines (:out s))]
              {:flags flags :ok (and (= ours theirs) (= (:status k) (:status s)))
               :count (count ours)
               :only-ours (take 3 (remove (set theirs) ours))
               :only-theirs (take 3 (remove (set ours) theirs))}))
          bad (remove :ok results)]
      (doseq [r results]
        (println (str (if (:ok r) "  ok   " "  FAIL ")
                      (pr-str (:flags r)) " -> " (:count r) " paths"
                      (when-not (:ok r)
                        (str "  only-ours=" (pr-str (:only-ours r))
                             " only-theirs=" (pr-str (:only-theirs r)))))))
      (println (pr-str {:ok (empty? bad) :cases (count results) :failed (count bad)}))
      (.exit js/process (if (seq bad) 1 0)))))
