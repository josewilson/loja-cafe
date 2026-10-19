import { useCallback, useEffect, useState } from 'react'
import {
  ajustarEstoque, caixaResumo, criarUsuario, definirAtivo, fecharCaixa, listarFechamentos,
  listarProdutos, listarUsuarios, relatorioVendas,
} from './api'
import Topo from './Topo'

const reais = (n) => Number(n).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
const quando = (iso) => (iso ? new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : 'início')
const FORMA = { PIX: 'Pix', CREDITO: 'Crédito', DEBITO: 'Débito', DINHEIRO: 'Dinheiro' }
const SECOES = [
  { id: 'vendas', titulo: 'Vendas' },
  { id: 'caixa', titulo: 'Caixa' },
  { id: 'equipe', titulo: 'Equipe' },
  { id: 'estoque', titulo: 'Estoque' },
]
// Data de hoje no fuso do navegador, no formato do <input type="date">.
const hoje = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10)

// Carrega dados de uma chamada à API e dá a função de recarregar. 401 manda de volta para o login.
function useDados(buscar, aoSair) {
  const [dados, setDados] = useState(null)
  const [erro, setErro] = useState(null)
  const [versao, setVersao] = useState(0)
  const recarregar = useCallback(() => setVersao((n) => n + 1), [])
  useEffect(() => {
    let ativo = true
    buscar()
      .then((lista) => {
        if (!ativo) return
        setDados(lista)
        setErro(null)
      })
      .catch((falha) => {
        if (!ativo) return
        if (falha.status === 401) return aoSair()
        setErro(falha)
      })
    return () => {
      ativo = false
    }
  }, [buscar, aoSair, versao])
  return { dados, erro, recarregar, setErro }
}

const Erro = ({ erro }) =>
  erro && (
    <div className="faixa-erro" role="alert">
      <strong>{erro.tipo === 'http' ? `Erro ${erro.status}` : 'Sem conexão'}</strong>
      <ul>{erro.mensagens.map((m) => <li key={m}>{m}</li>)}</ul>
    </div>
  )

function Formas({ formas, total }) {
  if (!formas.length) return <p className="vazio">Nenhuma venda ainda.</p>
  return (
    <ul className="formas">
      {formas.map((f) => (
        <li key={f.forma ?? 'sem'}>
          <span>{FORMA[f.forma] ?? 'Não informada'} <small>{f.pedidos} pedido(s)</small></span>
          <span className="barra" aria-hidden="true"><i style={{ width: `${total > 0 ? (f.total / total) * 100 : 0}%` }} /></span>
          <strong>{reais(f.total)}</strong>
        </li>
      ))}
    </ul>
  )
}

function Vendas({ aoSair }) {
  const [data, setData] = useState(hoje)
  const buscar = useCallback(() => relatorioVendas(data), [data])
  const { dados: v, erro } = useDados(buscar, aoSair)
  return (
    <section className="cartao">
      <h2 className="titulo-lista">
        Vendas do dia
        <input type="date" value={data} max={hoje()} onChange={(e) => e.target.value && setData(e.target.value)} aria-label="Dia" />
      </h2>
      <Erro erro={erro} />
      {v && (
        <>
          <div className="kpis">
            <div className="kpi"><small>Total vendido</small><strong>{reais(v.total)}</strong></div>
            <div className="kpi"><small>Pedidos</small><strong>{v.pedidos}</strong></div>
            <div className="kpi"><small>Ticket médio</small><strong>{reais(v.ticketMedio)}</strong></div>
          </div>
          <div className="duas-colunas">
            <div>
              <h3>Por forma de pagamento</h3>
              <Formas formas={v.porForma} total={v.total} />
            </div>
            <div>
              <h3>Mais vendidos</h3>
              {v.maisVendidos.length === 0 && <p className="vazio">Nenhuma venda ainda.</p>}
              <ol className="ranking">
                {v.maisVendidos.map((i) => (
                  <li key={i.item}><span>{i.item}</span><small>{i.quantidade} un.</small><strong>{reais(i.total)}</strong></li>
                ))}
              </ol>
            </div>
          </div>
          <p className="nota">Não entram na soma: {v.recusados} recusado(s), {v.cancelados} cancelado(s), {v.aguardando} aguardando pagamento.</p>
        </>
      )}
    </section>
  )
}

