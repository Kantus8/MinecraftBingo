#!/bin/sh
#
# Lanceur minimal du Gradle Wrapper (Linux / macOS / WSL).
#
# Ce script est un launcher réduit mais fonctionnel : il délègue à
# org.gradle.wrapper.GradleWrapperMain, exactement comme le script officiel.
# Pour régénérer la version officielle complète (et le jar), lance une fois :
#     ./gradlew wrapper --gradle-version 8.8
#

set -e

APP_HOME=$(cd "$(dirname "$0")" >/dev/null && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=$(command -v java || true)
fi

if [ -z "$JAVACMD" ]; then
    echo "ERREUR : aucun Java trouvé. Installe un JDK 17 et/ou définis JAVA_HOME." >&2
    exit 1
fi

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "ERREUR : gradle/wrapper/gradle-wrapper.jar est absent." >&2
    echo "Lance d'abord ./setup.sh (ou setup.bat sous Windows) pour le télécharger." >&2
    exit 1
fi

exec "$JAVACMD" \
    -Xmx64m -Xms64m \
    $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=gradlew" \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
