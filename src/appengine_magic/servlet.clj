;;; This code is adapted from Ring (http://github.com/mmcgrana/ring) to remove
;;; dependencies which use Java classes blacklisted in App Engine.


(ns appengine-magic.servlet
  (:import [java.io File FileInputStream InputStream OutputStream]
           java.nio.ByteBuffer
           [java.nio.channels Channel Channels ReadableByteChannel WritableByteChannel]
           [javax.servlet.http HttpServlet HttpServletRequest HttpServletResponse]))


(defn- get-headers [^HttpServletRequest request]
  (reduce (fn [headers, ^String name]
            (assoc headers
              (.toLowerCase name)
              (.getHeader request name)))
          {}
          (enumeration-seq (.getHeaderNames request))))


(defn- make-request-map [^HttpServlet servlet
                         ^HttpServletRequest request
                         ^HttpServletRespone response]
  {:servlet            servlet
   :servlet-context    (.getServletContext servlet)
   :request            request
   :response           response
   :server-port        (.getServerPort request)
   :server-name        (.getServerName request)
   :remote-addr        (.getRemoteAddr request)
   :uri                (.getRequestURI request)
   :query-string       (.getQueryString request)
   :scheme             (keyword (.getScheme request))
   :request-method     (keyword (.toLowerCase (.getMethod request)))
   :headers            (get-headers request)
   :content-type       (.getContentType request)
   :content-length     (.getContentLength request)
   :character-encoding (.getCharacterEncoding request)
   :body               (.getInputStream request)})


(defn- set-response-headers [^HttpServletResponse response, headers]
  (doseq [[key val-or-vals] headers]
    (if (string? val-or-vals)
        (.setHeader response key val-or-vals)
        (doseq [val val-or-vals]
          (.addHeader response key val))))
  ;; Use specific servlet API methods for some headers:
  (.setCharacterEncoding response "UTF-8")
  (when-let [content-type (get headers "Content-Type")]
    (.setContentType response content-type)))


(defn- copy-stream [^InputStream input, ^OutputStream output]
  (with-open [^ReadableByteChannel in-channel (Channels/newChannel input)
              ^WritableByteChannel out-channel (Channels/newChannel output)]
    (let [^ByteBuffer buf (ByteBuffer/allocateDirect (* 4 1024))]
      (loop []
        (when-not (= -1 (.read in-channel buf))
          (.flip buf)
          (.write out-channel buf)
          (.compact buf)
          (recur)))
      (.flip buf)
      (loop [] ; drain the buffer
        (when (.hasRemaining buf)
          (.write out-channel buf)
          (recur))))))


(defn- set-response-body [^HttpServletResponse response, body]
  (cond (string? body)
          (with-open [writer (.getWriter response)]
            (.println writer body))
        (seq? body)
          (with-open [writer (.getWriter response)]
            (doseq [chunk body]
              (.print writer (str chunk))
              (.flush writer)))
        (instance? InputStream body)
          (let [^InputStream b body]
            (with-open [out (.getOutputStream response)]
              (copy-stream b out)
              (.close b)
              (.flush out)))
        (instance? File body)
          (let [^File f body]
            (with-open [stream (FileInputStream. f)]
              (set-response-body response stream)))
        (nil? body) nil
        :else (throw (RuntimeException. (str "handler response body unknown" body)))))


(defn- adapt-servlet-response [^HttpServletResponse response,
                               {:keys [status headers body] :as response-map}]
  (if status
      (.setStatus response status)
      (throw (RuntimeException. "handler response status not set")))
  (when headers (set-response-headers response headers))
  (when body (set-response-body response body)))


(defn make-servlet-service-method [ring-handler]
  (fn [^HttpServlet servlet, ^HttpServletRequest request, ^HttpServletResponse response]
    (let [response-map (ring-handler (make-request-map servlet request response))]
      (when-not response-map
        (throw (RuntimeException. "handler returned nil (no response map)")))
      (adapt-servlet-response response response-map))))


(defn servlet [ring-handler]
  (proxy [HttpServlet] []
    (service [^HttpServletRequest request, ^HttpServletResponse response]
      ((make-servlet-service-method ring-handler) this request response))))