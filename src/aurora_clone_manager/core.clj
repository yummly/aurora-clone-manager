(ns aurora-clone-manager.core
  (:require [amazonica.aws.rds :as rds]
            [amazonica.aws.secretsmanager :as secrets]
            [amazonica.aws.securitytoken :as sts]
            [amazonica.aws.simplesystemsmanagement :as ssm]
            [amazonica.aws.kms :as kms]
            [cheshire.core :as json]
            [clj-time.core :as t]
            [clj-time.format :as ft]
            [clojure.core.memoize :as mem]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [crypto.random :as cr]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]
            [uswitch.lambada.core :refer [deflambdafn]])
  (:import [com.amazonaws.regions Region Regions]
           [com.amazonaws.services.lambda.runtime Context LambdaLogger]
           com.amazonaws.services.rds.model.DBClusterNotFoundException
           com.amazonaws.services.secretsmanager.model.ResourceNotFoundException
           org.joda.time.Period))

(s/check-asserts true)
(set! *warn-on-reflection* true)

(defn lambda-config-property
  ([^String p default]
   (or (System/getProperty p)
       (System/getenv p)

       default))
  ([^String p]
   (lambda-config-property p nil)))

(def non-empty-string? (s/and string? (complement str/blank?)))

(s/def ::secret-prefix non-empty-string?)
(s/def ::cluster-id non-empty-string?)
(s/def ::kms-key-id non-empty-string?)
(s/def ::target-clone-slots (s/and integer? pos?))
(s/def ::max-copy-age (s/and integer? pos?))
(s/def ::max-clones-per-source (s/and integer? pos?))
(s/def ::purge-obsolete-copies? boolean?)
(s/def ::dry-run? boolean?)
(s/def ::source-cluster-id non-empty-string?)
(s/def ::instance-class non-empty-string?)
(s/def ::restore-type #{:full-copy :copy-on-write})
(s/def ::create-instance? boolean?)
(s/def ::db-subnet-group-name non-empty-string?)
(s/def ::vpc-security-group-ids (s/coll-of non-empty-string?))
(s/def ::tags (s/map-of non-empty-string? non-empty-string?))
(s/def ::db-parameter-group non-empty-string?)
(s/def ::cluster-parameter-group non-empty-string?)
(s/def ::password-secret non-empty-string?)
(s/def ::access-principal-arns (s/every non-empty-string? :min-count 1))

(def tag-role "y.aurora.role")
(def role-source "source")
(def role-clone "clone")
(def role-copy "copy")
(def tag-source-arn "y.aurora.source-cluster-arn")
(def tag-root-arn "y.aurora.root-cluster-arn")
(def tag-data-timestamp "y.aurora.data-timestamp")
(def tag-cluster-id "y.aurora.cluster-id")
(def tag-password-secret "y.aurora.password-secret")
(def tag-password-key "y.aurora.password-key")
(def tag-key-purpose "y.key-purpose")
(def tag-key-purpose-value "aurora-clone-secret")

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

(defn unique-timestamp []
  (ft/unparse (ft/formatter "YYYY-MM-dd-HH-mm-ss-SSS") (t/now)))

(def caller-arn
  (memoize
   (fn []
     (:arn (sts/get-caller-identity {})))))

(defn cluster-id->arn [cluster-id]
  (format "arn:aws:rds:%s:%s:cluster:%s" (aws-region) (aws-account-id) cluster-id))

(defn arn->cluster-id [arn]
  (when-let [[_ id] (re-find #"^arn:.*:rds:.*:.*:cluster:(.*)$" arn)]
    id))

(defn cluster-tags
  "Get cluster tags as a map"
  [cluster-id]
  (try
    (let [tags (-> (rds/list-tags-for-resource {:resource-name (cluster-id->arn cluster-id)})
                   :tag-list)]
      (zipmap (map :key tags) (map :value tags)))
    (catch DBClusterNotFoundException e
      nil)))

(defn cluster-role [cluster-id]
  (get (cluster-tags cluster-id) tag-role))

(defn cluster-create-time [cluster-id]
  (some-> (rds/describe-db-clusters {:db-cluster-identifier cluster-id})
          :dbclusters
          first
          :cluster-create-time))

(defn cluster-exists? [cluster-id]
  (try
    (-> (rds/describe-db-clusters {:db-cluster-identifier cluster-id})
        :dbclusters
        seq
        boolean)
    (catch Exception _
      false)))

(defn tags->data-timestamp [tags default]
  (if-let [t (get tags tag-data-timestamp)]
    (ft/parse t)
    default))

(defn map->tags [tags]
  (for [[k v] tags
        :when (and k v)]
    {:key k :value v}))

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
        members (map :dbinstance-identifier (:dbcluster-members cluster))
        password-secret (get (cluster-tags cluster-id) tag-password-secret)
        password-key (get (cluster-tags cluster-id) tag-password-key)]
    (doseq [m members]
      (rds/delete-db-instance {:db-instance-identifier m}))
    (rds/delete-db-cluster {:db-cluster-identifier cluster-id :skip-final-snapshot skip-final-snapshot?})
    (when password-secret
      (secrets/delete-secret {:secret-id password-secret}))
    (when password-key
      (kms/schedule-key-deletion {:key-id password-key :pending-window-in-days 30}))))


