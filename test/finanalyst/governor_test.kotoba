(ns finanalyst.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [finanalyst.store :as store]
            [finanalyst.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-security! st {:security-id "SEC-1" :client-id "client-1"
                                  :name "ACME Corp"
                                  :valid-ratings #{"buy" "hold" "sell"}
                                  :known-conflicts #{"bank-relationship"}})
    st))

(defn- rate [r disclosed]
  {:op :approve-rating :effect :propose :security-id "SEC-1"
   :rating r :disclosed-conflicts disclosed :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-valid-rating-with-full-disclosure
  (let [st (fresh-store)
        v (governor/check req {} (rate "buy" #{"bank-relationship"}) st)]
    (is (:ok? v))))

(deftest ok-with-extra-disclosures-beyond-known
  (testing "a superset of the known conflicts still satisfies disclosure"
    (let [st (fresh-store)
          v (governor/check req {} (rate "buy" #{"bank-relationship" "personal-holding"}) st)]
      (is (:ok? v)))))

(deftest hard-on-invalid-rating
  (testing "rating fabrication is not permitted"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (rate "strong-buy!!!" #{"bank-relationship"})
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :invalid-rating (:rule %)) (:violations v))))))

(deftest hard-on-undisclosed-conflict
  (testing "undisclosed conflicts are detected by set difference"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (rate "buy" #{}) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :undisclosed-conflict (:rule %)) (:violations v))))))

(deftest hard-on-unknown-security
  (let [st (fresh-store)
        v (governor/check req {} (assoc (rate "buy" #{"bank-relationship"}) :security-id "SEC-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-security (:rule %)) (:violations v)))))

(deftest hard-on-foreign-security
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (rate "buy" #{"bank-relationship"}) st)]
      (is (:hard? v))
      (is (some #(= :security-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (rate "buy" #{"bank-relationship"}) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (rate "buy" #{"bank-relationship"}) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-rating-publication
  (let [st (fresh-store)
        v (governor/check req {} {:op :publish-rating :effect :propose
                                  :security-id "SEC-1" :disclosed-conflicts #{"bank-relationship"}
                                  :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (rate "buy" #{"bank-relationship"}) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
