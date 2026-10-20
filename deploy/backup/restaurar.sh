#!/usr/bin/env bash
# Restaura um backup (.dump.age) para um banco. Roda DENTRO da imagem de backup (que tem pg_restore e age).
# Quem usa no dia a dia é o deploy/restaurar.sh (na máquina), que monta os arquivos e cuida da API.
#
# Uso: restaurar.sh <backup.dump.age> <chave-privada.txt> <banco-de-destino> [--substituir]
#   Sem --substituir: o banco de destino precisa NÃO existir (restauração segura, para conferir antes de decidir).
#   Com --substituir: derruba as conexões, APAGA o banco de destino e o recria com o backup. Destrutivo.
# Variáveis: PGHOST PGUSER PGPASSWORD (como no backup).
set -euo pipefail

ARQUIVO=${1:?uso: restaurar.sh <backup.dump.age> <chave-privada.txt> <banco-de-destino> [--substituir]}
CHAVE=${2:?falta a chave privada}
DESTINO=${3:?falta o nome do banco de destino}
MODO=${4:-}

# O nome do banco entra em comandos SQL: só letras, números e _ (nada de aspas ou espaços).
[[ "$DESTINO" =~ ^[A-Za-z0-9_]+$ ]] || { echo "nome de banco inválido: $DESTINO" >&2; exit 2; }
[ -r "$ARQUIVO" ] || { echo "não consigo ler $ARQUIVO" >&2; exit 2; }
[ -r "$CHAVE" ] || { echo "não consigo ler a chave $CHAVE" >&2; exit 2; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Confere a soma de verificação, se o arquivo .sha256 estiver ao lado.
if [ -r "$ARQUIVO.sha256" ]; then
  ESPERADA=$(cut -d' ' -f1 "$ARQUIVO.sha256")
  ATUAL=$(sha256sum "$ARQUIVO" | cut -d' ' -f1)
  [ "$ESPERADA" = "$ATUAL" ] || { echo "ARQUIVO CORROMPIDO: a soma de verificação não confere" >&2; exit 3; }
  echo "soma de verificação conferida"
fi

echo "descriptografando..."
age --decrypt --identity "$CHAVE" --output "$TMP/dump" "$ARQUIVO" || { echo "não consegui descriptografar: chave errada ou arquivo corrompido" >&2; exit 3; }
pg_restore --list "$TMP/dump" > /dev/null || { echo "o dump descriptografado não é válido" >&2; exit 3; }

existe=$(psql -d postgres -Atc "select 1 from pg_database where datname = '$DESTINO'" || true)
if [ -n "$existe" ]; then
  if [ "$MODO" != "--substituir" ]; then
    echo "o banco $DESTINO já existe. Use outro nome, ou --substituir para APAGAR e recriar." >&2
    exit 4
  fi
  echo "ATENÇÃO: apagando o banco $DESTINO..."
  psql -d postgres -qc "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$DESTINO' and pid <> pg_backend_pid()" > /dev/null
  psql -d postgres -qc "drop database \"$DESTINO\""
fi
psql -d postgres -qc "create database \"$DESTINO\""

echo "restaurando em $DESTINO..."
pg_restore --dbname="$DESTINO" --no-owner --no-privileges --exit-on-error "$TMP/dump"

echo "restauração concluída. Conferência rápida:"
psql -d "$DESTINO" -Atc "select 'tabelas: ' || count(*) from information_schema.tables where table_schema = 'public'"
for tabela in usuario loja produto pedido; do
  if psql -d "$DESTINO" -Atc "select to_regclass('public.$tabela') is not null" | grep -q t; then
    echo "  $tabela: $(psql -d "$DESTINO" -Atc "select count(*) from $tabela") linha(s)"
  fi
done
