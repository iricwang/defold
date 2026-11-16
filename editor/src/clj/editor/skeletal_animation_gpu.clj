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

(ns editor.skeletal-animation-gpu
  "A shared batched OpenGL compositor for exact Spine PMA tint and blending.
  JavaFX's multiply mode differs on transparent targets, so it is not used."
  (:import [com.jogamp.common.nio Buffers]
           [com.jogamp.opengl GL GL2 GL2ES2 GLAutoDrawable GLCapabilities GLDrawableFactory GLProfile GLRunnable]
           [java.awt.image BufferedImage DataBufferInt]
           [java.nio FloatBuffer IntBuffer]
           [java.util IdentityHashMap]
           [javafx.scene.image Image PixelFormat WritableImage]))

(set! *warn-on-reflection* true)

(def ^:private vertex-source
  "#version 120
attribute vec2 position; attribute vec2 uv;
uniform vec2 viewport; varying vec2 texcoord;
void main(){texcoord=uv;gl_Position=vec4(position.x/viewport.x*2.0-1.0,1.0-position.y/viewport.y*2.0,0.0,1.0);}")

;; Algebraically equivalent to Spine 4.2's two-color PMA shader. Texture bytes
;; and framebuffer channels are premultiplied, matching Defold texture imports.
(def ^:private fragment-source
  "#version 120
uniform sampler2D image; uniform vec4 light; uniform vec3 dark;
varying vec2 texcoord;
void main(){vec4 t=texture2D(image,texcoord);gl_FragColor=vec4((t.rgb*light.rgb+(vec3(t.a)-t.rgb)*dark)*light.a,t.a*light.a);}")

(defn- shader! [^GL2 gl kind source]
  (let [id (.glCreateShader gl kind)
        status (int-array 1)]
    (.glShaderSource gl id 1 (into-array String [source]) nil 0)
    (.glCompileShader gl id)
    (.glGetShaderiv gl id GL2ES2/GL_COMPILE_STATUS status 0)
    (when (zero? (aget status 0))
      (let [log (byte-array 4096)]
        (.glGetShaderInfoLog gl id 4096 status 0 log 0)
        (.glDeleteShader gl id)
        (throw (ex-info "Spine preview shader failed to compile." {:log (String. log 0 (aget status 0))}))))
    id))

(defn- initialize! [^GL2 gl]
  (let [vertex (shader! gl GL2ES2/GL_VERTEX_SHADER vertex-source)
        fragment (shader! gl GL2ES2/GL_FRAGMENT_SHADER fragment-source)
        program (.glCreateProgram gl)
        status (int-array 1)
        buffer (int-array 1)]
    (.glAttachShader gl program vertex)
    (.glAttachShader gl program fragment)
    (.glBindAttribLocation gl program 0 "position")
    (.glBindAttribLocation gl program 1 "uv")
    (.glLinkProgram gl program)
    (.glGetProgramiv gl program GL2ES2/GL_LINK_STATUS status 0)
    (.glDeleteShader gl vertex)
    (.glDeleteShader gl fragment)
    (when (zero? (aget status 0)) (throw (ex-info "Spine preview shader failed to link." {})))
    (.glGenBuffers gl 1 buffer 0)
    {:program program :buffer (aget buffer 0)
     :viewport (.glGetUniformLocation gl program "viewport")
     :light (.glGetUniformLocation gl program "light")
     :dark (.glGetUniformLocation gl program "dark")
     :sampler (.glGetUniformLocation gl program "image")
     :textures (IdentityHashMap.) :texture-bytes (atom 0)
     :vertex-buffer (atom nil) :readback-buffer (atom nil)}))

(defn- texture! [^GL2 gl ^IdentityHashMap textures allocated ^Image image]
  (or (.get textures image)
      (let [width (if image (int (.getWidth image)) 1)
            height (if image (int (.getHeight image)) 1)
            bytes (byte-array (* width height 4))
            handle (int-array 1)]
        (when (or (>= (.size textures) 64) (> (+ @allocated (alength bytes)) 134217728))
          (let [old (int-array (.values textures))]
            (.glDeleteTextures gl (alength old) old 0)
            (.clear textures)
            (reset! allocated 0)))
        (if image
          (.getPixels (.getPixelReader image) 0 0 (int width) (int height) (PixelFormat/getByteBgraPreInstance) bytes 0 (int (* width 4)))
          (java.util.Arrays/fill bytes (byte -1)))
        (.glGenTextures gl 1 handle 0)
        (.glBindTexture gl GL/GL_TEXTURE_2D (aget handle 0))
        (.glTexParameteri gl GL/GL_TEXTURE_2D GL/GL_TEXTURE_MIN_FILTER GL/GL_LINEAR)
        (.glTexParameteri gl GL/GL_TEXTURE_2D GL/GL_TEXTURE_MAG_FILTER GL/GL_LINEAR)
        (.glTexParameteri gl GL/GL_TEXTURE_2D GL/GL_TEXTURE_WRAP_S GL/GL_CLAMP_TO_EDGE)
        (.glTexParameteri gl GL/GL_TEXTURE_2D GL/GL_TEXTURE_WRAP_T GL/GL_CLAMP_TO_EDGE)
        (.glTexImage2D gl GL/GL_TEXTURE_2D 0 GL/GL_RGBA8 (int width) (int height) 0 GL/GL_BGRA GL/GL_UNSIGNED_BYTE (Buffers/newDirectByteBuffer bytes))
        (.put textures image (aget handle 0))
        (swap! allocated + (alength bytes))
        (aget handle 0))))

(defn- draw-slot! [^GL2 gl state slot]
  (let [{:keys [points uvs triangles image opacity tint dark-tint blend]
         :or {tint [1.0 1.0 1.0] dark-tint [0.0 0.0 0.0] opacity 1.0 blend "normal"}} slot
        capacity (* 12 (count triangles))
        previous @(:vertex-buffer state)
        ^FloatBuffer vertices (if (and previous (>= (.capacity ^FloatBuffer previous) capacity))
                                previous
                                (let [created (Buffers/newDirectFloatBuffer capacity)]
                                  (reset! (:vertex-buffer state) created)
                                  created))]
    (.clear vertices)
    (doseq [triangle triangles index triangle]
      (let [[x y] (points index) [u v] (uvs index)]
        (.put vertices (float x)) (.put vertices (float y))
        (.put vertices (float u)) (.put vertices (float v))))
    (.flip vertices)
    (.glBindTexture gl GL/GL_TEXTURE_2D (int (texture! gl (:textures state) (:texture-bytes state) image)))
    (.glUniform4f gl (:light state) (float (tint 0)) (float (tint 1)) (float (tint 2)) (float opacity))
    (.glUniform3f gl (:dark state) (float (dark-tint 0)) (float (dark-tint 1)) (float (dark-tint 2)))
    (case blend
      "normal" (.glBlendFuncSeparate gl GL/GL_ONE GL/GL_ONE_MINUS_SRC_ALPHA GL/GL_ONE GL/GL_ONE_MINUS_SRC_ALPHA)
      "additive" (.glBlendFuncSeparate gl GL/GL_ONE GL/GL_ONE GL/GL_ONE GL/GL_ONE)
      "multiply" (.glBlendFuncSeparate gl GL/GL_DST_COLOR GL/GL_ONE_MINUS_SRC_ALPHA GL/GL_ONE GL/GL_ONE_MINUS_SRC_ALPHA)
      "screen" (.glBlendFuncSeparate gl GL/GL_ONE GL/GL_ONE_MINUS_SRC_COLOR GL/GL_ONE GL/GL_ONE_MINUS_SRC_ALPHA))
    (.glBufferData gl GL/GL_ARRAY_BUFFER (* 4 (.remaining vertices)) vertices GL2ES2/GL_STREAM_DRAW)
    (.glDrawArrays gl GL/GL_TRIANGLES 0 (* 3 (count triangles)))))

(defonce ^:private compositor (atom nil))

(defn- create-compositor []
  (let [profile (GLProfile/get GLProfile/GL2)
        caps (doto (GLCapabilities. profile) (.setAlphaBits 8) (.setDepthBits 0) (.setStencilBits 0) (.setDoubleBuffered false))
        drawable (.createOffscreenAutoDrawable (GLDrawableFactory/getFactory profile) nil caps nil 1 1)
        state (atom nil)]
    (.display drawable)
    (.invoke drawable true
             (reify GLRunnable
               (run [_ drawable]
                 (reset! state (initialize! (.getGL2 (.getGL ^GLAutoDrawable drawable))))
                 true)))
    {:drawable drawable :state @state :size (atom [1 1])}))

(defn render-image
  "Render an already-clipped, ordered plan into premultiplied ARGB pixels.
  A single shared GPU context serializes background export and live preview."
  ^BufferedImage [width height slots]
  (locking compositor
    (let [width (int width) height (int height)
          _ (when-not (and (pos? width) (pos? height) (<= (* (long width) height) 67108864))
              (throw (ex-info "Image dimensions must be positive and at most 64 megapixels." {})))
          {:keys [^com.jogamp.opengl.GLOffscreenAutoDrawable drawable state size]} (or @compositor (let [created (create-compositor)] (reset! compositor created) created))
          image (BufferedImage. width height BufferedImage/TYPE_INT_ARGB_PRE)
          ^ints pixels (.getData ^DataBufferInt (.getDataBuffer (.getRaster image)))
          previous @(:readback-buffer state)
          ^IntBuffer readback (if (and previous (>= (.capacity ^IntBuffer previous) (* width height)))
                                previous
                                (let [created (Buffers/newDirectIntBuffer (* width height))]
                                  (reset! (:readback-buffer state) created)
                                  created))]
      (.clear readback)
      (when (not= @size [width height])
        (.setSurfaceSize drawable width height)
        (reset! size [width height]))
      (.invoke drawable true
               (reify GLRunnable
                 (run [_ target]
                   (let [^GL2 gl (.getGL2 (.getGL ^GLAutoDrawable target))]
                     (.glViewport gl 0 0 width height)
                     (.glDisable gl GL/GL_DEPTH_TEST)
                     (.glDisable gl GL/GL_CULL_FACE)
                     (.glEnable gl GL/GL_BLEND)
                     (.glClearColor gl 0.0 0.0 0.0 0.0)
                     (.glClear gl GL/GL_COLOR_BUFFER_BIT)
                     (.glUseProgram gl (:program state))
                     (.glUniform2f gl (:viewport state) (float width) (float height))
                     (.glUniform1i gl (:sampler state) 0)
                     (.glActiveTexture gl GL/GL_TEXTURE0)
                     (.glBindBuffer gl GL/GL_ARRAY_BUFFER (:buffer state))
                     (.glEnableVertexAttribArray gl 0)
                     (.glEnableVertexAttribArray gl 1)
                     (.glVertexAttribPointer gl 0 2 GL/GL_FLOAT false 16 (long 0))
                     (.glVertexAttribPointer gl 1 2 GL/GL_FLOAT false 16 (long 8))
                     (run! #(when (pos? (count (:triangles %))) (draw-slot! gl state %)) slots)
                     (.glPixelStorei gl GL/GL_PACK_ALIGNMENT 4)
                     (.glReadPixels gl 0 0 width height GL/GL_BGRA GL2/GL_UNSIGNED_INT_8_8_8_8_REV readback)
                     (.glBindBuffer gl GL/GL_ARRAY_BUFFER 0)
                     (.glUseProgram gl 0)
                     (let [error (.glGetError gl)]
                       (when (not= GL/GL_NO_ERROR error) (throw (ex-info "Spine GPU compositor error." {:error error})))))
                   true)))
      (dotimes [row height]
        (.position readback (* (- height row 1) width))
        (.get readback pixels (* row width) width))
      image)))

(defn update-fx-image!
  "Upload one finished premultiplied image. No triangle clips or Canvas nodes."
  ^WritableImage [^BufferedImage image ^WritableImage previous]
  (let [width (.getWidth image) height (.getHeight image)
        result (if (and previous (= (double width) (.getWidth previous)) (= (double height) (.getHeight previous))) previous (WritableImage. width height))
        ^ints pixels (.getData ^DataBufferInt (.getDataBuffer (.getRaster image)))]
    (.setPixels (.getPixelWriter result) 0 0 width height (PixelFormat/getIntArgbPreInstance) pixels 0 width)
    result))
