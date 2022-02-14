;;; UI for in-browser ReCom puzzle.
(ns ^:figwheel-hooks mggg.recom-puzzle
  (:require-macros [hiccups.core :as hiccups]
                   [cljs.core.async.macros :refer [go]])
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs-http.client :as http]
            [hiccups.runtime]
            [goog.dom :as gdom]
            [goog.events.KeyCodes :as keycodes]
            [goog.events :as gevents]
            [goog.object :as gobj]
            [goog.string :as gstr]
            goog.string.format
            [cljs.core.async :refer [<!]])
  (:import [goog.events EventType KeyHandler]))


;;; ================ Global parameters and state ====================
;;; We don't keep a global state variable (rather, `render!` is called
;;; with a new `state` for each UI change), but we do keep global
;;; state history.
;;; TODO: add menu options, load initial plans from server.

(def grids 
  ;; A grid is parameterized by its width, height, and district count (`n`).
  ;; 
  ;; Starting plans are pregenerated with `enumpart`.
  ;; For parameterizations with ≤100,000 possible plans, we store
  ;; the whole enumeration in pages of varying size (always less
  ;; than 10,000 lines). In all other cases, we store a uniform
  ;; sample of 100,000 plans in 100 pages of 1,000 lines each.
  ;; 
  ;; TODO: autogenerate this? (up to special cases...)
  ;; (Useful to be able to remove some for debugging, though.)
  [{:width 4 :height 4 :n 4 :pages 1}
   {:width 4 :height 5 :n 4 :pages 1}
   {:width 4 :height 5 :n 5 :pages 1}
   {:width 4 :height 6 :n 4 :pages 1}
   {:width 4 :height 6 :n 6 :pages 1}
   {:width 4 :height 7 :n 4 :pages 1}
   {:width 4 :height 7 :n 7 :pages 1}
   {:width 4 :height 8 :n 4 :pages 40}
   {:width 4 :height 8 :n 8 :pages 41}
   {:width 4 :height 9 :n 4 :pages 100}
   {:width 4 :height 9 :n 9 :pages 100}

   {:width 5 :height 5 :n 5 :pages 1}
   {:width 5 :height 6 :n 5 :pages 34}
   {:width 5 :height 6 :n 6 :pages 28}
   {:width 5 :height 7 :n 5 :pages 100}
   {:width 5 :height 7 :n 7 :pages 100}
   {:width 5 :height 8 :n 5 :pages 100}
   {:width 5 :height 8 :n 8 :pages 100}
   {:width 5 :height 9 :n 5 :pages 100}
   {:width 5 :height 9 :n 9 :pages 100}

   {:width 6 :height 6 :n 6 :pages 100}
   {:width 6 :height 7 :n 6 :pages 100}
   {:width 6 :height 7 :n 7 :pages 100}
   {:width 6 :height 8 :n 6 :pages 100}
   {:width 6 :height 8 :n 8 :pages 100}
   {:width 6 :height 9 :n 6 :pages 100}
   {:width 6 :height 9 :n 9 :pages 100}

   {:width 7 :height 7 :n 7 :pages 100}
   {:width 7 :height 8 :n 7 :pages 100}
   {:width 7 :height 8 :n 8 :pages 100}
   {:width 7 :height 9 :n 7 :pages 100}
   {:width 7 :height 9 :n 9 :pages 100}

   {:width 8 :height 8 :n 8 :pages 100}
   {:width 8 :height 9 :n 8 :pages 100}
   {:width 8 :height 9 :n 9 :pages 100}])
(def default-grid {:width 6 :height 6 :n 6 :pages 100})


(defonce history (atom ()))
(defonce enum (atom ()))
(defonce params (atom default-grid))
(defonce start-time (atom (.now js/Date)))

(declare refresh!)
(declare init!)
(defn in-progress? [state] (seq (get state :in-progress)))