(defn cluster->data [cluster]
  (-> cluster
      (select-keys [:dbcluster-identifier :dbcluster-arn :endpoint :reader-endpoint])
      (set/rename-keys {:dbcluster-identifier :id
                        :dbcluster-arn        :arn})))

(s/def ::create-key!-args (s/keys :req-un [::access-principal-arns]))

(defn create-key! [{:keys [access-principal-arns] :as args}]
  (s/assert ::create-key!-args args)
  (let [{:keys [key-id arn]} (:key-metadata (kms/create-key
                                             {:tags [{:tag-key   tag-key-purpose
                                                      :tag-value tag-key-purpose-value}]}))]
    (kms/put-key-policy {:key-id      key-id
                         :policy-name "default" ;; this is the only valid value
                         :policy      (-> {:Version   "2012-10-17"
                                           :Id        "key-default"
                                           :Statement [{:Effect    "Allow"
                                                        :Action    ["kms:Decrypt"]
                                                        :Resource  "*"
                                                        :Principal {:AWS access-principal-arns}}
                                                       {:Effect    "Allow"
                                                        :Action    ["kms:*"]
                                                        :Resource  "*"
                                                        :Principal {:AWS (caller-arn)}}
                                                       {:Effect    "Allow"
                                                        :Action    ["kms:*"]
                                                        :Resource  "*"
                                                        :Principal {:AWS (format "arn:aws:iam::%s:root" (aws-account-id))}}]}
                                          (json/generate-string {:pretty true}))})

    key-id))

(s/fdef create-key!
  :args (s/tuple ::create-key!-args))

(defn random-password []
  (cr/hex 32))

(def default-secret-prefix "/aurora-clone-manager/")

(defn secret-prefix []
  (lambda-config-property "SECRET_PREFIX" default-secret-prefix))

(s/def ::create-password!-args (s/keys :req-un [::cluster-id ::access-principal-arns]))

