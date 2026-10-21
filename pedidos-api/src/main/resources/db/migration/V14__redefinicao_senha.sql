-- Fase 8b: link de uso único para o funcionário definir uma nova senha (o banco guarda só o hash do token).
alter table usuario add column redefinicao_hash varchar(64);
alter table usuario add column redefinicao_expira_em timestamp(6) with time zone;
create unique index usuario_redefinicao_hash on usuario (redefinicao_hash);
