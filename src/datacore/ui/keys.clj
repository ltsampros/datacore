(ns datacore.ui.keys
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.math.combinatorics :as combo]
            [datacore]
            [datacore.ui.timer :as timer]
            [datacore.ui.message :as message]
            [datacore.cells :as c])
  (:import [javafx.scene.input KeyEvent KeyCode]
           [javafx.event Event]))

(def modifiers [:alt :ctrl :meta :shift :shortcut])

(def initial-state {:chain []
                    :last-consumed nil
                    :timer nil})

(c/defcell key-input initial-state)
;;(c/add-watch! key-input :debug (fn [_ _ new] (prn 'KEYS new)))

(defn press-str [press]
  (if-not (set? press)
    (name press)
    (cond-> ""
      (press :shift) (str "shift-")
      (press :ctrl) (str "ctrl-")
      (press :alt) (str "alt-")
      (press :meta) (str "meta-")
      (press :shortcut) (str "shortcut-")
      :always (str (str/join "-" (map name (apply disj press modifiers)))))))

(defn chain-str [chain]
  (str/join " " (map press-str chain)))

(c/deformula keys-chain :chain key-input)
(c/add-watch! keys-chain :key-chain-message
              (fn [_ _ chain]
                (when-not (empty? chain)
                  ;;(prn 'CHAIN--- (chain-str chain))
                  (message/message (chain-str chain)))))

(defn keycode->keyword [k]
  (-> k .getName str/lower-case (str/replace " " "-") (str/replace "/" "-") keyword))

(defn code-map []
  (into
   {}
   (for [keycode (map keycode->keyword (into [] (KeyCode/values)))
         mod (map set (combo/subsets modifiers))]
     (let [combo (conj mod keycode)
           combo (if (= 1 (count combo)) (first combo) combo)]
       [combo ::propagate]))))

(defn prefix? [x] (map? x))
(defn action? [x] (keyword? x))

(defn event->map [fx-event]
  {:type      (condp = (.getEventType fx-event)
                KeyEvent/KEY_PRESSED :key-pressed
                KeyEvent/KEY_TYPED :key-typed
                KeyEvent/KEY_RELEASED :key-released)
   :code      (if (= KeyEvent/KEY_TYPED (.getEventType fx-event))
                (-> fx-event .getCharacter keyword)
                (-> fx-event .getCode .getName str/lower-case keyword))
   :alt       (.isAltDown fx-event)
   :ctrl      (.isControlDown fx-event)
   :meta      (.isMetaDown fx-event)
   :shift     (.isShiftDown fx-event)
   :shortcut  (.isShortcutDown fx-event)
   :modifier? (-> fx-event .getCode .isModifierKey)})

(defn event->press [{:keys [code alt ctrl meta shift shortcut]}]
  (let [press (->> [code
                    (when alt :alt)
                    (when ctrl :ctrl)
                    (when meta :meta)
                    (when shift :shift)
                    (when shortcut :shortcut)]
                   (remove nil?)
                   set)]
    (if (= 1 (count press))
      (first press)
      press)))

(defn- clear-chain! []
  (timer/cancel (:timer (c/value key-input)))
  (c/reset! key-input initial-state)
  nil)

;;(def debug prn)
(defn debug [& _])

(defn- wait-for-next! []
  (timer/cancel (:timer (c/value key-input)))
  (c/swap! key-input assoc :timer (timer/delayed 3500 #(do (debug 'KEY-STATE-RESET) (clear-chain!)))))


(defn- consume-event [^Event e press event]
  (debug 'CONSUMED press event)
  (c/swap! key-input assoc :last-consumed event)
  (.consume e)
  nil)

(defn- also-consume-this? [event]
  (when-let [last-consumed (-> key-input c/value :last-consumed)]
    (debug 'last-consumed last-consumed)
    (= (dissoc event :type)
       (dissoc last-consumed :type))))

(defn handle-key-match [match]
  (if-let [{:keys [var]} (get datacore/interactive-functions match)]
    (let [fun (deref var)]
      (fun))
    (message/error (str "No interactive function with alias " match " found!"))))

(defn key-handler [keymap]
  (fn [fx-event]
    (try
      (let [{:keys [type] :as event} (event->map fx-event)
            press                    (event->press event)
            new-chain                (conj (-> key-input c/value :chain) press)
            match                    (get-in keymap new-chain)]
        (cond
          ;;also consume :key-typed and :key-released equivalents of
          ;;events that have been consumed:
          (also-consume-this? event)
          (do
            (debug 'ALSO-CONSUMING)
            (consume-event fx-event press event)
            (when (= type :key-released)
              (c/swap! key-input assoc :last-consumed nil)) ;;...but stop consuming at :key-released
            nil)

          (and (= type :key-pressed) (not match))
          (do
            (println (str "ERROR - Key sequence " new-chain " not mapped to anything"))
            (message/error (str "Key sequence " (chain-str new-chain) " not mapped to anything"))
            (clear-chain!)
            (consume-event fx-event press event))

          (= match ::propagate)
          (do
            (debug 'PROPAGATED press event)
            (clear-chain!))

          :else
          (cond
            (prefix? match)
            (do
              (c/swap! key-input update :chain conj press)
              ;;(prn 'PREFIX (-> key-input c/value :chain))
              (wait-for-next!)
              (consume-event fx-event press event))
            (action? match)
            (do
              (debug 'COMBO new-chain match)
              (message/message (subs (str match) 1))
              (clear-chain!)
              (consume-event fx-event press event)
              (handle-key-match match)
              match))))
      (catch Exception e
        (.printStackTrace e)
        (throw e)))))
