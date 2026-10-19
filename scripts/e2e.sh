#!/usr/bin/env bash
# Teste de ponta a ponta contra o sistema NO AR (Postgres, Kafka, pedidos-api e pagamentos-py rodando):
#   ADMIN -> cria 2 lojas e um atendente -> pedido -> Kafka -> Pagamentos -> status -> estoque da loja.
# Usa ADMIN_EMAIL e ADMIN_PASSWORD (ambiente ou infra/.env). Cada execução cria lojas novas (não gasta o estoque das existentes).
# Uso: scripts/e2e.sh [url-da-api]. Sai com código 1 no primeiro passo que falhar.
set -euo pipefail

RAIZ=$(cd "$(dirname "$0")/.." && pwd)
if [ -f "$RAIZ/infra/.env" ]; then set -a; . "$RAIZ/infra/.env"; set +a; fi
: "${ADMIN_EMAIL:?defina ADMIN_EMAIL (infra/.env)}" "${ADMIN_PASSWORD:?defina ADMIN_PASSWORD (infra/.env)}"

API=${1:-http://localhost:8081}
SUFIXO="$RANDOM$RANDOM"

falhar() { echo "FALHOU: $*" >&2; exit 1; }
ok() { echo "  ok: $*"; }
# Lê JSON com node (o projeto já exige Node para o front): json "<expressão sobre d>" < entrada
json() { node -pe "const d = JSON.parse(require(\"fs\").readFileSync(0, \"utf8\")); $1"; }
# chamar <token> <metodo> <caminho> [corpo]: devolve o corpo; falha se o HTTP não for 2xx
chamar() {
  local args=(-fsS -X "$2" "$API$3" -H "Authorization: Bearer $1")
  if [ $# -ge 4 ]; then args+=(-H 'Content-Type: application/json' -d "$4"); fi
  curl "${args[@]}"
}
codigo() { # codigo <token|-> <metodo> <caminho> -> só o status HTTP
  local args=(-s -o /dev/null -w '%{http_code}' -X "$2" "$API$3")
  if [ "$1" != "-" ]; then args+=(-H "Authorization: Bearer $1"); fi
  curl "${args[@]}"
}
entrar() { curl -fsS -X POST "$API/auth/entrar" -H 'Content-Type: application/json' -d "{\"email\":\"$1\",\"senha\":\"$2\"}" | json "d.token"; }
aguardar_status() { # aguardar_status <token> <pedidoId> <status>: espera até 40 s
  for _ in $(seq 1 40); do
    atual=$(chamar "$1" GET "/pedidos?limite=20" | json "(d.find(p => p.id === $2) || {}).status")
    [ "$atual" = "$3" ] && return 0
    sleep 1
  done
  falhar "pedido $2 deveria estar $3 (último status visto: '${atual:-nenhum}')"
}
pedir() { # pedir <token> <forma>: 9 cappuccinos grandes = R$ 135,00 (acima do limite dos cartões); imprime o id
  chamar "$1" POST /pedidos "{\"cliente\":\"E2E\",\"formaPagamento\":\"$2\",\"itens\":[{\"produtoId\":2,\"quantidade\":9,\"tamanho\":\"GRANDE\"}]}" | json "d.id"
}
estoque_do_cappuccino() { # estoque_do_cappuccino <token> [lojaId]
  chamar "$1" GET "/produtos${2:+?lojaId=$2}" | json "d.find(p => p.id === 2).estoque"
}

echo "1. API no ar; só /opcoes é público"
[ "$(codigo - GET /opcoes)" = "200" ] || falhar "GET /opcoes deveria ser público"
[ "$(codigo - GET /produtos)" = "401" ] || falhar "GET /produtos sem token deveria dar 401"
[ "$(codigo - GET /pedidos)" = "401" ] || falhar "GET /pedidos sem token deveria dar 401"
ok "sem token: 401 (exceto /opcoes)"

echo "2. Login do ADMIN"
ADMIN=$(entrar "$ADMIN_EMAIL" "$ADMIN_PASSWORD")
[ -n "$ADMIN" ] || falhar "login do ADMIN"
ok "ADMIN logado"

echo "3. Duas lojas (50 unidades de cada produto) e um atendente na primeira"
LOJA_A=$(chamar "$ADMIN" POST /lojas "{\"nome\":\"E2E A $SUFIXO\",\"estoqueInicial\":50}" | json "d.id")
LOJA_B=$(chamar "$ADMIN" POST /lojas "{\"nome\":\"E2E B $SUFIXO\",\"estoqueInicial\":50}" | json "d.id")
EMAIL="e2e-$SUFIXO@exemplo.com"
chamar "$ADMIN" POST /usuarios "{\"nome\":\"Atendente E2E\",\"email\":\"$EMAIL\",\"senha\":\"segredo123\",\"papel\":\"ATENDENTE\",\"lojaId\":$LOJA_A}" > /dev/null
ATENDENTE=$(entrar "$EMAIL" segredo123)
ok "lojas $LOJA_A e $LOJA_B; atendente logado"

echo "4. O atendente não administra nada e não vê outra loja"
[ "$(codigo "$ATENDENTE" GET /usuarios)" = "403" ] || falhar "atendente em GET /usuarios deveria dar 403"
[ "$(codigo "$ATENDENTE" GET "/pedidos?lojaId=$LOJA_B")" = "403" ] || falhar "atendente vendo outra loja deveria dar 403"
ok "papéis e isolamento: 403"

echo "5. Pix de R\$ 135,00 (sem limite) deve ser PAGO"
ID_PIX=$(pedir "$ATENDENTE" PIX)
[ -n "$ID_PIX" ] || falhar "POST /pedidos (Pix) não devolveu id"
aguardar_status "$ATENDENTE" "$ID_PIX" PAGO
ok "pedido $ID_PIX PAGO"

echo "6. Débito de R\$ 135,00 (acima do limite) deve ser RECUSADO e devolver o estoque da loja"
ID_DEB=$(pedir "$ATENDENTE" DEBITO)
aguardar_status "$ATENDENTE" "$ID_DEB" RECUSADO
ok "pedido $ID_DEB RECUSADO"

echo "7. Estoque: só a loja A gastou (o Pix); a B ficou intacta"
A=$(estoque_do_cappuccino "$ATENDENTE")
[ "$A" = "41" ] || falhar "estoque da loja A: $A (esperado 41 = 50 - 9 do Pix; o recusado devolveu os dele)"
B=$(estoque_do_cappuccino "$ADMIN" "$LOJA_B")
[ "$B" = "50" ] || falhar "estoque da loja B: $B (esperado 50)"
ok "loja A: $A · loja B: $B"

echo "8. Os pedidos ficam só na loja certa e a auditoria registra"
N_B=$(chamar "$ADMIN" GET "/pedidos?lojaId=$LOJA_B" | json "d.length")
[ "$N_B" = "0" ] || falhar "a loja B não deveria ter pedidos (tem $N_B)"
[ "$(chamar "$ADMIN" GET "/auditoria?lojaId=$LOJA_A&limite=100" | json "d.some(x => x.acao === 'PEDIDO_CRIADO')")" = "true" ] \
  || falhar "auditoria sem PEDIDO_CRIADO da loja A"
ok "pedidos isolados e auditoria ok"

echo "TUDO CERTO"
