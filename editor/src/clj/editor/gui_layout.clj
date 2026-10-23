;; Copyright 2020-2026 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;;
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;;
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.gui-layout
  "Visual editing of native GUI layout properties. No additional runtime format."
  (:require [dynamo.graph :as g]
            [editor.camera :as camera]
            [editor.fxui :as fxui]
            [editor.gui-rect :as gui-rect]
            [editor.localization :as localization]
            [editor.properties :as properties]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.beans.value ChangeListener]
           [javafx.scene Node Parent]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.control Control Tab]
           [javafx.scene.input KeyCode KeyEvent MouseButton MouseEvent]
           [javafx.scene.layout Pane]
           [javafx.scene.paint Color]
           [javafx.stage Window]
           [javax.vecmath Matrix4d Point3d Quat4d Vector3d]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def pivot-points
  {:pivot-nw [0.0 1.0] :pivot-n [0.5 1.0] :pivot-ne [1.0 1.0]
   :pivot-w [0.0 0.5] :pivot-center [0.5 0.5] :pivot-e [1.0 0.5]
   :pivot-sw [0.0 0.0] :pivot-s [0.5 0.0] :pivot-se [1.0 0.0]})

(defn rect-corners [pivot [width height]]
  (let [[px py] (pivot-points pivot)
        left (- (* (double px) (double width)))
        bottom (- (* (double py) (double height)))
        right (+ left (double width))
        top (+ bottom (double height))]
    [[left bottom] [right bottom] [right top] [left top]]))

(defn local-delta->parent [rotation scale delta]
  (let [[x y z w] rotation
        matrix (doto (Matrix4d.) (.setIdentity) (.setRotation (Quat4d. x y z w)))
        [dx dy dz] (mapv * scale delta)
        vector (Vector3d. dx dy dz)]
    (.transform matrix vector)
    [(.x vector) (.y vector) (.z vector)]))

(defn pivot-delta [size old-pivot new-pivot]
  (conj (mapv (fn [size old new] (* (double size) (- (double new) (double old))))
              size (pivot-points old-pivot) (pivot-points new-pivot))
        0.0))

(defn resize-rect
  "Resize in the original local plane, keeping the opposite edge fixed.
  Returns the new size and the displacement of the pivot in that plane."
  [pivot size [hx hy] [dx dy] proportional]
  (let [[width height depth] size
        width (double width)
        height (double height)
        hx (double hx)
        hy (double hy)
        next-width (max 1.0 (+ width (* hx (double dx))))
        next-height (max 1.0 (+ height (* hy (double dy))))
        [next-width next-height]
        (if (and proportional (not (zero? hx)) (not (zero? hy)) (pos? width) (pos? height))
          (let [ratio (max (/ next-width width) (/ next-height height))]
            [(* width ratio) (* height ratio)])
          [next-width next-height])
        [px py] (pivot-points pivot)]
    {:size [next-width next-height depth]
     :delta [(* (- (double px) (if (neg? hx) 1.0 0.0)) (- (double next-width) width))
             (* (- (double py) (if (neg? hy) 1.0 0.0)) (- (double next-height) height))
             0.0]}))

(defn property-steps [evaluation-context node-id property value]
  (let [entry (get-in (g/node-value node-id :_properties evaluation-context) [:properties property])]
    (assert entry (str "Missing GUI property: " property))
    (properties/set-value evaluation-context
                          (assoc entry :key property :node-id node-id :prop-kw property)
                          value)))

