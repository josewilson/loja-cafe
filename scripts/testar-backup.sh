#!/usr/bin/env bash
# Prova que o backup funciona DE VERDADE, de ponta a ponta, sem tocar no banco real:
# sobe um Postgres descartável com dados, faz o backup (criptografado, com cópia "de fora"), restaura e compara.
# Também testa: chave errada, arquivo corrompido, restauração sobre banco existente, --substituir e a retenção.
# Precisa de Docker. Uso: scripts/testar-backup.sh        Sai com código 1 no primeiro passo que falhar.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash (Windows) não pode reescrever caminhos como /backups

RAIZ=$(cd "$(dirname "$0")/.." && { pwd -W 2>/dev/null || pwd; })   # pwd -W: caminho no formato do Windows, que o Docker entende
S=$RANDOM$RANDOM
REDE=lcbkp-rede-$S; PG=lcbkp-pg-$S; IMG=loja-cafe-backup:teste
V_BKP=lcbkp-bkp-$S; V_FORA=lcbkp-fora-$S; V_CHAVE=lcbkp-chave-$S; V_RET=lcbkp-ret-$S

limpar() {
  docker rm -f "$PG" > /dev/null 2>&1 || true
  docker volume rm "$V_BKP" "$V_FORA" "$V_CHAVE" "$V_RET" > /dev/null 2>&1 || true
  docker network rm "$REDE" > /dev/null 2>&1 || true
}
trap limpar EXIT
falhar() { echo "FALHOU: $*" >&2; exit 1; }
ok() { echo "  ok: $*"; }

# Atalhos: psql no banco descartável; e a imagem de backup com a rede, o banco e os volumes certos.
sql() { docker exec -e PGPASSWORD=teste "$PG" psql -U postgres -d "${2:-loja_cafe}" -Atc "$1"; }
imagem() { # imagem <entrypoint> [args...]  (variáveis de conexão e volumes já montados)
  local entrada=$1; shift
  docker run --rm --network "$REDE" -e PGHOST="$PG" -e PGUSER=postgres -e PGPASSWORD=teste -e PGDATABASE=loja_cafe \
    -v "$V_BKP":/backups -v "$V_FORA":/fora -v "$V_CHAVE":/chaves "${EXTRA[@]}" --entrypoint "$entrada" "$IMG" "$@"
}
EXTRA=()
raiz() { docker run --rm --user root -v "$V_BKP":/backups -v "$V_FORA":/fora -v "$V_CHAVE":/chaves -v "$V_RET":/ret --entrypoint "$1" "$IMG" "${@:2}"; }

echo "1. Imagem de backup e banco descartável com dados"
docker build -q -t "$IMG" "$RAIZ/deploy/backup" > /dev/null || falhar "construir a imagem de backup"
docker network create "$REDE" > /dev/null
docker run -d --name "$PG" --network "$REDE" -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=loja_cafe postgres:16 > /dev/null
for _ in $(seq 1 40); do docker exec "$PG" pg_isready -U postgres -d loja_cafe > /dev/null 2>&1 && break; sleep 1; done
docker exec "$PG" pg_isready -U postgres -d loja_cafe > /dev/null || falhar "o Postgres de teste não subiu"
sleep 2 # o primeiro "pronto" do postgres:16 acontece antes de ele reiniciar após a inicialização
for _ in $(seq 1 20); do sql "select 1" > /dev/null 2>&1 && break; sleep 1; done
sql "create table loja (id bigint primary key, nome text not null);
     create table usuario (id bigint primary key, nome text not null, ativo boolean not null);
     create table pedido (id bigint primary key, loja_id bigint references loja(id), valor numeric(10,2) not null, criado_em timestamptz not null, observacao text);
     insert into loja values (1, 'Matriz'), (2, 'Centro');
     insert into usuario select g, 'Funcionário ' || g, g % 2 = 0 from generate_series(1, 20) g;
     insert into pedido select g, 1 + g % 2, g * 3.5, now() - (g || ' hours')::interval, case when g % 3 = 0 then 'Café ☕ sem açúcar, ação!' else null end from generate_series(1, 500) g;" > /dev/null
ASSINATURA="select (select count(*) from pedido) || '/' || (select count(*) from usuario) || '/' || (select sum(valor) from pedido) || '/' || md5(string_agg(coalesce(observacao, '-') || valor, '|' order by id)) from pedido"
ORIGEM=$(sql "$ASSINATURA")
ok "500 pedidos, 20 usuários, texto com acentos e emoji (assinatura $ORIGEM)"

echo "2. Par de chaves: o servidor só recebe a PÚBLICA"
PUB=$(raiz sh -c 'age-keygen -o /chaves/chave.txt 2>&1 | sed -n "s/^Public key: //p"; chmod 644 /chaves/chave.txt')
raiz sh -c 'age-keygen -o /chaves/errada.txt > /dev/null 2>&1; chmod 644 /chaves/errada.txt'
case "$PUB" in age1*) ;; *) falhar "não consegui gerar a chave pública ($PUB)";; esac
ok "chave pública $PUB"

echo "3. Sem chave pública, o backup se recusa (nunca grava sem criptografia)"
if imagem backup.sh > /dev/null 2>&1; then falhar "o backup deveria falhar sem BACKUP_AGE_RECIPIENT"; fi
[ -z "$(raiz sh -c 'ls /backups')" ] || falhar "nenhum arquivo deveria ter sido criado"
ok "recusado, nada gravado"

