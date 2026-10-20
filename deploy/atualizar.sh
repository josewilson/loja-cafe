#!/usr/bin/env bash
# Atualiza a produção para uma versão nova COM REDE DE SEGURANÇA. Roda na máquina do servidor (qualquer pasta do repositório).
#
#   deploy/atualizar.sh                    puxa a versão nova da branch atual (só avanço direto) e atualiza
#   deploy/atualizar.sh --versao REF       vai para REF (branch, tag ou commit); serve também para VOLTAR a uma versão antiga
#   deploy/atualizar.sh --simular          só mostra o que faria; não muda nada
#   deploy/atualizar.sh --forcar           atualiza mesmo que a versão seja a mesma (reconstrói as imagens)
#
# O que ele faz, em ordem (qualquer falha ANTES do passo 5 deixa tudo exatamente como estava):
#   1. confere o servidor: sem alterações locais no código, pilha no ar, disco com folga
#   2. faz um BACKUP novo (sem backup novo, não atualiza)
#   3. guarda as imagens que estão rodando e atualiza o código
#   4. CONSTRÓI as imagens novas sem tocar nos contêineres (se a construção falhar, nada mudou)
#   5. sobe a versão nova e espera a API, o Pagamentos e o site ficarem saudáveis (até 3 minutos)
#   6. se não ficarem, VOLTA SOZINHO para a versão anterior (imagens e código)
# Códigos de saída: 0 atualizado (ou nada a fazer) · 1 não atualizou (a versão anterior continua no ar) · 2 SITUAÇÃO GRAVE (veja a mensagem)
set -uo pipefail
export MSYS_NO_PATHCONV=1

# O script se copia para um arquivo temporário e roda de lá: o git checkout abaixo pode trocar ESTE arquivo por outra versão,
# e o bash lê o script aos poucos, o que dá resultado imprevisível se o arquivo mudar no meio da execução.
if [ -z "${ATUALIZAR_RAIZ:-}" ]; then
  RAIZ_ORIGINAL=$(cd "$(dirname "$0")/.." && pwd)
  COPIA=$(mktemp "${TMPDIR:-/tmp}/atualizar.XXXXXX")
  cp "$0" "$COPIA"
  ATUALIZAR_RAIZ=$RAIZ_ORIGINAL exec bash "$COPIA" "$@"
fi
RAIZ=$ATUALIZAR_RAIZ
RAIZ_DOCKER=$(cd "$RAIZ" && { pwd -W 2>/dev/null || pwd; })   # pwd -W: caminho no formato do Windows (Git Bash); no Linux cai no pwd
cd "$RAIZ"
COMPOSE=(docker compose -f "$RAIZ_DOCKER/deploy/compose.prod.yml")
LOG=$RAIZ/deploy/atualizacoes.log
SERVICOS_COM_IMAGEM=(pedidos-api pagamentos web backup)
ESPERA_SEGUNDOS=${ESPERA_SEGUNDOS:-180}

VERSAO=""; SIMULAR=0; FORCAR=0
while [ $# -gt 0 ]; do
  case "$1" in
    --versao) VERSAO=${2:?falta a versão depois de --versao}; shift 2 ;;
    --simular) SIMULAR=1; shift ;;
    --forcar) FORCAR=1; shift ;;
    -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; rm -f "$0"; exit 0 ;;
    *) echo "opção desconhecida: $1 (veja --help)" >&2; exit 1 ;;
  esac
done

dizer() { echo "$*"; }
registrar() { [ "$SIMULAR" -eq 1 ] || echo "$(date '+%F %T') [$(whoami)] $*" >> "$LOG"; }
parar() { dizer "ABORTADO: $*"; registrar "ABORTADO: $*"; exit 1; }

# Uma atualização por vez.
TRAVA=$RAIZ/deploy/.atualizando.lock
mkdir "$TRAVA" 2>/dev/null || parar "há outra atualização em andamento (se travou, apague a pasta $TRAVA)"
trap 'rmdir "$TRAVA" 2>/dev/null; rm -f "$0"' EXIT   # apaga também a cópia temporária deste script

# ---------- 1. o servidor está em condição? ----------
[ -f "$RAIZ/deploy/.env" ] || parar "não existe deploy/.env (primeira instalação: veja deploy/README.md)"
set -a; . "$RAIZ/deploy/.env"; set +a
[ -z "$(git status --porcelain --untracked-files=no)" ] || parar "há alterações locais no código deste servidor. Desfaça-as (git status) antes de atualizar"
"${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx postgres \
  || parar "a pilha não está no ar (primeira instalação? use: docker compose -f deploy/compose.prod.yml up -d --build)"
