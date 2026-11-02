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


(ns editor.ai-canvas
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [util.coll :as coll])
  (:import [javafx.css PseudoClass]
           [javafx.event EventHandler]
           [javafx.geometry Insets Pos Side]
           [javafx.scene Cursor Group Scene]
           [javafx.scene.canvas Canvas]
           [javafx.beans.value ChangeListener]
           [javafx.scene.control Button ButtonBase ContextMenu Label MenuItem ScrollPane TextInputControl Tooltip]
           [javafx.scene.image Image ImageView]
           [javafx.scene.input ContextMenuEvent KeyCode KeyEvent MouseButton MouseEvent ScrollEvent]
           [javafx.scene.layout BorderPane HBox Pane Priority Region StackPane TilePane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape CubicCurve Rectangle]
           [javafx.scene.transform Scale]
           [javafx.stage Stage]))

(set! *warn-on-reflection* true)

(def colors {:action "#5B8CFF" :result "#AD7BFF" :assets "#48C78E"})

(defn initial-canvas []
  {:version 1 :selected "action-1" :viewport {:x 36.0 :y 44.0 :zoom 0.85}
   :nodes [{:id "action-1" :kind :action :title "设计目标" :x 0.0 :y 0.0}
           {:id "result-1" :kind :result :title "效果图候选" :x 310.0 :y 0.0 :candidates []}
           {:id "assets-1" :kind :assets :title "组件资源" :x 620.0 :y 0.0 :assets []}]
   :edges [{:from "action-1" :to "result-1"} {:from "result-1" :to "assets-1"}]})

