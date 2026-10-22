#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -d .tools/jdk21/Contents/Home ]; then
  JAVA="$PWD/.tools/jdk21/Contents/Home/bin/java"
else
  JAVA=java
fi
exec "$JAVA" -jar target/comfy-batch-service-1.0.0.jar
