(ns ruuvi-server.models.entities
  (:use korma.db)
  (:use korma.core)
  (:use ruuvi-server.standalone.config)
  )

(defn map-entities [database-spec]
  (defdb db (postgres database-spec))

  (defentity tracker
    (table :trackers)
    (pk :id)
    (entity-fields :id :tracker_identifier :name :latest_activity :shared_secret)
    )

  (defentity event-extension-type
    (table :event_extension_types)
    (pk :id)
    (entity-fields :name :description)
    )

  (defentity event-extension-value
    (table :event_extension_values)
    (pk :id)
    (entity-fields :value)
    (belongs-to event-extension-type {:fk :event_extension_type_id})
    )

  (defentity event-location
    (table :event_locations)
    (pk :id)
    (entity-fields :latitude :longitude)
    )
  
  (defentity event
    (table :events)
    (pk :id)
    (entity-fields :event_time :created_on)
    (belongs-to tracker {:fk :tracker_id})
    (has-one event-location {:fk :event_id})
    (has-many event-extension-value {:fk :event_id})
    )
)

;; private functions
(defn- to-sql-timestamp [date]
  (if date
    (java.sql.Date. (.getTime date))
    nil))

(defn- current-sql-timestamp []
  (java.sql.Date. (java.util.Date.)))

(defn- remove-nil-values [map-data]
  (flatten (filter (fn [x] (nth x 1) ) map-data) ))

;; public functions
(defn get-event [event_id]
  (first (select event
                 (with tracker)
                 (with event-location)
                 (with event-extension-value)
                 (where {:id event_id})))
  )

(defn get-tracker [id]
  (first (select tracker
                 (where {:id id}))))

(defn get-tracker-by-identifier [tracker-identifier]
  (first (select tracker
                 (where {:tracker_identifier tracker-identifier}))))

(defn get-tracker-by-identifier! [tracker-identifier & tracker-name]
  (let [existing-tracker (get-tracker-by-identifier tracker-identifier)]
    (if existing-tracker
      existing-tracker
      (insert tracker (values {:tracker_identifier tracker-identifier
                              :name (or tracker-name tracker-identifier)}))
      )))

(defn get-extension-type-by-name [type-name]
  (first (select event-extension-type
                 (where {:name (str (name type-name))}))))

(defn get-extension-type-by-name! [type-name]
  (let [existing-extension-type (get-extension-type-by-name type-name)]
    (if existing-extension-type
      existing-extension-type
      (insert event-extension-type (values {:name (str (name type-name))
                                            :description "Autogenerated"}))
      )))

(defn create-event [data]
  (let [extension-keys (filter (fn [key]
                                 (.startsWith (name key) "X-"))
                               (keys data))
        tracker (get-tracker-by-identifier! (:tracker_identifier data))
        latitude (:latitude data)
        longitude (:longitude data)

        event-entity (insert event (values
                                    {:tracker_id (tracker :id)
                                     :event_time (or (to-sql-timestamp (:event_time data))
                                                     (current-sql-timestamp) )
                                     }))]

    (if (and latitude longitude)
      (insert event-location (values
                              {:event_id (:id event-entity)
                               :latitude latitude
                               :longitude longitude
                               :accuracy (:accuracy data)
                               :satellite_count (:satellite_count data)
                               :altitude (:altitude data)}))
      )
    
    (doseq [key extension-keys]
      (insert event-extension-value
              (values
               {:event_id (:id event-entity)
                :value (data key)
                :event_extension_type_id (:id (get-extension-type-by-name! key))
                }
               )))
    event-entity
 ))
