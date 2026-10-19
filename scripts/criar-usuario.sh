#!/usr/bin/env bash
# Cria uma conta da equipe pela API, entrando como ADMIN (ADMIN_EMAIL / ADMIN_PASSWORD de infra/.env).
# Uso: scripts/criar-usuario.sh <email> <nome> <ADMIN|GERENTE|ATENDENTE|COZINHA> [lojaId]
# A senha da nova conta é pedida no terminal (não aparece na tela nem fica no histórico). Loja 1 = "Matriz".
set -euo pipefail

[ $# -ge 3 ] || { sed -n '2,5p' "$0"; exit 1; }
EMAIL=$1; NOME=$2; PAPEL=$3; LOJA=${4:-}

RAIZ=$(cd "$(dirname "$0")/.." && pwd)
if [ -f "$RAIZ/infra/.env" ]; then set -a; . "$RAIZ/infra/.env"; set +a; fi
: "${ADMIN_EMAIL:?defina ADMIN_EMAIL (infra/.env)}" "${ADMIN_PASSWORD:?defina ADMIN_PASSWORD (infra/.env)}"
API=${API:-http://localhost:8081}

read -r -s -p "Senha da nova conta (8 a 72 caracteres): " SENHA; echo
[ ${#SENHA} -ge 8 ] || { echo "Senha curta demais." >&2; exit 1; }

json() { node -pe "const d = JSON.parse(require(\"fs\").readFileSync(0, \"utf8\")); $1"; }
TOKEN=$(curl -fsS -X POST "$API/auth/entrar" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"senha\":\"$ADMIN_PASSWORD\"}" | json "d.token")

# O corpo é montado pelo node para escapar aspas e acentos corretamente.
CORPO=$(EMAIL="$EMAIL" NOME="$NOME" PAPEL="$PAPEL" LOJA="$LOJA" SENHA="$SENHA" node -pe \
  'JSON.stringify({ nome: process.env.NOME, email: process.env.EMAIL, senha: process.env.SENHA, papel: process.env.PAPEL, ...(process.env.LOJA && { lojaId: Number(process.env.LOJA) }) })')
curl -sS -X POST "$API/usuarios" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$CORPO"
echo