function Caixa({ aoSair }) {
  const resumo = useDados(caixaResumo, aoSair)
  const historico = useDados(listarFechamentos, aoSair)
  const [contado, setContado] = useState('')
  const [observacao, setObservacao] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [feito, setFeito] = useState(null)
  const r = resumo.dados
  const esperado = r?.porForma.find((f) => f.forma === 'DINHEIRO')?.total ?? 0
  const diferenca = contado === '' ? null : Number(contado) - esperado

  async function fechar(e) {
    e.preventDefault()
    if (!window.confirm(`Fechar o caixa? Dinheiro esperado ${reais(esperado)}, contado ${reais(contado)}. Não dá para desfazer.`)) return
    setEnviando(true)
    try {
      setFeito(await fecharCaixa(Number(contado), observacao.trim()))
      setContado('')
      setObservacao('')
      resumo.setErro(null)
      resumo.recarregar()
      historico.recarregar()
    } catch (falha) {
      if (falha.status === 401) return aoSair()
      resumo.setErro(falha)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <>
      <section className="cartao">
        <h2>Turno aberto <small>desde {quando(r?.desde)}</small></h2>
        <Erro erro={resumo.erro} />
        {r && (
          <>
            <div className="kpis">
              <div className="kpi"><small>Vendido no turno</small><strong>{reais(r.total)}</strong></div>
              <div className="kpi"><small>Pedidos</small><strong>{r.pedidos}</strong></div>
              <div className="kpi"><small>Dinheiro esperado na gaveta</small><strong>{reais(esperado)}</strong></div>
            </div>
            <Formas formas={r.porForma} total={r.total} />
            {r.aguardando > 0 && (
              <p className="nota aviso">{r.aguardando} pedido(s) ainda aguardam o pagamento: entram no próximo fechamento, depois de pagos.</p>
            )}
            <form className="fechar" onSubmit={fechar}>
              <label className="campo">
                <span>Dinheiro contado na gaveta</span>
                <input type="number" min="0" step="0.01" required value={contado} onChange={(e) => setContado(e.target.value)} placeholder="0,00" />
              </label>
              <label className="campo largo">
                <span>Observação <small>(opcional)</small></span>
                <input value={observacao} maxLength={140} onChange={(e) => setObservacao(e.target.value)} />
              </label>
              <button type="submit" className="botao" disabled={enviando || contado === ''}>{enviando ? 'Fechando…' : 'Fechar caixa'}</button>
            </form>
            {diferenca !== null && (
              <p className={diferenca === 0 ? 'diferenca ok' : 'diferenca erro'} aria-live="polite">
                {diferenca === 0 ? 'Confere: sem diferença.' : `${diferenca > 0 ? 'Sobra' : 'Falta'} de ${reais(Math.abs(diferenca))} em relação ao esperado.`}
              </p>
            )}
            {feito && <p className="sucesso" role="status">✓ Caixa fechado: {feito.pedidos} pedido(s), {reais(feito.total)}. Diferença: {reais(feito.diferenca)}.</p>}
          </>
        )}
      </section>

      <section className="cartao">
        <h2>Fechamentos anteriores</h2>
        <Erro erro={historico.erro} />
        {historico.dados?.length === 0 && <p className="vazio">Nenhum fechamento ainda.</p>}
        {historico.dados?.length > 0 && (
          <div className="rolagem">
            <table className="tabela">
              <thead><tr><th>Fechado em</th><th>Pedidos</th><th>Total</th><th>Esperado</th><th>Contado</th><th>Diferença</th><th>Obs.</th></tr></thead>
              <tbody>
                {historico.dados.map((f) => (
                  <tr key={f.id}>
                    <td>{quando(f.ate)}</td><td>{f.pedidos}</td><td>{reais(f.total)}</td>
                    <td>{reais(f.dinheiroEsperado)}</td><td>{reais(f.dinheiroContado)}</td>
                    <td className={f.diferenca === 0 ? '' : 'neg'}>{reais(f.diferenca)}</td><td>{f.observacao ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  )
}

function Equipe({ aoSair }) {
  const { dados, erro, recarregar, setErro } = useDados(listarUsuarios, aoSair)
  const [form, setForm] = useState({ nome: '', email: '', senha: '', papel: 'ATENDENTE' })
  const [ocupado, setOcupado] = useState(false)

  async function agir(acao) {
    setOcupado(true)
    try {
      await acao()
      setErro(null)
      recarregar()
    } catch (falha) {
      if (falha.status === 401) return aoSair()
      setErro(falha)
    } finally {
      setOcupado(false)
    }
  }

  const criar = (e) => {
    e.preventDefault()
    agir(async () => {
      await criarUsuario({ ...form, nome: form.nome.trim(), email: form.email.trim() })
      setForm({ nome: '', email: '', senha: '', papel: 'ATENDENTE' })
    })
  }

  return (
    <section className="cartao">
      <h2>Equipe da loja</h2>
      <Erro erro={erro} />
      {dados && (
        <ul className="equipe">
          {dados.map((u) => (
            <li key={u.id} className={u.ativo ? '' : 'inativo'}>
              <span><strong>{u.nome}</strong> <small>{u.email}</small></span>
              <span className="tag">{u.papel}</span>
              {u.papel !== 'GERENTE' && (
                <button type="button" className="link" disabled={ocupado} onClick={() => agir(() => definirAtivo(u.id, !u.ativo))}>
                  {u.ativo ? 'Desativar' : 'Reativar'}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      <h3>Nova conta</h3>
      <form className="nova-conta" onSubmit={criar}>
        <label className="campo"><span>Nome</span>
          <input required maxLength={60} value={form.nome} onChange={(e) => setForm({ ...form, nome: e.target.value })} /></label>
        <label className="campo"><span>E-mail</span>
          <input type="email" required maxLength={120} value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></label>
        <label className="campo"><span>Senha <small>(8 a 72)</small></span>
          <input type="password" required minLength={8} maxLength={72} autoComplete="new-password" value={form.senha} onChange={(e) => setForm({ ...form, senha: e.target.value })} /></label>
        <label className="campo"><span>Papel</span>
          <select value={form.papel} onChange={(e) => setForm({ ...form, papel: e.target.value })}>
            <option value="ATENDENTE">Atendente</option>
            <option value="COZINHA">Cozinha</option>
          </select></label>
        <button type="submit" className="botao" disabled={ocupado}>Criar conta</button>
      </form>
    </section>
  )
}

function Estoque({ sessao, aoSair }) {
  const { dados, erro, recarregar, setErro } = useDados(listarProdutos, aoSair)
  const [novo, setNovo] = useState({}) // produtoId -> texto digitado
  const [salvando, setSalvando] = useState(null)

  async function salvar(produto) {
    setSalvando(produto.id)
    try {
      await ajustarEstoque(sessao.lojaId, produto.id, Number(novo[produto.id]))
      setNovo((atual) => ({ ...atual, [produto.id]: undefined }))
      setErro(null)
      recarregar()
    } catch (falha) {
      if (falha.status === 401) return aoSair()
      setErro(falha)
    } finally {
      setSalvando(null)
    }
  }

  return (
    <section className="cartao">
      <h2>Estoque da loja</h2>
      <Erro erro={erro} />
      <div className="rolagem">
        <table className="tabela">
          <thead><tr><th>Produto</th><th>Em estoque</th><th>Novo valor</th><th /></tr></thead>
          <tbody>
            {(dados ?? []).map((p) => (
              <tr key={p.id}>
                <td>{p.emoji} {p.nome}</td>
                <td className={p.estoque <= 5 ? 'neg' : ''}>{p.estoque}</td>
                <td>
                  <input
                    type="number" min="0" max="100000" step="1" aria-label={`Novo estoque de ${p.nome}`}
                    value={novo[p.id] ?? ''} onChange={(e) => setNovo({ ...novo, [p.id]: e.target.value })}
                  />
                </td>
                <td>
                  <button type="button" className="link" disabled={salvando === p.id || (novo[p.id] ?? '') === ''} onClick={() => salvar(p)}>Salvar</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

// Painel do gerente: vendas do dia, caixa por turno, equipe e estoque da loja dele.
export default function Gerencia({ sessao, aoSair, topo }) {
  const [secao, setSecao] = useState('vendas')
  return (
    <>
      <Topo {...topo} offline={false} />
      <main>
        <nav className="sub-abas" aria-label="Seções da gerência">
          {SECOES.map((s) => (
            <button key={s.id} type="button" className={s.id === secao ? 'aba ativa' : 'aba'} onClick={() => setSecao(s.id)}>{s.titulo}</button>
          ))}
        </nav>
        {secao === 'vendas' && <Vendas aoSair={aoSair} />}
        {secao === 'caixa' && <Caixa aoSair={aoSair} />}
        {secao === 'equipe' && <Equipe aoSair={aoSair} />}
        {secao === 'estoque' && <Estoque sessao={sessao} aoSair={aoSair} />}
      </main>
    </>
  )
}