(defn create-password! [{:keys [secret-prefix cluster-id access-principal-arns]
                         :or   {secret-prefix default-secret-prefix}
                         :as   args}]
  (s/assert ::create-password!-args args)
  (let [secret-name          (cond-> secret-prefix
                               (not (str/ends-with? secret-prefix "/")) (str "/")
                               ;; add the timestamp because once a secret has been created and deleted, it apparently can't be created again
                               :always                                  (str "password/" cluster-id "-" (unique-timestamp)))
        {:keys [kms-key-id]} (try
                               (secrets/describe-secret {:secret-id secret-name})
                               (catch Exception _
                                 nil))
        kms-key-id           (or kms-key-id
                                 (create-key! {:access-principal-arns access-principal-arns}))]
    (try
      (secrets/put-secret-value {:secret-id     secret-name
                                 :kms-key-id    kms-key-id
                                 :secret-string (random-password)
                                 :tags          (map->tags {tag-cluster-id cluster-id})})
      (catch ResourceNotFoundException _
        (secrets/create-secret {:name          secret-name
                                :kms-key-id    kms-key-id
                                :secret-string (random-password)
                                :tags          (map->tags {tag-cluster-id cluster-id})})))
    (secrets/put-resource-policy {:secret-id       secret-name
                                  :resource-policy (-> {:Version   "2012-10-17"
                                                        :Id        "key-default"
                                                        :Statement [{:Effect    "Allow"
                                                                     :Action    ["secretsmanager:GetSecretValue"]
                                                                     :Resource  "*"
                                                                     :Principal {:AWS access-principal-arns}}
                                                                    {:Effect    "Allow"
                                                                     :Action    ["secretsmanager:GetSecretValue"]
                                                                     :Resource  "*"
                                                                     :Principal {:AWS access-principal-arns}}]}
                                                       (json/generate-string {:pretty true}))})
    {:password-secret secret-name :kms-key-id kms-key-id}))

(s/fdef create-password!
  :args (s/tuple ::create-password!-args))

(s/def ::create-copy!-args (s/keys :req-un [::cluster-id ::source-cluster-id]
                                   :opt-un [::restore-type ::create-instance? ::db-subnet-group-name ::vpc-security-group-ids ::tags ::db-parameter-group ::cluster-parameter-group ::password-secret ::kms-key-id]))