;;; ======================== Generic helpers ========================
;;; Primarily for grid (2D `vec`) and pair manipulation.
(defn unordered-pair-in? [coll [a b]]
  (some #(or (= (list a b) %) (= (list b a) %)) coll))
(defn pair-has-element? [el [a b]] (or (= a el) (= b el)))
(defn pair-other-element [el [a b]] (if (= a el) b a))
(defn index-when-matches-pair [a b]
  (fn [[idx dist]] (when (or (= dist a) (= dist b)) idx)))

(defn get-cell [grid [row col]] (-> grid (get row) (get col)))
(defn set-cell [grid [row col] v] (assoc grid row (assoc (get grid row) col v)))
(defn cell-neighbors [row col]
  (partition 2 (list (- row 1) col (+ row 1) col row (- col 1) row (+ col 1))))

(defn get-col [grid col] (vec (map #(get % col) grid)))
(defn transpose [grid]
  (vec (map #(vec (get-col grid %)) (range (count (get grid 0))))))
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

;;; ======================== Grid graph logic ========================
;;; We require districts to be connected (one rook-connected piece)
;;; and population-balanced. 

(defn dists [grid] (distinct (flatten grid)))
(defn dist-sizes [grid] (frequencies (flatten grid)))
(defn dist-cells [grid dist]
  (filter identity
    (map-grid-flat (fn [row col v] (when (= v dist) (list row col))) grid)))
(defn dist-size [grid dist] (count (dist-cells grid dist)))
(defn any-dist-cell [grid dist] (some identity (dist-cells grid dist)))
(defn dists-balanced? [grid]
  (let [sizes (vals (dist-sizes grid))]
    (= (apply min sizes) (apply max sizes))))

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

(defn dists-contiguous? [grid]
  (every? (partial dist-contiguous? grid) (dists grid)))

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

(defn dists-valid? [grid] (and (dists-contiguous? grid) (dists-balanced? grid)))


;;; ====================== Enumeration fetching ======================
(defn parse-next-plan! [params enum]
  ;; Fetches the next starting plan from the enumeration.
  (when-let [next-plan (first @enum)]
    (swap! enum pop)
    (vec (map vec (partition (get @params :width) (map int next-plan))))))

(defn fetch-random-enum! [params enum history start-time render?]
  ;; Populates the enumeration atom with a random enumeration.
  (go (let [{width :width height :height n :n pages :pages} @params
            page-idx   (gstr/format "%02d" (rand-int pages))
            dims       (gstr/format "%dx%d_%d" width height n)
            page-url   (gstr/format "enum/%s/%s_%s.dat" dims dims page-idx)
            page-data  (<! (http/get page-url))
            page-lines (into () (str/split (get page-data :body) #"\n"))]
      (reset! enum (apply list (shuffle page-lines)))
      (when render?
        (let [grid  (parse-next-plan! params enum)
              title (gstr/format "ReCom (%dx%d → %d)" width height n)]
          (set! (.-title js/document) title)
          (reset! history (list grid))
          (reset! start-time (.now js/Date))
          (refresh! {:grid grid :selected () :in-progress #{} :score 0}))))))


;;; ========================= UI rendering =========================
(defn render-grid-row [state row-idx row-state]
  (let [{grid :grid selected :selected in-progress :in-progress
         selected-row :row selected-col :col} state]
    (map-indexed
      (fn [col-idx dist]
        (let [selected-class (when (some #{dist} selected) " selected")
              progress-class (when (contains? in-progress dist) " in-progress")
              cursor-class   (when (and (= row-idx selected-row)
                                        (= col-idx selected-col)) " cursor")]
          [:button {:id (str "grid-cell-" row-idx "-" col-idx)
                    :class (str "grid-cell dist-" dist
                                selected-class progress-class cursor-class)}
           dist]))
      row-state)))

(defn render-grid [state]
  [:div {:id "grid"}
    (map-indexed
      (fn [row-idx row-state]
        [:div {:data-grid-row row-idx :class "grid-row"}
         (render-grid-row state row-idx row-state)])
      (get state :grid))])

(defn render-params [params]
  [:select {:id "grid-size"}
   (map-indexed
    (fn [idx grid]
      (let [{width :width height :height n :n} grid]
        [:option {:value idx :selected (= grid @params)}
         (gstr/format "%dx%d → %d" width height n)]))
    grids)])

;;; ================== Cell/district selection events =================
;;; For manipulating individual cells and districts.
(defn on-cell-select! [state row col]
  ;; Toggles a celal's district assignment when the cell
  ;; is in an in-progress district.
  (when (in-progress? state)
    (let [{grid :grid in-progress :in-progress} state
          cell-dist (get-cell grid (list row col))]
      (when (contains? in-progress cell-dist)
        (let [toggle-dist (pair-other-element cell-dist (seq in-progress))
              new-grid (set-cell grid (list row col) toggle-dist)]
          (refresh! (assoc state :grid new-grid :row row :col col)))))))

(defn toggle-selected-cell! [state]
  ;; Toggles the district of the selected cell of the in-progress district pair.
  (when (in-progress? state)
    (let [{row :row col :col} state] (on-cell-select! state row col))))


;;; ===================== Global selection events ====================
;;; For manipulating district pairs and multiple-district selections.
(defn shuffle! []
  (let [grid (parse-next-plan! params enum)]
    (reset! history (list grid))
    (reset! start-time (.now js/Date))
    (refresh! {:grid grid :selected () :in-progress #{} :score 0})))

(defn reset-selected! [state]
  ;; Clears selected district pair.
  (when (in-progress? state)
    (let [{grid :grid in-progress :in-progress score :score} state
          [a b] (seq in-progress)
          new-grid (replace-grid {a (min a b), b (min a b)} grid)
          [row col]  (any-dist-cell grid a)]
        (refresh! {:grid new-grid
                   :selected ()
                   :score score
                   :in-progress in-progress
                   :row row 
                   :col col}))))

(defn merge-selected! [state] 
  ;; If two contiguous districts are selected, makes them editable. 
  ;; Otherwise, attempts to save in-progress districts.
  (let [{grid :grid selected :selected in-progress :in-progress
         score :score} state]
    (when (and (= (count selected) 2)
             (dist-quotient-graph-connected? grid selected))
      (let [[a b] (seq selected)
            [row col] (any-dist-cell grid (min a b))]
        (refresh! {:grid grid
                   :selected ()
                   :in-progress (set selected)
                   :row row
                   :col col
                   :score score})))
    (when (in-progress? state) 
      (if (dists-valid? grid)
        (do (swap! history conj grid) ; save last state in global history
            (refresh! {:grid grid :selected () :in-progress #{}
                       :score (inc score)}))
        (when-let [grid (gdom/getElement "grid")]
          ;; hack: ephemerally attach the `shake` class (removed
          ;; at next render)
          (gdom/setProperties grid #js {"class" "shake"}))))))

(defn on-dist-select! [state dist]
  ;; Toggles district selection when no districts are in progress.
  ;; No more than two districts can be selected at a time.
  (let [selected (or (get state :selected) ())
        last-selected-time (get state :last-selected-time)
        now (.now js/Date)
        in-selected? (some #{dist} selected)]
    (when (not (in-progress? state))
      (if in-selected?
        ;; We register clicks with a <500 ms interval as a merge event.
        (if (< (- now last-selected-time) 500)
          (merge-selected! (dissoc state :last-selected-time))
          (refresh! (assoc state :selected (filter #(not (= dist %)) selected)
                                 :last-selected-time now)))
        (if (< (count selected) 2) ; non-toggle case
          (refresh! (assoc (update state :selected conj dist)
                           :last-selected-time now))
          (refresh! (assoc state :selected (list dist (first selected))
                                 :last-selected-time now)))))))

(defn on-dist-digit-key! [digit] #(on-dist-select! % digit))

(defn automerge! [state]
  ;; If the two in-progress districts can be reconfigured to
  ;; horizontal or vertical stripes in one move, reconfigure them.
  (when (in-progress? state)
    (let [{grid :grid in-progress :in-progress score :score} state
          [a b]   (seq in-progress)
          cells   (concat (dist-cells grid a) (dist-cells grid b))
          min-row (apply min (map first cells))
          max-row (apply max (map first cells))
          min-col (apply min (map second cells))
          max-col (apply max (map second cells))
          union-width  (+ 1 (- max-col min-col))
          union-height (+ 1 (- max-row min-row))]
      (when (= union-height 2)  ; horizontal merge
        (let [new-grid
              (assoc grid min-row (vec (repeat union-width (min a b)))
                          max-row (vec (repeat union-width (max a b))))]
          (refresh! (assoc state :grid new-grid))))
      (when (= union-width 2)  ; vertical merge
        (let [grid-t (transpose grid)
              new-grid
              (assoc grid-t min-col (vec (repeat union-height (min a b)))
                            max-col (vec (repeat union-height (max a b))))
              grid-tt (transpose new-grid)]
          (refresh! (assoc state :grid grid-tt)))))))
        

;;; ===================== History events (undo) =====================
;;; TODO: redo?
(defn undo! [state]
  ;; Reverts to the last global grid state.
  ;; In-progress/selected districts are cleared.
  (if (seq (get state :selected))
    (refresh! (assoc state :selected ()))
    (let [last-grid (first @history) {score :score} state]
      (when (second @history) (swap! history rest))
      (refresh! {:grid last-grid :selected () :in-progress #{}
                 :score (if (in-progress? state) (inc score) score)}))))


;;; ======================= Keyboard navigation =======================
;;; Helpers to move the selected cell up/down/left/right when a district
;;; pair is in progress. In-between cells are jumped over.
(defn move-up! [order state]
  (when (in-progress? state)
    (let [{row :row col :col grid :grid in-progress :in-progress} state
          col-above (subvec (get-col grid col) 0 row)
          pairs-above (order (map-indexed #(list %1 %2) col-above))
          [a b] (seq in-progress)]
      (when-let [first-row (some (index-when-matches-pair a b) pairs-above)]
        (refresh! (assoc state :row first-row))))))

(defn move-down! [order state]
  (when (in-progress? state)
    (let [{row :row col :col grid :grid in-progress :in-progress} state
          col-below (subvec (get-col grid col) (inc row))
          pairs-below (order (map-indexed #(list (+ 1 row %1) %2) col-below))
          [a b] (seq in-progress)]
      (when-let [first-row (some (index-when-matches-pair a b) pairs-below)]
        (refresh! (assoc state :row first-row))))))

(defn move-left! [order state]
  (when (in-progress? state)
    (let [{row :row col :col grid :grid in-progress :in-progress} state
          col-left (subvec (get grid row) 0 col)
          pairs-left (order (map-indexed #(list %1 %2) col-left))
          [a b] (seq in-progress)]
      (when-let [first-col (some (index-when-matches-pair a b) pairs-left)]
        (refresh! (assoc state :col first-col))))))

(defn move-right! [order state]
  (when (in-progress? state)
    (let [{row :row col :col grid :grid in-progress :in-progress} state
          col-right (subvec (get grid row) (inc col))
          pairs-right (order (map-indexed #(list (+ 1 col %1) %2) col-right))
          [a b] (seq in-progress)]
      (when-let [first-col (some (index-when-matches-pair a b) pairs-right)]
        (refresh! (assoc state :col first-col))))))

;;; Basic navigation (one cell at a time).
(def move-one-up!    (partial move-up!    reverse))
(def move-one-down!  (partial move-down!  identity))
(def move-one-left!  (partial move-left!  reverse))
(def move-one-right! (partial move-right! identity))

;;; Jump navigation (as far as possible in a direction).
(def jump-up!    (partial move-up!    identity))
(def jump-down!  (partial move-down!  reverse))
(def jump-left!  (partial move-left!  identity))
(def jump-right! (partial move-right! reverse))


;;; ===================== Global event handlers  =====================
;;; Key events and grid click events are re-attached to the DOM at
;;; each render.
(defn capture-key
  [state keycodes]
  ;; keypress handling based on snippet from
  ;; https://tech.toryanderson.com/2020/10/22/
  ;; capturing-key-presses-in-clojurescript-with-closure/
  (let [press-fn (fn [key-press]
                   (when-let [f (get keycodes (.. key-press -keyCode))]
                     (f state)))]
    (gevents/listen (KeyHandler. js/document)
                (-> KeyHandler .-EventType .-KEY)
                press-fn)))

(defn attach-grid-events! [state]
  (do-grid
    (fn [row-idx col-idx dist]
      (when-let
        [grid-button (gdom/getElement (str "grid-cell-" row-idx "-" col-idx))]
          (gevents/listen grid-button "click"
                          #(on-dist-select! state dist))
          (gevents/listen grid-button "click"
                          #(on-cell-select! state row-idx col-idx))))
    (get state :grid)))


(defn attach-shuffle-event! [state]
  (when-let [shuffle-button (gdom/getElement "shuffle-button")]
    (gevents/listen shuffle-button "click" shuffle!)))


(defn attach-grid-size-selector-event! [params]
  (when-let [grid-size-selector (gdom/getElement "grid-size")]
    (.addEventListener grid-size-selector "change"
      (fn []
        (let [new-params (get grids (. grid-size-selector -selectedIndex))]
          (reset! params new-params)
          (init!))))))


(defn attach-event-handlers! [state]
  (attach-grid-events! state)
  (attach-shuffle-event! state)
  (attach-grid-size-selector-event! params)
  (capture-key state {keycodes/R reset-selected!
                      keycodes/ENTER merge-selected!
                      keycodes/U undo!
                      keycodes/ESC undo!  ; TODO: keep this?
                      keycodes/SPACE toggle-selected-cell!
                      keycodes/ZERO  toggle-selected-cell!
                      keycodes/Q     automerge!

                      ;; district selection keybindings (up to 9x9)
                      ;; TODO: use modified hexadecimal for larger grids?
                      keycodes/ONE (on-dist-digit-key! 1)
                      keycodes/TWO (on-dist-digit-key! 2)
                      keycodes/THREE (on-dist-digit-key! 3)
                      keycodes/FOUR (on-dist-digit-key! 4)
                      keycodes/FIVE (on-dist-digit-key! 5)
                      keycodes/SIX (on-dist-digit-key! 6)
                      keycodes/SEVEN (on-dist-digit-key! 7)
                      keycodes/EIGHT (on-dist-digit-key! 8)
                      keycodes/NINE (on-dist-digit-key! 9)

                      ;; one-cell-at-a-time navigation
                      keycodes/UP move-one-up!
                      keycodes/LEFT move-one-left!
                      keycodes/DOWN move-one-down!
                      keycodes/RIGHT move-one-right!

                      ;; jump navigation
                      keycodes/W move-one-up!
                      keycodes/A move-one-left!
                      keycodes/S move-one-down!
                      keycodes/D move-one-right!}))


;;; ======================= App container setup =======================
;;; some app-level helpers inspired by (or borrowed from) learn-cljs
;;; see https://github.com/kendru/learn-cljs/blob/main/code/lesson-20/contacts/
;;;     src/learn_cljs/contacts.cljs
(def app-container (gdom/getElement "app"))
(defn set-app-html! [html-str]
  (set! (.-innerHTML app-container) html-str))


;;; ============================= Timer =============================
(defn render-timer [start-time]
  (let [delta (- (.now js/Date) @start-time)
        seconds (quot delta 1000)
        minutes (quot seconds 60)
        seconds-mod (mod seconds 60)]
    (gstr/format "%d:%02d" minutes seconds-mod)))

(defn update-timer! [start-time]
  (when-let [timer-el (gdom/getElement "timer")]
    (set! (.-innerHTML timer-el) (render-timer start-time)))
  (js/setTimeout (partial update-timer! start-time) 200))

;;; ======================== Global rendering ========================
(defn render-app! [state]
  (set-app-html!
   (hiccups/html
    [:div {:class "app-main"}
     [:div {:class "app-header"}
      [:span {:id "timer"} (render-timer start-time)]
      [:span {:class "score"} (get state :score)]
      [:button {:id "shuffle-button"} "🔀"]]
     (render-grid state)
     [:div {:class "app-footer"}
      (render-params params)]])))

(defn refresh! [state]
  (gevents/removeAll js/document) ; reset global event listeners
  (render-app! state)
  (attach-event-handlers! state)
  (print "Refreshed!"))

(defn init! []
  (fetch-random-enum! params enum history start-time true)
  (update-timer! start-time))
(init!)
