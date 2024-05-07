(ns frontend.worker.rtc.core2
  "Main(use missionary) ns for rtc related fns"
  (:require [frontend.worker.rtc.client :as r.client]
            [frontend.worker.rtc.exception :as r.ex]
            [frontend.worker.rtc.full-upload-download-graph :as r.upload-download]
            [frontend.worker.rtc.op-mem-layer :as op-mem-layer]
            [frontend.worker.rtc.remote-update :as r.remote-update]
            [frontend.worker.rtc.ws2 :as ws]
            [frontend.worker.state :as worker-state]
            [frontend.worker.util :as worker-util]
            [goog.string :as gstring]
            [logseq.common.config :as common-config]
            [logseq.common.missionary-util :as c.m]
            [logseq.db :as ldb]
            [malli.core :as ma]
            [missionary.core :as m])
  (:import [missionary Cancelled]))

(def ^:private rtc-state-schema
  [:map
   [:ws-state {:optional true} [:enum :connecting :open :closing :closed]]])
(def ^:private rtc-state-validator (ma/validator rtc-state-schema))

(defn- get-ws-url
  [token]
  (gstring/format @worker-state/*rtc-ws-url token))

(def ^:private sentinel (js-obj))
(defn- get-remote-updates
  "Return a flow: receive messages from ws, and filter messages with :req-id=`push-updates`."
  [get-ws-create-task]
  (m/stream
   (m/ap
     (loop []
       (let [ws (m/? get-ws-create-task)
             x (try
                 (m/?> (m/eduction
                        (filter (fn [data] (= "push-updates" (:req-id data))))
                        (ws/recv-flow ws)))
                 (catch js/CloseEvent _
                   sentinel))]
         (if (identical? x sentinel)
           (recur)
           (m/amb x (recur))))))))

(defn- create-local-updates-check-flow
  "Return a flow: emit if need to push local-updates"
  [repo *auto-push? interval-ms]
  (let [auto-push-flow (m/watch *auto-push?)
        clock-flow (c.m/clock interval-ms :clock)
        merge-flow (m/latest vector auto-push-flow clock-flow)]
    (m/eduction (filter first)
                (map second)
                (filter (fn [v] (when (pos? (op-mem-layer/get-unpushed-block-update-count repo)) v)))
                merge-flow)))

(defn- create-mixed-flow
  "Return a flow that emits all kinds of events:
  `:remote-update`: remote-updates data from server
  `:local-update-check`: event to notify to check if there're some new local-updates, then push to remote."
  [repo get-ws-create-task *auto-push?]
  (let [remote-updates-flow (m/eduction
                             (map (fn [data] {:type :remote-update :value data}))
                             (get-remote-updates get-ws-create-task))
        local-updates-check-flow (m/eduction
                                  (map (fn [data] {:type :local-update-check :value data}))
                                  (create-local-updates-check-flow repo *auto-push? 2000))]
    (c.m/mix remote-updates-flow local-updates-check-flow)))

(defn- new-task--get-ws-create
  "Return a map with atom *current-ws and a task
  that get current ws, create one if needed(closed or not created yet)"
  [url & {:keys [retry-count open-ws-timeout]
          :or {retry-count 10 open-ws-timeout 10000}}]
  (let [*current-ws (atom nil)
        ws-create-task (ws/mws-create url {:retry-count retry-count :open-ws-timeout open-ws-timeout})]
    {:*current-ws *current-ws
     :get-ws-create-task
     (m/sp
       (let [ws @*current-ws]
         (if (and ws
                  (not (ws/closed? ws)))
           ws
           (let [ws (m/? ws-create-task)]
             (reset! *current-ws ws)
             ws))))}))

(def new-task--get-ws-create--memoized
  "Return a memoized task to reuse the same websocket."
  (memoize new-task--get-ws-create))

(defn- create-ws-state-flow
  [*current-ws]
  (m/relieve
   (m/ap
     (let [ws (m/?< (m/watch *current-ws))]
       (try
         (if ws
           (m/?< (ws/create-mws-state-flow ws))
           (m/amb))
         (catch Cancelled _
           (m/amb)))))))

(defn- create-rtc-state-flow
  [ws-state-flow]
  (m/latest
   (fn [ws-state]
     {:post [(rtc-state-validator %)]}
     (cond-> {}
       ws-state (assoc :ws-state ws-state)))
   (m/reductions {} nil ws-state-flow)))

(defonce ^:private *rtc-lock (atom nil))
(defn- holding-rtc-lock
  "Use this fn to prevent multiple rtc-loops at same time.
  rtc-loop-task is stateless, but conn is not.
  we need to ensure that no two concurrent rtc-loop-tasks are modifying `conn` at the same time"
  [started-dfv task]
  (m/sp
    (when-not (compare-and-set! *rtc-lock nil true)
      (let [e (ex-info "Must not run multiple rtc-loops, try later"
                       {:type :rtc.exception/lock-failed
                        :missionary/retry true})]
        (started-dfv e)
        (throw e)))
    (try
      (m/? task)
      (finally
        (reset! *rtc-lock nil)))))

(defn- create-rtc-loop
  "Return a map with [:rtc-log-flow :rtc-state-flow :rtc-loop-task :*rtc-auto-push? :onstarted-task]
  TODO: auto refresh token if needed"
  [user-uuid graph-uuid repo conn date-formatter token
   & {:keys [auto-push? debug-ws-url] :or {auto-push? true}}]
  (let [ws-url              (or debug-ws-url (get-ws-url token))
        *auto-push?         (atom auto-push?)
        *log                (atom nil)
        started-dfv         (m/dfv)
        add-log-fn          #(reset! *log [(js/Date.) %])
        {:keys [*current-ws get-ws-create-task]}
        (new-task--get-ws-create--memoized ws-url)
        get-ws-create-task  (r.client/ensure-register-graph-updates get-ws-create-task graph-uuid)
        mixed-flow          (create-mixed-flow repo get-ws-create-task *auto-push?)]
    (assert (some? *current-ws))
    {:rtc-log-flow    (m/buffer 100 (m/watch *log))
     :rtc-state-flow  (create-rtc-state-flow (create-ws-state-flow *current-ws))
     :*rtc-auto-push? *auto-push?
     :onstarted-task  started-dfv
     :rtc-loop-task
     (holding-rtc-lock
      started-dfv
      (m/sp
        (try
          ;; init run to open a ws
          (m/? get-ws-create-task)
          (started-dfv true)
          (->>
           (let [event (m/?> mixed-flow)]
             (case (:type event)
               :remote-update
               (r.remote-update/apply-remote-update repo conn date-formatter event add-log-fn)

               :local-update-check
               (m/? (r.client/new-task--push-local-ops
                     repo conn user-uuid graph-uuid date-formatter
                     get-ws-create-task add-log-fn))))
           (m/ap)
           (m/reduce {} nil)
           (m/?))
          (catch Cancelled e
            (add-log-fn {:type :rtc/cancelled})
            (throw e)))))}))

(def ^:private empty-rtc-loop-metadata
  {:graph-uuid nil
   :user-uuid nil
   :rtc-log-flow nil
   :rtc-state-flow nil
   :*rtc-auto-push? nil
   :canceler nil})

(defonce ^:private *rtc-loop-metadata (atom empty-rtc-loop-metadata))

;;; ================ API ================
(defn new-task--rtc-start
  [repo token]
  (m/sp
    (if-let [conn (worker-state/get-datascript-conn repo)]
      (if-let [graph-uuid (ldb/get-graph-rtc-uuid @conn)]
        (let [user-uuid (:sub (worker-util/parse-jwt token))
              config (worker-state/get-config repo)
              date-formatter (common-config/get-date-formatter config)
              {:keys [onstarted-task rtc-log-flow rtc-state-flow *rtc-auto-push? rtc-loop-task]}
              (create-rtc-loop user-uuid graph-uuid repo conn date-formatter token)
              canceler (c.m/run-task rtc-loop-task :rtc-loop-task)
              start-ex (m/? onstarted-task)]
          (if (:ex-data start-ex)
            (r.ex/->map start-ex)
            (do (reset! *rtc-loop-metadata {:repo repo
                                            :graph-uuid graph-uuid
                                            :user-uuid user-uuid
                                            :rtc-log-flow rtc-log-flow
                                            :rtc-state-flow rtc-state-flow
                                            :*rtc-auto-push? *rtc-auto-push?
                                            :canceler canceler})
                nil)))
        (r.ex/->map r.ex/ex-local-not-rtc-graph))
      (r.ex/->map (ex-info "Not found db-conn" {:type :rtc.exception/not-found-db-conn
                                                :repo repo})))))

(defn rtc-stop
  []
  (when-let [canceler (:canceler @*rtc-loop-metadata)]
    (canceler)
    (reset! *rtc-loop-metadata empty-rtc-loop-metadata)))

(defn rtc-toggle-auto-push
  []
  (when-let [*auto-push? (:*rtc-auto-push? @*rtc-loop-metadata)]
    (swap! *auto-push? not)))

(defn new-task--get-graphs
  [token]
  (let [{:keys [get-ws-create-task]} (new-task--get-ws-create--memoized (get-ws-url token))]
    (m/join :graphs
            (r.client/send&recv get-ws-create-task {:action "list-graphs"}))))

(defn new-task--delete-graph
  "Return a task that return true if succeed"
  [token graph-uuid]
  (let [{:keys [get-ws-create-task]} (new-task--get-ws-create--memoized (get-ws-url token))]
    (m/sp
      (let [{:keys [ex-data]}
            (m/? (r.client/send&recv get-ws-create-task
                                     {:action "delete-graph" :graph-uuid graph-uuid}))]
        (when ex-data (prn ::delete-graph-failed graph-uuid ex-data))
        (boolean (nil? ex-data))))))

(defn new-task--get-user-info
  "Return a task that return users-info about the graph."
  [token graph-uuid]
  (let [{:keys [get-ws-create-task]} (new-task--get-ws-create--memoized (get-ws-url token))]
    (m/join :users
            (r.client/send&recv get-ws-create-task
                                {:action "get-users-info" :graph-uuid graph-uuid}))))

(defn new-task--grant-access-to-others
  [token graph-uuid & {:keys [target-user-uuids target-user-emails]}]
  (let [{:keys [get-ws-create-task]} (new-task--get-ws-create--memoized (get-ws-url token))]
    (r.client/send&recv get-ws-create-task
                        (cond-> {:action "grant-access"
                                 :graph-uuid graph-uuid}
                          target-user-uuids (assoc :target-user-uuids target-user-uuids)
                          target-user-emails (assoc :target-user-emails target-user-emails)))))

(defn new-task--get-block-content-versions
  "Return a task that return map [:ex-data :ex-message :versions]"
  [token graph-uuid block-uuid]
  (let [{:keys [get-ws-create-task]} (new-task--get-ws-create--memoized (get-ws-url token))]
    (m/join :versions (r.client/send&recv get-ws-create-task
                                          {:action "query-block-content-versions"
                                           :block-uuids [block-uuid]
                                           :graph-uuid graph-uuid}))))

(defn- create-get-debug-state-flow
  []
  (let [rtc-loop-metadata-flow (m/watch *rtc-loop-metadata)]
    (m/ap
      (let [{:keys [repo graph-uuid user-uuid rtc-state-flow *rtc-auto-push?]}
            (m/?< rtc-loop-metadata-flow)]
        (try
          (when (and repo rtc-state-flow *rtc-auto-push?)
            (m/?<
             (m/latest
              (fn [rtc-state rtc-auto-push?]
                {:graph-uuid graph-uuid
                 :user-uuid user-uuid
                 :unpushed-block-update-count (op-mem-layer/get-unpushed-block-update-count repo)
                 :rtc-state rtc-state
                 :auto-push? rtc-auto-push?})
              rtc-state-flow (m/watch *rtc-auto-push?))))
          (catch Cancelled _))))))

(defn new-task--get-debug-state
  []
  (m/reduce {} nil (m/eduction (take 1) (create-get-debug-state-flow))))

(defn new-task--snapshot-graph
  [token graph-uuid]
  (let [{:keys [get-ws-create-task]} (new-task--get-ws-create--memoized (get-ws-url token))]
    (m/join #(select-keys % [:snapshot-uuid :graph-uuid])
            (r.client/send&recv get-ws-create-task {:action "snapshot-graph"
                                                    :graph-uuid graph-uuid}))))
(defn new-task--snapshot-list
  [token graph-uuid]
  (let [{:keys [get-ws-create-task]} (new-task--get-ws-create--memoized (get-ws-url token))]
    (m/join :snapshot-list
            (r.client/send&recv get-ws-create-task {:action "snapshot-list"
                                                    :graph-uuid graph-uuid}))))

(defn new-task--upload-graph
  [token repo remote-graph-name]
  (m/sp
    (if-let [conn (worker-state/get-datascript-conn repo)]
      (let [{:keys [get-ws-create-task]} (new-task--get-ws-create--memoized (get-ws-url token))]
        (m/? (r.upload-download/new-task--upload-graph get-ws-create-task repo conn remote-graph-name)))
      (r.ex/->map (ex-info "Not found db-conn" {:type :rtc.exception/not-found-db-conn
                                                :repo repo})))))

(defn new-task--request-download-graph
  [token graph-uuid]
  (let [{:keys [get-ws-create-task]} (new-task--get-ws-create--memoized (get-ws-url token))]
    (r.upload-download/new-task--request-download-graph get-ws-create-task graph-uuid)))

(defn new-task--download-info-list
  [token graph-uuid]
  (let [{:keys [get-ws-create-task]} (new-task--get-ws-create--memoized (get-ws-url token))]
    (r.upload-download/new-task--download-info-list get-ws-create-task graph-uuid)))

(defn new-task--wait-download-info-ready
  [token download-info-uuid graph-uuid timeout-ms]
  (let [{:keys [get-ws-create-task]} (new-task--get-ws-create--memoized (get-ws-url token))]
    (r.upload-download/new-task--wait-download-info-ready
     get-ws-create-task download-info-uuid graph-uuid timeout-ms)))

(def new-task--download-graph-from-s3 r.upload-download/new-task--download-graph-from-s3)

;;; ================ API (ends) ================

;;; subscribe debug state ;;;

(defonce ^:private *last-subscribe-canceler (atom nil))
(defn- subscribe-debug-state
  []
  (when-let [canceler @*last-subscribe-canceler]
    (canceler)
    (reset! *last-subscribe-canceler nil))
  (let [cancel (c.m/run-task
                (m/reduce
                 (fn [_ v] (worker-util/post-message :rtc-sync-state v))
                 (create-get-debug-state-flow))
                :subscribe-debug-state)]
    (reset! *last-subscribe-canceler cancel)
    nil))

(subscribe-debug-state)

(comment
  (do
    (def user-uuid "7f41990d-2c8f-4f79-b231-88e9f652e072")
    (def graph-uuid "ff7186c1-5903-4bc8-b4e9-ca23525b9983")
    (def repo "logseq_db_4-23")
    (def conn (worker-state/get-datascript-conn repo))
    (def date-formatter "MMM do, yyyy")
    (def debug-ws-url "wss://ws-dev.logseq.com/rtc-sync?token=???")
    (let [{:keys [rtc-log-flow rtc-state-flow *rtc-auto-push? rtc-loop-task]}
          (create-rtc-loop user-uuid graph-uuid repo conn date-formatter nil {:debug-ws-url debug-ws-url})
          c (c.m/run-task rtc-loop-task :rtc-loop-task)]
      (def cancel c)
      (def rtc-log-flow rtc-log-flow)
      (def rtc-state-flow rtc-state-flow)
      (def *rtc-auto-push? *rtc-auto-push?)))
  (cancel))
