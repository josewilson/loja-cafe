#!/usr/bin/env bash
# Restaura um backup do Postgres na produção. Roda na máquina do servidor, na raiz do repositório.
#
#   deploy/restaurar.sh --listar                          lista os backups (neste servidor e fora dele)
#   deploy/restaurar.sh --baixar NOME                     traz um backup de FORA do servidor para cá
#   deploy/restaurar.sh NOME CHAVE-PRIVADA [--para-banco X]
#        Restauração SEGURA: cria um banco NOVO (padrão loja_cafe_restaurado) para você conferir. Não mexe no banco em uso.
#   deploy/restaurar.sh NOME CHAVE-PRIVADA --substituir
#        DESTRUTIVO: para a API, faz um backup de segurança do banco atual, APAGA o banco em uso e o recria com o backup.
#
# NOME = nome do arquivo (ex.: loja_cafe-20261019-033000.dump.age), que está na pasta de backups do servidor, ou um caminho de arquivo.
# CHAVE-PRIVADA = o arquivo da chave privada (gerado por deploy/gerar-chave-backup.sh). Ela nunca fica no servidor: traga-a só para restaurar.
set -euo pipefail
export MSYS_NO_PATHCONV=1

RAIZ=$(cd "$(dirname "$0")/.." && { pwd -W 2>/dev/null || pwd; })   # pwd -W: caminho no formato do Windows (Git Bash); no Linux cai no pwd
caminho() { (cd "$1" && { pwd -W 2>/dev/null || pwd; }); }
COMPOSE=(docker compose -f "$RAIZ/deploy/compose.prod.yml")
SEM_DEPENDENCIAS=(run --rm --no-deps)

uso() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-1}"; }
[ $# -ge 1 ] || uso

if [ "$1" = "--listar" ]; then
  "${COMPOSE[@]}" "${SEM_DEPENDENCIAS[@]}" --entrypoint sh backup -c '
    echo "== neste servidor (/backups)"; ls -1 /backups | grep "\.dump\.age$" || echo "(nenhum)"
    if [ -n "$RCLONE_DEST" ]; then echo "== fora do servidor ($RCLONE_DEST)"; rclone lsf "$RCLONE_DEST" | grep "\.dump\.age$" || echo "(nenhum)"; fi'
  exit 0
fi

if [ "$1" = "--baixar" ]; then
  NOME=${2:?uso: deploy/restaurar.sh --baixar NOME}
  "${COMPOSE[@]}" "${SEM_DEPENDENCIAS[@]}" --entrypoint sh backup -c '
    [ -n "$RCLONE_DEST" ] || { echo "RCLONE_DEST não está configurado" >&2; exit 1; }
    rclone copyto "$RCLONE_DEST/$1" "/backups/$1" && rclone copyto "$RCLONE_DEST/$1.sha256" "/backups/$1.sha256" && echo "baixado para /backups/$1"' _ "$NOME"
  exit 0
fi

ARQUIVO=${1:?}
CHAVE=${2:?falta o arquivo da chave privada}
shift 2
BANCO=loja_cafe_restaurado
SUBSTITUIR=0
while [ $# -gt 0 ]; do
  case "$1" in
    --para-banco) BANCO=${2:?falta o nome do banco}; shift 2 ;;
    --substituir) SUBSTITUIR=1; shift ;;
    *) uso ;;
  esac
done
[ -f "$CHAVE" ] || { echo "não encontro a chave privada: $CHAVE" >&2; exit 1; }
CHAVE_ABS=$(caminho "$(dirname "$CHAVE")")/$(basename "$CHAVE")

MONTAGENS=(-v "$CHAVE_ABS":/chave.txt:ro)
if [ -f "$ARQUIVO" ]; then # caminho de um arquivo desta máquina: monta a pasta dele (para pegar o .sha256 junto)
  PASTA=$(caminho "$(dirname "$ARQUIVO")")
  MONTAGENS+=(-v "$PASTA":/entrada:ro)
  DENTRO=/entrada/$(basename "$ARQUIVO")
else
  DENTRO=/backups/$ARQUIVO # um backup que já está na pasta de backups do servidor
fi

# --user root: a chave privada tem permissão 0600 (só do dono, na máquina) e o usuário do contêiner não conseguiria lê-la.
# O contêiner só lê a chave e fala com o banco pela rede; não escreve em nada além de uma pasta temporária.
restaurar() { # restaurar <banco> [--substituir]
  "${COMPOSE[@]}" "${SEM_DEPENDENCIAS[@]}" --user root "${MONTAGENS[@]}" --entrypoint restaurar.sh backup "$DENTRO" /chave.txt "$@"
}

if [ "$SUBSTITUIR" -eq 0 ]; then
  echo "Restauração SEGURA para o banco novo '$BANCO' (o banco em uso não é tocado)."
  restaurar "$BANCO"
  echo
  echo "Para conferir: docker compose -f deploy/compose.prod.yml exec postgres psql -U \$POSTGRES_USER -d $BANCO"
  echo "Para apagar este banco depois: ... psql -U \$POSTGRES_USER -d postgres -c 'drop database $BANCO'"
  exit 0
fi

echo "ATENÇÃO: isto vai PARAR a API e APAGAR o banco loja_cafe em uso, trocando-o pelo conteúdo de $ARQUIVO."
echo "Antes, será feito um backup de segurança do banco atual."
read -r -p "Digite RESTAURAR para continuar: " CONFIRMA
[ "$CONFIRMA" = "RESTAURAR" ] || { echo "cancelado."; exit 1; }

echo "1/4 backup de segurança do banco atual..."
"${COMPOSE[@]}" exec -T backup backup.sh
echo "2/4 parando a API e o Pagamentos..."
"${COMPOSE[@]}" stop pedidos-api pagamentos
echo "3/4 restaurando..."
restaurar loja_cafe --substituir
echo "4/4 subindo a API e o Pagamentos de novo (o Flyway confere/atualiza o esquema)..."
"${COMPOSE[@]}" start pedidos-api pagamentos
echo
echo "Pronto. Confira: curl -s https://SEU-ENDERECO/api/saude"
echo "Pedidos que estavam 'aguardando pagamento' no momento do backup podem nunca ser resolvidos (o Kafka não entra no backup)."
echo "Como ADMIN, reenvie-os ao Pagamentos:  POST /api/pedidos/reenfileirar?minutos=0"
