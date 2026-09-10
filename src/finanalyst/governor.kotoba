(ns finanalyst.governor
  "FinancialAnalystsGovernor — the independent safety/traceability
  layer for the ISCO-08 2413 community financial analysts actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.governor. Analyst
  twist: a rating is either a member of the registered valid rating
  scale or it is not, and a rating may only issue once EVERY known
  conflict of interest on that security is disclosed — undisclosed
  conflicts are detected by set difference, not editorial judgement.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. security basis     — a rating approval must cite a REGISTERED
                           security belonging to this client.
    4. rating validity    — the proposed rating must be a member of
                           the security's registered :valid-ratings
                           set (no invented rating category).
    5. conflict disclosure — the proposed disclosed-conflicts set must
                           be a superset of the security's registered
                           :known-conflicts set (no undisclosed
                           conflict).
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :publish-rating (external publication).
    7. low confidence (< `confidence-floor`)."
  (:require [clojure.set :as set]
            [finanalyst.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record sec]
  (let [{:keys [op rating disclosed-conflicts]} proposal
        approve? (= :approve-rating op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and approve? (nil? sec))
      (conj {:rule :unknown-security :detail "未登録 security へのレーティング承認は不可"})

      (and approve? sec (not= (:client-id sec) (:client-id request)))
      (conj {:rule :security-wrong-client :detail "security が別 client のもの"})

      (and approve? sec rating (not (contains? (:valid-ratings sec) rating)))
      (conj {:rule :invalid-rating
             :detail (str "レーティング " rating " は登録済みスケール "
                          (:valid-ratings sec) " の外（レーティングの捏造禁止）")})

      (and approve? sec
           (not (set/superset? (set disclosed-conflicts) (:known-conflicts sec))))
      (conj {:rule :undisclosed-conflict
             :detail (str "未開示の利益相反 "
                          (vec (set/difference (:known-conflicts sec) (set disclosed-conflicts)))
                          "（未開示利益相反は集合差で機械検出できる）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `finanalyst.store/Store`. Pure — never mutates
  the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        sec (some->> (:security-id proposal) (store/security store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record sec)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :publish-rating (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
