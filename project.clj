(def aws-sdk-version "1.11.414")

(defproject aurora-clone-manager "0.1.0-SNAPSHOT"
  :description "Create and delete Aurora clones on demand"
  :license {:name "Eclipse Public License"
            :url  "https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [amazonica "0.3.132"
                  :exclusions [com.amazonaws/aws-java-sdk
                               com.amazonaws/amazon-kinesis-client
                               joda-time
                               com.taoensso/nippy]]
                 [com.amazonaws/aws-lambda-java-core "1.1.0"]
                 [com.amazonaws/aws-lambda-java-events "1.3.0"]
                 [com.amazonaws/aws-lambda-java-log4j "1.0.0"]
                 [com.amazonaws/aws-java-sdk-s3 ~aws-sdk-version]
                 [com.amazonaws/aws-java-sdk-kinesis ~aws-sdk-version]
                 [com.amazonaws/aws-java-sdk-cloudformation ~aws-sdk-version]
                 [com.amazonaws/aws-java-sdk-ssm ~aws-sdk-version]
                 [com.amazonaws/aws-java-sdk-lambda ~aws-sdk-version]
                 [com.amazonaws/aws-java-sdk-rds ~aws-sdk-version]
                 [com.amazonaws/aws-java-sdk-sts ~aws-sdk-version]
                 [com.amazonaws/aws-java-sdk-ssm ~aws-sdk-version]
                 [com.amazonaws/aws-java-sdk-secretsmanager ~aws-sdk-version]
                 [com.amazonaws/aws-java-sdk-events ~aws-sdk-version]
                 [com.taoensso/encore "2.87.0"]
                 [com.taoensso/timbre "4.7.4"
                  :exclusions [com.taoensso/encore]]
                 [com.climate/claypoole "1.1.3"]
                 [uswitch/lambada "0.1.2"]
                 [cheshire "5.6.3"]
                 [clj-time "0.12.2"]
                 [javax.xml.bind/jaxb-api "2.2.12"]
                 [com.sun.xml.bind/jaxb-core "2.2.11"]
                 [http-kit "2.2.0"]
                 [org.clojure/core.memoize "0.7.1"]
                 [crypto-random "1.2.0"
                  :exclusions [commons-codec]]]
  :global-vars {*warn-on-reflection* true}
  :plugins [[lein-eftest "0.4.1"]]
  :eftest {:multithread?    true
           :report          eftest.report.progress/report
           :capture-output? true
           ;; warn on tests that take longer than 10 seconds
           :test-warn-time  (* 10 1e3)}
  :resource-paths ["resources"]
  :uberjar-name "lambda.jar"
  :main ^:skip-aot aurora-clone-manager.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
