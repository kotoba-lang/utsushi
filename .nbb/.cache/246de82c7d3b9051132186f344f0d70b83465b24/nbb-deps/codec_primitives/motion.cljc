(ns codec-primitives.motion
  (:require [malli.core :as m]))

(def MotionVector
  [:map
   [:poc :int]                     ;; picture order count
   [:ref-idx :int]
   [:mv [:tuple :int :int]]        ;; [dx dy] クォーターペル単位
   [:ref-frame :keyword]])         ;; フレームを指すID(実体はVault blob CID等、codec側が解決)

(defn valid-mv? [x] (m/validate MotionVector x))
