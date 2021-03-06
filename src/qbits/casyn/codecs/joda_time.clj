(ns qbits.casyn.codecs.joda-time
  "Encoding/decoding of org.joda.time.DateTime instances"
  (:require
   [qbits.casyn.codecs :refer [ByteBufferEncodable
                         bytes->clojure
                         clojure->byte-buffer]]
   [clj-time.coerce :as ct-c]))

(extend-protocol ByteBufferEncodable
  org.joda.time.DateTime
  (clojure->byte-buffer [dt]
    (clojure->byte-buffer (ct-c/to-long dt))))

(defmethod bytes->clojure :date-time [_ b]
  (ct-c/from-long (bytes->clojure :long b)))
