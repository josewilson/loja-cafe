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

echo "9. Cozinha: só o pedido PAGO está na fila; cada papel dá o seu passo"
criar_usuario() { # criar_usuario <papel> -> imprime o token de uma conta nova na loja A
  local e="e2e-$1-$SUFIXO@exemplo.com"
  chamar "$ADMIN" POST /usuarios "{\"nome\":\"E2E $1\",\"email\":\"$e\",\"senha\":\"segredo123\",\"papel\":\"$1\",\"lojaId\":$LOJA_A}" > /dev/null
  entrar "$e" segredo123
}
COZINHA=$(criar_usuario COZINHA)
GERENTE=$(criar_usuario GERENTE)
[ "$(chamar "$COZINHA" GET /cozinha | json "d.map(p => p.id).join()")" = "$ID_PIX" ] \
  || falhar "a fila deveria ter só o pedido $ID_PIX (o recusado não entra)"
mudar() { chamar "$1" PUT "/pedidos/$2/preparo" "{\"preparo\":\"$3\"}" | json "d.preparo"; }
[ "$(mudar "$COZINHA" "$ID_PIX" PREPARANDO)" = "PREPARANDO" ] || falhar "cozinha deveria iniciar o preparo"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$API/pedidos/$ID_PIX/preparo" -H "Authorization: Bearer $ATENDENTE" -H 'Content-Type: application/json' -d '{"preparo":"PRONTO"}')" = "403" ] \
  || falhar "atendente não deveria marcar PRONTO (403)"
[ "$(mudar "$COZINHA" "$ID_PIX" PRONTO)" = "PRONTO" ] || falhar "cozinha deveria marcar PRONTO"
[ "$(mudar "$ATENDENTE" "$ID_PIX" RETIRADO)" = "RETIRADO" ] || falhar "atendente deveria marcar RETIRADO"
[ "$(chamar "$COZINHA" GET /cozinha | json "d.length")" = "0" ] || falhar "depois de retirado a fila deveria esvaziar"
ok "fila -> PREPARANDO (cozinha) -> PRONTO (cozinha) -> RETIRADO (atendente) -> fila vazia"

echo "10. Cancelamento: só o gerente, e o estoque volta"
ID_CANC=$(pedir "$ATENDENTE" PIX)
aguardar_status "$ATENDENTE" "$ID_CANC" PAGO
[ "$(estoque_do_cappuccino "$ATENDENTE")" = "32" ] || falhar "o novo pedido deveria ter baixado o estoque para 32"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$API/pedidos/$ID_CANC/cancelar" -H "Authorization: Bearer $ATENDENTE" -H 'Content-Type: application/json' -d '{}')" = "403" ] \
  || falhar "atendente não cancela (403)"
[ "$(chamar "$GERENTE" PUT "/pedidos/$ID_CANC/cancelar" '{"motivo":"teste e2e"}' | json "d.preparo")" = "CANCELADO" ] || falhar "gerente deveria cancelar"
[ "$(estoque_do_cappuccino "$ATENDENTE")" = "41" ] || falhar "o cancelamento deveria devolver o estoque (41)"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$API/pedidos/$ID_CANC/cancelar" -H "Authorization: Bearer $GERENTE" -H 'Content-Type: application/json' -d '{}')" = "409" ] \
  || falhar "cancelar de novo deveria dar 409"
ok "cancelado, estoque de volta a 41, segundo cancelamento 409"

echo "11. Vendas do dia e caixa: só contam os pedidos pagos e não cancelados"
V=$(chamar "$GERENTE" GET /relatorios/vendas)
[ "$(echo "$V" | json "d.pedidos")" = "1" ] || falhar "vendas do dia: esperava 1 pedido (o Pix); recusado e cancelado não contam"
[ "$(echo "$V" | json "d.total")" = "135" ] || falhar "vendas do dia: total esperado 135"
[ "$(echo "$V" | json "d.cancelados + d.recusados")" = "2" ] || falhar "vendas do dia: 1 cancelado + 1 recusado"
[ "$(chamar "$GERENTE" GET /caixa/resumo | json "d.pedidos")" = "1" ] || falhar "o turno aberto deveria ter 1 pedido"
[ "$(codigo "$ATENDENTE" GET /caixa/resumo)" = "403" ] || falhar "atendente não vê o caixa (403)"
ok "1 venda de R\$ 135,00 (o recusado e o cancelado ficaram de fora)"

