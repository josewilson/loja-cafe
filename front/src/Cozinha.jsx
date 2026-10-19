import { useEffect, useState } from 'react'
import { cancelarPedido, listarCozinha, listarOpcoes, mudarPreparo } from './api'
import Topo from './Topo'

const COLUNAS = [
  { preparo: 'NA_FILA', titulo: 'Na fila' },
  { preparo: 'PREPARANDO', titulo: 'Preparando' },
  { preparo: 'PRONTO', titulo: 'Pronto para retirar' },
]

// O passo seguinte de cada coluna e quem pode dá-lo (o servidor confere de novo).
const PASSO = {
  NA_FILA: { novo: 'PREPARANDO', texto: 'Iniciar preparo', papeis: ['COZINHA', 'GERENTE'] },
  PREPARANDO: { novo: 'PRONTO', texto: 'Marcar pronto', papeis: ['COZINHA', 'GERENTE'] },
  PRONTO: { novo: 'RETIRADO', texto: 'Entregue ao cliente', papeis: ['ATENDENTE', 'GERENTE'] },
}

const minutos = (desde) => Math.max(0, Math.floor((Date.now() - new Date(desde).getTime()) / 60000))

// Fila de preparo da loja: pedidos PAGO ainda não retirados, atualizada a cada 2 s.
export default function Cozinha({ sessao, aoSair, topo }) {
  const [fila, setFila] = useState([])
  const [opcoes, setOpcoes] = useState({ tamanhos: [], extras: [] })
  const [offline, setOffline] = useState(false)
  const [erro, setErro] = useState(null)
  const [ocupado, setOcupado] = useState(null) // id do pedido com ação em andamento
  const [, relogio] = useState(0) // força redesenhar o "há X min" a cada ciclo
  const { papel } = sessao
  const ehGerente = papel === 'GERENTE'

  useEffect(() => {
    listarOpcoes().then(setOpcoes).catch(() => {})
  }, [])

  const carregar = async () => {
    const lista = await listarCozinha()
    setFila(lista)
    setOffline(false)
    relogio((n) => n + 1)
  }

  useEffect(() => {
    let ativo = true
    let timer
    const ciclo = async () => {
      try {
        const lista = await listarCozinha()
        if (!ativo) return
        setFila(lista)
        setOffline(false)
        relogio((n) => n + 1)
      } catch (falha) {
        if (!ativo) return
        if (falha.status === 401) return aoSair() // sessão vencida: volta para o login
        setOffline(true)
      }
      if (ativo) timer = setTimeout(ciclo, 2000)
    }
    ciclo()
    return () => {
      ativo = false
      clearTimeout(timer)
    }
  }, [aoSair])

  async function agir(pedido, acao) {
    setOcupado(pedido.id)
    setErro(null)
    try {
      await acao()
      await carregar()
    } catch (falha) {
      if (falha.status === 401) return aoSair()
      setErro(falha)
      await carregar().catch(() => {}) // outro funcionário pode ter mexido: mostra o estado atual
    } finally {
      setOcupado(null)
    }
  }

  const nomeTamanho = (codigo) => opcoes.tamanhos.find((t) => t.codigo === codigo)?.nome ?? codigo
  const nomeExtra = (codigo) => opcoes.extras.find((x) => x.codigo === codigo)?.nome ?? codigo

  return (
    <>
      <Topo {...topo} offline={offline} />
      <main>
        {erro && (
          <div className="faixa-erro" role="alert">
            <strong>{erro.tipo === 'http' ? `Erro ${erro.status}` : 'Sem conexão'}</strong>
            <ul>{erro.mensagens.map((m) => <li key={m}>{m}</li>)}</ul>
          </div>
        )}

        <div className="quadro">
          {COLUNAS.map(({ preparo, titulo }) => {
            const pedidos = fila.filter((p) => p.preparo === preparo)
            const passo = PASSO[preparo]
            return (
              <section key={preparo} className="cartao coluna-fila" aria-label={titulo}>
                <h2 className="titulo-lista">{titulo} <small>{pedidos.length}</small></h2>
                {pedidos.length === 0 && <p className="vazio">Nada por aqui.</p>}
                <ul className="pedidos">
                  {pedidos.map((p) => (
                    <li key={p.id} className="pedido PAGO">
                      <div className="pedido-topo">
                        <span className="id">#{p.id}</span>
                        <strong>{p.cliente}</strong>
                        <small className="espera">há {minutos(p.criadoEm)} min</small>
                      </div>
                      {p.itens.map((i, n) => (
                        <p key={n} className="pedido-item">
                          {i.quantidade}× {i.item}
                          <span className="tag">{nomeTamanho(i.tamanho)}</span>
                          {i.extras.map((x) => <span key={x} className="tag">{nomeExtra(x)}</span>)}
                        </p>
                      ))}
                      {p.observacao && <p className="obs">“{p.observacao}”</p>}
                      <div className="acoes">
                        {passo.papeis.includes(papel) && (
                          <button
                            type="button"
                            className="botao"
                            disabled={ocupado === p.id}
                            onClick={() => agir(p, () => mudarPreparo(p.id, passo.novo))}
                          >
                            {passo.texto}
                          </button>
                        )}
                        {ehGerente && preparo !== 'PRONTO' && (
                          <button
                            type="button"
                            className="botao perigo"
                            disabled={ocupado === p.id}
                            onClick={() => {
                              if (window.confirm(`Cancelar o pedido #${p.id} de ${p.cliente}? O estoque volta.`)) {
                                agir(p, () => cancelarPedido(p.id))
                              }
                            }}
                          >
                            Cancelar
                          </button>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              </section>
            )
          })}
        </div>
      </main>
    </>
  )
}
