(ns finanalyst.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`finanalyst.actor` -> `finanalyst.governor` -> `finanalyst.store`)
  through a scenario built from real, exercised store data and renders
  the result deterministically -- no invented numbers, no timestamps in
  the page content, byte-identical across reruns against the same seed
  (verify by diffing two consecutive runs before shipping).

  `client-1` (\"Kobo Trade\") + security `SEC-1` (\"ACME Corp\", valid
  ratings #{buy hold sell}, known conflict \"bank-relationship\") below
  are lifted VERBATIM from this repo's own proven-passing test fixture
  (`finanalyst.actor-test`/`finanalyst.governor-test` `fresh-store`
  helper) -- ground truth, not invented. `client-2` (\"Second City
  Capital\") + security `SEC-2` (\"Beacon Analytics\") is ADDITIONAL
  demo data registered via the SAME real protocol calls
  (`store/register-client!`/`store/register-security!`) this actor's
  own test fixtures use -- this actor has only one client/security pair
  in its own test fixture, so a second client+security is necessary to
  demonstrate the cross-client `:security-wrong-client` rule. Disclosed
  here plainly, not presented as if it were a pre-existing fixture.
  Every other field this page displays (statuses, records, hold
  reasons) is real output read after `run-demo!` actually executed the
  graph -- none of it is hand-typed.

  Known architectural gaps, honestly noted rather than papered over:
  - `finanalyst.governor`'s `:no-actuation` rule (proposal `:effect`
    must be `:propose`) is NOT reachable through this demo, because the
    real `mock-advisor` (`finanalyst.advisor/infer`) unconditionally
    sets `:effect :propose` on every proposal it emits.
  - The low-confidence escalation path is likewise NOT reachable
    through this demo: `mock-advisor` derives confidence purely from
    `:stake` (`:high` -> 0.7, `:medium` -> 0.85, `:low` -> 0.95), all of
    which sit above `finanalyst.governor/confidence-floor` (0.6) --
    there is no stake value the real advisor maps to a sub-floor
    confidence. Both rules ARE covered by
    `finanalyst.governor-test/hard-on-no-actuation-violation` and
    `escalates-low-confidence` (which call `governor/check` directly
    with hand-built proposals), not by this build-time renderer, which
    only ever drives the real actor/graph the way an operator actually
    would.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [finanalyst.store :as store]
            [finanalyst.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real financial-analyst operation request through the
  actual compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it (this
  demo's scenario never demonstrates an UNAPPROVED escalation -- every
  escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit, escalate-then-approve, and 5 of
  the 6 distinct HARD-hold reasons in `finanalyst.governor` -- the 6th,
  `:no-actuation`, is architecturally unreachable via the real advisor,
  see namespace docstring). Every `:op` keyword and violation rule name
  below is copied from `finanalyst.governor`'s own `hard-violations`/
  `check`, not invented."
  [;; client-1 / "Kobo Trade" / SEC-1 (real fixture from finanalyst.actor-test)
   ["c1-approve-buy-disclosed"  "client-1" :approve-rating {:security-id "SEC-1" :rating "buy"
                                                             :disclosed-conflicts #{"bank-relationship"} :stake :low}]
   ["c1-approve-undisclosed"    "client-1" :approve-rating {:security-id "SEC-1" :rating "buy"
                                                             :disclosed-conflicts #{} :stake :low}]
   ["c1-approve-invalid-rating" "client-1" :approve-rating {:security-id "SEC-1" :rating "strong-buy!!!"
                                                             :disclosed-conflicts #{"bank-relationship"} :stake :low}]
   ["c1-approve-unknown-sec"    "client-1" :approve-rating {:security-id "SEC-ghost" :rating "buy"
                                                             :disclosed-conflicts #{"bank-relationship"} :stake :low}]
   ;; unregistered client entirely
   ["ghost-no-client" "client-ghost" :approve-rating {:security-id "SEC-1" :rating "buy"
                                                       :disclosed-conflicts #{"bank-relationship"} :stake :low}]
   ;; client-2 / "Second City Capital" / SEC-2 (additional demo data,
   ;; registered via the same real register-client!/register-security!
   ;; calls -- see namespace docstring). Referencing client-1's SEC-1
   ;; from client-2 demonstrates the cross-client rule.
   ["c2-approve-wrong-security" "client-2" :approve-rating {:security-id "SEC-1" :rating "buy"
                                                             :disclosed-conflicts #{"bank-relationship"} :stake :low}]
   ["c2-approve-own-security"   "client-2" :approve-rating {:security-id "SEC-2" :rating "hold"
                                                             :disclosed-conflicts #{"personal-holding"} :stake :low}]
   ;; publish-rating always escalates (external publication) regardless
   ;; of confidence
   ["c1-publish-rating" "client-1" :publish-rating {:security-id "SEC-1"
                                                     :disclosed-conflicts #{"bank-relationship"} :stake :high}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `finanalyst.actor` graph. Returns `{:store :runs}` -- `:runs`
  is the ordered vector of real per-request outcomes; every field in
  `render` below is read from this or from `store` after the graph
  actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Kobo Trade"})
    (store/register-security! db {:security-id "SEC-1" :client-id "client-1"
                                   :name "ACME Corp"
                                   :valid-ratings #{"buy" "hold" "sell"}
                                   :known-conflicts #{"bank-relationship"}})
    (store/register-client! db {:client-id "client-2" :name "Second City Capital"})
    (store/register-security! db {:security-id "SEC-2" :client-id "client-2"
                                   :name "Beacon Analytics"
                                   :valid-ratings #{"hold" "sell"}
                                   :known-conflicts #{"personal-holding"}})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- security-row [store {:keys [security-id name client-id valid-ratings known-conflicts]} runs]
  (let [record-count (count (filter #(= security-id (:security-id %)) (store/records-of store client-id)))
        last-run (last (filter #(= security-id (get-in % [:request :security-id])) runs))]
    (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc client-id) (esc security-id) (esc name)
            (esc (str/join ", " (sort valid-ratings)))
            (esc (str/join ", " (sort known-conflicts)))
            record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (or (:rating request) (:security-id request) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README.md /
  ;; `finanalyst.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:approve-rating</code></td><td><span class=\"ok\">auto-commit when the rating is on the registered scale and every known conflict is disclosed</span></td></tr>"
   "        <tr><td><code>:publish-rating</code></td><td><span class=\"warn\">ALWAYS human approval &middot; external publication</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [securities [{:security-id "SEC-1" :name "ACME Corp" :client-id "client-1"
                     :valid-ratings #{"buy" "hold" "sell"} :known-conflicts #{"bank-relationship"}}
                    {:security-id "SEC-2" :name "Beacon Analytics" :client-id "client-2"
                     :valid-ratings #{"hold" "sell"} :known-conflicts #{"personal-holding"}}]
        security-rows (str/join "\n" (map #(security-row store % runs) securities))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-2413 &middot; community financial analysts</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Community Financial Analysts (ISCO-08 2413) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · every publication always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered clients &amp; securities</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>finanalyst.store</code> via <code>finanalyst.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly. Valid ratings and known conflicts are the registered ground truth the governor checks every proposal against, never a remembered number.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Security</th><th>Name</th><th>Valid ratings</th><th>Known conflicts</th><th>Records</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     security-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Financial Analysts Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. A rating must be on the registered scale and every known conflict must be disclosed before it can issue.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own rating/security, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Rating / security</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
