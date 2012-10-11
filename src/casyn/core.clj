(ns casyn.core
  (:require
   [useful.ns :refer [alias-ns]]))

(doseq [module '(client api schema ddl codecs codecs.composite cluster.core)]
  (alias-ns (symbol (str "casyn." module))))