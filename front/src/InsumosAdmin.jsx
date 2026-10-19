import { useCallback, useState } from 'react'
import {
  alterarInsumo, criarInsumo, lerFicha, listarCardapio, listarInsumos, listarLojas, listarOpcoes, salvarFicha,
} from './api'
import Erro from './Erro'
import Topo from './Topo'
import useDados from './useDados'

const UNIDADE = { G: 'g', ML: 'ml', UN: 'un' }
const numero = (n) => Number(n).toLocaleString('pt-BR', { maximumFractionDigits: 3 })

// Linhas da ficha de um produto/extra. O estado nasce das linhas carregadas; quem usa troca a `key` para recomeçar.
function LinhasFicha({ inicial, insumos, rotulo, aoSalvar, ocupado, fatores }) {
  const [linhas, setLinhas] = useState(inicial.map((i) => ({ insumoId: i.insumoId, quantidade: String(i.quantidade) })))
  const unidadeDe = (id) => UNIDADE[insumos.find((i) => i.id === Number(id))?.unidade] ?? ''
  const usados = linhas.map((l) => Number(l.insumoId))
  const livres = insumos.filter((i) => i.ativo && !usados.includes(i.id))

  const mudar = (n, campo) => (e) => setLinhas(linhas.map((l, i) => (i === n ? { ...l, [campo]: e.target.value } : l)))
  const adicionar = () => livres.length > 0 && setLinhas([...linhas, { insumoId: livres[0].id, quantidade: '' }])

  return (
    <form
      className="ficha"
      onSubmit={(e) => {
        e.preventDefault()
        aoSalvar(linhas.map((l) => ({ insumoId: Number(l.insumoId), quantidade: Number(l.quantidade) })))
      }}
    >
      <h3>Ficha de {rotulo} <small>(gasto por 1 unidade, tamanho Médio)</small></h3>
      {linhas.length === 0 && <p className="vazio">Sem ficha: este item não gasta insumos.</p>}
      {linhas.map((l, n) => (
        <div key={n} className="linha-ficha">
          <select value={l.insumoId} onChange={mudar(n, 'insumoId')} aria-label="Insumo">
            {insumos.filter((i) => i.id === Number(l.insumoId) || livres.includes(i)).map((i) => <option key={i.id} value={i.id}>{i.nome}</option>)}
          </select>
          <input type="number" required min="0.001" max="100000" step="0.001" value={l.quantidade} onChange={mudar(n, 'quantidade')} aria-label="Quantidade" />
          <span className="unidade">{unidadeDe(l.insumoId)}</span>
          <button type="button" className="link" onClick={() => setLinhas(linhas.filter((_, i) => i !== n))}>Remover</button>
        </div>
      ))}
      <div className="acoes">
        <button type="button" className="botao secundario" onClick={adicionar} disabled={livres.length === 0}>Adicionar insumo</button>
        <button type="submit" className="botao" disabled={ocupado}>Salvar ficha</button>
      </div>
      {fatores && <p className="nota">Pequeno gasta {numero(fatores.PEQUENO)}× e Grande {numero(fatores.GRANDE)}× do valor da ficha. Cada extra escolhido soma a ficha do extra.</p>}
    </form>
  )
}