echo "4. Backup de verdade (criptografado, com cópia fora do servidor)"
raiz sh -c 'chmod 777 /fora'   # o "armazenamento de fora" do teste é uma pasta: o usuário do backup precisa poder gravar nela
EXTRA=(-e BACKUP_AGE_RECIPIENT="$PUB" -e RCLONE_DEST=/fora -e HEALTHCHECK_URL=http://127.0.0.1:9/inexistente)
SAIDA=$(imagem backup.sh 2>&1) || { echo "$SAIDA" >&2; falhar "o backup falhou (um aviso do Healthchecks fora do ar não pode derrubá-lo)"; }
ARQ=$(raiz sh -c 'ls /backups | grep "\.dump\.age$"')
[ "$(echo "$ARQ" | wc -l)" = "1" ] || falhar "esperava 1 backup local, veio: $ARQ"
[ "$(raiz sh -c 'ls /fora | grep -c "\.dump\.age$"')" = "1" ] || falhar "esperava 1 backup fora do servidor"
[ "$(raiz sh -c "sha256sum /backups/$ARQ | cut -d' ' -f1")" = "$(raiz sh -c "sha256sum /fora/$ARQ | cut -d' ' -f1")" ] || falhar "a cópia de fora é diferente da local"
ok "$ARQ (local e fora do servidor, idênticos)"

echo "5. Está mesmo criptografado: sem texto legível e sem o dump do Postgres"
[ "$(raiz sh -c "head -c 21 /backups/$ARQ")" = "age-encryption.org/v1" ] || falhar "o arquivo não tem o cabeçalho do age"
[ "$(raiz sh -c "grep -c -a 'PGDMP\|Matriz\|Funcion' /backups/$ARQ || true")" = "0" ] || falhar "o arquivo tem conteúdo legível do banco!"
ok "só bytes cifrados"

echo "6. Chave errada e arquivo corrompido não restauram"
if imagem restaurar.sh "/backups/$ARQ" /chaves/errada.txt restaurado_errado > /dev/null 2>&1; then falhar "restaurou com a chave errada!"; fi
[ -z "$(sql "select 1 from pg_database where datname = 'restaurado_errado'" postgres)" ] || falhar "chave errada não pode criar banco"
raiz sh -c "cp /backups/$ARQ /ret/corrompido.dump.age && cp /backups/$ARQ.sha256 /ret/corrompido.dump.age.sha256 && printf 'X' | dd of=/ret/corrompido.dump.age bs=1 seek=100 conv=notrunc 2>/dev/null; chmod 644 /ret/*"
EXTRA=(-v "$V_RET":/ret)
if imagem restaurar.sh /ret/corrompido.dump.age /chaves/chave.txt restaurado_corrompido > /dev/null 2>&1; then falhar "restaurou um arquivo corrompido!"; fi
ok "chave errada: recusada; arquivo corrompido: recusado pela soma de verificação"

echo "7. Restauração (a partir da cópia de FORA do servidor) num banco novo, e conferência dos dados"
EXTRA=()
imagem restaurar.sh "/fora/$ARQ" /chaves/chave.txt restaurado > /dev/null 2>&1 || falhar "a restauração falhou"
RESTAURADO=$(sql "$ASSINATURA" restaurado)
[ "$ORIGEM" = "$RESTAURADO" ] || falhar "os dados restaurados são diferentes! origem=$ORIGEM restaurado=$RESTAURADO"
ok "idêntico ao original (assinatura $RESTAURADO)"

echo "8. Restaurar sobre um banco que já existe exige --substituir"
if imagem restaurar.sh "/fora/$ARQ" /chaves/chave.txt restaurado > /dev/null 2>&1; then falhar "restaurou sobre um banco existente sem --substituir!"; fi
ok "recusado sem --substituir"

echo "9. --substituir: o banco estragado volta a ser o do backup"
sql "delete from pedido where id <= 250; update usuario set nome = 'ESTRAGADO'" > /dev/null
[ "$(sql "$ASSINATURA")" != "$ORIGEM" ] || falhar "o teste não estragou o banco"
imagem restaurar.sh "/fora/$ARQ" /chaves/chave.txt loja_cafe --substituir > /dev/null 2>&1 || falhar "restauração com --substituir falhou"
[ "$(sql "$ASSINATURA")" = "$ORIGEM" ] || falhar "depois de --substituir os dados não voltaram ao original"
ok "banco recriado idêntico ao backup"

echo "10. Retenção: 14 dias + domingos por 8 semanas; o mais novo nunca sai"
raiz sh -c 'rm -f /ret/*; for d in 20261020 20261007 20261006 20261005 20261004 20260928 20260927 20260830 20260823 20260101; do touch "/ret/loja_cafe-$d-033000.dump.age"; done; chmod -R 777 /ret'
EXTRA=(-v "$V_RET":/ret)
SIM=$(imagem sh -c 'HOJE=2026-10-20 retencao.sh /ret --simular')
[ "$(raiz sh -c 'ls /ret | wc -l')" = "10" ] || falhar "a simulação apagou arquivos!"
imagem sh -c 'HOJE=2026-10-20 retencao.sh /ret' > /dev/null
RESTAM=$(raiz sh -c 'ls /ret | sed "s/loja_cafe-//; s/-033000.dump.age//" | tr "\n" " "')
ESPERADO="20260830 20260927 20261004 20261006 20261007 20261020 "
[ "$RESTAM" = "$ESPERADO" ] || falhar "retenção errada. Restaram: $RESTAM (esperado: $ESPERADO)"
raiz sh -c 'rm -f /ret/*; touch /ret/loja_cafe-20250101-033000.dump.age'
imagem sh -c 'HOJE=2026-10-20 retencao.sh /ret' > /dev/null
[ "$(raiz sh -c 'ls /ret | wc -l')" = "1" ] || falhar "o backup mais novo (mesmo antigo) nunca pode ser apagado"
ok "mantidos: hoje, 6 e 13 dias, e os domingos 04/10, 27/09 e 30/08; apagados os demais; o único backup restante é preservado"

echo "TUDO CERTO"
