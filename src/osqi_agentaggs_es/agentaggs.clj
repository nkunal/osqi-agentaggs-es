(ns osqi-agentaggs-es.agentaggs
  (:require [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [clj-time.format :as tf]
            [clj-time.coerce :as tcoerce]
            [clojure.string :refer [lower-case]]
            [clojurewerkz.elastisch.rest.document :as esdoc]
            [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.bulk :as esb]
            [taoensso.carmine :as car :refer (wcar)]
            [clj-redis.client :as redis]
            [clj-kafka.producer :as kproducer])
  (:gen-class))

(def redis-db (atom nil))
(def kafka-producer (atom nil))
(def ignore-names #{"uptime" "processes"})

(defn agg-listening-ports1
  [props data-item host-id unix-time action columns]
  (let [redis-conn (props :redis-conn)
        entry-key (str (columns "port") "-"
                       (columns "address") "-"
                       (columns "protocol") "-"
                       (columns "family"))
        l-key (str "aggs:listening_ports:" host-id)
        tmp-l-val (wcar car/get l-key)
        l-val (if tmp-l-val tmp-l-val {})]
    (prn "agg-listening-ports: l-key=" l-key ", l-val=" l-val ", entry-key=" entry-key)
    (if (= action "added")
      (wcar car/set l-key (assoc l-val entry-key columns))
      (wcar car/set l-key (dissoc l-val entry-key)))))

(defn log-errors
  [bulk-resp]
  (when (get bulk-resp :errors)
    (let [items (bulk-resp :items)]
      (doseq [entry items]
        (when (> ((entry :index) :status) 299)
          (log/error "indexing error item =" entry))))))

(defn insert-es
  [props kafka-val host-id unix-time name]
  (let [conn (esr/connect (get props :es.url))
        es-docs (assoc kafka-val
                       "hostIdentifier" host-id
                       "unixTime" unix-time)
        doc-resp (esdoc/create conn "agentstate" name es-docs :_id (str host-id "-" unix-time))]
    (when (get doc-resp :errors)
      (log-errors doc-resp))))


(defn agg-listening-ports
  [props data-item host-id unix-time action columns]
  (let [entry-key (str (columns "port") "-"
                       (columns "address") "-"
                       (columns "protocol") "-"
                       (columns "family"))
        l-key (str "aggs:listening_ports:" host-id)
        tmp-l-val (redis/get @redis-db l-key)
        l-val (if tmp-l-val (read-string tmp-l-val) {})
        final-val (if (= action "added")
                    (assoc l-val entry-key columns)
                    (dissoc l-val entry-key))
        final-val-str (str final-val)
        aggtable (doall (for [[k v] final-val] v))]
;    (prn "agg-listening-ports: l-key=" l-key ", l-val=" l-val ", entry-key=" entry-key)
    (redis/set @redis-db l-key final-val-str)
    (let [kafka-val {"hostIdentifier" host-id
                     "unixTime" unix-time
                     "name" "listening_ports"
                     "state" aggtable}]
      (clojure.pprint/pprint kafka-val)
      (insert-es props kafka-val host-id unix-time "listening_ports"))))
;    (kproducer/send-message @kafka-producer (kproducer/message topic (.getBytes kafka-val)))



(defn process-data-block
  [props data-block]
  (try
    (doseq [data-item data-block]
      (let [name (data-item "name")
            host-id (data-item "hostIdentifier")
            unix-time (data-item "unixTime")
            action (data-item "action")
            columns (data-item "columns")]
        (cond
          (= name "listening_ports") (agg-listening-ports props data-item host-id unix-time action columns)
          (contains? ignore-names name)  nil
          :else (prn "no handler for name: " name))))
    (catch Exception e
      (do
        (log/error "process-item: caught exception for event " data-block)
        (throw e)))))

(defn process-batch
  [props json-docs]
  (doseq [item @json-docs]
    (process-data-block props (item "data"))))

(defn handle-kafka-batch
  " This function can be called from torna"
  ([props json-docs]
   (process-batch props json-docs)))

(defn init
  [props]
  (let [db (redis/init {:uri (format "redis://%s:%s" (props :redis.host) (props :redis.port))})
        prods (kproducer/producer {"metadata.broker.list" (get props :broker.list)
                                   "serializer.class" "kafka.serializer.DefaultEncoder"
                                   "partitioner.class" "kafka.producer.DefaultPartitioner"})]
    (reset!  redis-db db)
    (reset! kafka-producer prods)))
