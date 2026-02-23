#!/bin/bash
set -e

JAVA_HOME=${JAVA_HOME:?Set JAVA_HOME to a GraalVM EA build with RuntimeClassLoading}

echo "Compiling Main.java..."
$JAVA_HOME/bin/javac Main.java

echo "Compiling CallsForName.java..."
$JAVA_HOME/bin/javac CallsForName.java

echo "Building native image..."
$JAVA_HOME/bin/native-image \
  -H:+UnlockExperimentalVMOptions \
  -H:+RuntimeClassLoading \
  -H:Preserve=package=java.util \
  -H:Preserve=package=java.lang \
  -H:Preserve=package=java.net \
  -H:Preserve=package=java.io \
  -H:ConfigurationFileDirectories=. \
  -H:+AllowJRTFileSystem \
  -o main \
  Main

echo ""
echo "Run with: ./main"
