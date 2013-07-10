(ns ruuvi-server.database.event-dao
  (:require [ruuvi-server.configuration :as conf]
            [ruuvi-server.util :as util]
            [clojure.string :as string]
            [ruuvi-server.cache :as cache]
            [clojure.java.jdbc :as sql]
            )
  (:use [korma.db :only (transaction)]
        [korma.core :only (select where insert update values
                                  set-fields with limit order fields)]
        [ruuvi-server.database.entities :only (tracker event event-session event-extension-type
                                                       event-extension-value event-location
                                                       event-annotation)]
        [clj-time.core :only (date-time now)]
        [clj-time.coerce :only (to-timestamp)]
        [clojure.string :only (join)]
        [clojure.tools.logging :only (debug info warn error)]
        )
  (:import [org.joda.time DateTime])
  )

(defn- get-pk "Fetch primary key from just inserted row."
  [insert-result]
  ;; scope_identity is for H2
  (some insert-result [:id
                       (keyword "SCOPE_IDENTITY()")
                       (keyword "scope_identity()")]))

(defn- current-sql-timestamp []
  (to-timestamp (now)))

(defn get-trackers [ids]
  (select tracker
          (where (in :id ids))))

(defn get-tracker-by-code [tracker-code]
  (let [tracker-code (string/lower-case tracker-code)]
    (first (select tracker
                   (where {:tracker_code tracker-code})))))

;; TODO add caching
(defn get-tracker-by-code! [tracker-code & tracker-name]
  (util/try-times 1 
                  (let [tracker-code (string/lower-case tracker-code)
                        existing-tracker (get-tracker-by-code tracker-code)]
                    (if existing-tracker
                      existing-tracker
                      (insert tracker (values {:tracker_code tracker-code
                                               :name (or tracker-name tracker-code)}))))))

;; TODO needed
(defn get-all-trackers []
  (select tracker))

(defn get-tracker [id]
  ;; TODO support also fetching with tracker_indentifier?
  ;; duplicate with get-trackers
  (first (select tracker
                 (where {:id id}))))  

