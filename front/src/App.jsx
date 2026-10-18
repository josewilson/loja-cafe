import { useEffect, useState } from 'react'
import { criarPedido, listarPedidos } from './api'

const CARDAPIO = [
  { item: 'Café coado', preco: 8.5 },
  { item: 'Cappuccino', preco: 12 },
  { item: 'Pão de queijo', preco: 6 },
  { item: 'Bolo de cenoura', preco: 9.5 },
]

const reais = (n) => Number(n).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })

const ROTULO = {
  AGUARDANDO_PAGAMENTO: 'Aguardando pagamento',
  PAGO: 'Pago',
  RECUSADO: 'Recusado',
}

export default function App() {
  const [cliente, setCliente] = useState('')
  const [escolha, setEscolha] = useState(CARDAPIO[0])
  const [quantidade, setQuantidade] = useState(1)
  const [enviando, setEnviando] = useState(false)
  const [tempo, setTempo] = useState(null)
  const [erro, setErro] = useState(null)
  const [pedidos, setPedidos] = useState([])

  // Pergunta à API a cada 2 segundos. Assim o status muda na tela sozinho.
  useEffect(() => {
    const atualizar = () => listarPedidos().then(setPedidos).catch(() => {})
    atualizar()
    const id = setInterval(atualizar, 2000)
    return () => clearInterval(id)
  }, [])

  async function enviar(e) {
    e.preventDefault()
    setEnviando(true)
    setErro(null)
    const inicio = performance.now()
    try {
      await criarPedido({ cliente, item: escolha.item, quantidade, precoUnitario: escolha.preco })
      setPedidos(await listarPedidos())
    } catch (falha) {
      setErro(falha.message === 'Failed to fetch' ? 'Não consegui falar com a API (porta 8081).' : falha.message)
    } finally {
      // O cronômetro mostra quanto o cliente esperou pela resposta.
      setTempo(Math.round(performance.now() - inicio))
      setEnviando(false)
    }
  }

  const aguardando = pedidos.filter((p) => p.status === 'AGUARDANDO_PAGAMENTO').length

  return (
    <main>
      <header>
        <h1>☕ Loja Café</h1>
        <p>Faça um pedido e acompanhe o pagamento.</p>
      </header>

      <form onSubmit={enviar} className="cartao">
        <label>
          Seu nome
          <input value={cliente} onChange={(e) => setCliente(e.target.value)} required placeholder="Ana" />
        </label>

        <fieldset>
          <legend>Cardápio</legend>
          <div className="cardapio">
            {CARDAPIO.map((opcao) => (
              <button
                type="button"
                key={opcao.item}
                className={opcao.item === escolha.item ? 'opcao escolhida' : 'opcao'}
                onClick={() => setEscolha(opcao)}
              >
                <strong>{opcao.item}</strong>
                <span>{reais(opcao.preco)}</span>
              </button>
            ))}
          </div>
        </fieldset>

        <label>
          Quantidade
          <input type="number" min="1" max="50" value={quantidade} onChange={(e) => setQuantidade(Number(e.target.value))} />
        </label>

        <div className="rodape">
          <span>Total: <strong>{reais(escolha.preco * quantidade)}</strong></span>
          <button type="submit" disabled={enviando}>{enviando ? 'Enviando…' : 'Fazer pedido'}</button>
        </div>
        {tempo !== null && <p className="tempo">A API respondeu em <strong>{tempo} ms</strong></p>}
        {erro && <p className="erro">⚠️ {erro}</p>}
      </form>

      <section className="cartao">
        <h2>Pedidos <small>{aguardando} aguardando</small></h2>
        {pedidos.length === 0 && <p className="vazio">Nenhum pedido ainda.</p>}
        <ul className="pedidos">
          {pedidos.map((p) => (
            <li key={p.id}>
              <span className="id">#{p.id}</span>
              <span className="desc">
                <strong>{p.cliente}</strong> · {p.quantidade}× {p.item} · {reais(p.valor)}
                {p.motivo && <em> — {p.motivo}</em>}
              </span>
              {p.processadoPor && <span className="caixa">{p.processadoPor}</span>}
              <span className={`status ${p.status}`}>{ROTULO[p.status] ?? p.status}</span>
            </li>
          ))}
        </ul>
      </section>
    </main>
  )
}
