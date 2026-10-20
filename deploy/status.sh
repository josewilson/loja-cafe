#!/usr/bin/env bash
# Painel rápido de saúde da produção: contêineres, /saude, último backup, disco e pedidos parados.
# Sai com código 1 se algo estiver errado (dá para pôr numa rotina de monitoramento).
# Uso (na máquina do servidor, na raiz do repositório): deploy/status.sh
set -uo pipefail
export MSYS_NO_PATHCONV=1

RAIZ=$(cd "$(dirname "$0")/.." && pwd)
COMPOSE=(docker compose -f "$RAIZ/deploy/compose.prod.yml")
ENV_FILE=$RAIZ/deploy/.env
[ -f "$ENV_FILE" ] && { set -a; . "$ENV_FILE"; set +a; }
PROBLEMAS=0
problema() { echo "  PROBLEMA: $*"; PROBLEMAS=$((PROBLEMAS + 1)); }

echo "== Contêineres"
"${COMPOSE[@]}" ps --format 'table {{.Service}}\t{{.Status}}'
"${COMPOSE[@]}" ps --format '{{.Service}} {{.Status}}' | grep -E "unhealthy|Exited|Restarting" && problema "há contêiner parado ou doente"

echo "== Saúde da API"
case "${SITE_ADDRESS:-localhost}" in
  :80) URL=http://localhost ;;
  localhost) URL=https://localhost ;;
  *) URL=https://$SITE_ADDRESS ;;
esac
SAUDE=$(curl -sk -m 10 "$URL/api/saude" || true)
echo "  $URL/api/saude -> ${SAUDE:-sem resposta}"
echo "$SAUDE" | grep -q '"status":"ok"' || problema "a API não está saudável"

echo "== Backup"
ULTIMO=$("${COMPOSE[@]}" exec -T backup sh -c 'ls -1t /backups | grep "\.dump\.age$" | head -1' 2>/dev/null | tr -d '\r')
if [ -z "$ULTIMO" ]; then
  problema "nenhum backup encontrado"
else
  IDADE_H=$("${COMPOSE[@]}" exec -T backup sh -c "echo \$(( (\$(date +%s) - \$(stat -c %Y /backups/$ULTIMO)) / 3600 ))" | tr -d '\r')
  echo "  último backup: $ULTIMO (há ${IDADE_H} h)"
  [ "${IDADE_H:-999}" -le 26 ] || problema "o último backup tem mais de 26 horas"
fi
[ -n "${RCLONE_DEST:-}" ] || problema "RCLONE_DEST vazio: os backups estão só neste servidor"
[ -n "${HEALTHCHECK_URL:-}" ] || echo "  (aviso: HEALTHCHECK_URL vazio: ninguém será avisado se o backup parar de rodar)"

echo "== Disco desta máquina"
df -h "$RAIZ" | tail -1 | awk '{print "  usado: " $5 " de " $2}'
USO=$(df --output=pcent "$RAIZ" 2>/dev/null | tail -1 | tr -dc '0-9')
[ "${USO:-0}" -lt 90 ] || problema "disco com ${USO}% de uso"

echo "== Pedidos parados"
PARADOS=$("${COMPOSE[@]}" exec -T postgres psql -U "${POSTGRES_USER:-loja}" -d loja_cafe -Atc "select count(*) from pedido where status = 'AGUARDANDO_PAGAMENTO' and criado_em < now() - interval '10 minutes'" 2>/dev/null | tr -d '\r')
echo "  aguardando pagamento há mais de 10 min: ${PARADOS:-?}"
[ "${PARADOS:-0}" = "0" ] || problema "há pedidos parados (Pagamentos fora do ar?). Se persistir, POST /api/pedidos/reenfileirar como ADMIN"

echo
if [ "$PROBLEMAS" -eq 0 ]; then echo "TUDO CERTO"; else echo "$PROBLEMAS problema(s) acima"; exit 1; fi
