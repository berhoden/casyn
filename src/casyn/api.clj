(ns casyn.api
  "Thrift API and related utils. Most of the fns here translate almost directly
to their thrift counterpart and most of the time return Thrift instances.
See: http://wiki.apache.org/cassandra/API \nand
http://javasourcecode.org/html/open-source/cassandra/cassandra-0.8.1/org/apache/cassandra/thrift/Cassandra.AsyncClient.html"
  (:require
   [lamina.core :as lc]
   [casyn.utils :as utils]
   [casyn.codecs :as codecs]
   [casyn.schema :as schema])

  (:import
   [org.apache.cassandra.thrift
    Column SuperColumn CounterSuperColumn CounterColumn ColumnPath
    ColumnOrSuperColumn ColumnParent Mutation Deletion SlicePredicate
    SliceRange KeyRange AuthenticationRequest Cassandra$AsyncClient
    IndexClause IndexExpression IndexOperator ConsistencyLevel Compression]
   [org.apache.thrift.async AsyncMethodCallback TAsyncClient]
   [java.nio ByteBuffer]))

(def ^:dynamic *consistency-default* :one)

(defn consistency-level [c]
  ((or c *consistency-default*)
   {:all ConsistencyLevel/ALL
    :any ConsistencyLevel/ANY
    :each-quorum ConsistencyLevel/EACH_QUORUM
    :local-quorum ConsistencyLevel/LOCAL_QUORUM
    :one ConsistencyLevel/ONE
    :quorum ConsistencyLevel/QUORUM
    :three ConsistencyLevel/THREE
    :two ConsistencyLevel/TWO}))

