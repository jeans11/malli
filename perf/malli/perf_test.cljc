(ns malli.perf-test
  (:require [clojure.spec.alpha :as s]
            [criterium.core :as cc]
            [malli.core :as m]
            [spec-tools.core :as st]
            [malli.transform :as transform]))

(s/def ::x boolean?)
(s/def ::y int?)
(s/def ::z string?)

(defn map-perf []

  (let [valid {:x true, :y 1, :z "kikka"}]

    ;; 18ns
    (let [valid? (fn [m]
                   (and (if-let [v (:x m)] (boolean? v) false)
                        (if-let [v (:y m)] (int? v) true)
                        (if-let [v (:z m)] (string? v) false)))]
      (assert (valid? valid))
      (cc/quick-bench
        (valid? valid)))

    ;; 37ns
    (let [valid? (m/validator [:map
                               [:x boolean?]
                               [:y {:optional true} int?]
                               [:z string?]])]
      (assert (valid? valid))
      (cc/quick-bench
        (valid? valid)))

    ;; 400ns
    (let [spec (s/keys :req-un [::x ::z] :opt-un [::y])]
      (assert (s/valid? spec valid))
      (cc/quick-bench
        (s/valid? spec valid)))))

(defn composite-perf []

  ;; 3ns
  (let [valid? (fn [x] (and (int? x) (or (pos-int? x) (neg-int? x))))]
    (assert (= [true false true] (map valid? [-1 0 1])))
    (cc/quick-bench
      (valid? 0)))

  ;; 5ns
  (let [valid? (m/validator [:and int? [:or pos-int? neg-int?]])]
    (assert (= [true false true] (map valid? [-1 0 1])))
    (cc/quick-bench
      (valid? 0)))

  ;; 40ns
  (let [spec (s/and int? (s/or :pos-int pos-int? :neg-int neg-int?))]
    (assert (= [true false true] (map (partial s/valid? spec) [-1 0 1])))
    (cc/quick-bench
      (s/valid? spec 0))))

