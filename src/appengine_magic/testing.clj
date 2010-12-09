(ns appengine-magic.testing
  (:import [com.google.appengine.tools.development.testing LocalServiceTestHelper
            LocalServiceTestConfig
            LocalMemcacheServiceTestConfig LocalMemcacheServiceTestConfig$SizeUnit
            LocalMailServiceTestConfig
            LocalDatastoreServiceTestConfig]))


(def *memcache-size-units*
     {:bytes LocalMemcacheServiceTestConfig$SizeUnit/BYTES
      :kb LocalMemcacheServiceTestConfig$SizeUnit/KB
      :mb LocalMemcacheServiceTestConfig$SizeUnit/MB})


(def *logging-levels*
     {:all java.util.logging.Level/ALL
      :severe java.util.logging.Level/SEVERE
      :warning java.util.logging.Level/WARNING
      :info java.util.logging.Level/INFO
      :config java.util.logging.Level/CONFIG
      :fine java.util.logging.Level/FINE
      :finer java.util.logging.Level/FINER
      :finest java.util.logging.Level/FINEST
      :off java.util.logging.Level/OFF})


(defn memcache [& {:keys [max-size size-units]}]
  (let [lmstc (LocalMemcacheServiceTestConfig.)]
    (cond
     ;; this means adjust the cache size
     (and max-size size-units)
     (.setMaxSize lmstc (long max-size) (*memcache-size-units* size-units))
     ;; nothing provided; do nothing
     (and (nil? max-size) (nil? size-units))
     true
     ;; one or the other provided: too error-prone, disallow
     :else
     (throw (RuntimeException. "provide both :max-size and :size-units")))
    lmstc))


(defn datastore [& {:keys [storage? store-delay-ms
                           max-txn-lifetime-ms max-query-lifetime-ms
                           backing-store-location]
                    :or {storage? false}}]
  (let [ldstc (LocalDatastoreServiceTestConfig.)]
    (.setNoStorage ldstc (not storage?))
    (when-not (nil? store-delay-ms)
      (.setStoreDelayMs ldstc store-delay-ms))
    (when-not (nil? max-txn-lifetime-ms)
      (.setMaxTxnLifetimeMs ldstc max-txn-lifetime-ms))
    (when-not (nil? max-query-lifetime-ms)
      (.setMaxQueryLifetimeMs ldstc max-query-lifetime-ms))
    (when-not (nil? backing-store-location)
      (.setBackingStoreLocation ldstc backing-store-location))
    ldstc))


(defn mail [& {:keys [log-mail-body? log-mail-level]
               :or {log-mail-body? false
                    log-mail-level :info}}]
  (let [lmstc (LocalMailServiceTestConfig.)]
    (.setLogMailBody lmstc log-mail-body?)
    (.setLogMailLevel lmstc (*logging-levels* log-mail-level))
    lmstc))


(defn- make-local-services-fixture-fn [services]
  (fn [test-fn]
    (let [helper (LocalServiceTestHelper. (into-array LocalServiceTestConfig services))]
      (.setUp helper)
      (test-fn)
      (.tearDown helper))))


(defn- local-services-helper
  ([]
     [(memcache) (datastore) (mail)])
  ([services override]
     (let [services (if (= :all services) (local-services-helper) services)]
       (if (nil? override)
           services
           (let [given-services (zipmap (map class services) services)
                 override-services (zipmap (map class override) override)]
             (merge given-services override-services))))))


(defn local-services
  ([]
     "Uses all services with their default settings."
     (make-local-services-fixture-fn (local-services-helper)))
  ([services & {:keys [override]}]
     "- If services is :all, uses all services with their default settings.
      - If services is a vector of services, uses those as given.
      - To use all defaults, but override some specific services, use :all
        and an :override vector."
     (make-local-services-fixture-fn (local-services-helper services override))))