;; TODO bad API
(defn get-event-sessions [{:keys [tracker_ids event_session_ids]}]
  (let [tracker-ids-crit (when tracker_ids {:tracker_id ['in tracker_ids]})
        session-ids-crit (when event_session_ids {:id ['in event_session_ids]})
        conditions (filter identity (list tracker-ids-crit session-ids-crit))]
    (select event-session
            (where (apply and conditions)))))

(defn- update-tracker-latest-activity [id]
  (update tracker
          (set-fields {:latest_activity (java.sql.Timestamp. (System/currentTimeMillis)) })
          (where {:id id})))

(defn create-tracker [owner-id code name shared-secret password description public]
  (let [tracker-code (string/lower-case code)]
    (info "Create new tracker" name "(" tracker-code ")")
    (insert tracker (values
                     {:tracker_code tracker-code
                      :owner_id owner-id
                      :description description
                      :shared_secret shared-secret
                      :name name
                      :password password
                      :public public}))))

(defn- get-event-session-for-code [tracker-id session-code]
  (first (select event-session
                 (where {:tracker_id tracker-id
                         :session_code session-code}))))

(defn- get-event-session-for-code! [tracker-id session-code & timestamp]
  (util/try-times 1
                  (let [existing-session (get-event-session-for-code tracker-id session-code)]
                    (if existing-session
                      ;; update latest timestamp, if timestamp != null
                      existing-session
                      (insert event-session (values {:tracker_id tracker-id
                                                     :session_code session-code
                                                     :first_event_time timestamp
                                                     :latest_event_time timestamp}))))
                  ))
  
(defn get-extension-type-by-id [id]
  (first (select event-extension-type
                 (where {:name id}))))


(defn get-extension-type-by-name [type-name]
  (first (select event-extension-type
                 (where {:name (str (name type-name))}))))

(def ^{:private true} cache-event-extra-data (cache/create-cache-region :event-extra-data 10000 (* 24 60 60 1000)))

;; TODO add caching
(defn get-extension-type-by-name! [type-name]
  (util/try-times 1
                  (let [existing-extension-type (get-extension-type-by-name type-name)]
                    (if existing-extension-type
                      existing-extension-type
                      (insert event-extension-type (values {:name (str (name type-name))
                                                            :description "Autogenerated"}))
                      ))))

(defn get-event [event_id]
  (first (select event
                 (with tracker)
                 (with event-location)
                 (with event-extension-value)
                 (where {:id event_id})))
  )

(def ^{:private true} cache-event-extra-data (cache/create-cache-region :event-extension-type 10000 (* 24 60 60 1000)))

;; TODO cache access to event-extension-type
(defn- get-event-extra-data [event-id]
    {:event_locations (select event-location
                              (where {:event_id event-id}))
     :event_extension_values (select event-extension-value
                                     (fields :value)
                                     (with event-extension-type (fields :name))
                                     (where {:event_id event-id}))
     }
  )

(defn- max-search-result-size
  "Maximum number of events to return in event search"
  [conf criteria]
  (let [allowed-max-result-count (get-in conf [:client-api :allowed-max-search-results] )
        max-results (if (:maxResults criteria)
                      (:maxResults criteria)
                      (get-in conf [:client-api :default-max-search-results] 100))
        result-limit (apply min (filter identity [allowed-max-result-count max-results]))
        ]
    result-limit))


;; {:where "where a = ? and c = ?"  :params [1 "2"]}
(defn make-sql-crit [criteria]
  (let [event-start (to-timestamp (:eventTimeStart criteria))
        event-end (to-timestamp (:eventTimeEnd criteria))
        store-start (to-timestamp (:storeTimeStart criteria))
        store-end (to-timestamp (:storeTimeEnd criteria))
        tracker-ids (filter identity (:trackerIds criteria))
        session-ids (filter identity (:sessionIds criteria))

        params (filter identity (flatten [event-start event-end store-start
                                          store-end tracker-ids session-ids]))
        conds '[]
        conds (conj conds (when event-start "e.event_time >= ?"))
        conds (conj conds (when event-end "e.event_time <= ?"))
        conds (conj conds (when store-start "e.created_on >= ?"))
        conds (conj conds (when store-end "e.created_on <= ?"))
        conds (conj conds (when (not (empty? tracker-ids))
                           (let [tracker-id-binds (join "," (repeat (count tracker-ids) "?"))]
                             (str "e.tracker_id in (" tracker-id-binds ")"))))
        conds (conj conds (when (not (empty? session-ids))
                           (let [session-id-binds (join "," (repeat (count session-ids) "?"))]
                             (str "e.event_session_id in (" session-id-binds ")"))))

        conds (filter identity conds)

        order-by-crit (:orderBy criteria)
        order-by (cond
                  (= order-by-crit :latest-store-time) "order by e.created_on desc"
                  (= order-by-crit :latest-event-time) "order by e.event_time desc"
                  :default "order by e.event_time asc")
        ] 
        
    {:params params
     :conds (join " and " conds)
     :order-by order-by}
    ))

(defn- make-search-results [rows]
  (map (fn [row]
         (let [event-data (select-keys row [:id :tracker_id :event_session_id
                                            :created_on :event_time])
               loc-data (select-keys row [:latitude :longitude :speed
                                          :satellite_count :heading :altitude
                                          :horizontal_accuracy :vertical_accuracy])
               event-id (:id event-data)
               extension-values (cache/lookup cache-event-extra-data event-id
                                              (fn [id] (select event-extension-value
                                                               (fields :value)
                                                               (with event-extension-type (fields :name))
                                                               (where {:event_id id}))))
               
               ]
           (merge event-data {:event_locations [loc-data]
                              :event_extension_values extension-values} )))
       rows))


(defn search-events
  "Search events: criteria is a map that can contain following keys.
- :storeTimeStart <DateTime>, find events that are created (stored) to database later than given time (inclusive).
- :storeTimeEnd <DateTime>, find events that are created (stored) to database earlier than given time (inclusive).
- :eventTimeStart <DateTime>, find events that are created in tracker later than given time (inclusive).
- :eventTimeEnd <DateTime>, find events that are created in tracker earlier than given time (inclusive).
- :trackerIds List of tracker ids
- :sessionIds List of session ids
- :maxResults <Integer>, maximum number of events. Default and maximum is 50.
TODO calculates milliseconds wrong (12:30:01.000 is rounded to 12:30:01 but 12:30:01.001 is rounded to 12:30:02)
"

  [criteria]
  (let [result-limit (max-search-result-size (conf/get-config) criteria)
        conn (get-in (conf/get-config) [:database])
        opts {:result-type :forward-only
              :concurrency :read-only
              :fetch-size (min 1000 result-limit)
              :max-rows result-limit
              }
        conditions (make-sql-crit criteria)
        ]

    (if (not (empty? (:conds conditions)))
      (sql/with-connection conn
        (let [sql (str "select e.*, l.* from events e "
                       "left outer join event_locations l on (e.id = l.event_id) "
                       "where " (:conds conditions) " "
                       (:order-by conditions) " limit " result-limit)
              params (:params conditions)
              sql-params (into [] (concat [opts sql] params))
              ]
          (apply sql/with-query-results*
                 [sql-params #(doall (make-search-results %))])
          ))
      '())))

(defn search-events-2
  "Search events: criteria is a map that can contain following keys.
- :storeTimeStart <DateTime>, find events that are created (stored) to database later than given time (inclusive).
- :storeTimeEnd <DateTime>, find events that are created (stored) to database earlier than given time (inclusive).
- :eventTimeStart <DateTime>, find events that are created in tracker later than given time (inclusive).
- :eventTimeEnd <DateTime>, find events that are created in tracker earlier than given time (inclusive).
- :maxResults <Integer>, maximum number of events. Default and maximum is 50.
TODO calculates milliseconds wrong (12:30:01.000 is rounded to 12:30:01 but 12:30:01.001 is rounded to 12:30:02)
"
  [criteria]

  (let [event-start (to-timestamp (:eventTimeStart criteria))
        event-end (to-timestamp (:eventTimeEnd criteria))
        store-start (to-timestamp (:storeTimeStart criteria))
        store-end (to-timestamp (:storeTimeEnd criteria))
        tracker-ids (:trackerIds criteria)
        session-ids (:sessionIds criteria)
        allowed-max-result-count (get-in (conf/get-config) [:client-api :allowed-max-search-results] )
        max-results (if (:maxResults criteria)
                      (:maxResults criteria)
                      (get-in (conf/get-config) [:client-api :default-max-search-results] 100))
        result-limit (apply min (filter identity [allowed-max-result-count max-results]))

        tracker-ids-crit (when tracker-ids {:tracker_id ['in tracker-ids]})
        session-ids-crit (when session-ids {:event_session_id ['in session-ids]})
        event-start-crit (when event-start {:event_time ['>= event-start]})
        event-end-crit (when event-end {:event_time ['<= event-end]})
        store-start-crit (when store-start {:created_on ['>= store-start]})
        store-end-crit (when store-end {:created_on ['<= store-end]})
        
        conditions (filter identity (list event-start-crit event-end-crit
                                          store-start-crit store-end-crit
                                          tracker-ids-crit session-ids-crit))
        order-by-crit (:orderBy criteria)
        order-by (cond
                  (= order-by-crit :latest-store-time) [:created_on :DESC]
                  (= order-by-crit :latest-event-time) [:event_time :DESC]
                  :default [:event_time :ASC])
        ]

    (if (not (empty? conditions))

      (let [results (select event
                            (where (apply and conditions))
                            (order (order-by 0) (order-by 1))
                            (limit result-limit)) ]
        (map (fn [event]
               (let [event-id (:id event)
                     extra-data (cache/lookup cache-event-extra-data event-id
                                              get-event-extra-data)]
                 (merge event extra-data)
                 )) results)
        )
      '() )))

(defn get-events [ids]
  (select event
          (with event-location)
          (with event-extension-value
                (with event-extension-type (fields :name)))
          (where (in :id ids)) ))

(defn get-all-events []
  (select event
          (with event-location)
          (with event-extension-value) ))

(defn create-event [data]
  (transaction
   (let [event-time (or (to-timestamp (:event_time data))
                        (current-sql-timestamp))
         tracker (get-tracker-by-code! (:tracker_code data))
         tracker-id (get-pk tracker)
         ;; TODO trim session_code to length of db field
         session-code (or (:session_code data) "default")
         event-session (get-event-session-for-code! tracker-id session-code event-time)
         event-session-id (get-pk event-session)]

     (update-tracker-latest-activity tracker-id)
     (let [extension-keys (filter (fn [key]
                                    (.startsWith (str (name key)) "X-"))
                                  (keys data))
           latitude (:latitude data)
           longitude (:longitude data)
           
           event-entity (insert event (values
                                       {:tracker_id tracker-id
                                        :event_session_id event-session-id
                                        :event_time event-time
                                        }))
           event-id (get-pk event-entity)

           location-entity
           (when (and latitude longitude)
             (insert event-location (values
                                     {:event_id event-id
                                      :latitude latitude
                                      :longitude longitude
                                      :horizontal_accuracy (:horizontal_accuracy data)
                                      :vertical_accuracy (:vertical_accuracy data)
                                      :speed (:speed data)
                                      :heading (:heading data)
                                      :satellite_count (:satellite_count data)
                                      :altitude (:altitude data)})))
           
           annotation-entity
           (when-let [annotation (:annotation data)]
             ;; TODO cut too long annotation value to match db field
             (insert event-annotation (values
                                       {:event_id event-id
                                        :annotation annotation})))
           
           extension-entities
           (map (fn [key]
                  (let [value-entity
                        (insert event-extension-value
                                (values
                                 {:event_id event-id
                                  :value (data key)
                                  :event_extension_type_id (:id (get-extension-type-by-name! key))
                                  }
                                 ))]
                    (select-keys (merge value-entity {:name key}) [:name :value])
                    )) extension-keys)
           ]
       ;; reconstruct event-entity to be same as entities returned by
       ;; search

       ;; TODO h2 db is not returning correct values when inserting,
       ;; so put event-id  and tracker-id to result
       (merge event-entity {:event_locations (when location-entity [location-entity]) 
                            :event_annotations (when annotation-entity [annotation-entity])
                            :event_extension_values extension-entities
                            :id event-id
                            :tracker_id tracker-id})
       ))))

