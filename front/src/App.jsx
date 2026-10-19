import { useEffect, useState } from 'react'
import { criarPedido, listarOpcoes, listarPedidos, listarProdutos } from './api'

const reais = (n) => Number(n).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })

const ROTULO = {
  AGUARDANDO_PAGAMENTO: 'Aguardando pagamento',
  PAGO: 'Pago',
  RECUSADO: 'Recusado',
}

// Lembra o nome do cliente entre visitas. localStorage pode estar bloqueado: nunca quebra a tela.
const nomeSalvo = () => {
  try {
    return localStorage.getItem('loja-cafe:cliente') ?? ''
  } catch {
    return ''
  }
}
const salvarNome = (nome) => {
  try {
    localStorage.setItem('loja-cafe:cliente', nome)
  } catch {
    // sem localStorage: segue sem lembrar
  }
}

export default function App() {
  const [produtos, setProdutos] = useState([])
  const [opcoes, setOpcoes] = useState({ tamanhos: [], extras: [] })
  const [catalogoErro, setCatalogoErro] = useState(null)
  const [tentativa, setTentativa] = useState(0)

  const [cliente, setCliente] = useState(nomeSalvo)
  const [produtoId, setProdutoId] = useState(null)
  const [tamanho, setTamanho] = useState('MEDIO')
  const [extras, setExtras] = useState([])
  const [quantidade, setQuantidade] = useState(1)
  const [observacao, setObservacao] = useState('')

  const [enviando, setEnviando] = useState(false)
  const [tempo, setTempo] = useState(null)
  const [erro, setErro] = useState(null)
  const [recebido, setRecebido] = useState(null)
  const [pedidos, setPedidos] = useState([])
  const [offline, setOffline] = useState(false)

  // Cardápio e opções vêm da API: o preço que vale é o dela.
  useEffect(() => {
    let ativo = true
    Promise.all([listarProdutos(), listarOpcoes()])
      .then(([listaProdutos, listaOpcoes]) => {
        if (!ativo) return
        setProdutos(listaProdutos)
        setOpcoes(listaOpcoes)
        setProdutoId((atual) => atual ?? listaProdutos[0]?.id ?? null)
        setCatalogoErro(null)
      })
      .catch((falha) => ativo && setCatalogoErro(falha))
    return () => {
      ativo = false
    }
  }, [tentativa])

  // Pergunta à API a cada 2 s (um pedido por vez, sem empilhar) para o status mudar sozinho.
  useEffect(() => {
    let ativo = true
    let timer
    const ciclo = async () => {
      try {
        const lista = await listarPedidos(20)
        if (!ativo) return
        setPedidos(lista)
        setOffline(false)
        setErro((atual) => (atual?.tipo === 'rede' ? null : atual)) // API voltou: some o aviso de rede
      } catch {
        if (ativo) setOffline(true)
      }
      if (ativo) timer = setTimeout(ciclo, 2000)
    }
    ciclo()
    return () => {
      ativo = false
      clearTimeout(timer)
    }
  }, [])

  const produto = produtos.find((p) => p.id === produtoId)
  const ajuste = opcoes.tamanhos.find((t) => t.codigo === tamanho)?.ajuste ?? 0
  const extrasEscolhidos = opcoes.extras.filter((e) => extras.includes(e.codigo))
  const unitario = produto ? produto.preco + ajuste + extrasEscolhidos.reduce((soma, e) => soma + e.preco, 0) : 0
  const total = unitario * quantidade
  const aguardando = pedidos.filter((p) => p.status === 'AGUARDANDO_PAGAMENTO').length

  const alternarExtra = (codigo) =>
    setExtras((atuais) => (atuais.includes(codigo) ? atuais.filter((c) => c !== codigo) : [...atuais, codigo]))
  const mudarQuantidade = (n) => setQuantidade(Math.min(50, Math.max(1, n || 1)))

  async function enviar(e) {
    e.preventDefault()
    setEnviando(true)
    setErro(null)
    setRecebido(null)
    const inicio = performance.now()
    try {
      const pedido = await criarPedido({
        cliente: cliente.trim(),
        produtoId,
        quantidade,
        tamanho,
        extras,
        observacao: observacao.trim() || undefined,
      })
      salvarNome(cliente.trim())
      setRecebido(pedido)
      setObservacao('')
      setPedidos(await listarPedidos(20))
    } catch (falha) {
      setErro(falha)
    } finally {
      // O cronômetro mostra quanto o cliente esperou pela resposta.
      setTempo(Math.round(performance.now() - inicio))
      setEnviando(false)
    }
  }

  return (
    <>
      <header className="topo">
        <div className="marca">
          <span className="logo" aria-hidden="true">☕</span>
          <div>
            <h1>Loja Café</h1>
            <p>Feito na hora. Pago em segundos.</p>
          </div>
        </div>
        <span className={offline ? 'conexao fora' : 'conexao'} role="status">
          <i aria-hidden="true" /> {offline ? 'API fora do ar' : 'API conectada'}
        </span>
      </header>

      <main>
        {catalogoErro && (
          <div className="faixa-erro" role="alert">
            <strong>Não consegui carregar o cardápio.</strong> {catalogoErro.message}
            <button type="button" onClick={() => setTentativa((n) => n + 1)}>Tentar de novo</button>
          </div>
        )}

        <form className="layout" onSubmit={enviar}>
          <div className="coluna">
            <section className="cartao">
              <h2><span className="passo">1</span> Quem está pedindo?</h2>
              <label className="campo">
                <span>Seu nome</span>
                <input
                  value={cliente}
                  onChange={(e) => setCliente(e.target.value)}
                  required
                  maxLength={60}
                  autoComplete="given-name"
                  placeholder="Ex.: Ana"
                />
              </label>
            </section>

            <section className="cartao">
              <h2><span className="passo">2</span> Escolha sua pedida</h2>
              {produtos.length === 0 && !catalogoErro && (
                <div className="grade-produtos" aria-hidden="true">
                  {[1, 2, 3, 4].map((n) => <div key={n} className="esqueleto" />)}
                </div>
              )}
              <div className="grade-produtos" role="radiogroup" aria-label="Cardápio">
                {produtos.map((p) => (
                  <label key={p.id} className="produto">
                    <input
                      type="radio"
                      name="produto"
                      className="so-leitor"
                      checked={p.id === produtoId}
                      onChange={() => setProdutoId(p.id)}
                    />
                    <span className="emoji" aria-hidden="true">{p.emoji}</span>
                    <strong>{p.nome}</strong>
                    <small>{p.descricao}</small>
                    <span className="preco">{reais(p.preco)}</span>
                  </label>
                ))}
              </div>
            </section>

            <section className="cartao">
              <h2><span className="passo">3</span> Do seu jeito</h2>

              <fieldset className="grupo">
                <legend>Tamanho</legend>
                <div className="segmentos">
                  {opcoes.tamanhos.map((t) => (
                    <label key={t.codigo} className="segmento">
                      <input
                        type="radio"
                        name="tamanho"
                        className="so-leitor"
                        checked={t.codigo === tamanho}
                        onChange={() => setTamanho(t.codigo)}
                      />
                      <span>{t.nome}</span>
                      <small>{t.ajuste === 0 ? 'preço do cardápio' : `${t.ajuste > 0 ? '+' : '−'} ${reais(Math.abs(t.ajuste))}`}</small>
                    </label>
                  ))}
                </div>
              </fieldset>

              <fieldset className="grupo">
                <legend>Extras <small>(opcional)</small></legend>
                <div className="chips">
                  {opcoes.extras.map((x) => (
                    <label key={x.codigo} className="chip">
                      <input
                        type="checkbox"
                        className="so-leitor"
                        checked={extras.includes(x.codigo)}
                        onChange={() => alternarExtra(x.codigo)}
                      />
                      <span>{x.nome} <small>+ {reais(x.preco)}</small></span>
                    </label>
                  ))}
                </div>
              </fieldset>

              <div className="linha">
                <div className="grupo">
                  <span className="rotulo" id="rotulo-qtd">Quantidade</span>
                  <div className="stepper" role="group" aria-labelledby="rotulo-qtd">
                    <button type="button" aria-label="Diminuir quantidade" onClick={() => mudarQuantidade(quantidade - 1)} disabled={quantidade <= 1}>−</button>
                    <input
                      type="number"
                      min="1"
                      max="50"
                      value={quantidade}
                      aria-label="Quantidade (1 a 50)"
                      onChange={(e) => mudarQuantidade(Number(e.target.value))}
                    />
                    <button type="button" aria-label="Aumentar quantidade" onClick={() => mudarQuantidade(quantidade + 1)} disabled={quantidade >= 50}>+</button>
                  </div>
                </div>

                <label className="campo largo">
                  <span>Observação <small>(opcional)</small></span>
                  <textarea
                    value={observacao}
                    onChange={(e) => setObservacao(e.target.value)}
                    maxLength={140}
                    rows={2}
                    placeholder="Ex.: sem açúcar, bem quente…"
                  />
                  <small className="contador">{observacao.length}/140</small>
                </label>
              </div>
            </section>
          </div>

          <aside className="resumo cartao" aria-label="Resumo do pedido">
            <h2>Seu pedido</h2>
            {produto ? (
              <>
                <div className="resumo-item">
                  <span className="emoji grande" aria-hidden="true">{produto.emoji}</span>
                  <div>
                    <strong>{quantidade}× {produto.nome}</strong>
                    <small>
                      {opcoes.tamanhos.find((t) => t.codigo === tamanho)?.nome}
                      {extrasEscolhidos.length > 0 && ` · ${extrasEscolhidos.map((x) => x.nome).join(', ')}`}
                    </small>
                    {observacao.trim() && <small className="obs">“{observacao.trim()}”</small>}
                  </div>
                </div>
                <dl className="contas">
                  <div><dt>Preço unitário</dt><dd>{reais(unitario)}</dd></div>
                  <div className="total" aria-live="polite"><dt>Total</dt><dd>{reais(total)}</dd></div>
                </dl>
              </>
            ) : (
              <p className="vazio">Escolha um item do cardápio.</p>
            )}

            <button type="submit" className="botao" disabled={enviando || !produto}>
              {enviando ? 'Enviando…' : 'Fazer pedido'}
            </button>
            <p className="nota">O valor final é calculado pela API.</p>

            {tempo !== null && (
              <p className="tempo">A API respondeu em <strong>{tempo} ms</strong></p>
            )}
            {recebido && (
              <p className="sucesso" role="status">
                ✓ Pedido #{recebido.id} recebido! Valor confirmado: <strong>{reais(recebido.valor)}</strong>
              </p>
            )}
            {erro && (
              <div className="faixa-erro" role="alert">
                <strong>{erro.tipo === 'http' ? `Erro ${erro.status}` : 'Sem conexão'}</strong>
                <ul>{erro.mensagens.map((m) => <li key={m}>{m}</li>)}</ul>
              </div>
            )}
          </aside>
        </form>

        <section className="cartao" aria-labelledby="titulo-pedidos">
          <h2 id="titulo-pedidos" className="titulo-lista">
            Acompanhe seus pedidos
            <small>{aguardando > 0 ? `${aguardando} aguardando pagamento` : 'atualiza sozinho a cada 2 s'}</small>
          </h2>
          {pedidos.length === 0 && <p className="vazio">Nenhum pedido ainda. Faça o primeiro acima. ☕</p>}
          <ul className="pedidos" aria-live="polite">
            {pedidos.map((p) => (
              <li key={p.id} className={`pedido ${p.status}`}>
                <div className="pedido-topo">
                  <span className="id">#{p.id}</span>
                  <strong>{p.cliente}</strong>
                  <span className="valor">{reais(p.valor)}</span>
                  <span className={`status ${p.status}`}>{ROTULO[p.status] ?? p.status}</span>
                </div>
                <p className="pedido-item">
                  {p.quantidade}× {p.item}
                  <span className="tag">{opcoes.tamanhos.find((t) => t.codigo === p.tamanho)?.nome ?? p.tamanho}</span>
                  {p.extras?.map((x) => (
                    <span key={x} className="tag">{opcoes.extras.find((o) => o.codigo === x)?.nome ?? x}</span>
                  ))}
                </p>
                {p.observacao && <p className="obs">“{p.observacao}”</p>}
                {p.motivo && <p className="motivo">Motivo: {p.motivo}</p>}
                <div className="trilha" aria-hidden="true">
                  <span className="etapa feita">Recebido</span>
                  <span className={p.status === 'AGUARDANDO_PAGAMENTO' ? 'etapa atual' : 'etapa feita'}>Pagamento</span>
                  <span className={p.status === 'AGUARDANDO_PAGAMENTO' ? 'etapa' : `etapa fim ${p.status}`}>
                    {p.status === 'RECUSADO' ? 'Recusado' : 'Concluído'}
                  </span>
                  {p.processadoPor && <span className="caixa">{p.processadoPor}</span>}
                </div>
              </li>
            ))}
          </ul>
        </section>
      </main>
    </>
  )
}
