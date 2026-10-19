import { useState } from 'react'
import { entrar, registrar } from './api'

// Tela de login / criar conta. Quem entra recebe a sessão e a loja aparece.
export default function Entrar({ aoEntrar }) {
  const [criando, setCriando] = useState(false)
  const [nome, setNome] = useState('')
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [erro, setErro] = useState(null)

  async function enviar(e) {
    e.preventDefault()
    setEnviando(true)
    setErro(null)
    try {
      aoEntrar(criando ? await registrar(nome.trim(), email.trim(), senha) : await entrar(email.trim(), senha))
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
            <p>{criando ? 'Crie sua conta' : 'Entre para pedir'}</p>
          </div>
        </div>

        {criando && (
          <label className="campo">
            <span>Seu nome</span>
            <input value={nome} onChange={(e) => setNome(e.target.value)} required maxLength={60} autoComplete="given-name" placeholder="Ex.: Ana" />
          </label>
        )}
        <label className="campo">
          <span>E-mail</span>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required maxLength={120} autoComplete="email" placeholder="voce@exemplo.com" />
        </label>
        <label className="campo">
          <span>Senha {criando && <small>(8 a 72 caracteres)</small>}</span>
          <input
            type="password"
            value={senha}
            onChange={(e) => setSenha(e.target.value)}
            required
            minLength={criando ? 8 : undefined}
            maxLength={72}
            autoComplete={criando ? 'new-password' : 'current-password'}
          />
        </label>

        {erro && (
          <div className="faixa-erro" role="alert">
            <ul>{erro.mensagens.map((m) => <li key={m}>{m}</li>)}</ul>
          </div>
        )}

        <button type="submit" className="botao" disabled={enviando}>
          {enviando ? 'Aguarde…' : criando ? 'Criar conta' : 'Entrar'}
        </button>
        <button type="button" className="link" onClick={() => { setCriando(!criando); setErro(null) }}>
          {criando ? 'Já tenho conta' : 'Criar uma conta'}
        </button>
      </form>
    </main>
  )
}
