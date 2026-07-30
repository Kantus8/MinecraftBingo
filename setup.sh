#!/bin/sh
# Récupère gradle-wrapper.jar (absent du dépôt) puis vérifie la config Gradle.
set -e
GRADLE_TAG="v8.8.0"
DEST="gradle/wrapper/gradle-wrapper.jar"
URL="https://raw.githubusercontent.com/gradle/gradle/${GRADLE_TAG}/gradle/wrapper/gradle-wrapper.jar"

cd "$(dirname "$0")"
mkdir -p gradle/wrapper

if [ -f "$DEST" ]; then
    echo "[=] $DEST déjà présent."
else
    echo "[>] Téléchargement du Gradle Wrapper ($GRADLE_TAG)..."
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$URL" -o "$DEST"
    else
        wget -q "$URL" -O "$DEST"
    fi
    echo "[+] $DEST installé."
fi

echo "[>] Vérification de la configuration Gradle (résolution des dépendances)..."
./gradlew --version
./gradlew dependencies --configuration modImplementation --quiet || true
echo "[OK] Environnement prêt. Prochaine étape : ./gradlew build"
