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

(ns editor.ai-governance-view
  "Native local governance tools with review-before-write plans."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-backup :as backup]
            [editor.ai-file-ops :as files]
            [editor.ai-templates :as templates]
            [editor.file-reveal :as reveal]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.geometry Insets Orientation]
           [javafx.scene Scene]
           [javafx.scene.control Button CheckBox Label ListView SplitPane Tab TabPane TextArea TextField]
           [javafx.scene.layout BorderPane HBox Priority VBox]
           [javafx.stage DirectoryChooser FileChooser Stage Window]))

(defn describe-plan [proposal]
  (str (:notice proposal)
       (when (:summary proposal) (str "\n\n" (coll/join-to-string "\n" (:summary proposal))))
       "\n\n变更预览 · " (count (:writes proposal)) " 个文件\n"
       (coll/join-to-string "\n" (mapv (fn file-diff [entry]
                                         (str (cond (nil? (:after entry)) "移除" (nil? (:before entry)) "新增" :else "更新") "  " (:path entry))) (:writes proposal)))
       "\n\n确认后写入；如果工程或导入包发生变化，必须重新预览。"))

(defn show! [root ^Window owner selected-path changed! open! report!]
  (let [stage (Stage.) pane (BorderPane.) tabs (TabPane.)
        preview (doto (TextArea. "选择操作并生成预览；确认前不会修改项目文件。") (.setEditable false) (.setWrapText true) (.setId "ai-governance-preview"))
        status (doto (Label. "本地管理 · 固定成果及审批历史受保护") (.setWrapText true))
        confirm (doto (Button. "确认应用当前预览") (.setId "ai-governance-apply") (.setDisable true))
        proposal (atom nil) busy (atom false)
        run! (fn run-operation [action completed!]
               (when (compare-and-set! busy false true)
                 (reset! proposal nil) (.setDisable confirm true) (.setDisable tabs true)
                 (.setText status "正在检查本地文件…")
                 (future
                   (try (let [result (action)]
                          (ui/run-later (reset! busy false) (.setDisable tabs false) (completed! result)))
                        (catch Exception e
                          (ui/run-later (reset! busy false) (.setDisable tabs false)
                                        (.setText status (.getMessage e)) (.setText preview (.getMessage e))))))))
        show-plan! (fn show-plan [plan]
                     (reset! proposal plan) (.setText preview (describe-plan plan))
                     (.setDisable confirm (not (coll/not-empty (:writes plan))))
                     (.setText status (if (coll/not-empty (:writes plan)) "请核对变更范围，再确认应用" "内容一致，无需重复导入")))
        button (fn action-button [title id action]
                 (doto (Button. title) (.setId id) (.setOnAction (ui/event-handler _ (action)))))
        tab! (fn tool-tab [title nodes]
               (let [box (doto (VBox. 12.0) (.setPadding (Insets. 14.0)))]
                 (.addAll (.getChildren box) ^java.util.Collection nodes)
                 (.add (.getTabs tabs) (doto (Tab. title box) (.setClosable false))) box))
        source (doto (TextField. (or selected-path "")) (.setId "ai-governance-source") (.setPromptText "/ai-workspace/library/分类/文件"))
        target (doto (TextField.) (.setId "ai-governance-target") (.setPromptText "移动或重命名后的项目相对路径"))
        replace-rules (CheckBox. "采用模板中的资源入库规则（默认保留本项目规则）")
        records (atom []) history (doto (ListView.) (.setId "ai-governance-history"))
        archived (doto (ListView.) (.setId "ai-governance-archives"))
        current-record (fn selected-record [] (get @records (.getSelectedIndex (.getSelectionModel history))))
        refresh-history! (fn refresh-history []
                           (run! #(files/history root)
                                 (fn show-history [items]
                                   (reset! records items)
                                   (.setAll (.getItems history) ^java.util.Collection (mapv #(str (:created-at %) " · " (:kind %) " · " (:status %) " · " (or (:source %) (:backup %) (:template %))) items))
                                   (.setText status "选择记录可检查涉及文件；受控移动与回收可预览恢复。"))))
        choose-backup! (fn choose-backup [action]
                         (when-let [directory (.showDialog (doto (DirectoryChooser.) (.setTitle "选择包含 manifest.json 的备份目录")) stage)]
                           (run! #(action directory) show-plan!)))]
    (.initOwner stage owner)
    (.setTitle stage "模板与文件管理")
    (.setMinWidth stage 760.0) (.setMinHeight stage 560.0)
    (tab! "模板" [(doto (Label. "复用职能配表、职员默认要求、Canvas 结构和资源规则。执行结果、审批与聊天绑定不会导入。") (.setWrapText true))
                replace-rules
                (doto (HBox. 8.0)
                  (-> .getChildren (.addAll ^java.util.Collection
                                     [(button "导出模板…" "ai-template-export"
                                              (fn export-template []
                                                (when-let [file (.showSaveDialog (doto (FileChooser.) (.setInitialFileName "workbench.aitemplate.json")) stage)]
                                                  (run! #(do (when (.startsWith (.toPath (.getCanonicalFile file)) (.toPath (.getCanonicalFile (io/file root))))
                                                               (throw (ex-info "模板包请保存到项目之外，避免覆盖工程文件" {})))
                                                             (when (.exists file) (throw (ex-info "请选择新的文件名，已有模板包不会覆盖" {})))
                                                             (files/write! file (files/json-bytes (templates/export-data root))) file)
                                                        (fn exported [file] (.setText status (str "模板已导出：" file)))))))
                                      (button "导入模板并预览…" "ai-template-import"
                                              (fn import-template []
                                                (when-let [file (.showOpenDialog (FileChooser.) stage)]
                                                  (let [replace (.isSelected replace-rules)]
                                                    (run! (fn prepare-import []
                                                            (let [hash (files/hash-file file) package (files/read-json file)]
                                                              (when-not (= hash (files/hash-file file)) (throw (ex-info "模板包在读取期间变化" {})))
                                                              (assoc (templates/import-plan root package replace)
                                                                :external-inputs [{:path (.getCanonicalPath file) :sha256 hash}]))) show-plan!)))))])))])
    (tab! "文件" [(doto (Label. "只管理创作资料中的单个普通文件。移动会更新引用；冻结版本、已分配任务和未知引用会阻止修改。") (.setWrapText true))
                source target
                (doto (HBox. 8.0)
                  (-> .getChildren (.addAll ^java.util.Collection
                                     [(button "预览移动 / 重命名" "ai-file-move" #(let [from (.getText source) to (.getText target)] (run! (fn prepare-move [] (files/move-plan root from to false)) show-plan!)))
                                      (button "预览移入回收站" "ai-file-trash" #(let [from (.getText source)] (run! (fn prepare-trash [] (files/move-plan root from nil true)) show-plan!)))])))])
    (tab! "备份与迁移" [(doto (Label. "备份包含源文件、固定成果、审批和证据。恢复原项目会暂停待执行队列；迁移到其他项目只复用模板和资料，旧台账作为离线历史保留。") (.setWrapText true))
                   (button "创建完整性备份…" "ai-backup-create"
                           (fn create-backup []
                             (when-let [directory (.showDialog (doto (DirectoryChooser.) (.setTitle "选择项目之外的备份保存目录")) stage)]
                               (run! #(backup/create! root directory) (fn created [file] (.setText status (str "备份已完成：" file)))))))
                   (doto (HBox. 8.0)
                     (-> .getChildren (.addAll ^java.util.Collection
                                        [(button "检查备份…" "ai-backup-verify"
                                                 (fn verify-backup []
                                                   (when-let [directory (.showDialog (DirectoryChooser.) stage)]
                                                     (run! #(backup/verify directory)
                                                           (fn verified [result] (.setText status (if (coll/not-empty (:issues result)) "备份存在缺失或损坏" "摘要和固定成果完整性检查通过"))
                                                             (.setText preview (str "文件数：" (count (:files result)) "\n缺失 / 损坏：\n" (coll/join-to-string "\n" (:issues result)))))))))
                                         (button "恢复原项目 · 预览…" "ai-backup-restore" #(choose-backup! (fn restore-backup [directory] (backup/restore-plan root directory))))
                                         (button "迁移到当前项目 · 预览…" "ai-backup-migrate" #(choose-backup! (fn migrate-backup [directory] (backup/migrate-plan root directory))))])))])
    (let [box (tab! "回收与操作记录" [history
                               (doto (HBox. 8.0)
                                 (-> .getChildren (.addAll ^java.util.Collection
                                                    [(button "刷新记录" "ai-governance-refresh" refresh-history!)
                                                     (button "查看变更" "ai-governance-inspect"
                                                             #(when-let [record (current-record)] (reset! proposal nil) (.setDisable confirm true)
                                                                        (.setText preview (describe-plan (assoc record :writes (:entries record))))))
                                                     (button "预览恢复文件" "ai-governance-recover"
                                                             #(when-let [record (current-record)] (run! (fn recover-file [] (files/restore-file-plan root (:id record))) show-plan!)))])))])]
      (VBox/setVgrow history Priority/ALWAYS) (.setMinHeight box 120.0))
    (tab! "迁移历史" [archived
                  (doto (HBox. 8.0)
                    (-> .getChildren (.addAll ^java.util.Collection
                                       [(button "刷新历史资料" "ai-migration-list"
                                                #(run! (fn read-archives [] (filterv (fn archived-file [entry] (string/starts-with? (:path entry) "/ai-workspace/migrations/")) (files/inventory root)))
                                                       (fn show-archives [entries] (.setAll (.getItems archived) ^java.util.Collection (mapv :path entries)) (.setText status "旧项目记录仅供查阅，不参与当前项目执行与验收。"))))
                                        (button "校验历史完整性" "ai-migration-verify"
                                                #(run! (fn verify-history [] (backup/verify-migrations root))
                                                       (fn verified-history [items]
                                                         (let [issues (coll/into-> items [] (mapcat :issues))]
                                                           (.setText status (str "迁移记录 " (count items) " 项；缺失 / 损坏 " (count issues) " 个文件"))
                                                           (.setText preview (coll/join-to-string "\n" issues))))))
                                        (button "打开资料" "ai-migration-open" #(when-let [path (.getSelectedItem (.getSelectionModel archived))] (open! path)))])))])
    (VBox/setVgrow archived Priority/ALWAYS)
    (reveal/install! source root #(.getText source) report!)
    (reveal/install! archived root #(.getSelectedItem (.getSelectionModel archived)) report!)
    (reveal/install! history root #(some-> (current-record) :entries first :path) report!)
    (.setOnAction confirm (ui/event-handler _
                            (when-let [plan @proposal]
                              (run! #(files/apply! root plan)
                                    (fn applied [_] (changed!) (.setText status "已应用并保存恢复记录；请重新打开受影响的页签。")
                                      (.setText preview (str "应用完成\n\n" (describe-plan plan))))))))
    (let [split (doto (SplitPane.) (.setOrientation Orientation/VERTICAL))
          footer (doto (VBox. 8.0) (.setPadding (Insets. 12.0)))]
      (.addAll (.getItems split) ^java.util.Collection [tabs preview]) (.setDividerPositions split (double-array [0.46]))
      (.addAll (.getChildren footer) ^java.util.Collection [status confirm])
      (.setCenter pane split) (.setBottom pane footer))
    (ui/apply-css! pane)
    (.setScene stage (Scene. pane 980.0 740.0))
    (.addAll (.getStylesheets (.getScene stage)) ^java.util.Collection (.getStylesheets (.getScene owner)))
    (.show stage)
    stage))