(defn node-by-id [canvas id]
  (coll/first-where #(= id (:id %)) (:nodes canvas)))

(defn update-node [canvas id f]
  (update canvas :nodes (fn update-nodes [nodes] (mapv #(if (= id (:id %)) (f %) %) nodes))))

(defn change-state [state f]
  (let [before (:canvas state)
        after (f before)
        history (vec (:canvas-undo state))]
    (cond-> (assoc state :canvas after)
      (not= (select-keys before [:nodes :edges]) (select-keys after [:nodes :edges]))
      (assoc :canvas-undo (conj (subvec history (max 0 (- (count history) 99))) [before after]) :canvas-redo []))))

(defn history-state [state direction]
  (let [source (if (= :undo direction) :canvas-undo :canvas-redo)
        target (if (= :undo direction) :canvas-redo :canvas-undo)]
    (if-let [[before after :as entry] (peek (get state source))]
      (let [[from to] (if (= :undo direction) [after before] [before after])
            current (:canvas state)
            old (coll/into-> (:nodes from) {} (map (juxt :id identity)))
            desired (coll/into-> (:nodes to) {} (map (juxt :id identity)))
            live (coll/into-> (:nodes current) {} (map (juxt :id identity)))
            ids (coll/into-> (into (mapv :id (:nodes to)) (mapv :id (:nodes current))) [] (distinct))
            nodes (coll/into-> ids []
                    (keep (fn restore-node [id]
                            (let [a (get old id) b (get desired id) c (get live id)]
                              (cond
                                (and a (nil? b)) nil
                                (and b (nil? a)) b
                                (and a b c)
                                (reduce (fn restore-field [node key]
                                          (if (= (find a key) (find b key))
                                            node
                                            (if (contains? b key) (assoc node key (get b key)) (dissoc node key))))
                                        c (set/union (set (coll/keys a)) (set (coll/keys b))))
                                :else c)))))
            removed-edges (set/difference (set (:edges from)) (set (:edges to)))
            added-edges (set/difference (set (:edges to)) (set (:edges from)))
            edges (into (filterv #(not (contains? removed-edges %)) (:edges current)) added-edges)
            selected (if (= (:selected current) (:selected from)) (:selected to) (:selected current))
            selected (if (coll/any? #(= selected (:id %)) nodes) selected (:id (first nodes)))]
        (-> state
            (assoc :canvas (assoc current :nodes nodes :edges edges :selected selected))
            (update source pop)
            (update target (fn append-history [entries] (conj (vec entries) entry)))))
      state)))

(defn connect-nodes [canvas from to]
  (let [source (node-by-id canvas from) target (node-by-id canvas to)]
    (when-not (contains? #{[:action :result] [:result :assets]} [(:kind source) (:kind target)])
      (throw (ex-info "连线顺序为 Action → Result → Assets" {})))
    ;; A result/asset has exactly one upstream source, keeping provenance clear.
    (when (coll/any? #(and (= to (:to %)) (not= from (:from %))) (:edges canvas))
      (throw (ex-info "目标卡片已连接上游，请使用新的流程卡片" {})))
    (when (coll/any? #(and (= from (:from %)) (not= to (:to %))) (:edges canvas))
      (throw (ex-info "当前阶段只支持线性流程，一张卡片连接一个下游" {})))
    (update canvas :edges #(conj (coll/into-> % [] (remove (fn [edge] (= edge {:from from :to to})))) {:from from :to to}))))

(defn zoom-about [viewport factor x y]
  (let [old (:zoom viewport) zoom (max 0.25 (min 1.8 (* old factor))) ratio (/ zoom old)]
    {:zoom zoom :x (- x (* (- x (:x viewport)) ratio)) :y (- y (* (- y (:y viewport)) ratio))}))

(defn- next-flow-number [canvas]
  (reduce max (or (:next-flow-id canvas) 1)
          (mapv (fn flow-number [node]
                  (if-let [[_ number] (re-matches #"(?:action|result|assets)-(\d+)" (:id node))]
                    (inc (Long/parseLong number))
                    1)) (:nodes canvas))))

(defn remove-node [canvas id]
  (let [nodes (filterv #(not= id (:id %)) (:nodes canvas))]
    (-> canvas
        (assoc :nodes nodes :next-flow-id (next-flow-number canvas))
        (update :edges (fn remove-edges [edges]
                         (filterv #(not (or (= id (:from %)) (= id (:to %)))) edges)))
        (cond-> (= id (:selected canvas)) (assoc :selected (:id (first nodes)))))))

(defn add-flow [canvas]
  (let [n (next-flow-number canvas)
        a (if (= :document (:workflow canvas)) (str "card_" (java.util.UUID/randomUUID)) (str "action-" n))
        r (if (= :document (:workflow canvas)) (str "card_" (java.util.UUID/randomUUID)) (str "result-" n))
        s (if (= :document (:workflow canvas)) (str "card_" (java.util.UUID/randomUUID)) (str "assets-" n))
        y (+ 500.0 (reduce max 0.0 (mapv :y (:nodes canvas))))]
    (-> canvas
        (update :nodes into [{:id a :kind :action :title (str "设计目标 " n) :x 0.0 :y y}
                             {:id r :kind :result :title (if (= :document (:workflow canvas)) "方案候选" "效果图候选") :x 310.0 :y y :candidates []}
                             {:id s :kind :assets :title (if (= :document (:workflow canvas)) "方案文档" "组件资源") :x 620.0 :y y :assets []}])
        (update :edges into [{:from a :to r} {:from r :to s}])
        (assoc :next-flow-id (inc n) :selected a :viewport {:x 36.0 :y (- 44.0 (* y 0.85)) :zoom 0.85}))))

(defn merge-candidates [canvas source-id candidates]
  (let [source (node-by-id canvas source-id)
        result-id (if (= :result (:kind source)) source-id
                      (:to (coll/first-where #(= source-id (:from %)) (:edges canvas))))]
    (if-not (= :result (:kind (node-by-id canvas result-id)))
      canvas
      (update-node canvas result-id
                   (fn merge-result [node]
                     (update node :candidates
                             (fn merge-versions [old]
                               (reduce (fn merge-candidate [all candidate]
                                         (if (coll/any? #(= (:id %) (:id candidate)) all)
                                           (mapv #(if (= (:id %) (:id candidate)) (merge % candidate) %) all)
                                           (conj all candidate)))
                                       (vec old) candidates))))))))

(defn adopt-candidate [canvas result-id]
  (let [result (node-by-id canvas result-id)
        chosen (coll/first-where #(= (:chosen result) (:id %)) (:candidates result))
        target (:to (coll/first-where #(= result-id (:from %)) (:edges canvas)))]
    (when-not (and (if (= :document (:workflow canvas)) (not (string/blank? (:text chosen))) (:image-path chosen)) (= :assets (:kind (node-by-id canvas target))))
      (throw (ex-info "先选择候选，并连接 Assets 卡片" {})))
    (-> canvas
        (update-node target #(assoc (dissoc % :production :production-plan :production-source-key :document-output) :assets (if (and (not= :document (:workflow canvas)) (coll/not-empty (:files chosen)))
                                                                                                                              (mapv (fn [file] (cond-> file
                                                                                                                                                 (and (:preview chosen) (= (:path file) (:image-path chosen)))
                                                                                                                                                 (assoc :preview (:preview chosen)))) (:files chosen))
                                                                                                                              [{:name "设计方案" :text (:text chosen)}])
                                    :source-result result-id :source-candidate (:id chosen)))
        (assoc :selected target))))

(defn snapshot-candidates [snapshot thread-id]
  (let [refs (get-in snapshot [:thread :referencedFiles])]
    (coll/into-> (get-in snapshot [:thread :turns]) []
      (filter #(= "completed" (:status %)))
      (mapcat (fn [turn]
                (let [items (filterv #(and (= "agentMessage" (:type %))
                                           (not (contains? #{"analysis" "commentary"} (:phase %)))) (:items turn))
                      text (coll/join-to-string "\n" (mapv :text items))
                      outputs (filterv #(= "imageGeneration" (:type %)) (:items turn))
                      files (coll/into-> refs []
                              (filter #(or (string/includes? text (:source %))
                                           (coll/any? (fn [item] (= (:source %) (:savedPath item))) outputs)))
                              (map #(assoc % :name (last (string/split (:source %) #"[/\\]")))))
                      images (filterv #(re-find #"(?i)\.(png|jpe?g|gif|bmp)$" (:name %)) files)
                      linked-images (filterv #(string/includes? text (:source %)) images)
                      images (if (coll/not-empty linked-images) linked-images images)
                      prefix (str thread-id "/" (:id turn))]
                  (cond
                    (coll/not-empty images) (mapv (fn [file] {:id (str prefix "/" (:path file)) :text text :files [file] :image-path (:path file)}) images)
                    (not (string/blank? text)) [{:id prefix :text text :files files}]
                    :else [])))))))

(defn- button
  ^Button [text id action]
  (doto (Button. text)
    (.setId id)
    (-> .getStyleClass (.add "button-small"))
    (.setOnAction (reify EventHandler (handle [_ _] (action))))))

(defn- label
  ^Label [text]
  (doto (Label. text) (.setWrapText true) (.setMaxWidth 242.0)))

(defn- primary-button
  ^Button [text id action]
  (doto (button text id action) (-> .getStyleClass (.add "ai-primary")) (.setMaxWidth Double/MAX_VALUE)))

(defonce ^:private preview-cache (atom {}))

(defn- image-preview
  ^ImageView [^String url ^double width ^double height]
  (let [modified (when (string/starts-with? url "file:")
                   (.lastModified (io/file (java.net.URI. url))))
        key [url width height modified]
        cached (get @preview-cache key)
        image (or (when cached (.get ^java.lang.ref.SoftReference cached))
                  (let [image (Image. url (* 2.0 width) (* 2.0 height) true true true)]
                    (swap! preview-cache (fn cache-preview [cache]
                                           (assoc (if (< (count cache) 128) cache {}) key (java.lang.ref.SoftReference. image))))
                    image))]
    (doto (ImageView. ^Image image)
      (.setFitWidth width) (.setFitHeight height) (.setPreserveRatio true))))

(defn focus-viewport [x y card-width card-height viewport-width viewport-height]
  (let [width (max 1.0 (- viewport-width 64.0))
        height (max 1.0 (- viewport-height 64.0))
        zoom (min 1.8 (/ width (max 1.0 card-width)) (/ height (max 1.0 card-height)))]
    {:zoom zoom
     :x (- (/ viewport-width 2.0) (* (+ x (/ card-width 2.0)) zoom))
     :y (- (/ viewport-height 2.0) (* (+ y (/ card-height 2.0)) zoom))}))

(defn card-content-key [state]
  [(mapv #(dissoc % :chosen) (get-in state [:canvas :nodes]))
   (coll/into-> (:sessions state) {} (map (fn [[id session]] [id (:attachments session)])))
   (get-in state [:context :path])])

(defn- empty-card [icon text]
  (let [box (VBox. 10.0)]
    (.add (.getStyleClass box) "ai-empty")
    (.addAll (.getChildren box) ^java.util.Collection
             (identity [(doto (label icon) (.setStyle "-fx-font-size: 32; -fx-text-fill: -ai-accent;")) (label text)]))
    box))

(defn- candidate-label [index]
  (if (< -1 index 26) (str (char (+ 65 index))) (str (inc index))))

(defn- compare-candidates! [owner candidates]
  (let [row (HBox. 12.0)
        scroll (ScrollPane. row)
        stage (Stage.)
        scene (Scene. scroll 920.0 620.0)]
    (.setStyle row "-fx-padding: 16; -fx-background-color: -df-background;")
    (doseq [[index candidate] (eduction (map-indexed vector) candidates)]
      (let [column (VBox. 8.0)]
        (.setPrefWidth column 280.0)
        (.add (.getChildren column) (label (str "候选 " (candidate-label index))))
        (when-let [preview (:preview candidate)] (.add (.getChildren column) (image-preview (str preview) 280.0 480.0)))
        (.add (.getChildren column) (label (:text candidate)))
        (.add (.getChildren row) column)))
    (.add (.getStylesheets scene) (str (io/resource "editor.css")))
    (.initOwner stage owner) (.setTitle stage "候选比较") (.setScene stage scene) (.show stage)))

(defn make-details [state choose! open-output!]
  (let [content (VBox. 12.0)
        scroll (ScrollPane. content)
        rendered (atom nil)
        section! (fn add-section [title]
                   (let [section (VBox. 8.0)]
                     (.add (.getStyleClass section) "ai-detail-section")
                     (.add (.getChildren section) (doto (label title) (-> .getStyleClass (.add "ai-card-kind"))))
                     (.add (.getChildren content) section)
                     section))
        text! (fn add-text [^VBox section text]
                (.add (.getChildren section) (doto (label (str text)) (.setMinWidth 0.0) (.setMaxWidth Double/MAX_VALUE))))
        image! (fn add-image [^VBox section preview]
                 (when preview
                   (let [^ImageView view (image-preview (str preview) 320.0 360.0)]
                     (.bind (.fitWidthProperty view) (.subtract (.widthProperty content) 28.0))
                     (.add (.getChildren section) view))))
        refresh! (fn refresh-details []
                   (let [s @state
                         card (node-by-id (:canvas s) (get-in s [:canvas :selected]))
                         session (get-in s [:sessions (:id card)])
                         files (or (coll/not-empty (:attachments session)) (:references card))
                         key [(dissoc card :x :y) files (:context s)]]
                     (when-not (= key @rendered)
                       (reset! rendered key)
                       (.clear (.getChildren content))
                       (.setVvalue scroll 0.0)
                       (.setStyle content (str "-ai-accent: " (colors (:kind card)) ";"))
                       (case (:kind card)
                         :action
                         (do
                           (text! (section! "设计目标") (or (:prompt card) "尚未填写目标，可在下方与 AI 对话。"))
                           (let [binding (section! (if (= :document (get-in s [:canvas :workflow])) "Canvas 文档" "GUI 上下文"))]
                             (text! binding (get-in s [:context :path]))
                             (when (coll/not-empty (get-in s [:context :selectedNodeIds]))
                               (text! binding (str "选中节点：" (coll/join-to-string "、" (get-in s [:context :selectedNodeIds]))))))
                           (let [^VBox references (section! (str "图片与文档 · " (count files)))]
                             (if (coll/not-empty files)
                               (doseq [file files]
                                 (let [item (VBox. 6.0)]
                                   (when (and (:path file) (re-find #"(?i)\.(png|jpe?g|gif|bmp)$" (or (:name file) "")))
                                     (image! item (str (.toURI (io/file (:path file))))))
                                   (text! item (:name file))
                                   (text! item (:path file))
                                   (.add (.getChildren references) item)))
                               (text! references "暂无附件"))))
                         :result
                         (if (coll/not-empty (:candidates card))
                           (doseq [[index candidate] (eduction (map-indexed vector) (:candidates card))]
                             (let [selected (= (:chosen card) (:id candidate))
                                   ^VBox section (section! (str "候选 " (candidate-label index) (when selected " · 已选")))]
                               (.add (.getStyleClass section) "ai-candidate")
                               (.pseudoClassStateChanged section (PseudoClass/getPseudoClass "selected") selected)
                               (image! section (:preview candidate))
                               (when-not (string/blank? (:text candidate)) (text! section (:text candidate)))
                               (doseq [file (:files candidate)] (text! section (or (:name file) (:source file) (:path file))))
                               (.add (.getChildren section)
                                     (button (if selected "✓ 当前候选" "选择此候选") (str "ai-detail-choose-" index)
                                             (fn choose-detail [] (choose! (:id card) (:id candidate)))))))
                           (text! (section! (:title card)) "等待 AI 返回候选。"))
                         :assets
                         (do
                           (let [source (section! "采用的资源")]
                             (if (coll/not-empty (:assets card))
                               (doseq [asset (:assets card)]
                                 (image! source (:preview asset))
                                 (text! source (:name asset))
                                 (when-let [text (:text asset)] (text! source text))
                                 (when-let [path (or (:source asset) (:path asset))] (text! source path)))
                               (text! source "先从 Result 卡片采用候选。")))
                           (when-let [path (:document-output card)]
                             (text! (section! "归档文档") path))
                           (when-let [plan (:production-plan card)]
                             (let [regions (section! (str "拆分计划 · " (count (:regions plan)) " 个组件"))]
                               (doseq [region (:regions plan)]
                                 (text! regions (str (or (:name region) (:id region)) "  " (:x region) ", " (:y region) "  ·  " (:width region) " × " (:height region))))))
                           (when-let [output (:production card)]
                             (let [^VBox delivery (section! "Defold 成品")]
                               (text! delivery (str (:count output) " 个组件 · 已归档"))
                               (doseq [[kind title] [[:gui "打开 GUI"] [:atlas "打开 Atlas"]]]
                                 (when-let [path (get output kind)] (text! delivery path))
                                 (.add (.getChildren delivery) (button title (str "ai-detail-open-" (name kind)) #(open-output! output kind)))))))
                         nil))))]
    (.setId scroll "ai-card-details")
    (.setFitToWidth scroll true)
    (.setMinHeight scroll 100.0)
    (.setHbarPolicy scroll javafx.scene.control.ScrollPane$ScrollBarPolicy/NEVER)
    (.add (.getStyleClass scroll) "ai-details")
    (.setStyle content "-fx-padding: 8;")
    {:root scroll :refresh! refresh!}))

(defn make-canvas [state change! select! focus! report! produce! open-output! attach! generate!]
  (let [root (BorderPane.) viewport (Pane.) world (Group.) grid (Canvas.) grid-paint (Rectangle.) links (Group.) cards (Group.)
        scale (Scale. 1.0 1.0 0.0 0.0) clip (Rectangle.)
        toolbar (HBox. 8.0) zoom-label (Label.) dock (VBox. 6.0) surface (StackPane.)
        connecting (atom nil) panning (atom nil) scroll-values (atom {}) rendered-content (atom nil)
        undo (Button. "撤销") redo (Button. "重做")
        draw-grid! (fn draw-grid []
                     (let [gc (.getGraphicsContext2D grid)
                           {:keys [x y zoom]} (get-in @state [:canvas :viewport])
                           spacing (* 24.0 zoom)]
                       (.clearRect gc 0.0 0.0 (.getWidth grid) (.getHeight grid))
                       (.setFill gc (.getFill grid-paint))
                       (doseq [column (range (mod x spacing) (.getWidth grid) spacing)
                               row (range (mod y spacing) (.getHeight grid) spacing)]
                         (.fillOval gc (double column) (double row) 1.0 1.0))))
        apply-viewport! (fn apply-viewport []
                          (let [{:keys [x y zoom]} (get-in @state [:canvas :viewport])]
                            (.setTranslateX world (double x)) (.setTranslateY world (double y))
                            (.setX scale (double zoom)) (.setY scale (double zoom))
                            (.setText zoom-label (str (Math/round (* 100.0 (double zoom))) "%")) (draw-grid!)))
        zoom! (fn zoom-canvas [factor x y]
                (change! #(update % :viewport zoom-about factor x y)) (apply-viewport!))
        fit! (fn fit-canvas []
               (let [nodes (get-in @state [:canvas :nodes])
                     left (reduce min 0.0 (mapv :x nodes)) top (reduce min 0.0 (mapv :y nodes))
                     width (- (+ 280.0 (reduce max 0.0 (mapv :x nodes))) left)
                     height (- (+ 470.0 (reduce max 0.0 (mapv :y nodes))) top)
                     zoom (max 0.25 (min 1.0 (/ (max 100.0 (- (.getWidth viewport) 60.0)) width)
                                         (/ (max 100.0 (- (.getHeight viewport) 60.0)) height)))]
                 (change! #(assoc % :viewport {:x (- 30.0 (* left zoom)) :y (- 30.0 (* top zoom)) :zoom zoom})))
               (apply-viewport!))
        focus-node! (fn focus-node [id]
                      (.applyCss root)
                      (.layout root)
                      (when-let [^VBox card (.lookup cards (str "#canvas-card-" id))]
                        (let [model (node-by-id (:canvas @state) id)
                              height (- (.getHeight viewport) (if (.isVisible dock) (+ 32.0 (.getHeight dock)) 0.0))]
                          (change! #(assoc % :viewport (focus-viewport (:x model) (:y model) (.getWidth card) (.getHeight card) (.getWidth viewport) height)))
                          (apply-viewport!))))
        draw-links! (fn draw-links []
                      (.setDisable undo (not (coll/not-empty (:canvas-undo @state))))
                      (.setDisable redo (not (coll/not-empty (:canvas-redo @state))))
                      (.clear (.getChildren links))
                      (let [canvas (:canvas @state)]
                        (doseq [{:keys [from to]} (:edges canvas)]
                          (let [a (node-by-id canvas from) b (node-by-id canvas to)
                                x1 (+ (:x a) 268.0) y1 (+ (:y a) 225.0) x2 (:x b) y2 (+ (:y b) 225.0)
                                line (doto (CubicCurve. x1 y1 (+ x1 55.0) y1 (- x2 55.0) y2 x2 y2)
                                       (.setStroke (Color/web (colors (:kind a)))) (.setStrokeWidth 2.0) (.setFill nil))]
                            (.setOnMouseClicked line
                                                (reify EventHandler
                                                  (handle [_ event]
                                                    (when (= 2 (.getClickCount ^MouseEvent event))
                                                      (change! #(update % :edges (fn [edges] (filterv (fn [edge] (not= edge {:from from :to to})) edges))))
                                                      (.remove (.getChildren links) line)
                                                      (.setDisable undo false) (.setDisable redo true)
                                                      (report! "连线已移除，点击圆点可重新连接")))))
                            (.add (.getChildren links) line)))))
        render! (fn render-canvas []
                  (let [key (card-content-key @state)]
                    (when-not (= key @rendered-content)
                      (reset! rendered-content key)
                      (doseq [^VBox old-card (.getChildren cards)]
                        (swap! scroll-values assoc (.getUserData old-card)
                               (.getVvalue ^ScrollPane (.get (.getChildren old-card) 1))))
                      (.clear (.getChildren cards))
                      (let [canvas (:canvas @state)]
                        (doseq [{:keys [id kind title x y candidates chosen assets] :as model} (:nodes canvas)]
                          (let [selected (= id (:selected canvas)) color (colors kind)
                                card (VBox. 10.0) header (HBox. 8.0)
                                kind-label (label ({:action "✦  Action" :result "▧  Result" :assets "◇  Assets"} kind))
                                title-label (label title)
                                spacer (Region.)
                                more (Button. "···")
                                menu (ContextMenu.)
                                discuss (MenuItem. "与这张卡片对话")
                                delete (MenuItem. "删除卡片")
                                content (VBox. 8.0) scroll (ScrollPane. content) drag (atom nil)
                                port-in (button "" (str "canvas-in-" id)
                                                (fn connect-input []
                                                  (when-let [source @connecting]
                                                    (try (change! #(connect-nodes % source id)) (draw-links!)
                                                         (catch Exception e (report! (.getMessage e))))
                                                    (reset! connecting nil))))
                                port-out (button "" (str "canvas-out-" id)
                                                 (fn connect-output [] (reset! connecting id) (report! "点击下一张卡片的输入圆点")))]
                            (.setId card (str "canvas-card-" id))
                            (.setUserData card id)
                            (.add (.getStyleClass card) "ai-card")
                            (.add (.getStyleClass card) (str "ai-" (name kind)))
                            (.pseudoClassStateChanged card (PseudoClass/getPseudoClass "selected") selected)
                            (.setStyle card (str "-ai-accent: " color ";"))
                            (.setPrefWidth card 268.0) (.setMinWidth card 268.0) (.setMaxWidth card 268.0)
                            (.setLayoutX card (double x)) (.setLayoutY card (double y))
                            (.add (.getStyleClass kind-label) "ai-card-kind")
                            (.add (.getStyleClass title-label) "ai-card-title")
                            (.add (.getStyleClass more) "ai-icon-button")
                            (.setAccessibleText more "卡片操作")
                            (.setId delete (str "canvas-delete-" id))
                            (.addAll (.getItems menu) ^java.util.Collection (identity [discuss delete]))
                            (.setOnAction delete
                                          (reify EventHandler
                                            (handle [_ _]
                                              (if (or (contains? (:busy @state) id) (get-in @state [:sessions id :pending]))
                                                (report! "这张卡片的任务尚未结束，请完成后再删除")
                                                (do
                                                  (when (= id @connecting) (reset! connecting nil))
                                                  (change! #(remove-node % id))
                                                  (select! (get-in @state [:canvas :selected])))))))
                            (.setOnContextMenuRequested card
                                                        (reify EventHandler
                                                          (handle [_ event]
                                                            (let [e ^ContextMenuEvent event]
                                                              (.show menu card (.getScreenX e) (.getScreenY e))
                                                              (.consume e)))))
                            (.setOnAction more (reify EventHandler (handle [_ _] (.show menu more Side/BOTTOM 0.0 0.0))))
                            (.setOnMouseClicked more (reify EventHandler (handle [_ e] (.consume ^MouseEvent e))))
                            (.setOnAction discuss (reify EventHandler (handle [_ _] (select! id) (focus!))))
                            (HBox/setHgrow spacer Priority/ALWAYS)
                            (.setId header (str "canvas-drag-" id))
                            (.addAll (.getChildren header) ^java.util.Collection (identity [kind-label spacer more]))
                            (doseq [[port px hidden] [[port-in -8.0 (= kind :action)] [port-out 260.0 (= kind :assets)]]]
                              (.setManaged ^Button port false)
                              (.resize ^Button port 16.0 16.0)
                              (.setVisible ^Button port (not hidden))
                              (.setLayoutX ^Button port px) (.setLayoutY ^Button port 217.0)
                              (.add (.getStyleClass ^Button port) "ai-port")
                              (.setTooltip ^Button port (Tooltip. (if (= port port-in) "连接上游卡片" "连接下游卡片"))))
                            (.addEventFilter card MouseEvent/MOUSE_CLICKED
                              (reify EventHandler
                                (handle [_ event]
                                  (let [e ^MouseEvent event]
                                    (when (and (= MouseButton/PRIMARY (.getButton e)) (= 2 (.getClickCount e))
                                               (loop [target (.getTarget e)]
                                                 (cond (instance? ButtonBase target) false
                                                       (or (= target card) (nil? target)) true
                                                       :else (recur (.getParent ^javafx.scene.Node target)))))
                                      (select! id) (focus-node! id) (.consume e))))))
                            (.setOnMouseClicked card (reify EventHandler (handle [_ event]
                                                                           (when (= MouseButton/PRIMARY (.getButton ^MouseEvent event)) (.requestFocus viewport) (select! id))
                                                                           (.consume ^MouseEvent event))))
                            (.setOnMousePressed header
                                                (reify EventHandler
                                                  (handle [_ event]
                                                    (let [e ^MouseEvent event]
                                                      (when (and (= MouseButton/PRIMARY (.getButton e))
                                                                 (not (.contains more (.sceneToLocal more (.getSceneX e) (.getSceneY e)))))
                                                        (reset! drag {:x (.getSceneX e) :y (.getSceneY e) :left x :top y})
                                                        (.consume e))))))
                            (.setOnMouseDragged header
                                                (reify EventHandler
                                                  (handle [_ event]
                                                    (when-let [start @drag]
                                                      (let [e ^MouseEvent event z (get-in @state [:canvas :viewport :zoom])
                                                            nx (+ (:left start) (/ (- (.getSceneX e) (:x start)) z))
                                                            ny (+ (:top start) (/ (- (.getSceneY e) (:y start)) z))]
                                                        (swap! state update :canvas update-node id #(assoc % :x nx :y ny))
                                                        (.setLayoutX card nx) (.setLayoutY card ny) (draw-links!) (.consume e))))))
                            (.setOnMouseReleased header
                                                 (reify EventHandler
                                                   (handle [_ _]
                                                     (when-let [start @drag]
                                                       (let [position (select-keys (node-by-id (:canvas @state) id) [:x :y])]
                                                         (reset! drag nil)
                                                         (swap! state update :canvas update-node id #(assoc % :x (:left start) :y (:top start)))
                                                         (change! #(update-node % id (fn move-node [node] (merge node position))))
                                                         (select! id))))))
                            (.setVvalue scroll (double (get @scroll-values id 0.0)))
                            (.setFitToWidth scroll true) (.setPrefViewportHeight scroll 330.0)
                            (.setHbarPolicy scroll javafx.scene.control.ScrollPane$ScrollBarPolicy/NEVER)
                            (.addAll (.getChildren card) ^java.util.Collection (identity [header scroll]))
                            (.add (.getChildren content) title-label)
                            (case kind
                              :action
                              (let [files (or (coll/not-empty (get-in @state [:sessions id :attachments])) (:references model))
                                    prompt (label (or (:prompt model) (if (= :document (:workflow canvas)) "描述游戏定位、核心循环与设计目标" "描述你想生成的界面")))
                                    refs (TilePane. 8.0 8.0)]
                                (.add (.getStyleClass prompt) "ai-card-prompt")
                                (.add (.getChildren content) prompt)
                                (.add (.getChildren content) (doto (label (str "参考图与文档" (when (coll/not-empty files) (str " (" (count files) ")"))))
                                                               (-> .getStyleClass (.add "ai-muted"))))
                                (.setPrefColumns refs 2)
                                (doseq [file files]
                                  (let [tile (VBox. 4.0)]
                                    (.add (.getStyleClass tile) "ai-reference")
                                    (if (re-find #"(?i)\.(png|jpe?g|gif|bmp)$" (or (:name file) ""))
                                      (.add (.getChildren tile) (image-preview (str (.toURI (io/file (:path file)))) 100.0 90.0))
                                      (.add (.getChildren tile) (label "▤ 文档")))
                                    (.add (.getChildren tile) (doto (label (:name file)) (.setMaxWidth 100.0)))
                                    (.add (.getChildren refs) tile)))
                                (.add (.getChildren refs)
                                      (doto (button "+" (str "canvas-reference-" id) (fn add-reference [] (select! id) (attach!)))
                                        (.setPrefSize 48.0 48.0) (.setTooltip (Tooltip. "添加图片或文档"))))
                                (.add (.getChildren content) refs)
                                (Tooltip/install card (Tooltip. (str "绑定 " (get-in @state [:context :path]))))
                                (.add (.getChildren card)
                                      (primary-button (if (= :document (:workflow canvas)) "生成方案" "生成候选") (str "canvas-compose-" id)
                                                      (fn generate-candidates [] (select! id) (focus!) (generate!)))))
                              :result
                              (do
                                (if-not (coll/not-empty candidates)
                                  (.add (.getChildren content) (empty-card "▧" "等待生成候选"))
                                  (doseq [[index candidate] (eduction (map-indexed vector) candidates)]
                                    (let [option (VBox. 5.0)
                                          choose (button (str (candidate-label index) (when (= chosen (:id candidate)) "  ✓ 已选"))
                                                         (str "canvas-candidate-" id "-" index)
                                                         (fn choose-result []
                                                           (change! #(-> % (update-node id (fn [n] (assoc n :chosen (:id candidate)))) (assoc :selected id)))
                                                           (select! id)))]
                                      (.add (.getStyleClass option) "ai-candidate")
                                      (.pseudoClassStateChanged option (PseudoClass/getPseudoClass "selected") (= chosen (:id candidate)))
                                      (.add (.getChildren option) choose)
                                      (when-let [preview (:preview candidate)]
                                        (let [^ImageView view (image-preview (str preview) 202.0 112.0)]
                                          (.setOnMouseClicked view (reify EventHandler (handle [_ e] (when (= MouseButton/PRIMARY (.getButton ^MouseEvent e)) (.fire choose)) (.consume ^MouseEvent e))))
                                          (.add (.getChildren option) view)))
                                      (when-not (:preview candidate)
                                        (.add (.getChildren option) (label (subs (:text candidate) 0 (min 120 (count (:text candidate)))))))
                                      (.add (.getChildren content) option))))
                                (let [actions (HBox. 8.0)
                                      compare (button "比较" (str "canvas-compare-" id) #(compare-candidates! (.getWindow (.getScene root)) candidates))
                                      adopt (primary-button (if chosen (str "采用 " (candidate-label (.indexOf ^java.util.List (mapv :id candidates) chosen))) "采用候选")
                                                            (str "canvas-adopt-" id)
                                                            (fn adopt-result []
                                                              (try (change! #(adopt-candidate % id)) (select! (get-in @state [:canvas :selected]))
                                                                   (catch Exception e (report! (.getMessage e))))))]
                                  (.setDisable compare (< (count candidates) 2))
                                  (.setDisable adopt (nil? ((if (= :document (:workflow canvas)) :text :image-path) (coll/first-where #(= chosen (:id %)) candidates))))
                                  (HBox/setHgrow adopt Priority/ALWAYS)
                                  (.addAll (.getChildren actions) ^java.util.Collection (identity [compare adopt]))
                                  (.add (.getChildren card) actions)))
                              :assets
                              (do
                                (if-not (coll/not-empty assets)
                                  (.add (.getChildren content) (empty-card "◇" (if (= :document (:workflow canvas)) "采用方案后归档文档" "采用候选后生成组件")))
                                  (doseq [asset assets]
                                    (when-let [preview (:preview asset)]
                                      (.add (.getChildren content) (image-preview (str preview) 226.0 210.0)))
                                    (.add (.getChildren content) (label (str "◇ " (:name asset))))
                                    (when-let [text (:text asset)]
                                      (.add (.getChildren content) (label (subs text 0 (min 360 (count text))))))))
                                (when-let [path (:document-output model)]
                                  (.add (.getChildren content) (label (str "已归档 · " (last (string/split path #"/"))))))
                                (when-let [output (:production model)]
                                  (.add (.getChildren content) (label (str (:count output) " 个组件 · 已归档")))
                                  (.add (.getChildren content) (button "打开 GUI" (str "canvas-open-gui-" id) #(open-output! output :gui)))
                                  (.add (.getChildren content) (button "打开 Atlas" (str "canvas-open-atlas-" id) #(open-output! output :atlas))))
                                (.add (.getChildren card) (primary-button (if (= :document (:workflow canvas)) (if (:document-output model) "另存文档版本" "归档方案文档") "拆分 / 生成 GUI") (str "canvas-produce-" id)
                                                                          (fn produce-assets [] (select! id) (produce! id))))))
                            (.addAll (.getChildren card) ^java.util.Collection (identity [port-in port-out]))
                            (.add (.getChildren cards) card))))))
                  ;; Keep nodes while selecting or moving the camera.
                  (doseq [^VBox card (.getChildren cards)]
                    (let [id (.getUserData card)
                          model (node-by-id (:canvas @state) id)
                          chosen (:chosen model)]
                      (.pseudoClassStateChanged card (PseudoClass/getPseudoClass "selected") (= id (get-in @state [:canvas :selected])))
                      (when (= :result (:kind model))
                        (doseq [[index candidate] (eduction (map-indexed vector) (:candidates model))]
                          (when-let [^Button choose (.lookup card (str "#canvas-candidate-" id "-" index))]
                            (.setText choose (str (candidate-label index) (when (= chosen (:id candidate)) "  ✓ 已选")))
                            (.pseudoClassStateChanged (.getParent choose) (PseudoClass/getPseudoClass "selected") (= chosen (:id candidate)))))
                        (when-let [^Button adopt (.lookup card (str "#canvas-adopt-" id))]
                          (.setText adopt (if chosen (str "采用 " (candidate-label (.indexOf ^java.util.List (mapv :id (:candidates model)) chosen))) "采用候选"))
                          (.setDisable adopt (nil? ((if (= :document (get-in @state [:canvas :workflow])) :text :image-path) (coll/first-where #(= chosen (:id %)) (:candidates model)))))))))
                  (.setDisable undo (not (coll/not-empty (:canvas-undo @state))))
                  (.setDisable redo (not (coll/not-empty (:canvas-redo @state))))
                  (draw-links!) (apply-viewport!))
        history! (fn change-history [direction]
                   (if (or (coll/not-empty (:busy @state)) (coll/any? :pending (coll/vals (:sessions @state))))
                     (report! "请等待当前任务结束后再撤销或重做")
                     (do
                       (reset! connecting nil)
                       (.requestFocus viewport)
                       (swap! state history-state direction)
                       (change! identity)
                       (select! (get-in @state [:canvas :selected])))))]
    (.setId undo "canvas-undo") (.setId redo "canvas-redo")
    (.setTooltip undo (Tooltip. "撤销 · Ctrl / ⌘ + Z"))
    (.setTooltip redo (Tooltip. "重做 · Ctrl / ⌘ + Shift + Z"))
    (.setOnAction undo (reify EventHandler (handle [_ _] (history! :undo))))
    (.setOnAction redo (reify EventHandler (handle [_ _] (history! :redo))))
    (.setFocusTraversable viewport true)
    (.addEventFilter root KeyEvent/KEY_PRESSED
      (reify EventHandler
        (handle [_ event]
          (let [e ^KeyEvent event]
            (when (and (.isShortcutDown e) (not (instance? TextInputControl (.getTarget e)))
                       (contains? #{KeyCode/Z KeyCode/Y} (.getCode e)))
              (history! (if (or (.isShiftDown e) (= KeyCode/Y (.getCode e))) :redo :undo))
              (.consume e))))))
    (.setId viewport "ai-canvas-viewport")
    (.setStyle viewport "-fx-background-color: -df-background;")
    (.setMinSize viewport 240.0 220.0)
    (.bind (.widthProperty clip) (.widthProperty viewport))
    (.bind (.heightProperty clip) (.heightProperty viewport))
    (.setClip viewport clip)
    (.add (.getTransforms world) scale)
    (.addAll (.getChildren world) ^java.util.Collection (identity [links cards]))
    ;; Resolve the dot color through the same palette as the surrounding editor.
    (.setStyle grid-paint "-fx-fill: -df-component-dark;")
    (.setManaged grid-paint false)
    (.setMouseTransparent grid-paint true)
    (.addListener (.fillProperty grid-paint)
                  (reify ChangeListener (changed [_ _ _ _] (draw-grid!))))
    (.setMouseTransparent grid true)
    (.bind (.widthProperty grid) (.widthProperty viewport))
    (.bind (.heightProperty grid) (.heightProperty viewport))
    (doseq [property [(.widthProperty viewport) (.heightProperty viewport)]]
      (.addListener ^javafx.beans.value.ObservableValue property
                    (reify ChangeListener (changed [_ _ _ _] (draw-grid!)))))
    (.addAll (.getChildren viewport) ^java.util.Collection (identity [grid-paint grid world]))
    (.addEventFilter viewport MouseEvent/MOUSE_PRESSED
      (reify EventHandler
        (handle [_ event]
          (let [e ^MouseEvent event]
            (when (= MouseButton/MIDDLE (.getButton e))
              (.requestFocus viewport)
              (.setCursor viewport Cursor/CLOSED_HAND)
              (reset! panning {:x (.getSceneX e) :y (.getSceneY e) :viewport (get-in @state [:canvas :viewport])})
              (.consume e))))))
    (.addEventFilter viewport MouseEvent/MOUSE_DRAGGED
      (reify EventHandler
        (handle [_ event]
          (when-let [start @panning]
            (let [e ^MouseEvent event]
              (swap! state assoc-in [:canvas :viewport]
                     (-> (:viewport start) (update :x + (- (.getSceneX e) (:x start))) (update :y + (- (.getSceneY e) (:y start)))))
              (apply-viewport!) (.consume e))))))
    (.addEventFilter viewport MouseEvent/MOUSE_RELEASED
      (reify EventHandler
        (handle [_ event]
          (when (and @panning (= MouseButton/MIDDLE (.getButton ^MouseEvent event)))
            (reset! panning nil)
            (.setCursor viewport Cursor/DEFAULT)
            (change! identity)
            (.consume ^MouseEvent event)))))
    (.setOnScroll viewport
                  (reify EventHandler
                    (handle [_ event]
                      (let [e ^ScrollEvent event point (.sceneToLocal viewport (.getSceneX e) (.getSceneY e))]
                        (when (or (.isControlDown e) (= viewport (.getTarget e)))
                          (zoom! (Math/pow 1.0015 (.getDeltaY e)) (.getX point) (.getY point)) (.consume e))))))
    (.addAll (.getChildren toolbar)
             ^java.util.Collection (identity [(label "AI Canvas")
                                              (button "+ 新流程" "canvas-add" (fn add-action [] (change! add-flow) (select! (get-in @state [:canvas :selected]))))
                                              (button "−" "canvas-zoom-out" #(zoom! 0.8 0.0 0.0)) zoom-label
                                              (button "+" "canvas-zoom-in" #(zoom! 1.25 0.0 0.0))
                                              (button "适应画布" "canvas-fit" fit!) undo redo
                                              (doto (Region.) (HBox/setHgrow Priority/ALWAYS))]))
    (.add (.getStyleClass toolbar) "ai-toolbar")
    (.setMaxHeight dock Region/USE_PREF_SIZE)
    (.setMaxWidth dock 640.0)
    (.setAlignment dock Pos/CENTER)
    (StackPane/setAlignment dock Pos/BOTTOM_CENTER)
    (StackPane/setMargin dock (Insets. 16.0))
    (.addAll (.getChildren surface) ^java.util.Collection (identity [viewport dock]))
    (.setTop root toolbar) (.setCenter root surface)
    (render!)
    {:root root :toolbar toolbar :dock dock :render! render! :fit! fit! :focus-node! focus-node!}))
