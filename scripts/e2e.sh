#!/usr/bin/env bash
# Teste de ponta a ponta: conta -> pedido -> Kafka -> Pagamentos -> status, contra o sistema NO AR
# (Postgres, Kafka, pedidos-api e pagamentos-py rodando). Uso: scripts/e2e.sh [url-da-api]
# Sai com código 1 no primeiro passo que falhar.
set -euo pipefail

API=${1:-http://localhost:8081}
EMAIL="e2e-$RANDOM$RANDOM@exemplo.com"

falhar() { echo "FALHOU: $*" >&2; exit 1; }
ok() { echo "  ok: $*"; }
# Lê JSON com node (o projeto já exige Node para o front): json "<expressão sobre d>" < entrada
json() { node -pe "const d = JSON.parse(require(\"fs\").readFileSync(0, \"utf8\")); $1"; }
campo() { json "d[\"$1\"]"; }
estoque_do_cappuccino() { curl -fsS "$API/produtos" | json "d.find(p => p.id === 2).estoque"; }
# aguardar_status <pedidoId> <status>: espera até 40 s pelo status
aguardar_status() {
  for _ in $(seq 1 40); do
    atual=$(curl -fsS "$API/pedidos?limite=20" -H "Authorization: Bearer $TOKEN" | json "(d.find(p => p.id === $1) || {}).status")
    [ "$atual" = "$2" ] && return 0
    sleep 1
  done
  falhar "pedido $1 deveria estar $2 (último status visto: '${atual:-nenhum}')"
}
pedir() { # pedir <forma>  -> imprime o JSON do pedido (9 cappuccinos grandes = R$ 135,00)
  curl -fsS -X POST "$API/pedidos" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"formaPagamento\":\"$1\",\"itens\":[{\"produtoId\":2,\"quantidade\":9,\"tamanho\":\"GRANDE\"}]}"
}

echo "1. API no ar e cardápio público"
curl -fsS "$API/produtos" > /dev/null || falhar "GET /produtos"
ok "GET /produtos"

echo "2. Pedidos exigem login"
codigo=$(curl -s -o /dev/null -w '%{http_code}' "$API/pedidos")
[ "$codigo" = "401" ] || falhar "GET /pedidos sem token deveria dar 401 e deu $codigo"
ok "sem token: 401"

echo "3. Cadastro"
TOKEN=$(curl -fsS -X POST "$API/auth/registrar" -H 'Content-Type: application/json' \
  -d "{\"nome\":\"E2E\",\"email\":\"$EMAIL\",\"senha\":\"segredo123\"}" | campo token)
[ -n "$TOKEN" ] || falhar "registrar não devolveu token"
ok "conta criada"

ANTES=$(estoque_do_cappuccino)

echo "4. Pix de R$ 135,00 (sem limite) deve ser PAGO"
ID_PIX=$(pedir PIX | campo id)
[ -n "$ID_PIX" ] || falhar "POST /pedidos (Pix) não devolveu id"
aguardar_status "$ID_PIX" PAGO
ok "pedido $ID_PIX PAGO"

echo "5. Débito de R$ 135,00 (acima do limite) deve ser RECUSADO e devolver o estoque"
ID_DEB=$(pedir DEBITO | campo id)
aguardar_status "$ID_DEB" RECUSADO
ok "pedido $ID_DEB RECUSADO"

DEPOIS=$(estoque_do_cappuccino)
# Só o Pix ficou com 9 cappuccinos; o recusado devolveu os dele.
[ "$DEPOIS" = "$((ANTES - 9))" ] || falhar "estoque do cappuccino: antes=$ANTES depois=$DEPOIS (esperado $((ANTES - 9)))"
ok "estoque: $ANTES -> $DEPOIS"

echo "TUDO CERTO"