(defn create-copy!
  "Create a copy of the cluster by restoring a backup. The `restore-type` parameter specifies whether it's a full copy (`full-copy`) or a clone (`copy-on-write`)"
  [{:keys [cluster-id source-cluster-id instance-class restore-type create-instance? db-subnet-group-name vpc-security-group-ids tags
           db-parameter-group cluster-parameter-group
           password-secret kms-key-id]
    :or   {instance-class   "db.r4.large"
           restore-type     :full-copy
           create-instance? true
           tags             {}}
    :as   args}]
  (s/assert ::create-copy!-args args)
  (when-let [source (-> (rds/describe-db-clusters {:db-cluster-identifier source-cluster-id})
                        :dbclusters
                        first)]
    (let [source-tags           (cluster-tags source-cluster-id)
          _                     (log/infof "creating cluster %s as a %s of %s" cluster-id restore-type source-cluster-id)
          ;; if the source is a copy, it will have the data-timestamp tag. otherwise consider the data up-to-date
          source-data-timestamp (tags->data-timestamp source-tags (t/now))
          tags                  (cond-> tags
                                  :always         (merge {tag-role           (case (name restore-type)
                                                                               "full-copy"     role-copy
                                                                               "copy-on-write" role-clone)
                                                          tag-source-arn     (:dbcluster-arn source)
                                                          tag-root-arn       (get source-tags tag-root-arn (:dbcluster-arn source))
                                                          tag-data-timestamp (ft/unparse (:date-time ft/formatters) source-data-timestamp)})
                                  password-secret (assoc tag-password-secret password-secret
                                                         tag-password-key kms-key-id))
          cluster               (rds/restore-db-cluster-to-point-in-time
                                 (cond-> {:source-db-cluster-identifier source-cluster-id
                                          :db-cluster-identifier        cluster-id
                                          :restore-type                 (name restore-type)
                                          :use-latest-restorable-time   true
                                          :db-subnet-group-name         (or db-subnet-group-name
                                                                            (:dbsubnet-group source))
                                          :vpc-security-group-ids       (or vpc-security-group-ids
                                                                            (->> source :vpc-security-groups (map :vpc-security-group-id)))
                                          :tags                         (map->tags tags)}
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
  [{:keys [source-cluster-id cluster-id max-copy-age max-clones-per-source instance-class purge-obsolete-copies? copy-ok? dry-run? copy-created-since secret-prefix set-password? access-principal-arns no-fail-on-existing-cluster?]
    :or   {purge-obsolete-copies? true
           copy-ok?               true
           instance-class         "db.r4.large"
           dry-run?               true}
    :as   args}]
  (log/infof "provisioning a clone of %s" source-cluster-id)
  (let [{:keys [root obsolete-copies cloneable-sources]} (-> args
                                                             (dissoc :instance-class :purge-obsolete-copies? :copy-ok? :dry-run?)
                                                             (set/rename-keys {:source-cluster-id :cluster-id})
                                                             analyze)
        cluster-id                                       (if cluster-id
                                                           ;; if the requested cluster id already exists, append a timestamp
                                                           (if (cluster-exists? cluster-id)
                                                             (if no-fail-on-existing-cluster?
                                                               (format "%s-%s" cluster-id (unique-timestamp))
                                                               (throw (ex-info (format "cluster %s already exists" cluster-id) {:error :cluster-already-exists})))
                                                             cluster-id)
                                                           (format "%s-%s" source-cluster-id (unique-timestamp)))]
    (when-not root
      (throw (ex-info (format "source cluster %s does not exist" source-cluster-id) {:error :cluster-does-not-exist})))
    (when (and purge-obsolete-copies? (seq obsolete-copies))
      (log/infof "going to purge %s obsolete copies of %s in the background" (count obsolete-copies) source-cluster-id)
      (when-not dry-run?
        (future
          (doseq [{:keys [dbcluster-identifier]} obsolete-copies]
            (terminate-cluster! {:cluster-id dbcluster-identifier :skip-final-snapshot? true})))))
    (let [password      (when set-password?
                          (create-password! {:cluster-id cluster-id :access-principal-arns access-principal-arns}))
          copy-args     (cond-> {:source-cluster-id source-cluster-id :instance-class instance-class :cluster-id cluster-id}
                          password (merge password))
          cloneable?    (boolean (first cloneable-sources))
          _             (when (and (not cloneable?) (not copy-ok?))
                          (throw (ex-info (format "unable to provision a cluster because there are not clone slots available and :copy-ok? was false")
                                          {:error :no-clone-slots-and-not-copy-ok})))
          _             (if cloneable?
                          (log/infof "creating a clone of %s" source-cluster-id)
                          (log/infof "no clone slots available, will create a copy of %s" source-cluster-id))
          copy-resource (when-not dry-run?
                          (create-copy! (assoc copy-args :restore-type (if cloneable? :copy-on-write :full-copy))))]
      (merge copy-resource password))))


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
  (s/assert ::maintain-args args)
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
            copy-id-base     (format "%s-%s" cluster-id (unique-timestamp))]
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
                      :reader-endpoint :ReaderEndpoint
                      :password-secret :PasswordSecret
                      :kms-key-id      :KmsKeyId}))

(defn parse-properties
  "Convert properties as specified in a CF resource into the internal representation"
  [properties]
  (-> properties
      (set/rename-keys {:SourceClusterId     :source-cluster-id
                        :ClusterId           :cluster-id
                        :CopyOk              :copy-ok?
                        :PurgeObsoleteCopies :purge-obsolete-copies?
                        :InstanceClass       :instance-class
                        :CopyCreatedSince    :copy-created-since
                        :SetPassword         :set-password?
                        :AccessPrincipalArns :access-principal-arns})
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

(defn cluster-tags->data [cluster-id]
  (-> (cluster-tags cluster-id)
      (set/rename-keys {tag-password-secret :password-secret
                        tag-password-key :kms-key-id})
      (select-keys [:password-secret :kms-key-id])))

(defn cluster->cloudformation
  "Describe a cluster and convert the description to the output format of `handle-create!`"
  [cluster-arn]
  (let [cluster-id (arn->cluster-id cluster-arn)]
    (-> (rds/describe-db-clusters {:filters [{:name "db-cluster-id" :values [cluster-id]}]})
        :dbclusters
        first
        cluster->data
        (merge (cluster-tags->data cluster-id))
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

      ;;if only copy-created-since is changing, we may or may not need to provision a new clone
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
            (do-create! (assoc props :no-fail-on-existing-cluster? true)))))

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
  (let [req      {:stack-id             stack-id
                  :request-id           request-id
                  :logical-resource-id  logical-resource-id
                  :response-url         response-url
                  :properties           properties
                  :old-properties       old-properties
                  :physical-resource-id physical-resource-id}
        response (try
                   (when-not (= resource-type (supported-resource-type))
                     (throw (ex-info (format "invalid resource type %s, only %s is supported" resource-type (supported-resource-type))
                                     {:error :invalid-resource-type})))
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
        response (merge {:PhysicalResourceId (java.util.UUID/randomUUID)} ;; make sure PhysicalResourceId is never empty
                        (select-keys args [:RequestId :LogicalResourceId :StackId :PhysicalResourceId])
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
      (log/infof "maintenance: %s" event)
      (when-let [config (get-maintenance-config)]
        (doseq [cluster (:clusters config)]
          (log/infof "Running maintenance on %s" cluster)
          (try
            (maintain! (merge {:dry-run? false} cluster))
            (log/infof "Done with maintenance on %s" cluster)
            (catch Exception e
              (log/errorf e "Error with maintenance on %s" cluster))))))
    :else (log/warnf "Unrecognized action in event %s" event)))

(defn handle-rds-instance-created! [{:keys [event-source event-time source-id event-id event-message] :as event}]
  (let [instance-id source-id
        instance    (-> (rds/describe-db-instances {:db-instance-identifier instance-id})
                        :dbinstances
                        first)
        cluster-id  (:dbcluster-identifier instance)]
    (if cluster-id
      (let [tags            (cluster-tags cluster-id)
            password-secret (get tags tag-password-secret)]
        (if password-secret
          (let [password (:secret-string (secrets/get-secret-value {:secret-id password-secret}))]
            (rds/modify-db-cluster {:db-cluster-identifier cluster-id
                                    :master-user-password  password
                                    :apply-immediately     true})
            (log/infof "Set master password for %s to the secret %s" cluster-id password-secret))
          (log/infof "DB cluster %s doesn't have a password secret" cluster-id)))
      (log/infof "DB instance %s does not exists or is not Aurora" instance-id))))

(defn handle-rds-event! [event]
  (let [{:keys [event-source event-time source-id event-id event-message] :as event}
        (set/rename-keys event
                         {(keyword "Event Source")  :event-source
                          (keyword "Event Time")    :event-time
                          (keyword "Source ID")     :source-id
                          (keyword "Event ID")      :event-id
                          (keyword "Event Message") :event-message})]
    (cond
      (and (= event-source "db-instance")
           (= event-message "DB instance created"))
      (handle-rds-instance-created! event)

      :else
      (log/infof "Ignoring unsupported RDS event %s" event))))

(comment
  (def example-request (-> "test-data/create-request.json" io/resource slurp (json/parse-string true))))

(deflambdafn aurora-clone-manager.core.HandleCustomResource
  [in out ctx]
  (let [^LambdaLogger logger (.getLogger ^Context ctx)
        log-fn (fn [s] (.log logger s))
        fn-name (.getFunctionName ^Context ctx)
        fn-name-for-config (or (lambda-config-property "FUNCTION_NAME")
                               fn-name)]
    (s/check-asserts true)
    (log/with-config (lambda-logging-config log-fn)
      (try
        (let [event (with-open [r (io/reader in)]
                      (json/decode-stream r true))]
          ;; A scheduled event call (with `source": "aws.events")
          (cond
            (= (:source event) "events")
            (handle-maintenance! event)

            (:ResourceType event)
            (handle-custom-resource-lambda-call! event)

            (:Records event)
            (doseq [rec (:Records event)
                    :let [m (get-in rec [:Sns :Message])]
                    :when m
                    :let [m (json/parse-string m true)]]
              (handle-rds-event! m))

            :else
            (log/infof "received unrecognized event: %s" event)))
        (catch clojure.lang.ExceptionInfo ex
          (log/errorf ex "error in %s" fn-name)
          (throw ex))
        (catch Exception e
          (log/errorf e "error in %s" fn-name)
          (throw e))))))
