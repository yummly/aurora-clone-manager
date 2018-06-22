(ns aurora-clone-manager.core
  (:require [amazonica.aws
             [s3 :as s3]]
            [amazonica.aws
             [cloudformation :as cf]
             [simplesystemsmanagement :as ssm]
             [rds :as rds]
             [securitytoken :as sts]]
            [amazonica.core :as amz]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [clj-time
             [core :as t]
             [format :as ft]
             [coerce :as ct]]
            [clojure.set :as set]
            [uswitch.lambada.core :refer [deflambdafn]]
            [cheshire.core :as json]
            [org.httpkit.client :as http]
            [clojure.core.memoize :as mem]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest])
  (:import [org.joda.time DateTime Period]
           [com.amazonaws.services.lambda.runtime RequestStreamHandler Context LambdaLogger]
           [com.amazonaws.regions Region Regions]))

(set! *warn-on-reflection* true)

(def tag-role "y.aurora.role")
(def role-source "source")
(def role-clone "clone")
(def role-copy "copy")
(def tag-source-arn "y.aurora.source-cluster-arn")
(def tag-root-arn "y.aurora.root-cluster-arn")
(def tag-data-timestamp "y.aurora.data-timestamp")

(def default-max-clones-per-source 15)
(def default-max-copy-age (t/days 3))

(def aws-region
  (memoize
   (fn []
    (if-let [^Region r (Regions/getCurrentRegion)]
      (.getName r)
      "us-east-1"))))

(def aws-account-id
  (memoize
   (fn []
     (:account (sts/get-caller-identity {})))))


(defn cluster-id->arn [cluster-id]
  (format "arn:aws:rds:%s:%s:cluster:%s" (aws-region) (aws-account-id) cluster-id))

