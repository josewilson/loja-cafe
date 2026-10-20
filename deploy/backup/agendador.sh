#!/usr/bin/env bash
# Roda o backup todo dia na hora combinada (BACKUP_HORA, padrão 03:30, no fuso TZ). É o processo principal da imagem de backup.
# BACKUP_AO_INICIAR=1 faz um backup logo ao subir (útil na primeira instalação, para ver que funciona).
set -uo pipefail

HORA=${BACKUP_HORA:-03:30}
echo "agendador de backup: todo dia às $HORA (fuso ${TZ:-UTC})"

if [ "${BACKUP_AO_INICIAR:-0}" = "1" ]; then
  /usr/local/bin/backup.sh || echo "backup inicial FALHOU (veja acima)"
fi

while true; do
  agora=$(date +%s)
  alvo=$(date -d "today $HORA" +%s)
  [ "$alvo" -le "$agora" ] && alvo=$(date -d "tomorrow $HORA" +%s)
  echo "próximo backup em $(date -d "@$alvo" '+%F %T'), daqui a $(( (alvo - agora) / 60 )) min"
  sleep $(( alvo - agora ))
  /usr/local/bin/backup.sh || echo "backup FALHOU (veja acima; o Healthchecks.io é avisado)"
done
