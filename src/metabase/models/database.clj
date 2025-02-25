(ns metabase.models.database
  (:require [cheshire.generate :refer [add-encoder encode-map]]
            [clojure.tools.logging :as log]
            [java-time :as t]
            [medley.core :as m]
            [metabase.api.common :refer [*current-user*]]
            [metabase.db.util :as mdb.u]
            [metabase.driver :as driver]
            [metabase.driver.util :as driver.u]
            [metabase.models.interface :as i]
            [metabase.models.permissions :as perms]
            [metabase.models.permissions-group :as perm-group]
            [metabase.models.secret :as secret :refer [Secret]]
            [metabase.plugins.classloader :as classloader]
            [metabase.public-settings.premium-features :as premium-features]
            [metabase.util :as u]
            [metabase.util.i18n :refer [trs tru]]
            [toucan.db :as db]
            [toucan.models :as models]))

;;; ----------------------------------------------- Entity & Lifecycle -----------------------------------------------

(models/defmodel Database :metabase_database)

(defn- schedule-tasks!
  "(Re)schedule sync operation tasks for `database`. (Existing scheduled tasks will be deleted first.)"
  [database]
  (try
    ;; this is done this way to avoid circular dependencies
    (classloader/require 'metabase.task.sync-databases)
    ((resolve 'metabase.task.sync-databases/check-and-schedule-tasks-for-db!) database)
    (catch Throwable e
      (log/error e (trs "Error scheduling tasks for DB")))))

;; TODO - something like NSNotificationCenter in Objective-C would be really really useful here so things that want to
;; implement behavior when an object is deleted can do it without having to put code here

(defn- unschedule-tasks!
  "Unschedule any currently pending sync operation tasks for `database`."
  [database]
  (try
    (classloader/require 'metabase.task.sync-databases)
    ((resolve 'metabase.task.sync-databases/unschedule-tasks-for-db!) database)
    (catch Throwable e
      (log/error e (trs "Error unscheduling tasks for DB.")))))

(defn- post-insert [database]
  (u/prog1 database
    ;; add this database to the All Users permissions groups
    (perms/grant-full-db-permissions! (perm-group/all-users) database)
    ;; schedule the Database sync & analyze tasks
    (schedule-tasks! database)))

(defn- post-select [{driver :engine, :as database}]
  (cond-> database
    (driver/initialized? driver)
    ;; TODO - this is only really needed for API responses. This should be a `hydrate` thing instead!
    (as-> db* ; database from outer cond->
        (assoc db* :features (driver.u/features driver database))
        (if (:details db*)
          (driver/normalize-db-details driver db*)
          db*))))

(defn- conn-props->secret-props-by-name
  "For the given `conn-props` (output of `driver/connection-properties`), return a map of all secret type properties,
  keyed by property name."
  [conn-props]
  (->> (filter #(= "secret" (:type %)) conn-props)
    (reduce (fn [acc prop] (assoc acc (:name prop) prop)) {})))

(defn- delete-orphaned-secrets!
  "Delete Secret instances from the app DB, that will become orphaned when `database` is deleted. For now, this will
  simply delete any Secret whose ID appears in the details blob, since every Secret instance that is currently created
  is exclusively associated with a single Database.

  In the future, if/when we allow arbitrary association of secret instances to database instances, this will need to
  change and become more complicated (likely by consulting a many-to-many join table)."
  [{:keys [id details] :as database}]
  (when-let [conn-props-fn (get-method driver/connection-properties (driver.u/database->driver database))]
    (let [conn-props                 (conn-props-fn (driver.u/database->driver database))
          possible-secret-prop-names (keys (conn-props->secret-props-by-name conn-props))]
      (doseq [secret-id (reduce (fn [acc prop-name]
                                  (if-let [secret-id (get details (keyword (str prop-name "-id")))]
                                    (conj acc secret-id)
                                    acc))
                                []
                                possible-secret-prop-names)]
        (log/info (trs "Deleting secret ID {0} from app DB because the owning database ({1}) is being deleted"
                       secret-id
                       id))
        (db/delete! Secret :id secret-id)))))

(defn- pre-delete [{id :id, driver :engine, details :details :as database}]
  (unschedule-tasks! database)
  (db/execute! {:delete-from (db/resolve-model 'Permissions)
                :where       [:or
                              [:like :object (str (perms/data-perms-path id) "%")]
                              [:= :object (perms/database-block-perms-path id)]]})
  (delete-orphaned-secrets! database)
  (try
    (driver/notify-database-updated driver database)
    (catch Throwable e
      (log/error e (trs "Error sending database deletion notification")))))

(defn- handle-db-details-secret-prop!
  "Helper fn for reducing over a map of all the secret connection-properties, keyed by name. This is side effecting. At
  each iteration step, if there is a -value suffixed property set in the details to be persisted, then we instead insert
  (or update an existing) Secret instance and point to the inserted -id instead."
  [database details conn-prop-nm conn-prop]
  (let [sub-prop  (fn [suffix]
                    (keyword (str conn-prop-nm suffix)))
        id-kw     (sub-prop "-id")
        new-name  (format "%s for %s" (:display-name conn-prop) (:name database))
        ;; in the future, when secret values can simply be changed by passing
        ;; in a new ID (as opposed to a new value), this behavior will change,
        ;; but for now, we should simply look for the value
        path-kw   (sub-prop "-path")
        value-kw  (sub-prop "-value")
        value     (if-let [v (value-kw details)]     ; the -value suffix was specified; use that
                    v
                    (when-let [path (path-kw details)] ; the -path suffix was specified; this is actually a :file-path
                      (when (premium-features/is-hosted?)
                        (throw (ex-info
                                (tru "{0} (a local file path) cannot be used in Metabase hosted environment" path-kw)
                                {:invalid-db-details-entry (select-keys details [path-kw])})))
                      path))
        kind      (:secret-kind conn-prop)
        source    (when (path-kw details)
                    :file-path)]                     ; set the :source due to the -path suffix (see above)
    (if (nil? value) ;; secret value for this conn prop was not changed
      details
      (let [{:keys [id creator_id created_at]} (secret/upsert-secret-value!
                                                 (id-kw details)
                                                 new-name
                                                 kind
                                                 source
                                                 value)]
        ;; remove the -value keyword (since in the persisted details blob, we only ever want to store the -id)
        (-> details
          (dissoc value-kw)
          (assoc id-kw id)
          (assoc (sub-prop "-source") source)
          (assoc (sub-prop "-creator-id") creator_id)
          (assoc (sub-prop "-created-at") (t/format :iso-offset-date-time created_at)))))))

(defn- handle-secrets-changes [{:keys [details] :as database}]
  (let [conn-props-fn (get-method driver/connection-properties (driver.u/database->driver database))]
    (cond (nil? conn-props-fn)
          database ; no connection-properties multimethod defined; can't check secret types so do nothing

          details ; we have details populated in this Database instance, so handle them
          (let [conn-props            (conn-props-fn (driver.u/database->driver database))
                conn-secrets-by-name  (conn-props->secret-props-by-name conn-props)
                updated-details       (reduce-kv (partial handle-db-details-secret-prop! database)
                                                 details
                                                 conn-secrets-by-name)]
           (assoc database :details updated-details))

          :else ; no details populated; do nothing
          database)))

(defn- pre-update
  [{new-metadata-schedule :metadata_sync_schedule, new-fieldvalues-schedule :cache_field_values_schedule, :as database}]
  (u/prog1 (handle-secrets-changes database)
    ;; TODO - this logic would make more sense in post-update if such a method existed
    ;; if the sync operation schedules have changed, we need to reschedule this DB
    (when (or new-metadata-schedule new-fieldvalues-schedule)
      (let [{old-metadata-schedule    :metadata_sync_schedule
             old-fieldvalues-schedule :cache_field_values_schedule
             existing-engine          :engine
             existing-name            :name} (db/select-one [Database
                                                             :metadata_sync_schedule
                                                             :cache_field_values_schedule
                                                             :engine
                                                             :name]
                                                            :id (u/the-id database))
            ;; if one of the schedules wasn't passed continue using the old one
            new-metadata-schedule            (or new-metadata-schedule old-metadata-schedule)
            new-fieldvalues-schedule         (or new-fieldvalues-schedule old-fieldvalues-schedule)]
        (when-not (= [new-metadata-schedule new-fieldvalues-schedule]
                     [old-metadata-schedule old-fieldvalues-schedule])
          (log/info
           (trs "{0} Database ''{1}'' sync/analyze schedules have changed!" existing-engine existing-name)
           "\n"
           (trs "Sync metadata was: ''{0}'' is now: ''{1}''" old-metadata-schedule new-metadata-schedule)
           "\n"
           (trs "Cache FieldValues was: ''{0}'', is now: ''{1}''" old-fieldvalues-schedule new-fieldvalues-schedule))
          ;; reschedule the database. Make sure we're passing back the old schedule if one of the two wasn't supplied
          (schedule-tasks!
           (assoc database
             :metadata_sync_schedule      new-metadata-schedule
             :cache_field_values_schedule new-fieldvalues-schedule)))))))

(defn- pre-insert [database]
  (handle-secrets-changes database))

(defn- perms-objects-set [database _]
  #{(perms/data-perms-path (u/the-id database))})

(u/strict-extend (class Database)
  models/IModel
  (merge models/IModelDefaults
         {:hydration-keys (constantly [:database :db])
          :types          (constantly {:details                     :encrypted-json
                                       :options                     :json
                                       :engine                      :keyword
                                       :metadata_sync_schedule      :cron-string
                                       :cache_field_values_schedule :cron-string
                                       :start_of_week               :keyword})
          :properties     (constantly {:timestamped? true})
          :post-insert    post-insert
          :post-select    post-select
          :pre-insert     pre-insert
          :pre-update     pre-update
          :pre-delete     pre-delete})
  i/IObjectPermissions
  (merge i/IObjectPermissionsDefaults
         {:perms-objects-set perms-objects-set
          :can-read?         (partial i/current-user-has-partial-permissions? :read)
          :can-write?        i/superuser?}))


;;; ---------------------------------------------- Hydration / Util Fns ----------------------------------------------

(defn ^:hydrate tables
  "Return the `Tables` associated with this `Database`."
  [{:keys [id]}]
  ;; TODO - do we want to include tables that should be `:hidden`?
  (db/select 'Table, :db_id id, :active true, {:order-by [[:%lower.display_name :asc]]}))

(defn schema-names
  "Return a *sorted set* of schema names (as strings) associated with this `Database`."
  [{:keys [id]}]
  (when id
    (apply sorted-set (db/select-field :schema 'Table
                        :db_id id
                        {:modifiers [:DISTINCT]}))))

(defn pk-fields
  "Return all the primary key `Fields` associated with this `database`."
  [{:keys [id]}]
  (let [table-ids (db/select-ids 'Table, :db_id id, :active true)]
    (when (seq table-ids)
      (db/select 'Field, :table_id [:in table-ids], :semantic_type (mdb.u/isa :type/PK)))))

(defn schema-exists?
  "Does `database` have any tables with `schema`?"
  ^Boolean [{:keys [id]}, schema]
  (db/exists? 'Table :db_id id, :schema (some-> schema name)))


;;; -------------------------------------------------- JSON Encoder --------------------------------------------------

(def ^:const protected-password
  "The string to replace passwords with when serializing Databases."
  "**MetabasePass**")

(defn sensitive-fields-for-db
  "Gets all sensitive fields that should be redacted in API responses for a given database. Delegates to
  driver.u/sensitive-fields using the given database's driver (if valid), so refer to that for full details. If a valid
  driver can't be clearly determined, this simply returns the default set (driver.u/default-sensitive-fields)."
  [database]
  (if (and (some? database) (not-empty database))
      (let [driver (driver.u/database->driver database)]
        (if (some? driver)
            (driver.u/sensitive-fields (driver.u/database->driver database))
            driver.u/default-sensitive-fields))
      driver.u/default-sensitive-fields))

;; when encoding a Database as JSON remove the `details` for any non-admin User. For admin users they can still see
;; the `details` but remove anything resembling a password. No one gets to see this in an API response!
(add-encoder
 DatabaseInstance
 (fn [db json-generator]
   (encode-map
    (if (not (:is_superuser @*current-user*))
      (dissoc db :details)
      (update db :details (fn [details]
                            (reduce
                             #(m/update-existing %1 %2 (constantly protected-password))
                             details
                             (sensitive-fields-for-db db)))))
    json-generator)))
