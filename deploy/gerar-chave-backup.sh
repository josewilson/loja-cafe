#!/usr/bin/env bash
# Gera o par de chaves do backup (age). Só precisa ser feito UMA vez.
#   - A chave PRIVADA vai para o arquivo indicado. GUARDE-A FORA DO SERVIDOR (gerenciador de senhas, pendrive, papel num cofre).
#     Sem ela ninguém restaura os backups, nem você. Quem a tiver lê todos eles.
#   - A chave PÚBLICA aparece na tela: cole em BACKUP_AGE_RECIPIENT no deploy/.env. Ela só serve para criptografar.
# Uso: deploy/gerar-chave-backup.sh [arquivo-da-chave-privada]     (padrão: ./chave-backup-PRIVADA.txt)
set -euo pipefail
export MSYS_NO_PATHCONV=1

DESTINO=${1:-chave-backup-PRIVADA.txt}
if [ -e "$DESTINO" ]; then
  echo "$DESTINO já existe. Não vou sobrescrever uma chave: troque o nome ou apague o arquivo de propósito." >&2
  exit 1
fi
RAIZ=$(cd "$(dirname "$0")/.." && { pwd -W 2>/dev/null || pwd; })
IMAGEM=loja-cafe-backup:chave

docker build -q -t "$IMAGEM" "$RAIZ/deploy/backup" > /dev/null
SAIDA=$(docker run --rm --entrypoint age-keygen "$IMAGEM")
umask 077
printf '%s\n' "$SAIDA" > "$DESTINO"
PUBLICA=$(printf '%s\n' "$SAIDA" | sed -n 's/^# public key: //p')

echo "Chave PRIVADA gravada em: $DESTINO   (guarde FORA do servidor e faça uma segunda cópia)"
echo
echo "Chave PÚBLICA (cole no deploy/.env):"
echo "BACKUP_AGE_RECIPIENT=$PUBLICA"
