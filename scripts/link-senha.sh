#!/usr/bin/env bash
# Gera o link de nova senha (Fase 8b) direto no banco, para quem não consegue entrar, em especial o ADMIN que esqueceu a senha.
# Uso (no servidor): scripts/link-senha.sh <email>            desenvolvimento (infra/compose.yml)
#                    COMPOSE=deploy/compose.prod.yml URL=https://meu-site scripts/link-senha.sh <email>      produção
# O link vale 1 hora e uma vez. Só o banco guarda o hash; este script não passa pela API (não grava auditoria).
set -euo pipefail

[ $# -eq 1 ] || { sed -n '2,5p' "$0"; exit 1; }
EMAIL=$(printf %s "$1" | tr '[:upper:]' '[:lower:]')

RAIZ=$(cd "$(dirname "$0")/.." && pwd)
COMPOSE=${COMPOSE:-$RAIZ/infra/compose.yml}
URL=${URL:-http://localhost:5173}
if [ -f "$(dirname "$COMPOSE")/.env" ]; then set -a; . "$(dirname "$COMPOSE")/.env"; set +a; fi

# tr -d '\r': o openssl do Windows termina a linha com CRLF, e o \r entraria no token
TOKEN=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\r\n')
HASH=$(printf %s "$TOKEN" | sha256sum | cut -d' ' -f1)

# -v email=...: o psql escapa o valor (nada de montar SQL com texto do usuário).
# O grep fica só com o id: "UPDATE 0" (ninguém achado) não conta.
SQL="update usuario set redefinicao_hash = '$HASH', redefinicao_expira_em = now() + interval '1 hour' where email = :'email' and ativo returning id;"
ACHOU=$(echo "$SQL" | docker compose -f "$COMPOSE" exec -T postgres psql -U "${POSTGRES_USER:-loja}" -d loja_cafe -tA -v "email=$EMAIL" \
  | tr -d '\r' | grep -E '^[0-9]+$' || true)
[ -n "$ACHOU" ] || { echo "Nenhuma conta ativa com o e-mail $EMAIL." >&2; exit 1; }
echo "Entregue só à pessoa (vale 1 hora, uma vez):"
echo "$URL/?redefinir=$TOKEN"