USO=$(df --output=pcent "$RAIZ" 2>/dev/null | tail -1 | tr -dc '0-9')
[ "${USO:-0}" -lt 90 ] || parar "disco com ${USO}% de uso: libere espaço antes de construir imagens novas"

case "${SITE_ADDRESS:-localhost}" in
  :80) URL=http://localhost ;;
  localhost) URL=https://localhost ;;
  *) URL=https://$SITE_ADDRESS ;;
esac

# ---------- versões ----------
ANTES=$(git rev-parse HEAD)
RAMO=$(git symbolic-ref --short -q HEAD || true)
if [ -n "$VERSAO" ]; then
  git fetch -q --tags origin 2>/dev/null || dizer "(aviso: não consegui falar com o GitHub; uso só o que já está nesta máquina)"
  ALVO=$(git rev-parse --verify -q "$VERSAO^{commit}" || git rev-parse --verify -q "origin/$VERSAO^{commit}" || true)
  [ -n "$ALVO" ] || parar "não encontro a versão '$VERSAO' (branch, tag ou commit)"
else
  [ -n "$RAMO" ] || parar "o código está num commit solto (sem branch). Informe a versão: deploy/atualizar.sh --versao REF"
  git fetch -q origin "$RAMO" 2>/dev/null || parar "não consegui buscar '$RAMO' no GitHub"
  ALVO=$(git rev-parse "origin/$RAMO")
  git merge-base --is-ancestor "$ANTES" "$ALVO" || parar "'$RAMO' local e do GitHub divergiram (não é avanço direto). Resolva no repositório antes"
fi

dizer "Versão em produção: $(git log -1 --format='%h %s' "$ANTES")"
dizer "Versão alvo:        $(git log -1 --format='%h %s' "$ALVO")"
if [ "$ANTES" = "$ALVO" ] && [ "$FORCAR" -eq 0 ]; then
  dizer "Já está nessa versão. Nada a fazer (use --forcar para reconstruir mesmo assim)."
  exit 0
fi
if [ "$ANTES" != "$ALVO" ]; then
  dizer "O que muda:"
  git log --oneline --no-decorate "$ANTES..$ALVO" 2>/dev/null | head -20 | sed 's/^/  + /'
  git merge-base --is-ancestor "$ALVO" "$ANTES" && dizer "  (é uma versão MAIS ANTIGA: isto é um retrocesso)"
fi
if [ "$SIMULAR" -eq 1 ]; then
  dizer
  dizer "SIMULAÇÃO: nada foi alterado. Sem --simular eu faria: backup, construir, subir, esperar a saúde e (se falhar) voltar."
  exit 0
fi
registrar "INÍCIO de ${ANTES:0:7} para ${ALVO:0:7}"

# ---------- funções ----------
voltar_codigo() { # devolve o código ao estado de antes
  if [ -n "$RAMO" ]; then git checkout -q "$RAMO" && git reset -q --hard "$ANTES"; else git checkout -q --detach "$ANTES"; fi
}
saudavel() { # a API (pelo Caddy), a tela e o Pagamentos respondem?
  curl -fsk -m 5 "$URL/api/saude" 2>/dev/null | grep -q '"status":"ok"' || return 1
  curl -fsk -m 5 "$URL/" > /dev/null || return 1   # redirecionamento do bash (o "curl -o /dev/null" falha no Git Bash do Windows)
  local id; id=$("${COMPOSE[@]}" ps -q pagamentos 2>/dev/null | head -1)
  [ -n "$id" ] && [ "$(docker inspect --format '{{.State.Health.Status}}' "$id" 2>/dev/null)" = "healthy" ]
}
esperar_saude() {
  local limite=$((SECONDS + ESPERA_SEGUNDOS))
  while [ "$SECONDS" -lt "$limite" ]; do saudavel && return 0; sleep 5; done
  return 1
}

