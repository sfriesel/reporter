(ns spootnik.reporter
  (:require [com.stuartsierra.component :as c]
            [schema.core                :as s]
            [net.http.client            :as http]
            [raven.client               :as raven]
            [metrics.reporters.console  :as console]
            [metrics.reporters.jmx      :as jmx]
            [metrics.reporters.graphite :as graphite]
            [metrics.reporters.riemann  :as riemann]
            [metrics.jvm.core           :as jvm]
            [metrics.core               :as m]
            [metrics.gauges             :as gge]
            [metrics.counters           :as cnt]
            [metrics.meters             :as mtr]
            [metrics.timers             :as tmr]
            [metrics.histograms         :as hst]
            [clojure.tools.logging      :refer [info error]]
            [spootnik.uncaught          :refer [with-uncaught]]))

(defprotocol RiemannSink
  (send! [this e]))

(defprotocol SentrySink
  (capture! [this e]))

(defprotocol MetricHolder
  (instrument! [this prefix])
  (build! [this type alias] [this type alias f])
  (inc! [this alias] [this alias v])
  (dec! [this alias] [this alias v])
  (mark! [this alias])
  (update! [this alias v])
  (time-fn! [this alias f])
  (start! [this alias])
  (stop! [this alias]))

(def config-schema
  (let [report-i {:interval              s/Num
                  (s/optional-key :opts) s/Any}
        report   {(s/optional-key :opts) s/Any}
        ssl      (s/either  {:bundle    s/Str
                             :password  s/Str}
                            {:cert      s/Str
                             :authority s/Str
                             :pkey      s/Str})
        http     {(s/optional-key :ssl)               ssl
                  (s/optional-key :disable-epoll)     s/Bool
                  (s/optional-key :logging)           s/Keyword
                  (s/optional-key :loop-thread-count) s/Num}]
    {(s/optional-key :sentry)  {:dsn                   s/Str
                                (s/optional-key :http) http}
     (s/optional-key :metrics) {:reporters {(s/optional-key :console)  report-i
                                            (s/optional-key :riemann)  report-i
                                            (s/optional-key :graphite) report-i
                                            (s/optional-key :jmx)      report
                                            }}
     (s/optional-key :riemann) {:host                      s/Str
                                (s/optional-key :batch)    s/Num
                                (s/optional-key :defaults) s/Any}}))

(def config-validator
  (s/validator config-schema))

