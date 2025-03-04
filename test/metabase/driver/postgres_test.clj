(ns metabase.driver.postgres-test
  "Tests for features/capabilities specific to PostgreSQL driver, such as support for Postgres UUID or enum types."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [honeysql.core :as hsql]
            [metabase.config :as config]
            [metabase.driver :as driver]
            [metabase.driver.postgres :as postgres]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.models.database :refer [Database]]
            [metabase.models.field :refer [Field]]
            [metabase.models.table :refer [Table]]
            [metabase.query-processor :as qp]
            [metabase.sync :as sync]
            [metabase.sync.sync-metadata :as sync-metadata]
            [metabase.test :as mt]
            [metabase.util :as u]
            [metabase.util.honeysql-extensions :as hx]
            [toucan.db :as db])
  (:import java.sql.DatabaseMetaData))

(defn- drop-if-exists-and-create-db!
  "Drop a Postgres database named `db-name` if it already exists; then create a new empty one with that name."
  [db-name]
  (let [spec (sql-jdbc.conn/connection-details->spec :postgres (mt/dbdef->connection-details :postgres :server nil))]
    ;; kill any open connections
    (jdbc/query spec ["SELECT pg_terminate_backend(pg_stat_activity.pid)
                       FROM pg_stat_activity
                       WHERE pg_stat_activity.datname = ?;" db-name])
    ;; create the DB
    (jdbc/execute! spec [(format "DROP DATABASE IF EXISTS \"%s\";
                                  CREATE DATABASE \"%s\";"
                                 db-name db-name)]
                   {:transaction? false})))

(defn- exec!
  "Execute a sequence of statements against the database whose spec is passed as the first param."
  [spec statements]
  (doseq [statement statements]
    (jdbc/execute! spec [statement])))

;;; ----------------------------------------------- Connection Details -----------------------------------------------

(deftest connection-details->spec-test
  (testing (str "Check that SSL params get added the connection details in the way we'd like # no SSL -- this should "
                "*not* include the key :ssl (regardless of its value) since that will cause the PG driver to use SSL "
                "anyway")
    (is (= {:classname                     "org.postgresql.Driver"
            :subprotocol                   "postgresql"
            :subname                       "//localhost:5432/bird_sightings"
            :OpenSourceSubProtocolOverride true
            :user                          "camsaul"
            :sslmode                       "disable"
            :ApplicationName               config/mb-version-and-process-identifier}
           (sql-jdbc.conn/connection-details->spec :postgres
             {:ssl    false
              :host   "localhost"
              :port   5432
              :dbname "bird_sightings"
              :user   "camsaul"}))))
  (testing "ssl - check that expected params get added"
    (is (= {:classname                     "org.postgresql.Driver"
            :subprotocol                   "postgresql"
            :subname                       "//localhost:5432/bird_sightings"
            :OpenSourceSubProtocolOverride true
            :user                          "camsaul"
            :ssl                           true
            :sslmode                       "require"
            :ApplicationName               config/mb-version-and-process-identifier}
           (sql-jdbc.conn/connection-details->spec :postgres
             {:ssl    true
              :host   "localhost"
              :port   5432
              :dbname "bird_sightings"
              :user   "camsaul"}))))
  (testing "make sure connection details w/ extra params work as expected"
    (is (= {:classname                     "org.postgresql.Driver"
            :subprotocol                   "postgresql"
            :subname                       "//localhost:5432/cool?prepareThreshold=0"
            :OpenSourceSubProtocolOverride true
            :sslmode                       "disable"
            :ApplicationName               config/mb-version-and-process-identifier}
           (sql-jdbc.conn/connection-details->spec :postgres
             {:host               "localhost"
              :port               "5432"
              :dbname             "cool"
              :additional-options "prepareThreshold=0"}))))
  (testing "user-specified SSL options should always take precendence over defaults"
    (is (= {:classname                     "org.postgresql.Driver"
            :subprotocol                   "postgresql"
            :subname                       "//localhost:5432/bird_sightings"
            :OpenSourceSubProtocolOverride true
            :user                          "camsaul"
            :ssl                           true
            :sslmode                       "verify-ca"
            :sslcert                       "my-cert"
            :sslkey                        "my-key"
            :sslfactory                    "myfactoryoverride"
            :sslrootcert                   "myrootcert"
            :ApplicationName               config/mb-version-and-process-identifier}
           (sql-jdbc.conn/connection-details->spec :postgres
             {:ssl         true
              :host        "localhost"
              :port        5432
              :dbname      "bird_sightings"
              :user        "camsaul"
              :sslmode     "verify-ca"
              :sslcert     "my-cert"
              :sslkey      "my-key"
              :sslfactory  "myfactoryoverride"
              :sslrootcert "myrootcert"})))))


;;; ------------------------------------------- Tests for sync edge cases --------------------------------------------

(mt/defdataset dots-in-names
  [["objects.stuff"
    [{:field-name "dotted.name", :base-type :type/Text}]
    [["toucan_cage"]
     ["four_loko"]
     ["ouija_board"]]]])

(deftest edge-case-identifiers-test
  (mt/test-driver :postgres
    (testing "Make sure that Tables / Fields with dots in their names get escaped properly"
      (mt/dataset dots-in-names
        (= {:columns ["id" "dotted.name"]
            :rows    [[1 "toucan_cage"]
                      [2 "four_loko"]
                      [3 "ouija_board"]]}
           (mt/rows+column-names (mt/run-mbql-query objects.stuff)))))
    (testing "make sure schema/table/field names with hyphens in them work correctly (#8766)"
      (let [details (mt/dbdef->connection-details :postgres :db {:database-name "hyphen-names-test"})
            spec    (sql-jdbc.conn/connection-details->spec :postgres details)]
        ;; create the postgres DB
        (drop-if-exists-and-create-db! "hyphen-names-test")
        ;; create the DB object
        (mt/with-temp Database [database {:engine :postgres, :details (assoc details :dbname "hyphen-names-test")}]
          (let [sync! #(sync/sync-database! database)]
            ;; populate the DB and create a view
            (exec! spec ["CREATE SCHEMA \"x-mas\";"
                         "CREATE TABLE \"x-mas\".\"presents-and-gifts\" (\"gift-description\" TEXT NOT NULL);"
                         "INSERT INTO \"x-mas\".\"presents-and-gifts\" (\"gift-description\") VALUES ('Bird Hat');;"])
            (sync!)
            (is (= [["Bird Hat"]]
                   (mt/rows (qp/process-query
                              {:database (u/the-id database)
                               :type     :query
                               :query    {:source-table (db/select-one-id Table :name "presents-and-gifts")}}))))))))))

(mt/defdataset duplicate-names
  [["birds"
    [{:field-name "name", :base-type :type/Text}]
    [["Rasta"]
     ["Lucky"]]]
   ["people"
    [{:field-name "name", :base-type :type/Text}
     {:field-name "bird_id", :base-type :type/Integer, :fk :birds}]
    [["Cam" 1]]]])

(deftest duplicate-names-test
  (mt/test-driver :postgres
    (testing "Make sure that duplicate column names (e.g. caused by using a FK) still return both columns"
      (mt/dataset duplicate-names
        (is (= {:columns ["name" "name_2"]
                :rows    [["Cam" "Rasta"]]}
               (mt/rows+column-names
                 (mt/run-mbql-query people
                   {:fields [$name $bird_id->birds.name]}))))))))

(defn- default-table-result [table-name]
  {:name table-name, :schema "public", :description nil})

(deftest materialized-views-test
  (mt/test-driver :postgres
    (testing (str "Check that we properly fetch materialized views. As discussed in #2355 they don't come back from "
                  "JDBC `DatabaseMetadata` so we have to fetch them manually.")
      (drop-if-exists-and-create-db! "materialized_views_test")
      (let [details (mt/dbdef->connection-details :postgres :db {:database-name "materialized_views_test"})]
        (jdbc/execute! (sql-jdbc.conn/connection-details->spec :postgres details)
                       ["DROP MATERIALIZED VIEW IF EXISTS test_mview;
                       CREATE MATERIALIZED VIEW test_mview AS
                       SELECT 'Toucans are the coolest type of bird.' AS true_facts;"])
        (mt/with-temp Database [database {:engine :postgres, :details (assoc details :dbname "materialized_views_test")}]
          (is (= {:tables #{(default-table-result "test_mview")}}
                 (driver/describe-database :postgres database))))))))

(deftest foreign-tables-test
  (mt/test-driver :postgres
    (testing "Check that we properly fetch foreign tables."
      (drop-if-exists-and-create-db! "fdw_test")
      (let [details (mt/dbdef->connection-details :postgres :db {:database-name "fdw_test"})]
        (jdbc/execute! (sql-jdbc.conn/connection-details->spec :postgres details)
                       [(str "CREATE EXTENSION IF NOT EXISTS postgres_fdw;
                              CREATE SERVER foreign_server
                                FOREIGN DATA WRAPPER postgres_fdw
                                OPTIONS (host '" (:host details) "', port '" (:port details) "', dbname 'fdw_test');
                              CREATE TABLE public.local_table (data text);
                              CREATE FOREIGN TABLE foreign_table (data text)
                                SERVER foreign_server
                                OPTIONS (schema_name 'public', table_name 'local_table');

                              CREATE USER MAPPING FOR " (:user details) "
                                SERVER foreign_server
                                OPTIONS (user '" (:user details) "');
                              GRANT ALL ON public.local_table to PUBLIC;")])
        (mt/with-temp Database [database {:engine :postgres, :details (assoc details :dbname "fdw_test")}]
          (is (= {:tables (set (map default-table-result ["foreign_table" "local_table"]))}
                 (driver/describe-database :postgres database))))))))

(deftest recreated-views-test
  (mt/test-driver :postgres
    (testing (str "make sure that if a view is dropped and recreated that the original Table object is marked active "
                  "rather than a new one being created (#3331)")
      (let [details (mt/dbdef->connection-details :postgres :db {:database-name "dropped_views_test"})
            spec    (sql-jdbc.conn/connection-details->spec :postgres details)]
        ;; create the postgres DB
        (drop-if-exists-and-create-db! "dropped_views_test")
        ;; create the DB object
        (mt/with-temp Database [database {:engine :postgres, :details (assoc details :dbname "dropped_views_test")}]
          (let [sync! #(sync/sync-database! database)]
            ;; populate the DB and create a view
            (exec! spec ["CREATE table birds (name VARCHAR UNIQUE NOT NULL);"
                         "INSERT INTO birds (name) VALUES ('Rasta'), ('Lucky'), ('Kanye Nest');"
                         "CREATE VIEW angry_birds AS SELECT upper(name) AS name FROM birds;"
                         "GRANT ALL ON angry_birds to PUBLIC;"])
            ;; now sync the DB
            (sync!)
            ;; drop the view
            (exec! spec ["DROP VIEW angry_birds;"])
            ;; sync again
            (sync!)
            ;; recreate the view
            (exec! spec ["CREATE VIEW angry_birds AS SELECT upper(name) AS name FROM birds;"
                         "GRANT ALL ON angry_birds to PUBLIC;"])
            ;; sync one last time
            (sync!)
            ;; now take a look at the Tables in the database related to the view. THERE SHOULD BE ONLY ONE!
            (is (= [{:name "angry_birds", :active true}]
                   (map (partial into {})
                        (db/select [Table :name :active] :db_id (u/the-id database), :name "angry_birds"))))))))))

(deftest partitioned-table-test
  (mt/test-driver :postgres
    (testing (str "Make sure that partitioned tables (in addition to the individual partitions themselves) are
                   synced properly (#15049")
      (let [db-name "partitioned_table_test"
            details (mt/dbdef->connection-details :postgres :db {:database-name db-name})
            spec    (sql-jdbc.conn/connection-details->spec :postgres details)]
        ;; create the postgres DB
        (drop-if-exists-and-create-db! db-name)
        (let [major-v ((jdbc/with-db-metadata [metadata spec]
                         #(.getDatabaseMajorVersion ^DatabaseMetaData metadata)))]
          (if (>= major-v 10)
            ;; create the DB object
            (mt/with-temp Database [database {:engine :postgres, :details (assoc details :dbname db-name)}]
              (let [sync! #(sync/sync-database! database)]
                ;; create a main partitioned table and two partitions for it
                (exec! spec ["CREATE TABLE part_vals (val bigint NOT NULL) PARTITION BY RANGE (\"val\")";"
                             "CREATE TABLE part_vals_0 (val bigint NOT NULL);"
                             "ALTER TABLE ONLY part_vals ATTACH PARTITION part_vals_0 FOR VALUES FROM (0) TO (1000);"
                             "CREATE TABLE part_vals_1 (val bigint NOT NULL);"
                             "ALTER TABLE ONLY part_vals ATTACH PARTITION part_vals_1 FOR VALUES FROM (1000) TO (2000);"
                             "GRANT ALL ON part_vals to PUBLIC;"
                             "GRANT ALL ON part_vals_0 to PUBLIC;"
                             "GRANT ALL ON part_vals_1 to PUBLIC;"])
                ;; now sync the DB
                (sync!)
                ;; all three of these tables should appear in the metadata (including, importantly, the "main" table)
                (is (= {:tables (set (map default-table-result ["part_vals" "part_vals_0" "part_vals_1"]))}
                       (driver/describe-database :postgres database)))))
            (println
             (u/format-color
              'yellow
              "Skipping partitioned-table-test; Postgres major version %d doesn't support PARTITION BY" major-v))))))))

;;; ----------------------------------------- Tests for exotic column types ------------------------------------------

(deftest json-columns-test
  (mt/test-driver :postgres
    (testing "Verify that we identify JSON columns and mark metadata properly during sync"
      (mt/dataset (mt/dataset-definition "Postgres with a JSON Field"
                    ["venues"
                     [{:field-name "address", :base-type {:native "json"}, :effective-type :type/Structured}]
                     [[(hsql/raw "to_json('{\"street\": \"431 Natoma\", \"city\": \"San Francisco\", \"state\": \"CA\", \"zip\": 94103}'::text)")]]])
        (is (= :type/SerializedJSON
               (db/select-one-field :semantic_type Field, :id (mt/id :venues :address))))))))

(mt/defdataset with-uuid
  [["users"
    [{:field-name "user_id", :base-type :type/UUID}]
    [[#uuid "4f01dcfd-13f7-430c-8e6f-e505c0851027"]
     [#uuid "4652b2e7-d940-4d55-a971-7e484566663e"]
     [#uuid "da1d6ecc-e775-4008-b366-c38e7a2e8433"]
     [#uuid "7a5ce4a2-0958-46e7-9685-1a4eaa3bd08a"]
     [#uuid "84ed434e-80b4-41cf-9c88-e334427104ae"]]]])

(deftest uuid-columns-test
  (mt/test-driver :postgres
    (mt/dataset with-uuid
      (testing "Check that we can load a Postgres Database with a :type/UUID"
        (is (= [{:name "id", :base_type :type/Integer}
                {:name "user_id", :base_type :type/UUID}]
               (map #(select-keys % [:name :base_type])
                    (mt/cols (mt/run-mbql-query users))))))
      (testing "Check that we can filter by a UUID Field"
        (is (= [[2 #uuid "4652b2e7-d940-4d55-a971-7e484566663e"]]
               (mt/rows (mt/run-mbql-query users
                          {:filter [:= $user_id "4652b2e7-d940-4d55-a971-7e484566663e"]})))))
      (testing "check that a nil value for a UUID field doesn't barf (#2152)"
        (is (= []
               (mt/rows (mt/run-mbql-query users
                          {:filter [:= $user_id nil]})))))
      (testing "Check that we can filter by a UUID for SQL Field filters (#7955)"
        (is (= [[1 #uuid "4f01dcfd-13f7-430c-8e6f-e505c0851027"]]
               (mt/rows
                 (qp/process-query
                   (assoc (mt/native-query
                            {:query         "SELECT * FROM users WHERE {{user}}"
                             :template-tags {:user
                                             {:name         "user"
                                              :display_name "User ID"
                                              :type         "dimension"
                                              :dimension    [:field (mt/id :users :user_id) nil]}}})
                       :parameters
                       [{:type   "text"
                         :target ["dimension" ["template-tag" "user"]]
                         :value  "4f01dcfd-13f7-430c-8e6f-e505c0851027"}])))))
      (testing "Check that we can filter by multiple UUIDs for SQL Field filters"
        (is (= [[1 #uuid "4f01dcfd-13f7-430c-8e6f-e505c0851027"]
                [3 #uuid "da1d6ecc-e775-4008-b366-c38e7a2e8433"]]
               (mt/rows
                 (qp/process-query
                   (assoc (mt/native-query
                            {:query         "SELECT * FROM users WHERE {{user}}"
                             :template-tags {:user
                                             {:name         "user"
                                              :display_name "User ID"
                                              :type         "dimension"
                                              :dimension    [:field (mt/id :users :user_id) nil]}}})
                       :parameters
                       [{:type   "text"
                         :target ["dimension" ["template-tag" "user"]]
                         :value  ["4f01dcfd-13f7-430c-8e6f-e505c0851027"
                                  "da1d6ecc-e775-4008-b366-c38e7a2e8433"]}]))))))))))


(mt/defdataset ip-addresses
  [["addresses"
    [{:field-name "ip", :base-type {:native "inet"}, :effective-type :type/IPAddress}]
    [[(hsql/raw "'192.168.1.1'::inet")]
     [(hsql/raw "'10.4.4.15'::inet")]]]])

(deftest inet-columns-test
  (mt/test-driver :postgres
    (testing (str "Filtering by inet columns should add the appropriate SQL cast, e.g. `cast('192.168.1.1' AS inet)` "
                  "(otherwise this wouldn't work)")
      (mt/dataset ip-addresses
        (is (= [[1]]
               (mt/rows (mt/run-mbql-query addresses
                          {:aggregation [[:count]]
                           :filter      [:= $ip "192.168.1.1"]}))))))))

(defn- do-with-money-test-db [thunk]
  (drop-if-exists-and-create-db! "money_columns_test")
  (let [details (mt/dbdef->connection-details :postgres :db {:database-name "money_columns_test"})]
    (jdbc/with-db-connection [conn (sql-jdbc.conn/connection-details->spec :postgres details)]
      (doseq [sql+args [["CREATE table bird_prices (bird TEXT, price money);"]
                        ["INSERT INTO bird_prices (bird, price) VALUES (?, ?::numeric::money), (?, ?::numeric::money);"
                         "Lucky Pigeon"   6.0
                         "Katie Parakeet" 23.99]]]
        (jdbc/execute! conn sql+args)))
    (mt/with-temp Database [db {:engine :postgres, :details (assoc details :dbname "money_columns_test")}]
      (sync/sync-database! db)
      (mt/with-db db
        (thunk)))))

(deftest money-columns-test
  (mt/test-driver :postgres
    (testing "We should support the Postgres MONEY type"
      (testing "It should be possible to return money column results (#3754)"
        (with-open [conn (sql-jdbc.execute/connection-with-timezone :postgres (mt/db) nil)
                    stmt (sql-jdbc.execute/prepared-statement :postgres conn "SELECT 1000::money AS \"money\";" nil)
                    rs   (sql-jdbc.execute/execute-prepared-statement! :postgres stmt)]
          (let [row-thunk (sql-jdbc.execute/row-thunk :postgres rs (.getMetaData rs))]
            (is (= [1000.00M]
                   (row-thunk))))))

      (do-with-money-test-db
       (fn []
         (testing "We should be able to select avg() of a money column (#11498)"
           (is (= [[14.995M]]
                  (mt/rows
                    (mt/run-mbql-query bird_prices
                      {:aggregation [[:avg $price]]})))))
         (testing "Should be able to filter on a money column"
           (is (= [["Katie Parakeet" 23.99M]]
                  (mt/rows
                    (mt/run-mbql-query bird_prices
                      {:filter [:= $price 23.99]}))))
           (is (= []
                  (mt/rows
                    (mt/run-mbql-query bird_prices
                      {:filter [:!= $price $price]})))))
         (testing "Should be able to sort by price"
           (is (= [["Katie Parakeet" 23.99M]
                   ["Lucky Pigeon" 6.00M]]
                  (mt/rows
                    (mt/run-mbql-query bird_prices
                      {:order-by [[:desc $price]]}))))))))))

(defn- enums-test-db-details [] (mt/dbdef->connection-details :postgres :db {:database-name "enums_test"}))

(defn- create-enums-db!
  "Create a Postgres database called `enums_test` that has a couple of enum types and a couple columns of those types.
  One of those types has a space in the name, which is legal when quoted, to make sure we handle such wackiness
  properly."
  []
  (drop-if-exists-and-create-db! "enums_test")
  (jdbc/with-db-connection [conn (sql-jdbc.conn/connection-details->spec :postgres (enums-test-db-details))]
    (doseq [sql ["CREATE TYPE \"bird type\" AS ENUM ('toucan', 'pigeon', 'turkey');"
                 "CREATE TYPE bird_status AS ENUM ('good bird', 'angry bird', 'delicious bird');"
                 (str "CREATE TABLE birds ("
                      "  name varchar PRIMARY KEY NOT NULL,"
                      "  status bird_status NOT NULL,"
                      "  type \"bird type\" NOT NULL"
                      ");")
                 (str "INSERT INTO birds (\"name\", status, \"type\") VALUES"
                      "  ('Rasta', 'good bird', 'toucan'),"
                      "  ('Lucky', 'angry bird', 'pigeon'),"
                      "  ('Theodore', 'delicious bird', 'turkey');")]]
      (jdbc/execute! conn [sql]))))

(defn- do-with-enums-db {:style/indent 0} [f]
  (create-enums-db!)
  (mt/with-temp Database [database {:engine :postgres, :details (enums-test-db-details)}]
    (sync-metadata/sync-db-metadata! database)
    (f database)
    (#'sql-jdbc.conn/set-pool! (u/id database) nil nil)))

(deftest enums-test
  (mt/test-driver :postgres
    (testing "check that values for enum types get wrapped in appropriate CAST() fn calls in `->honeysql`"
      (is (= (hx/with-database-type-info (hsql/call :cast "toucan" (keyword "bird type")) "bird type")
             (sql.qp/->honeysql :postgres [:value "toucan" {:database_type "bird type", :base_type :type/PostgresEnum}]))))

    (do-with-enums-db
      (fn [db]
        (testing "check that we can actually fetch the enum types from a DB"
          (is (= #{(keyword "bird type") :bird_status}
                 (#'postgres/enum-types :postgres db))))

        (testing "check that describe-table properly describes the database & base types of the enum fields"
          (is (= {:name   "birds"
                  :fields #{{:name              "name"
                             :database-type     "varchar"
                             :base-type         :type/Text
                             :pk?               true
                             :database-position 0}
                            {:name              "status"
                             :database-type     "bird_status"
                             :base-type         :type/PostgresEnum
                             :database-position 1}
                            {:name              "type"
                             :database-type     "bird type"
                             :base-type         :type/PostgresEnum
                             :database-position 2}}}
                 (driver/describe-table :postgres db {:name "birds"}))))

        (testing "check that when syncing the DB the enum types get recorded appropriately"
          (let [table-id (db/select-one-id Table :db_id (u/the-id db), :name "birds")]
            (is (= #{{:name "name", :database_type "varchar", :base_type :type/Text}
                     {:name "type", :database_type "bird type", :base_type :type/PostgresEnum}
                     {:name "status", :database_type "bird_status", :base_type :type/PostgresEnum}}
                   (set (map (partial into {})
                             (db/select [Field :name :database_type :base_type] :table_id table-id)))))))

        (testing "End-to-end check: make sure everything works as expected when we run an actual query"
          (let [table-id           (db/select-one-id Table :db_id (u/the-id db), :name "birds")
                bird-type-field-id (db/select-one-id Field :table_id table-id, :name "type")]
            (is (= {:rows        [["Rasta" "good bird" "toucan"]]
                    :native_form {:query  (str "SELECT \"public\".\"birds\".\"name\" AS \"name\","
                                               " \"public\".\"birds\".\"status\" AS \"status\","
                                               " \"public\".\"birds\".\"type\" AS \"type\" "
                                               "FROM \"public\".\"birds\" "
                                               "WHERE \"public\".\"birds\".\"type\" = CAST('toucan' AS \"bird type\") "
                                               "LIMIT 10")
                                  :params nil}}
                   (-> (qp/process-query
                        {:database (u/the-id db)
                         :type     :query
                         :query    {:source-table table-id
                                    :filter       [:= [:field-id (u/the-id bird-type-field-id)] "toucan"]
                                    :limit        10}})
                       :data
                       (select-keys [:rows :native_form]))))))))))


;;; ------------------------------------------------ Timezone-related ------------------------------------------------

(deftest timezone-test
  (mt/test-driver :postgres
    (letfn [(get-timezone-with-report-timezone [report-timezone]
              (mt/with-temporary-setting-values [report-timezone report-timezone]
                (ffirst
                 (mt/rows
                   (qp/process-query {:database (mt/id)
                                      :type     :native
                                      :native   {:query "SELECT current_setting('TIMEZONE') AS timezone;"}})))))]
      (testing "check that if we set report-timezone to US/Pacific that the session timezone is in fact US/Pacific"
        (is  (= "US/Pacific"
                (get-timezone-with-report-timezone "US/Pacific"))))
      (testing "check that we can set it to something else: America/Chicago"
        (is (= "America/Chicago"
               (get-timezone-with-report-timezone "America/Chicago"))))
      (testing (str "ok, check that if we try to put in a fake timezone that the query still reëxecutes without a "
                    "custom timezone. This should give us the same result as if we didn't try to set a timezone at all")
        (mt/suppress-output
          (is (= (get-timezone-with-report-timezone nil)
                 (get-timezone-with-report-timezone "Crunk Burger"))))))))

(deftest fingerprint-time-fields-test
  (mt/test-driver :postgres
    (testing "Make sure we're able to fingerprint TIME fields (#5911)"
      (drop-if-exists-and-create-db! "time_field_test")
      (let [details (mt/dbdef->connection-details :postgres :db {:database-name "time_field_test"})]
        (jdbc/execute! (sql-jdbc.conn/connection-details->spec :postgres details)
                       [(str "CREATE TABLE toucan_sleep_schedule ("
                             "  start_time TIME WITHOUT TIME ZONE NOT NULL, "
                             "  end_time TIME WITHOUT TIME ZONE NOT NULL, "
                             "  reason VARCHAR(256) NOT NULL"
                             ");"
                             "INSERT INTO toucan_sleep_schedule (start_time, end_time, reason) "
                             "  VALUES ('22:00'::time, '9:00'::time, 'Beauty Sleep');")])
        (mt/with-temp Database [database {:engine :postgres, :details (assoc details :dbname "time_field_test")}]
          (sync/sync-database! database)
          (is (= {"start_time" {:global {:distinct-count 1
                                         :nil%           0.0}
                                :type   {:type/DateTime {:earliest "22:00:00"
                                                         :latest   "22:00:00"}}}
                  "end_time"   {:global {:distinct-count 1
                                         :nil%           0.0}
                                :type   {:type/DateTime {:earliest "09:00:00"
                                                         :latest   "09:00:00"}}}
                  "reason"     {:global {:distinct-count 1
                                         :nil%           0.0}
                                :type   {:type/Text {:percent-json   0.0
                                                     :percent-url    0.0
                                                     :percent-email  0.0
                                                     :percent-state  0.0
                                                     :average-length 12.0}}}}
                 (db/select-field->field :name :fingerprint Field
                   :table_id (db/select-one-id Table :db_id (u/the-id database))))))))))


;;; ----------------------------------------------------- Other ------------------------------------------------------

(deftest exception-test
  (mt/test-driver :postgres
    (testing (str "If the DB throws an exception, is it properly returned by the query processor? Is it status "
                  ":failed? (#9942)")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Error executing query"
           (qp/process-query
            {:database (mt/id)
             :type     :native
             :native   {:query "SELECT adsasdasd;"}})))
      (try
        (qp/process-query
         {:database (mt/id)
          :type     :native
          :native   {:query "SELECT adsasdasd;"}})
        (catch Throwable e
          (is (= "ERROR: column \"adsasdasd\" does not exist\n  Position: 20"
                 (.. e getCause getMessage))))))))

(deftest pgobject-test
  (mt/test-driver :postgres
    (testing "Make sure PGobjects are decoded correctly"
      (let [results (qp/process-query (mt/native-query {:query "SELECT pg_sleep(0.1) AS sleep;"}))]
        (testing "rows"
          (is (= [[""]]
                 (mt/rows results))))
        (testing "cols"
          (is (= [{:display_name "sleep"
                   :base_type    :type/Text
                   :source       :native
                   :field_ref    [:field "sleep" {:base-type :type/Text}]
                   :name         "sleep"}]
                 (mt/cols results))))))))

(deftest id-field-parameter-test
  (mt/test-driver :postgres
    (testing "We should be able to filter a PK column with a String value -- should get parsed automatically (#13263)"
      (is (= [[2 "Stout Burgers & Beers" 11 34.0996 -118.329 2]]
             (mt/rows
               (mt/run-mbql-query venues
                 {:filter [:= $id "2"]})))))))

(deftest dont-sync-tables-with-no-select-permissions-test
  (testing "Make sure we only sync databases for which the current user has SELECT permissions"
    (mt/test-driver :postgres
      (drop-if-exists-and-create-db! "no-select-test")
      (let [details (mt/dbdef->connection-details :postgres :db {:database-name "no-select-test"})
            spec    (sql-jdbc.conn/connection-details->spec :postgres details)]
        (doseq [statement ["CREATE TABLE PUBLIC.table_with_perms (x INTEGER NOT NULL);"
                           "CREATE TABLE PUBLIC.table_with_no_perms (y INTEGER NOT NULL);"
                           "DROP USER IF EXISTS no_select_test_user;"
                           "CREATE USER no_select_test_user WITH PASSWORD '123456';"
                           "GRANT SELECT ON TABLE \"no-select-test\".PUBLIC.table_with_perms TO no_select_test_user;"]]
          (jdbc/execute! spec [statement])))
      (let [test-user-details (assoc (mt/dbdef->connection-details :postgres :db {:database-name "no-select-test"})
                                     :user "no_select_test_user"
                                     :password "123456")]
        (mt/with-temp Database [database {:engine :postgres, :details test-user-details}]
          (sync/sync-database! database)
          (is (= #{"table_with_perms"}
                 (db/select-field :name Table :db_id (:id database)))))))))

(deftest json-operator-?-works
  (testing "Make sure the Postgres ? operators (for JSON types) work in native queries"
    (mt/test-driver :postgres
      (drop-if-exists-and-create-db! "json-test")
      (let [details (mt/dbdef->connection-details :postgres :db {:database-name "json-test"})
            spec    (sql-jdbc.conn/connection-details->spec :postgres details)]
        (doseq [statement ["DROP TABLE IF EXISTS PUBLIC.json_table;"
                           "CREATE TABLE PUBLIC.json_table (json_val JSON NOT NULL);"
                           "INSERT INTO PUBLIC.json_table (json_val) VALUES ('{\"a\": 1, \"b\": 2}');"]]
          (jdbc/execute! spec [statement])))
      (let [json-db-details (mt/dbdef->connection-details :postgres :db {:database-name "json-test"})
            query           (str "SELECT json_val::jsonb ? 'a',"
                                 "json_val::jsonb ?| array['c', 'd'],"
                                 "json_val::jsonb ?& array['a', 'b']"
                                 "FROM \"json_table\";")]
        (mt/with-temp Database [database {:engine :postgres, :details json-db-details}]
          (mt/with-db database (sync/sync-database! database)
                               (is (= [[true false true]]
                                      (-> {:query query}
                                          (mt/native-query)
                                          (qp/process-query)
                                          (mt/rows))))))))))

(defn- pretty-sql [s]
  (-> s
      (str/replace #"\"" "")
      (str/replace #"public\." "")))

(deftest do-not-cast-to-date-if-column-is-already-a-date-test
  (testing "Don't wrap Field in date() if it's already a DATE (#11502)"
    (mt/test-driver :postgres
      (mt/dataset attempted-murders
        (let [query (mt/mbql-query attempts
                      {:aggregation [[:count]]
                       :breakout    [!day.date]})]
          (is (= (str "SELECT attempts.date AS date, count(*) AS count "
                      "FROM attempts "
                      "GROUP BY attempts.date "
                      "ORDER BY attempts.date ASC")
                 (some-> (qp/query->native query) :query pretty-sql))))))))
