// Endereço da pedidos-api (specs/api.md).
const API = 'http://localhost:8081'

// Erro de rede (API fora do ar) ou HTTP (400, 500...), com mensagens prontas para mostrar na tela.
export class ApiErro extends Error {
  constructor(tipo, status, mensagens) {
    super(mensagens.join(' · '))
    this.tipo = tipo // 'rede' | 'http'
    this.status = status
    this.mensagens = mensagens
  }
}

async function mensagensDoErro(resposta) {
  try {
    const corpo = await resposta.json()
    // Erro de validação: a API lista cada campo que falhou.
    if (corpo.errors?.length) return corpo.errors.map((e) => `${e.field}: ${e.defaultMessage}`)
    if (corpo.message) return [corpo.message]
  } catch {
    // corpo sem JSON: cai na mensagem padrão abaixo
  }
  return ['A API não explicou o erro.']
}

async function pedir(caminho, opcoes) {
  let resposta
  try {
    resposta = await fetch(`${API}${caminho}`, opcoes)
  } catch {
    throw new ApiErro('rede', 0, [`Não consegui falar com a API (${API}). Ela está rodando?`])
  }
  if (!resposta.ok) throw new ApiErro('http', resposta.status, await mensagensDoErro(resposta))
  return resposta.json()
}

export const listarProdutos = () => pedir('/produtos')
export const listarOpcoes = () => pedir('/opcoes')
export const listarPedidos = (limite = 20) => pedir(`/pedidos?limite=${limite}`)

export const criarPedido = (pedido) =>
  pedir('/pedidos', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(pedido),
  })
