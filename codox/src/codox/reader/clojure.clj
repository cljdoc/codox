(ns codox.reader.clojure
  "Read raw documentation information from Clojure source directory."
  (:import java.util.jar.JarFile
           java.io.FileNotFoundException)
  (:use [codox.utils :only (assoc-some update-some correct-indent)])
  (:require [clojure.java.io :as io]
            [clojure.tools.namespace.find :as ns]
            [clojure.string :as str]
            [codox.utils :as util]))

(defn try-require [namespace]
  (try
    (require namespace)
    (catch FileNotFoundException _ nil)))

(defn core-typed? []
  (find-ns 'clojure.core.typed.check))

(defn var->symbol [var]
  (let [{:keys [ns name]} (meta var)]
    (symbol (str ns) (str name))))

(defn typecheck-namespace [namespace]
  ((find-var 'clojure.core.typed/check-ns-info) namespace))

(defn typecheck-var [var]
  ((find-var 'clojure.core.typed/check-form-info) (var->symbol var)))

(defn- sorted-public-vars [namespace]
  (->> (ns-publics namespace)
       (vals)
       (sort-by (comp :name meta))))

(defn- no-doc? [var]
  (let [{:keys [skip-wiki no-doc]} (meta var)]
    (or skip-wiki no-doc)))

(defn- proxy? [var]
  (re-find #"proxy\$" (-> var meta :name str)))

(defn- macro? [var]
  (:macro (meta var)))

(defn- multimethod? [var]
  (instance? clojure.lang.MultiFn (var-get var)))

(defn- protocol? [var]
  (let [value (var-get var)]
    (and (map? value)
         (not (sorted? value)) ; workaround for CLJ-1242
         (:on-interface value))))

(defn- protocol-method? [vars var]
  (if-let [p (:protocol (meta var))]
    (some #{p} vars)))

(defn- include-record-factory-as-defrecord [var-meta]
  (let [n (str (:name var-meta))]
    (if (re-find #"map->\p{Upper}" n)
      (-> var-meta
          (assoc :name (symbol (subs n 5)))
          (dissoc :doc :arglists))
      var-meta)))

(defn- protocol-methods [protocol vars]
  (filter #(= protocol (:protocol (meta %))) vars))

(defn- var-type [var]
  (cond
   (macro? var)       :macro
   (multimethod? var) :multimethod
   (protocol? var)    :protocol
   :else              :var))

(defn core-typed-type [var]
  (let [{:keys [delayed-errors ret]} (typecheck-var var)]
    (if (empty? delayed-errors)
      (:t ret))))

(defn- read-var [source-path vars var]
  (let [normalize (partial util/normalize-to-source-path source-path)]
    (-> (meta var)
        (include-record-factory-as-defrecord)
        (select-keys [:name :file :line :arglists :doc :dynamic
                      :added :deprecated :doc/format])
        (update-some :doc correct-indent)
        (update-some :file normalize)
        (assoc-some  :type (var-type var)
                     :type-sig (if (core-typed?) (core-typed-type var))
                     :members (seq (map (partial read-var source-path vars)
                                        (protocol-methods var vars))))
        util/remove-empties)))

(defn- read-publics [source-path namespace]
  (let [vars (sorted-public-vars namespace)]
    (->> vars
         (remove proxy?)
         (remove no-doc?)
         (remove (partial protocol-method? vars))
         (map (partial read-var source-path vars))
         (sort-by (comp str/lower-case :name)))))

(defn- read-ns [namespace source-path exception-handler]
  (try-require 'clojure.core.typed.check)
  (when (core-typed?)
    (typecheck-namespace namespace))
  (try
    (require namespace)
    (-> (find-ns namespace)
        (meta)
        (dissoc :file :line :column :end-column :end-line)
        (assoc :name namespace)
        (assoc :publics (read-publics source-path namespace))
        (update-some :doc correct-indent)
        (list))
    (catch Exception e
      (exception-handler e namespace))))

(defn- jar-file? [file]
  (and (.isFile file)
       (-> file .getName (.endsWith ".jar"))))

(defn- find-namespaces [file]
  (cond
    (.isDirectory file) (set (ns/find-namespaces-in-dir file))
    (jar-file? file)    (set (ns/find-namespaces-in-jarfile (JarFile. file)))))

(defn read-namespaces
  "Read Clojure namespaces from a set of source directories (defaults
  to [\"src\"]), and return a list of maps suitable for documentation
  purposes.

  Supported options using the second argument:
    :exception-handler - function (fn [ex ns]) to handle exceptions
    while reading a namespace

  Any namespace with {:no-doc true} in its metadata will be skipped.

  The keys in the maps are:
    :name   - the name of the namespace
    :doc    - the doc-string on the namespace
    :author - the author of the namespace
    :publics
      :name       - the name of a public function, macro, or value
      :file       - the file the var was declared in
      :line       - the line at which the var was declared
      :arglists   - the arguments the function or macro takes
      :doc        - the doc-string of the var
      :type       - one of :macro, :protocol, :multimethod or :var
      :added      - the library version the var was added in
      :deprecated - the library version the var was deprecated in"
  ([] (read-namespaces ["src"] {}))
  ([paths] (read-namespaces paths {}))
  ([paths {:keys [exception-handler]
           :or {exception-handler (partial util/default-exception-handler "Clojure")}}]
   (mapcat (fn [path]
             (let [path (util/canonical-path path)]
               (->> (io/file path)
                    (find-namespaces)
                    (mapcat #(read-ns % path exception-handler))
                    (remove :no-doc))))
           paths)))
