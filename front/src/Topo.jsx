const PAPEL = { ADMIN: 'Administrador', GERENTE: 'Gerente', ATENDENTE: 'Atendente', COZINHA: 'Cozinha' }
const ROTULO_ABA = { balcao: 'Balcão', cozinha: 'Fila e retirada', gerencia: 'Gerência', cardapio: 'Cardápio da rede' }

// Cabeçalho comum: marca, abas (conforme o papel), estado da API, quem está logado e o botão de sair.
export default function Topo({ sessao, aoSair, abas, visao, aoMudarVisao, offline }) {
  const { nome, papel, lojaNome } = sessao
  return (
    <header className="topo">
      <div className="marca">
        <span className="logo" aria-hidden="true">☕</span>
        <div>
          <h1>Loja Café</h1>
          <p>Feito na hora. Pago em segundos.</p>
        </div>
      </div>

      {abas.length > 1 && (
        <nav className="abas" aria-label="Telas">
          {abas.map((aba) => (
            <button
              key={aba}
              type="button"
              className={aba === visao ? 'aba ativa' : 'aba'}
              aria-current={aba === visao ? 'page' : undefined}
              onClick={() => aoMudarVisao(aba)}
            >
              {aba === 'balcao' && papel === 'ADMIN' ? 'Pedidos' : ROTULO_ABA[aba]}
            </button>
          ))}
        </nav>
      )}

      <div className="topo-direita">
        <span className={offline ? 'conexao fora' : 'conexao'} role="status">
          <i aria-hidden="true" /> {offline ? 'API fora do ar' : 'API conectada'}
        </span>
        <span className="ola">Olá, <strong>{nome}</strong> · {PAPEL[papel]} · {lojaNome ?? 'Rede toda'}</span>
        <button type="button" className="link" onClick={aoSair}>Sair</button>
      </div>
    </header>
  )
}
