(ns finanalyst.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [finanalyst.actor :as actor]
            [finanalyst.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-security! st {:security-id "SEC-1" :client-id "client-1"
                                  :name "ACME Corp"
                                  :valid-ratings #{"buy" "hold" "sell"}
                                  :known-conflicts #{"bank-relationship"}})
    st))

(deftest commits-a-valid-fully-disclosed-rating
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-rating :stake :low
                 :security-id "SEC-1" :rating "buy"
                 :disclosed-conflicts #{"bank-relationship"}}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-undisclosed-conflict-rating
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-rating :stake :low
                 :security-id "SEC-1" :rating "buy" :disclosed-conflicts #{}}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-publishes-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :publish-rating :stake :high
                 :security-id "SEC-1" :disclosed-conflicts #{"bank-relationship"}}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
