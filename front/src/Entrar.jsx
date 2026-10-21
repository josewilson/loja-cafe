import { useState } from 'react'
import { entrar, redefinirSenha } from './api'

// Tela de login da equipe. Não há cadastro: as contas são criadas pelo gerente ou pelo administrador.
// Link de nova senha (?redefinir=...), gerado pelo gerente: o funcionário só escolhe a senha.
function NovaSenha({ token, aoTerminar }) {
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState(null)

  async function enviar(e) {
    e.preventDefault()
    try {
      await redefinirSenha(token, senha)
      aoTerminar()
    } catch (falha) {
      setErro(falha)
    }
  }

  return (
    <main className="entrar">
      <form className="cartao" onSubmit={enviar}>
        <h1>Nova senha</h1>
        <label className="campo">
          <span>Senha <small>(8 a 72)</small></span>
          <input type="password" value={senha} onChange={(e) => setSenha(e.target.value)} required minLength={8} maxLength={72} autoComplete="new-password" />
        </label>
        {erro && (
          <div className="faixa-erro" role="alert">
            <ul>{erro.mensagens.map((m) => <li key={m}>{m}</li>)}</ul>
          </div>
        )}
        <button type="submit" className="botao">Salvar senha</button>
      </form>
    </main>
  )
}

export default function Entrar({ aoEntrar }) {
  const [token, setToken] = useState(() => new URLSearchParams(window.location.search).get('redefinir'))
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [erro, setErro] = useState(null)

  async function enviar(e) {
    e.preventDefault()
    setEnviando(true)
    setErro(null)
    try {
      aoEntrar(await entrar(email.trim(), senha))
    } catch (falha) {
      setErro(falha)
      setEnviando(false)
    }
  }

  if (token) {
    return <NovaSenha token={token} aoTerminar={() => { window.history.replaceState(null, '', window.location.pathname); setToken(null) }} />
  }

  return (
    <main className="entrar">
      <form className="cartao" onSubmit={enviar}>
        <div className="marca">
          <span className="logo" aria-hidden="true">☕</span>
          <div>
            <h1>Loja Café</h1>
            <p>Acesso da equipe</p>
          </div>
        </div>

        <label className="campo">
          <span>E-mail</span>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required maxLength={120} autoComplete="username" placeholder="voce@loja.com" />
        </label>
        <label className="campo">
          <span>Senha</span>
          <input type="password" value={senha} onChange={(e) => setSenha(e.target.value)} required maxLength={72} autoComplete="current-password" />
        </label>

        {erro && (
          <div className="faixa-erro" role="alert">
            <ul>{erro.mensagens.map((m) => <li key={m}>{m}</li>)}</ul>
          </div>
        )}

        <button type="submit" className="botao" disabled={enviando}>
          {enviando ? 'Aguarde…' : 'Entrar'}
        </button>
        <p className="nota">Sem conta? Peça ao gerente da sua loja.</p>
      </form>
    </main>
  )
}
