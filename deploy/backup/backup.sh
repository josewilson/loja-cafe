#!/usr/bin/env bash
# Backup do Postgres: pg_dump -> confere -> CRIPTOGRAFA (age, chave pública) -> guarda local -> envia para fora do servidor -> retenção.
# O servidor só tem a chave PÚBLICA: quem invadir o servidor não consegue ler os backups. A privada fica com o dono, fora daqui.
#
# Configuração por variáveis de ambiente (veja deploy/.env.example):
#   PGHOST PGUSER PGPASSWORD PGDATABASE   banco a copiar
#   BACKUP_AGE_RECIPIENT                  chave pública age (age1...), OBRIGATÓRIA
#   RCLONE_DEST                           destino fora do servidor (ex.: offsite:bucket/loja-cafe); vazio = só local (e um aviso)
#   HEALTHCHECK_URL                       (opcional) endereço de ping do Healthchecks.io: avisa se o backup parar de rodar
#   BACKUP_DIR                            pasta local (padrão /backups)
set -euo pipefail

BACKUP_DIR=${BACKUP_DIR:-/backups}
PGDATABASE=${PGDATABASE:-loja_cafe}
export PGDATABASE
PREFIXO=${BACKUP_PREFIXO:-loja_cafe}
NOME="${PREFIXO}-$(date +%Y%m%d-%H%M%S).dump.age"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

ping() { # ping <sufixo> [mensagem]: avisa o Healthchecks.io (se configurado); nunca derruba o backup
  [ -n "${HEALTHCHECK_URL:-}" ] || return 0
  curl -fsS -m 10 --retry 3 --data-raw "${2:-}" "${HEALTHCHECK_URL%/}${1:+/$1}" > /dev/null 2>&1 || echo "aviso: não consegui avisar o Healthchecks.io" >&2
}
falhou() {
  local msg="backup FALHOU na linha $1: ${2:-erro}"
  echo "$msg" >&2
  ping fail "$msg"
}
trap 'falhou "$LINENO" "$BASH_COMMAND"' ERR

# 1. Pré-requisitos: sem chave pública não há backup (nunca gravamos um backup sem criptografia).
case "${BACKUP_AGE_RECIPIENT:-}" in
  age1*) ;;
  *) echo "defina BACKUP_AGE_RECIPIENT com a chave pública age (age1...). Gere o par com deploy/gerar-chave-backup.sh" >&2; ping fail "BACKUP_AGE_RECIPIENT ausente"; exit 1 ;;
esac
mkdir -p "$BACKUP_DIR"
ping start

# 2. Dump em formato "custom" (já vem comprimido e permite restaurar pedaços). Confere se o arquivo é legível antes de seguir.
echo "[$(date +%T)] copiando o banco $PGDATABASE..."
pg_dump --format=custom --no-owner --no-privileges --file="$TMP/dump" "$PGDATABASE"
pg_restore --list "$TMP/dump" > /dev/null
[ -s "$TMP/dump" ] || { echo "o dump saiu vazio" >&2; exit 1; }
TAMANHO_DUMP=$(stat -c %s "$TMP/dump")

# 3. Criptografa com a chave pública e guarda local. A soma de verificação vai junto, para conferir a cópia de fora.
age --recipient "$BACKUP_AGE_RECIPIENT" --output "$BACKUP_DIR/$NOME" "$TMP/dump"
SOMA=$(sha256sum "$BACKUP_DIR/$NOME" | cut -d' ' -f1)
echo "$SOMA  $NOME" > "$BACKUP_DIR/$NOME.sha256"
echo "[$(date +%T)] backup local: $NOME ($(stat -c %s "$BACKUP_DIR/$NOME") bytes criptografados, dump de $TAMANHO_DUMP bytes)"

# 4. Cópia fora do servidor (se a máquina morrer, o backup não morre junto).
AVISO=""
if [ -n "${RCLONE_DEST:-}" ]; then
  rclone copyto "$BACKUP_DIR/$NOME" "$RCLONE_DEST/$NOME"
  rclone copyto "$BACKUP_DIR/$NOME.sha256" "$RCLONE_DEST/$NOME.sha256"
  REMOTO=$(rclone size --json "$RCLONE_DEST/$NOME" | sed -n 's/.*"bytes":\([0-9]*\).*/\1/p')
  LOCAL=$(stat -c %s "$BACKUP_DIR/$NOME")
  [ "$REMOTO" = "$LOCAL" ] || { echo "a cópia de fora tem $REMOTO bytes e a local $LOCAL" >&2; exit 1; }
  echo "[$(date +%T)] cópia fora do servidor conferida: $RCLONE_DEST/$NOME"
else
  AVISO=" ATENÇÃO: sem RCLONE_DEST o backup está só neste servidor."
  echo "ATENÇÃO: RCLONE_DEST vazio. O backup está SÓ neste servidor: se a máquina morrer, ele morre junto." >&2
fi

# 5. Retenção (14 dias + domingos por 8 semanas), local e fora. Falha aqui não invalida o backup de hoje.
"$(dirname "$0")/retencao.sh" "$BACKUP_DIR" || echo "aviso: a retenção local falhou" >&2
if [ -n "${RCLONE_DEST:-}" ]; then
  "$(dirname "$0")/retencao.sh" "$RCLONE_DEST" || echo "aviso: a retenção remota falhou" >&2
fi
# os .sha256 órfãos (do arquivo já apagado) saem junto
for soma in "$BACKUP_DIR"/*.sha256; do [ -e "$soma" ] && [ ! -e "${soma%.sha256}" ] && rm -f "$soma"; done

# 6. Disco: avisa antes de encher (backup que não cabe é backup que falha).
USO=$(df --output=pcent "$BACKUP_DIR" | tail -1 | tr -dc '0-9')
if [ "${USO:-0}" -ge 90 ]; then
  AVISO="$AVISO DISCO COM ${USO}% DE USO."
  echo "ATENÇÃO: disco com ${USO}% de uso" >&2
fi

trap - ERR
ping "" "ok: $NOME, dump de $TAMANHO_DUMP bytes.$AVISO"
echo "[$(date +%T)] backup concluído: $NOME"
