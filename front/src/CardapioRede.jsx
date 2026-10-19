import { useState } from 'react'
import { alterarProduto, criarProduto, listarCardapio } from './api'
import Topo from './Topo'
import Erro from './Erro'
import useDados from './useDados'

const reais = (n) => Number(n).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
const VAZIO = { nome: '', descricao: '', emoji: '', preco: '' }

// Formulário de um produto (novo ou em edição). `ativo` só aparece ao editar.
function FormProduto({ inicial, comAtivo, ocupado, aoSalvar, aoCancelar, rotulo }) {
  const [v, setV] = useState({ ...inicial, ativo: inicial.ativo ?? true })
  const mudar = (campo) => (e) => setV({ ...v, [campo]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  return (
    <form
      className="form-produto"
      onSubmit={(e) => {
        e.preventDefault()
        aoSalvar({ nome: v.nome.trim(), descricao: v.descricao.trim() || undefined, emoji: v.emoji.trim(), preco: Number(v.preco), ativo: comAtivo ? v.ativo : undefined })
      }}
    >
      <label className="campo"><span>Nome</span><input required maxLength={60} value={v.nome} onChange={mudar('nome')} /></label>
      <label className="campo largo"><span>Descrição <small>(opcional)</small></span><input maxLength={140} value={v.descricao ?? ''} onChange={mudar('descricao')} /></label>
      <label className="campo"><span>Emoji</span><input required maxLength={16} value={v.emoji} onChange={mudar('emoji')} placeholder="☕" /></label>
      <label className="campo"><span>Preço (R$)</span>
        <input type="number" required min="0.01" max="10000" step="0.01" value={v.preco} onChange={mudar('preco')} /></label>
      {comAtivo && (
        <label className="marcar"><input type="checkbox" checked={v.ativo} onChange={mudar('ativo')} /> No cardápio (ativo)</label>
      )}
      <div className="acoes">
        <button type="submit" className="botao" disabled={ocupado}>{rotulo}</button>
        {aoCancelar && <button type="button" className="link" onClick={aoCancelar}>Cancelar</button>}
      </div>
    </form>
  )
}

// Cardápio da rede: só o ADMIN edita. O preço novo vale só para pedidos novos.
export default function CardapioRede({ aoSair, topo }) {
  const { dados, erro, recarregar, setErro } = useDados(listarCardapio, aoSair)
  const [editando, setEditando] = useState(null) // id do produto aberto para edição
  const [ocupado, setOcupado] = useState(false)
  const [novoKey, setNovoKey] = useState(0) // muda para limpar o formulário "Novo produto"

  async function agir(acao) {
    setOcupado(true)
    try {
      await acao()
      setErro(null)
      setEditando(null)
      recarregar()
    } catch (falha) {
      if (falha.status === 401) return aoSair()
      setErro(falha)
    } finally {
      setOcupado(false)
    }
  }

  return (
    <>
      <Topo {...topo} offline={false} />
      <main>
        <section className="cartao">
          <h2>Novo produto</h2>
          <FormProduto
            key={novoKey}
            inicial={VAZIO}
            comAtivo={false}
            ocupado={ocupado}
            rotulo="Adicionar ao cardápio"
            aoSalvar={(p) => agir(async () => { await criarProduto(p); setNovoKey((n) => n + 1) })}
          />
          <p className="nota">O produto novo nasce com estoque 0 em todas as lojas: cada gerente informa o da sua.</p>
        </section>

        <section className="cartao">
          <h2>Cardápio da rede <small>{dados ? `${dados.length} produto(s)` : ''}</small></h2>
          <Erro erro={erro} />
          <ul className="equipe">
            {(dados ?? []).map((p) => (
              <li key={p.id} className={p.ativo ? 'item-cardapio' : 'item-cardapio inativo'}>
                {editando === p.id ? (
                  <FormProduto
                    inicial={p}
                    comAtivo
                    ocupado={ocupado}
                    rotulo="Salvar"
                    aoCancelar={() => setEditando(null)}
                    aoSalvar={(dadosNovos) => agir(() => alterarProduto(p.id, dadosNovos))}
                  />
                ) : (
                  <>
                    <span className="emoji" aria-hidden="true">{p.emoji}</span>
                    <span><strong>{p.nome}</strong> <small>{p.descricao}</small></span>
                    <span className="valor">{reais(p.preco)}</span>
                    <span className={p.ativo ? 'status PAGO' : 'status CANCELADO'}>{p.ativo ? 'No cardápio' : 'Desativado'}</span>
                    <button type="button" className="link" onClick={() => setEditando(p.id)}>Editar</button>
                  </>
                )}
              </li>
            ))}
          </ul>
        </section>
      </main>
    </>
  )
}
