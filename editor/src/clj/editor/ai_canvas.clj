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
            [editor.ai-graph :as graph]
            [editor.ai-results :as results]
            [editor.ai-roles :as roles]
            [editor.ai-tasks :as tasks]
            [editor.ai-toast :as toast]
            [editor.file-reveal :as file-reveal]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.animation PauseTransition]
           [javafx.css PseudoClass]
           [javafx.event EventHandler]
           [javafx.geometry Insets Pos Side]
           [javafx.scene Cursor Group]
           [javafx.scene.canvas Canvas]
           [javafx.beans.value ChangeListener]
           [javafx.scene.control Button ButtonBase ContextMenu Label MenuButton MenuItem ProgressBar ScrollPane TextArea TextField TextInputControl Tooltip]
           [javafx.scene.image Image ImageView]
           [javafx.scene.input ContextMenuEvent KeyCode KeyEvent MouseButton MouseEvent ScrollEvent]
           [javafx.scene.layout BorderPane HBox Pane Priority Region StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape CubicCurve Rectangle]
           [javafx.scene.transform Scale]
           [javafx.util Duration]))

(set! *warn-on-reflection* true)

(def colors {:action "#5B8CFF" :result "#AD7BFF" :gui "#59BFD9" :assets "#48C78E" :role "#E6AF57" :person "#E6AF57"})

(def templates
  [{:id "concept" :title "文本方案" :default-name "核心玩法" :action "设计目标" :result "玩法方案候选" :assets "方案文档"
    :brief "描述游戏定位、核心循环与设计目标"
    :instructions "围绕游戏定位、核心循环、系统规则和验收标准给出可比较的文字方案。"}
   {:id "gui" :title "GUI 方案" :default-name "界面设计" :action "界面设计目标" :result "界面效果图候选" :assets "界面资源" :gui "GUI 界面"
    :brief "描述界面用途、布局、交互、分辨率与视觉风格"
    :instructions "设计游戏 GUI，明确布局层级、交互状态和组件规格，生成可比较的界面效果图。"}
   {:id "art-characters" :title "美术方案 · 角色与怪物" :default-name "角色与怪物" :action "角色与怪物设计" :result "角色与怪物候选" :assets "角色与怪物方案"
    :brief "描述主角或怪物的定位、造型、风格与动作需求"
    :instructions "设计角色与怪物（包括主角），统一造型、比例、配色与材质语言，生成候选概念图并说明制作规格。"}
   {:id "art-levels" :title "美术方案 · 场景与关卡" :default-name "场景与关卡" :action "场景与关卡设计" :result "场景与关卡候选" :assets "场景与关卡方案"
    :brief "描述环境主题、关卡路线、地标、视角与资源需求"
    :instructions "设计场景与关卡，统一环境风格、空间尺度、路线与地标，生成候选概念图并说明模块化资源需求。"}
   {:id "balancing" :title "数值与配置方案" :default-name "数值与配置" :action "数值与配置目标" :result "数值与配置候选" :assets "数值与配置文档"
    :brief "描述数值目标、成长曲线、经济循环、字段与约束"
    :instructions "给出数值与配置文字方案，包括公式、单位、字段类型、示例数据、边界约束、平衡假设和验证方法；不直接修改配置表。"}])

