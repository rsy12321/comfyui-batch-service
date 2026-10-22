#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -d .tools/jdk21/Contents/Home ]; then
  JAVA_HOME="$PWD/.tools/jdk21/Contents/Home"
  export JAVA_HOME
  PATH="$JAVA_HOME/bin:$PATH"
  export PATH
fi
exec mvn -s scripts/maven-settings.xml -gs scripts/maven-settings.xml -Dmaven.repo.local="$PWD/.tools/m2" "$@"