(defn commit! [changes label]
  (let [evaluation-context (g/make-evaluation-context)
        changes (into [] (remove (fn [[node-id property value]] (= value (g/node-value node-id property evaluation-context)))) changes)]
    (when (coll/not-empty changes)
      (g/transact
        {:tx-data-context-map {:edited-endpoints (into #{} (map (fn [[node-id property]] (g/endpoint node-id property))) changes)}}
        (into (vec (g/operation-label label))
              (mapcat (fn [[node-id property value]] (property-steps evaluation-context node-id property value)))
              changes)))))

(defn- child-compensation [node-id delta evaluation-context]
  (into []
        (keep (fn [child]
                (let [id (:node-id child)]
                  (when-not (and (g/has-property? (g/node-type* id) :rect-transform)
                                 (g/node-value id :rect-transform evaluation-context))
                    [id :position (mapv - (g/node-value id :position evaluation-context) delta)]))))
        (g/node-value node-id :scene-children evaluation-context)))

(defn- node-size [node-id entries evaluation-context]
  (g/node-value node-id (if (contains? entries :rect-transform) :rect-size :manual-size) evaluation-context))

(defn pivot-changes [node-id pivot keep-appearance evaluation-context]
  (let [entries (:properties (g/node-value node-id :_properties evaluation-context))
        size (node-size node-id entries evaluation-context)
        rect-enabled (g/node-value node-id :rect-transform evaluation-context)
        position (g/node-value node-id :rect-position evaluation-context)
        delta (pivot-delta size (g/node-value node-id :pivot evaluation-context) pivot)]
    (cond-> [[node-id :pivot pivot]]
      rect-enabled
      (into [[node-id :rect-reference (g/node-value node-id :rect-parent evaluation-context)]
             [node-id :manual-size size] [node-id :position position]])
      keep-appearance
      (into (into [[node-id :position
                    (mapv + position (local-delta->parent (g/node-value node-id :rotation evaluation-context)
                                                        (g/node-value node-id :scale evaluation-context) delta))]]
                  (child-compensation node-id delta evaluation-context))))))

(defn- message [localization-state suffix]
  (localization-state (localization/message (str "gui-layout." suffix))))

(defn- animation-preview-active? [app-view]
  (boolean
    (when-let [view (g/node-value app-view :active-view)]
      (when (g/has-property? (g/node-type* view) :animation-preview-active)
        (g/node-value view :animation-preview-active)))))

(def inspector-properties
  #{:id :enabled :visible :position :rotation :scale :manual-size :texture-size
    :size-mode :pivot :x-anchor :y-anchor})

(defn- preset-graphic [pivot]
  (let [[x y] (pivot-points pivot)]
    {:fx/type :pane
     :min-width 26.0 :pref-width 26.0 :max-width 26.0
     :min-height 22.0 :pref-height 22.0 :max-height 22.0
     :mouse-transparent true
     :children [{:fx/type :rectangle :x 4.0 :y 4.0 :width 18.0 :height 14.0
                 :style "-fx-fill: transparent; -fx-stroke: -df-text-dark;"}
                {:fx/type :circle :center-x (+ 4.0 (* 18.0 (double x)))
                 :center-y (+ 4.0 (* 14.0 (- 1.0 (double y)))) :radius 2.5
                 :style "-fx-fill: -df-defold-blue-lighter;"}]}))

(defn rect-preset-changes [node-id preset set-pivot set-position evaluation-context]
  (let [parent (g/node-value node-id :rect-parent evaluation-context)
        old-pivot (g/node-value node-id :pivot evaluation-context)
        pivot (if set-pivot
                (first (coll/first-where #(= (:pivot preset) (val %)) pivot-points))
                old-pivot)
        size (g/node-value node-id :rect-size evaluation-context)
        position (g/node-value node-id :rect-position evaluation-context)
        delta (pivot-delta size old-pivot pivot)
        position (mapv + position (local-delta->parent (g/node-value node-id :rotation evaluation-context)
                                                       (g/node-value node-id :scale evaluation-context) delta))
        {:keys [anchor-min anchor-max]} preset
        [position size]
        (if set-position
          (reduce (fn [[position size] axis]
                    (let [span (- (double (anchor-max axis)) (double (anchor-min axis)))
                          length (if (pos? span) (* span (double (parent axis))) (double (size axis)))
                          anchor (+ (double (anchor-min axis)) (* span (double ((pivot-points pivot) axis))))]
                      [(assoc position axis (* (- anchor (double (parent (+ (long axis) 2)))) (double (parent axis))))
                       (assoc size axis length)]))
                  [position size] [0 1])
          [position size])
        entries (:properties (g/node-value node-id :_properties evaluation-context))]
    (cond-> (into [[node-id :rect-transform true]
                  [node-id :rect-anchor-min anchor-min] [node-id :rect-anchor-max anchor-max]
                  [node-id :rect-reference parent] [node-id :position position]
                  [node-id :manual-size size] [node-id :pivot pivot]]
                 (child-compensation node-id delta evaluation-context))
      (contains? entries :size-mode) (conj [node-id :size-mode :size-mode-manual]))))

(defn- rect-preset-graphic [anchor-min anchor-max]
  {:fx/type :pane
   :min-width 30.0 :pref-width 30.0 :max-width 30.0
   :min-height 25.0 :pref-height 25.0 :max-height 25.0
   :mouse-transparent true
   :children
   (into [{:fx/type :rectangle :x 4.0 :y 4.0 :width 22.0 :height 17.0
           :style "-fx-fill: transparent; -fx-stroke: -df-text-dark;"}
          {:fx/type :rectangle
           :x (+ 4.0 (* 22.0 (double (first anchor-min))))
           :y (+ 4.0 (* 17.0 (- 1.0 (double (second anchor-max)))))
           :width (* 22.0 (- (double (first anchor-max)) (double (first anchor-min))))
           :height (* 17.0 (- (double (second anchor-max)) (double (second anchor-min))))
           :style "-fx-fill: transparent; -fx-stroke: -df-defold-blue-lighter;"}]
         (map (fn [[x y]]
                {:fx/type :circle :center-x (+ 4.0 (* 22.0 (double x)))
                 :center-y (+ 4.0 (* 17.0 (- 1.0 (double y)))) :radius 2.0
                 :style "-fx-fill: -df-defold-blue-lighter;"}))
         (into [] (distinct) [[(first anchor-min) (second anchor-min)] [(first anchor-min) (second anchor-max)]
                    [(first anchor-max) (second anchor-min)] [(first anchor-max) (second anchor-max)]]))})

(defn- rect-preset-popup [node-id app-view localization-state render-grid]
  (let [enabled (g/node-value node-id :rect-transform)
        anchor-min (g/node-value node-id :rect-anchor-min)
        anchor-max (g/node-value node-id :rect-anchor-max)
        apply-preset! (fn [^Node source preset set-pivot set-position]
                        (when-not (ui/user-data source ::preset-applied)
                          (ui/user-data! source ::preset-applied true)
                          (when-not (animation-preview-active? app-view)
                            (commit! (rect-preset-changes node-id preset set-pivot set-position (g/make-evaluation-context))
                                     (localization/message "gui-layout.operation")))
                          (.hide (.getWindow (.getScene source)))))]
    {:fx/type :custom-menu-item :hide-on-click false
     :style-class ["menu-item" "custom-menu-item" "gui-layout-preset-popup"]
     :content
     {:fx/type fxui/vertical :spacing :small :padding :small
      :children
      (into [] (remove nil?)
            [{:fx/type fxui/label :text (message localization-state "anchor-presets")}
             {:fx/type fxui/label :color :hint :text (message localization-state "preset-modifiers")}
             {:fx/type fxui/grid :spacing :small
              :children
              (into
                (into
                  (mapv (fn [column label]
                          {:fx/type fxui/label :text (message localization-state label)
                           :grid-pane/column (inc (long column)) :grid-pane/row 0})
                        (range 4) ["left" "center" "right" "stretch"])
                  (map-indexed (fn [row label]
                                 {:fx/type fxui/label :text (message localization-state label)
                                  :grid-pane/column 0 :grid-pane/row (inc (long row))}))
                  ["top" "middle" "bottom" "stretch"])
                (map (fn [{:keys [column row] :as preset}]
                       {:fx/type fxui/toggle-button
                        :id (str "gui-rect-preset-" column "-" row)
                        :style-class "gui-rect-preset-cell"
                        :graphic (rect-preset-graphic (:anchor-min preset) (:anchor-max preset))
                        :selected (and enabled (= anchor-min (:anchor-min preset)) (= anchor-max (:anchor-max preset)))
                        :grid-pane/column (inc (long column)) :grid-pane/row (inc (long row))
                        :tooltip (str (message localization-state (nth ["left" "center" "right" "stretch"] column))
                                      " / " (message localization-state (nth ["top" "middle" "bottom" "stretch"] row)))
                        :on-mouse-pressed (fn [^MouseEvent event]
                                            (ui/user-data! (.getSource event) ::preset-applied false)
                                            (ui/user-data! (.getSource event) ::preset-modifiers [(.isShiftDown event) (.isAltDown event)]))
                        :on-key-pressed (fn [^KeyEvent event]
                                          (ui/user-data! (.getSource event) ::preset-applied false)
                                          (ui/user-data! (.getSource event) ::preset-modifiers [(.isShiftDown event) (.isAltDown event)]))
                        ;; JavaFX button behavior ignores modified mouse presses.
                        :on-mouse-clicked (fn [^MouseEvent event]
                                            (when (and (= MouseButton/PRIMARY (.getButton event))
                                                       (or (.isShiftDown event) (.isAltDown event)))
                                              (apply-preset! (.getSource event) preset (.isShiftDown event) (.isAltDown event))
                                              (.consume event)))
                        :on-action (fn [^javafx.event.ActionEvent event]
                                     (let [[set-pivot set-position] (ui/user-data (.getSource event) ::preset-modifiers)]
                                       (apply-preset! (.getSource event) preset set-pivot set-position)))}))
                gui-rect/presets)}
             (when enabled
               {:fx/type fxui/grid :spacing :small
                :children
                (mapv (fn [index]
                        (let [maximum (>= (long index) 2)
                              axis (mod (long index) 2)
                              field (if maximum :rect-anchor-max :rect-anchor-min)
                              values (if maximum anchor-max anchor-min)]
                          {:fx/type fxui/vertical :spacing :small
                           :grid-pane/column (long index) :grid-pane/row 0
                           :children
                           [{:fx/type fxui/label :text (str (if maximum "Max " "Min ") (if (zero? (long axis)) "X" "Y"))}
                            {:fx/type fxui/value-field :id (str "gui-rect-" (name field) "-" axis)
                             :pref-column-count 3 :min-width 0.0 :value (values axis)
                             :to-string #(format "%.2f" (double %))
                             :to-value #(try
                                          (let [value (Double/parseDouble %)]
                                            (when (and (Double/isFinite value)
                                                       (if maximum (<= (double (anchor-min axis)) value)
                                                           (<= value (double (anchor-max axis))))) value))
                                          (catch NumberFormatException _ nil))
                             :on-value-changed
                             (fn [value]
                               (when-not (animation-preview-active? app-view)
                                 (commit! [[node-id :position (g/node-value node-id :rect-position)]
                                           [node-id :manual-size (g/node-value node-id :rect-size)]
                                           [node-id :rect-reference (g/node-value node-id :rect-parent)]
                                           [node-id field (assoc values axis value)]]
                                          (localization/message "gui-layout.operation"))))}]}))
                      (range 4))})
             {:fx/type fxui/check-box :id "gui-rect-enabled" :selected enabled
              :text (message localization-state "parent-relative")
              :on-selected-changed
              (fn [value]
                (when-not (animation-preview-active? app-view)
                  (commit! [[node-id :position (g/node-value node-id :rect-position)]
                            [node-id :manual-size (g/node-value node-id :rect-size)]
                            [node-id :rect-reference (g/node-value node-id :rect-parent)]
                            [node-id :rect-transform value]] (localization/message "gui-layout.operation"))))}
             (when-not enabled (render-grid [:x-anchor :y-anchor]))])}}))

(defn- rect-fields [node-id app-view read-only localization-state]
  (let [parent (g/node-value node-id :rect-parent)
        position (g/node-value node-id :rect-position)
        size (g/node-value node-id :rect-size)
        pivot (pivot-points (g/node-value node-id :pivot))
        anchor-min (g/node-value node-id :rect-anchor-min)
        anchor-max (g/node-value node-id :rect-anchor-max)
        offsets (gui-rect/offsets position size pivot anchor-min anchor-max parent)
        commit-geometry (fn [{:keys [position size]}]
                          (when-not (animation-preview-active? app-view)
                            (commit! [[node-id :position position] [node-id :manual-size size]
                                      [node-id :rect-reference parent]] (localization/message "gui-layout.operation"))))]
    {:fx/type fxui/grid :spacing :small :h-box/hgrow :always :min-width 0.0
     :column-constraints [{:fx/type :column-constraints :hgrow :always :min-width 0.0}
                          {:fx/type :column-constraints :hgrow :always :min-width 0.0}]
     :children
     (into []
           (mapcat
             (fn [axis]
               (let [stretch (not= (anchor-min axis) (anchor-max axis))]
                 (mapv (fn [row]
                         (let [label (if stretch
                                       (get-in [["left" "right"] ["bottom" "top"]] [axis row])
                                       (get-in [["pos-x" "width"] ["pos-y" "height"]] [axis row]))
                               index (+ (long axis) (* (long row) 2))
                               value (if stretch (offsets index)
                                         (if (zero? (long row))
                                           (- (double (position axis))
                                              (* (- (double (anchor-min axis)) (double (parent (+ (long axis) 2)))) (double (parent axis))))
                                           (size axis)))]
                           {:fx/type fxui/vertical :spacing :small
                            :grid-pane/column axis :grid-pane/row row :min-width 0.0
                            :children [{:fx/type fxui/label :text (message localization-state label)}
                                       {:fx/type fxui/value-field :id (str "gui-rect-" label)
                                        :min-width 0.0 :pref-column-count 6 :disable read-only
                                        :value value :to-string #(format "%.2f" (double %))
                                        :to-value #(try (let [n (Double/parseDouble %)] (when (Double/isFinite n) n)) (catch NumberFormatException _ nil))
                                        :on-value-changed
                                        (fn [value]
                                          (commit-geometry
                                            (if stretch
                                              (gui-rect/from-offsets (assoc offsets index value) position size pivot anchor-min anchor-max parent)
                                              (if (zero? (long row))
                                                {:position (assoc position axis (+ (double value) (* (- (double (anchor-min axis)) (double (parent (+ (long axis) 2)))) (double (parent axis))))) :size size}
                                                {:position position :size (assoc size axis (max 0.0 (double value)))}))))}]}))
                       [0 1]))))
           [0 1])}))

(defn inspector-view [coalesced context localization-state]
  (let [entries (:properties coalesced)
        node-ids (:original-node-ids coalesced)
        {:keys [app-view render-property-grid]} context]
    (when (and (= 1 (count node-ids)) (contains? entries :pivot) (contains? entries :x-anchor)
               (or (contains? entries :manual-size) (contains? entries :texture-size)))
      (let [keep-appearance (or (g/user-data app-view ::keep-appearance) (atom true))
            _ (g/user-data! app-view ::keep-appearance keep-appearance)
            selected-pivot (properties/unify-values (get-in entries [:pivot :values]))
            selected-x (properties/unify-values (get-in entries [:x-anchor :values]))
            selected-y (properties/unify-values (get-in entries [:y-anchor :values]))
            read-only (boolean (or (animation-preview-active? app-view)
                                   (coll/any? properties/read-only? (mapv entries [:pivot :position :manual-size]))))
            presets [:pivot-nw :pivot-n :pivot-ne :pivot-w :pivot-center :pivot-e :pivot-sw :pivot-s :pivot-se]
            visible-properties (into #{} (mapcat #(if (properties/category? %) (subvec % 1) [%])) (:display-order coalesced))
            render-grid (fn [keys]
                          (when render-property-grid
                            (-> (render-property-grid (into [] (comp (filter visible-properties) (map #(vector % (entries %)))) keys))
                                (assoc :min-width 0.0)
                                (assoc-in [:column-constraints 1 :min-width] 0.0))))
            rect-enabled (g/node-value (first node-ids) :rect-transform)
            layout (:current-layout (g/node-value (first node-ids) :trivial-gui-scene-info))
            preset-label (fn [kind pivot]
                           (message localization-state
                                    (if (and (= :anchor kind) (= :pivot-center pivot))
                                      "no-anchor"
                                      (name pivot))))]
        {:fx/type fxui/vertical
         :id "gui-layout-inspector"
         :fx/key node-ids
         :min-width 0.0
         :spacing :small
         :children
         (into [] (remove nil?)
               [(render-grid [:id :enabled :visible])
                {:fx/type fxui/horizontal :alignment :center-left :spacing :small
                 :children [{:fx/type fxui/label :text (message localization-state "rect-transform") :style-class "property-category"}
                            {:fx/type fxui/label :text "ⓘ" :color :hint
                             :tooltip (message localization-state "native-anchors")}
                            {:fx/type :region :h-box/hgrow :always}
                            {:fx/type fxui/label :color :hint
                             :text (if (= "" layout) (message localization-state "default-layout") layout)
                             :tooltip (str (message localization-state "current-layout") " " layout)}
                            {:fx/type fxui/toggle-button :id "gui-layout-rect-tool"
                             :text (message localization-state "rect-tool") :disable read-only
                             :selected (= :gui-rect (:active-tool context (g/node-value app-view :active-tool)))
                             :tooltip (message localization-state "drag-help")
                             :on-action (fn [_]
                                          (when-not (animation-preview-active? app-view)
                                            (g/transact {:undoable false}
                                                        (g/set-property app-view :active-tool
                                                                        (if (= :gui-rect (g/node-value app-view :active-tool)) :move :gui-rect)))))}]}
                {:fx/type fxui/horizontal :spacing :small
                 :children
                 (into
                   [{:fx/type fxui/vertical :spacing :small
                     :children
                     (mapv
                       (fn [kind]
                         (let [selected (if (= :pivot kind)
                                          selected-pivot
                                          (coll/first-where
                                            (fn [pivot]
                                              (let [[x y] (pivot-points pivot)]
                                                (and (= selected-x (case x 0.0 :xanchor-left 1.0 :xanchor-right :xanchor-none))
                                                     (= selected-y (case y 0.0 :yanchor-bottom 1.0 :yanchor-top :yanchor-none)))))
                                            presets))]
                           {:fx/type fxui/menu-button
                            :id (str "gui-layout-" (name kind) "-presets")
                            :style-class (cond-> ["gui-layout-preset-menu"]
                                           (coll/any? #(properties/overridden? (entries %))
                                                      (if (= :pivot kind) [:pivot] [:x-anchor :y-anchor]))
                                           (conj "overridden"))
                            :disable read-only
                            :content-display :top
                            :graphic (if (and (= :anchor kind) rect-enabled)
                                       (rect-preset-graphic (g/node-value (first node-ids) :rect-anchor-min)
                                                            (g/node-value (first node-ids) :rect-anchor-max))
                                       (preset-graphic selected))
                            :text (if (and (= :anchor kind) rect-enabled) (message localization-state "anchor") (preset-label kind selected))
                            :tooltip (str (message localization-state (name kind)) ": " (preset-label kind selected))
                            :items
                            (if (= :anchor kind)
                              [(rect-preset-popup (first node-ids) app-view localization-state render-grid)]
                              [{:fx/type :custom-menu-item :hide-on-click false
                              :style-class ["menu-item" "custom-menu-item" "gui-layout-preset-popup"]
                              :content
                              {:fx/type fxui/vertical :spacing :small :padding :small
                               :children
                               (cond->
                                 [{:fx/type fxui/label :text (message localization-state (name kind))}
                                  {:fx/type fxui/grid :spacing :small
                                   :children
                                   (into []
                                         (map-indexed
                                           (fn [index pivot]
                                             (let [[x y] (pivot-points pivot)
                                                   x-anchor (case x 0.0 :xanchor-left 1.0 :xanchor-right :xanchor-none)
                                                   y-anchor (case y 0.0 :yanchor-bottom 1.0 :yanchor-top :yanchor-none)]
                                               {:fx/type fxui/toggle-button
                                                :id (str "gui-layout-" (name kind) "-" (name pivot))
                                                :style-class "gui-layout-preset-cell"
                                                :content-display :top :graphic (preset-graphic pivot)
                                                :text (preset-label kind pivot)
                                                :selected (= pivot selected)
                                                :grid-pane/column (mod (long index) 3) :grid-pane/row (quot (long index) 3)
                                                :on-action
                                                (fn [event]
                                                  (when-not (animation-preview-active? app-view)
                                                    (commit! (if (= :pivot kind)
                                                               (pivot-changes (first node-ids) pivot @keep-appearance (g/make-evaluation-context))
                                                               [[(first node-ids) :x-anchor x-anchor] [(first node-ids) :y-anchor y-anchor]])
                                                             (localization/message "gui-layout.operation")))
                                                  (.hide (.getWindow (.getScene ^Node (.getSource ^javafx.event.ActionEvent event)))))})))
                                         presets)}]
                                 render-property-grid
                                 (conj (render-grid (if (= :pivot kind) [:pivot] [:x-anchor :y-anchor])))
                                 (= :pivot kind)
                                 (conj {:fx/type fxui/check-box :id "gui-layout-keep-appearance"
                                        :text (message localization-state "keep-appearance")
                                        :selected @keep-appearance :on-selected-changed #(reset! keep-appearance %)}))}}])}))
                       [:anchor :pivot])}]
                   (keep identity)
                   [(if rect-enabled
                      (rect-fields (first node-ids) app-view read-only localization-state)
                      (some-> (render-grid [:position :manual-size :texture-size :size-mode]) (assoc :h-box/hgrow :always)))])}
                (render-grid [:rotation :scale])])}))))

(defn- project-point [camera viewport ^Matrix4d transform [x y]]
  (let [point (Point3d. (double x) (double y) 0.0)]
    (.transform transform point)
    (let [projected (camera/camera-project camera viewport point)]
      [(.x projected) (.y projected)])))

(defn- unproject-point [camera viewport ^Matrix4d transform [x y]]
  ;; Intersect the mouse ray with the node's local XY plane. This also handles
  ;; rotated parents, non-uniform scale, and perspective cameras.
  (let [inverse (doto (Matrix4d. transform) (.invert))
        near (camera/camera-unproject camera viewport (Point3d. (double x) (double y) 0.0))
        far (camera/camera-unproject camera viewport (Point3d. (double x) (double y) 1.0))]
    (.transform inverse near)
    (.transform inverse far)
    (let [dz (- (.z far) (.z near))
          t (if (< (Math/abs dz) 1.0e-9) 0.0 (/ (- (.z near)) dz))]
      [(+ (.x near) (* t (- (.x far) (.x near))))
       (+ (.y near) (* t (- (.y far) (.y near))))])))

(defn- stroke-polygon! [^GraphicsContext gc points]
  (.strokePolygon gc (double-array (mapv first points)) (double-array (mapv second points)) (count points)))

(defn- inside-rect? [corners [x y]]
  (let [[[left bottom] _ [right top]] corners]
    (and (<= (double left) (double x) (double right))
         (<= (double bottom) (double y) (double top)))))

(defn- control-target? [target pane]
  (loop [target target]
    (cond
      (identical? target pane) false
      (instance? Control target) true
      (instance? Node target) (recur (.getParent ^Node target))
      :else false)))

(defn- draw! [^Canvas canvas data]
  (let [gc (.getGraphicsContext2D canvas)]
    (.clearRect gc 0.0 0.0 (.getWidth canvas) (.getHeight canvas))
    (when data
      (let [{:keys [corners handles pivot parent-corners size guides anchor]} data]
        (.setLineWidth gc 1.0)
        (.setStroke gc (Color/web "#92a1b0"))
        (.setLineDashes gc (double-array [4.0 4.0]))
        (when parent-corners (stroke-polygon! gc parent-corners))
        (doseq [{[x1 y1] :from [x2 y2] :to label :label} guides]
          (.strokeLine gc (double x1) (double y1) (double x2) (double y2))
          (.setFill gc (Color/web "#aebbc8"))
          (.fillText gc label (+ 5.0 (/ (+ (double x1) (double x2)) 2.0))
                     (- (/ (+ (double y1) (double y2)) 2.0) 5.0)))
        (.setLineDashes gc (double-array []))
        (when anchor
          (let [[x y] anchor]
            (.setStroke gc (Color/web "#7be3b1"))
            (stroke-polygon! gc [[(- (double x) 6.0) y] [x (- (double y) 6.0)]
                                 [(+ (double x) 6.0) y] [x (+ (double y) 6.0)]])))
        (.setStroke gc (Color/web "#50bfff"))
        (stroke-polygon! gc corners)
        (.setFill gc (Color/web "#172532"))
        (doseq [{[x y] :point} handles]
          (.fillRect gc (- (double x) 3.5) (- (double y) 3.5) 7.0 7.0)
          (.strokeRect gc (- (double x) 3.5) (- (double y) 3.5) 7.0 7.0))
        (let [[x y] pivot]
          (.setStroke gc (Color/web "#ffce70"))
          (.strokeOval gc (- (double x) 5.0) (- (double y) 5.0) 10.0 10.0)
          (.strokeLine gc (- (double x) 8.0) (double y) (+ (double x) 8.0) (double y))
          (.strokeLine gc (double x) (- (double y) 8.0) (double x) (+ (double y) 8.0)))
        (let [[x y] (nth corners 3)]
          (.setFill gc (Color/web "#d8efff"))
          (.fillText gc (format "%.1f × %.1f" (double (first size)) (double (second size)))
                     (+ (double x) 8.0) (- (double y) 10.0)))))))

(defn- selection-data [view-id resource-node app-view]
  (when (and (= :gui-rect (g/node-value app-view :active-tool))
             (not (g/node-value view-id :animation-preview-active)))
    (g/with-auto-evaluation-context ec
      (let [selection (g/node-value view-id :selection ec)
            id (when (= 1 (count selection)) (first selection))
            ids (g/node-value resource-node :node-ids ec)]
        (when (coll/any? #(= id %) (coll/vals ids))
          (let [entries (:properties (g/node-value id :_properties ec))]
            (when (and (contains? entries :manual-size) (contains? entries :pivot)
                       (not (properties/read-only? (:manual-size entries))))
              (let [renderable (first (g/node-value view-id :selected-renderables ec))
                    transform (:world-transform renderable)
                    overrides (get-in (g/node-value view-id :preview-overrides ec) [id :graph-properties])
                    size (get overrides :manual-size
                                 (node-size id entries ec))
                    pivot (get overrides :pivot (g/node-value id :pivot ec))
                    camera (g/node-value view-id :camera ec)
                    viewport (g/node-value view-id :viewport ec)
                    corners (rect-corners pivot size)
                    parent (get ids (g/node-value id :parent ec))
                    parent-entries (when parent (:properties (g/node-value parent :_properties ec)))
                    parent-size (when (contains? parent-entries :manual-size) (node-size parent parent-entries ec))
                    {:keys [width height]} (g/node-value resource-node :scene-dims ec)
                    reference-corners (if parent-size
                                        (rect-corners (g/node-value parent :pivot ec) parent-size)
                                        [[0.0 0.0] [(double width) 0.0] [(double width) (double height)] [0.0 (double height)]])
                    reference-transform (if parent-size (:parent-world-transform renderable) (doto (Matrix4d.) (.setIdentity)))
                    position (get overrides :position (g/node-value id :rect-position ec))]
                (when (and transform (> (Math/abs (.determinant ^Matrix4d transform)) 1.0e-12))
                  {:id id :layout (:current-layout (g/node-value id :trivial-gui-scene-info ec))
                   :size size :pivot-value pivot :transform transform :camera camera :viewport viewport
                   :position position
                   :rotation (g/node-value id :rotation ec) :scale (g/node-value id :scale ec)
                   :size-mode (get-in entries [:size-mode :value])
                   :corners (mapv #(project-point camera viewport transform %) corners)
                   :pivot (project-point camera viewport transform [0.0 0.0])
                   :parent-corners (mapv #(project-point camera viewport reference-transform %) reference-corners)
                   :guides (when (or parent-size (not parent))
                             (let [[x y] position
                                   [left bottom] (first reference-corners)
                                   from (project-point camera viewport transform [0.0 0.0])]
                               [{:from from :to (project-point camera viewport reference-transform [left y])
                                 :label (format "X %.1f" (- (double x) (double left)))}
                                {:from from :to (project-point camera viewport reference-transform [x bottom])
                                 :label (format "Y %.1f" (- (double y) (double bottom)))}]))
                   :anchor (let [x-anchor (g/node-value id :x-anchor ec)
                                 y-anchor (g/node-value id :y-anchor ec)]
                             (when (or (not= :xanchor-none x-anchor) (not= :yanchor-none y-anchor))
                               (project-point camera viewport (doto (Matrix4d.) (.setIdentity))
                                              [(case x-anchor :xanchor-left 0.0 :xanchor-right (double width) (/ (double width) 2.0))
                                               (case y-anchor :yanchor-bottom 0.0 :yanchor-top (double height) (/ (double height) 2.0))])))
                   :handles (mapv (fn [[hx hy]]
                                    (let [[left bottom] (first corners)
                                          x (+ (double left) (* (/ (+ (double hx) 1.0) 2.0) (double (first size))))
                                          y (+ (double bottom) (* (/ (+ (double hy) 1.0) 2.0) (double (second size))))]
                                      {:direction [hx hy] :point (project-point camera viewport transform [x y])}))
                                  [[-1 -1] [0 -1] [1 -1] [1 0] [1 1] [0 1] [-1 1] [-1 0]])})))))))))

(defn attach! [^Parent scene-content view-id resource-node {:keys [app-view ^Tab tab]}]
  (let [^Pane pane (.lookup scene-content "#scene-view-anchor-pane")
        canvas (doto (Canvas.) (.setId "gui-layout-overlay") (.setManaged false) (.setMouseTransparent true))
        controller (g/node-value view-id :tool-controller-id)
        state (atom {:data nil :drag nil})
        clear! (fn []
                 (when (:drag @state)
                   (swap! state assoc :drag nil)
                   (g/transact {:undoable false} (g/set-property controller :preview-overrides nil))))
        focus-listener (reify ChangeListener
                         (changed [_ _ _ focused]
                           (when-not focused (clear!))))
        refresh! (fn []
                   (let [window (some-> pane .getScene .getWindow)
                         ^Window previous (:window @state)]
                     (when-not (identical? previous window)
                       (when previous (.removeListener (.focusedProperty previous) focus-listener))
                       (when window (.addListener (.focusedProperty window) focus-listener))
                       (swap! state assoc :window window)))
                   (let [data (selection-data view-id resource-node app-view)
                         size [(.getWidth pane) (.getHeight pane)]]
                     (when (and (:drag @state)
                                (not= (select-keys data [:id :layout]) (select-keys (:drag @state) [:id :layout])))
                       (clear!))
                     (when (not= [data size] [(:data @state) (:canvas-size @state)])
                       (swap! state assoc :data data :canvas-size size)
                       (.setWidth canvas (first size))
                       (.setHeight canvas (second size))
                       (draw! canvas data))))
        mouse-point (fn [^MouseEvent event]
                      (let [point (.sceneToLocal pane (.getSceneX event) (.getSceneY event))]
                        [(.getX point) (.getY point)]))
        pressed (ui/event-handler event
                  (when (and (= MouseButton/PRIMARY (.getButton ^MouseEvent event))
                             (not (.isAltDown ^MouseEvent event))
                             (not (control-target? (.getTarget ^MouseEvent event) pane)))
                    (let [data (:data @state)
                          [x y :as point] (mouse-point event)
                          local (when data (unproject-point (:camera data) (:viewport data) (:transform data) point))
                          [px py] (:pivot data)
                          pivot-hit (and data (.isControlDown ^MouseEvent event)
                                         (< (Math/abs (- (double x) (double px))) 10.0)
                                         (< (Math/abs (- (double y) (double py))) 10.0))
                          handle (coll/first-where
                                   (fn [{[hx hy] :point}]
                                     (and (< (Math/abs (- (double x) (double hx))) 8.0)
                                          (< (Math/abs (- (double y) (double hy))) 8.0)))
                                   (:handles data))
                          kind (cond pivot-hit :pivot handle :resize
                                     (and data (inside-rect? (rect-corners (:pivot-value data) (:size data)) local)) :move)]
                      (when kind
                        (.requestFocus pane)
                        (swap! state assoc :drag (assoc data :kind kind :handle (:direction handle) :start local))
                        (.consume ^MouseEvent event)))))
        dragged (ui/event-handler event
                  (when-let [{:keys [id camera viewport transform start handle pivot-value size position rotation scale size-mode kind] :as drag} (:drag @state)]
                    (let [point (unproject-point camera viewport transform (mouse-point event))
                          ec (g/make-evaluation-context)
                          changes
                          (case kind
                            :pivot
                            (let [[left bottom] (first (rect-corners pivot-value size))
                                  [width height] size
                                  normalized [(/ (- (double (first point)) (double left)) (max 1.0 (double width)))
                                              (/ (- (double (second point)) (double bottom)) (max 1.0 (double height)))]]
                              (pivot-changes id
                                             (key (apply min-key
                                                         (fn [[_ coords]]
                                                           (reduce + 0.0 (mapv (fn [a b] (let [d (- (double a) (double b))] (* d d))) coords normalized)))
                                                         pivot-points))
                                             true ec))

                            :move
                            (let [[dx dy] (mapv - point start)
                                  delta (if (.isShiftDown ^MouseEvent event)
                                          (if (> (Math/abs (double dx)) (Math/abs (double dy))) [dx 0.0 0.0] [0.0 dy 0.0])
                                          [dx dy 0.0])]
                              [[id :position (mapv + position (local-delta->parent rotation scale delta))]])

                            :resize
                            (let [{:keys [size delta]} (resize-rect pivot-value size handle (mapv - point start) (.isShiftDown ^MouseEvent event))]
                              (into (cond-> []
                                      size-mode (conj [id :size-mode :size-mode-manual])
                                      true (conj [id :manual-size size]
                                                 [id :position (mapv + position (local-delta->parent rotation scale delta))]))
                                    (child-compensation id delta ec))))
                          changes (cond-> changes
                                    (g/node-value id :rect-transform ec)
                                    (conj [id :rect-reference (g/node-value id :rect-parent ec)]))
                          overrides (reduce (fn [result [node property value]]
                                              (-> result
                                                  (assoc-in [node :layout] (:layout drag))
                                                  (assoc-in [node :graph-properties property] value)))
                                            {} changes)]
                      (swap! state assoc :drag (assoc drag :changes changes))
                      (g/transact {:undoable false} (g/set-property controller :preview-overrides overrides))
                      (refresh!)
                      (.consume ^MouseEvent event))))
        released (ui/event-handler event
                   (when-let [drag (:drag @state)]
                     (try
                       (commit! (:changes drag) (localization/message "gui-layout.operation"))
                       (finally (clear!)))
                     (refresh!)
                     (.consume ^MouseEvent event)))
        key-pressed (ui/event-handler event
                      (when (and (:drag @state) (= KeyCode/ESCAPE (.getCode ^KeyEvent event)))
                        (clear!) (refresh!) (.consume ^KeyEvent event)))
        tab-listener (reify ChangeListener
                       (changed [_ _ _ selected]
                         (when-not selected
                           (clear!)
                           (when (= :gui-rect (g/node-value app-view :active-tool))
                             (g/transact {:undoable false} (g/set-property app-view :active-tool :move))))))]
    (.add (.getChildren pane) canvas)
    (.addEventFilter pane MouseEvent/MOUSE_PRESSED pressed)
    (.addEventFilter pane MouseEvent/MOUSE_DRAGGED dragged)
    (.addEventFilter pane MouseEvent/MOUSE_RELEASED released)
    (.addEventFilter pane KeyEvent/KEY_PRESSED key-pressed)
    (when tab (.addListener (.selectedProperty tab) tab-listener))
    {:refresh! refresh!
     :state state
     :dispose! (fn []
                 (clear!)
                 (when tab (.removeListener (.selectedProperty tab) tab-listener))
                 (when-let [^Window window (:window @state)]
                   (.removeListener (.focusedProperty window) focus-listener))
                 (.removeEventFilter pane MouseEvent/MOUSE_PRESSED pressed)
                 (.removeEventFilter pane MouseEvent/MOUSE_DRAGGED dragged)
                 (.removeEventFilter pane MouseEvent/MOUSE_RELEASED released)
                 (.removeEventFilter pane KeyEvent/KEY_PRESSED key-pressed)
                 (.remove (.getChildren pane) canvas))}))
