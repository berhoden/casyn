(ns casyn.types
  "Internal data types for cassandra results")

(defrecord Column [name value ttl timestamp])
(defrecord CounterColumn [name value])
(defrecord SuperColumn [name columns])
(defrecord CounterSuperColumn [name columns])

(defrecord KeySlice [row columns])
(defrecord CqlRow [row columns])
(defrecord CqlResult [num type rows])
(defrecord CqlPreparedResult [item-id count variable-names variable-types])