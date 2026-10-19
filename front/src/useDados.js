import { useCallback, useEffect, useState } from 'react'

// Carrega dados de uma chamada à API e dá a função de recarregar. 401 manda de volta para o login.
export default function useDados(buscar, aoSair) {
  const [dados, setDados] = useState(null)
  const [erro, setErro] = useState(null)
  const [versao, setVersao] = useState(0)
  const recarregar = useCallback(() => setVersao((n) => n + 1), [])
  useEffect(() => {
    let ativo = true
    buscar()
      .then((lista) => {
        if (!ativo) return
        setDados(lista)
        setErro(null)
      })
      .catch((falha) => {
        if (!ativo) return
        if (falha.status === 401) return aoSair()
        setErro(falha)
      })
    return () => {
      ativo = false
    }
  }, [buscar, aoSair, versao])
  return { dados, erro, recarregar, setErro }
}