# ---------- 2. backup novo ----------
dizer
dizer "1/5 backup antes de mexer em qualquer coisa..."
"${COMPOSE[@]}" exec -T backup backup.sh > /dev/null 2>&1 || parar "o backup falhou (veja: docker compose -f deploy/compose.prod.yml logs backup). Sem backup novo eu não atualizo"
BACKUP=$("${COMPOSE[@]}" exec -T backup sh -c 'ls -1t /backups | grep "\.dump\.age$" | head -1' | tr -d '\r')
dizer "    backup feito: $BACKUP"
registrar "backup de segurança: $BACKUP"

# ---------- 3. guarda as imagens de agora e atualiza o código ----------
dizer "2/5 guardando as imagens que estão rodando e trazendo o código novo..."
for s in "${SERVICOS_COM_IMAGEM[@]}"; do
  docker image inspect "loja-cafe-prod-$s:latest" > /dev/null 2>&1 && docker tag "loja-cafe-prod-$s:latest" "loja-cafe-prod-$s:anterior"
done
if [ -n "$VERSAO" ]; then git checkout -q --detach "$ALVO" || parar "não consegui ir para $VERSAO"
else git merge -q --ff-only "$ALVO" || parar "não consegui trazer a versão nova"; fi

# ---------- 4. constrói SEM tocar nos contêineres ----------
dizer "3/5 construindo as imagens novas (a versão antiga continua no ar)..."
if ! "${COMPOSE[@]}" build > "$RAIZ/deploy/ultima-construcao.log" 2>&1; then
  voltar_codigo
  registrar "FALHA na construção; código devolvido a ${ANTES:0:7}; nada mudou no ar"
  dizer "FALHA ao construir as imagens (log: deploy/ultima-construcao.log). O código voltou ao que era e a versão antiga continua no ar."
  exit 1
fi

# ---------- 5. sobe e espera ----------
dizer "4/5 subindo a versão nova..."
"${COMPOSE[@]}" up -d --remove-orphans > /dev/null 2>&1
dizer "5/5 esperando a API, o Pagamentos e o site ficarem saudáveis (até ${ESPERA_SEGUNDOS}s)..."
if esperar_saude; then
  docker image prune -f > /dev/null 2>&1 || true
  registrar "SUCESSO: ${ANTES:0:7} -> ${ALVO:0:7} (backup de segurança $BACKUP)"
  dizer
  dizer "ATUALIZADO para $(git log -1 --format='%h %s' "$ALVO")."
  dizer "Se algo estiver estranho, a versão anterior volta com: deploy/atualizar.sh --versao ${ANTES:0:7}"
  dizer "O banco de antes da atualização está no backup $BACKUP (veja deploy/OPERACAO.md)."
  exit 0
fi

# ---------- 6. não ficou saudável: volta sozinho ----------
dizer
dizer "A VERSÃO NOVA NÃO FICOU SAUDÁVEL em ${ESPERA_SEGUNDOS}s. Voltando para a anterior..."
"${COMPOSE[@]}" logs --tail=40 pedidos-api > "$RAIZ/deploy/ultima-falha.log" 2>&1 || true
for s in "${SERVICOS_COM_IMAGEM[@]}"; do
  docker image inspect "loja-cafe-prod-$s:anterior" > /dev/null 2>&1 && docker tag "loja-cafe-prod-$s:anterior" "loja-cafe-prod-$s:latest"
done
voltar_codigo
"${COMPOSE[@]}" up -d --no-build --remove-orphans > /dev/null 2>&1
if esperar_saude; then
  registrar "ROLLBACK OK: ${ALVO:0:7} falhou; voltou para ${ANTES:0:7}"
  dizer "VOLTOU para a versão anterior ($(git log -1 --format='%h %s' "$ANTES")) e está saudável."
  dizer "Motivo da falha (últimas linhas da API) em deploy/ultima-falha.log."
  dizer "ATENÇÃO: as migrações do banco da versão nova, se chegaram a rodar, NÃO são desfeitas (o Flyway só avança)."
  exit 1
fi
registrar "ROLLBACK FALHOU: ${ALVO:0:7} -> ${ANTES:0:7}; backup de segurança $BACKUP"
dizer "SITUAÇÃO GRAVE: a versão anterior TAMBÉM não ficou saudável."
dizer "O banco pode ter sido alterado pela versão nova. Restaure o backup feito antes da atualização:"
dizer "  deploy/restaurar.sh $BACKUP CHAVE-PRIVADA --substituir"
dizer "Veja deploy/OPERACAO.md ('A API não sobe') e deploy/ultima-falha.log."
exit 2
