#!/usr/bin/env bash
# Retenção dos backups: guarda TUDO dos últimos 14 dias e, além disso, os de DOMINGO até 8 semanas; apaga o resto.
# O backup mais novo nunca é apagado, aconteça o que acontecer.
# Uso: retencao.sh <destino> [--simular]      (destino: pasta local ou remoto do rclone, ex.: offsite:bucket/loja-cafe)
# Para testar com outra data: HOJE=2026-10-20 retencao.sh ... --simular
set -euo pipefail

DESTINO=${1:?uso: retencao.sh <destino> [--simular]}
SIMULAR=${2:-}
HOJE=${HOJE:-$(date +%F)}
DIAS_DIARIOS=${RETER_DIAS:-14}
DIAS_SEMANAIS=$(( ${RETER_SEMANAS:-8} * 7 ))

mapfile -t ARQUIVOS < <(rclone lsf "$DESTINO" --files-only 2>/dev/null | grep -E '^loja_cafe-[0-9]{8}-[0-9]{6}\.dump\.age$' | sort || true)
if [ ${#ARQUIVOS[@]} -eq 0 ]; then
  echo "retenção: nenhum backup em $DESTINO"
  exit 0
fi

MAIS_NOVO=${ARQUIVOS[-1]}
HOJE_S=$(date -d "$HOJE" +%s)
mantidos=0
apagados=0
for arquivo in "${ARQUIVOS[@]}"; do
  d=${arquivo#loja_cafe-}
  dia="${d:0:4}-${d:4:2}-${d:6:2}"
  idade=$(( (HOJE_S - $(date -d "$dia" +%s)) / 86400 ))
  domingo=$(( $(date -d "$dia" +%u) == 7 ))
  if [ "$arquivo" = "$MAIS_NOVO" ] || [ "$idade" -le "$DIAS_DIARIOS" ]; then
    echo "MANTER  $arquivo (${idade} dias)"
    mantidos=$((mantidos + 1))
  elif [ "$idade" -le "$DIAS_SEMANAIS" ] && [ "$domingo" -eq 1 ]; then
    echo "MANTER  $arquivo (${idade} dias, domingo: semanal)"
    mantidos=$((mantidos + 1))
  else
    echo "APAGAR  $arquivo (${idade} dias)"
    apagados=$((apagados + 1))
    if [ "$SIMULAR" != "--simular" ]; then
      rclone deletefile "$DESTINO/$arquivo"
      rclone deletefile "$DESTINO/$arquivo.sha256" 2>/dev/null || true   # a soma de verificação sai junto
    fi
  fi
done
echo "retenção em $DESTINO: $mantidos mantido(s), $apagados apagado(s)${SIMULAR:+ (simulação: nada foi apagado)}"
