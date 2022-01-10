;; UI for in-browser ReCom puzzle.
;;
;; some helpers inspired by (or borrowed from) the learn-cljs tutorials
;; see https://github.com/kendru/learn-cljs/blob/main/code/lesson-20/contacts/
;;     src/learn_cljs/contacts.cljs
(ns ^:figwheel-hooks mggg.recom-puzzle
  (:require-macros [hiccups.core :as hiccups])
  (:require [clojure.set :as set]
            [hiccups.runtime]
            [goog.dom :as gdom]
            [goog.events.KeyCodes :as keycodes]
            [goog.events :as gevents]
            [goog.string :as str])
  (:import [goog.events EventType KeyHandler]))


;; Container setup.
(def app-container (gdom/getElement "app"))
(defn set-app-html! [html-str]
  (set! (.-innerHTML app-container) html-str))
(declare refresh!)

;; Helpers.
(defn toggle [s val] ((if (s val) disj conj) s val))
(defn get-cell [grid cell] (get (get grid (first cell)) (second cell)))
(def do-map-indexed (comp doall map-indexed))
(defn do-grid [cell-fn grid]
  (do-map-indexed
    (fn [row-idx row]
      (do-map-indexed
        (fn [col-idx cell] (cell-fn row-idx col-idx cell))
        row))
    grid))
(defn map-grid [cell-fn grid]
  (map-indexed
    (fn [row-idx row]
      (map-indexed
        (fn [col-idx cell] (cell-fn row-idx col-idx cell))
        row))
    grid))
(defn replace-grid [smap grid] (vec (map (partial replace smap) grid)))
(defn map-grid-flat [cell-fn grid] (reduce concat (map-grid cell-fn grid)))
(defn cell-neighbors [row col]
  (partition 2 (list (- row 1) col (+ row 1) col row (- col 1) row (+ col 1))))
(defn unordered-pair-in? [coll [a b]]
  (some #(or (= (list a b) %) (= (list b a) %)) coll))
(defn pair-has-element? [el [a b]] (or (= a el) (= b el)))
(defn pair-other-element [el [a b]] (if (= a el) b a))


;; Grid logic.
(defn dists [grid] (distinct (flatten grid)))
(defn dist-sizes [grid] (frequencies (flatten grid)))
(defn dists-balanced? [grid]
  (let [sizes (vals (dist-sizes grid))] (= (min sizes) (max sizes))))
(defn dist-cells [grid dist]
  (filter identity
    (map-grid-flat (fn [row col v] (when (= v dist) (list row col))) grid)))
(defn dist-size [grid dist] (count (dist-cells grid dist)))
(defn any-dist-cell [grid dist] (some identity (dist-cells grid dist)))

(defn dist-component [grid dist first-cell]
  (loop [visited #{}
         stack (list first-cell)]
    (if (seq stack)
      (let [curr (first stack)
            [row col] curr
            neighbors (cell-neighbors row col)
            dist-neighbors
              (filter #(= (get-cell grid %) dist) neighbors)
            next-cells (set/difference (set dist-neighbors) visited)]
        (recur (conj visited curr) (concat next-cells (drop 1 stack))))
      visited)))

(defn dist-contiguous? [grid dist]
  (if-let [first-cell (any-dist-cell grid dist)]
    (= (count (dist-component grid dist first-cell))
       (dist-size grid dist))
    true)) ; empty districts are vacuously contiguous

(defn contiguous-dists [grid]
  (filter (partial dist-contiguous? grid) (dists grid)))

(defn adjacent-dist-pairs [grid]
  (filter
    (fn [dist-pair] (let [[a b] dist-pair] (and a b (> b a))))
    (reduce set/union
      (map-grid-flat
        (fn [row col cell]
          (set (map #(list cell (get-cell grid %)) (cell-neighbors row col))))
        grid))))

(defn dist-quotient-graph-connected? [grid dists]
  ;; Perform a BFS on the subgraph of the district
  ;; quotient graph induced by `dist`.
  (if-let [first-dist (first dists)]
    (loop [visited #{}
           stack (list first-dist)
           adj (adjacent-dist-pairs grid)]
      (if (seq stack)
        (let [curr (first stack)
              neighbors (map (partial pair-other-element curr)
                             (filter (partial pair-has-element? curr) adj))
              next-dists (set/intersection
                           (set dists)
                           (set/difference (set neighbors) visited))]
          (recur (conj visited curr) (concat next-dists (drop 1 stack)) adj))
        (= (set dists) visited)))
    true)) ; empty subgraph is vacuously contiguous



;; Grid parameters (TODO: add menu options, load initial plans from server)
(def width 8)
(def height 8)
(def stripes (vec (map (fn [row] (vec (repeat width row)))
                       (range 1 (+ height 1)))))
(def initial-state {:grid stripes :selected #{} :in-progress #{}})

(def cases
  (map (fn [n] {:name (str/format "%dx%d → %d, no tolerance" n n n)
                :path (str/format "%d_%d_%d_0.json" n n n)})
       (range 4 11)))

;; Grid rendering and event handling.
(defn render-grid-row [row-idx row-state selected]
  (map-indexed
    (fn [col-idx assignment]
      (let [selected-class (when (contains? selected assignment) " selected")]
        [:button {:id (str "grid-cell-" row-idx "-" col-idx)
                  :class (str "grid-cell dist-" assignment selected-class)}
         assignment]))
    row-state))

(defn render-grid [grid-state selected]
  (map-indexed
    (fn [row-idx row-state]
      [:div {:data-grid-row row-idx :class "grid-row"}
       (render-grid-row row-idx row-state selected)])
    grid-state))

(defn on-dist-click [state dist]
  (if (empty? (get state :in-progress))
    (let [next-state (update state :selected toggle dist)]
      (refresh! next-state))))

(defn reset-selected [state] (refresh! (assoc state :selected #{})))

(defn merge-selected [state]
  (let [{grid :grid selected :selected} state]
    (when (and (= (count selected) 2)
             (dist-quotient-graph-connected? grid selected))
      (let [[a b] (into () selected)]
        (refresh! {:grid (replace-grid {a 0, b 0} grid)
                   :selected #{}
                   :in-progress selected})))))


;; keypress handling based on snippet from
;; https://tech.toryanderson.com/2020/10/22/
;; capturing-key-presses-in-clojurescript-with-closure/
(defn capture-key
  [state keycodes]
  (let [press-fn (fn [key-press]
                   (when-let [f (get keycodes (.. key-press -keyCode))]
                     (f state)))]
    (gevents/listen (KeyHandler. js/document)
                (-> KeyHandler .-EventType .-KEY)
                press-fn)))

(defn attach-grid-events! [state]
  (do-grid
    (fn [row-idx col-idx assignment]
      (when-let
        [grid-button (gdom/getElement (str "grid-cell-" row-idx "-" col-idx))]
          (gevents/listen grid-button "click"
                          (fn [_] (on-dist-click state assignment)))))
    (get state :grid)))

;; App rendering.
(defn attach-event-handlers! [state]
  (attach-grid-events! state)
  (capture-key state {keycodes/R reset-selected
                      keycodes/M merge-selected}))

(defn render-app! [state]
  (set-app-html!
   (hiccups/html
    [:div {:class "app-main"}
     (render-grid (get state :grid) (get state :selected))])))

(defn refresh! [state]
  (gevents/removeAll js/document) ; reset global event listeners
  (render-app! state)
  (attach-event-handlers! state)
  (print "Refreshed!"))

(refresh! initial-state)