// Insumos da rede e fichas técnicas (só o ADMIN). A loja escolhida só serve para mostrar o estoque dela.
export default function InsumosAdmin({ aoSair, topo }) {
  const lojas = useDados(listarLojas, aoSair)
  const opcoes = useDados(listarOpcoes, aoSair)
  const cardapio = useDados(listarCardapio, aoSair)
  const [lojaEscolhida, setLojaEscolhida] = useState(null)
  const loja = lojaEscolhida ?? lojas.dados?.[0]?.id
  const buscarCatalogo = useCallback(() => (loja ? listarInsumos(loja) : Promise.resolve([])), [loja])
  const catalogo = useDados(buscarCatalogo, aoSair)

  const [novo, setNovo] = useState({ nome: '', unidade: 'G' })
  const [renomeando, setRenomeando] = useState(null) // { id, nome }
  const [alvo, setAlvo] = useState('produtos:') // "produtos:<id>" ou "extras:<codigo>"
  const [tipo, id] = alvo.split(':')
  const buscarFicha = useCallback(() => (id ? lerFicha(tipo, id) : Promise.resolve({ itens: [] })), [tipo, id])
  const ficha = useDados(buscarFicha, aoSair)
  const [ocupado, setOcupado] = useState(false)
  const [salvo, setSalvo] = useState(false)

  async function agir(acao, aoTerminar) {
    setOcupado(true)
    try {
      await acao()
      catalogo.setErro(null)
      aoTerminar?.()
    } catch (falha) {
      if (falha.status === 401) return aoSair()
      catalogo.setErro(falha)
    } finally {
      setOcupado(false)
    }
  }

  const insumos = catalogo.dados ?? []
  const fatores = opcoes.dados && Object.fromEntries(opcoes.dados.tamanhos.map((t) => [t.codigo, t.fatorInsumo]))
  const rotuloAlvo = !id ? '' : tipo === 'extras' ? (opcoes.dados?.extras.find((x) => x.codigo === id)?.nome ?? id) : (cardapio.dados?.find((p) => String(p.id) === id)?.nome ?? id)

  return (
    <>
      <Topo {...topo} offline={false} />
      <main>
        <section className="cartao">
          <h2 className="titulo-lista">
            Insumos da rede
            <label className="seletor-loja">Estoque da loja:{' '}
              <select value={loja ?? ''} onChange={(e) => setLojaEscolhida(Number(e.target.value))}>
                {(lojas.dados ?? []).map((l) => <option key={l.id} value={l.id}>{l.nome}</option>)}
              </select>
            </label>
          </h2>
          <Erro erro={catalogo.erro} />
          <form
            className="nova-conta"
            onSubmit={(e) => {
              e.preventDefault()
              agir(() => criarInsumo({ nome: novo.nome.trim(), unidade: novo.unidade }), () => { setNovo({ nome: '', unidade: 'G' }); catalogo.recarregar() })
            }}
          >
            <label className="campo"><span>Novo insumo</span>
              <input required maxLength={60} value={novo.nome} onChange={(e) => setNovo({ ...novo, nome: e.target.value })} placeholder="Ex.: Grão de café" /></label>
            <label className="campo"><span>Unidade</span>
              <select value={novo.unidade} onChange={(e) => setNovo({ ...novo, unidade: e.target.value })}>
                <option value="G">gramas (g)</option><option value="ML">mililitros (ml)</option><option value="UN">unidades (un)</option>
              </select></label>
            <button type="submit" className="botao" disabled={ocupado}>Cadastrar</button>
          </form>
          <ul className="equipe">
            {insumos.map((i) => (
              <li key={i.id} className={i.ativo ? '' : 'inativo'}>
                {renomeando?.id === i.id ? (
                  <form
                    className="renomear"
                    onSubmit={(e) => {
                      e.preventDefault()
                      agir(() => alterarInsumo(i.id, { nome: renomeando.nome.trim(), ativo: i.ativo }), () => { setRenomeando(null); catalogo.recarregar() })
                    }}
                  >
                    <input required maxLength={60} value={renomeando.nome} onChange={(e) => setRenomeando({ ...renomeando, nome: e.target.value })} aria-label="Novo nome" />
                    <button type="submit" className="link" disabled={ocupado}>Salvar</button>
                    <button type="button" className="link" onClick={() => setRenomeando(null)}>Cancelar</button>
                  </form>
                ) : (
                  <>
                    <span><strong>{i.nome}</strong> <small>{numero(i.quantidade)} {UNIDADE[i.unidade]} em estoque</small></span>
                    <span className="tag">{UNIDADE[i.unidade]}</span>
                    <button type="button" className="link" onClick={() => setRenomeando({ id: i.id, nome: i.nome })}>Renomear</button>
                    <button type="button" className="link" disabled={ocupado} onClick={() => agir(() => alterarInsumo(i.id, { nome: i.nome, ativo: !i.ativo }), catalogo.recarregar)}>
                      {i.ativo ? 'Desativar' : 'Reativar'}
                    </button>
                  </>
                )}
              </li>
            ))}
          </ul>
          <p className="nota">A unidade não muda depois de criado. Insumo desativado deixa de ser consumido e de alertar.</p>
        </section>

        <section className="cartao">
          <h2>Fichas técnicas</h2>
          <label className="campo">
            <span>Escolha o produto ou o extra</span>
            <select value={alvo} onChange={(e) => { setAlvo(e.target.value); setSalvo(false) }}>
              <option value="produtos:">— escolha —</option>
              <optgroup label="Produtos">
                {(cardapio.dados ?? []).map((p) => <option key={p.id} value={`produtos:${p.id}`}>{p.nome}{p.ativo ? '' : ' (desativado)'}</option>)}
              </optgroup>
              <optgroup label="Extras">
                {(opcoes.dados?.extras ?? []).map((x) => <option key={x.codigo} value={`extras:${x.codigo}`}>{x.nome}</option>)}
              </optgroup>
            </select>
          </label>
          <Erro erro={ficha.erro} />
          {id && ficha.dados && (
            <LinhasFicha
              key={`${alvo}:${JSON.stringify(ficha.dados)}`}
              inicial={ficha.dados.itens}
              insumos={insumos}
              rotulo={rotuloAlvo}
              ocupado={ocupado}
              fatores={fatores}
              aoSalvar={(itens) => agir(async () => { await salvarFicha(tipo, id, itens); setSalvo(true) }, ficha.recarregar)}
            />
          )}
          {salvo && <p className="sucesso" role="status">✓ Ficha salva.</p>}
        </section>
      </main>
    </>
  )
}
