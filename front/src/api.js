// Endereço da pedidos-api (specs/api.md).
const API = 'http://localhost:8081'
const CHAVE = 'loja-cafe:sessao'

// Erro de rede (API fora do ar) ou HTTP (400, 401, 403, 500...), com mensagens prontas para mostrar na tela.
export class ApiErro extends Error {
  constructor(tipo, status, mensagens) {
    super(mensagens.join(' · '))
    this.tipo = tipo // 'rede' | 'http'
    this.status = status
    this.mensagens = mensagens
  }
}

// Sessão { token, nome, papel, lojaId, lojaNome } guardada no navegador. localStorage pode estar bloqueado: nunca quebra a tela.
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
    // Token vencido, inválido ou conta desativada (fora do login): esquece a sessão; a tela volta para o login.
    if (resposta.status === 401 && !caminho.startsWith('/auth')) guardarSessao(null)
    throw new ApiErro('http', resposta.status, await mensagensDoErro(resposta))
  }
  return resposta.status === 204 ? null : resposta.json()
}

const enviar = (caminho, corpo) =>
  pedir(caminho, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(corpo) })

export async function entrar(email, senha) {
  const sessao = await enviar('/auth/entrar', { email, senha })
  guardarSessao(sessao)
  return sessao
}

// Encerra a sessão no servidor (se der) e esquece o token deste navegador.
export async function sair() {
  try {
    await pedir('/auth/sair', { method: 'POST' })
  } catch {
    // servidor fora do ar ou token já vencido: o que importa é esquecer o token aqui
  }
  guardarSessao(null)
}

export const listarProdutos = () => pedir('/produtos')
export const listarOpcoes = () => pedir('/opcoes')
export const listarPedidos = (limite = 20) => pedir(`/pedidos?limite=${limite}`)
export const criarPedido = (pedido) => enviar('/pedidos', pedido)

const alterar = (caminho, corpo) =>
  pedir(caminho, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(corpo) })

export const listarCozinha = () => pedir('/cozinha')
export const mudarPreparo = (id, preparo) => alterar(`/pedidos/${id}/preparo`, { preparo })
export const cancelarPedido = (id, motivo) => alterar(`/pedidos/${id}/cancelar`, motivo ? { motivo } : {})

// Painel do gerente
export const relatorioVendas = (data) => pedir(`/relatorios/vendas${data ? `?data=${data}` : ''}`)
export const caixaResumo = () => pedir('/caixa/resumo')
export const fecharCaixa = (dinheiroContado, observacao) =>
  enviar('/caixa/fechamentos', { dinheiroContado, observacao: observacao || undefined })
export const listarFechamentos = (limite = 20) => pedir(`/caixa/fechamentos?limite=${limite}`)
export const listarUsuarios = () => pedir('/usuarios')
export const criarUsuario = (usuario) => enviar('/usuarios', usuario)
export const definirAtivo = (id, ativo) => alterar(`/usuarios/${id}/ativo`, { ativo })
export const ajustarEstoque = (lojaId, produtoId, quantidade) =>
  alterar(`/lojas/${lojaId}/estoque/${produtoId}`, { quantidade })
