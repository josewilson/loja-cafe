import { useEffect, useState } from 'react'
import { criarPedido, listarOpcoes, listarPedidos, listarProdutos } from './api'

const reais = (n) => Number(n).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })

const ROTULO = {
  AGUARDANDO_PAGAMENTO: 'Aguardando pagamento',
  PAGO: 'Pago',
  RECUSADO: 'Recusado',
}

const PAPEL = { ADMIN: "Administrador", GERENTE: "Gerente", ATENDENTE: "Atendente", COZINHA: "Cozinha" }

export default function Loja({ sessao, aoSair }) {
  const { nome, papel, lojaNome } = sessao
  // Só atendente e gerente lançam pedidos (e só eles têm estoque de uma loja para mostrar).
  const podePedir = papel === "ATENDENTE" || papel === "GERENTE"

  const [produtos, setProdutos] = useState([])
  const [opcoes, setOpcoes] = useState({ tamanhos: [], extras: [], formasPagamento: [] })
  const [catalogoErro, setCatalogoErro] = useState(null)
  const [tentativa, setTentativa] = useState(0)

  // O item que está sendo montado (vai para o carrinho no botão "Adicionar")
  const [produtoId, setProdutoId] = useState(null)
  const [tamanho, setTamanho] = useState('MEDIO')
  const [extras, setExtras] = useState([])
  const [quantidade, setQuantidade] = useState(1)

  const [carrinho, setCarrinho] = useState([])
  const [forma, setForma] = useState('PIX')
  const [parcelas, setParcelas] = useState(1)
  const [observacao, setObservacao] = useState('')
  const [cliente, setCliente] = useState('') // nome dito no balcão (opcional)

  const [enviando, setEnviando] = useState(false)
  const [tempo, setTempo] = useState(null)
  const [erro, setErro] = useState(null)
  const [recebido, setRecebido] = useState(null)
  const [pedidos, setPedidos] = useState([])
  const [offline, setOffline] = useState(false)

  const aguardando = pedidos.filter((p) => p.status === 'AGUARDANDO_PAGAMENTO').length

  // Cardápio, estoque e opções vêm da API: o preço que vale é o dela.
  // Roda de novo quando `aguardando` muda: pedido novo ou concluído (recusado devolve estoque).
  useEffect(() => {
    let ativo = true
    Promise.all([podePedir ? listarProdutos() : Promise.resolve([]), listarOpcoes()])
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
  }, [tentativa, aguardando, podePedir])

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

  const produto = produtos.find((p) => p.id === produtoId)
  const ajusteDe = (codigo) => opcoes.tamanhos.find((t) => t.codigo === codigo)?.ajuste ?? 0
  const unitarioDe = (p, tam, lista) =>
    p.preco + ajusteDe(tam) + opcoes.extras.filter((e) => lista.includes(e.codigo)).reduce((soma, e) => soma + e.preco, 0)

  // Linhas do carrinho já com produto e valor (só para mostrar; o valor final é da API).
  const linhas = carrinho.map((c) => {
    const p = produtos.find((x) => x.id === c.produtoId)
    return { ...c, produto: p, valor: p ? unitarioDe(p, c.tamanho, c.extras) * c.quantidade : 0 }
  })
  const total = linhas.reduce((soma, l) => soma + l.valor, 0)

  // Estoque do produto menos o que já está no carrinho.
  const noCarrinho = carrinho.filter((c) => c.produtoId === produtoId).reduce((soma, c) => soma + c.quantidade, 0)
  const disponivel = produto ? Math.max(0, produto.estoque - noCarrinho) : 0
  const maximo = Math.min(50, disponivel)

  const formaAtual = opcoes.formasPagamento.find((f) => f.codigo === forma)
  const parcelasMax = formaAtual?.parcelasMax ?? 1

  const alternarExtra = (codigo) =>
    setExtras((atuais) => (atuais.includes(codigo) ? atuais.filter((c) => c !== codigo) : [...atuais, codigo]))
  const mudarQuantidade = (n) => setQuantidade(Math.min(Math.max(maximo, 1), Math.max(1, n || 1)))
  const escolherForma = (codigo) => {
    setForma(codigo)
    setParcelas(1)
  }

  function adicionar() {
    if (!produto || disponivel < 1) return
    setCarrinho((atual) => [...atual, { produtoId, tamanho, extras, quantidade: Math.min(quantidade, disponivel) }])
    setQuantidade(1)
    setExtras([])
    setRecebido(null)
  }

  async function enviar(e) {
    e.preventDefault()
    if (carrinho.length === 0) return
    setEnviando(true)
    setErro(null)
    setRecebido(null)
    const inicio = performance.now()
    try {
      const pedido = await criarPedido({
        formaPagamento: forma,
        parcelas: forma === 'CREDITO' ? parcelas : undefined,
        cliente: cliente.trim() || undefined,
        observacao: observacao.trim() || undefined,
        itens: carrinho,
      })
      setRecebido(pedido)
      setCarrinho([])
      setObservacao('')
      setCliente('')
      setPedidos(await listarPedidos(20))
    } catch (falha) {
      if (falha.status === 401) return aoSair()
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
        <div className="topo-direita">
          <span className={offline ? 'conexao fora' : 'conexao'} role="status">
            <i aria-hidden="true" /> {offline ? 'API fora do ar' : 'API conectada'}
          </span>
          <span className="ola">Olá, <strong>{nome}</strong> · {PAPEL[papel]} · {lojaNome ?? 'Rede toda'}</span>
          <button type="button" className="link" onClick={aoSair}>Sair</button>
        </div>
      </header>

      <main>
        {catalogoErro && (
          <div className="faixa-erro" role="alert">
            <strong>Não consegui carregar o cardápio.</strong> {catalogoErro.message}
            <button type="button" onClick={() => setTentativa((n) => n + 1)}>Tentar de novo</button>
          </div>
        )}

        {!podePedir && (
          <section className="cartao">
            <p className="vazio">
              {papel === 'COZINHA'
                ? 'Aqui você acompanha os pedidos da loja. A tela da cozinha chega em breve.'
                : 'Administradores acompanham os pedidos de todas as lojas. Os painéis de gestão chegam em breve.'}
            </p>
          </section>
        )}

        {podePedir && (
        <form className="layout" onSubmit={enviar}>
            <section className="cartao cardapio">
              <h2><span className="passo">1</span> Escolha sua pedida</h2>
              {produtos.length === 0 && !catalogoErro && (
                <div className="grade-produtos" aria-hidden="true">
                  {[1, 2, 3, 4].map((n) => <div key={n} className="esqueleto" />)}
                </div>
              )}
              <div className="grade-produtos" role="radiogroup" aria-label="Cardápio">
                {produtos.map((p) => (
                  <label key={p.id} className={p.estoque < 1 ? 'produto esgotado' : 'produto'}>
                    <input
                      type="radio"
                      name="produto"
                      className="so-leitor"
                      checked={p.id === produtoId}
                      disabled={p.estoque < 1}
                      onChange={() => { setProdutoId(p.id); setQuantidade(1) }}
                    />
                    <span className="emoji" aria-hidden="true">{p.emoji}</span>
                    <strong>{p.nome}</strong>
                    <small>{p.descricao}</small>
                    <span className="preco">{reais(p.preco)}</span>
                    {p.estoque < 1 && <span className="selo">Esgotado</span>}
                    {p.estoque >= 1 && p.estoque <= 5 && <span className="selo aviso">Restam {p.estoque}</span>}
                  </label>
                ))}
              </div>
            </section>

            <section className="cartao">
              <h2><span className="passo">2</span> Do seu jeito</h2>

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
                      max={Math.max(maximo, 1)}
                      value={quantidade}
                      aria-label={`Quantidade (1 a ${Math.max(maximo, 1)})`}
                      onChange={(e) => mudarQuantidade(Number(e.target.value))}
                    />
                    <button type="button" aria-label="Aumentar quantidade" onClick={() => mudarQuantidade(quantidade + 1)} disabled={quantidade >= maximo}>+</button>
                  </div>
                </div>
                {produto && (
                  <button type="button" className="botao secundario" onClick={adicionar} disabled={disponivel < 1}>
                    {disponivel < 1
                      ? 'Sem estoque para adicionar'
                      : `Adicionar ao pedido · ${reais(unitarioDe(produto, tamanho, extras) * quantidade)}`}
                  </button>
                )}
              </div>

              <div className="itens-pedido">
                <h3>
                  Itens do pedido ({carrinho.length})
                  {carrinho.length > 0 && (
                    <button type="button" className="link" onClick={() => setCarrinho([])}>Limpar tudo</button>
                  )}
                </h3>
                {linhas.length === 0 && <p className="vazio">Nenhum item ainda. Escolha acima e clique em “Adicionar ao pedido”.</p>}
                <ul className="carrinho">
                  {linhas.map((l, i) => (
                    <li key={i}>
                      <span className="emoji" aria-hidden="true">{l.produto?.emoji}</span>
                      <div>
                        <strong>{l.quantidade}× {l.produto?.nome}</strong>
                        <small>
                          {opcoes.tamanhos.find((t) => t.codigo === l.tamanho)?.nome}
                          {l.extras.length > 0 && ` · ${opcoes.extras.filter((x) => l.extras.includes(x.codigo)).map((x) => x.nome).join(', ')}`}
                        </small>
                      </div>
                      <span className="valor-item">{reais(l.valor)}</span>
                      <button
                        type="button"
                        className="remover"
                        aria-label={`Remover ${l.quantidade}× ${l.produto?.nome} do pedido`}
                        onClick={() => setCarrinho((atual) => atual.filter((_, j) => j !== i))}
                      >Remover</button>
                    </li>
                  ))}
                </ul>
              </div>
            </section>

            <section className="cartao">
              <h2><span className="passo">3</span> Pagamento</h2>
              <fieldset className="grupo">
                <legend>Forma de pagamento</legend>
                <div className="chips">
                  {opcoes.formasPagamento.map((f) => (
                    <label key={f.codigo} className="chip">
                      <input
                        type="radio"
                        name="forma"
                        className="so-leitor"
                        checked={f.codigo === forma}
                        onChange={() => escolherForma(f.codigo)}
                      />
                      <span>{f.nome}</span>
                    </label>
                  ))}
                </div>
              </fieldset>

              {parcelasMax > 1 && (
                <label className="campo">
                  <span>Parcelas</span>
                  <select value={parcelas} onChange={(e) => setParcelas(Number(e.target.value))}>
                    {Array.from({ length: parcelasMax }, (_, i) => i + 1).map((n) => (
                      <option key={n} value={n}>
                        {n}× de {reais(total / n)}{n === 1 ? ' (à vista)' : ' sem juros'}
                      </option>
                    ))}
                  </select>
                </label>
              )}

              <label className="campo">
                <span>Nome do cliente <small>(opcional)</small></span>
                <input
                  value={cliente}
                  onChange={(e) => setCliente(e.target.value)}
                  maxLength={60}
                  placeholder="Ex.: Carla (como diz no balcão)"
                />
              </label>

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
            </section>

          <aside className="resumo cartao" aria-label="Resumo do pedido">
            <h2>Seu pedido</h2>
            {linhas.length > 0 ? (
              <>
                <dl className="contas">
                  <div><dt>Itens</dt><dd>{linhas.reduce((soma, l) => soma + l.quantidade, 0)}</dd></div>
                  <div className="total" aria-live="polite"><dt>Total</dt><dd>{reais(total)}</dd></div>
                </dl>
                {observacao.trim() && <small className="obs">“{observacao.trim()}”</small>}
              </>
            ) : (
              <p className="vazio">Monte o item e clique em “Adicionar ao pedido”.</p>
            )}

            <button type="submit" className="botao" disabled={enviando || carrinho.length === 0}>
              {enviando ? 'Enviando…' : 'Fazer pedido'}
            </button>
            <p className="nota">O valor final é calculado pela API. Retire no balcão.</p>

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
        )}

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
                  <small>{opcoes.formasPagamento.find((f) => f.codigo === p.formaPagamento)?.nome ?? 'Pagamento'}
                    {p.parcelas > 1 && ` em ${p.parcelas}×`}</small>
                  {papel === 'ADMIN' && <span className="tag">Loja {p.lojaId}</span>}
                  <span className="valor">{reais(p.valor)}</span>
                  <span className={`status ${p.status}`}>{ROTULO[p.status] ?? p.status}</span>
                </div>
                {p.itens.map((i, n) => (
                  <p key={n} className="pedido-item">
                    {i.quantidade}× {i.item}
                    <span className="tag">{opcoes.tamanhos.find((t) => t.codigo === i.tamanho)?.nome ?? i.tamanho}</span>
                    {i.extras.map((x) => (
                      <span key={x} className="tag">{opcoes.extras.find((o) => o.codigo === x)?.nome ?? x}</span>
                    ))}
                  </p>
                ))}
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