(defn riemann-client
  "To keep dependency conflicts, let's use RiemannClient directly."
  [{:keys [host batch] :as opts}]
  (let [port (or (:port opts) 5555)
        tags (set (:tags opts))
        tcp? (if-let [p (some-> opts :protocol keyword #{:tcp :udp})]
               (= :tcp p)
               true)]
    (try
      (let [client (if tcp?
                     (com.aphyr.riemann.client.RiemannClient/tcp host (int port))
                     (com.aphyr.riemann.client.RiemannClient/udp host (int port)))]
        (if batch
          (com.aphyr.riemann.client.RiemannBatchClient. client (int batch))
          client))
      (catch Exception e
        (error e "cannot connect to riemann")))))

(defn riemann-event!
  [client defaults {:keys [host service time metric description] :as ev}]
  (let [tags  (set (concat (:tags defaults) (:tags ev)))
        attrs (merge (:attrs defaults) (:attrs ev))
        ttl   (if-let [t (or (:ttl ev) (:ttl defaults))] (float t))
        state (or (:state defaults) (:state ev))]
    (-> (.event client)
        (.host (or host (:host defaults) (raven/localhost)))
        (.service (or service (:service defaults) "<none>"))
        (.time (or time (quot (System/currentTimeMillis) 1000)))
        (cond-> metric      (.metric metric)
                state       (.state state)
                (seq attrs) (.attributes attrs)
                (seq tags)  (.tags tags)
                ttl         (.ttl ttl)
                description (.description description))
        (.send)
        (.deref 100 java.util.concurrent.TimeUnit/MILLISECONDS))))

(defn build-metrics-reporters
  [reg reporters rclient]
  (info "building metrics reporters")
  (vec
   (for [[k {:keys [opts interval]}] reporters]
     (case k
       :console (let [r (console/reporter reg opts)]
                  (info "building console reporter")
                  (reify
                    c/Lifecycle
                    (start [this] (console/start r interval) this)
                    (stop [this]
                      (info "scheduling a final console report")
                      (.report r)
                      (console/stop r)
                      this)))
       :jmx      (let [r (jmx/reporter reg opts)]
                  (info "building jmx reporter")
                   (reify
                     c/Lifecycle
                     (start [this] (jmx/start r) this)
                     (stop [this]  (jmx/stop r) this)))
       :graphite (let [r (graphite/reporter reg opts)]
                   (info "building graphite reporter")
                   (reify
                     c/Lifecycle
                     (start [this] (graphite/start r interval) this)
                     (stop [this]  (graphite/stop r) this)))
       :riemann   (if-not rclient
                    (throw (ex-info "need a valid riemann client to build reporter" {}))
                    (let [r (riemann/reporter rclient reg opts)]
                      (info "building riemann reporter")
                       (reify
                         c/Lifecycle
                         (start [this] (riemann/start r interval) this)
                         (stop [this]
                           (info "scheduling a final report of our metrics")
                           (.report r)
                           (riemann/stop r)
                           this))))
       (throw (ex-info "invalid metrics reporter" {}))))))

(defn build-metrics
  [{:keys [reporters]} rclient]
  (when reporters
    (let [reg  (m/new-registry)
          reps (build-metrics-reporters reg reporters rclient)]
      (doseq [r reps]
        (c/start r))
      [reg reps])))

(defn ->alias
  [v]
  (if (sequential? v)
    (map name v)
    (name v)))

(defrecord Reporter [rclient raven reporters registry sentry metrics riemann]
  c/Lifecycle
  (start [this]
    (let [rclient    (when riemann (riemann-client riemann))
          [reg reps] (build-metrics metrics rclient)
          raven      (when sentry (http/build-client (:http sentry)))]
      (when raven
        (with-uncaught e
          (capture! (assoc this :raven raven) e)))
      (assoc this
             :registry  reg
             :reporters reps
             :rclient   rclient
             :raven     raven)))
  (stop [this]
    (with-uncaught e
      (error e "uncaught exception."))
    (doseq [r reporters]
      (c/stop r))
    (when registry
      (info "shutting down metric registry")
      (m/remove-all-metrics registry))
    (when rclient
      (try
        (.close rclient)
        (catch Exception _)))
    (assoc this :raven nil :reporters nil :registry nil :rclient nil))
  MetricHolder
  (instrument! [this prefix]
    (when registry
      (doseq [[f title] [[jvm/register-jvm-attribute-gauge-set ["jvm" "attribute"]]
                         [jvm/register-memory-usage-gauge-set ["jvm" "memory"]]
                         [jvm/register-file-descriptor-ratio-gauge-set ["jvm" "file"]]
                         [jvm/register-garbage-collector-metric-set ["jvm" "gc"]]
                         [jvm/register-thread-state-gauge-set ["jvm" "thread"]]]]
        (f registry (mapv name (concat prefix title))))))
  (build! [this type alias f]
    (when registry
      (assert (fn? f))
      (case type
        :gauge (or (gge/gauge registry (->alias alias))
                   (gge/gauge-fn registry (->alias alias) f))
        (throw (ex-info "invalid metric type" {})))))
  (build! [this type alias]
    (when registry
      (case type
        :counter   (cnt/counter registry (->alias alias))
        :meter     (mtr/meter registry (->alias alias))
        :histogram (hst/histogram registry (->alias alias))
        :timer     (tmr/timer registry (->alias alias))
        (throw (ex-info "invalid metric type" {})))))
  (inc! [this alias]
    (when registry
      (cnt/inc! (cnt/counter registry (->alias alias)))))
  (inc! [this alias v]
    (when registry
      (cnt/inc! (cnt/counter registry (->alias alias)) v)))
  (dec! [this alias]
    (when registry
      (cnt/dec! (cnt/counter registry (->alias alias)))))
  (dec! [this alias v]
    (when registry
      (cnt/dec! (cnt/counter registry (->alias alias)) v)))
  (mark! [this alias]
    (when registry
      (mtr/mark! (mtr/meter registry (->alias alias)))))
  (update! [this alias v]
    (when registry
      (hst/update! (hst/histogram registry (->alias alias)) v)))
  (time-fn! [this alias f]
    (if registry
      (tmr/time-fn! (tmr/timer registry (->alias alias)) f)
      (f)))
  (start! [this alias]
    (when registry
      (tmr/start (tmr/timer registry (->alias alias)))))
  (stop! [this alias]
    (when registry
      (tmr/stop (tmr/timer registry (->alias alias)))))
  SentrySink
  (capture! [this e]
    (error e "uncaught exception")
    (when raven
      (try
        (raven/capture! raven (:dsn sentry) e)
        (catch Exception e
          (error e "could not send capture")))))
  RiemannSink
  (send! [this ev]
    (when rclient
      (riemann-event! rclient (:defaults riemann) ev))))

(defmacro time!
  [reporter alias & body]
  `(time-fn! ~reporter ~alias (fn [] (do ~@body))))

(defmacro with-time!
  [[reporter alias] & body]
  `(time-fn! ~reporter ~alias (fn [] (do ~@body))))

(defn make-reporter
  ([]
   (map->Reporter nil))
  ([reporter]
   (map->Reporter reporter)))
