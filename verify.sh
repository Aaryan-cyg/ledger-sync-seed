#!/usr/bin/env bash
# Compiles and runs the pipeline against fixtures/corpus-a.jsonl.
# Needs a JDK 21 and nothing else - no network, no database, no Gradle.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> compiling"
rm -rf build/selfcheck && mkdir -p build/selfcheck
# Build classpath from Gradle cache if present
CP=$(find "${HOME}/.gradle/caches" -name "*.jar" 2>/dev/null | tr '\n' ':' || true)
if [ -n "$CP" ]; then
    javac -cp "$CP" -d build/selfcheck $(find src/main/java -name '*.java')
    echo
    echo "==> running"
    java -cp "build/selfcheck:$CP" in.simplifymoney.ledgersync.SelfCheck "$@"
else
    javac -d build/selfcheck $(find src/main/java -name '*.java' ! -name 'Mongo*')
    echo
    echo "==> running"
    java -cp build/selfcheck in.simplifymoney.ledgersync.SelfCheck "$@"
fi