echo "12. Fechar o caixa: o turno zera e pedido já contado não cancela mais"
ID_FECHADO=$(pedir "$GERENTE" PIX)
aguardar_status "$GERENTE" "$ID_FECHADO" PAGO
FECH=$(chamar "$GERENTE" POST /caixa/fechamentos '{"dinheiroContado":0,"observacao":"e2e"}')
[ "$(echo "$FECH" | json "d.pedidos")" = "2" ] || falhar "o fechamento deveria contar 2 pedidos"
[ "$(echo "$FECH" | json "d.diferenca")" = "0" ] || falhar "sem dinheiro vendido a diferença deveria ser 0"
[ "$(chamar "$GERENTE" GET /caixa/resumo | json "d.pedidos")" = "0" ] || falhar "depois de fechar o turno deveria estar vazio"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$API/pedidos/$ID_FECHADO/cancelar" -H "Authorization: Bearer $GERENTE" -H 'Content-Type: application/json' -d '{}')" = "409" ] \
  || falhar "pedido já contado no fechamento não deveria cancelar (409)"
ok "fechado: 2 pedidos, turno zerado, cancelamento do pedido contado barrado"

echo "13. Cardápio da rede: o ADMIN cria e reajusta; produto desativado some do balcão"
[ "$(codigo "$GERENTE" GET /cardapio)" = "403" ] || falhar "gerente não edita o cardápio da rede (403)"
PROD=$(chamar "$ADMIN" POST /cardapio "{\"nome\":\"E2E Brownie $SUFIXO\",\"descricao\":\"teste\",\"emoji\":\"E2E\",\"preco\":11.50}" | json "d.id")
[ -n "$PROD" ] || falhar "o ADMIN deveria criar o produto"
no_balcao() { chamar "$ATENDENTE" GET /produtos | json "d.filter(p => p.id === $PROD).map(p => p.estoque + ':' + p.preco).join()"; }
[ "$(no_balcao)" = "0:11.5" ] || falhar "o produto novo deveria aparecer no balcão com estoque 0 (veio '$(no_balcao)')"
chamar "$ADMIN" PUT "/lojas/$LOJA_A/estoque/$PROD" '{"quantidade":5}' > /dev/null
vender() { chamar "$ATENDENTE" POST /pedidos "{\"formaPagamento\":\"PIX\",\"itens\":[{\"produtoId\":$PROD,\"quantidade\":2}]}" | json "d.valor"; }
[ "$(vender)" = "23" ] || falhar "2 brownies a 11,50 deveriam dar 23"
chamar "$ADMIN" PUT "/cardapio/$PROD" "{\"nome\":\"E2E Brownie $SUFIXO\",\"emoji\":\"E2E\",\"preco\":12.00,\"ativo\":true}" > /dev/null
[ "$(vender)" = "24" ] || falhar "depois do reajuste, 2 brownies a 12,00 deveriam dar 24"
chamar "$ADMIN" PUT "/cardapio/$PROD" "{\"nome\":\"E2E Brownie $SUFIXO\",\"emoji\":\"E2E\",\"preco\":12.00,\"ativo\":false}" > /dev/null
[ -z "$(no_balcao)" ] || falhar "produto desativado não deveria aparecer no balcão"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/pedidos" -H "Authorization: Bearer $ATENDENTE" -H 'Content-Type: application/json' -d "{\"formaPagamento\":\"PIX\",\"itens\":[{\"produtoId\":$PROD,\"quantidade\":1}]}")" = "400" ] \
  || falhar "pedido com produto desativado deveria dar 400"
ok "criado (id $PROD), vendido a 11,50 e depois a 12,00, desativado: some do balcão e não aceita pedido"

echo "TUDO CERTO"
