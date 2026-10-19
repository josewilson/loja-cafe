// Endereço da pedidos-api (specs/api.md).
const API = 'http://localhost:8081'
const CHAVE = 'loja-cafe:sessao'

// Erro de rede (API fora do ar) ou HTTP (400, 401, 500...), com mensagens prontas para mostrar na tela.
export class ApiErro extends Error {
  constructor(tipo, status, mensagens) {
    super(mensagens.join(' · '))
    this.tipo = tipo // 'rede' | 'http'
    this.status = status
    this.mensagens = mensagens
  }
}

// Sessão { token, nome } guardada no navegador. localStorage pode estar bloqueado: nunca quebra a tela.
export function sessaoSalva() {
  try {
    return JSON.parse(localStorage.getItem(CHAVE))
  } catch {
    return null
  }
}
function guardarSessao(sessao) {
  try {
    if (sessao) localStorage.setItem(CHAVE, JSON.stringify(sessao))
    else localStorage.removeItem(CHAVE)
  } catch {
    // sem localStorage: a sessão vale só até recarregar a página
  }
}
export const sair = () => guardarSessao(null)

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

async function pedir(caminho, opcoes = {}) {
  const token = sessaoSalva()?.token
  let resposta
  try {
    resposta = await fetch(`${API}${caminho}`, {
      ...opcoes,
      headers: { ...opcoes.headers, ...(token && { Authorization: `Bearer ${token}` }) },
    })
  } catch {
    throw new ApiErro('rede', 0, [`Não consegui falar com a API (${API}). Ela está rodando?`])
  }
  if (!resposta.ok) {
    // Token vencido ou inválido (fora do login): esquece a sessão; a tela volta para o login.
    if (resposta.status === 401 && !caminho.startsWith('/auth')) guardarSessao(null)
    throw new ApiErro('http', resposta.status, await mensagensDoErro(resposta))
  }
  return resposta.json()
}

const enviar = (caminho, corpo) =>
  pedir(caminho, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(corpo) })

const abrirSessao = async (chamada) => {
  const sessao = await chamada
  guardarSessao(sessao)
  return sessao
}
export const entrar = (email, senha) => abrirSessao(enviar('/auth/entrar', { email, senha }))
export const registrar = (nome, email, senha) => abrirSessao(enviar('/auth/registrar', { nome, email, senha }))

export const listarProdutos = () => pedir('/produtos')
export const listarOpcoes = () => pedir('/opcoes')
export const listarPedidos = (limite = 20) => pedir(`/pedidos?limite=${limite}`)
export const criarPedido = (pedido) => enviar('/pedidos', pedido)
