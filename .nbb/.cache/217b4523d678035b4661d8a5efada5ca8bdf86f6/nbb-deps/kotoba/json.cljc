(ns kotoba.json
  "Compatibility shim for the kotoba DSL pretty JSON emitter."
  (:require [json.core :as json]))

(def json json/json)
(def encode json/encode)
(def decode json/decode)
