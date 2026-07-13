(ns finanalyst.store
  "SSoT for the ISCO-08 2413 community financial analysts actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client   — a registered organization (:client-id, :name)
    security — a registered coverage security {:security-id
               :client-id :name :valid-ratings #{rating-str}
               :known-conflicts #{conflict-str}}. `:valid-ratings` is
               the registered rating scale a proposed rating must be a
               member of (no invented rating); `:known-conflicts` is
               the registered set of conflicts of interest that MUST
               all be disclosed before a rating may issue.
    record   — a committed operating record (approved rating) —
               written ONLY via commit-record!.
    ledger   — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (security [s security-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-security! [s sec])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (security [_ security-id] (get-in @a [:securities security-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-security! [s sec]
    (swap! a assoc-in [:securities (:security-id sec)] sec) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :securities {} :records [] :ledger []}
                                   seed)))))
