(defproject ruuvi-server "0.0.1-SNAPSHOT"
  :description "RuuviTracker server"
  :dependencies
  [
   [org.clojure/clojure "1.2.1"]
   [compojure "1.0.1"]
   [ring-json-params "0.1.3"]
   [clj-json "0.5.0"]
   [ring "1.0.1"]
   [ring/ring-servlet "1.0.1"]
   [ring/ring-jetty-adapter "1.0.1"]
   [sandbar "0.4.0-SNAPSHOT"]
   [enlive "1.0.0"]
   [org.clojure/tools.logging "0.2.3" :exclusions [log4j/log4j
                                                   commons-logging/commons-logging
                                                   org.slf4j/slf4j-api
                                                   org.slf4j/slf4j-log4j12]]
   ;; java deps
   [joda-time/joda-time "2.0"]
   [ch.qos.logback/logback-classic "1.0.0"]
   [org.slf4j/log4j-over-slf4j "1.6.4"]
   [commons-codec/commons-codec "1.6"]
   ;; database
   [postgresql/postgresql "8.4-702.jdbc4"]
   [org.apache.tomcat/tomcat-jdbc "7.0.25"]
   [org.clojure/java.jdbc "0.1.1"]
   [korma "0.3.0-beta3" :exclusions [log4j/log4j] ]
   [lobos "0.8.0"]
   ]
  :dev-dependencies
  [
   [ring/ring-devel "1.0.1"]
   ]
  :ring {:handler ruuvi-tracker.core/dev-app}
  :repositories {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  )


