(ns datacore.source.csv
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [datacore.util :as util]
            [datacore.cells :as c]
            [datacore.state :as state]
            [hawk.core :as hawk]))

(defn load-csv [{:keys [filename separator quote] :as options}]
  (with-open [in-file (io/reader filename)]
    (doall
     (apply csv/read-csv in-file (mapcat identity options)))))

(defn- data-map [options]
  (let [rows    (load-csv options)
        columns (mapv util/string->data-key (first rows))]
    {:original-column-labels (zipmap columns (first rows))
     :columns                columns
     :data                   (map (partial zipmap columns) (rest rows))}))

(defn file [{:keys [filename] :as options}]
  (when-not (fs/exists? filename)
    (throw (ex-info "File does not exist" {:filename filename})))
  (let [csv-cell (c/cell
                  :csv-file
                  (merge
                   (data-map options)
                   {:label         (fs/base-name filename)
                    :filename      filename
                    :last-modified (util/time-in-millis)}))]
    (hawk/watch! [{:paths   [filename]
                   :handler
                   (fn [_ _]
                     (c/swap!
                      csv-cell
                      #(merge
                        %
                        {:last-modified (util/time-in-millis)}
                        (data-map options))))}])
    csv-cell))

(defn default-view [csv-cell]
  (c/formula
   (fn [{:keys [label columns column-labels original-column-labels] :as contents}]
     (merge
      contents
      {:type :datacore.view/table}
      ;;TODO also uniquify label
      (when-not (not column-labels)
        {:column-labels (zipmap columns
                                (map (fn [c]
                                       (or (get original-column-labels c)
                                           (util/data-key->label c))) columns))})))
   csv-cell
   {:label :table-view}))
