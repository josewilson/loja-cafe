import { useCallback, useState } from 'react'
import { sair, sessaoSalva } from './api'
import Cozinha from './Cozinha'
import Entrar from './Entrar'
import Loja from './Loja'

// Quais telas cada papel enxerga (o servidor confere as permissões de novo em cada chamada).
const ABAS = {
  ADMIN: ['balcao'],
  GERENTE: ['balcao', 'cozinha'],
  ATENDENTE: ['balcao', 'cozinha'],
  COZINHA: ['cozinha'],
}

// Sem sessão: tela de login. Com sessão: a tela do papel. Se a API recusar o token (401), a tela chama aoSair.
export default function App() {
  const [sessao, setSessao] = useState(sessaoSalva)
  const [visao, setVisao] = useState(null)

  const aoSair = useCallback(async () => {
    await sair()
    setSessao(null)
    setVisao(null)
  }, [])

  if (!sessao) return <Entrar aoEntrar={setSessao} />

  const abas = ABAS[sessao.papel] ?? ['balcao']
  const atual = abas.includes(visao) ? visao : abas[0]
  const topo = { sessao, aoSair, abas, visao: atual, aoMudarVisao: setVisao }

  return atual === 'cozinha'
    ? <Cozinha sessao={sessao} aoSair={aoSair} topo={topo} />
    : <Loja sessao={sessao} aoSair={aoSair} topo={topo} />
}
