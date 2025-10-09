(ns build-native
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [babashka.tasks :as tasks]))

(tasks/clojure "-T:build" "uber")

#_(p/shell (fs/file (System/getenv "GRAALVM_HOME") "bin" "java")
         "-agentlib:native-image-agent=config-output-dir=." "-jar" "target/repro-1.0.0-standalone.jar")

(p/shell (fs/file (System/getenv "GRAALVM_HOME") "bin" "native-image")
         "-jar" "target/repro-1.0.0-standalone.jar"
         "--initialize-at-run-time=com.sun.tools.javac.file.Locations"
         "--initialize-at-build-time=clojure,com.sun.tools.doclint,com.sun.tools.javac.parser.Tokens$TokenKind,com.sun.tools.javac.parser.Tokens$Token$Tag"
         "-H:+UnlockExperimentalVMOptions"
         "-H:Name=cream"
         "-H:+RuntimeClassLoading"
         "-H:ConfigurationFileDirectories=."
         "-H:IncludeResources=clojure/.*"
         "-H:Preserve=package=java.util"
         ;; "-H:Preserve=package=clojure"
         ;; "-H:Preserve=package=clojure.lang"
         "-H:Preserve=package=java.lang"
         "-H:Preserve=package=java.util"
         "-H:Preserve=package=java.io"
         "-H:Preserve=package=java.util.concurrent"
         ;; "-H:Preserve=path=target/repro-1.0.0-standalone.jar"
         "-H:-InterpreterTraceSupport"
         (str "-Djava.home=" (System/getenv "GRAALVM_HOME"))
         "-H:+AllowJRTFileSystem"
         "-H:ConfigurationFileDirectories=."
         "--verbose")
