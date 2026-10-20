#!/usr/bin/env node
// Confere se o CÓDIGO e as SPECS (specs/) dizem a mesma coisa. Regra do AGENTS.md: "não invente endpoint, campo, tópico ou status
// que não esteja nas specs". Este script falha (código 1) quando algo existe só de um lado. Roda no CI e à mão: node scripts/conferir-specs.js
//   1. rotas do Spring  <->  specs/api.md (nos dois sentidos)
//   2. enums (papéis, status, preparo, formas de pagamento, tamanhos, extras, unidades)  ->  citados em specs/api.md
//   3. evento pedidos-criados (record Java)  <->  exemplo de specs/eventos.md
//   4. evento pagamentos-resultados (dicionário do Python)  <->  exemplo de specs/eventos.md
//   5. tópicos do Kafka (KafkaTopicos.java)  <->  specs/eventos.md
const fs = require("fs");
const path = require("path");

const RAIZ = path.resolve(__dirname, "..");
const ler = (p) => fs.readFileSync(path.join(RAIZ, p), "utf8").replace(/\r/g, "");
const JAVA = "pedidos-api/src/main/java/br/com/lojacafe/pedidos/";
const problemas = [];
const erro = (m) => problemas.push(m);

const api = ler("specs/api.md");
const eventos = ler("specs/eventos.md");

// ---------- 1. rotas ----------
const norm = (r) => r.replace(/\{[^}]+\}/g, "{x}").replace(/\?.*$/, "").replace(/\/$/, "") || "/";
const rotasDoCodigo = new Set();
for (const arquivo of fs.readdirSync(path.join(RAIZ, JAVA)).filter((f) => f.endsWith("Controller.java"))) {
  const fonte = ler(JAVA + arquivo);
  const base = (fonte.match(/@RequestMapping\("([^"]*)"\)/) || [])[1] || "";
  for (const m of fonte.matchAll(/@(Get|Post|Put|Delete)Mapping(?:\("([^"]*)"\))?/g)) {
    rotasDoCodigo.add(m[1].toUpperCase() + " " + norm(base + (m[2] || "")));
  }
}
// "METHOD /caminho" citado na spec: em títulos (### GET /x · POST /y) e em negrito (**GET /x**). Prosa solta é ignorada.
const rotasDaSpec = new Set();
const linhasDeRota = api.split("\n").filter((l) => /^#{2,3} .*\b(GET|POST|PUT|DELETE) \//.test(l) || /^\*\*(GET|POST|PUT|DELETE) \//.test(l));
for (const linha of linhasDeRota) {
  for (const m of linha.matchAll(/\b(GET|POST|PUT|DELETE) (\/[^\s·(*`]*)/g)) rotasDaSpec.add(m[1] + " " + norm(m[2]));
}
for (const r of rotasDoCodigo) if (!rotasDaSpec.has(r)) erro(`rota no código que NÃO está em specs/api.md: ${r}`);
for (const r of rotasDaSpec) if (!rotasDoCodigo.has(r)) erro(`rota em specs/api.md que NÃO existe no código: ${r}`);

// ---------- 2. enums citados na spec ----------
function valoresDoEnum(arquivo) {
  const fonte = ler(JAVA + arquivo);
  const corpo = fonte.slice(fonte.indexOf("{") + 1);
  const lista = corpo.split(/;|\n\s*\n/)[0]; // até o primeiro ";" (começo dos campos) ou linha em branco
  return [...lista.matchAll(/^\s*([A-Z][A-Z_]+)\b/gm)].map((m) => m[1]);
}
for (const arquivo of ["Papel", "StatusPedido", "Preparo", "FormaPagamento", "Tamanho", "Extra", "UnidadeInsumo"]) {
  const nome = arquivo + ".java";
  for (const valor of valoresDoEnum(nome)) {
    if (!new RegExp("`" + valor + "`").test(api)) erro(`${arquivo}.${valor} existe no código mas não aparece (entre crases) em specs/api.md`);
  }
}

// ---------- 3 e 4. eventos ----------
function chavesDoExemplo(texto, titulo) {
  const i = texto.indexOf(titulo);
  if (i < 0) return null;
  const bloco = texto.slice(i).match(/```json\n([\s\S]*?)```/);
  if (!bloco) return null;
  try { return Object.keys(JSON.parse(bloco[1])).sort(); } catch { return null; }
}
const record = ler(JAVA + "PedidoEventos.java").match(/record PedidoCriado\(([\s\S]*?)\) \{\}/);
const doRecord = record ? [...record[1].matchAll(/\b\w+(?:<[^>]*>)? (\w+)(?:,|$)/g)].map((m) => m[1]).sort() : null;
const doExemplo = chavesDoExemplo(eventos, "## Tópico `pedidos-criados`");
if (!doRecord || !doExemplo) erro("não consegui ler o evento pedidos-criados (record Java ou exemplo da spec)");
else if (doRecord.join() !== doExemplo.join()) erro(`pedidos-criados: o código publica [${doRecord}] e specs/eventos.md mostra [${doExemplo}]`);

const python = ler("pagamentos-py/app.py");
// O dicionário do resultado é o único "return { ... }" que tem a chave "versao" (o outro é a configuração de segurança do Kafka).
const dict = [...python.matchAll(/return \{\s*\n([\s\S]*?)\n\s*\}/g)].find((m) => m[1].includes('"versao"'));
const doPython = dict ? [...dict[1].matchAll(/"(\w+)":/g)].map((m) => m[1]).sort() : null;
const resultadoSpec = chavesDoExemplo(eventos, "## Tópico `pagamentos-resultados`");
if (!doPython || !resultadoSpec) erro("não consegui ler o evento pagamentos-resultados (app.py ou exemplo da spec)");
else if (doPython.join() !== resultadoSpec.join()) erro(`pagamentos-resultados: o código publica [${doPython}] e specs/eventos.md mostra [${resultadoSpec}]`);

// ---------- 5. tópicos ----------
for (const m of ler(JAVA + "KafkaTopicos.java").matchAll(/String \w+ = "([^"]+)"/g)) {
  if (!eventos.includes("`" + m[1] + "`")) erro(`tópico ${m[1]} existe no código mas não está em specs/eventos.md`);
}

// ---------- resultado ----------
console.log(`rotas no código: ${rotasDoCodigo.size} · rotas na spec: ${rotasDaSpec.size}`);
if (problemas.length) {
  console.error("\nCÓDIGO E SPECS DIVERGEM:\n  - " + problemas.join("\n  - "));
  console.error("\nSe a mudança é intencional, atualize a spec (e, se for um contrato, pergunte antes: regra do AGENTS.md).");
  process.exit(1);
}
console.log("código e specs batem.");
