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

(ns editor.ai-roles
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.craft-project :as craft-project]
            [util.coll :as coll])
  (:import [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.util UUID]
           [javafx.beans.value ChangeListener]
           [javafx.css PseudoClass]
           [javafx.event EventHandler]
           [javafx.geometry Pos]
           [javafx.scene.control Button CheckBox ComboBox Label MenuButton MenuItem ScrollPane TextArea TextField Tooltip]
           [javafx.scene.layout BorderPane HBox Pane Priority Region VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape Rectangle SVGPath]))

(set! *warn-on-reflection* true)

(def document-path "/ai-workspace/config/project-roles.aicanvas")
(def config-path "/ai-workspace/config/role-types.json")

(def card-width 392.0)
(def card-height 132.0)
(def row-height 164.0)
(def row-label-width 156.0)
(def column-step 416.0)

(def role-types
  [{:id "planning" :title "策划" :color "#5B8CFF"}
   {:id "art" :title "美术" :color "#AD7BFF"}
   {:id "programming" :title "程序" :color "#48C78E"}
   {:id "pm" :title "PM" :color "#E6AF57"}
   {:id "qa" :title "QA" :color "#F0788C"}])

(defn role-type [node]
  (or (::definition (meta node))
      (coll/first-where #(= (:id %) (:role-type node)) role-types)))

(def name-pools
  {"planning" ["林知远" "沈星河" "顾言" "许知夏" "江予安" "程思源" "陆云舟" "苏念" "叶知秋" "周书宁" "温以辰" "宋清和"]
   "art" ["唐映月" "夏予晴" "林绘" "白若溪" "苏晚棠" "顾青禾" "叶初雪" "江染" "温星澜" "许如墨" "沈云舒" "程景色"]
   "programming" ["陈知行" "陆明川" "周亦航" "林序" "许言初" "顾承宇" "江远帆" "沈墨" "叶思齐" "程予恒" "苏可为" "唐启明"]
   "pm" ["顾思衡" "林予宁" "周景和" "沈安然" "许时宜" "江致远" "陆知微" "程慕白" "苏以诺" "唐正则" "叶嘉言" "温明序"]
   "qa" ["许明镜" "林知微" "沈若真" "顾谨言" "周见微" "江清越" "陆思辨" "程一诺" "苏望舒" "唐知予" "叶明澈" "温书言"]})

(defn- random-name [type used-names]
  (let [pool (or (:namePool type) (get name-pools (:id type)))
        available (filterv #(not (contains? used-names %)) pool)]
    (if-let [available (coll/not-empty available)]
      (rand-nth available)
      (let [base (rand-nth pool)]
        (loop [suffix 2]
          (let [candidate (str base "·" suffix)]
            (if-not (contains? used-names candidate)
              candidate
              (recur (inc suffix)))))))))

(def role-defaults
  {"planning"
   {:responsibilities (str "定义核心玩法、系统规则与玩家体验目标，拆解需求和优先级。\n"
                           "梳理流程、数值与配置需求，明确边界条件和验收标准。\n"
                           "与美术、程序和 QA 对齐方案，跟进实现效果并根据反馈迭代。")
    :deliverables (str "提交可追溯的玩法或系统设计文档，包含目标、规则、流程与边界情况。\n"
                       "提供数值或配置说明、资源需求清单和可验证的验收条目。\n"
                       "标明关联项目文件、变更点、依赖及待确认问题，确保下游可据此制作与实现。")}
   "art"
   {:responsibilities (str "依据玩法需求确定视觉风格，设计角色、场景、界面及相关视觉资源。\n"
                           "维护项目视觉一致性，按照资源规格完成制作、整理与迭代。\n"
                           "与策划和程序对齐表现效果、导入方式及性能预算。")
    :deliverables (str "提交设计稿或预览图、可编辑源文件和可导入引擎的资源。\n"
                       "注明文件路径、命名、尺寸、格式、透明通道及必要的切图或动画说明。\n"
                       "附效果核对结果、资源来源与使用授权说明，并标记待替换素材和未完成项。")}
   "programming"
   {:responsibilities (str "依据设计与验收标准实现游戏功能、交互和工具，维护项目代码与资源集成。\n"
                           "处理数据、状态和生命周期，排查缺陷并关注性能、兼容性与可维护性。\n"
                           "与策划、美术和 QA 对齐接口、依赖及实现边界，及时反馈技术风险。")
    :deliverables (str "提交可审查的源代码及必要资源修改，列明变更文件、功能行为和使用方式。\n"
                       "完成与改动相关的构建或测试，提供复现步骤及实际验证结果。\n"
                       "说明配置或迁移要求、已知限制与未验证环境；交付可供 QA 验收的功能。")}
   "pm"
   {:responsibilities (str "将项目目标拆解为里程碑和可执行任务，明确负责人、优先级及时间安排。\n"
                           "协调跨职能依赖，跟踪进度、风险和阻塞，推动问题解决与范围确认。\n"
                           "组织评审和交付验收，维护项目决策及变更记录。")
    :deliverables (str "维护项目计划与任务清单，包含负责人、优先级、预期时间、依赖和验收条件。\n"
                       "提交进度更新、风险与阻塞清单，列明处理方案及需要确认的决策。\n"
                       "汇总里程碑交付物、评审结论和后续行动，保证任务与相关项目文件可追溯。")}
   "qa"
   {:responsibilities (str "依据需求和验收标准制定测试范围，覆盖核心流程、边界与异常情况。\n"
                           "执行功能、回归及必要的兼容性和性能检查，记录可复现的问题。\n"
                           "跟踪缺陷修复并复测，向相关职员反馈质量风险与验收结果。")
    :deliverables (str "提交测试用例和测试报告，注明版本、环境、范围及通过或失败结果。\n"
                       "缺陷记录包含复现步骤、预期与实际表现、严重程度及日志或截图等证据。\n"
                       "给出复测结果、未关闭问题与未覆盖范围，明确是否满足验收条件。")}})

(defn default-config []
  {:format "codexcamp.role-types" :schemaVersion 1
   :roles (mapv (fn default-type [type]
                  (assoc type :icon (:id type) :portrait (:id type)
                         :namePool (get name-pools (:id type)) :initialCount 1
                         :defaults (merge {:assignee "" :model "Sol" :effort "高" :enabled true}
                                          (get role-defaults (:id type))))) role-types)})

(defn validate-config [config]
  (when-not (and (= "codexcamp.role-types" (:format config)) (= 1 (:schemaVersion config))
                 (vector? (:roles config)) (<= 1 (count (:roles config)) 64)
                 (= (count (:roles config)) (count (set (mapv :id (:roles config))))))
    (throw (ex-info "职能配表需要 format=codexcamp.role-types、schemaVersion=1 和 1～64 个 ID 不重复的职能。" {})))
  (doseq [{:keys [id title color icon portrait namePool initialCount defaults]} (:roles config)]
    (when-not (and (string? id) (re-matches #"[a-z][a-z0-9_-]{0,63}" id)
                   (string? title) (<= 1 (count (string/trim title)) 80)
                   (string? color) (re-matches #"#[a-fA-F0-9]{6}" color)
                   (contains? #{"planning" "art" "programming" "pm" "qa" "person" "model" "document" "status"} icon)
                   (contains? #{"planning" "art" "programming" "pm" "qa"} portrait)
                   (vector? namePool) (<= 1 (count namePool) 500)
                   (coll/every? #(and (string? %) (<= 1 (count (string/trim %)) 60)) namePool)
                   (integer? initialCount) (<= 0 initialCount 20)
                   (coll/every? #(and (string? (get defaults %)) (<= (count (get defaults %)) 16000))
                                [:assignee :model :effort :responsibilities :deliverables])
                   (not (string/blank? (:model defaults)))
                   (contains? #{"低" "中" "高" "极高"} (:effort defaults))
                   (boolean? (:enabled defaults)))
      (throw (ex-info (str "职能配表字段无效：" id "。请检查名称、#RRGGBB 颜色、图标/头像、姓名池、初始人数和 defaults。") {:role-id id}))))
  config)

(defn read-config [root]
  (let [file (craft-project/resource-file root config-path)]
    (io/make-parents file)
    (when-not (.exists file)
      (try
        (Files/writeString (.toPath file) (with-out-str (json/pprint (default-config) :escape-unicode false))
                           (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))
        (catch java.nio.file.FileAlreadyExistsException _ nil)))
    (when (> (.length file) (* 1024 1024)) (throw (ex-info "职能配表不能超过 1 MB" {})))
    (validate-config (json/read-str (slurp file :encoding "UTF-8") :key-fn keyword))))

(defn save-config! [root expected text]
  (when (> (alength (.getBytes ^String text "UTF-8")) (* 1024 1024))
    (throw (ex-info "职能配表不能超过 1 MB" {})))
  (let [config (validate-config (json/read-str text :key-fn keyword))
        file (craft-project/resource-file root config-path)
        staff-file (craft-project/resource-file root document-path)
        ids (set (mapv :id (:roles config)))]
    (when-not (= expected (slurp file :encoding "UTF-8"))
      (throw (ex-info "职能配表已被外部修改，请先重载；本次未覆盖文件。" {})))
    (when (.isFile staff-file)
      (doseq [node (:nodes (json/read-str (slurp staff-file :encoding "UTF-8") :key-fn keyword))
              :when (and (:role-type node) (not (contains? ids (:role-type node))))]
        (throw (ex-info (str "职能仍有职员使用，不能删除或修改 ID：" (:role-type node)) {}))))
    (let [temp (Files/createTempFile (.toPath (.getParentFile file)) "roles-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (Files/writeString temp text (make-array StandardOpenOption 0))
        (Files/move temp (.toPath file) (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
        (finally (Files/deleteIfExists temp))))
    config))

(defn types [model]
  (or (:role-types model) (:roles (default-config))))

(defn apply-defaults [model]
  (update model :nodes
          (fn fill-staff-defaults [nodes]
            (mapv (fn fill-empty-fields [node]
                    (reduce-kv (fn fill-field [node field value]
                                 (if (contains? node field) node (assoc node field value)))
                               node (or (:defaults (role-type node)) (get role-defaults (:role-type node)))))
                  nodes))))

(defn new-role [type-id used-names]
  (let [type (if (map? type-id) type-id (coll/first-where #(= type-id (:id %)) (:roles (default-config))))]
    (when-not type (throw (ex-info "未知职能，请检查职能配表。" {:role-id type-id})))
    (with-meta (merge (:defaults type)
                      {:id (str "card_" (UUID/randomUUID)) :kind :role :role-type (:id type)
                       :title (random-name type used-names)}) {::definition type})))

(defn initial-nodes []
  (reduce (fn add-initial-staff [nodes type]
            (conj nodes (new-role (:id type) (coll/into-> nodes #{} (map :title)))))
          [] role-types))

(defn assign-staff-names [model]
  (let [used-names (coll/into-> (:nodes model) #{} (map :title))]
    (assoc model :nodes
           (:nodes (reduce (fn name-placeholder [{:keys [used] :as acc} node]
                             (let [title (:title node)
                                   node (if (or (string/blank? title) (= title (:title (role-type node))))
                                          (assoc node :title (random-name (role-type node) used))
                                          node)]
                               (-> acc (update :nodes conj node) (update :used conj (:title node)))))
                           {:used used-names :nodes []} (:nodes model))))))

(def ai-pm-id "ai-pm")

(defn ai-pm-card [catalog]
  (let [type (or (coll/first-where #(= "pm" (:id %)) catalog) (first catalog))]
    (with-meta (merge (:defaults type)
                      {:id ai-pm-id :kind :role :role-type (:id type) :title "AI-PM"
                       :builtin true :enabled true})
      {::definition type})))

;; AI-PM is a permanent planning identity. Production staff are assigned
;; explicitly; this identity cannot be removed from the project catalog.
(defn ensure-ai-pm [model]
  (let [nodes (:nodes model)
        ai-pm (coll/first-where #(or (:builtin %) (= ai-pm-id (:id %))) nodes)]
    (if ai-pm
      (assoc model :nodes (conj (vec (filterv #(not= (:id ai-pm) (:id %)) nodes)) ai-pm))
      (update model :nodes #(conj (vec %) (ai-pm-card (types model)))))))

(defn layout
  ([model] (layout model true))
  ([model ensure?]
   (let [model (if ensure? (ensure-ai-pm model) model)
         catalog (types model)
         nodes (mapv (fn migrate-role [index node]
                       (let [type (if (:role-type node)
                                    (or (coll/first-where #(= (:id %) (:role-type node)) catalog)
                                        (throw (ex-info (str "职能配表缺少已使用的职能：" (:role-type node)) {})))
                                    (or (when (= "制作人" (:title node)) (coll/first-where #(= "pm" (:id %)) catalog))
                                        (coll/first-where #(string/starts-with? (:title node) (:title %)) catalog)
                                        (get catalog index) (first catalog)))]
                         (with-meta (cond-> (assoc node :role-type (:id type))
                                      (= "制作人" (:title node)) (assoc :title "PM")) {::definition type})))
                     (range (count (:nodes model))) (:nodes model))
         positions (coll/into-> catalog {} (map-indexed (fn row-position [index type] [(:id type) (* row-height index)])))]
     (assoc model :nodes
            (:nodes (reduce (fn place-card [{:keys [counts] :as acc} node]
                              (let [type-id (:role-type node)
                                    column (get counts type-id 0)]
                                (-> acc
                                    (update :nodes conj (assoc node :x (+ row-label-width (* column-step column)) :y (get positions type-id)))
                                    (assoc-in [:counts type-id] (inc column)))))
                            {:counts {} :nodes []} nodes))))))

(defn rows [model]
  (let [catalog (types model)
        counts (frequencies (mapv :role-type (:nodes model)))]
    (mapv (fn describe-row [index type]
            (assoc type :count (get counts (:id type) 0)
                   :x (+ row-label-width (* column-step (get counts (:id type) 0))) :y (* row-height index)))
          (range (count catalog)) catalog)))

(defn visible-model [model query]
  (let [query (string/lower-case (string/trim (or query "")))]
    (if (string/blank? query)
      model
      (layout (update model :nodes
                      (fn matching-staff [nodes]
                        (filterv #(string/includes?
                                    (string/lower-case (str (:title %) " " (:assignee %) " " (:model %) " " (:title (role-type %))))
                                    query) nodes)))
              false))))

(defn validate-role [role]
  (when-not (and (string? (:title role)) (<= 1 (count (string/trim (:title role))) 80)
                 (coll/every? #(and (string? (get role %)) (<= (count (get role %)) 16000))
                              [:assignee :model :effort :responsibilities :deliverables])
                 (not (string/blank? (:model role)))
                 (contains? #{"低" "中" "高" "极高"} (:effort role))
                 (or (nil? (:role-type role)) (and (string? (:role-type role)) (re-matches #"[a-z][a-z0-9_-]{0,63}" (:role-type role))))
                 (boolean? (:enabled role)))
    (throw (ex-info "请填写职员名称和模型，并选择思考强度；名称最多 80 字，其他字段最多 16000 字。" {})))
  role)

(defn add-role [model type-id]
  (let [type (coll/first-where #(= type-id (:id %)) (types model))
        _ (when-not type (throw (ex-info "职能已从配表移除，请重新打开职能菜单。" {})))
        node (new-role type (coll/into-> (:nodes model) #{} (map :title)))]
    (-> model
        (update :nodes conj node)
        layout
        (assoc :selected (:id node)))))

(defn make-portrait [card]
  (let [{:keys [title color portrait]} (role-type card)
        type-id (or portrait (:role-type card))
        pane (doto (Pane.)
               (.setId (str "ai-role-portrait-" (:id card)))
               (.setAccessibleText (str title "人物形象"))
               (.setMinSize 80.0 100.0) (.setPrefSize 80.0 100.0) (.setMaxSize 80.0 100.0)
               (.setMouseTransparent true))
        skin (case type-id "art" "#EAC0A4" "programming" "#B97F5E" "pm" "#D6A079" "#E8B991")
        hair (case type-id "art" "#674662" "pm" "#555866" "qa" "#694B3D" "#293448")
        path! (fn add-portrait-path [shape fill]
                (.add (.getChildren pane) (doto (SVGPath.) (.setContent shape) (.setFill (Color/web fill)))))]
    (.add (.getChildren pane) (doto (Rectangle. 80.0 100.0 (Color/web color 0.16)) (.setArcWidth 16.0) (.setArcHeight 16.0)))
    (.setClip pane (doto (Rectangle. 80.0 100.0) (.setArcWidth 16.0) (.setArcHeight 16.0)))
    (when (= "art" type-id)
      (path! "M18 50 Q10 10 40 8 Q70 10 62 60 L68 88 L12 88 Z" hair))
    (path! "M6 102 L10 84 Q14 71 31 69 L49 69 Q66 71 70 84 L74 102 Z" color)
    (path! "M32 58 L48 58 L49 73 Q40 83 31 73 Z" skin)
    (path! "M22 32 Q22 13 40 13 Q58 13 58 32 L56 49 Q53 64 40 67 Q27 64 24 49 Z" skin)
    (path! (case type-id
             "art" "M20 37 Q14 10 39 9 Q65 10 60 39 L51 25 Q35 35 25 26 L24 43 Z"
             "pm" "M21 35 Q17 12 38 11 Q65 11 59 39 L53 24 L29 25 L25 40 Z"
             "qa" "M21 37 L20 21 Q38 7 58 22 L59 39 L52 29 L28 28 L25 40 Z"
             "M21 37 L19 20 L26 22 L29 10 L38 15 L48 9 L60 21 L58 38 L51 29 L27 28 L25 41 Z") hair)
    (path! "M29 42 A2 2 0 1 0 33 42 A2 2 0 1 0 29 42 M47 42 A2 2 0 1 0 51 42 A2 2 0 1 0 47 42" "#273044")
    (path! "M34 54 Q40 58 46 54 Q40 63 34 54 Z" "#A15F57")
    (case type-id
      "planning" (do (path! "M24 76 L30 72 L40 85 L50 72 L56 76 L40 94 Z" "#DCE8FF")
                     (path! "M55 86 L65 86 L65 98 L55 98 Z" "#F5F3E7"))
      "art" (do (path! "M18 21 Q12 7 35 5 Q61 3 65 20 L57 24 L20 25 Z" "#CC9CE8")
                (path! "M55 81 L59 80 L65 99 L61 100 Z" "#F4D394"))
      "programming" (do (path! "M24 37 L37 37 L37 47 L24 47 Z M43 37 L56 37 L56 47 L43 47 Z M37 40 L43 40 L43 43 L37 43 Z" "#25394C")
                        (path! "M26 39 L35 39 L35 45 L26 45 Z M45 39 L54 39 L54 45 L45 45 Z" "#BFDADB")
                        (path! "M24 76 L29 72 L34 91 L30 91 Z M56 76 L51 72 L46 91 L50 91 Z" "#D6F4E5"))
      "pm" (do (path! "M29 71 L40 81 L51 71 L48 99 L32 99 Z" "#FFF0DA")
               (path! "M37 81 L43 81 L42 87 L45 99 L35 99 L38 87 Z" "#354157"))
      "qa" (do (path! "M20 25 L20 18 Q41 3 60 18 L60 25 Z M18 25 L65 25 L65 30 L18 30 Z" "#F5B1BB")
               (path! "M54 82 L66 85 L65 95 L60 99 L55 95 Z" "#FBE3E8")
               (path! "M57 90 L59 92 L63 87 L64 89 L59 95 L56 92 Z" "#9F4157")))
    pane))

(defn make-icon [kind]
  (doto (SVGPath.)
    (.setContent (case kind
                   "planning" "M5 7 L19 7 L22 18 L18 19 L15 15 L9 15 L6 19 L2 18 Z M6 9 L6 13 M4 11 L8 11 M16 10 L17 10 M19 13 L20 13"
                   "art" "M12 2 C0 2 0 20 10 21 C16 22 8 15 15 15 C25 16 25 2 12 2 Z M6 8 L7 8 M10 5 L11 5 M16 6 L17 6 M19 10 L20 10"
                   "programming" "M7 6 L1 12 L7 18 M17 6 L23 12 L17 18 M14 3 L10 21"
                   "pm" "M12 2 L23 8 L12 14 L1 8 Z M1 12 L12 18 L23 12 M1 16 L12 22 L23 16"
                   "qa" "M12 2 L21 6 L20 15 L12 22 L4 15 L3 6 Z M7 12 L11 16 L17 8"
                   "person" "M16 6 A4 4 0 1 1 8 6 A4 4 0 1 1 16 6 M4 22 L4 18 Q4 13 12 13 Q20 13 20 18 L20 22 Z"
                   "model" "M12 2 L22 7 L22 17 L12 22 L2 17 L2 7 Z M2 7 L12 12 L22 7 M12 12 L12 22 M7 4 L17 10"
                   "document" "M6 4 L18 4 L20 6 L20 22 L4 22 L4 6 Z M9 2 L15 2 L15 7 L9 7 Z M8 12 L16 12 M8 17 L14 17"
                   "status" "M1 12 L6 12 L9 3 L13 21 L16 10 L19 12 L23 12"))
    (-> .getStyleClass (.add "ai-role-icon"))))

(defn- status-badge
  ^Label [enabled]
  (doto (Label. (if enabled "● 已启用" "○ 已停用"))
    (.pseudoClassStateChanged (PseudoClass/getPseudoClass "enabled") enabled)
    (-> .getStyleClass (.add "ai-role-badge"))))

(defn make-row-heading [{:keys [id title color icon count y]}]
  (let [root (HBox. 12.0)
        bar (doto (Rectangle. 3.0 76.0) (.setArcWidth 3.0) (.setArcHeight 3.0) (.setFill (Color/web color)))
        column (VBox. 9.0)
        heading (doto (Label. title) (.setId (str "canvas-role-row-" id)) (.setGraphic (make-icon (or icon "person"))))
        count-label (doto (Label. (str count " 人")) (.setId (str "canvas-role-count-" id)))]
    (.setStyle root (str "-ai-accent: " color ";"))
    (.setLayoutX root 0.0) (.setLayoutY root (+ y 24.0))
    (.setAlignment root Pos/CENTER_LEFT)
    (.setAlignment column Pos/CENTER_LEFT)
    (.add (.getStyleClass heading) "ai-role-row-title")
    (.add (.getStyleClass count-label) "ai-role-count")
    (.addAll (.getChildren column) ^java.util.Collection (identity [heading count-label]))
    (.addAll (.getChildren root) ^java.util.Collection (identity [bar column]))
    root))

(defn make-card-summary [card more]
  (let [root (HBox. 14.0)
        summary (VBox. 11.0)
        top (HBox. 8.0)
        title (doto (Label. (:title card)) (.setMaxWidth 130.0) (.setTooltip (Tooltip. (:title card))))
        spacer (Region.)
        meta-row (HBox. 10.0)
        assignee (if (string/blank? (:assignee card)) "待分配人员" (:assignee card))
        owner (doto (Label. assignee) (.setMaxWidth 130.0) (.setTooltip (Tooltip. assignee)) (.setGraphic (make-icon "person")))
        model (doto (Label. (str (:model card) " · " (:effort card))) (.setMaxWidth 130.0) (.setGraphic (make-icon "model")))
        text (if (string/blank? (:responsibilities card)) "尚未配置职责说明" (:responsibilities card))
        description (doto (Label. (string/replace text #"\s+" " ")) (.setMaxWidth 268.0) (.setTooltip (Tooltip. text)) (.setGraphic (make-icon "document")))]
    (.setAlignment root Pos/CENTER_LEFT)
    (.setAlignment top Pos/CENTER_LEFT)
    (.setAlignment meta-row Pos/CENTER_LEFT)
    (.add (.getStyleClass title) "ai-role-card-title")
    (.add (.getStyleClass description) "ai-role-description")
    (.add (.getStyleClass meta-row) "ai-role-metadata")
    (HBox/setHgrow spacer Priority/ALWAYS)
    (HBox/setHgrow summary Priority/ALWAYS)
    (.setMinWidth summary 0.0)
    (.addAll (.getChildren top) ^java.util.Collection (identity [title (status-badge (:enabled card)) spacer more]))
    (.addAll (.getChildren meta-row) ^java.util.Collection (identity [owner model]))
    (.addAll (.getChildren summary) ^java.util.Collection (identity [top meta-row description]))
    (.addAll (.getChildren root) ^java.util.Collection (identity [(make-portrait card) summary]))
    root))

(defn make-editor [card catalog save!]
  (let [root (BorderPane.)
        pane (VBox. 12.0)
        scroll (doto (ScrollPane. pane) (.setFitToWidth true) (.setHbarPolicy javafx.scene.control.ScrollPane$ScrollBarPolicy/NEVER))
        title (doto (TextField. (:title card)) (.setId "ai-role-title"))
        role (doto (ComboBox.) (.setId "ai-role-type") (.setMaxWidth Double/MAX_VALUE))
        assignee (doto (TextField. (:assignee card)) (.setId "ai-role-assignee") (.setPromptText "负责人或人员标识"))
        model (doto (ComboBox.) (.setId "ai-role-model") (.setEditable true) (.setMaxWidth Double/MAX_VALUE))
        effort (doto (ComboBox.) (.setId "ai-role-effort") (.setMaxWidth Double/MAX_VALUE))
        enabled (doto (CheckBox. "启用此职员") (.setId "ai-role-enabled") (.setSelected (:enabled card)))
        responsibilities (doto (TextArea. (:responsibilities card)) (.setId "ai-role-responsibilities") (.setWrapText true) (.setPrefRowCount 3) (.setPromptText "主要职责、工作范围与核心目标…"))
        deliverables (doto (TextArea. (:deliverables card)) (.setId "ai-role-deliverables") (.setWrapText true) (.setPrefRowCount 3) (.setPromptText "交付物、质量标准与格式要求…"))
        apply-defaults (doto (Button. "应用所选职能的默认参数") (.setId "ai-role-apply-defaults"))
        status (status-badge (:enabled card))
        error (doto (Label.) (.setId "ai-role-feedback") (.setWrapText true))
        save (doto (Button. "✓  保存配置") (.setId "ai-role-save") (.setMaxWidth Double/MAX_VALUE))
        reset (doto (Button. "重置") (.setId "ai-role-reset") (.setTooltip (Tooltip. "恢复为这张职员卡已保存的配置")))
        actions (HBox. 12.0)
        footer (VBox. 6.0)
        section! (fn add-section [kind heading]
                   (let [section (VBox. 10.0)
                         label (doto (Label. heading) (.setGraphic (make-icon kind)))]
                     (.add (.getStyleClass section) "ai-role-section")
                     (.add (.getStyleClass label) "ai-role-section-title")
                     (.add (.getChildren section) label)
                     (.add (.getChildren pane) section)
                     section))
        field! (fn add-field [^VBox section text field]
                 (let [row (HBox. 12.0)
                       label (doto (Label. text) (.setMinWidth 96.0) (.setPrefWidth 96.0))]
                   (.setAlignment row Pos/CENTER_LEFT)
                   (HBox/setHgrow field Priority/ALWAYS)
                   (.addAll (.getChildren row) ^java.util.Collection (identity [label field]))
                   (.add (.getChildren section) row)))
        text-section! (fn add-text-section [kind heading ^TextArea input]
                        (let [section ^VBox (section! kind heading)
                              counter (doto (Label. (str (.getLength input) " 字")) (.setMaxWidth Double/MAX_VALUE) (.setAlignment Pos/CENTER_RIGHT))]
                          (.add (.getStyleClass counter) "ai-muted")
                          (.addAll (.getChildren section) ^java.util.Collection (identity [input counter]))
                          (.addListener (.textProperty input)
                                        (reify ChangeListener
                                          (changed [_ _ _ text] (.setText counter (str (count text) " 字")))))))
        defaults (atom card)]
    (.add (.getStyleClass root) "ai-role-editor")
    (.add (.getStyleClass scroll) "ai-role-form-scroll")
    (.add (.getStyleClass pane) "ai-role-form")
    (.add (.getStyleClass footer) "ai-role-form-footer")
    (.add (.getStyleClass save) "ai-role-save")
    (.add (.getStyleClass error) "ai-muted")
    (.addAll (.getItems model) ^java.util.Collection (identity ["Sol" "Astra" "Terra"]))
    (.addAll (.getItems role) ^java.util.Collection (mapv #(str (:title %) " [" (:id %) "]") catalog))
    (.select (.getSelectionModel role) (int (.indexOf ^java.util.List (mapv :id catalog) (:role-type card))))
    (.setValue model (:model card))
    (.addAll (.getItems effort) ^java.util.Collection (identity ["低" "中" "高" "极高"]))
    (.setValue effort (:effort card))
    (let [header (HBox. 10.0)
          label (doto (Label. (str (:title (role-type card)) " · 职员配置")) (.setGraphic (make-icon "person")) (.setMaxWidth Double/MAX_VALUE))]
      (HBox/setHgrow label Priority/ALWAYS)
      (.add (.getStyleClass header) "ai-role-form-heading")
      (.setAlignment header Pos/CENTER_LEFT)
      (.addAll (.getChildren header) ^java.util.Collection (identity [label status]))
      (.setTop root header))
    (let [section ^VBox (section! "person" "基本信息")]
      (field! section "职能 *" role)
      (field! section "职员名称 *" title)
      (field! section "负责人" assignee)
      (.add (.getChildren section) apply-defaults))
    (let [section ^VBox (section! "model" "模型设置")]
      (field! section "智能体模型 *" model)
      (field! section "思考强度 *" effort)
      (.add (.getChildren section) (doto (Label. "更高的思考强度通常需要更长的响应时间。") (.setWrapText true) (-> .getStyleClass (.add "ai-muted")))))
    (text-section! "document" "职责说明" responsibilities)
    (text-section! "qa" "交付要求" deliverables)
    (.add (.getChildren ^VBox (section! "status" "状态")) enabled)
    (.addListener (.selectedProperty enabled)
                  (reify ChangeListener
                    (changed [_ _ _ active]
                      (.setText status (if active "● 已启用" "○ 已停用"))
                      (.pseudoClassStateChanged status (PseudoClass/getPseudoClass "enabled") active))))
    (.setOnAction apply-defaults
                  (reify EventHandler
                    (handle [_ _]
                      (let [values (:defaults (nth catalog (.getSelectedIndex (.getSelectionModel role))))]
                        (.setText assignee (:assignee values))
                        (.setValue model (:model values))
                        (.setText (.getEditor model) (:model values))
                        (.setValue effort (:effort values))
                        (.setText responsibilities (:responsibilities values))
                        (.setText deliverables (:deliverables values))
                        (.setSelected enabled (:enabled values))
                        (.setText error "已填入配表默认参数，保存后生效")))))
    (.setOnAction reset
                  (reify EventHandler
                    (handle [_ _]
                      (let [saved @defaults]
                        (.select (.getSelectionModel role) (int (.indexOf ^java.util.List (mapv :id catalog) (:role-type saved))))
                        (.setText title (:title saved))
                        (.setText assignee (:assignee saved))
                        (.setValue model (:model saved))
                        (.setText (.getEditor model) (:model saved))
                        (.setValue effort (:effort saved))
                        (.setText responsibilities (:responsibilities saved))
                        (.setText deliverables (:deliverables saved))
                        (.setSelected enabled (:enabled saved))
                        (.setText error "已恢复为保存的配置")))))
    (.setOnAction save
                  (reify EventHandler
                    (handle [_ _]
                      (try
                        (let [updated (validate-role
                                        (assoc card :title (string/trim (.getText title))
                                               :role-type (:id (nth catalog (.getSelectedIndex (.getSelectionModel role))))
                                               :assignee (string/trim (.getText assignee))
                                               :model (string/trim (.getText (.getEditor model)))
                                               :effort (.getValue effort) :enabled (.isSelected enabled)
                                               :responsibilities (.getText responsibilities) :deliverables (.getText deliverables)))]
                          (save! (:id card) (select-keys updated [:role-type :title :assignee :model :effort :enabled :responsibilities :deliverables]))
                          (reset! defaults updated)
                          (.setText error "已保存"))
                        (catch Exception e (.setText error (.getMessage e)))))))
    (HBox/setHgrow save Priority/ALWAYS)
    (.addAll (.getChildren actions) ^java.util.Collection (identity [reset save]))
    (.addAll (.getChildren footer) ^java.util.Collection (identity [error actions]))
    (when (:builtin card)
      (doseq [^javafx.scene.Node node [title role assignee model effort enabled responsibilities deliverables]]
        (.setDisable node true))
      (doseq [^javafx.scene.Node node [apply-defaults reset save]]
        (.setVisible node false)
        (.setManaged node false))
      (.setText error "AI-PM 是内置职员，配置固定不可修改；新建节点会自动指派它。"))
    (.setCenter root scroll)
    (.setBottom root footer)
    root))

(defn assigned-staff [card staff]
  (when-let [id (:staff-id card)]
    (let [person (coll/first-where #(= id (:id %)) staff)]
      (when-not (:enabled person)
        (throw (ex-info "指派的职员已停用或删除，请重新选择职员" {:staff-id id})))
      person)))

(defn assign-staff [card person]
  (if-not person
    (dissoc card :staff-id :staff-name :staff-role)
    (do
      (when-not (:enabled person) (throw (ex-info "只能指派已启用的职员" {})))
      (assoc card :staff-id (:id person) :staff-name (:title person) :staff-role (:role-type person)))))

(defn request-settings [person models]
  (when person
    (let [requested (:model person)
          family (string/lower-case requested)
          catalog (filterv #(not (:hidden %)) models)
          exact (coll/first-where #(or (= requested (:id %)) (= requested (:model %))) catalog)
          matches (filterv #(and (contains? #{"sol" "astra" "terra"} family)
                                 (string/ends-with? (or (:model %) (:id %) "") (str "-" family))) catalog)
          model (or exact (coll/first-where :isDefault matches) (first matches))
          effort (get {"低" "low" "中" "medium" "高" "high" "极高" "xhigh"} (:effort person))]
      (when-not model (throw (ex-info (str "职员模型不可用：" requested "，请在项目职能中修改") {})))
      (when-not (coll/any? #(= effort (:reasoningEffort %)) (:supportedReasoningEfforts model))
        (throw (ex-info "当前模型不支持职员设置的思考强度，请在项目职能中修改" {})))
      {:model (or (:model model) (:id model)) :effort effort})))

(defn make-assignment [card staff! assign! report! prefix]
  (let [person (coll/first-where #(= (:staff-id card) (:id %)) (staff!))
        picker (doto (MenuButton. (if (:staff-id card)
                                    (str (or (:title (role-type person)) (:staff-role card)) " · " (or (:title person) (:staff-name card)))
                                    "选择职员安排工作"))
                 (.setId (str prefix (:id card))) (.setMaxWidth Double/MAX_VALUE))]
    (.setTooltip picker (Tooltip. "按职员职责和交付要求执行本流程，继承职员模型与思考强度"))
    (.setOnShowing picker
                   (reify EventHandler
                     (handle [_ _]
                       (.clear (.getItems picker))
                       (try
                         (doseq [person (coll/into-> (staff!) [nil] (filter :enabled))]
                           (let [item (MenuItem. (if person
                                                   (str (:title (role-type person)) " · " (:title person) "  /  " (:model person) " · " (:effort person))
                                                   "不指定职员"))]
                             (.setOnAction item (reify EventHandler
                                                  (handle [_ _]
                                                    (try (assign! (:id card) (:id person))
                                                         (catch Exception e (report! (.getMessage e)))))))
                             (.add (.getItems picker) item)))
                         (catch Exception e (report! (.getMessage e)))))))
    picker))
