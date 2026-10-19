import { useCallback, useState } from 'react'
import { sair, sessaoSalva } from './api'
import Entrar from './Entrar'
import Loja from './Loja'

// Sem sessão: tela de login. Com sessão: a loja. Se a API recusar o token (401), a loja chama aoSair.
export default function App() {
  const [sessao, setSessao] = useState(sessaoSalva)

  const aoSair = useCallback(async () => {
    await sair()
    setSessao(null)
  }, [])

  return sessao ? <Loja sessao={sessao} aoSair={aoSair} /> : <Entrar aoEntrar={setSessao} />
}
