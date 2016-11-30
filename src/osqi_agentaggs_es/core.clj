(ns osqi-agentaggs-es.core
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [torna.core :as torna]
            [clojure.tools.cli :refer (parse-opts)]
            [osqi-agentaggs-es.agentaggs :as agentaggs])
  (:gen-class))

(def cli-options
  [["-c" "--config-file CONFIG-FILE" "Config file "]
   ["-h" "--help"]])

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn  load-resource
  [config-file]
  (let [thr (Thread/currentThread)
        ldr (.getContextClassLoader thr)]
    (read-string (slurp (.getResourceAsStream ldr config-file)))))

(defn -main
  "main func "
  [& args]
  (let [{:keys [options arguments summary errors]} (parse-opts args cli-options)]
    (cond
      (:help options) (exit 0 summary)
      (not (:config-file options)) (exit 1 (str "config-file not passed usage=" summary))
      errors (exit 2 error-msg errors))
    (let [{:keys [config-file]} options
          read-props (load-resource config-file)
          redis-conn {:pool {} :spec {:host (read-props :redis.host) :port (read-props :redis.port)}}
          cprops (assoc read-props :redis-conn redis-conn)]
      (log/info "running with config-file=" config-file " edn.data=" cprops)
      (try
        (agentaggs/init cprops)
        (torna/read-kafka cprops agentaggs/handle-kafka-batch)
        (catch Exception e
          (do
            (.printStackTrace e)
            (System/exit 2)))))))
