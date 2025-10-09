# Graal repros

Custom GraalVM native-image build:

- brew install / update mx
- clone graal (master) branch
- mx fetch-jdk labsjdk-ce-latest
- Set JAVA_HOME to labsjdk
- cd substratevm
- mx build
- mx graalvm-home returns location of new build

Custom Clojure:

- build crema branch of github.com/borkdude/clojure with:
- mvn install -Dmaven.test.skip=true

Then:

- bb bb/build_native.clj
- ./cream