(defn arn->cluster-id [arn]
  (when-let [[_ id] (re-find #"^arn:.*:rds:.*:.*:cluster:(.*)$" arn)]
    id))

(defn cluster-tags
  "Get cluster tags as a map"
  [cluster-id]
  (let [tags (-> (rds/list-tags-for-resource {:resource-name (cluster-id->arn cluster-id)})
                 :tag-list)]
    (zipmap (map :key tags) (map :value tags))))

(defn cluster-role [cluster-id]
  (get (cluster-tags cluster-id) tag-role))

(defn cluster-create-time [cluster-id]
  (some-> (rds/describe-db-clusters {:db-cluster-identifier cluster-id})
          :dbclusters
          first
          :cluster-create-time))

(defn tags->data-timestamp [tags default]
  (if-let [t (get tags tag-data-timestamp)]
    (ft/parse t)
    default))

(defn cluster-data-timestamp [cluster-id]
  (-> (cluster-tags cluster-id)
      (tags->data-timestamp nil)))

(defn cluster-age [cluster-id]
  (let [timestamp (or (cluster-data-timestamp cluster-id) (cluster-create-time cluster-id))]
    (t/interval timestamp (t/now))))

(defn source-cluster? [cluster-id]
  (= (cluster-role cluster-id) role-source))

(defn clone-cluster? [cluster-id]
  (= (cluster-role cluster-id) role-clone))

(defn terminate-cluster!
  "Delete a cluster and all its instances. Optionally create a final snapshot."
  [{:keys [cluster-id skip-final-snapshot?]
    :or {skip-final-snapshot? true}}]
  (when-not (#{role-clone role-copy} (cluster-role cluster-id))
    (throw (ex-info "Won't terminate a non-clone cluster" {:error :not-a-clone})))
  (log/infof "terminating cluster %s" cluster-id)
  (let [cluster (-> (rds/describe-db-clusters {:db-cluster-identifier cluster-id})
                    :dbclusters
                    first)
        members (map :dbinstance-identifier (:dbcluster-members cluster))]
    (doseq [m members]
      (rds/delete-db-instance {:db-instance-identifier m}))
    (rds/delete-db-cluster {:db-cluster-identifier cluster-id :skip-final-snapshot skip-final-snapshot?})))


(defn cluster->data [cluster]
  (-> cluster
      (select-keys [:dbcluster-identifier :dbcluster-arn :endpoint :reader-endpoint])
      (set/rename-keys {:dbcluster-identifier :id
                        :dbcluster-arn        :arn})))

(defn create-copy!
  "Create a copy of the cluster by restoring a backup. The `restore-type` parameter specifies whether it's a full copy (`full-copy`) or a clone (`copy-on-write`)"
  [{:keys [cluster-id source-cluster-id instance-class restore-type create-instance? db-subnet-group-name vpc-security-group-ids tags
           db-parameter-group cluster-parameter-group]
    :or   {instance-class   "db.r4.large"
           restore-type     :full-copy
           create-instance? true
           tags             {}}}]
  {:pre [source-cluster-id]}
  (when-let [source (-> (rds/describe-db-clusters {:db-cluster-identifier source-cluster-id})
                        :dbclusters
                        first)]
    (let [cluster-id            (or cluster-id
                                    (format "%s-%s" source-cluster-id (ft/unparse (ft/formatter "YYYY-MM-dd-HH-mm-ss-SSS") (t/now))))
          source-tags           (cluster-tags source-cluster-id)
          _                     (log/infof "creating cluster %s as a %s of %s" cluster-id restore-type source-cluster-id)
          ;; if the source is a copy, it will have the data-timestamp tag. otherwise consider the data up-to-date
          source-data-timestamp (tags->data-timestamp source-tags (t/now))
          tags                  (merge tags
                                       {tag-role           (case (name restore-type)
                                                             "full-copy"     role-copy
                                                             "copy-on-write" role-clone)
                                        tag-source-arn     (:dbcluster-arn source)
                                        tag-root-arn       (get source-tags tag-root-arn (:dbcluster-arn source))
                                        tag-data-timestamp (ft/unparse (:date-time ft/formatters) source-data-timestamp)})
          cluster               (rds/restore-db-cluster-to-point-in-time
                                 (cond-> {:source-db-cluster-identifier source-cluster-id
                                          :db-cluster-identifier        cluster-id
                                          :restore-type                 (name restore-type)
                                          :use-latest-restorable-time   true
                                          :db-subnet-group-name         (or db-subnet-group-name
                                                                            (:dbsubnet-group source))
                                          :vpc-security-group-ids       (or vpc-security-group-ids
                                                                            (->> source :vpc-security-groups (map :vpc-security-group-id)))
                                          :tags                         (for [[k v] tags]
                                                                          {:key k :value v})}
                                   cluster-parameter-group (assoc :db-cluster-parameter-group-name cluster-parameter-group)))]
      (when create-instance?
        (rds/create-db-instance
         (cond-> {:db-cluster-identifier  cluster-id
                  :db-instance-identifier cluster-id
                  :engine                 (:engine source)
                  :db-instance-class      instance-class}
           db-parameter-group (assoc :db-parameter-group-name db-parameter-group))))
      (cluster->data cluster))))

(defn create-clone!
  "A shortcut to calling `create-copy!` with `:restore-type :copy-on-write`"
  [{:keys [cluster-id source-cluster-id instance-class] :as args}]
  (create-copy! (assoc args :restore-type :copy-on-write :create-instance? true)))

(defn analyze
  "Given a cluster, analyze the state of its copies and clones and return the possible source of cloning.

  The master cluster is always considered as a possible cloning source unless it already has `max-clones-per-source` clones.
  Copies are considered if they are created after `copy-created-since` time, unless it's null, in which case any copy is considered."
  [{:keys [cluster-id max-copy-age max-clones-per-source copy-created-since]
    :or   {max-copy-age          default-max-copy-age
           max-clones-per-source default-max-clones-per-source}}]
  (let [clusters                     (:dbclusters (rds/describe-db-clusters))
        root-arn                     (cluster-id->arn cluster-id)
        clusters                     (for [{id :dbcluster-identifier status :status :as cluster} clusters
                                           :let                                                  [tags (cluster-tags id)]
                                           :when                                                 (#{"available" "creating"} status)
                                           :when                                                 (or (= id cluster-id)
                                                                                                     (= root-arn (get tags tag-root-arn)))]
                                       (assoc cluster :tags tags))
        role->clusters               (group-by
                                      (fn [{id :dbcluster-identifier tags :tags :as cluster}]
                                        (cond
                                          (= id cluster-id) "root"
                                          :else             (get tags tag-role)))
                                      clusters)
        root                         (first (role->clusters "root"))
        clones                       (role->clusters role-clone)
        copies                       (role->clusters role-copy)
        too-old?                     (let [cutoff (t/ago max-copy-age)]
                                       (fn [{:keys [cluster-create-time] :as copy}]
                                         (t/before? cluster-create-time cutoff)))
        fresh-enough?                (if copy-created-since
                                       (fn [{:keys [dbcluster-identifier tags] :as copy}]
                                         (t/before? copy-created-since (tags->data-timestamp tags (t/now))))
                                       (constantly true))
        obsolete-copies              (for [{:keys [cluster-create-time] :as copy} copies
                                           :when                                  (too-old? copy)]
                                       copy)
        fresh-copies                 (for [{:keys [cluster-create-time status] :as copy} copies
                                           :when                                         (not (too-old? copy))
                                           :when                                         (fresh-enough? copy)]
                                       copy)
        ;; copies that can be cloned
        eligible-copies              (for [{:keys [status] :as copy} fresh-copies
                                           :when                     (= status "available")]
                                       copy)
        clones-by-source             (group-by
                                      #(get-in % [:tags tag-source-arn])
                                      clones)
        cloneable?                   (fn [source-id]
                                       (< (count (clones-by-source source-id)) max-clones-per-source))
        ;; the cloneable source are the master and any fresh copies in the available state, unless all clone slots are take,
        cloneable-sources            (cons cluster-id (->> eligible-copies
                                                           (filter cloneable?)
                                                           (map :dbcluster-identifier)))
        ;; copies in creating state count against available slots
        sources-with-available-slots (cons cluster-id (->> fresh-copies
                                                           (filter cloneable?)
                                                           (map :dbcluster-identifier)))]
    {:root                  root
     :copies                copies
     :clones                clones
     :obsolete-copies       obsolete-copies
     :cloneable-sources     cloneable-sources
     :available-clone-slots (- (* (count sources-with-available-slots) max-clones-per-source) (count clones))}))

(defn provision-cluster-copy!
  "Create a Aurora cluster clone or copy."
  [{:keys [source-cluster-id cluster-id max-copy-age max-clones-per-source instance-class purge-obsolete-copies? copy-ok? dry-run? copy-created-since]
                                :or   {purge-obsolete-copies? true
                                       copy-ok?               true
                                       instance-class         "db.r4.large"
                                       dry-run?               true}
                                :as   args}]
  (log/infof "provisioning a clone of %s" source-cluster-id)
  (let [{:keys [root obsolete-copies cloneable-sources]} (-> args
                                                             (dissoc :instance-class :purge-obsolete-copies? :copy-ok? :dry-run?)
                                                             (set/rename-keys {:source-cluster-id :cluster-id})
                                                             analyze)]
    (when-not root
      (throw (ex-info (format "source cluster %s does not exist" source-cluster-id) {:error :cluster-does-not-exist})))
    (when (and purge-obsolete-copies? (seq obsolete-copies))
      (log/infof "going to purge %s obsolete copies of %s in the background" (count obsolete-copies) source-cluster-id)
      (when-not dry-run?
        (future
          (doseq [{:keys [dbcluster-identifier]} obsolete-copies]
            (terminate-cluster! {:cluster-id dbcluster-identifier :skip-final-snapshot? true})))))
    (if-let [source (first cloneable-sources)]
      (do
        (log/infof "creating a clone of %s" source-cluster-id)
        (when-not dry-run?
          (create-clone! {:source-cluster-id source-cluster-id :instance-class instance-class :cluster-id cluster-id})))
      (if copy-ok?
        (do
          (log/infof "no clone slots available, will create a copy of %s" source-cluster-id)
          (when-not dry-run?
            (create-copy! {:source-cluster-id source-cluster-id :instance-class instance-class :cluster-id cluster-id})))
        (throw (ex-info (format "unable to provision a cluster because there are not clone slots available and :copy-ok? was false")
                        {:error :no-clone-slots-and-not-copy-ok}))))))


(s/def ::cluster-id string?)
(s/def ::target-clone-slots (s/and integer? pos?))
(s/def ::max-copy-age (s/and integer? pos?))
(s/def ::max-clones-per-source (s/and integer? pos?))
(s/def ::purge-obsolete-copies? boolean?)
(s/def ::dry-run? boolean?)

(s/def ::maintain-args (s/keys :req-un [::cluster-id ::target-clone-slots]
                               :opt-un [::purge-obsolete-copies? ::max-clones-per-source ::max-copy-age ::dry-run?]))

(defn maintain!
  "Perform maintenance on a cluster:

  1. Purge copies that are too old to clone.
  2. Create new copies if necessary to allow for creation of `target-clone-slots` additional clones."
  [{:keys [cluster-id target-clone-slots max-copy-age max-clones-per-source purge-obsolete-copies? dry-run?]
    :or   {purge-obsolete-copies? true
           dry-run?               true
           max-clones-per-source  default-max-clones-per-source}
    :as   args}]
  (log/infof "doing maintenance on %s" cluster-id)
  (let [{:keys [root obsolete-copies cloneable-sources available-clone-slots]} (-> args
                                                                                   (dissoc  :purge-obsolete-copies? :dry-run?)
                                                                                   analyze)
        clone-slot-deficit                                                     (- target-clone-slots available-clone-slots)]
    (when-not root
      (throw (ex-info (format "source cluster %s does not exist" cluster-id) {:error :cluster-does-not-exist})))
    (when (and purge-obsolete-copies? (seq obsolete-copies))
      (log/infof "going to purge %s obsolete copies of %s in the background" (count obsolete-copies) cluster-id)
      (when-not dry-run?
        (future
          (doseq [{:keys [dbcluster-identifier]} obsolete-copies]
            (terminate-cluster! {:cluster-id dbcluster-identifier :skip-final-snapshot? true})))))
    (when (pos? clone-slot-deficit)
      (let [copies-to-create (int (Math/ceil (double (/ clone-slot-deficit max-clones-per-source))))
            copy-id-base     (format "%s-%s" cluster-id (ft/unparse (ft/formatter "YYYY-MM-dd-HH-mm-ss-SSS") (t/now)))]
        (log/infof "will create %s copies to gain %s missing clone slots" copies-to-create clone-slot-deficit)
        (dotimes [i copies-to-create]
          (let [copy-id (str copy-id-base "-" i)]
            (log/infof "will create copy %s" copy-id)
            (when-not dry-run?
              (create-copy! {:source-cluster-id cluster-id
                             :cluster-id        copy-id
                             ;; use the cheapest instance type since copies are not used directly
                             :instance-class    "db.r4.large"}))))))))

(s/fdef maintain! :args (s/cat :args ::maintain-args))
(stest/instrument `maintain!)

(defn lambda-config-property
  ([^String p default]
   (or (System/getProperty p)
       (System/getenv p)

       default))
  ([^String p]
   (lambda-config-property p nil)))

(defn log-level []
  (-> (lambda-config-property "LOG_LEVEL" "info")
      str/lower-case
      keyword))

(defn lambda-logging-config [log-fn]
  (-> {:level         (log-level)
       :ns-whitelist  []
       :ns-blacklist  []
       :middleware    []
       :timestamp-opts (assoc log/default-timestamp-opts
                              :pattern "yyyy-MM-dd HH:mm:ss.SSS")
       :output-fn (fn [{:keys [level ?err  msg_ ?ns-str hostname_ timestamp_ ?line]}]
                    ;; <timestamp> <LEVEL> [<ns>] - <message> <throwable>
                    (str
                     (force timestamp_)       " "
                     (str/upper-case (name level))  " "
                     "[" (or ?ns-str "?") ":" (or ?line "?") "] - "
                     (force msg_)
                     (when-let [err ?err]
                       (str "\n" (log/stacktrace err {:stacktrace-fonts nil})))))
       :appenders     {:lambda {:doc       "Log to lambda log"
                                :min-level nil :enabled? true :async? false :rate-limit nil
                                :fn        (fn [{:keys [error? output_]}] ; Can use any appender args
                                             (log-fn (force output_)))}}}))

(defn supported-resource-type []
  (lambda-config-property "RESOURCE_TYPE" "Custom::AuroraClone"))

(defn max-copy-age []
  (-> (lambda-config-property "MAX_COPY_AGE" (str default-max-copy-age))
      Period/parse))

(defn max-clones-per-source []
  (Integer/parseInt (lambda-config-property "MAX_CLONES_PER_SOURCE" "15")))

(defn cluster-data->cloudformation [c]
  (set/rename-keys c {:arn             :Arn
                      :id              :ClusterId
                      :endpoint        :Endpoint
                      :reader-endpoint :ReaderEndpoint}))

(defn parse-properties
  "Convert properties as specified in a CF resource into the internal representation"
  [properties]
  (-> properties
      (set/rename-keys {:SourceClusterId     :source-cluster-id
                        :ClusterId           :cluster-id
                        :CopyOk              :copy-ok?
                        :PurgeObsoleteCopies :purge-obsolete-copies?
                        :InstanceClass       :instance-class
                        :CopyCreatedSince    :copy-created-since})
      (update :copy-created-since (fnil ft/parse "2000-00-00"))))

(defn do-create! [args]
  (let [{:keys [arn] :as ret} (provision-cluster-copy! (assoc args :dry-run? false))]
    {:Status             "SUCCESS"
     :PhysicalResourceId arn
     :Data               (cluster-data->cloudformation ret)}))

(defn handle-create!
  "see https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/crpg-ref-requesttypes-create.html"
  [{:keys [response-url properties request-id logical-resource-id stack-id physical-resource-id]}]
  (let [args                                   (-> properties
                                                   parse-properties
                                                   (assoc :max-clones-per-source (max-clones-per-source)
                                                          :max-copy-age (max-copy-age)))
        _                                      (log/infof "handle-create: request %s" args)
        {:keys [cluster-id source-cluster-id]} args
        _                                      (when-not source-cluster-id
                                                 (throw (ex-info "required property SourceClusterId is missing")))
        ;; the default id for the new cluster is a combination of the source id and the stack id
        response                               (do-create! args)
        _                                      (log/infof "handle-create: response %s" response)]
    response))

(defn cluster->cloudformation
  "Describe a cluster and convert the description to the output format of `handle-create!`"
  [cluster-arn]
  (let [cluster-id (arn->cluster-id cluster-arn)]
    (-> (rds/describe-db-clusters {:filters [{:name "db-cluster-id" :values [cluster-id]}]})
        :dbclusters
        first
        cluster->data
        cluster-data->cloudformation)))

(defn handle-update!
  "Handle a CloudFormation update.

  A special case is when the SourceClusterId and other parameters don't change but the CopyCreatedSince does. In this case, the cluster is rebuilt only if necessary.

  Otherwise, for any other parameter changes, a new cluster will be built.

  Note that the update handler should only create a new resource. CloudFormation will separately call the Lambda with a Delete event to delete the old resource."
  [{:keys [response-url properties old-properties request-id logical-resource-id stack-id physical-resource-id]
    :as   args}]
  (let [props     (parse-properties properties)
        old-props (parse-properties old-properties)]
    (cond
      ;; a no-op update must return the same data as create
      (= old-props props)
      (let [{cluster-id :ClusterId :as data} (cluster->cloudformation physical-resource-id)]
        (log/infof "A no-op update for %s" cluster-id)
        {:Status "SUCCESS"
         :Data   data})

      ;;if only copy-created-since is changing, we may need to provision a new clone
      (= (dissoc old-props :copy-created-since) (dissoc props :copy-created-since))
      (let [new-copy-created-since (:copy-created-since props)
            {cluster-id :ClusterId :as data} (cluster->cloudformation physical-resource-id)
            timestamp                        (cluster-data-timestamp cluster-id)]
        (if (t/before? new-copy-created-since timestamp)
          (do
            (log/infof "A no-op update for %s: the CopyCreatedSince condition is still satisfied" cluster-id)
            {:Status "SUCCESS"
             :Data   data})
          (do
            (log/infof "The current clone %s is out of date; will create a new one and drop this one" cluster-id)
            (do-create! props))))

      ;; otherwise create a new cluster
      :else
      (do-create! props))))

(defn handle-delete! [{:keys [response-url properties request-id logical-resource-id stack-id physical-resource-id]}]
  (if-let [cluster-id (arn->cluster-id physical-resource-id)]
    (do
      (terminate-cluster! {:cluster-id cluster-id})
      {:Status "SUCCESS"})
    ;; CF calls Delete with an invalid physical resource id when rolling back a failed cluster creation
    {:Status "SUCCESS"}))

(defn handle-custom-resource-lambda-call! [{resource-type  :ResourceType          request-id           :RequestId          logical-resource-id :LogicalResourceId
                                            response-url   :ResponseURL           properties           :ResourceProperties request-type        :RequestType
                                            old-properties :OldResourceProperties physical-resource-id :PhysicalResourceId stack-id            :StackId
                                            :as            args}]
  (log/infof "event:\n%s" (with-out-str
                            (clojure.pprint/pprint args)))
  (when-not (= resource-type (supported-resource-type))
    (throw (ex-info (format "invalid resource type %s, only %s is supported" resource-type (supported-resource-type))
                    {:error :invalid-resource-type})))
  (let [req      {:stack-id             stack-id
                  :request-id           request-id
                  :logical-resource-id  logical-resource-id
                  :response-url         response-url
                  :properties           properties
                  :old-properties       old-properties
                  :physical-resource-id physical-resource-id}
        response (try
                   (case request-type
                     "Create" (handle-create! req)
                     "Update" (handle-update! req)
                     "Delete" (handle-delete! req)
                     (throw (ex-info (format "invalid request type %s" request-type)
                                     {:error :invalid-request-type})))
                   (catch Exception e
                     (log/errorf e "error with request %s" args)
                     {:Status "FAILED"
                      :Reason (.getMessage e)}))
        response (merge (select-keys args [:RequestId :LogicalResourceId :StackId :PhysicalResourceId])
                        response)]
    (log/infof "response:\n%s" (with-out-str
                                 (clojure.pprint/pprint response)))
    (let [{:keys [status error]} @(http/put response-url
                                            {:body    (json/generate-string response)
                                             ;; the content-type must be "" for the presigned URL PUT to work
                                             :headers {"content-type" ""}})]
      (when (or error (>= status 400))
        (log/errorf "PUT to the response url %s failed with %s:\n%s" response-url status error)
        (throw (ex-info "PUT to the response url failed" {:error :response-put-failed}))))))

(defn ssm-parameter* [parameter-name default]
  (or (try
        (-> (ssm/get-parameter {:name (name parameter-name) :with-decryption true})
            :parameter
            :value)
        (catch Exception e
          (log/debugf "Error reading SSM parameter %s: %s" parameter-name e)))
      default))

(def ssm-parameter
  (mem/ttl ssm-parameter* :ttl/threshold (* 5 60 1000)))

(s/def ::clusters (s/coll-of ::maintain-args))
(s/def ::maintenance-config (s/keys :req-un [::clusters]))

(defn ->maintenance-config [str]
  (when-not (str/blank? str)
    (if-let [config (or
                     (try
                       (clojure.edn/read-string str)
                       (catch Exception _))
                     (try
                       (json/parse-string str true)
                       (catch Exception _)))]
      (if (s/conform ::maintenance-config config)
        config
        (let [error (format "Invalid configuration: %s"
                            (with-out-str
                              (s/explain ::maintenance-config config)))]
          (throw (ex-info error {:error :invalid-maintenance-config}))))
      (throw (ex-info (format "Unparsable configuration: %s" str) {:error :unparsable-maintenance-config})))))

(s/fdef ->maintenance-config
  :args (s/cat :str (s/nilable string?))
  :ret (s/nilable ::maintenance-config))
(stest/instrument `->maintenance-config)

(defn get-maintenance-config []
  (some-> (lambda-config-property "MAINTENANCE_CONFIG_SSM")
          (ssm-parameter nil)
          ->maintenance-config))

(s/fdef get-maintenance-config
  :ret (s/nilable ::maintenance-config))
(stest/instrument `get-maintenance-config)

(defn handle-maintenance! [{:keys [action] :as event}]
  (cond
    (= action "maintain!")
    (do
      (log/info "maintenance: %s" event)
      (when-let [config (get-maintenance-config)]
        (doseq [cluster (:clusters config)]
          (log/infof "Running maintenance on %s" cluster)
          (try
            (maintain! (merge {:dry-run? false} cluster))
            (log/infof "Done with maintenance on %s" cluster)
            (catch Exception e
              (log/errorf e "Error with maintenance on %s" cluster))))))
    :else (log/warnf "Unrecognized action in event %s" event)))

(comment
  (def example-request (-> "test-data/create-request.json" io/resource slurp (json/parse-string true))))

(defn maintenance-rule-arn []
  (lambda-config-property "MAINTENANCE_RULE_ARN"))

(deflambdafn aurora-clone-manager.core.HandleCustomResource
  [in out ctx]
  (let [^LambdaLogger logger (.getLogger ^Context ctx)
        log-fn (fn [s] (.log logger s))
        fn-name (.getFunctionName ^Context ctx)
        fn-name-for-config (or (lambda-config-property "FUNCTION_NAME")
                               fn-name)]
    (log/with-config (lambda-logging-config log-fn)
      (try
        (let [event (with-open [r (io/reader in)]
                      (json/decode-stream r true))]
          ;; A scheduled event call (with `source": "aws.events")
          (if (= (:source event) "events")
            (handle-maintenance! event)
            (handle-custom-resource-lambda-call! event)))
        (catch clojure.lang.ExceptionInfo ex
          (log/errorf ex "error in %s" fn-name)
          (throw ex))
        (catch Exception e
          (log/errorf e "error in %s" fn-name)
          (throw e))))))
