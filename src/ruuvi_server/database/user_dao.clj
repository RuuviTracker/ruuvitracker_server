(ns ruuvi-server.database.user-dao
  (:require [clojure.java.jdbc :as sql]
            [ruuvi-server.cache :as cache]
            [clojure.string :as string]
            )
  (:use [ruuvi-server.database.entities :only ()]
        [clj-time.core :only (date-time now)]
        [clj-time.coerce :only (to-timestamp)]
        [clojure.tools.logging :only (debug info warn error)]
        ))

(defn- placeholders [values]
  (if values
    (let [values (vec (flatten [values]))]
      (string/join "," (take (count values) (repeat "?"))))
    ""))

  
(defn- in [field values]
  (let [v (vec (flatten [values]))
        p (placeholders values)]
    (vec (concat [(str (name field) " in (" p ")")] v))))

(defn get-user-by-username
  [db username]
  (let [query (in "select u.* from users u where u.username"
                    [username])]
    (first (sql/query db query))))

(defn get-users 
  [db user-ids]
  (let [query (if user-ids 
                (in "select u.* from users u where u.id"
                    user-ids)
                 ["select u.* from users u"])]
    (sql/query
      db
      query
      :row-fn #(dissoc % :password_hash :email))))

(defn get-user-visible-groups 
  "Fetch all groups where user is a member or owner."
  [db user-id & [group-ids]] 
  (let [base-select "select distinct g.* from groups g 
left join users_groups ug on (g.id = ug.group_id) 
where (g.owner_id = ? or ug.user_id = ?)"
        params (concat [user-id user-id] group-ids)]
    (if group-ids
      (let [group-select (str base-select " and g.id in (" (placeholders group-ids) ")")]
        (sql/query db (concat [group-select] params)))
      (sql/query db [base-select user-id user-id]))))

(defn get-user-owned-trackers "Get all trackers owned by user"
  [db user-id]
  (let [query [ "select t.* from trackers t 
where t.owner_id = ?" user-id] ]
  (sql/query db query)))

(defn get-user-visible-trackers "Get all trackers that belong to same group as user"
  [db user-id]
  (let [query [ "select distinct t.* from trackers t
 left join trackers_groups tg on (t.id = tg.tracker_id)
 left join users_groups ug on (ug.group_id = tg.group_id)
 where (t.owner_id is null or t.owner_id = ?) 
 or ug.user_id = ?" user-id user-id] ]
  (sql/query db query)))

(defn get-group-users "Fetch all users that belong to given group."
  [db group-ids] 
  (let [query (in "select u.* from users u 
join users_groups tg on (u.id = ug.user_id) 
where ug.group_id" group-ids)]
    (sql/query
      db
      query
      :row-fn #(dissoc % :password_hash))))


(defn get-group-trackers "Fetch all trackers that belong to group." 
  [db group-ids] 
  (let [query (in "select distinct t.* from trackers t 
join trackers_groups tg on (t.id = tg.tracker_id) 
where tg.group_id"
                  group-ids)]
    (sql/query
      db
      query
      :row-fn #(dissoc % :password :shared_secret))))

(defn get-groups [db group-ids]
  (let [query (if group-ids 
                (in "select g.* from groups g where g.id"
                    group-ids)
                 ["select g.* from groups g"])]
    (sql/query
      db
      query)))


;; transactions and authorization are handled in user_api
;; simple CRUD methods are here
(defn create-user!
  [db user] 
  (let [db-user (select-keys user [:username :email :name :password_hash])
        new-user (sql/insert! db :users db-user)]
    (dissoc (first new-user) :password_hash)
    ))

(defn create-group! 
  [db user-id group] 
  (let [db-group (assoc (select-keys group [:name])
                   :owner_id user-id)]
    (first (sql/insert! db :groups db-group)) ))

(defn remove-group! 
  [db group-ids]
  (sql/delete! db :groups (in "id" group-ids)))

(defn add-tracker-to-group!
  [db tracker group] 
  (let [user-group {:tracker_id (:id tracker) 
                    :group_id (:id group)}]
    (first (sql/insert! db :trackers_groups user-group))))

(defn add-user-to-group! 
  [db user group role] 
  (let [user-group {:user_id (:id user)
                    :group_id (:id group)
                    :role role}]
    (first (sql/insert! db :users_groups user-group) )))

(defn remove-trackers-from-group! [db tracker-id group-id]
  (sql/delete! db :trackers_groups ["tracker_id = ? and group_id = ?" tracker-id group-id]))


(defn remove-users-from-group! [db user-id group-id] 
  (sql/delete! db :users_groups ["user_id = ? and group_id = ?" user-id group-id]))


