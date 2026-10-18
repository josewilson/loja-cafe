// Endereço da pedidos-api (specs/api.md).
const API = 'http://localhost:8081'

export async function listarPedidos() {
  const resposta = await fetch(`${API}/pedidos`)
  if (!resposta.ok) throw new Error(`GET /pedidos respondeu ${resposta.status}`)
  return resposta.json()
}

export async function criarPedido(pedido) {
  const resposta = await fetch(`${API}/pedidos`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(pedido),
  })
  if (!resposta.ok) throw new Error(`POST /pedidos respondeu ${resposta.status}`)
  return resposta.json()
}
