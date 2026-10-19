import { useCallback, useState } from 'react'
import { sair, sessaoSalva } from './api'
import Cozinha from './Cozinha'
import Entrar from './Entrar'
import Gerencia from './Gerencia'
import Loja from './Loja'

// Quais telas cada papel enxerga (o servidor confere as permissões de novo em cada chamada).
const ABAS = {
  ADMIN: ['balcao'],
  GERENTE: ['balcao', 'cozinha', 'gerencia'],
  ATENDENTE: ['balcao', 'cozinha'],
  COZINHA: ['cozinha'],
}

// Sem sessão: tela de login. Com sessão: a tela do papel. Se a API recusar o token (401), a tela chama aoSair.
export default function App() {
  const [sessao, setSessao] = useState(sessaoSalva)
  // A tela fica guardada no endereço (#cozinha, #gerencia): recarregar a página não volta ao balcão.
  const [visao, setVisao] = useState(() => window.location.hash.slice(1) || null)
  const mudarVisao = useCallback((nova) => {
    window.location.hash = nova
    setVisao(nova)
  }, [])

  const aoSair = useCallback(async () => {
    await sair()
    setSessao(null)
    setVisao(null)
    window.location.hash = ''
  }, [])

  if (!sessao) return <Entrar aoEntrar={setSessao} />

  const abas = ABAS[sessao.papel] ?? ['balcao']
  const atual = abas.includes(visao) ? visao : abas[0]
  const topo = { sessao, aoSair, abas, visao: atual, aoMudarVisao: mudarVisao }

  if (atual === 'gerencia') return <Gerencia sessao={sessao} aoSair={aoSair} topo={topo} />
  if (atual === 'cozinha') return <Cozinha sessao={sessao} aoSair={aoSair} topo={topo} />
  return <Loja sessao={sessao} aoSair={aoSair} topo={topo} />
}
