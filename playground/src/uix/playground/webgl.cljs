(ns uix.playground.webgl
  (:require-macros [uix.playground.webgl])
  (:require [clojure.string :as str]
            [uix.core :as uix]
            [uix.playground.hooks :as hooks]))

(defn ^js/WebGL2RenderingContext get-context [canvas]
  (.getContext canvas "webgl2"))

(defn create-shader [^js/WebGL2RenderingContext gl type source]
  (let [shader (.createShader gl type)]
    (.shaderSource gl shader source)
    (.compileShader gl shader)
    (if (.getShaderParameter gl shader (.-COMPILE_STATUS gl))
      shader
      (do
        (js/console.error
          (str ({(.-VERTEX_SHADER gl) "Vertex"
                 (.-FRAGMENT_SHADER gl) "Fragment"}
                type)
               " shader compilation failed.\n\n"
               (.getShaderInfoLog gl shader)))
        (.deleteShader gl shader)
        nil))))

(defn create-program [^js/WebGL2RenderingContext gl vertex-shader-source fragment-shader-source]
  (let [vertex-shader (create-shader gl (.-VERTEX_SHADER gl) vertex-shader-source)
        fragment-shader (create-shader gl (.-FRAGMENT_SHADER gl) fragment-shader-source)
        program (.createProgram gl)]
    (.attachShader gl program vertex-shader)
    (.attachShader gl program fragment-shader)
    (.linkProgram gl program)
    (if (.getProgramParameter gl program (.-LINK_STATUS gl))
      program
      (do
        (js/console.error "Program linking failed.")
        (js/console.error (.getProgramInfoLog gl program))
        (.deleteProgram gl program)))))

(defn create-uniform [^js/WebGL2RenderingContext gl program name]
  (.getUniformLocation gl program name))

(defn create-full-screen-quad [^js/WebGL2RenderingContext gl program]
  (let [VBO (.createBuffer gl)
        position-attr (.getAttribLocation gl program "a_position")]
    (.bindBuffer gl (.-ARRAY_BUFFER gl) VBO)
    (.bufferData gl
                 (.-ARRAY_BUFFER gl)
                 (js/Float32Array. #js [-1, -1,
                                        -1, 1,
                                        1, -1,
                                        1, 1])
                 (.-STATIC_DRAW gl))
    (.enableVertexAttribArray gl position-attr)
    (.vertexAttribPointer gl position-attr 2 (.-FLOAT gl) false 0 0)))

(defn set-uniform [^js/WebGL2RenderingContext gl type u & args]
  (let [type (name type)
        values (cond
                 (str/ends-with? type "fv") [(js/Float32Array. (first args))]
                 :else args)]
    (apply js-invoke gl (str "uniform" type) u values)))

(defn use-webgl-program [{:keys [canvas fragment-shader vertex-shader uniforms]} render-fn]
  (let [fid (uix/use-ref)
        render-fn (hooks/use-event render-fn)
        uniforms (hooks/use-memo-dep uniforms)]
    (uix/use-effect
      (fn []
        (when canvas
          (let [gl (get-context canvas)
                program (create-program gl vertex-shader fragment-shader)
                _ (create-full-screen-quad gl program)
                _ (.clearColor gl 0 0 0 1)

                ;; setup uniforms
                uniforms (reduce-kv
                            (fn [ret k type]
                                (assoc ret k (partial set-uniform gl type (create-uniform gl program (name k)))))
                            {}
                            uniforms)

                 ;; rendering loop
                draw (fn draw []
                       (reset! fid (js/requestAnimationFrame draw))

                       (.clear gl (.-COLOR_BUFFER_BIT gl))
                       (.useProgram gl program)

                       (render-fn gl program uniforms)

                       (.drawArrays gl (.-TRIANGLE_STRIP gl) 0 4))]
            (draw)
            #(when @fid
               (js/cancelAnimationFrame @fid)
               (reset! fid nil)))))
      [canvas fragment-shader vertex-shader uniforms])))
