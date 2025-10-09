(ns build-native
  (:require [babashka.process :as p]
            [babashka.fs :as fs]))

(p/shell (fs/file (System/getenv "GRAALVM_HOME") "bin" "native-image")
         "-H:+UnlockExperimentalVMOptions"
         "-H:Name=cream"
         "-H:+RuntimeClassLoading"
         "-H:Preserve=package=java.util"
         "-H:Preserve=package=clojure"
         "-H:Preserve=package=clojure.lang"
         "-H:Preserve=package=java.lang"
         "-H:Preserve=package=java.io"
         "-H:-InterpreterTraceSupport"
         (str "-Djava.home=" (System/getenv "GRAALVM_HOME"))
         "-H:+AllowJRTFileSystem"
         "-H:ConfigurationFileDirectories=."
         "--initialize-at-run-time=com.sun.tools.javac.file.Locations"
         "--initialize-at-build-time=com.sun.tools.doclint,'com.sun.tools.javac.parser.Tokens$TokenKind','com.sun.tools.javac.parser.Tokens$Token$Tag'"
         "-H:IncludeResources=clojure/.*"
         "-jar" "target/repro-1.0.0-standalone.jar"
         #_"com.sun.tools.javac.launcher.SourceLauncher")
