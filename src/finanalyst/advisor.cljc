(ns finanalyst.advisor
  "FinancialAnalystsAdvisor — proposes a rating operation (approve a
  rating, publish a rating) for a registered organization. Swappable
  mock/llm; the advisor ONLY proposes — `finanalyst.governor` checks
  rating validity and conflict disclosure independently. Modeled on
  cloud-itonami-isco-4311's advisor.

  A proposal: {:op :approve-rating|:publish-rating
               :effect :propose :security-id str :rating str
               :disclosed-conflicts #{str} :stake kw :confidence n
               :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake security-id rating disclosed-conflicts] :as request}]
  {:op op
   :effect :propose
   :security-id security-id
   :rating rating
   :disclosed-conflicts disclosed-conflicts
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a financial analyst advisor. Given a request, propose an
   :op, the :security-id, :rating and :disclosed-conflicts, an honest
   :confidence and a :stake. Never call an invented rating or an
   undisclosed conflict conforming — the governor checks the rating
   scale and disclosure completeness.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