(defn template [id]
  (coll/first-where #(= (or id "concept") (:id %)) templates))

(defn text-workflow? [model]
  (and (= :document (:workflow model))
       (not (contains? #{"gui" "art-characters" "art-levels"} (:template model)))))

(defn gui-production? [model]
  (or (not= :document (:workflow model)) (= "gui" (:template model))))

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

(defn auto-layout [model]
  (if (= :roles (:workflow model))
    (roles/layout model)
    (let [columns {:person 0 :action 1 :result 2 :assets 3 :gui 4}
          cards (coll/into-> (:nodes model) {} (map (juxt :id identity)))
          parents (reduce (fn collect-parent [parents {:keys [from to]}]
                            (if (and (contains? cards from) (contains? cards to) (not (contains? parents to))
                                     (< (get columns (:kind (get cards from)) 1) (get columns (:kind (get cards to)) 1)))
                              (assoc parents to from)
                              parents)) {} (:edges model))
          children (reduce (fn collect-children [children {:keys [from to]}]
                             (if (and (= from (get parents to)) (coll/not-any? #{to} (get children from)))
                               (update children from (fnil conj []) to)
                               children)) {} (:edges model))]
      (letfn [(place-branch [id top]
                (let [{:keys [positions bottom]}
                      (reduce (fn place-child [{:keys [positions bottom]} child]
                                (let [branch (place-branch child bottom)]
                                  {:positions (merge positions (:positions branch)) :bottom (:bottom branch)}))
                              {:positions {} :bottom top} (get children id))
                      bottom (max (+ top 520.0) bottom)]
                  {:positions (assoc positions id {:x (* 340.0 (get columns (:kind (get cards id)) 1))
                                                   :y (+ top (/ (- bottom top 520.0) 2.0))})
                   :bottom bottom}))]
        (let [{:keys [positions]}
              (reduce (fn place-root [{:keys [positions bottom]} card]
                        (let [branch (place-branch (:id card) bottom)]
                          {:positions (merge positions (:positions branch)) :bottom (+ 80.0 (:bottom branch))}))
                      {:positions {} :bottom 0.0}
                      (filterv #(not (contains? parents (:id %))) (:nodes model)))]
          (update model :nodes #(mapv (fn position-card [card] (merge card (get positions (:id card)))) %)))))))

(defn change-state [state f]
  (let [before (:canvas state)
        after (cond-> (f before) (= :roles (:workflow before)) roles/layout)
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
            (assoc :canvas (cond-> (assoc current :nodes nodes :edges edges :selected selected)
                             (= :roles (:workflow current)) roles/layout))
            (update source pop)
            (update target (fn append-history [entries] (conj (vec entries) entry)))))
      state)))

(defn connect-nodes [canvas from to]
  (let [source (node-by-id canvas from) target (node-by-id canvas to)]
    (when-not (contains? #{[:person :action] [:action :result] [:result :assets] [:assets :gui]} [(:kind source) (:kind target)])
      (throw (ex-info "连线顺序为 Person → Action → Result → Assets → GUI" {})))
    (when (and (:source-action target) (not= from (:source-action target)))
      (throw (ex-info "Result 固定绑定产出它的 Action" {})))
    ;; A result/asset has exactly one upstream source, keeping provenance clear.
    (when (coll/any? #(and (= to (:to %)) (not= from (:from %))) (:edges canvas))
      (throw (ex-info "目标卡片已连接上游，请使用新的流程卡片" {})))
    (when (and (not (contains? #{:person :action} (:kind source))) (coll/any? #(and (= from (:from %)) (not= to (:to %))) (:edges canvas)))
      (throw (ex-info "当前阶段只支持线性流程，一张卡片连接一个下游" {})))
    (if (coll/any? #(= [from to] [(:from %) (:to %)]) (:edges canvas))
      canvas
      (update canvas :edges conj {:from from :to to}))))

(defn add-person [model]
  (let [selected (node-by-id model (:selected model))
        id (str "card_" (java.util.UUID/randomUUID))
        person (merge {:id id :kind :person :title "负责人" :x (- (or (:x selected) 0.0) 310.0) :y (or (:y selected) 0.0)}
                      (select-keys selected [:staff-id :staff-name :staff-role]))]
    (cond-> (-> model (update :nodes conj person) (assoc :selected id))
      (and (= :action (:kind selected)) (nil? (tasks/person-id model (:id selected))))
      (connect-nodes id (:id selected)))))

(defn add-node [model kind x y]
  (when-not (contains? graph/successors kind) (throw (ex-info "不支持的节点类型" {})))
  (let [id (str "card_" (java.util.UUID/randomUUID))
        node (cond-> {:id id :kind kind :title (if (= :person kind) "负责人" (get (template (:template model)) kind (if (= :gui kind) "GUI 界面" (name kind)))) :x x :y y}
               (= :action kind) (assoc :prompt "")
               (= :result kind) (assoc :candidates [])
               (= :assets kind) (assoc :assets []))]
    (-> model (update :nodes conj node) (assoc :selected id))))

(defn ensure-gui-card [model assets-id]
  (let [asset (node-by-id model assets-id)
        existing (:to (coll/first-where #(= assets-id (:from %)) (:edges model)))]
    (if (or (not= :assets (:kind asset)) existing)
      model
      (let [id (str "gui_" assets-id)]
        (-> model
            (update :nodes conj {:id id :kind :gui :title "GUI 界面"
                                 :x (+ 310.0 (or (:x asset) 0.0)) :y (or (:y asset) 0.0)})
            (connect-nodes assets-id id))))))

(defn deliver-gui [model assets-id output]
  (let [model (ensure-gui-card model assets-id)
        id (:to (coll/first-where #(= assets-id (:from %)) (:edges model)))]
    (-> model
        (update-node assets-id #(assoc % :production output))
        (update-node id #(assoc % :production output :gui-resource (:gui output) :gui-stale false)))))

(defn migrate-gui-cards [model]
  (if-not (gui-production? model)
    model
    (reduce (fn migrate-output [model card]
              (let [model (ensure-gui-card model (:id card))
                    id (:to (coll/first-where #(= (:id card) (:from %)) (:edges model)))]
                (if (and (:production card) (nil? (:gui-resource (node-by-id model id))))
                  (deliver-gui model (:id card) (:production card))
                  model)))
            model (filterv #(= :assets (:kind %)) (:nodes model)))))

(defn move-action [model person action delta]
  (let [edges (filterv #(= person (:from %)) (:edges model))
        index (.indexOf ^java.util.List (mapv :to edges) action)
        target (+ index delta)]
    (if (and (<= 0 index) (< -1 target (count edges)))
      (assoc model :edges (into (filterv #(not= person (:from %)) (:edges model))
                                (assoc edges index (nth edges target) target (nth edges index))))
      model)))

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
  (let [removed (coll/into-> (results/cards canvas id) #{id} (map :id))
        nodes (filterv #(not (contains? removed (:id %))) (:nodes canvas))]
    (-> canvas
        (assoc :nodes nodes :next-flow-id (next-flow-number canvas))
        (update :edges (fn remove-edges [edges]
                         (filterv #(not (or (contains? removed (:from %)) (contains? removed (:to %)))) edges)))
        (cond-> (contains? removed (:selected canvas)) (assoc :selected (:id (first nodes)))))))

(defn add-flow [canvas]
  (let [n (next-flow-number canvas)
        a (if (= :document (:workflow canvas)) (str "card_" (java.util.UUID/randomUUID)) (str "action-" n))
        r (if (= :document (:workflow canvas)) (str "card_" (java.util.UUID/randomUUID)) (str "result-" n))
        s (if (= :document (:workflow canvas)) (str "card_" (java.util.UUID/randomUUID)) (str "assets-" n))
        y (+ 500.0 (reduce max 0.0 (mapv :y (:nodes canvas))))]
    (-> canvas
        (update :nodes into [{:id a :kind :action :title (str (or (:action (template (:template canvas))) "设计目标") " " n) :x 0.0 :y y}
                             {:id r :kind :result :title (if (= :document (:workflow canvas)) (:result (template (:template canvas))) "效果图候选") :x 310.0 :y y :candidates []}
                             {:id s :kind :assets :title (if (= :document (:workflow canvas)) (:assets (template (:template canvas))) "组件资源") :x 620.0 :y y :assets []}])
        (update :edges into [{:from a :to r} {:from r :to s}])
        (assoc :next-flow-id (inc n) :selected a :viewport {:x 36.0 :y (- 44.0 (* y 0.85)) :zoom 0.85})
        (cond-> (= "gui" (:template canvas)) (ensure-gui-card s)))))

(defn component-candidate? [candidate]
  (if-let [purpose (:purpose candidate)]
    (= "component-sheet" purpose)
    (coll/any? #(re-find #"(?i)(?:components?|sprite[_-]?sheet|asset[_-]?kit|拆图|组件图)" (or (:name %) "")) (:files candidate))))

(defn- merge-versions [old candidates]
  (reduce (fn merge-candidate [all candidate]
            (if (coll/any? #(= (:id %) (:id candidate)) all)
              (mapv #(if (= (:id %) (:id candidate)) (merge % candidate) %) all)
              (conj all candidate)))
          (vec old) candidates))

(defn separate-gui-candidates [model]
  (if-not (gui-production? model)
    model
    (update model :nodes
            (fn separate-results [nodes]
              (mapv (fn separate-result [node]
                      (if-not (= :result (:kind node))
                        node
                        (let [components (filterv component-candidate? (:candidates node))
                              screens (filterv #(not (component-candidate? %)) (:candidates node))]
                          (cond-> (assoc node :candidates screens
                                         :asset-candidates (merge-versions (:asset-candidates node) components))
                            (coll/any? #(= (:chosen node) (:id %)) components) (dissoc :chosen))))) nodes)))))

(defn merge-candidates [canvas source-id candidates]
  (let [source (node-by-id canvas source-id)
        model (results/migrate (separate-gui-candidates canvas))]
    (if (= :assets (:kind source))
      (update-node model (:from (coll/first-where #(= source-id (:to %)) (:edges model)))
                   #(update % :asset-candidates merge-versions candidates))
      (let [action (if (= :action (:kind source)) source-id (results/action-id model source-id))
            components (if (gui-production? model) (filterv component-candidate? candidates) [])
            proposals (if (gui-production? model) (filterv #(not (component-candidate? %)) candidates) candidates)
            model (if action (results/merge-output model action proposals) model)
            target (:id (first (results/cards model action)))]
        (if (coll/not-empty components)
          (update-node model target #(update % :asset-candidates merge-versions components))
          model)))))

(defn ensure-result-assets [model result-id]
  (if (coll/any? #(= result-id (:from %)) (:edges model))
    model
    (let [result (node-by-id model result-id)
          id (str "assets_" result-id)]
      (-> model
          (update :nodes conj {:id id :kind :assets :title "方案交付"
                               :x (+ 310.0 (double (:x result))) :y (:y result) :assets []})
          (connect-nodes result-id id)))))

(defn disconnect-edge [model edge]
  (when (results/locked-edge? model edge)
    (throw (ex-info "Result 与来源 Action 固定绑定，不能断开" {})))
  (update model :edges #(filterv (fn remove-edge [item] (not= [(:from edge) (:to edge)] [(:from item) (:to item)])) %)))

(defn adopt-components [model assets-id candidate]
  (let [upstream (:from (coll/first-where #(= assets-id (:to %)) (:edges model)))
        downstream (:to (coll/first-where #(= assets-id (:from %)) (:edges model)))]
    (-> model
        (update-node assets-id #(assoc (dissoc % :production :production-plan :production-source-key)
                                  :source-result upstream :source-candidate (:id candidate)
                                  :assets (mapv (fn [file] (cond-> file (= (:path file) (:image-path candidate)) (assoc :preview (:preview candidate)))) (:files candidate))))
        (update-node downstream #(assoc % :gui-stale true)))))

(defn adopt-candidate [canvas result-id]
  (let [result (node-by-id canvas result-id)
        chosen (coll/first-where #(= (:chosen result) (:id %)) (:candidates result))
        target (:to (coll/first-where #(= result-id (:from %)) (:edges canvas)))]
    (when-not (and (if (text-workflow? canvas)
                     (or (not (string/blank? (:text chosen))) (:image-path chosen) (coll/not-empty (:files chosen)))
                     (:image-path chosen))
                   (= :assets (:kind (node-by-id canvas target))))
      (throw (ex-info "先选择候选，并连接 Assets 卡片" {})))
    (-> canvas
        (update-node target #(assoc (dissoc % :production :production-plan :production-source-key :document-output) :assets (if (coll/not-empty (:files chosen))
                                                                                                                              (mapv (fn [file] (cond-> file
                                                                                                                                                 (and (:preview chosen) (= (:path file) (:image-path chosen)))
                                                                                                                                                 (assoc :preview (:preview chosen)))) (:files chosen))
                                                                                                                              [{:name "设计方案" :text (:text chosen)}])
                                    :source-result result-id :source-candidate (:id chosen)
                                    :source-revision (results/revision result) :proposal-text (:text chosen)))
        (update-node (:to (coll/first-where #(= target (:from %)) (:edges canvas)))
                     #(assoc % :gui-stale true))
        (assoc :selected target))))

(defn- proposal-sections [text]
  (let [{:keys [parts]} (reduce
                          (fn split-heading [{:keys [fence] :as state} line]
                            (let [marker (second (re-find #"^\s*(`{3,}|~{3,})" line))
                                  heading (and (nil? fence) (re-find #"^##\s+方案\s*[A-Za-z0-9一二三四五六七八九十]+(?:\s|[：:、.－—-]|$)" line))]
                              (cond-> (if heading
                                        (update state :parts conj [line])
                                        (update-in state [:parts (dec (count (:parts state)))] conj line))
                                marker (assoc :fence (if (and fence (= (first fence) (first marker)) (<= (count fence) (count marker))) nil (or fence marker))))))
                          {:parts [[]] :fence nil} (string/split-lines text))]
    (if (< (count parts) 3)
      [text]
      (mapv #(coll/join-to-string "\n" (into (first parts) %)) (subvec parts 1)))))

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
                      prefix (str thread-id "/" (:id turn))
                      sections (proposal-sections text)]
                  (cond
                    (> (count sections) 1)
                    (coll/into-> sections []
                      (map-indexed (fn proposal-output [index section]
                                     (let [files (filterv #(string/includes? section (:source %)) files)
                                           image (coll/first-where #(string/includes? section (:source %)) images)]
                                       (cond-> {:id (str prefix "/proposal-" (inc index)) :text section :files files}
                                         image (assoc :image-path (:path image)))))))
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

(defn card-summary [text limit]
  (let [plain (-> (or text "")
                  (string/replace #"!?\[([^\]]*)\]\([^)]*\)" "$1")
                  (string/replace #"(?m)^\s*(?:#{1,6}|[-*+])\s+" "")
                  (string/replace #"[`*_]" "")
                  (string/replace #"\s+" " ") string/trim)]
    (if (<= (count plain) limit) plain (str (subs plain 0 limit) "…"))))

(defn- resource-row [file open! project-root report!]
  (let [row (doto (HBox. 8.0) (.setAlignment Pos/CENTER_LEFT) (.setMinWidth 0.0))
        title (doto (Label. (or (:name file) (last (string/split (or (:path file) "方案正文") #"[/\\]"))))
                (.setMinWidth 0.0) (.setMaxWidth Double/MAX_VALUE))
        size (:size file)
        metadata (doto (Label. (if (number? size)
                                 (if (>= size 1048576) (format "%.1f MB" (/ (double size) 1048576)) (format "%.0f KB" (/ (double size) 1024)))
                                 (if open! "↗" "")))
                   (-> .getStyleClass (.add "ai-muted")))
        icon (roles/make-icon "document")]
    (HBox/setHgrow title Priority/ALWAYS)
    (.addAll (.getChildren row) ^java.util.Collection (identity [icon title metadata]))
    (file-reveal/install! row project-root (constantly file) report!)
    (if-not open!
      (doto row (-> .getStyleClass (.add "ai-resource-row")))
      (let [control (doto (button "" "" open!) (.setGraphic row) (.setMinWidth 0.0) (.setMaxWidth Double/MAX_VALUE)
                          (.setTooltip (Tooltip. (or (:name file) (:path file) "查看内容")))
                          (-> .getStyleClass (.add "ai-resource-row")))]
        (.bind (.prefWidthProperty row) (.subtract (.widthProperty control) 20.0))
        (file-reveal/install! control project-root (constantly file) report!)))))

(defn- task-row [title status id action]
  (let [name (doto (Label. title) (.setMinWidth 0.0) (.setMaxWidth Double/MAX_VALUE))
        status-label (doto (Label. (get tasks/status-labels status "待执行"))
                       (.setMinWidth Region/USE_PREF_SIZE)
                       (-> .getStyleClass (.add "ai-task-status")))
        row (doto (HBox. 8.0) (.setAlignment Pos/CENTER_LEFT))
        control (doto (button "" id action) (.setGraphic row) (.setMaxWidth Double/MAX_VALUE)
                      (.setAccessibleText (str title " · " (.getText status-label)))
                      (-> .getStyleClass (.add "ai-task-row"))
                      (.pseudoClassStateChanged (PseudoClass/getPseudoClass "completed") (= "completed" status)))]
    (HBox/setHgrow name Priority/ALWAYS)
    (.addAll (.getChildren row) ^java.util.Collection (identity [name status-label]))
    (.bind (.prefWidthProperty row) (.subtract (.widthProperty control) 18.0))
    control))

(defn configure-scroll! [^ScrollPane scroll]
  (let [active (PseudoClass/getPseudoClass "scrolling")
        timer (PauseTransition. (Duration/millis 900.0))
        show! (ui/event-handler _
                (.pseudoClassStateChanged scroll active true)
                (.playFromStart timer))]
    (.add (.getStyleClass scroll) "ai-card-scroll")
    (.setOnFinished timer (ui/event-handler _ (.pseudoClassStateChanged scroll active false)))
    (.addEventFilter scroll ScrollEvent/SCROLL show!)
    (.addEventFilter scroll MouseEvent/MOUSE_MOVED show!)
    (.addEventFilter scroll MouseEvent/MOUSE_PRESSED show!)
    (ui/user-data! scroll ::scroll-timer timer)))

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

(defn- gui-preview-pane [state card width height]
  (let [preview (get-in state [:gui-previews (:id card)])
        image (when (and (not (:gui-stale card)) (= (:gui-resource card) (:path preview))) (:image preview))
        pane (VBox. 6.0)]
    (.setAlignment pane Pos/TOP_CENTER)
    (.add (.getChildren pane)
          (if image
            (doto (ImageView. ^Image image) (.setFitWidth width) (.setFitHeight height) (.setPreserveRatio true))
            (label (if (:gui-stale card) "上游方案已改变，请重新生成或绑定 GUI"
                       (or (:error preview) "绑定 GUI 后显示实际资源预览")))))
    (.add (.getChildren pane) (label "GUI 资源渲染 · 不执行游戏脚本"))
    pane))

(defn focus-viewport [x y card-width card-height viewport-width viewport-height]
  (let [width (max 1.0 (- viewport-width 64.0))
        height (max 1.0 (- viewport-height 64.0))
        zoom (min 1.8 (/ width (max 1.0 card-width)) (/ height (max 1.0 card-height)))]
    {:zoom zoom
     :x (- (/ viewport-width 2.0) (* (+ x (/ card-width 2.0)) zoom))
     :y (- (/ viewport-height 2.0) (* (+ y (/ card-height 2.0)) zoom))}))

(defn card-content-key [state]
  [(:gui-previews state)
   (mapv #(dissoc % :chosen) (get-in state [:canvas :nodes]))
   (get-in state [:canvas :edges])
   (:staff state)
   (mapv roles/role-type (:staff state))
   (get-in state [:canvas :role-types])
   (coll/into-> (:sessions state) {} (map (fn [[id session]] [id (:attachments session)])))
   (get-in state [:context :path])
   (:role-search state)
   (mapv #(select-keys % [:queue :execution]) (coll/vals (:sessions state)))])

(defn- empty-card [icon text]
  (let [box (VBox. 10.0)]
    (.add (.getStyleClass box) "ai-empty")
    (.addAll (.getChildren box) ^java.util.Collection
             (identity [(doto (label icon) (.setStyle "-fx-font-size: 32; -fx-text-fill: -ai-accent;")) (label text)]))
    box))

(defn make-details [state _choose! open-output! gui! save-role! staff! assign! report! work!]
  (let [root (BorderPane.)
        content (VBox. 12.0)
        scroll (ScrollPane. content)
        rendered (atom nil)
        section! (fn add-section [title]
                   (let [section (VBox. 8.0)]
                     (.add (.getStyleClass section) "ai-detail-section")
                     (.add (.getChildren section) (doto (label title) (-> .getStyleClass (.add "ai-card-kind"))))
                     (.add (.getChildren content) section)
                     section))
        text! (fn add-text [^VBox section text]
                (let [node (doto (label (str text)) (.setMinWidth 0.0) (.setMaxWidth Double/MAX_VALUE))]
                  (.add (.getChildren section) node)
                  node))
        file-text! (fn add-file-text [section text]
                     (file-reveal/install! (text! section text) (:project-root @state) (constantly text) report!))
        image! (fn add-image [^VBox section preview]
                 (when preview
                   (let [^ImageView view (image-preview (str preview) 320.0 360.0)]
                     (.bind (.fitWidthProperty view) (.subtract (.widthProperty content) 28.0))
                     (file-reveal/install! view (:project-root @state) (constantly preview) report!)
                     (.add (.getChildren section) view))))
        refresh! (fn refresh-details []
                   (let [s @state
                         card (node-by-id (:canvas s) (get-in s [:canvas :selected]))
                         session (get-in s [:sessions (:id card)])
                         files (or (coll/not-empty (:attachments session)) (:references card))
                         key [(get-in s [:gui-previews (:id card)]) (dissoc card :x :y) files (:context s) (:execution session) (:queue session) (:thread-id session) (:thread-name session) (:edges (:canvas s))
                              (:staff s) (mapv roles/role-type (:staff s)) (get-in s [:canvas :role-types])
                              (when (= :person (:kind card)) (mapv :execution (coll/vals (:sessions s))))
                              (tasks/locked? s) (mapv #(select-keys % [:id :title :staff-name]) (get-in s [:canvas :nodes]))]]
                     (when-not (= key @rendered)
                       (reset! rendered key)
                       (.clear (.getChildren content))
                       (.setVvalue scroll 0.0)
                       (when-not (= :role (:kind card)) (.setCenter root scroll))
                       (.setStyle content (str "-ai-accent: " (if (= :role (:kind card)) (:color (roles/role-type card)) (colors (:kind card))) ";"))
                       (when-not (= :role (:kind card))
                         (when-let [error (graph/execution-error (:canvas s) (:staff s) (:id card))]
                           (text! (section! "配置 / 连接待确认") error)))
                       (case (:kind card)
                         :role
                         (.setCenter root (roles/make-editor card (roles/types (:canvas @state)) save-role!))

                         :person
                         (do
                           (.add (.getChildren ^VBox (section! "负责人")) (roles/make-assignment card staff! assign! report! "ai-detail-staff-"))
                           (let [section (section! "Codex 项目子任务")]
                             (text! section (or (:thread-name session) "首次执行时自动创建并绑定"))
                             (when-let [thread-id (:thread-id session)] (text! section thread-id)))
                           (let [section ^VBox (section! "关联任务 · 按此顺序执行")]
                             (doseq [[index id] (eduction (map-indexed vector) (tasks/action-ids (:canvas s) (:id card)))]
                               (let [row (HBox. 6.0)]
                                 (.addAll (.getChildren row) ^java.util.Collection
                                          (identity [(label (str (inc index) ". " (:title (node-by-id (:canvas s) id))))
                                                     (button "↑" (str "task-up-" id) #(work! :move (:id card) {:id id :delta -1}))
                                                     (button "↓" (str "task-down-" id) #(work! :move (:id card) {:id id :delta 1}))]))
                                 (.add (.getChildren section) row)))
                             (.add (.getChildren section) (button "+ 添加任务" "person-add-task" #(work! :add-task (:id card) nil))))
                           (let [section ^VBox (section! "执行队列")
                                 queue (:queue session)]
                             (text! section (str (get tasks/status-labels (:status queue) "待执行") " · " (count (:completed queue)) "/" (count (:actions queue)) " 项完成"))
                             (doseq [id (eduction (distinct) (:actions queue))
                                     :when (= :result (:kind (node-by-id (:canvas s) id)))]
                               (text! section (str "方案确认 · " (:title (node-by-id (:canvas s) id)) " · "
                                                   (get tasks/status-labels (tasks/status s id)))))
                             (.addAll (.getChildren section) ^java.util.Collection
                                      (identity [(doto (button "按顺序执行 / 继续" "person-run" #(work! :run (:id card) nil))
                                                   (.setDisable (boolean (graph/execution-error (:canvas s) (:staff s) (:id card)))))
                                                 (button "暂停后续任务" "person-pause" #(work! :pause (:id card) nil))
                                                 (button "结束队列（当前任务继续）" "person-cancel" #(work! :cancel (:id card) nil))]))))

                         :action
                         (do
                           (let [section ^VBox (section! "负责人")
                                 owner (node-by-id (:canvas s) (tasks/person-id (:canvas s) (:id card)))]
                             (if owner
                               (text! section (str (:staff-name owner "待选择职员") " · 由 Person 卡统一安排"))
                               (text! section "等待连接 Person 负责人；职员由 Person 卡统一配置")))
                           (let [section ^VBox (section! "任务描述")
                                 title (doto (TextField. (:title card)) (.setId "action-task-title") (.setDisable (boolean (tasks/locked? s))))
                                 prompt (doto (TextArea. (or (:prompt card) "")) (.setId "action-task-description") (.setWrapText true) (.setPrefRowCount 4) (.setDisable (boolean (tasks/locked? s))))]
                             (.addAll (.getChildren section) ^java.util.Collection
                                      (identity [title prompt (button "保存任务" "action-task-save" #(work! :save-task (:id card) {:title (.getText title) :prompt (.getText prompt)}))])))
                           (let [section ^VBox (section! "执行状态")
                                 status (tasks/status s (:id card))]
                             (text! section (get tasks/status-labels status))
                             (when (tasks/active? s (:id card))
                               (.add (.getChildren section) (doto (ProgressBar. -1.0) (.setMaxWidth Double/MAX_VALUE))))
                             (when-let [error (get-in session [:execution :error])] (text! section error))
                             (text! section "请在 Person 负责人卡上按顺序执行任务"))
                           (let [binding (section! (if (= :document (get-in s [:canvas :workflow])) "Canvas 文档" "GUI 上下文"))]
                             (file-text! binding (get-in s [:context :path]))
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
                                   (file-reveal/install! item (:project-root s) (constantly file) report!)
                                   (.add (.getChildren references) item)))
                               (text! references "暂无附件"))))
                         :result
                         (if-let [proposal (results/candidate card)]
                           (let [id (:id card)
                                 pending (tasks/confirmation-locked? s id)
                                 ^VBox version (section! "方案版本")
                                 ^VBox body (section! "方案内容 · 可人工修改")
                                 text (doto (TextArea. (get-in s [:result-drafts id :text] (or (:text proposal) "")))
                                        (.setId "ai-result-text") (.setWrapText true) (.setPrefRowCount 12) (.setDisable pending))
                                 actions (HBox. 6.0)]
                             (text! version (str (:title (node-by-id (:canvas s) (results/action-id (:canvas s) id))) " · " (:title card)))
                             (text! version (str (if (:manually-edited card) "人工修改版本" "Action 原始产出") " · "
                                                 (if (= (:confirmed-revision card) (results/revision card))
                                                   (str "已选定 · " (get tasks/status-labels (tasks/status s id))) "尚未确认")))
                             (image! body (:preview proposal))
                             (.add (.getChildren body) text)
                             (.addListener (.textProperty text)
                                           (reify ChangeListener
                                             (changed [_ _ _ value]
                                               (swap! state assoc-in [:result-drafts id :text] value))))
                             (doseq [[title control-id op] [["保存修改" "ai-result-save" :save-result]
                                                            ["替换图片…" "ai-result-image" :replace-result-image]
                                                            ["添加文件…" "ai-result-files" :add-result-files]]]
                               (.add (.getChildren actions)
                                     (doto (button title control-id #(work! op id {:text (.getText text)})) (.setDisable pending))))
                             (.add (.getChildren body) actions)
                             (doseq [file (:files proposal)]
                               (let [row (HBox. 6.0)]
                                 (.addAll (.getChildren row) ^java.util.Collection
                                          (identity [(label (or (:name file) (:path file)))
                                                     (doto (button "×" "ai-result-remove-file" #(work! :remove-result-file id {:path (:path file) :text (.getText text)}))
                                                       (.setDisable pending))]))
                                 (file-reveal/install! row (:project-root s) (constantly file) report!)
                                 (.add (.getChildren body) row)))
                             (.add (.getChildren body)
                                   (doto (primary-button (if pending "已加入负责人队列" "确认此方案并排队") "ai-result-confirm"
                                                         #(work! :confirm-result id {:text (.getText text)}))
                                     (.setDisable pending))))
                           (text! (section! "方案内容") "等待 Action 产出方案。"))
                         :gui
                         (let [^VBox preview (section! "引擎界面预览")
                               path (doto (TextField. (or (:gui-resource card) ""))
                                      (.setId "ai-gui-resource") (.setPromptText "/game/layouts/screen.gui"))
                               actions (HBox. 6.0)]
                           (.add (.getChildren preview) (file-reveal/install! (gui-preview-pane s card 300.0 420.0) (:project-root s) (constantly (:gui-resource card)) report!))
                           (file-reveal/install! path (:project-root s) (fn gui-path [] (.getText path)) report!)
                           (.add (.getChildren preview) path)
                           (doseq [[title id action] [["绑定" "ai-gui-bind" #(gui! :bind (:id card) (.getText path))]
                                                      ["↗" "ai-gui-open" #(gui! :open (:id card) nil)]
                                                      ["↻" "ai-gui-refresh" #(gui! :refresh (:id card) nil)]]]
                             (.add (.getChildren actions) (button title id action)))
                           (.add (.getChildren preview) actions))
                         :assets
                         (do
                           (let [source (section! "采用的资源")]
                             (if (coll/not-empty (:assets card))
                               (doseq [asset (:assets card)]
                                 (image! source (:preview asset))
                                 (file-reveal/install! (text! source (:name asset)) (:project-root s) (constantly asset) report!)
                                 (when-let [text (:text asset)] (text! source text))
                                 (when-let [path (or (:source asset) (:path asset))] (file-text! source path)))
                               (text! source "先从 Result 卡片采用候选。")))
                           (when-let [path (:document-output card)]
                             (file-text! (section! "归档文档") path))
                           (when-let [plan (:production-plan card)]
                             (let [regions (section! (str "拆分计划 · " (count (:regions plan)) " 个组件"))]
                               (doseq [region (:regions plan)]
                                 (text! regions (str (or (:name region) (:id region)) "  " (:x region) ", " (:y region) "  ·  " (:width region) " × " (:height region))))))
                           (when-let [output (:production card)]
                             (let [^VBox delivery (section! "Defold 成品")]
                               (text! delivery (str (:count output) " 个组件 · 已归档"))
                               (doseq [[kind title] [[:atlas "打开 Atlas"]]]
                                 (when-let [path (get output kind)] (file-text! delivery path))
                                 (.add (.getChildren delivery) (button title (str "ai-detail-open-" (name kind)) #(open-output! output kind)))))))
                         nil)
                       (.setDisable content (boolean (and (contains? #{:result :assets :gui} (:kind card))
                                                          (graph/task-error (:canvas s) (:staff s) (:id card))))))))]
    (.setId root "ai-card-details")
    (.setCenter root scroll)
    (.setFitToWidth scroll true)
    (.setMinHeight scroll 100.0)
    (.setHbarPolicy scroll javafx.scene.control.ScrollPane$ScrollBarPolicy/NEVER)
    (.add (.getStyleClass scroll) "ai-details")
    (configure-scroll! scroll)
    (.setStyle content "-fx-padding: 8;")
    {:root root :refresh! refresh!}))

(defn- display-model [state]
  (let [model (:canvas state)]
    (if (= :roles (:workflow model)) (roles/visible-model model (:role-search state)) model)))

(defn make-canvas [state change! select! focus! report! produce! open-output! gui! attach! staff! assign! work!]
  (let [root (BorderPane.) viewport (Pane.) world (Group.) grid (Canvas.) grid-paint (Rectangle.) links (Group.) cards (Group.) role-rows (Group.)
        scale (Scale. 1.0 1.0 0.0 0.0) clip (Rectangle.)
        toolbar (HBox. 8.0) zoom-label (Label.) dock (VBox. 6.0) surface (StackPane.)
        notifications (toast/make-view)
        report! (fn report-canvas [notification]
                  (report! (if (map? notification) notification
                               {:id :canvas-interaction :level :warning :text notification})))
        connecting (atom nil) panning (atom nil) scroll-values (atom {}) rendered-content (atom nil)
        preview (doto (CubicCurve.) (.setManaged false) (.setMouseTransparent true) (.setFill nil) (.setStrokeWidth 2.0) (.setVisible false))
        undo (Button. "撤销") redo (Button. "重做")
        read-staff! (fn refresh-staff [] (let [staff (staff!)] (swap! state assoc :staff staff) staff))
        cancel-link! (fn cancel-link [] (reset! connecting nil) (.setVisible preview false))
        link! (fn link-nodes [from to]
                (try
                  (when (tasks/locked? @state) (throw (ex-info "执行队列未结束，暂不能修改连线" {})))
                  (let [staff (read-staff!)] (change! #(graph/connect % staff from to)))
                  (select! to)
                  (if-let [error (graph/upstream-error (:canvas @state) (:staff @state) to)]
                    (report! error)
                    (report! {:id :canvas-interaction :level :info :resolved true :text "连接已确认"}))
                  (catch Exception e (report! (.getMessage e)))))
        node-menu! (fn show-node-menu [from x y screen-x screen-y]
                     (try
                       (when (tasks/locked? @state) (throw (ex-info "执行队列未结束，暂不能新建节点" {})))
                       (let [model (:canvas @state) staff (read-staff!)
                             error (when from (graph/output-error model staff from))
                             kinds (if from (get graph/successors (:kind (node-by-id model from))) [:person :action :assets :gui])
                             menu (ContextMenu.)]
                         (when error (throw (ex-info error {})))
                         (when (and from (not= :person (:kind (node-by-id model from)))
                                    (coll/any? #(= from (:from %)) (:edges model)))
                           (throw (ex-info "此接点已有后续节点，请先断开原连线；重新确认时可直接拖到原输入接点" {})))
                         (doseq [kind kinds]
                           (.add (.getItems menu)
                                 (doto (MenuItem. ({:person "Person · 负责人" :action "Action · 执行任务" :result "Result · 方案结果" :assets "Assets · 交付内容" :gui "GUI · 引擎界面"} kind))
                                   (.setId (str "canvas-create-" (name kind)))
                                   (.setOnAction
                                     (reify EventHandler
                                       (handle [_ _]
                                         (try
                                           (let [staff (read-staff!)]
                                             (change! (fn create-connected-node [model]
                                                        (let [next (add-node model kind x y)]
                                                          (if from (graph/connect next staff from (:selected next)) next)))))
                                           (select! (get-in @state [:canvas :selected]))
                                           (catch Exception e (report! (.getMessage e))))))))))
                         (.setAutoHide menu true)
                         (.setHideOnEscape menu true)
                         (.setConsumeAutoHidingEvents menu false)
                         (ui/set-show-relative-to-window! menu true)
                         (ui/hide-context-menu-on-anchor-pressed! menu (.getRoot (.getScene viewport)))
                         (.show menu viewport (double screen-x) (double screen-y)))
                       (catch Exception e (report! (.getMessage e)))))
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
               (let [model (display-model @state)
                     role-layout (= :roles (:workflow model))
                     nodes (cond-> (:nodes model) role-layout (into (roles/rows model)))
                     left (reduce min 0.0 (mapv :x nodes)) top (reduce min 0.0 (mapv :y nodes))
                     width (- (+ (if role-layout 200.0 280.0) (reduce max 0.0 (mapv :x nodes))) left)
                     height (- (+ (if role-layout roles/card-height 470.0) (reduce max 0.0 (mapv :y nodes))) top)
                     zoom (max 0.25 (min 1.0 (/ (max 100.0 (- (.getWidth viewport) 60.0)) width)
                                         (/ (max 100.0 (- (.getHeight viewport) 60.0)) height)))]
                 (change! #(assoc % :viewport {:x (- 30.0 (* left zoom)) :y (- 30.0 (* top zoom)) :zoom zoom})))
               (apply-viewport!))
        focus-node! (fn focus-node [id]
                      (.applyCss root)
                      (.layout root)
                      (when-let [^VBox card (.lookup cards (str "#canvas-card-" id))]
                        (let [model (node-by-id (display-model @state) id)
                              height (- (.getHeight viewport) (if (.isVisible dock) (+ 32.0 (.getHeight dock)) 0.0))]
                          (change! #(assoc % :viewport (focus-viewport (:x model) (:y model) (.getWidth card) (.getHeight card) (.getWidth viewport) height)))
                          (apply-viewport!))))
        draw-links! (fn draw-links []
                      (when-let [^Button arrange (.lookup toolbar "#canvas-arrange")]
                        (.setDisable arrange (boolean (tasks/locked? @state))))
                      (.setDisable undo (or (tasks/locked? @state) (not (coll/not-empty (:canvas-undo @state)))))
                      (.setDisable redo (or (tasks/locked? @state) (not (coll/not-empty (:canvas-redo @state)))))
                      (.clear (.getChildren links))
                      (let [canvas (:canvas @state)]
                        (doseq [{:keys [from to] :as edge} (:edges canvas)]
                          (let [a (node-by-id canvas from) b (node-by-id canvas to)
                                error (graph/edge-error canvas (:staff @state) edge)
                                x1 (+ (:x a) 268.0) y1 (+ (:y a) 225.0) x2 (:x b) y2 (+ (:y b) 225.0)
                                line (doto (CubicCurve. x1 y1 (+ x1 55.0) y1 (- x2 55.0) y2 x2 y2)
                                       (.setId (str "canvas-link-" from "-" to))
                                       (.setStroke (Color/web (colors (:kind a)))) (.setStrokeWidth 3.0) (.setFill nil))
                                menu (ContextMenu.)
                                confirm (MenuItem. "重新确认连接")
                                disconnect (MenuItem. "断开连接")]
                            (.setDisable confirm (boolean (tasks/locked? @state)))
                            (.setDisable disconnect (boolean (or (tasks/locked? @state) (results/locked-edge? canvas edge))))
                            (when error
                              (.setStyle line "-fx-stroke: -df-text-dark; -fx-opacity: 0.45;")
                              (.addAll (.getStrokeDashArray line) ^java.util.Collection (identity [7.0 5.0])))
                            (.pseudoClassStateChanged line (PseudoClass/getPseudoClass "invalid") (boolean error))
                            (Tooltip/install line (Tooltip. (or error "右键管理连接")))
                            (.setOnAction confirm (reify EventHandler (handle [_ _] (link! from to))))
                            (.setOnAction disconnect
                                          (reify EventHandler
                                            (handle [_ _]
                                              (try (change! #(disconnect-edge % edge))
                                                   (catch Exception e (report! (.getMessage e))))
                                              (select! (:selected (:canvas @state))))))
                            (.addAll (.getItems menu) ^java.util.Collection (identity [confirm disconnect]))
                            (.setOnContextMenuRequested line
                                                        (reify EventHandler
                                                          (handle [_ event]
                                                            (let [e ^ContextMenuEvent event]
                                                              (.show menu line (.getScreenX e) (.getScreenY e))
                                                              (.consume e)))))
                            (.add (.getChildren links) line)))))
        render! (fn render-canvas []
                  (let [key (card-content-key @state)]
                    (when-not (= key @rendered-content)
                      (reset! rendered-content key)
                      (doseq [^VBox old-card (.getChildren cards)]
                        (swap! scroll-values assoc (.getUserData old-card)
                               (.getVvalue ^ScrollPane (.get (.getChildren old-card) 1))))
                      (.clear (.getChildren cards))
                      (.clear (.getChildren role-rows))
                      (let [canvas (display-model @state)]
                        (when (= :roles (:workflow canvas))
                          (doseq [{:keys [id title color x y] :as row} (roles/rows canvas)]
                            (let [heading (roles/make-row-heading row)
                                  separator (doto (Rectangle. (max 940.0 (+ x 200.0)) 1.0)
                                              (.setLayoutY (+ y roles/row-height -16.0))
                                              (.setStyle "-fx-fill: -df-component-dark;")
                                              (.setMouseTransparent true))
                                  add (doto (button (str "+  添加" title "职员") (str "canvas-add-role-" id)
                                                    (fn add-row-card []
                                                      (when-let [^TextField search (.lookup toolbar "#ai-role-search")] (.clear search))
                                                      (change! #(roles/add-role % id))
                                                      (select! (get-in @state [:canvas :selected]))))
                                        (.setLayoutX x) (.setLayoutY (+ y 36.0))
                                        (.setPrefSize 184.0 58.0)
                                        (.setStyle (str "-ai-accent: " color ";"))
                                        (-> .getStyleClass (.add "ai-role-add")))]
                              (.addAll (.getChildren role-rows) ^java.util.Collection (identity [separator heading add])))))
                        (doseq [{:keys [id kind title x y assets] :as model} (:nodes canvas)]
                          (let [selected (= id (:selected canvas)) color (if (= :role kind) (:color (roles/role-type model)) (colors kind))
                                blocked (when-not (= :role kind) (graph/execution-error canvas (:staff @state) id))
                                card (VBox. 10.0) header (HBox. 8.0)
                                kind-label (label (if (= :role kind) (:title (roles/role-type model)) ({:person "Person · 负责人" :action "Action · 执行任务" :result "Result · 执行结果" :assets "Assets · 产物资源" :gui "GUI · 引擎界面"} kind)))
                                title-label (label title)
                                spacer (Region.)
                                more (Button. "···")
                                menu (ContextMenu.)
                                discuss (doto (MenuItem. (cond (= :action kind) "查看任务与对话"
                                                               (contains? #{:result :assets} kind) "在新窗口查看详情"
                                                               :else "查看卡片详情"))
                                          (.setId (str "canvas-inspect-" id)))
                                delete (MenuItem. "删除卡片")
                                proposals (when (= :action kind) (results/produced-cards canvas id))
                                view-results (doto (MenuItem. (if (> (count proposals) 1) "对比方案" "查看方案"))
                                               (.setId (str "canvas-action-results-" id))
                                               (.setDisable (not (coll/not-empty proposals))))
                                content (VBox. 8.0) scroll (ScrollPane. content) drag (atom nil)
                                port-in (doto (Button.) (.setId (str "canvas-in-" id)))
                                port-out (doto (Button.) (.setId (str "canvas-out-" id)))
                                port-error (or (when (tasks/locked? @state) "执行队列未结束，暂不能修改连线")
                                               (when (= :action kind) "Result 随 Action 产出自动创建并固定连接")
                                               (graph/output-error canvas (:staff @state) id))]
                            (.setDisable port-out (boolean port-error))
                            (.addEventFilter port-out MouseEvent/MOUSE_PRESSED
                              (reify EventHandler
                                (handle [_ event]
                                  (let [e ^MouseEvent event]
                                    (when (= MouseButton/PRIMARY (.getButton e))
                                      (try
                                        (when (tasks/locked? @state) (throw (ex-info "执行队列未结束，暂不能修改连线" {})))
                                        (when-let [error (graph/output-error (:canvas @state) (read-staff!) id)] (throw (ex-info error {})))
                                        (.requestFocus viewport)
                                        (report! "")
                                        (reset! connecting {:from id})
                                        (.setStartX preview (+ x 268.0)) (.setStartY preview (+ y 225.0))
                                        (.setControlX1 preview (+ x 323.0)) (.setControlY1 preview (+ y 225.0))
                                        (.setEndX preview (+ x 268.0)) (.setEndY preview (+ y 225.0))
                                        (.setControlX2 preview (+ x 268.0)) (.setControlY2 preview (+ y 225.0))
                                        (.setStroke preview (Color/web color)) (.setVisible preview true)
                                        (catch Exception e (report! (.getMessage e))))
                                      (.consume e))))))
                            (doseq [port [port-in port-out]]
                              (.setOnMouseClicked ^Button port (reify EventHandler (handle [_ e] (.consume ^MouseEvent e)))))
                            (.setId card (str "canvas-card-" id))
                            (.setUserData card id)
                            (.add (.getStyleClass card) "ai-card")
                            (.add (.getStyleClass card) (str "ai-" (name kind)))
                            (.pseudoClassStateChanged card (PseudoClass/getPseudoClass "selected") selected)
                            (.pseudoClassStateChanged card (PseudoClass/getPseudoClass "running") (= "running" (tasks/status @state id)))
                            (.setStyle card (str "-ai-accent: " color ";"))
                            (.setOpacity card (if (and blocked (contains? #{:action :result :assets :gui} kind)) 0.55 1.0))
                            (.setDisable content (boolean (and blocked (contains? #{:result :assets :gui} kind))))
                            (.setDisable discuss (boolean (and (= :action kind) blocked)))
                            (let [width (if (= :role kind) roles/card-width 268.0)]
                              (.setPrefWidth card width) (.setMinWidth card width) (.setMaxWidth card width))
                            (.setLayoutX card (double x)) (.setLayoutY card (double y))
                            (.add (.getStyleClass kind-label) "ai-card-kind")
                            (when-not (= :role kind)
                              (.add (.getStyleClass kind-label) "ai-card-type-badge")
                              (.setGraphic kind-label (roles/make-icon (case kind :person "person" :action "planning" :result "document" "model"))))
                            (.add (.getStyleClass title-label) "ai-card-title")
                            (.add (.getStyleClass more) "ai-icon-button")
                            (.setAccessibleText more "卡片操作")
                            (.setId delete (str "canvas-delete-" id))
                            (.addAll (.getItems menu) ^java.util.Collection
                                     (identity (cond (contains? #{:role :person} kind) [delete]
                                                     (= :action kind) [view-results discuss delete]
                                                     :else [discuss delete])))
                            (.setOnAction view-results (ui/event-handler _ (work! :view-action-results id nil)))
                            (.setOnAction delete
                                          (reify EventHandler
                                            (handle [_ _]
                                              (if (or (contains? (:busy @state) id) (get-in @state [:sessions id :pending]))
                                                (report! "这张卡片的任务尚未结束，请完成后再删除")
                                                (do
                                                  (when (= id (:from @connecting)) (cancel-link!))
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
                            (.setOnAction discuss (reify EventHandler
                                                    (handle [_ _]
                                                      (select! id)
                                                      (cond
                                                        (= :action kind) (focus!)
                                                        (contains? #{:result :assets} kind) (work! :inspect-card id nil)))))
                            (HBox/setHgrow spacer Priority/ALWAYS)
                            (.setId header (str "canvas-drag-" id))
                            (.addAll (.getChildren header) ^java.util.Collection (identity [kind-label spacer more]))
                            (doseq [[port px hidden] [[port-in -8.0 (contains? #{:person :role} kind)] [port-out 260.0 (contains? #{:gui :role} kind)]]]
                              (.setManaged ^Button port false)
                              (.resize ^Button port 16.0 16.0)
                              (.setVisible ^Button port (not hidden))
                              (.setLayoutX ^Button port px) (.setLayoutY ^Button port 217.0)
                              (.add (.getStyleClass ^Button port) "ai-port")
                              (.setTooltip ^Button port (Tooltip. (if (= port port-in) "将上游接线拖到这里" (or port-error "拖到目标输入接点，或拖到空白处新建后续节点")))))
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
                                                      (when (and (not= :role kind) (= MouseButton/PRIMARY (.getButton e))
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
                            (configure-scroll! scroll)
                            (.setFitToWidth scroll true) (.setPrefViewportHeight scroll (if (= :role kind) 110.0 330.0))
                            (.setMinHeight scroll 0.0)
                            (VBox/setVgrow scroll Priority/ALWAYS)
                            (when-not (= :role kind)
                              (.setMinHeight card 470.0) (.setPrefHeight card 470.0) (.setMaxHeight card 470.0))
                            (when (= :role kind)
                              (.setMinHeight scroll 0.0)
                              (.setMinHeight card roles/card-height) (.setPrefHeight card roles/card-height) (.setMaxHeight card roles/card-height)
                              (.setVisible header false) (.setManaged header false)
                              (.remove (.getChildren header) more))
                            (.setHbarPolicy scroll javafx.scene.control.ScrollPane$ScrollBarPolicy/NEVER)
                            (.addAll (.getChildren card) ^java.util.Collection (identity [header scroll]))
                            (when-not (contains? #{:role :person} kind) (.add (.getChildren content) title-label))
                            (when blocked
                              (.add (.getChildren content) (doto (label blocked) (-> .getStyleClass (.add "ai-muted")))))
                            (case kind
                              :role
                              (.add (.getChildren content) (roles/make-card-summary model more))

                              :person
                              (let [person (coll/first-where #(= (:staff-id model) (:id %)) (:staff @state))
                                    queue (get-in @state [:sessions id :queue])
                                    actions (tasks/action-ids canvas id)
                                    total (count (or (:actions queue) actions))
                                    completed (count (:completed queue))
                                    profile (doto (HBox. 12.0) (.setAlignment Pos/CENTER_LEFT) (-> .getStyleClass (.add "ai-person-profile")))
                                    identity-box (VBox. 8.0)
                                    name (doto (label (or (:title person) (:staff-name model) "选择职员")) (-> .getStyleClass (.add "ai-card-title")))
                                    role (doto (label (if person (str (:title (roles/role-type person)) "负责人") "待分配")) (-> .getStyleClass (.add "ai-role-chip")))]
                                (.setMinWidth identity-box 0.0)
                                (.addAll (.getChildren identity-box) ^java.util.Collection (identity [name role]))
                                (.addAll (.getChildren profile) ^java.util.Collection (identity [(roles/make-portrait (or person {:id id :role-type "planning"})) identity-box]))
                                (.add (.getChildren content) profile)
                                (.add (.getChildren content) (roles/make-assignment model staff! assign! report! "canvas-staff-"))
                                (.add (.getChildren content) (doto (label (str "任务进度  " completed " / " total "  ·  " (get tasks/status-labels (:status queue) "待执行")))
                                                               (-> .getStyleClass (.add "ai-progress-caption"))))
                                (.add (.getChildren content) (doto (ProgressBar. (if (pos? total) (/ (double completed) total) 0.0))
                                                               (.setMaxWidth Double/MAX_VALUE) (-> .getStyleClass (.add "ai-person-progress"))))
                                (doseq [[index action-id] (eduction (map-indexed vector) actions)]
                                  (.add (.getChildren content)
                                        (task-row (str (inc index) ". " (card-summary (:title (node-by-id canvas action-id)) 14))
                                                  (tasks/status @state action-id) (str "person-task-" action-id) #(select! action-id))))
                                (doseq [result-id (eduction (distinct) (:actions queue))
                                        :when (= :result (:kind (node-by-id canvas result-id)))]
                                  (.add (.getChildren content)
                                        (task-row (str "确认 · " (card-summary (:title (node-by-id canvas result-id)) 12))
                                                  (tasks/status @state result-id) (str "person-confirmation-" result-id) #(select! result-id))))
                                (.add (.getChildren card) (doto (primary-button (case (:status queue) "paused" "继续队列" "completed" "重新执行任务" "按顺序执行") (str "canvas-run-person-" id) #(work! :run id nil))
                                                            (.setDisable (boolean blocked)))))

                              :action
                              (let [files (or (coll/not-empty (get-in @state [:sessions id :attachments])) (:references model))
                                    prompt (label (card-summary (or (:prompt model) (if (= :document (:workflow canvas)) (:brief (template (:template canvas))) "描述你想生成的界面")) 160))
                                    owner (node-by-id canvas (tasks/person-id canvas id))
                                    status (tasks/status @state id)]
                                (.add (.getChildren content) (doto (label (str "负责人  ·  " (if owner (or (:staff-name owner) "待选择职员") "等待连接 Person")))
                                                               (-> .getStyleClass (.add "ai-card-metadata"))))
                                (.add (.getChildren content) (doto (label (get tasks/status-labels status))
                                                               (.setId (str "canvas-status-" id))
                                                               (-> .getStyleClass (.add "ai-status-badge"))
                                                               (.pseudoClassStateChanged (PseudoClass/getPseudoClass "completed") (= "completed" status))))
                                (when (tasks/active? @state id) (.add (.getChildren content) (doto (ProgressBar. -1.0) (.setMaxWidth Double/MAX_VALUE))))
                                (.add (.getStyleClass prompt) "ai-card-summary")
                                (.add (.getChildren content) prompt)
                                (.add (.getChildren content) (doto (label (str "输入依据 · " (count files))) (-> .getStyleClass (.add "ai-section-caption"))))
                                (doseq [file (eduction (take 3) files)] (.add (.getChildren content) (resource-row file nil (:project-root @state) report!)))
                                (.add (.getChildren content)
                                      (doto (button (str "+ 添加参考资料" (when (> (count files) 3) (str " · 共 " (count files) " 项"))) (str "canvas-reference-" id)
                                                    (fn add-reference [] (select! id) (attach!)))
                                        (.setMaxWidth Double/MAX_VALUE) (.setDisable (boolean blocked)) (-> .getStyleClass (.add "ai-card-link"))))
                                (Tooltip/install card (Tooltip. "由 Person 负责人按顺序执行；完整描述在右侧详情")))

                              :result
                              (let [proposal (results/candidate model)
                                    pending (tasks/confirmation-locked? @state id)
                                    points (coll/into-> (string/split-lines (or (:text proposal) "")) []
                                             (filter #(re-find #"^#{2,3}\s+" %)) (take 3) (map #(card-summary % 34)))
                                    inspect! (fn inspect-result [] (select! id) (work! :inspect-card id nil))
                                    footer (HBox. 8.0)]
                                (if proposal
                                  (do
                                    (when-let [preview (:preview proposal)]
                                      (.add (.getChildren content) (file-reveal/install! (image-preview preview 226.0 128.0) (:project-root @state) (constantly proposal) report!)))
                                    (when-not (string/blank? (:text proposal))
                                      (.add (.getChildren content) (doto (label (card-summary (:text proposal) (if (coll/not-empty points) 100 210)))
                                                                     (-> .getStyleClass (.add "ai-card-summary")))))
                                    (doseq [point points]
                                      (.add (.getChildren content) (doto (label (str "•  " point)) (-> .getStyleClass (.add "ai-proposal-point")))))
                                    (.add (.getChildren content)
                                          (doto (label (str (if (:manually-edited model) "人工修改" "Action 产出") " · " (count (:files proposal)) " 项资源"
                                                            (when (= (:confirmed-revision model) (results/revision model)) " · 已选定")))
                                            (-> .getStyleClass (.add "ai-section-caption")))))
                                  (.add (.getChildren content) (empty-card "▧" "等待 Action 产出")))
                                (let [view (doto (primary-button "查看方案 ↗" (str "canvas-inspect-button-" id) inspect!) (.setDisable (nil? proposal)))
                                      confirm (doto (button (if pending "已排队" "选定方案") (str "canvas-confirm-result-" id) #(work! :confirm-result id nil))
                                                (.setMaxWidth Double/MAX_VALUE) (.setMinWidth 0.0) (-> .getStyleClass (.add "ai-card-confirm"))
                                                (.setDisable (boolean (or blocked pending (nil? proposal)))))]
                                  (HBox/setHgrow view Priority/ALWAYS)
                                  (.addAll (.getChildren footer) ^java.util.Collection (identity [view confirm])))
                                (.add (.getChildren card) footer))
                              :gui
                              (do
                                (.add (.getChildren content) (file-reveal/install! (gui-preview-pane @state model 226.0 280.0) (:project-root @state) (constantly (:gui-resource model)) report!))
                                (when-let [path (:gui-resource model)]
                                  (.add (.getChildren content) (file-reveal/install! (label (last (string/split path #"/")))
                                                                                     (:project-root @state) (constantly path) report!)))
                                (let [actions (doto (HBox. 6.0) (.setDisable (boolean blocked)))]
                                  (.addAll (.getChildren actions) ^java.util.Collection
                                           (identity [(button "↗ 打开 GUI" (str "canvas-gui-open-" id) #(gui! :open id nil))
                                                      (button "↻ 刷新" (str "canvas-gui-refresh-" id) #(gui! :refresh id nil))]))
                                  (.add (.getChildren card) actions)))
                              :assets
                              (do
                                (let [result (node-by-id canvas (:from (coll/first-where #(= id (:to %)) (:edges canvas))))]
                                  (doseq [[index candidate] (eduction (map-indexed vector) (:asset-candidates result))]
                                    (when-let [preview (:preview candidate)]
                                      (.add (.getChildren content) (file-reveal/install! (image-preview preview 226.0 140.0) (:project-root @state) (constantly candidate) report!)))
                                    (.add (.getChildren content)
                                          (button (str "采用组件素材 " (inc index)) (str "canvas-components-" id "-" index)
                                                  (fn choose-components [] (change! #(adopt-components % id candidate)) (select! id))))))
                                (let [paths (coll/into-> (into [(:document-output model)] (get-in model [:production :paths])) [] (filter string?) (distinct))
                                      files (into (mapv #(hash-map :path % :name (last (string/split % #"/"))) (sort paths)) assets)
                                      inspect! (fn inspect-assets [] (select! id) (work! :inspect-card id nil))]
                                  (if-not (coll/not-empty files)
                                    (.add (.getChildren content) (empty-card "◇" (if (gui-production? canvas) "选定方案后生成资源" "选定方案后归档文档")))
                                    (doseq [asset (eduction (take 4) files)]
                                      (.add (.getChildren content) (resource-row asset inspect! (:project-root @state) report!))))
                                  (when (coll/not-empty files)
                                    (.add (.getChildren content)
                                          (doto (button (str "查看全部内容 · " (count files) " 项  ↗") (str "canvas-inspect-button-" id) inspect!)
                                            (.setMaxWidth Double/MAX_VALUE) (-> .getStyleClass (.add "ai-card-link"))))))
                                (when-let [output (:production model)]
                                  (.add (.getChildren content) (label (str (:count output) " 个组件 · 已归档")))
                                  (.add (.getChildren content) (button "打开 Atlas" (str "canvas-open-atlas-" id) #(open-output! output :atlas))))
                                (.add (.getChildren card) (doto (primary-button (if (gui-production? canvas) "拆分 / 生成 GUI" (if (:document-output model) "另存文档版本" "归档方案文档")) (str "canvas-produce-" id)
                                                                                (fn produce-assets [] (select! id) (produce! id)))
                                                            (.setDisable (boolean blocked))))))
                            (.addAll (.getChildren card) ^java.util.Collection (identity [port-in port-out]))
                            (.add (.getChildren cards) card))))))
                  ;; Keep nodes while selecting or moving the camera.
                  (doseq [^VBox card (.getChildren cards)]
                    (let [id (.getUserData card)]
                      (.pseudoClassStateChanged card (PseudoClass/getPseudoClass "selected") (= id (get-in @state [:canvas :selected])))))
                  (.setDisable undo (or (tasks/locked? @state) (not (coll/not-empty (:canvas-undo @state)))))
                  (.setDisable redo (or (tasks/locked? @state) (not (coll/not-empty (:canvas-redo @state)))))
                  (draw-links!) (apply-viewport!))
        history! (fn change-history [direction]
                   (if (or (tasks/locked? @state) (coll/not-empty (:busy @state)) (coll/any? :pending (coll/vals (:sessions @state))))
                     (report! "请等待当前任务结束后再撤销或重做")
                     (do
                       (cancel-link!)
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
            (when (= KeyCode/ESCAPE (.getCode e))
              (cancel-link!) (.consume e))
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
    (.addAll (.getChildren world) ^java.util.Collection (identity [role-rows links cards preview]))
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
    (.setOnContextMenuRequested viewport
                                (reify EventHandler
                                  (handle [_ event]
                                    (let [e ^ContextMenuEvent event
                                          point (.sceneToLocal world (.getSceneX e) (.getSceneY e))]
                                      (when-not (= :roles (get-in @state [:canvas :workflow]))
                                        (cancel-link!)
                                        (node-menu! nil (.getX point) (.getY point) (.getScreenX e) (.getScreenY e)))
                                      (.consume e)))))
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
          (when @connecting
            (let [e ^MouseEvent event point (.sceneToLocal world (.getSceneX e) (.getSceneY e))]
              (.setEndX preview (.getX point)) (.setEndY preview (.getY point))
              (.setControlX2 preview (- (.getX point) 55.0)) (.setControlY2 preview (.getY point))
              (.consume e)))
          (when-let [start @panning]
            (let [e ^MouseEvent event]
              (swap! state assoc-in [:canvas :viewport]
                     (-> (:viewport start) (update :x + (- (.getSceneX e) (:x start))) (update :y + (- (.getSceneY e) (:y start)))))
              (apply-viewport!) (.consume e))))))
    (.addEventFilter viewport MouseEvent/MOUSE_RELEASED
      (reify EventHandler
        (handle [_ event]
          (when-let [{:keys [from]} @connecting]
            (let [e ^MouseEvent event
                  point (.sceneToLocal world (.getSceneX e) (.getSceneY e))
                  target (coll/first-where
                           (fn input-at-pointer [node]
                             (when-let [^Button input (.lookup cards (str "#canvas-in-" (:id node)))]
                               (and (.isVisible input)
                                    (.contains (.localToScene input (.getBoundsInLocal input)) (.getSceneX e) (.getSceneY e)))))
                           (get-in @state [:canvas :nodes]))
                  over-card (coll/any? (fn card-at-pointer [^VBox card]
                                         (.contains (.localToScene card (.getBoundsInLocal card)) (.getSceneX e) (.getSceneY e))) (.getChildren cards))]
              (cancel-link!)
              (cond
                target (link! from (:id target))
                over-card (report! "请拖到目标卡片的输入接点，或拖到画布空白处")
                (.contains viewport (.sceneToLocal viewport (.getSceneX e) (.getSceneY e)))
                (node-menu! from (.getX point) (.getY point) (.getScreenX e) (.getScreenY e)))
              (.consume e)))
          (when (and @panning (= MouseButton/MIDDLE (.getButton ^MouseEvent event)))
            (reset! panning nil)
            (.setCursor viewport Cursor/DEFAULT)
            (change! identity)
            (.consume ^MouseEvent event)))))
    (.setOnScroll viewport
                  (reify EventHandler
                    (handle [_ event]
                      (let [e ^ScrollEvent event point (.sceneToLocal viewport (.getSceneX e) (.getSceneY e))]
                        (if (and (= :roles (get-in @state [:canvas :workflow])) (not (.isControlDown e)))
                          (do
                            (change! #(-> % (update-in [:viewport :x] + (.getDeltaX e)) (update-in [:viewport :y] + (.getDeltaY e))))
                            (apply-viewport!))
                          (when (or (.isControlDown e) (= viewport (.getTarget e)))
                            (zoom! (Math/pow 1.0015 (.getDeltaY e)) (.getX point) (.getY point))))
                        (.consume ^ScrollEvent event)))))
    (.addAll (.getChildren toolbar)
             ^java.util.Collection (identity [(label (if (= :roles (get-in @state [:canvas :workflow])) "职能配置" "AI Canvas"))
                                              (doto (label "右键新建节点 · 拖动接点连接")
                                                (.setVisible (not= :roles (get-in @state [:canvas :workflow])))
                                                (.setManaged (not= :roles (get-in @state [:canvas :workflow]))))
                                              (button "−" "canvas-zoom-out" #(zoom! 0.8 0.0 0.0)) zoom-label
                                              (button "+" "canvas-zoom-in" #(zoom! 1.25 0.0 0.0))
                                              (button "适应画布" "canvas-fit" fit!) undo redo
                                              (doto (button "自动整理" "canvas-arrange"
                                                            (fn arrange-cards []
                                                              (when-not (tasks/locked? @state)
                                                                (change! auto-layout)
                                                                (render!) (fit!)
                                                                (report! {:level :info :text "已按执行顺序整理卡片，可撤销恢复原布局"}))))
                                                (.setTooltip (Tooltip. "按负责人、任务和方案分层排列，保留连线与任务顺序"))
                                                (.setVisible (not= :roles (get-in @state [:canvas :workflow])))
                                                (.setManaged (not= :roles (get-in @state [:canvas :workflow]))))
                                              (doto (Region.) (HBox/setHgrow Priority/ALWAYS))]))
    (when (= :roles (get-in @state [:canvas :workflow]))
      (let [search (doto (TextField.) (.setId "ai-role-search") (.setPromptText "搜索职能或人员…") (.setPrefWidth 180.0) (.setMinWidth 110.0))
            add (doto (MenuButton. "+ 添加职员") (.setId "ai-role-add-menu"))]
        (.addListener (.textProperty search)
                      (reify ChangeListener
                        (changed [_ _ _ query]
                          (swap! state assoc :role-search query)
                          (render!))))
        (.setOnShowing add
                       (reify EventHandler
                         (handle [_ _]
                           (.clear (.getItems add))
                           (doseq [{:keys [id title]} (roles/types (:canvas @state))]
                             (.add (.getItems add)
                                   (doto (MenuItem. (str "添加" title "职员"))
                                     (.setOnAction (reify EventHandler
                                                     (handle [_ _]
                                                       (.clear search)
                                                       (change! #(roles/add-role % id))
                                                       (select! (get-in @state [:canvas :selected])))))))))))
        (.addAll (.getChildren toolbar) ^java.util.Collection (identity [search add]))))
    (.add (.getStyleClass toolbar) "ai-toolbar")
    (.setMaxHeight dock Region/USE_PREF_SIZE)
    (.setMaxWidth dock 640.0)
    (.setAlignment dock Pos/CENTER)
    (StackPane/setAlignment dock Pos/BOTTOM_CENTER)
    (StackPane/setMargin dock (Insets. 16.0))
    (StackPane/setAlignment (:root notifications) Pos/TOP_CENTER)
    (StackPane/setMargin (:root notifications) (Insets. 12.0))
    (.addAll (.getChildren surface) ^java.util.Collection (identity [viewport dock (:root notifications)]))
    (.setTop root toolbar) (.setCenter root surface)
    (render!)
    {:root root :toolbar toolbar :dock dock :render! render! :fit! fit! :focus-node! focus-node!
     :show-toast! (:show! notifications) :resolve-toast! (:resolve! notifications) :dispose! (:dispose! notifications)}))