(defn composite-perf2 []
  (let [assert! (fn [f]
                  (doseq [[expected data] [[true [-1]]
                                           [true [-1 1 2]]
                                           [false [-1 0 2]]
                                           [false [-1 -1 -1 -1]]]]
                    (assert (= expected (f data)))))]

    ;; 155ns
    (let [valid? (fn [x]
                   (and (vector? x)
                        (<= (count x) 3)
                        (every? #(and (int? %) (or (pos-int? %) (neg-int? %))) x)))]
      (assert! valid?)
      (cc/quick-bench
        (valid? [-1 1 2])))

    ;; 27ns
    (let [valid? (m/validator
                   [:vector {:max 3}
                    [:and int? [:or pos-int? neg-int?]]])]
      (assert! valid?)
      (cc/quick-bench
        (valid? [-1 1 2])))

    ;; 506ns
    (let [spec (s/coll-of
                 (s/and int? (s/or :pos-int pos-int? :neg-int neg-int?))
                 :kind vector?
                 :max-count 3)
          valid? (partial s/valid? spec)]
      (assert! valid?)
      (cc/quick-bench
        (valid? [-1 1 2])))))

(s/def ::id string?)
(s/def ::tags (s/coll-of keyword? :kind set? :into #{}))
(s/def ::street string?)
(s/def ::city string?)
(s/def ::zip int?)
(s/def ::lonlat (s/coll-of double? :min-count 2, :max-count 2))
(s/def ::address (s/keys
                   :req-un [::street ::city ::zip]
                   :opt-un [::lonlat]))
(s/def ::place (s/keys :req-un [::id ::tags ::address]))

(def Place
  [:map
   [:id string?]
   [:tags [:set keyword?]]
   [:address
    [:map
     [:street string?]
     [:city string?]
     [:zip int?]
     [:lonlat {:optional true} [:tuple double? double?]]]]])

(defn composite-explain-perf []
  (let [valid {:id "Metosin"
               :tags #{:clj :cljs}
               :address {:street "Hämeenkatu 14"
                         :city "Tampere"
                         :zip 33800
                         :lonlat [61.4983866 23.7644223]}}
        invalid {:id "Metosin"
                 :tags #{"clj" "cljs"}
                 :address {:street "Hämeenkatu 14"
                           :zip 33800
                           :lonlat [61.4983866 nil]}}]

    (let [explain #(s/explain-data ::place %)]

      ;; 5.0µs
      (cc/quick-bench (explain valid))

      ;; 19µs
      (cc/quick-bench (explain invalid)))

    (let [explain (m/explainer Place)]
      (assert (not (explain valid)))
      (assert (explain invalid))

      ;; 1.2µs
      (cc/quick-bench (explain valid))

      ;; 1.4µs
      (cc/quick-bench (explain invalid)))))

(defn transform-test []
  (let [json {:id "Metosin"
              :tags #{"clj" "cljs"}
              :address {:street "Hämeenkatu 14"
                        :zip 33800
                        :lonlat [61.4983866 23.7644223]}}]

    (let [json->place #(st/coerce ::place % st/json-transformer)]
      (clojure.pprint/pprint (json->place json))

      ;; 74µs
      (cc/quick-bench (json->place json)))

    (let [json->place (m/transformer Place transform/json-transformer)]
      (clojure.pprint/pprint (json->place json))

      ;; 1µs
      (cc/quick-bench (json->place json)))))

(defn transform-test2 []

  ;;
  ;; predicate coercion
  ;;

  ;; 6µs
  (let [string->edn #(st/coerce int? % st/string-transformer)]
    (assert (= 1
               (string->edn "1")
               (string->edn 1)))
    (cc/quick-bench
      (string->edn "1")))

  ;; 4ns
  (let [string->edn (m/transformer int? transform/string-transformer)]
    (assert (= 1
               (string->edn "1")
               (string->edn 1)))
    (cc/quick-bench
      (string->edn "1")))

  ;;
  ;; simple map coercion
  ;;

  (s/def ::id int?)
  (s/def ::name string?)

  ;; 14µs
  (let [spec (s/keys :req-un [::id ::name])
        string->edn #(st/coerce spec % st/string-transformer)]
    (assert (= {:id 1, :name "kikka"}
               (string->edn {:id 1, :name "kikka"})
               (string->edn {:id "1", :name "kikka"})))
    (cc/quick-bench
      (string->edn {:id "1", :name "kikka"})))

  ;; 140ns
  (let [schema [:map [:id int?] [:name string?]]
        string->edn (m/transformer schema transform/string-transformer)]
    (assert (= {:id 1, :name "kikka"}
               (string->edn {:id 1, :name "kikka"})
               (string->edn {:id "1", :name "kikka"})))
    (cc/quick-bench
      (string->edn {:id "1", :name "kikka"})))

  ;;
  ;; no-op coercion
  ;;

  ;; 15µs
  (let [spec (s/keys :req-un [::id ::name])
        string->edn #(st/coerce spec % st/json-transformer)]
    (assert (= {:id 1, :name "kikka"}
               (string->edn {:id 1, :name "kikka"})))
    (cc/quick-bench
      (string->edn {:id 1, :name "kikka"})))

  ;; 3.0ns
  (let [schema [:map [:id int?] [:name string?]]
        string->edn (m/transformer schema transform/json-transformer)]
    (assert (= {:id 1, :name "kikka"}
               (string->edn {:id 1, :name "kikka"})))
    (cc/quick-bench
      (string->edn {:id 1, :name "kikka"}))))

(def tests
  [;; 1.7ns
   [int? 1]
   ;; 5.8ns
   [[:and int? [:> 2]] 3]
   ;; 107ns
   [[:vector int?] [1 2 3]]
   ;; 17ns
   [[:map [:x int?] [:y boolean?]] {:x 1, :y true}]])

(defn basic-perf []
  (doseq [[schema value] tests
          :let [validator (m/validator schema)]]
    (println)
    (println (m/form schema))
    (println "-------------")
    (cc/quick-bench (validator value))))

(comment
  (map-perf)
  (composite-perf)
  (composite-perf2)
  (composite-explain-perf)
  (basic-perf)
  (transform-test)
  (transform-test2))
