import { useState } from 'react'
import { entrar } from './api'

// Tela de login da equipe. Não há cadastro: as contas são criadas pelo gerente ou pelo administrador.
export default function Entrar({ aoEntrar }) {
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
