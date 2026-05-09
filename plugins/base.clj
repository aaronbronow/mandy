!yamlscript/v0

# Mandy Base Plugin - Structured Man Page Parser
# This logic is implemented in src/index.ts for industrial stability
# but preserved here as a pure Clojure reference.

=>:
  (reduce
    (fn [acc line]
      (let [trimmed (clojure.string/trim (or line ""))]
        (if (re-find #"^[A-Z][A-Z\s]{2,}$" trimmed)
          (conj acc {trimmed []})
          (let [last-map (peek acc)
                header (first (keys last-map))
                body (get last-map header)]
            (assoc (pop acc) header (conj body line))))))
    [{"PREAMBLE" []}]
    input)
