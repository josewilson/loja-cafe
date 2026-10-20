-- Fase 6: dados fiscais, todos opcionais (quem define NCM, CFOP e tributação é o contador; o sistema só guarda).
ALTER TABLE loja ADD COLUMN razao_social varchar(120);
ALTER TABLE loja ADD COLUMN cnpj varchar(14);
ALTER TABLE loja ADD COLUMN inscricao_estadual varchar(20);
ALTER TABLE loja ADD COLUMN endereco varchar(200);
ALTER TABLE produto ADD COLUMN ncm varchar(8);
ALTER TABLE produto ADD COLUMN cfop varchar(4);
