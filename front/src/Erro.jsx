export default function Erro({ erro }) {
  if (!erro) return null
  return (
    <div className="faixa-erro" role="alert">
      <strong>{erro.tipo === 'http' ? `Erro ${erro.status}` : 'Sem conexão'}</strong>
      <ul>{erro.mensagens.map((m) => <li key={m}>{m}</li>)}</ul>
    </div>
  )
}
