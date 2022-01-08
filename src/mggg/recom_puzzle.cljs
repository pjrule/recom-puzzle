;; UI for in-browser ReCom puzzle.
;;
;; some helpers inspired by (or borrowed from) the learn-cljs tutorials
;; see https://github.com/kendru/learn-cljs/blob/main/code/lesson-20/contacts/
;;     src/learn_cljs/contacts.cljs
(ns ^:figwheel-hooks mggg.recom-puzzle
  (:require-macros [hiccups.core :as hiccups])
  (:require [hiccups.runtime]
            [goog.dom :as gdom]
            [goog.events :as gevents]
            [goog.string :as str]))


;; Container setup.
(def app-container (gdom/getElement "app"))
(defn set-app-html! [html-str]
  (set! (.-innerHTML app-container) html-str))
(declare refresh!)


;; Grid parameters (TODO: add menu options, load initial plans from server)
(def width 8)
(def height 8)

(def initial-state
  {:grid (map (fn [row] (repeat width row)) (range height))
   :selected #{}})

(def cases
  (map (fn [n] {:name (str/format "%dx%d → %d, no tolerance" n n n)
                :path (str/format "%d_%d_%d_0.json" n n n)})
       (range 4 11)))

;; Grid rendering and event handling.
(defn render-grid-row [row-idx row-state]
  (map-indexed
    (fn [col-idx assignment]
      [:button {:id (str "grid-cell-" row-idx "-" col-idx)
                :class (str "grid-cell dist-" assignment)}
        (+ 1 assignment)])
    row-state))

(defn render-grid [grid-state]
  (map-indexed
    (fn [row-idx row-state]
      [:div {:data-grid-row row-idx :class "grid-row"}
       (render-grid-row row-idx row-state)])
    grid-state))

(defn on-dist-click [dist])

(defn attach-grid-events! [grid-state]
  (map-indexed
    (fn [row-idx row-state] 
      (map-indexed
        (fn [col-idx assignment]
          (when-let
            [grid-button (gdom/getElement (str "grid-cell-" row-idx "-" col-idx))]
              (gevents/listen grid-button "click"
                              (fn [_] (on-dist-click assignment)))))))
    grid-state))


;; App rendering.
(defn attach-event-handlers! [state]
  (attach-grid-events! (get state :grid)))

(defn render-app! [state]
  (set-app-html!
   (hiccups/html
    [:div {:class "app-main"}
     (render-grid (get state :grid))])))

(defn refresh! [state]
  (render-app! state)
  (attach-event-handlers! state))

(refresh! initial-state)
