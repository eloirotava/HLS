#!/bin/bash

# 1. Configurações
PORT=8080
APK_SOURCE="app/build/outputs/apk/debug/app-debug.apk"
APK_DEST="streamer.apk"

# 2. Verifica se o APK existe
if [ ! -f "$APK_SOURCE" ]; then
    echo "❌ Erro: APK não encontrado!"
    echo "   Rode './gradlew assembleDebug' primeiro."
    exit 1
fi

# 3. Prepara a pasta de distribuição
mkdir -p dist
cp "$APK_SOURCE" "dist/$APK_DEST"

# 4. Instala qrencode (com fix para repositórios antigos)
if ! command -v qrencode &> /dev/null; then
    echo "📦 Configurando dependências..."
    if grep -q "archive.ubuntu.com" /etc/apt/sources.list; then
        sed -i 's/archive.ubuntu.com/old-releases.ubuntu.com/g' /etc/apt/sources.list
        sed -i 's/security.ubuntu.com/old-releases.ubuntu.com/g' /etc/apt/sources.list
    fi
    apt-get update -qq && apt-get install -y qrencode -qq
fi

# --- AQUI ESTAVA O ERRO: O SERVIDOR PRECISA SUBIR ANTES ---

# 5. Mata servidor anterior e inicia o novo
echo "🚀 Iniciando servidor Python..."
fuser -k $PORT/tcp > /dev/null 2>&1
cd dist
python3 -m http.server "$PORT" > /dev/null 2>&1 &
SERVER_PID=$!

# Dá um tempo para o servidor registrar a porta no sistema do GitHub
sleep 3

# 6. Agora sim: Libera a porta e pega a URL
#echo "🔓 Configurando acesso público..."
#gh codespace ports visibility "$PORT:public" -c "$CODESPACE_NAME"
# > /dev/null

# 7. Tenta pegar a URL oficial
ROOT_URL=$(gh codespace ports -c "$CODESPACE_NAME" --json port,browseUrl -q ".[] | select(.port == $PORT) | .browseUrl")

# Fallback manual se o comando gh falhar
if [ -z "$ROOT_URL" ]; then
    DOMAIN="${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
    ROOT_URL="https://${CODESPACE_NAME}-${PORT}.${DOMAIN}"
fi

DOWNLOAD_URL="${ROOT_URL}/${APK_DEST}"

# 8. Mostra o Resultado
#clear
echo "=========================================="
echo "   📲 SCANNER PARA BAIXAR O APK"
echo "=========================================="
echo ""
echo "🔗 Link: $DOWNLOAD_URL"
echo ""
qrencode -t ANSIUTF8 "$DOWNLOAD_URL"
echo ""
echo "=========================================="
echo "Pressione [ENTER] para parar o servidor..."
read
#kill $SERVER_PID
echo "🛑 Servidor parado."