(defmacro with-consistency [consistency & body]
  `(binding [casyn.api/*consistency-default* ~consistency]
     ~@body))

;; Async helper
(defmacro wrap-result-channel
  "Wraps a form in a Lamina result-channel, and make the last arg of the form an
   AsyncMethodCallback with error/complete callback bound to a result-channel"
  [form]
  (let [thrift-cmd-call (gensym)
        result-hint (format "org.apache.cassandra.thrift.Cassandra$AsyncClient$%s_call"
                            (-> form first str (subs 1)))]
    `(let [result-ch# (lc/result-channel)]
       (~@form ^"org.apache.thrift.async.AsyncMethodCallback"
               (reify AsyncMethodCallback
                 (onComplete [_ ~thrift-cmd-call]
                   (lc/success result-ch#
                               (.getResult ~(with-meta thrift-cmd-call {:tag result-hint}))))
                 (onError [_ error#]
                   (lc/error result-ch# error#))))
       (lc/run-pipeline
        result-ch#
        {:error-handler (fn [_#])}
        codecs/thrift->clojure))))

;; Objects

(defn column
  "Returns a Thrift Column instance"
  [name value & {:keys [ttl timestamp]}]
  (let [col (Column. ^ByteBuffer (codecs/clojure->byte-buffer name))]
    (.setValue col ^ByteBuffer (codecs/clojure->byte-buffer value))
    (.setTimestamp col (or timestamp (utils/ts)))
    (when ttl (.setTtl col (int ttl)))
    col))

(defn counter-column
  "Returns a Thrift CounterColumn instance"
  ^CounterColumn
  [name value]
  (CounterColumn. (codecs/clojure->byte-buffer name)
                  (long value)))

(defn super-column
  "Returns a Thrift SuperColumn instance"
  ^SuperColumn
  [name columns]
  (SuperColumn. (codecs/clojure->byte-buffer name)
                (map #(apply column %) columns)))

(defn counter-super-column
  "Returns a Thrift CounterSuperColumn instance"
  [name columns]
  (CounterSuperColumn. (codecs/clojure->byte-buffer name)
                       (map #(apply counter-column %) columns)))

(defn column-parent
  "Returns a Thrift ColumnParent instance, works for common columns or
  super columns depending on arity used"
  ^ColumnParent
  ([^String cf sc]
     (doto (ColumnParent. cf)
       (.setSuper_column ^ByteBuffer (codecs/clojure->byte-buffer sc))))
  ([^String cf] (ColumnParent. cf)))

(defn column-path
  "Returns a Thrift ColumnPath instance, works for common columns or
  super columns depending on arity used"
  ^ColumnPath
  ([^String cf sc c]
     (doto ^ColumnPath (column-path cf c)
           (.setSuper_column ^ByteBuffer (codecs/clojure->byte-buffer sc))))
  ([^String cf c]
     (doto ^ColumnPath (column-path cf)
           (.setColumn ^ByteBuffer (codecs/clojure->byte-buffer c))))
  ([^String cf]
     (ColumnPath. cf)))

(defn- path
  [cf super c]
  (if super
    (column-path cf super c)
    (column-path cf c)))

(defn- parent
  [cf super]
  (if super
    (column-parent cf super)
    (column-parent cf)))

(defn slice-for-names
  "Returns a Thrift SlicePredicate instance for column names"
  [column-names]
  (doto (SlicePredicate.)
    (.setColumn_names (map codecs/clojure->byte-buffer column-names))))

(defn slice-for-range
  "Returns a Thrift SlicePredicate instance for a range of columns"
  [{:keys [start finish reversed count]}]
  (doto (SlicePredicate.)
    (.setSlice_range (SliceRange. (codecs/clojure->byte-buffer start)
                                  (codecs/clojure->byte-buffer finish)
                                  (boolean reversed)
                                  (int (or count 100))))))

(defn slice-predicate
  "Returns a SlicePredicate instance, takes a map, it can be either for named keys
using the :columns key, or a range defined from :start :finish :reversed :count
Ex: (slice-predicate :columns [\"foo\" \"bar\"])"
  ^SlicePredicate
  [opts]
  (if-let [cols (:columns opts)]
    (slice-for-names cols)
    (slice-for-range opts)))

(defn key-range
  "Returns a Thrift KeyRange instance for a range of keys"
  [{:keys [start-token start-key end-token end-key count-key]}]
  (let [kr (KeyRange.)]
    (when start-token (.setStart_token kr ^String start-token))
    (when end-token (.setEnd_token kr ^String end-token))
    (when start-key (.setStart_key kr ^ByteBuffer (codecs/clojure->byte-buffer start-key)))
    (when end-key (.setEnd_key kr ^ByteBuffer (codecs/clojure->byte-buffer end-key)))
    (when count-key (.setCount kr (int count-key)))
    kr))

(defmacro doto-mutation
  [& body]
  `(doto (Mutation.)
     (.setColumn_or_supercolumn
      (doto (ColumnOrSuperColumn.)
        ~@body))))

(defn column-mutation
  ""
  [& c]
  (doto-mutation
    (.setColumn (apply column c))))

(defn counter-column-mutation
  ""
  [n value]
  (doto-mutation
    (.setCounter_column (counter-column n value))))

(defn super-column-mutation
  ""
  [& sc]
  (doto-mutation
    (.setSuper_column (apply super-column sc))))

(defn super-counter-column-mutation
  ""
  [& sc]
  (doto-mutation
    (.setCounter_super_column ^CounterSuperColumn (apply counter-super-column sc))))

(defn deletion
  ^SlicePredicate
  [{:keys [super]
    :as opts}]
  (let [d (Deletion.)]
    (.setTimestamp d (utils/ts))
    (.setPredicate d (slice-predicate opts))
    (when super
      (.setSuper_column d ^ByteBuffer (codecs/clojure->byte-buffer super)))
    d))

(defn delete-mutation
  "Accepts optional slice-predicate arguments :columns, :start, :finish, :count,
:reversed, if you specify :columns the other slice args will be ignored (as
defined by thrift)
The :super key and specify a supercolumn name"
  [& args] ;; expects a pred and opt sc
  (doto (Mutation.)
    (.setDeletion (deletion args))))


;; Secondary indexes

(def index-operators
  {:eq? IndexOperator/EQ
   :lt? IndexOperator/LT
   :gt? IndexOperator/GT
   :lte? IndexOperator/LTE
   :gte? IndexOperator/GTE})

(defn index-expressions
  [expressions]
  (map (fn [[op k v]]
         (IndexExpression. (codecs/clojure->byte-buffer k)
                           (index-operators op)
                           (codecs/clojure->byte-buffer v)))
       expressions))

(defn index-clause
  "Defines one or more IndexExpressions for get_indexed_slices. An
IndexExpression containing an EQ IndexOperator must be present"
  [expressions & {:keys [start-key count]
                  :or {count 100}}]
  (IndexClause. (index-expressions expressions)
                (codecs/clojure->byte-buffer start-key)
                (int count)))

;; API

(defn login
  ""
  [^Cassandra$AsyncClient client ^AuthenticationRequest auth-req]
  (wrap-result-channel (.login client auth-req)))

(defn set-keyspace
  ""
  [^Cassandra$AsyncClient client ^String ks]
  (wrap-result-channel (.set_keyspace client ks)))

(defn get-column
  ""
  [^Cassandra$AsyncClient client cf row-key c
   & {:keys [super consistency]}]
  (wrap-result-channel
   (.get client
         ^ByteBuffer (codecs/clojure->byte-buffer row-key)
         (path cf super c)
         (consistency-level consistency))))

(defn get-slice
  "Returns a slice of columns. Accepts optional slice-predicate arguments :columns, :start, :finish, :count,
:reversed, if you specify :columns the other slice args will be ignored (as
defined by the cassandra api)"

  [^Cassandra$AsyncClient client cf row-key
   & {:keys [super consistency]
      :as opts}]
  (wrap-result-channel
   (.get_slice client
               (codecs/clojure->byte-buffer row-key)
               (parent cf super)
               (slice-predicate opts)
               (consistency-level consistency))))

(defn mget-slice
  "Returns a collection of slices of columns.
   Accepts optional slice-predicate
   arguments :columns, :start, :finish, :count, :reversed, if you
   specify :columns the other slice args will be ignored (as defined by the cassandra api)"
  [^Cassandra$AsyncClient client cf row-keys
   & {:keys [super consistency]
      :as opts}]
  (wrap-result-channel
   (.multiget_slice client
                    (map codecs/clojure->byte-buffer row-keys)
                    (parent cf super)
                    (slice-predicate opts)
                    (consistency-level consistency))))

(defn get-count
  "Accepts optional slice-predicate arguments :columns, :start, :finish, :count,
:reversed, if you specify :columns the other slice args will be ignored (as
defined by the cassandra api)"
  [^Cassandra$AsyncClient client cf row-key
   & {:keys [super consistency]
      :as opts}]
  (wrap-result-channel
   (.get_count client
               (codecs/clojure->byte-buffer row-key)
               (parent cf super)
               (slice-predicate opts)
               (consistency-level consistency))))

(defn mget-count
  "Accepts optional slice-predicate arguments :columns, :start, :finish, :count,
:reversed, if you specify :columns the other slice args will be ignored (as
defined by the cassandra api)"
  [^Cassandra$AsyncClient client cf row-keys
   & {:keys [super consistency]
      :as opts}]
  (wrap-result-channel
   (.multiget_count client
                    (map codecs/clojure->byte-buffer row-keys)
                    (parent cf super)
                    (slice-predicate opts)
                    (consistency-level consistency))))

(defn insert-column
  ""
  [^Cassandra$AsyncClient client cf row-key name value
   & {:keys [super counter consistency ttl timestamp]}]
  (wrap-result-channel
   (.insert client
            (codecs/clojure->byte-buffer row-key)
            (parent cf super)
            (cond
                counter (counter-column name value)
                super (super-column super value) ;; values is a collection of columns
                :else (column name value :ttl ttl :timestamp timestamp))
            (consistency-level consistency))))

(defn increment
  ""
  [^Cassandra$AsyncClient client cf row-key column-name value
   & {:keys [super consistency]}]
  (wrap-result-channel
   (.add client
         (codecs/clojure->byte-buffer row-key)
         (parent cf super)
         (counter-column column-name value)
         (consistency-level consistency))))

(defn remove-column
  ""
  [^Cassandra$AsyncClient client cf row-key c
   & {:keys [super timestamp consistency]
      :or {timestamp (utils/ts)}}]
  (wrap-result-channel
   (.remove client
            (codecs/clojure->byte-buffer row-key)
            (path cf super c)
            timestamp
            (consistency-level consistency))))

(defn remove-counter
  ""
  [^Cassandra$AsyncClient client cf row-key c
   & {:keys [super consistency]}]
  (wrap-result-channel
   (.remove_counter client
                    (codecs/clojure->byte-buffer row-key)
                    (path cf super c)
                    (consistency-level consistency))))

(defn batch-mutate
  ""
  [^Cassandra$AsyncClient client mutations
   & {:keys [consistency]}]
  (wrap-result-channel
   (.batch_mutate client
                  (reduce-kv (fn [m k v]
                               (assoc m (codecs/clojure->byte-buffer k) v))
                             {}
                             mutations)
                  (consistency-level consistency))))

(defn get-range-slice
  "Accepts optional slice-predicate arguments :columns, :start, :finish, :count,
:reversed, if you specify :columns the other slice args will be ignored (as
defined by the cassandra api). Accepts optional key-range arguments :start-token
:start-key :end-token :end-key :count-key"
  [^Cassandra$AsyncClient client cf
   & {:keys [super consistency]
      :as opts}]
  (wrap-result-channel
   (.get_range_slices client
                      (parent cf super)
                      (slice-predicate opts)
                      (key-range opts)
                      (consistency-level consistency))))

(defn get-indexed-slice
  "Accepts optional slice-predicate arguments :columns, :start, :finish, :count,
:reversed, if you specify :columns the other slice args will be ignored (as
defined by the cassandra api)"
  [^Cassandra$AsyncClient client cf index-clause-args
   & {:keys [super consistency]
      :as opts}]
  (wrap-result-channel
   (.get_indexed_slices client
                        (parent cf super)
                        (index-clause index-clause-args)
                        (slice-predicate opts)
                        (consistency-level consistency))))

(defn get-paged-slice
  "Accepts optional key-range arguments :start-token :start-key :end-token
:end-key :count-key, and takes an additional :start-column name"
  [^Cassandra$AsyncClient client cf
   & {:keys [super consistency]
      :as opts}]
  (wrap-result-channel
   (.get_paged_slice client
                     cf
                     (key-range opts)
                     (-> opts :start-column codecs/clojure->byte-buffer)
                     (consistency-level consistency))))

(defn truncate
  ""
  [^Cassandra$AsyncClient client cf]
  (wrap-result-channel (.truncate client cf)))

(defn describe-cluster-name
  ""
  [^Cassandra$AsyncClient client]
  (wrap-result-channel (.describe_cluster_name client)))

(defn describe-keyspace
  ""
  [^Cassandra$AsyncClient client ks]
  (wrap-result-channel (.describe_keyspace client ks)))

(defn describe-keyspaces
  ""
  [^Cassandra$AsyncClient client]
  (wrap-result-channel (.describe_keyspaces client)))

(defn describe-partitioner
  ""
  [^Cassandra$AsyncClient client]
  (wrap-result-channel (.describe_partitioner client)))

(defn describe-ring
  ""
  [^Cassandra$AsyncClient client ks]
  (wrap-result-channel (.describe_ring client ks)))

(defn describe-schema-versions
  ""
  [^Cassandra$AsyncClient client]
  (wrap-result-channel (.describe_schema_versions client)))

(defn describe-snitch
  ""
  [^Cassandra$AsyncClient client]
  (wrap-result-channel (.describe_snitch client)))

(defn describe-splits
  ""
  [^Cassandra$AsyncClient client cf start-token end-token keys-per-split]
  (wrap-result-channel (.describe_splits client
                                         cf
                                         start-token end-token
                                         keys-per-split)))

(defn describe-token-map
  ""
  [^Cassandra$AsyncClient client]
  (wrap-result-channel (.describe_token_map client)))

(defn describe-version
  ""
  [^Cassandra$AsyncClient client]
  (wrap-result-channel (.describe_version client)))

(defn prepare-cql-query
  ""
  [^Cassandra$AsyncClient client query]
  (wrap-result-channel
   (.prepare_cql_query client
                       (codecs/clojure->byte-buffer query)
                       Compression/NONE)))

(defn execute-cql-query
  ""
  [^Cassandra$AsyncClient client query]
  (wrap-result-channel
   (.execute_cql_query client
                       (codecs/clojure->byte-buffer query)
                       Compression/NONE)))

(defn execute-prepared-cql-query
  ""
  ([^Cassandra$AsyncClient client item-id values]
     (wrap-result-channel
      (.execute_prepared_cql_query client
                                   (int item-id)
                                   (map codecs/clojure->byte-buffer values))))
  ([^Cassandra$AsyncClient client item-id]
     (execute-prepared-cql-query client item-id [])))

;; Sugar

(defn put
  "Accepts cols as vectors or maps to be applied to cols
  constructors (use maps for simple key vals, use vectors if you need
  to set ttl or timestamp"
  [^Cassandra$AsyncClient client cf row-key columns
   & {:keys [consistency counters super-columns super-counters]}]
  (batch-mutate
   client
   {row-key
    {cf (map #(apply
               (cond
                 super-columns super-column-mutation
                 super-counters super-counter-column-mutation
                 counters counter-column-mutation
                 :else column-mutation)
               %)
             columns)}}
   :consistency consistency))

;; aliases
(def ^{:doc "Alias to mget-slice"} get-rows mget-slice)
(def ^{:doc "Alias to get-slice"} get-row get-slice)